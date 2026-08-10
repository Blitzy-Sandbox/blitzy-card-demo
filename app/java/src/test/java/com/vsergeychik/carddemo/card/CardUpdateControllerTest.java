package com.vsergeychik.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.WebConfig;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link CardUpdateController} - the {@code COCRDUPC} / {@code CCUP} credit-card update screen.
 *
 * <p>Every test that asserts a <em>decision</em> instantiates the controller <strong>directly</strong>
 * with a mocked {@link CardRepository}, a mocked {@link CardUpdateService} and a fixed {@link Clock}.
 * There is no Spring context and no {@code MockMvc} in the decision path, which is gate
 * <strong>G51</strong>: an eight-arm {@code EVALUATE} is asserted where it lives, and a failure names the
 * paragraph rather than an HTTP status.
 *
 * <p>{@link HttpWiring} is the one exception, and only in mechanism. It asserts the transport contract -
 * that {@code PUT /api/cards/{cardNum}} routes, that the path variable and the optional query parameters
 * bind, that the status codes are right, and that the body carries the seventeen {@code xxxO} items. Not
 * one branch of the program is asserted through it.
 *
 * <p>Expectations are <strong>statically derived</strong> from {@code app/cbl/COCRDUPC.cbl},
 * {@code app/cpy-bms/COCRDUP.CPY}, {@code app/bms/COCRDUP.bms}, {@code app/cpy/CVCRD01Y.cpy} and
 * {@code app/cpy/CVACT02Y.cpy}. The legacy COBOL cannot be executed in this environment (risk
 * <strong>R-A</strong>), so no captured baseline exists and none is claimed - each assertion cites the
 * line it was read from, so it can be checked against the source by eye.
 *
 * <p>The three assertions this class exists for above all others, because each one guards a defect that
 * would otherwise be silent:
 * <ul>
 *   <li>{@code detailsNotFetchedExecutesThePfk12Body()} - {@code WHEN CCUP-DETAILS-NOT-FETCHED} at
 *       {@code :954} has no body and shares {@code WHEN CCARD-AID-PFK12}'s ({@code :958-966}). Read as a
 *       no-op, the very first {@code ENTER} on the screen would fetch nothing.</li>
 *   <li>{@code confirmKeyIsRequiredToWrite()} and {@code withoutTheConfirmKeyNothingIsWritten()} -
 *       {@code CCUP-CHANGES-OK-NOT-CONFIRMED} is tested twice, at {@code :988} with
 *       {@code AND CCARD-AID-PFK05} and at {@code :1006} bare. Only source order makes the bare arm mean
 *       "confirmation not yet given".</li>
 *   <li>{@code lockFailureAfterAnEarlierMessageReportsSuccess()} - the latent COBOL defect at
 *       {@code :1445-1447}, pinned so that a future "tidy-up" that switches the inner {@code EVALUATE}
 *       onto {@link WriteOutcome} fails here rather than in production.</li>
 * </ul>
 */
@DisplayName("CardUpdateController - COCRDUPC / CCUP")
class CardUpdateControllerTest {

    /** {@code IBM037} is the dataset code page; the fixtures are ASCII-safe either way. */
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    /** A codec for the fixtures, so a test never depends on the platform default. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(CHARSET);

    /**
     * {@code 2022-07-19T14:35:07Z}, the date the analysed source carries in its version footer.
     *
     * <p>Fixed because {@code 3100-SCREEN-INIT} reads {@code FUNCTION CURRENT-DATE} twice
     * ({@code :1055}, {@code :1062}) and a screen carrying a live clock could not be compared
     * byte-for-byte against anything.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T14:35:07Z"), ZoneOffset.UTC);

    /** A sixteen-digit card number, the width {@code CVACT02Y} declares for {@code CARD-NUM}. */
    private static final String CARD_NUMBER = "4000000000000001";

    /** An eleven-digit account number, the width {@code CVACT01Y} declares for {@code ACCT-ID}. */
    private static final String ACCOUNT_NUMBER = "00000000011";

    private CardRepository repository;
    private CardUpdateService service;
    private CardUpdateController controller;

    @BeforeEach
    void setUp() {
        repository = mock(CardRepository.class);
        service = mock(CardUpdateService.class);
        controller = new CardUpdateController(repository, service, FIXED_CLOCK, CHARSET);
    }

    // =================================================================================================
    // Fixtures
    // =================================================================================================

    /** A card record whose expiry is the {@code YYYY-MM-DD} form {@code CVACT02Y} declares. */
    private static CardRecord card() {
        return CardRecord.moving(CARD_NUMBER, 11L, 123, "JOHN Q PUBLIC", "2026-04-30", "Y", CODEC);
    }

    /**
     * A read that landed on {@code DFHRESP(NORMAL)} with that record.
     *
     * <p>The stored image is the record's own 150-byte serialisation, because a real
     * {@code EXEC CICS READ ... INTO(CARD-RECORD)} fills the area from the dataset bytes and the harness
     * compares those bytes.
     */
    private static CardReadResult normalRead() {
        CardRecord record = card();
        return CardReadResult.normal(record, record.encodeToImage(CHARSET));
    }

    /** A request with the map area initialised and the two keys typed. */
    private static CardUpdateRequest request(String acctsid, String cardsid,
            NavigationContext commarea, CommArea trailer) {
        CardUpdateRequest request = new CardUpdateRequest();
        request.setAcctsid(acctsid);
        request.setCardsid(cardsid);
        request.setNavigationContext(commarea);
        request.setCommArea(trailer);
        return request;
    }

    /** A commarea in the {@code CDEMO-PGM-REENTER} state with both keys carried. */
    private static NavigationContext reentered() {
        return NavigationContext.empty()
                .withFromProgram(CardUpdateController.LIT_THISPGM)
                .withFromTranid(CardUpdateController.LIT_THISTRANID)
                .withPgmReenter()
                .withAcctId(11L)
                .withCardNum(Long.parseLong(CARD_NUMBER));
    }

    /**
     * A task whose storage is initialised as {@code :374-384} leaves it, then moved into the state the
     * test needs.
     *
     * <p>{@code initializeStorage} is the real paragraph, so the flags a test does not set hold exactly
     * what {@code INITIALIZE} put there - which matters, because {@code INITIALIZE} writes a
     * <em>space</em> into the seven one-character flags and a space satisfies the {@code *-BLANK}
     * condition names but none of {@code INPUT-OK}, {@code INPUT-ERROR} or {@code INPUT-PENDING}.
     */
    private Conversation task(NavigationContext commarea, ChangeAction action) {
        Conversation task = new Conversation();
        controller.initializeStorage(request("", "", commarea, CommArea.initialised()), task);
        task.carddemoCommarea = commarea;
        task.setChangeAction(action);
        return task;
    }

    /** A task carrying both key flags valid, as {@code 1210} and {@code 1220} leave them on success. */
    private Conversation taskWithValidKeys(ChangeAction action) {
        Conversation task = task(reentered(), action);
        task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_ISVALID;
        task.wsEditCardFlag = CardUpdateController.FLG_FILTER_ISVALID;
        task.ccWorkArea.setCcAcctId(ACCOUNT_NUMBER);
        task.ccWorkArea.setCcCardNum(CARD_NUMBER);
        return task;
    }

    /** The {@code CCUP-OLD-DETAILS} group as {@code 9000} leaves it after a successful read. */
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

    /** The {@code CCUP-NEW-DETAILS} group carrying one typed change: the status flipped to {@code N}. */
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

    /**
     * A {@link WriteResult} on the given arm, carrying that arm's own message.
     *
     * <p>{@link WriteOutcome#returnMessageLiteral()} is an {@link Optional} because the successful arm
     * sets no message at all - {@code 9200} leaves {@code WS-RETURN-MSG} exactly as it found it when the
     * rewrite succeeds ({@code app/cbl/COCRDUPC.cbl:1477-1486}). An absent literal therefore becomes the
     * empty message, not a placeholder.
     */
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

    /** The screen inside the envelope the mapping returns. */
    private static CardUpdateResponse screenOf(
            ResponseEntity<ScreenResponse<CardUpdateResponse>> answer) {
        ScreenResponse<CardUpdateResponse> envelope = answer.getBody();
        assertThat(envelope).isNotNull();
        assertThat(envelope.screen()).isNotNull();
        return envelope.screen();
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

        /**
         * {@code LIT-THISMAPSET PIC X(8) VALUE 'COCRDUP '} - {@code :225}.
         *
         * <p>Eight characters where every other mapset literal in the estate is seven, and the eighth is
         * the space that {@code 3400}'s move to {@code CCARD-NEXT-MAPSET PIC X(7)} discards. Pinned at
         * its declared width, because narrowing it here would hide the truncation that
         * {@code sendScreen3400DropsTheEighthByte()} asserts.
         */
        @Test
        @DisplayName("LIT-THISMAPSET is EIGHT characters, not seven - :225")
        void thisMapsetIsEightCharacters() {
            assertThat(CardUpdateController.LIT_THISMAPSET).isEqualTo("COCRDUP ").hasSize(8);
        }

        /**
         * {@code LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'} - {@code :233-234}.
         *
         * <p>The card list's map is really {@code CCRDLIA} ({@code app/cbl/COCRDLIC.cbl:185}), so this
         * literal names the card <em>detail</em> map instead. {@code COCRDSLC:178} carries the identical
         * defect. It is preserved verbatim under practice <strong>B5</strong>, and this test is what
         * stops a future reader from "fixing" it: a corrected literal fails here, which is where the
         * reason is written down.
         */
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

        /**
         * {@code WS-FILE-ERROR-MESSAGE} - {@code :133-152}: 12+8+4+9+15+10+7+10+5 = 80.
         *
         * <p>Asserted as a sum rather than as the number {@code 80}, so a mistranscribed filler is
         * caught by the arithmetic rather than by a message comparison that could not say why.
         */
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

        /**
         * {@code LIT-UPPER} and {@code LIT-LOWER} - {@code :259-262}: twenty-six characters each, and
         * the pairs the {@code INSPECT ... CONVERTING} at {@code :1356-1358} folds.
         *
         * <p>Twenty-six, not the whole of Unicode, which is why {@link String#toUpperCase()} would be
         * wrong here as well as locale-sensitive.
         */
        @Test
        @DisplayName("the case-folding tables are 26 characters each - :259-262")
        void caseFoldingTables() {
            assertThat(CardUpdateController.LIT_UPPER).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                    .hasSize(26);
            assertThat(CardUpdateController.LIT_LOWER).isEqualTo("abcdefghijklmnopqrstuvwxyz")
                    .hasSize(26);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("2000-DECIDE-ACTION - the eight arms of :949-1027, in source order")
    class DecideAction {

        /**
         * The assertion this whole class exists for.
         *
         * <p>{@code WHEN CCUP-DETAILS-NOT-FETCHED} at {@code :954} has <strong>no body</strong>; the
         * body at {@code :959-966} belongs to {@code WHEN CCARD-AID-PFK12} at {@code :958} and the two
         * consecutive {@code WHEN}s share it. That is COBOL's multi-{@code WHEN} OR-grouping and not
         * implicit fall-through, which COBOL does not have.
         *
         * <p>Read as a no-op - the reading a Java author naturally reaches for, since the arm looks
         * empty - the first {@code ENTER} on a freshly prompted screen would read nothing, show nothing,
         * and leave the operator's typed keys on a screen that never fetched them.
         */
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

        /**
         * {@code CCUP-DETAILS-NOT-FETCHED VALUE LOW-VALUES, SPACES} - {@code :277-278}.
         *
         * <p>Two byte patterns, {@code x'00'} and {@code x'40'}, and both satisfy the condition. Neither
         * is a Java {@code null}: the field always holds one character.
         */
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

        /** {@code :959-960} - the read is guarded by <em>both</em> key flags, not either. */
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

        /** {@code :963-965} - and the state only advances if the read actually found a card. */
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

        /** {@code :971-977} - details on screen, nothing wrong, so the changes are ready to confirm. */
        @Test
        @DisplayName("SHOW-DETAILS with clean input advances to CHANGES-OK-NOT-CONFIRMED - :971-977")
        void showDetailsAdvancesWhenInputIsClean() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.wsInputFlag = CardUpdateController.INPUT_OK;

            controller.decideAction2000(task);

            assertThat(task.changeAction().isChangesOkNotConfirmed()).isTrue();
        }

        /** {@code :972-974} - either an edit failure or no change at all holds the state where it is. */
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

        /**
         * {@code :982-983} - {@code WHEN CCUP-CHANGES-NOT-OK} is a {@code CONTINUE}.
         *
         * <p>An empty arm, and a load-bearing one: without it control would reach {@code WHEN OTHER} and
         * abend a screen whose only problem is that the operator mistyped a field.
         */
        @Test
        @DisplayName("CHANGES-NOT-OK is a CONTINUE, not an abend - :982-983")
        void changesNotOkContinues() {
            Conversation task = task(reentered(), ChangeAction.changesNotOk());

            controller.decideAction2000(task);

            assertThat(task.changeAction().isChangesNotOk()).isTrue();
            verifyNoInteractions(repository, service);
        }

        /**
         * {@code :988-991} - the confirm key, and the only path in the program that writes.
         */
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

        /**
         * {@code :1006-1007} - the <strong>second</strong> test of the same condition, bare.
         *
         * <p>Reachable only because {@code :988} was tested first. Inverting the two arms would make
         * {@code PF5} stop saving, and nothing else in the program would notice.
         */
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

        /** {@code :1011-1012} - back to showing details once the update has been applied. */
        @Test
        @DisplayName("OKAYED-AND-DONE returns to SHOW-DETAILS - :1011-1012")
        void okayedAndDoneReturnsToShowDetails() {
            Conversation task = task(reentered().withFromTranid("CCLI"),
                    ChangeAction.changesOkayedAndDone());

            controller.decideAction2000(task);

            assertThat(task.changeAction().isShowDetails()).isTrue();
        }

        /**
         * {@code :1013-1018} - and when there is no calling transaction to return to, the carried
         * identifiers are cleared.
         *
         * <p><strong>{@code ZEROES} for the two identifiers, {@code LOW-VALUES} for the status</strong> -
         * two different fill bytes in three adjacent statements. Both are asserted, because a single
         * "clear it" helper would have got one of them wrong.
         */
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

        /** {@code :1013} - a real calling transaction is left alone, keys and all. */
        @Test
        @DisplayName("with a caller the carried keys survive - :1013")
        void okayedAndDoneKeepsTheKeysWhenThereIsACaller() {
            Conversation task = task(reentered().withFromTranid("CCLI").withAcctId(11L)
                    .withCardNum(Long.parseLong(CARD_NUMBER)), ChangeAction.changesOkayedAndDone());

            controller.decideAction2000(task);

            assertThat(task.carddemoCommarea.acctId()).isEqualTo(11L);
            assertThat(task.carddemoCommarea.cardNum()).isEqualTo(Long.parseLong(CARD_NUMBER));
        }

        /**
         * {@code :1019-1026} - {@code WHEN OTHER}, and the text goes to {@code ABEND-MSG}.
         *
         * <p>The sibling {@code COCRDSLC:377-378} moves the identical text into {@code WS-RETURN-MSG}
         * instead, where it would have reached the map's error line. This program sends it to the
         * terminal through {@code EXEC CICS SEND FROM(ABEND-DATA)} and paints no map at all. The two
         * programs genuinely differ; this asserts which one is reproduced.
         */
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

        /** {@code :1537} - and {@code ABEND-CULPRIT} always names this program. */
        @Test
        @DisplayName("ABEND-CULPRIT is set unconditionally by the routine - :1537")
        void abendCulpritNamesThisProgram() {
            Conversation task = task(reentered(), ChangeAction.of("Q"));

            assertThatThrownBy(() -> controller.decideAction2000(task))
                    .isInstanceOf(AbendException.class);

            assertThat(task.abendData.abendCulprit().trim()).isEqualTo("COCRDUPC");
        }

        /**
         * {@code :1533-1535} - the routine's own default, and the surprise in it.
         *
         * <p>The guard is {@code IF ABEND-MSG EQUAL LOW-VALUES}, but {@code ABEND-DATA} comes from
         * {@code COPY CSMSG02Y} at {@code :343} and that copybook declares all four items
         * <strong>{@code VALUE SPACES}</strong> - and {@code ABEND-DATA} is <strong>not</strong> among the
         * three areas {@code INITIALIZE} covers at {@code :374-376}. So on every real arrival
         * {@code ABEND-MSG} holds 72 spaces, the guard is false, and the default text is never applied.
         * The only two statements in the program that write the field are {@code :1024}, which moves
         * {@code 'UNEXPECTED DATA SCENARIO'}, and {@code :1534} itself.
         *
         * <p>{@code :1534} is therefore <strong>effectively dead</strong> in {@code COCRDUPC}: nothing
         * moves {@code LOW-VALUES} into {@code ABEND-MSG}. It is reproduced anyway, because a paragraph
         * that is dead by arithmetic today is live the moment a caller supplies the commarea - which this
         * migration allows and CICS did not. Both sides of the guard are asserted: this test proves the
         * false side, {@code abendRoutineAppliesItsDefaultWhenTheFieldIsLowValues()} the true side.
         */
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

        /** {@code :1533-1535} - and the true side of the same guard, driven explicitly. */
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

        /**
         * {@code :1019-1026} reaches {@code ABEND-ROUTINE} with <strong>no exception in flight</strong>,
         * because it abends on the program's own logic rather than on a failure.
         *
         * <p>Its sibling {@code COCRDSLC} has no such path - its {@code WHEN OTHER} at {@code :373-380}
         * repaints the screen - so a null cause is specific to this program, and
         * {@code BackendDiagnostic.of} rejects a null argument. This test is what found that.
         */
        @Test
        @DisplayName("ABEND-ROUTINE tolerates the no-exception arrival - :1025-1026")
        void abendRoutineTakesNoCause() {
            Conversation task = task(reentered(), ChangeAction.showDetails());

            AbendException abend = controller.abendRoutine(task, null, null);

            assertThat(abend).isNotNull();
            assertThat(abend.getCause()).isNull();
            assertThat(task.returned).isTrue();
        }

        /** And the data-access arrival, where a cause does exist and is carried. */
        @Test
        @DisplayName("ABEND-ROUTINE carries a triggering failure as the cause - :1546-1552")
        void abendRoutineCarriesTheCause() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            RuntimeException failure = new IllegalStateException("dataset unavailable");

            AbendException abend = controller.abendRoutine(task, new CardUpdateResponse(), failure);

            assertThat(abend.getCause()).isSameAs(failure);
        }

        /** {@code :1533} - but an arm that supplied its own text keeps it. */
        @Test
        @DisplayName("ABEND-ROUTINE keeps a message the caller supplied - :1533")
        void abendRoutineKeepsASuppliedMessage() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.abendData = task.abendData.withAbendMsg(CardUpdateController.UNEXPECTED_DATA_SCENARIO);

            controller.abendRoutine(task, null, null);

            assertThat(task.abendData.abendMsg().trim()).isEqualTo("UNEXPECTED DATA SCENARIO");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The CCUP-CHANGE-ACTION condition names - :276-291")
    class ChangeActionConditions {

        /** {@code 88 CCUP-CHANGES-MADE VALUE 'E' 'N' 'C' 'L' 'F'} - {@code :281-282}, five values. */
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

        /** {@code 88 CCUP-CHANGES-FAILED VALUE 'L' 'F'} - {@code :288-289}, two values. */
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

    // =================================================================================================

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

        /**
         * The four arms of {@code :992-1001} map onto the four states {@code 'L' 'F' 'S' 'C'}.
         *
         * <p>Asserted through the <em>message</em> the service returns, because that is what the COBOL
         * {@code EVALUATE} tests - three of the four arms are {@code 88}-levels over
         * {@code WS-RETURN-MSG}.
         */
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

        /** {@code :1444} - the lock arm, and only the lock arm, sets {@code INPUT-ERROR}. */
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

        /**
         * The latent COBOL defect at {@code :1445-1447}, pinned deliberately.
         *
         * <p>{@code 9200}'s lock-failure arm sets its message only {@code IF WS-RETURN-MSG-OFF}. So when
         * an earlier paragraph has already placed a different message, a genuine lock failure leaves that
         * message in the field, none of the first three arms of the inner {@code EVALUATE} matches, and
         * {@code WHEN OTHER} declares the update <strong>done</strong> - {@code CONFIRM-UPDATE-SUCCESS}
         * on the screen with nothing written.
         *
         * <p>This test asserts the defect. It exists so that a future change which "simplifies" the
         * inner {@code EVALUATE} into a {@code switch} on {@link WriteOutcome} - which would repair the
         * defect and therefore change behaviour - fails here, next to the explanation, rather than
         * silently in production. Practice <strong>B5</strong>: a defect is behaviour.
         */
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

        /** {@code :1512-1517} - a refused update returns a refreshed snapshot, which is adopted. */
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

    // =================================================================================================

    @Nested
    @DisplayName("3000-SEND-MAP and its five children - :1035-1340")
    class Screen {

        private CardUpdateResponse response;

        @BeforeEach
        void newScreen() {
            response = new CardUpdateResponse();
        }

        /**
         * Sets all six field flags to {@code FLG-*-ISVALID}.
         *
         * <p>Needed by every test that asserts a <em>painted value</em>, because
         * {@code INITIALIZE WS-MISC-STORAGE} at {@code :374-376} writes a <strong>space</strong> into each
         * one-character flag and {@code 88 FLG-*-BLANK VALUE ' '} at {@code :60}, {@code :64}, {@code :68},
         * {@code :72}, {@code :76} and {@code :80} is exactly a space. So a freshly initialised task has
         * every field <em>blank</em>, and {@code 3300:1249} onwards would overwrite each painted value
         * with {@code '*'} - correctly, and unhelpfully for an assertion about the value itself.
         */
        private void allFieldFlagsValid(Conversation task) {
            task.wsEditAcctFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardnameFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardstatusFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardexpmonFlag = CardUpdateController.FLG_FILTER_ISVALID;
            task.wsEditCardexpyearFlag = CardUpdateController.FLG_FILTER_ISVALID;
        }

        /** Paints the screen for a task in the given state, returning what the map holds. */
        private CardUpdateResponse paint(Conversation task) {
            CardUpdateRequest request = request("", "", task.carddemoCommarea, CommArea.initialised());
            controller.sendMap3000(request, response, task);
            return response;
        }

        /** {@code :1053-1075} - the group reset, the four constants and the two clock values. */
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

        /**
         * {@code :1084-1086} - {@code IF CDEMO-PGM-ENTER CONTINUE}: on first entry the paragraph paints
         * nothing at all, so the fields keep the {@code LOW-VALUES} that {@code :1053} put there.
         */
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

        /** {@code :1087-1097} - a zero numeric view paints {@code LOW-VALUES}, not eleven zeroes. */
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

        /** {@code :1090}, {@code :1096} - and a set one paints its digits. */
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

        /** {@code :1100-1106} - the first arm blanks the five detail items. */
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

        /** {@code :1107-1112} - the second arm paints the stored values. */
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

        /**
         * {@code :1113-1123} - the third arm paints four typed values and <strong>the stored day</strong>.
         *
         * <p>{@code MOVE CCUP-NEW-EXPDAY} at {@code :1122} is commented out and {@code :1123} moves
         * {@code CCUP-OLD-EXPDAY} instead, with the source's own note at {@code :1118-1121} explaining
         * that the day is not user-changeable. The fixture makes the two differ so the assertion can only
         * pass if the right one was chosen.
         */
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

        /** {@code :1124-1129} - {@code WHEN OTHER} paints the stored values. */
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

        /**
         * The six {@code WS-INFO-MSG} literals, pinned verbatim against {@code :160-173}.
         *
         * <p>Written out here once so the text itself is asserted against the copybook rather than only
         * against the constant that holds it; the per-state test below then compares constants, which is
         * what keeps a text change from needing eight edits.
         */
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

        /**
         * {@code :1140-1163} - the informational-message arms, in source order.
         *
         * <p>{@code 'L'} and {@code 'F'} are two separate arms at {@code :1153-1156} carrying the
         * <strong>same</strong> text, even though the grouping condition {@code CCUP-CHANGES-FAILED} that
         * covers both exists. Both are driven, so a future merge of the two arms still has to keep them
         * behaving alike.
         */
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

        /** {@code :1141-1142} - {@code CDEMO-PGM-ENTER} is tested first and wins over the state. */
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

        /** {@code :1143-1144} - and the not-fetched arm carries the same prompt. */
        @Test
        @DisplayName("3250 DETAILS-NOT-FETCHED prompts for the keys - :1143-1144")
        void infomsgNotFetchedPrompts() {
            Conversation task = task(reentered(), ChangeAction.initial());

            paint(task);

            assertThat(response.getInfomsgo().trim())
                    .isEqualTo(CardUpdateController.PROMPT_FOR_SEARCH_KEYS.trim());
        }

        /**
         * {@code :1157-1158} - {@code WHEN WS-NO-INFO-MESSAGE} is the floor, and there is
         * <strong>no {@code WHEN OTHER}</strong>: an unmatched state leaves the field as it arrived.
         */
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

        /** {@code :1163} - and {@code WS-RETURN-MSG} always reaches {@code ERRMSGO}. */
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

        /**
         * {@code :1171-1208} - which fields are typeable in each state.
         *
         * <p>The confirmation state protects <strong>everything</strong>, which is what makes the
         * confirmation a confirmation of what was validated rather than of what is on the glass.
         */
        @ParameterizedTest(name = "[{index}] state ''{0}'': keys={1} details={2}")
        @CsvSource({"LOW,FSE,PRF", "S,PRF,FSE", "E,PRF,FSE", "N,PRF,PRF", "C,PRF,PRF", "Q,FSE,PRF"})
        @DisplayName("3300 protect/unprotect per state - :1171-1208")
        void protectPerState(String action, String keys, String details) {
            // "LOW" stands for LOW-VALUES: @CsvSource cannot carry an x'00' byte, and an empty column
            // binds as null, which ChangeAction correctly refuses.
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

        /**
         * {@code :1178}, {@code :1185}, {@code :1197}, {@code :1205} - {@code EXPDAYA} is commented out on
         * all four arms, so the day's attribute item is never assigned.
         */
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

        /** {@code :1211-1235} - the eight cursor arms, first match wins. */
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
            // Every flag valid FIRST, then exactly the one the scenario names, so the arm under test is
            // the first that matches. Without this every flag is blank - INITIALIZE writes a space and
            // 88 FLG-*-BLANK VALUE ' ' is a space - and the ACCTSID arm at :1215 would always win.
            allFieldFlagsValid(task);
            applyCursorScenario(task, scenario);
            CardUpdateRequest request =
                    request("", "", task.carddemoCommarea, CommArea.initialised());

            // positionCursor3300 directly, not through sendMap3000: 3250 runs first in the real paragraph
            // order and rewrites WS-INFO-MSG from the state, which would erase the FOUND-CARDS-FOR-ACCOUNT
            // the first arm tests. That ordering is asserted separately by infomsgPerState.
            controller.positionCursor3300(request, task);

            assertThat(task.cursorField).isEqualTo(expectedField);
            assertThat(request.metadataFor(expectedField).lengthItem())
                    .as("MOVE -1 TO <field>L is the BMS cursor request")
                    .isEqualTo(CardUpdateRequest.FieldMetadata.CURSOR_LENGTH_ITEM);
        }

        /**
         * {@code :1212-1213} are tested <strong>before</strong> the six field flags, so a successful fetch
         * claims the cursor even when an untouched key flag would otherwise have taken it.
         */
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

        /** {@code :1238-1241} - keys that arrived from the card list go back to the default colour. */
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

        /**
         * {@code :1243-1245} - the account's {@code NOT-OK} arm has <strong>no {@code REENTER}
         * guard</strong>, unlike {@code CSSETATY}. Asserted in the {@code ENTER} context precisely because
         * that is where a guard would have suppressed it.
         */
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

        /**
         * {@code :1247-1251} - the {@code BLANK} arm <strong>is</strong> guarded by
         * {@code CDEMO-PGM-REENTER}, which is the one place this program's rule matches
         * {@code CSSETATY}'s. Both sides asserted (gate G38).
         */
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

        /**
         * {@code :1263-1307} - the four detail fields are guarded by {@code CCUP-CHANGES-NOT-OK}, not by
         * {@code CDEMO-PGM-REENTER}. Both sides asserted.
         */
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

        /** {@code :1268-1307} - and a blank detail field is marked, under the same guard. */
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

        /**
         * {@code :1285} - {@code MOVE DFHBMDAR TO EXPDAYC} is <strong>unconditional</strong>, so the
         * expiry day is always dark. It is fetched, carried, painted, and then hidden.
         */
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

        /** {@code :1309-1313} - the message line is dark when empty and bright when it carries text. */
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

        /**
         * {@code :1315-1317} - {@code FKEYSCA} is brightened only while confirmation is being requested.
         *
         * <p>{@code FKEYSC} is an {@code X(18)} <strong>field</strong> at
         * {@code app/cpy-bms/COCRDUP.CPY:115-120}, not the colour item of {@code FKEYS}. A mapper that
         * stripped the trailing {@code C} as a suffix would have deleted it.
         */
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

        /**
         * {@code :1326-1327} - {@code LIT-THISMAPSET PIC X(8)} into {@code CCARD-NEXT-MAPSET PIC X(7)}.
         *
         * <p>A {@code PIC X} move truncates on the right, so the eighth byte - the trailing space - is
         * discarded and the receiver holds {@code COCRDUP}, correct by accident of the padding.
         */
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

        /** The metadata projection: seventeen quads, the message colour, and the cursor request. */
        @Test
        @DisplayName("screenMetadataOf publishes all seventeen quads plus the cursor")
        void metadataCarriesEverySeventeenQuad() {
            Conversation task = task(reentered(), ChangeAction.showDetails());
            task.setOldDetails(oldDetails());
            paint(task);

            ScreenMetadata metadata = controller.screenMetadataOf(response, task.cursorField);

            assertThat(metadata.fields()).hasSize(17)
                    .containsKeys(CardUpdateResponse.FKEYS, CardUpdateResponse.FKEYSC);
            assertThat(metadata.resetAllOutputFields())
                    .as(":1053 always moves LOW-VALUES and :1333 always sends ERASE")
                    .isTrue();
            assertThat(metadata.cursorField()).isEqualTo(task.cursorField);
        }

        /** Reads back an {@code xxxA} attribute item as the byte the {@code MOVE} put there. */
        private byte attributeItemOf(CardUpdateRequest request, String label) {
            String item = request.metadataFor(label).attributeItem();
            return (byte) item.charAt(0);
        }

        /** Sets exactly the one flag or message the cursor scenario names. */
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
                    // WHEN OTHER at :1233: every flag left as INITIALIZE made it, which is a space and
                    // satisfies no NOT-OK and no BLANK condition.
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

    // =================================================================================================

    @Nested
    @DisplayName("1100-RECEIVE-MAP, 1200-EDIT-MAP-INPUTS and the six edit paragraphs - :578-945")
    class Edits {

        /** Runs {@code 1000-PROCESS-INPUTS} over a request, in the given state. */
        private Conversation edit(CardUpdateRequest request, ChangeAction action) {
            Conversation task = task(reentered(), action);
            controller.processInputs1000(request, task);
            return task;
        }

        /** A request carrying the six typeable items. */
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

        /** {@code :644} - the paragraph opens by asserting {@code INPUT-OK}. */
        @Test
        @DisplayName("1200 starts from INPUT-OK - :644")
        void editsStartFromInputOk() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, CARD_NUMBER, "JOHN Q PUBLIC", "Y", "04",
                    "2026"), ChangeAction.initial());

            assertThat(task.inputOk() || task.inputError()).isTrue();
        }

        /**
         * {@code :646-666} - in the not-fetched state only the two keys are edited, and then
         * {@code GO TO 1200-EDIT-MAP-INPUTS-EXIT} skips the four detail edits entirely.
         */
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

        /** {@code :656-660} - both keys blank is its own message, set after the two edits. */
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

        /** {@code :725-735} - an unsupplied account is BLANK, not NOT-OK, and prompts for itself. */
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

        /** {@code :727} - and eleven zeroes count as unsupplied, through the numeric redefine. */
        @Test
        @DisplayName("1210 treats an all-zero account as unsupplied - :727")
        void editAccountAllZeroIsBlank() {
            Conversation task = edit(typed("00000000000", CARD_NUMBER, "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgAcctfilterBlank())
                    .as("OR CC-ACCT-ID-N EQUAL ZEROS at :727 tests the PIC 9(11) view of the same bytes")
                    .isTrue();
        }

        /** {@code :740-750} - non-numeric is NOT-OK with a direct-{@code MOVE} message, not a condition. */
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

        /** {@code :751-755} - and a good one is valid and reaches the commarea. */
        @Test
        @DisplayName("1210 valid account reaches CDEMO-ACCT-ID - :751-755")
        void editAccountValid() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, CARD_NUMBER, "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgAcctfilterIsvalid()).isTrue();
            assertThat(task.carddemoCommarea.acctId()).isEqualTo(11L);
            assertThat(task.newDetails().acctid()).isEqualTo(ACCOUNT_NUMBER);
        }

        /** {@code :767-779} - the card's blank arm. */
        @Test
        @DisplayName("1220 blank card: FLG-CARDFILTER-BLANK + its prompt - :767-779")
        void editCardBlank() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, "", "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgCardfilterBlank()).isTrue();
            assertThat(task.wsReturnMsg.trim()).isEqualTo("Card number not provided");
            assertThat(task.carddemoCommarea.cardNum()).isZero();
        }

        /** {@code :783-793} - and its non-numeric arm, with the other direct-{@code MOVE} message. */
        @Test
        @DisplayName("1220 non-numeric card: the direct-MOVE message - :783-793")
        void editCardNonNumeric() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, "400000000000000X", "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgCardfilterNotOk()).isTrue();
            assertThat(task.wsReturnMsg)
                    .isEqualTo(CardUpdateController.CARD_ID_FILTER_MUST_BE_16_DIGITS);
        }

        /** {@code :794-798} - a valid card sets the numeric commarea field and the alphanumeric group. */
        @Test
        @DisplayName("1220 valid card reaches both CDEMO-CARD-NUM and CCUP-NEW-CARDID - :794-798")
        void editCardValid() {
            Conversation task = edit(typed(ACCOUNT_NUMBER, CARD_NUMBER, "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgCardfilterIsvalid()).isTrue();
            assertThat(task.carddemoCommarea.cardNum()).isEqualTo(Long.parseLong(CARD_NUMBER));
            assertThat(task.newDetails().cardid()).isEqualTo(CARD_NUMBER);
        }

        /**
         * {@code :680-682} - once the details are fetched, an unchanged screen is detected by comparing
         * {@code FUNCTION UPPER-CASE} of the two 89-byte {@code CARDDATA} groups.
         */
        @Test
        @DisplayName("1200 detects no change by comparing the folded CARDDATA groups - :680-682")
        void noChangeDetectedByFoldedComparison() {
            // The day is part of CCUP-xxx-CARDDATA and :617 moves EXPDAYI into CCUP-NEW-EXPDAY
            // UNCONDITIONALLY, so a screen that echoed the stored day back transmits it and the two
            // groups match. Omitting it here would make the groups differ on a field the operator cannot
            // even see, which is what noChangeNeedsTheDayToo() asserts.
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

        /**
         * {@code :617} - {@code MOVE EXPDAYI OF CCRDUPAI TO CCUP-NEW-EXPDAY} is the <strong>only one of
         * the seven moves in {@code 1100} with no {@code '*'}-or-{@code SPACES} test</strong>.
         *
         * <p>So the day is taken verbatim from a field the operator can never see - {@code 3300:1285}
         * darkens it unconditionally - and it is part of the 89-byte {@code CARDDATA} group that
         * {@code :680-681} compares. A client that omits it therefore reports a change on a field nobody
         * touched. Faithful, and worth pinning.
         */
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

        /** {@code :685-691} - and the confirmation states skip the detail edits for the same reason. */
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

        /** {@code :695-712} - a real change runs all four detail edits and lands on {@code 'E'} or {@code 'N'}. */
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

        /** {@code :709-711} - and a rejected one stays on {@code 'E'}. */
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

        /** {@code :810-818} - a blank name. */
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

        /**
         * {@code :822-838} - only letters and spaces.
         *
         * <p>Implemented with the declared {@code LIT-ALL-ALPHA-FROM}/{@code LIT-ALL-SPACES-TO} pair, so a
         * character outside those 52 survives the fold and the trimmed length is non-zero.
         */
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

        /** {@code :839} - and accepts one that is only letters and spaces, in either case. */
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

        /** {@code :849-857} and {@code :861-872} - {@code Y} or {@code N}, and nothing else. */
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

        /** {@code :849-857} - a blank status is BLANK, and carries the same message as an invalid one. */
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

        /** {@code :896-899} - {@code 88 VALID-MONTH VALUES 1 THRU 12}, over the {@code PIC 9(2)} view. */
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

        /**
         * {@code :913-921} - the year's blank arm, and the asymmetry in it.
         *
         * <p>Every other edit paragraph opens with {@code SET FLG-*-NOT-OK TO TRUE}; {@code 1260} does it
         * <strong>after</strong> the blank test, at {@code :924}. So on the blank path the year's flag
         * reaches {@code BLANK} without having passed through {@code NOT-OK} first - a difference with no
         * observable consequence, and reproduced because it is what the source does.
         */
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

        /** {@code :926-929} - {@code 88 VALID-YEAR VALUES 1950 THRU 2099}. */
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

        /**
         * The consequence of {@code 88 VALID-YEAR} being declared over a {@code REDEFINES}, not over a
         * parse - {@code app/cbl/COCRDUPC.cbl:96-99}.
         *
         * <p>{@code CARD-YEAR-CHECK-N PIC 9(4) REDEFINES CARD-YEAR-CHECK PIC X(4)} reads the same four
         * bytes as zoned decimal, and a zoned digit is the byte's <strong>low-order nibble</strong>. So
         * {@code '20X7'} - where {@code 'X'} is {@code 0x58} and its low nibble is {@code 8} - reads as
         * {@code 2087}, which <em>is</em> within {@code 1950 THRU 2099}, and the year is
         * <strong>accepted</strong>.
         *
         * <p>{@link Integer#parseInt} would have thrown, and a translation that used it would reject an
         * input the COBOL accepts. This is exactly why the {@code X}/{@code 9} pair is implemented as a
         * byte reinterpretation and never as a parse (gate G34). The behaviour is odd; it is also the
         * behaviour, so it is asserted rather than corrected.
         */
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

        /** And the same rule rejects a month, because the nibble lands outside 1 to 12. */
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

        /**
         * The {@code IF WS-RETURN-MSG-OFF} guard, which every one of the six edits carries: the
         * <em>first</em> problem found is the one the operator is told about, not the last.
         *
         * <p>Shown with the account blank and the card non-numeric, so both edits have something to say
         * and only {@code 1210}'s survives. It cannot be shown with both keys blank, because
         * {@code :656-660} then overwrites unguarded - which
         * {@code bothKeysBlankIsItsOwnMessage()} asserts.
         */
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

        /**
         * {@code :594-638} - {@code 1100-RECEIVE-MAP} treats a field holding {@code '*'} as not supplied,
         * because {@code 3300} wrote that marker itself on the previous pass.
         */
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

        /** And a genuinely empty field is unsupplied for the same reason. */
        @Test
        @DisplayName("1100 reads an empty field as unsupplied - :594-638")
        void receiveMapTreatsSpacesAsUnsupplied() {
            Conversation task = edit(typed("           ", "                ", "", "", "", ""),
                    ChangeAction.initial());

            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(task.flgCardfilterBlank()).isTrue();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("9000-READ-DATA and 9100-GETCARD-BYACCTCARD - :1343-1417")
    class BaseRead {

        /** Reads with the given outcome, from a task carrying the two keys and a prior message. */
        private Conversation readWith(CardReadResult result, String priorMessage) {
            when(repository.readByCardNumber(anyString())).thenReturn(result);
            Conversation task = taskWithValidKeys(ChangeAction.initial());
            task.wsReturnMsg = priorMessage;
            controller.readData9000(task);
            return task;
        }

        /** {@code :1345-1347} - the keys are snapshotted before the read, so an error screen echoes them. */
        @Test
        @DisplayName("9000 snapshots the keys before the read - :1345-1347")
        void keysAreSnapshottedBeforeTheRead() {
            Conversation task = readWith(CardReadResult.notFound(),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.oldDetails().acctid()).isEqualTo(ACCOUNT_NUMBER);
            assertThat(task.oldDetails().cardid()).isEqualTo(CARD_NUMBER);
        }

        /** {@code :1352-1369} - and the six non-key items stay spaces when the read found nothing. */
        @Test
        @DisplayName("9000 leaves the detail items as INITIALIZE's spaces on NOTFND - :1352")
        void detailItemsStaySpacesOnNotFound() {
            Conversation task = readWith(CardReadResult.notFound(),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.oldDetails().crdname().trim()).isEmpty();
            assertThat(task.oldDetails().crdstcd()).isEqualTo(" ");
        }

        /**
         * {@code :1354-1367} - a successful read fills all eight items, splitting the expiry at the
         * 1-based {@code (1:4)}, {@code (6:2)}, {@code (9:2)}.
         *
         * <p>Characters 5 and 8 of {@code 2026-04-30} are the hyphens and belong to no component, which is
         * the whole point of the assertion: a 0-based misreading would put {@code 026-} in the year.
         */
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

        /**
         * {@code :1356-1358} - {@code INSPECT ... CONVERTING} folds the embossed name <strong>in the
         * record area</strong> before the move out, so the held record carries the folded value too.
         */
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

        /** {@code :1396-1398} - {@code NOTFND} sets both key flags unconditionally. */
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

        /**
         * {@code :1399-1401} - but the <em>message</em> defers to one already placed, while the flags do
         * not. Collapsing that guard would overwrite an earlier, more specific message.
         */
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

        /**
         * {@code :1402-1411} - {@code WHEN OTHER} composes the 80-character file-error message and
         * assigns it <strong>unguarded</strong>, so it replaces the message the guard two statements
         * earlier was protecting.
         */
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

        /** {@code :1404-1406} - and with no earlier message the guarded flag does get set. */
        @Test
        @DisplayName("9100 WHEN OTHER sets the guarded flag when no message was present - :1404-1406")
        void otherSetsTheGuardedFlagWhenTheMessageWasOff() {
            Conversation task = readWith(CardReadResult.reportedFailure(FileStatus.NOTOPEN, 2),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            assertThat(task.flgAcctfilterNotOk()).isTrue();
        }

        /**
         * {@code :133-152} - the composed message, character for character.
         *
         * <p>{@code 'File Error: '} + {@code READ} at {@code X(8)} + {@code ' on '} + {@code CARDDAT} at
         * {@code X(9)} + {@code ' returned RESP '} + nine zero-filled digits at {@code X(10)} +
         * {@code ',RESP2 '} + the same + five spaces = 80, then narrowed to 75 by the move at
         * {@code :1411}.
         */
        @Test
        @DisplayName("the file-error message, character for character - :133-152, :1407-1411")
        void fileErrorMessageCharacterForCharacter() {
            Conversation task = readWith(CardReadResult.reportedFailure(19, 2),
                    CardScreenState.spaces(CardUpdateController.WS_RETURN_MSG_LENGTH));

            // ERROR-RESP is PIC X(10) and takes nine zero-filled digits, so the tenth character is a PAD
            // SPACE and it sits between the digits and ',RESP2 '. Likewise ERROR-FILE is X(9) against an
            // X(8) literal, which is why there are two spaces before "returned".
            String expected = "File Error: READ     on CARDDAT   returned RESP 000000019 "
                    + ",RESP2 000000002 ";
            assertThat(task.wsReturnMsg)
                    .hasSize(CardUpdateController.WS_RETURN_MSG_LENGTH)
                    .isEqualTo(codecPad(expected, CardUpdateController.WS_RETURN_MSG_LENGTH));
            assertThat(controller.fileErrorMessage(task))
                    .as("the group itself is exactly 80 characters before the move narrows it")
                    .hasSize(CardUpdateController.FILE_ERROR_MESSAGE_LENGTH);
        }

        /**
         * {@code :1399-1401} and {@code :1404-1406} share one guard, and each of the six edit paragraphs
         * carries its own copy of it. Driven here with the message both off and already set for every one,
         * so no copy of the guard is left unexercised in either direction.
         */
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

        /** {@code :1409} - {@code MOVE WS-RESP-CD TO ERROR-RESP} zero-fills to nine digits. */
        @ParameterizedTest(name = "[{index}] RESP {0} renders as {1}")
        @CsvSource({"0,000000000", "13,000000013", "999999999,999999999"})
        @DisplayName("a RESP renders as nine zero-filled digits, padded to ten - :1409")
        void respCodeImage(int resp, String digits) {
            assertThat(controller.responseCodeImage(resp))
                    .isEqualTo(digits + " ")
                    .hasSize(CardUpdateController.ERROR_RESP_LENGTH);
        }

        /** A negative response code has no sign to move into a {@code PIC X} receiver. */
        @Test
        @DisplayName("a negative RESP loses its sign, as a PIC X move must - :1409")
        void negativeRespLosesItsSign() {
            assertThat(controller.responseCodeImage(-13)).isEqualTo("000000013 ");
        }

        private static String codecPad(String value, int width) {
            return CODEC.movePicX(value, width);
        }
    }

    // =================================================================================================

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

        /** {@code :388-394} - no communication area is the cold start. */
        @Test
        @DisplayName("EIBCALEN = 0 initialises and prompts - :388-394")
        void coldStartPrompts() {
            CardUpdateRequest request = request("", "", null, CommArea.initialised());

            Conversation task = run(request, 0, CicsAid.DFHENTER);

            assertThat(task.changeAction().isDetailsNotFetched()).isTrue();
            assertThat(task.wsTranid).isEqualTo("CCUP");
            verifyNoInteractions(repository, service);
        }

        /** {@code :390} - and arriving fresh from the menu is treated the same way. */
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

        /** {@code :396-400} - otherwise the passed area is restored, both halves of it. */
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

        /** {@code :413-424} - the four keys this screen accepts, and their preconditions. */
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

        /** {@code :435-476} - {@code PF3} transfers back, and the response says where. */
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

        /** {@code :445-448} - and back to the caller when there is one. */
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

        /**
         * {@code :437-439} - and the same arm is reached without {@code PF3} when the update finished and
         * the operator came from the card list, which is how a completed update returns there by itself.
         */
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

        /**
         * {@code :482-497} - arriving from the card list fetches immediately and
         * <strong>unconditionally</strong> sets {@code CCUP-SHOW-DETAILS}, unlike {@code 2000:963-965}
         * which guards the same {@code SET} with {@code IF FOUND-CARDS-FOR-ACCOUNT}.
         */
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

        /** {@code :491} - and a failed read still shows, which is the asymmetry with {@code 2000}. */
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

        /** {@code :502-511} - the prompt arm paints the screen <em>before</em> flipping to REENTER. */
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

        /** {@code :517-528} - the reset arm also clears the carried keys before repainting. */
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

        /** {@code :535-542} - and everything else goes through the state machine. */
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

        /**
         * {@code :437-439} - the two card-list arms are guarded by {@code CDEMO-LAST-MAPSET}, so a
         * finished update that did <em>not</em> come from the card list stays on this screen and takes the
         * reset arm at {@code :517-528} instead.
         */
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

        /**
         * {@code :484} and {@code :488} - both card-list arms are additionally guarded by
         * {@code CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM}, so {@code PF12} from anywhere else falls
         * through to the state machine rather than re-fetching.
         */
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

        /**
         * {@code :505} - the second half of the prompt arm's condition,
         * {@code CDEMO-FROM-PROGRAM EQUAL LIT-MENUPGM AND NOT CDEMO-PGM-REENTER}, reached with the change
         * action already advanced so only the menu half can have matched.
         */
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

        /** {@code :1055} and {@code :1062} - both readings are taken, and the second is the one used. */
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

        /** {@code :551-558} - {@code COMMON-RETURN} appends the trailer after the 160-byte commarea. */
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

    // =================================================================================================

    @Nested
    @DisplayName("The HTTP boundary - PUT /api/cards/{cardNum}")
    class Boundary {

        /** The URI's card number reaches {@code CARDSIDI} and the carried commarea alike. */
        @Test
        @DisplayName("bind moves the path card number into CARDSID")
        void bindMovesThePathCardNumber() {
            CardUpdateRequest bound = controller.bind(CARD_NUMBER, null);

            assertThat(bound.getCardsid()).isEqualTo(CARD_NUMBER);
        }

        /** A shorter one is space-padded, as a {@code PIC X(16)} receiver requires. */
        @Test
        @DisplayName("bind pads a short card number to X(16)")
        void bindPadsAShortCardNumber() {
            CardUpdateRequest bound = controller.bind("4000", null);

            assertThat(bound.getCardsid()).isEqualTo("4000            ")
                    .hasSize(CardUpdateRequest.CARDSID_LENGTH);
        }

        /**
         * An over-width one is <strong>refused</strong> rather than moved.
         *
         * <p>A {@code MOVE} would keep the leading sixteen characters and update a card the URI does not
         * name - and no operator could have typed it, because a 3270 field cannot accept more characters
         * than it declares. There is no faithful behaviour to reproduce, so the boundary refuses it.
         */
        @Test
        @DisplayName("bind refuses an over-width card number rather than truncating it")
        void bindRefusesAnOverWidthCardNumber() {
            assertThatThrownBy(() -> controller.bind("40000000000000019999", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CARDSIDI PIC X(16)");
        }

        /** And the same for the account filter. */
        @Test
        @DisplayName("bind refuses an over-width account filter")
        void bindRefusesAnOverWidthAccountFilter() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setAcctsid("123456789012345");

            assertThatThrownBy(() -> controller.bind(CARD_NUMBER, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ACCTSIDI PIC X(11)");
        }

        /** A commarea is only projected when one was actually passed - otherwise {@code EIBCALEN} is 0. */
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

        /** A non-numeric path value carries no numeric commarea value. */
        @Test
        @DisplayName("a non-numeric card number carries zero into CDEMO-CARD-NUM")
        void nonNumericCardNumberCarriesZero() {
            assertThat(CardUpdateController.carriedCardNumber("400000000000000X")).isZero();
            assertThat(CardUpdateController.carriedCardNumber("")).isZero();
            assertThat(CardUpdateController.carriedCardNumber(CARD_NUMBER))
                    .isEqualTo(Long.parseLong(CARD_NUMBER));
        }

        /**
         * {@code EIBCALEN} is derived from the payload, and an explicit value that agrees is honoured.
         *
         * <p>It cannot <em>contradict</em> the payload, because {@code :388} uses it to decide whether the
         * conversation's state survives the turn: a caller claiming {@code 0} while sending a
         * communication area would send itself down the cold-start arm and silently lose the state it
         * just transmitted.
         */
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

        /** And an explicit zero alongside a passed area is refused rather than believed. */
        @Test
        @DisplayName("resolveEibcalen refuses an explicit 0 when a commarea was passed - :388")
        void eibcalenRefusesAZeroThatContradictsThePayload() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setNavigationContext(reentered());

            assertThatThrownBy(() -> CardUpdateController.resolveEibcalen(0, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot contradict");
        }

        /** An {@code EIBCALEN} the payload cannot support is refused. */
        @Test
        @DisplayName("resolveEibcalen refuses a length the payload contradicts")
        void eibcalenRefusesAContradiction() {
            CardUpdateRequest request = new CardUpdateRequest();

            assertThatThrownBy(() -> CardUpdateController.resolveEibcalen(
                    CardUpdateController.PASSED_COMMAREA_LENGTH, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** {@code EIBAID} defaults to {@code DFHENTER}, which is what an unset AID means. */
        @Test
        @DisplayName("an absent EIBAID is DFHENTER")
        void absentAidIsEnter() {
            assertThat(CardUpdateController.resolveAttentionIdentifier(null))
                    .isEqualTo(CicsAid.DFHENTER);
        }

        /** And a value outside one byte is refused rather than silently masked. */
        @ParameterizedTest(name = "[{index}] EIBAID {0} is refused")
        @ValueSource(ints = {-1, 256, 1000})
        @DisplayName("an out-of-range EIBAID is refused")
        void outOfRangeAidIsRefused(int value) {
            assertThatThrownBy(() -> CardUpdateController.resolveAttentionIdentifier(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** A byte in range is passed through unchanged, including one above {@code 0x7F}. */
        @Test
        @DisplayName("an in-range EIBAID passes through, including above 0x7F")
        void inRangeAidPassesThrough() {
            assertThat(CardUpdateController.resolveAttentionIdentifier(
                    CicsAid.DFHPF3 & 0xFF)).isEqualTo(CicsAid.DFHPF3);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The figurative-constant and REDEFINES primitives")
    class Primitives {

        /** {@code LOW-VALUES} and {@code SPACES} are two byte patterns, and both are recognised. */
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

        /** {@code EQUAL ZEROS} on an alphanumeric item compares the character {@code '0'}. */
        @Test
        @DisplayName("isAllZeroCharacters compares the CHARACTER zero, not the value")
        void allZeroCharacters() {
            assertThat(CardUpdateController.isAllZeroCharacters("00000000000", 11)).isTrue();
            assertThat(CardUpdateController.isAllZeroCharacters("00000000001", 11)).isFalse();
            assertThat(CardUpdateController.isAllZeroCharacters("           ", 11)).isFalse();
        }

        /**
         * The {@code X}/{@code 9} {@code REDEFINES} pair is a byte reinterpretation, not a parse.
         *
         * <p>{@code CARD-CVV-CD-X PIC X(3)} and {@code CARD-CVV-CD-N PIC 9(3)} are the same three bytes,
         * so reading the numeric view of a non-numeric value must not throw - a {@code PIC 9} view of
         * {@code 'A12'} is simply not a valid number, and COBOL's {@code IS NUMERIC} is what a program
         * asks before relying on it. Gate G34.
         */
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

        /** Round-tripping every {@code CICS-OUTPUT-EDIT-VARS} width through both views. */
        @ParameterizedTest(name = "[{index}] {0} digits round-trip")
        @CsvSource({"11,00000000011", "3,123", "16,4000000000000001", "10,2026043012"})
        @DisplayName("every REDEFINES pair round-trips - :103-124, G34")
        void redefinesPairsRoundTrip(int width, String digits) {
            long value = CardUpdateController.zonedDigitsValue(digits, width, CODEC);

            assertThat(CODEC.movePic9(value, width))
                    .as("the alphanumeric view of the numeric view is the value it started from")
                    .isEqualTo(digits);
        }

        /** {@code INSPECT ... CONVERTING} over the declared pairs, never {@code toUpperCase}. */
        @Test
        @DisplayName("inspectConverting folds only the 26 declared pairs")
        void inspectConvertingFoldsOnlyTheDeclaredPairs() {
            String folded = CardUpdateController.inspectConverting("abc-XYZ 123",
                    CardUpdateController.LIT_LOWER, CardUpdateController.LIT_UPPER);

            assertThat(folded)
                    .as("only a-z are in LIT-LOWER, so the hyphen, the digits and the space survive")
                    .isEqualTo("ABC-XYZ 123");
        }

        /** A character outside the from-set is left exactly as it was. */
        @Test
        @DisplayName("inspectConverting leaves an unlisted character alone")
        void inspectConvertingLeavesUnlistedCharactersAlone() {
            assertThat(CardUpdateController.inspectConverting("\u00E9", CardUpdateController.LIT_LOWER,
                    CardUpdateController.LIT_UPPER))
                    .as("String.toUpperCase would have folded this; the 26 declared pairs do not")
                    .isEqualTo("\u00E9");
        }

        /** {@code FUNCTION LENGTH(FUNCTION TRIM(...)) = 0} - the test {@code 1230} performs. */
        @Test
        @DisplayName("isTrimmedEmpty is FUNCTION LENGTH(FUNCTION TRIM(x)) = 0 - :826")
        void trimmedEmpty() {
            assertThat(CardUpdateController.isTrimmedEmpty("   ")).isTrue();
            assertThat(CardUpdateController.isTrimmedEmpty("")).isTrue();
            assertThat(CardUpdateController.isTrimmedEmpty(" X ")).isFalse();
        }

        /** {@code FUNCTION UPPER-CASE} over the same 26 pairs. */
        @Test
        @DisplayName("functionUpperCase folds the 26 declared pairs - :680-681")
        void functionUpperCase() {
            assertThat(CardUpdateController.functionUpperCase("john q public"))
                    .isEqualTo("JOHN Q PUBLIC");
        }

        /**
         * {@code IF ACCTSIDI = '*' OR = SPACES} - {@code :587-588} and its five siblings.
         *
         * <p>The test names <strong>exactly two</strong> patterns, and {@code LOW-VALUES} is
         * <em>not</em> one of them - unlike the edit paragraphs, which all test
         * {@code EQUAL LOW-VALUES OR EQUAL SPACES OR EQUAL ZEROS}. So a field arriving as {@code x'00'}
         * falls to the {@code ELSE} at {@code :592} and is moved through as-is; the edit that follows is
         * what catches it, through its own {@code LOW-VALUES} test. The two-stage handling is the source's
         * and is reproduced rather than unified.
         */
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

    // =================================================================================================

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
                    // ScreenResponse declares @JsonUnwrapped on the screen, so the seventeen items sit at the
                    // top level of the body with the metadata beside them - not under a "screen" node.
                    .andExpect(jsonPath("$.trnnameo").value("CCUP"))
                    .andExpect(jsonPath("$.pgmnameo").value("COCRDUPC"))
                    .andExpect(jsonPath("$.cardsido").value(CARD_NUMBER))
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
}
