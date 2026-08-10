package com.vsergeychik.carddemo.user;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.PfKeyResolver;
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
 *
 * <p>Every test that asserts a <em>decision</em> constructs the controller directly with a mocked
 * {@link SecUserRepository} and a fixed {@link Clock} and calls
 * {@link UserUpdateController#handle(UserUpdateRequest, int, byte, Cu02Info)}. There is no HTTP layer,
 * no {@code MockMvc} and no Spring context in the decision path, which is gate <strong>G51</strong>: a
 * guard chain is asserted where it lives, so a failure names the COBOL paragraph rather than a status
 * code. Only {@link HttpAdapter} exercises the {@code PUT} adapter, and only for the projection rules
 * that live in it - the path variable binding and the {@code EIBCALEN} inference.
 *
 * <h2>Every expected value here is STATICALLY DERIVED, not captured from a run</h2>
 *
 * <p>The COBOL cannot be executed in this environment (open risk <strong>R-A</strong>: no z/OS runtime,
 * GnuCOBOL's indexed file handler reports {@code disabled}, no Language Environment {@code CEE*}
 * services, no CICS emulator, and {@code DFHAID} / {@code DFHBMSCA} / {@code DFHATTR} are absent from
 * the repository). Every value below was therefore read out of {@code app/cbl/COUSR02C.cbl},
 * {@code app/cpy-bms/COUSR02.CPY}, {@code app/bms/COUSR02.bms}, {@code app/cpy/CSUSR01Y.cpy} and
 * {@code app/csd/CARDDEMO.CSD}, and is cited at its point of use. A reader must not mistake any of them
 * for a captured value.
 *
 * <h2>The three things this class exists to pin</h2>
 *
 * <ol>
 *   <li><strong>{@code PF3} saves and {@code PF12} does not.</strong> {@code :111-119} calls
 *       {@code UPDATE-USER-INFO} before it transfers; {@code :124-126} transfers without calling it.
 *       Treating the two keys as interchangeable "exit" keys would silently drop a rewrite.</li>
 *   <li><strong>The five-way empty-field guard chain is ordered and short-circuits</strong>
 *       ({@code :180-211}), so a screen that is blank in two fields reports only the first.</li>
 *   <li><strong>An unmodified record is refused with {@code 'Please modify to update ...'}</strong>
 *       ({@code :236-242}) rather than rewritten, and the password is compared in <em>plaintext</em>
 *       ({@code :227}) exactly as the legacy program compares it - hashing would be a behaviour
 *       change.</li>
 * </ol>
 *
 * <h2>The paragraph map: every line range this class asserts against</h2>
 *
 * <p>The screen's identity comes from {@code app/csd/CARDDEMO.CSD:469-470} -
 * {@code DEFINE TRANSACTION(CU02) GROUP(CARDDEMO)} followed by {@code PROGRAM(COUSR02C)} - which AAP
 * section 0.3.9 maps to {@code PUT /api/users/{userId}}. Everything else comes from
 * {@code app/cbl/COUSR02C.cbl}, and every bare {@code :nnn} below and throughout this class is a line
 * in that file:
 *
 * <table border="1">
 *   <caption>COBOL line range to the group that asserts it</caption>
 *   <tr><th>Lines</th><th>What lives there</th><th>Asserted by</th></tr>
 *   <tr><td>{@code :35-47}</td><td>{@code WS-VARIABLES} and three of the six {@code 88}-levels</td>
 *       <td>{@link Construction}</td></tr>
 *   <tr><td>{@code :50-57}</td><td>the 34-byte {@code 05 CDEMO-CU02-INFO} extension</td>
 *       <td>{@link Cu02InfoTests}, {@link CommareaGeometry}</td></tr>
 *   <tr><td>{@code :85}</td><td>{@code SET USR-MODIFIED-NO TO TRUE} on every entry</td>
 *       <td>{@link ChangeDetection#theModifiedFlagDoesNotLeakBetweenRequests()}</td></tr>
 *   <tr><td>{@code :90-105}</td><td>{@code EIBCALEN = 0}, first entry, and the arrival auto-lookup</td>
 *       <td>{@link MainPara}</td></tr>
 *   <tr><td>{@code :108-131}</td><td>{@code EVALUATE EIBAID} - six arms</td>
 *       <td>{@link AidDispatchOrder}</td></tr>
 *   <tr><td>{@code :143-172}</td><td>{@code PROCESS-ENTER-KEY}, incl. {@code :167-170} painting the
 *       record and {@code :169} painting the <em>stored</em> password</td>
 *       <td>{@link ProcessEnterKey}, {@link ScreenContract}</td></tr>
 *   <tr><td>{@code :177-213}</td><td>{@code UPDATE-USER-INFO}'s ordered five-arm blank chain</td>
 *       <td>{@link UpdateUserInfo}, {@link LowValuesGuardChain}</td></tr>
 *   <tr><td>{@code :219-243}</td><td>the four independent change tests and the modification gate</td>
 *       <td>{@link ChangeDetection}</td></tr>
 *   <tr><td>{@code :250-261}</td><td>{@code RETURN-TO-PREV-SCREEN}</td>
 *       <td>{@link ReturnToPrevScreen}</td></tr>
 *   <tr><td>{@code :296-315}</td><td>{@code POPULATE-HEADER-INFO}</td>
 *       <td>{@link HeaderInfo}, {@link DeterministicHeader}</td></tr>
 *   <tr><td>{@code :320-353}</td><td>{@code READ-USER-SEC-FILE} - the held read and its three arms</td>
 *       <td>{@link DatasetContract}, {@link ProcessEnterKey}</td></tr>
 *   <tr><td>{@code :358-390}</td><td>{@code UPDATE-USER-SEC-FILE} - the keyless rewrite, three arms</td>
 *       <td>{@link DatasetContract}, {@link UpdateUserSecFile}</td></tr>
 *   <tr><td>{@code :395-411}</td><td>{@code CLEAR-CURRENT-SCREEN} and {@code INITIALIZE-ALL-FIELDS}</td>
 *       <td>{@link MainPara#pf4ClearsTheScreen()}</td></tr>
 * </table>
 *
 * <p>The remaining contracts come from {@code app/cpy-bms/COUSR02.CPY} and {@code app/bms/COUSR02.bms}
 * (the twelve field names and widths - {@link ScreenContract}), {@code app/cpy/CSUSR01Y.cpy} (the
 * 80-byte record - {@link DatasetContract}, {@link SeededUsrsec}), {@code app/cpy/COCOM01Y.cpy} (the
 * 160-byte communication area - {@link CommareaGeometry}) and {@code app/jcl/DUSRSECJ.jcl:34-44} (the
 * in-stream seed - {@link SeededUsrsec}).
 *
 * <h2>Governing rules: there are none, so the bar is enterprise best practice</h2>
 *
 * <p>{@code review_rules} returns exactly one line for this project - <em>"No user rules provided."</em>
 * - and that single line is the whole document. No rule therefore forces any file into scope, and no
 * rule is invented here. The absence is explicitly <strong>not</strong> treated as licence to lower the
 * bar: the binding constraints are the enterprise best-practice substitutes catalogued in the Agent
 * Action Plan section 0.10.2, and the ones that govern this file are named at their point of use:
 *
 * <ul>
 *   <li><strong>B1/B2 - a closed, pinned test stack.</strong> JUnit Jupiter, Mockito, AssertJ and
 *       Spring Test, all at the versions the parent BOM manages. Nothing else is imported: no
 *       Testcontainers, no Spring Security, no persistence provider, no COBOL parser.</li>
 *   <li><strong>B3 - reference inputs are immutable.</strong> {@code app/cbl/COUSR02C.cbl},
 *       {@code app/cpy-bms/COUSR02.CPY}, {@code app/bms/COUSR02.bms}, {@code app/cpy/CSUSR01Y.cpy},
 *       {@code app/cpy/COCOM01Y.cpy}, {@code app/jcl/DUSRSECJ.jcl:34-44} and
 *       {@code app/csd/CARDDEMO.CSD:469-470} are read and cited here, and written by nothing (gate
 *       <strong>G5</strong>).</li>
 *   <li><strong>B4 - conflicts are documented, never silently corrected.</strong> See the gate
 *       <strong>G43</strong> note immediately below, and {@link MigrationConstraints} which asserts
 *       its non-applicability rather than assuming it.</li>
 *   <li><strong>B5 - legacy behaviour is preserved, including its defects.</strong> This is the
 *       defining practice for this screen. {@code PF3} saves before it exits ({@code :111-119}); the
 *       {@code NORMAL} arm of {@code READ-USER-SEC-FILE} opens with a vestigial {@code CONTINUE}
 *       ({@code :335}); and the four change tests sit outside any {@code IF NOT ERR-FLG-ON} guard
 *       ({@code :219-234}). All three are pinned as they are.</li>
 *   <li><strong>B6 - the security posture is neither weakened nor unrequestedly strengthened.</strong>
 *       {@code SEC-USR-PWD PIC X(08)} is compared and stored in clear text (gate
 *       <strong>G41</strong>), and {@code :169} paints the <em>stored</em> password back onto the map,
 *       so {@link UserUpdateResponse#passwd()} must carry it. Introducing a digest would delete
 *       observable behaviour and would need a framework this migration excludes.</li>
 *   <li><strong>B7 - deterministic and non-interactive.</strong> A fixed {@link Clock} is injected
 *       everywhere, so {@code POPULATE-HEADER-INFO} renders identically on every run; there is no
 *       randomness, no wall-clock read and no dependence on test ordering (gate
 *       <strong>G54</strong>).</li>
 *   <li><strong>B8 - explicit over implicit.</strong> No wildcard import (gate <strong>G52</strong>),
 *       an explicitly named {@link Charset} rather than the platform default, and no dataset-name
 *       literal anywhere (gate <strong>G46</strong>).</li>
 *   <li><strong>B9 - no static mutable state</strong> (gate <strong>G53</strong>). Every fixture is
 *       built per test, the controller is constructed per test, and
 *       {@link Construction#noStaticMutableState()} proves the production type holds none either -
 *       which is also why {@code MAIN-PARA}'s reset at {@code :85} cannot leak between requests.</li>
 *   <li><strong>B12 - environmental limits are documented, not absorbed.</strong> See the provenance
 *       note above: every expectation is statically derived, and that deviation is open risk
 *       <strong>R-A</strong>.</li>
 * </ul>
 *
 * <h2>Gate G43 has no subject in this package, and that is asserted rather than assumed</h2>
 *
 * <p>Gate <strong>G43</strong> requires optimistic concurrency reproducing
 * {@code 9300-CHECK-CHANGE-IN-REC} field for field. That paragraph exists in {@code COACTUPC} and
 * {@code COCRDUPC}, so the gate is scoped to the {@code account} and {@code card} packages.
 * {@code COUSR02C} has no such paragraph: it takes a held read at {@code :322-331} with
 * {@code UPDATE}, compares the typed fields against the record area it just read, and rewrites the
 * held record at {@code :360-366} with no {@code RIDFLD}. The concurrency control is the CICS lock,
 * not a re-read-and-compare and not a version column. Adding either would be a schema and behaviour
 * change that AAP section 0.7.4 forbids, so
 * {@link MigrationConstraints#noOptimisticConcurrencyArtefactExists()} asserts their
 * <em>absence</em>.
 *
 * <h2>This screen tests RESP with {@code DFHRESP()}, where {@code COSGN00C} uses raw numerics</h2>
 *
 * <p>{@code COUSR02C:333-353} and {@code :368-390} both write {@code WHEN DFHRESP(NORMAL)} and
 * {@code WHEN DFHRESP(NOTFND)}. {@code COSGN00C:211-257} writes the equivalent test against the raw
 * numbers {@code 0} and {@code 13} instead. The two spellings are the same condition, and
 * {@link com.vsergeychik.carddemo.common.FileStatus} unifies them: one status vocabulary, so a
 * repository outcome reads the same whichever form the calling program used.
 * {@link DatasetContract#theTwoRespSpellingsUnifyThroughFileStatus()} states that once.
 */
@DisplayName("UserUpdateController - the COUSR02C / CU02 update-user screen")
class UserUpdateControllerTest {

    /**
     * The clock every test runs against: {@code 2022-07-19T23:12:34Z} at UTC, taken from the version
     * footer date the sources carry. Fixed, so {@code POPULATE-HEADER-INFO} at {@code :296-315} is
     * reproducible.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    /** {@code CURDATEO} for {@link #FIXED_CLOCK}: {@code MM/DD/YY}. */
    private static final String EXPECTED_DATE = "07/19/22";

    /** {@code CURTIMEO} for {@link #FIXED_CLOCK}: {@code HH:MM:SS}. */
    private static final String EXPECTED_TIME = "23:12:34";

    /** The code page the fixtures are built in; single byte, and never the platform default. */
    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    /** A user id at the full declared width of {@code SEC-USR-ID PIC X(08)}. */
    private static final String USER_ID = "USER0001";

    /**
     * A stand-in for {@code SEC-USR-PWD PIC X(08)}. Deliberately an obvious placeholder: the field is
     * compared in clear text by {@code :227}, and no test here needs a credential-shaped value.
     */
    private static final String STORED_PWD = "PWDAAAAA";

    private SecUserRepository repository;

    private UserUpdateController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        controller = new UserUpdateController(repository, FIXED_CLOCK);
    }

    // =================================================================================================
    // Fixtures
    // =================================================================================================

    /** {@code CDEMO-PGM-CONTEXT = 1}: the operator is typing into the map, so the map is received. */
    private static NavigationContext reenter() {
        return NavigationContext.empty().withPgmReenter();
    }

    /** {@code CDEMO-PGM-CONTEXT = 0}: first entry, so the map is painted rather than received. */
    private static NavigationContext enter() {
        return NavigationContext.empty().withPgmEnter();
    }

    /** A terminal input area carrying the five typed items and a communication area. */
    private static UserUpdateRequest screen(String usrIdIn,
                                            String fName,
                                            String lName,
                                            String passwd,
                                            String usrType,
                                            NavigationContext commarea) {
        return new UserUpdateRequest(null, null, null, null, null, null,
                usrIdIn, fName, lName, passwd, usrType, null, commarea, null, null);
    }

    /** The screen a fully populated, valid update carries. */
    private static UserUpdateRequest populated(NavigationContext commarea) {
        return screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", commarea);
    }

    /** An 80-byte {@code SEC-USER-DATA} for the given key, with the fixture's names and password. */
    private static SecUserRecord storedUser() {
        return SecUserRecord.of(USER_ID, "Sam", "Spade", STORED_PWD, "U", CODE_PAGE);
    }

    /** The commarea extension a cold-started list hand-off would leave, selecting nobody. */
    private static Cu02Info noSelection() {
        return Cu02Info.initial();
    }

    /**
     * The same terminal input area, carrying a stated {@code EIBAID} token.
     *
     * <p>An absent token resolves to {@link CicsAid#DFHENTER}, because a bare transmit is {@code ENTER}.
     * A test that means to reach {@code UPDATE-USER-INFO} over HTTP therefore has to say so: the
     * {@code ENTER} arm at {@code :109-110} runs {@code PROCESS-ENTER-KEY}, whose guard tests only
     * {@code USRIDINI}, so a blank <em>name</em> would sail past it and reach the read.
     *
     * @param request the payload to re-issue
     * @param token   the five-character token, one of {@link AidKey#token()}
     * @return the same payload carrying that token
     */
    private static UserUpdateRequest withAid(UserUpdateRequest request, String token) {
        return new UserUpdateRequest(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.passwd(), request.usrType(),
                request.errMsg(), request.navigationContext(), token, request.cu02Info());
    }

    /** The same terminal input area, carrying a stated {@code 05 CDEMO-CU02-INFO}. */
    private static UserUpdateRequest withExtension(UserUpdateRequest request, Cu02Info info) {
        return new UserUpdateRequest(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.passwd(), request.usrType(),
                request.errMsg(), request.navigationContext(), request.aid(), info);
    }

    /** {@code MOVE WS-MESSAGE TO ERRMSGO} - the text as the 78-character screen field holds it. */
    private static String errMsgImage(String text) {
        return text + " ".repeat(UserUpdateResponse.ERR_MSG_LENGTH - text.length());
    }

    /** A value as an {@code xxxI} item of the given width holds it: padded on the right with spaces. */
    private static String padded(String text, int length) {
        return text + " ".repeat(length - text.length());
    }

    /** The rendering of {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at {@code :347}. */
    private static String displayLine(int resp, int reas) {
        return "RESP:" + String.format("%09d", resp) + "REAS:" + String.format("%09d", reas);
    }

    /** Stub {@code :322-331} as a successful locking read of the stored record. */
    private void stubFoundRead() {
        when(repository.readForUpdate(anyString())).thenReturn(ReadResult.found(storedUser()));
    }

    /** Drive {@code MAIN-PARA} on the re-entry path for the given key. */
    private ProgramState reentryWith(UserUpdateRequest request, byte aid) {
        return controller.handle(request, UserUpdateController.PASSED_COMMAREA_LENGTH, aid,
                noSelection());
    }

    /** Stub {@code :360-366} as a successful rewrite of the held record. */
    private void stubSuccessfulRewrite() {
        when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());
    }

    /** The record the single {@code REWRITE} at {@code :360} was handed. */
    private SecUserRecord rewrittenRecord() {
        ArgumentCaptor<SecUserRecord> captor = ArgumentCaptor.forClass(SecUserRecord.class);
        verify(repository).rewrite(captor.capture());
        return captor.getValue();
    }

    /**
     * A {@code MockMvc} over this controller alone.
     *
     * <p>Standalone rather than {@code @WebMvcTest}: the decision path is asserted by calling
     * {@link UserUpdateController#handle} directly, and the only reason to raise an HTTP layer at all is
     * to exercise the rules that live in the adapter - the path-variable binding, the JSON projection,
     * and the status codes the dispatcher itself produces. A standalone setup registers exactly one
     * handler, so a request with the wrong method has nowhere else to land and the {@code 405} is the
     * dispatcher's own answer rather than an artefact of some other controller's mapping.
     *
     * @param mapper the mapper the message converter is to use; the same instance the assertions read
     * @return a dispatcher carrying only {@code PUT /api/users/&#123;userId&#125;}
     */
    private MockMvc httpOver(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    /**
     * Every property name reachable anywhere in a JSON tree, at any depth.
     *
     * <p>Used to prove a negative: that no {@code xxxL}, {@code xxxF} or {@code xxxA} item of the
     * symbolic map appears as a payload member. A shallow field list would miss one nested inside
     * {@code cu02Info} or {@code navigationContext}, so the walk is recursive.
     *
     * @param node the tree to walk; may be any node type
     * @return every field name in the tree, in encounter order
     */
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

    // =================================================================================================
    // Construction and identity
    // =================================================================================================

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

    // =================================================================================================
    // MAIN-PARA - the cold start and the two entry arms - :82-138
    // =================================================================================================

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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
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
            // :97 blanked the buffer, so the user id the payload carried is gone.
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
            // PROCESS-ENTER-KEY sent at :171, then MAIN-PARA sent again at :105.
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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":124-126 PF12 transfers to COADM01C and does NOT save")
        void pf12CancelsWithoutSaving() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF12);

            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(padded("COADM01C", 8));
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":111-119 PF3 SAVES first, then echoes CDEMO-FROM-PROGRAM as the target")
        void pf3SavesThenReturnsToTheCallingProgram() {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());
            NavigationContext from = reenter().withFromProgram("COUSR00C");

            ProgramState state = reentryWith(screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", from),
                    CicsAid.DFHPF3);

            verify(repository).rewrite(any(SecUserRecord.class));
            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(padded("COUSR00C", 8));
        }

        @Test
        @DisplayName(":113-114 PF3 with a blank CDEMO-FROM-PROGRAM falls back to COADM01C")
        void pf3WithoutACallerFallsBackToTheAdminMenu() {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(
                    screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF3);

            verify(repository).rewrite(any(SecUserRecord.class));
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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
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

    // =================================================================================================
    // PROCESS-ENTER-KEY - :143-172
    // =================================================================================================

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
            // :339 sent once inside READ-USER-SEC-FILE, then :171 sent again.
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.errFlgOn()).isFalse();
            assertThat(state.hold()).isEmpty();
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
            // :158-161 blanked the fields and the paint at :167-170 was never reached.
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

    // =================================================================================================
    // UPDATE-USER-INFO - :176-243
    // =================================================================================================

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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
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
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(
                    screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.errFlgOn()).isFalse();
            verify(repository).rewrite(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "changing {0} sets USR-MODIFIED-YES")
        @ValueSource(strings = {"fName", "lName", "passwd", "usrType"})
        @DisplayName(":219-233 each of the four comparisons independently marks the record modified")
        void everyFieldComparisonMarksTheRecordModified(String changed) {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(screen(USER_ID,
                    "fName".equals(changed) ? "Samuel" : "Sam",
                    "lName".equals(changed) ? "Spadey" : "Spade",
                    "passwd".equals(changed) ? "PWDBBBBB" : STORED_PWD,
                    "usrType".equals(changed) ? "A" : "U",
                    reenter()), CicsAid.DFHPF5);

            assertThat(state.usrModifiedYes()).isTrue();
            assertThat(state.wsUsrModified()).isEqualTo(UserUpdateController.USR_MODIFIED_YES);
            verify(repository).rewrite(any(SecUserRecord.class));
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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":227 the password is compared in PLAINTEXT, exactly as the legacy program does")
        void thePasswordComparisonIsPlaintext() {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            reentryWith(screen(USER_ID, "Sam", "Spade", "PWDBBBBB", "U", reenter()), CicsAid.DFHPF5);

            ArgumentCaptor<SecUserRecord> captor = ArgumentCaptor.forClass(SecUserRecord.class);
            verify(repository).rewrite(captor.capture());
            assertThat(captor.getValue().secUsrPwd()).isEqualTo("PWDBBBBB");
        }

        @Test
        @DisplayName(":217-237 a failed read does NOT stop the rewrite, because :219 carries no guard")
        void aFailedReadStillReachesTheRewrite() {
            // READ-USER-SEC-FILE reports NOTFND and sets ERR-FLG-ON, but lines 219-233 are not inside
            // an IF NOT ERR-FLG-ON block: they compare the typed fields against the record area, which
            // after a failed read is still SEC-USER-DATA's initialised value. Every comparison
            // therefore differs, USR-MODIFIED-YES is set, and line 237 issues the REWRITE. Adding the
            // guard the code reads as if it had would be a behaviour change, so it is pinned here.
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.notFound());

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF5);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.usrModifiedYes()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));
            verify(repository).rewrite(any(SecUserRecord.class));
        }
    }

    // =================================================================================================
    // UPDATE-USER-SEC-FILE - :356-390
    // =================================================================================================

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
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = save();

            assertThat(state.wsMessage())
                    .isEqualTo(padded("User " + USER_ID + " has been updated ...", 80));
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(state.errFlgOn()).isFalse();
        }

        @Test
        @DisplayName(":377-382 NOTFND on the rewrite is 'User ID NOT found...' with the cursor on USRIDIN")
        void aMissingRecordIsReported() {
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.notFound());

            ProgramState state = save();

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
        }

        @Test
        @DisplayName(":383-389 WHEN OTHER displays RESP/REAS and says 'Unable to Update User...'")
        void anUnexpectedResponseIsReported() {
            when(repository.rewrite(any(SecUserRecord.class)))
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
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = save();

            assertThat(state.wsRespCd()).isEqualTo(FileStatus.NORMAL);
        }
    }

    // =================================================================================================
    // RETURN-TO-PREV-SCREEN - :250-261
    // =================================================================================================

    @Nested
    @DisplayName("RETURN-TO-PREV-SCREEN :250-261")
    class ReturnToPrevScreen {

        @Test
        @DisplayName(":252-253 a blank CDEMO-TO-PROGRAM becomes COSGN00C")
        void aBlankTargetBecomesSignOn() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF12);
            // PF12 sets COADM01C at :125, so reach the blank arm through the cold start instead.
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

    // =================================================================================================
    // POPULATE-HEADER-INFO - :296-315
    // =================================================================================================

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

    // =================================================================================================
    // resolveAttentionIdentifier - the token-to-EIBAID inverse
    // =================================================================================================

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
        @DisplayName("the five keys the screen names round-trip to their own byte")
        void theFiveNamedKeysRoundTrip(String token) {
            byte expected = switch (token) {
                case "ENTER" -> CicsAid.DFHENTER;
                case "PFK03" -> CicsAid.DFHPF3;
                case "PFK04" -> CicsAid.DFHPF4;
                case "PFK05" -> CicsAid.DFHPF5;
                default -> CicsAid.DFHPF12;
            };
            assertThat(UserUpdateController.resolveAttentionIdentifier(token)).isEqualTo(expected);
            assertThat(UserUpdateController.resolveAttentionIdentifier(
                    AidKey.valueOf(token).token())).isEqualTo(expected);
        }

        @ParameterizedTest(name = "\"{0}\" has no WHEN clause, so it is DFHNULL")
        @ValueSource(strings = {"PFK01", "CLEAR", "PA1  ", "nope", ""})
        @DisplayName("every other token is DFHNULL, which reaches WHEN OTHER at :127")
        void everyOtherTokenIsNull(String token) {
            assertThat(UserUpdateController.resolveAttentionIdentifier(token))
                    .isEqualTo(CicsAid.DFHNULL);
        }
    }

    // =================================================================================================
    // The figurative-constant rules and the images
    // =================================================================================================

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
            // Zero would be indistinguishable from a reported DFHRESP(NORMAL), and -000000001 would read
            // as a response code CICS does not define. Neither is what the field holds: it holds nothing.
            assertThat(UserUpdateController.displayLine(FileStatus.RESP_NOT_REPORTED, 0))
                    .isEqualTo("RESP:*********REAS:000000000")
                    .doesNotContain("RESP:000000000")
                    .doesNotContain("-000000001");

            // Width-preserving, so the composed line keeps the shape DISPLAY gives it.
            assertThat(UserUpdateController.displayLine(FileStatus.RESP_NOT_REPORTED, 0))
                    .hasSameSizeAs(UserUpdateController.displayLine(FileStatus.NORMAL, 0));

            // And it applies to the reason operand too, on the same terms.
            assertThat(UserUpdateController.displayLine(0, FileStatus.RESP_NOT_REPORTED))
                    .isEqualTo("RESP:000000000REAS:*********");
        }
    }

    // =================================================================================================
    // CDEMO-CU02-INFO - :50-58
    // =================================================================================================

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

    // =================================================================================================
    // ScreenField, Send and ProgramState projections
    // =================================================================================================

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
            // The navigation triple names the screen this SEND paints - COUSR02C:272-278 sends MAP
            // 'COUSR2A' of MAPSET 'COUSR02' - not CDEMO-LAST-MAPSET and CDEMO-LAST-MAP, which the
            // caller wrote and COUSR02C never touches.
            assertThat(response.nextProgram()).isEqualTo(UserUpdateController.WS_PGMNAME);
            assertThat(response.nextMapset()).isEqualTo(UserUpdateResponse.MAPSET_NAME);
            assertThat(response.nextMap()).isEqualTo(UserUpdateResponse.MAP_NAME);
            // And the caller's two commarea items are still carried byte for byte, which is where the
            // COBOL values remain observable.
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

    // =================================================================================================
    // The HTTP adapter - the two rules that live in it
    // =================================================================================================

    @Nested
    @DisplayName("PUT /api/users/{userId} - the adapter's own rules")
    class HttpAdapter {

        /** The screen inside the envelope the mapping returns. */
        private UserUpdateResponse screenOf(ScreenResponse<UserUpdateResponse> answer) {
            assertThat(answer).isNotNull();
            assertThat(answer.screen()).isNotNull();
            return answer.screen();
        }

        @Test
        @DisplayName("the path variable is the identity and lands in USRIDIN")
        void thePathVariableIsTheIdentity() {
            stubFoundRead();

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    screen("IGNORED1", "Sam", "Spade", STORED_PWD, "U", reenter()), null));

            verify(repository).readForUpdate(USER_ID);
            assertThat(response.usrIdIn()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("the path variable lands in CDEMO-CU02-USR-SELECTED too, which first entry reads")
        void thePathVariableAlsoFillsTheSelectedId() {
            // app/cbl/COUSR02C.cbl:99-102 copies the extension's selected id OVER USRIDINI on first
            // entry, and :157-171 then paints the record it names - including SEC-USR-PWD. An extension
            // the caller controls would therefore outrank the URI on exactly the arm that discloses a
            // password, so the URI is projected into it as well.
            stubFoundRead();
            Cu02Info selectsAnotherUser =
                    new Cu02Info(null, null, 0, Cu02Info.NEXT_PAGE_NO, "S", "USER0002");

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    withExtension(populated(enter()), selectsAnotherUser), null));

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
                    withExtension(populated(enter()), arrived), null));

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
            // Driven through the real HTTP binder, because that is the only place the two identities are
            // separately bound: PUT /api/users/A with CDEMO-CU02-USR-SELECTED naming B used to paint B's
            // plaintext SEC-USR-PWD (app/cbl/COUSR02C.cbl:99-102 then :157-171).
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
                    .andExpect(jsonPath("$.usrIdIn").value(USER_ID))
                    .andExpect(jsonPath("$.cu02Info.usrSelected").value(USER_ID));

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
        }

        @Test
        @DisplayName("a path identity wider than PIC X(08) is REFUSED, never truncated onto another user")
        void anOverWidePathIdentityIsRefused() {
            assertThatThrownBy(() -> controller.updateUser("USER00019",
                    screen("IGNORED1", "Sam", "Spade", STORED_PWD, "U", reenter()), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("USRIDIN");

            // Refused at the boundary: no read, and therefore no lock.
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("an absent EIBCALEN is derived from whether the payload carried a commarea")
        void eibcalenIsDerivedFromTheCarrier() {
            // No communication area -> the cold start at :90.
            UserUpdateResponse cold = screenOf(controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null), null));
            assertThat(cold.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);

            // A communication area -> the transaction runs.
            stubFoundRead();
            UserUpdateResponse warm = screenOf(controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", reenter()), null));
            assertThat(warm.fName()).isEqualTo(padded("Sam", 20));
        }

        @Test
        @DisplayName("a stated EIBCALEN that agrees with the carrier is taken")
        void aStatedEibcalenThatAgreesIsTaken() {
            UserUpdateResponse cold = screenOf(controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null),
                    UserUpdateController.NO_COMMAREA_LENGTH));
            assertThat(cold.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);

            stubFoundRead();
            UserUpdateResponse warm = screenOf(controller.updateUser(USER_ID,
                    populated(reenter()), UserUpdateController.PASSED_COMMAREA_LENGTH));
            assertThat(warm.fName()).isEqualTo(padded("Sam", 20));
        }

        @Test
        @DisplayName("an EIBCALEN that contradicts the carrier is refused, in either direction")
        void aContradictingEibcalenIsRefused() {
            // Claiming state that was not sent - which used to force the cold start and discard the
            // conversation the payload actually carried.
            assertThatThrownBy(() -> controller.updateUser(USER_ID, populated(reenter()),
                    UserUpdateController.NO_COMMAREA_LENGTH))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a communication area");

            assertThatThrownBy(() -> controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null),
                    UserUpdateController.PASSED_COMMAREA_LENGTH))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no communication area");

            verify(repository, never()).readForUpdate(anyString());
        }

        @ParameterizedTest(name = "eibcalen = {0} is refused")
        @ValueSource(ints = {-1, 1, 159, 160, 193, 195, 2000})
        @DisplayName("EIBCALEN can only be one of the two lengths CICS could have set")
        void anImpossibleEibcalenIsRefused(int stated) {
            assertThatThrownBy(() -> controller.updateUser(USER_ID, populated(reenter()), stated))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(UserUpdateController.EIBCALEN_PARAM);
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
                    UserUpdateController.PASSED_COMMAREA_LENGTH));

            assertThat(response.cu02Info()).isEqualTo(sent);
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("a payload naming no extension is given its VALUE clauses, keyed by the URI")
        void anAbsentExtensionIsInitialised() {
            stubFoundRead();

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    populated(reenter()), null));

            // Five items at their VALUE-clause state, and the sixth - the one identity item - carrying
            // the URI's user, because the path is authoritative in every carrier of the key.
            Cu02Info initial = Cu02Info.initial();
            assertThat(response.cu02Info())
                    .isEqualTo(new Cu02Info(initial.usridFirst(), initial.usridLast(),
                            initial.pageNum(), initial.nextPageFlg(), initial.usrSelFlg(), USER_ID));
        }

        @Test
        @DisplayName("the reply publishes this screen's own identity, not the caller's leftovers")
        void theNavigationTripleNamesThisScreen() {
            stubFoundRead();

            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    populated(reenter()), null));

            assertThat(response.nextProgram()).isEqualTo(UserUpdateController.WS_PGMNAME);
            assertThat(response.nextMapset()).isEqualTo(UserUpdateResponse.MAPSET_NAME);
            assertThat(response.nextMap()).isEqualTo(UserUpdateResponse.MAP_NAME);
            // And the commarea arrives back untouched: COUSR02C writes CDEMO-FROM-TRANID and
            // CDEMO-FROM-PROGRAM only at :255-256, inside RETURN-TO-PREV-SCREEN, and never writes
            // CDEMO-LAST-MAPSET or CDEMO-LAST-MAP at all - so on a paint they are still the caller's.
            assertThat(response.navigationContext())
                    .isEqualTo(populated(reenter()).navigationContext());
        }

        @Test
        @DisplayName("on a transfer the reply names the target program and leaves both maps blank")
        void aTransferNamesTheTargetAndNoMap() {
            UserUpdateResponse response = screenOf(controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null), null));

            assertThat(response.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);
            assertThat(response.nextMapset()).isBlank();
            assertThat(response.nextMap()).isBlank();
        }

        @Test
        @DisplayName("the presentation metadata travels in the envelope, beside the screen")
        void theMetadataTravelsInTheEnvelope() {
            stubFoundRead();

            ScreenResponse<UserUpdateResponse> answer =
                    controller.updateUser(USER_ID, populated(reenter()), null);

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
            assertThatThrownBy(() -> controller.updateUser(null, populated(reenter()), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("user id");
            assertThatThrownBy(() -> controller.updateUser(USER_ID, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request");
            verify(repository, never()).readForUpdate(anyString());
        }
    }

    // =================================================================================================
    // The optional hold, for completeness of the ProgramState surface
    // =================================================================================================

    @Test
    @DisplayName("a read that reports no hold leaves the state holding nothing")
    void aReadWithoutAHoldLeavesNoHold() {
        stubFoundRead();

        ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

        assertThat(state.hold()).isEqualTo(Optional.empty());
    }
    // =================================================================================================
    // The payload's own readings of the communication area, and its redacted diagnostics
    // =================================================================================================

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

            // COUSR02C:90 tests EIBCALEN = 0 before :95 tests the context, so a payload with no area
            // never reaches the context test in the source either - and false is the honest answer.
            assertThat(none.contextIsEnter()).isFalse();
            assertThat(none.contextIsReenter()).isFalse();
        }

        @Test
        @DisplayName("toString masks the password, and discloses only whether one was transmitted")
        void toStringMasksThePassword() {
            String withPassword = populated(reenter()).toString();

            assertThat(withPassword).doesNotContain(STORED_PWD).contains("passwd=");
            assertThat(withPassword).contains("cu02Info=", "usrIdIn=" + USER_ID);

            // null models the LOW-VALUES a field the terminal never transmitted arrives as, and telling
            // that apart from a transmitted value is what makes the :198 guard traceable.
            String withoutPassword =
                    screen(USER_ID, "Sam", "Spade", null, "U", reenter()).toString();
            assertThat(withoutPassword).contains("passwd=null");
        }
    }

    // =================================================================================================
    // The screen contract - the twelve DFHMDF fields, and the four items that are NOT payload - G9
    // =================================================================================================

    /**
     * The payload shape, taken from {@code app/cpy-bms/COUSR02.CPY} and {@code app/bms/COUSR02.bms}.
     *
     * <p>Gate <strong>G9</strong>: every payload field must trace to a {@code DFHMDF} definition, and
     * every field length must trace to a symbolic-map {@code xxxI} {@code PICTURE} clause. Three
     * independent counts agree on twelve - twelve {@code xxxI} items, twelve {@code xxxO} items, and
     * twelve of the twenty-nine {@code DFHMDF} definitions carrying a name - so twelve is asserted as a
     * number and not merely as a list length.
     *
     * <p>The four items that are deliberately <em>not</em> screen fields are the communication area, its
     * 34-byte extension, the resolved {@code EIBAID} token, and - on the response - the navigation
     * triple. They are conversation state, which rule R6 requires to travel in the payload.
     */
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

            // The trap this pins: COUSR02 names its identifier USRIDIN and places it SEVENTH, ahead of
            // both names. COUSR01 names its own USERID and places it NINTH, after them. A DTO built by
            // analogy with the neighbouring screen would have both the name and the position wrong.
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

            // Same field, same width, opposite direction - one DFHMDF LENGTH governs both halves.
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
            // app/cpy-bms/COUSR02.CPY declares CURTIMEI PIC X(8) and app/bms/COUSR02.bms gives CURTIME
            // LENGTH=8. COSGN00 declares nine for the same-looking HH:MM:SS field. The controller writes
            // exactly HH:MM:SS - eight characters - so a nine-wide DTO would carry a trailing space that
            // the map has no column for.
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

            // The three non-screen components: the communication area and the extension carry no width
            // of their own, and the AID token is CCARD-AID PIC X(5).
            assertThat(components[12].getAccessor().getAnnotation(Size.class)).isNull();
            assertThat(components[13].getAccessor().getAnnotation(Size.class).max())
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(components[14].getAccessor().getAnnotation(Size.class)).isNull();
        }

        @Test
        @DisplayName("no @NotBlank and no @NotNull: a blank field is a MESSAGE, never a 400")
        void theRequestDeclaresNoPresenceConstraint() {
            // COUSR02C:180-208 answers a blank field with a screen and a message, which is a successful
            // pseudo-conversational turn. A presence constraint would make the framework answer 400
            // before UPDATE-USER-INFO ran, and the five ordered messages would become unreachable.
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
            // PFK05, because UPDATE-USER-INFO is the paragraph that owns the five-arm chain. The default
            // ENTER arm runs PROCESS-ENTER-KEY, whose guard at :146 tests only USRIDINI.
            UserUpdateRequest blankFirstName = withAid(
                    screen(USER_ID, "  ", "Spade", STORED_PWD, "U", reenter()), AidKey.PFK05.token());

            httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(blankFirstName))
                            .param(UserUpdateController.EIBCALEN_PARAM,
                                    String.valueOf(UserUpdateController.PASSED_COMMAREA_LENGTH)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errMsg")
                            .value(errMsgImage(UserUpdateController.MSG_FIRST_NAME_EMPTY)));

            // A blank field is a completed pseudo-conversational turn, not a rejected request: 200 with a
            // message, and no dataset access at all.
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("a blank field is never a 400 - the payload declares no presence constraint")
        void aBlankFieldIsNeverABadRequest() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MockMvc http = httpOver(mapper);

            // The four fields HTTP can blank. The fifth - the user id - cannot be blanked in the body,
            // because :649 of the adapter copies the URI's identity over USRIDIN before MAIN-PARA runs;
            // the id arm at :180 is therefore driven by direct invocation, in LowValuesGuardChain.
            List<UserUpdateRequest> blanks = List.of(
                    withAid(screen(USER_ID, "  ", "Spade", STORED_PWD, "U", reenter()),
                            AidKey.PFK05.token()),
                    withAid(screen(USER_ID, "Sam", "  ", STORED_PWD, "U", reenter()),
                            AidKey.PFK05.token()),
                    withAid(screen(USER_ID, "Sam", "Spade", "  ", "U", reenter()),
                            AidKey.PFK05.token()),
                    withAid(screen(USER_ID, "Sam", "Spade", STORED_PWD, " ", reenter()),
                            AidKey.PFK05.token()));

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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("B6 - the response carries PASSWDO, and it is the STORED value in clear text")
        void theResponseCarriesThePlaintextPassword() throws Exception {
            // COUSR02C:169 is MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI. The screen shows the password
            // that is on file so the operator can see what they are about to change. Omitting it would
            // delete observable behaviour; hashing it would change it. Both are refused (practice B6,
            // gate G41). This is the only response in the user package that legitimately carries one:
            // SignOnResponse carries none, and UserDeleteResponse has no password component at all.
            stubFoundRead();
            ObjectMapper mapper = new ObjectMapper();

            httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(populated(reenter()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passwd").value(STORED_PWD));

            assertThat(UserUpdateResponse.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .contains("passwd");
            assertThat(UserUpdateResponse.PASSWD_FIELD).isEqualTo("PASSWDO");
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

            // For each of the twelve screen fields, neither the COBOL spelling of its xxxL / xxxF /
            // xxxA companions nor a camel-cased rendering of them may appear anywhere in the tree.
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

            // The twelve that ARE payload are present, under their Java names.
            assertThat(members).contains("trnName", "title01", "curDate", "pgmName", "title02",
                    "curTime", "usrIdIn", "fName", "lName", "passwd", "usrType", "errMsg");
        }

        @Test
        @DisplayName("the cursor and the colour travel as METADATA, exactly as xxxL and xxxC do")
        void theCursorAndColourAreMetadataRatherThanScreenFields() {
            // MOVE -1 TO USRIDINL is a cursor request, and MOVE DFHRED TO ERRMSGC is an attribute
            // assignment. Both are reported on ProgramState and in the envelope's metadata, and neither
            // becomes a screen field - which is what keeps the twelve at twelve.
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
                    String.class, UserUpdateRequest.class, Integer.class);
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

            // 405 is the dispatcher refusing the verb, so nothing behind it ran.
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }
    }

    // =================================================================================================
    // EVALUATE EIBAID :108-131 - the six arms, in source order, and the PF3 defect - G30, practice B5
    // =================================================================================================

    /**
     * The {@code EVALUATE EIBAID} at {@code app/cbl/COUSR02C.cbl:108-131}, arm by arm and in order.
     *
     * <p>Gate <strong>G30</strong> requires the {@code WHEN} order to be preserved with
     * {@code WHEN OTHER} last. {@code EVALUATE} is ordered - the first matching {@code WHEN} wins - and
     * COBOL has no fall-through, so each arm is asserted to produce its own outcome <em>and</em> not to
     * produce the outcomes of the arms after it.
     *
     * <p><strong>The highest-value assertion in this file lives here.</strong> {@code PF3} is
     * conventionally "exit without saving", and this program does the opposite: {@code :112} performs
     * {@code UPDATE-USER-INFO} before {@code :119} transfers. The neighbouring delete screen proves this
     * is a property of this program and not a house style - {@code app/cbl/COUSR03C.cbl:111-118} has the
     * identical {@code WHEN DFHPF3} routing block with <strong>no</strong>
     * {@code PERFORM DELETE-USER-INFO} in front of it, which {@code UserDeleteControllerTest} asserts
     * from its side. Practice B5 forbids reconciling the two: the pair of tests documents a real
     * inconsistency in the legacy system rather than hiding it.
     *
     * <p>This program does not copy {@code CSSTRPFY}; it tests {@code EIBAID} inline. The Java side
     * routes through {@link PfKeyResolver} for all seventeen screens, so the resolver's booleans are
     * asserted to give the same answers the inline tests give.
     */
    @Nested
    @DisplayName("EVALUATE EIBAID :108-131 - six arms in order, and PF3 saves before it exits")
    class AidDispatchOrder {

        @Test
        @DisplayName("arm 1 :109-110 DFHENTER looks the user up and neither saves nor transfers")
        void armOneIsEnter() {
            stubFoundRead();

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).rewrite(any(SecUserRecord.class));
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
            InOrder sequence = inOrder(repository);
            sequence.verify(repository).readForUpdate(USER_ID);
            sequence.verify(repository).rewrite(any(SecUserRecord.class));
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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4)).isTrue();
        }

        @Test
        @DisplayName("arm 4 :122-123 DFHPF5 saves and STAYS - it is the only save that does not transfer")
        void armFourIsPf5AndItStays() {
            stubFoundRead();
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            verify(repository).rewrite(any(SecUserRecord.class));
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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("the eight unhandled AIDs above are the ones this screen names nowhere")
        void theUnhandledAidsAreTheOnesTheSourceNamesNowhere() {
            // Named at :109-126: DFHENTER, DFHPF3, DFHPF4, DFHPF5, DFHPF12. Everything else falls to
            // :127. CLEAR and the three PA keys are the ones most likely to be mistaken for handled,
            // because neighbouring screens in this application do handle some of them.
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
            verify(repository, times(2)).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":113-117 the PF3 fallback: blank caller -> COADM01C, named caller -> the caller")
        void thePf3FallbackHasBothArms() {
            stubFoundRead();
            stubSuccessfulRewrite();
            UserUpdateRequest modifiedFields =
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter());

            // CDEMO-FROM-PROGRAM = SPACES: the ELSE at :113 is not taken.
            assertThat(reentryWith(modifiedFields, CicsAid.DFHPF3).nextProgram())
                    .isEqualTo(padded(UserUpdateController.LIT_ADMIN_PGM, 8));

            // CDEMO-FROM-PROGRAM names the list screen: :116-117 echoes it.
            UserUpdateRequest fromTheList = screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U",
                    reenter().withFromProgram("COUSR00C"));
            assertThat(reentryWith(fromTheList, CicsAid.DFHPF3).nextProgram())
                    .isEqualTo(padded("COUSR00C", 8));
        }

        @ParameterizedTest(name = "PfKeyResolver resolves {0} to itself")
        @EnumSource(value = AidKey.class, names = {"ENTER", "PFK03", "PFK04", "PFK05", "PFK12"})
        @DisplayName("PfKeyResolver reproduces the inline EIBAID tests - COUSR02C copies no CSSTRPFY")
        void theResolverAgreesWithTheInlineTests(AidKey key) {
            // Both dataset calls are stubbed because PFK03 and PFK05 both perform UPDATE-USER-INFO,
            // which reads and may rewrite; the other three keys touch neither.
            stubFoundRead();
            stubSuccessfulRewrite();
            byte aid = UserUpdateController.resolveAttentionIdentifier(key.token());

            assertThat(PfKeyResolver.resolve(aid)).contains(key);
            assertThat(PfKeyResolver.isAid(aid, aid)).isTrue();
            assertThat(reentryWith(populated(reenter()), aid).aidKey()).contains(key);
        }
    }

    // =================================================================================================
    // The communication area and its 34-byte extension - G22, G37, G50
    // =================================================================================================

    /**
     * The geometry of what travels between turns: 160 shared bytes plus 34 that belong to this program.
     *
     * <p>{@code app/cpy/COCOM01Y.cpy} is exactly 160 bytes and is copied by all seventeen online
     * programs, so {@link NavigationContext} must stay exactly that size. The six items
     * {@code app/cbl/COUSR02C.cbl:50-58} appends belong to {@code CU02} alone and therefore live on the
     * {@code CU02} DTO pair rather than being folded into the shared type - folding them in would widen
     * the copybook for sixteen programs that never declared them.
     */
    @Nested
    @DisplayName("CARDDEMO-COMMAREA 160 + CDEMO-CU02-INFO 34 = the 194 bytes CU02 passes")
    class CommareaGeometry {

        @Test
        @DisplayName("NavigationContext is exactly 160 bytes, and the extension is not part of it")
        void theSharedCommareaStaysAtOneHundredAndSixty() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);

            // The item sizes account for all 160 with nothing left over and nothing double-counted.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            // And none of the six CU02 items is a component of the shared type.
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

            // Both carriers expose the extension, because line 94 restores it and line 260 hands it back.
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

            // No component of either carrier, nor of the record, is a floating-point type: a PIC 9 or
            // PIC 9V9 value rendered through a double could not round-trip its declared digits.
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

            // VALUE 'N' at :54 is the initialised state, so NEXT-PAGE-NO is what a cold start sees.
            assertThat(Cu02Info.initial().nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_NO);

            // Both survive the turn untouched: COUSR02C reads CDEMO-CU02-USR-SELECTED at :99 and writes
            // none of the other five, so a paging position taken on the list screen is still correct
            // when the operator goes back to it.
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

            // CDEMO-PGM-ENTER takes the first-entry arm, which paints without receiving the map, so the
            // AID is never evaluated and an invalid key cannot be reported.
            ProgramState first = controller.handle(populated(enter()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF7, noSelection());
            assertThat(first.errFlgOn()).isFalse();
            assertThat(first.commarea().isReenter()).isTrue();

            // CDEMO-PGM-REENTER takes the re-entry arm, which does evaluate it.
            ProgramState again = reentryWith(populated(reenter()), CicsAid.DFHPF7);
            assertThat(again.errFlgOn()).isTrue();
        }
    }

    // =================================================================================================
    // UPDATE-USER-INFO :179-213 - the ordered chain against LOW-VALUES as well as SPACES - G30
    // =================================================================================================

    /**
     * The same five-arm chain as {@link UpdateUserInfo}, driven by the <em>other</em> empty state.
     *
     * <p>{@code WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES} is one condition with two figurative
     * constants, and the two are physically different bytes: {@code X'40'} repeated for {@code SPACES}
     * and {@code X'00'} repeated for {@code LOW-VALUES}. {@code SPACES} is what an operator produces by
     * typing over a field and blanking it; {@code LOW-VALUES} is what {@code :97}
     * ({@code MOVE LOW-VALUES TO COUSR2AO}) leaves and what a field the 3270 never transmitted arrives
     * as. A chain tested only against spaces would pass while silently treating an untransmitted field
     * as populated - which would send an all-{@code X'00'} name to the dataset.
     *
     * <p>An absent JSON member models the untransmitted field, and
     * {@link UserUpdateController#receivedImage(String, int)} renders it as {@code LOW-VALUES} at the
     * declared width. Both halves of the {@code OR} therefore reach the same {@code WHEN}.
     */
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
            verify(repository, never()).rewrite(any(SecUserRecord.class));
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

            // A mixture is neither, so a field holding one real character is populated however the rest
            // of it is filled - which is why the guard tests the whole field and not just its first byte.
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

            // No message from the chain, the cursor parked on FNAMEL by :211, and :215 onwards reached.
            assertThat(state.errFlgOn()).isFalse();
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("the id arm comes FIRST here - COUSR01C puts its names ahead of its identifier")
        void theIdentifierArmIsFirstOnThisScreen() {
            // COUSR02C:180 tests USRIDINI before :186 tests FNAMEI. COUSR01C orders the same five checks
            // with the names first. A screen blank in the id AND the first name therefore reports
            // different messages on the two programs, and this pins which one belongs to CU02.
            ProgramState state = reentryWith(screen("  ", "  ", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_ID_EMPTY, 80))
                    .isNotEqualTo(padded(UserUpdateController.MSG_FIRST_NAME_EMPTY, 80));
        }
    }

    // =================================================================================================
    // :219-243 - the four INDEPENDENT change tests. This is the branch core of the program. G50
    // =================================================================================================

    /**
     * The four comparisons at {@code app/cbl/COUSR02C.cbl:219-234} and the gate at {@code :236}.
     *
     * <p><strong>They are four separate {@code IF} statements, not an {@code IF/ELSE} chain.</strong>
     * Each one that finds a difference performs its own {@code MOVE} and sets
     * {@code USR-MODIFIED-YES}, and control then falls to the next {@code IF} regardless. All
     * 2<sup>4</sup> = 16 combinations are therefore reachable, and a test suite covering only "one field
     * changed" and "nothing changed" would leave most of this program's branch surface untouched - which
     * matters directly for the package's 0.90 branch gate (<strong>G49</strong>).
     *
     * <p><strong>The comparisons are made on space-padded {@code PIC X} images.</strong>
     * {@code FNAMEI} is {@code PIC X(20)} and {@code SEC-USR-FNAME} is {@code PIC X(20)}, so a typed
     * {@code 'Sam'} is {@code 'Sam'} followed by seventeen spaces on both sides of the {@code NOT =}. A
     * Java implementation that trimmed before comparing would find {@code "Sam"} unequal to
     * {@code "Sam                 "}, report a modification that did not happen, and rewrite the record
     * on every visit. Both halves of that are asserted here.
     */
    @Nested
    @DisplayName(":219-243 - four independent IFs, sixteen combinations, and the padded comparison")
    class ChangeDetection {

        @BeforeEach
        void stubTheDataset() {
            stubFoundRead();
            stubSuccessfulRewrite();
        }

        /** The stored record, as {@link #storedUser()} holds it, at declared widths. */
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
            // Proof that :219 and :231 are separate IFs: an IF/ELSE chain would apply the first
            // difference and leave the second on the screen, so the record on file would end up
            // half-updated with no error reported.
            reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "A", reenter()),
                    CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrFname()).isEqualTo(padded("Samuel", 20));
            assertThat(written.secUsrType()).isEqualTo("A");
            assertThat(written.secUsrLname()).isEqualTo(stored().secUsrLname());
            assertThat(written.secUsrPwd()).isEqualTo(stored().secUsrPwd());
            verify(repository, times(1)).rewrite(any(SecUserRecord.class));
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
            // The key and the filler are carried forward untouched: the screen has no field for either,
            // so :219-234 cannot have changed them.
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
                verify(repository, never()).rewrite(any(SecUserRecord.class));
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
            // MAIN-PARA line 85 resets the flag before anything else happens. One controller instance
            // serves every request - practice B9 forbids a field for it - so a leak would make the
            // request after a successful save rewrite an unmodified record.
            ProgramState modified = reentryWith(
                    screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);
            assertThat(modified.usrModifiedYes()).isTrue();

            ProgramState unmodified = reentryWith(populated(reenter()), CicsAid.DFHPF5);
            assertThat(unmodified.usrModifiedYes()).isFalse();
            assertThat(unmodified.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_PLEASE_MODIFY, 80));

            // Exactly one rewrite in total - the first request's. The second contributed none.
            verify(repository, times(1)).rewrite(any(SecUserRecord.class));

            // And the error flag resets on the same line, so an invalid key does not stay reported.
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

            // Both sides are twenty characters, and they are equal - so :219 finds no difference.
            assertThat(typed).hasSize(UserUpdateRequest.FNAME_LENGTH).isEqualTo(onFile);

            // The counter-proof: a trim-then-compare implementation would compare "Sam" against the
            // twenty-character image, find them unequal, and report a modification that did not happen.
            assertThat("Sam").isNotEqualTo(onFile);
            assertThat(onFile.trim()).isEqualTo("Sam");

            // Driven through the program, the padded comparison is what decides.
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF5);
            assertThat(state.usrModifiedYes()).isFalse();
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("trailing spaces the operator typed are not a modification either")
        void trailingSpacesAreNotAModification() {
            // 'Sam   ' typed into a PIC X(20) field is the same twenty bytes as 'Sam' typed into it, so
            // the MOVE at :220 would be a no-op and :221 must not fire.
            ProgramState state = reentryWith(
                    screen(USER_ID, "Sam      ", "Spade   ", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.usrModifiedYes()).isFalse();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_PLEASE_MODIFY, 80));
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("the comparison is case sensitive: COUSR02C has no FUNCTION UPPER-CASE")
        void theComparisonIsCaseSensitive() {
            // COSGN00C:132 and :135 fold their inputs with FUNCTION UPPER-CASE. COUSR02C does not, so
            // 'sam' against a stored 'Sam' IS a modification and must be written as typed.
            reentryWith(screen(USER_ID, "sam", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            SecUserRecord written = rewrittenRecord();
            assertThat(written.secUsrFname()).isEqualTo(padded("sam", 20))
                    .isNotEqualTo(padded("SAM", 20));
        }
    }

    // =================================================================================================
    // The dataset contract - a held read, a keyless rewrite, and one status vocabulary - G47
    // =================================================================================================

    /**
     * How this program reaches {@code USRSEC}, and what the repository is therefore obliged to expose.
     *
     * <p>{@code READ-USER-SEC-FILE} at {@code :322-331} carries {@code RIDFLD(SEC-USR-ID)},
     * {@code KEYLENGTH(LENGTH OF SEC-USR-ID)} and - decisively - {@code UPDATE}. That makes it a
     * <em>locking</em> read, so it maps to {@link SecUserRepository#readForUpdate(String)} and not to
     * {@link SecUserRepository#read(String)}.
     *
     * <p>{@code UPDATE-USER-SEC-FILE} at {@code :360-366} carries {@code DATASET}, {@code FROM} and
     * {@code LENGTH} and <strong>no {@code RIDFLD} at all</strong>. It rewrites whatever record the task
     * is holding. A repository method taking a key would be a different command with different
     * semantics: it could address a record the task never read and never locked. So the method takes
     * only the record, and a {@code readForUpdate} must have preceded it.
     *
     * <p>Gate <strong>G47</strong> requires each {@link FileStatus} outcome to be exercised per call
     * site. Both call sites - the {@code ENTER} lookup at {@code :163} and the save at {@code :217} -
     * are driven through all three of their arms, here and in {@link ProcessEnterKey} and
     * {@link UpdateUserSecFile}.
     */
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

            // No key operand of any shape: no String, no long, no int.
            for (Class<?> parameter : rewrite.getParameterTypes()) {
                assertThat(parameter).isNotIn(String.class, long.class, int.class, Long.class,
                        Integer.class);
            }

            // Contrast: the READ does carry a RIDFLD, so readForUpdate does take the key.
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

            InOrder sequence = inOrder(repository);
            sequence.verify(repository).readForUpdate(USER_ID);
            sequence.verify(repository).rewrite(any(SecUserRecord.class));
            sequence.verifyNoMoreInteractions();

            // And the unlocking read is never used on this screen: :328 has UPDATE on the command.
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
            // Eighty bytes on the wire, filler included: LENGTH(LENGTH OF SEC-USER-DATA) at :363.
            assertThat(SecUserRecord.encode(written, UserUpdateController.WORKING_STORAGE_CHARSET))
                    .hasSize(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("B5 - the vestigial CONTINUE at :335 stays a no-op: the arm still does all three")
        void theVestigialContinueChangesNothing() {
            // WHEN DFHRESP(NORMAL) opens with a bare CONTINUE at :335 and then does three real things at
            // :336-339. CONTINUE is a no-op in COBOL - it does NOT end the arm - so tidying it away, or
            // reading it as an early exit, would each change what the operator sees. The assertion is
            // that all three statements after it still take effect, in order.
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
            // And the record area was filled, which is the INTO(SEC-USER-DATA) the arm depends on.
            assertThat(state.secUserData().secUsrFname()).isEqualTo(padded("Sam", 20));
            assertThat(state.errFlgOn()).isFalse();
        }

        @Test
        @DisplayName("the two RESP spellings unify: DFHRESP(NOTFND) here, raw 13 in COSGN00C")
        void theTwoRespSpellingsUnifyThroughFileStatus() {
            // COUSR02C:334-340 and :369-377 write WHEN DFHRESP(NORMAL) / WHEN DFHRESP(NOTFND).
            // COSGN00C:222 and :247 write WHEN 0 / WHEN 13 for the identical conditions. One vocabulary
            // in common.FileStatus resolves both, so a repository outcome reads the same either way.
            assertThat(FileStatus.NORMAL).isZero();
            assertThat(FileStatus.NOTFND).isEqualTo(13);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL))
                    .isEqualTo(FileStatus.outcomeOfStatus(FileStatus.OK));
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NOTFND))
                    .isEqualTo(FileStatus.outcomeOfStatus(FileStatus.NOT_FOUND));

            // Both directions of the file carry the same classification for the same condition.
            assertThat(ReadResult.notFound().isNotFound()).isTrue();
            assertThat(WriteResult.notFound().isNotFound()).isTrue();
            assertThat(ReadResult.notFound().statusImage())
                    .isEqualTo(WriteResult.notFound().statusImage());
        }

        @Test
        @DisplayName("G47 - the read's three arms are each reached from the ENTER call site at :163")
        void theReadsThreeArmsAreEachReachedFromTheLookup() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.found(storedUser()));
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

            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());
            assertThat(reentryWith(modified, CicsAid.DFHPF5).wsMessage())
                    .isEqualTo(UserUpdateController.updatedConfirmation(USER_ID));

            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.notFound());
            assertThat(reentryWith(modified, CicsAid.DFHPF5).wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));

            when(repository.rewrite(any(SecUserRecord.class)))
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
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.found(shortKey));
            stubSuccessfulRewrite();

            ProgramState state = reentryWith(
                    screen("AB", "Samuel", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.wsMessage())
                    .isEqualTo(padded("User AB has been updated ...", 80))
                    .doesNotContain("User AB      has");
            // The key itself is still eight bytes on the record; only the message trims it.
            assertThat(rewrittenRecord().secUsrId()).isEqualTo(padded("AB", 8));
        }
    }

    // =================================================================================================
    // The seed - there is NO USRSEC fixture file; the seed is in-stream in app/jcl/DUSRSECJ.jcl
    // =================================================================================================

    /**
     * The shape of the {@code USRSEC} seed, which is a {@code DD *} stream and not a fixture file.
     *
     * <p>{@code app/data/ASCII} holds exactly nine files and none of them is a security-user dataset.
     * The seed is in-stream in {@code app/jcl/DUSRSECJ.jcl}: {@code //SYSUT1   DD *} at line 34, then ten
     * records on lines 35-44, each exactly <strong>57</strong> characters - {@code SEC-USR-ID X(08)} plus
     * {@code SEC-USR-FNAME X(20)} plus {@code SEC-USR-LNAME X(20)} plus {@code SEC-USR-PWD X(08)} plus
     * {@code SEC-USR-TYPE X(01)}. The stream omits {@code SEC-USR-FILLER X(23)} entirely, so every row
     * must be right-padded from 57 to the 80 bytes {@code app/cpy/CSUSR01Y.cpy} declares before it can be
     * compared byte for byte. Dropping the filler instead would shorten the record and move every
     * subsequent offset.
     *
     * <p>All ten seeded rows carry the identical eight-character password literal, which exactly fills
     * {@code PIC X(08)}. A password-change test therefore has to supply a deliberately different value,
     * which is why {@link #STORED_PWD} is an obvious placeholder rather than the seed's own literal.
     */
    @Nested
    @DisplayName("The USRSEC seed - app/jcl/DUSRSECJ.jcl:34-44, ten rows of 57 padded to 80")
    class SeededUsrsec {

        /** The first seeded row's five named values, from {@code app/jcl/DUSRSECJ.jcl:35}. */
        private static final String SEED_ID = "ADMIN001";
        private static final String SEED_FIRST_NAME = "MARGARET";
        private static final String SEED_LAST_NAME = "GOLD";
        private static final String SEED_TYPE = "A";

        /** A key no seeded row carries, so a lookup for it is {@code DFHRESP(NOTFND)}. */
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

            // The stream supplies the 57; the codec supplies the 23 the stream omits.
            assertThat(seeded.secUsrFiller())
                    .hasSize(SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .isBlank();
            assertThat(SecUserRecord.encode(seeded, UserUpdateController.WORKING_STORAGE_CHARSET))
                    .hasSize(SecUserRecord.RECORD_LENGTH);

            // Right-padding a 57-character row to 80 is exactly what the codec does, stated explicitly.
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
            when(repository.readForUpdate(SEED_ID)).thenReturn(ReadResult.found(seeded));

            ProgramState state = reentryWith(
                    screen(SEED_ID, "typed", "over", "TYPEDPWD", "U", reenter()), CicsAid.DFHENTER);

            assertThat(state.fName()).isEqualTo(padded(SEED_FIRST_NAME, 20));
            assertThat(state.lName()).isEqualTo(padded(SEED_LAST_NAME, 20));
            assertThat(state.usrType()).isEqualTo(SEED_TYPE);
            // :169 paints the STORED password over whatever the operator typed.
            assertThat(state.passwd()).isEqualTo(STORED_PWD).isNotEqualTo("TYPEDPWD");
        }

        @Test
        @DisplayName("changing a seeded administrator's first name is the modification that fires")
        void changingTheSeededFirstNameIsAModification() {
            SecUserRecord seeded = SecUserRecord.of(SEED_ID, SEED_FIRST_NAME, SEED_LAST_NAME,
                    STORED_PWD, SEED_TYPE, UserUpdateController.WORKING_STORAGE_CHARSET);
            when(repository.readForUpdate(SEED_ID)).thenReturn(ReadResult.found(seeded));
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

    // =================================================================================================
    // CSSETATY - the error highlight, which applies ONLY in REENTER - G38
    // =================================================================================================

    /**
     * The {@code CSSETATY} highlight rule, and why the re-entry flag has to be passed explicitly.
     *
     * <p>{@code app/cpy/CSSETATY.cpy} moves {@link BmsAttributes#DFHRED} onto a field's colour item and
     * an asterisk onto its output item when the field failed validation <em>and</em> the program is in
     * its re-entry state. On first entry the map has not been received, nothing has been typed, and there
     * is nothing to have failed - so a highlight painted then would mark fields the operator has not
     * touched.
     *
     * <p>{@link FieldAttributeSetter} therefore takes the re-entry state as an explicit {@code boolean}
     * parameter rather than reading it from anywhere. Gate <strong>G38</strong> requires both states to
     * be exercised, and both are, on the same failing field.
     */
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

            // The items it names are the map's own, spelled as app/cpy-bms/COUSR02.CPY spells them.
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

            // The two raw 88-level outcomes reach the same answer as the state vocabulary does.
            assertThat(FieldAttributeSetter.resolveFromFlags(state.notOk(), state.blank(), true))
                    .isEqualTo(resolved);
        }

        @Test
        @DisplayName("the message colour the program itself sets is the one the screen carries")
        void theProgramsOwnColoursAreTheOnesItSets() {
            // COUSR02C sets ERRMSGC three times and only three times: DFHNEUTR on a successful read
            // (:338), DFHRED on an unmodified record (:241), and DFHGREEN on a completed update (:371).
            // No other colour appears in the program, so no other colour may appear on a send.
            stubFoundRead();
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHENTER).errMsgColour())
                    .isEqualTo(BmsAttributes.DFHNEUTR);
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF5).errMsgColour())
                    .isEqualTo(BmsAttributes.DFHRED);

            stubSuccessfulRewrite();
            assertThat(reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5).errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);

            // And an untouched screen keeps the map's default rather than inventing a colour.
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF4).errMsgColour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
        }
    }

    // =================================================================================================
    // POPULATE-HEADER-INFO against the fixed clock, and the bean that publishes one - practice B7
    // =================================================================================================

    /**
     * Determinism: one {@link Clock}, read once, and never the wall clock.
     *
     * <p>{@code POPULATE-HEADER-INFO} at {@code :296-315} reads {@code FUNCTION CURRENT-DATE} and paints
     * {@code CURDATEO} and {@code CURTIMEO} from it. A test that let it read the real clock could not
     * assert either field, and a parity comparison could not either. Practice B7 therefore requires the
     * clock to be injected, and {@link WebConfig} publishes exactly one {@link Clock} bean for
     * production to supply while a test supplies a fixed one.
     */
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

    // =================================================================================================
    // The migration constraints this screen has to satisfy - G22, G37, G40, G41, G43, G44, G46, G53
    // =================================================================================================

    /**
     * The constraints that are best stated as negatives, each asserted rather than assumed.
     *
     * <p>Gate <strong>G43</strong> is the interesting one, because it does <em>not</em> apply here and
     * practice B4 requires that to be recorded rather than glossed over. See the class-level note: the
     * gate names {@code 9300-CHECK-CHANGE-IN-REC}, which exists in {@code COACTUPC} and
     * {@code COCRDUPC} and in no {@code user} program. Reproducing it on this screen would be inventing
     * a feature the legacy program does not have, and a version column would be a schema change that
     * AAP section 0.7.4 forbids outright. The absence is therefore the requirement.
     */
    @Nested
    @DisplayName("Migration constraints - no concurrency artefact, no session, no security framework")
    class MigrationConstraints {

        @Test
        @DisplayName("G43 does NOT apply: no version column, no ETag and no re-read-and-compare exists")
        void noOptimisticConcurrencyArtefactExists() {
            // 1. No version, revision, timestamp or ETag component on any carrier or on the record.
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

            // 2. No 9300-CHECK-CHANGE-IN-REC equivalent on the controller. The concurrency control is the
            //    CICS lock the READ ... UPDATE at :328 takes, and nothing else.
            for (Method method : UserUpdateController.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("%s suggests a change-in-record check COUSR02C does not perform",
                                method.getName())
                        .doesNotContain("checkchange")
                        .doesNotContain("changeinrec")
                        .doesNotContain("concurren");
            }

            // 3. One read and one rewrite per save - never a second, confirming read.
            stubFoundRead();
            stubSuccessfulRewrite();
            reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);
            verify(repository, times(1)).readForUpdate(USER_ID);
            verify(repository, times(1)).rewrite(any(SecUserRecord.class));
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

            // The conversation is in the body: the commarea and its extension both come back out.
            JsonNode body = mapper.readTree(response.getContentAsString());
            assertThat(body.has("navigationContext")).isTrue();
            assertThat(body.has("cu02Info")).isTrue();
        }

        @Test
        @DisplayName("G40 - navigation is a RESPONSE FIELD: no redirect, no forward, no Location header")
        void navigationIsAResponseFieldRatherThanARedirect() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            // The cold start at :90-92, which is the one path that transfers on a bare first request.
            UserUpdateRequest noCommarea = screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null);

            httpOver(mapper).perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(noCommarea)))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.nextProgram")
                            .value(UserUpdateController.LIT_SIGNON_PGM));

            // An XCTL is 200 with a named target, never 3xx: the client decides what to call next.
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
            // The DSNAME lives in app/csd/CARDDEMO.CSD:89 and, for this module, only in
            // application.yml. What COUSR02C holds at :39 is WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  ' -
            // the eight-character CICS FILE name the DATASET operand of :323 and :361 names, which is
            // not a dataset name.
            //
            // The two probes are qualifier FRAGMENTS rather than dataset names, so their absence is
            // checkable without this file itself embedding a usable name.
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
            // One controller instance, two calls whose data has nothing in common. If any WORKING-STORAGE
            // item were a field rather than per-call state, the second call would inherit the first's
            // error flag, message, record area and cursor.
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

            // Neither state object is the other, and neither is the controller's.
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
