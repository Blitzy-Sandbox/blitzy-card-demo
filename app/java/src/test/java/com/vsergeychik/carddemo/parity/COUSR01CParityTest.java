package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
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
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserAddController;
import com.vsergeychik.carddemo.user.UserAddController.ProgramState;
import com.vsergeychik.carddemo.user.dto.UserAddRequest;
import com.vsergeychik.carddemo.user.dto.UserAddResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The twenty-case behavioural parity gate for {@code app/cbl/COUSR01C.cbl} - CICS transaction
 * {@code CU01}, "Add a new Regular/Admin user to USRSEC file", projected onto
 * {@code POST /api/users}.
 *
 * <h2>Where the expected values come from, and where they do not</h2>
 *
 * <p><strong>The baseline is statically derived. It was never captured from a running COBOL
 * program.</strong> Executing the twenty-eight legacy programs is impossible in this environment:
 * there is no z/OS runtime, the available compiler reports {@code indexed file handler : disabled}
 * so the seven programs using {@code ORGANIZATION INDEXED} cannot even build, there are no Language
 * Environment {@code CEE*} services, there is no CICS emulator, and {@code DFHAID},
 * {@code DFHBMSCA} and {@code DFHATTR} are absent from the repository altogether. Every expected
 * value in {@code src/test/resources/parity/COUSR01C/} was therefore obtained by reading
 * {@code app/cbl/COUSR01C.cbl} paragraph by paragraph and cross-checking four authoritative
 * sources: {@code app/cpy/CSUSR01Y.cpy} for the record's byte layout,
 * {@code app/cpy-bms/COUSR01.CPY} with {@code app/bms/COUSR01.bms} for the screen's field shapes,
 * {@code app/cpy/COCOM01Y.cpy} for the communication area, and {@code app/jcl/DUSRSECJ.jcl} for the
 * real seed data. That is a substitution of provenance and only of provenance: twenty cases,
 * field-for-field diffing and a required diff count of zero all stand. It is stated here rather
 * than in a commit message because a statically derived expectation can encode a misreading of the
 * COBOL where a captured one cannot, and whoever debugs a failure needs to know which side to
 * doubt.
 *
 * <h2>No HTTP between the assertion and the code</h2>
 *
 * <p>The unit is {@link UserAddController}, constructed through its own constructor as a plain Java
 * object with a fixture-backed {@link SecUserRepository} injected, and
 * {@link UserAddController#mainPara(UserAddRequest, byte, int)} called directly. There is no Mock
 * MVC, no test REST template, no web test client, no servlet container and no job launcher anywhere
 * in this file. That is not a shortcut, it is the requirement: a parity assertion is about
 * arithmetic and byte layout, and putting a dispatcher, a filter chain and a JSON round trip
 * between the assertion and the decision logic can only obscure which of them produced a
 * difference. {@code COUSR01C}'s decisions all live in {@code mainPara}, which mentions no servlet
 * type, so every one of them is reachable this way. The {@code user} package has a separate service
 * class only for {@code COSGN00C}; the other four user programs, this one included, keep their
 * logic in the controller, so {@link UnitKind#CONTROLLER_POJO} is the only way to reach it at all.
 *
 * <h2>Three properties of the legacy design that are asserted, not corrected</h2>
 *
 * <ul>
 *   <li><strong>No case normalisation.</strong> {@code app/cbl/COSGN00C.cbl:132} and {@code :135}
 *       wrap both the user id and the password in {@code FUNCTION UPPER-CASE} before comparing
 *       them. {@code COUSR01C} contains that intrinsic <em>zero</em> times and simply {@code MOVE}s
 *       each field at {@code :154-158}. The inconsistency is real, verified source behaviour, so
 *       {@code case11} asserts that a lower-case id and a mixed-case password are stored exactly as
 *       typed. Do not harmonise the two programs: harmonising them changes behaviour, and this file
 *       is what will notice.</li>
 *   <li><strong>The password is plaintext.</strong> {@code SEC-USR-PWD PIC X(08)} in
 *       {@code app/cpy/CSUSR01Y.cpy} holds the password as typed, and {@code COSGN00C} compares it
 *       as typed. The eighty-byte record images below therefore carry it in the clear, because that
 *       is what the program writes. Hashing it would need Spring Security, which is out of scope,
 *       and would change behaviour, which is forbidden. The characteristic is inherited from the
 *       legacy design and is stated here so it stays visible rather than buried; the seed values
 *       are the 2022-vintage demo password already present in this repository as in-stream JCL
 *       data, and nothing here introduces a credential that guards anything.</li>
 *   <li><strong>{@code DFHATTR} is reproduced from IBM documentation, not from this
 *       repository.</strong> {@code COUSR01C:57} carries {@code *COPY DFHATTR.} - the occurrence is
 *       real but the line is <em>commented out</em>, and the copybook is IBM-supplied and absent
 *       here in any case, as {@code DFHAID} and {@code DFHBMSCA} at {@code :55-56} also are. So
 *       every attribute value this file asserts - {@code DFHGREEN} on the confirmation,
 *       {@code DFHDFCOL} everywhere else - is asserted against {@link BmsAttributes} and
 *       {@link CicsAid}, which reproduce those constants from IBM CICS documentation. No assertion
 *       here traces to a copybook in this checkout, and implying otherwise would misdescribe the
 *       evidence.</li>
 * </ul>
 *
 * <h2>The screens that look interchangeable and are not</h2>
 *
 * <p>{@code COUSR01} has <strong>twelve</strong> named fields including {@code USERID} and
 * {@code PASSWD}. {@code COUSR02} also has twelve but replaces {@code USERID} with
 * {@code USRIDIN}. {@code COUSR03} has <strong>eleven</strong>, because the delete screen
 * deliberately has no password field. The three were measured rather than assumed, and no field
 * list is shared between them.
 *
 * @see UserAddController the translation of {@code app/cbl/COUSR01C.cbl}
 * @see SecUserRecord the eighty-byte {@code SEC-USER-DATA} record of {@code app/cpy/CSUSR01Y.cpy}
 * @see ParityHarness which seeds, invokes and captures
 * @see FieldDiffer which judges, field by field
 */
final class COUSR01CParityTest {

    // =============================================================================================
    // Identity. The class stem, the resource directory and the "program" member of all twenty case
    // files have to agree, so the name is written once.
    // =============================================================================================

    /** {@code PROGRAM-ID. COUSR01C} - {@code app/cbl/COUSR01C.cbl:23}. */
    private static final String PROGRAM = "COUSR01C";

    /**
     * The dataset binding key, which is the CICS file name at {@code app/cbl/COUSR01C.cbl:39} and
     * never a dataset name.
     *
     * <p>No {@code AWS.M2.CARDDEMO} literal appears in this file: dataset names live in
     * {@code application.yml} and are resolved from configuration.
     */
    private static final String USRSEC = SecUserRepository.CICS_FILE_NAME;

    /**
     * {@code KEYS(8,0)} - the primary key width {@code app/jcl/DUSRSECJ.jcl} STEP02 defines for
     * {@code USRSEC}, and the {@code KEYLENGTH} the program passes at
     * {@code app/cbl/COUSR01C.cbl:245}.
     */
    private static final int KEY_LENGTH = SecUserRecord.KEY_LENGTH;

    /** The offset of that key within the record - zero, per {@code app/cpy/CSUSR01Y.cpy}. */
    private static final int KEY_OFFSET = SecUserRecord.KEY_OFFSET;

    /**
     * {@code DFHAID} mnemonic to attention identifier byte.
     *
     * <p>A case declares its key as a mnemonic, which is how a reader recognises it; the program
     * evaluates a byte. {@link CicsAid#mnemonicsByAid()} is the module's single reproduction of the
     * absent copybook, so the mapping is inverted from that rather than restated - a second table
     * could disagree with the first, and a case naming {@code DFHPF3} while driving {@code DFHPF4}
     * would exercise the wrong arm and still pass.
     *
     * <p>Unmodifiable, so this introduces no mutable static state.
     */
    private static final Map<String, Byte> AID_BYTES = aidBytesByMnemonic();

    /**
     * The byte presented when a case declares no key at all.
     *
     * <p>Two of the twenty paths never read {@code EIBAID}: the no-commarea guard at
     * {@code app/cbl/COUSR01C.cbl:78} and the first-entry paint at {@code :83-87}, both of which
     * answer before the {@code EVALUATE EIBAID} at {@code :90} is reached. Those cases declare no
     * AID and are driven with {@code DFHNULL} - the value CICS itself uses for "no attention
     * identifier", which {@link PfKeyResolver#resolve(byte)} reports as no key.
     */
    private static final byte NO_AID = CicsAid.DFHNULL;

    /** {@code WS-MESSAGE PIC X(80)} - {@code app/cbl/COUSR01C.cbl:38}. */
    private static final int WS_MESSAGE_LENGTH = UserAddResponse.WS_MESSAGE_LENGTH;

    /** {@code ERRMSGO PIC X(78)} - {@code app/cpy-bms/COUSR01.CPY:90}, two bytes narrower. */
    private static final int ERRMSG_LENGTH = UserAddResponse.ERR_MSG_LENGTH;

    /** The sixteen {@code CARDDEMO-COMMAREA} fields of {@code app/cpy/COCOM01Y.cpy:19-44}. */
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

    /** The five data items {@code PROCESS-ENTER-KEY} validates and the write consumes. */
    private static final List<String> DATA_FIELDS = List.of(UserAddResponse.F_NAME_FIELD,
            UserAddResponse.L_NAME_FIELD,
            UserAddResponse.USER_ID_FIELD,
            UserAddResponse.PASSWD_FIELD,
            UserAddResponse.USR_TYPE_FIELD);

    /** How many of the twenty cases reach a completed {@code EXEC CICS WRITE}. */
    private static final int SUCCESSFUL_WRITES = 5;

    /**
     * How many of the twenty end by {@code EXEC CICS XCTL} rather than by {@code RETURN}.
     *
     * <p>One: {@code case16} presses {@code DFHPF3} and transfers at {@code :93-95}. The other
     * transferring path - the no-commarea guard at {@code :78-80} - is driven by
     * {@code UserAddControllerTest} rather than by a parity case, because a case that carries no
     * commarea and no map field also carries no record to diff, so the whole of its evidence is the
     * response, and the controller's own test states that more directly.
     */
    private static final int TRANSFERS = 1;

    // =============================================================================================
    // The case set.
    // =============================================================================================

    /**
     * The twenty cases, loaded from {@code src/test/resources/parity/COUSR01C/}.
     *
     * <p>{@link ParityHarness#casesOf(String)} refuses any set that is not exactly {@code case01}
     * through {@code case20}, which is the point of routing through it: "the diff count is zero
     * across all twenty cases" is satisfied vacuously by a set of four, so a short or misnamed set
     * has to fail loudly rather than quietly become a smaller gate.
     *
     * @return the twenty cases in ordinal order
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * The argument stream, each case labelled by its own identifier so a failure reads as
     * {@code COUSR01C case07} rather than as an index.
     *
     * @return one argument pair per case
     */
    static List<Arguments> declaredCases() {
        List<ParityCase> loaded = cases();
        List<Arguments> arguments = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            arguments.add(Arguments.of(parityCase.caseId(), parityCase));
        }
        return arguments;
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
    @ParameterizedTest(name = "COUSR01C {0}")
    @MethodSource("declaredCases")
    @DisplayName("diff count is zero on all twenty cases")
    void diffCountIsZero(String caseId, ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();

        DiffResult result = harness.judge(parityCase, UnitKind.CONTROLLER_POJO,
                COUSR01CParityTest::invokeUserAdd);

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
    // How the unit is reached. Everything below constructs COUSR01C's translation and calls it; not
    // one line of it can see the expectation, because Invocation carries the inputs only.
    // =============================================================================================

    /**
     * Constructs {@link UserAddController} and calls {@code MAIN-PARA} once.
     *
     * <p>Three inputs and nothing else, exactly as CICS gave the program three: the received map,
     * the attention identifier, and {@code EIBCALEN}. The clock and the code page come from the
     * case, so no wall clock and no platform default is ever consulted.
     *
     * @param invocation the seeded dataset, the online request, the pinned clock, the codec and the
     *                   recorder
     * @return everything the run observably produced
     */
    private static UnitOutcome invokeUserAdd(Invocation invocation) {
        FixedWidthCodec codec = invocation.codec();
        SeededDataset seeded = invocation.dataset(USRSEC);
        KeySequencedFile file = new KeySequencedFile(seeded.rows());

        UserAddController controller = new UserAddController(
                stubbedRepository(invocation, codec, file), invocation.clock(),
                invocation.charset());

        ProgramState state = controller.mainPara(requestOf(invocation, codec),
                aidByteOf(invocation.aid()), invocation.eibcalen());

        return record(invocation, codec, file, state);
    }

    /**
     * The {@code USRSEC} dataset behind {@code EXEC CICS WRITE} - {@code COUSR01C:240-248}.
     *
     * <p>A stub rather than the real repository, because the real one reaches a data source and this
     * assertion is about what {@code COUSR01C} does with the answer, not about how the answer is
     * fetched. The stub is nonetheless a faithful key-sequenced file rather than a constant: a write
     * whose key already exists is refused with {@code DUPREC}, which is what CICS reports for a
     * duplicate primary key on a base KSDS, and a write with a new key is inserted in key sequence.
     * That makes {@code case09}'s duplicate genuine - it collides with {@code ADMIN001}, which
     * {@code app/jcl/DUSRSECJ.jcl:L35} seeds - rather than stipulated.
     *
     * <p>A case may override the outcome through {@code screenRequest.forcedOutcomes.write}, and
     * three of the twenty need to: {@code DUPKEY} cannot arise from any arrangement of these rows,
     * since CICS raises it on an alternate index and not on a base KSDS, and neither a not-found nor
     * a permanent error can. The override is taken only when the write is actually reached, and the
     * harness refuses a run that declared one and never asked for it.
     *
     * @param invocation the invocation, consulted for a forced outcome
     * @param codec      the code page the record is encoded in
     * @param file       the in-memory key-sequenced file this write lands in
     * @return a repository whose {@code add} behaves as described
     */
    private static SecUserRepository stubbedRepository(Invocation invocation, FixedWidthCodec codec,
                                                      KeySequencedFile file) {
        SecUserRepository repository = mock(SecUserRepository.class);
        when(repository.add(any(SecUserRecord.class))).thenAnswer(call -> {
            SecUserRecord offered = call.getArgument(0);
            String image = codec.decodeImage(SecUserRecord.encode(offered, codec),
                    "the SEC-USER-DATA record offered to " + USRSEC);

            if (invocation.hasForcedOutcome(RepositoryOperation.WRITE)) {
                return forced(invocation.forcedOutcome(RepositoryOperation.WRITE));
            }
            if (file.holdsKey(keyOf(image))) {
                // A duplicate primary key on a base KSDS: RESP is DUPREC, never DUPKEY.
                return WriteResult.duplicateRecord();
            }
            file.insert(image);
            return WriteResult.written();
        });
        return repository;
    }

    /**
     * Translates a case's forced outcome into the {@link WriteResult} the repository would report.
     *
     * <p>{@code DUPKEY} and {@code DUPREC} are told apart by the {@code RESP} the case declares,
     * because they stay distinguishable at the repository boundary and collapse into one action only
     * inside {@code COUSR01C:260-266}. An {@code OTHER} outcome is the permanent-error shape: the
     * backend refused outright and reported no response code at all, which cannot be mistaken for
     * any of the three values the {@code EVALUATE} names.
     *
     * @param outcome the outcome the case forces
     * @return the corresponding write result
     * @throws IllegalArgumentException if the case forces an outcome a write cannot produce
     */
    private static WriteResult forced(ForcedOutcome outcome) {
        switch (outcome.outcome()) {
            case OK:
                return WriteResult.written();
            case DUPLICATE:
                return Integer.valueOf(FileStatus.DUPKEY).equals(outcome.resp())
                        ? WriteResult.duplicateKey()
                        : WriteResult.duplicateRecord();
            case NOT_FOUND:
                return WriteResult.notFound();
            case OTHER:
                return WriteResult.of(SecUserRepository.PERMANENT_ERROR_STATUS,
                        CicsResponse.none());
            case END_OF_FILE:
            default:
                throw new IllegalArgumentException("A case forces " + outcome.outcome()
                        + " on the write against " + USRSEC + ", which EXEC CICS WRITE cannot "
                        + "report: end-of-file belongs to a browse. Force OK, DUPLICATE, NOT_FOUND "
                        + "or OTHER - app/cbl/COUSR01C.cbl:250-274 has an arm for all four.");
        }
    }

    /**
     * The received map, as {@code EXEC CICS RECEIVE MAP} at {@code COUSR01C:203-209} delivered it.
     *
     * <p>Each of the twelve {@code xxxI} items is taken from the case's declared map fields, and a
     * field the case omitted is passed as {@code null} - which the controller treats as a field
     * never typed, that is {@code LOW-VALUES}. That distinction is load-bearing: the blank-field
     * chain tests {@code = SPACES OR LOW-VALUES}, two comparisons against figurative constants, so
     * "twenty spaces" and "never typed" reach the same arm by different routes and {@code case06}
     * exercises the second.
     *
     * <p>A case that declares {@code EIBCALEN} of zero with no commarea and no map field is driven
     * with a {@code null} payload, because that is precisely the shape of a call carrying no body at
     * all, and the no-commarea guard at {@code :78} is the branch that answers it. The payload's own
     * {@code aid} member is deliberately left unset: the key is declared once, in the case, and a
     * second copy could disagree with the first.
     *
     * @param invocation the invocation carrying the declared map fields and commarea
     * @param codec      the code page the commarea image is built in
     * @return the request, or {@code null} for the body-less call
     */
    private static UserAddRequest requestOf(Invocation invocation, FixedWidthCodec codec) {
        Map<String, String> fields = invocation.mapFields();
        Map<String, String> commarea = invocation.commarea();
        if (invocation.eibcalen() == 0 && fields.isEmpty() && commarea.isEmpty()) {
            return null;
        }
        return new UserAddRequest(fields.get(UserAddRequest.TRNNAME_FIELD),
                fields.get(UserAddRequest.TITLE01_FIELD),
                fields.get(UserAddRequest.CURDATE_FIELD),
                fields.get(UserAddRequest.PGMNAME_FIELD),
                fields.get(UserAddRequest.TITLE02_FIELD),
                fields.get(UserAddRequest.CURTIME_FIELD),
                fields.get(UserAddRequest.FNAME_FIELD),
                fields.get(UserAddRequest.LNAME_FIELD),
                fields.get(UserAddRequest.USERID_FIELD),
                fields.get(UserAddRequest.PASSWD_FIELD),
                fields.get(UserAddRequest.USRTYPE_FIELD),
                fields.get(UserAddRequest.ERRMSG_FIELD),
                commareaOf(commarea, codec),
                null);
    }

    /**
     * {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} - {@code COUSR01C:82}.
     *
     * <p>Built by writing the case's declared field images into a
     * {@value NavigationContext#COMMAREA_LENGTH}-byte area and decoding it back, so the context the
     * program adopts is a real communication-area image rather than a hand-assembled object. A field
     * the case leaves unstated keeps the area's initial state - spaces in a character span, zeros in
     * a {@code PIC 9} one - which is what {@code WORKING-STORAGE} holds.
     *
     * @param commarea the declared {@code CDEMO-} field images
     * @param codec    the code page
     * @return the context, or {@code null} when the payload carried none
     */
    private static NavigationContext commareaOf(Map<String, String> commarea,
                                                FixedWidthCodec codec) {
        if (commarea.isEmpty()) {
            return null;
        }
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, commarea));
    }

    /**
     * Resolves a declared {@code DFHAID} mnemonic to the byte {@code COUSR01C:90} evaluates.
     *
     * @param mnemonic the mnemonic the case declared, or {@code null} for a path that never reads
     *                 {@code EIBAID}
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

    // =============================================================================================
    // What the run observably produced.
    // =============================================================================================

    /**
     * Records the run into the harness's recorder: the record written, the state the dataset was
     * left in, the online response, the {@code RETURN-CODE} and the two message channels.
     *
     * <p>The writes and the final state are recorded separately because they answer different
     * questions. "Did the program write the right eighty bytes?" is the write channel, and it is
     * empty on every rejected path. "Is {@code USRSEC} in the right state now?" is the final-state
     * channel, and it is answered on <em>every</em> path including the two that never open the
     * dataset - where the honest answer is "exactly as seeded", which is the positive form of the
     * assertion that nothing was written.
     *
     * @param invocation the invocation, for the recorder
     * @param codec      the code page
     * @param file       the dataset as the run left it
     * @param state      the state {@code MAIN-PARA} ended in
     * @return the recorder's build, which is the last thing this method does
     */
    private static UnitOutcome record(Invocation invocation, FixedWidthCodec codec,
                                      KeySequencedFile file, ProgramState state) {
        UnitOutcome.Builder recorder = invocation.recorder();

        if (state.wroteRecord()) {
            SecUserRecord written = state.secUserData().orElseThrow(() -> new IllegalStateException(
                    "COUSR01C:154-158 builds SEC-USER-DATA before the write at :240, so a state "
                            + "reporting a completed write must carry the record it wrote"));
            recorder.wroteBytes(USRSEC, SecUserRecord.LAYOUT,
                    SecUserRecord.encode(written, codec));
        }
        recorder.finalState(USRSEC, SecUserRecord.LAYOUT, file.rows());
        recorder.response(observed(codec, state));

        // An online transaction sets no RETURN-CODE: COUSR01C has no CALL 'CEE3ABD' and no MOVE to
        // RETURN-CODE anywhere, so zero is stated as the observation rather than left to a default.
        recorder.returnCode(0);

        for (EmittedMessage message : emitted(state)) {
            recorder.message(message);
        }
        return recorder.build();
    }

    /**
     * Projects {@link ProgramState} onto the response the differ judges.
     *
     * @param codec the code page the commarea image is rendered in
     * @param state the state {@code MAIN-PARA} ended in
     * @return the observation
     */
    private static ObservedResponse observed(FixedWidthCodec codec, ProgramState state) {
        UserAddResponse response = state.response();
        return new ObservedResponse(response.nextProgram(),
                mapReferenceOrAbsent(response.nextMapset()),
                mapReferenceOrAbsent(response.nextMap()),
                navigationOf(codec, state.commarea()),
                sendsOf(state),
                state.cursorField().orElse(null),
                terminationOf(state));
    }

    /**
     * Projects a {@code CDEMO-LAST-MAPSET} or {@code CDEMO-LAST-MAP} image onto the differ's
     * vocabulary, in which "no map" is an absent member.
     *
     * <p>The two are {@code PIC X(07)} [{@code app/cpy/COCOM01Y.cpy}] and COBOL has no null, so the
     * {@code XCTL} arm at {@code :175-178} - which passes {@code PROGRAM} and {@code COMMAREA} and no
     * map at all - leaves them holding <strong>seven spaces</strong>, and that is what the response
     * carries. The fixture cannot pin that image directly: {@link ParityCase.ExpectedResponse} refuses
     * a blank {@code nextMapset} and directs the author to omit the key, so that a case can never
     * appear to assert a map reference while asserting nothing. Both sides therefore meet in the same
     * place - spaces are read as absence - which is exactly how the sibling folders project theirs
     * ({@code COADM01CParityTest.named}, {@code COACTVWCParityTest.tokenOrAbsent}).
     *
     * <p>This is a projection for comparison only and hides nothing: that the wire value is spaces
     * rather than {@code null} is asserted directly, at the response's declared width, by
     * {@code UserAddControllerTest} and by {@code UserAddResponseTest}.
     *
     * @param image the mapset or map image as the response carries it, possibly {@code null}
     * @return the trimmed reference, or {@code null} when it names no map
     */
    private static String mapReferenceOrAbsent(String image) {
        return image == null || image.isBlank() ? null : image.trim();
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
     * <p>{@code COUSR01C} sends <strong>at most once</strong> per invocation, and that is a property
     * of the source rather than an assumption: every arm that sends is terminal within its branch,
     * and the two arms that transfer send nothing at all. In particular the five blank-field arms
     * raise {@code WS-ERR-FLG}, so the guard at {@code :153} is false and the write - the only other
     * site that could send - is never reached. The invariant is checked rather than trusted, because
     * a state carrying two sends would mean the field values below are the second send's while the
     * first send's payload has been lost, and one {@link ProgramState} cannot report both.
     *
     * @param state the state {@code MAIN-PARA} ended in
     * @return zero or one send
     * @throws IllegalStateException if the run reported more than one send
     */
    private static List<ObservedSend> sendsOf(ProgramState state) {
        if (state.sendCount() > 1) {
            throw new IllegalStateException("COUSR01C reported " + state.sendCount()
                    + " screen sends in one invocation. app/cbl/COUSR01C.cbl performs SEND from "
                    + ":87, :102, :259, :266, :273 and :282, and no path reaches two of them, so a "
                    + "count above one means either the translation gained a send or the program "
                    + "state is being reused across invocations. Either way the field values would "
                    + "be the last send's only and the earlier send's payload would be "
                    + "unobservable.");
        }
        if (state.sendCount() == 0) {
            return List.of();
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(UserAddResponse.TRN_NAME_FIELD, state.trnName());
        fields.put(UserAddResponse.TITLE01_FIELD, state.title01());
        fields.put(UserAddResponse.CUR_DATE_FIELD, state.curDate());
        fields.put(UserAddResponse.PGM_NAME_FIELD, state.pgmName());
        fields.put(UserAddResponse.TITLE02_FIELD, state.title02());
        fields.put(UserAddResponse.CUR_TIME_FIELD, state.curTime());
        fields.put(UserAddResponse.F_NAME_FIELD, state.fName());
        fields.put(UserAddResponse.L_NAME_FIELD, state.lName());
        fields.put(UserAddResponse.USER_ID_FIELD, state.userId());
        fields.put(UserAddResponse.PASSWD_FIELD, state.passwd());
        fields.put(UserAddResponse.USR_TYPE_FIELD, state.usrType());
        fields.put(UserAddResponse.ERR_MSG_FIELD, state.errMsg());

        // ERRMSGC, the one attribute item this program assigns: MOVE DFHGREEN TO ERRMSGC at :254 and
        // nowhere else, so every other path leaves it at the map's default colour. The mnemonic comes
        // from BmsAttributes, which reproduces DFHBMSCA from IBM CICS documentation.
        Map<String, String> attributes = Map.of(colourItemOf(UserAddResponse.ERR_MSG_FIELD),
                BmsAttributes.colourMnemonic(state.errMsgColour()));

        return List.of(new ObservedSend(fields, attributes));
    }

    /**
     * The colour item paired with one symbolic-map output item.
     *
     * <p>{@code app/cpy-bms/COUSR01.CPY:91-96} declares the {@code COUSR1AO} redefinition with four
     * attribute views per field, suffixed {@code C}, {@code P}, {@code H} and {@code V}.
     * {@link FieldAttributeSetter#COLOUR_ITEM_SUFFIX} is the {@code C} of those, so {@code ERRMSGO}
     * pairs with {@code ERRMSGC} - derived rather than spelled out, because the suffix rule is the
     * map's and not this file's.
     *
     * @param outputItem an {@code xxxO} name
     * @return the paired {@code xxxC} name
     */
    private static String colourItemOf(String outputItem) {
        return outputItem.substring(0, outputItem.length()
                - FieldAttributeSetter.OUTPUT_ITEM_SUFFIX.length())
                + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
    }

    /**
     * How the transaction ended.
     *
     * <p>{@code EXEC CICS XCTL} at {@code :175-178} transfers control and does not come back, so the
     * {@code EXEC CICS RETURN} at {@code :107-110} that follows it in the source is not reached. The
     * two are not interchangeable, which is why the differ compares them.
     *
     * @param state the state {@code MAIN-PARA} ended in
     * @return the termination
     * @throws IllegalStateException if the run ended neither way
     */
    private static Termination terminationOf(ProgramState state) {
        if (state.transferred()) {
            return Termination.XCTL;
        }
        if (state.returned()) {
            return Termination.RETURN_TRANSID;
        }
        throw new IllegalStateException("The run ended neither by XCTL nor by EXEC CICS RETURN. "
                + "Every path through app/cbl/COUSR01C.cbl ends one of those two ways - the two "
                + "RETURN-TO-PREV-SCREEN arms transfer at :175 and everything else falls to :107 - "
                + "so a state reporting neither means MAIN-PARA returned early.");
    }

    /**
     * The message this run put on a terminal, on both of the channels it travels.
     *
     * <p>Two lines, and they are deliberately not one. {@code WS-MESSAGE} is declared
     * {@code PIC X(80)} at {@code :38}; {@code ERRMSGO} is {@code PIC X(78)} in the symbolic map; so
     * {@code MOVE WS-MESSAGE TO ERRMSGO} at {@code :188} narrows by two bytes, truncating on the
     * right. Recording both widths is what makes that narrowing comparable - a single line would let
     * a translation that skipped it, or that truncated on the left, pass unnoticed.
     *
     * <p>Nothing is emitted when nothing was sent. {@code ERRMSGO} reaches a terminal only through
     * {@code EXEC CICS SEND}, so on the two transferring paths the message field holds spaces no
     * terminal ever saw, and reporting them as emitted lines would assert an event that did not
     * happen. Note also that this program emits no {@code DISPLAY} line on any path: the
     * {@code DISPLAY 'RESP:' ... 'REAS:'} at {@code :268} is commented out, unlike the live
     * equivalents in {@code COUSR00C} and {@code COUSR03C}.
     *
     * @param state the state {@code MAIN-PARA} ended in
     * @return the emitted lines, in order
     */
    private static List<EmittedMessage> emitted(ProgramState state) {
        if (!state.screenSent()) {
            return List.of();
        }
        return List.of(new EmittedMessage(MessageChannel.WS_MESSAGE_80, state.message()),
                new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78, state.errMsg()));
    }

    // =============================================================================================
    // Focused assertions. Each states a property of the twenty cases as a SET, which no single case
    // can state about itself.
    // =============================================================================================

    /**
     * Exactly twenty cases, named {@code case01} through {@code case20} in order.
     *
     * <p>Asserted separately from the gate, because the gate is parameterised <em>by</em> the case
     * set: a set of four would run four green tests and report nothing wrong.
     */
    @Test
    @DisplayName("the case set is exactly case01 through case20")
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
                    .as("every case file must name the program its directory names")
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                    .as("COUSR01C keeps its decisions in the controller, which is reached as a "
                            + "plain Java object")
                    .isEqualTo(UnitKind.CONTROLLER_POJO);
            assertThat(parityCase.inputs())
                    .as("USRSEC is the program's only dataset - app/cbl/COUSR01C.cbl:39 - and every "
                            + "case seeds it, including the paths that never open it")
                    .containsOnlyKeys(USRSEC);
            assertThat(parityCase.jobParameters())
                    .as("an online transaction takes no job parameter")
                    .isEmpty();
            assertThat(parityCase.expectedReturnCode())
                    .as("COUSR01C has no CALL 'CEE3ABD' and never moves to RETURN-CODE")
                    .isZero();
        });
    }

    /**
     * Every case seeds {@code USRSEC} from {@code app/jcl/DUSRSECJ.jcl} and declares the pad that
     * brings those rows to the copybook's width.
     *
     * <p>There is no {@code usrsec} fixture in {@code app/data/ASCII} - the nine that exist are for
     * other datasets - so the ten rows are the JCL's in-stream data, which is fifty-seven characters
     * per row where {@code app/cpy/CSUSR01Y.cpy} declares eighty. The absent span is
     * {@code SEC-USR-FILLER PIC X(23)}. The pad has exactly one owner: it is declared here and
     * applied at seed time by the harness, never at comparison time, so a width disagreement in a
     * failure is always a real difference and never a missing declaration.
     */
    @Test
    @DisplayName("USRSEC is seeded from DUSRSECJ.jcl and padded from 57 to 80 exactly once")
    void everyCaseDeclaresTheUsrsecPad() {
        int seedWidth = SecUserRecord.RECORD_LENGTH - SecUserRecord.SEC_USR_FILLER_LENGTH;
        assertThat(seedWidth)
                .as("app/jcl/DUSRSECJ.jcl:L35-L44 carries 57 characters per record, which is "
                        + "CSUSR01Y's 80 less the 23-byte SEC-USR-FILLER")
                .isEqualTo(57);

        for (ParityCase parityCase : cases()) {
            DatasetInput input = parityCase.inputs().get(USRSEC);

            assertThat(input.rows())
                    .as("%s: the seed is app/jcl/DUSRSECJ.jcl:L35-L44 - ten records",
                            parityCase.caseId())
                    .hasSize(10);
            assertThat(input.rows()).allSatisfy(row -> assertThat(row)
                    .as("%s: an in-stream JCL row is stated at the width the JCL carries",
                            parityCase.caseId())
                    .hasSize(seedWidth));

            assertThat(parityCase.normalisations())
                    .as("%s: the 57-to-80 pad is the declared deviation and has one owner",
                            parityCase.caseId())
                    .singleElement()
                    .satisfies(normalisation -> {
                        assertThat(normalisation.dataset()).isEqualTo(USRSEC);
                        assertThat(normalisation.kind())
                                .isEqualTo(Normalisation.USRSEC_FILLER_PAD_57_TO_80);
                    });

            assertThat(parityCase.expectedFinalState())
                    .as("%s: every path answers the question 'what does USRSEC hold now?'",
                            parityCase.caseId())
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row.expectedBytes())
                            .as("%s: an expectation is always stated at the copybook's width, "
                                    + "because the pad is applied at seed time and never at "
                                    + "comparison time", parityCase.caseId())
                            .hasSize(SecUserRecord.RECORD_LENGTH));
        }
    }

    /**
     * Every record any case expects on the write channel is exactly eighty bytes, with
     * {@code SEC-USR-FILLER} present and space-filled.
     *
     * <p>{@code EXEC CICS WRITE} at {@code :243} sends {@code LENGTH OF SEC-USER-DATA}, which is the
     * sum of the six spans {@code app/cpy/CSUSR01Y.cpy} declares. {@code SEC-USR-FILLER X(23)} is
     * assigned by nothing in this program and must still be emitted: omit it and the record is
     * fifty-seven bytes, which is the very width the seeding deviation would otherwise disguise.
     */
    @Test
    @DisplayName("every expected write is 80 bytes with SEC-USR-FILLER space-filled")
    void everyExpectedWriteIsEightyBytes() {
        int written = 0;
        for (ParityCase parityCase : cases()) {
            for (ExpectedRecord expected : parityCase.expectedWrites()) {
                written++;
                assertThat(expected.dataset()).isEqualTo(USRSEC);
                assertThat(expected.expectedBytes())
                        .as("%s: SEC-USER-DATA is 8 + 20 + 20 + 8 + 1 + 23 bytes",
                                parityCase.caseId())
                        .hasSize(SecUserRecord.RECORD_LENGTH);
                assertThat(expected.expectedBytes()
                        .substring(SecUserRecord.SEC_USR_FILLER_OFFSET))
                        .as("%s: SEC-USR-FILLER is unassigned by COUSR01C and space-filled",
                                parityCase.caseId())
                        .isEqualTo(" ".repeat(SecUserRecord.SEC_USR_FILLER_LENGTH));
                assertThat(expected.fields())
                        .as("%s: the six spans are pinned by name as well as by image, so a "
                                + "difference names the field rather than an offset",
                                parityCase.caseId())
                        .containsOnlyKeys(SecUserRecord.FIELD_SEC_USR_ID,
                                SecUserRecord.FIELD_SEC_USR_FNAME,
                                SecUserRecord.FIELD_SEC_USR_LNAME,
                                SecUserRecord.FIELD_SEC_USR_PWD,
                                SecUserRecord.FIELD_SEC_USR_TYPE,
                                SecUserRecord.FIELD_SEC_USR_FILLER);
            }
        }
        assertThat(written)
                .as("five of the twenty cases reach a successful write - case01, case11, case12, "
                        + "case19 and case20 - and the other fifteen must write nothing at all")
                .isEqualTo(SUCCESSFUL_WRITES);
    }

    /**
     * The five blank-field messages appear in the twenty cases in the source's own order, and the
     * several-blanks case answers with the <em>first</em> of them.
     *
     * <p>An {@code EVALUATE} is ordered and the first matching {@code WHEN} wins, so the order of
     * {@code COUSR01C:117-151} is the whole of the contract. This is what makes it falsifiable
     * across the case set: {@code case02} through {@code case06} pin the five arms in order, and
     * {@code case07} - four fields blank at once - pins that only the earliest speaks.
     */
    @Test
    @DisplayName("the blank-field chain keeps source order and the first blank wins")
    void theBlankFieldChainIsOrdered() {
        List<String> chain = List.of(UserAddController.MSG_FIRST_NAME_EMPTY,
                UserAddController.MSG_LAST_NAME_EMPTY,
                UserAddController.MSG_USER_ID_EMPTY,
                UserAddController.MSG_PASSWORD_EMPTY,
                UserAddController.MSG_USER_TYPE_EMPTY);
        List<String> cursors = List.of(UserAddController.CURSOR_FNAME,
                UserAddController.CURSOR_LNAME,
                UserAddController.CURSOR_USERID,
                UserAddController.CURSOR_PASSWD,
                UserAddController.CURSOR_USRTYPE);

        Map<String, ParityCase> byId = casesById();
        for (int arm = 0; arm < chain.size(); arm++) {
            String caseId = ParityHarness.caseId(arm + 2);
            ParityCase parityCase = byId.get(caseId);

            assertThat(messageOn(parityCase, MessageChannel.WS_MESSAGE_80))
                    .as("%s pins arm %d of the chain at app/cbl/COUSR01C.cbl", caseId, arm + 1)
                    .isEqualTo(padded(chain.get(arm)));
            assertThat(parityCase.expectedResponse().cursorField())
                    .as("%s: COBOL positions the cursor by moving -1 into the length item, so the "
                            + "length item is the cursor", caseId)
                    .isEqualTo(cursors.get(arm));
            assertThat(parityCase.expectedWrites())
                    .as("%s: the arm raises WS-ERR-FLG, so the guard at :153 is false and nothing "
                            + "is written", caseId)
                    .isEmpty();
        }

        ParityCase severalBlanks = byId.get(ParityHarness.caseId(7));
        assertThat(severalBlanks.screenRequest().mapFields())
                .as("case07 must actually leave several fields blank, or it proves nothing: FNAMEI "
                        + "arrives as spaces while USERIDI and USRTYPEI are omitted entirely")
                .doesNotContainKey(UserAddRequest.USERID_FIELD)
                .doesNotContainKey(UserAddRequest.USRTYPE_FIELD);
        assertThat(severalBlanks.screenRequest().mapFields().get(UserAddRequest.FNAME_FIELD))
                .isBlank();
        assertThat(messageOn(severalBlanks, MessageChannel.WS_MESSAGE_80))
                .as("the FIRST arm answers; the other three are never reported")
                .isEqualTo(padded(UserAddController.MSG_FIRST_NAME_EMPTY));
        assertThat(severalBlanks.expectedResponse().cursorField())
                .isEqualTo(UserAddController.CURSOR_FNAME);
    }

    /**
     * {@code DUPKEY} and {@code DUPREC} reach one arm, and the arm is byte-identical for both.
     *
     * <p>{@code COUSR01C:260} and {@code :261} are two consecutive {@code WHEN} clauses with no
     * statements between them. {@code case08} forces {@code RESP} 15 and {@code case09} collides on
     * a real seeded key to obtain {@code RESP} 14, and both must produce the same message, the same
     * cursor and the same untouched dataset. Splitting them into separate arms with separate texts
     * would pass one of the two cases and fail the other, never both.
     */
    @Test
    @DisplayName("DUPKEY and DUPREC share one arm, byte for byte")
    void theTwoDuplicateResponsesShareOneArm() {
        Map<String, ParityCase> byId = casesById();
        ParityCase dupKey = byId.get(ParityHarness.caseId(8));
        ParityCase dupRec = byId.get(ParityHarness.caseId(9));

        assertThat(dupKey.screenRequest().forcedOutcomes())
                .as("DUPKEY cannot arise from any arrangement of these rows - CICS raises it on an "
                        + "alternate index, not on a base KSDS - so it is forced, and the RESP is "
                        + "stated so the two halves stay distinguishable at the repository boundary")
                .containsOnlyKeys(RepositoryOperation.WRITE);
        assertThat(dupKey.screenRequest().forcedOutcomes().get(RepositoryOperation.WRITE).resp())
                .isEqualTo(FileStatus.DUPKEY);
        assertThat(dupRec.screenRequest().forcedOutcomes())
                .as("DUPREC needs no forcing: case09 writes ADMIN001, which "
                        + "app/jcl/DUSRSECJ.jcl:L35 already seeds")
                .isEmpty();
        assertThat(dupRec.screenRequest().mapFields().get(UserAddRequest.USERID_FIELD))
                .as("the colliding key is a real seeded id, not a stipulated one")
                .isEqualTo("ADMIN001");

        assertThat(messageOn(dupKey, MessageChannel.WS_MESSAGE_80))
                .isEqualTo(padded(UserAddController.MSG_USER_ID_EXISTS));
        assertThat(messageOn(dupRec, MessageChannel.WS_MESSAGE_80))
                .as("one arm, one message - the two RESP values differ and nothing observable does")
                .isEqualTo(messageOn(dupKey, MessageChannel.WS_MESSAGE_80));
        assertThat(dupRec.expectedResponse().cursorField())
                .as("the duplicate arm moves -1 into USERIDL at :265, where the WHEN OTHER arm at "
                        + ":272 uses FNAMEL - a difference of two lines that is easy to lose")
                .isEqualTo(UserAddController.CURSOR_USERID)
                .isEqualTo(dupKey.expectedResponse().cursorField());
        assertThat(dupKey.expectedWrites()).isEmpty();
        assertThat(dupRec.expectedWrites()).isEmpty();

        ParityCase whenOther = byId.get(ParityHarness.caseId(10));
        assertThat(messageOn(whenOther, MessageChannel.WS_MESSAGE_80))
                .as("case10 is the write's WHEN OTHER at :267-273, which is a different text")
                .isEqualTo(padded(UserAddController.MSG_UNABLE_TO_ADD));
        assertThat(whenOther.expectedResponse().cursorField())
                .as("and a different cursor - FNAMEL at :272, not the USERIDL of :265")
                .isEqualTo(UserAddController.CURSOR_FNAME);
    }

    /**
     * The confirmation carries {@code DFHGREEN} and every other send carries the default colour.
     *
     * <p>{@code MOVE DFHGREEN TO ERRMSGC} at {@code :254} is the only attribute assignment anywhere
     * in {@code COUSR01C}, so a green message means the record was written and nothing else does.
     * The value is {@link BmsAttributes#DFHGREEN}, reproduced from IBM CICS documentation because
     * {@code DFHBMSCA} is absent from this repository; it is not read from any file in this
     * checkout.
     */
    @Test
    @DisplayName("DFHGREEN marks the confirmation and nothing else sets a colour")
    void onlyTheConfirmationIsGreen() {
        String colourItem = colourItemOf(UserAddResponse.ERR_MSG_FIELD);
        assertThat(colourItem)
                .as("app/cpy-bms/COUSR01.CPY:91-96 pairs each xxxO item with xxxC, xxxP, xxxH and "
                        + "xxxV; C is the colour view")
                .isEqualTo("ERRMSGC");

        String green = BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN);
        String defaultColour = BmsAttributes.colourMnemonic(BmsAttributes.DFHDFCOL);
        assertThat(green).isEqualTo("DFHGREEN");
        assertThat(defaultColour).isEqualTo("DFHDFCOL").isNotEqualTo(green);

        int greenSends = 0;
        for (ParityCase parityCase : cases()) {
            boolean wrote = !parityCase.expectedWrites().isEmpty();
            for (ScreenSend send : parityCase.expectedResponse().sends()) {
                assertThat(send.attributes())
                        .as("%s: ERRMSGC is the one attribute item this program assigns",
                                parityCase.caseId())
                        .containsOnlyKeys(colourItem);
                String colour = send.attributes().get(colourItem);
                if (wrote) {
                    greenSends++;
                    assertThat(colour)
                            .as("%s: the write succeeded, so :254 moved DFHGREEN",
                                    parityCase.caseId())
                            .isEqualTo(green);
                } else {
                    assertThat(colour)
                            .as("%s: no arm other than :254 assigns a colour, so an error message "
                                    + "keeps the map's default", parityCase.caseId())
                            .isEqualTo(defaultColour);
                }
            }
        }
        assertThat(greenSends)
                .as("each of the five successful writes confirms on a green message")
                .isEqualTo(SUCCESSFUL_WRITES);
    }

    /**
     * On every sending path {@code ERRMSGO} is the first seventy-eight characters of the eighty-byte
     * {@code WS-MESSAGE}, and on every transferring path nothing is emitted at all.
     *
     * <p>This is the {@code MOVE WS-MESSAGE TO ERRMSGO} of {@code :188} stated as an invariant over
     * the whole case set rather than as a single case, because the narrowing happens at every send
     * and a translation that truncated on the left, or padded instead, would break all of them at
     * once.
     */
    @Test
    @DisplayName("ERRMSGO is WS-MESSAGE truncated on the right, from 80 to 78")
    void theMessageNarrowsFromEightyToSeventyEight() {
        assertThat(WS_MESSAGE_LENGTH - ERRMSG_LENGTH)
                .as("the narrowing is exactly two bytes wide")
                .isEqualTo(2);

        for (ParityCase parityCase : cases()) {
            List<EmittedMessage> messages = parityCase.expectedMessages();
            boolean sent = !parityCase.expectedResponse().sends().isEmpty();

            if (!sent) {
                assertThat(messages)
                        .as("%s transfers control without sending, and ERRMSGO reaches a terminal "
                                + "only through EXEC CICS SEND", parityCase.caseId())
                        .isEmpty();
                continue;
            }

            assertThat(messages)
                    .as("%s: one line per channel - the 80-byte working-storage field and the "
                            + "78-byte screen field are different things and are compared as such",
                            parityCase.caseId())
                    .hasSize(2);
            EmittedMessage wsMessage = messages.get(0);
            EmittedMessage errMsg = messages.get(1);
            assertThat(wsMessage.channel()).isEqualTo(MessageChannel.WS_MESSAGE_80);
            assertThat(errMsg.channel()).isEqualTo(MessageChannel.SCREEN_ERRMSG_78);
            assertThat(wsMessage.text()).hasSize(WS_MESSAGE_LENGTH);
            assertThat(errMsg.text()).hasSize(ERRMSG_LENGTH);
            assertThat(errMsg.text())
                    .as("%s: COBOL truncates an alphanumeric receiver on the RIGHT, so ERRMSGO is "
                            + "the leading 78 characters and the last two bytes are dropped",
                            parityCase.caseId())
                    .isEqualTo(wsMessage.text().substring(0, ERRMSG_LENGTH));
            assertThat(parityCase.expectedResponse().sends().get(0).fields()
                    .get(UserAddResponse.ERR_MSG_FIELD))
                    .as("%s: the send carries the narrowed field, not the 80-byte one",
                            parityCase.caseId())
                    .isEqualTo(errMsg.text());
        }
    }

    /**
     * Nothing is upper-cased, and the assertion is stated three ways so it cannot be satisfied by
     * accident.
     *
     * <p>{@code case11} types a lower-case id, a mixed-case password, lower-case names and a
     * lower-case type. If an upper-casing "tidy-up" were ever added to match
     * {@code app/cbl/COSGN00C.cbl:132,135}, three independent things would change: the eighty-byte
     * record image, the confirmation text - which {@code :256} reads back from the stored id - and
     * the row's position in the key-sequenced final state, because {@code 'n'} is {@code x'6E'} and
     * sorts after every seeded key while {@code 'N'} is {@code x'4E'} and sorts into the middle.
     */
    @Test
    @DisplayName("no case normalisation: a lower-case id is stored, echoed and sorted as typed")
    void nothingIsUpperCased() {
        ParityCase parityCase = casesById().get(ParityHarness.caseId(11));
        String typedId = parityCase.screenRequest().mapFields().get(UserAddRequest.USERID_FIELD);

        assertThat(typedId)
                .as("the case must actually type a lower-case id, or it proves nothing")
                .isEqualTo("newusr01")
                .isNotEqualTo(typedId.toUpperCase(Locale.ROOT));

        ExpectedRecord write = parityCase.expectedWrites().get(0);
        assertThat(write.fields().get(SecUserRecord.FIELD_SEC_USR_ID))
                .as("MOVE USERIDI TO SEC-USR-ID at :154 is a plain move; COUSR01C contains "
                        + "FUNCTION UPPER-CASE zero times, where COSGN00C contains it twice")
                .isEqualTo(typedId);
        assertThat(write.fields().get(SecUserRecord.FIELD_SEC_USR_PWD))
                .as("the password is stored as typed and in plaintext, exactly as "
                        + "app/cpy/CSUSR01Y.cpy declares it and COSGN00C compares it")
                .isEqualTo("PaSsWoRd");
        assertThat(write.fields().get(SecUserRecord.FIELD_SEC_USR_TYPE))
                .as("even the single-character type is left as typed")
                .isEqualTo("u");

        assertThat(messageOn(parityCase, MessageChannel.WS_MESSAGE_80))
                .as("the confirmation at :255-258 reads the id back out of the record, so it "
                        + "carries the same casing")
                .isEqualTo(padded(UserAddController.MSG_USER_ADDED_PREFIX + typedId
                        + UserAddController.MSG_USER_ADDED_SUFFIX));

        List<ExpectedRecord> finalState = parityCase.expectedFinalState();
        assertThat(keyOf(finalState.get(finalState.size() - 1).expectedBytes()))
                .as("USRSEC is KEYS(8,0) INDEXED, so key sequence is observable: 'newusr01' sorts "
                        + "last, where 'NEWUSR01' would sort between ADMIN005 and USER0001")
                .isEqualTo(typedId);
    }

    /**
     * {@code STRING ... DELIMITED BY SPACE} cuts the id at its first space, so a short id leaves no
     * padding gap in the confirmation.
     *
     * <p>{@code :256} contributes {@code SEC-USR-ID DELIMITED BY SPACE}. {@code case12} stores
     * {@code 'AB'} in an eight-character field and the message must read
     * {@code 'User AB has been added ...'}. Concatenating the padded field instead - the classic
     * defect at this site, and invisible at the call site - would read
     * {@code 'User AB       has been added ...'}.
     */
    @Test
    @DisplayName("the confirmation cuts the id at its first space, not at its declared width")
    void theConfirmationIsDelimitedBySpace() {
        ParityCase parityCase = casesById().get(ParityHarness.caseId(12));

        assertThat(parityCase.expectedWrites().get(0).fields()
                .get(SecUserRecord.FIELD_SEC_USR_ID))
                .as("the record still carries the id padded to its declared eight")
                .isEqualTo("AB      ");
        assertThat(messageOn(parityCase, MessageChannel.WS_MESSAGE_80))
                .as("the message does not - DELIMITED BY SPACE stops at the first space")
                .isEqualTo(padded(UserAddController.MSG_USER_ADDED_PREFIX + "AB"
                        + UserAddController.MSG_USER_ADDED_SUFFIX));
        assertThat(UserAddController.stringDelimitedBySpace("AB      "))
                .as("the operation itself, at the boundary the message is composed from")
                .isEqualTo("AB");
        assertThat(keyOf(parityCase.expectedFinalState().get(0).expectedBytes()))
                .as("'AB      ' sorts ahead of ADMIN001, so the added row is row zero")
                .isEqualTo("AB      ");
    }

    /**
     * Conversation state travels in the payload, and a transferring path names a program and no map.
     *
     * <p>All sixteen {@code CARDDEMO-COMMAREA} fields are pinned on every case, at their declared
     * widths and totalling {@value NavigationContext#COMMAREA_LENGTH} bytes, because the differ
     * compares the navigation context in both directions - a field the case says nothing about is a
     * field nobody has checked, and the commarea is what the next transaction is handed. On the
     * {@code XCTL} paths {@code nextProgram} carries the target while {@code nextMapset} and
     * {@code nextMap} are absent <em>in the fixture</em>, because {@code :175-178} passes
     * {@code PROGRAM} and {@code COMMAREA} and no map at all; on the wire that same "no map" is seven
     * spaces at the declared width, and the two meet through
     * {@link #mapReferenceOrAbsent(String)}. On every sending path the conversation stays on
     * {@code COUSR01}/{@code COUSR1A}.
     */
    @Test
    @DisplayName("all sixteen commarea fields travel in the payload; XCTL names no map")
    void conversationStateTravelsInThePayload() {
        int transfers = 0;
        for (ParityCase parityCase : cases()) {
            ExpectedResponse response = parityCase.expectedResponse();

            assertThat(response.navigation())
                    .as("%s: every CARDDEMO-COMMAREA field is pinned, because every one of them is "
                            + "carried into the next transaction", parityCase.caseId())
                    .hasSize(COMMAREA_FIELDS.size())
                    .containsOnlyKeys(COMMAREA_FIELDS.toArray(new String[0]));

            int width = 0;
            for (String image : response.navigation().values()) {
                width += image.length();
            }
            assertThat(width)
                    .as("%s: the sixteen images total COCOM01Y's declared length",
                            parityCase.caseId())
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            if (response.termination() == Termination.XCTL) {
                transfers++;
                assertThat(response.nextProgram())
                        .as("%s: the transfer target is a response field, resolved by the client",
                                parityCase.caseId())
                        .isNotNull();
                assertThat(response.nextMapset())
                        .as("%s: XCTL passes PROGRAM and COMMAREA and no map", parityCase.caseId())
                        .isNull();
                assertThat(response.nextMap()).isNull();
                assertThat(response.sends()).isEmpty();
                assertThat(response.navigation().get(NavigationContext.PGM_CONTEXT_FIELD))
                        .as("%s: RETURN-TO-PREV-SCREEN zeroes CDEMO-PGM-CONTEXT at :174",
                                parityCase.caseId())
                        .isEqualTo(Integer.toString(NavigationContext.PGM_CONTEXT_ENTER));
                assertThat(response.navigation().get(NavigationContext.FROM_TRANID_FIELD))
                        .as("%s: and stamps CDEMO-FROM-TRANID at :170", parityCase.caseId())
                        .isEqualTo(UserAddResponse.TRANSACTION_ID);
                assertThat(response.navigation().get(NavigationContext.FROM_PROGRAM_FIELD))
                        .as("%s: and CDEMO-FROM-PROGRAM at :171", parityCase.caseId())
                        .isEqualTo(UserAddResponse.PROGRAM_NAME);
            } else {
                assertThat(response.nextMapset())
                        .as("%s: the conversation stays on this mapset", parityCase.caseId())
                        .isEqualTo(UserAddResponse.MAPSET_NAME);
                assertThat(response.nextMap()).isEqualTo(UserAddResponse.MAP_NAME);
                assertThat(response.nextProgram()).isEqualTo(UserAddResponse.PROGRAM_NAME);
            }
        }
        assertThat(transfers)
                .as("one parity case transfers - DFHPF3 at :93-95 to COADM01C - and the no-commarea "
                        + "guard at :78-80, which is the other transferring path, is driven by "
                        + "UserAddControllerTest instead")
                .isEqualTo(TRANSFERS);

        Map<String, ParityCase> byId = casesById();
        assertThat(byId.get(ParityHarness.caseId(16)).expectedResponse().nextProgram())
                .isEqualTo(UserAddController.ADMIN_MENU_PROGRAM);
        assertThat(byId.get(ParityHarness.caseId(16)).screenRequest().eibcalen())
                .as("case16 arrives WITH a communication area, so its transfer is DFHPF3's decision "
                        + "at :93 and not the guard's at :78 - the two are different branches and "
                        + "must not be confused for one")
                .isNotZero();
        assertThat(byId.get(ParityHarness.caseId(16)).screenRequest().commarea())
                .as("and the payload carries that area, because conversation state travels in the "
                        + "payload rather than in server-side session state")
                .isNotEmpty();
    }

    /**
     * The four attention identifiers this program distinguishes, driven four different ways.
     *
     * <p>{@code EVALUATE EIBAID} at {@code :90-103} has <strong>four</strong> arms -
     * {@code DFHENTER}, {@code DFHPF3}, {@code DFHPF4} and {@code WHEN OTHER} - and {@code DFHPF4}
     * is the one usually lost in summaries of this program. The default arm is reached two genuinely
     * different ways: {@code case17} presses {@code DFHPA3}, which {@code app/cpy/CSSTRPFY.cpy}
     * does not test at all even though it tests {@code DFHPA1} and {@code DFHPA2}, so
     * {@link PfKeyResolver} returns no key whatsoever; and {@code case18} presses {@code DFHPF12},
     * which it resolves to {@code PFK12} but which this program does not name. A resolver rewritten
     * to return a default key instead of nothing would pass one and fail the other.
     *
     * <p>The {@code DFHPF13} fold is checked here too, because it is the trap in the middle: the
     * copybook does <em>not</em> stop at PF12 - it folds {@code DFHPF13} through {@code DFHPF24}
     * back onto {@code PFK01} through {@code PFK12}, so {@code DFHPF13} resolves to {@code PFK01}
     * and is a third route to {@code WHEN OTHER}. Assuming it resolved to nothing would be wrong
     * about the resolver while still reaching the right arm, which is exactly the kind of wrong that
     * survives a parity run.
     *
     * <p>{@code EIBAID} is tested inline here - {@code COUSR01C} is one of the twelve programs that
     * do not {@code COPY CSSTRPFY} - so the single shared resolver has to reproduce those inline
     * tests as identical boolean outcomes.
     */
    @Test
    @DisplayName("all four EIBAID arms are driven, and WHEN OTHER two different ways")
    void everyAidArmIsDriven() {
        Map<String, ParityCase> byId = casesById();

        assertThat(byId.get(ParityHarness.caseId(1)).screenRequest().aid())
                .as("DFHENTER at :91-92")
                .isEqualTo("DFHENTER");
        assertThat(byId.get(ParityHarness.caseId(16)).screenRequest().aid())
                .as("DFHPF3 at :93-95")
                .isEqualTo("DFHPF3");
        assertThat(byId.get(ParityHarness.caseId(15)).screenRequest().aid())
                .as("DFHPF4 at :96-97 - the fourth arm, which repaints rather than transfers")
                .isEqualTo("DFHPF4");
        assertThat(byId.get(ParityHarness.caseId(17)).screenRequest().aid())
                .as("an AID CSSTRPFY does not test, so it resolves to no key at all")
                .isEqualTo("DFHPA3");
        assertThat(byId.get(ParityHarness.caseId(18)).screenRequest().aid())
                .as("an AID the resolver DOES map, to a key this program does not name")
                .isEqualTo("DFHPF12");

        assertThat(PfKeyResolver.resolve(aidByteOf("DFHENTER")))
                .contains(PfKeyResolver.AidKey.ENTER);
        assertThat(PfKeyResolver.resolve(aidByteOf("DFHPF3")))
                .contains(PfKeyResolver.AidKey.PFK03);
        assertThat(PfKeyResolver.resolve(aidByteOf("DFHPF4")))
                .contains(PfKeyResolver.AidKey.PFK04);
        assertThat(PfKeyResolver.resolve(aidByteOf("DFHPF12")))
                .as("resolvable, and still not one of the three keys :90-103 names")
                .contains(PfKeyResolver.AidKey.PFK12);
        assertThat(PfKeyResolver.resolve(aidByteOf("DFHPA3")))
                .as("app/cpy/CSSTRPFY.cpy tests twenty-eight AIDs and DFHPA3 is not one of them, "
                        + "even though DFHPA1 and DFHPA2 are - and the source EVALUATE has no WHEN "
                        + "OTHER, so nothing is set")
                .isEmpty();
        assertThat(PfKeyResolver.resolve(aidByteOf("DFHPF13")))
                .as("and the copybook does NOT stop at PF12: CSSTRPFY:L54-L77 folds PF13 through "
                        + "PF24 back onto PFK01 through PFK12, so DFHPF13 resolves to PFK01 - a "
                        + "third route to WHEN OTHER, and the one it is easiest to be wrong about")
                .contains(PfKeyResolver.AidKey.PFK01);
        assertThat(PfKeyResolver.resolve(NO_AID))
                .as("the byte the two paths that never read EIBAID are driven with")
                .isEmpty();

        for (String caseId : List.of(ParityHarness.caseId(17), ParityHarness.caseId(18))) {
            assertThat(messageOn(byId.get(caseId), MessageChannel.WS_MESSAGE_80))
                    .as("%s: both routes to WHEN OTHER produce CCDA-MSG-INVALID-KEY at :101",
                            caseId)
                    .isEqualTo(padded(SystemMessages.CCDA_MSG_INVALID_KEY));
            assertThat(byId.get(caseId).expectedResponse().cursorField())
                    .as("%s: the arm also moves -1 into FNAMEL at :100, which is the effect most "
                            + "often lost", caseId)
                    .isEqualTo(UserAddController.CURSOR_FNAME);
            assertThat(byId.get(caseId).expectedWrites())
                    .as("%s: an unrecognised key never reaches the dataset", caseId)
                    .isEmpty();
        }
    }

    /**
     * The first entry paints {@code LOW-VALUES} into the five data fields; {@code PF4} paints
     * {@code SPACES} into the same five. They are different observables.
     *
     * <p>{@code MOVE LOW-VALUES TO COUSR1AO} at {@code :85} blanks the whole output map with
     * {@code x'00'}, and {@code SEND-USRADD-SCREEN} then repopulates the six header fields and
     * {@code ERRMSGO} - so only the five data fields are still {@code LOW-VALUES} when the map goes
     * out. {@code INITIALIZE-ALL-FIELDS} at {@code :290-294} writes {@code SPACES} into those same
     * five. A translation that used one figurative constant for both would pass one of these two
     * cases and fail the other.
     */
    @Test
    @DisplayName("ENTER paints LOW-VALUES and PF4 paints SPACES into the same five fields")
    void enterAndReenterPaintDifferentBlanks() {
        Map<String, ParityCase> byId = casesById();
        ParityCase firstEntry = byId.get(ParityHarness.caseId(14));
        ParityCase cleared = byId.get(ParityHarness.caseId(15));

        assertThat(firstEntry.screenRequest().commarea()
                .get(NavigationContext.PGM_CONTEXT_FIELD))
                .as("case14 arrives with CDEMO-PGM-CONTEXT 0, so NOT CDEMO-PGM-REENTER at :83 is "
                        + "true")
                .isEqualTo(Integer.toString(NavigationContext.PGM_CONTEXT_ENTER));
        assertThat(firstEntry.expectedResponse().navigation()
                .get(NavigationContext.PGM_CONTEXT_FIELD))
                .as("and leaves with 1, because :84 flips the context - which travels in the "
                        + "payload and not in a session")
                .isEqualTo(Integer.toString(NavigationContext.PGM_CONTEXT_REENTER));
        assertThat(firstEntry.screenRequest().aid())
                .as("EIBAID is not evaluated on the first-entry path, so no key is declared: "
                        + "declaring one would imply it mattered")
                .isNull();
        assertThat(firstEntry.screenRequest().mapFields())
                .as("nothing is read from the five data fields on first entry")
                .isEmpty();
        assertThat(cleared.expectedResponse().navigation()
                .get(NavigationContext.PGM_CONTEXT_FIELD))
                .as("case15 is already re-entering and stays so: PF4 changes no commarea field")
                .isEqualTo(Integer.toString(NavigationContext.PGM_CONTEXT_REENTER));

        Map<String, String> painted = firstEntry.expectedResponse().sends().get(0).fields();
        Map<String, String> blanked = cleared.expectedResponse().sends().get(0).fields();
        for (String field : DATA_FIELDS) {
            String lowValues = painted.get(field);
            assertThat(lowValues)
                    .as("case14 %s is LOW-VALUES from :85, which is x'00' and not a space", field)
                    .isEqualTo("\u0000".repeat(lowValues.length()));
            assertThat(blanked.get(field))
                    .as("case15 %s is SPACES from :290-294, which is a different byte", field)
                    .isEqualTo(" ".repeat(lowValues.length()))
                    .isNotEqualTo(lowValues);
        }

        for (ParityCase parityCase : List.of(firstEntry, cleared)) {
            assertThat(parityCase.expectedResponse().cursorField())
                    .as("%s: both paths put the cursor on FNAMEL - :86 and :289",
                            parityCase.caseId())
                    .isEqualTo(UserAddController.CURSOR_FNAME);
            assertThat(messageOn(parityCase, MessageChannel.WS_MESSAGE_80))
                    .as("%s: neither path raises WS-ERR-FLG, so the screen carries no message",
                            parityCase.caseId())
                    .isEqualTo(padded(""));
            assertThat(parityCase.expectedWrites())
                    .as("%s: neither path reaches the write", parityCase.caseId())
                    .isEmpty();
        }
    }

    /**
     * The header is always repainted, never echoed back from the received map.
     *
     * <p>{@code case20}'s payload arrives carrying deliberate rubbish in all six header fields and
     * in {@code ERRMSGI}. {@code RECEIVE} at {@code :89} genuinely overlays the whole map, and
     * {@code POPULATE-HEADER-INFO} at {@code :214-233} then overwrites all six - so what goes out is
     * {@code 'CU01'}, the two {@code COTTL01Y} titles, {@code 'COUSR01C'}, and the pinned clock as
     * {@code mm/dd/yy} and {@code hh:mm:ss}. The same case carries the other half of the contract:
     * the user, customer, account and card fields of the commarea travel through byte for byte,
     * because {@code COUSR01C} writes only {@code CDEMO-PGM-CONTEXT} and the three
     * {@code RETURN-TO-PREV-SCREEN} fields.
     */
    @Test
    @DisplayName("the header is repainted from POPULATE-HEADER-INFO, never echoed")
    void theHeaderIsRepaintedNotEchoed() {
        ParityCase parityCase = casesById().get(ParityHarness.caseId(20));
        Map<String, String> received = parityCase.screenRequest().mapFields();
        Map<String, String> sent = parityCase.expectedResponse().sends().get(0).fields();

        assertThat(received.get(UserAddRequest.PGMNAME_FIELD))
                .as("the payload must actually carry rubbish, or the assertion proves nothing")
                .isEqualTo("GARBAGE!");
        assertThat(sent.get(UserAddResponse.TRN_NAME_FIELD))
                .isEqualTo(UserAddResponse.TRANSACTION_ID);
        assertThat(sent.get(UserAddResponse.PGM_NAME_FIELD))
                .isEqualTo(UserAddResponse.PROGRAM_NAME);
        assertThat(sent.get(UserAddResponse.TITLE01_FIELD))
                .as("CCDA-TITLE01 from app/cpy/COTTL01Y.cpy, byte for byte including both margins")
                .isEqualTo(ScreenTitles.CCDA_TITLE01);
        assertThat(sent.get(UserAddResponse.TITLE02_FIELD))
                .isEqualTo(ScreenTitles.CCDA_TITLE02);
        assertThat(sent.get(UserAddResponse.CUR_DATE_FIELD))
                .as("FUNCTION CURRENT-DATE at :216, read from the pinned clock and rendered as "
                        + "mm/dd/yy by :223-227")
                .isEqualTo("07/19/22");
        assertThat(sent.get(UserAddResponse.CUR_TIME_FIELD))
                .as("and as hh:mm:ss by :229-233")
                .isEqualTo("23:12:34");

        Map<String, String> navigation = parityCase.expectedResponse().navigation();
        Map<String, String> arriving = parityCase.screenRequest().commarea();
        for (String field : List.of(NavigationContext.USER_ID_FIELD,
                NavigationContext.USER_TYPE_FIELD,
                NavigationContext.CUST_ID_FIELD,
                NavigationContext.CUST_FNAME_FIELD,
                NavigationContext.CUST_MNAME_FIELD,
                NavigationContext.CUST_LNAME_FIELD,
                NavigationContext.ACCT_ID_FIELD,
                NavigationContext.ACCT_STATUS_FIELD,
                NavigationContext.CARD_NUM_FIELD,
                NavigationContext.LAST_MAP_FIELD,
                NavigationContext.LAST_MAPSET_FIELD)) {
            assertThat(navigation.get(field))
                    .as("%s travels through untouched: COUSR01C assigns it nowhere, and the two "
                            + "lines at :172-173 that would have set the user fields are commented "
                            + "out in the source", field)
                    .isEqualTo(arriving.get(field));
        }
    }

    /**
     * The twelve payload fields are this screen's twelve, at this screen's widths.
     *
     * <p>{@code COUSR01} has twelve named {@code DFHMDF} fields including {@code USERID} and
     * {@code PASSWD}; {@code COUSR02} has twelve but replaces {@code USERID} with {@code USRIDIN};
     * {@code COUSR03} has eleven, because the delete screen deliberately has no password field. The
     * three look interchangeable and are not, so every send is checked against
     * {@code app/cpy-bms/COUSR01.CPY}'s own {@code xxxO} names and its own {@code PICTURE} widths.
     */
    @Test
    @DisplayName("every send carries this screen's twelve fields at this screen's widths")
    void everySendCarriesTwelveFieldsAtTheirDeclaredWidths() {
        assertThat(UserAddResponse.MAP_DERIVED_FIELD_NAMES)
                .as("twelve, and USERIDO rather than the USRIDINO of COUSR02 and COUSR03")
                .hasSize(UserAddResponse.MAP_DERIVED_FIELD_COUNT)
                .contains(UserAddResponse.USER_ID_FIELD, UserAddResponse.PASSWD_FIELD);
        assertThat(UserAddResponse.MAP_DERIVED_FIELD_NAMES)
                .as("the five data items are among them, in the order PROCESS-ENTER-KEY tests them")
                .containsSubsequence(DATA_FIELDS.toArray(new String[0]));

        for (ParityCase parityCase : cases()) {
            for (ScreenSend send : parityCase.expectedResponse().sends()) {
                assertThat(send.fields())
                        .as("%s: the send is a 1:1 projection of the symbolic map's xxxO items",
                                parityCase.caseId())
                        .containsOnlyKeys(UserAddResponse.MAP_DERIVED_FIELD_NAMES
                                .toArray(new String[0]));
                for (int index = 0; index < UserAddResponse.MAP_DERIVED_FIELD_NAMES.size();
                        index++) {
                    String field = UserAddResponse.MAP_DERIVED_FIELD_NAMES.get(index);
                    int declared = UserAddResponse.MAP_DERIVED_FIELD_LENGTHS.get(index).intValue();
                    assertThat(send.fields().get(field))
                            .as("%s: %s is declared PIC X(%d) in app/cpy-bms/COUSR01.CPY",
                                    parityCase.caseId(), field, declared)
                            .hasSize(declared);
                }
            }
        }
    }

    // =============================================================================================
    // Helpers for the focused assertions.
    // =============================================================================================

    /**
     * The twenty cases by identifier, so an assertion about one case names it rather than indexing.
     *
     * @return case identifier to case
     */
    private static Map<String, ParityCase> casesById() {
        Map<String, ParityCase> byId = new LinkedHashMap<>();
        for (ParityCase parityCase : cases()) {
            byId.put(parityCase.caseId(), parityCase);
        }
        return byId;
    }

    /**
     * The text a case expects on one message channel.
     *
     * @param parityCase the case
     * @param channel    the channel
     * @return the expected text
     * @throws IllegalArgumentException if the case expects no line on that channel
     */
    private static String messageOn(ParityCase parityCase, MessageChannel channel) {
        for (EmittedMessage message : parityCase.expectedMessages()) {
            if (message.channel() == channel) {
                return message.text();
            }
        }
        throw new IllegalArgumentException("Case " + parityCase.program() + '/'
                + parityCase.caseId() + " expects no line on the " + channel + " channel, so an "
                + "assertion about that channel's text has nothing to read.");
    }

    /**
     * {@code MOVE '<literal>' TO WS-MESSAGE} - the receiver is {@code PIC X(80)}, so a shorter
     * literal is padded on the right.
     *
     * @param literal the sending literal
     * @return the literal at exactly eighty characters
     */
    private static String padded(String literal) {
        return literal + " ".repeat(WS_MESSAGE_LENGTH - literal.length());
    }

    /**
     * The primary key of one record image - {@code SEC-USR-ID}, the {@code RIDFLD} of
     * {@code COUSR01C:244}.
     *
     * @param image an eighty-byte record image
     * @return its eight-character key
     */
    private static String keyOf(String image) {
        return image.substring(KEY_OFFSET, KEY_OFFSET + KEY_LENGTH);
    }

    // =============================================================================================
    // The in-memory USRSEC.
    // =============================================================================================

    /**
     * {@code USRSEC} as a key-sequenced file, which is what {@code app/jcl/DUSRSECJ.jcl} STEP02
     * defines it as: {@code KEYS(8,0) RECORDSIZE(80,80) INDEXED}.
     *
     * <p>Ordered by key rather than by arrival, because that is what a KSDS is and because the
     * ordering is observable: {@code case11}'s lower-case id sorts after every seeded key and
     * {@code case12}'s two-character id sorts before all of them, so the position a row lands in is
     * itself an assertion about what the program stored.
     *
     * <p>Per-invocation state, created inside one {@code ParityUnit} call and reachable from nowhere
     * else, so no two cases can see each other's rows. There is no static mutable state here.
     */
    private static final class KeySequencedFile {

        /** The rows the file holds, in key sequence. Never shared outside one invocation. */
        private final List<String> rows;

        /**
         * Opens the file on the seeded rows, which the harness has already brought to the copybook's
         * eighty bytes.
         *
         * @param seeded the seeded rows, in the order the case declared them
         */
        KeySequencedFile(List<String> seeded) {
            this.rows = new ArrayList<>(Objects.requireNonNull(seeded,
                    "The seeded rows are required; a dataset holding no row is an empty list"));
            this.rows.sort(COUSR01CParityTest::byKey);
        }

        /**
         * Whether a record with this key already exists, which is what makes a write collide.
         *
         * @param key the eight-character primary key
         * @return {@code true} when the key is present
         */
        boolean holdsKey(String key) {
            for (String row : rows) {
                if (row.regionMatches(KEY_OFFSET, key, 0, KEY_LENGTH)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Inserts a record in key sequence.
         *
         * @param image the eighty-byte record image
         */
        void insert(String image) {
            rows.add(image);
            rows.sort(COUSR01CParityTest::byKey);
        }

        /**
         * The rows the file holds now.
         *
         * @return a snapshot in key sequence
         */
        List<String> rows() {
            return List.copyOf(rows);
        }
    }

    /**
     * Compares two record images by their eight-character primary key.
     *
     * @param left  one record image
     * @param right the other
     * @return the key ordering
     */
    private static int byKey(String left, String right) {
        return keyOf(left).compareTo(keyOf(right));
    }
}
