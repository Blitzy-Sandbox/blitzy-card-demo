package com.vsergeychik.carddemo.parity;

// Every internal import below is either a declared dependency of this file or a type named by one of
// their own published signatures, which is the same dependency arriving by its contract:
//   * UserUpdateController.handle(UserUpdateRequest, int, byte, Cu02Info) is the seam this class calls,
//     so user.dto.UserUpdateRequest and its nested Cu02Info come with it - the method cannot be called
//     without naming them.
//   * SecUserRepository.ReadResult.of(String, CicsResponse) and WriteResult.of(String, CicsResponse)
//     are how a forced RESP is reported, so common.CicsResponse comes with SecUserRepository. Without
//     it the RESP a forced I/O failure carries could not reach DISPLAY 'RESP:' WS-RESP-CD at line 347.
// No import is a wildcard, so every copybook-to-type correspondence stays auditable by name, and no
// symbol is imported that this file does not use.

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.user.SecUserRepository;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserUpdateController;
import com.vsergeychik.carddemo.user.UserUpdateController.ProgramState;
import com.vsergeychik.carddemo.user.UserUpdateController.Send;
import com.vsergeychik.carddemo.user.dto.UserUpdateRequest;
import com.vsergeychik.carddemo.user.dto.UserUpdateRequest.Cu02Info;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The behavioural-parity gate for {@code app/cbl/COUSR02C.cbl} - "Update a user in USRSEC file",
 * CICS transaction {@code CU02}, 414 lines, six {@code EXEC CICS} commands - against its translation
 * {@link UserUpdateController}.
 *
 * <p>Twenty declarative cases, {@code case01} through {@code case20}, live in
 * {@code src/test/resources/parity/COUSR02C/} and are loaded by
 * {@link ParityHarness#casesOf(String)}, which refuses any count other than twenty and refuses a
 * stray file in the directory. Each case is judged field by field by {@link FieldDiffer} and
 * <strong>must produce a diff count of zero</strong>. The gate is per module, not per build:
 * nineteen clean cases and one difference means this module is incomplete, not almost done.
 *
 * <h2>Baseline provenance - statically derived, never captured</h2>
 *
 * <p>The expected values in those twenty files are <strong>statically derived</strong>. They were
 * produced by structured reading of each {@code COUSR02C} paragraph, cross-checked against the
 * copybook byte layouts ({@code app/cpy/CSUSR01Y.cpy}, {@code app/cpy/COCOM01Y.cpy}), the symbolic
 * map ({@code app/cpy-bms/COUSR02.CPY}), the {@code DFHMDF} field definitions
 * ({@code app/bms/COUSR02.bms}), the transaction definition ({@code app/csd/CARDDEMO.CSD}) and the
 * in-stream seed data ({@code app/jcl/DUSRSECJ.jcl}).
 *
 * <p>They are <strong>not</strong> captured from, recorded against, or replayed from any execution
 * of the legacy COBOL, and nothing here should be read as though they were. Executing the legacy
 * programs is empirically impossible in this environment - among the verified blockers there is no
 * z/OS or CICS runtime, the available COBOL compiler reports its indexed file handler as disabled,
 * no Language Environment {@code CEE*} services exist, and the IBM-supplied copybooks
 * {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are absent from the repository. Only the
 * <em>provenance</em> of the expected values is substituted: twenty cases per program, field-for-field
 * diffing and the diff-count-equals-zero gate are all preserved. Because that nonetheless modifies a
 * stated success criterion it is escalated for explicit user confirmation rather than absorbed
 * silently. A statically derived expectation can encode a misreading of the COBOL where a captured
 * one could not, which is why every width here comes mechanically from a copybook and every case is
 * seeded from the genuine {@code DUSRSECJ} rows.
 *
 * <h2>The unit under test is a plain Java object - no HTTP anywhere</h2>
 *
 * <p>{@link ParityCase#unitKind()} is {@link UnitKind#CONTROLLER_POJO} on all twenty cases. The
 * {@code user} package has a dedicated service only for {@code COSGN00C}, so {@code COUSR02C}'s
 * decision logic lives in {@link UserUpdateController#handle} - a seam that takes the terminal input
 * area, {@code EIBCALEN}, the {@code EIBAID} byte and this program's commarea extension, and returns
 * the whole terminal state. This class constructs the controller with its own constructor, injects a
 * seeded {@link SecUserRepository} double and the case's pinned {@link java.time.Clock}, and calls
 * that method directly.
 *
 * <p>There is deliberately <strong>no {@code MockMvc}, no {@code TestRestTemplate}, no
 * {@code WebTestClient}, no {@code JobLauncher} and no Spring context</strong>. A parity assertion
 * has to fail because the translation disagrees with the COBOL, not because a JSON converter, a
 * validator or a servlet filter got in the way; those belong to {@code UserUpdateControllerTest},
 * which tests the HTTP adapter. Keeping the layers out also makes the branch surface reachable, which
 * is what the branch-coverage bar depends on.
 *
 * <h2>Three properties of this program that are easy to translate away</h2>
 *
 * <ol>
 *   <li><strong>{@code PF3} saves before it exits.</strong> {@code app/cbl/COUSR02C.cbl:111-119}
 *       performs {@code UPDATE-USER-INFO} <em>first</em> and only then transfers, so a pending edit
 *       is committed on the way out. {@code PF3} is not a cancel key in this program - {@code PF12}
 *       at {@code :124-126} is, and it transfers without saving. This is source behaviour, not a
 *       defect to correct: {@code case17} pins the saved record and the transfer together and fails
 *       if {@code PF3} is ever reduced to a plain exit.</li>
 *   <li><strong>The four change tests are independent.</strong> {@code :219-234} is four separate
 *       {@code IF ... END-IF} blocks over {@code FNAME}, {@code LNAME}, {@code PASSWD} and
 *       {@code USRTYPE}, each setting {@code USR-MODIFIED-YES} on its own. They do not chain and they
 *       do not short-circuit. {@code case10} drives one alone, {@code case11} drives all four in one
 *       invocation and {@code case12} drives none, which is what makes a short-circuit visible.</li>
 *   <li><strong>No optimistic-concurrency check belongs here.</strong> {@code COUSR02C} has no
 *       {@code 9300-CHECK-CHANGE-IN-REC} paragraph - that paragraph exists only in {@code COACTUPC}
 *       and {@code COCRDUPC}, so the re-read-and-compare gate is scoped to the {@code account} and
 *       {@code card} packages. The whole contract here is {@code EXEC CICS READ ... UPDATE} at
 *       {@code :322} followed by {@code EXEC CICS REWRITE} at {@code :360}. Adding a version column
 *       or a compare-before-write would be a schema change and a new failure mode the legacy screen
 *       cannot produce.</li>
 * </ol>
 *
 * <h2>The credential span stays plaintext</h2>
 *
 * <p>{@code app/cpy/CSUSR01Y.cpy} declares {@code SEC-USR-PWD PIC X(08)} and {@code COUSR02C}
 * compares it byte for byte at {@code :227}, stores it verbatim at {@code :228} and echoes the stored
 * value back to the screen at {@code :169}. Hashing it would change observable behaviour and would
 * require a security framework that is out of scope, so the plaintext comparison is preserved
 * exactly - an inherited property of the legacy design and an explicit non-goal of this migration.
 * Carrying it and printing it are different things: {@link ParityCase.Redaction} is what decides
 * that, and every rendering in this harness goes through it.
 *
 * <h2>This screen is not the other two user screens</h2>
 *
 * <p>{@code COUSR01} declares twelve fields whose identifier is {@code USERID}; this mapset,
 * {@code COUSR02}, declares twelve whose identifier is <strong>{@code USRIDIN}</strong>; and
 * {@code COUSR03} declares eleven, because it deliberately omits the password field. The field list
 * is never copied between them.
 *
 * @see UserUpdateController#handle(UserUpdateRequest, int, byte, Cu02Info)
 * @see ParityHarness#judge(ParityCase, UnitKind, ParityHarness.ParityUnit)
 */
final class COUSR02CParityTest {

    /**
     * The program these cases pin, which is also the {@code parity/<PROGRAM>/} directory segment
     * holding them. The class name stem and this value agree by construction.
     */
    private static final String PROGRAM = "COUSR02C";

    /**
     * The only dataset {@code COUSR02C} touches, taken from the repository that owns it rather than
     * written as a literal here.
     *
     * <p>{@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at {@code app/cbl/COUSR02C.cbl:39} is the
     * CICS file name; {@code USRSEC} is the binding key {@code application.yml} resolves to
     * {@code app/csd/CARDDEMO.CSD}'s {@code DSNAME}. No fully-qualified mainframe dataset name appears
     * anywhere in this file, which is the point of addressing datasets by binding key.
     */
    private static final String USRSEC = SecUserRepository.CICS_FILE_NAME;

    /**
     * {@code DFHAID} mnemonic to {@code EIBAID} byte, inverted from the constants class that owns
     * those bytes.
     *
     * <p>A case declares its {@code EIBAID} as the mnemonic {@code app/cbl/COUSR02C.cbl:108-131}
     * evaluates - {@code DFHENTER}, {@code DFHPF3}, {@code DFHPF4}, {@code DFHPF5}, {@code DFHPF12}
     * and, for the {@code WHEN OTHER} arm, a key the program names in no arm at all. The byte values
     * are IBM's and are reproduced in {@link CicsAid} from IBM CICS documentation, because
     * {@code DFHAID} is not in this repository. Inverting the published map rather than restating
     * the pairs here means the two can never disagree.
     *
     * <p>Unmodifiable, so this constant is shared immutable state rather than static mutable state.
     */
    private static final Map<String, Byte> AID_BY_MNEMONIC = invertAidMnemonics();

    /**
     * The {@code EIBAID} byte used when a case declares no AID.
     *
     * <p>Exactly one case does: the {@code EIBCALEN = 0} cold start at
     * {@code app/cbl/COUSR02C.cbl:90-92}, which returns before {@code EIBAID} is ever evaluated.
     * {@code DFHNULL} is chosen rather than {@code DFHENTER} so that an absent AID can never silently
     * look like a pressed {@code ENTER} on some future case: {@code DFHNULL} matches no {@code WHEN}
     * clause this program declares and would land on {@code WHEN OTHER} if it were ever reached.
     */
    private static final byte NO_AID_DECLARED = CicsAid.DFHNULL;

    /** The {@code RESP2} value a forced outcome reports when a case states none. */
    private static final int DEFAULT_RESP2 = FileStatus.NO_REASON_CODE;

    /**
     * The twenty cases, as a JUnit {@code @MethodSource}.
     *
     * <p>{@link ParityHarness#casesOf(String)} is what makes the count loud rather than implicit: it
     * refuses a directory holding anything other than {@code case01.json} through
     * {@code case20.json}, and refuses a set in which any of the twenty is absent or unreadable. A
     * short set is not a smaller gate - "the diff count is zero across all twenty cases" would be
     * satisfied vacuously by a set of four - so the count is checked before a single case runs.
     *
     * @return all twenty cases in ascending case order
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * The gate: every one of the twenty cases must produce a diff count of zero.
     *
     * <p>One assertion, deliberately. {@link DiffResult#render()} already names every difference with
     * its dataset, row, field, expected value and observed value, and explains why that particular
     * difference matters, so a failure reads as "3 difference(s) on case07" with all three spelled
     * out. Re-asserting the individual fields here would replace that report with whichever single
     * field happened to be checked first.
     *
     * @param parityCase one of the twenty cases, supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    @DisplayName("COUSR02C: every parity case diffs to zero against UserUpdateController")
    void reproducesCobolBehaviourFieldForField(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, UnitKind.CONTROLLER_POJO, COUSR02CParityTest::driveUserUpdate);

        assertThat(result.count())
                .as("Parity case %s/%s must produce a diff count of zero. %s is not complete until "
                        + "every one of its twenty cases is clean.%n%s",
                        parityCase.program(), parityCase.caseId(), PROGRAM, result.render())
                .isZero();
        assertThat(result.isClean())
                .as("DiffResult.count() and DiffResult.isClean() must agree for %s/%s",
                        parityCase.program(), parityCase.caseId())
                .isTrue();
    }

    // =================================================================================================
    // Sentinels over the case SET. The parameterised test above judges one case at a time and can say
    // nothing about the shape of the set as a whole, so the properties the gate depends on - the count,
    // the identifiers, the seed, the key coverage - are asserted once, here.
    // =================================================================================================

    /**
     * The set is exactly {@code case01} through {@code case20}, each naming this program.
     *
     * <p>{@link #cases()} already refuses a wrong count, but it cannot state <em>which</em>
     * identifiers it expected in a way a reader can check at a glance, and it cannot catch a case file
     * whose {@code program} member names a different program while sitting in this directory - which
     * would silently judge {@code COUSR02C}'s translation against another program's expectations.
     */
    @Test
    @DisplayName("the case set is exactly case01..case20 and every case names COUSR02C")
    void theCaseSetIsExactlyTwentyNumberedCasesForThisProgram() {
        List<ParityCase> loaded = cases();

        assertThat(loaded)
                .as("the gate is stated as twenty cases per program; a shorter set is a gate that "
                        + "passes without asking the questions")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);
        assertThat(loaded).extracting(ParityCase::caseId)
                .as("case identifiers must be case01..case20 with no gap and no duplicate")
                .containsExactlyElementsOf(expectedCaseIds());
        assertThat(loaded).extracting(ParityCase::program)
                .as("a case sitting in parity/%s/ must pin %s and nothing else", PROGRAM, PROGRAM)
                .containsOnly(PROGRAM);
        assertThat(loaded).allSatisfy(parityCase -> assertThat(parityCase.description())
                .as("every case must name the COBOL branch it pins, so parity coverage is auditable "
                        + "without re-reading the program")
                .isNotBlank());
    }

    /**
     * Every case declares the unit kind, the seed and the width normalisation this program needs.
     *
     * <p>The seed is the part that is easy to get wrong. There is no {@code usrsec} fixture under
     * {@code app/data/ASCII}, so the rows come from {@code app/jcl/DUSRSECJ.jcl}'s ten in-stream
     * records, and those records are <strong>57 characters</strong> where
     * {@code app/cpy/CSUSR01Y.cpy} declares eighty - the JCL simply stops after
     * {@code SEC-USR-TYPE} and leaves {@code SEC-USR-FILLER PIC X(23)} implicit.
     * {@link Normalisation#USRSEC_FILLER_PAD_57_TO_80} is the declaration that the pad is deliberate,
     * and the pad is applied once, on the seeding side, by the harness. A case that seeded 57-character
     * rows without declaring it would either fail on width or - far worse - quietly compare
     * short rows.
     */
    @Test
    @DisplayName("every case is a CONTROLLER_POJO seeded from USRSEC with the 57-to-80 filler pad")
    void everyCaseDeclaresTheControllerSeedAndTheFillerPad() {
        for (ParityCase parityCase : cases()) {
            String where = PROGRAM + '/' + parityCase.caseId();

            assertThat(parityCase.unitKind())
                    .as("%s: COUSR02C is a CICS online program, so its unit is the controller invoked "
                            + "as a plain object - never a batch job and never through HTTP", where)
                    .isEqualTo(UnitKind.CONTROLLER_POJO);
            assertThat(parityCase.screenRequest())
                    .as("%s: a CONTROLLER_POJO case is invoked through its typed screen request", where)
                    .isNotNull();
            assertThat(parityCase.expectedResponse())
                    .as("%s: an online program's observable behaviour is mostly its response, so the "
                            + "response is a first-class expectation", where)
                    .isNotNull();
            assertThat(parityCase.jobParameters())
                    .as("%s: COUSR02C is driven by a terminal, not by JCL, so it takes no job "
                            + "parameter", where)
                    .isEmpty();
            assertThat(parityCase.inputs())
                    .as("%s: USRSEC is the one dataset COUSR02C reads or writes "
                            + "(app/cbl/COUSR02C.cbl:39)", where)
                    .containsOnlyKeys(USRSEC);
            assertThat(parityCase.normalisations())
                    .as("%s: the ten DUSRSECJ rows are 57 characters and CSUSR01Y declares 80, so the "
                            + "SEC-USR-FILLER pad must be declared rather than assumed", where)
                    .containsExactly(new DatasetNormalisation(USRSEC,
                            Normalisation.USRSEC_FILLER_PAD_57_TO_80));
            assertThat(parityCase.expectedReturnCode())
                    .as("%s: a CICS online program sets no RETURN-CODE; zero is the absence of an "
                            + "abend, and all nine CALL 'CEE3ABD' sites are in batch programs", where)
                    .isZero();
        }
    }

    /**
     * The set drives every arm of the ordered {@code EVALUATE EIBAID} at
     * {@code app/cbl/COUSR02C.cbl:108-131}.
     *
     * <p>{@code COUSR02C} is one of the twelve online programs that test {@code EIBAID} inline rather
     * than copying {@code CSSTRPFY}, so the five named keys and the default are the whole of its key
     * dispatch. The AIDs are classified through {@link PfKeyResolver} - the same resolution the
     * translation performs - rather than by comparing mnemonic strings, so this asserts the keys were
     * actually reachable and not merely spelled.
     *
     * <p>The {@code WHEN OTHER} arm is asserted as "at least one case declares an AID that resolves to
     * no arm of this program", which is what {@code :127-130} actually responds to.
     */
    @Test
    @DisplayName("the case set drives ENTER, PF3, PF4, PF5, PF12 and an AID with no WHEN clause")
    void theCaseSetDrivesEveryArmOfTheKeyDispatch() {
        Set<String> resolved = new TreeSet<>();
        boolean drivesUnnamedKey = false;

        for (ParityCase parityCase : cases()) {
            byte eibAid = attentionIdentifierOf(parityCase.screenRequest().aid(),
                    PROGRAM + '/' + parityCase.caseId());
            Optional<AidKey> key = PfKeyResolver.resolve(eibAid);
            key.ifPresent(aidKey -> resolved.add(aidKey.name()));
            if (!PfKeyResolver.isEnter(eibAid) && !PfKeyResolver.isPf3(eibAid)
                    && !PfKeyResolver.isPf4(eibAid) && !PfKeyResolver.isPf5(eibAid)
                    && !PfKeyResolver.isPf12(eibAid)) {
                drivesUnnamedKey = true;
            }
        }

        assertThat(resolved)
                .as("every named arm of the EVALUATE EIBAID at app/cbl/COUSR02C.cbl:108-131 must be "
                        + "driven by at least one case: ENTER fetches, PF3 saves then exits, PF4 "
                        + "clears, PF5 saves and PF12 cancels")
                .contains(AidKey.ENTER.name(), AidKey.PFK03.name(), AidKey.PFK04.name(),
                        AidKey.PFK05.name(), AidKey.PFK12.name());
        assertThat(drivesUnnamedKey)
                .as("the WHEN OTHER arm at app/cbl/COUSR02C.cbl:127-130 must be driven by a key this "
                        + "program names in none of its five arms")
                .isTrue();
    }

    /**
     * The set drives every extended-colour byte {@code COUSR02C} moves into {@code ERRMSGC}.
     *
     * <p>Three, and only three: {@code DFHNEUTR} on the successful read at {@code :338},
     * {@code DFHRED} on "Please modify to update ..." at {@code :241} and {@code DFHGREEN} on the
     * successful rewrite at {@code :371}. The mnemonics are resolved from {@link BmsAttributes} - the
     * class that reproduces {@code DFHBMSCA} from IBM CICS documentation, because that copybook is not
     * in this repository - rather than typed as literals, so the expectation cannot drift from the
     * constants the translation actually moves.
     *
     * <p>{@code COUSR02C} does not copy {@code CSSETATY}, so there is no {@code '*'}-and-highlight
     * field attribute to assert here; this program's entire attribute surface is that one colour byte.
     */
    @Test
    @DisplayName("the case set drives DFHNEUTR, DFHRED and DFHGREEN on ERRMSGC")
    void theCaseSetDrivesEveryMessageColourThisProgramSets() {
        Set<String> colours = new TreeSet<>();
        for (ParityCase parityCase : cases()) {
            for (ParityCase.ScreenSend send : parityCase.expectedResponse().sends()) {
                String colour = send.attributes().get(UserUpdateController.ERR_MSG_COLOUR_ITEM);
                if (colour != null) {
                    colours.add(colour);
                }
            }
        }

        assertThat(colours)
                .as("COUSR02C moves exactly three colours into ERRMSGC OF COUSR2AO - DFHNEUTR at "
                        + "line 338, DFHRED at line 241 and DFHGREEN at line 371 - and each must be "
                        + "driven by at least one case")
                .contains(BmsAttributes.colourMnemonic(BmsAttributes.DFHNEUTR),
                        BmsAttributes.colourMnemonic(BmsAttributes.DFHRED),
                        BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN));
    }

    /**
     * The set drives both terminations, both entry paths and a rewrite that actually lands.
     *
     * <p>Statelessness is the property being protected here (rule R6). Conversation state travels in
     * the payload, so {@link Termination#XCTL} with a named {@code nextProgram} is how
     * {@code EXEC CICS XCTL} is observable at all, and {@link Termination#RETURN_TRANSID} is the
     * pseudo-conversational return at {@code :135-138}. A translation that held the conversation in a
     * server-side session could satisfy neither.
     */
    @Test
    @DisplayName("the case set drives XCTL and RETURN TRANSID, both entry paths and a real rewrite")
    void theCaseSetDrivesBothTerminationsAndBothEntryPaths() {
        Set<Termination> terminations = new TreeSet<>();
        boolean drivesColdStart = false;
        boolean drivesReenter = false;
        boolean drivesRewrite = false;

        for (ParityCase parityCase : cases()) {
            terminations.add(parityCase.expectedResponse().termination());
            int eibcalen = parityCase.screenRequest().eibcalen();
            if (eibcalen == 0) {
                drivesColdStart = true;
            } else {
                drivesReenter = true;
            }
            if (!parityCase.expectedWrites().isEmpty()) {
                drivesRewrite = true;
            }
        }

        assertThat(terminations)
                .as("both terminations are behaviour: XCTL at :259 hands the conversation on, and "
                        + "RETURN TRANSID at :135-138 keeps it")
                .containsExactlyInAnyOrder(Termination.XCTL, Termination.RETURN_TRANSID);
        assertThat(drivesColdStart)
                .as("the EIBCALEN = 0 guard at :90-92 is a whole path and needs a case of its own")
                .isTrue();
        assertThat(drivesReenter)
                .as("the paths that matter all arrive with a commarea")
                .isTrue();
        assertThat(drivesRewrite)
                .as("at least one case must rewrite a record; a set in which nothing is ever saved "
                        + "would never assert the 80-byte image REWRITE actually stores")
                .isTrue();
    }

    /**
     * The case identifiers the set must contain, produced by the harness so this test cannot disagree
     * with the loader about what "twenty cases" means.
     *
     * @return {@code case01} through {@code case20} in ascending order
     */
    private static List<String> expectedCaseIds() {
        List<String> ids = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            ids.add(ParityHarness.caseId(ordinal));
        }
        return ids;
    }

    // =================================================================================================
    // THE ADAPTER. Everything below constructs the unit under test, calls it, and records what it
    // observably produced. It is the only part of this file that knows anything about COUSR02C's
    // translation, which is the division of labour the harness is built around: the harness seeds and
    // judges, and this class - the only one permitted to import the user package - constructs and calls.
    //
    // Every value here is per-invocation. Nothing is cached in a field or a static, because two cases
    // that shared a seeded store or a stubbed repository would leak one case's writes into the other's
    // final state, and the pair would then pass or fail depending on execution order.
    // =================================================================================================

    /**
     * Constructs {@link UserUpdateController}, calls {@code handle} and records the run.
     *
     * <p>The five steps, in order:
     * <ol>
     *   <li>Seed an in-memory {@code USRSEC} from the case's rows, keyed byte-exactly on
     *       {@code SEC-USR-ID} - which is what {@code KEYS(8,0)} in {@code app/jcl/DUSRSECJ.jcl}
     *       means.</li>
     *   <li>Stub the repository over that seed, honouring any outcome the case forces.</li>
     *   <li>Rebuild the terminal input area, the communication area and its extension from the case,
     *       and resolve {@code EIBAID}.</li>
     *   <li>Call {@link UserUpdateController#handle} - the seam, with no HTTP in the path.</li>
     *   <li>Record the writes, the final state, the response and any {@code DISPLAY} line.</li>
     * </ol>
     *
     * <p>The recorder is populated and then built, rather than a {@link UnitOutcome} being assembled
     * separately, so that anything observed before a hypothetical abend would survive the exception.
     * {@code COUSR02C} has no {@code CALL 'CEE3ABD'} - all nine abend sites are in batch programs - but
     * recording through the recorder costs nothing and removes the question.
     *
     * @param invocation the seeded datasets, the screen request, the pinned clock and the codec
     * @return the outcome the recorder holds
     */
    private static UnitOutcome driveUserUpdate(Invocation invocation) {
        String where = invocation.program() + '/' + invocation.caseId();
        FixedWidthCodec codec = invocation.codec();
        SeededDataset seeded = invocation.dataset(USRSEC);

        // The seed, and the ordered record of what a successful REWRITE actually stored. A rewrite that
        // reported NOTFND or an I/O failure stored nothing and contributes nothing here, which is why
        // the list is appended to inside the successful branch alone.
        Map<String, SecUserRecord> store = seedStore(seeded, codec, where);
        List<String> stored = new ArrayList<>(1);

        SecUserRepository repository = stubRepository(invocation, store, stored, codec, where);
        UserUpdateController controller = new UserUpdateController(repository, invocation.clock());

        Cu02Info cu02Info = cu02InfoOf(invocation.commarea(), where);
        UserUpdateRequest request = requestOf(invocation, cu02Info, codec, where);
        byte eibAid = attentionIdentifierOf(invocation.aid(), where);

        ProgramState state = controller.handle(request, invocation.eibcalen(), eibAid, cu02Info);

        UnitOutcome.Builder recorder = invocation.recorder();
        // The WRITES channel is touched only when something was written. An empty expectation is a
        // positive assertion that nothing was produced, and reporting a zero-row USRSEC output for a
        // path that never rewrote would contradict it.
        for (String image : stored) {
            recorder.wrote(USRSEC, SecUserRecord.LAYOUT, image);
        }
        recorder.finalState(USRSEC, SecUserRecord.LAYOUT, imagesOf(store, codec));
        recorder.response(observedResponseOf(state, codec));
        for (String line : state.displayLines()) {
            recorder.display(line);
        }
        return recorder.build();
    }

    // -------------------------------------------------------------------------------------------------
    // The seeded dataset.
    // -------------------------------------------------------------------------------------------------

    /**
     * Decodes the seeded rows into records keyed on {@code SEC-USR-ID}, preserving seed order.
     *
     * <p>The rows arrive at their declared eighty bytes: the harness has already applied
     * {@link Normalisation#USRSEC_FILLER_PAD_57_TO_80} to the 57-character {@code DUSRSECJ} records, so
     * nothing is padded here. Decoding and later re-encoding each row through the same codec is
     * deliberate - it means the final state and the written record are produced by one code path, so a
     * {@code SEC-USR-FILLER} defect shows up in both rather than being masked in one.
     *
     * <p>A {@link LinkedHashMap} because {@code expectedFinalState} is asserted row by row in dataset
     * order, and because {@code put} on an existing key replaces the value in place without moving it -
     * which is exactly what {@code EXEC CICS REWRITE} does to a KSDS record.
     *
     * @param seeded the dataset the harness seeded
     * @param codec  the codec carrying the case's code page
     * @param where  the case, for a diagnostic
     * @return the seeded records by key, in seed order
     */
    private static Map<String, SecUserRecord> seedStore(SeededDataset seeded, FixedWidthCodec codec,
                                                        String where) {
        Map<String, SecUserRecord> store = new LinkedHashMap<>();
        for (int rowIndex = 0; rowIndex < seeded.rowCount(); rowIndex++) {
            SecUserRecord record = SecUserRecord.decode(seeded.rowBytes(rowIndex, codec), codec);
            String key = keyOf(record, codec);
            if (store.put(key, record) != null) {
                throw new IllegalStateException(where + ": the seeded " + USRSEC + " rows repeat the "
                        + "key at 0-based row " + rowIndex + ". " + USRSEC + " is a KSDS defined "
                        + "KEYS(8,0) in app/jcl/DUSRSECJ.jcl, so two records cannot share a key and a "
                        + "seed that says they can is describing a dataset VSAM would have refused.");
            }
        }
        return store;
    }

    /**
     * The eighty-character images the store holds, in dataset order.
     *
     * @param store the seeded records, mutated in place by any successful rewrite
     * @param codec the codec carrying the case's code page
     * @return one eighty-character image per row
     */
    private static List<String> imagesOf(Map<String, SecUserRecord> store, FixedWidthCodec codec) {
        List<String> images = new ArrayList<>(store.size());
        for (SecUserRecord record : store.values()) {
            images.add(imageOf(record, codec));
        }
        return images;
    }

    /**
     * One record as its eighty-character image, {@code SEC-USR-FILLER} included.
     *
     * <p>Produced by encoding through {@link SecUserRecord#encode} and decoding the bytes back with an
     * explicitly named code page, never by concatenating the components by hand and never by relying on
     * a platform default charset. The filler is a first-class span of that layout, so omitting it is
     * not possible here - the image would not be eighty characters and the recorder would refuse it.
     *
     * @param record the record
     * @param codec  the codec carrying the case's code page
     * @return the image, exactly {@link SecUserRecord#RECORD_LENGTH} characters
     */
    private static String imageOf(SecUserRecord record, FixedWidthCodec codec) {
        return codec.decodeImage(SecUserRecord.encode(record, codec), "dataset " + USRSEC);
    }

    /**
     * A record's key as the eight-byte image VSAM compares.
     *
     * <p>Through the alphanumeric {@code MOVE} rule rather than by trimming: {@code SEC-USR-ID} is
     * {@code PIC X(08)} and a KSDS key comparison is byte-exact, so {@code 'user0001'} is not
     * {@code 'USER0001'} and {@code 'ADMIN1  '} is not {@code 'ADMIN1'}. Case-folding or trimming here
     * would make {@code case15} - whose whole subject is a lower-case identifier missing the key -
     * silently find a record the mainframe would not have found.
     *
     * @param record the record
     * @param codec  the codec carrying the alphanumeric {@code MOVE} rule
     * @return the eight-character key image
     */
    private static String keyOf(SecUserRecord record, FixedWidthCodec codec) {
        return codec.movePicX(record.secUsrId(), SecUserRecord.KEY_LENGTH);
    }

    // -------------------------------------------------------------------------------------------------
    // The repository double. Fixture-backed, so the ordinary arms come from the seeded data and only the
    // arms no seeded data can reach are forced.
    // -------------------------------------------------------------------------------------------------

    /**
     * A {@link SecUserRepository} standing in for the {@code USRSEC} KSDS, backed by the seeded store.
     *
     * <p>A double rather than the real repository because the real one requires a {@code JdbcTemplate},
     * the dataset binding catalogue, a code page and a record-image form - infrastructure a parity case
     * has no use for, since what is under test is {@code COUSR02C}'s logic and not the JDBC layer.
     *
     * <p>Two operations are backed: {@code readForUpdate} for {@code EXEC CICS READ ... UPDATE} at
     * {@code app/cbl/COUSR02C.cbl:322-331}, and {@code rewrite} for {@code EXEC CICS REWRITE} at
     * {@code :360-366}. Those are the only two file commands in the program.
     *
     * <p>The other four are stubbed to <strong>refuse</strong>, and that is an assertion rather than
     * defensiveness. {@code COUSR02C} never issues a read without {@code UPDATE}, never writes a new
     * record, never browses and never deletes - {@code COUSR01C} adds, {@code COUSR03C} deletes and
     * {@code COUSR00C} browses, and each has its own program and its own cases. A translation that
     * reached for one of them here would be doing something the COBOL does not, and the refusal says so
     * by name instead of surfacing as an unexplained {@link NullPointerException} from an unstubbed
     * mock.
     *
     * @param invocation the invocation, consulted for forced outcomes
     * @param store      the seeded records, mutated in place by a successful rewrite
     * @param stored     collects the image of every record a successful rewrite stored, in order
     * @param codec      the codec carrying the case's code page
     * @param where      the case, for a diagnostic
     * @return the double
     */
    private static SecUserRepository stubRepository(Invocation invocation,
                                                    Map<String, SecUserRecord> store,
                                                    List<String> stored,
                                                    FixedWidthCodec codec,
                                                    String where) {
        SecUserRepository repository = mock(SecUserRepository.class);

        when(repository.readForUpdate(anyString())).thenAnswer(call ->
                readForUpdate(invocation, store, codec, call.getArgument(0, String.class), where));
        when(repository.rewrite(any(SecUserRecord.class))).thenAnswer(call ->
                rewrite(invocation, store, stored, codec,
                        call.getArgument(0, SecUserRecord.class), where));

        when(repository.read(anyString())).thenAnswer(call -> {
            throw unsupportedOperation(where, "read", "app/cbl/COUSR02C.cbl:322 issues EXEC CICS READ "
                    + "with UPDATE, which is readForUpdate. A plain read takes no lock, so using it "
                    + "here would drop the UPDATEMODEL(LOCKING) hold the REWRITE at :360 depends on");
        });
        when(repository.add(any(SecUserRecord.class))).thenAnswer(call -> {
            throw unsupportedOperation(where, "add", "COUSR02C updates an existing user and never "
                    + "creates one; EXEC CICS WRITE belongs to COUSR01C");
        });
        when(repository.startBrowse(anyString())).thenAnswer(call -> {
            throw unsupportedOperation(where, "startBrowse", "COUSR02C reads one record by key and "
                    + "never browses; STARTBR and READNEXT belong to COUSR00C");
        });
        when(repository.deleteHeld(any(HeldRecord.class))).thenAnswer(call -> {
            throw unsupportedOperation(where, "deleteHeld", "COUSR02C never deletes; EXEC CICS DELETE "
                    + "belongs to COUSR03C");
        });
        return repository;
    }

    /**
     * {@code EXEC CICS READ ... UPDATE} - {@code app/cbl/COUSR02C.cbl:322-331}.
     *
     * <p>Ordinarily the arm is decided by the seed: a key that is present is {@code DFHRESP(NORMAL)} and
     * one that is absent is {@code DFHRESP(NOTFND)}. The third arm, {@code WHEN OTHER} at
     * {@code :346-352}, is reachable only from a genuine I/O failure - a closed file, an invalid
     * request - which no arrangement of rows produces, so a case forces it and states the {@code RESP}
     * the program then emits through {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}.
     *
     * @param invocation the invocation, consulted for a forced outcome
     * @param store      the seeded records
     * @param codec      the codec carrying the case's code page
     * @param userId     the {@code RIDFLD} the program passed, which is {@code SEC-USR-ID}
     * @param where      the case, for a diagnostic
     * @return the outcome
     */
    private static ReadResult readForUpdate(Invocation invocation, Map<String, SecUserRecord> store,
                                            FixedWidthCodec codec, String userId, String where) {
        String key = codec.movePicX(userId == null ? "" : userId, SecUserRecord.KEY_LENGTH);
        if (invocation.hasForcedOutcome(RepositoryOperation.READ_FOR_UPDATE)) {
            ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.READ_FOR_UPDATE);
            if (forced.outcome() != FileStatus.Outcome.OK) {
                return ReadResult.of(statusOf(forced.outcome()), responseOf(forced));
            }
            // A forced NORMAL still has to hand back a record, and the only honest one is the record the
            // seed holds under that key. Inventing one would let a case assert values it also supplied.
            SecUserRecord forcedRecord = store.get(key);
            if (forcedRecord == null) {
                throw new IllegalStateException(where + ": the case forces DFHRESP(NORMAL) on the "
                        + "read-for-update, but the seeded " + USRSEC + " holds no record under the key "
                        + "the program asked for. A successful read must return a record, and the only "
                        + "record it can honestly return is the seeded one - so either seed that key or "
                        + "force NOT_FOUND, which is the arm this input actually reaches.");
            }
            return heldForUpdate(forcedRecord);
        }
        SecUserRecord found = store.get(key);
        return found == null ? ReadResult.notFound() : heldForUpdate(found);
    }

    /**
     * {@code EXEC CICS REWRITE} - {@code app/cbl/COUSR02C.cbl:360-366}.
     *
     * <p>A rewrite replaces a record that already exists and cannot create one, so an absent key is
     * {@code DFHRESP(NOTFND)} - the arm at {@code :377-382}. {@code WHEN OTHER} at {@code :383-389} is
     * again forced, because it needs an I/O failure.
     *
     * <p><strong>Only a successful rewrite records a write.</strong> One that reported {@code NOTFND} or
     * an I/O failure changed nothing on the file, and reporting it on the writes channel would assert
     * that {@code COUSR02C} stores records it does not store.
     *
     * @param invocation the invocation, consulted for a forced outcome
     * @param store      the seeded records, mutated in place on success
     * @param stored     collects the image of every record actually stored
     * @param codec      the codec carrying the case's code page
     * @param record     {@code SEC-USER-DATA} as the program has it after the four change tests
     * @param where      the case, for a diagnostic
     * @return the outcome
     */
    private static WriteResult rewrite(Invocation invocation, Map<String, SecUserRecord> store,
                                       List<String> stored, FixedWidthCodec codec,
                                       SecUserRecord record, String where) {
        String key = keyOf(record, codec);
        if (invocation.hasForcedOutcome(RepositoryOperation.REWRITE)) {
            ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.REWRITE);
            if (forced.outcome() != FileStatus.Outcome.OK) {
                return WriteResult.of(statusOf(forced.outcome()), responseOf(forced));
            }
            return applyRewrite(store, stored, codec, key, record, where);
        }
        if (!store.containsKey(key)) {
            return WriteResult.notFound();
        }
        return applyRewrite(store, stored, codec, key, record, where);
    }

    /**
     * Replaces the held record and records the image that landed.
     *
     * @param store  the seeded records
     * @param stored collects the stored image
     * @param codec  the codec carrying the case's code page
     * @param key    the eight-character key image
     * @param record the record to store
     * @param where  the case, for a diagnostic
     * @return {@link WriteResult#written()}
     */
    private static WriteResult applyRewrite(Map<String, SecUserRecord> store, List<String> stored,
                                            FixedWidthCodec codec, String key, SecUserRecord record,
                                            String where) {
        if (!store.containsKey(key)) {
            throw new IllegalStateException(where + ": the case forces a successful REWRITE for a key "
                    + "the seeded " + USRSEC + " does not hold. REWRITE replaces the record a "
                    + "read-for-update is holding and cannot create one, so a successful rewrite of an "
                    + "absent key is a state VSAM cannot be in - force NOT_FOUND instead, which is the "
                    + "arm at app/cbl/COUSR02C.cbl:377-382.");
        }
        store.put(key, record);
        stored.add(imageOf(record, codec));
        return WriteResult.written();
    }

    /**
     * A successful read-for-update, holding the record it read.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} defines {@code USRSEC} with {@code UPDATEMODEL(LOCKING)}, so
     * {@code READ ... UPDATE} acquires a hold for the duration of the unit of work. The hold is recorded
     * on {@link ProgramState#hold()}, but the program does <em>not</em> write through it: {@code :360}
     * issues {@code EXEC CICS REWRITE DATASET(WS-USRSEC-FILE)}, addressing the file rather than the
     * held record, and the translation follows suit.
     *
     * @param record the record read
     * @return the outcome, carrying both the record and the hold
     */
    private static ReadResult heldForUpdate(SecUserRecord record) {
        return ReadResult.held(record, mock(HeldRecord.class));
    }

    /**
     * The two-character {@code FILE STATUS} equivalent of a forced outcome.
     *
     * <p>{@code COUSR02C} tests {@code RESP} rather than {@code FILE STATUS} - it is an online program -
     * but the repository reports both through one vocabulary so that a batch caller and an online caller
     * branch on the same discriminated outcomes. {@code OTHER} has no named status, which is the point
     * of {@link SecUserRepository#PERMANENT_ERROR_STATUS}: any status that is none of the four named
     * ones classifies as {@code OTHER}.
     *
     * @param outcome the forced outcome, never {@link FileStatus.Outcome#OK}
     * @return the status image
     */
    private static String statusOf(FileStatus.Outcome outcome) {
        return switch (outcome) {
            case OK -> FileStatus.OK;
            case END_OF_FILE -> FileStatus.END_OF_FILE;
            case NOT_FOUND -> FileStatus.NOT_FOUND;
            case DUPLICATE -> FileStatus.DUPLICATE;
            case OTHER -> SecUserRepository.PERMANENT_ERROR_STATUS;
        };
    }

    /**
     * The {@code RESP} and {@code RESP2} a forced outcome reports.
     *
     * <p>The values matter as much as the arm, because {@code :347} and {@code :384} emit them:
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}. A case that states them pins the emitted
     * line; one that leaves them out gets the outcome's conventional value, which
     * {@link FileStatus} names.
     *
     * @param forced the forced outcome
     * @return the response codes
     */
    private static CicsResponse responseOf(ForcedOutcome forced) {
        int resp = forced.resp() == null ? conventionalResp(forced.outcome()) : forced.resp();
        int resp2 = forced.resp2() == null ? DEFAULT_RESP2 : forced.resp2();
        return CicsResponse.reported(resp, resp2);
    }

    /**
     * The {@code RESP} value conventionally reported for an outcome, for a case that states none.
     *
     * @param outcome the forced outcome
     * @return the conventional {@code RESP}
     */
    private static int conventionalResp(FileStatus.Outcome outcome) {
        return switch (outcome) {
            case OK -> FileStatus.NORMAL;
            case END_OF_FILE -> FileStatus.ENDFILE;
            case NOT_FOUND -> FileStatus.NOTFND;
            case DUPLICATE -> FileStatus.DUPREC;
            case OTHER -> FileStatus.INVREQ;
        };
    }

    /**
     * The failure raised when the translation reaches for a file command {@code COUSR02C} does not
     * issue.
     *
     * @param where     the case
     * @param operation the repository method that was called
     * @param why       why the program cannot have called it
     * @return the failure to throw
     */
    private static IllegalStateException unsupportedOperation(String where, String operation,
                                                              String why) {
        return new IllegalStateException(where + ": the translation called SecUserRepository."
                + operation + ", which COUSR02C never does. " + why + ". COUSR02C issues exactly six "
                + "EXEC CICS commands - RETURN, XCTL, SEND MAP, RECEIVE MAP, READ ... UPDATE and "
                + "REWRITE - so readForUpdate and rewrite are the whole of its file access.");
    }

    // -------------------------------------------------------------------------------------------------
    // The invocation: the terminal input area, the communication area and its extension, and EIBAID.
    // -------------------------------------------------------------------------------------------------

    /**
     * The twelve {@code xxxI} items of {@code COUSR2AI}, as the case declares them.
     *
     * <p>Values are carried through <strong>verbatim</strong> - untrimmed, unpadded and including a
     * {@code LOW-VALUES} span of {@code x'00'} bytes, which {@code case18} declares on {@code PASSWDI}
     * because that is what an unmodified 3270 field actually contains after
     * {@code MOVE LOW-VALUES TO COUSR2AO} at {@code :97}. The four change tests at {@code :219-234}
     * compare untrimmed, so a helpful {@code trim()} here would make a real difference invisible.
     *
     * <p>An item the case omits arrives as {@code null}, which the twelve components accept: a field the
     * terminal did not send is spaces rather than nothing, and the controller applies the alphanumeric
     * {@code MOVE} rule to it.
     *
     * <p>Every declared key is checked against the twelve items <em>this</em> mapset declares. That
     * guard exists for one specific mistake: {@code COUSR01}'s identifier is spelled {@code USERID} and
     * this one's is spelled {@code USRIDIN}, so a field list copied from the sibling screen would
     * otherwise bind to nothing at all and the case would pass having asserted an empty screen.
     *
     * @param invocation the invocation carrying the received map fields
     * @param cu02Info   the commarea extension, already rebuilt
     * @param codec      the codec carrying the case's code page
     * @param where      the case, for a diagnostic
     * @return the terminal input area
     */
    private static UserUpdateRequest requestOf(Invocation invocation, Cu02Info cu02Info,
                                               FixedWidthCodec codec, String where) {
        Map<String, String> received = invocation.mapFields();
        for (String declared : received.keySet()) {
            if (!UserUpdateRequest.MAP_FIELD_NAMES.contains(declared)) {
                throw new IllegalStateException(where + ": mapFields declares '" + declared
                        + "', which is not one of the " + UserUpdateRequest.MAP_FIELD_COUNT
                        + " symbolic-map input items of COUSR2AI (app/cpy-bms/COUSR02.CPY): "
                        + UserUpdateRequest.MAP_FIELD_NAMES + ". Note that this mapset's identifier is "
                        + UserUpdateRequest.USRIDIN_FIELD + " - COUSR01 spells its own USERIDI and "
                        + "COUSR03 has no password item at all, so a field list is never copied between "
                        + "the three user screens.");
            }
        }
        return new UserUpdateRequest(
                received.get(UserUpdateRequest.TRNNAME_FIELD),
                received.get(UserUpdateRequest.TITLE01_FIELD),
                received.get(UserUpdateRequest.CURDATE_FIELD),
                received.get(UserUpdateRequest.PGMNAME_FIELD),
                received.get(UserUpdateRequest.TITLE02_FIELD),
                received.get(UserUpdateRequest.CURTIME_FIELD),
                received.get(UserUpdateRequest.USRIDIN_FIELD),
                received.get(UserUpdateRequest.FNAME_FIELD),
                received.get(UserUpdateRequest.LNAME_FIELD),
                received.get(UserUpdateRequest.PASSWD_FIELD),
                received.get(UserUpdateRequest.USRTYPE_FIELD),
                received.get(UserUpdateRequest.ERRMSG_FIELD),
                navigationContextOf(invocation.commarea(), codec),
                aidTokenOf(invocation.aid(), where),
                cu02Info);
    }

    /**
     * {@code CARDDEMO-COMMAREA} as {@code :94} restores it -
     * {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA}.
     *
     * <p>Rebuilt as a byte image and decoded, rather than by assigning sixteen components, because that
     * is what the COBOL statement is: a byte move into a 160-byte group. Going through
     * {@link NavigationContext#LAYOUT} also means a field the case leaves out keeps the value the layout
     * declares - spaces in a character span, zeros in a numeric one - which is precisely the state a
     * cold start sees, instead of a {@code null} the caller would have to defend against.
     *
     * <p>Returns {@code null} for a case that carries no commarea at all. That is {@code EIBCALEN = 0},
     * the guarded path at {@code :90-92}, and it is a genuinely different state from a commarea full of
     * spaces: the program reads the length, not the content.
     *
     * @param commarea the {@code CDEMO-} field values the case declares, including this program's
     *                 extension, which is filtered out here
     * @param codec    the codec carrying the case's code page
     * @return the restored communication area, or {@code null} when none was passed
     */
    private static NavigationContext navigationContextOf(Map<String, String> commarea,
                                                         FixedWidthCodec codec) {
        if (commarea.isEmpty()) {
            return null;
        }
        Map<String, String> images = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : commarea.entrySet()) {
            if (NavigationContext.LAYOUT.hasSpan(field.getKey())) {
                images.put(field.getKey(), field.getValue());
            }
        }
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, images));
    }

    /**
     * {@code 05 CDEMO-CU02-INFO} - this program's own 34-byte commarea extension,
     * {@code app/cbl/COUSR02C.cbl:50-58}.
     *
     * <p>It is appended to the 160-byte {@code CARDDEMO-COMMAREA} rather than folded into it, because
     * {@code app/cpy/COCOM01Y.cpy} is exactly 160 bytes and is shared by all seventeen online programs
     * while this group belongs to {@code COUSR02C} alone.
     *
     * <p>{@code CDEMO-CU02-USR-SELECTED} is the member that matters most: {@code :99-102} copies it over
     * {@code USRIDINI} on first entry and then performs the lookup, so it is a second resource identity
     * and the whole subject of {@code case03}.
     *
     * <p>A case that carries no commarea gets {@link Cu02Info#initial()} - the state the {@code VALUE}
     * clauses leave, which is spaces, zero and {@code 'N'}. There is no third state.
     *
     * @param commarea the {@code CDEMO-} field values the case declares
     * @param where    the case, for a diagnostic
     * @return the extension
     */
    private static Cu02Info cu02InfoOf(Map<String, String> commarea, String where) {
        if (commarea.isEmpty()) {
            return Cu02Info.initial();
        }
        for (String declared : commarea.keySet()) {
            if (!NavigationContext.LAYOUT.hasSpan(declared) && !CU02_INFO_FIELDS.contains(declared)) {
                throw new IllegalStateException(where + ": the commarea declares '" + declared
                        + "', which is neither a CARDDEMO-COMMAREA field (app/cpy/COCOM01Y.cpy) nor one "
                        + "of the six CDEMO-CU02-INFO items (app/cbl/COUSR02C.cbl:50-58): "
                        + CU02_INFO_FIELDS + ". A field name nothing consumes is a field name that "
                        + "asserts nothing, and it reads in review as though it did.");
            }
        }
        return new Cu02Info(commarea.get(CU02_USRID_FIRST_FIELD),
                commarea.get(CU02_USRID_LAST_FIELD),
                pageNumberOf(commarea.get(CU02_PAGE_NUM_FIELD), where),
                commarea.get(CU02_NEXT_PAGE_FLG_FIELD),
                commarea.get(CU02_USR_SEL_FLG_FIELD),
                commarea.get(CU02_USR_SELECTED_FIELD));
    }

    /**
     * {@code CDEMO-CU02-PAGE-NUM PIC 9(08)} as a number.
     *
     * <p>An unsigned display picture, so its image is eight digits and an absent or blank one is zero -
     * the value the {@code VALUE} clause leaves. Parsed rather than assumed so that a case declaring a
     * page number actually drives it.
     *
     * @param image the eight-digit image, or {@code null} when the case declares none
     * @param where the case, for a diagnostic
     * @return the page number
     */
    private static int pageNumberOf(String image, String where) {
        if (image == null || image.isBlank()) {
            return 0;
        }
        String digits = image.trim();
        for (int index = 0; index < digits.length(); index++) {
            if (digits.charAt(index) < '0' || digits.charAt(index) > '9') {
                throw new IllegalStateException(where + ": " + CU02_PAGE_NUM_FIELD + " is declared '"
                        + image + "', which is not an unsigned display number. The item is PIC 9(08) "
                        + "and has no sign position and no space for one.");
            }
        }
        return Integer.parseInt(digits);
    }

    /**
     * {@code EIBAID} - the attention identifier byte {@code :108-131} evaluates.
     *
     * <p>The mnemonic a case declares is resolved against {@link CicsAid}, so the byte the translation
     * compares is the byte IBM documents. A mnemonic that class does not know is refused rather than
     * defaulted, because a defaulted AID would quietly land on {@code WHEN OTHER} and a case aiming at
     * {@code DFHPF5} would then pass while asserting the invalid-key screen.
     *
     * @param mnemonic the {@code DFHAID} mnemonic, or {@code null} when the case declares none
     * @param where    the case, for a diagnostic
     * @return the byte
     */
    private static byte attentionIdentifierOf(String mnemonic, String where) {
        if (mnemonic == null) {
            return NO_AID_DECLARED;
        }
        Byte eibAid = AID_BY_MNEMONIC.get(mnemonic);
        if (eibAid == null) {
            throw new IllegalStateException(where + ": '" + mnemonic + "' is not a DFHAID mnemonic. "
                    + "The known mnemonics are " + new TreeSet<>(AID_BY_MNEMONIC.keySet())
                    + ", reproduced in common.CicsAid from IBM CICS documentation because DFHAID is "
                    + "IBM-supplied and absent from this repository.");
        }
        return eibAid;
    }

    /**
     * The five-character resolved AID token the request payload carries.
     *
     * <p>{@link UserUpdateController#handle} takes the {@code EIBAID} byte as a parameter, so this token
     * is not what drives the dispatch; it is carried so the payload is coherent with the byte, the same
     * way the HTTP adapter receives it. {@code null} for an AID that resolves to no key - which is the
     * wire form of "a key this program names in no arm".
     *
     * @param mnemonic the {@code DFHAID} mnemonic, or {@code null}
     * @param where    the case, for a diagnostic
     * @return the token, or {@code null}
     */
    private static String aidTokenOf(String mnemonic, String where) {
        return PfKeyResolver.resolve(attentionIdentifierOf(mnemonic, where))
                .map(AidKey::token)
                .orElse(null);
    }

    // -------------------------------------------------------------------------------------------------
    // The observation: what the run produced, in the differ's vocabulary.
    // -------------------------------------------------------------------------------------------------

    /**
     * Projects the terminal state onto the differ's response observation.
     *
     * <p>Four things, each of which is behaviour the COBOL exhibits:
     * <ul>
     *   <li><strong>{@code nextProgram}</strong> - the {@code EXEC CICS XCTL PROGRAM(...)} target at
     *       {@code :259}, and {@code null} on a path that returns to its own transaction at
     *       {@code :135-138}. This is what a stateless translation has instead of a server-side
     *       forward.</li>
     *   <li><strong>{@code nextMapset} and {@code nextMap}</strong> - {@code CDEMO-LAST-MAPSET} and
     *       {@code CDEMO-LAST-MAP} as the conversation carries them. A blank pair is reported as
     *       {@code null}, which is the cold start: no map has been sent, so none is named.</li>
     *   <li><strong>{@code navigation}</strong> - all twenty-two commarea values, the sixteen shared
     *       ones plus this program's six. This is where statelessness is asserted: the conversation
     *       state is in the payload, so it is comparable at all. A translation holding it in a session
     *       could not produce this map.</li>
     *   <li><strong>{@code sends}</strong> - every {@code SEND-USRUPD-SCREEN} in order, each with the
     *       twelve {@code xxxO} values and the {@code ERRMSGC} colour <em>at that send</em>. A list
     *       rather than one screen because the send count is itself behaviour: several paths paint
     *       twice - a successful save sends "press PF5" over the record it read and then sends again
     *       with the green confirmation - and {@code case03} paints three times.</li>
     * </ul>
     *
     * @param state the terminal state the seam returned
     * @param codec the codec carrying the case's code page
     * @return the observation
     */
    private static ObservedResponse observedResponseOf(ProgramState state, FixedWidthCodec codec) {
        List<ObservedSend> sends = new ArrayList<>(state.sendCount());
        for (Send send : state.sends()) {
            sends.add(new ObservedSend(send.fields(), attributesSetBy(send)));
        }

        NavigationContext commarea = state.commarea();
        Map<String, String> navigation = new LinkedHashMap<>(
                codec.deserialise(NavigationContext.LAYOUT, commarea.toFixedWidth(codec)));
        navigation.putAll(state.cu02Info().fieldImages());

        return new ObservedResponse(state.transferred() ? state.nextProgram() : null,
                blankToNull(commarea.lastMapset()),
                blankToNull(commarea.lastMap()),
                navigation,
                sends,
                state.cursorFieldName(),
                Termination.valueOf(state.termination()));
    }

    /**
     * The attribute items a send actually <strong>set</strong>, which is not the same as the attribute
     * items it carries.
     *
     * <p>{@code ERRMSGC OF COUSR2AO} is the one attribute item {@code COUSR02C} ever moves a value into,
     * at three sites: {@code DFHNEUTR} on a successful read at {@code :338}, {@code DFHRED} on
     * "Please modify to update ..." at {@code :241} and {@code DFHGREEN} on a successful rewrite at
     * {@code :371}. On a path that reaches none of those three, the byte is still <em>there</em> - it is
     * the {@code x'00'} that {@code MOVE LOW-VALUES TO COUSR2AO} at {@code :97} left, which
     * {@link BmsAttributes#DFHDFCOL} names - but the program did not put it there.
     *
     * <p>So {@code DFHDFCOL} is dropped, and the distinction is the whole point: "the program moved a
     * colour onto the message field" and "the program moved nothing and the field is still default" are
     * different observations, and reporting the second as though it were the first would claim an
     * attribute was set on every send this program makes. The colour byte itself is what decides that,
     * not its rendered mnemonic, so the comparison is against the constant.
     *
     * <p>Note that the colour is <strong>sticky</strong> across sends within one call, and faithfully
     * so: once {@code :338} has moved {@code DFHNEUTR} into the redefined output area, an arm that
     * repaints without moving a colour of its own sends the same byte again. {@code case16} pins exactly
     * that - a successful read followed by a rewrite reporting {@code NOTFND}, whose arm at
     * {@code :377-382} moves no colour, so both sends carry {@code DFHNEUTR}.
     *
     * @param send one screen send
     * @return the attribute items this send set, empty when it set none
     */
    private static Map<String, String> attributesSetBy(Send send) {
        if (send.errMsgColour() == BmsAttributes.DFHDFCOL) {
            return Map.of();
        }
        return send.attributes();
    }

    /**
     * A character span reported as {@code null} when it holds nothing but spaces.
     *
     * <p>{@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} are {@code PIC X(07)} and are spaces until
     * a map has been sent, and "no map is named" is a different observation from "a map whose name is
     * seven blanks". The untrimmed value is still asserted in full through {@code navigation}, so
     * nothing is lost by normalising it here.
     *
     * @param span the span image
     * @return the trimmed value, or {@code null} when the span is blank
     */
    private static String blankToNull(String span) {
        return span == null || span.isBlank() ? null : span.trim();
    }

    // -------------------------------------------------------------------------------------------------
    // Copybook names this program appends to the shared communication area - app/cbl/COUSR02C.cbl:50-58.
    // Carried verbatim, hyphens and all, because these are the names the differ compares by.
    // -------------------------------------------------------------------------------------------------

    /** {@code 10 CDEMO-CU02-USRID-FIRST PIC X(08)} - the first user id on the list page. */
    private static final String CU02_USRID_FIRST_FIELD = "CDEMO-CU02-USRID-FIRST";

    /** {@code 10 CDEMO-CU02-USRID-LAST PIC X(08)} - the last user id on the list page. */
    private static final String CU02_USRID_LAST_FIELD = "CDEMO-CU02-USRID-LAST";

    /** {@code 10 CDEMO-CU02-PAGE-NUM PIC 9(08)} - the list page the operator came from. */
    private static final String CU02_PAGE_NUM_FIELD = "CDEMO-CU02-PAGE-NUM";

    /** {@code 10 CDEMO-CU02-NEXT-PAGE-FLG PIC X(01)} - {@code 88 NEXT-PAGE-YES/NO}. */
    private static final String CU02_NEXT_PAGE_FLG_FIELD = "CDEMO-CU02-NEXT-PAGE-FLG";

    /** {@code 10 CDEMO-CU02-USR-SEL-FLG PIC X(01)} - which selection action the list requested. */
    private static final String CU02_USR_SEL_FLG_FIELD = "CDEMO-CU02-USR-SEL-FLG";

    /**
     * {@code 10 CDEMO-CU02-USR-SELECTED PIC X(08)} - the user the list selected, which {@code :99-102}
     * copies over {@code USRIDINI} on first entry.
     */
    private static final String CU02_USR_SELECTED_FIELD = "CDEMO-CU02-USR-SELECTED";

    /** The six extension items, for validating a case's commarea. Unmodifiable. */
    private static final Set<String> CU02_INFO_FIELDS = Set.of(CU02_USRID_FIRST_FIELD,
            CU02_USRID_LAST_FIELD, CU02_PAGE_NUM_FIELD, CU02_NEXT_PAGE_FLG_FIELD,
            CU02_USR_SEL_FLG_FIELD, CU02_USR_SELECTED_FIELD);

    /**
     * Inverts {@link CicsAid#mnemonicsByAid()} so a case's mnemonic resolves to its byte.
     *
     * <p>The published map is one-to-one, and a collision would mean two mnemonics claim one byte, so it
     * is refused rather than silently resolved to whichever entry came last.
     *
     * @return mnemonic to {@code EIBAID} byte, unmodifiable
     */
    private static Map<String, Byte> invertAidMnemonics() {
        Map<String, Byte> byMnemonic = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            Byte clash = byMnemonic.put(entry.getValue(), entry.getKey());
            if (clash != null) {
                throw new IllegalStateException("CicsAid maps the mnemonic '" + entry.getValue()
                        + "' onto more than one AID byte, so a case naming it could not be resolved to "
                        + "one key. The DFHAID mnemonics are one-to-one with their bytes.");
            }
        }
        return Collections.unmodifiableMap(byMnemonic);
    }
}
