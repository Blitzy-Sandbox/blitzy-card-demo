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
 * The twenty-case behavioural parity gate for {@code app/cbl/COSGN00C.cbl} - CICS transaction {@code CC00},
 * "Signon Screen for the CardDemo Application", projected onto {@code POST /api/signon}.
 */
final class COSGN00CParityTest {
    private static final String PROGRAM = "COSGN00C";

    private static final String USRSEC = SecUserRepository.CICS_FILE_NAME;

    private static final int KEY_LENGTH = SecUserRecord.KEY_LENGTH;

    private static final int SEED_ROW_WIDTH =
            SecUserRecord.RECORD_LENGTH - SecUserRecord.SEC_USR_FILLER_LENGTH;

    private static final int SEED_ROW_COUNT = 10;

    private static final int SEED_ADMIN_COUNT = 5;

    private static final String APPLID = "CICSAWS1";

    private static final String SYSID = "AWS1";

    private static final int WS_MESSAGE_LENGTH = SignOnService.MESSAGE_LENGTH;

    private static final int ERRMSG_LENGTH = SignOnResponse.ERRMSG_LENGTH;

    private static final String LENGTH_ITEM_SUFFIX = "L";

    private static final char LOW_VALUE = '\u0000';

    private static final char SPACE = ' ';

    private static final Map<String, Byte> AID_BYTES = aidBytesByMnemonic();

    private static final byte NO_AID = CicsAid.DFHNULL;

    private static final Map<String, String> CURSOR_ITEMS = cursorItemsByLabel();

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

    private static final String USERID_INPUT_ITEM = "USERIDI";

    private static final String PASSWD_INPUT_ITEM = "PASSWDI";

    private static final String PASSWD_OUTPUT_ITEM = SignOnResponse.PASSWD_FIELD;

    private static final List<String> SCREEN_ITEMS = screenItems();

    private static final int SCREEN_ITEM_COUNT = SignOnResponse.MAPSET_NAMED_FIELD_COUNT;

    private static final int SUCCESSFUL_SIGN_ONS = 6;

    private static final int SCREEN_SENDS = 13;

    private static final int PLAIN_TEXT_SENDS = 1;

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

    private static Map<String, ParityCase> casesById() {
        Map<String, ParityCase> byId = new LinkedHashMap<>();
        for (ParityCase parityCase : cases()) {
            byId.put(parityCase.caseId(), parityCase);
        }
        return byId;
    }

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

    private record Run(SignOnOutcome outcome, ScreenResponse<SignOnResponse> screen, int reads) {
    }

    private static Run run(Invocation invocation, SignOnRequest request) {
        int[] reads = new int[1];
        RecordingSignOnService service =
                new RecordingSignOnService(stubbedRepository(invocation, reads));
        SignOnController controller = new SignOnController(service, invocation.clock(), APPLID,
                SYSID, invocation.charset());

        ScreenResponse<SignOnResponse> screen = controller.signOn(request, null, null);

        return new Run(service.requireOutcome(), screen, reads[0]);
    }

    private static final class RecordingSignOnService extends SignOnService {
        private SignOnOutcome captured;

        RecordingSignOnService(SecUserRepository secUserRepository) {
            super(secUserRepository);
        }

        @Override
        public SignOnOutcome handle(SignOnInput input) {
            SignOnOutcome outcome = super.handle(input);
            captured = outcome;
            return outcome;
        }

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

    private static void requireForcedResp(ForcedOutcome outcome, int expected) {
        if (outcome.resp() != null && outcome.resp() != expected) {
            throw new IllegalArgumentException("A case forces " + outcome.outcome()
                    + " on the read against " + USRSEC + " while declaring RESP " + outcome.resp()
                    + ". app/cbl/COSGN00C.cbl:221-257 evaluates WS-RESP-CD against the literals 0 "
                    + "and 13, so that arm is RESP " + expected + " and no other number reaches it.");
        }
    }

    private static void requireNoForcedResp(ForcedOutcome outcome) {
        if (outcome.resp() != null) {
            throw new IllegalArgumentException("A case forces OTHER on the read against " + USRSEC
                    + " while declaring RESP " + outcome.resp() + ". The WHEN OTHER arm at "
                    + "app/cbl/COSGN00C.cbl:252 is every response the two preceding arms did not "
                    + "name, and it treats them all alike, so pinning one number would assert a "
                    + "distinction the program does not make.");
        }
    }

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
                aidImageOf(invocation.aid()));
    }

    private static NavigationContext commareaOf(Invocation invocation) {
        if (invocation.eibcalen() == 0) {
            return null;
        }
        FixedWidthCodec codec = invocation.codec();
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, invocation.commarea()));
    }

    private static String aidImageOf(String mnemonic) {
        if (mnemonic == null) {
            return null;
        }
        return String.valueOf((char) (aidByteOf(mnemonic) & 0xFF));
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

    private static UnitOutcome record(Invocation invocation, Run run) {
        UnitOutcome.Builder recorder = invocation.recorder();

        recorder.finalStateUnchanged(invocation.dataset(USRSEC), SecUserRecord.LAYOUT);

        if (run.outcome().plainTextSent()) {
            recorder.message(new EmittedMessage(MessageChannel.WS_MESSAGE_80,
                    run.outcome().message()));
        } else {
            recorder.response(observed(invocation, run));
        }

        recorder.returnCode(0);
        return recorder.build();
    }

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

    private static Map<String, String> navigationOf(FixedWidthCodec codec,
                                                    NavigationContext commarea) {
        return codec.deserialise(NavigationContext.LAYOUT, commarea.toFixedWidth(codec));
    }

    private static List<ObservedSend> sendsOf(Invocation invocation, Run run) {
        if (!run.outcome().screenPainted()) {
            return List.of();
        }
        return List.of(ObservedSend.ofFields(screenFieldsOf(invocation, run)));
    }

    private static Map<String, String> screenFieldsOf(Invocation invocation, Run run) {
        SignOnResponse screen = run.screen().screen();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(SignOnResponse.TRNNAME_FIELD, screen.trnName());
        fields.put(SignOnResponse.TITLE01_FIELD, screen.title01());
        fields.put(SignOnResponse.CURDATE_FIELD, screen.curDate());
        fields.put(SignOnResponse.PGMNAME_FIELD, screen.pgmName());
        fields.put(SignOnResponse.TITLE02_FIELD, screen.title02());
        fields.put(SignOnResponse.CURTIME_FIELD, screen.curTime());
        fields.put(SignOnResponse.APPLID_FIELD, screen.applId());
        fields.put(SignOnResponse.SYSID_FIELD, screen.sysId());
        fields.put(SignOnResponse.USERID_FIELD, screen.userId());
        fields.put(PASSWD_OUTPUT_ITEM, screen.passwd());
        fields.put(SignOnResponse.ERRMSG_FIELD, screen.errMsg());
        return fields;
    }

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

    private static void assertOverlayItemsCarryTheReceivedImages(Invocation invocation, Run run) {
        SignOnResponse screen = run.screen().screen();
        boolean mapSent = !screen.nextMap().isBlank();
        if (!mapSent) {
            assertThat(screen.userId())
                    .as("no map was sent, so USERIDO carries nothing")
                    .isEqualTo(lowValues(SignOnResponse.USERID_LENGTH));
            assertThat(screen.passwd())
                    .as("no map was sent, so PASSWDO carries nothing")
                    .isEqualTo(lowValues(SignOnResponse.PASSWD_LENGTH));
            return;
        }
        boolean reset = run.screen().screenMetadata().resetAllOutputFields();
        assertThat(screen.userId())
                .as("USERIDO is the USERIDI span: COSGN0AO REDEFINES COSGN0AI at COSGN00.CPY:85")
                .isEqualTo(credentialItemImage(invocation, USERID_INPUT_ITEM, reset,
                        SignOnResponse.USERID_LENGTH));
        assertThat(screen.passwd())
                .as("PASSWDO is the PASSWDI span, transmitted dark by ATTRB=(DRK,FSET,UNPROT)")
                .isEqualTo(credentialItemImage(invocation, PASSWD_INPUT_ITEM, reset,
                        SignOnRequest.PASSWD_LENGTH));
    }

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

    private static void assertRunInvariants(Invocation invocation, SignOnRequest request, Run run) {
        SignOnOutcome outcome = run.outcome();
        SignOnResponse screen = run.screen().screen();
        String caseId = invocation.caseId();

        assertAttentionIdentifierSurvivedTheRoundTrip(invocation, request, outcome);
        assertExactlyOneExit(caseId, outcome);
        assertRoleAndTargetAgree(caseId, outcome, screen);
        assertMessageNarrowedFromEightyToSeventyEight(caseId, outcome, screen);
        assertThePlainTextSendIsEightyBytes(caseId, outcome, screen);
        assertOverlayItemsCarryTheReceivedImages(invocation, run);
        assertTheFileWasReadOnlyWhereTheSourceReadsIt(caseId, outcome, run.reads());
        assertTheCommareaIsOneHundredAndSixtyBytes(caseId, invocation, screen);
        assertTheErrorFlagFollowsTheSourceExactly(caseId, outcome);
    }

    private static void assertTheErrorFlagFollowsTheSourceExactly(String caseId,
                                                                  SignOnOutcome outcome) {
        String message = outcome.message();
        boolean raisedBySource = message.equals(padded(SignOnService.MSG_ENTER_USER_ID))
                || message.equals(padded(SignOnService.MSG_ENTER_PASSWORD))
                || message.equals(padded(SignOnService.MSG_USER_NOT_FOUND))
                || message.equals(padded(SignOnService.MSG_UNABLE_TO_VERIFY))
                || message.equals(padded(SystemMessages.CCDA_MSG_INVALID_KEY));

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

    private static void assertAttentionIdentifierSurvivedTheRoundTrip(Invocation invocation,
                                                                     SignOnRequest request,
                                                                     SignOnOutcome outcome) {
        assertThat(outcome.resolvedAid())
                .as("%s: the case declares %s and the payload carries its one-character image, and the "
                        + "byte the controller read back out of that character has to be the one the "
                        + "case named - CSSTRPFY folds PF13..PF24 onto PF1..PF12, so a round trip "
                        + "through the folded token could drive the wrong arm of EVALUATE EIBAID",
                        invocation.caseId(), invocation.aid())
                .isEqualTo(PfKeyResolver.resolve(aidByteOf(invocation.aid())));
    }

    private static void assertExactlyOneExit(String caseId, SignOnOutcome outcome) {
        int exits = (outcome.screenPainted() ? 1 : 0)
                + (outcome.plainTextSent() ? 1 : 0)
                + (outcome.signedOn() ? 1 : 0);
        assertThat(exits)
                .as("%s: COSGN00C either sends the map (:151), sends unformatted text (:164) or "
                        + "transfers control (:231) - exactly one, on every path", caseId)
                .isOne();
    }

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

    private static void assertMessageNarrowedFromEightyToSeventyEight(String caseId,
                                                                      SignOnOutcome outcome,
                                                                      SignOnResponse screen) {
        assertThat(outcome.message())
                .as("%s: WS-MESSAGE is PIC X(80) - app/cbl/COSGN00C.cbl:38", caseId)
                .hasSize(WS_MESSAGE_LENGTH);
        assertThat(screen.errMsg())
                .as("%s: ERRMSGO is PIC X(78) - app/cpy-bms/COSGN00.CPY:152", caseId)
                .hasSize(ERRMSG_LENGTH);
        if (!outcome.screenPainted()) {
            assertThat(screen.errMsg())
                    .as("%s: no SEND MAP ran, so ERRMSGO holds the :78 spaces", caseId)
                    .isEqualTo(spaces(ERRMSG_LENGTH));
            return;
        }
        assertThat(screen.errMsg())
                .as("%s: COBOL truncates an alphanumeric MOVE on the right, so the narrower receiver "
                        + "keeps the leading bytes and drops the trailing two", caseId)
                .isEqualTo(outcome.message().substring(0, ERRMSG_LENGTH));
    }

    private static void assertThePlainTextSendIsEightyBytes(String caseId,
                                                            SignOnOutcome outcome,
                                                            SignOnResponse screen) {
        assertThat(screen.plainText())
                .as("%s: WS-MESSAGE is PIC X(80) - app/cbl/COSGN00C.cbl:38", caseId)
                .hasSize(WS_MESSAGE_LENGTH);
        if (outcome.plainTextSent()) {
            assertThat(screen.plainText())
                    .as("%s: the PF3 arm at :88-90 moves CCDA-MSG-THANK-YOU into WS-MESSAGE and "
                            + ":164-169 sends all eighty bytes of it", caseId)
                    .isEqualTo(outcome.message());
            return;
        }
        assertThat(screen.plainText())
                .as("%s: SEND-PLAIN-TEXT is performed from :90 and nowhere else, so no other path "
                        + "transmits text", caseId)
                .isEqualTo(spaces(WS_MESSAGE_LENGTH));
    }

    private static void assertTheFileWasReadOnlyWhereTheSourceReadsIt(String caseId,
                                                                      SignOnOutcome outcome,
                                                                      int reads) {
        assertThat(reads)
                .as("%s: READ-USER-SEC-FILE is performed once, from :139, and only when :138 finds "
                        + "the error flag off - so a run reads USRSEC once or not at all", caseId)
                .isEqualTo(outcome.readOutcome().isPresent() ? 1 : 0);
    }

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

    private static String lowValues(int width) {
        return String.valueOf(LOW_VALUE).repeat(width);
    }

    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    private static String padded(String text) {
        Objects.requireNonNull(text, "A message literal is required");
        return text + String.valueOf(SPACE).repeat(WS_MESSAGE_LENGTH - text.length());
    }

    private static String named(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Map<String, Byte> aidBytesByMnemonic() {
        Map<String, Byte> byMnemonic = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            byMnemonic.put(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(byMnemonic);
    }

    private static Map<String, String> cursorItemsByLabel() {
        Map<String, String> byLabel = new LinkedHashMap<>();
        byLabel.put(SignOnController.CURSOR_USERID,
                requireLengthItemOf(SignOnController.CURSOR_USERID, CursorField.USER_ID));
        byLabel.put(SignOnController.CURSOR_PASSWD,
                requireLengthItemOf(SignOnController.CURSOR_PASSWD, CursorField.PASSWORD));
        return Collections.unmodifiableMap(byLabel);
    }

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

    private static List<String> screenItems() {
        return Collections.unmodifiableList(new ArrayList<>(SignOnResponse.MAP_FIELDS));
    }

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

    private static String errMsgOf(ParityCase parityCase) {
        ScreenSend send = sendOf(parityCase);
        return send == null ? null : send.fields().get(SignOnResponse.ERRMSG_FIELD);
    }

    private static String navigationOf(ParityCase parityCase, String field) {
        return parityCase.expectedResponse().navigation().get(field);
    }

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

    @Test
    @DisplayName("the blank-field chain keeps source order and the second arm falls through the first")
    void theBlankFieldChainIsOrderedAndTheSecondArmFallsThroughTheFirst() {
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

        ParityCase blankPasswordAgain = byId.get(ParityHarness.caseId(8));
        assertThat(errMsgOf(blankPasswordAgain))
                .as("case08 pins the second arm a second time, :123-127, so the arm does not rest on "
                        + "one row of seed data alone")
                .isEqualTo(truncated(SignOnService.MSG_ENTER_PASSWORD))
                .isNotEqualTo(truncated(SignOnService.MSG_ENTER_USER_ID));
        assertThat(blankPasswordAgain.screenRequest().mapFields())
                .as("case08 must supply a user id too, or reaching :123 proves nothing about the "
                        + "ordering")
                .containsKey(USERID_INPUT_ITEM);
        assertThat(blankPasswordAgain.screenRequest().mapFields().get(PASSWD_INPUT_ITEM))
                .as("case08 drives the SPACES half of ':123' rather than the LOW-VALUES half, so the "
                        + "field must be transmitted and blank rather than absent")
                .isNotNull()
                .isBlank();
        assertThat(blankPasswordAgain.expectedResponse().cursorField())
                .as("case08: :126 moves -1 into PASSWDL")
                .isEqualTo(CursorField.PASSWORD.lengthItemName().orElseThrow());
        assertThat(blankPasswordAgain.screenRequest().mapFields().get(USERID_INPUT_ITEM))
                .as("case08 must type a different seeded key from case07, or the two are one case "
                        + "written twice")
                .isNotEqualTo(blankPassword.screenRequest().mapFields().get(USERID_INPUT_ITEM));

        for (ParityCase parityCase : List.of(blankUserId, blankPassword, blankPasswordAgain)) {
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

    @Test
    @DisplayName("FUNCTION UPPER-CASE covers both fields and runs on every path")
    void upperCaseNormalisationIsUnconditionalAndCoversBothFields() {
        for (ParityCase parityCase : cases()) {
            if (parityCase.expectedResponse() == null) {
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
        ParityCase mixedCase = byId.get(ParityHarness.caseId(10));
        ParityCase lowerPasswordOnly = byId.get(ParityHarness.caseId(16));

        for (ParityCase parityCase : List.of(mixedCase, lowerPasswordOnly)) {
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

        ParityCase rejectedButNormalised = byId.get(ParityHarness.caseId(9));
        String rejectedId = rejectedButNormalised.screenRequest().mapFields().get(USERID_INPUT_ITEM);
        assertThat(rejectedButNormalised.expectedResponse().nextProgram())
                .as("case09 must not sign on, or it cannot witness that :132-136 runs on a rejected "
                        + "path")
                .isNull();
        assertThat(rejectedId)
                .as("case09 must type a user id, or :132-134 has nothing to normalise")
                .isNotNull();
        assertThat(navigationOf(rejectedButNormalised, NavigationContext.USER_ID_FIELD))
                .as("case09: the error flag is on, the file was never read, and CDEMO-USER-ID still "
                        + "carries FUNCTION UPPER-CASE(USERIDI) - which is the whole point of "
                        + ":132-136 sitting after END-EVALUATE at :130")
                .isEqualTo(SignOnService.upperCase(rejectedId));
    }

    @Test
    @DisplayName("the password comparison is plaintext and byte for byte")
    void thePasswordComparisonIsPlaintextAndByteForByte() {
        Map<String, ParityCase> byId = casesById();
        ParityCase signsOn = byId.get(ParityHarness.caseId(2));
        ParityCase differsAtWidth = byId.get(ParityHarness.caseId(14));
        ParityCase strictPrefix = byId.get(ParityHarness.caseId(15));

        String seeded = seededPasswordOf(signsOn);
        assertThat(SignOnService.upperCase(
                signsOn.screenRequest().mapFields().get(PASSWD_INPUT_ITEM)))
                .as("case02 types the seeded password exactly, so :223 compares equal with no "
                        + "transformation interposed on either side")
                .isEqualTo(seeded);
        assertThat(signsOn.expectedResponse().nextProgram())
                .as("case02 therefore reaches the XCTL at :231-239")
                .isNotNull();

        String sameWidth = SignOnService.upperCase(
                differsAtWidth.screenRequest().mapFields().get(PASSWD_INPUT_ITEM));
        assertThat(sameWidth)
                .as("case14 must type a password of the seeded width, so the mismatch it drives is "
                        + "unambiguously a value mismatch and not an artefact of length")
                .hasSize(seeded.length())
                .isNotEqualTo(seeded);
        assertThat(differingCharacters(sameWidth, seeded))
                .as("case14: and it must actually differ somewhere, which is what an "
                        + "always-succeeding comparison cannot survive")
                .isPositive();
        assertThat(sameWidth.charAt(sameWidth.length() - 1))
                .as("case14: the two images agree on their last character, so a comparison reduced "
                        + "to a suffix would sign this request on")
                .isEqualTo(seeded.charAt(seeded.length() - 1));
        assertRejectedAtThePasswordArm(differsAtWidth, "case14");

        String padded = SignOnService.upperCase(
                strictPrefix.screenRequest().mapFields().get(PASSWD_INPUT_ITEM));
        String keyed = padded.strip();
        assertThat(padded)
                .as("case15 types into the same PIC X(08) item, so CICS delivers what was keyed "
                        + "space-padded to eight - the width is never the difference here")
                .hasSize(seeded.length())
                .isNotEqualTo(seeded);
        assertThat(keyed)
                .as("case15 must key fewer characters than the seeded password holds, or the "
                        + "trimming and prefix failure modes are untested")
                .isNotEmpty()
                .hasSizeLessThan(seeded.length());
        assertThat(seeded)
                .as("case15: the %s that was keyed is the leading part of the seeded value, so a "
                        + "comparison that stripped the trailing spaces and then compared a prefix "
                        + "would sign this request on - :223 compares all eight characters and "
                        + "refuses it", keyed)
                .startsWith(keyed);
        assertRejectedAtThePasswordArm(strictPrefix, "case15");
    }

    private static void assertRejectedAtThePasswordArm(ParityCase parityCase, String label) {
        assertThat(parityCase.expectedResponse().nextProgram())
                .as("%s takes the ELSE at :241 and never transfers control", label)
                .isNull();
        assertThat(errMsgOf(parityCase))
                .as("%s: :242-243 moves 'Wrong Password. Try again ...' into WS-MESSAGE", label)
                .isEqualTo(truncated(SignOnService.MSG_WRONG_PASSWORD));
        assertThat(parityCase.expectedResponse().cursorField())
                .as("%s: :244 moves -1 into PASSWDL, so the cursor returns to the password", label)
                .isEqualTo(CursorField.PASSWORD.lengthItemName().orElseThrow());
    }

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
                .as("six of the twenty sign on; a change in that number means a case stopped "
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
                .as("case15 must arrive with CDEMO-PGM-CONTEXT at the re-entry value, or the "
                        + "placement of the reset at :228 is not being tested")
                .isEqualTo("1");
        assertThat(reentered.expectedResponse().nextProgram())
                .as("case15 must be refused at :223, or it would not reach past the MOVE ZEROS")
                .isNull();
        assertThat(navigationOf(reentered, NavigationContext.PGM_CONTEXT_FIELD))
                .as("case15: :228 sits inside the matching branch of :223, so the refused path "
                        + "never reaches it and the field leaves exactly as it arrived - which is "
                        + "what makes the reset asserted for the signing-on cases a property of "
                        + "that branch rather than of the paragraph")
                .isEqualTo("1");
        assertThat(navigationOf(reentered, NavigationContext.USER_TYPE_FIELD))
                .as("case15 arrives with a user type the row it reads does not carry, so a :227 "
                        + "that ran outside its branch would overwrite it and be caught")
                .isEqualTo(NavigationContext.USER_TYPE_ADMIN)
                .isNotEqualTo(NavigationContext.USER_TYPE_USER);
        assertThat(navigationOf(reentered, NavigationContext.USER_ID_FIELD).strip())
                .as("case15: :134 is outside that branch and unconditional, so the stale inbound "
                        + "user id is overwritten even though the sign-on was refused")
                .isEqualTo("USER0003")
                .isNotEqualTo(reentered.screenRequest().commarea()
                        .get(NavigationContext.USER_ID_FIELD).strip());

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
                    .as("case14: COSGN00C writes CDEMO-USER-ID at :134 and, on the signing-on path "
                            + "alone, the five at :224-228; it touches no other field on any path, "
                            + "so %s comes back as it arrived", arrived.getKey())
                    .isEqualTo(arrived.getValue());
        }
    }

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

    @Test
    @DisplayName("every painted screen carries all eleven named items and no attribute item")
    void everyPaintedScreenCarriesElevenItems() {
        assertThat(SCREEN_ITEMS)
                .as("all eleven projected items, in the order app/cpy-bms/COSGN00.CPY:86-152 "
                        + "declares them")
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
                .as("the projection carries all eleven, PASSWDO among them, because "
                        + "COSGN0AO REDEFINES COSGN0AI makes the receive paint it")
                .hasSize(SignOnResponse.MAP_FIELD_COUNT)
                .contains(PASSWD_OUTPUT_ITEM);

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
                .as("thirteen of the twenty reach SEND-SIGNON-SCREEN at :145-157")
                .isEqualTo(SCREEN_SENDS);
    }

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
                .as("six sign on")
                .isEqualTo(SUCCESSFUL_SIGN_ONS);
        assertThat(painted)
                .as("thirteen paint the screen")
                .isEqualTo(SCREEN_SENDS);
        assertThat(transfers + painted + plainText)
                .as("and the three account for every case: MAIN-PARA reaches exactly one terminal "
                        + "action on every path")
                .isEqualTo(ParityHarness.CASES_PER_PROGRAM);
    }

    @Test
    @DisplayName("the invalid-key arm is WHEN OTHER: every unnamed key lands there, and only those")
    void theInvalidKeyArmIsTheDefault() {
        List<ParityCase> unnamedKey = new ArrayList<>();
        List<String> mnemonics = new ArrayList<>();
        for (ParityCase parityCase : cases()) {
            if (parityCase.screenRequest() == null
                    || parityCase.screenRequest().eibcalen() == 0
                    || parityCase.screenRequest().aid() == null) {
                continue;
            }
            byte aid = aidByteOf(parityCase.screenRequest().aid());
            if (aid == CicsAid.DFHENTER || aid == CicsAid.DFHPF3) {
                assertThat(errMsgOf(parityCase))
                        .as("%s: :85-90 matches this key by name, so it cannot reach the WHEN OTHER "
                                + "arm at :91-94 - if it shows the invalid-key text, the EVALUATE is "
                                + "not ordered as the source orders it", parityCase.caseId())
                        .isNotEqualTo(truncated(SystemMessages.CCDA_MSG_INVALID_KEY));
                continue;
            }
            unnamedKey.add(parityCase);
            mnemonics.add(parityCase.caseId() + '=' + parityCase.screenRequest().aid());
        }

        assertThat(unnamedKey)
                .as("the WHEN OTHER arm at :91-94 has to be reached by something, or every "
                        + "expectation below holds vacuously. Reached by: %s", mnemonics)
                .isNotEmpty();

        for (ParityCase parityCase : unnamedKey) {
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

    private static String truncated(String literal) {
        return padded(literal).substring(0, ERRMSG_LENGTH);
    }

    private static String rightPadded(String text, int width) {
        return text + String.valueOf(SPACE).repeat(width - text.length());
    }

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
