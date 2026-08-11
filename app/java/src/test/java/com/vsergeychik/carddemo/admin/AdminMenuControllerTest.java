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
 *
 * <h2>What this file asserts, and what it deliberately does NOT</h2>
 *
 * <p>{@link AdminMenuController} holds <strong>no decision logic</strong>: it binds the payload,
 * calls {@link AdminMenuService#handle} once, and projects the outcome. Every branch
 * {@code COADM01C} makes - the {@code EIBCALEN = 0} diversion, the {@code ENTER}/{@code REENTER}
 * split, the ordered {@code EVALUATE EIBAID}, the option normalisation, the three-term validation,
 * the program-prefix test and the menu composition - is asserted in
 * {@code AdminMenuServiceTest}, which is therefore what carries package {@code admin}'s JaCoCo
 * {@code BRANCH >= 0.90} load (gates <strong>G51</strong> and <strong>G49</strong>).
 *
 * <p>So the service is <strong>stubbed</strong> here and its logic is never re-asserted. What is
 * left is exactly four things, and they are all observable behaviour no service test can see:
 *
 * <ol>
 *   <li>straight-line response assembly - the stubbed outcome projected onto
 *       {@link AdminMenuResponse};</li>
 *   <li>the one fixed-width operation the controller performs itself: the {@code PIC X(80)} to
 *       {@code PIC X(78)} <em>right</em>-truncation of the message image into {@code errMsg} through
 *       {@link FixedWidthCodec} ({@code app/cbl/COADM01C.cbl:177});</li>
 *   <li>the HTTP and JSON contract - member names, widths, and that space-padded {@code PIC X(n)}
 *       values survive the round trip (gate <strong>G9</strong>);</li>
 *   <li>statelessness (<strong>G37</strong>), the {@code ENTER}/{@code REENTER} split
 *       (<strong>G38</strong>), and {@code nextProgram}/{@code nextMapset}/{@code nextMap} standing
 *       in for {@code EXEC CICS XCTL} (<strong>G40</strong>).</li>
 * </ol>
 *
 * <h2>Why the service is stubbed with {@link MockitoSpyBean} and not {@code @MockitoBean}</h2>
 *
 * <p>The migration plan's test brief asks for {@code @MockitoBean AdminMenuService}. That cannot
 * work here, and the reason is in the production code rather than in this test:
 * {@code AdminMenuController}'s constructor <em>consumes</em> the service during construction -
 * {@code this.codec = this.adminMenuService.codec()} followed by
 * {@code codec.movePicX(SPACE, OPTION_LENGTH)} - so a collaborator that answers {@code null} to
 * {@code codec()} fails the context before any test method runs. A field-level bean override is
 * created bare and can only be stubbed in {@code @BeforeEach}, which is after the controller
 * singleton has already been built; the observed failure is
 * {@code NullPointerException: Cannot invoke FixedWidthCodec.movePicX(String, int) because
 * this.codec is null}.
 *
 * <p>{@link MockitoSpyBean} is used instead. It is a member of the same modern bean-override family
 * as {@code @MockitoBean} - so the deprecated {@code @MockBean} is still avoided, which is what
 * practice <strong>B2</strong> is actually after - and a spy answers {@code codec()} with the real,
 * immutable codec the constructor needs while every call to {@code handle} is stubbed with
 * {@code doReturn}. The stubbing seam, and therefore <strong>G51</strong>, is unchanged: no test
 * below asserts an outcome the service decided.
 *
 * <p>Outside the one Spring slice, the controller is constructed directly over a Mockito stub whose
 * {@code codec()} is answered at creation time, matching this module's established convention of
 * plain JUnit 5 with {@code MockMvcBuilders.standaloneSetup} where a full context earns nothing.
 *
 * <h2>Determinism</h2>
 *
 * <p>The {@link Clock} is fixed at {@code 2022-07-19T23:12:32Z}, which is
 * {@code COADM01C}'s own version footer ({@code app/cbl/COADM01C.cbl:267}), so the date and time
 * header is assertable byte for byte and the value documents where it came from (practice
 * <strong>B7</strong>). It carries {@link ZoneOffset#UTC}, so the suite is unaffected by the host
 * time zone.
 *
 * <h2>Gates and practices this file carries</h2>
 *
 * <p><strong>Applied:</strong> G5/B3 (the reference trees are read, never written, and nothing is
 * copied into test resources), G9, G37, G38, G40, G41, G49, G50, G51, G52 (no wildcard imports),
 * G53/B9 (no mutable static state - the only static values are an immutable {@code Clock}, an
 * immutable codec and interned strings), G54 (non-interactive), B1/B2 (nothing outside the closed
 * dependency set), B4 (this file only - no base class, no fixture utility, no second
 * {@code application-test.yml}), B5 (all twelve option slots preserved, and the
 * {@code app/cpy/COTTL01Y.cpy:21} title decoy left where it is), B6 (security posture untouched:
 * {@link SecUserRecord} is on the classpath and no test asserts a read of it), B7, B8 (named
 * {@code *_LENGTH} constants and an explicit {@link Charset} everywhere), B10 (no {@code @Disabled},
 * no stub test), B12 (every non-obvious literal carries its source line).
 *
 * <p><strong>Not applied, because they have no subject in {@code COADM01C}:</strong> G47 - the
 * program performs no file I/O at all, so there is no repository call site and no
 * file-status outcome to drive; G35 - it raises none of the nine abend sites; G22 to G29 -
 * it contains no arithmetic and no decimal {@code PICTURE}, so there is no numeric parity to
 * assert; G19 to G21, G43 to G46 and G48 - no persisted record, no optimistic concurrency, no DDL,
 * no dataset name and no statement subroutine.
 *
 * @see AdminMenuService the translated program, and where its branches are asserted
 * @see AdminMenuRequest the inbound projection of {@code 01 COADM1AI}
 * @see AdminMenuResponse the outbound projection of {@code 01 COADM1AO REDEFINES COADM1AI}
 */
@DisplayName("AdminMenuController - GET /api/admin/menu, CSD transaction CA00, program COADM01C")
class AdminMenuControllerTest {

    // =================================================================================================
    // Immutable fixtures. Every static below is deeply immutable - a fixed Clock, an immutable codec and
    // interned strings - so none of them is the mutable static state G53 and practice B9 forbid. Nothing
    // here is reassigned by a test, and no test can observe another's writes.
    // =================================================================================================

    /**
     * The pinned instant: {@code COADM01C}'s own version footer, {@code 2022-07-19 23:12:32}
     * ({@code app/cbl/COADM01C.cbl:267}). Choosing it means the expected header values below are
     * self-documenting rather than arbitrary (practice B7).
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    /** The clock every controller in this file is built over, fixed and zone-explicit (B7, B8). */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /**
     * The code page the fixed-width work is done in, stated explicitly and never defaulted (B8).
     *
     * <p>{@code US-ASCII} is what {@link AdminMenuService#DEFAULT_MESSAGE_CHARSET_NAME} declares for
     * the message images this screen composes. It is a property of the <em>record</em> layer only;
     * the HTTP and JSON layer below is UTF-8 and says so.
     */
    private static final Charset MESSAGE_CHARSET = StandardCharsets.US_ASCII;

    /** The codec used to build expected images. {@link FixedWidthCodec} is immutable. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(MESSAGE_CHARSET);

    /** {@code CDEMO-TO-PROGRAM} for the first admin option: {@code app/cpy/COADM02Y.cpy:24-27}. */
    private static final String USER_LIST_PROGRAM = "COUSR00C";

    /** The sign-on program {@code COADM01C:83} and {@code :97} both name. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** The single {@code PIC X} pad character; a COBOL alphanumeric field has no absent state. */
    private static final String SPACE = " ";

    // =================================================================================================
    // Fixture builders. Instance-free and side-effect-free: each call returns a fresh value, so no test
    // can hand another a mutated fixture.
    // =================================================================================================

    /**
     * The dataset catalogue the real service needs, carrying only the {@code USRSEC} entry
     * {@code app/cbl/COADM01C.cbl:39} declares and never reads.
     *
     * <p>{@link SecUserRecord#RECORD_LENGTH} is 80 because {@code app/cpy/CSUSR01Y.cpy} says so, and
     * {@link AdminMenuService}'s constructor refuses a binding that disagrees. Present so the bean
     * can be built; asserted by nothing (practice B6).
     */
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

    /**
     * A Mockito stub of the decision core whose {@code codec()} is answered <em>at creation time</em>,
     * because {@code AdminMenuController}'s constructor reads it while building
     * {@code optionSpaces} and the cold-start request.
     *
     * <p>{@code handle} is left unstubbed on purpose: each test states the outcome it is projecting,
     * so no test inherits another's. That is also what keeps this file inside G51 - the outcome is
     * given, never computed here.
     */
    private static AdminMenuService stubbedService() {
        AdminMenuService stub = mock(AdminMenuService.class);
        when(stub.codec()).thenReturn(CODEC);
        return stub;
    }

    /** A controller over the given stub, on the fixed clock. */
    private static AdminMenuController controllerOver(final AdminMenuService service) {
        return new AdminMenuController(service, FIXED_CLOCK);
    }

    /** The twelve {@code OPTN00nO} lines as {@code MOVE LOW-VALUES TO COADM1AO} leaves them. */
    private static List<String> blankOptionLines() {
        List<String> lines = new ArrayList<>(AdminMenuResponse.OPTION_LINE_COUNT);
        for (int slot = 1; slot <= AdminMenuResponse.OPTION_LINE_COUNT; slot++) {
            lines.add(CODEC.movePicX(SPACE, AdminMenuResponse.OPTION_LINE_LENGTH));
        }
        return lines;
    }

    /**
     * The four lines {@code BUILD-MENU-OPTIONS} can actually write, followed by eight spaces-filled
     * slots.
     *
     * <p>{@code app/cbl/COADM01C.cbl:238-261} is an {@code EVALUATE WS-IDX} with arms
     * {@code WHEN 1} to {@code WHEN 10} and a {@code WHEN OTHER CONTINUE} - there is <strong>no arm
     * for 11 or 12 at all</strong>, so {@code OPTN011O} and {@code OPTN012O} are structurally
     * unwritable by this program. They are carried anyway, because the map declares them
     * ({@code app/cpy-bms/COADM01.CPY:120,126}); pruning them would be a change of wire format
     * (practice B5). {@code CDEMO-ADMIN-OPT-COUNT} is 4 ({@code app/cpy/COADM02Y.cpy:20}), so slots
     * 5 to 10 stay blank as well even though arms exist for them.
     */
    private static List<String> paintedOptionLines() {
        List<String> lines = blankOptionLines();
        lines.set(0, CODEC.movePicX("01. User List (Security)", AdminMenuResponse.OPTION_LINE_LENGTH));
        lines.set(1, CODEC.movePicX("02. User Add (Security)", AdminMenuResponse.OPTION_LINE_LENGTH));
        lines.set(2, CODEC.movePicX("03. User Update (Security)", AdminMenuResponse.OPTION_LINE_LENGTH));
        lines.set(3, CODEC.movePicX("04. User Delete (Security)", AdminMenuResponse.OPTION_LINE_LENGTH));
        return lines;
    }

    /**
     * An outcome exactly as {@link AdminMenuService} would hand one back, at the widths its own
     * canonical constructor enforces: twelve lines of 40, an 80-character {@code WS-MESSAGE}, a
     * two-character {@code OPTIONO} and an eight-character transfer target.
     *
     * @param optionLines the twelve {@code OPTN00nO} images
     * @param message80   the {@code WS-MESSAGE} image, {@code PIC X(80)} - {@code COADM01C:38}
     * @param colour      {@code ERRMSGC OF COADM1AO}: {@code COLOR=RED} from
     *                    {@code app/bms/COADM01.bms:155}, overridden with {@code DFHGREEN} at
     *                    {@code COADM01C:148}
     * @param nextProgram the {@code XCTL} target, or spaces when the program returned to CICS
     * @param reset       whether {@code MOVE LOW-VALUES TO COADM1AO} ran - {@code COADM01C:89}
     * @param context     {@code CARDDEMO-COMMAREA}, handed back on every return - {@code :109}
     * @return the outcome to stub {@code handle} with
     */
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

    /**
     * The first-entry paint: {@code COADM01C:87-90}, so the message is spaces, the output fields were
     * cleared, and no transfer is named.
     */
    private static AdminMenuOutcome paintedOutcome(final NavigationContext context) {
        return outcome(paintedOptionLines(),
                CODEC.movePicX(SPACE, AdminMenuService.MESSAGE_LENGTH),
                AdminMenuService.MAP_MESSAGE_COLOUR,
                SPACE,
                true,
                context);
    }

    /**
     * A re-entry that reports a message: the shape {@code COADM01C:130-133} and {@code :100-102}
     * produce. {@code resetAllOutputFields} is false because only the first-entry path runs
     * {@code MOVE LOW-VALUES}.
     */
    private static AdminMenuOutcome reportingOutcome(final String message, final byte colour) {
        return outcome(paintedOptionLines(),
                CODEC.movePicX(message, AdminMenuService.MESSAGE_LENGTH),
                colour,
                SPACE,
                false,
                NavigationContext.empty().withPgmReenter());
    }

    /** A payload whose twenty items are each space-filled to the width the map declares. */
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

    /**
     * Drives one request through a controller whose service is stubbed to return {@code outcome}, and
     * returns the whole envelope.
     *
     * <p>The stub is built per call, so nothing is shared between tests.
     */
    private static ScreenResponse<AdminMenuResponse> answerFor(final AdminMenuOutcome outcome) {
        AdminMenuService service = stubbedService();
        doReturn(outcome).when(service).handle(any(AdminMenuInput.class));
        return controllerOver(service).getAdminMenu(
                blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER));
    }

    /** The freshly painted screen: the shape {@code SEND-MENU-SCREEN} produces on first entry. */
    private static AdminMenuResponse paint() {
        return answerFor(paintedOutcome(NavigationContext.empty().withPgmReenter())).screen();
    }

    /** A payload carrying a typed option, continuing the pseudo-conversation. */
    private static AdminMenuRequest withOption(final String option, final NavigationContext context) {
        AdminMenuRequest blank = blankScreen(context, CicsAid.DFHENTER);
        return new AdminMenuRequest(blank.trnName(), blank.title01(), blank.curDate(),
                blank.pgmName(), blank.title02(), blank.curTime(),
                blank.optn001(), blank.optn002(), blank.optn003(), blank.optn004(),
                blank.optn005(), blank.optn006(), blank.optn007(), blank.optn008(),
                blank.optn009(), blank.optn010(), blank.optn011(), blank.optn012(),
                option, blank.errMsg(), context, blank.eibAid());
    }

    // =================================================================================================
    // Construction, and the seam an absent body goes through.
    // =================================================================================================

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

            // AdminMenuController:276 reads it during construction - which is exactly why a bare bean
            // override cannot stand in for this collaborator. See the class javadoc.
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

    // =================================================================================================
    // The route, the handler signature, and what an absent body is taken to mean.
    // =================================================================================================

    @Nested
    @DisplayName("The handler - one GET, one path, and EIBCALEN = 0 for an absent body")
    class TheHandlerContract {

        @Test
        @DisplayName("the mapping is the GET the plan assigns to transaction CA00")
        void theMappingIsTheAssignedOne() throws NoSuchMethodException {
            // app/csd/CARDDEMO.CSD:327-328 defines TRANSACTION(CA00) PROGRAM(COADM01C); AAP 0.3.9 maps
            // it to this resource.
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

    // =================================================================================================
    // Gate G9 - every payload member traces to a DFHMDF definition and every width to an xxxI PICTURE.
    // =================================================================================================

    @Nested
    @DisplayName("The payload contract (G9) - twenty members, at the widths the symbolic map declares")
    class ThePayloadContract {

        @Test
        @DisplayName("twenty named DFHMDF fields of twenty-eight, and twenty payload members")
        void twentyOfTwentyEightFieldsTravel() {
            // app/bms/COADM01.bms declares 28 DFHMDF fields; the 8 unnamed ones are screen furniture
            // ('Tran:' :29-33, 'Date:' :42-46, and so on) and never travel.
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
            // app/cpy-bms/COADM01.CPY, in the order the map declares: TRNNAMEI X(4) :24,
            // TITLE01I X(40) :30, CURDATEI X(8) :36, PGMNAMEI X(8) :42, TITLE02I X(40) :48,
            // CURTIMEI X(8) :54, OPTN001I..OPTN012I X(40) :60-:126, OPTIONI X(2) :132,
            // ERRMSGI X(78) :138.
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

            // CDEMO-ADMIN-OPT-COUNT is 4 (app/cpy/COADM02Y.cpy:20), so L228-L229 iterates four times.
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
            // app/bms/COADM01.bms:145-149: OPTION is ATTRB=(FSET,IC,NORM,NUM,UNPROT), LENGTH=2,
            // POS=(20,41). Every other named field is ASKIP, so a client cannot have typed into it -
            // and POPULATE-HEADER-INFO (:202-221) rewrites the header from working storage regardless.
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

        /** {@code TRNNAMEO} to {@code trnName}: the map's item name as the DTO spells it. */
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


    // =================================================================================================
    // The one fixed-width operation the controller performs itself: PIC X(80) -> PIC X(78).
    // =================================================================================================

    @Nested
    @DisplayName("The message image - WS-MESSAGE X(80) into ERRMSGO X(78), truncated on the RIGHT")
    class TheMessageTruncation {

        @Test
        @DisplayName("the two widths disagree by two bytes, and that is the whole point")
        void theTwoWidthsDisagree() {
            // app/cbl/COADM01C.cbl:38 declares WS-MESSAGE PIC X(80); app/bms/COADM01.bms:156 declares
            // ERRMSG LENGTH=78 and app/cpy-bms/COADM01.CPY:138 ERRMSGI PIC X(78). Line 177 moves one
            // into the other, so COBOL discards two bytes on every send.
            assertThat(AdminMenuService.MESSAGE_LENGTH).isEqualTo(80);
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("a short message is right-space-padded to exactly 78, never trimmed")
        void aShortMessageIsPaddedNotTrimmed() {
            // app/cbl/COADM01C.cbl:131-132 moves a 36-character literal into the 80-byte field, which
            // line 177 then moves into the 78-byte one.
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
            // A probe whose every position is identifiable: 'H' at 1, 'T' at 78, then '#' and '$' at
            // 79 and 80. Right truncation keeps H..T; left truncation would keep '#' and '$'.
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
            // B8: FixedWidthCodec takes a Charset and this file always names one; the platform default
            // is never relied on. B11: the codec is hand-written, so the direction of every MOVE is
            // explicit at the call site.
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
            // app/bms/COADM01.bms:155 declares COLOR=RED; app/cbl/COADM01C.cbl:148 overrides it with
            // DFHGREEN on the coming-soon path. Which one applies is the service's decision, so this
            // asserts carriage only.
            assertThat(answerFor(reportingOutcome(AdminMenuService.COMING_SOON_MESSAGE,
                    AdminMenuService.COMING_SOON_MESSAGE_COLOUR)).screenMetadata().messageColour())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHGREEN));
            assertThat(answerFor(reportingOutcome(SystemMessages.CCDA_MSG_INVALID_KEY,
                    AdminMenuService.MAP_MESSAGE_COLOUR)).screenMetadata().messageColour())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
        }
    }

    // =================================================================================================
    // The header fields, and the three literal traps around them.
    // =================================================================================================

    @Nested
    @DisplayName("The header - the two identity literals, the two titles, and the date and time")
    class TheHeaderFields {

        @Test
        @DisplayName("TRNNAMEO is 'CA00' and PGMNAMEO is 'COADM01C' - L208-L209")
        void theTwoIdentityLiterals() {
            AdminMenuResponse painted = paint();

            // app/cbl/COADM01C.cbl:37 WS-TRANID PIC X(04) VALUE 'CA00', moved at :208.
            assertThat(painted.trnName())
                    .isEqualTo(AdminMenuService.TRANSACTION_ID)
                    .isEqualTo("CA00")
                    .hasSize(AdminMenuResponse.TRN_NAME_LENGTH);
            // app/cbl/COADM01C.cbl:36 WS-PGMNAME PIC X(08) VALUE 'COADM01C', moved at :209.
            assertThat(painted.pgmName())
                    .isEqualTo(AdminMenuService.PROGRAM_NAME)
                    .isEqualTo("COADM01C")
                    .hasSize(AdminMenuResponse.PGM_NAME_LENGTH);
        }

        @Test
        @DisplayName("B5: the ACTIVE titles travel - not the commented-out decoy at COTTL01Y:21")
        void theActiveTitlesTravel() {
            AdminMenuResponse painted = paint();

            // app/cpy/COTTL01Y.cpy:18-19 CCDA-TITLE01, forty characters with six leading and seven
            // trailing spaces.
            assertThat(painted.title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("AWS Mainframe Modernization")
                    .startsWith(SPACE)
                    .endsWith(SPACE);
            // app/cpy/COTTL01Y.cpy:22 is the ACTIVE CCDA-TITLE02. Line 21 immediately above it holds a
            // commented-out '  Credit Card Demo Application (CCDA)   ', also exactly forty characters.
            // It is left exactly where it is, and must never be substituted for the active value.
            assertThat(painted.title02())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("CardDemo")
                    .doesNotContain("Credit Card Demo Application");
        }

        @Test
        @DisplayName("the invalid-key text is CCDA-MSG-INVALID-KEY: not either thank-you literal")
        void theInvalidKeyTextIsNotConflated() {
            // Three different literals, three different owners, and two different widths:
            //   app/cpy/CSMSG01Y.cpy:20-21 CCDA-MSG-INVALID-KEY  PIC X(50), says "Invalid key"
            //   app/cpy/CSMSG01Y.cpy:18-19 CCDA-MSG-THANK-YOU    PIC X(50), says "CardDemo"
            //   app/cpy/COTTL01Y.cpy:23-24 CCDA-THANK-YOU        PIC X(40), says "CCDA"
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);

            // COADM01C:101 widens the 50-byte text into WS-MESSAGE X(80); line 177 then narrows it to
            // the 78-byte map field, which is still wide enough to carry all fifty.
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

            // The clock is pinned to COADM01C's own footer, 2022-07-19 23:12:32 (:267). WS-CURDATE-YY
            // is WS-CURDATE-YEAR(3:2), the last two digits of the year (:213), and the separators are
            // the declared FILLER '/' at app/cpy/CSDAT01Y.cpy:32,34 and ':' at :38,40.
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


    // =================================================================================================
    // Gates G38 and G50 - the ENTER / REENTER split, both user types, and the CSSETATY rule.
    // =================================================================================================

    @Nested
    @DisplayName("ENTER versus REENTER (G38, G50) - both contexts carried, and no invented highlight")
    class EnterAndReenter {

        @Test
        @DisplayName("G50: CDEMO-PGM-ENTER and CDEMO-PGM-REENTER are both driven, true and false")
        void bothContextConditionNamesAreDriven() {
            // app/cpy/COCOM01Y.cpy:29-31: CDEMO-PGM-CONTEXT PIC 9(01) with 88 CDEMO-PGM-ENTER VALUE 0
            // and 88 CDEMO-PGM-REENTER VALUE 1.
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
            // app/cpy/COCOM01Y.cpy:27-28: 88 CDEMO-USRTYP-ADMIN VALUE 'A', 88 CDEMO-USRTYP-USER 'U'.
            // COADM01C never tests either - it is the sign-on screen that routes on user type - so the
            // controller must carry whichever arrives, unchanged.
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
            // app/cpy/CSSETATY.cpy: DFHRED goes to the colour item when a field is NOT-OK or BLANK and
            // the program is in REENTER context; the literal '*' additionally goes to the output item
            // only when the field is BLANK. The REENTER state is an explicit parameter, never implied.
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
            // The include appears in COACTUPC 39 times; it appears in COADM01C not at all. So whatever
            // CSSETATY would do to a field, this screen does not do it, and no per-field quad travels.
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
            // The two arms app/cbl/COADM01C.cbl:94 and :96 name, as the shared resolver classifies them.
            assertThat(PfKeyResolver.isEnter(entered)).isTrue();
            assertThat(PfKeyResolver.isPf3(exited)).isTrue();
            assertThat(PfKeyResolver.isEnter(unhandled)).isFalse();
            assertThat(PfKeyResolver.isPf3(unhandled))
                    .as("PF15 matches neither arm, so it lands on WHEN OTHER - a service decision")
                    .isFalse();
        }
    }

    // =================================================================================================
    // Gate G40 - EXEC CICS XCTL becomes response fields, resolved by the client.
    // =================================================================================================

    @Nested
    @DisplayName("Navigation (G40) - XCTL as three response fields, and never a redirect")
    class Navigation {

        @Test
        @DisplayName("a painted screen names its own mapset and map - L180-L181")
        void aPaintedScreenNamesItsOwnMap() {
            AdminMenuResponse painted = paint();

            // app/cbl/COADM01C.cbl:180-181 SEND MAP('COADM1A') MAPSET('COADM01'); the mapset is defined
            // at app/csd/CARDDEMO.CSD:110.
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
            // Two of the three XCTL shapes in this program: :143 XCTL PROGRAM(
            // CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)) - option 1 is COUSR00C per app/cpy/COADM02Y.cpy:27 -
            // and :166 XCTL PROGRAM(CDEMO-TO-PROGRAM) inside RETURN-TO-SIGNON-SCREEN, which :163
            // defaults to COSGN00C.
            AdminMenuOutcome transferring = outcome(paintedOptionLines(),
                    CODEC.movePicX(SPACE, AdminMenuService.MESSAGE_LENGTH),
                    AdminMenuService.MAP_MESSAGE_COLOUR,
                    target,
                    false,
                    NavigationContext.empty().withPgmEnter().withToProgram(target));

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
        @DisplayName("G37: the outbound communication area is the 160-byte COMMAREA, carried in payload")
        void theOutboundCommareaIsCarried() {
            NavigationContext outbound = NavigationContext.empty()
                    .withPgmReenter()
                    .withUserTypeAdmin()
                    .withLastMap(AdminMenuResponse.MAP_NAME)
                    .withLastMapset(AdminMenuResponse.MAPSET_NAME);

            AdminMenuResponse painted = answerFor(paintedOutcome(outbound)).screen();

            assertThat(painted.navigationContext()).isEqualTo(outbound);
            // app/cpy/COCOM01Y.cpy:19-44: 34 + 84 + 12 + 16 + 14 = 160 bytes, and :43-44 declare both
            // CDEMO-LAST-MAP and CDEMO-LAST-MAPSET as PIC X(7).
            assertThat(outbound.toFixedWidth(CODEC)).hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(painted.navigationContext().lastMap())
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(painted.navigationContext().lastMapset())
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
        }
    }


    // =================================================================================================
    // The wire, over MockMvc. Standalone rather than a context, matching this module's convention: the
    // subject here is the JSON shape and the servlet-level facts, and both are observable without one.
    // =================================================================================================

    @Nested
    @DisplayName("The wire - the screen stays flat, the padding survives, and nothing is stashed")
    class TheWire {

        /** A fresh dispatcher per test; nothing is shared, so nothing can leak (G53). */
        private MockMvc mockMvcOver(final AdminMenuService service) {
            return MockMvcBuilders.standaloneSetup(controllerOver(service)).build();
        }

        /** A stub answering one outcome, and the payload that reaches it. */
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
                    // Neither is a DFHMDF field, so neither is a member of the screen itself.
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
                    // Forty characters with leading AND trailing spaces, all of them intact.
                    .andExpect(jsonPath("$.title01").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02").value(ScreenTitles.CCDA_TITLE02))
                    // Seventy-eight spaces: present, not null, not "", not dropped.
                    .andExpect(jsonPath("$.errmsg").exists())
                    .andExpect(jsonPath("$.errmsg")
                            .value(SPACE.repeat(AdminMenuResponse.ERR_MSG_LENGTH)))
                    // A slot BUILD-MENU-OPTIONS never fills is still a member, and still forty wide.
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

    // =================================================================================================
    // The Spring MVC slice - the real dispatcher, the real WebConfig, and a spied decision core.
    // =================================================================================================

    /**
     * The collaborators the slice needs: the real decision core, which {@link MockitoSpyBean} then
     * wraps, and a fixed {@link Clock} that displaces {@code WebConfig}'s
     * {@code Clock.systemDefaultZone()}.
     *
     * <p>Declared on the enclosing class because {@code @TestConfiguration} must be static and a
     * {@code @Nested} class cannot hold a static member. It is imported by the slice below and by
     * nothing else.
     */
    @TestConfiguration
    static class SliceCollaborators {

        /**
         * The real service, so the spy over it answers {@code codec()} with a real codec - which
         * {@code AdminMenuController}'s constructor requires. Its {@code handle} is stubbed per test.
         */
        @Bean
        AdminMenuService adminMenuService() {
            return new AdminMenuService(bindings());
        }

        /**
         * The pinned clock. {@code @Primary} because {@code WebConfig} contributes a
         * {@code Clock.systemDefaultZone()} bean and a header rendered from the wall clock cannot be
         * asserted byte for byte (practice B7).
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }

    @Nested
    @WebMvcTest(AdminMenuController.class)
    @ActiveProfiles("test")
    @Import(SliceCollaborators.class)
    @DisplayName("The Spring MVC slice - real dispatcher, real WebConfig, stubbed decision core")
    class TheSpringSlice {

        /**
         * The decision core, spied rather than mocked.
         *
         * <p>{@code @MockitoBean} is impossible here: {@code AdminMenuController}'s constructor calls
         * {@code adminMenuService.codec()} while the bean is being created, and a bare override answers
         * {@code null}, which fails the context before {@code @BeforeEach} can stub anything. A spy
         * calls through for {@code codec()} and is stubbed for {@code handle}, so the controller still
         * projects an outcome it did not compute (gate G51).
         */
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
            // app/bms/COADM01.bms:148 declares LENGTH=2 and app/cpy-bms/COADM01.CPY:132 OPTIONI
            // PIC X(2), so @Size(max = OPTION_LENGTH) is the map's own constraint. The exhaustive
            // per-field constraint set is AdminMenuRequestTest's subject; what is asserted here is that
            // MVC honours @Valid on the handler at all.
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
