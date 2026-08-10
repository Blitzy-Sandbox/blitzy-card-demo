package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.user.SecUserRepository;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SignOnController;
import com.vsergeychik.carddemo.user.SignOnService;
import com.vsergeychik.carddemo.user.SignOnService.CursorField;
import com.vsergeychik.carddemo.user.SignOnService.SignOnInput;
import com.vsergeychik.carddemo.user.SignOnService.SignOnOutcome;
import com.vsergeychik.carddemo.user.dto.SignOnRequest;
import com.vsergeychik.carddemo.user.dto.SignOnResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The twenty-case behavioural parity gate for {@code app/cbl/COSGN00C.cbl} - CICS transaction
 * {@code CC00}, "Signon Screen for the CardDemo Application", projected onto
 * {@code POST /api/signon}.
 *
 * <h2>Where the expected values come from, and where they do not</h2>
 *
 * <p><strong>The baseline is statically derived. It was never captured from a running COBOL
 * program.</strong> Executing the twenty-eight legacy programs is impossible in this environment:
 * there is no z/OS runtime and no CICS emulator, the available compiler reports
 * {@code indexed file handler : disabled} so the programs using {@code ORGANIZATION INDEXED} cannot
 * even build, there are no Language Environment {@code CEE*} services, and {@code DFHAID},
 * {@code DFHBMSCA} and {@code DFHATTR} are absent from this repository altogether. Every expected
 * value in {@code src/test/resources/parity/COSGN00C/} was therefore obtained by reading
 * {@code app/cbl/COSGN00C.cbl} paragraph by paragraph and cross-checking five authoritative
 * sources: {@code app/cpy/CSUSR01Y.cpy} for the record's byte layout,
 * {@code app/cpy-bms/COSGN00.CPY} with {@code app/bms/COSGN00.bms} for the screen's field shapes,
 * {@code app/cpy/COCOM01Y.cpy} for the communication area, {@code app/csd/CARDDEMO.CSD} for the
 * transaction definition, and {@code app/jcl/DUSRSECJ.jcl} for the real seed data. That is a
 * substitution of provenance and only of provenance: twenty cases, field-for-field diffing and a
 * required diff count of zero all stand. It is stated here rather than only in a commit message
 * because a statically derived expectation can encode a misreading of the COBOL where a captured
 * one cannot, and whoever debugs a failure needs to know which side to doubt.
 *
 * <h2>The password is compared in plaintext, and that is asserted rather than corrected</h2>
 *
 * <p>{@code SEC-USR-PWD PIC X(08)} in {@code app/cpy/CSUSR01Y.cpy} holds the password as typed, and
 * {@code app/cbl/COSGN00C.cbl:223} is {@code IF SEC-USR-PWD = WS-USER-PWD} - a plain alphanumeric
 * comparison across all eight characters, with no hash, no salt and no encoder anywhere in the
 * program. This gate asserts that comparison as it stands. Hashing it would require Spring
 * Security, which is out of scope, and would change observable behaviour, which is forbidden: a
 * password that hashes differently would sign on differently. <strong>The plaintext-credential
 * characteristic is an inherited property of the legacy design and an explicit non-goal of this
 * migration</strong>, recorded here so that it stays visible rather than buried in generated code.
 * The values involved are the 2022-vintage demo strings already present in this repository as
 * in-stream JCL data at {@code app/jcl/DUSRSECJ.jcl:35-44}; nothing here introduces a credential
 * that guards anything, and no {@code PasswordEncoder}, {@code BCrypt}, token or filter chain is
 * named in this file.
 *
 * <h2>Role routing is a response field, not an {@code XCTL}</h2>
 *
 * <p>{@code app/cbl/COSGN00C.cbl:230-240} is the program's only transfer of control:
 * {@code EXEC CICS XCTL PROGRAM('COADM01C')} at {@code :232} when {@code 88 CDEMO-USRTYP-ADMIN}
 * holds, and {@code PROGRAM('COMEN01C')} at {@code :237} otherwise. In a stateless translation
 * there is no server-side forward: the sign-on response carries the role and the target program,
 * and the client issues the follow-on call. So the cases below pin {@code nextProgram} rather than
 * observing a dispatch, and they pin it to the administrator target for {@code 'A'} and to the
 * regular-user target for {@code 'U'}. No servlet forward, redirect, session or session-scoped bean
 * exists anywhere in the path - the whole communication area travels in the payload, which
 * {@link #theNavigationContextTravelsInThePayload()} asserts at its declared
 * {@value NavigationContext#COMMAREA_LENGTH} bytes.
 *
 * <h2>No HTTP between the assertion and the code</h2>
 *
 * <p>The unit is {@link SignOnService}, the real {@code @Service}, constructed through its own
 * constructor with a fixture-backed {@link SecUserRepository} injected. The eleven-field screen
 * projection is produced by the real {@link SignOnController}, constructed as a plain Java object
 * and called through {@link SignOnController#signOn(SignOnRequest)} as any other method would be.
 * There is no Mock MVC, no test REST template, no web test client, no servlet container and no job
 * launcher anywhere in this file: a parity assertion is about arithmetic and byte layout, and
 * putting a dispatcher, a filter chain and a JSON round trip between the assertion and the decision
 * logic can only obscure which of them produced a difference.
 *
 * <p>The service is invoked <strong>exactly once</strong> per case. {@link RecordingSignOnService}
 * is a subclass that delegates to {@code super.handle} and keeps the outcome, so the controller
 * drives the real decision and this file still sees the whole of {@link SignOnOutcome} - the
 * eighty-byte {@code WS-MESSAGE}, the {@code WS-ERR-FLG} state, the {@code RESP} classification and
 * the termination - none of which survives the projection onto ten screen fields.
 *
 * @see SignOnService the translation of {@code app/cbl/COSGN00C.cbl}
 * @see SignOnController the eleven-field projection of mapset {@code COSGN00}
 * @see SecUserRecord the eighty-byte {@code SEC-USER-DATA} record of {@code app/cpy/CSUSR01Y.cpy}
 * @see ParityHarness which seeds, invokes and captures
 * @see FieldDiffer which judges, field by field
 */
final class COSGN00CParityTest {

    // =============================================================================================
    // Identity. The class stem, the resource directory and the "program" member of all twenty case
    // files have to agree, so the name is written once.
    // =============================================================================================

    /** {@code PROGRAM-ID. COSGN00C} - {@code app/cbl/COSGN00C.cbl:23}. */
    private static final String PROGRAM = "COSGN00C";

    /**
     * The dataset binding key, which is the CICS file name of
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at {@code app/cbl/COSGN00C.cbl:39}.
     *
     * <p>No {@code AWS.M2.CARDDEMO} literal appears in this file: dataset names live in
     * {@code application.yml} and are resolved from configuration, never from Java source.
     */
    private static final String USRSEC = SecUserRepository.CICS_FILE_NAME;

    /**
     * {@code KEYS(8,0)} - the primary key width {@code app/jcl/DUSRSECJ.jcl} STEP02 defines for
     * {@code USRSEC}, and the {@code KEYLENGTH(LENGTH OF WS-USER-ID)} the program passes at
     * {@code app/cbl/COSGN00C.cbl:216}.
     */
    private static final int KEY_LENGTH = SecUserRecord.KEY_LENGTH;

    /**
     * The width the ten in-stream seed rows of {@code app/jcl/DUSRSECJ.jcl:35-44} are carried at.
     *
     * <p>Derived rather than typed: it is {@code CSUSR01Y}'s eighty less the twenty-three bytes of
     * {@code SEC-USR-FILLER}, which is the span the JCL omits. It comes to fifty-seven, and
     * {@link #everyCaseDeclaresTheUsrsecPad()} asserts that arithmetic so a reader never has to take
     * the number on trust.
     */
    private static final int SEED_ROW_WIDTH =
            SecUserRecord.RECORD_LENGTH - SecUserRecord.SEC_USR_FILLER_LENGTH;

    /** Ten rows: five {@code ADMIN00n} and five {@code USER000n} - {@code DUSRSECJ.jcl:35-44}. */
    private static final int SEED_ROW_COUNT = 10;

    /** How many of the ten seeded users carry {@code SEC-USR-TYPE} {@code 'A'}. */
    private static final int SEED_ADMIN_COUNT = 5;

    /**
     * The {@code APPLID} standing in for {@code EXEC CICS ASSIGN APPLID} at
     * {@code app/cbl/COSGN00C.cbl:198-200}.
     *
     * <p>A documented substitution rather than a derivation. There is no CICS region here to ask,
     * and neither {@code application.yml} nor {@code application-test.yml} binds an applid, so this
     * is a pinned constant that every one of the twenty cases uses. It is stated at the map's
     * declared width so the projection can be compared byte for byte.
     */
    private static final String APPLID = "CICSAWS1";

    /**
     * The {@code SYSID} standing in for {@code EXEC CICS ASSIGN SYSID} at
     * {@code app/cbl/COSGN00C.cbl:202-204} - the same documented substitution as {@link #APPLID}.
     */
    private static final String SYSID = "AWS1";

    /**
     * {@code WS-MESSAGE PIC X(80)} - {@code app/cbl/COSGN00C.cbl:38}.
     */
    private static final int WS_MESSAGE_LENGTH = SignOnService.MESSAGE_LENGTH;

    /** {@code ERRMSGO PIC X(78)} - {@code app/cpy-bms/COSGN00.CPY:152}, two bytes narrower. */
    private static final int ERRMSG_LENGTH = SignOnResponse.ERRMSG_LENGTH;

    /**
     * The suffix that turns a {@code DFHMDF} label into its symbolic-map length item.
     *
     * <p>{@link ScreenMetadata} reports the cursor by the field's {@code DFHMDF} label -
     * {@code USERID}, {@code PASSWD} - while a {@link ParityCase} pins the {@code xxxL} item that
     * received the {@code MOVE -1}, because that is what COBOL actually writes to. The two spellings
     * are tied together by {@link #CURSOR_ITEMS} rather than by a literal here.
     */
    private static final String LENGTH_ITEM_SUFFIX = "L";

    /**
     * The {@code LOW-VALUES} character a symbolic-map field holds when nothing has been moved into
     * it - {@code X'00'}, and emphatically not a space.
     */
    private static final char LOW_VALUE = '\u0000';

    /** The space character {@code MOVE SPACES} writes, named so no bare literal is needed. */
    private static final char SPACE = ' ';

    /**
     * {@code DFHAID} mnemonic to attention identifier byte.
     *
     * <p>A case declares its key as a mnemonic, which is how a reader recognises it;
     * {@code app/cbl/COSGN00C.cbl:85} evaluates a byte. {@link CicsAid#mnemonicsByAid()} is the
     * module's single reproduction of the absent IBM copybook, so the mapping is inverted from that
     * rather than restated - a second table could disagree with the first, and a case naming
     * {@code DFHPF3} while driving {@code DFHPF4} would exercise the wrong arm of the
     * {@code EVALUATE} and still pass.
     *
     * <p>Unmodifiable, so this introduces no mutable static state.
     */
    private static final Map<String, Byte> AID_BYTES = aidBytesByMnemonic();

    /**
     * The byte presented when a case declares no key at all.
     *
     * <p>One of the twenty paths never reads {@code EIBAID}: the {@code IF EIBCALEN = 0} guard at
     * {@code app/cbl/COSGN00C.cbl:80}, which answers before the {@code EVALUATE EIBAID} at
     * {@code :85} is reached. That case declares no AID and is driven with {@code DFHNULL} - the
     * value CICS itself uses for "no attention identifier", which
     * {@link PfKeyResolver#resolve(byte)} reports as no key.
     */
    private static final byte NO_AID = CicsAid.DFHNULL;

    /**
     * {@code DFHMDF} label to the symbolic-map length item that receives {@code MOVE -1}.
     *
     * <p>Built from both production spellings at once - {@link SignOnController}'s cursor labels and
     * {@link CursorField#lengthItemName()} - so the two cannot drift apart without this table
     * failing to build. {@code app/cbl/COSGN00C.cbl} positions the cursor at four sites: {@code :82}
     * and {@code :121} and {@code :250} and {@code :255} onto {@code USERIDL}, and {@code :126} and
     * {@code :244} onto {@code PASSWDL}.
     */
    private static final Map<String, String> CURSOR_ITEMS = cursorItemsByLabel();

    /**
     * The sixteen {@code CARDDEMO-COMMAREA} fields of {@code app/cpy/COCOM01Y.cpy:19-44}, in
     * copybook order.
     *
     * <p>{@code COSGN00C} copies the area at {@code :48} and hands it back at {@code :100} on every
     * path, so all sixteen are compared on every case even though the program writes only five of
     * them, at {@code :224-228}.
     */
    private static final List<String> COMMAREA_FIELDS = List.of(
            NavigationContext.FROM_TRANID_FIELD,
            NavigationContext.FROM_PROGRAM_FIELD,
            NavigationContext.TO_TRANID_FIELD,
            NavigationContext.TO_PROGRAM_FIELD,
            NavigationContext.USER_ID_FIELD,
            NavigationContext.USER_TYPE_FIELD,
            NavigationContext.PGM_CONTEXT_FIELD,
            NavigationContext.CUST_ID_FIELD,
            NavigationContext.CUST_FNAME_FIELD,
            NavigationContext.CUST_MNAME_FIELD,
            NavigationContext.CUST_LNAME_FIELD,
            NavigationContext.ACCT_ID_FIELD,
            NavigationContext.ACCT_STATUS_FIELD,
            NavigationContext.CARD_NUM_FIELD,
            NavigationContext.LAST_MAP_FIELD,
            NavigationContext.LAST_MAPSET_FIELD);

    /**
     * The symbolic-map input item behind {@code USERIDI OF COSGN0AI}, tested at
     * {@code app/cbl/COSGN00C.cbl:118} and normalised at {@code :132}.
     */
    private static final String USERID_INPUT_ITEM = "USERIDI";

    /**
     * The symbolic-map input item behind {@code PASSWDI OF COSGN0AI}, tested at
     * {@code app/cbl/COSGN00C.cbl:123} and normalised at {@code :135}.
     */
    private static final String PASSWD_INPUT_ITEM = "PASSWDI";

    /**
     * {@code PASSWDO}, the eleventh named item of mapset {@code COSGN00}.
     *
     * <p>{@link SignOnResponse} deliberately carries no component for it -
     * {@link SignOnResponse#OMITTED_ITEM} names it as the omission - so the payload never echoes a
     * password back over JSON. The screen storage still holds one, because
     * {@code app/cpy-bms/COSGN00.CPY:85} declares {@code COSGN0AO REDEFINES COSGN0AI}; see
     * {@link #credentialItemImage(Invocation, String, boolean)}.
     */
    private static final String PASSWD_OUTPUT_ITEM = SignOnResponse.OMITTED_ITEM;

    /**
     * The eleven named output items of mapset {@code COSGN00}, in symbolic-map order.
     *
     * <p>Ten come from {@link SignOnResponse#MAP_FIELDS} and the eleventh is
     * {@link #PASSWD_OUTPUT_ITEM}, inserted at the position {@code app/cpy-bms/COSGN00.CPY:141-146}
     * gives it - between {@code USERIDO} and {@code ERRMSGO}. Composed rather than typed out, so the
     * eleven-versus-ten discrepancy is expressed once and cannot be half-fixed.
     */
    private static final List<String> SCREEN_ITEMS = screenItems();

    /** {@code TRNNAMEO} through {@code ERRMSGO}: eleven items - {@code COSGN00.CPY:86-152}. */
    private static final int SCREEN_ITEM_COUNT = SignOnResponse.MAPSET_NAMED_FIELD_COUNT;

    /**
     * How many of the twenty cases sign on successfully and therefore leave by
     * {@code EXEC CICS XCTL} at {@code app/cbl/COSGN00C.cbl:231-239}.
     *
     * <p>Stated as a property of the case set so that a case which quietly stopped signing on -
     * because a seed row changed, or a password expectation was edited - cannot pass unnoticed.
     */
    private static final int SUCCESSFUL_SIGN_ONS = 8;

    /**
     * How many of the twenty reach {@code SEND-SIGNON-SCREEN} at {@code :145-157} and transmit the
     * map.
     */
    private static final int SCREEN_SENDS = 11;

    /**
     * How many of the twenty reach {@code SEND-PLAIN-TEXT} at {@code :162-172} - exactly one, the
     * {@code DFHPF3} arm at {@code :88-90}.
     */
    private static final int PLAIN_TEXT_SENDS = 1;

    // =============================================================================================
    // The case set.
    // =============================================================================================

    /**
     * The twenty cases, loaded from {@code src/test/resources/parity/COSGN00C/}.
     *
     * <p>{@link ParityHarness#casesOf(String)} refuses any set that is not exactly {@code case01}
     * through {@code case20}, and refuses a stray file in the directory too. That is the point of
     * routing through it: "the diff count is zero across all twenty cases" is satisfied vacuously by
     * a set of four, so a short or misnamed set has to fail loudly rather than quietly become a
     * smaller gate.
     *
     * @return the twenty cases in ordinal order
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * The argument stream, each case labelled by its own identifier so a failure reads as
     * {@code COSGN00C case07} rather than as an index.
     *
     * @return one argument pair per case, in ordinal order
     */
    static List<Arguments> declaredCases() {
        List<ParityCase> loaded = cases();
        List<Arguments> arguments = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            arguments.add(Arguments.of(parityCase.caseId(), parityCase));
        }
        return arguments;
    }

    /** @return the twenty cases keyed by identifier, for the set-level assertions below */
    private static Map<String, ParityCase> casesById() {
        Map<String, ParityCase> byId = new LinkedHashMap<>();
        for (ParityCase parityCase : cases()) {
            byId.put(parityCase.caseId(), parityCase);
        }
        return byId;
    }

    // =============================================================================================
    // The gate.
    // =============================================================================================

    /**
     * The parity gate: every one of the twenty cases produces a diff count of exactly zero.
     *
     * <p>The whole {@link DiffResult} is rendered on failure rather than reduced to a count. The
     * differ compares record channels as complete sets in both directions, and compares the
     * navigation context, every screen send's fields and every send's attribute items in both
     * directions too - so it reports a field the case pinned and the run did not produce
     * <em>and</em> a field the run produced and the case did not pin. Both are real findings, and
     * the second is the one a lookup-based judge misses.
     *
     * @param caseId     the case identifier, for the test name
     * @param parityCase the case whose expectations are authoritative
     */
    @ParameterizedTest(name = "COSGN00C {0}")
    @MethodSource("declaredCases")
    @DisplayName("diff count is zero on all twenty cases")
    void diffCountIsZero(String caseId, ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();

        DiffResult result = harness.judge(parityCase, UnitKind.SERVICE,
                COSGN00CParityTest::invokeSignOn);

        assertThat(result.count())
                .as("%s: every difference is a parity failure, and the gate is a count of zero "
                        + "across all twenty cases rather than a majority of them.%n%s",
                        caseId, result.render())
                .isZero();
        assertThat(result.isClean())
                .as("%s: a clean result and a zero count must agree - they are two readings of one "
                        + "list.%n%s", caseId, result.render())
                .isTrue();
    }

    // =============================================================================================
    // How the unit is reached. Everything below constructs COSGN00C's translation and calls it; not
    // one line of it can see the expectation, because Invocation carries the inputs only.
    // =============================================================================================

    /**
     * Constructs {@link SignOnService}, runs {@code MAIN-PARA} once, and projects the screen through
     * {@link SignOnController}.
     *
     * <p>Two independent triples are built and run, and their outcomes are required to be equal.
     * That is the statelessness assertion stated per case rather than once: {@code COSGN00C} is a
     * pseudo-conversational transaction whose whole conversation state - the communication area, the
     * attention identifier and the two typed fields - arrives in the payload, so two independently
     * constructed instances handed the same payload must agree in every component. If either class
     * ever cached a user, a role or a screen between requests, the second run would differ and this
     * would say so.
     *
     * @param invocation the seeded dataset, the online request, the pinned clock, the codec and the
     *                   recorder
     * @return {@code null}, meaning the recorder holds the observation
     */
    private static UnitOutcome invokeSignOn(Invocation invocation) {
        SignOnRequest request = requestOf(invocation);

        Run first = run(invocation, request);
        Run second = run(invocation, request);

        assertThat(second.outcome())
                .as("%s: COSGN00C keeps no server-side state - CICS hands it the communication area, "
                        + "EIBAID and the received map, and nothing else - so two independently "
                        + "constructed services handed one payload must produce one outcome",
                        invocation.caseId())
                .isEqualTo(first.outcome());
        assertThat(second.screen())
                .as("%s: the projection is a pure function of the outcome, the pinned clock and the "
                        + "two ASSIGN substitutions, so it too must repeat exactly",
                        invocation.caseId())
                .isEqualTo(first.screen());

        assertRunInvariants(invocation, request, first);
        return record(invocation, first);
    }

    /**
     * One construction of the unit and one call through it.
     *
     * @param outcome the {@link SignOnOutcome} {@code MAIN-PARA} produced - the whole of it,
     *                including the eighty-byte {@code WS-MESSAGE} and the {@code WS-ERR-FLG} state
     *                that the ten-field screen projection cannot carry
     * @param screen  what {@link SignOnController} projected that outcome onto
     * @param reads   how many times {@code EXEC CICS READ} at {@code :211-219} was issued
     */
    private record Run(SignOnOutcome outcome, ScreenResponse<SignOnResponse> screen, int reads) {
    }

    /**
     * Builds a repository, a service and a controller, and calls the controller once.
     *
     * <p>The service is invoked exactly once, by the controller, through
     * {@link RecordingSignOnService} - which delegates to {@code super.handle} and keeps the answer.
     * Calling {@code handle} here as well and then calling the controller would run the decision
     * twice, and a decision run twice is a decision this file could no longer describe as "what
     * COSGN00C did".
     *
     * @param invocation the invocation
     * @param request    the received map, built once and shared by both runs
     * @return the outcome, the projection and the read count
     */
    private static Run run(Invocation invocation, SignOnRequest request) {
        int[] reads = new int[1];
        RecordingSignOnService service =
                new RecordingSignOnService(stubbedRepository(invocation, reads));
        SignOnController controller = new SignOnController(service, invocation.clock(), APPLID,
                SYSID, invocation.charset());

        ScreenResponse<SignOnResponse> screen = controller.signOn(request);

        return new Run(service.requireOutcome(), screen, reads[0]);
    }

    /**
     * {@link SignOnService} with a one-line override that keeps the outcome on its way out.
     *
     * <p>Not a mock and not a stub: {@code super.handle} <em>is</em> the unit under test, and every
     * decision it makes is made here. The override exists only because
     * {@link SignOnController#signOn(SignOnRequest)} returns the projection and discards the
     * outcome, while this gate has to compare both.
     *
     * <p>The captured field is an instance field of a throwaway per-run object, never static, so it
     * introduces no shared mutable state - and {@link #run(Invocation, SignOnRequest)} builds a
     * fresh one for each of its two runs.
     */
    private static final class RecordingSignOnService extends SignOnService {

        /** What {@code super.handle} answered, or {@code null} until it has been called. */
        private SignOnOutcome captured;

        /**
         * @param secUserRepository the fixture-backed {@code USRSEC}
         */
        RecordingSignOnService(SecUserRepository secUserRepository) {
            super(secUserRepository);
        }

        @Override
        public SignOnOutcome handle(SignOnInput input) {
            SignOnOutcome outcome = super.handle(input);
            captured = outcome;
            return outcome;
        }

        /**
         * @return the outcome of the single call
         * @throws IllegalStateException if the controller never delegated, which would mean the
         *                               projection describes a run that did not happen
         */
        SignOnOutcome requireOutcome() {
            if (captured == null) {
                throw new IllegalStateException("SignOnController.performSignOn calls "
                        + "SignOnService.handle unconditionally - it binds, delegates and projects, "
                        + "and makes no decision of its own - so a run that produced a screen "
                        + "without reaching handle means the controller answered from something "
                        + "other than the service");
            }
            return captured;
        }
    }

    // =============================================================================================
    // USRSEC. A stub rather than the real repository, because the real one reaches a data source and
    // this assertion is about what COSGN00C does with the answer, not about how the answer is
    // fetched.
    // =============================================================================================

    /**
     * The {@code USRSEC} dataset behind {@code EXEC CICS READ} - {@code COSGN00C:211-219}.
     *
     * <p>A faithful key-sequenced read rather than a constant: the key offered is matched against the
     * eight-byte {@code SEC-USR-ID} span of the seeded rows exactly, at the full
     * {@value SecUserRecord#KEY_LENGTH} the program passes as {@code KEYLENGTH}, so a key that is one
     * character short finds nothing and a key that differs in case finds nothing. Both are real
     * {@code DFHRESP(NOTFND)} outcomes, and both are exercised below - which is what makes the
     * {@code WHEN 13} arm at {@code :247} genuine rather than stipulated.
     *
     * <p>A case may override the outcome through {@code screenRequest.forcedOutcomes.read}, and one
     * of the twenty needs to: no arrangement of ten well-formed rows can make a keyed read report
     * anything other than {@code NORMAL} or {@code NOTFND}, so the {@code WHEN OTHER} arm at
     * {@code :252} is unreachable from the data alone. The override is taken only when the read is
     * actually reached, and the harness refuses a run that declared one and never asked for it.
     *
     * @param invocation the invocation, consulted for the seed and for a forced outcome
     * @param reads      a one-element counter incremented on each read, so the four paths that must
     *                   not read the file can be held to it
     * @return a repository whose {@code read} behaves as described
     */
    private static SecUserRepository stubbedRepository(Invocation invocation, int[] reads) {
        SeededDataset seeded = invocation.dataset(USRSEC);
        FixedWidthCodec codec = invocation.codec();
        SecUserRepository repository = mock(SecUserRepository.class);

        when(repository.read(anyString())).thenAnswer(call -> {
            reads[0]++;
            String ridfld = call.getArgument(0);
            if (invocation.hasForcedOutcome(RepositoryOperation.READ)) {
                return forced(invocation.forcedOutcome(RepositoryOperation.READ), seeded, codec,
                        ridfld);
            }
            return readByKey(seeded, codec, ridfld);
        });
        return repository;
    }

    /**
     * The ordinary keyed read: an exact match on {@code SEC-USR-ID}, or {@code NOTFND}.
     *
     * @param seeded the ten rows, already padded to the copybook's width by the harness
     * @param codec  the code page the rows are encoded in
     * @param ridfld {@code WS-USER-ID}, which {@code :132-133} has already upper-cased
     * @return the outcome the program's {@code EVALUATE WS-RESP-CD} branches on
     */
    private static ReadResult readByKey(SeededDataset seeded, FixedWidthCodec codec, String ridfld) {
        String key = codec.movePicX(ridfld, KEY_LENGTH);
        for (String row : seeded.rows()) {
            if (key.equals(row.substring(SecUserRecord.KEY_OFFSET,
                    SecUserRecord.KEY_OFFSET + KEY_LENGTH))) {
                return ReadResult.found(SecUserRecord.decode(
                        codec.encodeImage(row, "the SEC-USER-DATA row read from " + USRSEC), codec));
            }
        }
        return ReadResult.notFound();
    }

    /**
     * Translates a case's forced outcome into the {@link ReadResult} the repository would report.
     *
     * <p>Only three of the five {@link FileStatus.Outcome} constants can come back from
     * {@code EXEC CICS READ} against a base KSDS with no {@code UPDATE} option. End-of-file belongs
     * to a browse, and {@code DUPKEY} is raised on an alternate index - {@code USRSEC} has none, as
     * {@code app/csd/CARDDEMO.CSD} shows by defining no path over it. Forcing either would describe a
     * response CICS cannot produce here, so both are refused rather than quietly mapped.
     *
     * @param outcome the outcome the case forces
     * @param seeded  the seeded rows, so a forced {@code OK} still returns the real record
     * @param codec   the code page
     * @param ridfld  the key offered
     * @return the corresponding read result
     * @throws IllegalArgumentException if the case forces an outcome a keyed read cannot produce, or
     *                                  states a {@code RESP} that contradicts the outcome it names
     */
    private static ReadResult forced(ForcedOutcome outcome, SeededDataset seeded,
                                     FixedWidthCodec codec, String ridfld) {
        switch (outcome.outcome()) {
            case OK:
                requireForcedResp(outcome, SignOnService.RESP_NORMAL);
                return readByKey(seeded, codec, ridfld);
            case NOT_FOUND:
                requireForcedResp(outcome, SignOnService.RESP_NOTFND);
                return ReadResult.notFound();
            case OTHER:
                // The WHEN OTHER arm at :252 is "every response the source did not enumerate", so a
                // forced OTHER reports no RESP at all rather than some particular other number: a
                // stated value would suggest the arm distinguishes them, and :252-256 does not.
                requireNoForcedResp(outcome);
                return ReadResult.of(SecUserRepository.PERMANENT_ERROR_STATUS, CicsResponse.none());
            case END_OF_FILE:
            case DUPLICATE:
            default:
                throw new IllegalArgumentException("A case forces " + outcome.outcome()
                        + " on the read against " + USRSEC + ", which EXEC CICS READ at "
                        + "app/cbl/COSGN00C.cbl:211-219 cannot report: end-of-file belongs to a "
                        + "browse, and DUPKEY is raised on an alternate index, of which USRSEC has "
                        + "none. Force OK, NOT_FOUND or OTHER - :221-257 has an arm for all three.");
        }
    }

    /**
     * Requires a forced outcome's declared {@code RESP}, if it states one, to be the value
     * {@code EVALUATE WS-RESP-CD} names for that arm.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:221-257} branches on raw numeric literals - {@code WHEN 0} and
     * {@code WHEN 13} - so a case that named a different number while claiming that arm would be
     * describing a different program.
     *
     * @param outcome  the forced outcome
     * @param expected the {@code RESP} the arm is written against
     * @throws IllegalArgumentException if the case states a contradictory {@code RESP}
     */
    private static void requireForcedResp(ForcedOutcome outcome, int expected) {
        if (outcome.resp() != null && outcome.resp() != expected) {
            throw new IllegalArgumentException("A case forces " + outcome.outcome()
                    + " on the read against " + USRSEC + " while declaring RESP " + outcome.resp()
                    + ". app/cbl/COSGN00C.cbl:221-257 evaluates WS-RESP-CD against the literals 0 "
                    + "and 13, so that arm is RESP " + expected + " and no other number reaches it.");
        }
    }

    /**
     * Requires a forced {@code OTHER} to state no {@code RESP}.
     *
     * @param outcome the forced outcome
     * @throws IllegalArgumentException if the case states a {@code RESP}
     */
    private static void requireNoForcedResp(ForcedOutcome outcome) {
        if (outcome.resp() != null) {
            throw new IllegalArgumentException("A case forces OTHER on the read against " + USRSEC
                    + " while declaring RESP " + outcome.resp() + ". The WHEN OTHER arm at "
                    + "app/cbl/COSGN00C.cbl:252 is every response the two preceding arms did not "
                    + "name, and it treats them all alike, so pinning one number would assert a "
                    + "distinction the program does not make.");
        }
    }

    // =============================================================================================
    // The received map and the communication area, as CICS delivered them.
    // =============================================================================================

    /**
     * The payload, as {@code EXEC CICS RECEIVE MAP} at {@code COSGN00C:110-115} and the
     * {@code DFHCOMMAREA} at {@code :65-67} together delivered it.
     *
     * <p>Each of the eleven {@code xxxI} items is taken from the case's declared map fields, and a
     * field the case omitted is passed as {@code null} - which the service treats as a field never
     * transmitted, that is {@code LOW-VALUES}. That distinction is load-bearing: the blank-field
     * chain at {@code :118} and {@code :123} tests {@code = SPACES OR LOW-VALUES}, two comparisons
     * against figurative constants, so "eight spaces" and "never typed" reach the same arm by
     * different routes and both are exercised below.
     *
     * <p>A case declaring {@code eibcalen} of zero is given a {@code null} communication area, which
     * is precisely what {@code EIBCALEN = 0} means at {@code :80}; the ten seeded rows are still
     * present, because a dataset the program never opens is a dataset whose unchanged state is the
     * assertion.
     *
     * @param invocation the invocation carrying the declared map fields, commarea and key
     * @return the request; never {@code null}
     */
    private static SignOnRequest requestOf(Invocation invocation) {
        Map<String, String> fields = invocation.mapFields();
        return new SignOnRequest(fields.get("TRNNAMEI"),
                fields.get("TITLE01I"),
                fields.get("CURDATEI"),
                fields.get("PGMNAMEI"),
                fields.get("TITLE02I"),
                fields.get("CURTIMEI"),
                fields.get("APPLIDI"),
                fields.get("SYSIDI"),
                fields.get(USERID_INPUT_ITEM),
                fields.get(PASSWD_INPUT_ITEM),
                fields.get("ERRMSGI"),
                commareaOf(invocation),
                aidTokenOf(invocation.aid()));
    }

    /**
     * {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} - the area {@code :80} measures and
     * {@code :100} hands back.
     *
     * <p>Built by writing the case's declared field images into a
     * {@value NavigationContext#COMMAREA_LENGTH}-byte area and decoding it back, so the context the
     * program adopts is a real communication-area image rather than a hand-assembled object. A field
     * the case leaves unstated keeps the area's initial state - spaces in a character span, zeros in a
     * {@code PIC 9} one - which is what {@code WORKING-STORAGE} holds.
     *
     * @param invocation the invocation
     * @return the context, or {@code null} when {@code EIBCALEN} is zero
     */
    private static NavigationContext commareaOf(Invocation invocation) {
        if (invocation.eibcalen() == 0) {
            return null;
        }
        FixedWidthCodec codec = invocation.codec();
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, invocation.commarea()));
    }

    /**
     * Resolves a declared {@code DFHAID} mnemonic to the {@code CVCRD01Y} token the payload carries.
     *
     * <p>The case names the key the way {@code app/cbl/COSGN00C.cbl:85-91} names it, as a
     * {@code DFHAID} mnemonic; the JSON payload carries the {@code CCARD-AID} token, because a byte
     * is not a JSON value. The mnemonic is therefore turned into its byte and the byte into its
     * token, and {@link #assertRunInvariants(Invocation, SignOnRequest, Run)} then requires the byte
     * the controller resolved back out of that token to agree with the one the case declared - so the
     * round trip is proved rather than assumed.
     *
     * @param mnemonic the mnemonic the case declared, or {@code null} for the path that never reads
     *                 {@code EIBAID}
     * @return the token, or {@code null} for that path
     * @throws IllegalArgumentException if the mnemonic names a byte {@link PfKeyResolver} maps to no
     *                                  token, which cannot travel in a payload at all
     */
    private static String aidTokenOf(String mnemonic) {
        if (mnemonic == null) {
            return null;
        }
        byte aid = aidByteOf(mnemonic);
        return PfKeyResolver.resolve(aid)
                .orElseThrow(() -> new IllegalArgumentException(mnemonic + " resolves to a byte that "
                        + "app/cpy/CSSTRPFY.cpy maps to no CCARD-AID token, so it cannot be carried "
                        + "in a JSON payload and cannot be driven through the controller. The "
                        + "WHEN OTHER arm at app/cbl/COSGN00C.cbl:91 is reachable with any key that "
                        + "is neither ENTER nor PF3 - DFHCLEAR and DFHPF4 both do it - so no case "
                        + "needs an unmapped byte."))
                .token();
    }

    /**
     * Resolves a declared {@code DFHAID} mnemonic to the byte {@code COSGN00C:85} evaluates.
     *
     * @param mnemonic the mnemonic the case declared, or {@code null}
     * @return the attention identifier byte
     * @throws IllegalArgumentException if the mnemonic names no reproduced {@code DFHAID} constant
     */
    private static byte aidByteOf(String mnemonic) {
        if (mnemonic == null) {
            return NO_AID;
        }
        Byte aid = AID_BYTES.get(mnemonic);
        if (aid == null) {
            throw new IllegalArgumentException('"' + mnemonic + "\" names no DFHAID constant. "
                    + "DFHAID is IBM-supplied and absent from this repository, so common.CicsAid is "
                    + "the single reproduction of it and the permitted mnemonics come from there.");
        }
        return aid;
    }

    // =============================================================================================
    // What the run observably produced.
    // =============================================================================================

    /**
     * Records the run into the harness's recorder: the state {@code USRSEC} was left in, the online
     * response, the {@code RETURN-CODE} and - on one path only - the line a terminal received.
     *
     * <p>Nothing is recorded on the write channel, and that is an assertion rather than an omission:
     * {@code app/cbl/COSGN00C.cbl} contains no {@code WRITE}, no {@code REWRITE} and no
     * {@code DELETE} at all. The final-state channel is answered on <em>every</em> path including the
     * four that never open the dataset, where the honest answer is "exactly as seeded" - which is the
     * positive form of the assertion that nothing was written.
     *
     * <p>The message channels are used on the {@code DFHPF3} path alone. That path is the program's
     * only {@code EXEC CICS SEND TEXT}, at {@code :164-169}, and it transmits
     * {@code FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE)} - eighty bytes of unformatted text, which
     * is a line a terminal received and therefore a message. Every other path transmits through
     * {@code SEND MAP}, whose payload is the map's fields and is recorded as a screen send; there is
     * no {@code DISPLAY} anywhere in the program, so nothing else is ever emitted.
     *
     * @param invocation the invocation, for the recorder and the seed
     * @param run        the run to record
     * @return the recorder's build, which is the last thing this method does
     */
    private static UnitOutcome record(Invocation invocation, Run run) {
        UnitOutcome.Builder recorder = invocation.recorder();

        recorder.finalStateUnchanged(invocation.dataset(USRSEC), SecUserRecord.LAYOUT);

        if (run.outcome().plainTextSent()) {
            recorder.message(new EmittedMessage(MessageChannel.WS_MESSAGE_80,
                    run.outcome().message()));
        } else {
            recorder.response(observed(invocation, run));
        }

        // An online transaction sets no RETURN-CODE: COSGN00C has no CALL 'CEE3ABD' and no MOVE to
        // RETURN-CODE anywhere, so zero is stated as the observation rather than left to a default.
        recorder.returnCode(0);
        return recorder.build();
    }

    /**
     * Projects the run onto the response the differ judges.
     *
     * @param invocation the invocation, for the codec and the received map
     * @param run        the run
     * @return the observation
     */
    private static ObservedResponse observed(Invocation invocation, Run run) {
        SignOnResponse screen = run.screen().screen();
        return new ObservedResponse(named(screen.nextProgram()),
                named(screen.nextMapset()),
                named(screen.nextMap()),
                navigationOf(invocation.codec(), screen.navigationContext()),
                sendsOf(invocation, run),
                cursorItemOf(run.screen().screenMetadata().cursorField()),
                terminationOf(run.outcome()));
    }

    /**
     * The communication area as sixteen named field images.
     *
     * <p>Rendered through {@link NavigationContext#LAYOUT} rather than by reading the record's
     * components, so every value is the image the field actually occupies - {@code CDEMO-CUST-ID} as
     * nine digits, {@code CDEMO-CARD-NUM} as sixteen - at the copybook's declared width. The layout
     * totals {@value NavigationContext#COMMAREA_LENGTH} bytes and declares no {@code FILLER}, so all
     * sixteen fields are addressable and every one of them is compared.
     *
     * @param codec    the code page
     * @param commarea the context the program is carrying forward
     * @return field name to image, in copybook order
     */
    private static Map<String, String> navigationOf(FixedWidthCodec codec,
                                                    NavigationContext commarea) {
        return codec.deserialise(NavigationContext.LAYOUT, commarea.toFixedWidth(codec));
    }

    /**
     * Every {@code EXEC CICS SEND MAP} the run performed, in order.
     *
     * <p>{@code COSGN00C} sends the map <strong>at most once</strong> per invocation, and that is a
     * property of the source rather than an assumption: {@code SEND-SIGNON-SCREEN} is performed from
     * exactly one site on each of the paths that reach it - {@code :83}, {@code :94}, {@code :122},
     * {@code :127}, {@code :245}, {@code :251} and {@code :256} - and each of those sites is terminal
     * within its branch, while the two paths that transfer or send text reach none of them.
     *
     * @param invocation the invocation, for the received credential-entry items
     * @param run        the run
     * @return zero or one send
     */
    private static List<ObservedSend> sendsOf(Invocation invocation, Run run) {
        if (!run.outcome().screenPainted()) {
            return List.of();
        }
        // attributes is empty, and deliberately so. COSGN00C copies neither CSSETATY - whose only
        // consumer is COACTUPC - nor, in effect, DFHATTR: app/cbl/COSGN00C.cbl:59 is *COPY DFHATTR.
        // with the asterisk in column 7, which makes it a comment. The program assigns no xxxC, xxxP,
        // xxxH or xxxV item anywhere, so no runtime attribute is observable. See
        // theRedEmphasisIsAStaticMapProperty().
        return List.of(ObservedSend.ofFields(screenFieldsOf(invocation, run)));
    }

    /**
     * The eleven named output items of mapset {@code COSGN00}, in symbolic-map order.
     *
     * <p>Nine come straight from the projection. The two that do not are {@code USERIDO} and
     * {@code PASSWDO}, and the reason is the map's own storage model:
     * {@code app/cpy-bms/COSGN00.CPY:85} declares {@code 01 COSGN0AO REDEFINES COSGN0AI}, and within
     * each field the input view spends {@code xxxL} plus {@code xxxF} plus a four-byte {@code FILLER}
     * - seven bytes - exactly where the output view spends a three-byte {@code FILLER} plus
     * {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV}. The two views therefore place
     * {@code xxxI} and {@code xxxO} on the same bytes at the same width. No paragraph of
     * {@code COSGN00C} moves anything into {@code USERIDO} or {@code PASSWDO} - the program reads
     * {@code USERIDI} at {@code :118} and {@code :132} and {@code PASSWDI} at {@code :123} and
     * {@code :135}, and repositions the cursor through the length items - so what those two output
     * items hold at {@code SEND} time is whatever last occupied that storage.
     *
     * <p>Which is one of two things, and only two. After {@code MOVE LOW-VALUES TO COSGN0AO} at
     * {@code :81} both are {@code X'00'} eight times over; otherwise both hold what
     * {@code RECEIVE MAP} left there, which is the transmitted image, or {@code LOW-VALUES} again for
     * a field the terminal did not transmit.
     *
     * <p>{@link SignOnResponse} carries neither item - {@link SignOnResponse#OMITTED_ITEM} names
     * {@code PASSWDO} as the deliberate omission, and there is no {@code withUserId} for the other -
     * so the payload never echoes a credential back over JSON. That is a property of the projection,
     * not of the screen, and this method is where the difference is stated.
     *
     * @param invocation the invocation, for the received map
     * @param run        the run
     * @return item name to image, in symbolic-map order
     */
    private static Map<String, String> screenFieldsOf(Invocation invocation, Run run) {
        SignOnResponse screen = run.screen().screen();
        boolean reset = run.screen().screenMetadata().resetAllOutputFields();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(SignOnResponse.TRNNAME_FIELD, screen.trnName());
        fields.put(SignOnResponse.TITLE01_FIELD, screen.title01());
        fields.put(SignOnResponse.CURDATE_FIELD, screen.curDate());
        fields.put(SignOnResponse.PGMNAME_FIELD, screen.pgmName());
        fields.put(SignOnResponse.TITLE02_FIELD, screen.title02());
        fields.put(SignOnResponse.CURTIME_FIELD, screen.curTime());
        fields.put(SignOnResponse.APPLID_FIELD, screen.applId());
        fields.put(SignOnResponse.SYSID_FIELD, screen.sysId());
        fields.put(SignOnResponse.USERID_FIELD,
                credentialItemImage(invocation, USERID_INPUT_ITEM, reset,
                        SignOnResponse.USERID_LENGTH));
        fields.put(PASSWD_OUTPUT_ITEM,
                credentialItemImage(invocation, PASSWD_INPUT_ITEM, reset,
                        SignOnRequest.PASSWD_LENGTH));
        fields.put(SignOnResponse.ERRMSG_FIELD, screen.errMsg());
        return fields;
    }

    /**
     * The image one credential-entry output item holds when the map is sent.
     *
     * @param invocation the invocation, for the received map
     * @param inputItem  the paired {@code xxxI} item, which shares the storage
     * @param reset      whether {@code MOVE LOW-VALUES TO COSGN0AO} at {@code :81} ran
     * @param width      the item's declared {@code PICTURE} width
     * @return the eight-character image
     */
    private static String credentialItemImage(Invocation invocation, String inputItem, boolean reset,
                                              int width) {
        if (reset) {
            return lowValues(width);
        }
        String received = invocation.mapFields().get(inputItem);
        return received == null
                ? lowValues(width)
                : invocation.codec().movePicX(received, width);
    }

    /**
     * Maps the {@code DFHMDF} label {@link ScreenMetadata} reports onto the symbolic-map length item
     * that received the {@code MOVE -1}.
     *
     * @param label the label, or {@code null} on a path that positions no cursor
     * @return the {@code xxxL} item name, or {@code null}
     * @throws IllegalStateException if the projection named a field that is not a cursor target
     */
    private static String cursorItemOf(String label) {
        if (label == null) {
            return null;
        }
        String item = CURSOR_ITEMS.get(label);
        if (item == null) {
            throw new IllegalStateException("The projection positioned the cursor on \"" + label
                    + "\", which is not one of the two fields app/cbl/COSGN00C.cbl moves -1 into. "
                    + "The program has exactly two cursor targets: USERIDL at :82, :121, :250 and "
                    + ":255, and PASSWDL at :126 and :244.");
        }
        return item;
    }

    /**
     * How the transaction ended, in the two forms a {@link ParityCase} can pin.
     *
     * <p>{@code EXEC CICS XCTL} at {@code :231-239} transfers control and does not come back, so the
     * {@code EXEC CICS RETURN TRANSID} at {@code :98-102} that follows it in the source is not
     * reached. The two are not interchangeable, which is why the differ compares them.
     *
     * @param outcome the outcome
     * @return the termination
     * @throws IllegalStateException if the run ended by the bare {@code RETURN} at {@code :171},
     *                               which has no response to carry a termination on
     */
    private static Termination terminationOf(SignOnOutcome outcome) {
        switch (outcome.termination()) {
            case XCTL:
                return Termination.XCTL;
            case RETURN_TRANSID:
                return Termination.RETURN_TRANSID;
            case RETURN_NO_TRANSID:
            default:
                throw new IllegalStateException("SEND-PLAIN-TEXT at app/cbl/COSGN00C.cbl:162-172 "
                        + "ends with a bare EXEC CICS RETURN carrying no TRANSID and no COMMAREA, so "
                        + "the conversation ends and there is no screen response to describe. That "
                        + "path is recorded as an emitted eighty-byte line instead, and must not "
                        + "reach this method.");
        }
    }

    // =============================================================================================
    // Per-run assertions. These state what the differ cannot see, because the differ compares the
    // observation against the case and these compare the observation against itself.
    // =============================================================================================

    /**
     * Everything one run must be internally consistent about, whatever arm it took.
     *
     * <p>An {@link AssertionError} raised here is an {@code Error} rather than an {@code Exception},
     * so {@link ParityHarness#run} lets it through untouched and the failure reads at its own call
     * site rather than as "the case raised something that is not an abend".
     *
     * @param invocation the invocation
     * @param request    the payload the run was driven with
     * @param run        the run
     */
    private static void assertRunInvariants(Invocation invocation, SignOnRequest request, Run run) {
        SignOnOutcome outcome = run.outcome();
        SignOnResponse screen = run.screen().screen();
        String caseId = invocation.caseId();

        assertAttentionIdentifierSurvivedTheRoundTrip(invocation, request, outcome);
        assertExactlyOneExit(caseId, outcome);
        assertRoleAndTargetAgree(caseId, outcome, screen);
        assertMessageNarrowedFromEightyToSeventyEight(caseId, outcome, screen);
        assertTheFileWasReadOnlyWhereTheSourceReadsIt(caseId, outcome, run.reads());
        assertTheCommareaIsOneHundredAndSixtyBytes(caseId, invocation, screen);
        assertTheErrorFlagFollowsTheSourceExactly(caseId, outcome);
    }

    /**
     * {@code WS-ERR-FLG} is raised on exactly the paths that raise it in the source - and the
     * wrong-password path is <strong>not</strong> one of them.
     *
     * <p>This is the program's most easily "corrected" quirk. Five paths move {@code 'Y'} into
     * {@code WS-ERR-FLG}: the invalid-key arm at {@code :92}, the blank user id at {@code :119}, the
     * blank password at {@code :124}, the not-found arm at {@code :248} and the unexpected-response
     * arm at {@code :253}. The wrong-password arm at {@code :241-246} moves the message, moves the
     * cursor and paints the screen - and sets no flag. Nothing downstream reads the flag by then,
     * because {@code :138} has already been evaluated, so the omission changes no outcome and is
     * invisible to every other assertion in this gate. That is exactly why it is asserted here: an
     * invisible inconsistency is the kind a translation harmonises for tidiness, and preserving
     * legacy behaviour means preserving it including its defects.
     *
     * <p>The expected flag is derived from the eighty-byte {@code WS-MESSAGE}, which identifies the
     * path uniquely, so the two are cross-checked rather than restated: nine message states partition
     * the program's paths, and each one fixes the flag.
     *
     * @param caseId  the case identifier
     * @param outcome the outcome, carrying both the message and the flag
     */
    private static void assertTheErrorFlagFollowsTheSourceExactly(String caseId,
                                                                  SignOnOutcome outcome) {
        String message = outcome.message();
        boolean raisedBySource = message.equals(padded(SignOnService.MSG_ENTER_USER_ID))      // :119
                || message.equals(padded(SignOnService.MSG_ENTER_PASSWORD))                   // :124
                || message.equals(padded(SignOnService.MSG_USER_NOT_FOUND))                   // :248
                || message.equals(padded(SignOnService.MSG_UNABLE_TO_VERIFY))                 // :253
                || message.equals(padded(SystemMessages.CCDA_MSG_INVALID_KEY));               // :92

        if (message.equals(padded(SignOnService.MSG_WRONG_PASSWORD))) {
            assertThat(outcome.errorFlag())
                    .as("%s: :241-246 moves the message, moves -1 into PASSWDL and paints the "
                            + "screen, and sets NO flag - every other rejecting path in the program "
                            + "sets one. The asymmetry is real, it changes no outcome because :138 "
                            + "has already been evaluated, and it is preserved rather than "
                            + "harmonised", caseId)
                    .isFalse();
            assertThat(outcome.errFlgImage())
                    .as("%s: so the flag still reads as its initialised value from :75", caseId)
                    .isEqualTo(SignOnService.ERR_FLG_OFF);
            return;
        }

        assertThat(outcome.errorFlag())
                .as("%s: WS-ERR-FLG is 'Y' on exactly the five paths that move it - :92, :119, :124, "
                        + ":248 and :253 - and stays at the SET ERR-FLG-OFF of :75 everywhere else, "
                        + "which is the cold start at :80-83, the PF3 sign-off at :88-90 and the "
                        + "signing-on path at :223-239", caseId)
                .isEqualTo(raisedBySource);
        assertThat(outcome.errFlgImage())
                .as("%s: and the 88-level image agrees with the boolean, because they are two "
                        + "readings of one field", caseId)
                .isEqualTo(raisedBySource ? SignOnService.ERR_FLG_ON : SignOnService.ERR_FLG_OFF);
    }

    /**
     * The byte the controller resolved is the byte the case declared.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:85} is {@code EVALUATE EIBAID} over the raw byte, while a JSON
     * payload can only carry the {@code CCARD-AID} token. {@code app/cpy/CSSTRPFY.cpy} folds
     * {@code PF13} through {@code PF24} onto {@code PF1} through {@code PF12}, so a token round trip
     * is lossy for a folded key and would silently drive {@code DFHPF3} for a case that named
     * {@code DFHPF15}. Requiring the resolved token to be the one the declared byte resolves to is
     * what proves no fold happened.
     *
     * @param invocation the invocation carrying the declared mnemonic
     * @param request    the payload built from it
     * @param outcome    the outcome, which carries the token the service resolved
     */
    private static void assertAttentionIdentifierSurvivedTheRoundTrip(Invocation invocation,
                                                                     SignOnRequest request,
                                                                     SignOnOutcome outcome) {
        assertThat(outcome.resolvedAid())
                .as("%s: the case declares %s and the payload carries %s, and the byte the "
                        + "controller resolved back out of that token has to be the one the case "
                        + "named - CSSTRPFY folds PF13..PF24 onto PF1..PF12, so a round trip that "
                        + "was not checked could drive the wrong arm of EVALUATE EIBAID",
                        invocation.caseId(), invocation.aid(), request.aid())
                .isEqualTo(PfKeyResolver.resolve(aidByteOf(invocation.aid())));
    }

    /**
     * Exactly one of the three exits happened.
     *
     * <p>{@code MAIN-PARA} reaches a terminal action on every path and never two of them: the map
     * send at {@code :151-157}, the unformatted text at {@code :164-169} or the transfer at
     * {@code :231-239}. A run reporting two would mean the field values recorded below belong to one
     * of them while the other's payload has been lost.
     *
     * @param caseId  the case identifier
     * @param outcome the outcome
     */
    private static void assertExactlyOneExit(String caseId, SignOnOutcome outcome) {
        int exits = (outcome.screenPainted() ? 1 : 0)
                + (outcome.plainTextSent() ? 1 : 0)
                + (outcome.signedOn() ? 1 : 0);
        assertThat(exits)
                .as("%s: COSGN00C either sends the map (:151), sends unformatted text (:164) or "
                        + "transfers control (:231) - exactly one, on every path", caseId)
                .isOne();
    }

    /**
     * Gate {@code G40}: the role and the transfer target agree, and no forward happens.
     *
     * <p>{@code :230} tests {@code 88 CDEMO-USRTYP-ADMIN} and nothing else, so the target is a
     * function of the one character {@code :227} moved out of {@code SEC-USR-TYPE}. The response
     * carries both, and the client resolves the navigation.
     *
     * @param caseId  the case identifier
     * @param outcome the outcome
     * @param screen  the projection
     */
    private static void assertRoleAndTargetAgree(String caseId, SignOnOutcome outcome,
                                                 SignOnResponse screen) {
        assertThat(screen.nextProgram())
                .as("%s: the XCTL target is a response field, so the projection must carry exactly "
                        + "what the service decided", caseId)
                .isEqualTo(outcome.nextProgram());
        assertThat(screen.role())
                .as("%s: CDEMO-USER-TYPE travels to the client as the role", caseId)
                .isEqualTo(outcome.role());

        if (!outcome.signedOn()) {
            assertThat(screen.nextProgram())
                    .as("%s: no sign-on, so :231-239 was never reached and no program is named",
                            caseId)
                    .isBlank();
            return;
        }
        assertThat(screen.nextProgram().strip())
                .as("%s: :230 routes on CDEMO-USRTYP-ADMIN alone - PROGRAM('COADM01C') at :232 for "
                        + "'A' and PROGRAM('COMEN01C') at :237 for anything else", caseId)
                .isEqualTo(outcome.isAdminRole()
                        ? SignOnResponse.NEXT_PROGRAM_ADMIN
                        : SignOnResponse.NEXT_PROGRAM_USER);
        assertThat(screen.nextMapset())
                .as("%s: an XCTL sends no map, so the mapset the response names is blank - the "
                        + "client is being sent to a program, not to a screen", caseId)
                .isBlank();
        assertThat(screen.nextMap())
                .as("%s: and neither is a map named", caseId)
                .isBlank();
    }

    /**
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COSGN0AO} at {@code :149} narrows
     * {@value #WS_MESSAGE_LENGTH} bytes to {@value #ERRMSG_LENGTH}, truncating on the right.
     *
     * <p>Asserted as a relationship between the two widths rather than as two literals, so a
     * translation that truncated on the left - or that did not truncate at all - fails here whatever
     * the message happens to be.
     *
     * @param caseId  the case identifier
     * @param outcome the outcome, carrying the eighty-byte {@code WS-MESSAGE}
     * @param screen  the projection, carrying the seventy-eight-byte {@code ERRMSGO}
     */
    private static void assertMessageNarrowedFromEightyToSeventyEight(String caseId,
                                                                      SignOnOutcome outcome,
                                                                      SignOnResponse screen) {
        assertThat(outcome.message())
                .as("%s: WS-MESSAGE is PIC X(80) - app/cbl/COSGN00C.cbl:38", caseId)
                .hasSize(WS_MESSAGE_LENGTH);
        assertThat(screen.errMsg())
                .as("%s: ERRMSGO is PIC X(78) - app/cpy-bms/COSGN00.CPY:152", caseId)
                .hasSize(ERRMSG_LENGTH);
        assertThat(screen.errMsg())
                .as("%s: COBOL truncates an alphanumeric MOVE on the right, so the narrower receiver "
                        + "keeps the leading bytes and drops the trailing two", caseId)
                .isEqualTo(outcome.message().substring(0, ERRMSG_LENGTH));
    }

    /**
     * Gate {@code G47}: {@code EXEC CICS READ} was issued exactly where the source issues it.
     *
     * <p>{@code READ-USER-SEC-FILE} is performed from one site, {@code :139}, which {@code :138}
     * guards with {@code IF NOT ERR-FLG-ON}. So a run has a read outcome if and only if it read the
     * file, and it read it at most once. Tying the observable classification to the call count is
     * what stops a translation from reading twice, or from reporting a classification it never
     * obtained.
     *
     * @param caseId  the case identifier
     * @param outcome the outcome
     * @param reads   how many reads the stub served
     */
    private static void assertTheFileWasReadOnlyWhereTheSourceReadsIt(String caseId,
                                                                      SignOnOutcome outcome,
                                                                      int reads) {
        assertThat(reads)
                .as("%s: READ-USER-SEC-FILE is performed once, from :139, and only when :138 finds "
                        + "the error flag off - so a run reads USRSEC once or not at all", caseId)
                .isEqualTo(outcome.readOutcome().isPresent() ? 1 : 0);
    }

    /**
     * Gate {@code G37}: the whole communication area travels in the payload, at its declared width.
     *
     * @param caseId     the case identifier
     * @param invocation the invocation, for the codec
     * @param screen     the projection
     */
    private static void assertTheCommareaIsOneHundredAndSixtyBytes(String caseId,
                                                                   Invocation invocation,
                                                                   SignOnResponse screen) {
        assertThat(screen.navigationContext().toFixedWidth(invocation.codec()))
                .as("%s: CARDDEMO-COMMAREA is %d bytes - app/cpy/COCOM01Y.cpy:19-44 - and it is "
                        + "carried in the response body, never in a session", caseId,
                        NavigationContext.COMMAREA_LENGTH)
                .hasSize(NavigationContext.COMMAREA_LENGTH);
        assertThat(navigationOf(invocation.codec(), screen.navigationContext()))
                .as("%s: all sixteen copybook fields are addressable, so all sixteen are compared",
                        caseId)
                .containsOnlyKeys(COMMAREA_FIELDS.toArray(new String[0]));
    }

    // =============================================================================================
    // Small pure helpers. Each states one COBOL primitive or one derived table.
    // =============================================================================================

    /**
     * @param width the field width
     * @return {@code LOW-VALUES} at that width - {@code X'00'} repeated, never spaces
     */
    private static String lowValues(int width) {
        return String.valueOf(LOW_VALUE).repeat(width);
    }

    /**
     * {@code MOVE <literal> TO WS-MESSAGE} where the sender is narrower than
     * {@value #WS_MESSAGE_LENGTH}.
     *
     * @param text the literal
     * @return the literal left-justified in an eighty-character field, space-filled on the right
     */
    private static String padded(String text) {
        Objects.requireNonNull(text, "A message literal is required");
        return text + String.valueOf(SPACE).repeat(WS_MESSAGE_LENGTH - text.length());
    }

    /**
     * @param value a fixed-width image
     * @return {@code null} when the image is blank, otherwise the image with padding removed
     */
    private static String named(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Inverts {@link CicsAid#mnemonicsByAid()} once, at class initialisation.
     *
     * @return mnemonic to byte, unmodifiable
     */
    private static Map<String, Byte> aidBytesByMnemonic() {
        Map<String, Byte> byMnemonic = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            byMnemonic.put(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(byMnemonic);
    }

    /**
     * Pairs the two cursor labels the projection reports with the two length items the source moves
     * {@code -1} into.
     *
     * <p>Built from both production spellings rather than from literals, so the {@code DFHMDF} label
     * and the {@code xxxL} item name cannot drift apart unnoticed. The pairing is checked as it is
     * built: an item that is not its label plus {@value #LENGTH_ITEM_SUFFIX} fails here.
     *
     * @return label to length item, unmodifiable
     * @throws IllegalStateException if a label and its length item do not correspond
     */
    private static Map<String, String> cursorItemsByLabel() {
        Map<String, String> byLabel = new LinkedHashMap<>();
        byLabel.put(SignOnController.CURSOR_USERID,
                requireLengthItemOf(SignOnController.CURSOR_USERID, CursorField.USER_ID));
        byLabel.put(SignOnController.CURSOR_PASSWD,
                requireLengthItemOf(SignOnController.CURSOR_PASSWD, CursorField.PASSWORD));
        return Collections.unmodifiableMap(byLabel);
    }

    /**
     * @param label  the {@code DFHMDF} label
     * @param cursor the service's cursor target
     * @return the {@code xxxL} item name
     * @throws IllegalStateException if the two do not correspond
     */
    private static String requireLengthItemOf(String label, CursorField cursor) {
        String item = cursor.lengthItemName().orElseThrow(() -> new IllegalStateException(
                "CursorField." + cursor + " names no length item, yet SignOnController reports \""
                        + label + "\" as a cursor target; CursorField.NONE is the only value that "
                        + "positions nothing"));
        if (!item.equals(label + LENGTH_ITEM_SUFFIX)) {
            throw new IllegalStateException("SignOnController reports the cursor as \"" + label
                    + "\" while CursorField." + cursor + " names the length item \"" + item
                    + "\". app/cpy-bms/COSGN00.CPY spells the length item as the DFHMDF label plus "
                    + LENGTH_ITEM_SUFFIX + ", so the two spellings have diverged.");
        }
        return item;
    }

    /**
     * Composes the eleven named output items from the ten the projection carries plus the one it
     * deliberately omits.
     *
     * @return the item names in symbolic-map order, unmodifiable
     */
    private static List<String> screenItems() {
        List<String> projected = SignOnResponse.MAP_FIELDS;
        List<String> items = new ArrayList<>(projected.size() + 1);
        for (String item : projected) {
            if (SignOnResponse.ERRMSG_FIELD.equals(item)) {
                // app/cpy-bms/COSGN00.CPY:141-146 puts PASSWDO between USERIDO and ERRMSGO.
                items.add(PASSWD_OUTPUT_ITEM);
            }
            items.add(item);
        }
        return Collections.unmodifiableList(items);
    }

    /**
     * @param parityCase the case
     * @return the single {@link ScreenSend} the case pins, or {@code null} when it pins none
     */
    private static ScreenSend sendOf(ParityCase parityCase) {
        if (parityCase.expectedResponse() == null
                || parityCase.expectedResponse().sends().isEmpty()) {
            return null;
        }
        assertThat(parityCase.expectedResponse().sends())
                .as("%s: COSGN00C sends the map at most once per invocation",
                        parityCase.caseId())
                .hasSize(1);
        return parityCase.expectedResponse().sends().get(0);
    }

    /**
     * @param parityCase the case
     * @return the {@code ERRMSGO} image the case pins, or {@code null} when it pins no send
     */
    private static String errMsgOf(ParityCase parityCase) {
        ScreenSend send = sendOf(parityCase);
        return send == null ? null : send.fields().get(SignOnResponse.ERRMSG_FIELD);
    }

    /**
     * @param parityCase the case
     * @param field      a {@code CARDDEMO-COMMAREA} field name
     * @return the image the case pins for it
     */
    private static String navigationOf(ParityCase parityCase, String field) {
        return parityCase.expectedResponse().navigation().get(field);
    }

    // =============================================================================================
    // Section - structural checks on the case set itself. Each states a property of the twenty cases
    // as a SET, which no single case can state about itself, and a gate whose fixtures nobody
    // validates is a gate that can be weakened by editing a fixture.
    // =============================================================================================

    /**
     * Gate {@code G15}: exactly twenty cases, named {@code case01} through {@code case20} in order,
     * all naming this program and all declaring the shape this adapter constructs.
     *
     * <p>Asserted separately from the gate, because the gate is parameterised <em>by</em> the case
     * set: a set of four would run four green tests and report nothing wrong.
     */
    @Test
    @DisplayName("the case set is exactly case01 through case20, all naming COSGN00C")
    void theCaseSetIsExactlyTwenty() {
        List<ParityCase> loaded = cases();

        assertThat(loaded)
                .as("the gate is stated as twenty cases per program; a shorter set is not a smaller "
                        + "gate but a gate that passes without asking the questions")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        List<String> expected = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expected.add(ParityHarness.caseId(ordinal));
        }
        assertThat(loaded.stream().map(ParityCase::caseId).toList())
                .as("the identifiers and their order are part of the contract, because the resource "
                        + "path is derived from them")
                .containsExactlyElementsOf(expected);

        assertThat(loaded).allSatisfy(parityCase -> {
            assertThat(parityCase.program())
                    .as("%s must name the program its directory names, or it was copied from "
                            + "another program's directory", parityCase.caseId())
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                    .as("%s: the decisions of COSGN00C live in SignOnService, which is a real "
                            + "@Service and is reached as a plain object", parityCase.caseId())
                    .isEqualTo(UnitKind.SERVICE);
            assertThat(parityCase.inputs())
                    .as("%s: USRSEC is the program's only dataset - app/cbl/COSGN00C.cbl:39 - and "
                            + "every case seeds it, including the paths that never open it",
                            parityCase.caseId())
                    .containsOnlyKeys(USRSEC);
            assertThat(parityCase.jobParameters())
                    .as("%s: an online transaction takes no job parameter", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedReturnCode())
                    .as("%s: COSGN00C has no CALL 'CEE3ABD' and never moves to RETURN-CODE",
                            parityCase.caseId())
                    .isZero();
            assertThat(parityCase.description().length())
                    .as("%s must say what it exercises, citing the COBOL it derives from",
                            parityCase.caseId())
                    .isGreaterThan(120);
        });
    }

    /**
     * Every case seeds {@code USRSEC} from {@code app/jcl/DUSRSECJ.jcl} and declares the pad that
     * brings those rows to the copybook's width.
     *
     * <p>There is no {@code usrsec} fixture in {@code app/data/ASCII} - the nine that exist are for
     * other datasets - so the ten rows are the JCL's in-stream data, which is
     * {@value #SEED_ROW_WIDTH} characters per row where {@code app/cpy/CSUSR01Y.cpy} declares
     * {@value SecUserRecord#RECORD_LENGTH}. The absent span is {@code SEC-USR-FILLER PIC X(23)}. The
     * pad has exactly one owner: it is declared in the case and applied at seed time by the harness,
     * never at comparison time, so a width disagreement in a failure is always a real difference and
     * never a missing declaration.
     */
    @Test
    @DisplayName("USRSEC is seeded from DUSRSECJ.jcl and padded from 57 to 80 exactly once")
    void everyCaseDeclaresTheUsrsecPad() {
        assertThat(SEED_ROW_WIDTH)
                .as("app/jcl/DUSRSECJ.jcl:35-44 carries 57 characters per record, which is "
                        + "CSUSR01Y's 80 less the 23-byte SEC-USR-FILLER")
                .isEqualTo(57);

        for (ParityCase parityCase : cases()) {
            DatasetInput input = parityCase.inputs().get(USRSEC);

            assertThat(input.rows())
                    .as("%s: the seed is app/jcl/DUSRSECJ.jcl:35-44 - ten records",
                            parityCase.caseId())
                    .hasSize(SEED_ROW_COUNT);
            assertThat(input.rows()).allSatisfy(row -> assertThat(row)
                    .as("%s: an in-stream JCL row is stated at the width the JCL carries",
                            parityCase.caseId())
                    .hasSize(SEED_ROW_WIDTH));

            assertThat(parityCase.normalisations())
                    .as("%s: the 57-to-80 pad is the declared deviation and has one owner",
                            parityCase.caseId())
                    .singleElement()
                    .satisfies(normalisation -> {
                        assertThat(normalisation.dataset()).isEqualTo(USRSEC);
                        assertThat(normalisation.kind())
                                .isEqualTo(Normalisation.USRSEC_FILLER_PAD_57_TO_80);
                    });
        }
    }

    /**
     * The ten seeded rows are the ones {@code app/jcl/DUSRSECJ.jcl} actually carries: five
     * administrators, five regular users, in ascending key order, all sharing one password.
     *
     * <p>The password is compared row against row rather than against a literal spelled here. That
     * keeps this file free of a credential string while still proving the property the sign-on cases
     * depend on - that one typed value authenticates all ten users, so a case that signs on has
     * exercised the comparison rather than a coincidence.
     */
    @Test
    @DisplayName("the seed is DUSRSECJ.jcl's ten rows: five admins, five users, one password")
    void theSeedIsTheJclInStreamData() {
        for (ParityCase parityCase : cases()) {
            List<String> rows = parityCase.inputs().get(USRSEC).rows();

            List<String> keys = new ArrayList<>(rows.size());
            int admins = 0;
            String password = rows.get(0).substring(SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_PWD_OFFSET + SecUserRecord.SEC_USR_PWD_LENGTH);
            for (String row : rows) {
                keys.add(row.substring(SecUserRecord.KEY_OFFSET,
                        SecUserRecord.KEY_OFFSET + KEY_LENGTH));
                String type = row.substring(SecUserRecord.SEC_USR_TYPE_OFFSET,
                        SecUserRecord.SEC_USR_TYPE_OFFSET + SecUserRecord.SEC_USR_TYPE_LENGTH);
                assertThat(type)
                        .as("%s: SEC-USR-TYPE is the one character :227 moves into CDEMO-USER-TYPE "
                                + "and :230 tests, so it is 'A' or 'U' and nothing else",
                                parityCase.caseId())
                        .isIn(NavigationContext.USER_TYPE_ADMIN, NavigationContext.USER_TYPE_USER);
                if (NavigationContext.USER_TYPE_ADMIN.equals(type)) {
                    admins++;
                }
                assertThat(row.substring(SecUserRecord.SEC_USR_PWD_OFFSET,
                        SecUserRecord.SEC_USR_PWD_OFFSET + SecUserRecord.SEC_USR_PWD_LENGTH))
                        .as("%s: DUSRSECJ.jcl gives all ten rows the same eight-character password, "
                                + "which is what lets one typed value drive every role", 
                                parityCase.caseId())
                        .isEqualTo(password);
            }

            assertThat(admins)
                    .as("%s: five ADMIN00n rows carry 'A' - DUSRSECJ.jcl:35-39", parityCase.caseId())
                    .isEqualTo(SEED_ADMIN_COUNT);
            assertThat(keys)
                    .as("%s: USRSEC is a KSDS with KEYS(8,0), so its rows are in ascending key order "
                            + "and every key is distinct", parityCase.caseId())
                    .doesNotHaveDuplicates()
                    .isSorted();
            assertThat(password)
                    .as("%s: SEC-USR-PWD is PIC X(08) and the seed fills it exactly",
                            parityCase.caseId())
                    .hasSize(SecUserRecord.SEC_USR_PWD_LENGTH);
        }
    }

    /**
     * Gates {@code G19} and {@code G21}: every row any case pins is exactly
     * {@value SecUserRecord#RECORD_LENGTH} bytes with {@code SEC-USR-FILLER} present and
     * space-filled, and no case pins a write at all.
     *
     * <p>{@code SEC-USR-FILLER X(23)} is assigned by nothing and must still be emitted: omit it and
     * the record is fifty-seven bytes, which is the very width the seeding deviation would otherwise
     * disguise. The write channel is empty because {@code app/cbl/COSGN00C.cbl} contains no
     * {@code WRITE}, {@code REWRITE} or {@code DELETE} - the sign-on read at {@code :211} does not
     * even specify {@code UPDATE}, so it takes no lock and holds no record.
     */
    @Test
    @DisplayName("every pinned row is 80 bytes with SEC-USR-FILLER space-filled, and nothing is written")
    void everyPinnedRowIsEightyBytesAndNothingIsWritten() {
        String filler = String.valueOf(SPACE).repeat(SecUserRecord.SEC_USR_FILLER_LENGTH);

        for (ParityCase parityCase : cases()) {
            assertThat(parityCase.expectedWrites())
                    .as("%s: COSGN00C issues no WRITE, REWRITE or DELETE on any path, so an empty "
                            + "write channel is the assertion rather than an omission",
                            parityCase.caseId())
                    .isEmpty();

            assertThat(parityCase.expectedFinalState())
                    .as("%s: every path answers the question 'what does USRSEC hold now?'",
                            parityCase.caseId())
                    .hasSize(SEED_ROW_COUNT);
            for (ExpectedRecord expected : parityCase.expectedFinalState()) {
                assertThat(expected.dataset()).isEqualTo(USRSEC);
                assertThat(expected.expectedBytes())
                        .as("%s: SEC-USER-DATA is 8 + 20 + 20 + 8 + 1 + 23 bytes, and an expectation "
                                + "is always stated at the copybook's width because the pad is "
                                + "applied at seed time and never at comparison time",
                                parityCase.caseId())
                        .hasSize(SecUserRecord.RECORD_LENGTH);
                assertThat(expected.expectedBytes()
                        .substring(SecUserRecord.SEC_USR_FILLER_OFFSET))
                        .as("%s: SEC-USR-FILLER is unassigned by COSGN00C and space-filled",
                                parityCase.caseId())
                        .isEqualTo(filler);
            }

            assertThat(parityCase.expectedDatasets())
                    .as("%s: the dataset-level assertion states the row count and the record length, "
                            + "which a per-row expectation cannot", parityCase.caseId())
                    .singleElement()
                    .satisfies(dataset -> {
                        assertThat(dataset.dataset()).isEqualTo(USRSEC);
                        assertThat(dataset.channel())
                                .as("%s: the question is what USRSEC holds now, not what was written "
                                        + "to it - nothing was", parityCase.caseId())
                                .isEqualTo(DatasetChannel.FINAL_STATE);
                        assertThat(dataset.rowCount()).isEqualTo(SEED_ROW_COUNT);
                        assertThat(dataset.recordLength()).isEqualTo(SecUserRecord.RECORD_LENGTH);
                    });
        }
    }

    /**
     * The blank-field chain keeps the source's order, and the case with both fields blank answers
     * with the <em>first</em> message.
     *
     * <p>{@code EVALUATE TRUE} at {@code app/cbl/COSGN00C.cbl:117} is ordered and the first matching
     * {@code WHEN} wins, so the order of {@code :118-130} is the whole of the contract - and it is
     * only falsifiable across the case set. {@code case06} pins the user-id arm, {@code case07} pins
     * the password arm, and {@code case08} - which transmits neither field, so both arrive as
     * {@code LOW-VALUES} - pins that only the earlier one speaks. Each arm also raises
     * {@code WS-ERR-FLG}, so {@code :138} finds the flag on and the file is never read: an empty read
     * outcome is part of what these three assert.
     */
    @Test
    @DisplayName("the blank-field chain keeps source order and the first blank wins")
    void theBlankFieldChainIsOrderedAndTheFirstBlankWins() {
        Map<String, ParityCase> byId = casesById();

        ParityCase blankUserId = byId.get(ParityHarness.caseId(6));
        assertThat(errMsgOf(blankUserId))
                .as("case06 pins the first arm of the chain, app/cbl/COSGN00C.cbl:118-122")
                .isEqualTo(truncated(SignOnService.MSG_ENTER_USER_ID));
        assertThat(blankUserId.expectedResponse().cursorField())
                .as("case06: :121 moves -1 into USERIDL")
                .isEqualTo(CursorField.USER_ID.lengthItemName().orElseThrow());

        ParityCase blankPassword = byId.get(ParityHarness.caseId(7));
        assertThat(errMsgOf(blankPassword))
                .as("case07 pins the second arm, :123-127, which is reachable only when the user id "
                        + "was NOT blank")
                .isEqualTo(truncated(SignOnService.MSG_ENTER_PASSWORD));
        assertThat(blankPassword.screenRequest().mapFields())
                .as("case07 must actually supply a user id, or it proves nothing about the ordering")
                .containsKey(USERID_INPUT_ITEM);
        assertThat(blankPassword.expectedResponse().cursorField())
                .as("case07: :126 moves -1 into PASSWDL")
                .isEqualTo(CursorField.PASSWORD.lengthItemName().orElseThrow());

        ParityCase bothBlank = byId.get(ParityHarness.caseId(8));
        assertThat(bothBlank.screenRequest().mapFields())
                .as("case08 must transmit neither field, so both arrive as LOW-VALUES and both arms "
                        + "of the chain match - which is the only way to prove the first one wins")
                .doesNotContainKey(USERID_INPUT_ITEM)
                .doesNotContainKey(PASSWD_INPUT_ITEM);
        assertThat(errMsgOf(bothBlank))
                .as("case08: both arms match and the EVALUATE stops at the first, so the password "
                        + "message is never produced")
                .isEqualTo(truncated(SignOnService.MSG_ENTER_USER_ID))
                .isNotEqualTo(truncated(SignOnService.MSG_ENTER_PASSWORD));

        for (ParityCase parityCase : List.of(blankUserId, blankPassword, bothBlank)) {
            assertThat(parityCase.expectedResponse().nextProgram())
                    .as("%s: the arm raises WS-ERR-FLG, so :138 skips READ-USER-SEC-FILE entirely "
                            + "and no sign-on can occur", parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedResponse().termination())
                    .as("%s: the screen is painted at :122 or :127 and :98 then returns the area",
                            parityCase.caseId())
                    .isEqualTo(Termination.RETURN_TRANSID);
        }
    }

    /**
     * {@code FUNCTION UPPER-CASE} is applied to <strong>both</strong> the user id and the password,
     * and it is applied <strong>unconditionally</strong>.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:132-136} sits <em>after</em> the {@code EVALUATE} of
     * {@code :117-130} and outside every arm of it, so it runs on all three paths - including the two
     * that already raised the error flag. That is why {@code CDEMO-USER-ID} carries the normalised
     * image even on a rejected sign-on, and it is asserted here because it is exactly the sort of
     * detail a translation "tidies" by folding the normalisation into the success path.
     *
     * <p>Note the deliberate asymmetry with the sibling screens: {@code COUSR01C}, which adds a user,
     * contains {@code FUNCTION UPPER-CASE} <em>zero</em> times and stores what was typed. The
     * inconsistency is real, verified source behaviour, so it is preserved on both sides rather than
     * harmonised.
     */
    @Test
    @DisplayName("FUNCTION UPPER-CASE covers both fields and runs on every path")
    void upperCaseNormalisationIsUnconditionalAndCoversBothFields() {
        for (ParityCase parityCase : cases()) {
            if (parityCase.expectedResponse() == null) {
                // The PF3 arm at :88-90 never performs PROCESS-ENTER-KEY, so :132-136 is not reached.
                continue;
            }
            String typed = parityCase.screenRequest().mapFields().get(USERID_INPUT_ITEM);
            if (typed == null) {
                continue;
            }
            assertThat(navigationOf(parityCase, NavigationContext.USER_ID_FIELD))
                    .as("%s: :132-134 moves FUNCTION UPPER-CASE(USERIDI) into CDEMO-USER-ID on every "
                            + "path through PROCESS-ENTER-KEY, error flag or no error flag",
                            parityCase.caseId())
                    .isEqualTo(SignOnService.upperCase(typed));
        }

        Map<String, ParityCase> byId = casesById();
        ParityCase lowerCase = byId.get(ParityHarness.caseId(9));
        ParityCase mixedCase = byId.get(ParityHarness.caseId(10));
        ParityCase lowerPasswordOnly = byId.get(ParityHarness.caseId(16));

        for (ParityCase parityCase : List.of(lowerCase, mixedCase, lowerPasswordOnly)) {
            String typedId = parityCase.screenRequest().mapFields().get(USERID_INPUT_ITEM);
            String typedPassword = parityCase.screenRequest().mapFields().get(PASSWD_INPUT_ITEM);
            assertThat(parityCase.expectedResponse().nextProgram())
                    .as("%s: the seeded keys and passwords are upper case, so a run that signs on "
                            + "with \"%s\" typed has necessarily normalised both fields",
                            parityCase.caseId(), typedId)
                    .isNotNull();
            assertThat(typedId + typedPassword)
                    .as("%s must actually type something that is not already upper case, or it "
                            + "proves nothing", parityCase.caseId())
                    .isNotEqualTo(SignOnService.upperCase(typedId + typedPassword));
        }

        assertThat(lowerPasswordOnly.screenRequest().mapFields().get(USERID_INPUT_ITEM))
                .as("case16 types the key in upper case and the password in lower, which isolates "
                        + ":135-136 from :132-134 - a translation that normalised only the id would "
                        + "fail here and nowhere else")
                .isEqualTo(SignOnService.upperCase(
                        lowerPasswordOnly.screenRequest().mapFields().get(USERID_INPUT_ITEM)));
    }

    /**
     * Gate {@code G41}: the password comparison at {@code app/cbl/COSGN00C.cbl:223} is plaintext and
     * byte for byte.
     *
     * <p>{@code IF SEC-USR-PWD = WS-USER-PWD} is an alphanumeric comparison of two
     * {@code PIC X(08)} fields. There is no encoder in the path, and there is no tolerance in it
     * either: {@code case17} types a password differing from the seeded one in a single character and
     * is refused, while {@code case02} types the seeded one and signs on. Those two together are the
     * assertion - the first alone could be satisfied by a comparison that always failed, and the
     * second alone by one that always succeeded.
     *
     * <p>Recorded once more because it matters: <strong>the plaintext credential is an inherited
     * property of the legacy design and an explicit non-goal of this migration.</strong> Hashing it
     * would need Spring Security, which is out of scope, and would change which requests sign on,
     * which is forbidden.
     */
    @Test
    @DisplayName("the password comparison is plaintext and byte for byte")
    void thePasswordComparisonIsPlaintextAndByteForByte() {
        Map<String, ParityCase> byId = casesById();
        ParityCase signsOn = byId.get(ParityHarness.caseId(2));
        ParityCase refused = byId.get(ParityHarness.caseId(17));

        String seeded = seededPasswordOf(signsOn);
        assertThat(SignOnService.upperCase(
                signsOn.screenRequest().mapFields().get(PASSWD_INPUT_ITEM)))
                .as("case02 types the seeded password exactly, so :223 compares equal with no "
                        + "transformation interposed on either side")
                .isEqualTo(seeded);
        assertThat(signsOn.expectedResponse().nextProgram())
                .as("case02 therefore reaches the XCTL at :231-239")
                .isNotNull();

        String rejectedPassword = SignOnService.upperCase(
                refused.screenRequest().mapFields().get(PASSWD_INPUT_ITEM));
        assertThat(rejectedPassword)
                .as("case17 must type a password of the same length as the seeded one, differing in "
                        + "exactly one character, or it is not testing the comparison")
                .hasSize(seeded.length())
                .isNotEqualTo(seeded);
        assertThat(differingCharacters(rejectedPassword, seeded))
                .as("case17: one byte apart, which is the smallest difference the comparison has to "
                        + "notice")
                .isOne();
        assertThat(refused.expectedResponse().nextProgram())
                .as("case17 therefore takes the ELSE at :241 and never transfers control")
                .isNull();
        assertThat(errMsgOf(refused))
                .as("case17: :242-243 moves 'Wrong Password. Try again ...' into WS-MESSAGE")
                .isEqualTo(truncated(SignOnService.MSG_WRONG_PASSWORD));
        assertThat(refused.expectedResponse().cursorField())
                .as("case17: :244 moves -1 into PASSWDL, so the cursor returns to the password")
                .isEqualTo(CursorField.PASSWORD.lengthItemName().orElseThrow());
    }

    /**
     * Gate {@code G47}: all three arms of {@code EVALUATE WS-RESP-CD} are driven.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:221-257} branches on the raw numeric literals {@code 0} and
     * {@code 13} and defaults everything else to {@code WHEN OTHER}. The three arms produce three
     * different messages and two different cursor positions, and each is pinned by a case:
     * {@code case02} the found arm, {@code case05} the not-found arm reached from the data itself, and
     * {@code case11} the unexpected arm, which no arrangement of well-formed rows can reach and which
     * therefore declares a forced outcome.
     */
    @Test
    @DisplayName("the RESP split drives 0, 13 and other")
    void theRespSplitDrivesAllThreeArms() {
        assertThat(SignOnService.RESP_NORMAL)
                .as("WHEN 0 at :222 is DFHRESP(NORMAL)")
                .isEqualTo(FileStatus.NORMAL);
        assertThat(SignOnService.RESP_NOTFND)
                .as("WHEN 13 at :247 is DFHRESP(NOTFND) - the literal the source writes")
                .isEqualTo(FileStatus.NOTFND)
                .isEqualTo(13);

        Map<String, ParityCase> byId = casesById();

        ParityCase found = byId.get(ParityHarness.caseId(2));
        assertThat(found.screenRequest().forcedOutcomes())
                .as("case02 reaches WHEN 0 from the seeded data alone, so it forces nothing")
                .isEmpty();

        ParityCase notFound = byId.get(ParityHarness.caseId(5));
        assertThat(notFound.screenRequest().forcedOutcomes())
                .as("case05 reaches WHEN 13 from the seeded data too, by naming a key USRSEC does "
                        + "not hold - a forced outcome would make the arm stipulated rather than "
                        + "genuine")
                .isEmpty();
        assertThat(errMsgOf(notFound))
                .as("case05: :249 moves 'User not found. Try again ...'")
                .isEqualTo(truncated(SignOnService.MSG_USER_NOT_FOUND));
        assertThat(notFound.expectedResponse().cursorField())
                .as("case05: :250 moves -1 into USERIDL")
                .isEqualTo(CursorField.USER_ID.lengthItemName().orElseThrow());

        ParityCase other = byId.get(ParityHarness.caseId(11));
        assertThat(other.screenRequest().forcedOutcomes())
                .as("case11: WHEN OTHER is unreachable from ten well-formed rows, so the outcome is "
                        + "forced - and forced for the read, which is the only file operation the "
                        + "program performs")
                .containsOnlyKeys(RepositoryOperation.READ);
        assertThat(other.screenRequest().forcedOutcomes().get(RepositoryOperation.READ).outcome())
                .as("case11: an outcome the two preceding arms did not name")
                .isEqualTo(FileStatus.Outcome.OTHER);
        assertThat(errMsgOf(other))
                .as("case11: :254 moves 'Unable to verify the User ...'")
                .isEqualTo(truncated(SignOnService.MSG_UNABLE_TO_VERIFY));
        assertThat(other.expectedResponse().cursorField())
                .as("case11: :255 moves -1 into USERIDL, as the not-found arm does")
                .isEqualTo(CursorField.USER_ID.lengthItemName().orElseThrow());
    }

    /**
     * Gate {@code G40}: the {@code EXEC CICS XCTL} becomes a response field, and the role decides
     * which target it names.
     *
     * <p>{@code :230-240} is the program's only transfer of control and it routes on
     * {@code 88 CDEMO-USRTYP-ADMIN} alone. In the stateless translation the response carries the role
     * and the target and the client resolves the navigation, so what is asserted here is a pair of
     * response fields rather than a dispatch. Both targets are exercised, and the count of signing-on
     * cases is pinned so that a case which stopped signing on cannot pass unnoticed.
     */
    @Test
    @DisplayName("role routing becomes a response field, admin to COADM01C and user to COMEN01C")
    void theRoleFieldReplacesTheXctl() {
        int adminTargets = 0;
        int userTargets = 0;

        for (ParityCase parityCase : cases()) {
            if (parityCase.expectedResponse() == null
                    || parityCase.expectedResponse().nextProgram() == null) {
                continue;
            }
            String target = parityCase.expectedResponse().nextProgram();
            String role = navigationOf(parityCase, NavigationContext.USER_TYPE_FIELD);

            if (NavigationContext.USER_TYPE_ADMIN.equals(role)) {
                assertThat(target)
                        .as("%s: :230 finds CDEMO-USRTYP-ADMIN true, so :232 names the admin menu",
                                parityCase.caseId())
                        .isEqualTo(SignOnService.ADMIN_PROGRAM);
                adminTargets++;
            } else {
                assertThat(target)
                        .as("%s: :235 is the ELSE of a test on 'A' alone, so :237 names the main menu",
                                parityCase.caseId())
                        .isEqualTo(SignOnService.USER_PROGRAM);
                userTargets++;
            }

            assertThat(parityCase.expectedResponse().termination())
                    .as("%s: an XCTL does not come back, so the EXEC CICS RETURN at :98 that follows "
                            + "it in the source is never reached", parityCase.caseId())
                    .isEqualTo(Termination.XCTL);
            assertThat(parityCase.expectedResponse().sends())
                    .as("%s: :231-239 sends no map - the client is handed a program name, not a "
                            + "screen", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedResponse().nextMapset())
                    .as("%s: and names no mapset either", parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedResponse().cursorField())
                    .as("%s: :230-240 contains no MOVE -1, so no cursor is requested",
                            parityCase.caseId())
                    .isNull();
        }

        assertThat(adminTargets + userTargets)
                .as("eight of the twenty sign on; a change in that number means a case stopped "
                        + "exercising the comparison at :223")
                .isEqualTo(SUCCESSFUL_SIGN_ONS);
        assertThat(adminTargets)
                .as("both arms of :230 are driven, so neither target is assumed")
                .isPositive();
        assertThat(userTargets).isPositive();
        assertThat(SignOnService.ADMIN_PROGRAM)
                .as("the admin target is the literal at app/cbl/COSGN00C.cbl:232")
                .isEqualTo(SignOnResponse.NEXT_PROGRAM_ADMIN);
        assertThat(SignOnService.USER_PROGRAM)
                .as("the regular-user target is the literal at :237")
                .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
    }

    /**
     * Gate {@code G37}: the whole conversation state travels in the payload and none of it is held on
     * the server.
     *
     * <p>Every case pins all sixteen {@code CARDDEMO-COMMAREA} fields, at their copybook widths, on
     * whichever side of the conversation the program left them. The five that {@code :224-228} writes
     * are written on the signing-on path only, and {@code CDEMO-PGM-CONTEXT} is reset to the
     * {@code 88 CDEMO-PGM-ENTER} value there by {@code MOVE ZEROS} at {@code :228} - which
     * {@code case15} proves by arriving with it set to the re-entry value and leaving with it zero.
     *
     * <p>{@code case14} carries a fully populated inbound area and asserts the complement: the eleven
     * fields {@code COSGN00C} does not write come back exactly as they arrived. A translation holding
     * any of this in a session would have nothing to hand back.
     */
    @Test
    @DisplayName("the navigation context travels in the payload, all sixteen fields, no session")
    void theNavigationContextTravelsInThePayload() {
        for (ParityCase parityCase : cases()) {
            if (parityCase.expectedResponse() == null) {
                continue;
            }
            assertThat(parityCase.expectedResponse().navigation())
                    .as("%s: :100 hands back CARDDEMO-COMMAREA on every path, so all sixteen fields "
                            + "of app/cpy/COCOM01Y.cpy:19-44 are compared", parityCase.caseId())
                    .containsOnlyKeys(COMMAREA_FIELDS.toArray(new String[0]));

            boolean signedOn = parityCase.expectedResponse().nextProgram() != null;
            assertThat(navigationOf(parityCase, NavigationContext.FROM_TRANID_FIELD).strip())
                    .as("%s: :224 moves WS-TRANID into CDEMO-FROM-TRANID, and only on the signing-on "
                            + "path", parityCase.caseId())
                    .isEqualTo(signedOn ? SignOnService.TRANSACTION_ID : "");
            assertThat(navigationOf(parityCase, NavigationContext.FROM_PROGRAM_FIELD).strip())
                    .as("%s: :225 moves WS-PGMNAME into CDEMO-FROM-PROGRAM, likewise",
                            parityCase.caseId())
                    .isEqualTo(signedOn ? SignOnService.PROGRAM_NAME : "");
            if (signedOn) {
                assertThat(navigationOf(parityCase, NavigationContext.PGM_CONTEXT_FIELD))
                        .as("%s: :228 moves ZEROS into CDEMO-PGM-CONTEXT, which asserts "
                                + "88 CDEMO-PGM-ENTER", parityCase.caseId())
                        .isEqualTo("0");
                assertThat(navigationOf(parityCase, NavigationContext.USER_TYPE_FIELD))
                        .as("%s: :227 moves SEC-USR-TYPE straight out of the record",
                                parityCase.caseId())
                        .isIn(NavigationContext.USER_TYPE_ADMIN, NavigationContext.USER_TYPE_USER);
            }
        }

        Map<String, ParityCase> byId = casesById();

        ParityCase reentered = byId.get(ParityHarness.caseId(15));
        assertThat(reentered.screenRequest().commarea().get(NavigationContext.PGM_CONTEXT_FIELD))
                .as("case15 must arrive with CDEMO-PGM-CONTEXT at the re-entry value, or the reset "
                        + "at :228 is not being tested")
                .isEqualTo("1");
        assertThat(navigationOf(reentered, NavigationContext.PGM_CONTEXT_FIELD))
                .as("case15: :228 resets it, unconditionally, on the way to the XCTL")
                .isEqualTo("0");

        ParityCase carried = byId.get(ParityHarness.caseId(14));
        assertThat(carried.screenRequest().commarea())
                .as("case14 must arrive with the customer, account and card fields populated, or the "
                        + "complement it asserts is vacuous")
                .containsKeys(NavigationContext.CUST_ID_FIELD,
                        NavigationContext.ACCT_ID_FIELD,
                        NavigationContext.CARD_NUM_FIELD);
        for (Map.Entry<String, String> arrived : carried.screenRequest().commarea().entrySet()) {
            if (List.of(NavigationContext.FROM_TRANID_FIELD,
                    NavigationContext.FROM_PROGRAM_FIELD,
                    NavigationContext.USER_ID_FIELD,
                    NavigationContext.USER_TYPE_FIELD,
                    NavigationContext.PGM_CONTEXT_FIELD).contains(arrived.getKey())) {
                continue;
            }
            assertThat(navigationOf(carried, arrived.getKey()))
                    .as("case14: COSGN00C writes five commarea fields at :224-228 and touches no "
                            + "other, so %s comes back as it arrived", arrived.getKey())
                    .isEqualTo(arrived.getValue());
        }
    }

    /**
     * The header widths are this screen's own, and the two {@code EXEC CICS ASSIGN} substitutions
     * appear on every painted screen.
     *
     * <p>{@code COSGN00} is the only one of the five user screens that carries {@code APPLID} and
     * {@code SYSID} - they come from {@code EXEC CICS ASSIGN} at {@code :198-204}, which no other
     * program in the set performs - and the only one whose {@code CURTIMEO} is declared
     * {@code PIC X(9)} rather than {@code PIC X(8)}. Both are easy to get wrong by copying a sibling
     * screen's field list, so both are asserted against the widths this map declares, and the extra
     * byte of {@code CURTIMEO} is asserted to be the trailing space it is: {@code WS-CURTIME-HH-MM-SS}
     * is eight characters and {@code :196} moves it into a nine-character receiver.
     */
    @Test
    @DisplayName("CURTIMEO is nine bytes and APPLIDO and SYSIDO are this screen's alone")
    void theHeaderWidthsAreThisScreensOwn() {
        assertThat(SignOnResponse.CURTIME_LENGTH)
                .as("CURTIMEO PIC X(9) - app/cpy-bms/COSGN00.CPY:122. The sibling COUSR02 map "
                        + "declares its CURTIMEO PIC X(8), and the two must not be conflated")
                .isEqualTo(9);
        assertThat(SignOnResponse.APPLID_LENGTH)
                .as("APPLIDO PIC X(8) - COSGN00.CPY:128")
                .isEqualTo(8);
        assertThat(SignOnResponse.SYSID_LENGTH)
                .as("SYSIDO PIC X(8) - COSGN00.CPY:134")
                .isEqualTo(8);

        for (ParityCase parityCase : cases()) {
            ScreenSend send = sendOf(parityCase);
            if (send == null) {
                continue;
            }
            assertThat(send.fields().get(SignOnResponse.CURTIME_FIELD))
                    .as("%s: :192-196 composes HH:MM:SS - eight characters - and moves it into a "
                            + "nine-character item, so the ninth byte is a space",
                            parityCase.caseId())
                    .hasSize(SignOnResponse.CURTIME_LENGTH)
                    .endsWith(String.valueOf(SPACE));
            assertThat(send.fields().get(SignOnResponse.APPLID_FIELD))
                    .as("%s: the APPLID substitution for EXEC CICS ASSIGN at :198-200, at the map's "
                            + "declared width", parityCase.caseId())
                    .isEqualTo(rightPadded(APPLID, SignOnResponse.APPLID_LENGTH));
            assertThat(send.fields().get(SignOnResponse.SYSID_FIELD))
                    .as("%s: the SYSID substitution for EXEC CICS ASSIGN at :202-204",
                            parityCase.caseId())
                    .isEqualTo(rightPadded(SYSID, SignOnResponse.SYSID_LENGTH));
            assertThat(send.fields().get(SignOnResponse.TITLE01_FIELD))
                    .as("%s: :181 moves CCDA-TITLE01 out of app/cpy/COTTL01Y.cpy into a "
                            + "forty-character item; the text itself is pinned by the case and "
                            + "compared by the differ", parityCase.caseId())
                    .hasSize(SignOnResponse.TITLE01_LENGTH);
            assertThat(send.fields().get(SignOnResponse.TITLE02_FIELD))
                    .as("%s: :182 moves CCDA-TITLE02, likewise forty characters",
                            parityCase.caseId())
                    .hasSize(SignOnResponse.TITLE02_LENGTH);
            assertThat(send.fields().get(SignOnResponse.CURDATE_FIELD))
                    .as("%s: :186-190 composes MM/DD/YY - CURDATEO PIC X(8), COSGN00.CPY:104",
                            parityCase.caseId())
                    .hasSize(SignOnResponse.CURDATE_LENGTH);
            assertThat(send.fields().get(SignOnResponse.TRNNAME_FIELD))
                    .as("%s: :183 moves WS-TRANID, which is the CSD transaction CC00",
                            parityCase.caseId())
                    .isEqualTo(SignOnService.TRANSACTION_ID);
            assertThat(send.fields().get(SignOnResponse.PGMNAME_FIELD))
                    .as("%s: :184 moves WS-PGMNAME", parityCase.caseId())
                    .isEqualTo(SignOnService.PROGRAM_NAME);
        }
    }

    /**
     * Every painted screen carries all eleven named items of mapset {@code COSGN00}, in symbolic-map
     * order, and no attribute item at all.
     *
     * <p>The eleven-versus-ten discrepancy is the interesting part. {@link SignOnResponse} projects
     * ten, deliberately omitting {@code PASSWDO} so the payload never echoes a password over JSON;
     * the screen storage nonetheless holds eleven, because {@code COSGN0AO REDEFINES COSGN0AI}. This
     * is where the two counts are held apart, so the gap cannot be closed in either direction without
     * a test turning red.
     */
    @Test
    @DisplayName("every painted screen carries all eleven named items and no attribute item")
    void everyPaintedScreenCarriesElevenItems() {
        assertThat(SCREEN_ITEMS)
                .as("ten projected items plus the one deliberately omitted, in the order "
                        + "app/cpy-bms/COSGN00.CPY:86-152 declares them")
                .hasSize(SCREEN_ITEM_COUNT)
                .containsExactly(SignOnResponse.TRNNAME_FIELD,
                        SignOnResponse.TITLE01_FIELD,
                        SignOnResponse.CURDATE_FIELD,
                        SignOnResponse.PGMNAME_FIELD,
                        SignOnResponse.TITLE02_FIELD,
                        SignOnResponse.CURTIME_FIELD,
                        SignOnResponse.APPLID_FIELD,
                        SignOnResponse.SYSID_FIELD,
                        SignOnResponse.USERID_FIELD,
                        PASSWD_OUTPUT_ITEM,
                        SignOnResponse.ERRMSG_FIELD);
        assertThat(SignOnResponse.MAP_FIELDS)
                .as("the projection carries ten of the eleven, and PASSWDO is the omission")
                .hasSize(SignOnResponse.MAP_FIELD_COUNT)
                .doesNotContain(PASSWD_OUTPUT_ITEM);

        int sends = 0;
        for (ParityCase parityCase : cases()) {
            ScreenSend send = sendOf(parityCase);
            if (send == null) {
                continue;
            }
            sends++;
            assertThat(send.fields().keySet())
                    .as("%s: a send pins every named item of the map, because a field left unpinned "
                            + "is a field a translation could get wrong for free", parityCase.caseId())
                    .containsExactlyElementsOf(SCREEN_ITEMS);
            assertThat(send.attributes())
                    .as("%s: COSGN00C assigns no xxxC, xxxP, xxxH or xxxV item anywhere - it copies "
                            + "neither CSSETATY, whose only consumer is COACTUPC, nor in effect "
                            + "DFHATTR, since app/cbl/COSGN00C.cbl:59 is *COPY DFHATTR. with the "
                            + "asterisk in column 7 and is therefore a comment", parityCase.caseId())
                    .isEmpty();
        }
        assertThat(sends)
                .as("eleven of the twenty reach SEND-SIGNON-SCREEN at :145-157")
                .isEqualTo(SCREEN_SENDS);
    }

    /**
     * The three exits partition the twenty cases, and the {@code DFHPF3} arm is the only one that
     * emits a line.
     *
     * <p>{@code SEND-PLAIN-TEXT} at {@code :162-172} transmits
     * {@code FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE)} - eighty bytes of unformatted text - and
     * then issues a bare {@code EXEC CICS RETURN} carrying no {@code TRANSID} and no
     * {@code COMMAREA}, so the pseudo-conversation ends there and {@code :98-102} is never reached.
     * That case therefore pins no screen response at all: there is no map, no navigation context
     * handed on and no termination to state. What it pins instead is the eighty-byte line, which is
     * the whole of what the terminal received, and which is also the only place in this gate where
     * the full {@value #WS_MESSAGE_LENGTH}-byte {@code WS-MESSAGE} is observable rather than its
     * {@value #ERRMSG_LENGTH}-byte projection.
     *
     * <p>{@code COSGN00C} contains no {@code DISPLAY} on any path, so no other case emits anything.
     */
    @Test
    @DisplayName("the three exits partition the twenty, and only the PF3 arm emits a line")
    void theThreeExitsPartitionTheCaseSet() {
        int transfers = 0;
        int painted = 0;
        int plainText = 0;

        for (ParityCase parityCase : cases()) {
            if (parityCase.expectedResponse() == null) {
                plainText++;
                assertThat(parityCase.expectedMessages())
                        .as("%s: the bare RETURN at :171 leaves no screen behind, so the eighty-byte "
                                + "SEND TEXT is the entire observation", parityCase.caseId())
                        .singleElement()
                        .satisfies(message -> {
                            assertThat(message.channel())
                                    .as("%s: LENGTH(LENGTH OF WS-MESSAGE) is eighty bytes",
                                            parityCase.caseId())
                                    .isEqualTo(MessageChannel.WS_MESSAGE_80);
                            assertThat(message.text())
                                    .as("%s: :89 moves CCDA-MSG-THANK-YOU, a PIC X(50) sender from "
                                            + "app/cpy/CSMSG01Y.cpy, into the PIC X(80) receiver - so "
                                            + "the fifty characters are left-justified and the "
                                            + "remaining thirty are spaces", parityCase.caseId())
                                    .isEqualTo(padded(SystemMessages.CCDA_MSG_THANK_YOU));
                        });
                continue;
            }

            assertThat(parityCase.expectedMessages())
                    .as("%s: SEND MAP is not a line - its payload is the map's fields, recorded as a "
                            + "screen send - and app/cbl/COSGN00C.cbl has no DISPLAY anywhere, so "
                            + "nothing is emitted here", parityCase.caseId())
                    .isEmpty();

            if (parityCase.expectedResponse().nextProgram() != null) {
                transfers++;
            } else {
                painted++;
            }
        }

        assertThat(plainText)
                .as("exactly one of the twenty presses PF3")
                .isEqualTo(PLAIN_TEXT_SENDS);
        assertThat(transfers)
                .as("eight sign on")
                .isEqualTo(SUCCESSFUL_SIGN_ONS);
        assertThat(painted)
                .as("eleven paint the screen")
                .isEqualTo(SCREEN_SENDS);
        assertThat(transfers + painted + plainText)
                .as("and the three account for every case: MAIN-PARA reaches exactly one terminal "
                        + "action on every path")
                .isEqualTo(ParityHarness.CASES_PER_PROGRAM);
    }

    /**
     * The invalid-key arm is driven by more than one key, and it is the {@code WHEN OTHER} of an
     * ordered {@code EVALUATE} rather than a test for one particular key.
     *
     * <p>{@code :85-95} names {@code DFHENTER} and {@code DFHPF3} and defaults everything else. Two
     * cases press two different keys that are neither, and both must land on the same arm with the
     * same message and no cursor request - {@code :91-94} contains no {@code MOVE -1} at all, which
     * is easy to miss because every other rejecting path in the program has one.
     */
    @Test
    @DisplayName("the invalid-key arm is WHEN OTHER, driven by two different keys")
    void theInvalidKeyArmIsTheDefault() {
        Map<String, ParityCase> byId = casesById();
        ParityCase functionKey = byId.get(ParityHarness.caseId(13));
        ParityCase clearKey = byId.get(ParityHarness.caseId(19));

        assertThat(functionKey.screenRequest().aid())
                .as("case13 and case19 must press different keys, or the arm is being shown to "
                        + "accept one key rather than to be the default")
                .isNotEqualTo(clearKey.screenRequest().aid());

        for (ParityCase parityCase : List.of(functionKey, clearKey)) {
            assertThat(errMsgOf(parityCase))
                    .as("%s: :93 moves CCDA-MSG-INVALID-KEY, a PIC X(50) literal from "
                            + "app/cpy/CSMSG01Y.cpy, into the PIC X(80) WS-MESSAGE, which :149 then "
                            + "narrows to the seventy-eight-byte ERRMSGO", parityCase.caseId())
                    .isEqualTo(truncated(SystemMessages.CCDA_MSG_INVALID_KEY));
            assertThat(parityCase.expectedResponse().cursorField())
                    .as("%s: :91-94 performs no MOVE -1, so no field is repositioned - unlike every "
                            + "other rejecting path in the program", parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedResponse().termination())
                    .as("%s: the screen is painted at :94 and :98 returns the area",
                            parityCase.caseId())
                    .isEqualTo(Termination.RETURN_TRANSID);
            assertThat(parityCase.expectedResponse().nextProgram())
                    .as("%s: PROCESS-ENTER-KEY is never performed, so USRSEC is never read and no "
                            + "sign-on can occur", parityCase.caseId())
                    .isNull();
        }
    }

    /**
     * Risk {@code R-D}: the red emphasis on the error line is a static map property, and the
     * attribute constants this module reproduces are IBM's rather than this repository's.
     *
     * <p>{@code app/bms/COSGN00.bms:197-200} declares
     * {@code ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET) COLOR=RED LENGTH=78 POS=(23,1)}, so the colour is
     * assembled into the map once and is never assigned at run time by this program. Where a runtime
     * value <em>would</em> come from is {@link BmsAttributes}, which reproduces {@code DFHBMSCA} and
     * {@code DFHATTR} from IBM CICS documentation because both copybooks are IBM-supplied and absent
     * from this checkout - as {@code DFHAID} is too. This test states that provenance explicitly, so
     * that no reader takes an attribute value asserted anywhere in this gate to have been read from a
     * copybook in this tree.
     *
     * <p>Nothing else in this file asserts an attribute value, because {@code COSGN00C} sets none;
     * {@link #everyPaintedScreenCarriesElevenItems()} is where that emptiness is required.
     */
    @Test
    @DisplayName("the red emphasis is a static map property and DFHBMSCA is reproduced from IBM docs")
    void theRedEmphasisIsAStaticMapProperty() {
        assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED))
                .as("BmsAttributes is the module's single reproduction of DFHBMSCA and DFHATTR, both "
                        + "of which are IBM-supplied and absent from this repository - so a colour "
                        + "named anywhere in this gate is named against that class and never against "
                        + "a copybook in this checkout")
                .isEqualTo("DFHRED");
        assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHDFCOL))
                .as("and the map's default colour, which is what every field of COSGN00 other than "
                        + "the statically red ERRMSG shows")
                .isEqualTo("DFHDFCOL");
    }

    // =============================================================================================
    // Helpers used only by the set-level assertions.
    // =============================================================================================

    /**
     * {@code MOVE <literal> TO WS-MESSAGE} followed by {@code MOVE WS-MESSAGE TO ERRMSGO} - the two
     * moves at, for example, {@code :242-243} and {@code :149}, composed.
     *
     * @param literal the message literal the source moves
     * @return the {@value #ERRMSG_LENGTH}-character image the screen field ends up holding
     */
    private static String truncated(String literal) {
        return padded(literal).substring(0, ERRMSG_LENGTH);
    }

    /**
     * @param text  the value
     * @param width the field width
     * @return the value left-justified at that width, space-filled on the right
     */
    private static String rightPadded(String text, int width) {
        return text + String.valueOf(SPACE).repeat(width - text.length());
    }

    /**
     * The {@code SEC-USR-PWD} span of the row a case's typed user id addresses.
     *
     * @param parityCase the case
     * @return the eight-character seeded password
     * @throws IllegalStateException if the case types a key the seed does not hold
     */
    private static String seededPasswordOf(ParityCase parityCase) {
        String key = SignOnService.upperCase(
                parityCase.screenRequest().mapFields().get(USERID_INPUT_ITEM));
        for (String row : parityCase.inputs().get(USRSEC).rows()) {
            if (key.equals(row.substring(SecUserRecord.KEY_OFFSET,
                    SecUserRecord.KEY_OFFSET + KEY_LENGTH))) {
                return row.substring(SecUserRecord.SEC_USR_PWD_OFFSET,
                        SecUserRecord.SEC_USR_PWD_OFFSET + SecUserRecord.SEC_USR_PWD_LENGTH);
            }
        }
        throw new IllegalStateException(parityCase.caseId() + " types a user id the seed does not "
                + "hold, so there is no SEC-USR-PWD for the comparison at "
                + "app/cbl/COSGN00C.cbl:223 to reach. A case asserting the password comparison has "
                + "to address a row that exists.");
    }

    /**
     * @param left  one image
     * @param right another image of the same length
     * @return how many character positions differ
     */
    private static int differingCharacters(String left, String right) {
        int differences = 0;
        for (int index = 0; index < left.length(); index++) {
            if (left.charAt(index) != right.charAt(index)) {
                differences++;
            }
        }
        return differences;
    }
}
