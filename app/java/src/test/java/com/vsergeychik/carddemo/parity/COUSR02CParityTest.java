package com.vsergeychik.carddemo.parity;

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
 * The behavioural-parity gate for {@code app/cbl/COUSR02C.cbl} - "Update a user in USRSEC file", CICS
 * transaction {@code CU02}, 414 lines, six {@code EXEC CICS} commands - against its translation
 * {@link UserUpdateController}.
 */
final class COUSR02CParityTest {
    private static final String PROGRAM = "COUSR02C";

    private static final String USRSEC = SecUserRepository.CICS_FILE_NAME;

    private static final Map<String, Byte> AID_BY_MNEMONIC = invertAidMnemonics();

    private static final byte NO_AID_DECLARED = CicsAid.DFHNULL;

    private static final int DEFAULT_RESP2 = FileStatus.NO_REASON_CODE;

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

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

    private static List<String> expectedCaseIds() {
        List<String> ids = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            ids.add(ParityHarness.caseId(ordinal));
        }
        return ids;
    }

    private static UnitOutcome driveUserUpdate(Invocation invocation) {
        String where = invocation.program() + '/' + invocation.caseId();
        FixedWidthCodec codec = invocation.codec();
        SeededDataset seeded = invocation.dataset(USRSEC);

        Map<String, SecUserRecord> store = seedStore(seeded, codec, where);
        List<String> stored = new ArrayList<>(1);

        SecUserRepository repository = stubRepository(invocation, store, stored, codec, where);
        UserUpdateController controller = new UserUpdateController(repository, invocation.clock());

        Cu02Info cu02Info = cu02InfoOf(invocation.commarea(), where);
        UserUpdateRequest request = requestOf(invocation, cu02Info, codec, where);
        byte eibAid = attentionIdentifierOf(invocation.aid(), where);

        ProgramState state = controller.handle(request, invocation.eibcalen(), eibAid, cu02Info);

        UnitOutcome.Builder recorder = invocation.recorder();
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

    private static List<String> imagesOf(Map<String, SecUserRecord> store, FixedWidthCodec codec) {
        List<String> images = new ArrayList<>(store.size());
        for (SecUserRecord record : store.values()) {
            images.add(imageOf(record, codec));
        }
        return images;
    }

    private static String imageOf(SecUserRecord record, FixedWidthCodec codec) {
        return codec.decodeImage(SecUserRecord.encode(record, codec), "dataset " + USRSEC);
    }

    private static String keyOf(SecUserRecord record, FixedWidthCodec codec) {
        return codec.movePicX(record.secUsrId(), SecUserRecord.KEY_LENGTH);
    }

    private static SecUserRepository stubRepository(Invocation invocation,
                                                    Map<String, SecUserRecord> store,
                                                    List<String> stored,
                                                    FixedWidthCodec codec,
                                                    String where) {
        SecUserRepository repository = mock(SecUserRepository.class);

        when(repository.readForUpdate(anyString())).thenAnswer(call ->
                readForUpdate(invocation, store, stored, codec, call.getArgument(0, String.class),
                        where));
        // The keyed rewrite is refused, and that is an assertion too. EXEC CICS REWRITE at :360-366
        // carries no RIDFLD, so the row it replaces is the one the READ ... UPDATE at :322-331 holds and
        // the translation issues it through that hold. A keyed rewrite here would be a different command:
        // on the fall-through at :215-237, where the read failed and nothing is held, it would replace a
        // row this task never read - which is exactly what CICS refuses with INVREQ.
        when(repository.rewrite(any(SecUserRecord.class))).thenAnswer(call -> {
            throw unsupportedOperation(where, "rewrite", "app/cbl/COUSR02C.cbl:360-366 issues "
                    + "EXEC CICS REWRITE with no RIDFLD, so the record replaced is the one the "
                    + "READ ... UPDATE at :322-331 holds. The rewrite therefore goes through the "
                    + "HeldRecord that read returned, and with nothing held it is the INVREQ condition "
                    + "rather than a keyed write");
        });

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

    private static ReadResult readForUpdate(Invocation invocation, Map<String, SecUserRecord> store,
                                            List<String> stored, FixedWidthCodec codec, String userId,
                                            String where) {
        String key = codec.movePicX(userId == null ? "" : userId, SecUserRecord.KEY_LENGTH);
        if (invocation.hasForcedOutcome(RepositoryOperation.READ_FOR_UPDATE)) {
            ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.READ_FOR_UPDATE);
            if (forced.outcome() != FileStatus.Outcome.OK) {
                return ReadResult.of(statusOf(forced.outcome()), responseOf(forced));
            }
            SecUserRecord forcedRecord = store.get(key);
            if (forcedRecord == null) {
                throw new IllegalStateException(where + ": the case forces DFHRESP(NORMAL) on the "
                        + "read-for-update, but the seeded " + USRSEC + " holds no record under the key "
                        + "the program asked for. A successful read must return a record, and the only "
                        + "record it can honestly return is the seeded one - so either seed that key or "
                        + "force NOT_FOUND, which is the arm this input actually reaches.");
            }
            return heldForUpdate(invocation, store, stored, codec, forcedRecord, where);
        }
        SecUserRecord found = store.get(key);
        return found == null
                ? ReadResult.notFound()
                : heldForUpdate(invocation, store, stored, codec, found, where);
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
     * <p><strong>The row replaced is the held one.</strong> The command carries no {@code RIDFLD}, so this
     * is reached from the {@link HeldRecord} the locking read handed back and the row it writes is the row
     * that read returned - not one selected by the key inside {@code SEC-USER-DATA}.
     *
     * @param invocation the invocation, consulted for a forced outcome
     * @param store      the seeded records, mutated in place on success
     * @param stored     collects the image of every record actually stored
     * @param codec      the codec carrying the case's code page
     * @param held       the record the {@code READ ... UPDATE} is holding, which is the row the command
     *                   replaces because it carries no {@code RIDFLD}
     * @param record     {@code SEC-USER-DATA} as the program has it after the four change tests
     * @param where      the case, for a diagnostic
     * @return the outcome
     */
    private static WriteResult rewriteHeld(Invocation invocation, Map<String, SecUserRecord> store,
                                          List<String> stored, FixedWidthCodec codec,
                                          SecUserRecord held, SecUserRecord record, String where) {
        String key = keyOf(record, codec);
        if (invocation.hasForcedOutcome(RepositoryOperation.REWRITE)) {
            ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.REWRITE);
            if (forced.outcome() != FileStatus.Outcome.OK) {
                return WriteResult.of(statusOf(forced.outcome()), responseOf(forced));
            }
            return applyRewrite(store, stored, codec, key, record, where);
        }
        // The record area may name only the row the read is holding. UPDATE-USER-INFO at :219-234 changes
        // the first name, the last name, the password and the user type and never SEC-USR-ID, so a
        // differing key is a request the paragraph cannot have produced: CICS answers INVREQ and the file
        // is untouched.
        if (!keyOf(held, codec).equals(key)) {
            return WriteResult.of(SecUserRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ));
        }
        if (!store.containsKey(key)) {
            return WriteResult.notFound();
        }
        return applyRewrite(store, stored, codec, key, record, where);
    }

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
     * {@code READ ... UPDATE} acquires a hold for the duration of the unit of work, and {@code :360}
     * issues {@code EXEC CICS REWRITE DATASET(WS-USRSEC-FILE)} with <strong>no {@code RIDFLD}</strong> -
     * which is CICS for "replace the record this task holds". The hold is recorded on
     * {@link ProgramState#hold()} and the rewrite is issued <em>through</em> it, so the stand-in wires the
     * held record's own rewrite to the seeded store and leaves the repository's keyed rewrite refused.
     *
     * @param invocation the invocation, consulted for a forced outcome on the rewrite
     * @param store      the seeded records, mutated in place by a successful rewrite
     * @param stored     collects the image of every record actually stored
     * @param codec      the codec carrying the case's code page
     * @param record     the record read, which is also the row the rewrite replaces
     * @param where      the case, for a diagnostic
     * @return the outcome, carrying both the record and the hold
     */
    private static ReadResult heldForUpdate(Invocation invocation, Map<String, SecUserRecord> store,
                                            List<String> stored, FixedWidthCodec codec,
                                            SecUserRecord record, String where) {
        HeldRecord hold = mock(HeldRecord.class);
        when(hold.record()).thenReturn(record);
        when(hold.rewrite(any(SecUserRecord.class))).thenAnswer(call ->
                rewriteHeld(invocation, store, stored, codec, record,
                        call.getArgument(0, SecUserRecord.class), where));
        return ReadResult.held(record, hold);
    }

    private static String statusOf(FileStatus.Outcome outcome) {
        return switch (outcome) {
            case OK -> FileStatus.OK;
            case END_OF_FILE -> FileStatus.END_OF_FILE;
            case NOT_FOUND -> FileStatus.NOT_FOUND;
            case DUPLICATE -> FileStatus.DUPLICATE;
            case OTHER -> SecUserRepository.PERMANENT_ERROR_STATUS;
        };
    }

    private static CicsResponse responseOf(ForcedOutcome forced) {
        int resp = forced.resp() == null ? conventionalResp(forced.outcome()) : forced.resp();
        int resp2 = forced.resp2() == null ? DEFAULT_RESP2 : forced.resp2();
        return CicsResponse.reported(resp, resp2);
    }

    private static int conventionalResp(FileStatus.Outcome outcome) {
        return switch (outcome) {
            case OK -> FileStatus.NORMAL;
            case END_OF_FILE -> FileStatus.ENDFILE;
            case NOT_FOUND -> FileStatus.NOTFND;
            case DUPLICATE -> FileStatus.DUPREC;
            case OTHER -> FileStatus.INVREQ;
        };
    }

    private static IllegalStateException unsupportedOperation(String where, String operation,
                                                              String why) {
        return new IllegalStateException(where + ": the translation called SecUserRepository."
                + operation + ", which COUSR02C never does. " + why + ". COUSR02C issues exactly six "
                + "EXEC CICS commands - RETURN, XCTL, SEND MAP, RECEIVE MAP, READ ... UPDATE and "
                + "REWRITE - so readForUpdate and rewrite are the whole of its file access.");
    }

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

    private static String aidTokenOf(String mnemonic, String where) {
        return PfKeyResolver.resolve(attentionIdentifierOf(mnemonic, where))
                .map(AidKey::token)
                .orElse(null);
    }

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

    private static Map<String, String> attributesSetBy(Send send) {
        if (send.errMsgColour() == BmsAttributes.DFHDFCOL) {
            return Map.of();
        }
        return send.attributes();
    }

    private static String blankToNull(String span) {
        return span == null || span.isBlank() ? null : span.trim();
    }

    private static final String CU02_USRID_FIRST_FIELD = "CDEMO-CU02-USRID-FIRST";

    private static final String CU02_USRID_LAST_FIELD = "CDEMO-CU02-USRID-LAST";

    private static final String CU02_PAGE_NUM_FIELD = "CDEMO-CU02-PAGE-NUM";

    private static final String CU02_NEXT_PAGE_FLG_FIELD = "CDEMO-CU02-NEXT-PAGE-FLG";

    private static final String CU02_USR_SEL_FLG_FIELD = "CDEMO-CU02-USR-SEL-FLG";

    private static final String CU02_USR_SELECTED_FIELD = "CDEMO-CU02-USR-SELECTED";

    private static final Set<String> CU02_INFO_FIELDS = Set.of(CU02_USRID_FIRST_FIELD,
            CU02_USRID_LAST_FIELD, CU02_PAGE_NUM_FIELD, CU02_NEXT_PAGE_FLG_FIELD,
            CU02_USR_SEL_FLG_FIELD, CU02_USR_SELECTED_FIELD);

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
