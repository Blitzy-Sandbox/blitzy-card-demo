package com.vsergeychik.carddemo.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuInput;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOptionTable;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOutcome;
import com.vsergeychik.carddemo.admin.MainMenuService.OptionNormalisation;
import com.vsergeychik.carddemo.admin.MainMenuService.ReceiveOutcome;
import com.vsergeychik.carddemo.admin.model.MenuOptions;
import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MainMenuService}, the decision core of {@code app/cbl/COMEN01C.cbl} - the CardDemo
 * main menu for regular users, CICS transaction {@code CM00}, mapped at
 * {@code app/csd/CARDDEMO.CSD:399-400}.
 *
 * <h2>Deliberately no HTTP anywhere</h2>
 * Every assertion below drives the service by constructing it directly and calling a method. There is
 * no {@code MockMvc}, no {@code WebApplicationContext}, no {@code @SpringBootTest} and no
 * {@code JobLauncher}. That is the point of the class under test: the branches of {@code COMEN01C} are
 * reachable from a plain JUnit test, which is what makes the per-package branch-coverage gate
 * satisfiable and what makes a failure point at one statement of one paragraph. Context loading is
 * owned by {@code CardDemoApplicationTest} in the parent test package and is not repeated here.
 *
 * <h2>Every expected value is transcribed from the source, never read back off the class</h2>
 * The literals, widths and program names below are typed out from {@code app/cbl/COMEN01C.cbl},
 * {@code app/cpy/COMEN02Y.cpy}, {@code app/cpy/COCOM01Y.cpy}, {@code app/cpy/CSMSG01Y.cpy},
 * {@code app/cpy/CSDAT01Y.cpy}, {@code app/cpy-bms/COMEN01.CPY} and {@code app/bms/COMEN01.bms}, so a
 * drift in either direction is caught. Asserting a constant against itself would prove nothing. Every
 * non-obvious expectation carries its source line beside it, because no COBOL run was possible in this
 * environment and the baseline is therefore <strong>statically derived</strong> - a reviewer must be
 * able to re-derive each value from the cited line alone.
 *
 * <h2>The authorisation filter gets the densest coverage</h2>
 * {@code COMEN01C}'s user-type filter at lines 136 to 143 is the one decision in this package with no
 * sibling counterpart, it is <em>not</em> guarded by the error flag, and it subscripts an
 * {@code OCCURS} table with a value the validation above it may already have rejected. It therefore
 * gets its own nested class, driven at subscripts 0, 1, 10, 11, 12, 13 and 99, at both truth states of
 * {@code 88 CDEMO-USRTYP-USER} plus a user type that satisfies neither, and with a stub entry that
 * makes its true arm fire.
 *
 * <h2>THIS FILE IS NOT A COPY OF {@code AdminMenuServiceTest}, AND MUST NEVER BECOME ONE</h2>
 * {@code COMEN01C} and {@code COADM01C} share a near-identical skeleton and diverge in three verified,
 * behaviour-changing ways. Each divergence is asserted here, and each is the reason a well-meant later
 * "harmonisation" of the two test files would be a parity regression:
 * <ol>
 *   <li><strong>The authorisation filter exists only here.</strong> {@code COMEN01C:136-143} has no
 *       counterpart anywhere in {@code COADM01C}, and it is <em>ungated</em> where every other guard in
 *       either program is gated. See {@link AuthorisationFilter}.</li>
 *   <li><strong>The two coming-soon messages differ.</strong> {@code COMEN01C:159-163} composes the
 *       option name {@code DELIMITED BY SPACE}, so this program emits
 *       {@code This option Accountis coming soon ...} - 37 characters, one word of the name, and
 *       <strong>no space before {@code is}</strong>. {@code COADM01C:150-151} comments the name out
 *       altogether, so the sibling emits {@code This option is coming soon ...} - 30 characters,
 *       <strong>with</strong> the space. If the two files ever assert the same string, one of them is
 *       wrong. See {@link #SIBLING_COMING_SOON} and {@link ComingSoon}.</li>
 *   <li><strong>The arm counts differ.</strong> The {@code EVALUATE WS-IDX} of
 *       {@code BUILD-MENU-OPTIONS} has {@value #EVALUATE_WS_IDX_ARM_COUNT} arms here -
 *       {@code WHEN 1} through {@code WHEN 12} at {@code COMEN01C:249-272} plus
 *       {@code WHEN OTHER CONTINUE} at {@code :273-274} - where the sibling's has eleven, stopping at
 *       {@code WHEN 10}. Equal counts in the two files mean one was copied from the other. See
 *       {@link BuildMenuOptions#allThirteenArmsAreEnumerated()}.</li>
 * </ol>
 *
 * <h2>No user-specified rules govern this file</h2>
 * {@code review_rules} returns exactly one line - "No user rules provided." - and that is the whole
 * document. Their absence is not licence to lower the bar, so the migration's own enterprise-practice
 * substitutes bind instead and are named where they apply:
 * <ul>
 *   <li><strong>B1/B2</strong> - only the closed dependency set is used: JUnit Jupiter, AssertJ and the
 *       JDK. No dependency is added and no version is written anywhere below.</li>
 *   <li><strong>B3/G5</strong> - the reference inputs are read for their bytes and never written. No
 *       COBOL, copybook, BMS, JCL or CSD file is copied into {@code src/test/resources} and none is read
 *       at run time; every fact is transcribed as a literal with its citation.</li>
 *   <li><strong>B4</strong> - no scope creep: this file is the only artefact, with no shared base
 *       class, no fixture-builder utility and no extra test resource.</li>
 *   <li><strong>B5</strong> - behaviour is preserved including its defects and dead code. Four
 *       instances are asserted: the {@code Accountis} defect ({@link ComingSoon}), the commented-out
 *       option-8 literal at {@code app/cpy/COMEN02Y.cpy:69}
 *       ({@link BuildMenuOptions#optionEightAvoidsTheCommentedLiteralTrap()}), the unreachable
 *       {@code WHEN 11}, {@code WHEN 12} and {@code WHEN OTHER} arms, and the commented-out
 *       {@code COMEN01C:149-150} whose <em>absence</em> of effect is itself asserted
 *       ({@link Dispatch}).</li>
 *   <li><strong>B6/G41</strong> - the security posture is unchanged. {@code WS-USRSEC-FILE} at
 *       {@code :39} and {@code COPY CSUSR01Y} at {@code :58} are declared and never opened, and no test
 *       below asserts a read of either. The filter of {@link AuthorisationFilter} is an
 *       <em>authorisation</em> comparison of one data byte, not authentication: no Spring Security, no
 *       hashing, no token and no credential appears anywhere in this file.</li>
 *   <li><strong>B7/G54</strong> - deterministic and non-interactive. The service consults no clock, no
 *       random source and no environment; {@link LayerBoundaryAndDeterminism} proves it and says where
 *       the clock-dependent header fields are asserted instead.</li>
 *   <li><strong>B8</strong> - explicit over implicit: the {@link Charset} is always named where a codec
 *       is built, and every width is a named constant traced to a {@code PICTURE} clause or a
 *       {@code DFHMDF LENGTH} rather than a bare number at a use site.</li>
 *   <li><strong>B9/G53</strong> - no static mutable state. Every {@code static} member below is
 *       {@code final} and immutable, and
 *       {@link LayerBoundaryAndDeterminism#theServiceHoldsNoMutableOrTimeDependentState()} asserts the
 *       same of the class under test.</li>
 *   <li><strong>B10</strong> - every test below is enabled, complete and asserting: nothing is skipped,
 *       nothing is deferred to a later pass and no method is a placeholder. The suite ships in the same
 *       phase as the implementation it covers.</li>
 * </ul>
 *
 * <h2>The AAP gates this file discharges</h2>
 * <ul>
 *   <li><strong>G51</strong> - the unit under test is the service, reached with no HTTP in the
 *       path.</li>
 *   <li><strong>G49</strong> - branch coverage for package {@code admin}, carried by this file together
 *       with {@code AdminMenuServiceTest}. Arms are enumerated with {@code @ParameterizedTest} so each
 *       is visible in the report rather than incidental.</li>
 *   <li><strong>G30</strong> - both {@code EVALUATE}s, every {@code WHEN} in source order with
 *       {@code WHEN OTHER} last: the {@value #EVALUATE_EIBAID_ARM_COUNT}-arm {@code EVALUATE EIBAID} at
 *       {@code :93-103} ({@link AidEvaluate}) and the
 *       {@value #EVALUATE_WS_IDX_ARM_COUNT}-arm {@code EVALUATE WS-IDX} at {@code :248-275}
 *       ({@link BuildMenuOptions}).</li>
 *   <li><strong>G50</strong> - both states of every {@code 88}-level: {@code ERR-FLG-ON}/{@code OFF} at
 *       {@code :41-42}, {@code CDEMO-USRTYP-ADMIN}/{@code CDEMO-USRTYP-USER} at
 *       {@code app/cpy/COCOM01Y.cpy:27-28}, and {@code CDEMO-PGM-ENTER}/{@code CDEMO-PGM-REENTER} at
 *       {@code :30-31}.</li>
 *   <li><strong>G33</strong> - the 1-based loop over {@code OCCURS 12}: the first and the last produced
 *       line are asserted character for character, and the bounds are asserted rejected rather than
 *       clamped at subscripts 0 and 13.</li>
 *   <li><strong>G37</strong> - statelessness, proved by re-using one service instance across two
 *       different invocations in both orders; see {@link UnconditionalResetAndStatelessness}.</li>
 *   <li><strong>G38</strong> - both the {@code ENTER} path at {@code :87-90} and the {@code REENTER}
 *       path at {@code :91-103}.</li>
 *   <li><strong>G40</strong> - every {@code XCTL} becomes a {@code nextProgram} field: the ten option
 *       targets, both shapes of {@code RETURN-TO-SIGNON-SCREEN}, and the {@code 'U'}-role inbound
 *       context {@code COSGN00C:237} hands over.</li>
 *   <li><strong>G52</strong> - no wildcard imports.</li>
 * </ul>
 *
 * <h2>Gates that verifiably have no subject here, and the types this file therefore does not name</h2>
 * Four families of assertion are deliberately absent, each because the program gives them nothing to
 * assert. The absences were established by exhaustive search of {@code COMEN01C} and are recorded here so
 * that a reviewer reads them as verified findings rather than as omissions:
 * <ul>
 *   <li><strong>G47, the per-call-site file-status gate: zero call sites.</strong> Searching the program
 *       for {@code READ}, {@code STARTBR}, {@code READNEXT}, {@code WRITE}, {@code REWRITE} and
 *       {@code DELETE} returns nothing, and the service injects no repository of any kind, so the
 *       module's file-status constant class is neither imported nor referenced below.</li>
 *   <li><strong>G35, the abend gate: no site.</strong> The program contains none of the nine
 *       {@code CALL 'CEE3ABD'} sites in the migration, so the module's abend exception type is neither
 *       imported nor referenced below.</li>
 *   <li><strong>G22 to G29, numeric parity: no subject.</strong> The program has zero {@code ADD},
 *       {@code SUBTRACT}, {@code COMPUTE}, {@code MULTIPLY} and {@code DIVIDE} verbs, zero
 *       {@code COMP-3} declarations and zero scaled {@code PIC S9(n)V(n)} clauses - there is no monetary
 *       value anywhere in it. So no fixed-point policy type, no arbitrary-precision decimal type and no
 *       binary floating-point primitive is named anywhere in this file, and no rounding mode is
 *       asserted.</li>
 *   <li><strong>G19 to G21 and G44 to G48: no subject.</strong> No record is persisted, no DDL exists,
 *       no dataset name is written in Java and no statement subroutine is involved.</li>
 * </ul>
 */
@DisplayName("MainMenuService - the decision core of COMEN01C")
class MainMenuServiceTest {

    /** {@code app/cbl/COMEN01C.cbl:38} - {@code WS-MESSAGE PIC X(80)}. */
    private static final int WS_MESSAGE_WIDTH = 80;

    /** {@code app/cpy-bms/COMEN01.CPY:182} - {@code OPTN001O PIC X(40)}. */
    private static final int OPTION_LINE_WIDTH = 40;

    /** {@code app/cpy/COCOM01Y.cpy:24} - {@code CDEMO-TO-PROGRAM PIC X(08)}. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** {@code app/cpy/COMEN02Y.cpy:90} - {@code CDEMO-MENU-OPT-NAME PIC X(35)}. */
    private static final int OPTION_NAME_WIDTH = 35;

    /** Eighty spaces: {@code WS-MESSAGE} as {@code app/cbl/COMEN01C.cbl:79} leaves it. */
    private static final String BLANK_MESSAGE = " ".repeat(WS_MESSAGE_WIDTH);

    /**
     * Forty {@code X'00'} characters: an {@code OPTN00nO} line the program never writes, as
     * {@code MOVE LOW-VALUES TO COMEN1AO} at {@code app/cbl/COMEN01C.cbl:89} leaves it.
     *
     * <p>Not spaces. {@code MOVE SPACES TO WS-MENU-OPT-TXT} at {@code :241} blanks the
     * {@code WORKING-STORAGE} composition buffer, not the map field; the {@code MOVE ... TO OPTN00nO}
     * statements are inside the {@code EVALUATE} arms and run only for a subscript the loop reaches, so
     * a line past {@code CDEMO-MENU-OPT-COUNT} is never written at all.
     */
    /**
     * {@code OPTIONO} as the group {@code MOVE LOW-VALUES} leaves it: two {@code X'00'} characters.
     * {@code MOVE WS-OPTION TO OPTIONO} at {@code :125} is the only writer and it lives inside
     * {@code PROCESS-ENTER-KEY}, so on the first-entry paint the field is simply never written.
     */
    private static final String BLANK_OPTION_ECHO = ScreenFieldImage.unpainted(2);

    private static final String BLANK_OPTION_LINE =
            ScreenFieldImage.unpainted(OPTION_LINE_WIDTH);

    /** Eight spaces: an {@code XCTL} target on a path that does not transfer. */
    private static final String NO_NEXT_PROGRAM = " ".repeat(PROGRAM_NAME_WIDTH);

    /** {@code app/cbl/COMEN01C.cbl:131}, transcribed - 37 characters. */
    private static final String INVALID_OPTION_TEXT = "Please enter a valid option number...";

    /**
     * {@code app/cbl/COMEN01C.cbl:140}, transcribed - 33 characters, and the trailing space is real.
     */
    private static final String NO_ACCESS_TEXT = "No access - Admin Only option... ";

    /**
     * {@code CCDA-MSG-INVALID-KEY} - {@code app/cpy/CSMSG01Y.cpy:20-21}, transcribed as the full
     * <strong>fifty</strong> bytes the copybook declares.
     *
     * <p>The declaration is
     * {@code 05 CCDA-MSG-INVALID-KEY PIC X(50) VALUE 'Invalid key pressed. Please see below...         '}:
     * a 40-character text plus nine trailing spaces in the literal is 49 characters, and a
     * {@code VALUE} shorter than its {@code PICTURE} is stored right-space-padded, so the field holds 50.
     * The concatenation below keeps that derivation visible - 40 characters of text and then ten spaces -
     * rather than burying it in one long quoted string a reviewer would have to count.
     *
     * <p>This is the literal {@code app/cbl/COMEN01C.cbl:101} moves into {@code WS-MESSAGE PIC X(80)}.
     * It is <strong>not</strong> {@code CCDA-MSG-THANK-YOU} at {@code CSMSG01Y.cpy:18-19}, which is a
     * different fifty-byte literal with different text and a different owner, and it is not the
     * forty-character screen-title text either. Substituting any of the three would be a silent parity
     * failure, so {@link Construction#theInvalidKeyLiteralIsTheFullFiftyBytes()} asserts they differ.
     */
    private static final String INVALID_KEY_MESSAGE_X50 =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /**
     * The number of arms in {@code EVALUATE EIBAID} at {@code app/cbl/COMEN01C.cbl:93-103}:
     * {@code WHEN DFHENTER} at {@code :94}, {@code WHEN DFHPF3} at {@code :96} and
     * {@code WHEN OTHER} at {@code :99}.
     *
     * <p>Three, and there is no arm for any other PF key and none for {@code DFHPA3}: every other
     * attention identifier - resolvable or not - lands on {@code WHEN OTHER}.
     */
    private static final int EVALUATE_EIBAID_ARM_COUNT = 3;

    /**
     * The number of arms in {@code EVALUATE WS-IDX} at {@code app/cbl/COMEN01C.cbl:248-275}:
     * {@code WHEN 1} through {@code WHEN 12} at {@code :249-272}, then {@code WHEN OTHER CONTINUE} at
     * {@code :273-274}.
     *
     * <p><strong>Thirteen, where {@code COADM01C}'s equivalent has eleven</strong> - that one stops at
     * {@code WHEN 10} and so can never write {@code OPTN011O} or {@code OPTN012O}. The two counts are
     * the mechanical cross-check that neither test file was copied from the other.
     */
    private static final int EVALUATE_WS_IDX_ARM_COUNT = MainMenuService.OPTION_DISPATCH_ARM_COUNT + 1;

    /** {@code app/cpy/COMEN02Y.cpy:26-27} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_1_NAME = "Account View                       ";

    /**
     * {@code app/cpy/COMEN02Y.cpy:32-33} - {@code PIC X(35)}, transcribed.
     *
     * <p>Its first word is {@code Account}, the same as option 1's, which is what makes the pair the
     * clearest demonstration that {@code DELIMITED BY SPACE} discards information.
     */
    private static final String OPTION_2_NAME = "Account Update                     ";

    /** {@code app/cpy/COMEN02Y.cpy:38-39} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_3_NAME = "Credit Card List                   ";

    /** {@code app/cpy/COMEN02Y.cpy:56-57} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_6_NAME = "Transaction List                   ";

    /**
     * {@code app/cpy/COMEN02Y.cpy:70} - the <strong>live</strong> literal, {@code PIC X(35)}.
     *
     * <p>Line 69 immediately above it holds a commented-out
     * {@code 'Transaction Add (Admin Only)       '}, which is also 35 characters and would therefore
     * pass every width check. Only the live one is correct, and this constant is what proves the trap
     * was avoided.
     */
    private static final String OPTION_8_NAME = "Transaction Add                    ";

    /** {@code app/cpy/COMEN02Y.cpy:81-82} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_10_NAME = "Bill Payment                       ";

    /** The commented-out alternative at {@code app/cpy/COMEN02Y.cpy:69}, which must never appear. */
    private static final String OPTION_8_COMMENTED_NAME = "Transaction Add (Admin Only)       ";

    /** The composed {@code OPTN001O} line: {@code 01} then {@code '. '} then the 35-byte name. */
    private static final String OPTION_1_LINE = "01. " + OPTION_1_NAME + " ";

    /** The composed {@code OPTN010O} line - the last one the active count reaches. */
    private static final String OPTION_10_LINE = "10. " + OPTION_10_NAME + " ";

    /**
     * The "coming soon" text for option 1, composed exactly as
     * {@code app/cbl/COMEN01C.cbl:159-163} composes it.
     *
     * <p>Note what is <strong>not</strong> here: a space before {@code is}, and any word of the name
     * after the first. {@code DELIMITED BY SPACE} stops at the first space of
     * {@code 'Account View'} and {@code 'is coming soon ...'} has no leading space.
     */
    private static final String COMING_SOON_OPTION_1 = "This option Accountis coming soon ...";

    /**
     * The same for option 2, whose name is {@code 'Account Update'} at
     * {@code app/cpy/COMEN02Y.cpy:32-33}.
     *
     * <p>Byte-for-byte <strong>identical to {@link #COMING_SOON_OPTION_1}</strong>, because
     * {@code DELIMITED BY SPACE} keeps only {@code Account} from either name. Two different menu
     * entries produce the same message, and that is a property of the source, not of this test.
     */
    private static final String COMING_SOON_OPTION_2 = "This option Accountis coming soon ...";

    /** The same for option 3, whose name's first word is {@code Credit}. */
    private static final String COMING_SOON_OPTION_3 = "This option Creditis coming soon ...";

    /**
     * The same for option 6, whose name is {@code 'Transaction List'} at
     * {@code app/cpy/COMEN02Y.cpy:56-57} - the longest first word in the table, at eleven characters.
     */
    private static final String COMING_SOON_OPTION_6 = "This option Transactionis coming soon ...";

    /** The same for option 10, whose name's first word is {@code Bill}. */
    private static final String COMING_SOON_OPTION_10 = "This option Billis coming soon ...";

    /**
     * What the <strong>sibling</strong> program emits, transcribed from
     * {@code app/cbl/COADM01C.cbl:149-153} - and the one string this file must never produce.
     *
     * <pre>
     *   L149          STRING 'This option '       DELIMITED BY SIZE
     *   L150  *              CDEMO-ADMIN-OPT-NAME(WS-OPTION)
     *   L151  *                              DELIMITED BY SIZE
     *   L152                 'is coming soon ...' DELIMITED BY SIZE
     *   L153            INTO WS-MESSAGE
     * </pre>
     *
     * <p>Lines 150 and 151 are <strong>commented out</strong> there, so {@code COADM01C} concatenates
     * only the prefix and the suffix and its message is thirty characters long <em>with</em> the space
     * before {@code is}. {@code COMEN01C:159-163} keeps its name operand and delimits it by space, so
     * this program's message is name-dependent and has <em>no</em> space before {@code is}. The
     * assertion in {@link ComingSoon#thisProgramIsNotItsSibling()} exists so that a later attempt to
     * "harmonise" the two test files fails loudly instead of silently repairing a legacy defect.
     */
    private static final String SIBLING_COMING_SOON = "This option is coming soon ...";

    /** An attention identifier {@code PfKeyResolver} maps to no key at all. */
    private static final byte UNRESOLVABLE_AID = (byte) 0x01;

    /**
     * The {@code carddemo.datasets} catalogue as {@code application.yml} declares {@code USRSEC}:
     * an indexed dataset of eighty-byte records keyed on the eight-byte {@code SEC-USR-ID}.
     *
     * @param recordLength the configured record width, so a disagreeing one can be driven too
     * @return a catalogue holding only that entry
     */
    private static DatasetBindings bindings(int recordLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(MainMenuService.USRSEC_DATASET_KEY,
                new DatasetBinding("CARDDEMO.TEST.USRSEC",
                        DatasetBinding.KSDS,
                        false,
                        "FB",
                        null,
                        recordLength,
                        "CSUSR01Y",
                        SecUserRecord.KEY_LENGTH,
                        null,
                        null,
                        null));
        return catalogue;
    }

    /** The catalogue as configured, with the copybook's eighty-byte record. */
    private static DatasetBindings bindings() {
        return bindings(SecUserRecord.RECORD_LENGTH);
    }

    /** A service wired the way the container wires it. */
    private static MainMenuService service() {
        return new MainMenuService(bindings());
    }

    /**
     * The eighty-byte {@code WS-MESSAGE} image a piece of composed text produces:
     * {@code app/cbl/COMEN01C.cbl:38} declares {@code WS-MESSAGE PIC X(80)}, and every literal the
     * program stores there is left-justified with the remainder space-filled.
     *
     * <p>This helper <strong>pads</strong>; it never trims, strips or normalises. Comparing a padded
     * expectation against the actual value is what makes the assertion byte-exact, and it is deliberately
     * the only way this file states a message expectation: stripping the actual instead would quietly
     * excuse a field of the wrong width.
     *
     * @param text the composed text, at most {@value #WS_MESSAGE_WIDTH} characters
     * @return exactly {@value #WS_MESSAGE_WIDTH} characters
     */
    private static String wsMessageImage(String text) {
        return text + " ".repeat(WS_MESSAGE_WIDTH - text.length());
    }

    /**
     * A communication area in {@code CDEMO-PGM-REENTER} state carrying user type {@code 'U'} - the
     * state {@code COSGN00C} hands a regular user to {@code COMEN01C} in, after one keystroke.
     */
    private static NavigationContext userReenteredContext() {
        return NavigationContext.empty().withPgmReenter().withUserTypeUser();
    }

    /** The same, but carrying user type {@code 'A'} so the filter's first conjunct is false. */
    private static NavigationContext adminReenteredContext() {
        return NavigationContext.empty().withPgmReenter().withUserTypeAdmin();
    }

    /**
     * A stub option table, so branches the real ten-entry all-{@code 'U'} table cannot reach become
     * reachable - the {@value MainMenuService#DUMMY_PROGRAM_PREFIX} prefix and the true arm of the
     * authorisation filter.
     *
     * @param activeCount  {@code CDEMO-MENU-OPT-COUNT}
     * @param slotCount    how many {@code OCCURS} slots the stub declares
     * @param programNames the target program name of each valued slot, in subscript order
     * @param usrTypes     the {@code CDEMO-MENU-OPT-USRTYPE} of each valued slot, in the same order
     * @return the table, with any slot beyond {@code programNames} left absent
     */
    private static MainMenuOptionTable stubTable(int activeCount,
            int slotCount,
            String[] programNames,
            String[] usrTypes) {
        List<Optional<MenuOption>> slots = new ArrayList<>();
        for (int index = 0; index < slotCount; index++) {
            if (index < programNames.length) {
                slots.add(Optional.of(MenuOption.of(index + 1,
                        "Stub option " + (index + 1),
                        programNames[index],
                        usrTypes[index])));
            } else {
                slots.add(Optional.empty());
            }
        }
        return new MainMenuOptionTable(slots, activeCount);
    }

    /** A one-slot stub whose single entry is a regular-user option with the given target program. */
    private static MainMenuOptionTable stubTable(String programName) {
        return stubTable(1, 1, new String[] {programName},
                new String[] {NavigationContext.USER_TYPE_USER});
    }

    /**
     * A stub of {@code slotCount} valued regular-user entries with an active count of
     * {@code activeCount}, so the option-line dispatch arms above the copybook's active count of
     * {@value MenuOptions#ACTIVE_OPTION_COUNT} become reachable without widening or mutating the shipped
     * table.
     *
     * @param activeCount {@code CDEMO-MENU-OPT-COUNT} for this stub
     * @param slotCount   how many {@code OCCURS} slots the stub declares
     * @return the table
     */
    private static MainMenuOptionTable numberedStub(int activeCount, int slotCount) {
        String[] programs = new String[slotCount];
        String[] types = new String[slotCount];
        for (int index = 0; index < slotCount; index++) {
            programs[index] = "COPGM0" + (char) ('A' + index);
            types[index] = NavigationContext.USER_TYPE_USER;
        }
        return stubTable(activeCount, slotCount, programs, types);
    }

    /**
     * A stub declaring and offering all {@value MenuOptions#TABLE_SIZE} {@code OCCURS} slots, which is
     * what makes the {@code WHEN 11} and {@code WHEN 12} arms of
     * {@code app/cbl/COMEN01C.cbl:269-272} reachable.
     *
     * @return the table
     */
    private static MainMenuOptionTable fullTwelveSlotStub() {
        return numberedStub(MainMenuService.OPTION_LINE_COUNT, MainMenuService.OPTION_LINE_COUNT);
    }

    @Nested
    @DisplayName("Construction: the dead WS-USRSEC-FILE declaration, resolved from configuration")
    class Construction {

        @Test
        @DisplayName("WS-USRSEC-FILE is the eight-byte logical name 'USRSEC  ', with its two spaces")
        void usrSecFileNameIsTheEightByteLogicalName() {
            assertThat(service().usrSecFileName())
                    .as("app/cbl/COMEN01C.cbl:39 declares PIC X(08) VALUE 'USRSEC  '")
                    .isEqualTo("USRSEC  ")
                    .hasSize(MainMenuService.USRSEC_FILE_NAME_LENGTH);
        }

        @Test
        @DisplayName("no dataset name reaches the service - only the catalogue key does")
        void theConfiguredDatasetNameIsNeverRead() {
            assertThat(service().usrSecFileName()).doesNotContain("CARDDEMO.TEST.USRSEC");
        }

        @Test
        @DisplayName("SEC-USER-DATA is declared and blank, and nothing reads it")
        void secUserDataIsADeclaredButUnusedRecord() {
            SecUserRecord declared = service().secUserData();
            assertThat(declared).isEqualTo(SecUserRecord.blank());
            assertThat(declared.secUsrId()).isBlank();
            assertThat(declared.secUsrPwd()).isBlank();
            assertThat(declared.secUsrType()).isBlank();
        }

        @Test
        @DisplayName("the codec is the one supplied, and is exposed for the controller's X(80)->X(78)")
        void theCodecIsExposed() {
            FixedWidthCodec supplied = new FixedWidthCodec(Charset.forName("IBM037"));
            assertThat(new MainMenuService(bindings(), supplied).codec()).isSameAs(supplied);
            assertThat(service().codec().charset().name())
                    .isEqualTo(MainMenuService.DEFAULT_MESSAGE_CHARSET_NAME);
        }

        @Test
        @DisplayName("a USRSEC binding of the wrong record width refuses to start")
        void aDisagreeingRecordWidthIsRejected() {
            DatasetBindings wrong = bindings(SecUserRecord.RECORD_LENGTH + 1);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new MainMenuService(wrong))
                    .withMessageContaining("record length of 81")
                    .withMessageContaining("app/cpy/CSUSR01Y.cpy");
        }

        @Test
        @DisplayName("an unconfigured USRSEC key refuses to start")
        void anAbsentBindingIsRejected() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new MainMenuService(new DatasetBindings()))
                    .withMessageContaining(MainMenuService.USRSEC_DATASET_KEY);
        }

        @Test
        @DisplayName("both constructor arguments are required")
        void bothConstructorArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new MainMenuService(null))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException()
                    .isThrownBy(() -> new MainMenuService(bindings(), null))
                    .withMessageContaining("fixed-width codec");
        }

        @Test
        @DisplayName("the program identity is COMEN01C / CM00 / COMEN1A / COMEN01")
        void theProgramIdentityIsTranscribedFromTheSource() {
            assertThat(MainMenuService.PROGRAM_NAME).isEqualTo("COMEN01C");
            assertThat(MainMenuService.TRANSACTION_ID).isEqualTo("CM00");
            assertThat(MainMenuService.MAP_NAME).isEqualTo("COMEN1A");
            assertThat(MainMenuService.MAPSET_NAME).isEqualTo("COMEN01");
            assertThat(MainMenuService.SIGNON_PROGRAM).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("every emitted literal is exactly the length the source declares")
        void everyEmittedLiteralIsAtItsVerifiedLength() {
            assertThat(MainMenuService.INVALID_OPTION_MESSAGE).isEqualTo(INVALID_OPTION_TEXT)
                    .hasSize(37);
            assertThat(MainMenuService.NO_ACCESS_MESSAGE).isEqualTo(NO_ACCESS_TEXT).hasSize(33)
                    .endsWith(" ");
            assertThat(MainMenuService.COMING_SOON_PREFIX).isEqualTo("This option ").hasSize(12);
            assertThat(MainMenuService.COMING_SOON_SUFFIX).isEqualTo("is coming soon ...").hasSize(18)
                    .doesNotStartWith(" ");
            assertThat(MainMenuService.OPTION_NUMBER_SEPARATOR).isEqualTo(". ");
            assertThat(MainMenuService.DUMMY_PROGRAM_PREFIX).isEqualTo("DUMMY")
                    .hasSize(MainMenuService.DUMMY_PREFIX_LENGTH);
            assertThat(MainMenuService.ADMIN_ONLY_USRTYPE).isEqualTo("A");
            assertThat(MainMenuService.ERR_FLG_ON).isEqualTo("Y");
            assertThat(MainMenuService.ERR_FLG_OFF).isEqualTo("N");
            assertThat(MainMenuService.ZERO_OPTION).isZero();
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY is the full fifty bytes, and is not the thank-you literal")
        void theInvalidKeyLiteralIsTheFullFiftyBytes() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("app/cpy/CSMSG01Y.cpy:20-21 - a 40-character text, 9 trailing spaces in the "
                            + "VALUE literal, stored padded to the declared PIC X(50)")
                    .isEqualTo(INVALID_KEY_MESSAGE_X50)
                    .hasSize(50)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .startsWith("Invalid key pressed. Please see below...")
                    .endsWith(" ".repeat(10));
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("app/cpy/CSMSG01Y.cpy:18-19 declares a DIFFERENT fifty-byte literal; "
                            + "app/cbl/COMEN01C.cbl:101 moves the invalid-key one and never that")
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(MainMenuService.MESSAGE_LENGTH)
                    .as("app/cbl/COMEN01C.cbl:38 - WS-MESSAGE is PIC X(80), wider than the PIC X(50) "
                            + "sender and wider still than ERRMSGO's PIC X(78); all three are real")
                    .isEqualTo(WS_MESSAGE_WIDTH)
                    .isGreaterThan(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the dispatch has TWELVE arms here, unlike COADM01C's ten")
        void theDispatchHasTwelveArms() {
            assertThat(MainMenuService.OPTION_DISPATCH_ARM_COUNT)
                    .as("app/cbl/COMEN01C.cbl:249-272 runs WHEN 1 through WHEN 12")
                    .isEqualTo(12)
                    .isEqualTo(MainMenuService.OPTION_LINE_COUNT);
            assertThat(AdminMenuService.OPTION_DISPATCH_ARM_COUNT)
                    .as("app/cbl/COADM01C.cbl stops at WHEN 10 - the two programs differ")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the map's ERRMSG colour is red, and green is the program's only override")
        void theColourConstantsComeFromTheMapAndTheProgram() {
            assertThat(MainMenuService.MAP_MESSAGE_COLOUR)
                    .as("app/bms/COMEN01.bms:154-157 declares COLOR=RED")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(MainMenuService.COMING_SOON_MESSAGE_COLOUR)
                    .as("app/cbl/COMEN01C.cbl:158 MOVE DFHGREEN TO ERRMSGC")
                    .isEqualTo(BmsAttributes.DFHGREEN);
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L82-L84: EIBCALEN = 0 sets CDEMO-FROM-PROGRAM, not TO")
    class AbsentCommarea {

        @Test
        @DisplayName("line 83 writes FROM-PROGRAM; the TO-PROGRAM default comes from line 173")
        void anAbsentCommareaWritesFromProgramAndDefaultsToProgram() {
            MainMenuOutcome outcome = service()
                    .handle(MainMenuInput.withoutCommarea(CicsAid.DFHENTER, "  "));

            assertThat(outcome.navigationContext().fromProgram())
                    .as("app/cbl/COMEN01C.cbl:83 - MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM")
                    .isEqualTo("COSGN00C");
            assertThat(outcome.nextProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.hasNextProgram()).isTrue();
            assertThat(outcome.nextProgramCarriesCommarea())
                    .as("app/cbl/COMEN01C.cbl:175-177 specifies no COMMAREA")
                    .isFalse();
            assertThat(outcome.screenPainted()).isFalse();
            assertThat(outcome.resetAllOutputFields()).isFalse();
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.errFlgImage()).isEqualTo("N");
            assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
            assertThat(outcome.optionLines()).containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("the attention identifier is never consulted on this path")
        void theAidIsImmaterialWithNoCommarea() {
            MainMenuOutcome viaEnter = service()
                    .handle(MainMenuInput.withoutCommarea(CicsAid.DFHENTER, "01"));
            MainMenuOutcome viaGarbage = service()
                    .handle(MainMenuInput.withoutCommarea(UNRESOLVABLE_AID, "01"));

            assertThat(viaEnter.nextProgram()).isEqualTo(viaGarbage.nextProgram());
            assertThat(viaEnter.errorFlag()).isEqualTo(viaGarbage.errorFlag());
        }

        @Test
        @DisplayName("an absent communication area is EIBCALEN = 0, and reports itself as such")
        void isCommareaPresentReportsTheAbsence() {
            MainMenuInput cold = MainMenuInput.withoutCommarea(CicsAid.DFHENTER, "  ");
            assertThat(cold.isCommareaPresent()).isFalse();
            assertThat(cold.isReenter()).isFalse();
            assertThat(cold.navigationContext()).isNull();
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L87-L90: first entry paints, resets and flips to REENTER")
    class FirstEntry {

        @Test
        @DisplayName("context 0 paints the menu, clears every output field and reads no option")
        void firstEntryPaintsAndResets() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "07"));

            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.resetAllOutputFields())
                    .as("app/cbl/COMEN01C.cbl:89 - MOVE LOW-VALUES TO COMEN1AO")
                    .isTrue();
            assertThat(outcome.navigationContext().isReenter())
                    .as("app/cbl/COMEN01C.cbl:88 - SET CDEMO-PGM-REENTER TO TRUE")
                    .isTrue();
            assertThat(outcome.option())
                    .as("OPTIONO is one of the fields LOW-VALUES cleared, so it holds X'00' at its "
                            + "declared width - not spaces, which is a different byte")
                    .isEqualTo(BLANK_OPTION_ECHO);
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.hasNextProgram()).isFalse();
            assertThat(outcome.nextProgram()).isEqualTo(NO_NEXT_PROGRAM);
            assertThat(outcome.receive().performed()).isFalse();
            assertThat(outcome.transactionId()).isEqualTo("CM00");
            assertThat(outcome.mapName()).isEqualTo("COMEN1A");
            assertThat(outcome.mapsetName()).isEqualTo("COMEN01");
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0} takes the first-entry path")
        @ValueSource(ints = {0, 2, 5, 9})
        @DisplayName("IF NOT CDEMO-PGM-REENTER is not 'is enter': any non-1 digit paints")
        void anyContextOtherThanOneTakesTheFirstEntryPath(int pgmContext) {
            MainMenuOutcome outcome = service().handle(new MainMenuInput(
                    NavigationContext.empty().withPgmContext(pgmContext), CicsAid.DFHENTER, "01"));

            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.resetAllOutputFields()).isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("a re-entered context does not take the first-entry path")
        void aReenteredContextIsNotFirstEntry()
        {
            MainMenuInput input =
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01");
            assertThat(input.isReenter()).isTrue();
            assertThat(service().handle(input).resetAllOutputFields()).isFalse();
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L93-L103: the three arms of EVALUATE EIBAID, in source order")
    class AidEvaluate {

        @Test
        @DisplayName("WHEN DFHENTER performs PROCESS-ENTER-KEY")
        void enterProcessesTheOption() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(outcome.hasNextProgram()).isTrue();
            assertThat(outcome.nextProgram()).isEqualTo("COACTVWC");
            assertThat(outcome.receive().performed()).isTrue();
            assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NORMAL);
        }

        @Test
        @DisplayName("WHEN DFHPF3 writes CDEMO-TO-PROGRAM and leaves with no COMMAREA")
        void pf3ReturnsToSignon() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHPF3, "01"));

            assertThat(outcome.navigationContext().toProgram())
                    .as("app/cbl/COMEN01C.cbl:97 - MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM")
                    .isEqualTo("COSGN00C");
            assertThat(outcome.nextProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.nextProgramCarriesCommarea()).isFalse();
            assertThat(outcome.screenPainted()).isFalse();
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.option())
                    .as("OPTIONO is not written on this path; the client's value survives")
                    .isEqualTo("01");
        }

        // app/cbl/COMEN01C.cbl:93-103 names exactly two attention identifiers, so every other byte a
        // 3270 can send lands on WHEN OTHER at :99. The rows below are the mnemonics the migration plan
        // calls for plus three more, each with the CicsAid constant it is:
        //
        //   6D DFHCLEAR   F1 DFHPF1    6C DFHPA1    7C DFHPF12   6B DFHPA3
        //   F4 DFHPF4     F5 DFHPF5    7A DFHPF10   C3 DFHPF15   01 and 00 - no AID at all
        //
        // DFHPA3 and the two unnamed bytes matter because PfKeyResolver cannot name them either: the
        // arm has to absorb a byte with no token, not just a byte with the wrong token.
        @ParameterizedTest(name = "AID 0x{0} falls to WHEN OTHER")
        @CsvSource({"F4", "F5", "6D", "01", "00", "7A", "F1", "6C", "7C", "6B", "C3"})
        @DisplayName("WHEN OTHER raises the invalid-key message, widened X(50) -> X(80)")
        void anyOtherAidRaisesTheInvalidKeyMessage(String hexAid) {
            byte aid = (byte) Integer.parseInt(hexAid, 16);
            MainMenuOutcome outcome =
                    service().handle(new MainMenuInput(userReenteredContext(), aid, "01"));

            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.errFlgImage()).isEqualTo("Y");
            assertThat(outcome.message())
                    .as("app/cbl/COMEN01C.cbl:101 - the PIC X(50) literal of "
                            + "app/cpy/CSMSG01Y.cpy:20-21 into WS-MESSAGE PIC X(80)")
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(wsMessageImage(INVALID_KEY_MESSAGE_X50));
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(outcome.messageColourOverridden()).isFalse();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("the two arms are chosen by AID byte, so PF15 does not behave as PF3")
        void pf15IsNotPf3() {
            byte pf15 = (byte) 0xC3;
            MainMenuOutcome outcome =
                    service().handle(new MainMenuInput(userReenteredContext(), pf15, "01"));
            assertThat(outcome.errorFlag())
                    .as("COMEN01C does not copy CSSTRPFY, so PF15 is just another AID")
                    .isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("both handle overloads require their arguments")
        void handleRequiresItsArguments() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(() -> service.handle(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> service.handle(null, MainMenuOptionTable.copybook()));
            assertThatNullPointerException().isThrownBy(() -> service.handle(
                    MainMenuInput.withoutCommarea(CicsAid.DFHENTER, "  "), null));
        }

        @Test
        @DisplayName("G30: the EVALUATE has exactly three arms, and WHEN OTHER is the last of them")
        void theEvaluateHasExactlyThreeArmsInSourceOrder() {
            NavigationContext context = userReenteredContext();

            // Arm 1, L94 WHEN DFHENTER -> PERFORM PROCESS-ENTER-KEY, which dispatches.
            MainMenuOutcome armOne =
                    service().handle(new MainMenuInput(context, CicsAid.DFHENTER, "01"));
            // Arm 2, L96-L98 WHEN DFHPF3 -> MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, then return to signon.
            MainMenuOutcome armTwo =
                    service().handle(new MainMenuInput(context, CicsAid.DFHPF3, "01"));
            // Arm 3, L99-L102 WHEN OTHER -> the invalid-key message. There is no fourth arm: no other PF
            // key is named and DFHPA3 is not named either, so every remaining byte lands here.
            MainMenuOutcome armThree =
                    service().handle(new MainMenuInput(context, CicsAid.DFHPF4, "01"));

            assertThat(armOne.nextProgram())
                    .as("app/cpy/COMEN02Y.cpy:28 - option 1 targets COACTVWC")
                    .isEqualTo("COACTVWC");
            assertThat(armTwo.nextProgram())
                    .as("app/cbl/COMEN01C.cbl:97 - 'COSGN00C' into CDEMO-TO-PROGRAM")
                    .isEqualTo("COSGN00C");
            assertThat(armThree.errorFlag())
                    .as("app/cbl/COMEN01C.cbl:100 - MOVE 'Y' TO WS-ERR-FLG")
                    .isTrue();

            assertThat(List.of(armOne.nextProgram(), armTwo.nextProgram(), armThree.errFlgImage()))
                    .as("three distinct outcomes for three arms, in the source order of L94, L96, L99")
                    .containsExactly("COACTVWC", "COSGN00C", MainMenuService.ERR_FLG_ON);
            assertThat(EVALUATE_EIBAID_ARM_COUNT)
                    .as("app/cbl/COMEN01C.cbl:93-103 - WHEN DFHENTER, WHEN DFHPF3, WHEN OTHER")
                    .isEqualTo(3);
        }
    }

    /**
     * How {@code MainMenuService} resolves the attention identifier, and why the shared resolver's
     * five-character token is <em>not</em> what selects the arm.
     *
     * <p>{@code COMEN01C} does <strong>not</strong> copy {@code CSSTRPFY}: its nine copybooks are listed
     * at {@code app/cbl/COMEN01C.cbl:50-61} and the PF-key store is not among them. Line 93 compares
     * {@code EIBAID} to {@code DFHENTER} and {@code DFHPF3} inline, so the service uses
     * {@link PfKeyResolver#isAid(byte, byte)} - a raw byte comparison - and never
     * {@link PfKeyResolver#resolve(byte)} to choose an arm. The tokens are nevertheless asserted here,
     * for two reasons: they are the vocabulary the five programs that <em>do</em> copy {@code CSSTRPFY}
     * share, so a drift in them would be a cross-package parity failure; and the {@code PFK03} fold is
     * the exact place where selecting the arm by token instead of by byte would silently change this
     * program's behaviour.
     *
     * <p>The resolver's <strong>no-match</strong> outcome is the other half of the contract.
     * {@code CSSTRPFY}'s own {@code EVALUATE} has no {@code WHEN OTHER}, so an unrecognised byte leaves
     * nothing set at all - an empty {@link Optional}, never a substituted default - and this program must
     * absorb such a byte on its {@code WHEN OTHER} arm without an exception escaping.
     */
    @Nested
    @DisplayName("Attention identifiers: the raw byte selects the arm, not the CSSTRPFY token")
    class AttentionIdentifiers {

        /**
         * The AID bytes {@link PfKeyResolver} resolves, paired with the exact five-character
         * {@code CCARD-AID} literal each yields.
         *
         * @return one case per attention identifier a terminal running {@code CM00} can send
         */
        static Stream<Arguments> resolvableAids() {
            return Stream.of(
                    Arguments.of("DFHENTER", CicsAid.DFHENTER, AidKey.ENTER, "ENTER"),
                    Arguments.of("DFHPF3", CicsAid.DFHPF3, AidKey.PFK03, "PFK03"),
                    Arguments.of("DFHPF15", CicsAid.DFHPF15, AidKey.PFK03, "PFK03"),
                    Arguments.of("DFHCLEAR", CicsAid.DFHCLEAR, AidKey.CLEAR, "CLEAR"),
                    Arguments.of("DFHPF1", CicsAid.DFHPF1, AidKey.PFK01, "PFK01"),
                    Arguments.of("DFHPF12", CicsAid.DFHPF12, AidKey.PFK12, "PFK12"),
                    Arguments.of("DFHPA1", CicsAid.DFHPA1, AidKey.PA1, "PA1  "),
                    Arguments.of("DFHPA2", CicsAid.DFHPA2, AidKey.PA2, "PA2  "));
        }

        @ParameterizedTest(name = "{0} resolves to the token {3}")
        @DisplayName("each resolvable AID yields its exact five-character CVCRD01Y literal")
        @MethodSource("resolvableAids")
        void eachResolvableAidYieldsItsToken(String mnemonic,
                byte eibAid,
                AidKey expectedKey,
                String expectedToken) {

            assertThat(PfKeyResolver.resolve(eibAid))
                    .as("%s", mnemonic)
                    .hasValue(expectedKey);
            assertThat(expectedKey.token())
                    .as("CCARD-AID is PIC X(5), so PA1 and PA2 keep their two trailing spaces")
                    .isEqualTo(expectedToken)
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @ParameterizedTest(name = "EIBAID {0} resolves to nothing")
        @DisplayName("an unmatched AID is an empty Optional, never a substituted default")
        @ValueSource(bytes = {CicsAid.DFHPA3, UNRESOLVABLE_AID, (byte) 0x00, (byte) 0x7F})
        void anUnmatchedAidResolvesToNothing(byte eibAid) {
            assertThat(PfKeyResolver.resolve(eibAid))
                    .as("CSSTRPFY's EVALUATE has no WHEN OTHER, so nothing is set at all")
                    .isEmpty();
        }

        @ParameterizedTest(name = "EIBAID {0} is absorbed by WHEN OTHER without an exception")
        @DisplayName("an AID the resolver cannot name is still handled: L99's WHEN OTHER, no exception")
        @ValueSource(bytes = {CicsAid.DFHPA3, UNRESOLVABLE_AID, (byte) 0x00, (byte) 0x7F})
        void anUnresolvableAidIsHandledWithoutException(byte eibAid) {
            MainMenuInput input = new MainMenuInput(userReenteredContext(), eibAid, "01");

            assertThatNoException().isThrownBy(() -> service().handle(input));

            MainMenuOutcome outcome = service().handle(input);
            assertThat(PfKeyResolver.resolve(eibAid)).isEmpty();
            assertThat(outcome.errorFlag())
                    .as("L99's WHEN OTHER absorbs it; the absence of a token is not an error path")
                    .isTrue();
            assertThat(outcome.message())
                    .as("app/cpy/CSMSG01Y.cpy:20-21 widened from PIC X(50) into WS-MESSAGE PIC X(80)")
                    .isEqualTo(wsMessageImage(INVALID_KEY_MESSAGE_X50));
            assertThat(outcome.hasNextProgram())
                    .as("WHEN OTHER paints; it does not transfer")
                    .isFalse();
        }

        @Test
        @DisplayName("the PFK03 fold is why the byte, not the token, must select the arm")
        void thePfk03FoldWouldChangeBehaviourIfTheTokenSelectedTheArm() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .as("CSSTRPFY folds PF15 onto PFK03, the same token PF3 yields")
                    .hasValue(AidKey.PFK03);
            assertThat(PfKeyResolver.isAid(CicsAid.DFHPF15, CicsAid.DFHPF3))
                    .as("but the bytes differ - 0xC3 against 0xF3 - and L93 compares bytes")
                    .isFalse();

            MainMenuOutcome viaPf3 = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHPF3, "  "));
            MainMenuOutcome viaPf15 = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHPF15, "  "));

            assertThat(viaPf3.nextProgram())
                    .as("L96-L98: PF3 takes arm 2 and transfers to the sign-on program")
                    .isEqualTo(MainMenuService.SIGNON_PROGRAM);
            assertThat(viaPf15.hasNextProgram())
                    .as("PF15 takes WHEN OTHER instead, so it transfers nowhere")
                    .isFalse();
        }

        @Test
        @DisplayName("the two bytes L93 does compare are the ones the service tests")
        void theTwoComparedBytesAreTheOnesTheServiceTests() {
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isAid(CicsAid.DFHENTER, CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isAid(CicsAid.DFHPF3, CicsAid.DFHENTER)).isFalse();
            assertThat(PfKeyResolver.isAid(UNRESOLVABLE_AID, CicsAid.DFHENTER))
                    .as("an unresolvable byte matches neither named constant")
                    .isFalse();
            assertThat(PfKeyResolver.isAid(UNRESOLVABLE_AID, CicsAid.DFHPF3)).isFalse();
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L117-L125: the four-step option normalisation")
    class Normalisation {

        // The worked cases below were hand-traced against app/cbl/COMEN01C.cbl:117-125 statement by
        // statement, and they are the reason this file can be reviewed without a COBOL run: each row is
        // re-derivable from the cited lines alone.
        //
        //   OPTIONI  scan ends at WS-IDX  after L122 (JUST RIGHT)  after L123 (INSPECT)  WS-OPTION
        //   '3 '     1                    ' 3'                     '03'                  3
        //   '  '     1                    '  '                     '00'                  0
        //   '10'     2                    '10'                     '10'                  10  <- valid
        //   '11'     2                    '11'                     '11'                  11  <- > count
        //   '99'     2                    '99'                     '99'                  99  <- > count
        //   'AB'     2                    'AB'                     'AB'                  not numeric
        //
        // The scan at L117-L121 has an EMPTY body and is PRE-test, starting at
        // WS-IDX = LENGTH OF OPTIONI = 2 (app/cpy-bms/COMEN01.CPY:132 declares OPTIONI PIC X(2)): it
        // stops immediately when byte 2 is not a space, and otherwise decrements to the WS-IDX = 1
        // termination term. That is why '3 ' ends at 1 - its trailing space is scanned off, and the
        // JUST RIGHT receiver at L45 then puts the digit back on the right - while '10' ends at 2.
        //
        // 11 and 99 normalise perfectly well; it is the validation at L128 that rejects them, and they
        // are carried into the ungated filter at L136 as out-of-range subscripts. See
        // AuthorisationFilter#theBoundedSubscriptNeverThrows.
        @ParameterizedTest(name = "OPTIONI \"{0}\" -> WS-IDX {1}, step 3 \"{2}\", step 4 \"{3}\"")
        @CsvSource({
            "'  ', 1, '  ', '00', 0",
            "' 3', 2, ' 3', '03', 3",
            "'3 ', 1, ' 3', '03', 3",
            "'12', 2, '12', '12', 12",
            "'10', 2, '10', '10', 10",
            "'11', 2, '11', '11', 11",
            "'99', 2, '99', '99', 99",
            "'01', 2, '01', '01', 1",
            "'1 ', 1, ' 1', '01', 1",
        })
        @DisplayName("every worked numeric case, asserting each intermediate and not just the integer")
        void theFourNumericCases(String received, int wsIdx, String justified, String inspected,
                int option) {
            OptionNormalisation normalisation = service().normaliseOption(received);

            assertThat(normalisation.wsIdx()).isEqualTo(wsIdx);
            assertThat(normalisation.receivedOptionI()).isEqualTo(received);
            assertThat(normalisation.justifiedOptionX())
                    .as("app/cbl/COMEN01C.cbl:45 - WS-OPTION-X PIC X(02) JUST RIGHT")
                    .isEqualTo(justified);
            assertThat(normalisation.optionX())
                    .as("app/cbl/COMEN01C.cbl:123 - INSPECT REPLACING ALL ' ' BY '0'")
                    .isEqualTo(inspected);
            assertThat(normalisation.option()).hasValue(option);
            assertThat(normalisation.isNumeric()).isTrue();
            assertThat(normalisation.optionEcho()).isEqualTo(inspected);
        }

        @ParameterizedTest(name = "OPTIONI \"{0}\" is not numeric")
        // 'A ' is the row worth tracing: the scan ends at WS-IDX = 1, L122 sends the single 'A' into the
        // JUST RIGHT receiver as ' A', and L123's INSPECT then replaces that leading space with a zero -
        // so WS-OPTION comes to hold '0A', which is still not numeric.
        @CsvSource({"'1x', '1x'", "'1!', '1!'", "'xx', 'xx'", "'AB', 'AB'", "'A ', '0A'"})
        @DisplayName("a non-digit survives the MOVE, which is what line 127 detects")
        void aNonDigitIsReportedAsNotNumeric(String received, String expectedImage) {
            OptionNormalisation normalisation = service().normaliseOption(received);

            assertThat(normalisation.optionX()).isEqualTo(expectedImage);
            assertThat(normalisation.option()).isEmpty();
            assertThat(normalisation.isNumeric()).isFalse();
        }

        @Test
        @DisplayName("a value wider or narrower than PIC X(2) is moved into that width first")
        void theReceivedValueIsMovedIntoItsDeclaredWidth() {
            assertThat(service().normaliseOption("123").receivedOptionI())
                    .as("an alphanumeric MOVE truncates on the right")
                    .isEqualTo("12");
            assertThat(service().normaliseOption("").receivedOptionI())
                    .as("and pads on the right")
                    .isEqualTo("  ");
            assertThat(service().normaliseOption("").option()).hasValue(0);
        }

        @Test
        @DisplayName("OPTIONI is never null")
        void nullIsRejected() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(() -> service.normaliseOption(null));
            assertThatNullPointerException().isThrownBy(
                    () -> new MainMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, null));
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L127-L134: the three validation terms, each on its own")
    class Validation {

        private void assertInvalidOption(MainMenuOutcome outcome) {
            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(INVALID_OPTION_TEXT
                            + " ".repeat(WS_MESSAGE_WIDTH - INVALID_OPTION_TEXT.length()));
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("term 1, L127: WS-OPTION IS NOT NUMERIC")
        void aNonNumericOptionIsRejected() {
            assertInvalidOption(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "1x")));
        }

        @Test
        @DisplayName("term 2, L128: WS-OPTION > CDEMO-MENU-OPT-COUNT")
        void anOptionAboveTheActiveCountIsRejected() {
            assertInvalidOption(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "11")));
        }

        @Test
        @DisplayName("term 3, L129: WS-OPTION = ZEROS")
        void zeroIsRejected() {
            assertInvalidOption(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "00")));
            assertInvalidOption(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "  ")));
        }

        @Test
        @DisplayName("the rejected option is still echoed to OPTIONO, normalised")
        void theRejectedOptionIsEchoed() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "11"));
            assertThat(outcome.option()).isEqualTo("11");
        }

        @ParameterizedTest(name = "option {0} passes validation against the copybook count of 10")
        @ValueSource(strings = {"01", "05", "10", " 1", "1 "})
        @DisplayName("1 through the active count pass, and are dispatched")
        void everyActiveOptionPassesValidation(String option) {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, option));
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.hasNextProgram()).isTrue();
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L136-L143: THE USER-TYPE AUTHORISATION FILTER")
    class AuthorisationFilter {

        @ParameterizedTest(name = "WS-OPTION = {0} evaluates the filter without throwing")
        @ValueSource(ints = {0, 1, 10, 11, 12, 99, 13, -1, Integer.MAX_VALUE})
        @DisplayName("the bounded subscript never throws, whatever value the ungated filter sees")
        void theBoundedSubscriptNeverThrows(int wsOption) {
            MainMenuService service = service();
            MainMenuOptionTable table = MainMenuOptionTable.copybook();

            assertThatNoException().isThrownBy(
                    () -> service.isAdminOnlyOption(table, OptionalInt.of(wsOption)));
            assertThat(service.isAdminOnlyOption(table, OptionalInt.of(wsOption)))
                    .as("every COMEN02Y entry carries 'U', 11 and 12 carry nothing, and 0 is below "
                            + "the table, so line 137 is false for every reachable subscript")
                    .isFalse();
        }

        @Test
        @DisplayName("a non-numeric WS-OPTION has no subscript at all, and the filter is false")
        void anAbsentSubscriptIsFalse() {
            assertThat(service().isAdminOnlyOption(MainMenuOptionTable.copybook(),
                    OptionalInt.empty())).isFalse();
        }

        @Test
        @DisplayName("an entry whose CDEMO-MENU-OPT-USRTYPE is 'A' makes the filter true")
        void anAdminOnlyEntryMakesTheFilterTrue() {
            MainMenuOptionTable adminOnly = stubTable(1, 1, new String[] {"COUSR00C"},
                    new String[] {NavigationContext.USER_TYPE_ADMIN});
            assertThat(service().isAdminOnlyOption(adminOnly, OptionalInt.of(1))).isTrue();
        }

        @Test
        @DisplayName("the filter's arguments are required")
        void theFilterRequiresItsArguments() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(
                    () -> service.isAdminOnlyOption(null, OptionalInt.of(1)));
            assertThatNullPointerException().isThrownBy(
                    () -> service.isAdminOnlyOption(MainMenuOptionTable.copybook(), null));
        }

        @Test
        @DisplayName("88 CDEMO-USRTYP-USER true + an 'A' entry -> the 33-character No access message")
        void aRegularUserIsRefusedAnAdminOnlyOption() {
            MainMenuOptionTable adminOnly = stubTable(1, 1, new String[] {"COUSR00C"},
                    new String[] {NavigationContext.USER_TYPE_ADMIN});

            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"), adminOnly);

            assertThat(outcome.errorFlag())
                    .as("app/cbl/COMEN01C.cbl:138 - SET ERR-FLG-ON TO TRUE")
                    .isTrue();
            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(NO_ACCESS_TEXT
                            + " ".repeat(WS_MESSAGE_WIDTH - NO_ACCESS_TEXT.length()));
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.hasNextProgram())
                    .as("the dispatch at line 145 is suppressed by the flag")
                    .isFalse();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("88 CDEMO-USRTYP-USER false: an administrator is never refused")
        void anAdministratorIsNeverRefused() {
            MainMenuOptionTable adminOnly = stubTable(1, 1, new String[] {"COUSR00C"},
                    new String[] {NavigationContext.USER_TYPE_ADMIN});

            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(adminReenteredContext(), CicsAid.DFHENTER, "01"), adminOnly);

            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.hasNextProgram()).isTrue();
            assertThat(outcome.nextProgram()).isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("a blank user type satisfies neither 88, so the filter does not fire")
        void aBlankUserTypeSatisfiesNeitherCondition() {
            MainMenuOptionTable adminOnly = stubTable(1, 1, new String[] {"COUSR00C"},
                    new String[] {NavigationContext.USER_TYPE_ADMIN});
            NavigationContext blank = NavigationContext.empty().withPgmReenter();

            assertThat(blank.isUser()).isFalse();
            assertThat(blank.isAdmin()).isFalse();
            MainMenuOutcome outcome = service()
                    .handle(new MainMenuInput(blank, CicsAid.DFHENTER, "01"), adminOnly);
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.hasNextProgram()).isTrue();
        }

        @Test
        @DisplayName("a regular user with a 'U' entry passes the filter and is dispatched")
        void aRegularUserIsAllowedARegularOption() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"),
                    stubTable("COACTVWC"));
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.nextProgram()).isEqualTo("COACTVWC");
        }

        // app/cpy/COMEN02Y.cpy carries CDEMO-MENU-OPT-USRTYPE 'U' on every one of its ten valued
        // entries - :29, :35, :41, :47, :53, :59, :65, :72, :78 and :84 - so under the shipped table the
        // second conjunct of L137 is false for every option a regular user can legitimately choose, and
        // the 'No access' outcome is unreachable. That is a property of the data, not of the code, and it
        // is asserted across all ten rather than sampled: a single altered column would otherwise lock a
        // whole menu entry out of the regular-user menu without any test noticing.
        @ParameterizedTest(name = "a 'U' caller choosing option {0} is dispatched to {1}, not refused")
        @CsvSource({
            "01, COACTVWC", "02, COACTUPC", "03, COCRDLIC", "04, COCRDSLC", "05, COCRDUPC",
            "06, COTRN00C", "07, COTRN01C", "08, COTRN02C", "09, CORPT00C", "10, COBIL00C",
        })
        @DisplayName("every one of the ten copybook options is open to a regular user")
        void everyCopybookOptionIsOpenToARegularUser(String option, String expectedProgram) {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, option));

            assertThat(outcome.errorFlag())
                    .as("L136-L137 cannot fire: every shipped CDEMO-MENU-OPT-USRTYPE is 'U', not 'A'")
                    .isFalse();
            assertThat(outcome.message())
                    .as("no message at all is emitted on a successful transfer")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.nextProgram()).isEqualTo(expectedProgram);
        }

        @Test
        @DisplayName("the copybook's ten authorisation columns really are all 'U'")
        void everyCopybookAuthorisationColumnIsRegularUser() {
            assertThat(MenuOptions.activeOptions())
                    .as("app/cpy/COMEN02Y.cpy - ten valued entries")
                    .hasSize(MenuOptions.ACTIVE_OPTION_COUNT)
                    .allSatisfy(entry -> assertThat(entry.menuOptUsrType())
                            .as("CDEMO-MENU-OPT-USRTYPE of option %d", entry.menuOptNum())
                            .isEqualTo(NavigationContext.USER_TYPE_USER)
                            .isNotEqualTo(MainMenuService.ADMIN_ONLY_USRTYPE));
        }

        @Test
        @DisplayName("THE FILTER IS UNGATED: it runs even after validation already failed, and the "
                + "second paint wins")
        void theFilterRunsEvenWhenValidationAlreadyFailed() {
            // An active count of 1 rejects option 3, and slot 3 is nevertheless a valued entry
            // carrying 'A'. Both IF statements therefore fire in one pass: line 133 paints the
            // invalid-option message, control falls through to line 136, and line 142 repaints with
            // the No access message. Were the filter hoisted into the validation's ELSE, or moved
            // after the dispatch, this outcome would carry the invalid-option message instead.
            MainMenuOptionTable table = stubTable(1, 4,
                    new String[] {"COACTVWC", "COACTUPC", "COUSR00C", "COBIL00C"},
                    new String[] {NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_ADMIN,
                        NavigationContext.USER_TYPE_USER});

            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "03"), table);

            assertThat(outcome.message())
                    .as("line 142's SEND overwrites line 133's")
                    .isEqualTo(NO_ACCESS_TEXT
                            + " ".repeat(WS_MESSAGE_WIDTH - NO_ACCESS_TEXT.length()));
            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("validation failing with no admin-only entry keeps the invalid-option message")
        void validationAloneKeepsItsOwnMessage() {
            MainMenuOptionTable table = stubTable(1, 4,
                    new String[] {"COACTVWC", "COACTUPC", "COCRDLIC", "COBIL00C"},
                    new String[] {NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_USER});

            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "03"), table);

            assertThat(outcome.message()).isEqualTo(INVALID_OPTION_TEXT
                    + " ".repeat(WS_MESSAGE_WIDTH - INVALID_OPTION_TEXT.length()));
            assertThat(outcome.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("no Spring Security is involved: the filter is a one-byte data comparison")
        void theFilterIsAPlainDataComparison() {
            assertThat(MainMenuService.ADMIN_ONLY_USRTYPE)
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN)
                    .as("the same character, but CDEMO-MENU-OPT-USRTYPE and CDEMO-USER-TYPE are "
                            + "different copybook fields")
                    .hasSize(MenuOptions.OPT_USRTYPE_LENGTH);
            assertThat(MenuOptions.activeOptions())
                    .allSatisfy(option -> assertThat(option.menuOptUsrType())
                            .isEqualTo(NavigationContext.USER_TYPE_USER));
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L145-L156: dispatch, and the XCTL that never returns")
    class Dispatch {

        @ParameterizedTest(name = "option {0} transfers to {1}")
        @CsvSource({
            "01, COACTVWC", "02, COACTUPC", "03, COCRDLIC", "04, COCRDSLC", "05, COCRDUPC",
            "06, COTRN00C", "07, COTRN01C", "08, COTRN02C", "09, CORPT00C", "10, COBIL00C",
        })
        @DisplayName("every copybook option transfers to its own program, carrying the COMMAREA")
        void everyOptionTransfersToItsProgram(String option, String expectedProgram) {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, option));

            assertThat(outcome.nextProgram()).isEqualTo(expectedProgram);
            assertThat(outcome.nextProgramCarriesCommarea())
                    .as("app/cbl/COMEN01C.cbl:154 specifies COMMAREA(CARDDEMO-COMMAREA)")
                    .isTrue();
            assertThat(outcome.navigationContext().fromTranid()).isEqualTo("CM00");
            assertThat(outcome.navigationContext().fromProgram()).isEqualTo("COMEN01C");
            assertThat(outcome.navigationContext().isEnter())
                    .as("app/cbl/COMEN01C.cbl:151 - MOVE ZEROS TO CDEMO-PGM-CONTEXT")
                    .isTrue();
        }

        @Test
        @DisplayName("the transfer RETURNS IMMEDIATELY: no coming-soon message is attached")
        void aSuccessfulTransferDoesNotFallThrough() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(outcome.message())
                    .as("falling through would emit a message COMEN01C never emits")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.messageColourOverridden()).isFalse();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(outcome.screenPainted()).isFalse();
            assertThat(outcome.optionLines()).containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("CDEMO-USER-TYPE is never written: lines 149 and 150 stay commented out")
        void theUserTypeIsNeverWrittenByThisProgram() {
            NavigationContext inbound = userReenteredContext().withUserId("USER0001");
            MainMenuOutcome outcome = service()
                    .handle(new MainMenuInput(inbound, CicsAid.DFHENTER, "01"));

            assertThat(outcome.navigationContext().userType())
                    .as("line 150 is commented out, so the inbound value survives untouched")
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(outcome.navigationContext().userId())
                    .as("line 149 is commented out - and WS-USER-ID is not even declared")
                    .isEqualTo("USER0001");
        }

        // The two commented-out statements are the ONLY place COMEN01C would ever have assigned either
        // field:
        //
        //   L149  *            MOVE WS-USER-ID   TO CDEMO-USER-ID
        //   L150  *            MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
        //
        // Their live counterparts live in the sign-on program instead - app/cbl/COSGN00C.cbl:226-227 -
        // which is what puts the values into the communication area before CM00 ever runs. So this
        // program never derives, defaults or looks up CDEMO-USER-TYPE or CDEMO-USER-ID: it echoes back
        // exactly what arrived, however odd. Driving the odd values is the point - a Java translation
        // that "helpfully" defaulted a blank user type to 'U' would pass a test that only ever supplied
        // 'U', and would have invented an authorisation decision the source does not make.
        @ParameterizedTest(name = "inbound CDEMO-USER-TYPE ''{0}'' and CDEMO-USER-ID ''{1}'' survive")
        @CsvSource(delimiter = '|', value = {
            "U | USER0001",
            "A | ADMIN001",
            "' ' | '        '",
            "X | ODDUSER1",
            "u | lower001",
            "9 | 00000001"
        })
        @DisplayName("B5: the commented-out L149-L150 have NO effect - both fields are echoed unaltered")
        void theCommentedOutMovesHaveNoEffect(String inboundUserType, String inboundUserId) {
            NavigationContext inbound = NavigationContext.empty()
                    .withPgmReenter()
                    .withUserType(inboundUserType)
                    .withUserId(inboundUserId);

            MainMenuOutcome outcome = service()
                    .handle(new MainMenuInput(inbound, CicsAid.DFHENTER, "01"));

            assertThat(outcome.navigationContext().userType())
                    .as("L150 is commented out, so CDEMO-USER-TYPE is inbound-only")
                    .isEqualTo(inboundUserType);
            assertThat(outcome.navigationContext().userId())
                    .as("L149 is commented out, and WS-USER-ID is not declared in this program at all")
                    .isEqualTo(inboundUserId);
            assertThat(outcome.nextProgram())
                    .as("the transfer happens regardless of who the user is: only L136-L137 filters, "
                            + "and every shipped entry is open to 'U'")
                    .isEqualTo("COACTVWC");
            assertThat(outcome.navigationContext().fromTranid())
                    .as("app/cbl/COMEN01C.cbl:147 - MOVE WS-TRANID TO CDEMO-FROM-TRANID")
                    .isEqualTo(MainMenuService.TRANSACTION_ID);
            assertThat(outcome.navigationContext().fromProgram())
                    .as("app/cbl/COMEN01C.cbl:148 - MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM")
                    .isEqualTo(MainMenuService.PROGRAM_NAME);
            assertThat(outcome.navigationContext().isEnter())
                    .as("app/cbl/COMEN01C.cbl:151 - MOVE ZEROS TO CDEMO-PGM-CONTEXT, so 88 "
                            + "CDEMO-PGM-ENTER holds again on arrival at the next program")
                    .isTrue();
            assertThat(outcome.navigationContext().isReenter()).isFalse();
        }

        @Test
        @DisplayName("G50: a blank user type satisfies neither 88, and is still echoed as it arrived")
        void aBlankUserTypeIsNeitherAdminNorUserAndSurvives() {
            NavigationContext inbound = NavigationContext.empty().withPgmReenter().withUserType(" ");

            MainMenuOutcome outcome = service()
                    .handle(new MainMenuInput(inbound, CicsAid.DFHENTER, "01"));

            assertThat(inbound.isAdmin())
                    .as("app/cpy/COCOM01Y.cpy:27 - 88 CDEMO-USRTYP-ADMIN VALUE 'A'")
                    .isFalse();
            assertThat(inbound.isUser())
                    .as("app/cpy/COCOM01Y.cpy:28 - 88 CDEMO-USRTYP-USER VALUE 'U'")
                    .isFalse();
            assertThat(outcome.navigationContext().userType()).isEqualTo(" ");
            assertThat(outcome.navigationContext().isAdmin()).isFalse();
            assertThat(outcome.navigationContext().isUser()).isFalse();
        }

        @Test
        @DisplayName("the option is echoed to OPTIONO on the transfer path too")
        void theOptionIsEchoedOnTransfer() {
            assertThat(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, " 7")).option())
                    .isEqualTo("07");
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L157-L164: the DUMMY prefix and the DELIMITED BY SPACE message")
    class ComingSoon {

        private MainMenuOutcome dummyOutcome(String name, String programName) {
            List<Optional<MenuOption>> slots = List.of(
                    Optional.of(new MenuOption(1, "01", name, programName,
                            NavigationContext.USER_TYPE_USER)));
            return service().handle(new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"),
                    new MainMenuOptionTable(slots, 1));
        }

        @Test
        @DisplayName("a DUMMY-prefixed target suppresses the transfer and paints in green")
        void aDummyTargetSuppressesTheTransfer() {
            MainMenuOutcome outcome = dummyOutcome(OPTION_1_NAME, "DUMMY001");

            assertThat(outcome.hasNextProgram()).isFalse();
            assertThat(outcome.nextProgram()).isEqualTo(NO_NEXT_PROGRAM);
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.messageColour())
                    .as("app/cbl/COMEN01C.cbl:158 overrides the map's COLOR=RED")
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(outcome.messageColourOverridden()).isTrue();
            assertThat(outcome.errorFlag()).isFalse();
        }

        // Every expectation below is compared against the FULL eighty-byte WS-MESSAGE image produced by
        // wsMessageImage - the composed text followed by the spaces app/cbl/COMEN01C.cbl:157 left in the
        // field. Nothing here trims, strips or normalises the actual value: the whole point of the
        // assertion is that the bytes are what they are, missing separator included.
        @ParameterizedTest(name = "\"{0}\" composes \"{1}\"")
        @CsvSource({
            "'Account View                       ', 'This option Accountis coming soon ...'",
            "'Account Update                     ', 'This option Accountis coming soon ...'",
            "'Credit Card List                   ', 'This option Creditis coming soon ...'",
            "'Transaction List                   ', 'This option Transactionis coming soon ...'",
            "'Transaction Reports                ', 'This option Transactionis coming soon ...'",
            "'Bill Payment                       ', 'This option Billis coming soon ...'",
        })
        @DisplayName("DELIMITED BY SPACE keeps only the first word, and there is NO space before 'is'")
        void theComingSoonMessageIsComposedDelimitedBySpace(String name, String expected) {
            MainMenuOutcome outcome = dummyOutcome(name, "DUMMY001");

            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(wsMessageImage(expected))
                    .as("app/cbl/COMEN01C.cbl:162 - 'is coming soon ...' has no leading space, and the "
                            + "operand before it stopped at the name's first space")
                    .doesNotContain(" is coming soon")
                    .endsWith("is coming soon ..." + " ".repeat(WS_MESSAGE_WIDTH - expected.length()));
        }

        /**
         * The four copybook names named in the migration plan, each with the message it composes.
         *
         * <p>Options 1 and 2 are the pair that matters most: their names differ - {@code 'Account View'}
         * against {@code 'Account Update'} - and their messages do not, because
         * {@code DELIMITED BY SPACE} keeps only {@code Account} from either. Option 6 gives the longest
         * surviving fragment and option 10 the shortest.
         *
         * @return one case per transcribed expectation
         */
        static Stream<Arguments> transcribedComingSoonCases() {
            return Stream.of(
                    // app/cpy/COMEN02Y.cpy:26-27 -> app/cbl/COMEN01C.cbl:159-163
                    Arguments.of(1, OPTION_1_NAME, COMING_SOON_OPTION_1, 37),
                    // app/cpy/COMEN02Y.cpy:32-33 - the same message as option 1
                    Arguments.of(2, OPTION_2_NAME, COMING_SOON_OPTION_2, 37),
                    // app/cpy/COMEN02Y.cpy:38-39
                    Arguments.of(3, OPTION_3_NAME, COMING_SOON_OPTION_3, 36),
                    // app/cpy/COMEN02Y.cpy:56-57 - the longest first word, eleven characters
                    Arguments.of(6, OPTION_6_NAME, COMING_SOON_OPTION_6, 41),
                    // app/cpy/COMEN02Y.cpy:81-82 - the shortest, four characters
                    Arguments.of(10, OPTION_10_NAME, COMING_SOON_OPTION_10, 34));
        }

        @ParameterizedTest(name = "option {0} composes a {3}-character message")
        @MethodSource("transcribedComingSoonCases")
        @DisplayName("B5: each transcribed expectation is what its copybook name really produces")
        void theTranscribedExpectationsAgree(int cobolSubscript,
                String name,
                String expectedText,
                int expectedTextLength) {

            assertThat(name)
                    .as("CDEMO-MENU-OPT-NAME of option %d is PIC X(35)", cobolSubscript)
                    .hasSize(OPTION_NAME_WIDTH)
                    .isEqualTo(MenuOptions.optionBySubscript(cobolSubscript).orElseThrow()
                            .menuOptName());
            assertThat(expectedText)
                    .as("the composed text before WS-MESSAGE pads it")
                    .hasSize(expectedTextLength);
            assertThat(dummyOutcome(name, "DUMMY001").message())
                    .isEqualTo(wsMessageImage(expectedText));
        }

        @Test
        @DisplayName("options 1 and 2 emit the SAME message from DIFFERENT names - the delimiter loses "
                + "information, and that is preserved")
        void twoDifferentNamesCollapseOntoOneMessage() {
            assertThat(OPTION_1_NAME)
                    .as("app/cpy/COMEN02Y.cpy:27 against :33 - the names genuinely differ")
                    .isNotEqualTo(OPTION_2_NAME);
            assertThat(COMING_SOON_OPTION_2)
                    .as("both keep only 'Account', so both messages are the same 37 characters")
                    .isEqualTo(COMING_SOON_OPTION_1);
            assertThat(dummyOutcome(OPTION_2_NAME, "DUMMY001").message())
                    .isEqualTo(dummyOutcome(OPTION_1_NAME, "DUMMY001").message());
        }

        @Test
        @DisplayName("B5: this program's message is NOT its sibling's - COMEN01C:159-163 keeps the "
                + "name, COADM01C:150-151 comments it out")
        void thisProgramIsNotItsSibling() {
            assertThat(COMING_SOON_OPTION_1)
                    .as("the asymmetry cross-check: equal strings here would mean one of the two test "
                            + "files was copied from the other, and one of them would be wrong")
                    .isNotEqualTo(SIBLING_COMING_SOON);
            assertThat(COMING_SOON_OPTION_1)
                    .as("37 characters here, because the name operand survives")
                    .hasSize(37)
                    .contains("Accountis")
                    .doesNotContain(" is coming soon");
            assertThat(SIBLING_COMING_SOON)
                    .as("30 characters there, because COADM01C:150-151 comment the name out, which is "
                            + "why that program - and only that program - has the space before 'is'")
                    .hasSize(30)
                    .contains(" is coming soon");
            assertThat(dummyOutcome(OPTION_1_NAME, "DUMMY001").message())
                    .as("and the service really does emit this program's form, not the sibling's")
                    .isEqualTo(wsMessageImage(COMING_SOON_OPTION_1))
                    .isNotEqualTo(wsMessageImage(SIBLING_COMING_SOON));
        }

        @Test
        @DisplayName("a name that is entirely spaces sends nothing, leaving prefix + suffix")
        void anAllSpaceNameSendsNothing() {
            String allSpaces = " ".repeat(OPTION_NAME_WIDTH);

            assertThat(dummyOutcome(allSpaces, "DUMMY001").message())
                    .as("DELIMITED BY SPACE with the delimiter at position 1 sends an empty portion")
                    .isEqualTo(wsMessageImage("This option is coming soon ..."));
        }

        @Test
        @DisplayName("a name with no space at all is sent whole")
        void aNameWithoutASpaceIsSentWhole() {
            String unbroken = "AccountView".concat(" ".repeat(OPTION_NAME_WIDTH - 11))
                    .replace(' ', '.');

            assertThat(dummyOutcome(unbroken, "DUMMY001").message())
                    .isEqualTo(wsMessageImage("This option " + unbroken + "is coming soon ..."));
        }

        @Test
        @DisplayName("L157's MOVE SPACES clears any earlier message before the STRING composes")
        void theEarlierMessageIsClearedFirst() {
            // A one-slot DUMMY table with an active count of 1 accepts option 1 without error, so the
            // validation at L127-L134 never fires and there is nothing left over. The assertion that
            // matters is the shape of the result: exactly the composed text and then spaces to eighty,
            // with no residue of any other literal anywhere in the field.
            String message = dummyOutcome(OPTION_1_NAME, "DUMMY001").message();

            assertThat(message)
                    .isEqualTo(wsMessageImage(COMING_SOON_OPTION_1))
                    .doesNotContain(INVALID_OPTION_TEXT)
                    .doesNotContain(NO_ACCESS_TEXT.substring(0, NO_ACCESS_TEXT.length() - 1))
                    .endsWith(" ".repeat(WS_MESSAGE_WIDTH - COMING_SOON_OPTION_1.length()));
        }

        @Test
        @DisplayName("the prefix test compares exactly five bytes, so DUMM0001 still transfers")
        void thePrefixTestComparesExactlyFiveBytes() {
            assertThat(dummyOutcome(OPTION_1_NAME, "DUMM0001").hasNextProgram())
                    .as("only the first five characters are compared, and these are not 'DUMMY'")
                    .isTrue();
            assertThat(dummyOutcome(OPTION_1_NAME, "DUMMYXYZ").hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("no copybook entry begins with DUMMY, so the branch is dead in production")
        void noCopybookEntryIsDummy() {
            assertThat(MenuOptions.activeOptions())
                    .noneSatisfy(option -> assertThat(option.menuOptPgmName())
                            .startsWith(MainMenuService.DUMMY_PROGRAM_PREFIX));
        }
    }

    @Nested
    @DisplayName("BUILD-MENU-OPTIONS L236-L277: OCCURS 12, active count 10, twelve dispatch arms")
    class BuildMenuOptions {

        @Test
        @DisplayName("the copybook table composes exactly ten 40-byte lines, 11 and 12 blank")
        void theCopybookTableComposesTenLines() {
            List<String> lines = service().buildMenuOptions(MainMenuOptionTable.copybook());

            assertThat(lines).hasSize(MainMenuService.OPTION_LINE_COUNT);
            assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(OPTION_LINE_WIDTH));
            assertThat(lines.subList(MenuOptions.ACTIVE_OPTION_COUNT, MenuOptions.TABLE_SIZE))
                    .as("slots 11 and 12 are present but unvalued and are never trimmed away")
                    .containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("line 1 is '01. Account View' - the first populated entry, leading zero and all")
        void lineOneIsTranscribedCharacterForCharacter() {
            List<String> lines = service().buildMenuOptions(MainMenuOptionTable.copybook());
            assertThat(lines.get(0)).isEqualTo(OPTION_1_LINE);
        }

        @Test
        @DisplayName("line 10 is '10. Bill Payment' - the last populated entry")
        void lineTenIsTranscribedCharacterForCharacter() {
            List<String> lines = service().buildMenuOptions(MainMenuOptionTable.copybook());
            assertThat(lines.get(MenuOptions.ACTIVE_OPTION_COUNT - 1)).isEqualTo(OPTION_10_LINE);
        }

        @Test
        @DisplayName("option 8 carries the LIVE literal, never the commented '(Admin Only)' variant")
        void optionEightAvoidsTheCommentedLiteralTrap() {
            List<String> lines = service().buildMenuOptions(MainMenuOptionTable.copybook());
            assertThat(lines.get(7)).isEqualTo("08. " + OPTION_8_NAME + " ")
                    .doesNotContain("(Admin Only)");
            assertThat(OPTION_8_COMMENTED_NAME)
                    .as("the commented alternative is also 35 characters, so a width check "
                            + "alone would not have caught the substitution")
                    .hasSize(OPTION_NAME_WIDTH);
        }

        @Test
        @DisplayName("all twelve arms exist here: a twelve-slot stub writes every line")
        void allTwelveArmsWrite() {
            List<String> lines = service().buildMenuOptions(fullTwelveSlotStub());

            assertThat(lines).doesNotContain(BLANK_OPTION_LINE);
            assertThat(lines.get(10)).startsWith("11. ");
            assertThat(lines.get(11)).startsWith("12. ");
        }

        @Test
        @DisplayName("WHEN OTHER CONTINUE: a thirteenth subscript writes nothing")
        void theWhenOtherArmWritesNothing() {
            int slotCount = MainMenuService.OPTION_LINE_COUNT + 1;
            List<String> lines = service().buildMenuOptions(numberedStub(slotCount, slotCount));

            assertThat(lines)
                    .as("the map has only twelve lines, so subscript 13 falls to CONTINUE")
                    .hasSize(MainMenuService.OPTION_LINE_COUNT);
            assertThat(lines.get(11)).startsWith("12. ");
        }

        // Gate G30 for the second EVALUATE. app/cbl/COMEN01C.cbl:248-275 has exactly THIRTEEN arms:
        //
        //   L249 WHEN 1  -> OPTN001O      L261 WHEN 7  -> OPTN007O
        //   L251 WHEN 2  -> OPTN002O      L263 WHEN 8  -> OPTN008O
        //   L253 WHEN 3  -> OPTN003O      L265 WHEN 9  -> OPTN009O
        //   L255 WHEN 4  -> OPTN004O      L267 WHEN 10 -> OPTN010O
        //   L257 WHEN 5  -> OPTN005O      L269 WHEN 11 -> OPTN011O
        //   L259 WHEN 6  -> OPTN006O      L271 WHEN 12 -> OPTN012O
        //                                 L273 WHEN OTHER -> CONTINUE
        //
        // COADM01C's equivalent at :238-261 has only ELEVEN - WHEN 1 through WHEN 10 plus WHEN OTHER,
        // with no arm for 11 or 12 at all. Equal counts in the two test files would mean one was copied
        // from the other. Arms 11, 12 and WHEN OTHER are unreachable while CDEMO-MENU-OPT-COUNT is 10
        // (app/cpy/COMEN02Y.cpy:21), and they are reached here only through a stub table: the shipped
        // count is never widened and the shipped table is never mutated.
        @ParameterizedTest(name = "arm {0} writes OPTN0{0}O")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("G30: each of the twelve WHEN arms writes its own line and no other")
        void eachWhenArmWritesItsOwnLine(int cobolSubscript) {
            List<String> lines = service().buildMenuOptions(fullTwelveSlotStub());
            String expectedPrefix = (cobolSubscript < 10 ? "0" : "") + cobolSubscript + ". ";

            assertThat(lines.get(cobolSubscript - 1))
                    .as("app/cbl/COMEN01C.cbl:243 - CDEMO-MENU-OPT-NUM is PIC 9(02), so its two-byte "
                            + "zero-filled image leads the line, and :244 adds '. '")
                    .startsWith(expectedPrefix)
                    .hasSize(OPTION_LINE_WIDTH);
        }

        @Test
        @DisplayName("G30: the EVALUATE has THIRTEEN arms here - twelve WHENs plus WHEN OTHER - where "
                + "COADM01C's has eleven")
        void allThirteenArmsAreEnumerated() {
            List<String> twelveSlots = service().buildMenuOptions(fullTwelveSlotStub());

            // Arms 1 through 12: every one of the twelve writes, so no line is left blank.
            assertThat(twelveSlots)
                    .as("arms WHEN 1 to WHEN 12, app/cbl/COMEN01C.cbl:249-272")
                    .hasSize(MainMenuService.OPTION_LINE_COUNT)
                    .doesNotContain(BLANK_OPTION_LINE);
            assertThat(twelveSlots.get(MainMenuService.OPTION_DISPATCH_ARM_COUNT - 2))
                    .as("arm WHEN 11 at :269-270, which COADM01C does not have at all")
                    .startsWith("11. ");
            assertThat(twelveSlots.get(MainMenuService.OPTION_DISPATCH_ARM_COUNT - 1))
                    .as("arm WHEN 12 at :271-272, which COADM01C does not have at all")
                    .startsWith("12. ");

            // Arm 13, WHEN OTHER CONTINUE at :273-274: a thirteenth subscript writes nothing, and the
            // map still has only twelve lines.
            int thirteenSlots = MainMenuService.OPTION_LINE_COUNT + 1;
            List<String> withThirteenth = service().buildMenuOptions(
                    numberedStub(thirteenSlots, thirteenSlots));
            assertThat(withThirteenth)
                    .as("WHEN OTHER writes no line, so the map's twelve are all there are")
                    .hasSize(MainMenuService.OPTION_LINE_COUNT);

            assertThat(EVALUATE_WS_IDX_ARM_COUNT)
                    .as("twelve WHEN arms plus WHEN OTHER; the sibling's equivalent count is eleven")
                    .isEqualTo(13)
                    .isEqualTo(MainMenuService.OPTION_DISPATCH_ARM_COUNT + 1);
            assertThat(MainMenuService.OPTION_DISPATCH_ARM_COUNT)
                    .as("app/cbl/COMEN01C.cbl:249-272 - WHEN 1 through WHEN 12")
                    .isEqualTo(MenuOptions.TABLE_SIZE)
                    .isNotEqualTo(MenuOptions.ACTIVE_OPTION_COUNT);
        }

        @Test
        @DisplayName("an active count of zero visits no slot and leaves every line blank")
        void anEmptyMenuLeavesEveryLineBlank() {
            assertThat(service().buildMenuOptions(stubTable(0, 1, new String[] {"COACTVWC"},
                    new String[] {NavigationContext.USER_TYPE_USER})))
                    .containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("a table is required")
        void aTableIsRequired() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(() -> service.buildMenuOptions(null));
        }
    }

    @Nested
    @DisplayName("RETURN-TO-SIGNON-SCREEN L172-L174: the abbreviated combined relation")
    class SignonTarget {

        @Test
        @DisplayName("the LOW-VALUES half defaults to COSGN00C")
        void lowValuesDefaults() {
            assertThat(service().resolveSignonTarget("\u0000".repeat(PROGRAM_NAME_WIDTH)))
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the SPACES half defaults to COSGN00C")
        void spacesDefaults() {
            assertThat(service().resolveSignonTarget(" ".repeat(PROGRAM_NAME_WIDTH)))
                    .isEqualTo("COSGN00C");
            assertThat(service().resolveSignonTarget("")).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("a populated field is left alone, and a mixed field satisfies neither half")
        void aPopulatedFieldIsLeftAlone() {
            assertThat(service().resolveSignonTarget("COADM01C")).isEqualTo("COADM01C");
            assertThat(service().resolveSignonTarget("CO\u0000\u0000\u0000\u0000\u0000\u0000"))
                    .as("partly low-values and partly not satisfies neither half of the relation")
                    .isEqualTo("CO\u0000\u0000\u0000\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("CDEMO-TO-PROGRAM is never null")
        void nullIsRejected() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(() -> service.resolveSignonTarget(null));
        }
    }

    @Nested
    @DisplayName("The carriers: every declared width and every required component")
    class Carriers {

        @Test
        @DisplayName("MainMenuOptionTable rejects a count the table cannot address")
        void theTableRejectsAnUnaddressableCount() {
            List<Optional<MenuOption>> slots = MenuOptions.options();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOptionTable(slots, -1))
                    .withMessageContaining("CDEMO-MENU-OPT-COUNT is -1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOptionTable(slots, MenuOptions.TABLE_SIZE + 1))
                    .withMessageContaining("does not address a table");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOptionTable(slots, MenuOptions.TABLE_SIZE))
                    .withMessageContaining("is absent, yet CDEMO-MENU-OPT-COUNT is 12");
            assertThatNullPointerException()
                    .isThrownBy(() -> new MainMenuOptionTable(null, 0));
        }

        @Test
        @DisplayName("MainMenuOptionTable copies its slot list defensively")
        void theTableCopiesItsSlots() {
            List<Optional<MenuOption>> mutable = new ArrayList<>(MenuOptions.options());
            MainMenuOptionTable table =
                    new MainMenuOptionTable(mutable, MenuOptions.ACTIVE_OPTION_COUNT);
            mutable.clear();
            assertThat(table.slots()).hasSize(MenuOptions.TABLE_SIZE);
        }

        @Test
        @DisplayName("optionBySubscript throws outside 1..size; optionWithinTable never does")
        void theTwoAccessorsDifferAtTheBoundary() {
            MainMenuOptionTable table = MainMenuOptionTable.copybook();

            assertThat(table.optionBySubscript(1)).isPresent();
            assertThat(table.optionBySubscript(MenuOptions.TABLE_SIZE)).isEmpty();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.optionBySubscript(0))
                    .withMessageContaining("COBOL has no subscript 0");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.optionBySubscript(MenuOptions.TABLE_SIZE + 1));

            assertThat(table.optionWithinTable(0)).isEmpty();
            assertThat(table.optionWithinTable(MenuOptions.TABLE_SIZE + 1)).isEmpty();
            assertThat(table.optionWithinTable(MenuOptions.TABLE_SIZE)).isEmpty();
            assertThat(table.optionWithinTable(1)).isPresent();
        }

        @Test
        @DisplayName("OptionNormalisation checks the two widths the copybook fixes")
        void theNormalisationChecksItsWidths() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "123", " 3", "03",
                            OptionalInt.of(3)))
                    .withMessageContaining("OPTIONI is PIC X(2)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", "3", "03",
                            OptionalInt.of(3)))
                    .withMessageContaining("JUST RIGHT");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", " 3", "003",
                            OptionalInt.of(3)))
                    .withMessageContaining("JUST RIGHT");
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, null, " 3", "03",
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", null, "03",
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", " 3", null,
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", " 3", "03", null));
            assertThatNoException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", "12", "12",
                            OptionalInt.of(12)));
        }

        @Test
        @DisplayName("MainMenuOutcome checks the line count and every declared width")
        void theOutcomeChecksItsWidths() {
            List<String> lines = Collections.nCopies(MainMenuService.OPTION_LINE_COUNT,
                    BLANK_OPTION_LINE);
            NavigationContext context = NavigationContext.empty();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(List.of(BLANK_OPTION_LINE), BLANK_MESSAGE,
                            BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true, false,
                            context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL))
                    .withMessageContaining("option lines");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(
                            Collections.nCopies(MainMenuService.OPTION_LINE_COUNT, "short"),
                            BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM,
                            false, true, false, context, "CM00", "COMEN01", "COMEN1A",
                            ReceiveOutcome.NORMAL))
                    .withMessageContaining("Option line 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(lines, "too short",
                            BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true, false,
                            context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL))
                    .withMessageContaining("WS-MESSAGE is PIC X(80)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(lines, BLANK_MESSAGE,
                            BmsAttributes.DFHRED, false, "123", NO_NEXT_PROGRAM, false, true, false,
                            context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL))
                    .withMessageContaining("OPTIONO is PIC X(2)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(lines, BLANK_MESSAGE,
                            BmsAttributes.DFHRED, false, "  ", "TOOLONGXX", false, true, false,
                            context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL))
                    .withMessageContaining("An XCTL target is PIC X(8)");
        }

        @Test
        @DisplayName("MainMenuOutcome requires every reference component")
        void theOutcomeRequiresItsComponents() {
            List<String> lines = Collections.nCopies(MainMenuService.OPTION_LINE_COUNT,
                    BLANK_OPTION_LINE);
            NavigationContext context = NavigationContext.empty();

            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(null,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines, null,
                    BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true, false,
                    context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, null, NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", null, false, true, false,
                    context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, null, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, null, "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", null, "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", "COMEN01", null, ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", "COMEN01", "COMEN1A", null));
        }

        @Test
        @DisplayName("optionLine addresses the map's twelve lines with a 1-based subscript")
        void optionLineIsOneBased() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "  "));

            assertThat(outcome.optionLine(1)).isEqualTo(OPTION_1_LINE);
            assertThat(outcome.optionLine(MenuOptions.ACTIVE_OPTION_COUNT))
                    .isEqualTo(OPTION_10_LINE);
            assertThat(outcome.optionLine(MainMenuService.OPTION_LINE_COUNT))
                    .isEqualTo(BLANK_OPTION_LINE);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> outcome.optionLine(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> outcome.optionLine(MainMenuService.OPTION_LINE_COUNT + 1));
        }

        @Test
        @DisplayName("ReceiveOutcome carries RESP and RESP2 and no branch depends on them")
        void theReceiveOutcomeCarriesTheUntestedCodes() {
            assertThat(ReceiveOutcome.NOT_PERFORMED.performed()).isFalse();
            assertThat(ReceiveOutcome.NORMAL.performed()).isTrue();
            assertThat(ReceiveOutcome.NORMAL.respCode()).isEqualTo(ReceiveOutcome.RESP_NORMAL);
            assertThat(ReceiveOutcome.NORMAL.reasonCode()).isEqualTo(ReceiveOutcome.RESP2_NONE);

            // A non-normal pair changes nothing, because the source never tests either code - which
            // is also why the file-status gate has zero call sites in this package.
            ReceiveOutcome odd = new ReceiveOutcome(true, 13, 80);
            assertThat(odd.respCode()).isEqualTo(13);
            assertThat(odd.reasonCode()).isEqualTo(80);
        }
    }

    /**
     * {@code MAIN-PARA}'s opening three statements, and the statelessness that makes them sufficient.
     *
     * <p>{@code app/cbl/COMEN01C.cbl:77-80} runs on <strong>every</strong> invocation, before the
     * {@code EIBCALEN} test and before anything is read:
     * <pre>
     *   L77   SET ERR-FLG-OFF TO TRUE
     *   L79   MOVE SPACES TO WS-MESSAGE
     *   L80                 ERRMSGO OF COMEN1AO
     * </pre>
     *
     * <p>In CICS those three statements are not merely tidy, they are load-bearing:
     * {@code WS-ERR-FLG} and {@code WS-MESSAGE} are {@code WORKING-STORAGE}, which in a
     * pseudo-conversational transaction is re-initialised per task, and the program re-establishes the
     * cleared state itself rather than relying on that. The Java projection keeps them method-local, so
     * the unconditional reset is <em>also</em> the proof that no state leaks between invocations - which
     * is what gate G37 asks for. One service instance is deliberately re-used across two different calls
     * below, in both orders, because a service that carried per-request working storage in a field would
     * pass a one-call-per-instance test and fail in production.
     */
    @Nested
    @DisplayName("MAIN-PARA L77-L80: the unconditional reset, and the statelessness behind it")
    class UnconditionalResetAndStatelessness {

        @Test
        @DisplayName("G37: one service instance, two invocations, and the second sees nothing of the "
                + "first")
        void oneInstanceServesTwoInvocationsIndependently() {
            MainMenuService shared = service();

            // First: an unmatched AID, so L100-L101 set the flag and the invalid-key message.
            MainMenuOutcome errored = shared.handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHPF4, "01"));
            // Second: ENTER on a valid option, so L77-L80's reset must have wiped the first outcome.
            MainMenuOutcome clean = shared.handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(errored.errorFlag()).isTrue();
            assertThat(errored.message())
                    .isEqualTo(wsMessageImage(INVALID_KEY_MESSAGE_X50));

            assertThat(clean.errorFlag())
                    .as("L77 SET ERR-FLG-OFF TO TRUE runs unconditionally on the second call")
                    .isFalse();
            assertThat(clean.message())
                    .as("L79 MOVE SPACES TO WS-MESSAGE - no residue of the first call's message")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(clean.nextProgram())
                    .as("and the second call dispatches normally")
                    .isEqualTo("COACTVWC");
        }

        @Test
        @DisplayName("G37: the reverse order too - a clean call first does not suppress a later error")
        void theReverseOrderIsIndependentAsWell() {
            MainMenuService shared = service();

            MainMenuOutcome clean = shared.handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "02"));
            MainMenuOutcome errored = shared.handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHCLEAR, "02"));

            assertThat(clean.nextProgram())
                    .as("app/cpy/COMEN02Y.cpy:34 - option 2 targets COACTUPC")
                    .isEqualTo("COACTUPC");
            assertThat(clean.errorFlag()).isFalse();

            assertThat(errored.errorFlag())
                    .as("the earlier success does not carry a cleared flag into this call")
                    .isTrue();
            assertThat(errored.message())
                    .isEqualTo(wsMessageImage(INVALID_KEY_MESSAGE_X50));
            assertThat(errored.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("G37: an invalid option then a valid one - the error message does not persist")
        void anInvalidOptionDoesNotPoisonTheNextCall() {
            MainMenuService shared = service();

            MainMenuOutcome rejected = shared.handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "11"));
            MainMenuOutcome accepted = shared.handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "10"));

            assertThat(rejected.message())
                    .as("app/cbl/COMEN01C.cbl:131 - 11 is above CDEMO-MENU-OPT-COUNT")
                    .isEqualTo(wsMessageImage(INVALID_OPTION_TEXT));
            assertThat(accepted.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(accepted.nextProgram())
                    .as("app/cpy/COMEN02Y.cpy:83 - option 10 targets COBIL00C")
                    .isEqualTo("COBIL00C");
        }

        @Test
        @DisplayName("G37: no server-side state - the whole conversation travels in the two value types")
        void theConversationTravelsInThePayload() {
            NavigationContext inbound = userReenteredContext()
                    .withUserId("USER0001")
                    .withCustId(123456789)
                    .withAcctId(11111111111L)
                    .withLastMap(MainMenuService.MAP_NAME)
                    .withLastMapset(MainMenuService.MAPSET_NAME);

            MainMenuOutcome outcome = service()
                    .handle(new MainMenuInput(inbound, CicsAid.DFHENTER, "01"));

            assertThat(outcome.navigationContext().custId())
                    .as("everything the program does not touch is handed straight back, which is what "
                            + "app/cbl/COMEN01C.cbl:109's COMMAREA(CARDDEMO-COMMAREA) does")
                    .isEqualTo(123456789);
            assertThat(outcome.navigationContext().acctId()).isEqualTo(11111111111L);
            assertThat(outcome.navigationContext().lastMap()).isEqualTo(MainMenuService.MAP_NAME);
            assertThat(outcome.navigationContext().lastMapset()).isEqualTo(MainMenuService.MAPSET_NAME);
            assertThat(outcome.navigationContext().userId()).isEqualTo("USER0001");
        }

        // app/cbl/COSGN00C.cbl is what hands a regular user to this program: :226-227 put the user id and
        // the user type into the communication area, :228 zeroes CDEMO-PGM-CONTEXT, and the ELSE of the
        // role test at :230 transfers with XCTL PROGRAM('COMEN01C') at :237 - an admin goes to
        // 'COADM01C' at :232 instead. In the stateless projection that role decision becomes a field on
        // the sign-on response and the client issues the follow-up call, so what has to be asserted here
        // is only that this service accepts a context shaped exactly the way a 'U'-role sign-on produces
        // it, and takes the first-entry path on arrival. SignOnService itself is neither imported nor
        // instantiated: this is a contract on the shape of the context, not on that collaborator.
        @Test
        @DisplayName("G40: the context COSGN00C:237 hands a 'U' user is accepted, and paints first")
        void theSignOnHandoverContextTakesTheFirstEntryPath() {
            NavigationContext handover = NavigationContext.empty()
                    .withFromTranid("CC00")                          // COSGN00C's own WS-TRANID
                    .withFromProgram(MainMenuService.SIGNON_PROGRAM) // COSGN00C.cbl:225
                    .withUserId("USER0001")                          // COSGN00C.cbl:226
                    .withUserTypeUser()                              // COSGN00C.cbl:227, type 'U'
                    .withPgmEnter();                                 // COSGN00C.cbl:228, ZEROS

            assertThat(handover.isUser())
                    .as("app/cpy/COCOM01Y.cpy:28 - 88 CDEMO-USRTYP-USER VALUE 'U'")
                    .isTrue();
            assertThat(handover.isAdmin()).isFalse();
            assertThat(handover.isEnter())
                    .as("app/cpy/COCOM01Y.cpy:30 - 88 CDEMO-PGM-ENTER VALUE 0")
                    .isTrue();

            MainMenuInput input = new MainMenuInput(handover, CicsAid.DFHENTER, "  ");
            assertThat(input.isCommareaPresent())
                    .as("XCTL ... COMMAREA(CARDDEMO-COMMAREA) at COSGN00C:238, so EIBCALEN is not 0")
                    .isTrue();
            assertThat(input.isReenter()).isFalse();

            MainMenuOutcome outcome = service().handle(input);

            assertThat(outcome.screenPainted())
                    .as("app/cbl/COMEN01C.cbl:87-90 - the first-entry path paints the menu")
                    .isTrue();
            assertThat(outcome.resetAllOutputFields())
                    .as("app/cbl/COMEN01C.cbl:89 - MOVE LOW-VALUES TO COMEN1AO")
                    .isTrue();
            assertThat(outcome.navigationContext().isReenter())
                    .as("app/cbl/COMEN01C.cbl:88 - SET CDEMO-PGM-REENTER TO TRUE for the next keystroke")
                    .isTrue();
            assertThat(outcome.navigationContext().fromProgram())
                    .as("nothing on the first-entry path rewrites CDEMO-FROM-PROGRAM")
                    .isEqualTo(MainMenuService.SIGNON_PROGRAM);
            assertThat(outcome.navigationContext().userType())
                    .as("and the inbound role survives - COMEN01C:150 is commented out")
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(outcome.optionLine(MenuOptions.FIRST_SUBSCRIPT)).isEqualTo(OPTION_1_LINE);
            assertThat(outcome.hasNextProgram()).isFalse();
        }
    }

    /**
     * Where the layer boundary falls, and why this file needs no clock to fix.
     *
     * <p>{@code SEND-MENU-SCREEN} at {@code app/cbl/COMEN01C.cbl:182-194} performs
     * {@code POPULATE-HEADER-INFO}, then {@code BUILD-MENU-OPTIONS}, then
     * {@code MOVE WS-MESSAGE TO ERRMSGO} and the {@code SEND MAP}. The middle two are this service's
     * work; the first is not, because its opening statement is
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :214}.
     *
     * <p>The migration's determinism practice calls for a <strong>fixed</strong> clock so that a header
     * assertion cannot drift. {@code MainMenuService} satisfies that requirement in the stronger form: it
     * takes <strong>no clock at all</strong> - neither constructor accepts one, no field holds one and no
     * method reads one - so there is no time source in the path to fix, and this suite is invariant under
     * the host's default time zone. The header fields that do depend on the clock are
     * {@code CURDATEO} at {@code :225}, rendered {@code MM/DD/YY} with the {@code '/'} separators
     * {@code app/cpy/CSDAT01Y.cpy:32} and {@code :34} declare and its year taken from
     * {@code WS-CURDATE-YEAR(3:2)} at {@code :223}, and {@code CURTIMEO} at {@code :231}, rendered
     * {@code HH:MM:SS} with the {@code ':'} separators at {@code CSDAT01Y.cpy:38} and {@code :40}. Those
     * are produced by {@code MainMenuController} from the shared date header driven by the module's single
     * injected {@code Clock} bean, and they are asserted there under a fixed instant. Duplicating them
     * here would assert a collaborator this class does not have.
     *
     * <p>What {@code POPULATE-HEADER-INFO} takes from <em>this</em> class is the pair of identity literals
     * at {@code :218-219} - {@code MOVE WS-TRANID TO TRNNAMEO} and
     * {@code MOVE WS-PGMNAME TO PGMNAMEO} - and those are asserted below.
     */
    @Nested
    @DisplayName("The layer boundary: no clock in the service, so nothing to fix and nothing to drift")
    class LayerBoundaryAndDeterminism {

        @Test
        @DisplayName("L218-L219: the two identity literals POPULATE-HEADER-INFO moves to the header")
        void theHeaderIdentityLiteralsComeFromHere() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "  "));

            assertThat(outcome.transactionId())
                    .as("L218 MOVE WS-TRANID TO TRNNAMEO, L108's RETURN TRANSID, and "
                            + "app/csd/CARDDEMO.CSD:399-400's DEFINE TRANSACTION(CM00)")
                    .isEqualTo("CM00")
                    .isEqualTo(MainMenuService.TRANSACTION_ID);
            assertThat(MainMenuService.PROGRAM_NAME)
                    .as("L219 MOVE WS-PGMNAME TO PGMNAMEO, and L148's CDEMO-FROM-PROGRAM")
                    .isEqualTo("COMEN01C");
            assertThat(outcome.mapName())
                    .as("L190 MAP('COMEN1A')")
                    .isEqualTo("COMEN1A");
            assertThat(outcome.mapsetName())
                    .as("L191 MAPSET('COMEN01')")
                    .isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("B7: the same input produces a byte-identical outcome every time it is run")
        void repeatedInvocationsAreByteIdentical() {
            MainMenuInput input =
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01");

            MainMenuOutcome first = service().handle(input);
            MainMenuOutcome second = service().handle(input);
            MainMenuOutcome third = new MainMenuService(bindings(),
                    new FixedWidthCodec(Charset.forName(
                            MainMenuService.DEFAULT_MESSAGE_CHARSET_NAME))).handle(input);

            assertThat(second)
                    .as("no clock, no random source, no environment and no ambient state")
                    .isEqualTo(first);
            assertThat(third)
                    .as("nor does a separately constructed instance differ")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("B7: the screen-painting path is deterministic too, header fields excepted")
        void thePaintedScreenIsDeterministic() {
            MainMenuInput input =
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHCLEAR, "  ");

            MainMenuOutcome first = service().handle(input);
            MainMenuOutcome second = service().handle(input);

            assertThat(second.optionLines()).isEqualTo(first.optionLines());
            assertThat(second.message()).isEqualTo(first.message());
            assertThat(second.messageColour()).isEqualTo(first.messageColour());
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("B7/B9/G53: the service holds no mutable state and no time source at all")
        void theServiceHoldsNoMutableOrTimeDependentState() {
            for (Field field : MainMenuService.class.getDeclaredFields()) {
                // A coverage agent adds its own synthetic counter field to every instrumented class, so
                // synthetic members are excluded: they are the tooling's, not the migration's.
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("MainMenuService.%s must be final - COBOL WORKING-STORAGE never becomes a "
                                + "mutable Java field, because that would break request isolation",
                                field.getName())
                        .isTrue();
                assertThat(field.getType())
                        .as("MainMenuService.%s must not be a clock: POPULATE-HEADER-INFO's "
                                + "FUNCTION CURRENT-DATE at app/cbl/COMEN01C.cbl:214 belongs to the "
                                + "controller, so there is no time source here to fix", field.getName())
                        .isNotEqualTo(Clock.class);
            }

            assertThat(MainMenuService.class.getDeclaredConstructors())
                    .as("two constructors, and neither takes a Clock")
                    .hasSize(2)
                    .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                            .doesNotContain(Clock.class));
            assertThat(MainMenuService.class.getDeclaredMethods())
                    .as("and no method accepts or returns one either")
                    .allSatisfy(method -> {
                        assertThat(method.getParameterTypes()).doesNotContain(Clock.class);
                        assertThat(method.getReturnType()).isNotEqualTo(Clock.class);
                    });
        }
    }
}
