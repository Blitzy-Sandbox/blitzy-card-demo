package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SignOnService.CursorField;
import com.vsergeychik.carddemo.user.SignOnService.ReceiveOutcome;
import com.vsergeychik.carddemo.user.SignOnService.SignOnInput;
import com.vsergeychik.carddemo.user.SignOnService.SignOnOutcome;
import com.vsergeychik.carddemo.user.SignOnService.Termination;
import com.vsergeychik.carddemo.user.dto.SignOnRequest;
import com.vsergeychik.carddemo.user.dto.SignOnResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SignOnController}, the HTTP adapter over {@code app/cbl/COSGN00C.cbl} - CSD transaction
 * {@code CC00}.
 *
 * <h2>Why this class exists, given the controller makes no decisions</h2>
 *
 * <p>{@link SignOnService} carries every branch of the program and is tested directly in
 * {@code SignOnServiceTest}. What is left here is nonetheless observable behaviour that no service test
 * can see: which HTTP method and path the screen is reached at, what an absent body is taken to mean,
 * how the ten {@code COSGN0AO} output items are projected at their exact declared widths, how the three
 * distinct CICS exits are told apart by a stateless client, and - the two things that have no
 * {@code DFHMDF} field to travel in - that the cursor request and the full-repaint instruction actually
 * reach the caller.
 *
 * <p>Three properties are asserted here that are the whole reason the split exists:
 *
 * <ul>
 *   <li><strong>The controller decides nothing.</strong> Every path below is reached by stubbing the
 *       repository and letting the real service decide, or by handing a constructed
 *       {@link SignOnOutcome} straight to the projection. No assertion depends on the controller
 *       choosing anything.</li>
 *   <li><strong>No branch needs {@code MockMvc}.</strong> All but one nested class drives plain method
 *       calls. {@code MockMvc} appears only in {@link TheWire}, and only to confirm the route, the
 *       status and the wire shape - never to reach a branch.</li>
 *   <li><strong>The password never comes back.</strong> Asserted structurally, over the response
 *       record's components, and over the serialised JSON.</li>
 * </ul>
 */
@DisplayName("SignOnController - the HTTP surface of COSGN00C, transaction CC00")
class SignOnControllerTest {

    /** A single-byte code page, named explicitly rather than taken from the platform. */
    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    /**
     * A pinned instant, so the two header fields are assertable byte for byte.
     *
     * <p>Chosen as the version stamp {@code app/cbl/COSGN00C.cbl:259} carries. At {@link ZoneOffset#UTC}
     * it renders {@code 07/19/22} into {@code CURDATEO} and {@code 23:12:33} into the nine-character
     * {@code CURTIMEO}.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** The {@code MM/DD/YY} rendering {@link #FIXED_CLOCK} produces, {@code WS-CURDATE-MM-DD-YY}. */
    private static final String EXPECTED_CURDATE = "07/19/22";

    /**
     * The {@code CURTIMEO} image {@link #FIXED_CLOCK} produces: {@code HH:MM:SS} in eight characters,
     * right-padded by the {@code MOVE} at {@code app/cbl/COSGN00C.cbl:196} into a {@code PIC X(9)}
     * receiver.
     */
    private static final String EXPECTED_CURTIME = "23:12:33 ";

    /** The seeded password from {@code app/jcl/DUSRSECJ.jcl:35-44}, stored in the clear. */
    private static final String STORED_PASSWORD = "PASSWORD";

    /** A configured application identifier, exactly {@code PIC X(8)}. */
    private static final String APPLID = "CICSAWSC";

    /** A configured system identifier, deliberately shorter than {@code PIC X(8)}. */
    private static final String SYSID = "AWSC";

    private SecUserRepository repository;

    private SignOnController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        controller = new SignOnController(new SignOnService(repository), FIXED_CLOCK, APPLID, SYSID,
                CODE_PAGE);
    }

    // =================================================================================================
    // Helpers.
    // =================================================================================================

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static SecUserRecord storedUser(String userId, String password, String userType) {
        return SecUserRecord.of(userId, "FIRSTNAME", "LASTNAME", password, userType, CODE_PAGE);
    }

    private void stubRead(ReadResult result) {
        when(repository.read(anyString())).thenReturn(result);
    }

    /**
     * A payload continuing the pseudo-conversation: a communication area is present, so
     * {@code EIBCALEN} is non-zero and {@code app/cbl/COSGN00C.cbl:85} decides.
     */
    private static SignOnRequest submitted(String userId, String password, String aidToken) {
        return new SignOnRequest(spaces(SignOnRequest.TRNNAME_LENGTH),
                spaces(SignOnRequest.TITLE01_LENGTH),
                spaces(SignOnRequest.CURDATE_LENGTH),
                spaces(SignOnRequest.PGMNAME_LENGTH),
                spaces(SignOnRequest.TITLE02_LENGTH),
                spaces(SignOnRequest.CURTIME_LENGTH),
                spaces(SignOnRequest.APPLID_LENGTH),
                spaces(SignOnRequest.SYSID_LENGTH),
                userId,
                password,
                spaces(SignOnRequest.ERRMSG_LENGTH),
                NavigationContext.empty(),
                aidToken);
    }

    /** The ENTER key, the arm {@code app/cbl/COSGN00C.cbl:86-87} takes. */
    private static SignOnRequest enterKey(String userId, String password) {
        return submitted(userId, password, PfKeyResolver.AidKey.ENTER.token());
    }

    private SignOnResponse screenOf(SignOnRequest request) {
        return controller.performSignOn(request).screen();
    }

    private ScreenMetadata metadataOf(SignOnRequest request) {
        return controller.performSignOn(request).screenMetadata();
    }

    /**
     * An outcome that paints nothing, for asserting the projection in isolation.
     *
     * <p>The canonical constructor enforces that a sign-on succeeds exactly when the task left through
     * an {@code XCTL} and that only an {@code XCTL} names a target, so a non-signed-on outcome must
     * carry {@link Termination#RETURN_TRANSID} and a blank target.
     */
    private static SignOnOutcome outcomeWithMessage(String eightyCharacterMessage,
            CursorField cursorField,
            boolean screenPainted) {
        return new SignOnOutcome(false,
                spaces(SignOnService.ROLE_LENGTH),
                spaces(SignOnService.NEXT_PROGRAM_LENGTH),
                eightyCharacterMessage,
                false,
                cursorField,
                screenPainted,
                false,
                false,
                Termination.RETURN_TRANSID,
                NavigationContext.empty(),
                ReceiveOutcome.NOT_PERFORMED,
                Optional.empty(),
                Optional.empty());
    }

    // =================================================================================================
    // The route.
    // =================================================================================================

    @Nested
    @DisplayName("The route - one transaction, one mapping")
    class TheRoute {

        @Test
        @DisplayName("the path is POST /api/signon, per CARDDEMO.CSD:378")
        void thePathIsTheOneTheCsdDefines() {
            Assertions.assertThat(SignOnController.SIGNON_PATH).isEqualTo("/api/signon");
        }

        @Test
        @DisplayName("exactly one request mapping exists, and it is a POST")
        void exactlyOneMappingAndItIsAPost() {
            Method[] mapped = Arrays.stream(SignOnController.class.getDeclaredMethods())
                    .filter(method -> method.getAnnotations().length > 0)
                    .filter(method -> Arrays.stream(method.getAnnotations())
                            .anyMatch(annotation -> annotation.annotationType()
                                    .getName().startsWith("org.springframework.web.bind.annotation")))
                    .toArray(Method[]::new);

            Assertions.assertThat(mapped)
                    .as("app/csd/CARDDEMO.CSD:378 defines one transaction against COSGN00C, so this "
                            + "controller publishes one route and no other")
                    .hasSize(1);
            PostMapping mapping = mapped[0].getAnnotation(PostMapping.class);
            Assertions.assertThat(mapping)
                    .as("the body carries a credential, so it must not travel in a URL or query string")
                    .isNotNull();
            Assertions.assertThat(mapping.path()).containsExactly(SignOnController.SIGNON_PATH);
            Assertions.assertThat(mapping.produces())
                    .containsExactly(MediaType.APPLICATION_JSON_VALUE);
            Assertions.assertThat(mapping.consumes())
                    .as("no consumes is declared, so a body-less POST binds rather than being refused "
                            + "with 415 before the program runs")
                    .isEmpty();
        }

        @Test
        @DisplayName("the two cursor labels are the DFHMDF labels of COSGN00.bms, not the xxxL items")
        void cursorLabelsAreDfhmdfLabels() {
            Assertions.assertThat(SignOnController.CURSOR_USERID)
                    .isEqualTo("USERID").doesNotEndWith("L");
            Assertions.assertThat(SignOnController.CURSOR_PASSWD)
                    .isEqualTo("PASSWD").doesNotEndWith("L");
        }

        @Test
        @DisplayName("the code page is named, never the platform default")
        void theCodePageIsNamed() {
            Assertions.assertThat(SignOnController.DEFAULT_WORKING_STORAGE_CHARSET)
                    .isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("the endpoint answers in the shared screen envelope")
        void theEndpointAnswersInTheSharedEnvelope() {
            ScreenResponse<SignOnResponse> envelope = controller.signOn(null);

            Assertions.assertThat(envelope).isNotNull();
            Assertions.assertThat(envelope.screen()).isInstanceOf(SignOnResponse.class);
            Assertions.assertThat(envelope.screenMetadata()).isNotNull();
        }
    }

    // =================================================================================================
    // Construction.
    // =================================================================================================

    @Nested
    @DisplayName("Construction - constructor injection only, every collaborator required")
    class Construction {

        @Test
        @DisplayName("every argument is required")
        void everyArgumentIsRequired() {
            SignOnService service = new SignOnService(repository);

            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(null, FIXED_CLOCK, APPLID, SYSID, CODE_PAGE));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(service, null, APPLID, SYSID, CODE_PAGE));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(service, FIXED_CLOCK, null, SYSID, CODE_PAGE));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(service, FIXED_CLOCK, APPLID, null, CODE_PAGE));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(service, FIXED_CLOCK, APPLID, SYSID, null));
        }

        @Test
        @DisplayName("the bean constructor applies the declared code page")
        void theBeanConstructorAppliesTheDeclaredCodePage() {
            SignOnController bean =
                    new SignOnController(new SignOnService(repository), FIXED_CLOCK, APPLID, SYSID);

            Assertions.assertThat(bean.signOn(null).screen().applId()).isEqualTo(APPLID);
        }

        @Test
        @DisplayName("the two EXEC CICS ASSIGN images are brought to PIC X(8) by the MOVE rule")
        void theAssignImagesAreBroughtToWidth() {
            SignOnResponse painted = controller.signOn(null).screen();

            Assertions.assertThat(painted.applId())
                    .isEqualTo("CICSAWSC").hasSize(SignOnResponse.APPLID_LENGTH);
            Assertions.assertThat(painted.sysId())
                    .as("a four-character SYSID is padded on the right, as a MOVE into PIC X(8) does")
                    .isEqualTo("AWSC    ").hasSize(SignOnResponse.SYSID_LENGTH);
        }

        @Test
        @DisplayName("an over-long identifier is truncated on the right, not the left")
        void anOverLongIdentifierIsTruncatedOnTheRight() {
            SignOnController wide = new SignOnController(new SignOnService(repository), FIXED_CLOCK,
                    "ABCDEFGHIJ", "KLMNOPQRST", CODE_PAGE);

            SignOnResponse painted = wide.signOn(null).screen();

            Assertions.assertThat(painted.applId()).isEqualTo("ABCDEFGH");
            Assertions.assertThat(painted.sysId()).isEqualTo("KLMNOPQR");
        }

        @Test
        @DisplayName("an unconfigured region reports spaces rather than an invented name")
        void anUnconfiguredRegionReportsSpaces() {
            SignOnController blank =
                    new SignOnController(new SignOnService(repository), FIXED_CLOCK, "", "", CODE_PAGE);

            SignOnResponse painted = blank.signOn(null).screen();

            Assertions.assertThat(painted.applId()).isBlank().hasSize(SignOnResponse.APPLID_LENGTH);
            Assertions.assertThat(painted.sysId()).isBlank().hasSize(SignOnResponse.SYSID_LENGTH);
        }
    }

    // =================================================================================================
    // The attention-identifier decode, COSGN00C:85.
    // =================================================================================================

    @Nested
    @DisplayName("The AID decode - token to the raw EIBAID byte COSGN00C:85 compares")
    class TheAidDecode {

        @ParameterizedTest
        @EnumSource(PfKeyResolver.AidKey.class)
        @DisplayName("every CCARD-AID token round-trips to the key it came from")
        void everyTokenRoundTrips(PfKeyResolver.AidKey key) {
            Assertions.assertThat(PfKeyResolver.resolve(controller.toEibAid(key.token())))
                    .as("decoding a token and resolving the byte again must be the identity")
                    .contains(key);
        }

        @Test
        @DisplayName("ENTER and PF3, the only two bytes this program distinguishes")
        void enterAndPf3DecodeToTheBytesTheProgramTests() {
            Assertions.assertThat(controller.toEibAid(PfKeyResolver.AidKey.ENTER.token()))
                    .isEqualTo(CicsAid.DFHENTER);
            Assertions.assertThat(controller.toEibAid(PfKeyResolver.AidKey.PFK03.token()))
                    .as("PFK03 resolves to the primary key of its folded pair, DFHPF3, because :88 "
                            + "tests WHEN DFHPF3 specifically")
                    .isEqualTo(CicsAid.DFHPF3);
        }

        @Test
        @DisplayName("an absent token yields a byte that is neither ENTER nor PF3")
        void anAbsentTokenLandsOnWhenOther() {
            byte decoded = controller.toEibAid(null);

            Assertions.assertThat(decoded).isEqualTo(CicsAid.DFHNULL);
            Assertions.assertThat(PfKeyResolver.resolve(decoded)).isEmpty();
            Assertions.assertThat(PfKeyResolver.isEnter(decoded))
                    .as("guessing ENTER would run the whole validate-and-read path unasked")
                    .isFalse();
            Assertions.assertThat(PfKeyResolver.isPf3(decoded)).isFalse();
        }

        @Test
        @DisplayName("an unrecognised token lands on the same arm as an absent one")
        void anUnrecognisedTokenLandsOnTheSameArm() {
            Assertions.assertThat(controller.toEibAid("ZZZZZ")).isEqualTo(CicsAid.DFHNULL);
            Assertions.assertThat(controller.toEibAid("")).isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("a short token is space-padded to PIC X(5) before it is matched")
        void aShortTokenIsPaddedToWidth() {
            Assertions.assertThat(PfKeyResolver.AidKey.PA1.token())
                    .as("CVCRD01Y declares PA1 with two trailing spaces")
                    .isEqualTo("PA1  ");
            Assertions.assertThat(controller.toEibAid("PA1")).isEqualTo(CicsAid.DFHPA1);
            Assertions.assertThat(controller.toEibAid("PA2")).isEqualTo(CicsAid.DFHPA2);
        }

        @Test
        @DisplayName("an over-long token is truncated on the right and so matches nothing")
        void anOverLongTokenMatchesNothing() {
            Assertions.assertThat(controller.toEibAid("ENTERPRISE"))
                    .as("truncated to 'ENTER' by the PIC X(5) rule, which is a real token")
                    .isEqualTo(CicsAid.DFHENTER);
            Assertions.assertThat(controller.toEibAid("PFK0399")).isEqualTo(CicsAid.DFHPF3);
        }

        @Test
        @DisplayName("toInput carries the communication area, the user id and the password verbatim")
        void toInputCarriesEverythingVerbatim() {
            SignOnRequest request = enterKey("Admin001", "pWd");

            SignOnInput input = controller.toInput(request);

            Assertions.assertThat(input.navigationContext()).isSameAs(request.navigationContext());
            Assertions.assertThat(input.isCommareaPresent()).isTrue();
            Assertions.assertThat(input.userId())
                    .as("no trim, no pad, no upper-case: :132 belongs to the service")
                    .isEqualTo("Admin001");
            Assertions.assertThat(input.password()).isEqualTo("pWd");
            Assertions.assertThat(input.eibAid()).isEqualTo(CicsAid.DFHENTER);
        }

        @Test
        @DisplayName("an absent communication area is carried through as EIBCALEN = 0")
        void anAbsentCommareaIsCarriedThrough() {
            SignOnRequest request = submitted(null, null, null);
            SignOnRequest coldStart = new SignOnRequest(request.trnName(), request.title01(),
                    request.curDate(), request.pgmName(), request.title02(), request.curTime(),
                    request.applId(), request.sysId(), null, null, request.errMsg(), null, null);

            SignOnInput input = controller.toInput(coldStart);

            Assertions.assertThat(input.navigationContext()).isNull();
            Assertions.assertThat(input.isCommareaPresent()).isFalse();
            Assertions.assertThat(input.userId())
                    .as("null is LOW-VALUES - a state :118 distinguishes from SPACES")
                    .isNull();
            Assertions.assertThat(input.password()).isNull();
        }

        @Test
        @DisplayName("a payload is required to build an invocation")
        void aPayloadIsRequired() {
            Assertions.assertThatNullPointerException().isThrownBy(() -> controller.toInput(null));
            Assertions.assertThatNullPointerException().isThrownBy(() -> controller.performSignOn(null));
        }
    }

    // =================================================================================================
    // The cold start, COSGN00C:80-83.
    // =================================================================================================

    @Nested
    @DisplayName("The cold start - IF EIBCALEN = 0 at COSGN00C:80, not the re-enter flag")
    class ColdStart {

        @Test
        @DisplayName("an absent body paints the sign-on screen and reads nothing")
        void anAbsentBodyPaintsAndReadsNothing() {
            ScreenResponse<SignOnResponse> envelope = controller.signOn(null);

            Assertions.assertThat(envelope.screen().errMsg()).isBlank();
            Assertions.assertThat(envelope.screen().role()).isBlank();
            Assertions.assertThat(envelope.screen().nextProgram()).isBlank();
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .as(":82 MOVE -1 TO USERIDL puts the cursor on the user id")
                    .isEqualTo(SignOnController.CURSOR_USERID);
            Assertions.assertThat(envelope.screenMetadata().resetAllOutputFields())
                    .as(":81 MOVE LOW-VALUES TO COSGN0AO clears every output field first")
                    .isTrue();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the screen is repainted, so the map to render next is COSGN00 / COSGN0A")
        void theColdStartNamesTheMapItPainted() {
            SignOnResponse painted = controller.signOn(null).screen();

            Assertions.assertThat(painted.nextMapset()).isEqualTo(SignOnResponse.MAPSET_NAME);
            Assertions.assertThat(painted.nextMap()).isEqualTo(SignOnResponse.MAP_NAME);
        }

        @Test
        @DisplayName("a payload whose communication area is null is the cold start too")
        void aNullCommareaInsideAPayloadIsAlsoTheColdStart() {
            SignOnRequest noCommarea = new SignOnRequest(spaces(SignOnRequest.TRNNAME_LENGTH),
                    spaces(SignOnRequest.TITLE01_LENGTH), spaces(SignOnRequest.CURDATE_LENGTH),
                    spaces(SignOnRequest.PGMNAME_LENGTH), spaces(SignOnRequest.TITLE02_LENGTH),
                    spaces(SignOnRequest.CURTIME_LENGTH), spaces(SignOnRequest.APPLID_LENGTH),
                    spaces(SignOnRequest.SYSID_LENGTH), "ADMIN001", STORED_PASSWORD,
                    spaces(SignOnRequest.ERRMSG_LENGTH), null,
                    PfKeyResolver.AidKey.ENTER.token());

            ScreenResponse<SignOnResponse> envelope = controller.performSignOn(noCommarea);

            Assertions.assertThat(envelope.screenMetadata().resetAllOutputFields()).isTrue();
            Assertions.assertThat(envelope.screen().role()).isBlank();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the re-enter flag is never consulted - COSGN00C does not read CDEMO-PGM-CONTEXT")
        void theReenterFlagIsNeverConsulted() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            SignOnRequest inEnterState = enterKey("ADMIN001", STORED_PASSWORD);
            Assertions.assertThat(inEnterState.inEnterState()).isTrue();
            Assertions.assertThat(inEnterState.inReenterState()).isFalse();

            SignOnResponse painted = screenOf(inEnterState);

            Assertions.assertThat(painted.nextProgram())
                    .as("a present communication area takes the ELSE at :84 whatever the context byte "
                            + "holds, so the sign-on proceeds")
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_ADMIN);
        }
    }

    // =================================================================================================
    // The program's paths, projected. COSGN00C:85-95, :108-140 and :209-257.
    // =================================================================================================

    @Nested
    @DisplayName("The paths - each of COSGN00C's exits, as a stateless client sees it")
    class ThePaths {

        @Test
        @DisplayName("an administrator signs on and is sent to COADM01C, per :230-232")
        void anAdministratorIsSentToTheAdminMenu() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            ScreenResponse<SignOnResponse> envelope =
                    controller.performSignOn(enterKey("ADMIN001", STORED_PASSWORD));

            Assertions.assertThat(envelope.screen().role())
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            Assertions.assertThat(envelope.screen().nextProgram()).isEqualTo("COADM01C");
            Assertions.assertThat(envelope.screen().errMsg()).isBlank();
            Assertions.assertThat(envelope.screen().nextMapset())
                    .as("XCTL transferred control, so the target program paints its own map")
                    .isBlank();
            Assertions.assertThat(envelope.screen().nextMap()).isBlank();
            Assertions.assertThat(envelope.screen().navigationContext().isAdmin()).isTrue();
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .as("no MOVE -1 occurs on the transfer path")
                    .isNull();
        }

        @Test
        @DisplayName("a regular user signs on and is sent to COMEN01C, per :235-237")
        void aRegularUserIsSentToTheMainMenu() {
            stubRead(ReadResult.found(storedUser("USER0001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER)));

            SignOnResponse painted = screenOf(enterKey("USER0001", STORED_PASSWORD));

            Assertions.assertThat(painted.role()).isEqualTo(NavigationContext.USER_TYPE_USER);
            Assertions.assertThat(painted.nextProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(SignOnResponse.isAdminRole(painted.role())).isFalse();
        }

        @Test
        @DisplayName("a wrong password repaints with the message and the cursor on the password, :241-245")
        void aWrongPasswordRepaintsWithTheCursorOnThePassword() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            ScreenResponse<SignOnResponse> envelope =
                    controller.performSignOn(enterKey("ADMIN001", "NOTRIGHT"));

            Assertions.assertThat(envelope.screen().errMsg())
                    .startsWith(SignOnService.MSG_WRONG_PASSWORD);
            Assertions.assertThat(envelope.screen().nextProgram()).isBlank();
            Assertions.assertThat(envelope.screen().role()).isBlank();
            Assertions.assertThat(envelope.screen().nextMapset()).isEqualTo(SignOnResponse.MAPSET_NAME);
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .isEqualTo(SignOnController.CURSOR_PASSWD);
            Assertions.assertThat(envelope.screenMetadata().resetAllOutputFields())
                    .as("only the cold start moves LOW-VALUES")
                    .isFalse();
        }

        @Test
        @DisplayName("an unknown user repaints with the cursor on the user id, :247-251")
        void anUnknownUserRepaintsWithTheCursorOnTheUserId() {
            stubRead(ReadResult.notFound());

            ScreenResponse<SignOnResponse> envelope =
                    controller.performSignOn(enterKey("NOBODY01", STORED_PASSWORD));

            Assertions.assertThat(envelope.screen().errMsg())
                    .startsWith(SignOnService.MSG_USER_NOT_FOUND);
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .isEqualTo(SignOnController.CURSOR_USERID);
        }

        @Test
        @DisplayName("any other read outcome repaints with 'Unable to verify the User ...', :252-256")
        void anyOtherReadOutcomeRepaintsWithUnableToVerify() {
            stubRead(ReadResult.endOfFile());

            ScreenResponse<SignOnResponse> envelope =
                    controller.performSignOn(enterKey("ADMIN001", STORED_PASSWORD));

            Assertions.assertThat(envelope.screen().errMsg())
                    .startsWith(SignOnService.MSG_UNABLE_TO_VERIFY);
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .isEqualTo(SignOnController.CURSOR_USERID);
        }

        @Test
        @DisplayName("a blank user id is answered with a message, not a 400, and reads nothing, :118-122")
        void aBlankUserIdIsAnsweredWithAMessage() {
            ScreenResponse<SignOnResponse> envelope = controller.performSignOn(
                    enterKey(spaces(SignOnRequest.USERID_LENGTH), STORED_PASSWORD));

            Assertions.assertThat(envelope.screen().errMsg())
                    .startsWith(SignOnService.MSG_ENTER_USER_ID);
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .isEqualTo(SignOnController.CURSOR_USERID);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a blank password is answered with a message and reads nothing, :123-127")
        void aBlankPasswordIsAnsweredWithAMessage() {
            ScreenResponse<SignOnResponse> envelope = controller.performSignOn(
                    enterKey("ADMIN001", spaces(SignOnRequest.PASSWD_LENGTH)));

            Assertions.assertThat(envelope.screen().errMsg())
                    .startsWith(SignOnService.MSG_ENTER_PASSWORD);
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .isEqualTo(SignOnController.CURSOR_PASSWD);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("PF3 ends the conversation - no map and no program, :88-90 and :164-172")
        void pf3EndsTheConversation() {
            ScreenResponse<SignOnResponse> envelope = controller.performSignOn(
                    submitted("ADMIN001", STORED_PASSWORD, PfKeyResolver.AidKey.PFK03.token()));

            Assertions.assertThat(envelope.screen().errMsg())
                    .as(":89 moves CCDA-MSG-THANK-YOU, the PIC X(50) literal of CSMSG01Y")
                    .startsWith(SystemMessages.CCDA_MSG_THANK_YOU);
            Assertions.assertThat(envelope.screen().errMsg())
                    .as("and NOT the similarly named PIC X(40) title literal of COTTL01Y")
                    .doesNotStartWith(ScreenTitles.CCDA_THANK_YOU);
            Assertions.assertThat(envelope.screen().nextMapset())
                    .as("a bare EXEC CICS RETURN carries no TRANSID, so there is no map to render")
                    .isBlank();
            Assertions.assertThat(envelope.screen().nextMap()).isBlank();
            Assertions.assertThat(envelope.screen().nextProgram())
                    .as("and no program either, which is what distinguishes this from a sign-on")
                    .isBlank();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the terminal PF3 answer is distinguishable from every repaint")
        void theTerminalAnswerIsDistinguishableFromARepaint() {
            SignOnResponse terminal = screenOf(
                    submitted("ADMIN001", STORED_PASSWORD, PfKeyResolver.AidKey.PFK03.token()));
            SignOnResponse repaint = screenOf(
                    submitted("ADMIN001", STORED_PASSWORD, PfKeyResolver.AidKey.PFK05.token()));

            Assertions.assertThat(terminal.nextMap()).isBlank();
            Assertions.assertThat(repaint.nextMap()).isEqualTo(SignOnResponse.MAP_NAME);
        }

        @Test
        @DisplayName("any other key repaints with the invalid-key message and no cursor move, :91-94")
        void anyOtherKeyRepaintsWithTheInvalidKeyMessage() {
            ScreenResponse<SignOnResponse> envelope = controller.performSignOn(
                    submitted("ADMIN001", STORED_PASSWORD, PfKeyResolver.AidKey.PFK05.token()));

            Assertions.assertThat(envelope.screen().errMsg())
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .as(":91-94 contains no MOVE -1 at all")
                    .isNull();
            Assertions.assertThat(envelope.screen().nextMapset()).isEqualTo(SignOnResponse.MAPSET_NAME);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("an unmapped key lands on exactly the same arm as an unrecognised one")
        void anUnmappedKeyLandsOnTheInvalidKeyArm() {
            SignOnResponse fromUnknownToken =
                    screenOf(submitted("ADMIN001", STORED_PASSWORD, "ZZZZZ"));
            SignOnResponse fromAbsentToken =
                    screenOf(submitted("ADMIN001", STORED_PASSWORD, null));

            Assertions.assertThat(fromUnknownToken.errMsg())
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .isEqualTo(fromAbsentToken.errMsg());
            verifyNoInteractions(repository);
        }
    }

    // =================================================================================================
    // The screen contract - eleven named DFHMDF fields at their exact declared widths.
    // =================================================================================================

    @Nested
    @DisplayName("The screen contract - COSGN00.CPY widths, POPULATE-HEADER-INFO at :177-204")
    class TheScreenContract {

        @Test
        @DisplayName("every projected field is exactly its declared width, CURTIMEO at nine")
        void everyFieldIsExactlyItsDeclaredWidth() {
            SignOnResponse painted = controller.signOn(null).screen();

            Assertions.assertThat(painted.trnName()).hasSize(SignOnResponse.TRNNAME_LENGTH);
            Assertions.assertThat(painted.title01()).hasSize(SignOnResponse.TITLE01_LENGTH);
            Assertions.assertThat(painted.curDate()).hasSize(SignOnResponse.CURDATE_LENGTH);
            Assertions.assertThat(painted.pgmName()).hasSize(SignOnResponse.PGMNAME_LENGTH);
            Assertions.assertThat(painted.title02()).hasSize(SignOnResponse.TITLE02_LENGTH);
            Assertions.assertThat(painted.curTime())
                    .as("CURTIMEO is PIC X(9) - nine, uniquely among the five user screens")
                    .hasSize(9)
                    .hasSize(SignOnResponse.CURTIME_LENGTH);
            Assertions.assertThat(painted.applId()).hasSize(SignOnResponse.APPLID_LENGTH);
            Assertions.assertThat(painted.sysId()).hasSize(SignOnResponse.SYSID_LENGTH);
            Assertions.assertThat(painted.userId()).hasSize(SignOnResponse.USERID_LENGTH);
            Assertions.assertThat(painted.errMsg())
                    .as("ERRMSGO is PIC X(78), narrower than the 80-byte WS-MESSAGE")
                    .hasSize(78)
                    .hasSize(SignOnResponse.ERRMSG_LENGTH);
            Assertions.assertThat(painted.role()).hasSize(SignOnResponse.ROLE_LENGTH);
            Assertions.assertThat(painted.nextProgram()).hasSize(SignOnResponse.NEXT_PROGRAM_LENGTH);
            Assertions.assertThat(painted.nextMapset()).hasSize(SignOnResponse.NEXT_MAPSET_LENGTH);
            Assertions.assertThat(painted.nextMap()).hasSize(SignOnResponse.NEXT_MAP_LENGTH);
        }

        @Test
        @DisplayName("the header carries the titles, the transaction, the program and the pinned clock")
        void theHeaderIsPopulatedFromPopulateHeaderInfo() {
            SignOnResponse painted = controller.signOn(null).screen();

            Assertions.assertThat(painted.trnName()).isEqualTo("CC00");
            Assertions.assertThat(painted.pgmName()).isEqualTo("COSGN00C");
            Assertions.assertThat(painted.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            Assertions.assertThat(painted.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            Assertions.assertThat(painted.curDate())
                    .as(":186-190 assemble MM/DD/YY, with YY from WS-CURDATE-YEAR(3:2)")
                    .isEqualTo(EXPECTED_CURDATE);
            Assertions.assertThat(painted.curTime())
                    .as(":192-196 assemble HH:MM:SS, right-padded into the nine-wide receiver")
                    .isEqualTo(EXPECTED_CURTIME);
        }

        @Test
        @DisplayName("the clock is injected - two calls on a fixed clock give the same header")
        void theClockIsInjectedRatherThanRead() {
            Assertions.assertThat(controller.signOn(null).screen().curTime())
                    .isEqualTo(controller.signOn(null).screen().curTime())
                    .isEqualTo(EXPECTED_CURTIME);
        }

        @Test
        @DisplayName("USERIDO stays at spaces - COSGN00C never writes it")
        void userIdOutputStaysAtSpaces() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            Assertions.assertThat(screenOf(enterKey("ADMIN001", STORED_PASSWORD)).userId())
                    .as("the program only ever reads USERIDI, at :118 and :132")
                    .isBlank()
                    .hasSize(SignOnResponse.USERID_LENGTH);
        }

        @Test
        @DisplayName("WS-MESSAGE PIC X(80) into ERRMSGO PIC X(78) truncates on the RIGHT, per :149")
        void theMessageIsTruncatedOnTheRight() {
            String eighty = "S".repeat(78) + "ZZ";
            Assertions.assertThat(eighty).hasSize(SignOnService.MESSAGE_LENGTH);

            SignOnResponse painted =
                    controller.toResponse(outcomeWithMessage(eighty, CursorField.NONE, true));

            Assertions.assertThat(painted.errMsg())
                    .hasSize(SignOnResponse.ERRMSG_LENGTH)
                    .isEqualTo("S".repeat(78))
                    .as("taking the trailing 78 instead of the leading 78 is the classic error")
                    .doesNotContain("Z");
        }

        @Test
        @DisplayName("a painted screen names its map; an unpainted one does not")
        void bothArmsOfTheMapProjection() {
            SignOnResponse painted =
                    controller.toResponse(outcomeWithMessage(spaces(80), CursorField.NONE, true));
            SignOnResponse unpainted =
                    controller.toResponse(outcomeWithMessage(spaces(80), CursorField.NONE, false));

            Assertions.assertThat(painted.nextMapset()).isEqualTo(SignOnResponse.MAPSET_NAME);
            Assertions.assertThat(painted.nextMap()).isEqualTo(SignOnResponse.MAP_NAME);
            Assertions.assertThat(unpainted.nextMapset())
                    .isBlank().hasSize(SignOnResponse.NEXT_MAPSET_LENGTH);
            Assertions.assertThat(unpainted.nextMap())
                    .isBlank().hasSize(SignOnResponse.NEXT_MAP_LENGTH);
        }

        @Test
        @DisplayName("an outcome is required to project either the screen or its metadata")
        void anOutcomeIsRequired() {
            Assertions.assertThatNullPointerException().isThrownBy(() -> controller.toResponse(null));
            Assertions.assertThatNullPointerException().isThrownBy(() -> controller.toMetadata(null));
        }
    }

    // =================================================================================================
    // The cursor request - MOVE -1 TO xxxL, carried as metadata and never as a payload field.
    // =================================================================================================

    @Nested
    @DisplayName("The cursor request - MOVE -1 TO USERIDL / PASSWDL")
    class TheCursorRequest {

        @ParameterizedTest
        @EnumSource(CursorField.class)
        @DisplayName("every cursor target projects to a DFHMDF label, or to null for NONE")
        void everyTargetProjects(CursorField target) {
            String label = SignOnController.cursorLabel(target);

            if (target == CursorField.NONE) {
                Assertions.assertThat(label)
                        .as("absence of a request is not a request for position zero")
                        .isNull();
            } else {
                Assertions.assertThat(label)
                        .isNotNull()
                        .isEqualTo(target.lengthItemName().orElseThrow()
                                .substring(0, target.lengthItemName().orElseThrow().length() - 1));
            }
        }

        @Test
        @DisplayName("the two labels are USERID and PASSWD, stripped of the xxxL suffix")
        void theTwoLabels() {
            Assertions.assertThat(SignOnController.cursorLabel(CursorField.USER_ID))
                    .isEqualTo(SignOnController.CURSOR_USERID);
            Assertions.assertThat(SignOnController.cursorLabel(CursorField.PASSWORD))
                    .isEqualTo(SignOnController.CURSOR_PASSWD);
        }

        @Test
        @DisplayName("a cursor target is required; NONE is the value for no MOVE -1")
        void aCursorTargetIsRequired() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> SignOnController.cursorLabel(null));
        }

        @Test
        @DisplayName("no colour is asserted, because COSGN00C moves none")
        void noColourIsAsserted() {
            ScreenMetadata metadata = metadataOf(enterKey(spaces(8), STORED_PASSWORD));

            Assertions.assertThat(metadata.messageColour())
                    .as("COSGN00C writes no xxxC item, does not copy CSSETATY, and has COPY DFHATTR "
                            + "commented out at :59 - a DFHRED here would be invented")
                    .isNull();
            Assertions.assertThat(metadata.fields())
                    .as("COSGN00 declares no per-field attribute quad this program writes to")
                    .isEmpty();
        }
    }

    // =================================================================================================
    // The credential never comes back.
    // =================================================================================================

    @Nested
    @DisplayName("The credential - accepted in the clear, never echoed, never logged")
    class TheCredential {

        @Test
        @DisplayName("the response record has no password component at all")
        void theResponseHasNoPasswordComponent() {
            RecordComponent[] components = SignOnResponse.class.getRecordComponents();

            Assertions.assertThat(components)
                    .extracting(RecordComponent::getName)
                    .as("PASSWDO is the one named DFHMDF field the response deliberately omits")
                    .doesNotContain("passwd", "password", "secUsrPwd");
            Assertions.assertThat(SignOnResponse.OMITTED_FIELD).isEqualTo("PASSWD");
            Assertions.assertThat(components).hasSize(15);
        }

        @Test
        @DisplayName("the serialised body of a successful sign-on does not contain the password")
        void theSerialisedBodyDoesNotContainThePassword() throws Exception {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            String body = new ObjectMapper()
                    .writeValueAsString(controller.performSignOn(enterKey("ADMIN001", STORED_PASSWORD)));

            Assertions.assertThat(body).doesNotContain(STORED_PASSWORD).doesNotContain("passwd");
        }

        @Test
        @DisplayName("the submitted password is not echoed even when it is wrong")
        void aWrongPasswordIsNotEchoedEither() throws Exception {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            String body = new ObjectMapper()
                    .writeValueAsString(controller.performSignOn(enterKey("ADMIN001", "HUNTER22")));

            Assertions.assertThat(body).doesNotContain("HUNTER22");
        }

        @Test
        @DisplayName("the controller declares no logger, so it cannot print the credential")
        void theControllerDeclaresNoLogger() {
            Assertions.assertThat(SignOnController.class.getDeclaredFields())
                    .noneMatch(field -> field.getType().getName().toLowerCase().contains("log"));
        }
    }

    // =================================================================================================
    // Statelessness - practice B9 and rule R6.
    // =================================================================================================

    @Nested
    @DisplayName("Statelessness - no session, no mutable state, nothing survives a call")
    class Statelessness {

        @Test
        @DisplayName("every field is final, and every static field is final")
        void everyFieldIsFinal() {
            Assertions.assertThat(SignOnController.class.getDeclaredFields())
                    .allSatisfy(field -> Assertions
                            .assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as("field %s must be final", field.getName())
                            .isTrue());
        }

        @Test
        @DisplayName("a message from one request does not leak into the next")
        void nothingLeaksBetweenSuccessiveRequests() {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnResponse rejected = screenOf(enterKey("ADMIN001", "NOTRIGHT"));
            SignOnResponse coldStart = controller.signOn(null).screen();

            Assertions.assertThat(rejected.errMsg()).isNotBlank();
            Assertions.assertThat(coldStart.errMsg())
                    .as("the second call must not see the first call's WS-MESSAGE")
                    .isBlank();
            Assertions.assertThat(coldStart.role()).isBlank();
            Assertions.assertThat(coldStart.nextProgram()).isBlank();
        }

        @Test
        @DisplayName("two identical requests produce equal, independent responses")
        void twoIdenticalRequestsProduceEqualIndependentResponses() {
            stubRead(ReadResult.found(storedUser("USER0001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER)));

            SignOnResponse first = screenOf(enterKey("USER0001", STORED_PASSWORD));
            SignOnResponse second = screenOf(enterKey("USER0001", STORED_PASSWORD));

            Assertions.assertThat(first).isEqualTo(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("the inbound communication area is never mutated")
        void theInboundCommareaIsNeverMutated() {
            stubRead(ReadResult.notFound());
            SignOnRequest request = enterKey("NOBODY01", STORED_PASSWORD);
            NavigationContext inbound = request.navigationContext();

            controller.performSignOn(request);

            Assertions.assertThat(request.navigationContext())
                    .as("NavigationContext is an immutable record, so the caller's copy is intact")
                    .isEqualTo(inbound);
        }
    }

    // =================================================================================================
    // The wire.
    // =================================================================================================

    @Nested
    @DisplayName("The wire - POST /api/signon over HTTP")
    class TheWire {

        private MockMvc mockMvc() {
            return MockMvcBuilders.standaloneSetup(controller).build();
        }

        @Test
        @DisplayName("a body-less POST answers the cold start with 200")
        void aBodyLessPostAnswersTheColdStart() throws Exception {
            mockMvc().perform(post(SignOnController.SIGNON_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnName").value("CC00"))
                    .andExpect(jsonPath("$.pgmName").value("COSGN00C"))
                    .andExpect(jsonPath("$.screenMetadata.cursorField")
                            .value(SignOnController.CURSOR_USERID))
                    .andExpect(jsonPath("$.screenMetadata.resetAllOutputFields").value(true));
        }

        @Test
        @DisplayName("an administrator's sign-on names COADM01C on the wire")
        void anAdministratorsSignOnNamesTheAdminMenu() throws Exception {
            stubRead(ReadResult.found(storedUser("ADMIN001", STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            String body = new ObjectMapper().writeValueAsString(enterKey("ADMIN001", STORED_PASSWORD));

            mockMvc().perform(post(SignOnController.SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value(NavigationContext.USER_TYPE_ADMIN))
                    .andExpect(jsonPath("$.nextProgram").value("COADM01C"));
        }

        @Test
        @DisplayName("a refused sign-on is still 200 - a message on the screen, not a 4xx")
        void aRefusedSignOnIsStillTwoHundred() throws Exception {
            String body = new ObjectMapper()
                    .writeValueAsString(enterKey(spaces(SignOnRequest.USERID_LENGTH), ""));

            mockMvc().perform(post(SignOnController.SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value(spaces(8)));
        }

        @Test
        @DisplayName("the screen stays flat and only screenMetadata joins it - no xxxL leaks")
        void theEnvelopeLeavesTheScreenFlatAndLeaksNoMetadataItem() throws Exception {
            String body = new ObjectMapper()
                    .writeValueAsString(controller.signOn(null));

            var keys = new ObjectMapper().readTree(body).fieldNames();
            var seen = new java.util.ArrayList<String>();
            keys.forEachRemaining(seen::add);

            Assertions.assertThat(seen)
                    .as("the fifteen screen members stay at the top level, plus the metadata envelope")
                    .containsExactlyInAnyOrder("trnName", "title01", "curDate", "pgmName", "title02",
                            "curTime", "applId", "sysId", "userId", "errMsg", "role", "nextProgram",
                            "nextMapset", "nextMap", "navigationContext", "screenMetadata");
            Assertions.assertThat(seen)
                    .as("no xxxL length item, xxxF/xxxA attribute item or xxxC/xxxP/xxxH/xxxV output "
                            + "attribute item may become a payload member (gate G9)")
                    .noneMatch(key -> key.equalsIgnoreCase("USERIDL")
                            || key.equalsIgnoreCase("PASSWDL")
                            || key.equalsIgnoreCase("ERRMSGL")
                            || key.equalsIgnoreCase("ERRMSGC")
                            || key.equalsIgnoreCase("ERRMSGF")
                            || key.equalsIgnoreCase("ERRMSGA"));
            Assertions.assertThat(body)
                    .as("messageColour is absent, so it is omitted entirely rather than sent as null")
                    .doesNotContain("messageColour");
        }
    }

    // =================================================================================================
    // Bean wiring.
    //
    // The module does not use @SpringBootTest - CardDemoApplicationTest records why: the DataSource is
    // configuration-bound to a site-specific mainframe driver, so a full refresh cannot succeed here.
    // That leaves the one runtime risk this controller actually carries untested by everything above:
    // the @Autowired constructor resolves two @Value placeholders, and a placeholder that cannot be
    // resolved fails the context at startup rather than at compile time. So a real Spring container is
    // refreshed here with just this bean and its two collaborators.
    // =================================================================================================

    @Nested
    @DisplayName("Bean wiring - the @Autowired constructor and its two @Value placeholders")
    class Wiring {

        private AnnotationConfigApplicationContext contextWith(Map<String, Object> properties) {
            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test-cics", properties));
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.registerBean(SecUserRepository.class, () -> repository);
            context.registerBean(SignOnService.class);
            context.registerBean(Clock.class, () -> FIXED_CLOCK);
            context.register(SignOnController.class);
            context.refresh();
            return context;
        }

        @Test
        @DisplayName("the bean wires with no CICS properties configured, defaulting to spaces")
        void theBeanWiresWithNoPropertiesConfigured() {
            try (AnnotationConfigApplicationContext context = contextWith(Map.of())) {
                SignOnController bean = context.getBean(SignOnController.class);

                SignOnResponse painted = bean.signOn(null).screen();
                Assertions.assertThat(painted.applId())
                        .as("an unresolvable placeholder would have failed the refresh above")
                        .isBlank()
                        .hasSize(SignOnResponse.APPLID_LENGTH);
                Assertions.assertThat(painted.sysId())
                        .isBlank().hasSize(SignOnResponse.SYSID_LENGTH);
            }
        }

        @Test
        @DisplayName("configured APPLID and SYSID reach the screen through the placeholders")
        void configuredIdentifiersReachTheScreen() {
            try (AnnotationConfigApplicationContext context = contextWith(Map.of(
                    SignOnController.APPLID_PROPERTY, "CICSPRDA",
                    SignOnController.SYSID_PROPERTY, "PRDA"))) {
                SignOnController bean = context.getBean(SignOnController.class);

                SignOnResponse painted = bean.signOn(null).screen();
                Assertions.assertThat(painted.applId()).isEqualTo("CICSPRDA");
                Assertions.assertThat(painted.sysId()).isEqualTo("PRDA    ");
            }
        }

        @Test
        @DisplayName("exactly one bean of this type exists, and it is a singleton")
        void exactlyOneSingletonBean() {
            try (AnnotationConfigApplicationContext context = contextWith(Map.of())) {
                Assertions.assertThat(context.getBeanNamesForType(SignOnController.class)).hasSize(1);
                Assertions.assertThat(context.getBean(SignOnController.class))
                        .as("one instance serves every concurrent request, which is safe only because "
                                + "the bean holds no mutable state")
                        .isSameAs(context.getBean(SignOnController.class));
            }
        }

        @Test
        @DisplayName("the property keys are the ones the class publishes")
        void thePropertyKeysArePublished() {
            Assertions.assertThat(SignOnController.APPLID_PROPERTY)
                    .isEqualTo("carddemo.cics.applid");
            Assertions.assertThat(SignOnController.SYSID_PROPERTY)
                    .isEqualTo("carddemo.cics.sysid");
        }
    }
}
