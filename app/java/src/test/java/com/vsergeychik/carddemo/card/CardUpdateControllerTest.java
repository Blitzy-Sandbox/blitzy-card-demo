package com.vsergeychik.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link CardUpdateController} - the {@code COCRDUPC} / {@code CCUP} credit-card update screen.
 *
 * <p>The unit under test migrates {@code app/cbl/COCRDUPC.cbl} (1,560 lines), reached in CICS as
 * transaction {@code CCUP} - {@code app/csd/CARDDEMO.CSD:367},
 * {@code DESCRIPTION(CREDIT CARD UPDATE TRANSACTION)} - which sends and receives mapset
 * {@code COCRDUP}, map {@code CCRDUPA}. The map's field contract is
 * {@code app/cpy-bms/COCRDUP.CPY} beside {@code app/bms/COCRDUP.bms}; the record it edits is
 * {@code app/cpy/CVACT02Y.cpy}; the work area it carries is {@code app/cpy/CVCRD01Y.cpy}. All five are
 * read as the contract and <strong>cited in comments only</strong> - not one of them is opened at
 * runtime by this test (practice B3), so the parity oracle cannot be perturbed by running the suite.
 *
 * <h2>Governing rules: there are none, and that is a finding rather than an omission</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document. <strong>No user-specified rule governs this file.</strong> None has been
 * invented to fill the space, and the absence is not treated as licence to lower the bar. The binding
 * standard is therefore enterprise best practice as codified by the Agent Action Plan in
 * <strong>&sect;0.10.2 B1-B12</strong> together with the absolutes in <strong>&sect;0.8.9</strong>.
 * Those are plan directives, and are never described here as "user rules". The ones this file answers
 * to directly:
 *
 * <ul>
 *   <li><strong>B1 / B2</strong> - only the closed test stack the module already declares: JUnit
 *       Jupiter, Mockito, AssertJ and Spring Test's {@code MockMvc}. JUnit 5 only, no new dependency
 *       and no version literal anywhere below.</li>
 *   <li><strong>B3</strong> - the COBOL, copybook, BMS and CSD sources are quoted, never read or
 *       written.</li>
 *   <li><strong>B5</strong> - behaviour is preserved including its quirks. The empty-bodied
 *       {@code WHEN}, the body two arms share, the condition name tested twice and the two 88-levels
 *       carrying one byte pattern are all real, and are asserted rather than tidied.</li>
 *   <li><strong>B6</strong> - no card number, CVV or embossed name is masked, redacted or truncated,
 *       in a payload, an assertion or a failure message.</li>
 *   <li><strong>B7</strong> - determinism. {@link #FIXED_CLOCK} is injected because
 *       {@code 3100-SCREEN-INIT} reads {@code FUNCTION CURRENT-DATE} at {@code :1055} and again at
 *       {@code :1062}; nothing here reads a wall clock, depends on test ordering, or touches a
 *       network.</li>
 *   <li><strong>B8</strong> - explicit over implicit: no wildcard import, an explicitly named
 *       {@link Charset}, and no dataset name in any form.</li>
 *   <li><strong>B9</strong> - no static mutable state. Every {@code static} member here is
 *       {@code final} and immutable; the three collaborators are per-test instance fields rebuilt by
 *       {@link #setUp()}.</li>
 *   <li><strong>B11</strong> - widths and field names are asserted explicitly against the symbolic
 *       map, one at a time. Nothing is compared by reflective deep equality, because a reflective
 *       comparison that passes proves only that two objects agree, not that either matches the
 *       copybook.</li>
 *   <li><strong>&sect;0.8.9</strong> - a copybook field is never renamed, and that includes the
 *       misspelling four of them share: {@code CARD-EXPIRAION-DATE},
 *       {@code CARD-UPDATE-EXPIRAION-DATE}, {@code CCUP-OLD-EXPIRAION-DATE} and
 *       {@code CCUP-NEW-EXPIRAION-DATE}. The conventionally spelled form of that word appears
 *       nowhere in this file - only the copybook's own spelling does, which is what a mechanical
 *       scan for the corrected spelling is meant to confirm.</li>
 * </ul>
 *
 * <h2>The gates this file is answerable for</h2>
 *
 * <p><strong>G9</strong> every payload field traces to a {@code DFHMDF} definition -
 * {@link PayloadContract}. <strong>G30</strong> every {@code WHEN} in source order with
 * {@code WHEN OTHER} last - {@link DecideAction}, {@link StateAlphabet}. <strong>G37</strong> no
 * server-side session state - {@link Statelessness}. <strong>G38</strong> both {@code ENTER} and
 * {@code REENTER}, with the highlight only on re-entry - {@link Screen}. <strong>G40</strong> the
 * {@code XCTL} site resolves to a {@code nextProgram} response field - {@link Dispatcher},
 * {@link Statelessness}. <strong>G47</strong> each {@link FileStatus} outcome per repository call site
 * - {@link BaseRead}. <strong>G50</strong> both states of every 88-level touched -
 * {@link ChangeActionConditions}, {@link StateAlphabet}, {@link MessageLiterals}.
 * <strong>G51</strong> service logic is not re-asserted here - see below. <strong>G49</strong> branch
 * coverage at or above 0.90 for {@code com.vsergeychik.carddemo.card}. <strong>G52</strong> no
 * wildcard import. <strong>G53</strong> no static mutable state. <strong>G54</strong> the suite runs
 * non-interactively, with no watch mode and no ordering dependence.
 *
 * <h2>Division of labour with {@link CardUpdateServiceTest} - gate G51</h2>
 *
 * <p>This is a hard boundary, not a preference. The write path
 * ({@code 9200-WRITE-PROCESSING}, {@code :1420-1494}) and the optimistic-concurrency check
 * ({@code 9300-CHECK-CHANGE-IN-REC}, {@code :1498-1523}) live in {@link CardUpdateService} and are
 * asserted field by field in {@code CardUpdateServiceTest}. Here the service is
 * <strong>stubbed and nothing more</strong>: this file asserts only what the controller itself owns -
 * the eight-arm state machine, the projection of the map, navigation and statelessness. There is
 * deliberately <strong>no</strong> assertion below of the six-field {@code CCUP-OLD-DETAILS} versus
 * re-read comparison; a {@code CCUP-OLD} reference in this file is stub setup, never a verification of
 * the comparison itself. Asserting it twice would let the two suites drift apart and would say
 * nothing about the controller.
 *
 * <p>Every test that asserts a <em>decision</em> instantiates the controller <strong>directly</strong>
 * with a mocked {@link CardRepository}, a mocked {@link CardUpdateService} and a fixed {@link Clock}.
 * There is no Spring context and no {@code MockMvc} in the decision path, which is the other half of
 * gate <strong>G51</strong>: an eight-arm {@code EVALUATE} is asserted where it lives, and a failure
 * names the paragraph rather than an HTTP status.
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

    /**
     * A request with every one of the six editable screen fields typed, so that
     * {@code 1200-EDIT-MAP-INPUTS} finds nothing to complain about.
     *
     * <p>{@code 1100-RECEIVE-MAP:594-638} reads {@code ACCTSID}, {@code CARDSID}, {@code CRDNAME},
     * {@code CRDSTCD}, {@code EXPMON}, {@code EXPYEAR} and {@code EXPDAY} into
     * {@code CCUP-NEW-DETAILS}; a field left unset arrives blank, and a blank editable field is an
     * input error. So a test that needs the {@code 'S'} arm to <em>advance</em> has to supply all
     * seven, and the status is flipped to {@code N} against {@link #oldDetails()}'s {@code Y} so that
     * {@code NO-CHANGES-DETECTED} ({@code :682}) is false as well.
     *
     * @param commarea the {@code CARDDEMO-COMMAREA} to carry
     * @param trailer  the 329-byte {@code WS-THIS-PROGCOMMAREA} to carry
     * @return a request that survives all six edit paragraphs
     */
    private static CardUpdateRequest typedRequest(NavigationContext commarea, CommArea trailer) {
        CardUpdateRequest typed = request(ACCOUNT_NUMBER, CARD_NUMBER, commarea, trailer);
        typed.setCrdname("JOHN Q PUBLIC");
        typed.setCrdstcd("N");
        typed.setExpmon("04");
        typed.setExpyear("2026");
        typed.setExpday("30");
        return typed;
    }

    /**
     * The trailer a real conversation carries once {@code 9000-READ-DATA} has run: the state byte plus
     * the {@code CCUP-OLD-DETAILS} snapshot the fetch left behind.
     *
     * <p>Supplying the snapshot matters even for tests that never look at it. {@code 1200:671-672}
     * moves {@code CCUP-OLD-ACCTID} and {@code CCUP-OLD-CARDID} into {@code CDEMO-ACCT-ID PIC 9(11)}
     * and {@code CDEMO-CARD-NUM PIC 9(16)}, so an initialised - blank - snapshot is eleven spaces
     * arriving at a zoned {@code DISPLAY} receiver. CICS would never reach {@code 'S'} without having
     * fetched first, so there is no COBOL behaviour to be faithful to there; a hand-built payload that
     * does is refused as {@link ScreenInputRejectedException} by
     * {@link CardUpdateController#requireFetchedKey}, ahead of the two {@code MOVE}s, rather than
     * abending the transaction over the caller's own input. See {@link CommareaConsistency}.
     *
     * @param action the state byte to carry
     * @return the trailer as the previous turn would have returned it
     */
    private static CommArea fetchedTrailer(ChangeAction action) {
        return CommArea.initialised()
                .withChangeAction(action)
                .withOldDetails(oldDetails())
                .withNewDetails(newDetails());
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

    // =================================================================================================
    // The remaining four groups take the same eight arms, the same nineteen literals and the same
    // seventeen fields and assert them as COMPLETE SETS rather than one case at a time. A per-case test
    // proves a case behaves; a set test proves nothing was left out - which is the property gates G9,
    // G30, G37 and G50 actually name.
    // =================================================================================================

    @Nested
    @DisplayName("The CCUP-CHANGE-ACTION alphabet - every byte, routed through :949-1027 (G30, G50)")
    class StateAlphabet {

        /**
         * {@code CCUP-CHANGE-ACTION} is one byte wide and only nine values mean anything to it. This
         * table walks every one of them through {@code 2000-DECIDE-ACTION} and records the arm it lands
         * on, in the order {@code app/cbl/COCRDUPC.cbl:949-1027} tests them.
         *
         * <p>The two rows worth pausing on are {@code 'L'} and {@code 'F'}. Both are
         * {@code CCUP-CHANGES-FAILED} ({@code :288}) and both are {@code CCUP-CHANGES-MADE}
         * ({@code :282-284}), so they read like states the paragraph handles - and it does not. Neither
         * appears in any of the seven qualified {@code WHEN}s, so both reach {@code WHEN OTHER} at
         * {@code :1019} and <strong>abend</strong>. That is not a defect to be smoothed over: it is why
         * {@code 0000-MAIN:437-439} intercepts those two states and transfers control before
         * {@code 2000} is ever performed, which {@code Dispatcher} asserts separately. Adding an
         * {@code 'L'} or {@code 'F'} arm here would silently delete that interception.
         *
         * @param state    the single byte in {@code CCUP-CHANGE-ACTION}
         * @param expected the state the paragraph leaves behind, or {@code ABEND}
         */
        @ParameterizedTest(name = "[{index}] {0} -> {1}")
        @CsvSource({
            // token     | resulting CCUP-CHANGE-ACTION, or ABEND
            "LOW_VALUES,S",  // :954 DETAILS-NOT-FETCHED, x'00' form  -> shared body reads, sets 'S'
            "SPACES,S",      // :954 DETAILS-NOT-FETCHED, x'40' form  -> the same shared body
            "S,S_TO_N",      // :971 SHOW-DETAILS, input clean        -> 'N'
            "E,E",           // :982 CHANGES-NOT-OK                   -> CONTINUE, stays 'E'
            "N,N",           // :1006 bare OK-NOT-CONFIRMED, no PF5   -> CONTINUE, stays 'N'
            "C,S",           // :1011 OKAYED-AND-DONE                 -> 'S'
            "L,ABEND",       // :1019 WHEN OTHER - CHANGES-FAILED is NOT an arm of this paragraph
            "F,ABEND",       // :1019 WHEN OTHER - likewise
            "Q,ABEND"})      // :1019 WHEN OTHER - and any byte outside the alphabet
        @DisplayName("every byte lands on exactly one arm, and three of them abend - :949-1027")
        void everyStateByteLandsOnItsArm(String token, String expected) {
            // The two figurative constants are named rather than written, because a CSV cell holding
            // x'00' or a lone space is trimmed to the empty string before it reaches the test - and
            // CCUP-CHANGE-ACTION is never empty, it is one byte wide with VALUE LOW-VALUES (:276-277).
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

            // 'S' is the one arm whose outcome depends on more than the state byte: :972-977 advances to
            // 'N' only when neither INPUT-ERROR nor NO-CHANGES-DETECTED holds, and a task built by
            // taskWithValidKeys has INITIALIZE's blank input flags rather than INPUT-ERROR, so it
            // advances. The three-way split itself is asserted in DecideAction.
            String want = "S_TO_N".equals(expected) ? "N" : expected;
            assertThat(task.changeAction().value()).isEqualTo(want);
        }

        /**
         * {@code 10 CCUP-CHANGE-ACTION PIC X(1) VALUE LOW-VALUES} - {@code :276-277}.
         *
         * <p>The declared {@code VALUE} clause, asserted on a request that carries no commarea at all.
         * {@code x'00'} and not a space, not a Java {@code null} and not the empty string: the field is
         * always exactly one character, and on the very first call that character is binary zero. It
         * matters because {@code x'00'} is what makes the first {@code ENTER} take the shared
         * {@code :954}/{@code :958} body instead of abending at {@code :1019}.
         */
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

        /**
         * {@code :988-991} - the confirm arm performs {@code 9200-WRITE-PROCESSING}
         * <strong>once</strong>.
         *
         * <p>{@code PERFORM ... THRU} executes its range a single time; it is not a loop and it is not
         * conditional on anything inside {@code 9200}. A retry, or a second call from the inner
         * {@code EVALUATE} reading the outcome, would post a card update twice over - so the count is
         * asserted, not just the fact of the call.
         */
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

    // =================================================================================================

    @Nested
    @DisplayName("The message literals, byte for byte - :156-214 (G50)")
    class MessageLiterals {

        /**
         * The nineteen {@code WS-RETURN-MSG} condition names of {@code :173-214}, each at the
         * {@code X(75)} width {@code SET} would have produced.
         *
         * <p>Read left to right this is the copybook: the literal exactly as the source quotes it, and
         * the line it was quoted from. A {@code SET} on an alphanumeric item assigns the literal and
         * space-fills the remainder, so each constant must be the literal followed by spaces to
         * seventy-five - never trimmed, never truncated, never padded to some other width.
         *
         * @param actual  the transcribed constant
         * @param literal the literal as {@code app/cbl/COCRDUPC.cbl} quotes it
         */
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
            // 1210-EDIT-ACCOUNT, :177-178
            assertReturnMessage(CardUpdateController.WS_PROMPT_FOR_ACCT,
                    "Account number not provided");
            // 1220-EDIT-CARD, :179-180
            assertReturnMessage(CardUpdateController.WS_PROMPT_FOR_CARD, "Card number not provided");
            // 1230-EDIT-NAME, :181-184 - two messages, blank and non-alphabetic
            assertReturnMessage(CardUpdateController.WS_PROMPT_FOR_NAME, "Card name not provided");
            assertReturnMessage(CardUpdateController.WS_NAME_MUST_BE_ALPHA,
                    "Card name can only contain alphabets and spaces");
            // 1240-EDIT-CARDSTATUS, :195-196
            assertReturnMessage(CardUpdateController.CARD_STATUS_MUST_BE_YES_NO,
                    "Card Active Status must be Y or N");
            // 1250-EDIT-EXPIRY-MON, :197-198
            assertReturnMessage(CardUpdateController.CARD_EXPIRY_MONTH_NOT_VALID,
                    "Card expiry month must be between 1 and 12");
            // 1260-EDIT-EXPIRY-YEAR, :199-200
            assertReturnMessage(CardUpdateController.CARD_EXPIRY_YEAR_NOT_VALID,
                    "Invalid card expiry year");
        }

        @Test
        @DisplayName("the input, change and read messages - :185-188, :201-214")
        void theRemainingReturnMessages() {
            assertReturnMessage(CardUpdateController.NO_SEARCH_CRITERIA_RECEIVED,
                    "No input received");                                       // :185-186
            assertReturnMessage(CardUpdateController.NO_CHANGES_DETECTED,
                    "No change detected with respect to values fetched.");      // :187-188, tested :973
            assertReturnMessage(CardUpdateController.SEARCHED_CARD_NOT_NUMERIC,
                    "Card number if supplied must be a 16 digit number");       // :193-194
            assertReturnMessage(CardUpdateController.DID_NOT_FIND_ACCT_IN_CARDXREF,
                    "Did not find this account in cards database");             // :201-202
            assertReturnMessage(CardUpdateController.DID_NOT_FIND_ACCTCARD_COMBO,
                    "Did not find cards for this search condition");            // :203-204
            assertReturnMessage(CardUpdateController.XREF_READ_ERROR,
                    "Error reading Card Data File");                            // :211-212
            assertReturnMessage(CardUpdateController.CODING_TO_BE_DONE,
                    "Looks Good.... so far");                                   // :213-214, four dots
        }

        /**
         * {@code 88 WS-EXIT-MESSAGE VALUE 'PF03 pressed.Exiting              '} - {@code :175-176}.
         *
         * <p>Fourteen spaces sit <strong>inside the quotes</strong>, before the pad to {@code X(75)}
         * ever runs. They are invisible on a screen and invisible in a diff, and the only way to keep
         * them is to assert the literal's own length: thirty-four characters, of which twenty are text.
         * A transcription that trims the literal first would produce a constant that still passes a
         * {@code startsWith} check and still renders identically, so the length is asserted explicitly
         * before the padded form.
         */
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

        /**
         * The 88-aliasing trap: <strong>two condition names over one byte pattern</strong>.
         *
         * <p>{@code 88 SEARCHED-ACCT-ZEROES} ({@code :189-190}) and
         * {@code 88 SEARCHED-ACCT-NOT-NUMERIC} ({@code :191-192}) declare the
         * <em>byte-identical</em> literal {@code 'Account number must be a non zero 11 digit number'}.
         * That is legal COBOL and it is transcribed as written rather than collapsed, but it has a
         * consequence for testing that is easy to get wrong: <strong>the two conditions cannot be
         * distinguished by the message they produce.</strong> Testing "which condition fired" by reading
         * the message text would pass whichever one actually fired, so gate G50 is satisfied here by
         * driving each condition from its own <em>input</em> - an all-zeroes account for the first, a
         * non-numeric account for the second - and asserting that both inputs are rejected while the
         * text stays common to them.
         *
         * <p>A second thing is true of this pair in this program specifically, and it is why the
         * assertion below is about the constants rather than about a screen: {@code 1210-EDIT-ACCOUNT}
         * sets <em>neither</em> name. Its blank arm sets {@link CardUpdateController#WS_PROMPT_FOR_ACCT}
         * and its not-numeric arm moves a different, 52-character literal
         * ({@link CardUpdateController#ACCOUNT_FILTER_MUST_BE_11_DIGITS}). Both aliases are dead
         * declarations, preserved because a dead declaration is still behaviour (B5), and the live
         * message is asserted separately so the two can never be confused.
         */
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

        /**
         * The same pair, driven by <strong>input</strong> rather than by text - the half of gate G50 the
         * shared literal makes necessary.
         *
         * <p>An all-zeroes account is the {@code SEARCHED-ACCT-ZEROES} case; a non-numeric one is the
         * {@code SEARCHED-ACCT-NOT-NUMERIC} case. Both are rejected by {@code 1210-EDIT-ACCOUNT}, and
         * the assertion is on the <em>flag</em> ({@code FLG-ACCTFILTER-NOT-OK}) plus the input that
         * produced it, never on which of two identical strings came back.
         *
         * @param typed the eleven characters typed into {@code ACCTSID}
         */
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

        /**
         * {@code 05 WS-INFO-MSG PIC X(40)} and its seven condition names - {@code :157-171}.
         *
         * <p>Forty characters, not seventy-five: {@code WS-INFO-MSG} and {@code WS-RETURN-MSG} are
         * different fields with different widths, and a literal padded to the wrong one paints a screen
         * that is wrong from {@code INFOMSGO} onwards.
         *
         * @param actual  the transcribed constant
         * @param literal the literal as the source quotes it
         */
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
                    "Details of selected card shown above");                    // :160-161
            assertInfoMessage(CardUpdateController.PROMPT_FOR_SEARCH_KEYS,
                    "Please enter Account and Card Number");                    // :162-163
            assertInfoMessage(CardUpdateController.PROMPT_FOR_CHANGES,
                    "Update card details presented above.");                    // :164-165, full stop
            assertInfoMessage(CardUpdateController.PROMPT_FOR_CONFIRMATION,
                    "Changes validated.Press F5 to save");                      // :166-167, no space
            assertInfoMessage(CardUpdateController.CONFIRM_UPDATE_SUCCESS,
                    "Changes committed to database");                           // :168-169
            assertInfoMessage(CardUpdateController.INFORM_FAILURE,
                    "Changes unsuccessful. Please try again");                  // :170-171
        }

        /**
         * {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} - {@code :158-159}.
         *
         * <p>The seventh condition name, and the only one on this field with <strong>two</strong>
         * values. Both forms are forty characters and both satisfy the condition, so both are asserted:
         * a transcription that kept only {@code SPACES} would leave the freshly reset screen - which
         * {@code 3100:1053} fills with {@code LOW-VALUES} - failing a condition the source says it
         * satisfies.
         */
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

        /**
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} - {@code :174}.
         *
         * <p>Spaces, and not {@code LOW-VALUES}. {@code app/cpy/CVCRD01Y.cpy:29-30} declares a
         * similarly named {@code CCARD-RETURN-MSG-OFF} whose value <em>is</em> {@code LOW-VALUES}, and
         * this program never references it. Both states of the condition are driven, because it is the
         * guard behind every "first error wins" decision in the six edit paragraphs.
         */
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

    // =================================================================================================

    @Nested
    @DisplayName("The payload contract - 17 DFHMDF fields and nothing else (G9)")
    class PayloadContract {

        /**
         * Every payload field, its width, and the {@code app/cpy-bms/COCRDUP.CPY} line the width was
         * read from - all seventeen, in declaration order.
         *
         * <p>The count and the order are both contract. {@code app/bms/COCRDUP.bms} carries thirty-four
         * {@code DFHMDF} entries, of which seventeen are name-labelled and seventeen are unnamed
         * literals; only the labelled ones become payload members, which is why the number is 17 and
         * not 34. The widths come from the symbolic map's {@code xxxI} items rather than from the
         * {@code bms} {@code LENGTH=} operands, because the {@code xxxI} item is what the program's
         * {@code MOVE}s actually target.
         *
         * @param name         the {@code DFHMDF} label
         * @param width        the declared {@code xxxI} width
         * @param copybookLine the line of the {@code xxxI} declaration
         */
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

        /**
         * Seventeen, in that order, and no eighteenth.
         *
         * <p>{@code EXPDAY} is the row that makes this map unlike its neighbour: {@code COCRDSL} has no
         * expiry-day field at all, so a DTO cloned from the card-select screen would be one field short
         * and the shortfall would only show up as a missing day on a saved expiry date.
         */
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

        /**
         * The {@code FKEYSC} collision, asserted on the two lines that cause it.
         *
         * <p>{@code app/cpy-bms/COCRDUP.CPY:214} declares {@code 02 FKEYSC PICTURE X} - that is
         * <strong>{@code FKEYS}'s colour byte</strong>, one character wide. {@code :220} declares
         * {@code 02 FKEYSCC PICTURE X} - that is the colour byte of the <em>field</em> named
         * {@code FKEYSC}, whose payload item is {@code FKEYSCO PIC X(18)} at {@code :224}. So the same
         * eight characters mean two unrelated things four lines apart, and a mapper that reaches a
         * colour byte by appending {@code C} to a field name, or reaches a field by stripping a
         * trailing {@code C}, merges them and silently deletes an eighteen-byte field.
         *
         * <p>Both are asserted here: the two fields exist with their own widths, and the two colour
         * bytes are reached by <em>field name</em> so that neither can stand in for the other.
         */
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

        /**
         * The metadata items are <strong>not</strong> JSON members.
         *
         * <p>{@code app/cpy-bms/COCRDUP.CPY} declares seven items around every payload field: on the
         * input side {@code xxxL} ({@code COMP PIC S9(4)}, the length CICS reports), {@code xxxF} (the
         * flag byte) and {@code xxxA} (its {@code REDEFINES} attribute view); on the output side
         * {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} - colour, protection, highlight and
         * validation. They are validation and highlight metadata, addressable in Java and asserted
         * elsewhere in this class, and they must never reach the wire: seventeen fields times seven
         * items is 119 extra members that would swell the body and expose attribute bytes as if they
         * were data.
         *
         * <p>Asserted by serialising both directions and looking for every one of the 119.
         *
         * <p><strong>One name has to be excluded, and the reason is the collision itself.</strong>
         * Appending {@code C} to the field {@code FKEYS} spells {@code FKEYSC} - which is not
         * {@code FKEYS}'s colour item at all in Java, but the <em>name of the seventeenth field</em>,
         * whose payload member is legitimately on the wire. {@code FKEYS}'s colour item is the
         * {@code 02 FKEYSC PICTURE X} of {@code app/cpy-bms/COCRDUP.CPY:214}, reached here through
         * {@code attributesOf(FKEYS)} rather than by spelling, exactly so the two cannot be confused.
         * So the sweep skips any generated name that is itself a declared field, and the skip is
         * asserted rather than silent - a search that did not skip it would report the real
         * {@code FKEYSC} field as a leaked attribute byte and invite someone to delete it.
         */
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

        /**
         * {@code TITLE01O} and {@code TITLE02O} come from {@code COTTL01Y}, at {@code X(40)} each.
         *
         * <p>{@code 3100-SCREEN-INIT:1057-1058} moves {@code CCDA-TITLE01} and {@code CCDA-TITLE02}
         * into them, so the constants are the screen's, not this program's, and the width has to match
         * the map's forty or the second title would be truncated into the first field's span.
         */
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

        /**
         * The expiry projection: three separate two- and four-character fields over one ten-character
         * stored value, and the two separators never surface.
         *
         * <p>{@code CVACT02Y} stores {@code CARD-EXPIRAION-DATE} as {@code X(10)} in
         * {@code YYYY-MM-DD} form - <strong>the misspelling is the field's real name</strong> and is
         * preserved (&sect;0.8.9). {@code 9000-READ-DATA:1361-1366} splits it with COBOL reference
         * modification, whose subscripts are 1-based: {@code (1:4)}, {@code (6:2)} and {@code (9:2)},
         * which in Java are {@code substring(0,4)}, {@code substring(5,7)} and {@code substring(8,10)}.
         * Positions 5 and 8 - the two dashes - belong to no field, so an off-by-one in either direction
         * puts a dash on the screen or drops a digit.
         */
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

    // =================================================================================================

    @Nested
    @DisplayName("Statelessness, the commarea and navigation (G37, G40)")
    class Statelessness {

        /**
         * Gate G37, asserted structurally rather than by prose.
         *
         * <p>CICS is pseudo-conversational: the terminal holds the conversation and the program holds
         * nothing between tasks. The faithful translation is that the server holds nothing between
         * requests either - so there must be no {@code @SessionAttributes} on the class and no
         * {@code HttpSession} in any method signature. Both are checked by looking at the class, because
         * a comment saying "no session" is not a test and a session introduced by a later edit would
         * pass every other assertion in this file.
         */
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

        /**
         * The 329 bytes, and where each of them comes from.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:274-321} declares {@code 01 WS-THIS-PROGCOMMAREA} as
         * {@code CCUP-CHANGE-ACTION X(1)} + {@code CCUP-OLD-DETAILS} + {@code CCUP-NEW-DETAILS} +
         * {@code CARD-UPDATE-RECORD}, and the arithmetic is
         * <strong>1 + 89 + 89 + 150 = 329</strong>. Each 89 is
         * {@code 11 + 16 + 3 + 50 + (4 + 2 + 2) + 1}, whose parenthesised eight is the separator-free
         * {@code CCUP-{OLD,NEW}-EXPIRAION-DATE}; the 150 is
         * {@code 16 + 11 + 3 + 50 + 10 + 1 + FILLER X(59)}, and dropping that filler - declared at
         * {@code :321} and carrying no data - would leave the record 91 bytes wide and every offset
         * after it wrong.
         *
         * <p>Beside it travels {@code CARDDEMO-COMMAREA} at 160 bytes ({@code app/cpy/COCOM01Y.cpy}).
         * Both areas are request and response payload members; neither is held anywhere.
         */
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

        /**
         * Both areas are on the wire, in both directions.
         *
         * <p>If either were dropped from the payload the conversation could not continue, because there
         * is nowhere else for it to live. Asserted on the serialised body rather than on the object, so
         * that a {@code @JsonIgnore} added to either accessor fails here.
         */
        @Test
        @DisplayName("both the 329-byte trailer and the 160-byte commarea are serialised - G37")
        void bothAreasTravelInThePayload() throws Exception {
            when(repository.readByCardNumber(anyString())).thenReturn(normalRead());
            ObjectMapper mapper = new ObjectMapper();

            ResponseEntity<ScreenResponse<CardUpdateResponse>> answer = controller.updateCardDetail(
                    CARD_NUMBER,
                    typedRequest(reentered(), fetchedTrailer(ChangeAction.showDetails())),
                    null, null, null);

            JsonNode body = mapper.readTree(mapper.writeValueAsString(screenOf(answer)));
            assertThat(body.has("commArea"))
                    .as("WS-THIS-PROGCOMMAREA, all 329 bytes of it")
                    .isTrue();
            assertThat(body.has("navigationContext"))
                    .as("CARDDEMO-COMMAREA, all 160")
                    .isTrue();
            assertThat(body.get("commArea").has("changeAction"))
                    .as("including the one byte the whole state machine turns on")
                    .isTrue();
        }

        /**
         * Two identical requests produce two identical responses.
         *
         * <p>The definition of statelessness that can actually be tested: the response is a function of
         * the request alone. Nothing accumulates, nothing is remembered, and the second call cannot
         * observe that the first happened. The clock is fixed, so even the heading is identical
         * (practice B7).
         */
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

            assertThat(second)
                    .as("a second call must not be able to tell that a first one happened")
                    .isEqualTo(first);
        }

        /**
         * And the converse: <strong>the state machine cannot advance unless the client sends the
         * commarea back.</strong>
         *
         * <p>This is the assertion that proves there is no hidden store. The first call is given the
         * trailer in {@code CCUP-SHOW-DETAILS} and comes back one arm further on, at
         * {@code CCUP-CHANGES-OK-NOT-CONFIRMED} ({@code :976}). The second call is identical except
         * that the trailer is left at its initialised {@code LOW-VALUES} - and it goes back to the
         * beginning, because the server kept nothing. If any state were held anywhere, the second call
         * would resume where the first left off.
         */
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

        /**
         * Gate G40 - the {@code XCTL} becomes a field, not a redirect.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:473-474} is
         * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)}, preceded at
         * {@code :469-471} by {@code EXEC CICS SYNCPOINT} - the transfer commits first and does not come
         * back. An HTTP redirect would be the wrong shape twice over: it would put the next screen's
         * identity in a {@code Location} header where a client cannot read it as data, and it would make
         * the server decide the navigation. So the answer is {@code 200} with the target named in the
         * body, and the client chooses.
         */
        @Test
        @DisplayName("XCTL is a nextProgram field: 200, no redirect, no Location header - :473-474")
        void theTransferIsAFieldAndNotARedirect() {
            ResponseEntity<ScreenResponse<CardUpdateResponse>> answer = controller.updateCardDetail(
                    CARD_NUMBER,
                    request(ACCOUNT_NUMBER, CARD_NUMBER,
                            reentered().withFromProgram("").withFromTranid(""),
                            CommArea.initialised()),
                    // The AID travels as an UNSIGNED byte value: DFHPF3 is x'F3', so 243 and not the
                    // -13 a plain (int) cast of the signed byte would produce. The controller refuses
                    // anything outside 0-255 rather than narrowing it, because on this screen a
                    // misnarrowed AID could land on PF5 and save.
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

        /**
         * {@code PfKeyResolver} must agree with this program's inline {@code EIBAID} tests.
         *
         * <p>{@code COCRDUPC} copies {@code 'CSSTRPFY'} at {@code :1528}, so the shared resolver is
         * literally this program's own key mapping. Three properties of {@code CSSTRPFY} are asserted
         * because each is a way the translation could drift:
         *
         * <ul>
         *   <li>{@code DFHPF13} through {@code DFHPF24} <strong>fold</strong> onto {@code PFK01}
         *       through {@code PFK12} - the copybook writes all twenty-four arms out and the second
         *       twelve set the same condition names as the first twelve. Because the tokens are enum
         *       singletons the fold is observable by identity, not merely by value.</li>
         *   <li>There is <strong>no {@code WHEN OTHER}</strong>. An unrecognised byte sets nothing, so
         *       the resolver returns an empty {@link Optional} rather than substituting
         *       {@code ENTER}.</li>
         *   <li>There is <strong>no {@code DFHPA3} branch</strong>. The constant exists in
         *       {@link CicsAid} but {@code CSSTRPFY} never tests it, so PA3 is unrecognised.</li>
         * </ul>
         */
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

        /**
         * The three keys this program acts on, and the resolver's agreement with them.
         *
         * <p>{@code COCRDUPC} tests {@code DFHPF3} (exit, {@code :435}), {@code DFHPF5} (confirm the
         * save, {@code :989}) and {@code DFHPF12} (cancel back to the fetched values, {@code :958}).
         * Every other key is coerced to {@code ENTER} by {@code :422-424}, which {@code Dispatcher}
         * asserts; here the point is only that the resolver names the same three keys the inline tests
         * do.
         */
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
    @DisplayName("A value a RECEIVE MAP could not have delivered is the caller's error, not an abend")
    class ScreenInputRefusal {

        @Test
        @DisplayName("An unrepresentable character is refused rather than routed to ABEND-ROUTINE by "
                + "the HANDLE ABEND declarative at :370-372")
        void anUnrepresentableCharacterIsNotAnAbend() {
            CardUpdateRequest received =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), CommArea.initialised());
            received.setCrdname("JOS\u00C9 MU\u00D1OZ");

            assertThatThrownBy(() -> controller.handle(received, 0, CicsAid.DFHENTER))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .isNotInstanceOf(AbendException.class);
        }

        @Test
        @DisplayName("It names the offending field and its xxxI item, and echoes no value")
        void itNamesTheFieldAndNotTheValue() {
            CardUpdateRequest received =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), CommArea.initialised());
            received.setCrdname("JOS\u00C9");

            assertThatThrownBy(() -> controller.handle(received, 0, CicsAid.DFHENTER))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .satisfies(thrown -> {
                        ScreenInputRejectedException rejected = (ScreenInputRejectedException) thrown;
                        assertThat(rejected.member()).contains("CRDNAME");
                        assertThat(rejected.getMessage()).contains("CRDNAMEI").contains("U+00C9");
                    });
        }

        @Test
        @DisplayName("The sweep runs before the repository is touched, so nothing partial is written")
        void theSweepPrecedesEveryDatasetAccess() {
            CardUpdateRequest received =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), fetchedTrailer(ChangeAction.showDetails()));
            received.setCrdname("JOS\u00C9");

            assertThatThrownBy(() -> controller.handle(received, 0, CicsAid.DFHENTER))
                    .isInstanceOf(ScreenInputRejectedException.class);

            verifyNoInteractions(repository, service);
        }

        @Test
        @DisplayName("A representable payload passes the sweep untouched")
        void aRepresentablePayloadIsUntouched() {
            CardUpdateRequest received =
                    request(ACCOUNT_NUMBER, CARD_NUMBER, reentered(), CommArea.initialised());
            received.setCrdname("JOHN Q PUBLIC");

            assertThat(controller.handle(received, 0, CicsAid.DFHENTER)).isNotNull();
        }
    }

    @Nested
    @DisplayName("requireFetchedKey - a commarea the program itself could not have written")
    class CommareaConsistency {

        @Test
        @DisplayName("A blank CCUP-OLD-ACCTID with a processing action is refused as the caller's "
                + "error: the only writer of that member is :1006-1007, which writes eleven digits")
        void aBlankFetchedAccountKeyIsRefused() {
            assertThatThrownBy(() -> CardUpdateController.requireFetchedKey(
                    "           ", CardDetails.ACCTID_LENGTH, "commArea.oldDetails.acctid",
                    "the eleven digits of the fetched account identifier"))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .isNotInstanceOf(AbendException.class)
                    .satisfies(thrown -> assertThat(((ScreenInputRejectedException) thrown).member())
                            .contains("commArea.oldDetails.acctid"));
        }

        @Test
        @DisplayName("An absent value is refused the same way, because a PIC 9 receiver cannot hold it "
                + "either")
        void anAbsentFetchedKeyIsRefused() {
            assertThatThrownBy(() -> CardUpdateController.requireFetchedKey(
                    null, CardDetails.ACCTID_LENGTH, "commArea.oldDetails.acctid", "eleven digits"))
                    .isInstanceOf(ScreenInputRejectedException.class);
        }

        @Test
        @DisplayName("A non-digit anywhere in the span is refused, at the front, the middle and the end")
        void aNonDigitAnywhereIsRefused() {
            for (String value : new String[] {"A0000000001", "00000A00001", "0000000000A"}) {
                assertThatThrownBy(() -> CardUpdateController.requireFetchedKey(
                        value, CardDetails.ACCTID_LENGTH, "commArea.oldDetails.acctid", "digits"))
                        .as("value %s", value)
                        .isInstanceOf(ScreenInputRejectedException.class);
            }
        }

        @Test
        @DisplayName("The digits a real fetch leaves behind pass, so the guard costs a genuine "
                + "conversation nothing")
        void theDigitsARealFetchLeavesBehindPass() {
            CardUpdateController.requireFetchedKey(ACCOUNT_NUMBER, CardDetails.ACCTID_LENGTH,
                    "commArea.oldDetails.acctid", "eleven digits");
            CardUpdateController.requireFetchedKey(CARD_NUMBER, CardDetails.CARDID_LENGTH,
                    "commArea.oldDetails.cardid", "sixteen digits");
        }

        @Test
        @DisplayName("A short value is zero-... no: a short value is space-padded by the PIC X move and "
                + "so is refused, because eleven digits is what the member holds")
        void aShortValueIsRefusedBecausePicXPadsWithSpaces() {
            assertThatThrownBy(() -> CardUpdateController.requireFetchedKey(
                    "11", CardDetails.ACCTID_LENGTH, "commArea.oldDetails.acctid", "eleven digits"))
                    .isInstanceOf(ScreenInputRejectedException.class);
        }

        @Test
        @DisplayName("Driven through 1200-EDIT-MAP-INPUTS' card-data path: a blank snapshot answers the "
                + "caller's refusal rather than the 500 the abend handler would otherwise produce")
        void drivenThroughEditMapInputsItIsARefusalNotAnAbend() {
            // The card-data path at :668-714, reached because the state is not CCUP-DETAILS-NOT-FETCHED,
            // is where :671-672 moves the two snapshot keys into the PIC 9 commarea items. The snapshot
            // here is CommArea.initialised()'s - blank - which is the state a hand-built payload asking
            // for a processing action without having fetched arrives in.
            Conversation task = taskWithValidKeys(ChangeAction.showDetails());
            task.setOldDetails(CardDetails.initialised(DetailGroup.OLD));

            assertThatThrownBy(() -> controller.editMapInputs1200(task))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .isNotInstanceOf(AbendException.class)
                    .satisfies(thrown -> assertThat(((ScreenInputRejectedException) thrown).member())
                            .contains("commArea.oldDetails.acctid"));
        }

        @Test
        @DisplayName("The same path with the digits a real fetch left behind proceeds normally, so the "
                + "guard changes nothing for a genuine conversation")
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
