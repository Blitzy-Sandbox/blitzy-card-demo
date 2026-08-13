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
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.user.SecUserRepository;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserDeleteController;
import com.vsergeychik.carddemo.user.UserDeleteController.CursorField;
import com.vsergeychik.carddemo.user.UserDeleteController.ProgramState;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest.Cu03Info;
import com.vsergeychik.carddemo.user.dto.UserDeleteResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parity gate for {@code app/cbl/COUSR03C.cbl} - "Delete a user from USRSEC file", CICS transaction
 * {@code CU03}, projected as {@code DELETE /api/users/&#123;userId&#125;}.
 */
class COUSR03CParityTest {
    private static final String PROGRAM = "COUSR03C";

    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.CONTROLLER_POJO;

    private static final String USRSEC = "USRSEC";

    private static final String CHARSET = "US-ASCII";

    private static final String PINNED_CLOCK = "2022-07-19T23:12:35";

    private static final String CUR_DATE = "07/19/22";

    private static final String CUR_TIME = "23:12:35";

    private static final int EIBCALEN_FULL = 194;

    private static final int EIBCALEN_NONE = 0;

    private static final int TRNNAME_WIDTH = 4;

    private static final int TITLE_WIDTH = 40;

    private static final int EIGHT = 8;

    private static final int USRIDIN_WIDTH = 8;

    private static final int NAME_WIDTH = 20;

    private static final int USRTYPE_WIDTH = 1;

    private static final int ERRMSG_WIDTH = 78;

    private static final int WS_MESSAGE_WIDTH = 80;

    private static final int USRSEC_RECORD_WIDTH = 80;

    private static final String MSG_PRESS_PF5 = "Press PF5 key to delete this user ...";

    private static final String MSG_NOT_FOUND = "User ID NOT found...";

    private static final String MSG_ID_EMPTY = "User ID can NOT be empty...";

    private static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    private static final String MSG_UNABLE_TO_UPDATE = "Unable to Update User...";

    private static final String MSG_USER_PREFIX = "User ";

    private static final String MSG_DELETED_SUFFIX = " has been deleted ...";

    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below..."
            + "          ";

    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    private static final String TITLE02 = "              CardDemo                  ";

    private static final String TRANID = "CU03";

    private static final String PGMNAME = "COUSR03C";

    private static final String MAPSET = "COUSR03";

    private static final String MAP = "COUSR3A";

    private static final String SIGNON_PGM = "COSGN00C";

    private static final String ADMIN_PGM = "COADM01C";

    private static final String USER_LIST_PGM = "COUSR00C";

    private static final String SEED_ADMIN001 =
            "ADMIN001MARGARET            GOLD                PASSWORDA";

    private static final String SEED_ADMIN002 =
            "ADMIN002RUSSELL             RUSSELL             PASSWORDA";

    private static final String SEED_ADMIN003 =
            "ADMIN003RAYMOND             WHITMORE            PASSWORDA";

    private static final String SEED_ADMIN004 =
            "ADMIN004EMMANUEL            CASGRAIN            PASSWORDA";

    private static final String SEED_ADMIN005 =
            "ADMIN005GRANVILLE           LACHAPELLE          PASSWORDA";

    private static final String SEED_USER0001 =
            "USER0001LAWRENCE            THOMAS              PASSWORDU";

    private static final String SEED_USER0002 =
            "USER0002AJITH               KUMAR               PASSWORDU";

    private static final String SEED_USER0003 =
            "USER0003LAURITZ             ALME                PASSWORDU";

    private static final String SEED_USER0004 =
            "USER0004AVERARDO            MAZZI               PASSWORDU";

    private static final String SEED_USER0005 =
            "USER0005LEE                 TING                PASSWORDU";

    private static final String SEED_SHORT_KEY =
            "AB      ANNABEL             BRIGHTWELL          PASSWORDU";

    private static final List<String> SEED_ROWS = List.of(SEED_ADMIN001, SEED_ADMIN002,
            SEED_ADMIN003, SEED_ADMIN004, SEED_ADMIN005, SEED_USER0001, SEED_USER0002,
            SEED_USER0003, SEED_USER0004, SEED_USER0005);

    private static final List<String> SEED_ROWS_WITH_SHORT_KEY = List.of(SEED_ADMIN001, SEED_ADMIN002,
            SEED_ADMIN003, SEED_ADMIN004, SEED_ADMIN005, SEED_USER0001, SEED_USER0002,
            SEED_USER0003, SEED_USER0004, SEED_USER0005, SEED_SHORT_KEY);

    private static final String ABSENT_ID = "NOSUCH01";

    private static final String ERRMSGC = "ERRMSGC";

    private static final String DFHDFCOL = "DFHDFCOL";

    private static final String DFHNEUTR = "DFHNEUTR";

    private static final String DFHGREEN = "DFHGREEN";

    private static final String USRIDINL = "USRIDINL";

    private static final String FNAMEL = "FNAMEL";

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COUSR03C parity: every case produces zero differences")
    void producesNoDifferences(ParityScenario scenario) {
        Cousr03cUnit unit = new Cousr03cUnit();

        FieldDiffer.DiffResult result =
                ParityHarness.usAscii().judge(scenario.parityCase(), UNIT_KIND, unit);

        assertThat(result.count())
                .withFailMessage("%s", result.render())
                .isZero();
        assertThat(unit.trace())
                .as("the USRSEC operations %s issued, in order - a keyless DELETE is only ever "
                        + "correct against a record a preceding READ ... UPDATE left held",
                        scenario.caseId())
                .containsExactlyElementsOf(scenario.parityCase().expectedOperations());
    }

    private static List<ParityScenario> cases() {
        List<ParityScenario> scenarios = ParityHarness.casesOf(PROGRAM).stream()
                .map(ParityScenario::new)
                .toList();
        requireCompleteCaseSet(scenarios);
        return scenarios;
    }

    private static void requireCompleteCaseSet(List<ParityScenario> scenarios) {
        if (scenarios.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException(PROGRAM + " declares " + scenarios.size()
                    + " parity case(s) but the gate requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + ". A short set is not a smaller gate, it is a "
                    + "gate that passes without asking the questions.");
        }
        for (int ordinal = 1; ordinal <= scenarios.size(); ordinal++) {
            String expected = ParityHarness.caseId(ordinal);
            ParityCase declared = scenarios.get(ordinal - 1).parityCase();
            if (!expected.equals(declared.caseId())) {
                throw new IllegalStateException("Case " + ordinal + " of " + PROGRAM + " is declared "
                        + declared.caseId() + " where the set requires " + expected
                        + ". The identifiers are positional: a gap or a repeat means a case nobody "
                        + "is running, or one running twice while another runs not at all.");
            }
            if (!PROGRAM.equals(declared.program())) {
                throw new IllegalStateException("Case " + expected + " names program "
                        + declared.program() + " but this class gates " + PROGRAM
                        + ", whose cases live in " + ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + '/');
            }
        }
    }

    @Test
    @DisplayName("all twenty cases declare COUSR03C, CONTROLLER_POJO, US-ASCII and the USRSEC 57-to-80 pad")
    void everyCaseDeclaresTheSameContract() {
        List<ParityScenario> scenarios = cases();

        assertThat(scenarios).hasSize(ParityHarness.CASES_PER_PROGRAM);
        for (ParityScenario scenario : scenarios) {
            ParityCase declared = scenario.parityCase();
            assertThat(declared.program()).isEqualTo(PROGRAM);
            assertThat(declared.unitKind()).isEqualTo(UNIT_KIND);
            assertThat(declared.description()).isNotBlank();
            assertThat(declared.jobParameters())
                    .as("%s is an online case: a batch job parameter here would be meaningless",
                            scenario.caseId())
                    .isEmpty();
            assertThat(declared.expectedReturnCode())
                    .as("%s - an online transaction sets no RETURN-CODE", scenario.caseId())
                    .isZero();
            assertThat(declared.expectedWrites())
                    .as("%s - COUSR03C writes no record: it reads for update and deletes",
                            scenario.caseId())
                    .isEmpty();
            assertThat(declared.screenRequest()).isNotNull();
            assertThat(declared.screenRequest().charset()).isEqualTo(CHARSET);
            assertThat(declared.screenRequest().pinnedClock()).isEqualTo(PINNED_CLOCK);
            assertThat(declared.inputs()).containsOnlyKeys(USRSEC);
            assertThat(declared.normalisations())
                    .as("%s must declare the 57-to-80 pad: DUSRSECJ.jcl holds 57-character rows and "
                            + "CSUSR01Y declares an 80-byte record", scenario.caseId())
                    .containsExactly(new ParityCase.DatasetNormalisation(USRSEC,
                            ParityCase.Normalisation.USRSEC_FILLER_PAD_57_TO_80));
            assertThat(declared.expectedResponse()).isNotNull();
        }
    }

    @Test
    @DisplayName("the class stem matches the parity/COUSR03C resource directory")
    void resourceConventionIsHonoured() {
        assertThat(getClass().getSimpleName()).isEqualTo(PROGRAM + "ParityTest");
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(1)))
                .isEqualTo(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/case01"
                        + ParityHarness.CASE_RESOURCE_EXTENSION);
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(20)))
                .isEqualTo("parity/COUSR03C/case20.json");
        assertThat(ParityHarness.CASES_PER_PROGRAM).isEqualTo(20);
    }

    @Test
    @DisplayName("the ten USRSEC seed rows pad from 57 to 80 with a space-filled SEC-USR-FILLER")
    void seedRowsPadFromFiftySevenToEighty() {
        FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

        assertThat(ParityCase.Normalisation.USRSEC_FILLER_PAD_57_TO_80.sourceWidth()).isEqualTo(57);
        assertThat(ParityCase.Normalisation.USRSEC_FILLER_PAD_57_TO_80.targetWidth())
                .isEqualTo(USRSEC_RECORD_WIDTH);
        for (String row : SEED_ROWS_WITH_SHORT_KEY) {
            assertThat(row).hasSize(57);
            String padded = padded(row);
            assertThat(padded).hasSize(USRSEC_RECORD_WIDTH);
            SecUserRecord record =
                    SecUserRecord.decode(codec.encodeImage(padded, "a seeded USRSEC row"), codec);
            assertThat(record.secUsrFiller())
                    .as("SEC-USR-FILLER PIC X(23) is space-filled, and omitting it would make every "
                            + "downstream offset and the record width wrong")
                    .isEqualTo(blanks(23));
            assertThat(record.secUsrId()).hasSize(USRIDIN_WIDTH);
            assertThat(record.secUsrType()).hasSize(USRTYPE_WIDTH);
        }
    }

    @Test
    @DisplayName("no state survives between two requests through one controller")
    void stateIsNotCarriedBetweenRequests() {
        FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);
        Map<String, String> rows = new LinkedHashMap<>();
        for (String row : SEED_ROWS) {
            String image = padded(row);
            rows.put(image.substring(0, USRIDIN_WIDTH), image);
        }
        Cousr03cUnit unit = new Cousr03cUnit();
        SecUserRepository repository =
                unit.stubRepository(codec, ParityHarness.FIXTURE_CHARSET, rows, null);
        UserDeleteController controller = new UserDeleteController(repository,
                ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK)));

        ProgramState first = controller.mainPara(
                reentryRequest(codec, fields(MAP_USRIDIN, "USER0002")), CicsAid.DFHPF5, null);
        ProgramState second = controller.mainPara(
                reentryRequest(codec, fields(MAP_USRIDIN, "USER0003")), CicsAid.DFHENTER, null);

        assertThat(first.message()).isEqualTo(pad(MSG_USER_PREFIX + "USER0002" + MSG_DELETED_SUFFIX,
                WS_MESSAGE_WIDTH));
        assertThat(first.errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);
        assertThat(rows).doesNotContainKey("USER0002");

        assertThat(second.message())
                .as("the second request paints its own prompt: the first request's green confirmation "
                        + "is not carried forward")
                .isEqualTo(pad(MSG_PRESS_PF5, WS_MESSAGE_WIDTH));
        assertThat(second.errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);
        assertThat(second.response().usrIdIn()).isEqualTo("USER0003");
        assertThat(second.heldRecord())
                .as("the hold the first request took died with it; a hold that outlived a request "
                        + "would be a lock nobody releases")
                .isPresent();
        assertThat(unit.trace()).containsExactly(
                new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.READ_FOR_UPDATE, "USER0002"),
                new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.DELETE, "USER0002"),
                new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.READ_FOR_UPDATE, "USER0003"));
    }

    @Test
    @DisplayName("the carried commarea is 160 bytes plus a 34-byte extension and round-trips exactly")
    void commareaIsOneHundredAndSixtyBytesPlusThirtyFour() {
        FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(Cu03Info.LENGTH).isEqualTo(34);
        assertThat(NavigationContext.COMMAREA_LENGTH + Cu03Info.LENGTH).isEqualTo(EIBCALEN_FULL);

        NavigationContext carried = commareaOf(reentryCommarea(), codec);
        assertThat(carried.toFixedWidth(codec)).hasSize(NavigationContext.COMMAREA_LENGTH);
        assertThat(navigationImages(carried, extensionOf(reentryCommarea(), codec), codec))
                .containsAllEntriesOf(reentryCommarea());
    }

    private record ParityScenario(ParityCase parityCase) {
        String caseId() {
            return parityCase.caseId();
        }

        @Override
        public String toString() {
            String description = parityCase.description();
            int firstStop = description.indexOf(". ");
            return caseId() + " - "
                    + (firstStop < 0 ? description : description.substring(0, firstStop));
        }
    }

    private static final class Cousr03cUnit implements ParityHarness.ParityUnit {
        private final List<ParityCase.ExpectedOperation> trace = new ArrayList<>();

        List<ParityCase.ExpectedOperation> trace() {
            return List.copyOf(trace);
        }

        /**
         * Seeds the dataset, constructs the controller, calls {@code MAIN-PARA}, and records the outcome.
         *
         * @param invocation the seeded datasets, the pinned clock, the codec and the recorder
         * @return the recorded outcome; never {@code null}
         */
        @Override
        public ParityHarness.UnitOutcome invoke(ParityHarness.Invocation invocation) {
            FixedWidthCodec codec = invocation.codec();
            Map<String, String> rows = seededRows(invocation);
            SecUserRepository repository =
                    stubRepository(codec, invocation.charset(), rows, invocation);
            UserDeleteController controller =
                    new UserDeleteController(repository, invocation.clock());

            UserDeleteRequest request = inboundScreen(invocation, codec);
            String usrSelected = invocation.commarea().get(CU03_USR_SELECTED);

            ProgramState state = invocation.aid() == null
                    ? controller.mainPara(request, CicsAid.DFHNULL, usrSelected)
                    : controller.mainPara(request, aidByte(invocation.aid()), usrSelected);

            ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();
            for (String line : state.displayLines()) {
                recorder.display(line);
            }
            recorder.message(new ParityCase.EmittedMessage(ParityCase.MessageChannel.WS_MESSAGE_80,
                    state.message()));
            recorder.message(new ParityCase.EmittedMessage(
                    ParityCase.MessageChannel.SCREEN_ERRMSG_78, state.response().errMsg()));
            return recorder.response(observedResponse(state, codec))
                    .finalState(USRSEC, SecUserRecord.LAYOUT, List.copyOf(rows.values()))
                    .returnCode(0)
                    .build();
        }

        SecUserRepository stubRepository(FixedWidthCodec codec,
                                        Charset charset,
                                        Map<String, String> rows,
                                        ParityHarness.Invocation invocation) {
            SecUserRepository repository = mock(SecUserRepository.class);
            when(repository.datasetCharset()).thenReturn(charset);
            when(repository.read(anyString())).thenThrow(new IllegalStateException(
                    "COUSR03C issues no non-locking READ: its only read is EXEC CICS READ ... UPDATE "
                            + "at app/cbl/COUSR03C.cbl:269-278, and the keyless DELETE at :307-311 "
                            + "acts on the hold that read takes"));
            when(repository.deleteHeld(any(HeldRecord.class))).thenThrow(new IllegalStateException(
                    "the delete is reached from the hold - HeldRecord.deleteHeld() - because "
                            + "app/cbl/COUSR03C.cbl:307-311 names no RIDFLD and therefore removes the "
                            + "record the task is holding, not one identified by key"));
            when(repository.rewrite(any(SecUserRecord.class))).thenThrow(new IllegalStateException(
                    "COUSR03C never rewrites a record; that is COUSR02C's UPDATE-USER-INFO. This "
                            + "screen reads for update and deletes"));

            when(repository.readForUpdate(anyString())).thenAnswer(call -> {
                String key = call.getArgument(0);
                trace.add(new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.READ_FOR_UPDATE, key.strip()));
                if (forces(invocation, ParityCase.RepositoryOperation.READ_FOR_UPDATE)) {
                    return forcedRead(invocation.forcedOutcome(
                            ParityCase.RepositoryOperation.READ_FOR_UPDATE));
                }
                String image = rows.get(key);
                if (image == null) {
                    return ReadResult.notFound();
                }
                SecUserRecord record = SecUserRecord.decode(
                        codec.encodeImage(image, "the seeded USRSEC row for key " + key.strip()),
                        codec);
                return ReadResult.held(record, heldRecord(record, key, rows, invocation));
            });
            return repository;
        }

        private HeldRecord heldRecord(SecUserRecord record,
                                      String key,
                                      Map<String, String> rows,
                                      ParityHarness.Invocation invocation) {
            HeldRecord hold = mock(HeldRecord.class);
            when(hold.record()).thenReturn(record);
            when(hold.datasetName()).thenReturn(USRSEC);
            when(hold.deleteHeld()).thenAnswer(call -> {
                trace.add(new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.DELETE, record.secUsrId().strip()));
                if (forces(invocation, ParityCase.RepositoryOperation.DELETE)) {
                    return forcedWrite(
                            invocation.forcedOutcome(ParityCase.RepositoryOperation.DELETE));
                }
                rows.remove(key);
                return WriteResult.written();
            });
            return hold;
        }

        private static boolean forces(ParityHarness.Invocation invocation,
                                     ParityCase.RepositoryOperation operation) {
            return invocation != null && invocation.hasForcedOutcome(operation);
        }
    }

    private static final String CU03_PREFIX = "CDEMO-CU03-";

    private static final String CU03_USRID_FIRST = CU03_PREFIX + "USRID-FIRST";

    private static final String CU03_USRID_LAST = CU03_PREFIX + "USRID-LAST";

    private static final String CU03_PAGE_NUM = CU03_PREFIX + "PAGE-NUM";

    private static final String CU03_NEXT_PAGE_FLG = CU03_PREFIX + "NEXT-PAGE-FLG";

    private static final String CU03_USR_SEL_FLG = CU03_PREFIX + "USR-SEL-FLG";

    private static final String CU03_USR_SELECTED = CU03_PREFIX + "USR-SELECTED";

    private static final String MAP_TRNNAME = "TRNNAMEI";

    private static final String MAP_TITLE01 = "TITLE01I";

    private static final String MAP_CURDATE = "CURDATEI";

    private static final String MAP_PGMNAME = "PGMNAMEI";

    private static final String MAP_TITLE02 = "TITLE02I";

    private static final String MAP_CURTIME = "CURTIMEI";

    private static final String MAP_USRIDIN = "USRIDINI";

    private static final String MAP_FNAME = "FNAMEI";

    private static final String MAP_LNAME = "LNAMEI";

    private static final String MAP_USRTYPE = "USRTYPEI";

    private static final String MAP_ERRMSG = "ERRMSGI";

    private static Map<String, String> seededRows(ParityHarness.Invocation invocation) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String row : invocation.dataset(USRSEC).rows()) {
            rows.put(row.substring(0, USRIDIN_WIDTH), row);
        }
        return rows;
    }

    private static UserDeleteRequest inboundScreen(ParityHarness.Invocation invocation,
                                                  FixedWidthCodec codec) {
        if (invocation.eibcalen() == EIBCALEN_NONE) {
            return null;
        }
        return screenOf(invocation.mapFields(), invocation.commarea(), codec);
    }

    private static UserDeleteRequest screenOf(Map<String, String> fields,
                                              Map<String, String> commarea,
                                              FixedWidthCodec codec) {
        return new UserDeleteRequest(fields.get(MAP_TRNNAME),
                fields.get(MAP_TITLE01),
                fields.get(MAP_CURDATE),
                fields.get(MAP_PGMNAME),
                fields.get(MAP_TITLE02),
                fields.get(MAP_CURTIME),
                fields.get(MAP_USRIDIN),
                fields.get(MAP_FNAME),
                fields.get(MAP_LNAME),
                fields.get(MAP_USRTYPE),
                fields.get(MAP_ERRMSG),
                commareaOf(commarea, codec),
                null,
                extensionOf(commarea, codec));
    }

    private static UserDeleteRequest reentryRequest(FixedWidthCodec codec,
                                                   Map<String, String> fields) {
        return screenOf(fields, reentryCommarea(), codec);
    }

    private static NavigationContext commareaOf(Map<String, String> images, FixedWidthCodec codec) {
        Map<String, String> general = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : images.entrySet()) {
            if (!entry.getKey().startsWith(CU03_PREFIX)) {
                general.put(entry.getKey(), entry.getValue());
            }
        }
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, general));
    }

    private static Cu03Info extensionOf(Map<String, String> images, FixedWidthCodec codec) {
        return new Cu03Info(images.get(CU03_USRID_FIRST),
                images.get(CU03_USRID_LAST),
                codec.decodePic9AsInt(images.get(CU03_PAGE_NUM)),
                images.get(CU03_NEXT_PAGE_FLG),
                images.get(CU03_USR_SEL_FLG),
                images.get(CU03_USR_SELECTED));
    }

    private static Map<String, String> navigationImages(NavigationContext context,
                                                        Cu03Info extension,
                                                        FixedWidthCodec codec) {
        Map<String, String> images =
                new LinkedHashMap<>(codec.deserialise(NavigationContext.LAYOUT,
                        context.toFixedWidth(codec)));
        images.putAll(extension.fieldImages());
        return images;
    }

    private static FieldDiffer.ObservedResponse observedResponse(ProgramState state,
                                                                 FixedWidthCodec codec) {
        UserDeleteResponse response = state.response();
        List<FieldDiffer.ObservedSend> sends = new ArrayList<>(state.sendCount());
        for (UserDeleteController.Send send : state.sends()) {
            sends.add(new FieldDiffer.ObservedSend(send.fields(), send.attributes()));
        }
        return new FieldDiffer.ObservedResponse(blankToNull(response.nextProgram()),
                blankToNull(response.nextMapset()),
                blankToNull(response.nextMap()),
                navigationImages(state.commarea(), state.cu03Info(), codec),
                sends,
                state.cursorField().map(CursorField::lengthItem).orElse(null),
                termination(state));
    }

    private static ParityCase.Termination termination(ProgramState state) {
        if (state.isTransferred()) {
            return ParityCase.Termination.XCTL;
        }
        return state.isReturned() ? ParityCase.Termination.RETURN_TRANSID : null;
    }

    private static byte aidByte(String mnemonic) {
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("'" + mnemonic + "' is not a DFHAID mnemonic, so no "
                + "EIBAID byte stands for it. CicsAid reproduces the copybook; name one of its "
                + "constants.");
    }

    private static ReadResult forcedRead(ParityCase.ForcedOutcome forced) {
        CicsResponse response = respOf(forced);
        return switch (forced.outcome()) {
            case NOT_FOUND -> ReadResult.of(FileStatus.NOT_FOUND, response);
            case END_OF_FILE -> ReadResult.of(FileStatus.END_OF_FILE, response);
            case DUPLICATE -> ReadResult.of(FileStatus.DUPLICATE, response);
            case OTHER -> ReadResult.of(SecUserRepository.PERMANENT_ERROR_STATUS, response);
            case OK -> throw new IllegalArgumentException("A successful read is not forced: seed the "
                    + "row and let the read find it, so the record the program paints is the record "
                    + "the case put in the file.");
        };
    }

    private static WriteResult forcedWrite(ParityCase.ForcedOutcome forced) {
        CicsResponse response = respOf(forced);
        return switch (forced.outcome()) {
            case NOT_FOUND -> WriteResult.of(FileStatus.NOT_FOUND, response);
            case END_OF_FILE -> WriteResult.of(FileStatus.END_OF_FILE, response);
            case DUPLICATE -> WriteResult.of(FileStatus.DUPLICATE, response);
            case OTHER -> WriteResult.of(SecUserRepository.PERMANENT_ERROR_STATUS, response);
            case OK -> throw new IllegalArgumentException("A successful delete is not forced: it is "
                    + "the removal of the seeded row, which is what the final state asserts.");
        };
    }

    private static CicsResponse respOf(ParityCase.ForcedOutcome forced) {
        if (forced.resp() == null) {
            return CicsResponse.none();
        }
        return CicsResponse.reported(forced.resp(),
                forced.resp2() == null ? FileStatus.NO_REASON_CODE : forced.resp2());
    }

    private static String blanks(int width) {
        return " ".repeat(width);
    }

    private static String pad(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException("'" + value + "' is " + value.length()
                    + " characters and the receiver is PIC X(" + width + "). A COBOL MOVE would "
                    + "truncate on the right; state the truncated image outright rather than letting "
                    + "an expectation shorten it.");
        }
        return value + blanks(width - value.length());
    }

    private static String padded(String row) {
        return pad(row, USRSEC_RECORD_WIDTH);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ' && character != '\u0000') {
                return value;
            }
        }
        return null;
    }

    private static Map<String, String> commareaImages(String fromTranid,
                                                      String fromProgram,
                                                      String toProgram,
                                                      String pgmContext,
                                                      String usrSelected) {
        Map<String, String> images = new LinkedHashMap<>();
        images.put("CDEMO-FROM-TRANID", pad(fromTranid, TRNNAME_WIDTH));
        images.put("CDEMO-FROM-PROGRAM", pad(fromProgram, EIGHT));
        images.put("CDEMO-TO-TRANID", TRANID);
        images.put("CDEMO-TO-PROGRAM", pad(toProgram, EIGHT));
        images.put("CDEMO-USER-ID", "ADMIN001");
        images.put("CDEMO-USER-TYPE", "A");
        images.put("CDEMO-PGM-CONTEXT", pgmContext);
        images.put("CDEMO-CUST-ID", "000000000");
        images.put("CDEMO-CUST-FNAME", blanks(25));
        images.put("CDEMO-CUST-MNAME", blanks(25));
        images.put("CDEMO-CUST-LNAME", blanks(25));
        images.put("CDEMO-ACCT-ID", "00000000000");
        images.put("CDEMO-ACCT-STATUS", " ");
        images.put("CDEMO-CARD-NUM", "0000000000000000");
        images.put("CDEMO-LAST-MAP", MAP);
        images.put("CDEMO-LAST-MAPSET", MAPSET);
        images.put(CU03_USRID_FIRST, "ADMIN001");
        images.put(CU03_USRID_LAST, "USER0005");
        images.put(CU03_PAGE_NUM, "00000001");
        images.put(CU03_NEXT_PAGE_FLG, "N");
        images.put(CU03_USR_SEL_FLG, " ");
        images.put(CU03_USR_SELECTED, pad(usrSelected, USRIDIN_WIDTH));
        return images;
    }

    private static Map<String, String> reentryCommarea() {
        return commareaImages("CU00", USER_LIST_PGM, PGMNAME, "1", blanks(USRIDIN_WIDTH));
    }

    private static Map<String, String> fields(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Field images come in name-and-image pairs; "
                    + pairs.length + " value(s) were given");
        }
        Map<String, String> images = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            images.put(pairs[index], pairs[index + 1]);
        }
        return images;
    }

    private static Map<String, String> headerFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("TRNNAMEO", TRANID);
        fields.put("TITLE01O", TITLE01);
        fields.put("CURDATEO", CUR_DATE);
        fields.put("PGMNAMEO", PGMNAME);
        fields.put("TITLE02O", TITLE02);
        fields.put("CURTIMEO", CUR_TIME);
        return fields;
    }

    private static List<ParityCase.ScreenSend> sends(int count,
                                                     Map<String, String> painted,
                                                     String colour) {
        List<ParityCase.ScreenSend> screens = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (index == count - 1) {
                Map<String, String> fields = headerFields();
                fields.putAll(painted);
                screens.add(new ParityCase.ScreenSend(fields, Map.of(ERRMSGC, colour)));
            } else {
                screens.add(new ParityCase.ScreenSend(headerFields(), Map.of()));
            }
        }
        return screens;
    }

    private static List<ParityCase.ExpectedRecord> finalState(List<String> rows) {
        List<ParityCase.ExpectedRecord> records = new ArrayList<>(rows.size());
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            records.add(new ParityCase.ExpectedRecord(USRSEC, rowIndex, Map.of(),
                    padded(rows.get(rowIndex))));
        }
        return records;
    }

    private static List<ParityCase.ExpectedDataset> datasets(int rowCount) {
        return List.of(new ParityCase.ExpectedDataset(USRSEC,
                ParityCase.DatasetChannel.FINAL_STATE, rowCount, USRSEC_RECORD_WIDTH));
    }

    private static Map<String, ParityCase.DatasetInput> input(List<String> rows) {
        return Map.of(USRSEC, ParityCase.DatasetInput.ofRows(rows));
    }

    private static List<ParityCase.DatasetNormalisation> normalisations() {
        return List.of(new ParityCase.DatasetNormalisation(USRSEC,
                ParityCase.Normalisation.USRSEC_FILLER_PAD_57_TO_80));
    }

    private static ParityCase.ScreenRequest screenRequest(String aid,
                                                          Map<String, String> fields,
                                                          Map<String, String> commarea,
                                                          Map<ParityCase.RepositoryOperation,
                                                                  ParityCase.ForcedOutcome> forced) {
        return new ParityCase.ScreenRequest(EIBCALEN_FULL, aid, PINNED_CLOCK, CHARSET, commarea,
                fields, forced);
    }

    private static ParityCase parityCase(String caseId,
                                         String description,
                                         List<String> seedRows,
                                         ParityCase.ScreenRequest request,
                                         ParityCase.ExpectedResponse response,
                                         List<String> finalRows,
                                         List<ParityCase.EmittedMessage> messages) {
        return new ParityCase(PROGRAM, caseId, description, UNIT_KIND, input(seedRows), Map.of(),
                request, response, List.of(), finalState(finalRows), 0, messages, normalisations(),
                datasets(finalRows.size()));
    }

}
