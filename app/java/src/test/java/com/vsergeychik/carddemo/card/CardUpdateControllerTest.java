package com.vsergeychik.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.CardUpdateController.Conversation;
import com.vsergeychik.carddemo.card.CardUpdateController.PaintedScreen;
import com.vsergeychik.carddemo.card.CardUpdateService.WriteOutcome;
import com.vsergeychik.carddemo.card.CardUpdateService.WriteResult;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CommArea;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse.ScreenField;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.testsupport.ConversationStateSealFixture;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link CardUpdateController} - the {@code COCRDUPC} / {@code CCUP} credit-card update screen.
 */
@DisplayName("CardUpdateController - COCRDUPC / CCUP")
class CardUpdateControllerTest {
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(CHARSET);

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T14:35:07Z"), ZoneOffset.UTC);

    private static final String CARD_NUMBER = "4000000000000001";

    private static final String ACCOUNT_NUMBER = "00000000011";

    private CardRepository repository;
    private CardUpdateService service;
    private CardUpdateController controller;

    @BeforeEach
    void setUp() {
        repository = mock(CardRepository.class);
        service = mock(CardUpdateService.class);
        controller = new CardUpdateController(repository, service, FIXED_CLOCK,
                ConversationStateSealFixture.seal(), CHARSET);
    }

    private static CardRecord card() {
        return CardRecord.moving(CARD_NUMBER, 11L, 123, "JOHN Q PUBLIC", "2026-04-30", "Y", CODEC);
    }

    private static CardReadResult normalRead() {
        CardRecord record = card();
        return CardReadResult.normal(record, record.encodeToImage(CHARSET));
    }

    private static CardUpdateRequest request(String acctsid, String cardsid,
            NavigationContext commarea, CommArea trailer) {
        CardUpdateRequest request = new CardUpdateRequest();
        request.setAcctsid(acctsid);
        request.setCardsid(cardsid);
        request.setNavigationContext(commarea);
        request.setCommArea(trailer);
        return request;
    }

    private static CardUpdateRequest typedRequest(NavigationContext commarea, CommArea trailer) {
        CardUpdateRequest typed = request(ACCOUNT_NUMBER, CARD_NUMBER, commarea, trailer);
        typed.setCrdname("JOHN Q PUBLIC");
        typed.setCrdstcd("N");
        typed.setExpmon("04");
        typed.setExpyear("2026");
        typed.setExpday("30");
        return typed;
    }

    private static CommArea fetchedTrailer(ChangeAction action) {
        return CommArea.initialised()
                .withChangeAction(action)
                .withOldDetails(oldDetails())
                .withNewDetails(newDetails());
    }

    private static NavigationContext reentered() {
        return NavigationContext.empty()
                .withFromProgram(CardUpdateController.LIT_THISPGM)
                .withFromTranid(CardUpdateController.LIT_THISTRANID)
                .withPgmReenter()
                .withAcctId(11L)
                .withCardNum(Long.parseLong(CARD_NUMBER));
    }

    private Conversation task(NavigationContext commarea, ChangeAction action) {
        Conversation task = new Conversation();
        controller.initializeStorage(request("", "", commarea, CommArea.initialised()), task);
        task.carddemoCommarea = commarea;
        task.setChangeAction(action);
        return task;
    }

    private Conversation taskWithValidKeys(ChangeAction action) {
        Conversation task = task(reentered(), action);
        task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_ISVALID;
        task.wsEditCardFlag = CardUpdateController.FLG_FILTER_ISVALID;
        task.ccWorkArea.setCcAcctId(ACCOUNT_NUMBER);
        task.ccWorkArea.setCcCardNum(CARD_NUMBER);
        return task;
    }

    private static CardDetails oldDetails() {
        return CardDetails.initialised(DetailGroup.OLD)
                .withAcctid(ACCOUNT_NUMBER)
                .withCardid(CARD_NUMBER)
                .withCvvCd("123")
                .withCrdname(CODEC.movePicX("JOHN Q PUBLIC", CardDetails.CRDNAME_LENGTH))
                .withExpyear("2026")
                .withExpmon("04")
                .withExpday("30")
                .withCrdstcd("Y");
    }

    private static CardDetails newDetails() {
        return CardDetails.initialised(DetailGroup.NEW)
                .withAcctid(ACCOUNT_NUMBER)
                .withCardid(CARD_NUMBER)
                .withCvvCd("123")
                .withCrdname(CODEC.movePicX("JOHN Q PUBLIC", CardDetails.CRDNAME_LENGTH))
                .withExpyear("2026")
                .withExpmon("04")
                .withExpday("30")
                .withCrdstcd("N");
    }

    private static WriteResult writeResult(WriteOutcome outcome) {
        return new WriteResult(outcome,
                outcome == WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE,
                outcome.returnMessageLiteral().orElse(""),
                oldDetails(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                FileStatus.NORMAL,
                0);
    }

    private static CardUpdateResponse screenOf(
            ResponseEntity<ScreenResponse<CardUpdateResponse>> answer) {
        ScreenResponse<CardUpdateResponse> envelope = answer.getBody();
        assertThat(envelope).isNotNull();
        assertThat(envelope.screen()).isNotNull();
        return envelope.screen();
    }

    /**
     * Opens the {@code stateToken} a response carries, the way the next turn's {@code bind} does.
     *
     * <p>Built on {@link ConversationStateSealFixture}, which is the same secret the controller under test
     * was constructed with, so a token this suite cannot open is a token the controller could not have
     * issued.
     *
     * @param response the response whose token to open
     * @return the 329-byte {@code WS-THIS-PROGCOMMAREA} it holds
     */
    private static CardUpdateRequest.CommArea unsealedAreaOf(CardUpdateResponse response) {
        return CardUpdateRequest.CommArea.decode(
                ConversationStateSealFixture.seal().unseal(CardUpdateController.STATE_TOKEN_MEMBER,
                        CardUpdateController.STATE_PURPOSE,
                        CODEC.movePicX(CARD_NUMBER, CardUpdateRequest.CARDSID_LENGTH),
                        response.getStateToken()),
                CODEC);
    }

    // =================================================================================================

    @Nested
    @DisplayName("The literals, at the widths app/cbl/COCRDUPC.cbl:218-262 declares them")
    class Literals {
        @Test
        @DisplayName("this program's own four navigation literals - :219-228")
        void thisProgramsLiterals() {
            assertThat(CardUpdateController.LIT_THISPGM).isEqualTo("COCRDUPC");
            assertThat(CardUpdateController.LIT_THISTRANID).isEqualTo("CCUP");
            assertThat(CardUpdateController.LIT_THISMAP).isEqualTo("CCRDUPA");
        }

        @Test
        @DisplayName("LIT-THISMAPSET is EIGHT characters, not seven - :225")
        void thisMapsetIsEightCharacters() {
            assertThat(CardUpdateController.LIT_THISMAPSET).isEqualTo("COCRDUP ").hasSize(8);
        }

        @Test
        @DisplayName("LIT-CCLISTMAP keeps its 'CCRDSLA' defect - :233-234, B5")
        void cclistmapKeepsItsDefect() {
            assertThat(CardUpdateController.LIT_CCLISTMAP)
                    .as("app/cbl/COCRDUPC.cbl:233-234 declares 'CCRDSLA'; the card list's real map is "
                            + "'CCRDLIA'. The defect is behaviour and is preserved (B5)")
                    .isEqualTo("CCRDSLA")
                    .isNotEqualTo("CCRDLIA");
        }

        @Test
        @DisplayName("the card list and menu literals - :229-244")
        void neighbourProgramLiterals() {
            assertThat(CardUpdateController.LIT_CCLISTPGM).isEqualTo("COCRDLIC");
            assertThat(CardUpdateController.LIT_CCLISTTRANID).isEqualTo("CCLI");
            assertThat(CardUpdateController.LIT_CCLISTMAPSET).isEqualTo("COCRDLI");
            assertThat(CardUpdateController.LIT_MENUPGM).isEqualTo("COMEN01C");
            assertThat(CardUpdateController.LIT_MENUTRANID).isEqualTo("CM00");
        }

        @Test
        @DisplayName("WS-FILE-ERROR-MESSAGE is exactly 80 characters - :133-152")
        void fileErrorMessageIsEighty() {
            assertThat(CardUpdateController.FILE_ERROR_MESSAGE_LENGTH).isEqualTo(80);
        }

        @Test
        @DisplayName("the abend literals - :1021, :1023, :1534, :1552")
        void abendLiterals() {
            assertThat(CardUpdateController.UNEXPECTED_DATA_ABEND_CODE).isEqualTo("0001");
            assertThat(CardUpdateController.ABEND_ROUTINE_ABCODE).isEqualTo("9999");
            assertThat(CardUpdateController.UNEXPECTED_DATA_SCENARIO.trim())
                    .isEqualTo("UNEXPECTED DATA SCENARIO");
            assertThat(CardUpdateController.UNEXPECTED_ABEND_OCCURRED.trim())
                    .isEqualTo("UNEXPECTED ABEND OCCURRED.");
        }

        @Test
        @DisplayName("the case-folding tables are 26 characters each - :259-262")
        void caseFoldingTables() {
            assertThat(CardUpdateController.LIT_UPPER).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                    .hasSize(26);
            assertThat(CardUpdateController.LIT_LOWER).isEqualTo("abcdefghijklmnopqrstuvwxyz")
                    .hasSize(26);
        }
    }

    @Nested
    @DisplayName("2000-DECIDE-ACTION - the eight arms of :949-1027, in source order")
    class DecideAction {
        @Test
        @DisplayName("DETAILS-NOT-FETCHED executes the PFK12 body: the OR-group at :954/:958")
        void detailsNotFetchedExecutesThePfk12Body() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            Conversation task = taskWithValidKeys(ChangeAction.initial());

            controller.decideAction2000(task);

            verify(repository).readByCardNumber(CARD_NUMBER);
            assertThat(task.changeAction().isShowDetails())
                    .as("app/cbl/COCRDUPC.cbl:963-965 sets CCUP-SHOW-DETAILS when the read found a card")
                    .isTrue();
        }

        @Test
        @DisplayName("PFK12 executes the same body - :958-966")
        void pfk12ExecutesTheSharedBody() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            Conversation task = taskWithValidKeys(ChangeAction.showDetails());
            task.ccWorkArea.setCcardAidCondition(AidKey.PFK12);

            controller.decideAction2000(task);

            verify(repository).readByCardNumber(CARD_NUMBER);
            assertThat(task.changeAction().isShowDetails()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] change action {0} satisfies DETAILS-NOT-FETCHED")
        @ValueSource(strings = {"\u0000", " "})
        @DisplayName("both LOW-VALUES and SPACES satisfy DETAILS-NOT-FETCHED - :277-278")
        void bothLowValuesAndSpacesAreDetailsNotFetched(String value) {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            Conversation task = taskWithValidKeys(ChangeAction.of(value));

            assertThat(task.changeAction().isDetailsNotFetched()).isTrue();

            controller.decideAction2000(task);
            verify(repository).readByCardNumber(CARD_NUMBER);
        }

        @ParameterizedTest(name = "[{index}] acct valid={0}, card valid={1} -> reads={2}")
        @CsvSource({"true,true,true", "true,false,false", "false,true,false", "false,false,false"})
        @DisplayName("the read needs BOTH key flags valid - :959-960")
        void theReadNeedsBothKeyFlags(boolean acctValid, boolean cardValid, boolean expectRead) {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            Conversation task = taskWithValidKeys(ChangeAction.initial());
            task.wsEditAcctFlag = acctValid
                    ? CardUpdateController.FLG_FILTER_ISVALID : CardUpdateController.FLG_FILTER_NOT_OK;
            task.wsEditCardFlag = cardValid
                    ? CardUpdateController.FLG_FILTER_ISVALID : CardUpdateController.FLG_FILTER_NOT_OK;

            controller.decideAction2000(task);

            if (expectRead) {
                verify(repository).readByCardNumber(CARD_NUMBER);
            } else {
                verifyNoInteractions(repository);
            }
        }

        @Test
        @DisplayName("a NOTFND read leaves the state unadvanced - :963-965")
        void notFoundLeavesTheStateUnadvanced() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.notFound());
            Conversation task = taskWithValidKeys(ChangeAction.initial());

            controller.decideAction2000(task);

            assertThat(task.changeAction().isDetailsNotFetched())
                    .as("CCUP-SHOW-DETAILS is set only under IF FOUND-CARDS-FOR-ACCOUNT at :963")
                    .isTrue();
        }

        @Test
        @DisplayName("SHOW-DETAILS with clean input advances to CHANGES-OK-NOT-CONFIRMED - :971-977")
        void showDetailsAdvancesWhenInputIsClean() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.wsInputFlag = CardUpdateController.INPUT_OK;

            controller.decideAction2000(task);

            assertThat(task.changeAction().isChangesOkNotConfirmed()).isTrue();
        }

        @Test
        @DisplayName("SHOW-DETAILS holds when INPUT-ERROR - :972-974")
        void showDetailsHoldsOnInputError() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.wsInputFlag = CardUpdateController.INPUT_ERROR;

            controller.decideAction2000(task);

            assertThat(task.changeAction().isShowDetails()).isTrue();
        }

        @Test
        @DisplayName("SHOW-DETAILS holds when NO-CHANGES-DETECTED - :973")
        void showDetailsHoldsOnNoChangesDetected() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.wsInputFlag = CardUpdateController.INPUT_OK;
            task.wsReturnMsg = CardUpdateController.NO_CHANGES_DETECTED;

            controller.decideAction2000(task);

            assertThat(task.changeAction().isShowDetails()).isTrue();
        }

        @Test
        @DisplayName("CHANGES-NOT-OK is a CONTINUE, not an abend - :982-983")
        void changesNotOkContinues() {
            Conversation task = task(reentered(), ChangeAction.changesNotOk());

            controller.decideAction2000(task);

            assertThat(task.changeAction().isChangesNotOk()).isTrue();
            verifyNoInteractions(repository, service);
        }

        @Test
        @DisplayName("PF5 on CHANGES-OK-NOT-CONFIRMED writes - :988-991")
        void confirmKeyIsRequiredToWrite() {
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(writeResult(WriteOutcome.CHANGES_OKAYED_AND_DONE));
            Conversation task = task(reentered(), ChangeAction.changesOkNotConfirmed());
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());
            task.ccWorkArea.setCcardAidCondition(AidKey.PFK05);

            controller.decideAction2000(task);

            verify(service).writeProcessing(any(), any(), any(), anyString(), any());
            assertThat(task.changeAction().isChangesOkayedAndDone()).isTrue();
        }

        @Test
        @DisplayName("the write is handed the injected dataset codec, never the static literal one")
        void theWriteUsesTheInjectedCodePage() {
            ArgumentCaptor<FixedWidthCodec> passed = ArgumentCaptor.forClass(FixedWidthCodec.class);
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(writeResult(WriteOutcome.CHANGES_OKAYED_AND_DONE));
            Conversation task = task(reentered(), ChangeAction.changesOkNotConfirmed());
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());
            task.ccWorkArea.setCcardAidCondition(AidKey.PFK05);

            controller.decideAction2000(task);

            verify(service).writeProcessing(any(), any(), any(), anyString(), passed.capture());
            assertThat(passed.getValue()).isSameAs(controller.codec());
            assertThat(passed.getValue().charset()).isEqualTo(CHARSET);

            Charset ebcdic = Charset.forName("IBM037");
            assertThat(new CardUpdateController(repository, service, FIXED_CLOCK,
                    ConversationStateSealFixture.seal(), ebcdic)
                    .codec().charset()).isEqualTo(ebcdic);
        }

        @Test
        @DisplayName("without PF5 the same state writes nothing - :1006-1007")
        void withoutTheConfirmKeyNothingIsWritten() {
            Conversation task = task(reentered(), ChangeAction.changesOkNotConfirmed());
            task.ccWorkArea.setCcardAidCondition(AidKey.ENTER);

            controller.decideAction2000(task);

            verifyNoInteractions(service);
            assertThat(task.changeAction().isChangesOkNotConfirmed())
                    .as("the bare arm at :1006 is a CONTINUE: the state waits for the confirm key")
                    .isTrue();
        }

        @Test
        @DisplayName("OKAYED-AND-DONE returns to SHOW-DETAILS - :1011-1012")
        void okayedAndDoneReturnsToShowDetails() {
            Conversation task = task(reentered().withFromTranid("CCLI"),
                    ChangeAction.changesOkayedAndDone());

            controller.decideAction2000(task);

            assertThat(task.changeAction().isShowDetails()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] CDEMO-FROM-TRANID = {0}")
        @ValueSource(strings = {"\u0000\u0000\u0000\u0000", "    "})
        @DisplayName("with no caller: ids to ZEROES, status to LOW-VALUES - :1013-1018")
        void okayedAndDoneClearsTheCarriedKeys(String fromTranid) {
            Conversation task = task(reentered().withFromTranid(fromTranid).withAcctId(11L)
                    .withCardNum(Long.parseLong(CARD_NUMBER)).withAcctStatus("Y"),
                    ChangeAction.changesOkayedAndDone());

            controller.decideAction2000(task);

            assertThat(task.carddemoCommarea.acctId()).isZero();
            assertThat(task.carddemoCommarea.cardNum()).isZero();
            assertThat(task.carddemoCommarea.acctStatus())
                    .as(":1017 moves LOW-VALUES, not ZEROES, into CDEMO-ACCT-STATUS")
                    .isEqualTo("\u0000");
        }

        @Test
        @DisplayName("with a caller the carried keys survive - :1013")
        void okayedAndDoneKeepsTheKeysWhenThereIsACaller() {
            Conversation task = task(reentered().withFromTranid("CCLI").withAcctId(11L)
                    .withCardNum(Long.parseLong(CARD_NUMBER)), ChangeAction.changesOkayedAndDone());

            controller.decideAction2000(task);

            assertThat(task.carddemoCommarea.acctId()).isEqualTo(11L);
            assertThat(task.carddemoCommarea.cardNum()).isEqualTo(Long.parseLong(CARD_NUMBER));
        }

        @Test
        @DisplayName("WHEN OTHER abends with '0001' in ABEND-MSG, not WS-RETURN-MSG - :1019-1026")
        void unexpectedDataScenarioAbends() {
            Conversation task = task(reentered(), ChangeAction.of("Q"));

            assertThatThrownBy(() -> controller.decideAction2000(task))
                    .isInstanceOf(AbendException.class)
                    .hasMessageContaining("UNEXPECTED DATA SCENARIO")
                    .hasMessageContaining(CardUpdateController.ABEND_ROUTINE_ABCODE);

            assertThat(task.abendData.abendCode()).isEqualTo("0001");
            assertThat(task.abendData.abendMsg().trim()).isEqualTo("UNEXPECTED DATA SCENARIO");
            assertThat(task.abendData.abendReason().trim())
                    .as(":1022 moves SPACES into ABEND-REASON")
                    .isEmpty();
            assertThat(task.wsReturnMsg)
                    .as("unlike COCRDSLC:377-378, this program leaves WS-RETURN-MSG untouched. It holds "
                            + "the 75 SPACES that SET WS-RETURN-MSG-OFF at :384 put there - and SPACES, "
                            + "because 88 WS-RETURN-MSG-OFF VALUE SPACES at :174 is spaces and not "
                            + "LOW-VALUES, unlike CVCRD01Y:30's CCARD-RETURN-MSG-OFF")
                    .isEqualTo(CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));
            assertThat(task.returnMessageOff()).isTrue();
        }

        @Test
        @DisplayName("ABEND-CULPRIT is set unconditionally by the routine - :1537")
        void abendCulpritNamesThisProgram() {
            Conversation task = task(reentered(), ChangeAction.of("Q"));

            assertThatThrownBy(() -> controller.decideAction2000(task))
                    .isInstanceOf(AbendException.class);

            assertThat(task.abendData.abendCulprit().trim()).isEqualTo("COCRDUPC");
        }

        @Test
        @DisplayName("a spaces ABEND-MSG does NOT get the default: CSMSG02Y is VALUE SPACES - :1533")
        void abendRoutineLeavesASpacesMessageAlone() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            assertThat(task.abendData.abendMsg().trim())
                    .as("CSMSG02Y declares ABEND-MSG VALUE SPACES and :374-376 does not INITIALIZE it")
                    .isEmpty();

            controller.abendRoutine(task, null, null);

            assertThat(task.abendData.abendMsg().trim())
                    .as("IF ABEND-MSG EQUAL LOW-VALUES at :1533 is false for spaces, so :1534 is skipped")
                    .isEmpty();
        }

        @Test
        @DisplayName("a LOW-VALUES ABEND-MSG does get the default - :1533-1535")
        void abendRoutineAppliesItsDefaultWhenTheFieldIsLowValues() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.abendData = task.abendData.withAbendMsg(
                    CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH));

            AbendException abend = controller.abendRoutine(task, null, null);

            assertThat(task.abendData.abendMsg().trim()).isEqualTo("UNEXPECTED ABEND OCCURRED.");
            assertThat(abend.getMessage()).contains("UNEXPECTED ABEND OCCURRED.");
        }

        @Test
        @DisplayName("ABEND-ROUTINE tolerates the no-exception arrival - :1025-1026")
        void abendRoutineTakesNoCause() {
            Conversation task = task(reentered(), ChangeAction.showDetails());

            AbendException abend = controller.abendRoutine(task, null, null);

            assertThat(abend).isNotNull();
            assertThat(abend.getCause()).isNull();
            assertThat(task.returned).isTrue();
        }

        @Test
        @DisplayName("ABEND-ROUTINE carries a triggering failure as the cause - :1546-1552")
        void abendRoutineCarriesTheCause() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            RuntimeException failure = new IllegalStateException("dataset unavailable");

            AbendException abend = controller.abendRoutine(task, new CardUpdateResponse(), failure);

            assertThat(abend.getCause()).isSameAs(failure);
        }

        @Test
        @DisplayName("ABEND-ROUTINE keeps a message the caller supplied - :1533")
        void abendRoutineKeepsASuppliedMessage() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.abendData = task.abendData.withAbendMsg(CardUpdateController.UNEXPECTED_DATA_SCENARIO);

            controller.abendRoutine(task, null, null);

            assertThat(task.abendData.abendMsg().trim()).isEqualTo("UNEXPECTED DATA SCENARIO");
        }
    }

    @Nested
    @DisplayName("The CCUP-CHANGE-ACTION condition names - :276-291")
    class ChangeActionConditions {
        @ParameterizedTest(name = "[{index}] '{0}' is CHANGES-MADE")
        @ValueSource(strings = {"E", "N", "C", "L", "F"})
        @DisplayName("CHANGES-MADE is a grouping level over five values - :281-282")
        void changesMadeCoversFiveValues(String value) {
            assertThat(ChangeAction.of(value).isChangesMade()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] '{0}' is not CHANGES-MADE")
        @ValueSource(strings = {"S", "\u0000", " ", "Q"})
        @DisplayName("and nothing else is - :281-282")
        void changesMadeExcludesTheRest(String value) {
            assertThat(ChangeAction.of(value).isChangesMade()).isFalse();
        }

        @ParameterizedTest(name = "[{index}] '{0}' is CHANGES-FAILED")
        @ValueSource(strings = {"L", "F"})
        @DisplayName("CHANGES-FAILED is a grouping level over two values - :288-289")
        void changesFailedCoversTwoValues(String value) {
            assertThat(ChangeAction.of(value).isChangesFailed()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] '{0}' is not CHANGES-FAILED")
        @ValueSource(strings = {"E", "N", "C", "S", "\u0000", " "})
        @DisplayName("and nothing else is - :288-289")
        void changesFailedExcludesTheRest(String value) {
            assertThat(ChangeAction.of(value).isChangesFailed()).isFalse();
        }

        @Test
        @DisplayName("the six single-valued condition names - :279-291")
        void singleValuedConditions() {
            assertThat(ChangeAction.showDetails().isShowDetails()).isTrue();
            assertThat(ChangeAction.changesNotOk().isChangesNotOk()).isTrue();
            assertThat(ChangeAction.changesOkNotConfirmed().isChangesOkNotConfirmed()).isTrue();
            assertThat(ChangeAction.changesOkayedAndDone().isChangesOkayedAndDone()).isTrue();
            assertThat(ChangeAction.changesOkayedLockError().isChangesOkayedLockError()).isTrue();
            assertThat(ChangeAction.changesOkayedButFailed().isChangesOkayedButFailed()).isTrue();
        }
    }

    @Nested
    @DisplayName("9200-WRITE-PROCESSING and the inner EVALUATE - :990-1001")
    class WriteProcessing {
        private Conversation confirming() {
            Conversation task = task(reentered(), ChangeAction.changesOkNotConfirmed());
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());
            task.ccWorkArea.setCcardAidCondition(
                    AidKey.PFK05);
            return task;
        }

        @ParameterizedTest(name = "[{index}] {0} -> CCUP-CHANGE-ACTION {1}")
        @CsvSource({
            "COULD_NOT_LOCK_FOR_UPDATE,L",
            "LOCKED_BUT_UPDATE_FAILED,F",
            "DATA_WAS_CHANGED_BEFORE_UPDATE,S",
            "CHANGES_OKAYED_AND_DONE,C"})
        @DisplayName("each outcome selects its own arm - :992-1001")
        void eachOutcomeSelectsItsArm(WriteOutcome outcome, String expected) {
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(writeResult(outcome));

            Conversation task = confirming();
            controller.decideAction2000(task);

            assertThat(task.changeAction().value()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the lock arm sets INPUT-ERROR - :1444")
        void lockArmSetsInputError() {
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(writeResult(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE));

            Conversation task = confirming();
            controller.decideAction2000(task);

            assertThat(task.inputError()).isTrue();
        }

        @Test
        @DisplayName("a successful write does not - :1444")
        void successfulWriteLeavesInputAlone() {
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(writeResult(WriteOutcome.CHANGES_OKAYED_AND_DONE));

            Conversation task = confirming();
            task.wsInputFlag = CardUpdateController.INPUT_OK;
            controller.decideAction2000(task);

            assertThat(task.inputError()).isFalse();
        }

        @Test
        @DisplayName("a lock failure behind an earlier message reports success - the :1445-1447 defect")
        void lockFailureAfterAnEarlierMessageReportsSuccess() {
            String earlier = CardUpdateController.DID_NOT_FIND_ACCTCARD_COMBO;
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(new WriteResult(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE, true, earlier,
                            oldDetails(), Optional.empty(), Optional.empty(), Optional.empty(),
                            FileStatus.NORMAL, 0));

            Conversation task = confirming();
            task.wsReturnMsg = earlier;
            controller.decideAction2000(task);

            assertThat(task.changeAction().isChangesOkayedAndDone())
                    .as("app/cbl/COCRDUPC.cbl:1445-1447 guards the lock message with "
                            + "IF WS-RETURN-MSG-OFF, so the inner EVALUATE at :992-1001 falls to "
                            + "WHEN OTHER and reports success. Preserved (B5)")
                    .isTrue();
            assertThat(task.inputError())
                    .as("INPUT-ERROR is set unconditionally at :1444, so the flag still tells the truth")
                    .isTrue();
        }

        @Test
        @DisplayName("the refreshed CCUP-OLD-DETAILS is adopted - :1512-1517")
        void refreshedSnapshotIsAdopted() {
            CardDetails refreshed = oldDetails().withCrdstcd("N");
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(new WriteResult(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE, false,
                            WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE.returnMessageLiteral()
                                    .orElseThrow(),
                            refreshed, Optional.empty(), Optional.empty(), Optional.empty(),
                            FileStatus.NORMAL, 0));

            Conversation task = confirming();
            controller.decideAction2000(task);

            assertThat(task.oldDetails().crdstcd()).isEqualTo("N");
            assertThat(task.changeAction().isShowDetails()).isTrue();
        }
    }

    @Nested
    @DisplayName("3000-SEND-MAP and its five children - :1035-1340")
    class Screen {
        private CardUpdateResponse response;

        @BeforeEach
        void newScreen() {
            response = new CardUpdateResponse();
        }

        private void allFieldFlagsValid(Conversation task) {
            task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardnameFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardstatusFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardexpmonFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardexpyearFlag = CardUpdateController.FLG_FILTER_ISVALID;
        }

        private CardUpdateRequest paintedInputArea;

        private CardUpdateResponse paint(Conversation task) {
            CardUpdateRequest request = request("", "", task.carddemoCommarea, CommArea.initialised());
            controller.sendMap3000(request, response, task);
            paintedInputArea = request;
            return response;
        }

        @Test
        @DisplayName("3100-SCREEN-INIT fills the heading from the fixed clock - :1053-1075")
        void screenInitFillsTheHeading() {
            paint(task(reentered(), ChangeAction.showDetails()));

            assertThat(response.getTrnnameo()).isEqualTo("CCUP");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDUPC");
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.getCurdateo()).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).isEqualTo("14:35:07");
        }

        @Test
        @DisplayName("3200 paints nothing on first entry - :1084-1086")
        void screenVarsPaintNothingOnEnter() {
            Conversation task = task(NavigationContext.empty().withPgmEnter(),
                    ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            task.ccWorkArea.setCcAcctId(ACCOUNT_NUMBER);

            paint(task);

            assertThat(response.getAcctsido())
                    .isEqualTo(CardScreenState.lowValues(CardUpdateResponse.ACCTSIDO_LENGTH));
            assertThat(response.getCrdnameo())
                    .isEqualTo(CardScreenState.lowValues(CardUpdateResponse.CRDNAMEO_LENGTH));
        }

        @Test
        @DisplayName("3200 paints LOW-VALUES for an unset key - :1087-1097")
        void screenVarsPaintLowValuesForAnUnsetKey() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            task.ccWorkArea.setCcAcctIdN(0L);
            task.ccWorkArea.setCcCardNumN(0L);
            allFieldFlagsValid(task);

            paint(task);

            assertThat(response.getAcctsido())
                    .isEqualTo(CardScreenState.lowValues(CardUpdateResponse.ACCTSIDO_LENGTH));
            assertThat(response.getCardsido())
                    .isEqualTo(CardScreenState.lowValues(CardUpdateResponse.CARDSIDO_LENGTH));
        }

        @Test
        @DisplayName("3200 echoes a set key - :1090, :1096")
        void screenVarsEchoASetKey() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            task.ccWorkArea.setCcAcctId(ACCOUNT_NUMBER);
            task.ccWorkArea.setCcCardNum(CARD_NUMBER);
            allFieldFlagsValid(task);

            paint(task);

            assertThat(response.getAcctsido()).isEqualTo(ACCOUNT_NUMBER);
            assertThat(response.getCardsido()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("3200 arm 1: DETAILS-NOT-FETCHED blanks the details - :1100-1106")
        void screenVarsArmOneBlanksTheDetails() {
            Conversation task = task(reentered(), ChangeAction.initial());
            task.setOldDetails(oldDetails());

            paint(task);

            assertThat(response.getCrdnameo())
                    .isEqualTo(CardScreenState.lowValues(CardUpdateResponse.CRDNAMEO_LENGTH));
            assertThat(response.getExpdayo())
                    .isEqualTo(CardScreenState.lowValues(CardUpdateResponse.EXPDAYO_LENGTH));
        }

        @Test
        @DisplayName("3200 arm 2: SHOW-DETAILS paints CCUP-OLD-* - :1107-1112")
        void screenVarsArmTwoPaintsTheStoredValues() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            allFieldFlagsValid(task);

            paint(task);

            assertThat(response.getCrdnameo().trim()).isEqualTo("JOHN Q PUBLIC");
            assertThat(response.getCrdstcdo()).isEqualTo("Y");
            assertThat(response.getExpyearo()).isEqualTo("2026");
            assertThat(response.getExpmono()).isEqualTo("04");
            assertThat(response.getExpdayo()).isEqualTo("30");
        }

        @ParameterizedTest(name = "[{index}] CCUP-CHANGE-ACTION ''{0}'' is CHANGES-MADE")
        @ValueSource(strings = {"E", "N", "C", "L", "F"})
        @DisplayName("3200 arm 3: CHANGES-MADE takes EXPDAY from the OLD group - :1122-1123")
        void screenVarsArmThreeTakesTheDayFromTheOldGroup(String action) {
            Conversation task = task(reentered(), ChangeAction.of(action));
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails().withExpday("15"));
            allFieldFlagsValid(task);

            paint(task);

            assertThat(response.getCrdstcdo())
                    .as("the status is the typed one, from CCUP-NEW-CRDSTCD at :1115")
                    .isEqualTo("N");
            assertThat(response.getExpdayo())
                    .as("app/cbl/COCRDUPC.cbl:1122 is commented out and :1123 moves CCUP-OLD-EXPDAY, so "
                            + "the day is always the stored value even on a screen of typed changes")
                    .isEqualTo("30");
        }

        @Test
        @DisplayName("3200 arm 4: WHEN OTHER paints CCUP-OLD-* - :1124-1129")
        void screenVarsArmFourPaintsTheStoredValues() {
            Conversation task = task(reentered(), ChangeAction.of("Q"));
            task.setOldDetails(oldDetails());
            allFieldFlagsValid(task);

            paint(task);

            assertThat(response.getCrdstcdo()).isEqualTo("Y");
            assertThat(response.getExpdayo()).isEqualTo("30");
        }

        @Test
        @DisplayName("the six WS-INFO-MSG literals, verbatim - :160-173")
        void infomsgLiterals() {
            assertThat(CardUpdateController.FOUND_CARDS_FOR_ACCOUNT.trim())
                    .isEqualTo("Details of selected card shown above");
            assertThat(CardUpdateController.PROMPT_FOR_SEARCH_KEYS.trim())
                    .isEqualTo("Please enter Account and Card Number");
            assertThat(CardUpdateController.PROMPT_FOR_CHANGES.trim())
                    .isEqualTo("Update card details presented above.");
            assertThat(CardUpdateController.PROMPT_FOR_CONFIRMATION.trim())
                    .isEqualTo("Changes validated.Press F5 to save");
            assertThat(CardUpdateController.CONFIRM_UPDATE_SUCCESS.trim())
                    .isEqualTo("Changes committed to database");
            assertThat(CardUpdateController.INFORM_FAILURE.trim())
                    .isEqualTo("Changes unsuccessful. Please try again");
        }

        @ParameterizedTest(name = "[{index}] state ''{0}''")
        @CsvSource({"S", "E", "N", "C", "L", "F"})
        @DisplayName("3250 chooses the message per state - :1145-1156")
        void infomsgPerState(String action) {
            Conversation task = task(reentered(), ChangeAction.of(action));
            task.setOldDetails(oldDetails());
            allFieldFlagsValid(task);

            paint(task);

            String expected = switch (action) {
                case "S" -> CardUpdateController.FOUND_CARDS_FOR_ACCOUNT;
                case "E" -> CardUpdateController.PROMPT_FOR_CHANGES;
                case "N" -> CardUpdateController.PROMPT_FOR_CONFIRMATION;
                case "C" -> CardUpdateController.CONFIRM_UPDATE_SUCCESS;
                case "L", "F" -> CardUpdateController.INFORM_FAILURE;
                default -> throw new IllegalArgumentException(action);
            };
            assertThat(response.getInfomsgo().trim()).isEqualTo(expected.trim());
        }

        @Test
        @DisplayName("3250 tests CDEMO-PGM-ENTER first - :1141-1142")
        void infomsgEnterWinsOverTheState() {
            Conversation task = task(NavigationContext.empty().withPgmEnter(),
                    ChangeAction.changesOkayedAndDone());

            paint(task);

            assertThat(response.getInfomsgo().trim())
                    .as("on first entry the prompt wins regardless of CCUP-CHANGE-ACTION")
                    .isEqualTo(CardUpdateController.PROMPT_FOR_SEARCH_KEYS.trim());
        }

        @Test
        @DisplayName("3250 DETAILS-NOT-FETCHED prompts for the keys - :1143-1144")
        void infomsgNotFetchedPrompts() {
            Conversation task = task(reentered(), ChangeAction.initial());

            paint(task);

            assertThat(response.getInfomsgo().trim())
                    .isEqualTo(CardUpdateController.PROMPT_FOR_SEARCH_KEYS.trim());
        }

        @Test
        @DisplayName("3250 has no WHEN OTHER: an unmatched state keeps its message - :1157-1159")
        void infomsgUnmatchedStateKeepsItsMessage() {
            Conversation task = task(reentered(), ChangeAction.of("Q"));
            task.setOldDetails(oldDetails());
            task.wsInfoMsg = CardUpdateController.PROMPT_FOR_CHANGES;

            paint(task);

            assertThat(response.getInfomsgo().trim())
                    .isEqualTo(CardUpdateController.PROMPT_FOR_CHANGES.trim());
        }

        @Test
        @DisplayName("3250 copies WS-RETURN-MSG into ERRMSGO, padded to 80 - :1163")
        void errmsgCarriesTheReturnMessage() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            task.wsReturnMsg = CardUpdateController.DID_NOT_FIND_ACCTCARD_COMBO;
            allFieldFlagsValid(task);

            paint(task);

            assertThat(response.getErrmsgo().trim())
                    .isEqualTo("Did not find cards for this search condition");
            assertThat(response.getErrmsgo())
                    .as("X(75) into X(80) space-pads by five")
                    .hasSize(CardUpdateResponse.ERRMSGO_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] state ''{0}'': keys={1} details={2}")
        @CsvSource({"LOW,FSE,PRF", "S,PRF,FSE", "E,PRF,FSE", "N,PRF,PRF", "C,PRF,PRF", "Q,FSE,PRF"})
        @DisplayName("3300 protect/unprotect per state - :1171-1208")
        void protectPerState(String action, String keys, String details) {
            Conversation task = task(reentered(),
                    "LOW".equals(action) ? ChangeAction.initial() : ChangeAction.of(action));
            task.setOldDetails(oldDetails());
            CardUpdateRequest request =
                    request("", "", task.carddemoCommarea, CommArea.initialised());

            controller.sendMap3000(request, response, task);

            byte expectedKeys = "FSE".equals(keys) ? BmsAttributes.DFHBMFSE : BmsAttributes.DFHBMPRF;
            byte expectedDetails =
                    "FSE".equals(details) ? BmsAttributes.DFHBMFSE : BmsAttributes.DFHBMPRF;
            assertThat(attributeItemOf(request, CardUpdateRequest.ACCTSID_FIELD))
                    .isEqualTo(expectedKeys);
            assertThat(attributeItemOf(request, CardUpdateRequest.CARDSID_FIELD))
                    .isEqualTo(expectedKeys);
            assertThat(attributeItemOf(request, CardUpdateRequest.CRDNAME_FIELD))
                    .isEqualTo(expectedDetails);
            assertThat(attributeItemOf(request, CardUpdateRequest.EXPYEAR_FIELD))
                    .isEqualTo(expectedDetails);
        }

        @Test
        @DisplayName("3300 never assigns EXPDAYA: commented out on all four arms - B5")
        void expdayAttributeItemIsNeverAssigned() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            CardUpdateRequest request =
                    request("", "", task.carddemoCommarea, CommArea.initialised());

            controller.sendMap3000(request, response, task);

            assertThat(request.metadataFor(CardUpdateRequest.EXPDAY_FIELD).attributeItem())
                    .as("MOVE ... TO EXPDAYA is commented out at :1178, :1185, :1197 and :1205")
                    .isEqualTo(CardUpdateRequest.FieldMetadata.FLAG_ITEM_NOT_MODIFIED);
        }

        @ParameterizedTest(name = "[{index}] {0} -> cursor on {1}")
        @CsvSource({
            "FOUND,CRDNAME",
            "NO_CHANGES,CRDNAME",
            "ACCT_NOT_OK,ACCTSID",
            "ACCT_BLANK,ACCTSID",
            "CARD_NOT_OK,CARDSID",
            "CARD_BLANK,CARDSID",
            "NAME_NOT_OK,CRDNAME",
            "NAME_BLANK,CRDNAME",
            "STATUS_NOT_OK,CRDSTCD",
            "STATUS_BLANK,CRDSTCD",
            "MON_NOT_OK,EXPMON",
            "MON_BLANK,EXPMON",
            "YEAR_NOT_OK,EXPYEAR",
            "YEAR_BLANK,EXPYEAR",
            "NOTHING,ACCTSID"})
        @DisplayName("3300 positions the cursor per flag, in screen order - :1211-1235")
        void cursorPerFlag(String scenario, String expectedField) {
            Conversation task = task(reentered(), ChangeAction.changesNotOk());
            task.setOldDetails(oldDetails());
            allFieldFlagsValid(task);
            applyCursorScenario(task, scenario);
            CardUpdateRequest request =
                    request("", "", task.carddemoCommarea, CommArea.initialised());

            controller.positionCursor3300(request, task);

            assertThat(task.cursorField).isEqualTo(expectedField);
            assertThat(request.metadataFor(expectedField).lengthItem())
                    .as("MOVE -1 TO <field>L is the BMS cursor request")
                    .isEqualTo(CardUpdateRequest.FieldMetadata.CURSOR_LENGTH_ITEM);
        }

        @Test
        @DisplayName("3300 tests FOUND-CARDS before the field flags - :1212-1215")
        void cursorFoundCardsWinsOverAFieldFlag() {
            Conversation task = task(reentered(), ChangeAction.changesNotOk());
            task.setOldDetails(oldDetails());
            task.wsInfoMsg = CardUpdateController.FOUND_CARDS_FOR_ACCOUNT;
            task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_NOT_OK;
            CardUpdateRequest request =
                    request("", "", task.carddemoCommarea, CommArea.initialised());

            controller.positionCursor3300(request, task);

            assertThat(task.cursorField).isEqualTo(CardUpdateResponse.CRDNAME);
        }

        @Test
        @DisplayName("3300 defaults the key colours when arrived from the card list - :1238-1241")
        void colourDefaultsTheKeysFromTheCardList() {
            Conversation task = task(reentered().withLastMapset(
                    CardUpdateController.LIT_CCLISTMAPSET), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            allFieldFlagsValid(task);

            paint(task);

            assertThat(response.colourOf(CardUpdateResponse.ACCTSID))
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(response.colourOf(CardUpdateResponse.CARDSID))
                    .isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("3300 reddens a rejected key with NO reenter guard - :1243-1245")
        void colourRedIsUnguardedForTheKeys() {
            Conversation task = task(NavigationContext.empty().withPgmEnter().withAcctId(11L),
                    ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_NOT_OK;
            task.wsEditCardFlag = CardUpdateController.FLG_FILTER_NOT_OK;

            paint(task);

            assertThat(response.colourOf(CardUpdateResponse.ACCTSID))
                    .as("app/cbl/COCRDUPC.cbl:1243 has no AND CDEMO-PGM-REENTER; COCRDUPC does not copy "
                            + "CSSETATY, whose rule would have guarded it")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.colourOf(CardUpdateResponse.CARDSID)).isEqualTo(BmsAttributes.DFHRED);
        }

        @ParameterizedTest(name = "[{index}] reenter={0} -> marked={0}")
        @CsvSource({"true", "false"})
        @DisplayName("3300 marks a blank key only in REENTER - :1247-1251, G38")
        void colourBlankKeyIsGuardedByReenter(boolean reenter) {
            NavigationContext commarea = reenter
                    ? NavigationContext.empty().withPgmReenter().withAcctId(11L)
                    : NavigationContext.empty().withPgmEnter().withAcctId(11L);
            Conversation task = task(commarea, ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_BLANK;

            paint(task);

            if (reenter) {
                assertThat(response.colourOf(CardUpdateResponse.ACCTSID))
                        .isEqualTo(BmsAttributes.DFHRED);
                assertThat(response.getAcctsido().trim()).isEqualTo("*");
            } else {
                assertThat(response.colourOf(CardUpdateResponse.ACCTSID))
                        .isNotEqualTo(BmsAttributes.DFHRED);
                assertThat(response.getAcctsido()).doesNotContain("*");
            }
        }

        @ParameterizedTest(name = "[{index}] state ''{0}'' -> reddened={1}")
        @CsvSource({"E,true", "N,false", "S,false"})
        @DisplayName("3300 reddens a detail field only while CHANGES-NOT-OK - :1263-1307")
        void colourDetailFieldsAreGuardedByChangesNotOk(String action, boolean reddened) {
            Conversation task = task(reentered(), ChangeAction.of(action));
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());
            task.wsEditCardnameFlag = CardUpdateController.FLG_FILTER_NOT_OK;
            task.wsEditCardstatusFlag = CardUpdateController.FLG_FILTER_NOT_OK;
            task.wsEditCardexpmonFlag = CardUpdateController.FLG_FILTER_NOT_OK;
            task.wsEditCardexpyearFlag = CardUpdateController.FLG_FILTER_NOT_OK;

            paint(task);

            byte expected = reddened ? BmsAttributes.DFHRED : BmsAttributes.DFHDFCOL;
            assertThat(response.colourOf(CardUpdateResponse.CRDNAME)).isEqualTo(expected);
            assertThat(response.colourOf(CardUpdateResponse.CRDSTCD)).isEqualTo(expected);
            assertThat(response.colourOf(CardUpdateResponse.EXPMON)).isEqualTo(expected);
            assertThat(response.colourOf(CardUpdateResponse.EXPYEAR)).isEqualTo(expected);
        }

        @Test
        @DisplayName("3300 marks blank detail fields while CHANGES-NOT-OK - :1268-1307")
        void colourMarksBlankDetailFields() {
            Conversation task = task(reentered(), ChangeAction.changesNotOk());
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());
            task.wsEditCardnameFlag = CardUpdateController.FLG_FILTER_BLANK;
            task.wsEditCardstatusFlag = CardUpdateController.FLG_FILTER_BLANK;
            task.wsEditCardexpmonFlag = CardUpdateController.FLG_FILTER_BLANK;
            task.wsEditCardexpyearFlag = CardUpdateController.FLG_FILTER_BLANK;

            paint(task);

            assertThat(response.getCrdnameo().trim()).isEqualTo("*");
            assertThat(response.getCrdstcdo()).isEqualTo("*");
            assertThat(response.getExpmono().trim()).isEqualTo("*");
            assertThat(response.getExpyearo().trim()).isEqualTo("*");
            assertThat(response.colourOf(CardUpdateResponse.CRDNAME)).isEqualTo(BmsAttributes.DFHRED);
        }

        @ParameterizedTest(name = "[{index}] state ''{0}'' still darkens EXPDAY")
        @ValueSource(strings = {"\u0000", "S", "E", "N", "C", "L", "F", "Q"})
        @DisplayName("3300 always darkens EXPDAY - :1285")
        void expdayIsAlwaysDark(String action) {
            Conversation task = task(reentered(), ChangeAction.of(action));
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());

            paint(task);

            assertThat(response.colourOf(CardUpdateResponse.EXPDAY))
                    .as("app/cbl/COCRDUPC.cbl:1285 is the only unconditional statement in the colour "
                            + "section, so EXPDAY is never visible on any screen")
                    .isEqualTo(BmsAttributes.DFHBMDAR);
        }

        @Test
        @DisplayName("3300 darkens an empty INFOMSG and brightens a filled one - :1309-1313")
        void infomsgAttributeFollowsItsContent() {
            Conversation withMessage = task(reentered(), ChangeAction.showDetails());
            withMessage.setOldDetails(oldDetails());
            CardUpdateRequest request =
                    request("", "", withMessage.carddemoCommarea, CommArea.initialised());

            controller.sendMap3000(request, response, withMessage);

            assertThat(attributeItemOf(request, CardUpdateRequest.INFOMSG_FIELD))
                    .isEqualTo(BmsAttributes.DFHBMBRY);
        }

        @ParameterizedTest(name = "[{index}] state ''{0}'' -> FKEYSC bright={1}")
        @CsvSource({"N,true", "S,false", "E,false"})
        @DisplayName("3300 brightens FKEYSC only while confirming - :1315-1317")
        void fkeyscBrightOnlyWhileConfirming(String action, boolean bright) {
            Conversation task = task(reentered(), ChangeAction.of(action));
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());
            CardUpdateRequest request =
                    request("", "", task.carddemoCommarea, CommArea.initialised());

            controller.sendMap3000(request, response, task);

            if (bright) {
                assertThat(attributeItemOf(request, CardUpdateRequest.FKEYSC_FIELD))
                        .isEqualTo(BmsAttributes.DFHBMBRY);
            } else {
                assertThat(request.metadataFor(CardUpdateRequest.FKEYSC_FIELD).attributeItem())
                        .isEqualTo(CardUpdateRequest.FieldMetadata.FLAG_ITEM_NOT_MODIFIED);
            }
        }

        @Test
        @DisplayName("3400 drops LIT-THISMAPSET's eighth byte - :1326")
        void sendScreen3400DropsTheEighthByte() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            paint(task);

            assertThat(task.ccWorkArea.getCcardNextMapset()).isEqualTo("COCRDUP").hasSize(7);
            assertThat(response.getNextMapset()).isEqualTo("COCRDUP");
            assertThat(response.getNextMap()).isEqualTo("CCRDUPA");
            assertThat(task.wsRespCd).isEqualTo(FileStatus.NORMAL);
        }

        @Test
        @DisplayName("screenMetadataOf publishes all seventeen quads plus the cursor")
        void metadataCarriesEverySeventeenQuad() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            paint(task);

            ScreenMetadata metadata =
                    controller.screenMetadataOf(response, paintedInputArea, task.cursorField);

            assertThat(metadata.fields()).hasSize(17)
                    .containsKeys(CardUpdateResponse.FKEYS, CardUpdateResponse.FKEYSC);
            assertThat(metadata.resetAllOutputFields())
                    .as(":1053 always moves LOW-VALUES and :1333 always sends ERASE")
                    .isTrue();
            assertThat(metadata.cursorField()).isEqualTo(task.cursorField);
        }

        @ParameterizedTest(name = "{0}: keys {1}, details {2}")
        @CsvSource({"NOT_FETCHED, FSE, PRF", "SHOW_DETAILS, PRF, FSE", "NOT_OK, PRF, FSE",
            "NOT_CONFIRMED, PRF, PRF", "OKAYED_AND_DONE, PRF, PRF"})
        @DisplayName("the metadata reports the real xxxA protection byte, per 3300 arm")
        void metadataReportsTheProtectionByteOfEachArm(String state, String keys, String details) {
            Conversation task = task(reentered(), changeActionNamed(state));
            task.setOldDetails(oldDetails());
            allFieldFlagsValid(task);
            paint(task);

            ScreenMetadata metadata =
                    controller.screenMetadataOf(response, paintedInputArea, task.cursorField);

            int expectedKeys = BmsAttributes.unsigned(attributeNamed(keys));
            int expectedDetails = BmsAttributes.unsigned(attributeNamed(details));
            assertThat(metadata.fields().get(CardUpdateResponse.ACCTSID).protection())
                    .isEqualTo(expectedKeys);
            assertThat(metadata.fields().get(CardUpdateResponse.CARDSID).protection())
                    .isEqualTo(expectedKeys);
            for (String field : List.of(CardUpdateResponse.CRDNAME, CardUpdateResponse.CRDSTCD,
                    CardUpdateResponse.EXPMON, CardUpdateResponse.EXPYEAR)) {
                assertThat(metadata.fields().get(field).protection())
                        .as(field + " in state " + state)
                        .isEqualTo(expectedDetails);
            }

            assertThat(metadata.fields().get(CardUpdateResponse.EXPDAY).protection()).isZero();
        }

        @Test
        @DisplayName("the metadata reports the dark/bright message line and the confirmation key line")
        void metadataReportsBrightnessAndTheConfirmationKeyLine() {
            Conversation quiet = task(reentered(), ChangeAction.showDetails());
            quiet.wsInfoMsg = CardUpdateController.WS_INFO_MSG_SPACES;
            CardUpdateRequest quietArea =
                    request("", "", quiet.carddemoCommarea, CommArea.initialised());
            controller.messageAttributes3300(quietArea, quiet);

            ScreenMetadata dark = controller.screenMetadataOf(response, quietArea, null);
            assertThat(dark.fields().get(CardUpdateResponse.INFOMSG).protection())
                    .as(":1310 hides the message line when there is no message")
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHBMDAR));
            assertThat(dark.fields().get(CardUpdateResponse.FKEYSC).protection())
                    .as(":1315-1317 leaves FKEYSC alone unless confirmation is being requested")
                    .isZero();

            Conversation prompting = task(reentered(), ChangeAction.changesOkNotConfirmed());
            prompting.wsInfoMsg = CardUpdateController.PROMPT_FOR_CONFIRMATION;
            CardUpdateRequest promptArea =
                    request("", "", prompting.carddemoCommarea, CommArea.initialised());
            controller.messageAttributes3300(promptArea, prompting);

            ScreenMetadata bright = controller.screenMetadataOf(response, promptArea, null);
            assertThat(bright.fields().get(CardUpdateResponse.INFOMSG).protection())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHBMBRY));
            assertThat(bright.fields().get(CardUpdateResponse.FKEYSC).protection())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHBMBRY));
        }

        @Test
        @DisplayName("screenMetadataOf needs both map areas")
        void metadataNeedsBothAreas() {
            paint(task(reentered(), ChangeAction.showDetails()));

            assertThatThrownBy(() -> controller.screenMetadataOf(null, paintedInputArea, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.screenMetadataOf(response, null, null))
                    .isInstanceOf(NullPointerException.class);
        }

        private ChangeAction changeActionNamed(String state) {
            return switch (state) {
                case "NOT_FETCHED" -> ChangeAction.initial();
                case "SHOW_DETAILS" -> ChangeAction.showDetails();
                case "NOT_OK" -> ChangeAction.changesNotOk();
                case "NOT_CONFIRMED" -> ChangeAction.changesOkNotConfirmed();
                case "OKAYED_AND_DONE" -> ChangeAction.changesOkayedAndDone();
                default -> throw new IllegalArgumentException("Unknown state " + state);
            };
        }

        private byte attributeNamed(String name) {
            return switch (name) {
                case "FSE" -> BmsAttributes.DFHBMFSE;
                case "PRF" -> BmsAttributes.DFHBMPRF;
                default -> throw new IllegalArgumentException("Unknown attribute " + name);
            };
        }

        private byte attributeItemOf(CardUpdateRequest request, String label) {
            String item = request.metadataFor(label).attributeItem();
            return (byte) item.charAt(0);
        }

        private void applyCursorScenario(Conversation task, String scenario) {
            switch (scenario) {
                case "FOUND" -> task.wsInfoMsg = CardUpdateController.FOUND_CARDS_FOR_ACCOUNT;
                case "NO_CHANGES" -> task.wsReturnMsg = CardUpdateController.NO_CHANGES_DETECTED;
                case "ACCT_NOT_OK" -> task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_NOT_OK;
                case "ACCT_BLANK" -> task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_BLANK;
                case "CARD_NOT_OK" -> task.wsEditCardFlag = CardUpdateController.FLG_FILTER_NOT_OK;
                case "CARD_BLANK" -> task.wsEditCardFlag = CardUpdateController.FLG_FILTER_BLANK;
                case "NAME_NOT_OK" ->
                        task.wsEditCardnameFlag = CardUpdateController.FLG_FILTER_NOT_OK;
                case "NAME_BLANK" -> task.wsEditCardnameFlag = CardUpdateController.FLG_FILTER_BLANK;
                case "STATUS_NOT_OK" ->
                        task.wsEditCardstatusFlag = CardUpdateController.FLG_FILTER_NOT_OK;
                case "STATUS_BLANK" ->
                        task.wsEditCardstatusFlag = CardUpdateController.FLG_FILTER_BLANK;
                case "MON_NOT_OK" ->
                        task.wsEditCardexpmonFlag = CardUpdateController.FLG_FILTER_NOT_OK;
                case "MON_BLANK" ->
                        task.wsEditCardexpmonFlag = CardUpdateController.FLG_FILTER_BLANK;
                case "YEAR_NOT_OK" ->
                        task.wsEditCardexpyearFlag = CardUpdateController.FLG_FILTER_NOT_OK;
                case "YEAR_BLANK" ->
                        task.wsEditCardexpyearFlag = CardUpdateController.FLG_FILTER_BLANK;
                case "NOTHING" -> {
                    task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_ISVALID;
                    task.wsEditCardFlag = CardUpdateController.FLG_FILTER_ISVALID;
                    task.wsEditCardnameFlag = CardUpdateController.FLG_FILTER_ISVALID;
                    task.wsEditCardstatusFlag = CardUpdateController.FLG_FILTER_ISVALID;
                    task.wsEditCardexpmonFlag = CardUpdateController.FLG_FILTER_ISVALID;
                    task.wsEditCardexpyearFlag = CardUpdateController.FLG_FILTER_ISVALID;
                }
                default -> throw new IllegalArgumentException("unknown scenario " + scenario);
            }
        }
    }

    @Nested
    @DisplayName("1100-RECEIVE-MAP, 1200-EDIT-MAP-INPUTS and the six edit paragraphs - :578-945")
    class Edits {
        private Conversation edit(CardUpdateRequest request, ChangeAction action) {
            Conversation task = task(reentered(), action);
            controller.processInputs1000(request, task);
            return task;
        }

        private CardUpdateRequest typed(String acctsid, String cardsid, String crdname,
                String crdstcd, String expmon, String expyear) {
            CardUpdateRequest request =
                    request(acctsid, cardsid, reentered(), CommArea.initialised());
            request.setCrdname(crdname);
            request.setCrdstcd(crdstcd);
            request.setExpmon(expmon);
            request.setExpyear(expyear);
            return request;
        }

        @Test
        @DisplayName("1200 starts from INPUT-OK - :644")
        void editsStartFromInputOk() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, CARD_NUMBER, "JOHN Q PUBLIC", "Y", "04",
                    "2026"), ChangeAction.initial());

            assertThat(task.inputOk() || task.inputError()).isTrue();
        }

        @Test
        @DisplayName("1200 edits only the keys before the details are fetched - :646-666")
        void keysOnlyBeforeTheFetch() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, CARD_NUMBER, "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgAcctfilterIsvalid()).isTrue();
            assertThat(task.flgCardfilterIsvalid()).isTrue();
            assertThat(task.flgCardnameBlank())
                    .as("the four detail flags are untouched, so they hold INITIALIZE's space, which is "
                            + "88 FLG-CARDNAME-BLANK VALUE ' '")
                    .isTrue();
        }

        @Test
        @DisplayName("1200 reports no search criteria when both keys are blank - :656-660")
        void bothKeysBlankIsItsOwnMessage() {
            Conversation task = edit(typed("", "", "", "", "", ""), ChangeAction.initial());

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(task.flgCardfilterBlank()).isTrue();
            assertThat(task.wsReturnMsg.trim())
                    .as("app/cbl/COCRDUPC.cbl:656-660 has NO IF WS-RETURN-MSG-OFF guard, unlike every "
                            + "other message assignment in the edits, so it OVERWRITES the prompt 1210 "
                            + "already placed. Both keys blank is reported as one problem, not two")
                    .isEqualTo("No input received");
        }

        @Test
        @DisplayName("1210 blank account: FLG-ACCTFILTER-BLANK + its prompt - :725-735")
        void editAccountBlank() {
            Conversation task = edit(typed("", CARD_NUMBER, "", "", "", ""), ChangeAction.initial());

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(task.wsReturnMsg.trim()).isEqualTo("Account number not provided");
            assertThat(task.carddemoCommarea.acctId())
                    .as(":733 moves ZEROES to CDEMO-ACCT-ID")
                    .isZero();
        }

        @Test
        @DisplayName("1210 treats an all-zero account as unsupplied - :727")
        void editAccountAllZeroIsBlank() {
            Conversation task = edit(typed("00000000000", CARD_NUMBER, "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgAcctfilterBlank())
                    .as("OR CC-ACCT-ID-N EQUAL ZEROS at :727 tests the PIC 9(11) view of the same bytes")
                    .isTrue();
        }

        @Test
        @DisplayName("1210 non-numeric account: the direct-MOVE message - :740-750")
        void editAccountNonNumeric() {
            Conversation task = edit(typed("1234567890X", CARD_NUMBER, "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.wsReturnMsg)
                    .isEqualTo(CardUpdateController.ACCOUNT_FILTER_MUST_BE_11_DIGITS);
        }

        @Test
        @DisplayName("1210 valid account reaches CDEMO-ACCT-ID - :751-755")
        void editAccountValid() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, CARD_NUMBER, "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgAcctfilterIsvalid()).isTrue();
            assertThat(task.carddemoCommarea.acctId()).isEqualTo(11L);
            assertThat(task.newDetails().acctid()).isEqualTo(ACCOUNT_NUMBER);
        }

        @Test
        @DisplayName("1220 blank card: FLG-CARDFILTER-BLANK + its prompt - :767-779")
        void editCardBlank() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, "", "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgCardfilterBlank()).isTrue();
            assertThat(task.wsReturnMsg.trim()).isEqualTo("Card number not provided");
            assertThat(task.carddemoCommarea.cardNum()).isZero();
        }

        @Test
        @DisplayName("1220 non-numeric card: the direct-MOVE message - :783-793")
        void editCardNonNumeric() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, "400000000000000X", "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgCardfilterNotOk()).isTrue();
            assertThat(task.wsReturnMsg)
                    .isEqualTo(CardUpdateController.CARD_ID_FILTER_MUST_BE_16_DIGITS);
        }

        @Test
        @DisplayName("1220 valid card reaches both CDEMO-CARD-NUM and CCUP-NEW-CARDID - :794-798")
        void editCardValid() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, CARD_NUMBER, "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgCardfilterIsvalid()).isTrue();
            assertThat(task.carddemoCommarea.cardNum()).isEqualTo(Long.parseLong(CARD_NUMBER));
            assertThat(task.newDetails().cardid()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("1200 detects no change by comparing the folded CARDDATA groups - :680-682")
        void noChangeDetectedByFoldedComparison() {
            CardUpdateRequest request = typed(ACCOUNT_NUMBER, CARD_NUMBER, "john q public", "y", "04",
                    "2026");
            request.setExpday("30");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            controller.processInputs1000(request, task);

            assertThat(task.noChangesDetected())
                    .as("FUNCTION UPPER-CASE on both sides at :680-681, so case alone is not a change")
                    .isTrue();
            assertThat(task.flgCardnameIsvalid())
                    .as(":686-689 marks all four detail fields valid and skips their edits")
                    .isTrue();
        }

        @Test
        @DisplayName("1100 moves EXPDAYI unconditionally, so omitting it reads as a change - :617")
        void noChangeNeedsTheDayToo() {
            CardUpdateRequest request =
                    typed(ACCOUNT_NUMBER, CARD_NUMBER, "JOHN Q PUBLIC", "Y", "04", "2026");

            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            controller.processInputs1000(request, task);

            assertThat(task.noChangesDetected())
                    .as("EXPDAY is in CCUP-xxx-CARDDATA, so an absent day makes the folded groups differ")
                    .isFalse();
        }

        @ParameterizedTest(name = "[{index}] state ''{0}'' skips the detail edits")
        @CsvSource({"N", "C"})
        @DisplayName("1200 skips the detail edits while confirming or done - :685-691")
        void confirmationStatesSkipTheDetailEdits(String action) {
            CardUpdateRequest request = typed(ACCOUNT_NUMBER, CARD_NUMBER, "", "", "", "");
            Conversation task = task(reentered(), ChangeAction.of(action));
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardnameIsvalid()).isTrue();
            assertThat(task.flgCardstatusIsvalid()).isTrue();
            assertThat(task.flgCardexpmonIsvalid()).isTrue();
            assertThat(task.flgCardexpyearIsvalid()).isTrue();
        }

        @Test
        @DisplayName("1200 a valid change advances to CHANGES-OK-NOT-CONFIRMED - :709-712")
        void aValidChangeAdvances() {
            CardUpdateRequest request = typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE Q PUBLIC", "N", "06",
                    "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.inputError()).isFalse();
            assertThat(task.changeAction().isChangesOkNotConfirmed()).isTrue();
        }

        @Test
        @DisplayName("1200 a rejected change stays on CHANGES-NOT-OK - :709-711")
        void aRejectedChangeStaysNotOk() {
            CardUpdateRequest request = typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE 99", "N", "06",
                    "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.inputError()).isTrue();
            assertThat(task.changeAction().isChangesNotOk()).isTrue();
        }

        @Test
        @DisplayName("1230 blank name: BLANK + its prompt - :810-818")
        void editNameBlank() {
            CardUpdateRequest request = typed(ACCOUNT_NUMBER, CARD_NUMBER, "", "N", "06", "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardnameBlank()).isTrue();
            assertThat(task.wsReturnMsg.trim()).isEqualTo("Card name not provided");
        }

        @ParameterizedTest(name = "[{index}] name ''{0}'' rejected")
        @ValueSource(strings = {"JANE 99", "JANE-PUBLIC", "O'BRIEN", "JOHN.Q"})
        @DisplayName("1230 rejects a name with anything but letters and spaces - :822-838")
        void editNameRejectsNonAlphabetic(String name) {
            CardUpdateRequest request = typed(ACCOUNT_NUMBER, CARD_NUMBER, name, "N", "06", "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardnameNotOk()).isTrue();
            assertThat(task.wsReturnMsg.trim())
                    .isEqualTo("Card name can only contain alphabets and spaces");
        }

        @ParameterizedTest(name = "[{index}] name ''{0}'' accepted")
        @ValueSource(strings = {"JANE Q PUBLIC", "jane q public", "Jane"})
        @DisplayName("1230 accepts letters and spaces - :839")
        void editNameAcceptsAlphabetic(String name) {
            CardUpdateRequest request = typed(ACCOUNT_NUMBER, CARD_NUMBER, name, "N", "06", "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardnameIsvalid()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] status ''{0}'' -> valid={1}")
        @CsvSource({"Y,true", "N,true", "X,false", "y,false", "1,false"})
        @DisplayName("1240 accepts only Y or N, case-sensitively - :861-872")
        void editCardStatus(String status, boolean valid) {
            CardUpdateRequest request =
                    typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE Q PUBLIC", status, "06", "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardstatusIsvalid()).isEqualTo(valid);
            if (!valid) {
                assertThat(task.wsReturnMsg.trim()).isEqualTo("Card Active Status must be Y or N");
            }
        }

        @Test
        @DisplayName("1240 blank status: BLANK, same message - :849-857")
        void editCardStatusBlank() {
            CardUpdateRequest request =
                    typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE Q PUBLIC", "", "06", "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardstatusBlank()).isTrue();
            assertThat(task.wsReturnMsg.trim()).isEqualTo("Card Active Status must be Y or N");
        }

        @ParameterizedTest(name = "[{index}] month ''{0}'' -> valid={1}")
        @CsvSource({"01,true", "12,true", "00,false", "13,false", "1X,false"})
        @DisplayName("1250 accepts 1 through 12 - :896-899")
        void editExpiryMonth(String month, boolean valid) {
            CardUpdateRequest request =
                    typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE Q PUBLIC", "N", month, "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardexpmonIsvalid()).isEqualTo(valid);
            if (!valid) {
                assertThat(task.wsReturnMsg.trim())
                        .isEqualTo("Card expiry month must be between 1 and 12");
            }
        }

        @Test
        @DisplayName("1260 sets NOT-OK after the blank test, not before - :913-924")
        void editExpiryYearBlankArmOrdering() {
            CardUpdateRequest request =
                    typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE Q PUBLIC", "N", "06", "");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardexpyearBlank()).isTrue();
            assertThat(task.wsReturnMsg.trim()).isEqualTo("Invalid card expiry year");
        }

        @ParameterizedTest(name = "[{index}] year ''{0}'' -> valid={1}")
        @CsvSource({"1950,true", "2099,true", "1949,false", "2100,false", "2X99,false"})
        @DisplayName("1260 accepts 1950 through 2099 - :926-929")
        void editExpiryYear(String year, boolean valid) {
            CardUpdateRequest request =
                    typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE Q PUBLIC", "N", "06", year);
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardexpyearIsvalid()).isEqualTo(valid);
            if (!valid) {
                assertThat(task.wsReturnMsg.trim()).isEqualTo("Invalid card expiry year");
            }
        }

        @Test
        @DisplayName("a non-numeric year can still pass: the zoned nibble of 'X' is 8 - :96-99, G34")
        void nonNumericYearCanPassThroughTheRedefines() {
            CardUpdateRequest request =
                    typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE Q PUBLIC", "N", "06", "20X7");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardexpyearIsvalid())
                    .as("'20X7' reads as 2087 through CARD-YEAR-CHECK-N, and 2087 is in 1950 THRU 2099. "
                            + "Integer.parseInt would have rejected an input COBOL accepts")
                    .isTrue();
        }

        @Test
        @DisplayName("a non-numeric month is rejected: the nibble makes it 18 - :92-95")
        void nonNumericMonthIsRejectedByValue() {
            CardUpdateRequest request =
                    typed(ACCOUNT_NUMBER, CARD_NUMBER, "JANE Q PUBLIC", "N", "1X", "2027");
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.processInputs1000(request, task);

            assertThat(task.flgCardexpmonNotOk())
                    .as("'1X' reads as 18, which is outside 1 THRU 12 - rejected by value, not by parse")
                    .isTrue();
        }

        @Test
        @DisplayName("the edits keep the first message, not the last - the WS-RETURN-MSG-OFF guard")
        void theFirstMessageWins() {
            CardUpdateRequest request = typed("", "400000000000000X", "", "", "", "");

            Conversation task = edit(request, ChangeAction.initial());

            assertThat(task.wsReturnMsg.trim())
                    .as("1210 speaks first at :731-733, so 1220's guard at :788-793 leaves it alone")
                    .isEqualTo("Account number not provided");
            assertThat(task.flgCardfilterNotOk())
                    .as("1220 still sets its flag: only the message is guarded")
                    .isTrue();
        }

        @Test
        @DisplayName("1100 reads back its own '*' marker as unsupplied - :594-638")
        void receiveMapTreatsTheMarkerAsUnsupplied() {
            CardUpdateRequest request = typed("*", "*", "*", "*", "*", "*");

            Conversation task = edit(request, ChangeAction.initial());

            assertThat(task.flgAcctfilterBlank())
                    .as("the '*' 3300:1249 wrote is not an account number when it comes back")
                    .isTrue();
            assertThat(task.flgCardfilterBlank()).isTrue();
        }

        @Test
        @DisplayName("1100 reads an empty field as unsupplied - :594-638")
        void receiveMapTreatsSpacesAsUnsupplied() {
            Conversation task = edit(typed("           ", "                ", "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(task.flgCardfilterBlank()).isTrue();
        }
    }

    @Nested
    @DisplayName("9000-READ-DATA and 9100-GETCARD-BYACCTCARD - :1343-1417")
    class BaseRead {
        private Conversation readWith(CardReadResult result, String priorMessage) {
            when(repository.readByCardNumber(anyString())).thenReturn(result);
            Conversation task = taskWithValidKeys(ChangeAction.initial());
            task.wsReturnMsg = priorMessage;
            controller.readData9000(task);
            return task;
        }

        @Test
        @DisplayName("9000 snapshots the keys before the read - :1345-1347")
        void keysAreSnapshottedBeforeTheRead() {
            Conversation task = readWith(CardReadResult.notFound(),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.oldDetails().acctid()).isEqualTo(ACCOUNT_NUMBER);
            assertThat(task.oldDetails().cardid()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("9000 leaves the detail items as INITIALIZE's spaces on NOTFND - :1352")
        void detailItemsStaySpacesOnNotFound() {
            Conversation task = readWith(CardReadResult.notFound(),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.oldDetails().crdname().trim()).isEmpty();
            assertThat(task.oldDetails().crdstcd()).isEqualTo(" ");
        }

        @Test
        @DisplayName("9000 splits YYYY-MM-DD excluding the separators - :1361-1366")
        void expirySplitExcludesTheSeparators() {
            Conversation task = readWith(normalRead(),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.oldDetails().expyear()).isEqualTo("2026");
            assertThat(task.oldDetails().expmon()).isEqualTo("04");
            assertThat(task.oldDetails().expday()).isEqualTo("30");
            assertThat(task.oldDetails().cvvCd())
                    .as(":1354 moves PIC 9(03) into PIC X(3), which zero-fills to three digits")
                    .isEqualTo("123");
            assertThat(task.foundCardsForAccount()).isTrue();
        }

        @Test
        @DisplayName("9000 folds the embossed name in place - :1356-1360")
        void embossedNameIsFoldedInPlace() {
            CardRecord lower =
                    CardRecord.moving(CARD_NUMBER, 11L, 123, "john q public", "2026-04-30", "Y", CODEC);
            Conversation task = readWith(
                    CardReadResult.normal(lower, lower.encodeToImage(CHARSET)),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.oldDetails().crdname().trim()).isEqualTo("JOHN Q PUBLIC");
            assertThat(task.cardRecord).isPresent();
            assertThat(task.cardRecord.orElseThrow().cardEmbossedName().trim())
                    .as("INSPECT CONVERTING alters the record area itself, not only the copy taken out")
                    .isEqualTo("JOHN Q PUBLIC");
        }

        @Test
        @DisplayName("9100 NOTFND sets both key flags and its own message - :1395-1401")
        void notFoundSetsBothKeyFlags() {
            Conversation task = readWith(CardReadResult.notFound(),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.flgCardfilterNotOk()).isTrue();
            assertThat(task.wsReturnMsg).isEqualTo(CardUpdateController.DID_NOT_FIND_ACCTCARD_COMBO);
        }

        @Test
        @DisplayName("9100 NOTFND keeps an earlier message but still sets the flags - :1399-1401")
        void notFoundDefersOnlyTheMessage() {
            String earlier = CardUpdateController.ACCOUNT_FILTER_MUST_BE_11_DIGITS;
            Conversation task = readWith(CardReadResult.notFound(), earlier);

            assertThat(task.wsReturnMsg).isEqualTo(earlier);
            assertThat(task.flgAcctfilterNotOk())
                    .as("the flags at :1397-1398 are outside the guard")
                    .isTrue();
        }

        @Test
        @DisplayName("9100 WHEN OTHER overwrites even a message it just protected - :1404-1411")
        void otherOverwritesTheMessageUnguarded() {
            String earlier = CardUpdateController.ACCOUNT_FILTER_MUST_BE_11_DIGITS;
            Conversation task = readWith(CardReadResult.reportedFailure(FileStatus.NOTOPEN, 2),
                    earlier);

            assertThat(task.inputError()).isTrue();
            assertThat(task.wsReturnMsg)
                    .as(":1411 is outside the guard, so the composed message wins anyway")
                    .isNotEqualTo(earlier)
                    .startsWith("File Error: READ");
            assertThat(task.flgAcctfilterNotOk())
                    .as(":1404-1406 guards the FLAG here, unlike every other use of the guard, so with a "
                            + "message already present the account field is left unhighlighted")
                    .isFalse();
        }

        @Test
        @DisplayName("9100 WHEN OTHER sets the guarded flag when no message was present - :1404-1406")
        void otherSetsTheGuardedFlagWhenTheMessageWasOff() {
            Conversation task = readWith(CardReadResult.reportedFailure(FileStatus.NOTOPEN, 2),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.flgAcctfilterNotOk()).isTrue();
        }

        @Test
        @DisplayName("the file-error message, character for character - :133-152, :1407-1411")
        void fileErrorMessageCharacterForCharacter() {
            Conversation task = readWith(CardReadResult.reportedFailure(19, 2),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            String expected = "File Error: READ     on CARDDAT   returned RESP 000000019 "
                    + ",RESP2 000000002 ";
            assertThat(task.wsReturnMsg)
                    .hasSize(CardUpdateController.WS_RETURN_MSG_LENGTH)
                    .isEqualTo(codecPad(expected, CardUpdateController.WS_RETURN_MSG_LENGTH));
            assertThat(controller.fileErrorMessage(task))
                    .as("the group itself is exactly 80 characters before the move narrows it")
                    .hasSize(CardUpdateController.FILE_ERROR_MESSAGE_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0} with the message already set keeps it")
        @CsvSource({"ACCT", "CARD", "NAME", "STATUS", "MONTH", "YEAR"})
        @DisplayName("every edit's WS-RETURN-MSG-OFF guard defers to a message already placed")
        void everyEditGuardDefersToAnEarlierMessage(String field) {
            String earlier = CardUpdateController.CODING_TO_BE_DONE;
            CardUpdateRequest request = request(
                    "ACCT".equals(field) ? "" : ACCOUNT_NUMBER,
                    "CARD".equals(field) ? "" : CARD_NUMBER,
                    reentered(), CommArea.initialised());
            request.setCrdname("NAME".equals(field) ? "" : "JANE Q PUBLIC");
            request.setCrdstcd("STATUS".equals(field) ? "" : "N");
            request.setExpmon("MONTH".equals(field) ? "" : "06");
            request.setExpyear("YEAR".equals(field) ? "" : "2027");
            request.setExpday("30");

            boolean keyField = "ACCT".equals(field) || "CARD".equals(field);
            Conversation task = task(reentered(),
                    keyField ? ChangeAction.initial() : ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            task.wsReturnMsg = earlier;

            controller.processInputs1000(request, task);

            assertThat(task.wsReturnMsg)
                    .as("the guard means the first message placed survives, whichever edit finds a "
                            + "problem afterwards")
                    .isEqualTo(earlier);
            assertThat(task.inputError())
                    .as("and the flags and INPUT-ERROR are set regardless: only the message is guarded")
                    .isTrue();
        }

        @ParameterizedTest(name = "[{index}] RESP {0} renders as {1}")
        @CsvSource({"0,000000000", "13,000000013", "999999999,999999999"})
        @DisplayName("a RESP renders as nine zero-filled digits, padded to ten - :1409")
        void respCodeImage(int resp, String digits) {
            assertThat(controller.responseCodeImage(resp))
                    .isEqualTo(digits + " ")
                    .hasSize(CardUpdateController.ERROR_RESP_LENGTH);
        }

        @Test
        @DisplayName("a negative RESP loses its sign, as a PIC X move must - :1409")
        void negativeRespLosesItsSign() {
            assertThat(controller.responseCodeImage(-13)).isEqualTo("000000013 ");
        }

        private static String codecPad(String value, int width) {
            return CODEC.movePicX(value, width);
        }
    }

    @Nested
    @DisplayName("0000-MAIN and its five-arm dispatcher - :367-544")
    class Dispatcher {
        private CardUpdateResponse response;

        @BeforeEach
        void newScreen() {
            response = new CardUpdateResponse();
        }

        private Conversation run(CardUpdateRequest request, int eibcalen, byte eibAid) {
            Conversation task = new Conversation();
            controller.main0000(request, response, task, eibcalen, eibAid);
            return task;
        }

        @Test
        @DisplayName("EIBCALEN = 0 initialises and prompts - :388-394")
        void coldStartPrompts() {
            CardUpdateRequest request = request("", "", null, CommArea.initialised());

            Conversation task = run(request, 0, CicsAid.DFHENTER);

            assertThat(task.changeAction().isDetailsNotFetched()).isTrue();
            assertThat(task.wsTranid).isEqualTo("CCUP");
            verifyNoInteractions(repository, service);
        }

        @Test
        @DisplayName("a fresh arrival from the menu is also reinitialised - :390")
        void freshFromMenuIsReinitialised() {
            CardUpdateRequest request = request("", "",
                    NavigationContext.empty().withFromProgram(CardUpdateController.LIT_MENUPGM)
                            .withPgmEnter(), CommArea.initialised());

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(task.changeAction().isDetailsNotFetched()).isTrue();
            assertThat(task.carddemoCommarea.isReenter())
                    .as(":393 sets ENTER, which routes to the prompt arm, and :508 then flips it to "
                            + "REENTER after the screen has been painted - so the state the operator's "
                            + "next keystroke arrives in is REENTER")
                    .isTrue();
        }

        @Test
        @DisplayName("a passed commarea is restored, both halves - :396-400")
        void passedCommareaIsRestored() {
            CommArea trailer = CommArea.initialised()
                    .withChangeAction(ChangeAction.showDetails())
                    .withOldDetails(oldDetails());
            CardUpdateRequest request =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), trailer);

            Conversation task = new Conversation();
            task.carddemoCommarea = reentered();
            task.thisProgCommarea = trailer;
            controller.restoreCommarea(task, CardUpdateController.PASSED_COMMAREA_LENGTH);

            assertThat(task.changeAction().isShowDetails()).isTrue();
            assertThat(task.oldDetails().cardid()).isEqualTo(CARD_NUMBER);
            assertThat(task.carddemoCommarea.fromTranid().trim()).isEqualTo("CCUP");
        }

        @ParameterizedTest(name = "[{index}] {0} in state ''{1}'' -> valid={2}")
        @CsvSource({
            "ENTER,S,true",
            "PFK03,S,true",
            "PFK05,N,true",
            "PFK05,S,false",
            "PFK12,S,true",
            "PFK12,LOW,false",
            "PFK01,S,false",
            "CLEAR,S,false"})
        @DisplayName("validatePfKey accepts only ENTER, PF3, PF5-when-validated and PF12-once-fetched"
                + " - :413-424")
        void pfKeyValidity(AidKey key, String state, boolean valid) {
            Conversation task = task(reentered(),
                    "LOW".equals(state) ? ChangeAction.initial() : ChangeAction.of(state));
            task.ccWorkArea.setCcardAidCondition(key);

            controller.validatePfKey(task);

            assertThat(task.pfkValid()).isEqualTo(valid);
            if (!valid) {
                assertThat(task.ccWorkArea.isCcardAidEnter())
                        .as(":422-424 coerces an unaccepted key to ENTER")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("PF3 transfers to the menu when there is no caller - :435-476")
        void pf3TransfersToTheMenu() {
            CardUpdateRequest request = request(ACCOUNT_NUMBER, CARD_NUMBER,
                    reentered().withFromProgram("").withFromTranid(""), CommArea.initialised());

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHPF3);

            assertThat(response.getNextProgram().trim()).isEqualTo(CardUpdateController.LIT_MENUPGM);
            assertThat(task.returned).isTrue();
        }

        @Test
        @DisplayName("PF3 returns to the calling transaction when there is one - :445-448")
        void pf3ReturnsToTheCaller() {
            CardUpdateRequest request = request(ACCOUNT_NUMBER, CARD_NUMBER,
                    reentered().withFromProgram(CardUpdateController.LIT_CCLISTPGM)
                            .withFromTranid(CardUpdateController.LIT_CCLISTTRANID),
                    CommArea.initialised());

            run(request, CardUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3);

            assertThat(response.getNextProgram().trim())
                    .isEqualTo(CardUpdateController.LIT_CCLISTPGM);
        }

        @ParameterizedTest(name = "[{index}] state ''{0}'' from the card list transfers back")
        @ValueSource(strings = {"C", "L", "F"})
        @DisplayName("a finished or failed update from the card list transfers back - :437-439")
        void finishedUpdateFromTheCardListTransfersBack(String state) {
            CommArea trailer = CommArea.initialised().withChangeAction(ChangeAction.of(state));
            CardUpdateRequest request = request(ACCOUNT_NUMBER, CARD_NUMBER,
                    reentered().withLastMapset(CardUpdateController.LIT_CCLISTMAPSET)
                            .withFromProgram(CardUpdateController.LIT_CCLISTPGM)
                            .withFromTranid(CardUpdateController.LIT_CCLISTTRANID),
                    trailer);

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(response.getNextProgram().trim())
                    .isEqualTo(CardUpdateController.LIT_CCLISTPGM);
            assertThat(task.returned).isTrue();
        }

        @Test
        @DisplayName("arriving from the card list fetches and shows unconditionally - :482-497")
        void arrivingFromTheCardListFetches() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            CardUpdateRequest request = request(ACCOUNT_NUMBER, CARD_NUMBER,
                    NavigationContext.empty()
                            .withFromProgram(CardUpdateController.LIT_CCLISTPGM)
                            .withFromTranid(CardUpdateController.LIT_CCLISTTRANID)
                            .withPgmEnter().withAcctId(11L)
                            .withCardNum(Long.parseLong(CARD_NUMBER)),
                    CommArea.initialised());

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            verify(repository).readByCardNumber(anyString());
            assertThat(task.changeAction().isShowDetails()).isTrue();
        }

        @Test
        @DisplayName("that SET is unconditional: a NOTFND read still shows details - :491-494")
        void arrivingFromTheCardListShowsEvenOnNotFound() {
            when(repository.readByCardNumber(anyString())).thenReturn(CardReadResult.notFound());
            CardUpdateRequest request = request(ACCOUNT_NUMBER, CARD_NUMBER,
                    NavigationContext.empty()
                            .withFromProgram(CardUpdateController.LIT_CCLISTPGM)
                            .withPgmEnter().withAcctId(11L)
                            .withCardNum(Long.parseLong(CARD_NUMBER)),
                    CommArea.initialised());

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(task.changeAction().isShowDetails())
                    .as("app/cbl/COCRDUPC.cbl:494 has no IF FOUND-CARDS-FOR-ACCOUNT guard, unlike :963")
                    .isTrue();
        }

        @Test
        @DisplayName("the prompt arm paints then sets REENTER - :502-511")
        void promptArmPaintsThenSetsReenter() {
            CardUpdateRequest request = request("", "",
                    NavigationContext.empty().withPgmEnter(), CommArea.initialised());

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(response.getInfomsgo().trim())
                    .isEqualTo(CardUpdateController.PROMPT_FOR_SEARCH_KEYS.trim());
            assertThat(task.carddemoCommarea.isReenter())
                    .as(":508 sets REENTER after 3000 has already run")
                    .isTrue();
            assertThat(task.changeAction().isDetailsNotFetched()).isTrue();
        }

        @Test
        @DisplayName("the reset arm clears the carried keys and repaints - :517-528")
        void resetArmClearsTheCarriedKeys() {
            CommArea trailer = CommArea.initialised()
                    .withChangeAction(ChangeAction.changesOkayedAndDone())
                    .withOldDetails(oldDetails());
            CardUpdateRequest request = request(ACCOUNT_NUMBER, CARD_NUMBER,
                    reentered().withLastMapset("COCRDUP"), trailer);

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(task.changeAction().isDetailsNotFetched()).isTrue();
            assertThat(task.carddemoCommarea.acctId()).isZero();
            assertThat(task.carddemoCommarea.cardNum()).isZero();
        }

        @Test
        @DisplayName("WHEN OTHER runs 1000, 2000 and 3000 - :535-542")
        void whenOtherRunsTheStateMachine() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            CommArea trailer = CommArea.initialised().withChangeAction(ChangeAction.initial());
            CardUpdateRequest request =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), trailer);

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            verify(repository).readByCardNumber(anyString());
            assertThat(task.changeAction().isShowDetails()).isTrue();
            assertThat(response.getCrdnameo().trim()).isEqualTo("JOHN Q PUBLIC");
        }

        @ParameterizedTest(name = "[{index}] state ''{0}'' without the card-list mapset stays here")
        @ValueSource(strings = {"C", "L", "F"})
        @DisplayName("a finished update NOT from the card list stays on this screen - :437-439, :517")
        void finishedUpdateElsewhereStaysHere(String state) {
            CommArea trailer = CommArea.initialised().withChangeAction(ChangeAction.of(state));
            CardUpdateRequest request = request(ACCOUNT_NUMBER, CARD_NUMBER,
                    reentered().withLastMapset("COCRDUP"), trailer);

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(response.getNextMap())
                    .as("no XCTL: the reset arm repaints this screen")
                    .isEqualTo("CCRDUPA");
            assertThat(task.changeAction().isDetailsNotFetched()).isTrue();
        }

        @Test
        @DisplayName("PF12 from somewhere other than the card list falls through - :488")
        void pfk12FromElsewhereFallsThrough() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            CommArea trailer = CommArea.initialised().withChangeAction(ChangeAction.showDetails())
                    .withOldDetails(oldDetails());
            CardUpdateRequest request = request(ACCOUNT_NUMBER, CARD_NUMBER,
                    reentered().withFromProgram(CardUpdateController.LIT_MENUPGM)
                            .withPgmReenter(), trailer);

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHPF12);

            assertThat(task.ccWorkArea.isCcardAidPfk12())
                    .as("PF12 with details already fetched is an accepted key at :417, so it survives "
                            + "validatePfKey")
                    .isTrue();
            verify(repository).readByCardNumber(anyString());
        }

        @Test
        @DisplayName("the menu half of the prompt arm's condition - :505")
        void promptArmMenuHalf() {
            CommArea trailer = CommArea.initialised().withChangeAction(ChangeAction.showDetails());
            CardUpdateRequest request = request("", "",
                    NavigationContext.empty().withFromProgram(CardUpdateController.LIT_MENUPGM)
                            .withPgmEnter(), trailer);

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(task.changeAction().isDetailsNotFetched())
                    .as(":510 resets the action after the prompt is painted")
                    .isTrue();
            verifyNoInteractions(repository, service);
        }

        @Test
        @DisplayName("3100 reads the clock twice and uses the second - :1055, :1062")
        void screenInitReadsTheClockTwice() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());

            controller.screenInit3100(response, task);

            assertThat(task.dateHeader).isNotNull();
            assertThat(task.dateHeader.wsCurdateMmDdYy()).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).isEqualTo("14:35:07");
        }

        @Test
        @DisplayName("COMMON-RETURN returns both halves of the commarea - :551-558")
        void commonReturnCarriesBothHalves() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            CommArea trailer = CommArea.initialised().withChangeAction(ChangeAction.initial());
            CardUpdateRequest request =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), trailer);

            Conversation task = run(request, CardUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(response.getCommArea()).isNotNull();
            assertThat(response.getCommArea().changeAction().isShowDetails()).isTrue();
            assertThat(task.wsCommarea)
                    .as("WS-COMMAREA is PIC X(2000) and carries the 160 + "
                            + CommArea.RECORD_LENGTH + " bytes at 1-based offset 161")
                    .hasSize(2000);
        }
    }

    @Nested
    @DisplayName("The HTTP boundary - PUT /api/cards/{cardNum}")
    class Boundary {
        @Test
        @DisplayName("bind moves the path card number into CARDSID")
        void bindMovesThePathCardNumber() {
            CardUpdateRequest bound = controller.bind(CARD_NUMBER, null);

            assertThat(bound.getCardsid()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("bind pads a short card number to X(16)")
        void bindPadsAShortCardNumber() {
            CardUpdateRequest bound = controller.bind("4000", null);

            assertThat(bound.getCardsid()).isEqualTo("4000            ")
                    .hasSize(CardUpdateRequest.CARDSID_LENGTH);
        }

        @Test
        @DisplayName("bind refuses an over-width card number rather than truncating it")
        void bindRefusesAnOverWidthCardNumber() {
            assertThatThrownBy(() -> controller.bind("40000000000000019999", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CARDSIDI PIC X(16)");
        }

        @Test
        @DisplayName("bind refuses an over-width account filter")
        void bindRefusesAnOverWidthAccountFilter() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setAcctsid("123456789012345");

            assertThatThrownBy(() -> controller.bind(CARD_NUMBER, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ACCTSIDI PIC X(11)");
        }

        @Test
        @DisplayName("bind projects the card number into a passed commarea only")
        void bindProjectsIntoAPassedCommareaOnly() {
            CardUpdateRequest without = controller.bind(CARD_NUMBER, null);
            assertThat(CardUpdateController.resolveEibcalen(null, without)).isZero();

            CardUpdateRequest request = new CardUpdateRequest();
            request.setNavigationContext(reentered());
            CardUpdateRequest with = controller.bind(CARD_NUMBER, request);
            assertThat(with.getNavigationContext().cardNum())
                    .isEqualTo(Long.parseLong(CARD_NUMBER));
            assertThat(CardUpdateController.resolveEibcalen(null, with))
                    .isEqualTo(CardUpdateController.PASSED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("bind: an absent state token is the cold start, not a refusal - EIBCALEN = 0 at :388")
        void bindTreatsAnAbsentStateTokenAsAColdStart() {
            CardUpdateRequest received = new CardUpdateRequest();
            received.setNavigationContext(reentered());

            CardUpdateRequest bound = controller.bind(CARD_NUMBER, received);

            assertThat(bound.getStateToken()).isEmpty();
            assertThat(bound.getCommArea())
                    .as("INITIALIZE WS-THIS-PROGCOMMAREA, from which CCUP-DETAILS-NOT-FETCHED holds and "
                            + "no write arm is reachable")
                    .isEqualTo(CommArea.initialised());
        }

        @Test
        @DisplayName("bind: a state token this screen issued restores all 329 bytes")
        void bindRestoresAnIssuedStateToken() {
            CommArea awaiting = fetchedTrailer(ChangeAction.changesOkNotConfirmed());
            CardUpdateRequest received = new CardUpdateRequest();
            received.setNavigationContext(reentered());
            received.setStateToken(ConversationStateSealFixture.seal().seal(
                    CardUpdateController.STATE_PURPOSE, CARD_NUMBER, awaiting.encode(CODEC)));

            assertThat(controller.bind(CARD_NUMBER, received).getCommArea()).isEqualTo(awaiting);
        }

        @Test
        @DisplayName("bind: a forged state token is refused, and no value is echoed")
        void bindRefusesAForgedStateToken() {
            CardUpdateRequest received = new CardUpdateRequest();
            received.setStateToken("bm90LWEtdG9rZW4tYXQtYWxs");

            ScreenInputRejectedException refusal = catchThrowableOfType(
                    ScreenInputRejectedException.class,
                    () -> controller.bind(CARD_NUMBER, received));

            assertThat(refusal).isNotNull();
            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
            assertThat(refusal.member()).contains(CardUpdateController.STATE_TOKEN_MEMBER);
            assertThat(refusal.publicDetail()).doesNotContain("bm90LWEtdG9rZW4tYXQtYWxs");
        }

        @Test
        @DisplayName("bind: a state token issued for another card is refused by this URI - and with it "
                + "the CCUP-OLD-DETAILS, CVV included, that belongs to that card")
        void bindRefusesAStateTokenIssuedForAnotherCard() {
            String forAnotherCard = ConversationStateSealFixture.seal().seal(
                    CardUpdateController.STATE_PURPOSE, CARD_NUMBER,
                    fetchedTrailer(ChangeAction.changesOkNotConfirmed()).encode(CODEC));
            CardUpdateRequest received = new CardUpdateRequest();
            received.setStateToken(forAnotherCard);

            ScreenInputRejectedException refusal = catchThrowableOfType(
                    ScreenInputRejectedException.class,
                    () -> controller.bind("4000000000000002", received));

            assertThat(refusal).isNotNull();
            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.STATE_NAMES_ANOTHER_RECORD);
            assertThat(refusal.publicDetail()).doesNotContain(CARD_NUMBER);
        }

        /** A non-numeric path value carries no numeric commarea value. */
        @Test
        @DisplayName("a non-numeric card number carries zero into CDEMO-CARD-NUM")
        void nonNumericCardNumberCarriesZero() {
            assertThat(CardUpdateController.carriedCardNumber("400000000000000X")).isZero();
            assertThat(CardUpdateController.carriedCardNumber("")).isZero();
            assertThat(CardUpdateController.carriedCardNumber(CARD_NUMBER))
                    .isEqualTo(Long.parseLong(CARD_NUMBER));
        }

        @Test
        @DisplayName("resolveEibcalen honours an agreeing explicit value")
        void eibcalenHonoursAnAgreeingValue() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setNavigationContext(reentered());

            assertThat(CardUpdateController.resolveEibcalen(
                    CardUpdateController.PASSED_COMMAREA_LENGTH, request))
                    .isEqualTo(CardUpdateController.PASSED_COMMAREA_LENGTH);
            assertThat(CardUpdateController.resolveEibcalen(null, request))
                    .isEqualTo(CardUpdateController.PASSED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("resolveEibcalen refuses an explicit 0 when a commarea was passed - :388")
        void eibcalenRefusesAZeroThatContradictsThePayload() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setNavigationContext(reentered());

            assertThatThrownBy(() -> CardUpdateController.resolveEibcalen(0, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot contradict");
        }

        @Test
        @DisplayName("resolveEibcalen refuses a length the payload contradicts")
        void eibcalenRefusesAContradiction() {
            CardUpdateRequest request = new CardUpdateRequest();

            assertThatThrownBy(() -> CardUpdateController.resolveEibcalen(
                    CardUpdateController.PASSED_COMMAREA_LENGTH, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an absent EIBAID is DFHENTER")
        void absentAidIsEnter() {
            assertThat(CardUpdateController.resolveAttentionIdentifier(null))
                    .isEqualTo(CicsAid.DFHENTER);
        }

        @ParameterizedTest(name = "[{index}] EIBAID {0} is refused")
        @ValueSource(ints = {-1, 256, 1000})
        @DisplayName("an out-of-range EIBAID is refused")
        void outOfRangeAidIsRefused(int value) {
            assertThatThrownBy(() -> CardUpdateController.resolveAttentionIdentifier(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an in-range EIBAID passes through, including above 0x7F")
        void inRangeAidPassesThrough() {
            assertThat(CardUpdateController.resolveAttentionIdentifier(
                    CicsAid.DFHPF3 & 0xFF)).isEqualTo(CicsAid.DFHPF3);
        }
    }

    @Nested
    @DisplayName("The figurative-constant and REDEFINES primitives")
    class Primitives {
        @Test
        @DisplayName("isLowValuesOrSpaces recognises both patterns and nothing else")
        void lowValuesOrSpaces() {
            assertThat(CardUpdateController.isLowValuesOrSpaces("\u0000\u0000\u0000\u0000", 4)).isTrue();
            assertThat(CardUpdateController.isLowValuesOrSpaces("    ", 4)).isTrue();
            assertThat(CardUpdateController.isLowValuesOrSpaces("CCUP", 4)).isFalse();
            assertThat(CardUpdateController.isLowValuesOrSpaces("", 4))
                    .as("a short value is padded to the declared width before the comparison")
                    .isTrue();
        }

        @Test
        @DisplayName("isAllZeroCharacters compares the CHARACTER zero, not the value")
        void allZeroCharacters() {
            assertThat(CardUpdateController.isAllZeroCharacters("00000000000", 11)).isTrue();
            assertThat(CardUpdateController.isAllZeroCharacters("00000000001", 11)).isFalse();
            assertThat(CardUpdateController.isAllZeroCharacters("           ", 11)).isFalse();
        }

        @Test
        @DisplayName("the numeric view of a non-numeric value does not throw - G34")
        void numericViewOfNonNumericDoesNotThrow() {
            assertThat(CardUpdateController.zonedDigitsValue("123", 3, CODEC)).isEqualTo(123);
            assertThat(CardUpdateController.zonedDigitsValue("A12", 3, CODEC))
                    .as("never Integer.parseInt: a REDEFINES read has no failure mode")
                    .isNotNull();
            assertThat(CardUpdateController.zonedDigitsValue("   ", 3, CODEC)).isNotNull();
            assertThat(CardUpdateController.zonedDigitsValue("000", 3, CODEC)).isZero();
        }

        @ParameterizedTest(name = "[{index}] {0} digits round-trip")
        @CsvSource({"11,00000000011", "3,123", "16,4000000000000001", "10,2026043012"})
        @DisplayName("every REDEFINES pair round-trips - :103-124, G34")
        void redefinesPairsRoundTrip(int width, String digits) {
            long value = CardUpdateController.zonedDigitsValue(digits, width, CODEC);

            assertThat(CODEC.movePic9(value, width))
                    .as("the alphanumeric view of the numeric view is the value it started from")
                    .isEqualTo(digits);
        }

        @Test
        @DisplayName("inspectConverting folds only the 26 declared pairs")
        void inspectConvertingFoldsOnlyTheDeclaredPairs() {
            String folded = CardUpdateController.inspectConverting("abc-XYZ 123",
                    CardUpdateController.LIT_LOWER, CardUpdateController.LIT_UPPER);

            assertThat(folded)
                    .as("only a-z are in LIT-LOWER, so the hyphen, the digits and the space survive")
                    .isEqualTo("ABC-XYZ 123");
        }

        @Test
        @DisplayName("inspectConverting leaves an unlisted character alone")
        void inspectConvertingLeavesUnlistedCharactersAlone() {
            assertThat(CardUpdateController.inspectConverting("\u00E9", CardUpdateController.LIT_LOWER,
                    CardUpdateController.LIT_UPPER))
                    .as("String.toUpperCase would have folded this; the 26 declared pairs do not")
                    .isEqualTo("\u00E9");
        }

        @Test
        @DisplayName("isTrimmedEmpty is FUNCTION LENGTH(FUNCTION TRIM(x)) = 0 - :826")
        void trimmedEmpty() {
            assertThat(CardUpdateController.isTrimmedEmpty("   ")).isTrue();
            assertThat(CardUpdateController.isTrimmedEmpty("")).isTrue();
            assertThat(CardUpdateController.isTrimmedEmpty(" X ")).isFalse();
        }

        @Test
        @DisplayName("functionUpperCase folds the 26 declared pairs - :680-681")
        void functionUpperCase() {
            assertThat(CardUpdateController.functionUpperCase("john q public"))
                    .isEqualTo("JOHN Q PUBLIC");
        }

        @Test
        @DisplayName("isAsteriskOrSpaces recognises '*' and SPACES only, not LOW-VALUES - :587-588")
        void asteriskOrSpaces() {
            assertThat(CardUpdateController.isAsteriskOrSpaces("*", 11)).isTrue();
            assertThat(CardUpdateController.isAsteriskOrSpaces("           ", 11)).isTrue();
            assertThat(CardUpdateController.isAsteriskOrSpaces("\u0000", 11))
                    .as("app/cbl/COCRDUPC.cbl:587-588 tests '*' and SPACES only; LOW-VALUES falls to the "
                            + "ELSE and is caught later by the edit paragraph's own LOW-VALUES test")
                    .isFalse();
            assertThat(CardUpdateController.isAsteriskOrSpaces("00000000011", 11)).isFalse();
        }
    }

    @Nested
    @DisplayName("HTTP wiring - transport only, no decision asserted here")
    class HttpWiring {
        private MockMvc mockMvc;
        private final ObjectMapper mapper = new ObjectMapper();

        @BeforeEach
        void standaloneSetup() {
            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .build();
        }

        @Test
        @DisplayName("PUT /api/cards/{cardNum} routes and answers 200 with the map")
        void putRoutes() throws Exception {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            CardUpdateRequest body = request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(),
                    CommArea.initialised().withChangeAction(ChangeAction.initial()));

            mockMvc.perform(put("/api/cards/{cardNum}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value("CCUP"))
                    .andExpect(jsonPath("$.pgmname").value("COCRDUPC"))
                    .andExpect(jsonPath("$.cardsid").value(CARD_NUMBER))
                    .andExpect(jsonPath("$.screenMetadata").exists());
        }

        @Test
        @DisplayName("an absent body is spaces, not a rejection")
        void absentBodyIsAccepted() throws Exception {
            mockMvc.perform(put("/api/cards/{cardNum}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the response carries both FKEYS and FKEYSC as distinct members - G9")
        void bothFkeysFieldsAreSerialised() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThat(response.getFkeyso()).hasSize(21);
            assertThat(response.getFkeysco())
                    .as("FKEYSC is an X(18) FIELD at app/cpy-bms/COCRDUP.CPY:115-120, not the colour "
                            + "item of FKEYS; a suffix-stripping mapper would have deleted it")
                    .hasSize(18);
            assertThat(CardUpdateResponse.namedFields()).hasSize(17);
        }

        @Test
        @DisplayName("the mapping returns a ScreenResponse envelope with the metadata beside the screen")
        void envelopeCarriesMetadata() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            CardUpdateRequest body = request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(),
                    CommArea.initialised().withChangeAction(ChangeAction.initial()));

            ResponseEntity<ScreenResponse<CardUpdateResponse>> answer =
                    controller.updateCardDetail(CARD_NUMBER, body, null, null, null);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            CardUpdateResponse screen = screenOf(answer);
            assertThat(screen.getTrnnameo()).isEqualTo("CCUP");
            ScreenResponse<CardUpdateResponse> envelope = answer.getBody();
            assertThat(envelope).isNotNull();
            assertThat(envelope.screenMetadata()).isNotNull();
            assertThat(envelope.screenMetadata().fields()).hasSize(17);
        }
    }

    @Nested
    @DisplayName("The CCUP-CHANGE-ACTION alphabet - every byte, routed through :949-1027 (G30, G50)")
    class StateAlphabet {
        @ParameterizedTest(name = "[{index}] {0} -> {1}")
        @CsvSource({
            "LOW_VALUES,S",
            "SPACES,S",
            "S,S_TO_N",
            "E,E",
            "N,N",
            "C,S",
            "L,ABEND",
            "F,ABEND",
            "Q,ABEND"})
        @DisplayName("every byte lands on exactly one arm, and three of them abend - :949-1027")
        void everyStateByteLandsOnItsArm(String token, String expected) {
            String state = switch (token) {
                case "LOW_VALUES" -> ChangeAction.LOW_VALUES;
                case "SPACES" -> ChangeAction.SPACES;
                default -> token;
            };
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            Conversation task = taskWithValidKeys(ChangeAction.of(state));
            task.ccWorkArea.setCcardAidCondition(AidKey.ENTER);

            if ("ABEND".equals(expected)) {
                assertThatThrownBy(() -> controller.decideAction2000(task))
                        .as("%s matches no WHEN before :1019, so WHEN OTHER is the only arm left", token)
                        .isInstanceOf(AbendException.class);
                assertThat(task.abendData.abendMsg().trim()).isEqualTo("UNEXPECTED DATA SCENARIO");
                return;
            }

            controller.decideAction2000(task);

            String want = "S_TO_N".equals(expected) ? "N" : expected;
            assertThat(task.changeAction().value()).isEqualTo(want);
        }

        @Test
        @DisplayName("VALUE LOW-VALUES is the state of a first request carrying no commarea - :276-277")
        void theDeclaredInitialValueIsLowValues() {
            CardUpdateRequest request = request("", "", null, CommArea.initialised());
            Conversation task = new Conversation();

            controller.main0000(request, new CardUpdateResponse(), task, 0, CicsAid.DFHENTER);

            assertThat(task.changeAction().value())
                    .as("app/cbl/COCRDUPC.cbl:277 declares VALUE LOW-VALUES, which is x'00'")
                    .isEqualTo("\u0000");
            assertThat(ChangeAction.LOW_VALUES).isEqualTo("\u0000");
            assertThat(ChangeAction.RECORD_LENGTH)
                    .as("PIC X(1) - one byte, and the first of the 329")
                    .isEqualTo(1);
            assertThat(task.changeAction().isDetailsNotFetched()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] outcome {0} still writes exactly once")
        @CsvSource({"COULD_NOT_LOCK_FOR_UPDATE", "LOCKED_BUT_UPDATE_FAILED",
            "DATA_WAS_CHANGED_BEFORE_UPDATE", "CHANGES_OKAYED_AND_DONE"})
        @DisplayName("the confirm arm performs 9200 exactly once, whatever it returns - :990-991")
        void theConfirmArmWritesExactlyOnce(WriteOutcome outcome) {
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(writeResult(outcome));
            Conversation task = task(reentered(), ChangeAction.changesOkNotConfirmed());
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());
            task.ccWorkArea.setCcardAidCondition(AidKey.PFK05);

            controller.decideAction2000(task);

            verify(service, times(1)).writeProcessing(any(), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("the confirm arm re-runs the four edits first, and a screen that no longer edits "
                + "clean is repainted rather than written - :696-714 before :988")
        void theConfirmArmRefusesValuesThatDoNotEditClean() {
            Conversation task = task(reentered(), ChangeAction.changesOkNotConfirmed());
            task.setOldDetails(oldDetails());
            // A status only a composed payload can present: the state the arm claims says the four edits
            // passed, and CRDSTCD says they cannot have. On a 3270 the two are the same screen image, so
            // the source has no reason to re-check; over HTTP they are two independent messages.
            task.setNewDetails(newDetails().withCrdstcd("Q"));
            task.ccWorkArea.setCcardAidCondition(AidKey.PFK05);

            controller.decideAction2000(task);

            verify(service, never()).writeProcessing(any(), any(), any(), anyString(), any());
            // :696 is where a failed pass leaves the state, and the field carries the edit's own verdict,
            // so 3000-SEND-MAP paints exactly the screen 1200 paints for this input.
            assertThat(task.changeAction().isChangesNotOk()).isTrue();
            assertThat(task.inputError()).isTrue();
            assertThat(task.wsEditCardstatusFlag).isEqualTo(CardUpdateController.FLG_FILTER_NOT_OK);
            assertThat(task.wsReturnMsg)
                    .isEqualTo(CardUpdateController.CARD_STATUS_MUST_BE_YES_NO);
        }

        @Test
        @DisplayName("the confirm arm's re-run is invisible when the values do edit clean: the flags and "
                + "the message are the image the turn arrived with")
        void theConfirmArmsReEditLeavesACleanScreenUntouched() {
            when(service.writeProcessing(any(), any(), any(), anyString(), any()))
                    .thenReturn(writeResult(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE));
            Conversation task = task(reentered(), ChangeAction.changesOkNotConfirmed());
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());
            task.ccWorkArea.setCcardAidCondition(AidKey.PFK05);
            // The image :685-693 leaves on the confirming turn: all four flags declared valid and no
            // message.
            task.wsEditCardnameFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardstatusFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardexpmonFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardexpyearFlag = CardUpdateController.FLG_FILTER_ISVALID;

            controller.decideAction2000(task);

            // The write was reached, so the guard was transparent; and nothing the edit pass writes was
            // left behind - the four flags still read valid and the message is the one 9200's outcome set,
            // not one an edit set.
            verify(service).writeProcessing(any(), any(), any(), anyString(), any());
            assertThat(task.inputError()).isFalse();
            assertThat(task.wsEditCardnameFlag).isEqualTo(CardUpdateController.FLG_FILTER_ISVALID);
            assertThat(task.wsEditCardstatusFlag).isEqualTo(CardUpdateController.FLG_FILTER_ISVALID);
            assertThat(task.wsEditCardexpmonFlag).isEqualTo(CardUpdateController.FLG_FILTER_ISVALID);
            assertThat(task.wsEditCardexpyearFlag).isEqualTo(CardUpdateController.FLG_FILTER_ISVALID);
        }

        /**
         * And the six arms that are not the confirm arm perform it <strong>never</strong>.
         *
         * <p>{@code verify(service, never())} is the source-order proof the class documentation names:
         * {@code 'N'} without {@code PF5} reaches the bare {@code WHEN} at {@code :1006} only because
         * {@code :988} was tested first and demanded the key. Move {@code :1006} above {@code :988} and
         * this row still passes for every other state while {@code PF5} quietly stops saving - so the
         * {@code 'N'} row here is the one that fails, and it fails loudly.
         */
        @ParameterizedTest(name = "[{index}] state ''{0}'' never writes")
        @ValueSource(strings = {"\u0000", " ", "S", "E", "N", "C"})
        @DisplayName("every other arm performs 9200 never - :954, :971, :982, :1006, :1011")
        void everyOtherArmWritesNever(String state) {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            Conversation task = taskWithValidKeys(ChangeAction.of(state));
            task.ccWorkArea.setCcardAidCondition(AidKey.ENTER);

            controller.decideAction2000(task);

            verify(service, never()).writeProcessing(any(), any(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("The message literals, byte for byte - :156-214 (G50)")
    class MessageLiterals {
        private void assertReturnMessage(String actual, String literal) {
            assertThat(actual)
                    .as("the literal itself, before the pad")
                    .startsWith(literal);
            assertThat(actual)
                    .as("88-levels on WS-RETURN-MSG PIC X(75) are space-filled to 75 by SET, :173")
                    .hasSize(CardUpdateController.WS_RETURN_MSG_LENGTH)
                    .isEqualTo(literal + " ".repeat(
                            CardUpdateController.WS_RETURN_MSG_LENGTH - literal.length()));
        }

        @Test
        @DisplayName("the six edit-paragraph messages, 1210 to 1260 - :177-200")
        void theSixEditParagraphMessages() {
            assertReturnMessage(CardUpdateController.WS_PROMPT_FOR_ACCT,
                    "Account number not provided");
            assertReturnMessage(CardUpdateController.WS_PROMPT_FOR_CARD, "Card number not provided");
            assertReturnMessage(CardUpdateController.WS_PROMPT_FOR_NAME, "Card name not provided");
            assertReturnMessage(CardUpdateController.WS_NAME_MUST_BE_ALPHA,
                    "Card name can only contain alphabets and spaces");
            assertReturnMessage(CardUpdateController.CARD_STATUS_MUST_BE_YES_NO,
                    "Card Active Status must be Y or N");
            assertReturnMessage(CardUpdateController.CARD_EXPIRY_MONTH_NOT_VALID,
                    "Card expiry month must be between 1 and 12");
            assertReturnMessage(CardUpdateController.CARD_EXPIRY_YEAR_NOT_VALID,
                    "Invalid card expiry year");
        }

        @Test
        @DisplayName("the input, change and read messages - :185-188, :201-214")
        void theRemainingReturnMessages() {
            assertReturnMessage(CardUpdateController.NO_SEARCH_CRITERIA_RECEIVED,
                    "No input received");
            assertReturnMessage(CardUpdateController.NO_CHANGES_DETECTED,
                    "No change detected with respect to values fetched.");
            assertReturnMessage(CardUpdateController.SEARCHED_CARD_NOT_NUMERIC,
                    "Card number if supplied must be a 16 digit number");
            assertReturnMessage(CardUpdateController.DID_NOT_FIND_ACCT_IN_CARDXREF,
                    "Did not find this account in cards database");
            assertReturnMessage(CardUpdateController.DID_NOT_FIND_ACCTCARD_COMBO,
                    "Did not find cards for this search condition");
            assertReturnMessage(CardUpdateController.XREF_READ_ERROR,
                    "Error reading Card Data File");
            assertReturnMessage(CardUpdateController.CODING_TO_BE_DONE,
                    "Looks Good.... so far");
        }

        @Test
        @DisplayName("'PF03 pressed.Exiting              ' keeps its fourteen inner spaces - :175-176")
        void theExitMessageKeepsItsTrailingSpaces() {
            String literal = "PF03 pressed.Exiting              ";

            assertThat(literal)
                    .as("20 characters of text plus 14 trailing spaces, as the source quotes it")
                    .hasSize(34);
            assertThat(literal.strip()).hasSize(20);
            assertReturnMessage(CardUpdateController.WS_EXIT_MESSAGE, literal);
            assertThat(CardUpdateController.WS_EXIT_MESSAGE.charAt(33))
                    .as("the last character inside the quotes is a space, not the start of the pad")
                    .isEqualTo(' ');
        }

        @Test
        @DisplayName("SEARCHED-ACCT-ZEROES and -NOT-NUMERIC are one byte pattern, two names - :189-192")
        void theTwoAccountConditionsShareOneLiteral() {
            String shared = "Account number must be a non zero 11 digit number";

            assertReturnMessage(CardUpdateController.SEARCHED_ACCT_ZEROES, shared);
            assertReturnMessage(CardUpdateController.SEARCHED_ACCT_NOT_NUMERIC, shared);
            assertThat(CardUpdateController.SEARCHED_ACCT_NOT_NUMERIC)
                    .as("byte-identical: :190 and :192 quote the same 49 characters, so no test may "
                            + "ever tell the two condition names apart by their text")
                    .isEqualTo(CardUpdateController.SEARCHED_ACCT_ZEROES);
            assertThat(CardUpdateController.ACCOUNT_FILTER_MUST_BE_11_DIGITS)
                    .as("and the message 1210-EDIT-ACCOUNT actually moves at :744-746 is a DIFFERENT "
                            + "literal, so neither alias can be mistaken for the live one")
                    .isNotEqualTo(CardUpdateController.SEARCHED_ACCT_ZEROES);
        }

        @ParameterizedTest(name = "[{index}] ACCTSID ''{0}'' is rejected")
        @ValueSource(strings = {"00000000000", "0000000000A"})
        @DisplayName("both aliased conditions are driven by input, not by message text - :189-192")
        void theTwoAccountConditionsAreDistinguishedByInput(String typed) {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.ccWorkArea.setCcAcctId(typed);

            controller.editAccount1210(task);

            assertThat(task.wsEditAcctFlag)
                    .as("neither an all-zeroes nor a non-numeric account may set FLG-ACCTFILTER-ISVALID")
                    .isNotEqualTo(CardUpdateController.FLG_FILTER_ISVALID);
            assertThat(task.returnMessageOff())
                    .as("and a rejection always leaves a message behind")
                    .isFalse();
        }

        private void assertInfoMessage(String actual, String literal) {
            assertThat(actual)
                    .as("88-levels on WS-INFO-MSG PIC X(40) are space-filled to 40, :157")
                    .hasSize(CardUpdateController.WS_INFO_MSG_LENGTH)
                    .isEqualTo(literal + " ".repeat(
                            CardUpdateController.WS_INFO_MSG_LENGTH - literal.length()));
        }

        @Test
        @DisplayName("the six informational messages at X(40) - :160-171")
        void theSixInformationalMessages() {
            assertInfoMessage(CardUpdateController.FOUND_CARDS_FOR_ACCOUNT,
                    "Details of selected card shown above");
            assertInfoMessage(CardUpdateController.PROMPT_FOR_SEARCH_KEYS,
                    "Please enter Account and Card Number");
            assertInfoMessage(CardUpdateController.PROMPT_FOR_CHANGES,
                    "Update card details presented above.");
            assertInfoMessage(CardUpdateController.PROMPT_FOR_CONFIRMATION,
                    "Changes validated.Press F5 to save");
            assertInfoMessage(CardUpdateController.CONFIRM_UPDATE_SUCCESS,
                    "Changes committed to database");
            assertInfoMessage(CardUpdateController.INFORM_FAILURE,
                    "Changes unsuccessful. Please try again");
        }

        @Test
        @DisplayName("WS-NO-INFO-MESSAGE has TWO values, spaces and low-values - :158-159")
        void theNoInfoMessageConditionHasTwoValues() {
            assertThat(CardUpdateController.WS_INFO_MSG_SPACES)
                    .hasSize(CardUpdateController.WS_INFO_MSG_LENGTH)
                    .isEqualTo(" ".repeat(CardUpdateController.WS_INFO_MSG_LENGTH));
            assertThat(CardUpdateController.WS_INFO_MSG_LOW_VALUES)
                    .hasSize(CardUpdateController.WS_INFO_MSG_LENGTH)
                    .isEqualTo("\u0000".repeat(CardUpdateController.WS_INFO_MSG_LENGTH));
            assertThat(CardUpdateController.WS_INFO_MSG_LOW_VALUES)
                    .as("x'00' and x'40' are different bytes; :158-159 accepts both and this asserts "
                            + "they were not collapsed into one constant")
                    .isNotEqualTo(CardUpdateController.WS_INFO_MSG_SPACES);
        }

        @Test
        @DisplayName("WS-RETURN-MSG-OFF is SPACES, both states driven - :174")
        void theReturnMessageOffConditionIsSpaces() {
            assertThat(CardUpdateController.WS_RETURN_MSG_OFF)
                    .hasSize(CardUpdateController.WS_RETURN_MSG_LENGTH)
                    .isEqualTo(" ".repeat(CardUpdateController.WS_RETURN_MSG_LENGTH));

            Conversation task = task(reentered(), ChangeAction.showDetails());
            assertThat(task.returnMessageOff())
                    .as(":384 SETs WS-RETURN-MSG-OFF during initialisation, so the true state is the "
                            + "state a task starts in")
                    .isTrue();

            task.wsReturnMsg = CardUpdateController.WS_PROMPT_FOR_ACCT;
            assertThat(task.returnMessageOff())
                    .as("and the false state is any message at all")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The payload contract - 17 DFHMDF fields and nothing else (G9)")
    class PayloadContract {
        @ParameterizedTest(name = "[{index}] {0} is X({1}) at COCRDUP.CPY:{2}")
        @CsvSource({
            "TRNNAME,4,24",
            "TITLE01,40,30",
            "CURDATE,8,36",
            "PGMNAME,8,42",
            "TITLE02,40,48",
            "CURTIME,8,54",
            "ACCTSID,11,60",
            "CARDSID,16,66",
            "CRDNAME,50,72",
            "CRDSTCD,1,78",
            "EXPMON,2,84",
            "EXPYEAR,4,90",
            "EXPDAY,2,96",
            "INFOMSG,40,102",
            "ERRMSG,80,108",
            "FKEYS,21,114",
            "FKEYSC,18,120"})
        @DisplayName("each field carries the width its xxxI item declares - COCRDUP.CPY:24-120")
        void eachFieldCarriesItsDeclaredWidth(String name, int width, int copybookLine) {
            assertThat(CardUpdateRequest.declaredLength(name))
                    .as("the request side reads %s from COCRDUP.CPY:%d", name, copybookLine)
                    .isEqualTo(width);

            ScreenField field = CardUpdateResponse.fieldOf(name);
            assertThat(field.length())
                    .as("and the response side must agree, or a round trip changes the width")
                    .isEqualTo(width);
            assertThat(field.name()).isEqualTo(name);
        }

        @Test
        @DisplayName("seventeen fields in copybook order, EXPDAY among them - COCRDUP.CPY:24-120")
        void seventeenFieldsInCopybookOrder() {
            assertThat(CardUpdateResponse.namedFieldPrefixes())
                    .containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
                            "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR", "EXPDAY",
                            "INFOMSG", "ERRMSG", "FKEYS", "FKEYSC");
            assertThat(CardUpdateResponse.namedFields()).hasSize(17);
            assertThat(CardUpdateResponse.namedFieldPrefixes())
                    .as("EXPDAY is unique to COCRDUP: COCRDSL declares no expiry-day field")
                    .contains(CardUpdateResponse.EXPDAY);
        }

        @Test
        @DisplayName("FKEYSC at :214 is FKEYS's colour byte; FKEYSCC at :220 is FKEYSC's - :214, :220")
        void theFkeyscCollisionDoesNotCollapse() {
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            assertThat(fkeys.length())
                    .as("FKEYSO PIC X(21) at app/cpy-bms/COCRDUP.CPY:218")
                    .isEqualTo(21);
            assertThat(fkeysc.length())
                    .as("FKEYSCO PIC X(18) at app/cpy-bms/COCRDUP.CPY:224 - a field of its own, not a "
                            + "suffixed view of FKEYS")
                    .isEqualTo(18);
            assertThat(fkeysc.fieldOffset())
                    .as("and it occupies its own span, after FKEYS rather than inside it")
                    .isGreaterThan(fkeys.fieldOffset());

            CardUpdateResponse response = new CardUpdateResponse();
            assertThat(response.getFkeyso()).hasSize(21);
            assertThat(response.getFkeysco()).hasSize(18);
            assertThat(response.attributesOf(CardUpdateResponse.FKEYS))
                    .as("FKEYSC at :214 is this quad's colour byte, reached by the name FKEYS")
                    .isNotSameAs(response.attributesOf(CardUpdateResponse.FKEYSC));
        }

        @Test
        @DisplayName("xxxL/F/A and xxxC/P/H/V are metadata, absent from JSON - COCRDUP.CPY:19-124")
        void metadataItemsAreNotJsonMembers() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = new CardUpdateResponse();
            CardUpdateRequest requestBody = new CardUpdateRequest();

            JsonNode responseJson = mapper.readTree(mapper.writeValueAsString(response));
            JsonNode requestJson = mapper.readTree(mapper.writeValueAsString(requestBody));

            List<String> fields = CardUpdateResponse.namedFieldPrefixes();
            List<String> leaked = new ArrayList<>();
            List<String> skippedAsRealFields = new ArrayList<>();
            int checked = 0;
            for (String field : fields) {
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    String generated = field + suffix;
                    if (fields.contains(generated)) {
                        skippedAsRealFields.add(generated);
                        continue;
                    }
                    checked++;
                    String member = generated.toLowerCase(Locale.ROOT);
                    if (responseJson.has(member)) {
                        leaked.add("response." + member);
                    }
                    if (requestJson.has(member)) {
                        leaked.add("request." + member);
                    }
                }
            }

            assertThat(leaked)
                    .as("17 fields x 7 metadata items = 119 names, of which %d are checked; the xxxI "
                            + "and xxxO items are the only payload", checked)
                    .isEmpty();
            assertThat(skippedAsRealFields)
                    .as("FKEYS + 'C' spells the SEVENTEENTH FIELD, not FKEYS's colour byte - "
                            + "app/cpy-bms/COCRDUP.CPY:214 against :220. It is the only such name, and "
                            + "skipping it by spelling is why the colour byte is reached by field name")
                    .containsExactly(CardUpdateResponse.FKEYSC);
            assertThat(checked).isEqualTo(fields.size() * 7 - 1);
            assertThat(responseJson.has("fkeysc"))
                    .as("while the payload item of the FIELD FKEYSC is present, at its own name")
                    .isTrue();
            assertThat(requestJson.has("fkeysc"))
                    .as("and on the request side it is the xxxI item, also at its own name")
                    .isTrue();
        }

        @Test
        @DisplayName("TITLE01 and TITLE02 are the COTTL01Y constants at X(40) - :1057-1058")
        void theTwoTitlesAreTheSharedConstants() {
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(CardUpdateResponse.fieldOf(CardUpdateResponse.TITLE01).length())
                    .isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(CardUpdateResponse.fieldOf(CardUpdateResponse.TITLE02).length())
                    .isEqualTo(ScreenTitles.TITLE_LENGTH);
        }

        @Test
        @DisplayName("EXPYEAR/EXPMON/EXPDAY are (1:4)/(6:2)/(9:2), the dashes excluded - :1361-1366")
        void theExpiryProjectionExcludesTheSeparators() {
            String stored = "2026-04-30";
            assertThat(stored).hasSize(CardUpdateRequest.CardUpdateRecord
                    .CARD_UPDATE_EXPIRAION_DATE_LENGTH);

            assertThat(stored.substring(0, 4)).isEqualTo("2026");
            assertThat(stored.substring(5, 7)).isEqualTo("04");
            assertThat(stored.substring(8, 10)).isEqualTo("30");
            assertThat(stored.charAt(4)).as("1-based position 5 is a separator, not data").isEqualTo('-');
            assertThat(stored.charAt(7)).as("1-based position 8 likewise").isEqualTo('-');

            assertThat(CardUpdateResponse.fieldOf(CardUpdateResponse.EXPYEAR).length()).isEqualTo(4);
            assertThat(CardUpdateResponse.fieldOf(CardUpdateResponse.EXPMON).length()).isEqualTo(2);
            assertThat(CardUpdateResponse.fieldOf(CardUpdateResponse.EXPDAY).length()).isEqualTo(2);
            assertThat(CardUpdateRequest.CardDetails.EXPIRAION_DATE_LENGTH)
                    .as("4 + 2 + 2: the commarea's own copy of the date is separator-FREE at 8 bytes, "
                            + "while the record's is 10 with dashes - :297-300 against CVACT02Y")
                    .isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("Statelessness, the commarea and navigation (G37, G40)")
    class Statelessness {
        @Test
        @DisplayName("no @SessionAttributes on the class and no HttpSession in any signature - G37")
        void thereIsNoServerSideSessionState() {
            List<String> sessionAnnotations = new ArrayList<>();
            for (java.lang.annotation.Annotation annotation
                    : CardUpdateController.class.getAnnotations()) {
                String name = annotation.annotationType().getSimpleName();
                if (name.contains("Session")) {
                    sessionAnnotations.add(name);
                }
            }
            assertThat(sessionAnnotations)
                    .as("CICS is pseudo-conversational; a session attribute would make the Java "
                            + "stateful where the COBOL is not")
                    .isEmpty();

            List<String> sessionParameters = new ArrayList<>();
            for (Method method : CardUpdateController.class.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    String type = parameter.getType().getName();
                    if (type.contains("HttpSession") || type.contains("SessionStatus")) {
                        sessionParameters.add(method.getName() + "(" + type + ")");
                    }
                }
            }
            assertThat(sessionParameters).isEmpty();
        }

        @Test
        @DisplayName("1 + 89 + 89 + 150 = 329, and 160 beside it - :274-321")
        void theCommareaArithmetic() {
            assertThat(ChangeAction.RECORD_LENGTH).isEqualTo(1);
            assertThat(CardDetails.RECORD_LENGTH)
                    .as("11 + 16 + 3 + 50 + 8 + 1, the 8 being the separator-free EXPIRAION date")
                    .isEqualTo(89);
            assertThat(CardUpdateRequest.CardUpdateRecord.RECORD_LENGTH)
                    .as("16 + 11 + 3 + 50 + 10 + 1 + FILLER X(59) at :321")
                    .isEqualTo(150);
            assertThat(CardUpdateRequest.CardUpdateRecord.FILLER_LENGTH).isEqualTo(59);

            assertThat(CommArea.RECORD_LENGTH)
                    .as("1 + 89 + 89 + 150")
                    .isEqualTo(ChangeAction.RECORD_LENGTH + CardDetails.RECORD_LENGTH
                            + CardDetails.RECORD_LENGTH
                            + CardUpdateRequest.CardUpdateRecord.RECORD_LENGTH)
                    .isEqualTo(329);
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("CARDDEMO-COMMAREA travels beside it, at its own declared width")
                    .isEqualTo(160);
        }

        @Test
        @DisplayName("both the 329-byte trailer and the 160-byte commarea are serialised - G37")
        void bothAreasTravelInThePayload() throws Exception {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            ObjectMapper mapper = new ObjectMapper();

            ResponseEntity<ScreenResponse<CardUpdateResponse>> answer = controller.updateCardDetail(
                    CARD_NUMBER,
                    typedRequest(reentered(), fetchedTrailer(ChangeAction.showDetails())),
                    null, null, null);

            CardUpdateResponse screen = screenOf(answer);
            JsonNode body = mapper.readTree(mapper.writeValueAsString(screen));
            assertThat(body.has("stateToken"))
                    .as("WS-THIS-PROGCOMMAREA, all 329 bytes of it, sealed")
                    .isTrue();
            assertThat(body.has("navigationContext"))
                    .as("CARDDEMO-COMMAREA, all 160, structured - it is not privileged state")
                    .isTrue();
            assertThat(body.has("commArea"))
                    .as("the program's own area is not a structured member: its first byte is "
                            + "CCUP-CHANGE-ACTION, which records that the four edits already passed")
                    .isFalse();
            // All 329 bytes really are in the token, including the one byte the whole state machine turns
            // on: this is the seal, not a redaction.
            assertThat(unsealedAreaOf(screen))
                    .as("including the one byte the whole state machine turns on")
                    .isEqualTo(screen.getCommArea());
        }

        @Test
        @DisplayName("the same request twice gives the same response - G37, B7")
        void thesameRequestTwiceGivesTheSameResponse() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());

            CardUpdateResponse first = screenOf(controller.updateCardDetail(CARD_NUMBER,
                    typedRequest(reentered(), fetchedTrailer(ChangeAction.showDetails())),
                    null, null, null));
            CardUpdateResponse second = screenOf(controller.updateCardDetail(CARD_NUMBER,
                    typedRequest(reentered(), fetchedTrailer(ChangeAction.showDetails())),
                    null, null, null));

            // The state is the same state: both tokens unseal to one 329-byte area, which is the property
            // statelessness actually asserts.
            assertThat(unsealedAreaOf(second)).isEqualTo(unsealedAreaOf(first));
            assertThat(second.getCommArea()).isEqualTo(first.getCommArea());
            // The two tokens are nonetheless different, and have to be: AES-GCM takes a fresh
            // initialisation vector per seal, so a byte-identical token across two calls would mean the
            // vector had been reused - which is a real weakness, not a reassuring determinism.
            assertThat(second.getStateToken()).isNotEqualTo(first.getStateToken());

            // Everything else is identical, so nothing accumulated and the second call could not observe
            // the first.
            second.setStateToken("");
            first.setStateToken("");
            assertThat(second)
                    .as("a second call must not be able to tell that a first one happened")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("a transition is impossible unless the client returns the commarea - G37")
        void noTransitionWithoutTheReturnedCommarea() {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());

            CardUpdateResponse carried = screenOf(controller.updateCardDetail(CARD_NUMBER,
                    typedRequest(reentered(), fetchedTrailer(ChangeAction.showDetails())),
                    null, null, null));

            assertThat(carried.getCommArea().changeAction().isChangesOkNotConfirmed())
                    .as(":971-976 advances SHOW-DETAILS to CHANGES-OK-NOT-CONFIRMED when the input is "
                            + "clean, and the new state goes back to the client")
                    .isTrue();

            CardUpdateResponse withheld = screenOf(controller.updateCardDetail(CARD_NUMBER,
                    typedRequest(reentered(), CommArea.initialised()),
                    null, null, null));

            assertThat(withheld.getCommArea().changeAction().isChangesOkNotConfirmed())
                    .as("withhold the trailer and the advance is simply not available: there is no "
                            + "session, no cache and no static field for it to have been kept in")
                    .isFalse();
        }

        @Test
        @DisplayName("XCTL is a nextProgram field: 200, no redirect, no Location header - :473-474")
        void theTransferIsAFieldAndNotARedirect() {
            ResponseEntity<ScreenResponse<CardUpdateResponse>> answer = controller.updateCardDetail(
                    CARD_NUMBER,
                    request(ACCOUNT_NUMBER, CARD_NUMBER,
                            reentered().withFromProgram("").withFromTranid(""),
                            CommArea.initialised()),
                    null, null, CicsAid.DFHPF3 & 0xFF);

            assertThat(answer.getStatusCode())
                    .as("the transfer target is data, so the status is a plain 200")
                    .isEqualTo(HttpStatus.OK);
            assertThat(answer.getStatusCode().is3xxRedirection()).isFalse();
            assertThat(answer.getHeaders().getLocation())
                    .as("no Location header: an XCTL is not a redirect and the client is not being told "
                            + "where to go, it is being told where control went")
                    .isNull();
            assertThat(screenOf(answer).getNextProgram().trim())
                    .as(":449-454 defaults CDEMO-TO-PROGRAM to LIT-MENUPGM when there is no caller, and "
                            + ":474 transfers to it")
                    .isEqualTo(CardUpdateController.LIT_MENUPGM);
        }

        @Test
        @DisplayName("PF13-PF24 fold onto PFK01-PFK12, by identity - CSSTRPFY via :1528")
        void theResolverFoldsTheSecondTwelveKeys() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13))
                    .containsSame(PfKeyResolver.resolve(CicsAid.DFHPF1).orElseThrow());
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .containsSame(AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF17))
                    .containsSame(AidKey.PFK05);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24))
                    .as("the twelfth of the folded arms lands on the twelfth token")
                    .containsSame(AidKey.PFK12);
        }

        @Test
        @DisplayName("the resolver has no WHEN OTHER and no DFHPA3 branch - CSSTRPFY")
        void theResolverHasNoDefaultArmAndNoPa3() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                    .as("CSSTRPFY tests DFHPA1 and DFHPA2 and stops; PA3 matches no arm")
                    .isEmpty();
            assertThat(PfKeyResolver.resolve((byte) 0x01))
                    .as("no WHEN OTHER means an unrecognised AID sets nothing - it does NOT become "
                            + "ENTER, which would silently turn a stray byte into a submit")
                    .isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).containsSame(AidKey.ENTER);
        }

        @Test
        @DisplayName("PF3, PF5 and PF12 are the keys this program acts on - :435, :958, :989")
        void theThreeKeysThisProgramActsOn() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3)).containsSame(AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF5)).containsSame(AidKey.PFK05);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF12)).containsSame(AidKey.PFK12);

            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF5)).isTrue();
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF12)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF5))
                    .as("and they are distinct: PF5 saves, PF3 leaves")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The code-page judgement is the JSON boundary's, not this program's")
    class ScreenInputRefusal {
        @Test
        @DisplayName("A character the code page cannot represent is NOT refused here: COCRDUPC has no "
                + "such test, and a sweep placed in the flow ran ahead of the outer EVALUATE at "
                + ":429-543, five of whose arms never receive a map at all")
        void anUnrepresentableCharacterIsNotRefusedByTheProgram() {
            CardUpdateRequest received =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), CommArea.initialised());
            received.setCrdname("JOS\u00C9 MU\u00D1OZ");

            assertThat(controller.handle(received, 0, CicsAid.DFHENTER)).isNotNull();
        }

        @Test
        @DisplayName("A representable payload behaves identically, so nothing about an accepted value "
                + "changed with the sweep's removal")
        void aRepresentablePayloadIsUntouched() {
            CardUpdateRequest received =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), CommArea.initialised());
            CardUpdateRequest reference =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), CommArea.initialised());
            received.setCrdname("JOHN Q PUBLIC");

            assertThat(controller.handle(received, 0, CicsAid.DFHENTER)).isNotNull();

            assertThat(received.getCrdname())
                    .as("the embossed name the sweep examined must come back exactly as it was set")
                    .isEqualTo("JOHN Q PUBLIC");
            assertThat(received.getCardsid())
                    .as("and no neighbouring field moved either")
                    .isEqualTo(reference.getCardsid());
            assertThat(received.getAcctsid()).isEqualTo(reference.getAcctsid());
            assertThat(received.getCrdstcd()).isEqualTo(reference.getCrdstcd());
            assertThat(received.getExpmon()).isEqualTo(reference.getExpmon());
            assertThat(received.getExpyear()).isEqualTo(reference.getExpyear());
        }
    }

    @Nested
    @DisplayName(":671-672 is unconditional, and a blank snapshot is answered by ABEND-ROUTINE")
    class CommareaConsistency {
        @Test
        @DisplayName("A blank CCUP-OLD-ACCTID reaches the MOVE and raises a data exception there, "
                + "because :671-672 has no guard in front of it")
        void aBlankFetchedAccountKeyReachesTheMove() {
            Conversation task = taskWithValidKeys(ChangeAction.showDetails());
            task.setOldDetails(CardDetails.initialised(DetailGroup.OLD));

            assertThatThrownBy(() -> controller.editMapInputs1200(task))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(ScreenInputRejectedException.class)
                    .isNotInstanceOf(AbendException.class);
        }

        @Test
        @DisplayName("Through handle(), that data exception is answered by the HANDLE ABEND declarative "
                + "at :370-372 - an abend, which is what the source does with it")
        void throughHandleItIsTheAbendTheDeclarativeProduces() {
            CardUpdateRequest received = typedRequest(reentered(),
                    CommArea.initialised().withChangeAction(ChangeAction.showDetails()));

            assertThatThrownBy(() -> controller.handle(received, CardUpdateController.WS_COMMAREA_LENGTH,
                    CicsAid.DFHENTER))
                    .isInstanceOf(AbendException.class);
        }

        @Test
        @DisplayName("The same path with the digits a real fetch left behind proceeds normally, so "
                + "nothing about a genuine conversation changed")
        void theSamePathWithARealSnapshotProceeds() {
            Conversation task = taskWithValidKeys(ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            task.setNewDetails(newDetails());

            controller.editMapInputs1200(task);

            assertThat(task.carddemoCommarea.acctId()).isEqualTo(Long.parseLong(ACCOUNT_NUMBER));
            assertThat(task.carddemoCommarea.cardNum()).isEqualTo(Long.parseLong(CARD_NUMBER));
        }
    }
}
