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
 * The twenty-case behavioural parity gate for {@code app/cbl/COUSR01C.cbl} - CICS transaction {@code CU01},
 * "Add a new Regular/Admin user to USRSEC file", projected onto {@code POST /api/users}.
 */
final class COUSR01CParityTest {
    private static final String PROGRAM = "COUSR01C";

    private static final String USRSEC = SecUserRepository.CICS_FILE_NAME;

    private static final int KEY_LENGTH = SecUserRecord.KEY_LENGTH;

    private static final int KEY_OFFSET = SecUserRecord.KEY_OFFSET;

    private static final Map<String, Byte> AID_BYTES = aidBytesByMnemonic();

    private static final byte NO_AID = CicsAid.DFHNULL;

    private static final int WS_MESSAGE_LENGTH = UserAddResponse.WS_MESSAGE_LENGTH;

    private static final int ERRMSG_LENGTH = UserAddResponse.ERR_MSG_LENGTH;

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

    private static final List<String> DATA_FIELDS = List.of(UserAddResponse.F_NAME_FIELD,
            UserAddResponse.L_NAME_FIELD,
            UserAddResponse.USER_ID_FIELD,
            UserAddResponse.PASSWD_FIELD,
            UserAddResponse.USR_TYPE_FIELD);

    private static final int SUCCESSFUL_WRITES = 5;

    private static final int TRANSFERS = 1;

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    static List<Arguments> declaredCases() {
        List<ParityCase> loaded = cases();
        List<Arguments> arguments = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            arguments.add(Arguments.of(parityCase.caseId(), parityCase));
        }
        return arguments;
    }

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

    private static UnitOutcome invokeUserAdd(Invocation invocation) {
        FixedWidthCodec codec = invocation.codec();
        SeededDataset seeded = invocation.dataset(USRSEC);
        KeySequencedFile file = new KeySequencedFile(seeded.rows());

        UserAddController controller = new UserAddController(
                stubbedRepository(invocation, codec, file), invocation.clock());

        ProgramState state = controller.mainPara(requestOf(invocation, codec),
                aidByteOf(invocation.aid()), invocation.eibcalen());

        return record(invocation, codec, file, state);
    }

    private static SecUserRepository stubbedRepository(Invocation invocation, FixedWidthCodec codec,
                                                      KeySequencedFile file) {
        SecUserRepository repository = mock(SecUserRepository.class);
        when(repository.datasetCharset()).thenReturn(codec.charset());
        when(repository.add(any(SecUserRecord.class))).thenAnswer(call -> {
            SecUserRecord offered = call.getArgument(0);
            String image = codec.decodeImage(SecUserRecord.encode(offered, codec),
                    "the SEC-USER-DATA record offered to " + USRSEC);

            if (invocation.hasForcedOutcome(RepositoryOperation.WRITE)) {
                return forced(invocation.forcedOutcome(RepositoryOperation.WRITE));
            }
            if (file.holdsKey(keyOf(image))) {
                return WriteResult.duplicateRecord();
            }
            file.insert(image);
            return WriteResult.written();
        });
        return repository;
    }

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

    private static NavigationContext commareaOf(Map<String, String> commarea,
                                                FixedWidthCodec codec) {
        if (commarea.isEmpty()) {
            return null;
        }
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, commarea));
    }

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

    private static Map<String, Byte> aidBytesByMnemonic() {
        Map<String, Byte> byMnemonic = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            byMnemonic.put(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(byMnemonic);
    }

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

        recorder.returnCode(0);

        for (EmittedMessage message : emitted(state)) {
            recorder.message(message);
        }
        return recorder.build();
    }

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

    private static String mapReferenceOrAbsent(String image) {
        return image == null || image.isBlank() ? null : image.trim();
    }

    private static Map<String, String> navigationOf(FixedWidthCodec codec,
                                                    NavigationContext commarea) {
        return codec.deserialise(NavigationContext.LAYOUT, commarea.toFixedWidth(codec));
    }

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

        Map<String, String> attributes = Map.of(colourItemOf(UserAddResponse.ERR_MSG_FIELD),
                BmsAttributes.colourMnemonic(state.errMsgColour()));

        return List.of(new ObservedSend(fields, attributes));
    }

    private static String colourItemOf(String outputItem) {
        return outputItem.substring(0, outputItem.length()
                - FieldAttributeSetter.OUTPUT_ITEM_SUFFIX.length())
                + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
    }

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

    private static List<EmittedMessage> emitted(ProgramState state) {
        if (!state.screenSent()) {
            return List.of();
        }
        return List.of(new EmittedMessage(MessageChannel.WS_MESSAGE_80, state.message()),
                new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78, state.errMsg()));
    }

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

    private static Map<String, ParityCase> casesById() {
        Map<String, ParityCase> byId = new LinkedHashMap<>();
        for (ParityCase parityCase : cases()) {
            byId.put(parityCase.caseId(), parityCase);
        }
        return byId;
    }

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

    private static String padded(String literal) {
        return literal + " ".repeat(WS_MESSAGE_LENGTH - literal.length());
    }

    private static String keyOf(String image) {
        return image.substring(KEY_OFFSET, KEY_OFFSET + KEY_LENGTH);
    }

    /**
     * {@code USRSEC} as a key-sequenced file, which is what {@code app/jcl/DUSRSECJ.jcl} STEP02 defines it
     * as: {@code KEYS(8,0) RECORDSIZE(80,80) INDEXED}.
     */
    private static final class KeySequencedFile {
        private final List<String> rows;

        KeySequencedFile(List<String> seeded) {
            this.rows = new ArrayList<>(Objects.requireNonNull(seeded,
                    "The seeded rows are required; a dataset holding no row is an empty list"));
            this.rows.sort(COUSR01CParityTest::byKey);
        }

        boolean holdsKey(String key) {
            for (String row : rows) {
                if (row.regionMatches(KEY_OFFSET, key, 0, KEY_LENGTH)) {
                    return true;
                }
            }
            return false;
        }

        void insert(String image) {
            rows.add(image);
            rows.sort(COUSR01CParityTest::byKey);
        }

        List<String> rows() {
            return List.copyOf(rows);
        }
    }

    private static int byKey(String left, String right) {
        return keyOf(left).compareTo(keyOf(right));
    }
}
