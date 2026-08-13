package com.vsergeychik.carddemo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.dto.UserUpdateRequest.Cu02Info;
import com.vsergeychik.carddemo.user.UserUpdateController.ProgramState;
import com.vsergeychik.carddemo.user.UserUpdateController.ScreenField;
import com.vsergeychik.carddemo.user.UserUpdateController.Send;
import com.vsergeychik.carddemo.user.dto.UserUpdateRequest;
import com.vsergeychik.carddemo.user.dto.UserUpdateResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * {@link UserUpdateController} - the {@code COUSR02C} / {@code CU02} update-user screen.
 */
@DisplayName("UserUpdateController - the COUSR02C / CU02 update-user screen")
class UserUpdateControllerTest {
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    private static final String EXPECTED_DATE = "07/19/22";

    private static final String EXPECTED_TIME = "23:12:34";

    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    private static final String USER_ID = "USER0001";

    private static final String STORED_PWD = "PWDAAAAA";

    private SecUserRepository repository;

    /**
     * The hold a successful {@code READ ... UPDATE} hands back, and the object the rewrite is issued
     * through.
     *
     * <p>{@code :360-366} carries no {@code RIDFLD}, so the record it replaces is the one the read at
     * {@code :322-331} is holding: the rewrite is therefore an operation on <em>this</em>, not on the
     * repository, and a test that stubs a successful read stubs this alongside it.
     */
    private HeldRecord hold;

    private UserUpdateController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        hold = mock(HeldRecord.class);
        controller = new UserUpdateController(repository, FIXED_CLOCK);
    }

    private static NavigationContext reenter() {
        return NavigationContext.empty().withPgmReenter();
    }

    private static NavigationContext enter() {
        return NavigationContext.empty().withPgmEnter();
    }

    private static UserUpdateRequest screen(String usrIdIn,
                                            String fName,
                                            String lName,
                                            String passwd,
                                            String usrType,
                                            NavigationContext commarea) {
        return new UserUpdateRequest(null, null, null, null, null, null,
                usrIdIn, fName, lName, passwd, usrType, null, commarea, null, null);
    }

    private static UserUpdateRequest populated(NavigationContext commarea) {
        return screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", commarea);
    }

    private static SecUserRecord storedUser() {
        return SecUserRecord.of(USER_ID, "Sam", "Spade", STORED_PWD, "U", CODE_PAGE);
    }

    private static Cu02Info noSelection() {
        return Cu02Info.initial();
    }

    private static UserUpdateRequest withAid(UserUpdateRequest request, String token) {
        return new UserUpdateRequest(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.passwd(), request.usrType(),
                request.errMsg(), request.navigationContext(), token, request.cu02Info());
    }

    private static UserUpdateRequest withExtension(UserUpdateRequest request, Cu02Info info) {
        return new UserUpdateRequest(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.passwd(), request.usrType(),
                request.errMsg(), request.navigationContext(), request.aid(), info);
    }

    private static String errMsgImage(String text) {
        return text + " ".repeat(UserUpdateResponse.ERR_MSG_LENGTH - text.length());
    }

    private static String padded(String text, int length) {
        return text + " ".repeat(length - text.length());
    }

    private static String displayLine(int resp, int reas) {
        return "RESP:" + String.format("%09d", resp) + "REAS:" + String.format("%09d", reas);
    }

    private void stubFoundRead() {
        when(repository.readForUpdate(anyString())).thenReturn(ReadResult.held(storedUser(), hold));
    }

    private ProgramState reentryWith(UserUpdateRequest request, byte aid) {
        return controller.handle(request, UserUpdateController.PASSED_COMMAREA_LENGTH, aid,
                noSelection());
    }

    private void stubSuccessfulRewrite() {
        when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());
    }

    private SecUserRecord rewrittenRecord() {
        ArgumentCaptor<SecUserRecord> captor = ArgumentCaptor.forClass(SecUserRecord.class);
        verify(hold).rewrite(captor.capture());
        return captor.getValue();
    }

    private MockMvc httpOver(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private static Set<String> propertyNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        collectPropertyNames(node, names);
        return names;
    }

    private static void collectPropertyNames(JsonNode node, Set<String> into) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                into.add(entry.getKey());
                collectPropertyNames(entry.getValue(), into);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectPropertyNames(child, into));
        }
    }

    @Nested
    @DisplayName("Construction - two collaborators, both required, no mutable static")
    class Construction {
        @Test
        @DisplayName("a missing repository is refused: COUSR02C reaches USRSEC no other way")
        void repositoryIsRequired() {
            assertThatThrownBy(() -> new UserUpdateController(null, FIXED_CLOCK))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SecUserRepository");
        }

        @Test
        @DisplayName("a missing clock is refused: POPULATE-HEADER-INFO reads FUNCTION CURRENT-DATE")
        void clockIsRequired() {
            assertThatThrownBy(() -> new UserUpdateController(repository, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Clock");
        }

        @Test
        @DisplayName("identity matches app/csd/CARDDEMO.CSD: transaction CU02, program COUSR02C")
        void identityIsPinned() {
            assertThat(UserUpdateController.WS_TRANID).isEqualTo("CU02");
            assertThat(UserUpdateController.WS_PGMNAME).isEqualTo("COUSR02C");
            assertThat(UserUpdateController.LIT_SIGNON_PGM).isEqualTo("COSGN00C");
            assertThat(UserUpdateController.LIT_ADMIN_PGM).isEqualTo("COADM01C");
            assertThat(UserUpdateController.WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(UserUpdateController.WS_RESP_CD_DIGITS).isEqualTo(9);
            assertThat(UserUpdateController.WS_USRSEC_FILE)
                    .isEqualTo(SecUserRepository.CICS_FILE_NAME_IMAGE);
            assertThat(UserUpdateController.WORKING_STORAGE_CHARSET).isEqualTo(CODE_PAGE);
            assertThat(UserUpdateController.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + Cu02Info.LENGTH);
            assertThat(UserUpdateController.NO_COMMAREA_LENGTH).isZero();
        }

        @Test
        @DisplayName("G53: every static field is final, so no request can see another's WORKING-STORAGE")
        void noStaticMutableState() {
            for (Field field : UserUpdateController.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("MAIN-PARA :82-138 - the cold start and the two entry arms")
    class MainPara {
        @Test
        @DisplayName(":90-92 EIBCALEN = 0 transfers to COSGN00C and runs nothing else")
        void coldStartTransfersToSignOn() {
            ProgramState state = controller.handle(populated(reenter()),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);
            assertThat(state.termination()).isEqualTo(UserUpdateController.TERMINATION_XCTL);
            assertThat(state.screenSent()).isFalse();
            assertThat(state.commarea().fromTranid()).isEqualTo("CU02");
            assertThat(state.commarea().fromProgram()).isEqualTo(padded("COUSR02C", 8));
            assertThat(state.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(state.returnTransid()).isEqualTo("CU02");
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":95-105 first entry with no row selected: blank the buffer, ask for the cursor, send")
        void firstEntryWithoutSelectionOnlyPaints() {
            ProgramState state = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(state.commarea().isReenter()).isTrue();
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            assertThat(state.transferred()).isFalse();
            assertThat(state.termination())
                    .isEqualTo(UserUpdateController.TERMINATION_RETURN_TRANSID);
            assertThat(state.usrIdIn()).isEqualTo("\u0000".repeat(8));
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":99-103 first entry WITH a selected row looks that user up before sending")
        void firstEntryWithSelectionLooksTheUserUp() {
            stubFoundRead();
            Cu02Info selected = new Cu02Info(null, null, 3, Cu02Info.NEXT_PAGE_YES, "S", USER_ID);

            ProgramState state = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, selected);

            verify(repository).readForUpdate(USER_ID);
            assertThat(state.usrIdIn()).isEqualTo(USER_ID);
            assertThat(state.fName()).isEqualTo(padded("Sam", 20));
            assertThat(state.lName()).isEqualTo(padded("Spade", 20));
            assertThat(state.passwd()).isEqualTo(STORED_PWD);
            assertThat(state.usrType()).isEqualTo("U");
            assertThat(state.sendCount()).isEqualTo(3);
            assertThat(state.cu02Info().pageNum()).isEqualTo(3);
        }

        @Test
        @DisplayName(":95 is NOT CDEMO-PGM-REENTER, so a context of 9 takes the first-entry arm")
        void aContextThatIsNeitherEnterNorReenterTakesTheFirstEntryArm() {
            NavigationContext neither = NavigationContext.empty().withPgmContext(9);
            assertThat(neither.isEnter()).isFalse();
            assertThat(neither.isReenter()).isFalse();

            ProgramState state = controller.handle(populated(neither),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(state.commarea().isReenter()).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":107 the re-entry arm receives the map, which reports RESP NORMAL")
        void reentryReceivesTheMap() {
            stubFoundRead();
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.wsReasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(state.aidKey()).contains(AidKey.ENTER);
        }

        @Test
        @DisplayName(":127-130 WHEN OTHER flags the error and paints CCDA-MSG-INVALID-KEY")
        void anUnhandledKeyIsAnInvalidKey() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF7);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsErrFlg()).isEqualTo(UserUpdateController.ERR_FLG_ON);
            assertThat(state.wsMessage())
                    .isEqualTo(padded(SystemMessages.CCDA_MSG_INVALID_KEY, 80));
            assertThat(state.errMsg()).isEqualTo(errMsgImage(SystemMessages.CCDA_MSG_INVALID_KEY));
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("an AID PfKeyResolver cannot resolve at all also reaches WHEN OTHER")
        void anUnresolvableAidAlsoReachesWhenOther() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHNULL);

            assertThat(state.aidKey()).isEmpty();
            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(SystemMessages.CCDA_MSG_INVALID_KEY, 80));
        }

        @Test
        @DisplayName(":120-121 PF4 clears every field and repaints, touching no dataset")
        void pf4ClearsTheScreen() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.usrIdIn()).isEqualTo(" ".repeat(8));
            assertThat(state.fName()).isEqualTo(" ".repeat(20));
            assertThat(state.lName()).isEqualTo(" ".repeat(20));
            assertThat(state.passwd()).isEqualTo(" ".repeat(8));
            assertThat(state.usrType()).isEqualTo(" ");
            assertThat(state.wsMessage()).isEqualTo(" ".repeat(80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            assertThat(state.errFlgOn()).isFalse();
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":124-126 PF12 transfers to COADM01C and does NOT save")
        void pf12CancelsWithoutSaving() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF12);

            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(padded("COADM01C", 8));
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":111-119 PF3 SAVES first, then echoes CDEMO-FROM-PROGRAM as the target")
        void pf3SavesThenReturnsToTheCallingProgram() {
            stubFoundRead();
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());
            NavigationContext from = reenter().withFromProgram("COUSR00C");

            ProgramState state = reentryWith(screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", from),
                    CicsAid.DFHPF3);

            verify(hold).rewrite(any(SecUserRecord.class));
            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(padded("COUSR00C", 8));
        }

        @Test
        @DisplayName(":113-114 PF3 with a blank CDEMO-FROM-PROGRAM falls back to COADM01C")
        void pf3WithoutACallerFallsBackToTheAdminMenu() {
            stubFoundRead();
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(
                    screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF3);

            verify(hold).rewrite(any(SecUserRecord.class));
            assertThat(state.nextProgram()).isEqualTo(padded("COADM01C", 8));
        }

        @Test
        @DisplayName(":112 PF3 transfers even when validation failed, because the source has no guard")
        void pf3TransfersEvenWhenValidationFails() {
            ProgramState state = reentryWith(screen("   ", "Sam", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF3);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_ID_EMPTY, 80));
            assertThat(state.transferred()).isTrue();
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":135-138 EXEC CICS RETURN TRANSID(CU02) is recorded on every path")
        void everyPathRecordsTheReturnTransid() {
            stubFoundRead();
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHENTER).returnTransid())
                    .isEqualTo("CU02");
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF12).returnTransid())
                    .isEqualTo("CU02");
        }

        @Test
        @DisplayName("a null request or a null Cu02Info is refused")
        void nullArgumentsAreRefused() {
            assertThatThrownBy(() -> controller.handle(null, 0, CicsAid.DFHENTER, noSelection()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.handle(populated(reenter()), 0, CicsAid.DFHENTER, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY :143-172 - look the user up and paint the record")
    class ProcessEnterKey {
        @Test
        @DisplayName(":146-151 a blank user id is 'User ID can NOT be empty...' with the cursor on USRIDIN")
        void aBlankUserIdIsRefused() {
            ProgramState state = reentryWith(screen("        ", "Sam", "Spade", STORED_PWD, "U",
                    reenter()), CicsAid.DFHENTER);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_ID_EMPTY, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":157-171 a found user paints the four fields and sends twice")
        void aFoundUserIsPaintedOnTheScreen() {
            stubFoundRead();

            ProgramState state = reentryWith(screen(USER_ID, "typed", "over", "TYPEDPWD", "A",
                    reenter()), CicsAid.DFHENTER);

            verify(repository).readForUpdate(USER_ID);
            assertThat(state.fName()).isEqualTo(padded("Sam", 20));
            assertThat(state.lName()).isEqualTo(padded("Spade", 20));
            assertThat(state.passwd()).isEqualTo(STORED_PWD);
            assertThat(state.usrType()).isEqualTo("U");
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.errFlgOn()).isFalse();
            // The lock the read took travels on the state, because the REWRITE at :360 carries no RIDFLD
            // and is issued against it.
            assertThat(state.hold()).contains(hold);
        }

        @Test
        @DisplayName(":340-345 NOTFND is 'User ID NOT found...' and stops before the paint")
        void aMissingUserIsReported() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(state.fName()).isEqualTo(" ".repeat(20));
            assertThat(state.sendCount()).isEqualTo(1);
        }

        @Test
        @DisplayName(":346-352 WHEN OTHER displays RESP/REAS and says 'Unable to lookup User...'")
        void anUnexpectedResponseIsDisplayedAndReported() {
            when(repository.readForUpdate(anyString()))
                    .thenReturn(ReadResult.of(FileStatus.END_OF_FILE, CicsResponse.reported(17, 42)));

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.wsRespCd()).isEqualTo(17);
            assertThat(state.wsReasCd()).isEqualTo(42);
            assertThat(state.displayLines()).containsExactly(displayLine(17, 42));
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_UNABLE_TO_LOOKUP, 80));
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.errFlgOn()).isTrue();
        }

        @Test
        @DisplayName(":334-339 a successful read reports 'Press PF5 key...' in DFHNEUTR")
        void aSuccessfulReadPromptsForPf5() {
            stubFoundRead();

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.sends().get(0).value("ERRMSGO"))
                    .isEqualTo(errMsgImage(UserUpdateController.MSG_PRESS_PF5));
            assertThat(state.sends().get(0).errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);
        }

        @Test
        @DisplayName("a locking read hands back the hold the task took")
        void aHeldReadCarriesItsHold() {
            HeldRecord hold = mock(HeldRecord.class);
            when(repository.readForUpdate(anyString()))
                    .thenReturn(ReadResult.held(storedUser(), hold));

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.hold()).contains(hold);
        }
    }

    @Nested
    @DisplayName("UPDATE-USER-INFO :176-243 - the ordered guard chain and the modification test")
    class UpdateUserInfo {
        @ParameterizedTest(name = "{0} empty -> \"{1}\", cursor on {2}")
        @CsvSource({
            "usrIdIn, User ID can NOT be empty..., USRIDIN",
            "fName,   First Name can NOT be empty..., FNAME",
            "lName,   Last Name can NOT be empty..., LNAME",
            "passwd,  Password can NOT be empty..., PASSWD",
            "usrType, User Type can NOT be empty..., USRTYPE"
        })
        @DisplayName(":180-209 the chain is ordered and reports only the first empty field")
        void theGuardChainIsOrdered(String blankField, String message, ScreenField cursor) {
            String id = "usrIdIn".equals(blankField) ? "  " : USER_ID;
            String first = "fName".equals(blankField) ? "  " : "Sam";
            String last = "lName".equals(blankField) ? "  " : "Spade";
            String pwd = "passwd".equals(blankField) ? "  " : STORED_PWD;
            String type = "usrType".equals(blankField) ? " " : "U";

            ProgramState state = reentryWith(screen(id, first, last, pwd, type, reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage()).isEqualTo(padded(message, 80));
            assertThat(state.cursorRequestedOn(cursor)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("a screen blank in two fields reports only the earlier one: the chain short-circuits")
        void theChainShortCircuits() {
            ProgramState state = reentryWith(screen(USER_ID, "  ", "  ", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_FIRST_NAME_EMPTY, 80));
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.cursorRequestedOn(ScreenField.LNAME)).isFalse();
        }

        @Test
        @DisplayName(":210-211 a fully populated screen puts the cursor on FNAME and proceeds")
        void aValidScreenProceeds() {
            stubFoundRead();
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(
                    screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.errFlgOn()).isFalse();
            verify(hold).rewrite(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "changing {0} sets USR-MODIFIED-YES")
        @ValueSource(strings = {"fName", "lName", "passwd", "usrType"})
        @DisplayName(":219-233 each of the four comparisons independently marks the record modified")
        void everyFieldComparisonMarksTheRecordModified(String changed) {
            stubFoundRead();
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(screen(USER_ID,
                    "fName".equals(changed) ? "Samuel" : "Sam",
                    "lName".equals(changed) ? "Spadey" : "Spade",
                    "passwd".equals(changed) ? "PWDBBBBB" : STORED_PWD,
                    "usrType".equals(changed) ? "A" : "U",
                    reenter()), CicsAid.DFHPF5);

            assertThat(state.usrModifiedYes()).isTrue();
            assertThat(state.wsUsrModified()).isEqualTo(UserUpdateController.USR_MODIFIED_YES);
            verify(hold).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":236-242 an unmodified record is refused with 'Please modify to update ...' in red")
        void anUnmodifiedRecordIsNotRewritten() {
            stubFoundRead();

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF5);

            assertThat(state.usrModifiedYes()).isFalse();
            assertThat(state.wsUsrModified()).isEqualTo(UserUpdateController.USR_MODIFIED_NO);
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_PLEASE_MODIFY, 80));
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(state.errMsgColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":227 the password is compared in PLAINTEXT, exactly as the legacy program does")
        void thePasswordComparisonIsPlaintext() {
            stubFoundRead();
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            reentryWith(screen(USER_ID, "Sam", "Spade", "PWDBBBBB", "U", reenter()), CicsAid.DFHPF5);

            ArgumentCaptor<SecUserRecord> captor = ArgumentCaptor.forClass(SecUserRecord.class);
            verify(hold).rewrite(captor.capture());
            assertThat(captor.getValue().secUsrPwd()).isEqualTo("PWDBBBBB");
        }

        @Test
        @DisplayName(":217-237 a failed read does NOT stop the rewrite, and with nothing held it is "
                + "DFHRESP(INVREQ)")
        void aFailedReadStillReachesTheRewrite() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF5);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.usrModifiedYes()).isTrue();
            assertThat(state.hold()).isEmpty();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_UNABLE_TO_UPDATE, 80));
            assertThat(state.wsRespCd()).isEqualTo(FileStatus.INVREQ);
            assertThat(state.wsReasCd()).isZero();
            assertThat(state.displayLines()).contains(displayLine(FileStatus.INVREQ, 0));
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
        }

        @Test
        @DisplayName("a rewrite with nothing held touches the dataset in no way at all")
        void aRewriteWithNothingHeldIssuesNoWrite() {
            // The race this closes: between the failed read and the write, another task may have added
            // the very key the operator typed. A rewrite addressed by that key would replace a record
            // this task never read and never showed anyone - so no write of any kind may be issued, and
            // the only interaction with the file may be the read that failed.
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            reentryWith(populated(reenter()), CicsAid.DFHPF5);

            verify(repository).readForUpdate(anyString());
            verify(repository, never()).rewrite(any(SecUserRecord.class));
            verify(repository, never()).add(any(SecUserRecord.class));
            verify(repository, never()).deleteHeld(any(HeldRecord.class));
            verify(hold, never()).rewrite(any(SecUserRecord.class));
            verifyNoMoreInteractions(repository, hold);
        }

        @Test
        @DisplayName("a held read rewrites THROUGH the hold, so the row written is the row read")
        void aHeldReadRewritesThroughTheHold() {
            // The positive form of the same property. The hold is the CICS "record this task holds", and
            // routing the rewrite through it is what makes the row written the row the lock was taken on
            // rather than one selected by the key inside SEC-USER-DATA.
            stubFoundRead();
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.hold()).contains(hold);
            verify(hold).rewrite(any(SecUserRecord.class));
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }
    }

    @Nested
    @DisplayName("UPDATE-USER-SEC-FILE :356-390 - the three rewrite outcomes")
    class UpdateUserSecFile {
        @BeforeEach
        void stubLookup() {
            stubFoundRead();
        }

        private ProgramState save() {
            return reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);
        }

        @Test
        @DisplayName(":369-376 a written record confirms 'User USER0001 has been updated ...' in green")
        void aWrittenRecordIsConfirmed() {
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = save();

            assertThat(state.wsMessage())
                    .isEqualTo(padded("User " + USER_ID + " has been updated ...", 80));
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(state.errFlgOn()).isFalse();
        }

        @Test
        @DisplayName(":377-382 NOTFND on the rewrite is 'User ID NOT found...' with the cursor on USRIDIN")
        void aMissingRecordIsReported() {
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.notFound());

            ProgramState state = save();

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
        }

        @Test
        @DisplayName(":383-389 WHEN OTHER displays RESP/REAS and says 'Unable to Update User...'")
        void anUnexpectedResponseIsReported() {
            when(hold.rewrite(any(SecUserRecord.class)))
                    .thenReturn(WriteResult.of(FileStatus.DUPLICATE, CicsResponse.reported(15, 8)));

            ProgramState state = save();

            assertThat(state.wsRespCd()).isEqualTo(15);
            assertThat(state.wsReasCd()).isEqualTo(8);
            assertThat(state.displayLines()).contains(displayLine(15, 8));
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_UNABLE_TO_UPDATE, 80));
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.errFlgOn()).isTrue();
        }

        @Test
        @DisplayName("a rewrite outcome that reports no CICS response defaults WS-RESP-CD to NORMAL")
        void anAbsentCicsResponseDefaultsToNormal() {
            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = save();

            assertThat(state.wsRespCd()).isEqualTo(FileStatus.NORMAL);
        }
    }

    @Nested
    @DisplayName("RETURN-TO-PREV-SCREEN :250-261")
    class ReturnToPrevScreen {
        @Test
        @DisplayName(":252-253 a blank CDEMO-TO-PROGRAM becomes COSGN00C")
        void aBlankTargetBecomesSignOn() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF12);
            assertThat(state.nextProgram()).isEqualTo(padded("COADM01C", 8));

            ProgramState cold = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());
            assertThat(cold.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);
        }

        @Test
        @DisplayName(":255-257 the transfer stamps CU02, COUSR02C and CDEMO-PGM-ENTER")
        void theTransferStampsTheCommarea() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF12);

            assertThat(state.commarea().fromTranid()).isEqualTo("CU02");
            assertThat(state.commarea().fromProgram()).isEqualTo(padded("COUSR02C", 8));
            assertThat(state.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(state.transferred()).isTrue();
        }
    }

    @Nested
    @DisplayName("POPULATE-HEADER-INFO :296-315 - the header every send carries")
    class HeaderInfo {
        @Test
        @DisplayName("the titles, transaction, program, date and time are painted from the fixed clock")
        void theHeaderIsPaintedOnEverySend() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.title01())
                    .isEqualTo(padded(ScreenTitles.CCDA_TITLE01, UserUpdateResponse.TITLE01_LENGTH));
            assertThat(state.title02())
                    .isEqualTo(padded(ScreenTitles.CCDA_TITLE02, UserUpdateResponse.TITLE02_LENGTH));
            assertThat(state.trnName()).isEqualTo("CU02");
            assertThat(state.pgmName()).isEqualTo(padded("COUSR02C", 8));
            assertThat(state.curDate()).isEqualTo(EXPECTED_DATE);
            assertThat(state.curTime()).isEqualTo(EXPECTED_TIME);
        }

        @Test
        @DisplayName(":270 WS-MESSAGE is eighty and ERRMSGO is seventy-eight, so the move truncates")
        void theMessageIsTruncatedIntoTheScreenField() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF7);

            assertThat(state.wsMessage()).hasSize(80);
            assertThat(state.errMsg()).hasSize(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(state.errMsg()).isEqualTo(state.wsMessage().substring(0, 78));
        }
    }

    @Nested
    @DisplayName("resolveAttentionIdentifier - the AID token inverse")
    class AttentionIdentifier {
        @Test
        @DisplayName("an absent token is DFHENTER, because a bare transmit is ENTER")
        void anAbsentTokenIsEnter() {
            assertThat(UserUpdateController.resolveAttentionIdentifier(null))
                    .isEqualTo(CicsAid.DFHENTER);
        }

        @ParameterizedTest(name = "{0} -> the byte this screen branches on")
        @CsvSource({"ENTER", "PFK03", "PFK04", "PFK05", "PFK12"})
        @DisplayName("the five keys the screen names arrive as their own byte, and the folded token does "
                + "not")
        void theFiveNamedKeysRoundTrip(String token) {
            byte expected = switch (token) {
                case "ENTER" -> CicsAid.DFHENTER;
                case "PFK03" -> CicsAid.DFHPF3;
                case "PFK04" -> CicsAid.DFHPF4;
                case "PFK05" -> CicsAid.DFHPF5;
                default -> CicsAid.DFHPF12;
            };
            assertThat(UserUpdateController.resolveAttentionIdentifier(
                    PfKeyResolver.aidImage(expected))).isEqualTo(expected);
            assertThat(UserUpdateController.resolveAttentionIdentifier(AidKey.valueOf(token).token()))
                    .as("the five-character CCARD-AID token is not one byte, so it names no key: "
                            + "CSSTRPFY folds PF15 onto PFK03 and PF17 onto PFK05, and COUSR02C compares "
                            + "EIBAID itself")
                    .isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("a high function key stays itself and reaches WHEN OTHER at :127")
        void highFunctionKeysAreNotFolded() {
            assertThat(UserUpdateController.resolveAttentionIdentifier(
                    PfKeyResolver.aidImage(CicsAid.DFHPF17))).isEqualTo(CicsAid.DFHPF17);
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF17))
                    .as("PF17 must not reach the PF5 arm, which performs UPDATE-USER-INFO")
                    .isFalse();
        }

        @ParameterizedTest(name = "\"{0}\" is not one byte, so it is DFHNULL")
        @ValueSource(strings = {"PFK01", "CLEAR", "PA1  ", "nope", ""})
        @DisplayName("every value that is not one byte is DFHNULL, which reaches WHEN OTHER at :127")
        void everyOtherTokenIsNull(String token) {
            assertThat(UserUpdateController.resolveAttentionIdentifier(token))
                    .isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("a stated raw byte is what is acted on, and PF15 stays distinct from PF3")
        void aStatedByteWinsAndKeepsTheUpperKeysDistinct() {
            assertThat(UserUpdateController.resolveEibAid(
                    Byte.toUnsignedInt(CicsAid.DFHPF15), null)).isEqualTo(CicsAid.DFHPF15);
            assertThat(UserUpdateController.resolveEibAid(
                    Byte.toUnsignedInt(CicsAid.DFHPF3), null)).isEqualTo(CicsAid.DFHPF3);
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF15))
                    .as(":111 tests WHEN DFHPF3, which PF15 is not")
                    .isFalse();
        }

        @Test
        @DisplayName("the stated byte wins over the payload's own image of the same key")
        void theParameterWinsOverThePayload() {
            assertThat(UserUpdateController.resolveEibAid(Byte.toUnsignedInt(CicsAid.DFHPF5),
                    PfKeyResolver.aidImage(CicsAid.DFHPF5)))
                    .as("one key stated twice, consistently")
                    .isEqualTo(CicsAid.DFHPF5);
        }

        @Test
        @DisplayName("no stated byte leaves the payload's one-character image as the only statement")
        void anAbsentByteFallsBackToTheImage() {
            assertThat(UserUpdateController.resolveEibAid(null, null)).isEqualTo(CicsAid.DFHENTER);
            assertThat(UserUpdateController.resolveEibAid(null,
                    PfKeyResolver.aidImage(CicsAid.DFHPF3))).isEqualTo(CicsAid.DFHPF3);
            assertThat(UserUpdateController.resolveEibAid(null, AidKey.PFK03.token()))
                    .as("a five-character token is not one byte, so it is DFHNULL and reaches WHEN OTHER")
                    .isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("a token restating the byte agrees; one naming a different key is refused")
        void theTokenMayRestateTheByteButNotContradictIt() {
            assertThat(UserUpdateController.resolveEibAid(Byte.toUnsignedInt(CicsAid.DFHPF15),
                    AidKey.PFK03.token()))
                    .as("'PFK03' is exactly what CSSTRPFY stores for PF15, so the two agree")
                    .isEqualTo(CicsAid.DFHPF15);

            assertThatThrownBy(() -> UserUpdateController.resolveEibAid(
                    Byte.toUnsignedInt(CicsAid.DFHPF3), AidKey.PFK12.token()))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasMessageContaining("aid");
        }

        @ParameterizedTest(name = "a stated {0} is refused")
        @ValueSource(ints = {-1, 256, 4096})
        @DisplayName("a value that is not one byte is refused rather than narrowed")
        void anImpossibleByteIsRefused(int stated) {
            assertThatThrownBy(() -> UserUpdateController.resolveEibAid(stated, null))
                    .isInstanceOf(ScreenInputRejectedException.class);
        }

        @ParameterizedTest(name = "a raw DFHPF{0} byte reaches WHEN OTHER over the route")
        @ValueSource(ints = {13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24})
        @DisplayName("all twelve upper function keys are invalid keys here, end to end over the route")
        void everyUpperKeyIsInvalidOverTheRoute(int pfNumber) {
            byte upper = functionKeyByte(pfNumber);

            UserUpdateResponse painted = controller.updateUser(USER_ID, populated(reenter()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH,
                    Byte.toUnsignedInt(upper), null).screen();

            assertThat(painted.errMsg().strip())
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("both spellings of the parameter reach the same byte through the route")
        void bothSpellingsAreHonoured() {
            assertThat(controller.updateUser(USER_ID, populated(reenter()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH,
                    Byte.toUnsignedInt(CicsAid.DFHPF15), null).screen().errMsg().strip())
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
            assertThat(controller.updateUser(USER_ID, populated(reenter()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, null,
                    Byte.toUnsignedInt(CicsAid.DFHPF15)).screen().errMsg().strip())
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
        }

        private static byte functionKeyByte(int pfNumber) {
            try {
                return CicsAid.class.getDeclaredField("DFHPF" + pfNumber).getByte(null);
            } catch (ReflectiveOperationException absent) {
                throw new AssertionError("CicsAid does not declare DFHPF" + pfNumber, absent);
            }
        }
    }

    @Nested
    @DisplayName("The COBOL figurative-constant rules, stated once each")
    class FigurativeConstants {
        @ParameterizedTest(name = "\"{0}\" is SPACES or LOW-VALUES")
        @ValueSource(strings = {"        ", " ", "\u0000\u0000", "\u0000"})
        @DisplayName("a field of all spaces or all LOW-VALUES tests true")
        void spacesAndLowValuesTestTrue(String image) {
            assertThat(UserUpdateController.isSpacesOrLowValues(image)).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\" is neither")
        @ValueSource(strings = {"A", " A ", "\u0000A", "A\u0000"})
        @DisplayName("a field with any other byte tests false, whichever end it sits at")
        void anythingElseTestsFalse(String image) {
            assertThat(UserUpdateController.isSpacesOrLowValues(image)).isFalse();
        }

        @Test
        @DisplayName("an empty image is vacuously both, and an absent one is refused")
        void theDegenerateCases() {
            assertThat(UserUpdateController.isSpacesOrLowValues("")).isTrue();
            assertThatThrownBy(() -> UserUpdateController.isSpacesOrLowValues(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an absent received value is LOW-VALUES; a present one is a PIC X move")
        void receivedImageRendersAbsenceAsLowValues() {
            assertThat(UserUpdateController.receivedImage(null, 4)).isEqualTo("\u0000".repeat(4));
            assertThat(UserUpdateController.receivedImage("AB", 4)).isEqualTo("AB  ");
            assertThat(UserUpdateController.receivedImage("ABCDE", 4)).isEqualTo("ABCD");
        }

        @Test
        @DisplayName("spaces(n) is exactly n spaces")
        void spacesIsExact() {
            assertThat(UserUpdateController.spaces(3)).isEqualTo("   ");
            assertThat(UserUpdateController.spaces(0)).isEmpty();
        }

        @Test
        @DisplayName("STRING ... DELIMITED BY SPACE stops at the first space, or takes the whole field")
        void delimitedBySpaceStopsAtTheFirstSpace() {
            assertThat(UserUpdateController.delimitedBySpace("USER0001")).isEqualTo("USER0001");
            assertThat(UserUpdateController.delimitedBySpace("AB  CD")).isEqualTo("AB");
            assertThat(UserUpdateController.delimitedBySpace(" X")).isEmpty();
            assertThatThrownBy(() -> UserUpdateController.delimitedBySpace(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName(":372-375 the confirmation trims the key at its first space and fits eighty")
        void theConfirmationIsComposedFromTheTrimmedKey() {
            assertThat(UserUpdateController.updatedConfirmation("USER0001"))
                    .isEqualTo(padded("User USER0001 has been updated ...", 80));
            assertThat(UserUpdateController.updatedConfirmation("AB      "))
                    .isEqualTo(padded("User AB has been updated ...", 80));
        }

        @Test
        @DisplayName(":347 the DISPLAY line zero-fills to nine digits and keeps a sign")
        void theDisplayLineIsNineDigitsWide() {
            assertThat(UserUpdateController.displayLine(0, 0))
                    .isEqualTo("RESP:000000000REAS:000000000");
            assertThat(UserUpdateController.displayLine(13, 8))
                    .isEqualTo("RESP:000000013REAS:000000008");
            assertThat(UserUpdateController.displayLine(-2, 123456789))
                    .as("a negative that is not the sentinel still renders as a signed number")
                    .isEqualTo("RESP:-000000002REAS:123456789");
        }

        @Test
        @DisplayName("an unreported response renders as nine asterisks, never as nine zeros")
        void anUnreportedResponseIsNotRenderedAsANumber() {
            assertThat(UserUpdateController.displayLine(FileStatus.RESP_NOT_REPORTED, 0))
                    .isEqualTo("RESP:*********REAS:000000000")
                    .doesNotContain("RESP:000000000")
                    .doesNotContain("-000000001");

            assertThat(UserUpdateController.displayLine(FileStatus.RESP_NOT_REPORTED, 0))
                    .hasSameSizeAs(UserUpdateController.displayLine(FileStatus.NORMAL, 0));

            assertThat(UserUpdateController.displayLine(0, FileStatus.RESP_NOT_REPORTED))
                    .isEqualTo("RESP:000000000REAS:*********");
        }
    }

    @Nested
    @DisplayName("Cu02Info :50-58 - this program's own 34-byte commarea extension")
    class Cu02InfoTests {
        @Test
        @DisplayName("the declared widths sum to thirty-four")
        void theWidthsSumToThirtyFour() {
            assertThat(Cu02Info.LENGTH).isEqualTo(34);
            assertThat(Cu02Info.USRID_FIRST_LENGTH).isEqualTo(8);
            assertThat(Cu02Info.USRID_LAST_LENGTH).isEqualTo(8);
            assertThat(Cu02Info.PAGE_NUM_DIGITS).isEqualTo(8);
            assertThat(Cu02Info.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(Cu02Info.USR_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(Cu02Info.USR_SELECTED_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("initial() is the area a cold start sees: spaces, page zero and NEXT-PAGE-FLG 'N'")
        void initialIsTheInitialisedArea() {
            Cu02Info initial = Cu02Info.initial();

            assertThat(initial.usridFirst()).isEqualTo(" ".repeat(8));
            assertThat(initial.usridLast()).isEqualTo(" ".repeat(8));
            assertThat(initial.pageNum()).isZero();
            assertThat(initial.nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_NO);
            assertThat(initial.usrSelFlg()).isEqualTo(" ");
            assertThat(initial.usrSelected()).isEqualTo(" ".repeat(8));
        }

        @Test
        @DisplayName("an absent alphanumeric becomes spaces at its declared width, never null")
        void absentValuesBecomeSpaces() {
            Cu02Info info = new Cu02Info(null, null, 0, null, null, null);

            assertThat(info.usridFirst()).isEqualTo(" ".repeat(8));
            assertThat(info.usridLast()).isEqualTo(" ".repeat(8));
            assertThat(info.nextPageFlg()).isEqualTo(" ");
            assertThat(info.usrSelFlg()).isEqualTo(" ");
            assertThat(info.usrSelected()).isEqualTo(" ".repeat(8));
        }

        @Test
        @DisplayName("a longer value is truncated on the right, as an alphanumeric MOVE truncates")
        void longerValuesAreTruncatedOnTheRight() {
            Cu02Info info = new Cu02Info("USER00019", "USER00029", 1, "YES", "SEL", "USER00039");

            assertThat(info.usridFirst()).isEqualTo("USER0001");
            assertThat(info.usridLast()).isEqualTo("USER0002");
            assertThat(info.nextPageFlg()).isEqualTo("Y");
            assertThat(info.usrSelFlg()).isEqualTo("S");
            assertThat(info.usrSelected()).isEqualTo("USER0003");
        }

        @Test
        @DisplayName("PIC 9(08) is unsigned, so a negative page number has no representation")
        void aNegativePageNumberIsRefused() {
            assertThatThrownBy(() -> new Cu02Info(null, null, -1, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsigned");
        }

        @Test
        @DisplayName("PIC 9(08) holds eight digits, so a ninth would be silently dropped and is refused")
        void anOverwidePageNumberIsRefused() {
            assertThatThrownBy(() -> new Cu02Info(null, null, 100_000_000, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("high-order");
            assertThat(new Cu02Info(null, null, 99_999_999, null, null, null).pageNum())
                    .isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("fieldImages() names every item as the copybook spells it, page number zero-filled")
        void fieldImagesAreNamedAndZeroFilled() {
            Map<String, String> images =
                    new Cu02Info("A", "B", 7, "Y", "S", "C").fieldImages();

            assertThat(images).containsEntry("CDEMO-CU02-USRID-FIRST", padded("A", 8))
                    .containsEntry("CDEMO-CU02-USRID-LAST", padded("B", 8))
                    .containsEntry("CDEMO-CU02-PAGE-NUM", "00000007")
                    .containsEntry("CDEMO-CU02-NEXT-PAGE-FLG", "Y")
                    .containsEntry("CDEMO-CU02-USR-SEL-FLG", "S")
                    .containsEntry("CDEMO-CU02-USR-SELECTED", padded("C", 8));
            assertThatThrownBy(() -> images.put("X", "Y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("ScreenField, Send and the ProgramState projection")
    class Projections {
        @Test
        @DisplayName("each ScreenField names its xxxL item as app/cpy-bms/COUSR02.CPY spells it")
        void screenFieldsNameTheirLengthItems() {
            assertThat(ScreenField.USRIDIN.cobolName()).isEqualTo("USRIDINL");
            assertThat(ScreenField.FNAME.cobolName()).isEqualTo("FNAMEL");
            assertThat(ScreenField.LNAME.cobolName()).isEqualTo("LNAMEL");
            assertThat(ScreenField.PASSWD.cobolName()).isEqualTo("PASSWDL");
            assertThat(ScreenField.USRTYPE.cobolName()).isEqualTo("USRTYPEL");
            assertThat(ScreenField.values()).hasSize(5);
        }

        @Test
        @DisplayName("a send carries an unmodifiable copy of what it painted plus the colour attribute")
        void aSendIsAnImmutableSnapshot() {
            Send send = new Send(Map.of("ERRMSGO", "x"), BmsAttributes.DFHRED);

            assertThat(send.value("ERRMSGO")).isEqualTo("x");
            assertThat(send.value("ABSENT")).isNull();
            assertThat(send.attributes())
                    .containsEntry(UserUpdateController.ERR_MSG_COLOUR_ITEM,
                            BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            assertThat(send.errMsgColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            assertThatThrownBy(() -> send.fields().put("K", "V"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> new Send(null, BmsAttributes.DFHRED))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("no cursor is requested until a paragraph asks for one")
        void theCursorIsAbsentUntilRequested() {
            ProgramState cold = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(cold.cursorField()).isEmpty();
            assertThat(cold.cursorFieldName()).isNull();
            assertThat(cold.cursorRequestedOn(ScreenField.USRIDIN)).isFalse();
        }

        @Test
        @DisplayName("a requested cursor is reported by name")
        void aRequestedCursorIsReportedByName() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.cursorField()).contains(ScreenField.USRIDIN);
            assertThat(state.cursorFieldName()).isEqualTo("USRIDINL");
        }

        @Test
        @DisplayName("response() projects the twelve map items, the navigation triple and the commarea")
        void theResponseIsTheWholeScreen() {
            stubFoundRead();
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);
            UserUpdateResponse response = state.response();

            assertThat(response.trnName()).isEqualTo("CU02");
            assertThat(response.pgmName()).isEqualTo(padded("COUSR02C", 8));
            assertThat(response.curDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.curTime()).isEqualTo(EXPECTED_TIME);
            assertThat(response.usrIdIn()).isEqualTo(USER_ID);
            assertThat(response.fName()).isEqualTo(padded("Sam", 20));
            assertThat(response.lName()).isEqualTo(padded("Spade", 20));
            assertThat(response.passwd()).isEqualTo(STORED_PWD);
            assertThat(response.usrType()).isEqualTo("U");
            assertThat(response.errMsg()).hasSize(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(response.navigationContext()).isEqualTo(state.commarea());
            assertThat(response.nextProgram()).isEqualTo(UserUpdateController.WS_PGMNAME);
            assertThat(response.nextMapset()).isEqualTo(UserUpdateResponse.MAPSET_NAME);
            assertThat(response.nextMap()).isEqualTo(UserUpdateResponse.MAP_NAME);
            assertThat(response.navigationContext().lastMapset())
                    .isEqualTo(state.commarea().lastMapset());
            assertThat(response.navigationContext().lastMap()).isEqualTo(state.commarea().lastMap());
        }

        @Test
        @DisplayName("the record area starts blank, and the key is set before the read")
        void theRecordAreaStartsBlank() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.secUserData().secUsrId()).isEqualTo(USER_ID);
            assertThat(state.secUserData().secUsrFname()).isEqualTo(" ".repeat(20));
            assertThat(state.eibAid()).isEqualTo(CicsAid.DFHENTER);
        }

        @Test
        @DisplayName("the send list and the display list are unmodifiable views")
        void theRecordedListsAreUnmodifiable() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF7);

            assertThatThrownBy(() -> state.sends().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> state.displayLines().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toString redacts a populated password and leaves a blank one legible")
        void toStringRedactsThePassword() {
            stubFoundRead();
            ProgramState populatedState = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(populatedState.toString())
                    .contains(NavigationContext.REDACTED)
                    .doesNotContain(STORED_PWD);

            ProgramState clearedState = reentryWith(populated(reenter()), CicsAid.DFHPF4);
            assertThat(clearedState.toString()).contains("passwd=        ");
        }

        @Test
        @DisplayName("the default colour before any paragraph sets one is DFHDFCOL")
        void theDefaultColourIsTheMapDefault() {
            ProgramState cold = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(cold.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("a payload with no communication area is the initialised area, never null")
        void anAbsentCommareaIsTheInitialisedArea() {
            ProgramState state = controller.handle(
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(state.commarea()).isNotNull();
            assertThat(state.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
        }
    }

    @Nested
    @DisplayName("PUT /api/users/{userId} - the adapter's own rules")
    class HttpAdapter {
        private UserUpdateResponse screenOf(ScreenResponse<UserUpdateResponse> answer) {
            assertThat(answer).isNotNull();
            assertThat(answer.screen()).isNotNull();
            return answer.screen();
        }

        @Test
        @DisplayName("on a first entry the path variable is the identity and lands in USRIDIN")
        void thePathVariableIsTheIdentity() {
            stubFoundRead();

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    screen(" ".repeat(8), "Sam", "Spade", STORED_PWD, "U", enter()), null, null, null));

            verify(repository).readForUpdate(USER_ID);
            assertThat(response.usrIdIn()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("a re-entry USRIDIN naming a different user is REFUSED before the locking read, "
                + "because :162 and :216 make that field the RIDFLD of the read and of the rewrite")
        void aDisagreeingIdentityIsRefused() {
            // COUSR02C has no URI. :90-110 restores the commarea and its first-entry arm copies
            // CDEMO-CU02-USR-SELECTED over USRIDINI; on a re-entry PROCESS-ENTER-KEY reads USRIDINI
            // itself. Honouring a body that names another user therefore let PUT /api/users/A read,
            // paint and rewrite user B - and painting B is what discloses B's stored record. Refused
            // before anything is read or locked, naming the member and echoing neither identity.
            ScreenInputRejectedException refusal = catchThrowableOfType(
                    ScreenInputRejectedException.class,
                    () -> controller.updateUser(USER_ID,
                            screen("IGNORED1", "Sam", "Spade", STORED_PWD, "U", reenter()),
                            null, null, null));

            assertThat(refusal).isNotNull();
            assertThat(refusal.member()).contains(UserUpdateController.USRIDIN_MEMBER);
            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.CONFLICTING_KEY);
            assertThat(refusal.getMessage()).doesNotContain("IGNORED1");
            assertThat(refusal.publicDetail()).doesNotContain("IGNORED1");
            verify(repository, never()).readForUpdate(anyString());
        }

        @ParameterizedTest(name = "a re-entry stating USRIDIN as \"{0}\" agrees with the URI")
        @ValueSource(strings = {"        ", "USER0001", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName("the images that AGREE with the URI are accepted - blank, LOW-VALUES and the URI's "
                + "own key - and the URI's key is what USRIDIN then carries on every turn")
        void theStatesThatAgreeAreAccepted(String stated) {
            stubFoundRead();

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    screen(stated, "Sam", "Spade", STORED_PWD, "U", reenter()), null, null, null));

            assertThat(response.usrIdIn()).isEqualTo(USER_ID);
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("the path variable lands in CDEMO-CU02-USR-SELECTED too, which first entry reads")
        void thePathVariableAlsoFillsTheSelectedId() {
            stubFoundRead();
            Cu02Info selectsAnotherUser =
                    new Cu02Info(null, null, 0, Cu02Info.NEXT_PAGE_NO, "S", "USER0002");

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    withExtension(populated(enter()), selectsAnotherUser), null, null, null));

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
            assertThat(response.usrIdIn()).isEqualTo(USER_ID);
            assertThat(response.cu02Info().usrSelected()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("the extension's other five items are carried untouched - only the key is the URI's")
        void theExtensionsOtherItemsAreCarriedUntouched() {
            stubFoundRead();
            Cu02Info arrived = new Cu02Info("USER0005", "USER0009", 3, Cu02Info.NEXT_PAGE_YES, "S",
                    "USER0002");

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    withExtension(populated(enter()), arrived), null, null, null));

            assertThat(response.cu02Info().usridFirst()).isEqualTo(arrived.usridFirst());
            assertThat(response.cu02Info().usridLast()).isEqualTo(arrived.usridLast());
            assertThat(response.cu02Info().pageNum()).isEqualTo(3);
            assertThat(response.cu02Info().nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_YES);
            assertThat(response.cu02Info().usrSelFlg()).isEqualTo("S");
            assertThat(response.cu02Info().usrSelected()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("over HTTP, an extension naming another user discloses neither its password nor it")
        void theUriIsTheOnlyIdentityOverHttp() throws Exception {
            stubFoundRead();
            ObjectMapper mapper = new ObjectMapper();
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();
            UserUpdateRequest arriving = withExtension(populated(enter()),
                    new Cu02Info(null, null, 0, Cu02Info.NEXT_PAGE_NO, "S", "USER0002"));

            mockMvc.perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(arriving)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usridin").value(USER_ID))
                    .andExpect(jsonPath("$.cu02Info.usrSelected").value(USER_ID));

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
        }

        @Test
        @DisplayName("a path identity wider than PIC X(08) is REFUSED, never truncated onto another user")
        void anOverWidePathIdentityIsRefused() {
            assertThatThrownBy(() -> controller.updateUser("USER00019",
                    screen("IGNORED1", "Sam", "Spade", STORED_PWD, "U", reenter()), null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("USRIDIN");

            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("an absent EIBCALEN is derived from whether the payload carried a commarea")
        void eibcalenIsDerivedFromTheCarrier() {
            UserUpdateResponse cold = screenOf(controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null), null, null, null));
            assertThat(cold.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);

            stubFoundRead();
            UserUpdateResponse warm = screenOf(controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", reenter()), null, null, null));
            assertThat(warm.fName()).isEqualTo(padded("Sam", 20));
        }

        @Test
        @DisplayName("a stated EIBCALEN that agrees with the carrier is taken")
        void aStatedEibcalenThatAgreesIsTaken() {
            UserUpdateResponse cold = screenOf(controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null),
                    UserUpdateController.NO_COMMAREA_LENGTH, null, null));
            assertThat(cold.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);

            stubFoundRead();
            UserUpdateResponse warm = screenOf(controller.updateUser(USER_ID,
                    populated(reenter()), UserUpdateController.PASSED_COMMAREA_LENGTH, null, null));
            assertThat(warm.fName()).isEqualTo(padded("Sam", 20));
        }

        @Test
        @DisplayName("an EIBCALEN that contradicts the carrier is refused, in either direction")
        void aContradictingEibcalenIsRefused() {
            assertThatThrownBy(() -> controller.updateUser(USER_ID, populated(reenter()),
                    UserUpdateController.NO_COMMAREA_LENGTH, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a communication area");

            assertThatThrownBy(() -> controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no communication area");

            verify(repository, never()).readForUpdate(anyString());
        }

        @ParameterizedTest(name = "eibcalen = {0} is preserved, because line 90 tests only for zero")
        @ValueSource(ints = {1, 159, 160, 193, 195, 2000})
        @DisplayName("Every non-zero length is carried through: 160 is what COADM01C's XCTL passes and "
                + "194 what COUSR00C's and this program's own RETURN pass, and all of them are real")
        void anImpossibleEibcalenIsRefused(int stated) {
            assertThat(UserUpdateController.resolveEibcalen(stated, populated(reenter())))
                    .isEqualTo(stated);
        }

        @Test
        @DisplayName("A negative EIBCALEN is refused: it is not a length at all")
        void aNegativeEibcalenIsRefused() {
            assertThatThrownBy(() -> controller.updateUser(USER_ID, populated(reenter()), -1, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(UserUpdateController.EIBCALEN_PARAM)
                    .hasMessageContaining("cannot be negative");
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("the 34-byte extension arrives in the body and comes back out on the response")
        void theExtensionTravelsInThePayload() {
            stubFoundRead();
            Cu02Info sent = new Cu02Info("USER0001", "USER0050", 2, Cu02Info.NEXT_PAGE_YES, "S",
                    USER_ID);

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    withExtension(populated(reenter()), sent),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, null, null));

            assertThat(response.cu02Info()).isEqualTo(sent);
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("a payload naming no extension is given its VALUE clauses, keyed by the URI")
        void anAbsentExtensionIsInitialised() {
            stubFoundRead();

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    populated(reenter()), null, null, null));

            // Every item at its VALUE-clause state except the selected id, which is bound from the URI
            // on every turn: :99-102 reads that carrier on a first entry and :162 and :216 key the
            // locking read and the rewrite on the identity it seeds, so it cannot be left to disagree
            // with the resource the call names.
            assertThat(response.cu02Info()).isEqualTo(new Cu02Info(Cu02Info.initial().usridFirst(),
                    Cu02Info.initial().usridLast(),
                    Cu02Info.initial().pageNum(),
                    Cu02Info.initial().nextPageFlg(),
                    Cu02Info.initial().usrSelFlg(),
                    USER_ID));
        }

        @Test
        @DisplayName("the reply publishes this screen's own identity, not the caller's leftovers")
        void theNavigationTripleNamesThisScreen() {
            stubFoundRead();

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    populated(reenter()), null, null, null));

            assertThat(response.nextProgram()).isEqualTo(UserUpdateController.WS_PGMNAME);
            assertThat(response.nextMapset()).isEqualTo(UserUpdateResponse.MAPSET_NAME);
            assertThat(response.nextMap()).isEqualTo(UserUpdateResponse.MAP_NAME);
            assertThat(response.navigationContext())
                    .isEqualTo(populated(reenter()).navigationContext());
        }

        @Test
        @DisplayName("on a transfer the reply names the target program and leaves both maps blank")
        void aTransferNamesTheTargetAndNoMap() {
            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null), null, null, null));

            assertThat(response.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);
            assertThat(response.nextMapset()).isBlank();
            assertThat(response.nextMap()).isBlank();
        }

        @Test
        @DisplayName("the presentation metadata travels in the envelope, beside the screen")
        void theMetadataTravelsInTheEnvelope() {
            stubFoundRead();

            ScreenResponse<UserUpdateResponse> answer =
                    controller.updateUser(USER_ID, populated(reenter()), null, null, null);

            assertThat(answer.screenMetadata()).isNotNull();
            assertThat(answer.screenMetadata().fields())
                    .as("COUSR02 declares no attribute quads this program writes")
                    .isEmpty();
            assertThat(answer.screenMetadata().messageColour()).isNotNull();
            assertThat(answer.screenMetadata().resetAllOutputFields()).isFalse();
        }

        @Test
        @DisplayName("a null path variable or a null payload is refused before anything runs")
        void nullArgumentsAreRefused() {
            assertThatThrownBy(() -> controller.updateUser(null, populated(reenter()), null, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("user id");
            assertThatThrownBy(() -> controller.updateUser(USER_ID, null, null, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request");
            verify(repository, never()).readForUpdate(anyString());
        }
    }

    @Test
    @DisplayName("a read that reports no hold leaves the state holding nothing")
    void aReadWithoutAHoldLeavesNoHold() {
        // The NOTFND arm at :340-345. CICS fills INTO only on success and takes no hold when the read
        // failed, so the state holds nothing - which is what makes the REWRITE at :360 the INVREQ
        // condition rather than a write.
        when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

        ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

        assertThat(state.hold()).isEqualTo(Optional.empty());
    }

    @Nested
    @DisplayName("UserUpdateRequest - the 88-level readings and the masked toString")
    class PayloadReadings {
        @Test
        @DisplayName("contextIsEnter and contextIsReenter read CDEMO-PGM-CONTEXT, both arms")
        void theContextReadingsReadTheEightyEightLevels() {
            assertThat(populated(enter()).contextIsEnter()).isTrue();
            assertThat(populated(enter()).contextIsReenter()).isFalse();
            assertThat(populated(reenter()).contextIsEnter()).isFalse();
            assertThat(populated(reenter()).contextIsReenter()).isTrue();
        }

        @Test
        @DisplayName("with no communication area both readings are false, which :90 has already handled")
        void withNoCommunicationAreaBothAreFalse() {
            UserUpdateRequest none = populated(null);

            assertThat(none.contextIsEnter()).isFalse();
            assertThat(none.contextIsReenter()).isFalse();
        }

        @Test
        @DisplayName("toString masks the password, and discloses only whether one was transmitted")
        void toStringMasksThePassword() {
            String withPassword = populated(reenter()).toString();

            assertThat(withPassword).doesNotContain(STORED_PWD).contains("passwd=");
            assertThat(withPassword).contains("cu02Info=", "usrIdIn=" + USER_ID);

            String withoutPassword =
                    screen(USER_ID, "Sam", "Spade", null, "U", reenter()).toString();
            assertThat(withoutPassword).contains("passwd=null");
        }
    }

    @Nested
    @DisplayName("The screen contract - twelve DFHMDF fields, their widths, and what is NOT one")
    class ScreenContract {
        @Test
        @DisplayName("G9 - twelve xxxI items in map order, with USRIDINI seventh, before the names")
        void theTwelveInputItemsAreInMapOrder() {
            assertThat(UserUpdateRequest.MAP_FIELD_COUNT).isEqualTo(12);
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES).containsExactly("TRNNAMEI",
                    "TITLE01I",
                    "CURDATEI",
                    "PGMNAMEI",
                    "TITLE02I",
                    "CURTIMEI",
                    "USRIDINI",
                    "FNAMEI",
                    "LNAMEI",
                    "PASSWDI",
                    "USRTYPEI",
                    "ERRMSGI");

            assertThat(UserUpdateRequest.MAP_FIELD_NAMES.indexOf(UserUpdateRequest.USRIDIN_FIELD))
                    .isEqualTo(6)
                    .isLessThan(UserUpdateRequest.MAP_FIELD_NAMES
                            .indexOf(UserUpdateRequest.FNAME_FIELD));
            assertThat(UserUpdateRequest.USRIDIN_FIELD).isEqualTo("USRIDINI").doesNotContain("USERID");
        }

        @Test
        @DisplayName("G9 - twelve xxxO items in the same order, each the same width as its xxxI item")
        void theTwelveOutputItemsMirrorTheInputItems() {
            assertThat(UserUpdateResponse.MAP_FIELD_COUNT)
                    .isEqualTo(UserUpdateRequest.MAP_FIELD_COUNT);
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES).containsExactly("TRNNAMEO",
                    "TITLE01O",
                    "CURDATEO",
                    "PGMNAMEO",
                    "TITLE02O",
                    "CURTIMEO",
                    "USRIDINO",
                    "FNAMEO",
                    "LNAMEO",
                    "PASSWDO",
                    "USRTYPEO",
                    "ERRMSGO");

            assertThat(UserUpdateResponse.TRN_NAME_LENGTH).isEqualTo(UserUpdateRequest.TRNNAME_LENGTH);
            assertThat(UserUpdateResponse.TITLE01_LENGTH).isEqualTo(UserUpdateRequest.TITLE01_LENGTH);
            assertThat(UserUpdateResponse.CUR_DATE_LENGTH).isEqualTo(UserUpdateRequest.CURDATE_LENGTH);
            assertThat(UserUpdateResponse.PGM_NAME_LENGTH).isEqualTo(UserUpdateRequest.PGMNAME_LENGTH);
            assertThat(UserUpdateResponse.TITLE02_LENGTH).isEqualTo(UserUpdateRequest.TITLE02_LENGTH);
            assertThat(UserUpdateResponse.CUR_TIME_LENGTH).isEqualTo(UserUpdateRequest.CURTIME_LENGTH);
            assertThat(UserUpdateResponse.USR_ID_IN_LENGTH).isEqualTo(UserUpdateRequest.USRIDIN_LENGTH);
            assertThat(UserUpdateResponse.FNAME_LENGTH).isEqualTo(UserUpdateRequest.FNAME_LENGTH);
            assertThat(UserUpdateResponse.LNAME_LENGTH).isEqualTo(UserUpdateRequest.LNAME_LENGTH);
            assertThat(UserUpdateResponse.PASSWD_LENGTH).isEqualTo(UserUpdateRequest.PASSWD_LENGTH);
            assertThat(UserUpdateResponse.USR_TYPE_LENGTH).isEqualTo(UserUpdateRequest.USRTYPE_LENGTH);
            assertThat(UserUpdateResponse.ERR_MSG_LENGTH).isEqualTo(UserUpdateRequest.ERRMSG_LENGTH);
        }

        @ParameterizedTest(name = "{0} is PIC X({1})")
        @CsvSource({"TRNNAMEI, 4", "TITLE01I, 40", "CURDATEI, 8", "PGMNAMEI, 8", "TITLE02I, 40",
            "CURTIMEI, 8", "USRIDINI, 8", "FNAMEI, 20", "LNAMEI, 20", "PASSWDI, 8", "USRTYPEI, 1",
            "ERRMSGI, 78"})
        @DisplayName("G9 - each declared width is the one app/cpy-bms/COUSR02.CPY states")
        void eachDeclaredWidthIsTheSymbolicMapsWidth(String field, int width) {
            Map<String, Integer> declared = Map.ofEntries(
                    Map.entry("TRNNAMEI", UserUpdateRequest.TRNNAME_LENGTH),
                    Map.entry("TITLE01I", UserUpdateRequest.TITLE01_LENGTH),
                    Map.entry("CURDATEI", UserUpdateRequest.CURDATE_LENGTH),
                    Map.entry("PGMNAMEI", UserUpdateRequest.PGMNAME_LENGTH),
                    Map.entry("TITLE02I", UserUpdateRequest.TITLE02_LENGTH),
                    Map.entry("CURTIMEI", UserUpdateRequest.CURTIME_LENGTH),
                    Map.entry("USRIDINI", UserUpdateRequest.USRIDIN_LENGTH),
                    Map.entry("FNAMEI", UserUpdateRequest.FNAME_LENGTH),
                    Map.entry("LNAMEI", UserUpdateRequest.LNAME_LENGTH),
                    Map.entry("PASSWDI", UserUpdateRequest.PASSWD_LENGTH),
                    Map.entry("USRTYPEI", UserUpdateRequest.USRTYPE_LENGTH),
                    Map.entry("ERRMSGI", UserUpdateRequest.ERRMSG_LENGTH));

            assertThat(declared.get(field)).isEqualTo(width);
        }

        @Test
        @DisplayName("CURTIME here is X(08), not COSGN00's X(09) - one character narrower")
        void curTimeIsEightNotNine() {
            assertThat(UserUpdateRequest.CURTIME_LENGTH).isEqualTo(8).isNotEqualTo(9);
            assertThat(EXPECTED_TIME).hasSize(UserUpdateRequest.CURTIME_LENGTH);
            assertThat(EXPECTED_DATE).hasSize(UserUpdateRequest.CURDATE_LENGTH);
        }

        @Test
        @DisplayName("every @Size max equals its xxxI width, and no width is left unconstrained")
        void everySizeMaximumIsTheDeclaredWidth() {
            RecordComponent[] components = UserUpdateRequest.class.getRecordComponents();
            assertThat(components).hasSize(UserUpdateRequest.MAP_FIELD_COUNT + 3);

            List<Integer> widths = List.of(UserUpdateRequest.TRNNAME_LENGTH,
                    UserUpdateRequest.TITLE01_LENGTH,
                    UserUpdateRequest.CURDATE_LENGTH,
                    UserUpdateRequest.PGMNAME_LENGTH,
                    UserUpdateRequest.TITLE02_LENGTH,
                    UserUpdateRequest.CURTIME_LENGTH,
                    UserUpdateRequest.USRIDIN_LENGTH,
                    UserUpdateRequest.FNAME_LENGTH,
                    UserUpdateRequest.LNAME_LENGTH,
                    UserUpdateRequest.PASSWD_LENGTH,
                    UserUpdateRequest.USRTYPE_LENGTH,
                    UserUpdateRequest.ERRMSG_LENGTH);

            for (int index = 0; index < UserUpdateRequest.MAP_FIELD_COUNT; index++) {
                Size size = components[index].getAccessor().getAnnotation(Size.class);
                assertThat(size)
                        .as("%s must declare its DFHMDF LENGTH", components[index].getName())
                        .isNotNull();
                assertThat(size.max())
                        .as("%s max", components[index].getName())
                        .isEqualTo(widths.get(index));
            }

            assertThat(components[12].getAccessor().getAnnotation(Size.class)).isNull();
            assertThat(components[13].getAccessor().getAnnotation(Size.class).max())
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(components[14].getAccessor().getAnnotation(Size.class)).isNull();
        }

        @Test
        @DisplayName("no @NotBlank and no @NotNull: a blank field is a MESSAGE, never a 400")
        void theRequestDeclaresNoPresenceConstraint() {
            List<Annotation> declared = new ArrayList<>();
            for (RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
                declared.addAll(List.of(component.getAccessor().getAnnotations()));
            }
            for (Field field : UserUpdateRequest.class.getDeclaredFields()) {
                declared.addAll(List.of(field.getAnnotations()));
            }

            assertThat(declared).isNotEmpty();
            assertThat(declared)
                    .extracting(Annotation::annotationType)
                    .doesNotContain(NotBlank.class, NotNull.class);
        }

        @Test
        @DisplayName("over HTTP a blank first name is 200 with 'First Name can NOT be empty...'")
        void aBlankFieldIsAnOkResponseCarryingTheMessage() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            UserUpdateRequest blankFirstName = withAid(
                    screen(USER_ID, "  ", "Spade", STORED_PWD, "U", reenter()),
                            PfKeyResolver.aidImage(CicsAid.DFHPF5));

            httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(blankFirstName))
                            .param(UserUpdateController.EIBCALEN_PARAM,
                                    String.valueOf(UserUpdateController.PASSED_COMMAREA_LENGTH)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(errMsgImage(UserUpdateController.MSG_FIRST_NAME_EMPTY)));

            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("a blank field is never a 400 - the payload declares no presence constraint")
        void aBlankFieldIsNeverABadRequest() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MockMvc http = httpOver(mapper);

            List<UserUpdateRequest> blanks = List.of(
                    withAid(screen(USER_ID, "  ", "Spade", STORED_PWD, "U", reenter()),
                            PfKeyResolver.aidImage(CicsAid.DFHPF5)),
                    withAid(screen(USER_ID, "Sam", "  ", STORED_PWD, "U", reenter()),
                            PfKeyResolver.aidImage(CicsAid.DFHPF5)),
                    withAid(screen(USER_ID, "Sam", "Spade", "  ", "U", reenter()),
                            PfKeyResolver.aidImage(CicsAid.DFHPF5)),
                    withAid(screen(USER_ID, "Sam", "Spade", STORED_PWD, " ", reenter()),
                            PfKeyResolver.aidImage(CicsAid.DFHPF5)));

            for (UserUpdateRequest blank : blanks) {
                MvcResult answered = http.perform(put("/api/users/{userId}", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(blank)))
                        .andExpect(status().isOk())
                        .andReturn();
                assertThat(answered.getResponse().getContentAsString())
                        .contains("can NOT be empty...");
            }

            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("B6 - the response carries PASSWDO as the unchanged marker, never the stored value")
        void theResponseCarriesThePlaintextPassword() throws Exception {
            stubFoundRead();
            ObjectMapper mapper = new ObjectMapper();

            MvcResult result = httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(populated(reenter()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passwd").value(UserUpdateResponse.PASSWD_UNCHANGED))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .as("the stored value appears nowhere in the payload, under any name")
                    .doesNotContain(STORED_PWD);
            assertThat(UserUpdateResponse.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .as("the component is still declared, so line 169's MOVE stays observable")
                    .contains("passwd");
            assertThat(UserUpdateResponse.PASSWD_FIELD).isEqualTo("PASSWDO");
            assertThat(UserUpdateResponse.PASSWD_UNCHANGED)
                    .as("the marker is the field's own width and is not blank, because line 198 "
                            + "refuses a blank password")
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH)
                    .isNotBlank()
                    .isNotEqualTo(STORED_PWD);
        }

        @Test
        @DisplayName("the marker read back means 'unchanged', so the stored secret survives a rewrite")
        void theMarkerPreservesTheStoredSecretThroughARewrite() {
            // The round trip a client actually performs: it receives the marker, edits some other field,
            // and sends the marker back untouched. Line 227 must then read EQUAL for the password and
            // NOT EQUAL for the name that changed, so exactly one modification is detected and the
            // rewrite carries the stored secret through unaltered.
            stubFoundRead();
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(screen(USER_ID, "Sammy", "Spade",
                    UserUpdateResponse.PASSWD_UNCHANGED, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.passwd())
                    .as("the marker was substituted for the stored value before line 227's comparison")
                    .isEqualTo(STORED_PWD);
            assertThat(rewrittenRecord().secUsrPwd())
                    .as("so the stored secret is carried through the locked rewrite unchanged")
                    .isEqualTo(STORED_PWD);
            assertThat(rewrittenRecord().secUsrFname())
                    .as("while the field the operator really did change is applied")
                    .isEqualTo(padded("Sammy", SecUserRecord.SEC_USR_FNAME_LENGTH));
        }

        @Test
        @DisplayName("the marker is not blank, so it passes :198 rather than refusing the whole update")
        void theMarkerIsNotTreatedAsAnEmptyPassword() {
            // Line 198's IF PASSWDI = SPACES OR LOW-VALUES refuses a blank password with "Password can
            // NOT be empty". A blank stand-in would therefore have left a client unable to change any
            // other field; the marker is eight non-blank characters precisely so that it does not.
            stubFoundRead();
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(screen(USER_ID, "Sammy", "Spade",
                    UserUpdateResponse.PASSWD_UNCHANGED, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.errFlgOn()).isFalse();
            assertThat(state.wsMessage())
                    .doesNotContain(UserUpdateController.MSG_PASSWORD_EMPTY.trim());
        }

        @Test
        @DisplayName("a genuinely retyped password is compared verbatim and does reach the record")
        void aRetypedPasswordIsStillApplied() {
            stubFoundRead();
            stubSuccessfulRewrite();

            reentryWith(screen(USER_ID, "Sam", "Spade", "NEWPWD01", "U", reenter()),
                    CicsAid.DFHPF5);

            assertThat(rewrittenRecord().secUsrPwd())
                    .as("any value that is not the marker reaches line 227's comparison unchanged")
                    .isEqualTo("NEWPWD01");
        }

        @Test
        @DisplayName("a failed read leaves the marker alone, so the no-guard path is unchanged")
        void aFailedReadLeavesTheMarkerAlone() {
            // The substitution is guarded on the read having succeeded. On NOTFND there is no stored
            // value to restore, so the marker has to behave like any other typed value: lines 219-233
            // carry no error guard, every comparison differs, and line 237 still issues the REWRITE.
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(screen(USER_ID, "Sam", "Spade",
                    UserUpdateResponse.PASSWD_UNCHANGED, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.passwd())
                    .as("nothing was restored, because nothing was read")
                    .isEqualTo(UserUpdateResponse.PASSWD_UNCHANGED);
            assertThat(state.usrModifiedYes()).isTrue();
        }

        @Test
        @DisplayName("no xxxL, xxxF or xxxA item is a JSON member - they are metadata, not payload")
        void theLengthFlagAndAttributeItemsAreNotPayload() throws Exception {
            stubFoundRead();
            ObjectMapper mapper = new ObjectMapper();

            MvcResult result = httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(populated(reenter()))))
                    .andExpect(status().isOk())
                    .andReturn();

            Set<String> members =
                    propertyNames(mapper.readTree(result.getResponse().getContentAsString()));
            assertThat(members).isNotEmpty();

            for (String inputName : UserUpdateRequest.MAP_FIELD_NAMES) {
                String base = inputName.substring(0, inputName.length() - 1);
                for (String suffix : List.of("L", "F", "A")) {
                    String cobol = base + suffix;
                    for (String member : members) {
                        assertThat(member.toUpperCase(Locale.ROOT))
                                .as("%s is a symbolic-map %s item and must stay metadata", cobol, suffix)
                                .isNotEqualTo(cobol);
                    }
                }
            }

            assertThat(members).contains("trnname", "title01", "curdate", "pgmname", "title02",
                    "curtime", "usridin", "fname", "lname", "usrtype", "errmsg", "passwd");
            assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("passwd").asText())
                    .as("and the member the wire carries is the marker, never the stored credential")
                    .isEqualTo(UserUpdateResponse.PASSWD_UNCHANGED)
                    .isNotEqualTo(STORED_PWD);
        }

        @Test
        @DisplayName("the cursor and the colour travel as METADATA, exactly as xxxL and xxxC do")
        void theCursorAndColourAreMetadataRatherThanScreenFields() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.cursorFieldName()).isEqualTo("USRIDINL");
            assertThat(UserUpdateController.ERR_MSG_COLOUR_ITEM).isEqualTo("ERRMSGC");
            assertThat(state.response().fieldValues().keySet())
                    .containsExactlyElementsOf(UserUpdateResponse.MAP_FIELD_NAMES)
                    .doesNotContain("USRIDINL", "ERRMSGC");
        }

        @Test
        @DisplayName("the route is PUT /api/users/{userId}, from app/csd/CARDDEMO.CSD:469-470")
        void theRouteIsThePutMapping() throws Exception {
            Method mapped = UserUpdateController.class.getMethod("updateUser",
                    String.class, UserUpdateRequest.class, Integer.class, Integer.class,
                    Integer.class);
            PutMapping mapping = mapped.getAnnotation(PutMapping.class);

            assertThat(mapping).isNotNull();
            assertThat(mapping.path()).containsExactly("/api/users/{userId}");
            assertThat(mapping.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        }

        @Test
        @DisplayName("the path answers PUT only: POST and DELETE on it are 405")
        void anyOtherMethodOnThePathIsMethodNotAllowed() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(populated(reenter()));
            MockMvc http = httpOver(mapper);

            http.perform(post("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isMethodNotAllowed());
            http.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isMethodNotAllowed());

            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }
    }

    @Nested
    @DisplayName("EVALUATE EIBAID :108-131 - six arms in order, and PF3 saves before it exits")
    class AidDispatchOrder {
        @Test
        @DisplayName("arm 1 :109-110 DFHENTER looks the user up and neither saves nor transfers")
        void armOneIsEnter() {
            stubFoundRead();

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            verify(repository).readForUpdate(USER_ID);
            verify(hold, never()).rewrite(any(SecUserRecord.class));
            assertThat(state.transferred()).isFalse();
            assertThat(state.errFlgOn()).isFalse();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
        }

        @Test
        @DisplayName("arm 2 :111-119 DFHPF3 SAVES FIRST - the rewrite happens, then the transfer")
        void armTwoIsPf3AndItSavesBeforeItExits() {
            stubFoundRead();
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF3);

            // The order is the assertion: UPDATE-USER-INFO at :112 runs to completion, including its
            // REWRITE, and only then does :119 transfer. A "cancel" implementation would call neither.
            InOrder sequence = inOrder(repository, hold);
            sequence.verify(repository).readForUpdate(USER_ID);
            sequence.verify(hold).rewrite(any(SecUserRecord.class));
            sequence.verifyNoMoreInteractions();

            assertThat(rewrittenRecord().secUsrFname()).isEqualTo(padded("Samuel", 20));
            assertThat(state.transferred()).isTrue();
            assertThat(state.termination()).isEqualTo(UserUpdateController.TERMINATION_XCTL);
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
        }

        @Test
        @DisplayName("arm 3 :120-121 DFHPF4 clears and repaints - no read, no rewrite, no transfer")
        void armThreeIsPf4() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.transferred()).isFalse();
            assertThat(state.errFlgOn()).isFalse();
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4)).isTrue();
        }

        @Test
        @DisplayName("arm 4 :122-123 DFHPF5 saves and STAYS - it is the only save that does not transfer")
        void armFourIsPf5AndItStays() {
            stubFoundRead();
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            verify(hold).rewrite(any(SecUserRecord.class));
            assertThat(state.transferred()).isFalse();
            assertThat(state.termination())
                    .isEqualTo(UserUpdateController.TERMINATION_RETURN_TRANSID);
            assertThat(state.returnTransid()).isEqualTo(UserUpdateController.WS_TRANID);
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF5)).isTrue();
        }

        @Test
        @DisplayName("arm 5 :124-126 DFHPF12 transfers to COADM01C and never reaches the dataset")
        void armFiveIsPf12() {
            ProgramState state = reentryWith(
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF12);

            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(padded(UserUpdateController.LIT_ADMIN_PGM, 8));
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF12)).isTrue();
        }

        @ParameterizedTest(name = "arm 6 :127-130 - AID 0x{0} is an invalid key")
        @CsvSource({"6D", "6C", "6E", "6B", "F7", "F8", "F1", "40"})
        @DisplayName("arm 6 :127-130 WHEN OTHER - CLEAR, PA1-PA3 and every unnamed PF key")
        void armSixIsWhenOther(String hex) {
            byte aid = (byte) Integer.parseInt(hex, 16);

            ProgramState state = reentryWith(populated(reenter()), aid);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(SystemMessages.CCDA_MSG_INVALID_KEY, 80));
            assertThat(state.transferred()).isFalse();
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("the eight unhandled AIDs above are the ones this screen names nowhere")
        void theUnhandledAidsAreTheOnesTheSourceNamesNowhere() {
            assertThat(List.of(CicsAid.DFHCLEAR, CicsAid.DFHPA1, CicsAid.DFHPA2, CicsAid.DFHPA3,
                            CicsAid.DFHPF7, CicsAid.DFHPF8, CicsAid.DFHPF1, CicsAid.DFHNULL))
                    .doesNotContain(CicsAid.DFHENTER, CicsAid.DFHPF3, CicsAid.DFHPF4, CicsAid.DFHPF5,
                            CicsAid.DFHPF12);
        }

        @Test
        @DisplayName("PF3 and PF5 both rewrite; only PF3 transfers - and PF12 rewrites nothing")
        void thePf3Pf5Pf12DistinctionIsThreeWay() {
            stubFoundRead();
            stubSuccessfulRewrite();
            UserUpdateRequest modified =
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter());

            ProgramState viaPf5 = reentryWith(modified, CicsAid.DFHPF5);
            assertThat(viaPf5.transferred()).isFalse();

            ProgramState viaPf3 = reentryWith(modified, CicsAid.DFHPF3);
            assertThat(viaPf3.transferred()).isTrue();

            ProgramState viaPf12 = reentryWith(modified, CicsAid.DFHPF12);
            assertThat(viaPf12.transferred()).isTrue();

            // Two rewrites in total, from PF5 and PF3. PF12 contributed none: :125-126 transfers without
            // performing UPDATE-USER-INFO, which is exactly the shape PF3 does NOT have.
            verify(hold, times(2)).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":113-117 the PF3 fallback: blank caller -> COADM01C, named caller -> the caller")
        void thePf3FallbackHasBothArms() {
            stubFoundRead();
            stubSuccessfulRewrite();
            UserUpdateRequest modifiedFields =
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter());

            assertThat(reentryWith(modifiedFields, CicsAid.DFHPF3).nextProgram())
                    .isEqualTo(padded(UserUpdateController.LIT_ADMIN_PGM, 8));

            UserUpdateRequest fromTheList = screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U",
                    reenter().withFromProgram("COUSR00C"));
            assertThat(reentryWith(fromTheList, CicsAid.DFHPF3).nextProgram())
                    .isEqualTo(padded("COUSR00C", 8));
        }

        @ParameterizedTest(name = "PfKeyResolver resolves {0} to itself")
        @EnumSource(value = AidKey.class, names = {"ENTER", "PFK03", "PFK04", "PFK05", "PFK12"})
        @DisplayName("PfKeyResolver reproduces the inline EIBAID tests - COUSR02C copies no CSSTRPFY")
        void theResolverAgreesWithTheInlineTests(AidKey key) {
            stubFoundRead();
            stubSuccessfulRewrite();
            byte aid = UserUpdateController.resolveAttentionIdentifier(
                    PfKeyResolver.aidImage(byteBehind(key)));

            assertThat(PfKeyResolver.resolve(aid)).contains(key);
            assertThat(PfKeyResolver.isAid(aid, aid)).isTrue();
            assertThat(reentryWith(populated(reenter()), aid).aidKey()).contains(key);
        }

        private byte byteBehind(AidKey key) {
            return switch (key) {
                case ENTER -> CicsAid.DFHENTER;
                case PFK03 -> CicsAid.DFHPF3;
                case PFK04 -> CicsAid.DFHPF4;
                case PFK05 -> CicsAid.DFHPF5;
                case PFK12 -> CicsAid.DFHPF12;
                default -> throw new AssertionError("this suite drives only the five keys :108-131 names");
            };
        }
    }

    @Nested
    @DisplayName("CARDDEMO-COMMAREA 160 + CDEMO-CU02-INFO 34 = the 194 bytes CU02 passes")
    class CommareaGeometry {
        @Test
        @DisplayName("NavigationContext is exactly 160 bytes, and the extension is not part of it")
        void theSharedCommareaStaysAtOneHundredAndSixty() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);

            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            assertThat(NavigationContext.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .doesNotContain("usridFirst", "usridLast", "pageNum", "nextPageFlg", "usrSelFlg",
                            "usrSelected");
        }

        @Test
        @DisplayName("the extension adds 34, so EIBCALEN on a passed commarea is 194")
        void theExtensionMakesTheCommareaOneHundredAndNinetyFour() {
            assertThat(Cu02Info.LENGTH).isEqualTo(34);
            assertThat(UserUpdateController.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + Cu02Info.LENGTH)
                    .isEqualTo(194);
            assertThat(UserUpdateController.NO_COMMAREA_LENGTH).isZero();

            assertThat(UserUpdateRequest.class.getRecordComponents())
                    .extracting(RecordComponent::getName).contains("cu02Info");
            assertThat(UserUpdateResponse.class.getRecordComponents())
                    .extracting(RecordComponent::getName).contains("cu02Info");
        }

        @Test
        @DisplayName("G22 - CDEMO-CU02-PAGE-NUM PIC 9(08) is an int, never a floating-point type")
        void thePageNumberIsIntegral() {
            RecordComponent pageNum = null;
            for (RecordComponent component : Cu02Info.class.getRecordComponents()) {
                if ("pageNum".equals(component.getName())) {
                    pageNum = component;
                }
            }

            assertThat(pageNum).isNotNull();
            assertThat(pageNum.getType()).isEqualTo(int.class)
                    .isNotEqualTo(double.class)
                    .isNotEqualTo(float.class);

            for (Class<?> type : List.of(UserUpdateRequest.class, UserUpdateResponse.class,
                    Cu02Info.class, SecUserRecord.class, NavigationContext.class)) {
                for (RecordComponent component : type.getRecordComponents()) {
                    assertThat(component.getType())
                            .as("%s.%s", type.getSimpleName(), component.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                }
            }
        }

        @Test
        @DisplayName("G50 - both 88 states of CDEMO-CU02-NEXT-PAGE-FLG are reachable and distinct")
        void bothNextPageStatesAreReachable() {
            Cu02Info more = new Cu02Info("USER0001", "USER0007", 1, Cu02Info.NEXT_PAGE_YES, "S",
                    USER_ID);
            Cu02Info noMore = new Cu02Info("USER0008", "USER0010", 2, Cu02Info.NEXT_PAGE_NO, " ",
                    USER_ID);

            assertThat(more.nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_YES).isEqualTo("Y");
            assertThat(noMore.nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_NO).isEqualTo("N");
            assertThat(Cu02Info.NEXT_PAGE_YES).isNotEqualTo(Cu02Info.NEXT_PAGE_NO);

            assertThat(Cu02Info.initial().nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_NO);

            stubFoundRead();
            assertThat(controller.handle(populated(reenter()),
                            UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, more)
                    .cu02Info().nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_YES);
            assertThat(controller.handle(populated(reenter()),
                            UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, noMore)
                    .cu02Info().nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("G50 - both CDEMO-PGM-CONTEXT states drive the two arms of :95")
        void bothProgramContextStatesDriveTheEntryArms() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);
            assertThat(enter().isEnter()).isTrue();
            assertThat(enter().isReenter()).isFalse();
            assertThat(reenter().isReenter()).isTrue();
            assertThat(reenter().isEnter()).isFalse();

            ProgramState first = controller.handle(populated(enter()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF7, noSelection());
            assertThat(first.errFlgOn()).isFalse();
            assertThat(first.commarea().isReenter()).isTrue();

            ProgramState again = reentryWith(populated(reenter()), CicsAid.DFHPF7);
            assertThat(again.errFlgOn()).isTrue();
        }
    }

    @Nested
    @DisplayName("UPDATE-USER-INFO :179-213 - LOW-VALUES reaches the same arm SPACES does")
    class LowValuesGuardChain {
        @ParameterizedTest(name = "{0} untransmitted -> \"{1}\", cursor on {2}")
        @CsvSource({
            "usrIdIn, User ID can NOT be empty..., USRIDIN",
            "fName,   First Name can NOT be empty..., FNAME",
            "lName,   Last Name can NOT be empty..., LNAME",
            "passwd,  Password can NOT be empty..., PASSWD",
            "usrType, User Type can NOT be empty..., USRTYPE"
        })
        @DisplayName("each arm fires on LOW-VALUES exactly as it fires on SPACES")
        void eachArmFiresOnLowValues(String absentField, String message, ScreenField cursor) {
            ProgramState state = reentryWith(screen(
                    "usrIdIn".equals(absentField) ? null : USER_ID,
                    "fName".equals(absentField) ? null : "Sam",
                    "lName".equals(absentField) ? null : "Spade",
                    "passwd".equals(absentField) ? null : STORED_PWD,
                    "usrType".equals(absentField) ? null : "U",
                    reenter()), CicsAid.DFHPF5);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage()).isEqualTo(padded(message, 80));
            assertThat(state.cursorRequestedOn(cursor)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("the two empty states are different bytes, and both satisfy the same condition")
        void spacesAndLowValuesAreDifferentBytesAndBothCount() {
            String spaces = UserUpdateController.spaces(UserUpdateRequest.FNAME_LENGTH);
            String lowValues = UserUpdateController.receivedImage(null, UserUpdateRequest.FNAME_LENGTH);

            assertThat(spaces).isNotEqualTo(lowValues);
            assertThat(spaces.charAt(0)).isEqualTo(' ');
            assertThat(lowValues.charAt(0)).isEqualTo('\u0000');
            assertThat(UserUpdateController.isSpacesOrLowValues(spaces)).isTrue();
            assertThat(UserUpdateController.isSpacesOrLowValues(lowValues)).isTrue();

            assertThat(UserUpdateController.isSpacesOrLowValues("\u0000   ")).isFalse();
        }

        @Test
        @DisplayName("first blank wins across the two states too: an absent id outranks a blank name")
        void theChainShortCircuitsAcrossBothEmptyStates() {
            ProgramState state = reentryWith(screen(null, "  ", "  ", null, null, reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_ID_EMPTY, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isFalse();
        }

        @Test
        @DisplayName(":210-212 WHEN OTHER puts the cursor on FNAMEL and CONTINUEs into the read")
        void whenOtherFallsThroughToTheRead() {
            stubFoundRead();
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.errFlgOn()).isFalse();
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("the id arm comes FIRST here - COUSR01C puts its names ahead of its identifier")
        void theIdentifierArmIsFirstOnThisScreen() {
            ProgramState state = reentryWith(screen("  ", "  ", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_ID_EMPTY, 80))
                    .isNotEqualTo(padded(UserUpdateController.MSG_FIRST_NAME_EMPTY, 80));
        }
    }

    @Nested
    @DisplayName(":219-243 - four independent IFs, sixteen combinations, and the padded comparison")
    class ChangeDetection {
        @BeforeEach
        void stubTheDataset() {
            stubFoundRead();
            stubSuccessfulRewrite();
        }

        private SecUserRecord stored() {
            return storedUser();
        }

        @Test
        @DisplayName(":219-220 only the first name changed: only SEC-USR-FNAME differs on the rewrite")
        void onlyTheFirstNameChanges() {
            reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrFname()).isEqualTo(padded("Samuel", 20))
                    .isNotEqualTo(stored().secUsrFname());
            assertThat(written.secUsrLname()).isEqualTo(stored().secUsrLname());
            assertThat(written.secUsrPwd()).isEqualTo(stored().secUsrPwd());
            assertThat(written.secUsrType()).isEqualTo(stored().secUsrType());
            assertThat(written.secUsrId()).isEqualTo(stored().secUsrId());
            assertThat(written.secUsrFiller()).isEqualTo(stored().secUsrFiller());
        }

        @Test
        @DisplayName(":223-224 only the last name changed: only SEC-USR-LNAME differs on the rewrite")
        void onlyTheLastNameChanges() {
            reentryWith(screen(USER_ID, "Sam", "Spadey", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrLname()).isEqualTo(padded("Spadey", 20))
                    .isNotEqualTo(stored().secUsrLname());
            assertThat(written.secUsrFname()).isEqualTo(stored().secUsrFname());
            assertThat(written.secUsrPwd()).isEqualTo(stored().secUsrPwd());
            assertThat(written.secUsrType()).isEqualTo(stored().secUsrType());
        }

        @Test
        @DisplayName(":227-228 only the password changed: only SEC-USR-PWD differs, in clear text")
        void onlyThePasswordChanges() {
            reentryWith(screen(USER_ID, "Sam", "Spade", "PWDBBBBB", "U", reenter()), CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrPwd()).isEqualTo("PWDBBBBB")
                    .isNotEqualTo(stored().secUsrPwd())
                    .hasSize(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(written.secUsrFname()).isEqualTo(stored().secUsrFname());
            assertThat(written.secUsrLname()).isEqualTo(stored().secUsrLname());
            assertThat(written.secUsrType()).isEqualTo(stored().secUsrType());
        }

        @Test
        @DisplayName(":231-232 only the user type changed: only SEC-USR-TYPE differs on the rewrite")
        void onlyTheUserTypeChanges() {
            reentryWith(screen(USER_ID, "Sam", "Spade", STORED_PWD, "A", reenter()), CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrType()).isEqualTo("A").isNotEqualTo(stored().secUsrType());
            assertThat(written.secUsrFname()).isEqualTo(stored().secUsrFname());
            assertThat(written.secUsrLname()).isEqualTo(stored().secUsrLname());
            assertThat(written.secUsrPwd()).isEqualTo(stored().secUsrPwd());
        }

        @Test
        @DisplayName("two fields changed at once produce ONE rewrite carrying BOTH changes")
        void twoChangesProduceOneRewriteCarryingBoth() {
            reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "A", reenter()),
                    CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrFname()).isEqualTo(padded("Samuel", 20));
            assertThat(written.secUsrType()).isEqualTo("A");
            assertThat(written.secUsrLname()).isEqualTo(stored().secUsrLname());
            assertThat(written.secUsrPwd()).isEqualTo(stored().secUsrPwd());
            verify(hold, times(1)).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("all four changed at once still produce ONE rewrite carrying all four")
        void allFourChangesProduceOneRewrite() {
            reentryWith(screen(USER_ID, "Samuel", "Spadey", "PWDBBBBB", "A", reenter()),
                    CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrFname()).isEqualTo(padded("Samuel", 20));
            assertThat(written.secUsrLname()).isEqualTo(padded("Spadey", 20));
            assertThat(written.secUsrPwd()).isEqualTo("PWDBBBBB");
            assertThat(written.secUsrType()).isEqualTo("A");
            assertThat(written.secUsrId()).isEqualTo(stored().secUsrId());
            assertThat(written.secUsrFiller()).isEqualTo(stored().secUsrFiller());
        }

        @ParameterizedTest(name = "combination {0} of 16")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
        @DisplayName("all sixteen combinations are reachable, and modified is true for exactly fifteen")
        void allSixteenCombinationsAreReachable(int mask) {
            boolean firstNameChanged = (mask & 1) != 0;
            boolean lastNameChanged = (mask & 2) != 0;
            boolean passwordChanged = (mask & 4) != 0;
            boolean userTypeChanged = (mask & 8) != 0;

            ProgramState state = reentryWith(screen(USER_ID,
                    firstNameChanged ? "Samuel" : "Sam",
                    lastNameChanged ? "Spadey" : "Spade",
                    passwordChanged ? "PWDBBBBB" : STORED_PWD,
                    userTypeChanged ? "A" : "U",
                    reenter()), CicsAid.DFHPF5);

            boolean anythingChanged = mask != 0;
            assertThat(state.usrModifiedYes()).isEqualTo(anythingChanged);
            assertThat(state.wsUsrModified()).isEqualTo(anythingChanged
                    ? UserUpdateController.USR_MODIFIED_YES
                    : UserUpdateController.USR_MODIFIED_NO);

            if (anythingChanged) {
                SecUserRecord written = rewrittenRecord();
                assertThat(written.secUsrFname())
                        .isEqualTo(padded(firstNameChanged ? "Samuel" : "Sam", 20));
                assertThat(written.secUsrLname())
                        .isEqualTo(padded(lastNameChanged ? "Spadey" : "Spade", 20));
                assertThat(written.secUsrPwd()).isEqualTo(passwordChanged ? "PWDBBBBB" : STORED_PWD);
                assertThat(written.secUsrType()).isEqualTo(userTypeChanged ? "A" : "U");
                assertThat(state.wsMessage())
                        .isEqualTo(UserUpdateController.updatedConfirmation(USER_ID));
            } else {
                verify(hold, never()).rewrite(any(SecUserRecord.class));
                assertThat(state.wsMessage())
                        .isEqualTo(padded(UserUpdateController.MSG_PLEASE_MODIFY, 80));
                assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHRED);
            }
        }

        @Test
        @DisplayName("G50 - both 88 states of WS-USR-MODIFIED are driven, and both are one character")
        void bothModifiedStatesAreDriven() {
            assertThat(reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5).usrModifiedYes()).isTrue();
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF5).usrModifiedYes()).isFalse();

            assertThat(UserUpdateController.USR_MODIFIED_YES).isEqualTo("Y").hasSize(1);
            assertThat(UserUpdateController.USR_MODIFIED_NO).isEqualTo("N").hasSize(1);
            assertThat(UserUpdateController.USR_MODIFIED_YES)
                    .isNotEqualTo(UserUpdateController.USR_MODIFIED_NO);
        }

        @Test
        @DisplayName(":85 SET USR-MODIFIED-NO runs on every entry, so nothing leaks between requests")
        void theModifiedFlagDoesNotLeakBetweenRequests() {
            ProgramState modified = reentryWith(
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);
            assertThat(modified.usrModifiedYes()).isTrue();

            ProgramState unmodified = reentryWith(populated(reenter()), CicsAid.DFHPF5);
            assertThat(unmodified.usrModifiedYes()).isFalse();
            assertThat(unmodified.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_PLEASE_MODIFY, 80));

            // Exactly one rewrite in total - the first request's. The second contributed none.
            verify(hold, times(1)).rewrite(any(SecUserRecord.class));

            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF7).errFlgOn()).isTrue();
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF4).errFlgOn()).isFalse();
        }

        @Test
        @DisplayName("the comparison is on the PADDED image: 'Sam' equals a stored 'Sam' at width 20")
        void theComparisonIsOnThePaddedImage() {
            FixedWidthCodec pictureRules =
                    new FixedWidthCodec(UserUpdateController.WORKING_STORAGE_CHARSET);
            String typed = pictureRules.movePicX("Sam", UserUpdateRequest.FNAME_LENGTH);
            String onFile = stored().secUsrFname();

            assertThat(typed).hasSize(UserUpdateRequest.FNAME_LENGTH).isEqualTo(onFile);

            assertThat("Sam").isNotEqualTo(onFile);
            assertThat(onFile.trim()).isEqualTo("Sam");

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF5);
            assertThat(state.usrModifiedYes()).isFalse();
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("trailing spaces the operator typed are not a modification either")
        void trailingSpacesAreNotAModification() {
            ProgramState state = reentryWith(
                    screen(USER_ID, "Sam      ", "Spade   ", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.usrModifiedYes()).isFalse();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_PLEASE_MODIFY, 80));
            verify(hold, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("the comparison is case sensitive: COUSR02C has no FUNCTION UPPER-CASE")
        void theComparisonIsCaseSensitive() {
            reentryWith(screen(USER_ID, "sam", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrFname()).isEqualTo(padded("sam", 20))
                    .isNotEqualTo(padded("SAM", 20));
        }
    }

    @Nested
    @DisplayName("The dataset contract - READ ... UPDATE, a keyless REWRITE, one status vocabulary")
    class DatasetContract {
        @Test
        @DisplayName(":360 the REWRITE takes NO key - every rewrite overload takes only the record")
        void theRewriteTakesNoKey() throws Exception {
            List<Method> rewrites = new ArrayList<>();
            for (Method method : SecUserRepository.class.getDeclaredMethods()) {
                if ("rewrite".equals(method.getName()) && Modifier.isPublic(method.getModifiers())) {
                    rewrites.add(method);
                }
            }

            assertThat(rewrites).hasSize(1);
            Method rewrite = rewrites.get(0);
            assertThat(rewrite.getParameterTypes()).containsExactly(SecUserRecord.class);
            assertThat(rewrite.getReturnType()).isEqualTo(WriteResult.class);

            for (Class<?> parameter : rewrite.getParameterTypes()) {
                assertThat(parameter).isNotIn(String.class, long.class, int.class, Long.class,
                        Integer.class);
            }

            assertThat(SecUserRepository.class.getMethod("readForUpdate", String.class)
                    .getReturnType()).isEqualTo(ReadResult.class);
        }

        @Test
        @DisplayName("a REWRITE is always preceded by the READ ... UPDATE that took the hold")
        void theReadAlwaysPrecedesTheRewrite() {
            stubFoundRead();
            stubSuccessfulRewrite();

            reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            InOrder sequence = inOrder(repository, hold);
            sequence.verify(repository).readForUpdate(USER_ID);
            sequence.verify(hold).rewrite(any(SecUserRecord.class));
            sequence.verifyNoMoreInteractions();

            verify(repository, never()).read(anyString());
        }

        @Test
        @DisplayName("the rewrite is handed the record the read returned, with the key it was read by")
        void theRewriteCarriesTheRecordTheReadReturned() {
            stubFoundRead();
            stubSuccessfulRewrite();

            reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrId()).isEqualTo(USER_ID);
            assertThat(written.secUsrId()).hasSize(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(SecUserRecord.encode(written, UserUpdateController.WORKING_STORAGE_CHARSET))
                    .hasSize(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("B5 - the vestigial CONTINUE at :335 stays a no-op: the arm still does all three")
        void theVestigialContinueChangesNothing() {
            stubFoundRead();

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.wsMessage())
                    .as(":336-337 MOVE 'Press PF5 key to save your updates ...' TO WS-MESSAGE")
                    .isEqualTo(padded(UserUpdateController.MSG_PRESS_PF5, 80));
            assertThat(state.sends().get(0).errMsgColour())
                    .as(":338 MOVE DFHNEUTR TO ERRMSGC")
                    .isEqualTo(BmsAttributes.DFHNEUTR);
            assertThat(state.sendCount())
                    .as(":339 PERFORM SEND-USRUPD-SCREEN, then :171 sends again")
                    .isEqualTo(2);
            assertThat(state.secUserData().secUsrFname()).isEqualTo(padded("Sam", 20));
            assertThat(state.errFlgOn()).isFalse();
        }

        @Test
        @DisplayName("the two RESP spellings unify: DFHRESP(NOTFND) here, raw 13 in COSGN00C")
        void theTwoRespSpellingsUnifyThroughFileStatus() {
            assertThat(FileStatus.NORMAL).isZero();
            assertThat(FileStatus.NOTFND).isEqualTo(13);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL))
                    .isEqualTo(FileStatus.outcomeOfStatus(FileStatus.OK));
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NOTFND))
                    .isEqualTo(FileStatus.outcomeOfStatus(FileStatus.NOT_FOUND));

            assertThat(ReadResult.notFound().isNotFound()).isTrue();
            assertThat(WriteResult.notFound().isNotFound()).isTrue();
            assertThat(ReadResult.notFound().statusImage())
                    .isEqualTo(WriteResult.notFound().statusImage());
        }

        @Test
        @DisplayName("G47 - the read's three arms are each reached from the ENTER call site at :163")
        void theReadsThreeArmsAreEachReachedFromTheLookup() {
            when(repository.readForUpdate(anyString()))
                    .thenReturn(ReadResult.held(storedUser(), hold));
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHENTER).wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_PRESS_PF5, 80));

            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHENTER).wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));

            when(repository.readForUpdate(anyString()))
                    .thenReturn(ReadResult.of(FileStatus.END_OF_FILE, CicsResponse.reported(20, 0)));
            ProgramState other = reentryWith(populated(reenter()), CicsAid.DFHENTER);
            assertThat(other.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_UNABLE_TO_LOOKUP, 80));
            assertThat(other.displayLines()).containsExactly(displayLine(20, 0));
        }

        @Test
        @DisplayName("G47 - the rewrite's three arms are each reached from the save call site at :237")
        void theRewritesThreeArmsAreEachReachedFromTheSave() {
            stubFoundRead();
            UserUpdateRequest modified =
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter());

            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());
            assertThat(reentryWith(modified, CicsAid.DFHPF5).wsMessage())
                    .isEqualTo(UserUpdateController.updatedConfirmation(USER_ID));

            when(hold.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.notFound());
            assertThat(reentryWith(modified, CicsAid.DFHPF5).wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));

            when(hold.rewrite(any(SecUserRecord.class)))
                    .thenReturn(WriteResult.of(FileStatus.DUPLICATE, CicsResponse.reported(14, 0)));
            ProgramState other = reentryWith(modified, CicsAid.DFHPF5);
            assertThat(other.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_UNABLE_TO_UPDATE, 80));
            assertThat(other.displayLines()).contains(displayLine(14, 0));
        }

        @Test
        @DisplayName(":372-374 DELIMITED BY SPACE trims the key: 'AB' gives no interior padding")
        void theConfirmationTrimsTheKeyAtItsFirstSpace() {
            SecUserRecord shortKey = SecUserRecord.of("AB", "Sam", "Spade", STORED_PWD, "U",
                    UserUpdateController.WORKING_STORAGE_CHARSET);
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.held(shortKey, hold));
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(
                    screen("AB", "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.wsMessage())
                    .isEqualTo(padded("User AB has been updated ...", 80))
                    .doesNotContain("User AB      has");
            assertThat(rewrittenRecord().secUsrId()).isEqualTo(padded("AB", 8));
        }
    }

    @Nested
    @DisplayName("The USRSEC seed - app/jcl/DUSRSECJ.jcl:34-44, ten rows of 57 padded to 80")
    class SeededUsrsec {
        private static final String SEED_ID = "ADMIN001";
        private static final String SEED_FIRST_NAME = "MARGARET";
        private static final String SEED_LAST_NAME = "GOLD";
        private static final String SEED_TYPE = "A";

        private static final String ABSENT_ID = "NOSUCH01";

        @Test
        @DisplayName("the seeded row is 57 named characters, right-padded to the declared 80")
        void theSeedRowIsFiftySevenPaddedToEighty() {
            SecUserRecord seeded = SecUserRecord.of(SEED_ID, SEED_FIRST_NAME, SEED_LAST_NAME,
                    STORED_PWD, SEED_TYPE, UserUpdateController.WORKING_STORAGE_CHARSET);

            int named = SecUserRecord.SEC_USR_ID_LENGTH
                    + SecUserRecord.SEC_USR_FNAME_LENGTH
                    + SecUserRecord.SEC_USR_LNAME_LENGTH
                    + SecUserRecord.SEC_USR_PWD_LENGTH
                    + SecUserRecord.SEC_USR_TYPE_LENGTH;
            assertThat(named).isEqualTo(57);
            assertThat(named + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);

            assertThat(seeded.secUsrFiller())
                    .hasSize(SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .isBlank();
            assertThat(SecUserRecord.encode(seeded, UserUpdateController.WORKING_STORAGE_CHARSET))
                    .hasSize(SecUserRecord.RECORD_LENGTH);

            FixedWidthCodec codec =
                    new FixedWidthCodec(UserUpdateController.WORKING_STORAGE_CHARSET);
            String stream = SEED_ID + padded(SEED_FIRST_NAME, 20) + padded(SEED_LAST_NAME, 20)
                    + STORED_PWD + SEED_TYPE;
            assertThat(stream).hasSize(57);
            assertThat(codec.padToDeclaredWidth(stream, SecUserRecord.RECORD_LENGTH))
                    .hasSize(SecUserRecord.RECORD_LENGTH)
                    .startsWith(stream);
        }

        @Test
        @DisplayName("a seeded administrator is found and painted, type and all")
        void aSeededAdministratorIsPainted() {
            SecUserRecord seeded = SecUserRecord.of(SEED_ID, SEED_FIRST_NAME, SEED_LAST_NAME,
                    STORED_PWD, SEED_TYPE, UserUpdateController.WORKING_STORAGE_CHARSET);
            when(repository.readForUpdate(SEED_ID)).thenReturn(ReadResult.held(seeded, hold));

            ProgramState state = reentryWith(
                    screen(SEED_ID, "typed", "over", "TYPEDPWD", "U", reenter()), CicsAid.DFHENTER);

            assertThat(state.fName()).isEqualTo(padded(SEED_FIRST_NAME, 20));
            assertThat(state.lName()).isEqualTo(padded(SEED_LAST_NAME, 20));
            assertThat(state.usrType()).isEqualTo(SEED_TYPE);
            assertThat(state.passwd()).isEqualTo(STORED_PWD).isNotEqualTo("TYPEDPWD");
        }

        @Test
        @DisplayName("changing a seeded administrator's first name is the modification that fires")
        void changingTheSeededFirstNameIsAModification() {
            SecUserRecord seeded = SecUserRecord.of(SEED_ID, SEED_FIRST_NAME, SEED_LAST_NAME,
                    STORED_PWD, SEED_TYPE, UserUpdateController.WORKING_STORAGE_CHARSET);
            when(repository.readForUpdate(SEED_ID)).thenReturn(ReadResult.held(seeded, hold));
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(
                    screen(SEED_ID, "MARGARETHE", SEED_LAST_NAME, STORED_PWD, SEED_TYPE, reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.usrModifiedYes()).isTrue();
            assertThat(rewrittenRecord().secUsrFname()).isEqualTo(padded("MARGARETHE", 20));
            assertThat(state.wsMessage())
                    .isEqualTo(padded("User " + SEED_ID + " has been updated ...", 80));
        }

        @Test
        @DisplayName("a key no seeded row carries is DFHRESP(NOTFND): 'User ID NOT found...'")
        void anAbsentKeyIsNotFound() {
            when(repository.readForUpdate(ABSENT_ID)).thenReturn(ReadResult.notFound());

            ProgramState state = reentryWith(
                    screen(ABSENT_ID, "Sam", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHENTER);

            verify(repository).readForUpdate(ABSENT_ID);
            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(ABSENT_ID).hasSize(SecUserRecord.SEC_USR_ID_LENGTH);
        }
    }

    @Nested
    @DisplayName("CSSETATY - DFHRED and '*' in REENTER, and nothing at all on first entry")
    class ErrorHighlight {
        @Test
        @DisplayName("G38 - a blank field in REENTER takes DFHRED on its colour item and '*' on output")
        void aFailingFieldIsHighlightedOnReentry() {
            FieldHighlight highlighted =
                    FieldAttributeSetter.resolve(FieldValidationState.BLANK, true, "FNAME",
                            UserUpdateResponse.MAP_NAME);

            assertThat(highlighted.untouched()).isFalse();
            assertThat(highlighted.colourItemAssigned()).isTrue();
            assertThat(highlighted.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(highlighted.outputItemAssigned()).isTrue();
            assertThat(highlighted.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);

            assertThat(highlighted.colourItemName())
                    .isEqualTo("FNAME" + FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
            assertThat(highlighted.outputItemName())
                    .isEqualTo("FNAME" + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
        }

        @Test
        @DisplayName("G38 - the same blank field on FIRST entry is left completely untouched")
        void nothingIsHighlightedOnFirstEntry() {
            FieldHighlight untouched =
                    FieldAttributeSetter.resolve(FieldValidationState.BLANK, false, "FNAME",
                            UserUpdateResponse.MAP_NAME);

            assertThat(untouched.untouched()).isTrue();
            assertThat(untouched.colourItemAssigned()).isFalse();
            assertThat(untouched.outputItemAssigned()).isFalse();
        }

        @ParameterizedTest(name = "{0} in REENTER")
        @EnumSource(FieldValidationState.class)
        @DisplayName("only a failing state highlights: OK is untouched even in REENTER")
        void onlyAFailingStateHighlights(FieldValidationState state) {
            FieldHighlight resolved = FieldAttributeSetter.resolve(state, true);

            if (state == FieldValidationState.OK) {
                assertThat(resolved.untouched()).isTrue();
            } else {
                assertThat(resolved.colourItemAssigned()).isTrue();
                assertThat(resolved.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            }

            assertThat(FieldAttributeSetter.resolveFromFlags(state.notOk(), state.blank(), true))
                    .isEqualTo(resolved);
        }

        @Test
        @DisplayName("the message colour the program itself sets is the one the screen carries")
        void theProgramsOwnColoursAreTheOnesItSets() {
            stubFoundRead();
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHENTER).errMsgColour())
                    .isEqualTo(BmsAttributes.DFHNEUTR);
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF5).errMsgColour())
                    .isEqualTo(BmsAttributes.DFHRED);

            stubSuccessfulRewrite();
            assertThat(reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5).errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);

            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF4).errMsgColour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
        }
    }

    @Nested
    @DisplayName("POPULATE-HEADER-INFO :296-315 - a fixed Clock, so the header is reproducible")
    class DeterministicHeader {
        @Test
        @DisplayName("the header equals what common.DateHeader renders from the same fixed instant")
        void theHeaderMatchesTheSharedRenderer() {
            DateHeader expected = DateHeader.from(
                    new FixedWidthCodec(UserUpdateController.WORKING_STORAGE_CHARSET), FIXED_CLOCK);
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.curDate()).isEqualTo(expected.wsCurdateMmDdYy()).isEqualTo(EXPECTED_DATE);
            assertThat(state.curTime()).isEqualTo(expected.wsCurtimeHhMmSs()).isEqualTo(EXPECTED_TIME);
            assertThat(state.curDate()).hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(state.curTime()).hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);
        }

        @Test
        @DisplayName("two calls a notional second apart still render the same header, because it is fixed")
        void repeatedCallsRenderTheSameHeader() {
            ProgramState first = reentryWith(populated(reenter()), CicsAid.DFHPF4);
            ProgramState second = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(second.curDate()).isEqualTo(first.curDate());
            assertThat(second.curTime()).isEqualTo(first.curTime());
        }

        @Test
        @DisplayName("config.WebConfig publishes exactly one Clock bean, which is this seam")
        void theClockSeamIsAPublishedBean() throws Exception {
            Method clockBean = WebConfig.class.getMethod("clock");

            assertThat(clockBean.getReturnType()).isEqualTo(Clock.class);
            assertThat(clockBean.getParameterCount()).isZero();
            assertThat(clockBean.getAnnotations())
                    .extracting(annotation -> annotation.annotationType().getName())
                    .anyMatch(name -> name.endsWith(".Bean"));

            long clockBeanCount = 0;
            for (Method method : WebConfig.class.getDeclaredMethods()) {
                if (Clock.class.equals(method.getReturnType())) {
                    clockBeanCount++;
                }
            }
            assertThat(clockBeanCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Migration constraints - no concurrency artefact, no session, no security framework")
    class MigrationConstraints {
        @Test
        @DisplayName("G43 does NOT apply: no version column, no ETag and no re-read-and-compare exists")
        void noOptimisticConcurrencyArtefactExists() {
            for (Class<?> type : List.of(UserUpdateRequest.class, UserUpdateResponse.class,
                    Cu02Info.class, SecUserRecord.class)) {
                for (RecordComponent component : type.getRecordComponents()) {
                    String name = component.getName().toLowerCase(Locale.ROOT);
                    assertThat(name)
                            .as("%s.%s", type.getSimpleName(), component.getName())
                            .doesNotContain("version")
                            .doesNotContain("revision")
                            .doesNotContain("etag")
                            .doesNotContain("lastmodified")
                            .doesNotContain("optimistic");
                }
            }

            for (Method method : UserUpdateController.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("%s suggests a change-in-record check COUSR02C does not perform",
                                method.getName())
                        .doesNotContain("checkchange")
                        .doesNotContain("changeinrec")
                        .doesNotContain("concurren");
            }

            stubFoundRead();
            stubSuccessfulRewrite();
            reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);
            verify(repository, times(1)).readForUpdate(USER_ID);
            verify(hold, times(1)).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("G44 - no persistence provider is even on the classpath to be annotated with")
        void noPersistenceArtefactExists() {
            List<Annotation> annotations = new ArrayList<>();
            annotations.addAll(List.of(UserUpdateController.class.getAnnotations()));
            for (Method method : UserUpdateController.class.getDeclaredMethods()) {
                annotations.addAll(List.of(method.getAnnotations()));
            }
            for (Class<?> type : List.of(UserUpdateRequest.class, UserUpdateResponse.class,
                    SecUserRecord.class)) {
                annotations.addAll(List.of(type.getAnnotations()));
            }

            for (Annotation annotation : annotations) {
                assertThat(annotation.annotationType().getName())
                        .doesNotContain("persistence")
                        .doesNotContain("hibernate");
            }
            assertThatThrownBy(() -> Class.forName("jakarta.persistence.Entity"))
                    .isInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("G41/B6 - no encoder, digest, token or security type is reachable from this screen")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> forbidden = List.of("PasswordEncoder", "BCrypt", "MessageDigest",
                    "org.springframework.security", "Jwt", "Cipher", "SecretKey");

            List<String> reachable = new ArrayList<>();
            for (Class<?> type : List.of(UserUpdateController.class, UserUpdateRequest.class,
                    UserUpdateResponse.class, SecUserRecord.class)) {
                for (Method method : type.getDeclaredMethods()) {
                    reachable.add(method.getReturnType().getName());
                    reachable.add(method.getName());
                    for (Class<?> parameter : method.getParameterTypes()) {
                        reachable.add(parameter.getName());
                    }
                }
                for (Annotation annotation : type.getAnnotations()) {
                    reachable.add(annotation.annotationType().getName());
                }
            }

            for (String name : reachable) {
                for (String marker : forbidden) {
                    assertThat(name)
                            .as("%s suggests %s; strengthening the comparison at :227 would change "
                                    + "observable behaviour and is out of scope", name, marker)
                            .doesNotContain(marker);
                }
            }
        }

        @Test
        @DisplayName("G37 - no HttpSession is created, no cookie set, and no state kept server-side")
        void nothingIsRetainedServerSide() throws Exception {
            stubFoundRead();
            ObjectMapper mapper = new ObjectMapper();

            MvcResult result = httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(populated(reenter()))))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false))
                    .as("COUSR02C is pseudo-conversational: state travels in the payload (rule R6)")
                    .isNull();
            MockHttpServletResponse response = result.getResponse();
            assertThat(response.getCookies()).isEmpty();
            assertThat(response.getHeaderNames()).doesNotContain("Set-Cookie");

            JsonNode body = mapper.readTree(response.getContentAsString());
            assertThat(body.has("navigationContext")).isTrue();
            assertThat(body.has("cu02Info")).isTrue();
        }

        @Test
        @DisplayName("G40 - navigation is a RESPONSE FIELD: no redirect, no forward, no Location header")
        void navigationIsAResponseFieldRatherThanARedirect() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            UserUpdateRequest noCommarea = screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null);

            httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(noCommarea)))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.nextProgram")
                            .value(UserUpdateController.LIT_SIGNON_PGM));

            MvcResult transferred = httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(noCommarea)))
                    .andReturn();
            assertThat(transferred.getResponse().getStatus()).isEqualTo(200);
            assertThat(transferred.getResponse().getForwardedUrl()).isNull();
            assertThat(transferred.getResponse().getRedirectedUrl()).isNull();
        }

        @Test
        @DisplayName("G46 - no dataset name literal appears in any of the controller's constants")
        void noDatasetNameLiteralIsUsed() throws IllegalAccessException {
            String highLevelQualifiers = "AWS" + ".M2.";
            String clusterSuffix = "VSAM" + ".KSDS";

            int inspected = 0;
            for (Field field : UserUpdateController.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    assertThat((String) field.get(null))
                            .as("constant %s must not embed a dataset name", field.getName())
                            .doesNotContain(highLevelQualifiers)
                            .doesNotContain(clusterSuffix);
                    inspected++;
                }
            }
            assertThat(inspected)
                    .as("the controller's string constants must actually have been inspected")
                    .isGreaterThanOrEqualTo(15);

            assertThat(UserUpdateController.WS_USRSEC_FILE)
                    .isEqualTo(SecUserRepository.CICS_FILE_NAME_IMAGE)
                    .hasSize(8);
            assertThat(UserUpdateController.WS_USRSEC_FILE.trim()).isEqualTo("USRSEC");
        }

        @Test
        @DisplayName("G53 - two independent requests cannot see each other's WORKING-STORAGE")
        void independentRequestsDoNotInterfere() {
            stubFoundRead();

            ProgramState failing = reentryWith(populated(reenter()), CicsAid.DFHPF7);
            ProgramState succeeding = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(failing.errFlgOn()).isTrue();
            assertThat(succeeding.errFlgOn()).isFalse();
            assertThat(succeeding.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_PRESS_PF5, 80));
            assertThat(succeeding.displayLines()).isEmpty();
            assertThat(failing.secUserData().secUsrFname()).isBlank();
            assertThat(succeeding.secUserData().secUsrFname()).isEqualTo(padded("Sam", 20));

            assertThat(succeeding).isNotSameAs(failing);
            for (Field field : UserUpdateController.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }
}
