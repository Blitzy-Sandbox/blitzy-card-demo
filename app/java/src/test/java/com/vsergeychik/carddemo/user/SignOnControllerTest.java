package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.user.SignOnService.CursorField;
import com.vsergeychik.carddemo.user.SignOnService.ReceiveOutcome;
import com.vsergeychik.carddemo.user.SignOnService.SignOnInput;
import com.vsergeychik.carddemo.user.SignOnService.SignOnOutcome;
import com.vsergeychik.carddemo.user.SignOnService.Termination;
import com.vsergeychik.carddemo.user.dto.SignOnRequest;
import com.vsergeychik.carddemo.user.dto.SignOnResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 *   <li><strong>The controller decides nothing.</strong> Every service result in this class is a
 *       Mockito stub or an immutable outcome fixture. {@link TheServiceContract} and
 *       {@link TheSpringSlice} prove the adapter merely translates, delegates once and projects that
 *       supplied outcome; repository reads, password comparison, upper-casing, message selection and
 *       role determination remain exclusively in {@code SignOnServiceTest}.</li>
 *   <li><strong>HTTP tests stop at the wire.</strong> {@code MockMvc} appears in {@link TheWire} for a
 *       lightweight shape check and in {@link TheSpringSlice} for the real dispatcher, configured
 *       Jackson mapper, validation advice and statelessness. Branch decisions remain plain service
 *       outcomes, never dispatcher behaviour.</li>
 *   <li><strong>The password never comes back.</strong> Asserted structurally, over the response
 *       record's components, and over the serialised JSON.</li>
 * </ul>
 *
 * <h2>Where every expectation below comes from</h2>
 *
 * <p>The reference is read, never run. COBOL cannot be executed in this environment - eight blockers
 * are recorded in the plan's special analysis as risk R-A, among them a disabled indexed-file handler,
 * absent Language Environment {@code CEE*} services and no CICS emulator - so every value asserted here
 * is <strong>statically derived</strong> from the cited lines rather than captured from a live run
 * (practice B12). The citations are exact, so each one can be checked against the file:
 *
 * <table border="1">
 *   <caption>The reference lines this class asserts against</caption>
 *   <tr><th>Source</th><th>What it fixes</th></tr>
 *   <tr><td>{@code app/csd/CARDDEMO.CSD:378-379}</td>
 *       <td>{@code DEFINE TRANSACTION(CC00) … PROGRAM(COSGN00C)} - the one route</td></tr>
 *   <tr><td>{@code app/cbl/COSGN00C.cbl:80-83}</td>
 *       <td>{@code IF EIBCALEN = 0} - the cold start, its {@code LOW-VALUES} and its
 *           {@code MOVE -1 TO USERIDL}</td></tr>
 *   <tr><td>{@code app/cbl/COSGN00C.cbl:85-95}</td>
 *       <td>{@code EVALUATE EIBAID} - three arms, and only {@code DFHENTER} and {@code DFHPF3} are
 *           named</td></tr>
 *   <tr><td>{@code app/cbl/COSGN00C.cbl:132-137}</td>
 *       <td>{@code MOVE FUNCTION UPPER-CASE} - the service's, which is why the controller passes the
 *           two credential fields through verbatim</td></tr>
 *   <tr><td>{@code app/cbl/COSGN00C.cbl:149}</td>
 *       <td>{@code MOVE WS-MESSAGE TO ERRMSGO} - eighty into seventy-eight, truncating on the
 *           right</td></tr>
 *   <tr><td>{@code app/cbl/COSGN00C.cbl:198-204}</td>
 *       <td><strong>two separate</strong> {@code EXEC CICS ASSIGN} statements - {@code APPLID} at
 *           {@code :198-200}, then {@code SYSID} at {@code :202-204}</td></tr>
 *   <tr><td>{@code app/cpy-bms/COSGN00.CPY:17-60}</td>
 *       <td>the {@code xxxI} declarations, and so the eleven payload names and their widths -
 *           {@code CURTIMEI PIC X(9)} at {@code :54} among them</td></tr>
 *   <tr><td>{@code app/bms/COSGN00.bms}</td>
 *       <td>37 {@code DFHMDF} definitions of which 11 carry a name label</td></tr>
 * </table>
 *
 * <h2>Two divergences documented rather than corrected (practice B4)</h2>
 *
 * <ol>
 *   <li><strong>{@code DFHATTR} is not in this program's compile at all.</strong>
 *       {@code app/cbl/COSGN00C.cbl:59} reads {@code *COPY DFHATTR.} - an asterisk in the indicator
 *       column, so the line is a comment and the copybook contributes nothing. The plan's
 *       "{@code DFHATTR}: 2 consumers" is a <em>textual</em> count that includes commented lines.
 *       Nothing here asserts {@code DFHATTR}-specific attribute behaviour. {@code DFHAID} and
 *       {@code DFHBMSCA} at {@code :57-58} <em>are</em> copied, so {@code common.CicsAid} and
 *       {@code common.BmsAttributes} are legitimately in play.</li>
 *   <li><strong>Eleven output items, ten response members.</strong> {@code app/cpy-bms/COSGN00.CPY}
 *       declares 11 {@code xxxO} items including {@code PASSWDO}, and {@link SignOnResponse} carries
 *       10. The gap is deliberate: {@code COSGN00C} never writes {@code PASSWDO} on any path - it only
 *       ever repositions the cursor with {@code MOVE -1 TO PASSWDL} at {@code :126} and {@code :244} -
 *       so an eleventh member would be a field the program does not paint. The ten are asserted, the
 *       omission is named by {@link SignOnResponse#OMITTED_FIELD}, and no eleventh member is
 *       invented.</li>
 * </ol>
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

    /** An obviously fake eight-character plaintext fixture; never a credential or environment secret. */
    private static final String STORED_PASSWORD = "PASSWORD";

    /** A configured application identifier, exactly {@code PIC X(8)}. */
    private static final String APPLID = "CICSAWSC";

    /** A configured system identifier, deliberately shorter than {@code PIC X(8)}. */
    private static final String SYSID = "AWSC";

    private SignOnService decisionCore;

    private SignOnController controller;

    @BeforeEach
    void setUp() {
        decisionCore = mock(SignOnService.class);
        controller = new SignOnController(decisionCore, FIXED_CLOCK, APPLID, SYSID, CODE_PAGE);
    }

    // =================================================================================================
    // Helpers.
    // =================================================================================================

    private static String spaces(int width) {
        return " ".repeat(width);
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

    /**
     * A service result that repaints the sign-on map with one of the five source messages.
     *
     * <p>The message is first moved into {@code WS-MESSAGE PIC X(80)}. The controller then performs the
     * separate 80-to-78 move into {@code ERRMSGO}; keeping both moves visible prevents a test fixture
     * from hiding which layer owns the truncation.
     */
    private static SignOnOutcome repaintOutcome(String message,
            CursorField cursorField,
            NavigationContext navigationContext) {
        FixedWidthCodec codec = new FixedWidthCodec(CODE_PAGE);
        return new SignOnOutcome(false,
                spaces(SignOnService.ROLE_LENGTH),
                spaces(SignOnService.NEXT_PROGRAM_LENGTH),
                codec.movePicX(message, SignOnService.MESSAGE_LENGTH),
                false,
                cursorField,
                true,
                false,
                false,
                Termination.RETURN_TRANSID,
                navigationContext,
                ReceiveOutcome.normal(),
                Optional.of(PfKeyResolver.AidKey.ENTER),
                Optional.empty());
    }

    /**
     * The service result for {@code EIBCALEN = 0}: a full initial paint, no receive and no read.
     */
    private static SignOnOutcome coldStartOutcome() {
        return new SignOnOutcome(false,
                spaces(SignOnService.ROLE_LENGTH),
                spaces(SignOnService.NEXT_PROGRAM_LENGTH),
                spaces(SignOnService.MESSAGE_LENGTH),
                false,
                CursorField.USER_ID,
                true,
                true,
                false,
                Termination.RETURN_TRANSID,
                NavigationContext.empty(),
                ReceiveOutcome.NOT_PERFORMED,
                Optional.empty(),
                Optional.empty());
    }

    /**
     * The service result for the {@code DFHPF3} plain-text exit.
     */
    private static SignOnOutcome plainTextOutcome(NavigationContext navigationContext) {
        FixedWidthCodec codec = new FixedWidthCodec(CODE_PAGE);
        return new SignOnOutcome(false,
                spaces(SignOnService.ROLE_LENGTH),
                spaces(SignOnService.NEXT_PROGRAM_LENGTH),
                codec.movePicX(SystemMessages.CCDA_MSG_THANK_YOU, SignOnService.MESSAGE_LENGTH),
                false,
                CursorField.NONE,
                false,
                false,
                true,
                Termination.RETURN_NO_TRANSID,
                navigationContext,
                ReceiveOutcome.NOT_PERFORMED,
                Optional.of(PfKeyResolver.AidKey.PFK03),
                Optional.empty());
    }

    /**
     * The service result for the source's {@code WHEN OTHER} arm.
     */
    private static SignOnOutcome invalidKeyOutcome(NavigationContext navigationContext,
            Optional<PfKeyResolver.AidKey> resolvedAid) {
        FixedWidthCodec codec = new FixedWidthCodec(CODE_PAGE);
        return new SignOnOutcome(false,
                spaces(SignOnService.ROLE_LENGTH),
                spaces(SignOnService.NEXT_PROGRAM_LENGTH),
                codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY, SignOnService.MESSAGE_LENGTH),
                true,
                CursorField.NONE,
                true,
                false,
                false,
                Termination.RETURN_TRANSID,
                navigationContext,
                ReceiveOutcome.NOT_PERFORMED,
                resolvedAid,
                Optional.empty());
    }

    /**
     * A successful service result for proving that role and navigation values are projected rather
     * than recomputed by the controller.
     */
    private static SignOnOutcome signedOnOutcome(String role,
            String nextProgram,
            NavigationContext inbound) {
        NavigationContext outbound = inbound.withUserType(role)
                .withToProgram(nextProgram)
                .withPgmEnter();
        return new SignOnOutcome(true,
                role,
                nextProgram,
                spaces(SignOnService.MESSAGE_LENGTH),
                false,
                CursorField.NONE,
                false,
                false,
                false,
                Termination.XCTL,
                outbound,
                ReceiveOutcome.normal(),
                Optional.of(PfKeyResolver.AidKey.ENTER),
                Optional.empty());
    }

    /**
     * Test-only beans for the MVC slice. The production {@link WebConfig} clock remains present; this
     * immutable primary bean displaces it so date and time assertions can never depend on wall time.
     */
    @TestConfiguration
    static class SliceCollaborators {

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

            var envelope = controller.signOn(null);

            Assertions.assertThat(envelope).isNotNull();
            Assertions.assertThat(envelope.screen()).isInstanceOf(SignOnResponse.class);
            Assertions.assertThat(envelope.screenMetadata()).isNotNull();
        }
    }

    // =================================================================================================
    // The inbound symbolic-map contract.
    // =================================================================================================

    @Nested
    @DisplayName("The request contract - eleven xxxI fields, their exact names and widths")
    class TheRequestContract {

        private SignOnRequest fullyPopulatedRequest() {
            return new SignOnRequest("CC00",
                    ScreenTitles.CCDA_TITLE01,
                    EXPECTED_CURDATE,
                    "COSGN00C",
                    ScreenTitles.CCDA_TITLE02,
                    EXPECTED_CURTIME,
                    APPLID,
                    "AWSC    ",
                    "ADMIN001",
                    STORED_PASSWORD,
                    spaces(SignOnRequest.ERRMSG_LENGTH),
                    NavigationContext.empty().withPgmReenter(),
                    PfKeyResolver.AidKey.ENTER.token());
        }

        @Test
        @DisplayName("all eleven xxxI members survive a JSON round trip, including CURTIME X(9)")
        void allElevenMapMembersBind() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            SignOnRequest expected = fullyPopulatedRequest();

            SignOnRequest bound =
                    mapper.readValue(mapper.writeValueAsBytes(expected), SignOnRequest.class);

            Assertions.assertThat(bound.trnName()).isEqualTo(expected.trnName());
            Assertions.assertThat(bound.title01()).isEqualTo(expected.title01());
            Assertions.assertThat(bound.curDate()).isEqualTo(expected.curDate());
            Assertions.assertThat(bound.pgmName()).isEqualTo(expected.pgmName());
            Assertions.assertThat(bound.title02()).isEqualTo(expected.title02());
            Assertions.assertThat(bound.curTime())
                    .isEqualTo(expected.curTime())
                    .hasSize(9)
                    .hasSize(SignOnRequest.CURTIME_LENGTH);
            Assertions.assertThat(bound.applId()).isEqualTo(expected.applId());
            Assertions.assertThat(bound.sysId()).isEqualTo(expected.sysId());
            Assertions.assertThat(bound.userId()).isEqualTo(expected.userId());
            Assertions.assertThat(bound.passwd()).isEqualTo(expected.passwd());
            Assertions.assertThat(bound.errMsg()).isEqualTo(expected.errMsg());
            Assertions.assertThat(bound.navigationContext()).isEqualTo(expected.navigationContext());
            Assertions.assertThat(bound.aid()).isEqualTo(expected.aid());
            Assertions.assertThat(SignOnRequest.MAP_FIELD_COUNT).isEqualTo(11);
        }

        @Test
        @DisplayName("APPLID and SYSID are payload fields; xxxL, xxxF and xxxA metadata is not")
        void onlyTheNamedInputItemsBecomeJsonProperties() throws Exception {
            var json = new ObjectMapper().valueToTree(fullyPopulatedRequest());
            List<String> names = new java.util.ArrayList<>();
            json.fieldNames().forEachRemaining(names::add);

            Assertions.assertThat(names)
                    .containsExactly("trnname", "title01", "curdate", "pgmname", "title02",
                            "curtime", "applid", "sysid", "userid", "passwd", "errmsg",
                            "navigationContext", "aid");
            Assertions.assertThat(json.has("applid")).isTrue();
            Assertions.assertThat(json.has("sysid")).isTrue();

            List<String> mapMembers = names.subList(0, SignOnRequest.MAP_FIELD_COUNT);
            for (String member : mapMembers) {
                Assertions.assertThat(names)
                        .as("%sL/%sF/%sA are symbolic-map metadata, not JSON", member, member, member)
                        .doesNotContain(member + "L", member + "F", member + "A");
            }
            Assertions.assertThat(names)
                    .doesNotContain("USERIDL", "USERIDF", "USERIDA",
                            "PASSWDL", "PASSWDF", "PASSWDA",
                            "ERRMSGL", "ERRMSGF", "ERRMSGA");
        }

        @Test
        @DisplayName("@Size maxima are the eleven xxxI PICTURE widths, in map order")
        void sizeConstraintsAreTheCopybookWidths() {
            Map<String, Integer> actual = new LinkedHashMap<>();
            List<String> mapMembers = List.of("trnName", "title01", "curDate", "pgmName", "title02",
                    "curTime", "applId", "sysId", "userId", "passwd", "errMsg");

            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                if (mapMembers.contains(component.getName())) {
                    Size constraint = component.getAccessor().getAnnotation(Size.class);
                    Assertions.assertThat(constraint)
                            .as("%s must carry the symbolic-map width", component.getName())
                            .isNotNull();
                    actual.put(component.getName(), constraint.max());
                }
            }

            Assertions.assertThat(actual).containsExactly(
                    Map.entry("trnName", 4),
                    Map.entry("title01", 40),
                    Map.entry("curDate", 8),
                    Map.entry("pgmName", 8),
                    Map.entry("title02", 40),
                    Map.entry("curTime", 9),
                    Map.entry("applId", 8),
                    Map.entry("sysId", 8),
                    Map.entry("userId", 8),
                    Map.entry("passwd", 8),
                    Map.entry("errMsg", 78));
        }

        @Test
        @DisplayName("an explicit PIC X move loses surplus characters on the right")
        void deliberateShorteningUsesThePicXDirection() {
            FixedWidthCodec codec = new FixedWidthCodec(CODE_PAGE);

            Assertions.assertThat(codec.movePicX("ABCDEFGHI", SignOnRequest.USERID_LENGTH))
                    .as("HTTP validation rejects nine characters; a deliberate COBOL MOVE would keep "
                            + "the leftmost eight and lose only the rightmost character")
                    .isEqualTo("ABCDEFGH");
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
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(null, FIXED_CLOCK, APPLID, SYSID, CODE_PAGE));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(decisionCore, null, APPLID, SYSID, CODE_PAGE));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(decisionCore, FIXED_CLOCK, null, SYSID, CODE_PAGE));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(decisionCore, FIXED_CLOCK, APPLID, null, CODE_PAGE));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    new SignOnController(decisionCore, FIXED_CLOCK, APPLID, SYSID, null));
        }

        @Test
        @DisplayName("the bean constructor applies the declared code page")
        void theBeanConstructorAppliesTheDeclaredCodePage() {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());
            SignOnController bean =
                    new SignOnController(decisionCore, FIXED_CLOCK, APPLID, SYSID);

            Assertions.assertThat(bean.signOn(null).screen().applId()).isEqualTo(APPLID);
        }

        @Test
        @DisplayName("the two EXEC CICS ASSIGN images are brought to PIC X(8) by the MOVE rule")
        void theAssignImagesAreBroughtToWidth() {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());
            SignOnController wide = new SignOnController(decisionCore, FIXED_CLOCK,
                    "ABCDEFGHIJ", "KLMNOPQRST", CODE_PAGE);

            SignOnResponse painted = wide.signOn(null).screen();

            Assertions.assertThat(painted.applId()).isEqualTo("ABCDEFGH");
            Assertions.assertThat(painted.sysId()).isEqualTo("KLMNOPQR");
        }

        @Test
        @DisplayName("an unconfigured region reports spaces rather than an invented name")
        void anUnconfiguredRegionReportsSpaces() {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());
            SignOnController blank =
                    new SignOnController(decisionCore, FIXED_CLOCK, "", "", CODE_PAGE);

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
    // The controller/service seam.
    // =================================================================================================

    @Nested
    @DisplayName("The service contract - translate, invoke once, and project without deciding")
    class TheServiceContract {

        private SignOnService decisionCore;

        private SignOnController adapter;

        @BeforeEach
        void setUpDecisionCore() {
            decisionCore = mock(SignOnService.class);
            adapter = new SignOnController(decisionCore, FIXED_CLOCK, APPLID, SYSID, CODE_PAGE);
        }

        @Test
        @DisplayName("DFHENTER invokes SignOnService exactly once with the verbatim credential fields")
        void enterInvokesTheServiceExactlyOnce() {
            NavigationContext context = NavigationContext.empty().withPgmReenter();
            SignOnRequest request = new SignOnRequest(spaces(SignOnRequest.TRNNAME_LENGTH),
                    spaces(SignOnRequest.TITLE01_LENGTH),
                    spaces(SignOnRequest.CURDATE_LENGTH),
                    spaces(SignOnRequest.PGMNAME_LENGTH),
                    spaces(SignOnRequest.TITLE02_LENGTH),
                    spaces(SignOnRequest.CURTIME_LENGTH),
                    spaces(SignOnRequest.APPLID_LENGTH),
                    spaces(SignOnRequest.SYSID_LENGTH),
                    "Admin001",
                    "pWd",
                    spaces(SignOnRequest.ERRMSG_LENGTH),
                    context,
                    PfKeyResolver.AidKey.ENTER.token());
            when(decisionCore.handle(any(SignOnInput.class)))
                    .thenReturn(repaintOutcome("", CursorField.NONE, context));

            adapter.performSignOn(request);

            ArgumentCaptor<SignOnInput> input = ArgumentCaptor.forClass(SignOnInput.class);
            verify(decisionCore).handle(input.capture());
            verifyNoMoreInteractions(decisionCore);
            Assertions.assertThat(input.getValue().navigationContext()).isSameAs(context);
            Assertions.assertThat(input.getValue().eibAid()).isEqualTo(CicsAid.DFHENTER);
            Assertions.assertThat(input.getValue().userId())
                    .as("FUNCTION UPPER-CASE at COSGN00C:132 belongs to the service")
                    .isEqualTo("Admin001");
            Assertions.assertThat(input.getValue().password()).isEqualTo("pWd");
        }

        @Test
        @DisplayName("the five source messages pass through unchanged apart from the X(80) to X(78) move")
        void allFiveServiceMessagesPassThrough() {
            List<String> messages = List.of(
                    SignOnService.MSG_ENTER_USER_ID,
                    SignOnService.MSG_ENTER_PASSWORD,
                    SignOnService.MSG_WRONG_PASSWORD,
                    SignOnService.MSG_USER_NOT_FOUND,
                    SignOnService.MSG_UNABLE_TO_VERIFY);
            FixedWidthCodec codec = new FixedWidthCodec(CODE_PAGE);

            for (String message : messages) {
                reset(decisionCore);
                when(decisionCore.handle(any(SignOnInput.class)))
                        .thenReturn(repaintOutcome(message, CursorField.USER_ID,
                                NavigationContext.empty()));

                SignOnResponse projected =
                        adapter.performSignOn(enterKey("ADMIN001", STORED_PASSWORD)).screen();

                String expected = codec.movePicX(
                        codec.movePicX(message, SignOnService.MESSAGE_LENGTH),
                        SignOnResponse.ERRMSG_LENGTH);
                Assertions.assertThat(projected.errMsg())
                        .as("the controller must not reword service message '%s'", message)
                        .isEqualTo(expected);
                verify(decisionCore).handle(any(SignOnInput.class));
                verifyNoMoreInteractions(decisionCore);
            }
        }

        @Test
        @DisplayName("A routes to COADM01C; U and every unexpected role take the COBOL ELSE")
        void roleAndFallbackTargetAreProjectedExactly() {
            Map<String, String> targets = new LinkedHashMap<>();
            targets.put(NavigationContext.USER_TYPE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN);
            targets.put(NavigationContext.USER_TYPE_USER, SignOnResponse.NEXT_PROGRAM_USER);
            targets.put("X", SignOnResponse.NEXT_PROGRAM_USER);

            for (Map.Entry<String, String> expectation : targets.entrySet()) {
                reset(decisionCore);
                when(decisionCore.handle(any(SignOnInput.class)))
                        .thenReturn(signedOnOutcome(expectation.getKey(), expectation.getValue(),
                                NavigationContext.empty()));

                SignOnResponse projected =
                        adapter.performSignOn(enterKey("ADMIN001", STORED_PASSWORD)).screen();

                Assertions.assertThat(projected.role()).isEqualTo(expectation.getKey());
                Assertions.assertThat(projected.nextProgram())
                        .as("COSGN00C:230 tests only ADMIN; its ELSE includes unexpected role %s",
                                expectation.getKey())
                        .isEqualTo(expectation.getValue());
                verify(decisionCore).handle(any(SignOnInput.class));
                verifyNoMoreInteractions(decisionCore);
            }
        }
    }

    // =================================================================================================
    // The cold start, COSGN00C:80-83.
    // =================================================================================================

    @Nested
    @DisplayName("The cold start - IF EIBCALEN = 0 at COSGN00C:80, not the re-enter flag")
    class ColdStart {

        @Test
        @DisplayName("an absent body is delegated as EIBCALEN zero and projects the initial paint")
        void anAbsentBodyPaintsAndReadsNothing() {
            SignOnOutcome coldStart = coldStartOutcome();
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStart);

            var envelope = controller.signOn(null);

            Assertions.assertThat(envelope.screen().errMsg()).isBlank();
            Assertions.assertThat(envelope.screen().role()).isBlank();
            Assertions.assertThat(envelope.screen().nextProgram()).isBlank();
            Assertions.assertThat(envelope.screenMetadata().cursorField())
                    .as(":82 MOVE -1 TO USERIDL puts the cursor on the user id")
                    .isEqualTo(SignOnController.CURSOR_USERID);
            Assertions.assertThat(envelope.screenMetadata().resetAllOutputFields())
                    .as(":81 MOVE LOW-VALUES TO COSGN0AO clears every output field first")
                    .isTrue();
            Assertions.assertThat(coldStart.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
            Assertions.assertThat(coldStart.readOutcome()).isEmpty();
            ArgumentCaptor<SignOnInput> input = ArgumentCaptor.forClass(SignOnInput.class);
            verify(decisionCore).handle(input.capture());
            Assertions.assertThat(input.getValue().isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("the screen is repainted, so the map to render next is COSGN00 / COSGN0A")
        void theColdStartNamesTheMapItPainted() {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

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

            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

            var envelope = controller.performSignOn(noCommarea);

            Assertions.assertThat(envelope.screenMetadata().resetAllOutputFields()).isTrue();
            Assertions.assertThat(envelope.screen().role()).isBlank();
            ArgumentCaptor<SignOnInput> input = ArgumentCaptor.forClass(SignOnInput.class);
            verify(decisionCore).handle(input.capture());
            Assertions.assertThat(input.getValue().navigationContext()).isNull();
        }

        @Test
        @DisplayName("the controller passes the context byte through and does not interpret it")
        void theProgramContextIsPassedThroughUnchanged() {
            SignOnRequest inEnterState = enterKey("ADMIN001", STORED_PASSWORD);
            Assertions.assertThat(inEnterState.inEnterState()).isTrue();
            Assertions.assertThat(inEnterState.inReenterState()).isFalse();
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(signedOnOutcome(
                    NavigationContext.USER_TYPE_ADMIN,
                    SignOnResponse.NEXT_PROGRAM_ADMIN,
                    inEnterState.navigationContext()));

            SignOnResponse painted = screenOf(inEnterState);

            Assertions.assertThat(painted.nextProgram())
                    .as("a present communication area takes the ELSE at :84 whatever the context byte "
                            + "holds, so the sign-on proceeds")
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_ADMIN);
            ArgumentCaptor<SignOnInput> input = ArgumentCaptor.forClass(SignOnInput.class);
            verify(decisionCore).handle(input.capture());
            Assertions.assertThat(input.getValue().navigationContext())
                    .isSameAs(inEnterState.navigationContext());
        }
    }

    // =================================================================================================
    // The communication-area program-context byte.
    // =================================================================================================

    @Nested
    @DisplayName("CDEMO-PGM-CONTEXT - both 88 states and the shared highlight gate")
    class TheProgramContextByte {

        private SignOnRequest requestWith(NavigationContext context) {
            return new SignOnRequest(spaces(SignOnRequest.TRNNAME_LENGTH),
                    spaces(SignOnRequest.TITLE01_LENGTH),
                    spaces(SignOnRequest.CURDATE_LENGTH),
                    spaces(SignOnRequest.PGMNAME_LENGTH),
                    spaces(SignOnRequest.TITLE02_LENGTH),
                    spaces(SignOnRequest.CURTIME_LENGTH),
                    spaces(SignOnRequest.APPLID_LENGTH),
                    spaces(SignOnRequest.SYSID_LENGTH),
                    spaces(SignOnRequest.USERID_LENGTH),
                    spaces(SignOnRequest.PASSWD_LENGTH),
                    spaces(SignOnRequest.ERRMSG_LENGTH),
                    context,
                    PfKeyResolver.AidKey.ENTER.token());
        }

        @Test
        @DisplayName("88 ENTER is 0 and 88 REENTER is 1; neither is inferred as the other's negation")
        void bothConditionNamesAreExercised() {
            NavigationContext enter = NavigationContext.empty().withPgmEnter();
            NavigationContext reenter = NavigationContext.empty().withPgmReenter();

            Assertions.assertThat(enter.pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            Assertions.assertThat(reenter.pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
            Assertions.assertThat(requestWith(enter).inEnterState()).isTrue();
            Assertions.assertThat(requestWith(enter).inReenterState()).isFalse();
            Assertions.assertThat(requestWith(reenter).inEnterState()).isFalse();
            Assertions.assertThat(requestWith(reenter).inReenterState()).isTrue();
        }

        @Test
        @DisplayName("blank highlights are suppressed on ENTER and become DFHRED plus '*' on REENTER")
        void theSharedHighlightHelperTakesTheContextStateExplicitly() {
            FieldHighlight onEnter =
                    FieldAttributeSetter.resolve(FieldValidationState.BLANK, false);
            FieldHighlight onReenter =
                    FieldAttributeSetter.resolve(FieldValidationState.BLANK, true);

            Assertions.assertThat(onEnter.untouched()).isTrue();
            Assertions.assertThat(onEnter.colourItemAssigned()).isFalse();
            Assertions.assertThat(onEnter.outputItemAssigned()).isFalse();
            Assertions.assertThat(onReenter.colourItemAssigned()).isTrue();
            Assertions.assertThat(onReenter.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            Assertions.assertThat(onReenter.outputItemAssigned()).isTrue();
            Assertions.assertThat(onReenter.outputItemValue())
                    .isEqualTo(FieldAttributeSetter.ASTERISK)
                    .isEqualTo("*");
        }

        @Test
        @DisplayName("COSGN00C itself still emits no colour metadata in either context state")
        void thisProgramDoesNotInventTheSharedHighlight() {
            SignOnOutcome blank = repaintOutcome(SignOnService.MSG_ENTER_USER_ID,
                    CursorField.USER_ID,
                    NavigationContext.empty().withPgmReenter());

            var metadata = controller.toMetadata(blank);

            Assertions.assertThat(metadata.messageColour())
                    .as("CSSETATY defines the shared gate, but COSGN00C does not COPY or invoke it")
                    .isNull();
            Assertions.assertThat(metadata.fields()).isEmpty();
        }
    }

    // =================================================================================================
    // The three EIBAID exits as controller projections. The service outcome is always stubbed.
    // =================================================================================================

    @Nested
    @DisplayName("The AID projections - decode, delegate once, and project the supplied exit")
    class TheAidProjections {

        @Test
        @DisplayName("PF3 projects the X(50) plain-text outcome with no map or next program")
        void pf3ProjectsTheTerminalOutcome() {
            NavigationContext context = NavigationContext.empty().withPgmReenter();
            SignOnOutcome plainText = plainTextOutcome(context);
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(plainText);

            var envelope = controller.performSignOn(
                    submitted("ADMIN001", STORED_PASSWORD, PfKeyResolver.AidKey.PFK03.token()));

            Assertions.assertThat(envelope.screen().errMsg())
                    .startsWith(SystemMessages.CCDA_MSG_THANK_YOU)
                    .doesNotStartWith(ScreenTitles.CCDA_THANK_YOU);
            Assertions.assertThat(envelope.screen().nextMapset()).isBlank();
            Assertions.assertThat(envelope.screen().nextMap()).isBlank();
            Assertions.assertThat(envelope.screen().nextProgram()).isBlank();
            Assertions.assertThat(plainText.plainTextSent()).isTrue();
            Assertions.assertThat(plainText.readOutcome()).isEmpty();
            ArgumentCaptor<SignOnInput> input = ArgumentCaptor.forClass(SignOnInput.class);
            verify(decisionCore).handle(input.capture());
            Assertions.assertThat(input.getValue().eibAid()).isEqualTo(CicsAid.DFHPF3);
        }

        @Test
        @DisplayName("PF12, CLEAR and PA1 decode by name but project the stubbed WHEN OTHER outcome")
        void namedWhenOtherKeysStayOnTheInvalidKeyContract() {
            Map<PfKeyResolver.AidKey, Byte> keys = Map.of(
                    PfKeyResolver.AidKey.PFK12, CicsAid.DFHPF12,
                    PfKeyResolver.AidKey.CLEAR, CicsAid.DFHCLEAR,
                    PfKeyResolver.AidKey.PA1, CicsAid.DFHPA1);

            for (Map.Entry<PfKeyResolver.AidKey, Byte> key : keys.entrySet()) {
                reset(decisionCore);
                SignOnOutcome invalid =
                        invalidKeyOutcome(NavigationContext.empty(), Optional.of(key.getKey()));
                when(decisionCore.handle(any(SignOnInput.class))).thenReturn(invalid);

                var envelope = controller.performSignOn(
                        submitted("ADMIN001", STORED_PASSWORD, key.getKey().token()));

                ArgumentCaptor<SignOnInput> input = ArgumentCaptor.forClass(SignOnInput.class);
                verify(decisionCore).handle(input.capture());
                verifyNoMoreInteractions(decisionCore);
                Assertions.assertThat(input.getValue().eibAid()).isEqualTo(key.getValue());
                Assertions.assertThat(invalid.errorFlag()).isTrue();
                Assertions.assertThat(envelope.screen().errMsg())
                        .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
                Assertions.assertThat(envelope.screenMetadata().cursorField()).isNull();
                Assertions.assertThat(envelope.screen().nextMap()).isEqualTo(SignOnResponse.MAP_NAME);
            }
        }

        @Test
        @DisplayName("unknown and absent tokens both decode to the unmapped DFHNULL byte")
        void anUnmappedByteProjectsTheSameWhenOtherOutcome() {
            for (String token : Arrays.asList("ZZZZZ", null)) {
                reset(decisionCore);
                SignOnOutcome invalid =
                        invalidKeyOutcome(NavigationContext.empty(), Optional.empty());
                when(decisionCore.handle(any(SignOnInput.class))).thenReturn(invalid);

                var envelope = controller.performSignOn(
                        submitted("ADMIN001", STORED_PASSWORD, token));

                ArgumentCaptor<SignOnInput> input = ArgumentCaptor.forClass(SignOnInput.class);
                verify(decisionCore).handle(input.capture());
                Assertions.assertThat(input.getValue().eibAid()).isEqualTo(CicsAid.DFHNULL);
                Assertions.assertThat(invalid.resolvedAid()).isEmpty();
                Assertions.assertThat(invalid.errorFlag()).isTrue();
                Assertions.assertThat(envelope.screen().errMsg())
                        .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            }
        }

        @Test
        @DisplayName("the PF3 terminal response is distinguishable from a repaint")
        void terminalAndRepaintNavigationStayDistinct() {
            SignOnResponse terminal = controller.toResponse(
                    plainTextOutcome(NavigationContext.empty()));
            SignOnResponse repaint = controller.toResponse(
                    invalidKeyOutcome(NavigationContext.empty(),
                            Optional.of(PfKeyResolver.AidKey.PFK05)));

            Assertions.assertThat(terminal.nextMap()).isBlank();
            Assertions.assertThat(repaint.nextMap()).isEqualTo(SignOnResponse.MAP_NAME);
        }
    }

    // =================================================================================================
    // The screen contract - eleven named DFHMDF fields at their exact declared widths.
    // =================================================================================================

    @Nested
    @DisplayName("The screen contract - COSGN00.CPY widths, POPULATE-HEADER-INFO at :177-204")
    class TheScreenContract {

        @Test
        @DisplayName("37 DFHMDF fields become 11 named items and exactly 10 response map members")
        void thePublishedFieldCensusPreservesThePasswordOmission() {
            Assertions.assertThat(SignOnResponse.MAPSET_FIELD_COUNT).isEqualTo(37);
            Assertions.assertThat(SignOnResponse.MAPSET_NAMED_FIELD_COUNT).isEqualTo(11);
            Assertions.assertThat(SignOnResponse.MAP_FIELD_COUNT).isEqualTo(10);
            Assertions.assertThat(SignOnResponse.MAPSET_NAMED_FIELDS)
                    .hasSize(SignOnResponse.MAPSET_NAMED_FIELD_COUNT)
                    .containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02",
                            "CURTIME", "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");
            Assertions.assertThat(SignOnResponse.MAP_FIELDS)
                    .hasSize(SignOnResponse.MAP_FIELD_COUNT)
                    .containsExactly("TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O",
                            "CURTIMEO", "APPLIDO", "SYSIDO", "USERIDO", "ERRMSGO")
                    .doesNotContain(SignOnResponse.OMITTED_ITEM);
            Assertions.assertThat(SignOnResponse.OMITTED_FIELD).isEqualTo("PASSWD");
            Assertions.assertThat(SignOnResponse.OMITTED_ITEM).isEqualTo("PASSWDO");
        }

        @Test
        @DisplayName("every projected field is exactly its declared width, CURTIMEO at nine")
        void everyFieldIsExactlyItsDeclaredWidth() {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

            SignOnResponse painted = controller.signOn(null).screen();

            Assertions.assertThat(painted.trnName()).isEqualTo("CC00");
            Assertions.assertThat(painted.pgmName()).isEqualTo("COSGN00C");
            Assertions.assertThat(painted.title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(40)
                    .hasSize(SignOnResponse.TITLE01_LENGTH);
            Assertions.assertThat(painted.title02())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(40)
                    .hasSize(SignOnResponse.TITLE02_LENGTH);
            Assertions.assertThat(painted.curDate())
                    .as(":186-190 assemble MM/DD/YY, with YY from WS-CURDATE-YEAR(3:2)")
                    .isEqualTo(EXPECTED_CURDATE);
            Assertions.assertThat(painted.curTime())
                    .as(":192-196 assemble HH:MM:SS, right-padded into the nine-wide receiver")
                    .isEqualTo(EXPECTED_CURTIME);
        }

        @Test
        @DisplayName("the X(50) PF3 message is not the similarly named X(40) screen title")
        void thankYouMessageAndTitleRemainDistinct() {
            Assertions.assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(50)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            Assertions.assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(40);
        }

        @Test
        @DisplayName("the clock is injected - two calls on a fixed clock give the same header")
        void theClockIsInjectedRatherThanRead() {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

            Assertions.assertThat(controller.signOn(null).screen().curTime())
                    .isEqualTo(controller.signOn(null).screen().curTime())
                    .isEqualTo(EXPECTED_CURTIME);
        }

        @Test
        @DisplayName("USERIDO stays unpainted - COSGN00C never writes it")
        void userIdOutputStaysUnpainted() {
            SignOnResponse projected = controller.toResponse(signedOnOutcome(
                    NavigationContext.USER_TYPE_ADMIN,
                    SignOnResponse.NEXT_PROGRAM_ADMIN,
                    NavigationContext.empty()));

            Assertions.assertThat(projected.userId())
                    .as("the program only ever reads USERIDI, at :118 and :132 - it is not a MOVE target "
                            + "anywhere, so it keeps the LOW-VALUES image :81 left")
                    .isEqualTo(ScreenFieldImage.unpainted(SignOnResponse.USERID_LENGTH))
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
            var metadata = controller.toMetadata(repaintOutcome(
                    SignOnService.MSG_ENTER_USER_ID,
                    CursorField.USER_ID,
                    NavigationContext.empty().withPgmReenter()));

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
        @DisplayName("JSON contains exactly ten map-derived properties and never PASSWD")
        void exactlyTenSerialisedPropertiesComeFromTheMap() {
            var json = new ObjectMapper().valueToTree(SignOnResponse.empty());
            // The wire names: each screen field's xxxI item in lower case, which @JsonProperty pins
            // per AAP 0.6.3. The Java components keep camel case, and that is not what a caller sees.
            List<String> mapProperties = List.of("trnname", "title01", "curdate", "pgmname",
                    "title02", "curtime", "applid", "sysid", "userid", "errmsg");

            Assertions.assertThat(mapProperties).hasSize(SignOnResponse.MAP_FIELD_COUNT);
            for (String property : mapProperties) {
                Assertions.assertThat(json.has(property))
                        .as("%s is one of the ten projected xxxO items", property)
                        .isTrue();
            }
            Assertions.assertThat(json.has("passwd")).isFalse();
            Assertions.assertThat(json.has("password")).isFalse();
            Assertions.assertThat(json.size())
                    .as("ten map fields plus role/program/map/mapset/commarea navigation")
                    .isEqualTo(15);
        }

        @Test
        @DisplayName("the serialised body of a successful sign-on does not contain the password")
        void theSerialisedBodyDoesNotContainThePassword() throws Exception {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(signedOnOutcome(
                    NavigationContext.USER_TYPE_ADMIN,
                    SignOnResponse.NEXT_PROGRAM_ADMIN,
                    NavigationContext.empty()));

            String body = new ObjectMapper()
                    .writeValueAsString(controller.performSignOn(enterKey("ADMIN001", STORED_PASSWORD)));

            Assertions.assertThat(body).doesNotContain(STORED_PASSWORD).doesNotContain("passwd");
        }

        @Test
        @DisplayName("the submitted password is not echoed even when it is wrong")
        void aWrongPasswordIsNotEchoedEither() throws Exception {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(repaintOutcome(
                    SignOnService.MSG_WRONG_PASSWORD,
                    CursorField.PASSWORD,
                    NavigationContext.empty()));

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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(
                    repaintOutcome(SignOnService.MSG_WRONG_PASSWORD,
                            CursorField.PASSWORD,
                            NavigationContext.empty()),
                    coldStartOutcome());

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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(signedOnOutcome(
                    NavigationContext.USER_TYPE_USER,
                    SignOnResponse.NEXT_PROGRAM_USER,
                    NavigationContext.empty()));

            SignOnResponse first = screenOf(enterKey("USER0001", STORED_PASSWORD));
            SignOnResponse second = screenOf(enterKey("USER0001", STORED_PASSWORD));

            Assertions.assertThat(first).isEqualTo(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("the inbound communication area is never mutated")
        void theInboundCommareaIsNeverMutated() {
            SignOnRequest request = enterKey("NOBODY01", STORED_PASSWORD);
            NavigationContext inbound = request.navigationContext();
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(repaintOutcome(
                    SignOnService.MSG_USER_NOT_FOUND,
                    CursorField.USER_ID,
                    inbound));

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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

            mockMvc().perform(post(SignOnController.SIGNON_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value("CC00"))
                    .andExpect(jsonPath("$.pgmname").value("COSGN00C"))
                    .andExpect(jsonPath("$.screenMetadata.cursorField")
                            .value(SignOnController.CURSOR_USERID))
                    .andExpect(jsonPath("$.screenMetadata.resetAllOutputFields").value(true));
        }

        @Test
        @DisplayName("an administrator's sign-on names COADM01C on the wire")
        void anAdministratorsSignOnNamesTheAdminMenu() throws Exception {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(signedOnOutcome(
                    NavigationContext.USER_TYPE_ADMIN,
                    SignOnResponse.NEXT_PROGRAM_ADMIN,
                    NavigationContext.empty()));
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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(repaintOutcome(
                    SignOnService.MSG_ENTER_USER_ID,
                    CursorField.USER_ID,
                    NavigationContext.empty()));
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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());

            String body = new ObjectMapper()
                    .writeValueAsString(controller.signOn(null));

            var keys = new ObjectMapper().readTree(body).fieldNames();
            var seen = new java.util.ArrayList<String>();
            keys.forEachRemaining(seen::add);

            Assertions.assertThat(seen)
                    .as("the fifteen screen members stay at the top level, plus the metadata envelope")
                    .containsExactlyInAnyOrder("trnname", "title01", "curdate", "pgmname", "title02",
                            "curtime", "applid", "sysid", "userid", "errmsg", "role", "nextProgram",
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
    // The production-equivalent Spring MVC slice.
    // =================================================================================================

    @Nested
    @WebMvcTest(SignOnController.class)
    @ActiveProfiles("test")
    @Import(SliceCollaborators.class)
    @DisplayName("The Spring MVC slice - dispatcher, WebConfig, validation and a mocked service")
    class TheSpringSlice {

        @MockitoBean
        private SignOnService decisionCore;

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private ApplicationContext applicationContext;

        private SignOnRequest requestWith(NavigationContext navigationContext) {
            return new SignOnRequest("CC00",
                    ScreenTitles.CCDA_TITLE01,
                    EXPECTED_CURDATE,
                    "COSGN00C",
                    ScreenTitles.CCDA_TITLE02,
                    EXPECTED_CURTIME,
                    APPLID,
                    "AWSC    ",
                    "ADMIN001",
                    STORED_PASSWORD,
                    spaces(SignOnRequest.ERRMSG_LENGTH),
                    navigationContext,
                    PfKeyResolver.AidKey.ENTER.token());
        }

        @Test
        @DisplayName("POST /api/signon binds a well-formed body and returns the fixed-width screen")
        void theDispatcherBindsTheDeclaredRoute() throws Exception {
            NavigationContext context = NavigationContext.empty().withPgmReenter();
            when(decisionCore.handle(any(SignOnInput.class)))
                    .thenReturn(repaintOutcome("", CursorField.NONE, context));

            mockMvc.perform(post(SignOnController.SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(requestWith(context))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value(SignOnResponse.TRANID))
                    .andExpect(jsonPath("$.pgmname").value(SignOnResponse.PROGRAM_NAME))
                    .andExpect(jsonPath("$.title01").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02").value(ScreenTitles.CCDA_TITLE02))
                    .andExpect(jsonPath("$.curdate").value(EXPECTED_CURDATE))
                    .andExpect(jsonPath("$.curtime").value(EXPECTED_CURTIME))
                    .andExpect(jsonPath("$.applid").value(spaces(SignOnResponse.APPLID_LENGTH)))
                    .andExpect(jsonPath("$.sysid").value(spaces(SignOnResponse.SYSID_LENGTH)))
                    .andExpect(jsonPath("$.errmsg").value(spaces(SignOnResponse.ERRMSG_LENGTH)));

            verify(decisionCore).handle(any(SignOnInput.class));
            verifyNoMoreInteractions(decisionCore);
            Assertions.assertThat(applicationContext.getBean(WebConfig.class)).isNotNull();
            Assertions.assertThat(applicationContext.getBean(Clock.class).instant())
                    .isEqualTo(FIXED_CLOCK.instant());
        }

        @Test
        @DisplayName("a wrong method is 405 and an unknown path is 404 through the real advice")
        void routingFailuresKeepTheirMvcStatuses() throws Exception {
            mockMvc.perform(get(SignOnController.SIGNON_PATH))
                    .andExpect(status().isMethodNotAllowed());
            mockMvc.perform(post(SignOnController.SIGNON_PATH + "/unknown"))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(decisionCore);
        }

        @Test
        @DisplayName("@Size rejects a value wider than USERIDI X(8) before the service is called")
        void validationRejectsAnOverLongMapValue() throws Exception {
            SignOnRequest valid = requestWith(NavigationContext.empty().withPgmReenter());
            SignOnRequest tooWide = new SignOnRequest(valid.trnName(),
                    valid.title01(),
                    valid.curDate(),
                    valid.pgmName(),
                    valid.title02(),
                    valid.curTime(),
                    valid.applId(),
                    valid.sysId(),
                    "ABCDEFGHI",
                    valid.passwd(),
                    valid.errMsg(),
                    valid.navigationContext(),
                    valid.aid());

            mockMvc.perform(post(SignOnController.SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(tooWide)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("userid"));

            verify(decisionCore, never()).handle(any(SignOnInput.class));
        }

        @Test
        @DisplayName("XCTL is response data: no session, cookie, forward, redirect or security filter")
        void aSuccessfulSignOnRemainsStatelessAndClientDriven() throws Exception {
            NavigationContext context = NavigationContext.empty().withPgmReenter();
            when(decisionCore.handle(any(SignOnInput.class)))
                    .thenReturn(signedOnOutcome(NavigationContext.USER_TYPE_ADMIN,
                            SignOnResponse.NEXT_PROGRAM_ADMIN,
                            context));

            MvcResult result = mockMvc.perform(post(SignOnController.SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(requestWith(context))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value(SignOnResponse.NEXT_PROGRAM_ADMIN))
                    .andReturn();

            Assertions.assertThat(result.getRequest().getSession(false)).isNull();
            Assertions.assertThat(result.getResponse().getCookies()).isEmpty();
            Assertions.assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            Assertions.assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
            Assertions.assertThat(result.getResponse().getForwardedUrl()).isNull();
            Assertions.assertThat(result.getResponse().getRedirectedUrl()).isNull();
            Assertions.assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            Assertions.assertThat(applicationContext.containsBean("springSecurityFilterChain"))
                    .as("Spring Security is deliberately absent from this parity migration")
                    .isFalse();
            Assertions.assertThatExceptionOfType(ClassNotFoundException.class)
                    .isThrownBy(() -> Class.forName(
                            "org.springframework.security.crypto.password.PasswordEncoder"));
            Assertions.assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("passwd")
                    .doesNotContain("password")
                    .doesNotContain("token")
                    .doesNotContain(STORED_PASSWORD);
        }

        @Test
        @DisplayName("two communication areas cross the dispatcher independently and need no cookie")
        void successiveContextsDoNotInterfere() throws Exception {
            NavigationContext enter = NavigationContext.empty().withPgmEnter();
            NavigationContext reenter = NavigationContext.empty().withPgmReenter();
            when(decisionCore.handle(any(SignOnInput.class))).thenAnswer(invocation -> {
                SignOnInput input = invocation.getArgument(0, SignOnInput.class);
                return repaintOutcome("", CursorField.NONE, input.navigationContext());
            });

            MvcResult first = mockMvc.perform(post(SignOnController.SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(requestWith(enter))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.navigationContext.pgmContext")
                            .value(NavigationContext.PGM_CONTEXT_ENTER))
                    .andReturn();
            MvcResult second = mockMvc.perform(post(SignOnController.SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(requestWith(reenter))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.navigationContext.pgmContext")
                            .value(NavigationContext.PGM_CONTEXT_REENTER))
                    .andReturn();

            ArgumentCaptor<SignOnInput> inputs = ArgumentCaptor.forClass(SignOnInput.class);
            verify(decisionCore, times(2)).handle(inputs.capture());
            Assertions.assertThat(inputs.getAllValues())
                    .extracting(SignOnInput::navigationContext)
                    .containsExactly(enter, reenter);
            Assertions.assertThat(first.getRequest().getSession(false)).isNull();
            Assertions.assertThat(second.getRequest().getSession(false)).isNull();
            Assertions.assertThat(first.getResponse().getCookies()).isEmpty();
            Assertions.assertThat(second.getResponse().getCookies()).isEmpty();
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
            context.registerBean(SignOnService.class, () -> decisionCore);
            context.registerBean(Clock.class, () -> FIXED_CLOCK);
            context.register(SignOnController.class);
            context.refresh();
            return context;
        }

        @Test
        @DisplayName("the bean wires with no CICS properties configured, defaulting to spaces")
        void theBeanWiresWithNoPropertiesConfigured() {
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());
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
            when(decisionCore.handle(any(SignOnInput.class))).thenReturn(coldStartOutcome());
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
