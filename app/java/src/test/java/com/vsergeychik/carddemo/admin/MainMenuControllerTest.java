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
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * The Spring MVC slice test for {@link MainMenuController} - CSD transaction {@code CM00}
 * ({@code app/csd/CARDDEMO.CSD:399-400}), program {@code app/cbl/COMEN01C.cbl}, "Main Menu for the
 * Regular users", projected onto the single endpoint {@code GET /api/menu}.
 *
 * <h2>What this file asserts, and what it deliberately does NOT</h2>
 *
 * <p>{@link MainMenuController} holds <strong>no decision logic</strong>. It binds the payload, calls
 * {@link MainMenuService#handle(MainMenuInput)} exactly once, and projects the returned outcome. Every
 * branch {@code COMEN01C} takes - the {@code IF EIBCALEN = 0} diversion at line 82, the
 * first-entry/re-entry split at line 87, the ordered {@code EVALUATE EIBAID} at lines 93 to 103, the
 * four-step option normalisation at lines 117 to 125, the three validation terms at lines 127 to 134,
 * <strong>the user-type authorisation filter at lines 136 to 143</strong>, the {@code 'DUMMY'} prefix
 * test at line 146, the {@code DELIMITED BY SPACE} coming-soon message at lines 159 to 163 and the
 * twelve-arm {@code BUILD-MENU-OPTIONS} composition at lines 236 to 277 - is asserted in
 * {@code MainMenuServiceTest}. That file, not this one, therefore carries package {@code admin}'s
 * JaCoCo {@code BRANCH >= 0.90} load (gates <strong>G51</strong> and <strong>G49</strong>).
 *
 * <p>So the decision core is <strong>stubbed</strong> here and none of its logic is re-asserted. Every
 * appearance below of {@code 'Please enter a valid option number...'},
 * {@code 'No access - Admin Only option... '} or the coming-soon text is a <em>stubbed return value</em>
 * whose <em>projection</em> is under test - never a branch outcome this file computes or re-derives.
 * What remains is exactly five things, and every one of them is observable behaviour that no service
 * test can see:
 *
 * <ol>
 *   <li>straight-line response assembly - the stubbed outcome projected onto
 *       {@link MainMenuResponse};</li>
 *   <li>the one fixed-width operation the controller performs itself: the {@code PIC X(80)} to
 *       {@code PIC X(78)} <strong>right</strong>-truncation of the {@code WS-MESSAGE} image into
 *       {@code ERRMSGO} through {@link FixedWidthCodec} ({@code app/cbl/COMEN01C.cbl:187});</li>
 *   <li>the HTTP and JSON contract - member names, widths, and that space-padded {@code PIC X(n)}
 *       values survive the round trip untrimmed and unomitted (gate <strong>G9</strong>);</li>
 *   <li>statelessness (<strong>G37</strong>), the {@code ENTER}/{@code REENTER} split
 *       (<strong>G38</strong>), and {@code nextProgram}/{@code nextMapset}/{@code nextMap} standing in
 *       for {@code EXEC CICS XCTL} (<strong>G40</strong>);</li>
 *   <li><strong>the two negative assertions that define this file</strong>, below.</li>
 * </ol>
 *
 * <h2>Negative assertion one: {@code CDEMO-USER-TYPE} passes through UNALTERED</h2>
 *
 * <p>{@code app/cbl/COMEN01C.cbl:150} is {@code *  MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} - it is
 * <strong>commented out</strong>, as is line 149 for {@code CDEMO-USER-ID}. Those two lines are the
 * program's only assignments to either field, so {@code COMEN01C} never writes them: it only ever
 * <em>receives</em> them, and the authorisation filter at line 136 reads whatever arrived.
 * {@link UserTypePassesThroughUnaltered} therefore drives {@code 'A'}, {@code 'U'}, a single space and
 * an undefined {@code 'X'} and asserts each is echoed byte-identically, with <strong>no</strong>
 * default substituted, nothing blanked and nothing rejected. A future reader tempted to "helpfully"
 * default the field should read that nested class first.
 *
 * <h2>Negative assertion two: the authorisation filter is NOT the controller's job</h2>
 *
 * <p>{@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'}
 * ({@code app/cbl/COMEN01C.cbl:136-137}) lives <strong>entirely</strong> in {@link MainMenuService}.
 * {@link TheControllerDoesNotFilter} proves the controller does not re-implement it: it renders all ten
 * option lines for a {@code 'U'} caller, merely <em>projects</em> the refusal message rather than
 * deriving it, compares no option's user-type column against {@code 'A'}, and never consults
 * {@link MenuOptions} at all. That last point matters more than it looks: all ten shipped
 * {@code CDEMO-MENU-OPT-USRTYPE} values are {@code 'U'}
 * ({@code app/cpy/COMEN02Y.cpy:29,35,41,47,53,59,65,72,78,84}), so a controller-side filter would be
 * silently inert on every happy path and would only surface as a defect once an admin-only row was
 * added.
 *
 * <h2>Why this is not a copy of {@code AdminMenuControllerTest}</h2>
 *
 * <p>{@code diff app/cpy-bms/COADM01.CPY app/cpy-bms/COMEN01.CPY} differs at exactly two lines - 17 and
 * 139, the two group names - and {@code COMEN01.bms} differs from {@code COADM01.bms} only in the
 * mapset and map names and in one <em>unnamed</em> heading literal ({@code 'Main Menu'} at
 * {@code LENGTH=9}, {@code app/bms/COMEN01.bms:75-79}, against {@code 'Admin Menu'} at
 * {@code LENGTH=10}). The DTO shapes therefore genuinely match - and the types are nonetheless
 * <strong>separate by design</strong> (practice <strong>B4</strong>), which
 * {@link ThePayloadContract#theTwoMenuScreensKeepSeparateTypes()} asserts outright so that a later
 * "de-duplicating refactor" has to argue with a failing test. The identity constants differ
 * ({@code CM00}/{@code COMEN01C}/{@code COMEN01}/{@code COMEN1A}), this program composes
 * <strong>twelve</strong> {@code BUILD-MENU-OPTIONS} arms where its sibling stops at ten, and the two
 * negative assertions above have no counterpart in the sibling file. Nothing is shared between the two
 * test classes: no base class, no fixture utility, no second {@code application-test.yml}.
 *
 * <h2>Why the decision core is a {@link MockitoSpyBean} and not a {@code @MockitoBean}</h2>
 *
 * <p>The migration plan's brief asks for {@code @MockitoBean MainMenuService}. That cannot work, and the
 * reason is in the production code rather than in this test: {@code MainMenuController}'s constructor
 * <em>consumes</em> the service while the bean is being created -
 * {@code this.codec = this.mainMenuService.codec()}, then
 * {@code codec.movePicX(SPACE, MainMenuRequest.OPTION_LENGTH)} and the cold-start request - so a
 * collaborator that answers {@code null} to {@code codec()} fails the context before any test method
 * runs. A field-level bean override is created bare and can first be stubbed in {@code @BeforeEach},
 * which is after the controller singleton has already been built.
 *
 * <p>{@link MockitoSpyBean} is used instead. It belongs to the same modern bean-override family as
 * {@code @MockitoBean}, so the {@code @MockBean} that Spring Boot 3.5.16 deprecates is still avoided -
 * which is what practice <strong>B2</strong> is actually after - and a spy answers {@code codec()} with
 * the real immutable codec the constructor needs while {@code handle} is stubbed with
 * {@code doReturn}. The stubbing seam, and therefore <strong>G51</strong>, is unchanged: no test below
 * asserts an outcome the service decided. Outside the one Spring slice the controller is constructed
 * directly over a Mockito stub whose {@code codec()} is answered at creation time, matching this
 * module's convention of plain JUnit 5 with {@code MockMvcBuilders.standaloneSetup} wherever a full
 * context earns nothing.
 *
 * <h2>Determinism</h2>
 *
 * <p>The {@link Clock} is fixed at {@code 2022-07-19T23:12:33Z} - {@code COMEN01C}'s own version footer
 * ({@code app/cbl/COMEN01C.cbl:281}) - so the date and time header is assertable byte for byte and the
 * value documents where it came from (practice <strong>B7</strong>). It carries {@link ZoneOffset#UTC},
 * so the suite is unaffected by the host time zone.
 *
 * <h2>Gates and practices this file carries</h2>
 *
 * <p><strong>Applied:</strong> G5/B3 (the reference trees are read, never written, and nothing is
 * copied into test resources - every byte fact below is transcribed as a literal with its source line),
 * G9, G37, G38, G40, G41, G49, G50, G51, G52 (no wildcard imports), G53/B9 (no mutable static state -
 * every static is deeply immutable: a fixed {@code Clock}, an immutable codec, and interned strings and
 * immutable lists), G54 (non-interactive), B1/B2 (nothing outside the closed dependency set, and no
 * deprecated annotation), B4 (this file only), B5 (four preserved-behaviour subjects: the
 * {@code Accountis} defect, the {@code app/cpy/COMEN02Y.cpy:69} option-eight decoy, the
 * {@code app/cpy/COTTL01Y.cpy:21} title decoy, and the commented-out {@code COMEN01C:149-150} whose
 * <em>absence</em> of effect is asserted), B6 (security posture untouched: {@link SecUserRecord} is on
 * the classpath and no test asserts a read of it), B7, B8 (named {@code *_LENGTH} constants and an
 * explicit {@link Charset} everywhere), B10 (no {@code @Disabled}, no stub test, no {@code TODO}), B12
 * (every non-obvious literal carries its source line, because the baseline is statically derived).
 *
 * <p><strong>Not applied, because they have no subject in {@code COMEN01C}:</strong> G47 - the program
 * performs no file access at all, so there is no repository call site and no file-status outcome to
 * drive, and {@code common.FileStatus} is not imported; G35 - it raises none of the nine abend sites,
 * and {@code common.AbendException} is not imported; G22 to G29 - it contains no arithmetic, no
 * {@code COMP-3} item and no decimal {@code PICTURE}, so there is no numeric parity to assert and
 * neither {@code common.CobolDecimal} nor {@code BigDecimal} appears anywhere below; G19 to G21, G43 to
 * G46 and G48 - no persisted record, no optimistic concurrency, no DDL, no dataset name and no
 * statement subroutine.
 *
 * @see MainMenuService the translated program, and where all of its branches are asserted
 * @see MainMenuRequest the inbound projection of {@code 01 COMEN1AI}
 * @see MainMenuResponse the outbound projection of {@code 01 COMEN1AO REDEFINES COMEN1AI}
 * @see AdminMenuController the sibling adapter over the byte-identical admin screen
 */
@DisplayName("MainMenuController - GET /api/menu, CSD transaction CM00, program COMEN01C")
class MainMenuControllerTest {

    // =================================================================================================
    // Immutable fixtures. Every static below is deeply immutable - a fixed Clock, an immutable codec,
    // interned strings and List.of / List.copyOf lists - so none of them is the mutable static state
    // that gate G53 and practice B9 forbid. Nothing here is reassigned by a test, and no test can
    // observe another's writes.
    // =================================================================================================

    /**
     * The pinned instant: {@code COMEN01C}'s own version footer, {@code 2022-07-19 23:12:33}
     * ({@code app/cbl/COMEN01C.cbl:281}). Choosing the program's own timestamp makes the two expected
     * header values below self-documenting rather than arbitrary (practice B7).
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    /** The clock every controller in this file is built over: fixed, and zone-explicit (B7, B8). */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /**
     * {@code WS-CURDATE-MM-DD-YY} under {@link #FIXED_CLOCK}: eight bytes, {@code MM/DD/YY}, with the
     * two {@code '/'} separators the group's own {@code FILLER}s declare
     * ({@code app/cpy/CSDAT01Y.cpy:32,34}). The year is the last two digits only, because
     * {@code app/cbl/COMEN01C.cbl:223} moves {@code WS-CURDATE-YEAR(3:2)}.
     */
    private static final String EXPECTED_CURDATE = "07/19/22";

    /**
     * {@code WS-CURTIME-HH-MM-SS} under {@link #FIXED_CLOCK}: eight bytes, {@code HH:MM:SS}, with the
     * two {@code ':'} separators declared at {@code app/cpy/CSDAT01Y.cpy:38,40}.
     */
    private static final String EXPECTED_CURTIME = "23:12:33";

    /**
     * The code page the fixed-width work is done in, stated explicitly and never defaulted (B8).
     *
     * <p>{@code US-ASCII} is what {@link MainMenuService#DEFAULT_MESSAGE_CHARSET_NAME} declares for the
     * message images this screen composes. It is a property of the <em>record</em> layer only: the HTTP
     * and JSON layer asserted further down is UTF-8 and says so, and {@code IBM037} belongs solely to
     * dataset input and output, which this screen performs none of.
     */
    private static final Charset MESSAGE_CHARSET = StandardCharsets.US_ASCII;

    /** The codec used to build every expected image. {@link FixedWidthCodec} is immutable. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(MESSAGE_CHARSET);

    /** The single {@code PIC X} pad character; a COBOL alphanumeric field has no absent state. */
    private static final String SPACE = " ";

    /**
     * The one member the response envelope adds beside the unwrapped screen.
     *
     * <p>{@code ScreenResponse} declares {@code @JsonUnwrapped T screen}, so the twenty map fields sit at
     * the top level and this is their single sibling. It is not a {@code DFHMDF} field and the screen
     * record does not declare it, which is why the wire assertions below strip it before comparing
     * against {@link MainMenuResponse}.
     */
    private static final String SCREEN_METADATA_MEMBER = "screenMetadata";

    /**
     * {@code CDEMO-MENU-OPT-NAME PIC X(35)} - {@code app/cpy/COMEN02Y.cpy:90}, and the width each
     * {@code FILLER} literal in the populated table is declared at ({@code :26}, {@code :32}, and so
     * on).
     *
     * <p>Declared locally rather than read from {@link MenuOptions} on purpose: this file references
     * that type in exactly one place, and that place is a <em>negative</em> assertion proving the
     * controller never consults it. Borrowing a width from it here would make the reference ambiguous.
     */
    private static final int MENU_OPT_NAME_LENGTH = 35;

    /** {@code CDEMO-MENU-OPT-NUM PIC 9(02)} - {@code app/cpy/COMEN02Y.cpy:89}. */
    private static final int MENU_OPT_NUM_LENGTH = 2;

    /** The {@code '. '} literal {@code BUILD-MENU-OPTIONS} strings in - {@code COMEN01C:244}. */
    private static final String MENU_OPT_SEPARATOR = ". ";

    /**
     * The ten {@code CDEMO-MENU-OPT-NAME} literals, byte for byte, each exactly
     * {@value #MENU_OPT_NAME_LENGTH} characters including its trailing spaces.
     *
     * <p>Transcribed from {@code app/cpy/COMEN02Y.cpy} lines 27, 33, 39, 45, 51, 57, 63, 70, 76 and 82.
     *
     * <p><strong>Practice B5 - the option-eight decoy.</strong> {@code app/cpy/COMEN02Y.cpy:69} holds a
     * <em>commented-out</em> {@code 'Transaction Add (Admin Only)       '} immediately above the
     * <strong>active</strong> {@code 'Transaction Add                    '} at line 70. Both are exactly
     * thirty-five characters, so a wrong pick would not fail on width. The ACTIVE line 70 value is the
     * one below, and the decoy is left exactly where it is - reproducing it would invent an
     * admin-only restriction the shipped table does not declare.
     *
     * <p>{@link #theTenOptionLabelsAreTheDeclaredWidth()} re-checks every width, so a mistyped literal
     * fails here rather than surfacing as a puzzling forty-character diff downstream.
     */
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

    /**
     * The ten {@code CDEMO-MENU-OPT-PGMNAME} targets of
     * {@code XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))} ({@code app/cbl/COMEN01C.cbl:153}), in
     * subscript order.
     *
     * <p>Transcribed from {@code app/cpy/COMEN02Y.cpy} lines 28, 34, 40, 46, 52, 58, 64, 71, 77 and 83.
     */
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

    /**
     * The message {@code COMEN01C:159-163} composes for option one, byte for byte.
     *
     * <p><strong>Practice B5 - the preserved defect.</strong> The {@code STRING} statement is
     * {@code 'This option '} {@code DELIMITED BY SIZE}, then {@code CDEMO-MENU-OPT-NAME(WS-OPTION)}
     * <strong>{@code DELIMITED BY SPACE}</strong>, then {@code 'is coming soon ...'}
     * {@code DELIMITED BY SIZE}. The middle operand stops at its first space, so
     * {@code 'Account View                       '} contributes only {@code 'Account'} - and because
     * the prefix already ended with its own space and the suffix begins with {@code 'is'}, the two words
     * fuse into <strong>{@code Accountis}</strong> with no separator.
     *
     * <p>That is a defect in the 2022 source and it is preserved exactly. Nothing in the controller may
     * trim, strip, normalise, re-split or re-join it, and no assertion below repairs it.
     */
    private static final String COMING_SOON_OPTION_ONE = "This option Accountis coming soon ...";

    /**
     * A distinguishable eighty-character probe for the {@code PIC X(80)} to {@code PIC X(78)}
     * narrowing: seventy-eight {@code '#'} characters followed by {@code 'AB'}.
     *
     * <p>COBOL fills an alphanumeric receiver from its <em>leftmost</em> position and discards whatever
     * does not fit, so a faithful narrowing loses the {@code 'A'} and the {@code 'B'} and keeps the
     * seventy-eight {@code '#'}. Had the truncation gone the other way the surviving text would end in
     * {@code 'AB'}, which is exactly what {@link TheMessageTruncation} distinguishes.
     */
    private static final String EIGHTY_BYTE_PROBE =
            "#".repeat(MainMenuResponse.ERR_MSG_LENGTH) + "AB";

    // =================================================================================================
    // Fixture builders. Static, instance-free and side-effect-free: each call returns a fresh value, so
    // no test can hand another a mutated fixture (practice B9).
    // =================================================================================================

    /**
     * The dataset catalogue {@link MainMenuService}'s constructor insists on, carrying only the
     * {@code USRSEC} entry that {@code app/cbl/COMEN01C.cbl:39} declares and the program never reads.
     *
     * <p>{@link SecUserRecord#RECORD_LENGTH} is eighty because {@code app/cpy/CSUSR01Y.cpy} says so, and
     * the service refuses a binding that disagrees. The entry is present so the bean can be built and is
     * asserted by nothing: {@code COMEN01C} performs no file access whatsoever, so no test here reads a
     * security-user record, compares a credential or exercises a file-status outcome (practice B6, and
     * why gate G47 has no subject in this package).
     *
     * @return a fresh catalogue, never {@code null}
     */
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

    /**
     * A Mockito stub of the decision core whose {@code codec()} is answered <em>at creation time</em>,
     * because {@code MainMenuController}'s constructor reads it while building its space-filled
     * {@code OPTIONI} image and its cold-start request.
     *
     * <p>{@code handle} is deliberately left unstubbed: each test states the outcome it is projecting, so
     * no test inherits another's. That is also what keeps this file inside gate G51 - an outcome is
     * always <em>given</em> here, never computed.
     *
     * @return a fresh stub, never {@code null}
     */
    private static MainMenuService stubbedService() {
        MainMenuService stub = mock(MainMenuService.class);
        when(stub.codec()).thenReturn(CODEC);
        return stub;
    }

    /**
     * A controller over the given collaborator, on the fixed clock.
     *
     * @param service the decision core, stubbed or spied
     * @return the controller under test, never {@code null}
     */
    private static MainMenuController controllerOver(final MainMenuService service) {
        return new MainMenuController(service, FIXED_CLOCK);
    }

    /**
     * The twelve {@code OPTN00nO} lines as {@code MOVE LOW-VALUES TO COMEN1AO}
     * ({@code app/cbl/COMEN01C.cbl:89}) leaves them: all spaces, at the declared forty characters.
     *
     * @return a fresh mutable list of twelve blank lines
     */
    private static List<String> blankOptionLines() {
        List<String> lines = new ArrayList<>(MainMenuResponse.OPTION_LINE_COUNT);
        for (int slot = MainMenuResponse.FIRST_OPTION_LINE_SLOT;
                slot <= MainMenuResponse.LAST_OPTION_LINE_SLOT;
                slot++) {
            lines.add(CODEC.movePicX(SPACE, MainMenuResponse.OPTION_LINE_LENGTH));
        }
        return lines;
    }

    /**
     * One composed menu line, exactly as {@code BUILD-MENU-OPTIONS} assembles it:
     * {@code CDEMO-MENU-OPT-NUM} zero-filled to two digits, then the {@code '. '} literal, then the
     * thirty-five-character {@code CDEMO-MENU-OPT-NAME} - thirty-nine characters into a
     * {@code PIC X(40)} receiver, so one trailing space completes it
     * ({@code app/cbl/COMEN01C.cbl:243-246}).
     *
     * @param cobolSubscript the COBOL subscript, 1 to 10
     * @return the forty-character line
     */
    private static String menuLine(final int cobolSubscript) {
        String number = CODEC.movePic9(cobolSubscript, MENU_OPT_NUM_LENGTH);
        String label = MENU_OPT_NAMES.get(cobolSubscript - 1);
        return CODEC.movePicX(number + MENU_OPT_SEPARATOR + label,
                MainMenuResponse.OPTION_LINE_LENGTH);
    }

    /**
     * The ten lines this program can write, followed by two slots nothing can write.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:248-275} is an {@code EVALUATE WS-IDX} with arms for subscripts one
     * to twelve - including {@code WHEN 11} at lines 269 to 270 and {@code WHEN 12} at lines 271 to 272 -
     * but the enclosing {@code PERFORM VARYING} at lines 238 to 239 is bounded by
     * {@code CDEMO-MENU-OPT-COUNT}, which is ten ({@code app/cpy/COMEN02Y.cpy:21}). Those last two arms
     * are therefore unreachable, and {@code OPTN011O} and {@code OPTN012O} stay spaces. All twelve are
     * carried anyway, because the map declares twelve ({@code app/cpy-bms/COMEN01.CPY:120,126}) and the
     * table is {@code OCCURS 12 TIMES} ({@code app/cpy/COMEN02Y.cpy:88}); pruning them would be a change
     * of wire format (practice B5).
     *
     * @return a fresh list of twelve lines, ten composed and two blank
     */
    private static List<String> paintedOptionLines() {
        List<String> lines = blankOptionLines();
        for (int subscript = 1; subscript <= MENU_OPT_NAMES.size(); subscript++) {
            lines.set(subscript - 1, menuLine(subscript));
        }
        return lines;
    }

    /**
     * A {@code WS-MESSAGE} image: the given text moved into the {@code PIC X(80)} working-storage field
     * {@code app/cbl/COMEN01C.cbl:38} declares.
     *
     * @param text the message text as the program moves it
     * @return the eighty-character image
     */
    private static String message80(final String text) {
        return CODEC.movePicX(text, MainMenuService.MESSAGE_LENGTH);
    }

    /**
     * An outcome shaped exactly as {@link MainMenuService} hands one back, at the widths its own
     * canonical constructor enforces: twelve lines of forty, an eighty-character {@code WS-MESSAGE}, a
     * two-character {@code OPTIONO} and an eight-character transfer target.
     *
     * @param optionLines the twelve {@code OPTN00nO} images
     * @param message80   the {@code WS-MESSAGE} image, {@code PIC X(80)} - {@code COMEN01C:38}
     * @param colour      {@code ERRMSGC OF COMEN1AO}: {@code COLOR=RED} by mapset declaration
     *                    ({@code app/bms/COMEN01.bms:154-156}), overridden with {@code DFHGREEN} at
     *                    {@code COMEN01C:158}
     * @param option      the normalised {@code OPTIONO} image - {@code COMEN01C:125}
     * @param nextProgram the {@code XCTL} target, or spaces when the program returned to CICS
     * @param reset       whether {@code MOVE LOW-VALUES TO COMEN1AO} ran - {@code COMEN01C:89}
     * @param context     {@code CARDDEMO-COMMAREA}, handed back on every return - {@code COMEN01C:107-110}
     * @return the outcome to stub {@code handle} with, never {@code null}
     */
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

    /**
     * The first-entry paint: {@code app/cbl/COMEN01C.cbl:87-90}, so the message is spaces, the output
     * fields were cleared by {@code MOVE LOW-VALUES}, the typed option is blank and no transfer is named.
     *
     * @param context the communication area the program hands back
     * @return the outcome, never {@code null}
     */
    private static MainMenuOutcome paintedOutcome(final NavigationContext context) {
        return outcome(paintedOptionLines(),
                message80(SPACE),
                MainMenuService.MAP_MESSAGE_COLOUR,
                SPACE,
                SPACE,
                true,
                context);
    }

    /**
     * A re-entry that reports a message: the shape {@code COMEN01C:100-102}, {@code :130-133},
     * {@code :138-142} and {@code :157-164} all produce. {@code resetAllOutputFields} is false, because
     * only the first-entry path runs {@code MOVE LOW-VALUES}.
     *
     * @param message the {@code WS-MESSAGE} text the service decided on
     * @param colour  the {@code ERRMSGC} byte the service resolved
     * @return the outcome, never {@code null}
     */
    private static MainMenuOutcome reportingOutcome(final String message, final byte colour) {
        return outcome(paintedOptionLines(),
                message80(message),
                colour,
                SPACE,
                SPACE,
                false,
                NavigationContext.empty().withPgmReenter());
    }

    /**
     * An outcome that left through {@code EXEC CICS XCTL}, so no map was sent and the successor is named.
     *
     * <p>Covers both {@code XCTL} sites: {@code COMEN01C:152-155}
     * {@code PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))} with the communication area, and
     * {@code COMEN01C:175-177} {@code PROGRAM(CDEMO-TO-PROGRAM)} without it.
     *
     * @param nextProgram the transfer target
     * @param context     the communication area, echoed on every response regardless
     * @return the outcome, never {@code null}
     */
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

    /**
     * A payload whose twenty items are each space-filled to the width the symbolic map declares.
     *
     * @param context the inbound {@code CARDDEMO-COMMAREA}, or {@code null} for {@code EIBCALEN = 0}
     * @param aid     the raw {@code EIBAID} byte
     * @return the request, never {@code null}
     */
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

    /**
     * A payload carrying a typed option, continuing the pseudo-conversation.
     *
     * @param option  the {@code OPTIONI} field exactly as the terminal sent it, unnormalised
     * @param context the inbound communication area
     * @return the request, never {@code null}
     */
    private static MainMenuRequest withOption(final String option, final NavigationContext context) {
        MainMenuRequest blank = blankScreen(context, CicsAid.DFHENTER);
        return new MainMenuRequest(blank.trnName(), blank.title01(), blank.curDate(),
                blank.pgmName(), blank.title02(), blank.curTime(),
                blank.optn001(), blank.optn002(), blank.optn003(), blank.optn004(),
                blank.optn005(), blank.optn006(), blank.optn007(), blank.optn008(),
                blank.optn009(), blank.optn010(), blank.optn011(), blank.optn012(),
                option, blank.errMsg(), context, blank.eibAid());
    }

    /**
     * The communication area a {@code 'U'}-role sign-on hands to this endpoint.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:225-228} writes {@code CDEMO-FROM-PROGRAM},
     * {@code CDEMO-USER-ID}, {@code CDEMO-USER-TYPE} and {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}, then
     * line 230 tests {@code IF CDEMO-USRTYP-ADMIN} and transfers to {@code PROGRAM('COADM01C')} at line
     * 232 for an administrator or to {@code PROGRAM('COMEN01C')} at line 237 for everyone else. This is
     * the shape that arrives on the second of those two paths. The sign-on service is neither imported
     * nor instantiated here: only the shape of the context this endpoint must accept is asserted.
     *
     * @return the inbound context, never {@code null}
     */
    private static NavigationContext signOnHandoffContext() {
        return NavigationContext.empty()
                .withFromProgram(CODEC.movePicX(MainMenuService.SIGNON_PROGRAM,
                        NavigationContext.FROM_PROGRAM_LENGTH))
                .withUserId(CODEC.movePicX("USER0001", NavigationContext.USER_ID_LENGTH))
                .withUserTypeUser()
                .withPgmEnter();
    }

    /**
     * Drives one request through a controller whose service is stubbed to return {@code outcome}, and
     * returns the whole envelope.
     *
     * <p>The stub is built per call, so nothing is shared between tests.
     *
     * @param outcome the outcome the decision core is stubbed to return
     * @return the response envelope, never {@code null}
     */
    private static ScreenResponse<MainMenuResponse> answerFor(final MainMenuOutcome outcome) {
        return answerFor(outcome,
                blankScreen(NavigationContext.empty().withPgmReenter(), CicsAid.DFHENTER));
    }

    /**
     * Drives a specific request through a controller whose service is stubbed to return {@code outcome}.
     *
     * @param outcome the outcome the decision core is stubbed to return
     * @param request the inbound payload
     * @return the response envelope, never {@code null}
     */
    private static ScreenResponse<MainMenuResponse> answerFor(final MainMenuOutcome outcome,
            final MainMenuRequest request) {
        MainMenuService service = stubbedService();
        doReturn(outcome).when(service).handle(any(MainMenuInput.class));
        return controllerOver(service).getMainMenu(request);
    }

    /**
     * The freshly painted screen: the shape {@code SEND-MENU-SCREEN} produces on first entry.
     *
     * @return the projected screen, never {@code null}
     */
    private static MainMenuResponse paint() {
        return answerFor(paintedOutcome(NavigationContext.empty().withPgmReenter())).screen();
    }

    /**
     * The twenty payload member names, in map order, as {@link MainMenuResponse} spells them.
     *
     * <p>One per {@code xxxI} item in {@code app/cpy-bms/COMEN01.CPY}: {@code TRNNAMEI} at line 24,
     * {@code TITLE01I} at 30, {@code CURDATEI} at 36, {@code PGMNAMEI} at 42, {@code TITLE02I} at 48,
     * {@code CURTIMEI} at 54, {@code OPTN001I} to {@code OPTN012I} at 60 through 126 in steps of six,
     * {@code OPTIONI} at 132 and {@code ERRMSGI} at 138.
     *
     * @return a fresh list of exactly twenty names
     */
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

    /**
     * The response body parsed as the envelope it is: twenty unwrapped screen members, the four
     * navigation and communication members, and {@value #SCREEN_METADATA_MEMBER}.
     *
     * @param body the serialized response
     * @return the parsed envelope, never {@code null}
     * @throws JsonProcessingException if the body is not the JSON object the envelope declares
     */
    private static ObjectNode envelopeOf(final String body) throws JsonProcessingException {
        return (ObjectNode) new ObjectMapper().readTree(body);
    }

    /**
     * The envelope with its metadata sibling removed, leaving exactly the screen projection.
     *
     * <p>Stripping it explicitly - rather than relaxing the mapper's unknown-property handling - keeps the
     * reason visible: the metadata is a <em>sibling</em> of the screen, so a screen record that refused it
     * is behaving correctly.
     *
     * @param body the serialized response
     * @return the screen node, never {@code null}
     * @throws JsonProcessingException if the body is not the JSON object the envelope declares
     */
    private static ObjectNode screenNodeOf(final String body) throws JsonProcessingException {
        ObjectNode screenOnly = envelopeOf(body).deepCopy();
        screenOnly.remove(SCREEN_METADATA_MEMBER);
        return screenOnly;
    }

    /**
     * The screen projection read back from the wire, completing a full serialize-and-deserialize round
     * trip through the same member names and widths the symbolic map declares.
     *
     * @param body the serialized response
     * @return the deserialized screen, never {@code null}
     * @throws JsonProcessingException if the body does not read back as this screen
     */
    private static MainMenuResponse screenFromWire(final String body) throws JsonProcessingException {
        return new ObjectMapper().treeToValue(screenNodeOf(body), MainMenuResponse.class);
    }

    /**
     * The controller's own declared fields, with the coverage agent's injected members filtered out.
     *
     * <p>JaCoCo adds a {@code private static transient boolean[] $jacocoData} to every instrumented
     * class. It is marked synthetic, so filtering on that flag - and on the {@code '$'} prefix the JVM
     * specification reserves for compiler-generated names - leaves exactly the fields the source
     * declares. Without this filter the assertions below would be measuring the instrumentation rather
     * than the code.
     *
     * @return a fresh list of source-declared fields, never {@code null}
     */
    private static List<Field> declaredFieldsOfController() {
        List<Field> fields = new ArrayList<>();
        for (Field field : MainMenuController.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Every declared type {@link MainMenuController} mentions in its own signature surface: field types,
     * constructor parameter types, and method parameter and return types.
     *
     * <p>Used only by the two negative assertions, which need to show that a type the controller must not
     * consult appears nowhere in that surface.
     *
     * @return a fresh list of types, never {@code null}
     */
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

    // =================================================================================================
    // Construction: two collaborators, both required, and no state of the controller's own.
    // =================================================================================================

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
            // This is why the Spring slice below uses @MockitoSpyBean rather than @MockitoBean: the
            // constructor CONSUMES the collaborator, so an override created bare answers null to
            // codec() and the context fails before @BeforeEach can stub anything. See the class javadoc.
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
            // CDEMO-USER-TYPE is functionally load-bearing on this screen - it is the first conjunct of
            // the authorisation filter at app/cbl/COMEN01C.cbl:136 - so carry-over between two callers
            // of different types would be a real defect rather than a cosmetic one.
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

    // =================================================================================================
    // The handler contract: one GET, one path, and an absent body standing in for EIBCALEN = 0.
    // =================================================================================================

    @Nested
    @DisplayName("The handler - one GET, one path, and EIBCALEN = 0 for an absent body")
    class TheHandlerContract {

        @Test
        @DisplayName("the mapping is the GET the plan assigns to transaction CM00")
        void theMappingIsTheDocumentedRoute() throws NoSuchMethodException {
            // app/csd/CARDDEMO.CSD:399-400 DEFINE TRANSACTION(CM00) ... PROGRAM(COMEN01C).
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
                    .isEqualTo(CODEC.movePicX(SPACE, MainMenuRequest.OPTION_LENGTH));
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
            // The program consults exactly three things: EIBCALEN through the presence of DFHCOMMAREA
            // (COMEN01C:67-69 and :82), EIBAID once at :93, and OPTIONI at :118-122. The option arrives
            // UNNORMALISED - the backwards space scan, the JUST RIGHT receiver and the
            // INSPECT ... REPLACING at :117-124 are the service's work, and trimming or padding it here
            // would destroy the input that sequence consumes.
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
        @DisplayName("an omitted OPTIONI member becomes spaces, because a PIC X(2) field is never absent")
        void anOmittedOptionBecomesSpaces() {
            MainMenuController controller = controllerOver(stubbedService());

            MainMenuInput translated = controller.toInput(
                    withOption(null, NavigationContext.empty().withPgmReenter()));

            assertThat(translated.option())
                    .isEqualTo(CODEC.movePicX(SPACE, MainMenuRequest.OPTION_LENGTH))
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

    // =================================================================================================
    // The payload contract (G9): every member traces to a DFHMDF definition and every width to an xxxI
    // PICTURE clause. Widths are asserted through named constants only - never a bare integer (B8).
    // =================================================================================================

    @Nested
    @DisplayName("The payload contract (G9) - twenty members, at the widths the symbolic map declares")
    class ThePayloadContract {

        @Test
        @DisplayName("twenty named DFHMDF fields of twenty-eight, and twenty payload members")
        void twentyOfTwentyEightFieldsTravel() {
            // app/bms/COMEN01.bms declares 28 DFHMDF entries; exactly 20 carry a name label. The eight
            // unnamed ones are literal screen furniture and never travel: 'Tran:' (:29-33), 'Date:'
            // (:42-46), 'Prog:' (:52-56), 'Time:' (:65-69), 'Main Menu' LENGTH=9 POS=(4,35) (:75-79),
            // 'Please select an option :' (:140-144), a LENGTH=0 stopper (:150-153) and
            // 'ENTER=Continue  F3=Exit' (:158-162).
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
            // app/cpy-bms/COMEN01.CPY, in map order:
            assertThat(MainMenuResponse.TRN_NAME_LENGTH).isEqualTo(4);      // TRNNAMEI  :24  PIC X(4)
            assertThat(MainMenuResponse.TITLE_LENGTH).isEqualTo(40);        // TITLE01I  :30  PIC X(40)
            assertThat(MainMenuResponse.CUR_DATE_LENGTH).isEqualTo(8);      // CURDATEI  :36  PIC X(8)
            assertThat(MainMenuResponse.PGM_NAME_LENGTH).isEqualTo(8);      // PGMNAMEI  :42  PIC X(8)
            assertThat(MainMenuResponse.CUR_TIME_LENGTH).isEqualTo(8);      // CURTIMEI  :54  PIC X(8)
            assertThat(MainMenuResponse.OPTION_LINE_LENGTH).isEqualTo(40);  // OPTN00nI  :60-:126
            assertThat(MainMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);   // twelve of them
            assertThat(MainMenuResponse.OPTION_LENGTH).isEqualTo(2);        // OPTIONI   :132 PIC X(2)
            assertThat(MainMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);      // ERRMSGI   :138 PIC X(78)
            // TITLE02I at :48 is PIC X(40) as well, which is why one constant serves both titles.
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
            // The EVALUATE WS-IDX at app/cbl/COMEN01C.cbl:248-275 DOES have WHEN 11 (:269-270) and
            // WHEN 12 (:271-272) - unlike the admin screen's, which stops at ten - but the enclosing
            // PERFORM VARYING at :238-239 is bounded by CDEMO-MENU-OPT-COUNT = 10, so those two arms are
            // unreachable and the table's OCCURS 12 tail is never written. Present, empty, and preserved.
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
            // app/bms/COMEN01.bms:145-149: OPTION is ATTRB=(FSET,IC,NORM,NUM,UNPROT),
            // HILIGHT=UNDERLINE, JUSTIFY=(RIGHT,ZERO), LENGTH=2, POS=(20,41). Every other named field is
            // ATTRB=(ASKIP,FSET,NORM), so a terminal operator cannot have typed into it - and
            // POPULATE-HEADER-INFO (:212-231) rewrites the header from working storage regardless.
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
            // diff app/cpy-bms/COADM01.CPY app/cpy-bms/COMEN01.CPY differs at exactly two lines - 17 and
            // 139, the COADM1AI/COADM1AO against COMEN1AI/COMEN1AO group names - and COMEN01.bms differs
            // from COADM01.bms only in the mapset and map names and in one UNNAMED heading literal
            // ('Main Menu' LENGTH=9 at app/bms/COMEN01.bms:75-79 against 'Admin Menu' LENGTH=10). The
            // field contract is therefore shared and the types are still separate, so that a later change
            // to one screen cannot silently alter the other. This test exists to make that deliberate
            // duplication cost a failing test to remove.
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

    // =================================================================================================
    // The one fixed-width operation the controller performs itself: WS-MESSAGE PIC X(80) into
    // ERRMSGO PIC X(78), truncated on the RIGHT. app/cbl/COMEN01C.cbl:187.
    // =================================================================================================

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
                // COMEN01C:131 - the three-term validation guard at :127-:129 failed.
                "Please enter a valid option number...",
                // COMEN01C:140 - the authorisation filter at :136-:137 refused. NOTE the trailing space
                // inside the literal; it is part of the source text and is preserved.
                "No access - Admin Only option... ",
                // COMEN01C:159-163 - the DELIMITED BY SPACE coming-soon text, defect included.
                "This option Accountis coming soon ...",
        })
        void everyMessageIsRightPaddedToSeventyEight(final String text) {
            // These are STUBBED SERVICE RETURN VALUES, not branch outcomes. Which of them the program
            // chooses is MainMenuServiceTest's subject; that each survives the narrowing byte for byte is
            // this file's (gate G51).
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
            // app/cbl/COMEN01C.cbl:140 - the literal ends with a space, inside the quotes. Padding to 78
            // makes that invisible unless the assertion looks for it explicitly, which is why the
            // character at the text's own last position is checked rather than the padded tail.
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
            // app/cbl/COMEN01C.cbl:159-163 STRINGs 'This option ' DELIMITED BY SIZE, then
            // CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE, then 'is coming soon ...'. The middle
            // operand stops at its first space, so 'Account View' contributes only 'Account' and the two
            // words fuse. Nothing in the controller may repair that, and nothing here does.
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
            // app/cbl/COMEN01C.cbl:101 MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE, on the WHEN OTHER arm of
            // the EVALUATE EIBAID. Three literals are easy to confuse and none may substitute for
            // another:
            //   app/cpy/COTTL01Y.cpy:23-24  CCDA-THANK-YOU      PIC X(40), says "CCDA"
            //   app/cpy/CSMSG01Y.cpy:18-19  CCDA-MSG-THANK-YOU  PIC X(50), says "CardDemo"
            //   app/cpy/CSMSG01Y.cpy:20-21  CCDA-MSG-INVALID-KEY PIC X(50)
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

    // =================================================================================================
    // POPULATE-HEADER-INFO (app/cbl/COMEN01C.cbl:212-231) and BUILD-MENU-OPTIONS (:236-277) as the
    // controller projects them - the two identity literals, the two titles, the clock-derived header,
    // and the ten option lines.
    // =================================================================================================

    @Nested
    @DisplayName("The header and the option lines - identity literals, titles, clock, and ten lines")
    class TheHeaderFields {

        @Test
        @DisplayName("TRNNAMEO is 'CM00' and PGMNAMEO is 'COMEN01C' - L218-L219")
        void theTwoIdentityLiteralsAreTheProgramsOwn() {
            // app/cbl/COMEN01C.cbl:37 WS-TRANID PIC X(04) VALUE 'CM00' and :36 WS-PGMNAME PIC X(08)
            // VALUE 'COMEN01C', moved into the map at :218 and :219. The CSD agrees:
            // app/csd/CARDDEMO.CSD:399-400 DEFINE TRANSACTION(CM00) ... PROGRAM(COMEN01C).
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
            // app/cpy/COTTL01Y.cpy:18-19 CCDA-TITLE01 PIC X(40) VALUE
            //   '      AWS Mainframe Modernization       '  - six leading, seven trailing spaces
            // app/cpy/COTTL01Y.cpy:22    CCDA-TITLE02 PIC X(40) VALUE
            //   '              CardDemo                  '
            // DECOY: line 21, immediately above that value, holds a COMMENTED-OUT
            //   '  Credit Card Demo Application (CCDA)   ' which is ALSO exactly forty characters, so
            // picking the wrong one would not fail on width. The active line 22 value is asserted, and
            // the decoy is left exactly where it is.
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
            // app/cpy/CSDAT01Y.cpy:30-35 declares WS-CURDATE-MM-DD-YY as MM, FILLER '/', DD, FILLER '/',
            // YY, and :36-41 declares WS-CURTIME-HH-MM-SS as HH, FILLER ':', MM, FILLER ':', SS. The two
            // digits of the year come from WS-CURDATE-YEAR(3:2) at app/cbl/COMEN01C.cbl:223.
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
            // Composed by BUILD-MENU-OPTIONS at app/cbl/COMEN01C.cbl:243-246 as
            // CDEMO-MENU-OPT-NUM + '. ' + CDEMO-MENU-OPT-NAME - thirty-nine characters into a
            // PIC X(40) receiver. Labels from app/cpy/COMEN02Y.cpy:27,33,39,45,51,57,63,70,76,82.
            // Subscript 8 is the one to watch: line 69 holds a COMMENTED-OUT
            // 'Transaction Add (Admin Only)       ' immediately above the ACTIVE
            // 'Transaction Add                    ' at line 70. Both are thirty-five characters; the
            // ACTIVE one is expected here and the decoy stays where it is (practice B5).
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
            // Deliberately not the copybook values: an arbitrary distinguishable image proves the
            // controller copies rather than composes, which is the property under test.
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

    // =================================================================================================
    // NEGATIVE ASSERTION ONE - CDEMO-USER-TYPE passes through UNALTERED.
    //
    // app/cbl/COMEN01C.cbl:149-150 are:
    //       *            MOVE WS-USER-ID   TO CDEMO-USER-ID
    //       *            MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
    // Both are COMMENTED OUT, and they are the program's only assignments to either field. COMEN01C
    // therefore never writes them: it only ever RECEIVES them, and the authorisation filter at line 136
    // reads whatever arrived. Nothing in the controller may derive, default, look up or normalise either
    // field - and this nested class exists so that a future reader who is tempted to "helpfully" add a
    // default has to delete a test that says why not (practice B5).
    // =================================================================================================

    @Nested
    @DisplayName("CDEMO-USER-TYPE passes through UNALTERED - COMEN01C:149-150 are commented out")
    class UserTypePassesThroughUnaltered {

        @ParameterizedTest(name = "[{index}] user type ''{0}'' echoes unaltered")
        @DisplayName("G50: every inbound user type is echoed byte-identically, defined or not")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
                // value | 88 CDEMO-USRTYP-ADMIN | 88 CDEMO-USRTYP-USER  (app/cpy/COCOM01Y.cpy:26-28)
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
            // Two collaborators, and neither can answer "what type of user is this?". Injecting the
            // security-user repository would invent a USRSEC read that COMEN01C never performs -
            // app/cbl/COMEN01C.cbl declares WS-USRSEC-FILE at :39 and never references it again, and
            // COPY CSUSR01Y at :58 brings in SEC-USER-DATA whose only mention is the commented-out :150.
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

    // =================================================================================================
    // NEGATIVE ASSERTION TWO - the user-type authorisation filter is NOT the controller's job.
    //
    // app/cbl/COMEN01C.cbl:136-143:
    //       IF CDEMO-USRTYP-USER AND
    //          CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
    //           SET ERR-FLG-ON          TO TRUE
    //           MOVE SPACES             TO WS-MESSAGE
    //           MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
    //           PERFORM SEND-MENU-SCREEN
    //       END-IF
    // That decision lives ENTIRELY in MainMenuService and is asserted in MainMenuServiceTest. All ten
    // shipped CDEMO-MENU-OPT-USRTYPE values are 'U' (app/cpy/COMEN02Y.cpy:29,35,41,47,53,59,65,72,78,84),
    // so a controller-side copy of this filter would be silently inert on every happy path and would
    // surface as a defect only once an admin-only row was added. That is exactly why it is asserted
    // absent rather than left to inspection.
    // =================================================================================================

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
            // A stubbed return value, not a branch outcome. The controller is handed the message the
            // service already decided on and copies it into ERRMSGO; it performs no user-type test of its
            // own on the way, which is why the SAME message is projected for an ADMIN caller here - a
            // controller-side filter would never have produced it for one.
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
            // MenuOptions is the Java projection of app/cpy/COMEN02Y.cpy - the option table whose X(01)
            // CDEMO-MENU-OPT-USRTYPE column line 137 compares against 'A'. It is the SERVICE's input,
            // never the controller's: the option lines arrive pre-rendered. This is the ONE place in this
            // file that names the type, and it names it only to prove its absence.
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
            // The screen has no credential field: the twenty payload members are the twenty named DFHMDF
            // fields, and none of them is a password. app/cbl/COMEN01C.cbl reads no user record, so there
            // is nothing to hash and nothing to compare (practice B6, gate G41).
            assertThat(payloadMemberNames())
                    .doesNotContain("passwd", "password", "secUsrPwd", "token", "authorization");
            assertThat(declaredTypesOfController()).doesNotContain(SecUserRecord.class);
        }
    }

    // =================================================================================================
    // ENTER versus REENTER (G38, G50). CDEMO-PGM-CONTEXT PIC 9(01) with 88 CDEMO-PGM-ENTER VALUE 0 and
    // 88 CDEMO-PGM-REENTER VALUE 1 (app/cpy/COCOM01Y.cpy:29-31) separates first entry - paint, at
    // app/cbl/COMEN01C.cbl:87-90 - from re-entry - validate, at :91-103.
    // =================================================================================================

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
            // app/cbl/COMEN01C.cbl:87-90 - SET CDEMO-PGM-REENTER TO TRUE, MOVE LOW-VALUES TO COMEN1AO,
            // PERFORM SEND-MENU-SCREEN. The area handed back therefore already reads re-enter, which is
            // what makes the next keystroke take the other branch.
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
                // notOk | blank | reenter | colour item assigned | output item assigned
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
            // app/cpy/CSSETATY.cpy:L18-L26 - the outer test is (NOT-OK OR BLANK) AND CDEMO-PGM-REENTER,
            // which moves DFHRED into the colour item; the '*' move is NESTED inside it and fires only
            // for BLANK. FieldAttributeSetter takes re-entry as an explicit boolean parameter, so the
            // dependency is visible at the call site rather than implied.
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
            // COMEN01C copies neither CSSETATY nor CSSTRPFY - it compares EIBAID inline at :93 - so no
            // per-field attribute quad is set in EITHER state. Inventing one would be a new feature.
            assertThat(answerFor(paintedOutcome(NavigationContext.empty().withPgmEnter()))
                    .screenMetadata().fields()).isEmpty();
            assertThat(answerFor(reportingOutcome(MainMenuService.INVALID_OPTION_MESSAGE,
                    MainMenuService.MAP_MESSAGE_COLOUR)).screenMetadata().fields()).isEmpty();
        }

        @Test
        @DisplayName("the message colour defaults to DFHRED and carries DFHGREEN when the service says so")
        void theMessageColourFollowsTheService() {
            // app/bms/COMEN01.bms:154-156 declares ERRMSG with COLOR=RED, and app/cbl/COMEN01C.cbl:158
            // MOVE DFHGREEN TO ERRMSGC OF COMEN1AO overrides it on the coming-soon path alone.
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

    // =================================================================================================
    // Navigation (G40). EXEC CICS XCTL becomes three response fields; there is no server-side forward,
    // no redirect chain and no session affinity. COMEN01C has two XCTL sites: :153
    // PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) and :176 PROGRAM(CDEMO-TO-PROGRAM) inside
    // RETURN-TO-SIGNON-SCREEN.
    // =================================================================================================

    @Nested
    @DisplayName("Navigation (G40) - XCTL as three response fields, and never a redirect")
    class Navigation {

        @Test
        @DisplayName("a painted screen names its own mapset and map - L190-L191")
        void aPaintedScreenNamesItsOwnMapAndMapset() {
            // app/cbl/COMEN01C.cbl:190-191 SEND MAP('COMEN1A') MAPSET('COMEN01'), and
            // app/csd/CARDDEMO.CSD:133 DEFINE MAPSET(COMEN01).
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
            // The ten option targets come from app/cpy/COMEN02Y.cpy:28,34,40,46,52,58,64,71,77,83 and
            // reach the client through :153; COSGN00C is the RETURN-TO-SIGNON-SCREEN target of :176,
            // defaulted at :173 when CDEMO-TO-PROGRAM arrived empty.
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
            // app/cpy/COCOM01Y.cpy declares CDEMO-GENERAL-INFO 34 + CDEMO-CUSTOMER-INFO 84 +
            // CDEMO-ACCOUNT-INFO 12 + CDEMO-CARD-INFO 16 + CDEMO-MORE-INFO 14 = 160 bytes.
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
            // app/cbl/COSGN00C.cbl:230 IF CDEMO-USRTYP-ADMIN transfers to PROGRAM('COADM01C') at :232;
            // everyone else reaches PROGRAM('COMEN01C') at :237. This endpoint must accept exactly the
            // area that second path produces. SignOnService is neither imported nor instantiated - only
            // the SHAPE of the context is asserted.
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

    // =================================================================================================
    // The wire, over MockMvc. Standalone rather than a full context, matching this module's convention:
    // the JSON shape, the surviving padding and the absence of a session are all observable without one,
    // and the Spring slice below covers what genuinely needs the real dispatcher.
    // =================================================================================================

    @Nested
    @DisplayName("The wire - the screen stays flat, the padding survives, and nothing is stashed")
    class TheWire {

        /** A dispatcher over a controller whose decision core is stubbed. */
        private MockMvc mockMvcOver(final MainMenuService service) {
            return MockMvcBuilders.standaloneSetup(controllerOver(service)).build();
        }

        /** A dispatcher whose stubbed core always returns {@code outcome}. */
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
                    .andExpect(jsonPath("$.trnName").value(MainMenuResponse.TRANSACTION_ID))
                    .andExpect(jsonPath("$.pgmName").value(MainMenuResponse.PROGRAM_NAME));
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
            for (String member : payloadMemberNames()) {
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
                    .andExpect(jsonPath("$.errMsg")
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
                    .contains("\"trnName\"", "\"curDate\"", "\"pgmName\"", "\"curTime\"",
                            "\"errMsg\"", "\"optn001\"", "\"nextMapset\"")
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

    // =================================================================================================
    // The Spring MVC slice - the real dispatcher, the real WebConfig, and a spied decision core.
    // =================================================================================================

    /**
     * The collaborators the slice needs: the real decision core, which {@link MockitoSpyBean} then wraps,
     * and a fixed {@link Clock} that displaces {@code WebConfig}'s {@code Clock.systemDefaultZone()}.
     *
     * <p>Declared on the enclosing class because {@code @TestConfiguration} must be static and a
     * {@code @Nested} class cannot hold a static member. It is imported by the slice below and by nothing
     * else.
     */
    @TestConfiguration
    static class SliceCollaborators {

        /**
         * The real service, so that the spy over it answers {@code codec()} with a real codec - which
         * {@code MainMenuController}'s constructor requires. Its {@code handle} is stubbed per test.
         *
         * @return the decision core, never {@code null}
         */
        @Bean
        MainMenuService mainMenuService() {
            return new MainMenuService(bindings());
        }

        /**
         * The pinned clock. {@code @Primary} because {@code WebConfig} contributes a
         * {@code Clock.systemDefaultZone()} bean, and a header rendered from the wall clock could not be
         * asserted byte for byte (practice B7).
         *
         * @return the fixed clock, never {@code null}
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }

    @Nested
    @WebMvcTest(MainMenuController.class)
    @ActiveProfiles("test")
    @Import(SliceCollaborators.class)
    @DisplayName("The Spring MVC slice - real dispatcher, real WebConfig, stubbed decision core")
    class TheSpringSlice {

        /**
         * The decision core, spied rather than mocked.
         *
         * <p>{@code @MockitoBean} is impossible here: {@code MainMenuController}'s constructor calls
         * {@code mainMenuService.codec()} while the bean is being created, and a bare override answers
         * {@code null}, which fails the context before {@code @BeforeEach} can stub anything. A spy calls
         * through for {@code codec()} and is stubbed for {@code handle}, so the controller still projects
         * an outcome it did not compute (gate G51). {@code @MockBean} - deprecated since Spring Boot 3.4 -
         * is avoided either way (practice B2).
         */
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
                    .andExpect(jsonPath("$.trnName").value(MainMenuResponse.TRANSACTION_ID))
                    .andExpect(jsonPath("$.pgmName").value(MainMenuResponse.PROGRAM_NAME))
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
                    .andExpect(jsonPath("$.curDate").value(EXPECTED_CURDATE))
                    .andExpect(jsonPath("$.curTime").value(EXPECTED_CURTIME));
        }

        @Test
        @DisplayName("the configured mapper keeps the padding: 78 spaces travel as 78 spaces")
        void theConfiguredMapperKeepsThePadding() throws Exception {
            doReturn(paintedOutcome(NavigationContext.empty().withPgmReenter()))
                    .when(service).handle(any(MainMenuInput.class));

            mockMvc.perform(get(MainMenuController.MAIN_MENU_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errMsg")
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
            // app/bms/COMEN01.bms:148 declares LENGTH=2 and app/cpy-bms/COMEN01.CPY:132 declares
            // OPTIONI PIC X(2), so @Size(max = OPTION_LENGTH) is the map's own constraint. The exhaustive
            // per-field constraint set is MainMenuRequestTest's subject; what is asserted here is that MVC
            // honours @Valid on this handler at all.
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
            // app/cbl/COMEN01C.cbl:58 COPY CSUSR01Y brings SEC-USER-DATA into working storage, and its
            // only other mention in the whole program is the commented-out line 150. The record layout
            // therefore has to exist - the service declares a blank one - and no test may assert a read of
            // it, because COMEN01C performs no file access at all.
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
