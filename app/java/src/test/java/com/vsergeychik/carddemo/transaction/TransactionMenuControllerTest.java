package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.TransactionMenuController.FileArm;
import com.vsergeychik.carddemo.transaction.TransactionMenuController.Ridfld;
import com.vsergeychik.carddemo.transaction.TransactionMenuController.RidfldKind;
import com.vsergeychik.carddemo.transaction.TransactionMenuController.TransactBrowse;
import com.vsergeychik.carddemo.transaction.TransactionMenuController.WorkArea;
import com.vsergeychik.carddemo.transaction.TransactionRepository.Browse;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.PaginationCursor;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.TransactionListCursor;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TransactionMenuController} against {@code app/cbl/COTRN00C.cbl}.
 *
 * <p>The controller carries the {@code CT00} transaction, so this suite carries the gates asserted
 * against it: <strong>G39</strong> (the page size is ten and is not configurable),
 * <strong>G37</strong> (no server-side conversation state), <strong>G38</strong> (both the enter and
 * the re-enter path, and the attribute reset that belongs to the enter path only),
 * <strong>G30</strong>/<strong>G50</strong> (every {@code EVALUATE} arm including
 * {@code WHEN OTHER}, and both truth states of the {@code 88}-levels), <strong>G40</strong> (every
 * transfer resolves to a response field), <strong>G46</strong> (no dataset literal),
 * <strong>G47</strong> (every file outcome at every call site), <strong>G51</strong> (no HTTP in any
 * decision path) and <strong>G52</strong>/<strong>G53</strong> (no wildcard import, no mutable
 * static).
 *
 * <p>No project rules exist - {@code review_rules} returns "No user rules provided" - so AAP 0.10.2's
 * substitutes bind, and the ones this suite pins are B4 (the documented name divergence and the two
 * documented deviations are asserted, not merely described), B5 (the two commented-out statements and
 * the one dead {@code WORKING-STORAGE} item are pinned so they cannot be "fixed"), B8 (every edited
 * rendering is asserted byte for byte) and B10/G51 (the decision path is driven with no
 * {@code MockMvc} in it; the one {@code MockMvc} test covers the adapter alone).
 *
 * <p>Expectations are statically derived from the source rather than captured from a COBOL run, which
 * cannot be executed in this environment - risk R-A, practice B12.
 */
@DisplayName("TransactionMenuController - COTRN00C, transaction CT00, GET /api/transactions")
final class TransactionMenuControllerTest {

    /** The dataset code page, always named rather than defaulted. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** A fixed instant so the two heading fields are assertable. 2022-07-19 is the source's own date. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** The file name every stubbed outcome is tagged with. */
    private static final String FILE = TransactionRepository.CICS_FILE_NAME;

    /** {@code EIBAID} as the unsigned integer the query parameter carries. */
    private static final int ENTER_PARAM = CicsAid.DFHENTER & 0xFF;

    private TransactionRepository repository;
    private Browse browse;
    private TransactionMenuController controller;

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRepository.class);
        browse = positionedBrowse();
        when(repository.startBrowse(any(BrowseDirection.class))).thenReturn(browse);
        when(repository.startBrowse(anyString(), any(BrowseDirection.class))).thenReturn(browse);
        controller = new TransactionMenuController(repository, CODEC, CLOCK);
    }

    // =============================================================================================
    // Fixtures. Transaction n has identifier n zero-filled to sixteen, so a row's identity is
    // readable straight out of an assertion.
    // =============================================================================================

    private static String idOf(int n) {
        return CODEC.movePic9(n, TranRecord.TRAN_ID_LENGTH);
    }

    private static TranRecord tran(int n) {
        TranRecord record = new TranRecord(StandardCharsets.US_ASCII);
        record.moveTranId(idOf(n));
        record.moveTranTypeCd("01");
        record.moveTranCatCd(1);
        record.moveTranSource("POS");
        record.moveTranDesc("TRANSACTION " + n);
        record.moveTranAmt(new BigDecimal(n + ".01"));
        record.moveTranMerchantId(1L);
        record.moveTranMerchantName("MERCHANT");
        record.moveTranMerchantCity("CITY");
        record.moveTranMerchantZip("00000");
        record.moveTranCardNum(CODEC.movePic9(n, TranRecord.TRAN_CARD_NUM_LENGTH));
        record.moveTranOrigTs("2022-07-19 10:20:30.000000");
        record.moveTranProcTs("2022-07-20 10:20:30.000000");
        return record;
    }

    private static ReadResult found(int n) {
        return ReadResult.found(FILE, tran(n));
    }

    private static ReadResult endOfFile() {
        return ReadResult.endOfFile(FILE);
    }

    private static ReadResult failure() {
        return ReadResult.other(FILE, TransactionRepository.PERMANENT_ERROR_STATUS);
    }

    /**
     * Stubs the forward browse to return these transactions in order and then report end of file for
     * every read after them. The first of them is consumed by the controller's positioning probe and
     * handed back as the first real read, so the numbers here are the records the page will show.
     */
    private void forward(int... ids) {
        stub(true, results(ids));
    }

    /** The same for the backward browse. */
    private void backward(int... ids) {
        stub(false, results(ids));
    }

    private static ReadResult[] results(int... ids) {
        ReadResult[] all = new ReadResult[ids.length + 1];
        for (int index = 0; index < ids.length; index++) {
            all[index] = found(ids[index]);
        }
        all[ids.length] = endOfFile();
        return all;
    }

    private void stub(boolean next, ReadResult... all) {
        ReadResult[] rest = Arrays.copyOfRange(all, 1, all.length);
        if (next) {
            when(browse.readNext()).thenReturn(all[0], rest);
        } else {
            when(browse.readPrev()).thenReturn(all[0], rest);
        }
        stubPositioningFrom(all[0]);
    }

    /**
     * Stubs the {@code STARTBR} outcome to agree with what the browse's first read finds.
     *
     * <p>{@code STARTBR ... GTEQ} reports {@code NORMAL} exactly when a record exists at or beyond the
     * {@code RIDFLD}, which is exactly when the first {@code READNEXT} or {@code READPREV} of that browse
     * returns one - so a mock whose reads and whose positioning disagreed would describe a state CICS
     * cannot produce. {@code NOTFND} is the condition a {@code STARTBR} raises for an exhausted position;
     * an exhausted <em>read</em> is {@code ENDFILE}, and the repository restates it as {@code NOTFND} on
     * the positioning side, which this mirrors.
     *
     * <p>A record-returning read is mirrored as a plain {@code found} position rather than as itself,
     * because {@code STARTBR} establishes a position without transferring a record: it cannot raise
     * {@code DUPKEY}, which arises on the read that returns a row whose alternate key duplicates the
     * next one. Echoing a duplicate read into the position would describe a {@code STARTBR} condition
     * CICS never raises, and would stop the paragraph before the read that the duplicate belongs to.
     *
     * @param first what the first read of this browse reports
     */
    private void stubPositioningFrom(ReadResult first) {
        ReadResult positioning;
        if (first.isRecordReturned()) {
            positioning = ReadResult.found(first.ddName(), first.record().orElseThrow());
        } else if (first.isEndOfFile()) {
            positioning = ReadResult.notFound(TransactionRepository.CICS_FILE_NAME);
        } else {
            positioning = first;
        }
        when(browse.positioningResult()).thenReturn(positioning);
        when(browse.positioningOutcome()).thenReturn(positioning.outcome());
        when(browse.isStarted()).thenReturn(positioning.isRecordReturned());
    }

    /** A request carrying a communication area in the given context - that is, {@code EIBCALEN} non-zero. */
    private static TransactionListRequest request(boolean reenter) {
        TransactionListRequest request = new TransactionListRequest();
        request.setNavigationContext(reenter
                ? NavigationContext.empty().withPgmReenter()
                : NavigationContext.empty().withPgmEnter());
        return request;
    }

    private static List<String> rowIds(TransactionListResponse response) {
        List<String> ids = new ArrayList<>();
        for (int row = TransactionListResponse.FIRST_ROW;
                row <= TransactionListResponse.LAST_ROW; row++) {
            ids.add(response.getRowTransactionId(row));
        }
        return ids;
    }

    // =============================================================================================
    // Construction and the contract guards.
    // =============================================================================================

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("the wiring constructor builds the codec from the named code page")
        void wiringConstructor() {
            TransactionMenuController wired =
                    new TransactionMenuController(repository, StandardCharsets.US_ASCII, CLOCK);
            assertThat(wired.listTransactions(null, CicsAid.DFHENTER).getNextProgram().strip())
                    .isEqualTo(TransactionMenuController.LIT_SIGNON_PGM);
        }

        @ParameterizedTest(name = "argument {0} is required")
        @ValueSource(strings = {"repository", "codec", "clock"})
        @DisplayName("every collaborator is required, because none of them has a safe default")
        void collaboratorsRequired(String absent) {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionMenuController(
                            "repository".equals(absent) ? null : repository,
                            "codec".equals(absent) ? null : CODEC,
                            "clock".equals(absent) ? null : CLOCK));
        }

        @Test
        @DisplayName("the charset overload rejects an absent code page rather than defaulting it")
        void charsetRequired() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionMenuController(repository, (java.nio.charset.Charset) null, CLOCK));
        }

        @Test
        @DisplayName("the screen contract holds: every width and literal agrees with its authority")
        void screenContractHolds() {
            TransactionMenuController.verifyScreenContract();
        }

        @Test
        @DisplayName("a drifted width fails loudly, and the guard says which one")
        void driftedWidthFails() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionMenuController.requireAgreement(10, 7, "the page size"))
                    .withMessageContaining("the page size");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionMenuController.requireAgreement("CT00", "CT01", "the id"))
                    .withMessageContaining("the id");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionMenuController.requireOrdering(90, 80, "the widths"))
                    .withMessageContaining("the widths");
        }

        @Test
        @DisplayName("an agreeing width and an ordered pair both pass silently")
        void agreementPasses() {
            TransactionMenuController.requireAgreement(10, 10, "the page size");
            TransactionMenuController.requireAgreement("CT00", "CT00", "the id");
            TransactionMenuController.requireOrdering(50, 80, "the widths");
        }

        @Test
        @DisplayName("no field of the controller is mutable static (gate G53)")
        void noMutableStatic() {
            for (Field field : TransactionMenuController.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("%s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    // =============================================================================================
    // MAIN-PARA, :95-141 - the EIBCALEN test and the EVALUATE EIBAID dispatch (gates G30, G38, G40).
    // =============================================================================================

    @Nested
    @DisplayName("MAIN-PARA - the dispatch")
    class MainPara {

        @Test
        @DisplayName("EIBCALEN = 0 returns to COSGN00C with the context reset to ENTER (:107-109)")
        void coldStartReturnsToSignOn() {
            WorkArea ws = new WorkArea();
            TransactionListResponse response = controller.listTransactions(null, CicsAid.DFHENTER, ws);

            assertThat(ws.eibcalen()).isZero();
            assertThat(ws.isTransferred()).isTrue();
            assertThat(response.getNextProgram().strip())
                    .isEqualTo(TransactionMenuController.LIT_SIGNON_PGM);
            // XCTL states PROGRAM and COMMAREA only, and COTRN00C never writes CDEMO-LAST-MAP or
            // CDEMO-LAST-MAPSET, so the target is handed neither and picks its own.
            assertThat(response.getNextMapset()).isBlank();
            assertThat(response.getNextMap()).isBlank();
            assertThat(response.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(response.getNavigationContext().fromTranid().strip())
                    .isEqualTo(TransactionMenuController.LIT_THIS_TRANID);
            assertThat(response.getNavigationContext().fromProgram().strip())
                    .isEqualTo(TransactionMenuController.LIT_THIS_PGM);
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
        }

        @Test
        @DisplayName("an empty request body is EIBCALEN = 0 too, and takes the same path")
        void emptyBodyIsColdStart() {
            TransactionListResponse response =
                    controller.listTransactions(new TransactionListRequest(), CicsAid.DFHENTER);
            assertThat(response.getNextProgram().strip())
                    .isEqualTo(TransactionMenuController.LIT_SIGNON_PGM);
        }

        @Test
        @DisplayName("the ENTER path paints a page, sets REENTER and sends twice (:112-116, gate G38)")
        void enterPathPaintsAndSendsTwice() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(false), CicsAid.DFHENTER, ws);

            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(rowIds(response)).containsExactly(idOf(1), idOf(2), idOf(3), idOf(4), idOf(5),
                    idOf(6), idOf(7), idOf(8), idOf(9), idOf(10));
            // :326 sends inside PROCESS-PAGE-FORWARD and :116 sends again in MAIN-PARA.
            assertThat(ws.sendCount()).isEqualTo(2);
            assertThat(ws.isTransferred()).isFalse();
        }

        @Test
        @DisplayName("the REENTER path with ENTER sends once, because :121 has no send after it")
        void reenterEnterSendsOnce() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            WorkArea ws = new WorkArea();

            controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(ws.sendCount()).isEqualTo(1);
            assertThat(ws.receiveOutcome()).isEqualTo(Outcome.OK);
        }

        @Test
        @DisplayName("PF3 transfers to COMEN01C (:122-124, gate G40)")
        void pf3TransfersToMenu() {
            WorkArea ws = new WorkArea();
            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHPF3, ws);

            assertThat(response.getNextProgram().strip())
                    .isEqualTo(TransactionMenuController.LIT_MENU_PGM);
            assertThat(ws.isTransferred()).isTrue();
            assertThat(ws.sendCount()).isZero();
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
        }

        @ParameterizedTest(name = "EIBAID {0} is not a key this screen handles")
        @CsvSource({"DFHPF1", "DFHPF4", "DFHPF5", "DFHPF12", "DFHCLEAR", "DFHPA1", "DFHNULL"})
        @DisplayName("WHEN OTHER reports the invalid-key message and sets the error flag (:129-133)")
        void unhandledKeyIsInvalid(String constant) throws ReflectiveOperationException {
            byte aid = (byte) CicsAid.class.getField(constant).get(null);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), aid, ws);

            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.message()).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(response.getErrmsgO())
                    .isEqualTo(CODEC.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                            TransactionListResponse.ERRMSG_LENGTH));
            assertThat(ws.cursorField()).isEqualTo(TransactionListRequest.TRNIDIN_FIELD);
            assertThat(ws.sendCount()).isEqualTo(1);
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
        }

        @Test
        @DisplayName("PF15 is not PF3: the AID tests are exact-byte, so it lands on WHEN OTHER")
        void pf15IsNotPf3() {
            WorkArea ws = new WorkArea();
            controller.listTransactions(request(true), CicsAid.DFHPF15, ws);
            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15)).contains(AidKey.PFK03);
        }

        @Test
        @DisplayName("the heading is filled from the injected clock on every send (:567-586)")
        void headingIsFilledFromTheClock() {
            forward(1);
            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER);

            DateHeader expected = DateHeader.from(CODEC, CLOCK);
            assertThat(response.getTitle01O()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getTitle02O()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.getTrnnameO()).isEqualTo(TransactionMenuController.LIT_THIS_TRANID);
            assertThat(response.getPgmnameO()).isEqualTo(TransactionMenuController.LIT_THIS_PGM);
            assertThat(response.getCurdateO()).isEqualTo(expected.wsCurdateMmDdYy());
            assertThat(response.getCurtimeO()).isEqualTo(expected.wsCurtimeHhMmSs());
            // ScreenTitles.CCDA_THANK_YOU is X(40) and SystemMessages.CCDA_MSG_THANK_YOU is X(50):
            // different literals, never interchangeable, and neither belongs on this screen.
            assertThat(ScreenTitles.CCDA_THANK_YOU).isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
        }
    }

    // =============================================================================================
    // PROCESS-ENTER-KEY, :146-229 - the ordered selection EVALUATE and the browse-key edit.
    // =============================================================================================

    @Nested
    @DisplayName("PROCESS-ENTER-KEY")
    class ProcessEnterKey {

        private TransactionListRequest selected(int row, String flag) {
            TransactionListRequest request = request(true);
            request.setSelection(row, flag);
            request.setTransactionId(row, idOf(row));
            return request;
        }

        @ParameterizedTest(name = "row {0} selected with S transfers to COTRN01C")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("every one of the ten selection arms is reachable (:148-178, gate G50)")
        void everyRowCanBeSelected(int row) {
            WorkArea ws = new WorkArea();
            TransactionListResponse response =
                    controller.listTransactions(selected(row, "S"), CicsAid.DFHENTER, ws);

            assertThat(ws.selectedRow()).isEqualTo(row);
            assertThat(response.getNextProgram().strip())
                    .isEqualTo(TransactionMenuController.LIT_TRAN_VIEW_PGM);
            assertThat(response.getCursor().getTrnSelected()).isEqualTo(idOf(row));
            assertThat(response.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(ws.isTransferred()).isTrue();
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
        }

        @ParameterizedTest(name = "selection {0} is accepted")
        @ValueSource(strings = {"S", "s"})
        @DisplayName("'S' and 's' share one body and both navigate (:186-195)")
        void bothCasesOfSAreAccepted(String flag) {
            TransactionListResponse response =
                    controller.listTransactions(selected(4, flag), CicsAid.DFHENTER);
            assertThat(response.getNextProgram().strip())
                    .isEqualTo(TransactionMenuController.LIT_TRAN_VIEW_PGM);
            assertThat(response.getNavigationContext().fromTranid().strip())
                    .isEqualTo(TransactionMenuController.LIT_THIS_TRANID);
            assertThat(response.getNavigationContext().fromProgram().strip())
                    .isEqualTo(TransactionMenuController.LIT_THIS_PGM);
        }

        @ParameterizedTest(name = "selection {0} is rejected")
        @ValueSource(strings = {"X", "V", "1", "z"})
        @DisplayName("WHEN OTHER sets the byte-exact message and does NOT re-send (:196-202, B5)")
        void invalidSelectionDoesNotResend(String flag) {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(selected(1, flag), CicsAid.DFHENTER, ws);

            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_INVALID_SELECTION,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            assertThat(response.getErrmsgO())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_INVALID_SELECTION,
                            TransactionListResponse.ERRMSG_LENGTH));
            // The commented-out PERFORM SEND-TRNLST-SCREEN at :202 stays commented out: the only send
            // is the one PROCESS-PAGE-FORWARD performs at :326.
            assertThat(ws.sendCount()).isEqualTo(1);
            // The commented-out SET TRANSACT-EOF at :197 stays commented out, so the browse still ran.
            assertThat(ws.isTransactEof()).isFalse();
            assertThat(rowIds(response)).element(0).isEqualTo(idOf(1));
            assertThat(ws.isTransferred()).isFalse();
        }

        @Test
        @DisplayName("with several rows filled the FIRST wins, which is EVALUATE TRUE's order")
        void firstFilledRowWins() {
            TransactionListRequest request = request(true);
            request.setSelection(3, "S");
            request.setTransactionId(3, idOf(3));
            request.setSelection(7, "S");
            request.setTransactionId(7, idOf(7));
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request, CicsAid.DFHENTER, ws);

            assertThat(ws.selectedRow()).isEqualTo(3);
            assertThat(response.getCursor().getTrnSelected()).isEqualTo(idOf(3));
        }

        @Test
        @DisplayName("an earlier invalid row beats a later valid one, so no transfer happens")
        void earlierInvalidRowBeatsLaterValidRow() {
            forward(1);
            TransactionListRequest request = request(true);
            request.setSelection(2, "X");
            request.setTransactionId(2, idOf(2));
            request.setSelection(8, "S");
            request.setTransactionId(8, idOf(8));
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHENTER, ws);

            assertThat(ws.selectedRow()).isEqualTo(2);
            assertThat(ws.isTransferred()).isFalse();
        }

        @Test
        @DisplayName("no cell filled is WHEN OTHER: both cursor fields are cleared (:179-181)")
        void noSelectionClearsBothFields() {
            forward(1);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(0, idOf(5), idOf(9), false, "S", idOf(5)));
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request, CicsAid.DFHENTER, ws);

            assertThat(response.getCursor().getTrnSelFlg()).isEqualTo(" ");
            assertThat(response.getCursor().getTrnSelected()).isEqualTo(CODEC.movePicX("",
                    TransactionListCursor.TRN_SELECTED_LENGTH));
            assertThat(ws.selectedRow()).isZero();
        }

        @Test
        @DisplayName("a flag without an identifier is not acted on (:183-184)")
        void flagWithoutIdentifierIsIgnored() {
            forward(1);
            TransactionListRequest request = request(true);
            request.setSelection(1, "S");
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHENTER, ws);

            // The :183-184 guard needs BOTH halves, so neither arm of the inner EVALUATE runs: there
            // is no transfer and no invalid-selection message. The paging message that does appear
            // comes from the short one-record page, not from the selection.
            assertThat(ws.isTransferred()).isFalse();
            assertThat(ws.message())
                    .doesNotContain(TransactionMenuController.MSG_INVALID_SELECTION);
            assertThat(ws.cursor().getTrnSelFlg()).isEqualTo("S");
            assertThat(ws.cursor().getTrnSelected())
                    .isEqualTo(CODEC.movePicX("", TransactionListCursor.TRN_SELECTED_LENGTH));
        }

        @Test
        @DisplayName("a blank Tran ID browses from the start of the file (:206-207)")
        void blankTranIdBrowsesFromTheStart() {
            forward(1, 2);
            WorkArea ws = new WorkArea();

            controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(ws.ridfld().kind()).isEqualTo(RidfldKind.LOW_VALUES);
            verify(repository).startBrowse(BrowseDirection.FORWARD);
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
        }

        @Test
        @DisplayName("a sixteen-digit Tran ID becomes the browse key (:209-210)")
        void numericTranIdBecomesTheKey() {
            forward(7, 8);
            TransactionListRequest request = request(true);
            request.setTrnidin(idOf(7));
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHENTER, ws);

            assertThat(ws.ridfld().kind()).isEqualTo(RidfldKind.KEY);
            assertThat(ws.ridfld().key()).isEqualTo(idOf(7));
            verify(repository).startBrowse(idOf(7), BrowseDirection.FORWARD);
        }

        @ParameterizedTest(name = "\"{0}\" is not numeric")
        @ValueSource(strings = {"12", "0000000000000 1", "ABCDEFGHIJKLMNOP", "000000000000001-",
                "123456789012345 "})
        @DisplayName("anything else is rejected with the byte-exact message (:211-218)")
        void nonNumericTranIdIsRejected(String typed) {
            forward(1, 2, 3);
            TransactionListRequest request = request(true);
            request.setTrnidin(typed);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request, CicsAid.DFHENTER, ws);

            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_TRAN_ID_NOT_NUMERIC,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            assertThat(response.getErrmsgO()).startsWith("Tran ID must be Numeric ...");
            // :281 performs the STARTBR before the :283 guard, so the browse IS positioned even here.
            verify(repository).startBrowse(BrowseDirection.FORWARD);
            // ...and the guard then stops the paging, so only the error send happened.
            assertThat(ws.sendCount()).isEqualTo(1);
            // :227-229 is guarded by NOT ERR-FLG-ON, so the typed key survives on the screen.
            assertThat(response.getTrnidinO())
                    .isEqualTo(CODEC.movePicX(typed, TransactionListResponse.TRNIDIN_LENGTH));
        }

        @Test
        @DisplayName("an accepted key is cleared once the page is painted (:227-229 and :325)")
        void acceptedKeyIsClearedAfterPainting() {
            forward(1, 2);
            TransactionListRequest request = request(true);
            request.setTrnidin(idOf(1));

            TransactionListResponse response =
                    controller.listTransactions(request, CicsAid.DFHENTER);

            assertThat(response.getTrnidinO())
                    .isEqualTo(CODEC.movePicX("", TransactionListResponse.TRNIDIN_LENGTH));
        }

        @Test
        @DisplayName("an ENTER always restarts the page count at zero (:224)")
        void enterRestartsThePageCount() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(9, idOf(81), idOf(90), true, " ", ""));

            TransactionListResponse response =
                    controller.listTransactions(request, CicsAid.DFHENTER);

            assertThat(response.getCursor().getPageNum()).isEqualTo(1);
            assertThat(response.getPagenumO()).isEqualTo("00000001");
        }
    }

    /** Builds an inbound {@code CDEMO-CT00-INFO}. */
    private static TransactionListRequest.PaginationCursor cursor(int pageNum, String first,
                                                                 String last, boolean nextPage,
                                                                 String selFlag, String selected) {
        TransactionListRequest.PaginationCursor inbound = new TransactionListRequest.PaginationCursor();
        inbound.setPageNum(pageNum);
        inbound.setTrnidFirst(first);
        inbound.setTrnidLast(last);
        if (nextPage) {
            inbound.setNextPageYes();
        } else {
            inbound.setNextPageNo();
        }
        inbound.setTrnSelFlg(selFlag);
        inbound.setTrnSelected(selected);
        return inbound;
    }

    /**
     * Sets one {@code WORKING-STORAGE} item directly.
     *
     * <p>Needed for exactly two guards, and only because those two guards are dead in the COBOL as
     * well: {@code PROCESS-PAGE-BACKWARD} is reached from {@code PROCESS-PF7-KEY} alone, so its
     * {@code IF EIBAID NOT = DFHENTER AND DFHPF8} at {@code :339} is always true and its
     * {@code IF NEXT-PAGE-YES} at {@code :361} is always true, because {@code :242} has just set it.
     * Practice B5 keeps both, and this is how the other side of each is driven.
     */
    private static void set(WorkArea ws, String name, Object value) {
        try {
            Field field = WorkArea.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(ws, value);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException("WorkArea." + name + " must exist", unreachable);
        }
    }

    // =============================================================================================
    // PROCESS-PAGE-FORWARD and PROCESS-PAGE-BACKWARD, :279-376 - the page size (gate G39).
    // =============================================================================================

    @Nested
    @DisplayName("Paging")
    class Paging {

        @Test
        @DisplayName("a page is exactly ten rows, and the eleventh record is not displayed (G39)")
        void aPageIsExactlyTenRows() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(rowIds(response)).hasSize(10).doesNotContain(idOf(11), idOf(12));
            assertThat(ws.idx()).isEqualTo(11);
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo(idOf(1));
            assertThat(response.getCursor().getTrnidLast()).isEqualTo(idOf(10));
            assertThat(response.getCursor().isNextPageYes()).isTrue();
            assertThat(response.getPagenumO()).isEqualTo("00000001");
            // Ten fill reads plus one look-ahead; the positioning probe is not counted.
            assertThat(ws.readCount()).isEqualTo(11);
        }

        @Test
        @DisplayName("the look-ahead read reporting end of file sets NEXT-PAGE-NO (:309-313)")
        void lookAheadEndOfFileSetsNoNextPage() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(rowIds(response)).hasSize(10).last().isEqualTo(idOf(10));
            assertThat(response.getCursor().isNextPageNo()).isTrue();
            assertThat(response.getCursor().getPageNum()).isEqualTo(1);
            assertThat(ws.isTransactEof()).isTrue();
            assertThat(ws.message()).startsWith(TransactionMenuController.MSG_REACHED_BOTTOM_OF_PAGE);
        }

        @Test
        @DisplayName("a short page fills what it can, counts the page and stops (:314-319)")
        void shortPageStillCountsAsAPage() {
            forward(1, 2, 3);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(rowIds(response)).containsExactly(idOf(1), idOf(2), idOf(3),
                    blank16(), blank16(), blank16(), blank16(), blank16(), blank16(), blank16());
            assertThat(response.getCursor().getPageNum()).isEqualTo(1);
            assertThat(response.getCursor().isNextPageNo()).isTrue();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_REACHED_BOTTOM_OF_PAGE,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("an empty file is the STARTBR NOTFND arm, and the page count stays at zero")
        void emptyFileIsTheNotFoundArm() {
            forward();
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(ws.startbrOutcome())
                    .as(":602-619 has no DFHRESP(ENDFILE) arm - an exhausted STARTBR is NOTFND")
                    .isEqualTo(Outcome.NOT_FOUND);
            assertThat(ws.isTransactEof()).isTrue();
            assertThat(ws.isErrFlgOn()).as("NOTFND does not set WS-ERR-FLG").isFalse();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_AT_TOP_OF_PAGE,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            assertThat(response.getCursor().getPageNum()).isZero();
            assertThat(response.getPagenumO()).isEqualTo("00000000");
            assertThat(response.getCursor().isNextPageNo()).isTrue();
            assertThat(ws.idx()).isEqualTo(1);
            // :611 sends inside STARTBR and :326 sends again at the end of the paragraph.
            assertThat(ws.sendCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("the ninth-digit page number wraps rather than failing (:306-307)")
        void pageNumberWraps() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(0, blank16(), blank16(), false, " ", ""));

            // PF8 keeps the inbound page number, unlike ENTER which zeroes it at :224.
            request.setCursor(cursor(99_999_999, blank16(), idOf(5), true, " ", ""));
            TransactionListResponse response =
                    controller.listTransactions(request, CicsAid.DFHPF8);

            assertThat(response.getCursor().getPageNum()).isZero();
            assertThat(response.getPagenumO()).isEqualTo("00000000");
        }

        @Test
        @DisplayName("PF8 skips the record at the key; ENTER keeps it (:285-287)")
        void skipReadFiresOnlyForKeysOutsideTheThree() {
            forward(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(1, idOf(1), idOf(10), true, " ", ""));

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHPF8);

            // The record at the key - transaction 10, the last row of the page being left - is read
            // and discarded, so the page starts at 11.
            assertThat(rowIds(response)).element(0).isEqualTo(idOf(11));
            assertThat(response.getCursor().getPageNum()).isEqualTo(2);
            verify(repository).startBrowse(idOf(10), BrowseDirection.FORWARD);
        }

        @Test
        @DisplayName("PF7 on page one refuses and re-sends without erase (:245-251)")
        void pf7OnPageOneRefuses() {
            TransactionListRequest request = request(true);
            request.setCursor(cursor(1, idOf(1), idOf(10), true, " ", ""));
            WorkArea ws = new WorkArea();

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHPF7, ws);

            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_ALREADY_TOP_OF_PAGE,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            assertThat(response.getErrmsgO()).startsWith("You are already at the top of the page...");
            assertThat(ws.isSendEraseNo()).isTrue();
            assertThat(ws.lastSendErase()).isFalse();
            assertThat(ws.sendCount()).isEqualTo(1);
            assertThat(ws.cursor().isNextPageYes()).as(":242 runs before the guard").isTrue();
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
        }

        @Test
        @DisplayName("PF8 with no next page refuses and re-sends without erase (:267-273)")
        void pf8WithNoNextPageRefuses() {
            TransactionListRequest request = request(true);
            request.setCursor(cursor(3, idOf(21), idOf(30), false, " ", ""));
            WorkArea ws = new WorkArea();

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHPF8, ws);

            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_ALREADY_BOTTOM_OF_PAGE,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            assertThat(response.getErrmsgO())
                    .startsWith("You are already at the bottom of the page...");
            assertThat(ws.isSendEraseNo()).isTrue();
            assertThat(ws.sendCount()).isEqualTo(1);
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
        }

        @Test
        @DisplayName("PF7 above page one pages backward and rewinds the count (:245-246, :359-369)")
        void pf7PagesBackward() {
            backward(11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(3, idOf(11), idOf(20), true, " ", ""));
            WorkArea ws = new WorkArea();

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHPF7, ws);

            // The record at the key is discarded, then rows ten down to one are filled - so the screen
            // reads in ascending order even though the file was walked backwards.
            assertThat(rowIds(response)).containsExactly(idOf(1), idOf(2), idOf(3), idOf(4), idOf(5),
                    idOf(6), idOf(7), idOf(8), idOf(9), idOf(10));
            assertThat(ws.idx()).isZero();
            assertThat(response.getCursor().getPageNum()).isEqualTo(2);
            assertThat(response.getPagenumO()).isEqualTo("00000002");
            verify(repository).startBrowse(idOf(11), BrowseDirection.BACKWARD);
        }

        @Test
        @DisplayName("running off the front of the file lands on page one (:365-367)")
        void pagingOffTheFrontLandsOnPageOne() {
            backward(11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(2, idOf(11), idOf(20), true, " ", ""));
            WorkArea ws = new WorkArea();

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHPF7, ws);

            assertThat(response.getCursor().getPageNum()).isEqualTo(1);
            assertThat(ws.isTransactEof()).isTrue();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_REACHED_TOP_OF_PAGE,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("a backward page shorter than ten is bottom-aligned, as the source leaves it")
        void shortBackwardPageIsBottomAligned() {
            backward(11, 10, 9, 8);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(2, idOf(11), idOf(20), true, " ", ""));

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHPF7);

            assertThat(rowIds(response)).containsExactly(blank16(), blank16(), blank16(), blank16(),
                    blank16(), blank16(), blank16(), idOf(8), idOf(9), idOf(10));
        }

        @Test
        @DisplayName("PF7 with no remembered first key cannot read anything, and asks no backend (:237)")
        void pf7WithoutAFirstKeyReadsNothing() {
            TransactionListRequest request = request(true);
            request.setCursor(cursor(2, blank16(), idOf(20), true, " ", ""));
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHPF7, ws);

            assertThat(ws.ridfld().kind()).isEqualTo(RidfldKind.LOW_VALUES);
            assertThat(ws.startbrOutcome()).isEqualTo(Outcome.END_OF_FILE);
            assertThat(ws.isTransactEof()).as("the NOTFND arm at :607").isTrue();
            // And then the source's own consequence, which is easy to miss: :337's guard is
            // IF NOT ERR-FLG-ON, and NOTFND did not set that flag, so the skip READPREV at :340 is
            // still attempted - against a browse that was never started. CICS answers INVREQ, the
            // WHEN OTHER arm at :680 runs, and ITS message is the one the operator finally sees.
            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_UNABLE_TO_LOOKUP,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            assertThat(ws.lastReadOutcome()).isEqualTo(Outcome.OTHER);
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
        }

        @Test
        @DisplayName("PF8 with no remembered last key uses HIGH-VALUES and finds nothing (:260)")
        void pf8WithoutALastKeyReadsNothing() {
            TransactionListRequest request = request(true);
            request.setCursor(cursor(2, idOf(11), blank16(), true, " ", ""));
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHPF8, ws);

            assertThat(ws.ridfld().kind()).isEqualTo(RidfldKind.HIGH_VALUES);
            assertThat(ws.startbrOutcome()).isEqualTo(Outcome.END_OF_FILE);
            assertThat(ws.isTransactEof()).isTrue();
            // As on the PF7 path: NOTFND leaves no browse, and the skip READNEXT at :286 is still
            // attempted because :283's guard tests the error flag, which NOTFND does not set.
            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_UNABLE_TO_LOOKUP,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
        }

        @Test
        @DisplayName("the backward skip-read guard's other side, dead in the source too (:339, B5)")
        void backwardSkipReadGuardOtherSide() {
            backward(9, 8, 7);
            WorkArea ws = new WorkArea();
            set(ws, "eibAid", CicsAid.DFHENTER);
            set(ws, "ridfld", Ridfld.ofKey(idOf(9)));
            ws.cursor().setPageNum(4);
            ws.cursor().setNextPageNo();
            TransactionListResponse response = new TransactionListResponse();

            controller.processPageBackward(request(true), ws, response);

            // No record was skipped, so the record at the key IS displayed - at the bottom row.
            assertThat(response.getRowTransactionId(TransactionListResponse.LAST_ROW))
                    .isEqualTo(idOf(9));
            // NEXT-PAGE-NO, so :361 does not rewind and the page number is untouched.
            assertThat(ws.cursor().getPageNum()).isEqualTo(4);
        }

        @Test
        @DisplayName("the backward rewind clamps to page one when the extra read finds nothing (:366)")
        void backwardRewindClampsToPageOne() {
            backward(9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
            WorkArea ws = new WorkArea();
            set(ws, "eibAid", CicsAid.DFHENTER);
            set(ws, "ridfld", Ridfld.ofKey(idOf(9)));
            ws.cursor().setPageNum(1);
            ws.cursor().setNextPageYes();
            TransactionListResponse response = new TransactionListResponse();

            controller.processPageBackward(request(true), ws, response);

            assertThat(ws.cursor().getPageNum()).isEqualTo(1);
        }
    }

    /** Sixteen spaces - a blank {@code TRNIDnn} or {@code CDEMO-CT00-TRNID-FIRST}. */
    private static String blank16() {
        return CODEC.movePicX("", TranRecord.TRAN_ID_LENGTH);
    }

    // =============================================================================================
    // The file paragraphs, :591-696 - every arm at every call site (gate G47).
    // =============================================================================================

    @Nested
    @DisplayName("The file arms")
    class FileArms {

        @Test
        @DisplayName("a refused positioning is WHEN OTHER and stops the paragraph (:612-618, :283)")
        void refusedPositioningStopsTheParagraph() {
            stub(true, failure(), failure());
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(ws.startbrOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.isTransactEof()).as("WHEN OTHER does not set TRANSACT-EOF").isFalse();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_UNABLE_TO_LOOKUP,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            assertThat(response.getErrmsgO()).startsWith("Unable to lookup transaction...");
            assertThat(ws.sendCount()).isEqualTo(1);
            assertThat(response.getPagenumO())
                    .as(":324 is inside the guard, so nothing was painted")
                    .isEqualTo(CODEC.movePicX("", TransactionListResponse.PAGENUM_LENGTH));
        }

        @Test
        @DisplayName("a refused read mid-page is WHEN OTHER and ends the fill loop (:646-652)")
        void refusedReadEndsTheFillLoop() {
            stub(true, found(1), failure());
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.lastReadOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(response.getRowTransactionId(1)).isEqualTo(idOf(1));
            assertThat(ws.idx()).isEqualTo(2);
            assertThat(response.getCursor().getPageNum()).isEqualTo(1);
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_UNABLE_TO_LOOKUP,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
            // :652 sends, then :326 sends the painted page.
            assertThat(ws.sendCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("a refused backward read is WHEN OTHER with the same message (:680-686)")
        void refusedBackwardRead() {
            stub(false, found(9), failure());
            TransactionListRequest request = request(true);
            request.setCursor(cursor(2, idOf(9), idOf(18), true, " ", ""));
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHPF7, ws);

            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_UNABLE_TO_LOOKUP,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("a duplicate-key response is neither NORMAL nor ENDFILE, so the read takes WHEN OTHER")
        void duplicateIsWhenOther() {
            stub(true, ReadResult.duplicate(FILE, tran(1)), endOfFile());
            WorkArea ws = new WorkArea();

            controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            // DUPKEY is a read condition, not a positioning one: STARTBR establishes a position without
            // transferring a record, and :602-619 has no DFHRESP(DUPKEY) arm to raise. The position
            // succeeds here because a record does satisfy it; the duplicate surfaces on the READNEXT that
            // returns that row, and it is the READNEXT's own WHEN OTHER arm [:646-652] that runs.
            assertThat(ws.startbrOutcome())
                    .as("the STARTBR found a record, so it reported NORMAL")
                    .isEqualTo(Outcome.OK);
            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.isTransactEof()).as("WHEN OTHER does not set TRANSACT-EOF").isFalse();
            assertThat(ws.message())
                    .isEqualTo(CODEC.movePicX(TransactionMenuController.MSG_UNABLE_TO_LOOKUP,
                            TransactionMenuController.WS_MESSAGE_LENGTH));
        }

        @ParameterizedTest(name = "a read reporting {0}")
        @EnumSource(Outcome.class)
        @DisplayName("every outcome the repository can report maps onto one of the three read arms")
        void everyOutcomeMapsToAReadArm(Outcome outcome) {
            FileArm arm = TransactionMenuController.readArmOf(outcome);
            assertThat(arm).isNotNull();
            switch (outcome) {
                case OK -> assertThat(arm).isEqualTo(FileArm.NORMAL);
                case END_OF_FILE -> assertThat(arm).isEqualTo(FileArm.END_OF_DATA);
                default -> assertThat(arm).isEqualTo(FileArm.FAILED);
            }
        }

        @ParameterizedTest(name = "positioning reporting {0}")
        @EnumSource(Outcome.class)
        @DisplayName("positioning differs in one place: a not-found is the NOTFND arm, not an error")
        void everyOutcomeMapsToAPositionArm(Outcome outcome) {
            FileArm arm = TransactionMenuController.positionArmOf(outcome);
            switch (outcome) {
                case OK -> assertThat(arm).isEqualTo(FileArm.NORMAL);
                case END_OF_FILE, NOT_FOUND -> assertThat(arm).isEqualTo(FileArm.END_OF_DATA);
                default -> assertThat(arm).isEqualTo(FileArm.FAILED);
            }
            assertThat(TransactionMenuController.readArmOf(Outcome.NOT_FOUND))
                    .as("the same status on a read is an error")
                    .isEqualTo(FileArm.FAILED);
        }

        @Test
        @DisplayName("a not-found positioning takes the NOTFND arm, message and all")
        void notFoundPositioningTakesTheNotFoundArm() {
            stub(true, ReadResult.notFound(FILE), endOfFile());
            WorkArea ws = new WorkArea();

            controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(ws.startbrOutcome()).isEqualTo(Outcome.NOT_FOUND);
            assertThat(ws.isTransactEof()).isTrue();
            assertThat(ws.isErrFlgOn()).isFalse();
            assertThat(ws.message()).startsWith(TransactionMenuController.MSG_AT_TOP_OF_PAGE);
        }

        @Test
        @DisplayName("ENDBR is issued once the page is built, and closing again is harmless (:692-696)")
        void endBrowseIsIssuedAndIdempotent() {
            forward(1, 2);
            controller.listTransactions(request(true), CicsAid.DFHENTER);
            verify(browse, org.mockito.Mockito.atLeastOnce()).endBrowse();
        }

        @Test
        @DisplayName("every one of the eight messages this screen can show is distinct")
        void everyMessageIsDistinct() {
            assertThat(List.of(TransactionMenuController.MSG_INVALID_SELECTION,
                            TransactionMenuController.MSG_TRAN_ID_NOT_NUMERIC,
                            TransactionMenuController.MSG_ALREADY_TOP_OF_PAGE,
                            TransactionMenuController.MSG_ALREADY_BOTTOM_OF_PAGE,
                            TransactionMenuController.MSG_AT_TOP_OF_PAGE,
                            TransactionMenuController.MSG_REACHED_BOTTOM_OF_PAGE,
                            TransactionMenuController.MSG_REACHED_TOP_OF_PAGE,
                            TransactionMenuController.MSG_UNABLE_TO_LOOKUP,
                            SystemMessages.CCDA_MSG_INVALID_KEY))
                    .doesNotHaveDuplicates();
            // The three "... top of the page ..." literals really are three different literals.
            assertThat(TransactionMenuController.MSG_AT_TOP_OF_PAGE)
                    .isEqualTo("You are at the top of the page...");
            assertThat(TransactionMenuController.MSG_ALREADY_TOP_OF_PAGE)
                    .isEqualTo("You are already at the top of the page...");
            assertThat(TransactionMenuController.MSG_REACHED_TOP_OF_PAGE)
                    .isEqualTo("You have reached the top of the page...");
            assertThat(TransactionMenuController.MSG_TRAN_ID_NOT_NUMERIC)
                    .as("the space before the ellipsis is part of the literal")
                    .isEqualTo("Tran ID must be Numeric ...");
            assertThat(TransactionMenuController.MSG_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid value is S");
            assertThat(TransactionMenuController.MSG_ALREADY_BOTTOM_OF_PAGE)
                    .isEqualTo("You are already at the bottom of the page...");
            assertThat(TransactionMenuController.MSG_REACHED_BOTTOM_OF_PAGE)
                    .isEqualTo("You have reached the bottom of the page...");
            assertThat(TransactionMenuController.MSG_UNABLE_TO_LOOKUP)
                    .isEqualTo("Unable to lookup transaction...");
        }
    }

    // =============================================================================================
    // The two edited renderings, :56-57 and :383-388 (practice B8, gates G22 to G24).
    // =============================================================================================

    @Nested
    @DisplayName("The edited renderings")
    class EditedRenderings {

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource({
                "0,          +00000000.00",
                "0.00,       +00000000.00",
                "1.01,       +00000001.01",
                "-1.01,      -00000001.01",
                "99999999.99,+99999999.99",
                "-99999999.99,-99999999.99",
                "123456789.12,+23456789.12",
                "-123456789.12,-23456789.12",
                "1.239,      +00000001.23",
                "-1.239,     -00000001.23",
                "-0.001,     +00000000.00"
        })
        @DisplayName("PIC +99999999.99: always signed, left-truncated at eight digits, DOWN at two")
        void amountIsEditedExactly(String amount, String expected) {
            assertThat(controller.editedAmount(new BigDecimal(amount)))
                    .isEqualTo(expected)
                    .hasSize(TransactionMenuController.WS_TRAN_AMT_LENGTH);
        }

        @Test
        @DisplayName("the rounding mode really is DOWN, not HALF_UP (gate G24)")
        void roundingIsDown() {
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(java.math.RoundingMode.DOWN);
            assertThat(controller.editedAmount(new BigDecimal("1.999"))).isEqualTo("+00000001.99");
            assertThat(controller.editedAmount(new BigDecimal("-1.999"))).isEqualTo("-00000001.99");
        }

        @Test
        @DisplayName("an absent amount is refused rather than rendered as zero")
        void amountIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> controller.editedAmount(null));
        }

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource({
                "'2022-07-19 10:20:30.000000', 07/19/22",
                "'1999-12-31 23:59:59.999999', 12/31/99",
                "'2000-01-01 00:00:00.000000', 01/01/00"
        })
        @DisplayName("a well-formed timestamp becomes MM/DD/YY through DateHeader (:384-388)")
        void wellFormedTimestampIsEdited(String timestamp, String expected) {
            assertThat(controller.editedTranDate(timestamp))
                    .isEqualTo(expected)
                    .hasSize(TransactionMenuController.WS_TRAN_DATE_LENGTH);
            assertThat(controller.editedTranDateByPosition(timestamp))
                    .as("the positional fallback must agree for a well-formed image")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("a malformed timestamp still yields eight bytes, because a MOVE cannot fail")
        void malformedTimestampFallsBackToTheByteMoves() {
            assertThat(controller.editedTranDate("2022-13-45 99:99:99.999999")).isEqualTo("13/45/22");
            assertThat(controller.editedTranDate("")).isEqualTo("  /  /  ");
            // The three slices are taken by position, exactly as :385-387 do: characters 6-7 as the
            // month, 9-10 as the day and 3-4 as the two-digit year of "not a timestamp at all".
            assertThat(controller.editedTranDate("not a timestamp at all    ")).isEqualTo(" t/me/t ");
        }

        @Test
        @DisplayName("a short image is padded to twenty-six before the slices are taken")
        void shortImageIsPaddedFirst() {
            assertThat(controller.editedTranDate("2022-07")).isEqualTo("07/  /22");
        }

        @Test
        @DisplayName("an absent timestamp is refused rather than rendered as the declared VALUE")
        void timestampIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> controller.editedTranDate(null));
        }

        @Test
        @DisplayName("a row carries the edited pair, and the description truncates to the screen")
        void aRowCarriesTheEditedPair() {
            forward(3);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(response.getRowAmount(1)).isEqualTo("+00000003.01");
            assertThat(response.getRowTransactionDate(1)).isEqualTo("07/19/22");
            assertThat(response.getRowDescription(1))
                    .isEqualTo(CODEC.movePicX("TRANSACTION 3", TransactionListResponse.TDESC_LENGTH));
            assertThat(ws.tranAmt()).isEqualTo("+00000003.01");
            assertThat(ws.tranDate()).isEqualTo("07/19/22");
        }

        @Test
        @DisplayName("the declared VALUE of WS-TRAN-DATE is '00/00/00' and survives an unused run")
        void declaredValueOfTranDate() {
            assertThat(new WorkArea().tranDate())
                    .isEqualTo(TransactionMenuController.WS_TRAN_DATE_INITIAL)
                    .isEqualTo("00/00/00");
        }
    }

    // =============================================================================================
    // The COBOL relational tests.
    // =============================================================================================

    @Nested
    @DisplayName("The COBOL relations")
    class CobolRelations {

        @Test
        @DisplayName("= SPACES and = LOW-VALUES are whole-field tests")
        void spacesAndLowValues() {
            assertThat(TransactionMenuController.isAllSpaces("   ")).isTrue();
            assertThat(TransactionMenuController.isAllSpaces("")).isTrue();
            assertThat(TransactionMenuController.isAllSpaces(" x ")).isFalse();
            assertThat(TransactionMenuController.isAllLowValues("\u0000\u0000")).isTrue();
            assertThat(TransactionMenuController.isAllLowValues("")).isTrue();
            assertThat(TransactionMenuController.isAllLowValues("\u0000x")).isFalse();
            assertThat(TransactionMenuController.isAllLowValues("  ")).isFalse();
        }

        @Test
        @DisplayName("NOT = SPACES AND LOW-VALUES is the relation, not 'contains something else'")
        void notSpacesAndNotLowValues() {
            assertThat(TransactionMenuController.isPresentCobol("S")).isTrue();
            assertThat(TransactionMenuController.isPresentCobol(" ")).isFalse();
            assertThat(TransactionMenuController.isPresentCobol("\u0000")).isFalse();
            // Neither all spaces nor all low values, so PRESENT by the source's relation.
            assertThat(TransactionMenuController.isPresentCobol(" \u0000")).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\" numeric: {1}")
        @CsvSource({
                "0000000000000001, true",
                "0, true",
                "'', false",
                "' ', false",
                "'1 ', false",
                "'-1', false",
                "'1.0', false",
                "'١٢٣', false"
        })
        @DisplayName("IS NUMERIC accepts the ten ASCII digits and nothing else")
        void isNumericIsAsciiDigitsOnly(String value, boolean expected) {
            assertThat(TransactionMenuController.isCobolNumeric(value)).isEqualTo(expected);
        }
    }

    // =============================================================================================
    // The attention identifier.
    // =============================================================================================

    @Nested
    @DisplayName("Attention identifier resolution")
    class AidResolution {

        @Test
        @DisplayName("the query parameter wins, because only a byte distinguishes PF3 from PF15")
        void parameterWins() {
            TransactionListRequest request = new TransactionListRequest();
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF7));
            assertThat(controller.resolveEibAid(request, CicsAid.DFHPF15 & 0xFF))
                    .isEqualTo(CicsAid.DFHPF15);
            assertThat(controller.resolveEibAid(request, null)).isEqualTo(CicsAid.DFHPF7);
        }

        @ParameterizedTest(name = "eibaid={0} is refused")
        @ValueSource(ints = {-1, 256, 300, Integer.MAX_VALUE, Integer.MIN_VALUE})
        @DisplayName("a value outside 0..255 is refused rather than narrowed")
        void outOfRangeIsRefused(int value) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionMenuController.requireAidByte(value))
                    .withMessageContaining(TransactionMenuController.EIBAID_PARAM);
        }

        @ParameterizedTest(name = "eibaid={0} is accepted")
        @ValueSource(ints = {0, 1, 125, 255})
        @DisplayName("every value inside 0..255 narrows to its byte")
        void inRangeNarrows(int value) {
            assertThat(TransactionMenuController.requireAidByte(value) & 0xFF).isEqualTo(value);
        }

        @Test
        @DisplayName("no key named at all is ENTER, the only default that reaches no new branch")
        void absentKeyIsEnter() {
            assertThat(controller.aidByteOfToken(null)).isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.aidByteOfToken("     ")).isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.aidByteOfToken("")).isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.aidByteOfToken("\u0000\u0000\u0000\u0000\u0000"))
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.resolveEibAid(null, null)).isEqualTo(CicsAid.DFHENTER);
        }

        @Test
        @DisplayName("a value that is not one byte is no key at all, which is the WHEN OTHER arm")
        void unrecognisedTokenIsNoKey() {
            assertThat(controller.aidByteOfToken("ZZZZZ")).isEqualTo(CicsAid.DFHNULL);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL)).isEmpty();
        }

        @ParameterizedTest(name = "byte {0} round-trips")
        @ValueSource(ints = {1, 0x40, 0x4B, 0x6B, 0x7D, 0xC1, 0xC8, 0xF1, 0xF3, 0xF8, 0xFC, 255})
        @DisplayName("the payload's one character IS the byte, across the whole AID space")
        void everyByteRoundTrips(int unsigned) {
            byte stated = (byte) unsigned;

            assertThat(controller.aidByteOfToken(PfKeyResolver.aidImage(stated)))
                    .as("nothing is folded, so PF19 and PF20 survive as themselves")
                    .isEqualTo(stated);
        }

        @Test
        @DisplayName("the blank and LOW-VALUES defaults cost no AID: neither U+0020 nor U+0000 is one")
        void theNoKeyDefaultsCostNothing() {
            // "States no key" keeps its ENTER default, and the two images that mean it - a blank field and
            // a never-written one - are the only two characters not read back as bytes. Neither is an
            // attention identifier: DFHNULL is X'40' and every DFHAID constant this application reproduces
            // lies at X'40' or above, so no key becomes unreachable by keeping the default.
            assertThat(controller.aidByteOfToken(" ")).isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.aidByteOfToken("\u0000")).isEqualTo(CicsAid.DFHENTER);
            assertThat(CicsAid.DFHNULL).isEqualTo((byte) 0x40);
            assertThat(controller.aidByteOfToken(PfKeyResolver.aidImage(CicsAid.DFHNULL)))
                    .as("X'40' arrives as U+0040, the at-sign, and is read back as DFHNULL itself")
                    .isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("a CCARD-AID token is not one byte, so it names no key: WHEN OTHER at :129")
        void aFoldedTokenIsNoLongerDecoded() {
            // CSSTRPFY folds PF7 onto 'PFK07' together with PF19, and PF8 onto 'PFK08' with PF20; those
            // are this screen's two paging keys, so decoding a token paged for a key nobody pressed.
            assertThat(AidKey.PFK07.token()).isEqualTo("PFK07");
            assertThat(controller.aidByteOfToken("PFK07")).isEqualTo(CicsAid.DFHNULL);
            assertThat(controller.aidByteOfToken("PA1  ")).isEqualTo(CicsAid.DFHNULL);
            assertThat(controller.aidByteOfToken("PA1")).isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("PF20 is not PF8: it pages nothing and takes WHEN OTHER, as on a terminal")
        void highFunctionKeysAreNotFolded() {
            byte pf20 = controller.aidByteOfToken(PfKeyResolver.aidImage(CicsAid.DFHPF20));

            assertThat(pf20).isEqualTo(CicsAid.DFHPF20);
            assertThat(TransactionMenuController.isEnterOrPf8(pf20)).isFalse();
            assertThat(PfKeyResolver.resolve(pf20))
                    .as("CSSTRPFY does fold it onto PFK08 - which is why the token is not the input")
                    .contains(AidKey.PFK08);
        }

        @Test
        @DisplayName("a character above the one-byte AID space is DFHNULL, never narrowed onto PF8")
        void aCharacterAboveTheAidSpaceIsDfhnull() {
            assertThat(controller.aidByteOfToken(String.valueOf((char) 0x01F8)))
                    .isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("the two compound EIBAID guards name different key sets, and that is deliberate")
        void theTwoGuardsDiffer() {
            assertThat(TransactionMenuController.isEnterPf7OrPf3(CicsAid.DFHENTER)).isTrue();
            assertThat(TransactionMenuController.isEnterPf7OrPf3(CicsAid.DFHPF7)).isTrue();
            assertThat(TransactionMenuController.isEnterPf7OrPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(TransactionMenuController.isEnterPf7OrPf3(CicsAid.DFHPF8)).isFalse();
            assertThat(TransactionMenuController.isEnterOrPf8(CicsAid.DFHENTER)).isTrue();
            assertThat(TransactionMenuController.isEnterOrPf8(CicsAid.DFHPF8)).isTrue();
            assertThat(TransactionMenuController.isEnterOrPf8(CicsAid.DFHPF7)).isFalse();
            assertThat(TransactionMenuController.isEnterOrPf8(CicsAid.DFHPF3)).isFalse();
        }
    }

    // =============================================================================================
    // TRAN-ID as a RIDFLD, and the browse handle that recovers the STARTBR status.
    // =============================================================================================

    @Nested
    @DisplayName("The RIDFLD and the browse handle")
    class BrowseHandle {

        @Test
        @DisplayName("the three kinds the source moves into TRAN-ID, and what each one is")
        void theThreeKinds() {
            assertThat(Ridfld.lowValues().kind()).isEqualTo(RidfldKind.LOW_VALUES);
            assertThat(Ridfld.lowValues().isBoundary()).isTrue();
            assertThat(Ridfld.highValues().kind()).isEqualTo(RidfldKind.HIGH_VALUES);
            assertThat(Ridfld.highValues().isBoundary()).isTrue();
            assertThat(Ridfld.ofKey(idOf(3)).kind()).isEqualTo(RidfldKind.KEY);
            assertThat(Ridfld.ofKey(idOf(3)).isBoundary()).isFalse();
            assertThat(Ridfld.ofKey(idOf(3)).key()).isEqualTo(idOf(3));
        }

        @Test
        @DisplayName("neither component may be absent")
        void componentsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Ridfld(null, ""));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Ridfld(RidfldKind.KEY, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> Ridfld.ofKey(null));
        }

        @ParameterizedTest(name = "{0} {1} unreachable: {2}")
        @CsvSource({
                "LOW_VALUES,  FORWARD,  false",
                "LOW_VALUES,  BACKWARD, true",
                "HIGH_VALUES, FORWARD,  true",
                "HIGH_VALUES, BACKWARD, false",
                "KEY,         FORWARD,  false",
                "KEY,         BACKWARD, false"
        })
        @DisplayName("only two of the six combinations can return nothing without asking the backend")
        void onlyTwoCombinationsAreUnreachable(RidfldKind kind, BrowseDirection direction,
                                               boolean unreachable) {
            Ridfld ridfld = new Ridfld(kind, kind == RidfldKind.KEY ? idOf(1) : "");
            assertThat(ridfld.isUnreachable(direction)).isEqualTo(unreachable);
        }

        @Test
        @DisplayName("the probe's record is buffered, so the first real read still sees it")
        void theProbeConsumesNothing() {
            stub(true, found(1), found(2), endOfFile());
            TransactBrowse handle = TransactBrowse.position(repository, BrowseDirection.FORWARD,
                    Ridfld.lowValues());

            assertThat(handle.positioningOutcome().outcome()).isEqualTo(Outcome.OK);
            assertThat(handle.read().requireRecord().tranId()).isEqualTo(idOf(1));
            assertThat(handle.read().requireRecord().tranId()).isEqualTo(idOf(2));
            assertThat(handle.read().isEndOfFile()).isTrue();
            handle.endBrowse();
            handle.close();
            verify(browse, org.mockito.Mockito.atLeastOnce()).endBrowse();
        }

        @Test
        @DisplayName("a probe that finds nothing ends the browse, so no later read can resume it")
        void aFruitlessProbeEndsTheBrowse() {
            stub(true, endOfFile(), endOfFile());
            TransactBrowse handle = TransactBrowse.position(repository, BrowseDirection.FORWARD,
                    Ridfld.lowValues());

            assertThat(handle.positioningOutcome().isNotFound())
                    .as("a STARTBR that finds nothing raises NOTFND, not ENDFILE")
                    .isTrue();
            verify(browse).endBrowse();
        }

        @Test
        @DisplayName("a backward boundary browse probes with READPREV, not READNEXT")
        void backwardProbesWithReadPrev() {
            stub(false, found(9), endOfFile());
            TransactBrowse handle = TransactBrowse.position(repository, BrowseDirection.BACKWARD,
                    Ridfld.highValues());

            assertThat(handle.read().requireRecord().tranId()).isEqualTo(idOf(9));
            verify(repository).startBrowse(BrowseDirection.BACKWARD);
            verify(browse, never()).readNext();
        }

        @Test
        @DisplayName("an unreachable anchor asks the backend nothing and refuses every read")
        void anUnreachableAnchorAsksNothing() {
            TransactBrowse handle = TransactBrowse.position(repository, BrowseDirection.FORWARD,
                    Ridfld.highValues());

            assertThat(handle.positioningOutcome().isEndOfFile()).isTrue();
            assertThat(handle.read().isOther()).isTrue();
            handle.endBrowse();
            handle.close();
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
        }

        @Test
        @DisplayName("every argument is required")
        void positionArgumentsRequired() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    TransactBrowse.position(null, BrowseDirection.FORWARD, Ridfld.lowValues()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    TransactBrowse.position(repository, null, Ridfld.lowValues()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    TransactBrowse.position(repository, BrowseDirection.FORWARD, null));
        }
    }

    // =============================================================================================
    // The map area: MOVE LOW-VALUES TO COTRN0AO and RECEIVE MAP INTO COTRN0AI (gates G9, G38).
    // =============================================================================================

    @Nested
    @DisplayName("The map area")
    class MapArea {

        @Test
        @DisplayName("MOVE LOW-VALUES blanks all 59 payload items and resets every attribute (:114)")
        void lowValuesBlanksEverything() {
            TransactionListResponse response = new TransactionListResponse();
            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                response.setPayloadValue(prefix,
                        CODEC.movePicX("X", TransactionListResponse.declaredLength(prefix)));
            }
            response.applyHighlight(TransactionListResponse.TRNIDIN,
                    FieldAttributeSetter.resolveFromFlags(
                            true, true, false, TransactionListResponse.TRNIDIN,
                            TransactionListResponse.MAP_NAME));

            controller.moveLowValuesToOutputMap(response);

            assertThat(TransactionListResponse.fieldPrefixes())
                    .hasSize(TransactionListResponse.FIELD_COUNT);
            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                assertThat(response.payloadValue(prefix))
                        .as("%s - MOVE LOW-VALUES TO COTRN0AO at :114 writes X'00', not spaces", prefix)
                        .isEqualTo(ScreenFieldImage.unpainted(
                                TransactionListResponse.declaredLength(prefix)));
                assertThat(response.attributesOf(prefix).isLowValues()).as("%s", prefix).isTrue();
            }
        }

        @Test
        @DisplayName("the ENTER path resets the attributes and the REENTER path does not (gate G38)")
        void attributeResetBelongsToTheEnterPath() {
            forward(1);
            TransactionListResponse onEnter =
                    controller.listTransactions(request(false), CicsAid.DFHENTER);
            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                assertThat(onEnter.attributesOf(prefix).isLowValues()).as("%s", prefix).isTrue();
            }

            // And no path of this screen ever writes a colour: it copies neither CSSETATY nor DFHATTR,
            // so there is no field highlighting to gate - see the class documentation.
            forward(1);
            TransactionListResponse onReenter =
                    controller.listTransactions(request(true), CicsAid.DFHPF12);
            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                assertThat(onReenter.isFieldHighlighted(prefix)).as("%s", prefix).isFalse();
            }
        }

        @Test
        @DisplayName("RECEIVE MAP copies all 59 received items into the aliased output view (:554-562)")
        void receiveCopiesEveryItem() {
            TransactionListRequest request = request(true);
            request.setTrnidin(idOf(4));
            request.setSelection(2, "S");
            request.setTransactionId(2, idOf(2));
            TransactionListResponse response = new TransactionListResponse();
            WorkArea ws = new WorkArea();

            controller.receiveTrnlstScreen(request, ws, response);

            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                assertThat(response.payloadValue(prefix)).as("%s", prefix)
                        .isEqualTo(request.getPayloadValue(prefix));
            }
            assertThat(response.getTrnidinO()).isEqualTo(idOf(4));
            assertThat(response.getRowSelection(2)).isEqualTo("S");
            assertThat(ws.receiveOutcome()).isEqualTo(Outcome.OK);
        }

        @Test
        @DisplayName("all 59 DFHMDF fields are payload members and no length, flag or attribute "
                + "item is (gate G9)")
        void onlyThePayloadItemsAreOnTheWire() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            List<String> responseMembers = membersOf(mapper, new TransactionListResponse());
            List<String> requestMembers = membersOf(mapper, request(true));

            assertThat(TransactionListResponse.fieldPrefixes()).hasSize(59);
            assertThat(TransactionListRequest.FIELD_NAMES)
                    .containsExactlyElementsOf(TransactionListResponse.fieldPrefixes());

            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                // The xxxI item is the request member and the xxxO item is the response member: those
                // two carry the field's value and its authoritative PIC X(n) width.
                assertThat(named(requestMembers, prefix)).as("request payload %s", prefix).hasSize(1);
                // Both sides publish the same name: @JsonProperty pins the response to the xxxI item
                // in lower case (AAP 0.6.3), which is what makes a response acceptable as the next
                // request. The Java accessor keeps the xxxO spelling.
                assertThat(named(responseMembers, prefix)).as("response payload %s", prefix)
                        .hasSize(1);
                // xxxL is the length CICS reports, xxxF the flag byte, xxxA its attribute view, and
                // xxxC / xxxP / xxxH / xxxV the output-side colour, highlight, hilight and validation
                // bytes. Every one of them is validation or highlight metadata and none may be on the
                // wire - app/cpy-bms/COTRN00.CPY, and AAP 0.6.3.
                for (String metadata : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    assertThat(named(responseMembers, prefix + metadata))
                            .as("response metadata %s%s", prefix, metadata).isEmpty();
                    assertThat(named(requestMembers, prefix + metadata))
                            .as("request metadata %s%s", prefix, metadata).isEmpty();
                }
            }

            // What remains on each side is the communication area, this screen's pagination cursor and
            // the transfer target - payload by rule R6, and none of it a DFHMDF field.
            assertThat(responseMembers).hasSize(59 + 5)
                    .contains("nextProgram", "nextMapset", "nextMap", "navigationContext", "cursor");
            assertThat(requestMembers).hasSize(59 + 3)
                    .contains("aid", "navigationContext", "cursor");
        }

        private List<String> membersOf(ObjectMapper mapper,
                                       Object payload) {
            List<String> members = new ArrayList<>();
            mapper.valueToTree(payload).fieldNames().forEachRemaining(members::add);
            return members;
        }

        private List<String> named(List<String> members, String expected) {
            return members.stream().filter(member -> member.equalsIgnoreCase(expected)).toList();
        }

        @Test
        @DisplayName("the cursor projection copies all six fields of CDEMO-CT00-INFO")
        void cursorProjectionCopiesEveryField() {
            TransactionListCursor projected =
                    TransactionMenuController.cursorOf(cursor(7, idOf(61), idOf(70), true, "S",
                            idOf(65)));

            assertThat(projected.getPageNum()).isEqualTo(7);
            assertThat(projected.getTrnidFirst()).isEqualTo(idOf(61));
            assertThat(projected.getTrnidLast()).isEqualTo(idOf(70));
            assertThat(projected.isNextPageYes()).isTrue();
            assertThat(projected.getTrnSelFlg()).isEqualTo("S");
            assertThat(projected.getTrnSelected()).isEqualTo(idOf(65));
            assertThat(projected.isRowSelected()).isTrue();
        }

        @Test
        @DisplayName("an absent cursor is refused rather than silently restarting the browse")
        void cursorIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionMenuController.cursorOf(null));
        }

        @Test
        @DisplayName("the response's cursor is the one this class works on, not a copy of it")
        void theCursorIsShared() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            WorkArea ws = new WorkArea();
            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);
            assertThat(ws.cursor()).isSameAs(response.getCursor());
        }

        @Test
        @DisplayName("the work area's declared VALUE state is the copybook's")
        void declaredValueState() {
            WorkArea ws = new WorkArea();
            assertThat(ws.isErrFlgOff()).isTrue();
            assertThat(ws.isErrFlgOn()).isFalse();
            assertThat(ws.errFlg()).isEqualTo(WorkArea.FLAG_NO);
            assertThat(ws.isTransactNotEof()).isTrue();
            assertThat(ws.isTransactEof()).isFalse();
            assertThat(ws.transactEof()).isEqualTo(WorkArea.FLAG_NO);
            assertThat(ws.isSendEraseYes()).isTrue();
            assertThat(ws.isSendEraseNo()).isFalse();
            assertThat(ws.sendEraseFlg()).isEqualTo(WorkArea.FLAG_YES);
            assertThat(ws.idx()).isZero();
            assertThat(ws.recCount()).as("WS-REC-COUNT is declared and never used - practice B5")
                    .isZero();
            assertThat(ws.tranAmt()).isEmpty();
            assertThat(ws.message()).isEmpty();
            assertThat(ws.cursorField()).isNull();
            assertThat(ws.cursorPositions()).isZero();
            assertThat(ws.readCount()).isZero();
            assertThat(ws.sendCount()).isZero();
            assertThat(ws.lastSendErase()).isTrue();
            assertThat(ws.startbrOutcome()).isNull();
            assertThat(ws.lastReadOutcome()).isNull();
            assertThat(ws.receiveOutcome()).isNull();
            assertThat(ws.eibAid()).isEqualTo(CicsAid.DFHENTER);
            assertThat(ws.eibcalen()).isZero();
            assertThat(ws.commarea()).isEqualTo(NavigationContext.empty());
            assertThat(ws.ridfld().kind()).isEqualTo(RidfldKind.LOW_VALUES);
            assertThat(ws.selectedRow()).isZero();
            assertThat(ws.isTransferred()).isFalse();
        }

        @Test
        @DisplayName("a work area is required, and each call gets its own (gate G53)")
        void workAreaIsRequiredAndPerCall() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    controller.listTransactions(request(true), CicsAid.DFHENTER, null));

            forward(1);
            WorkArea first = new WorkArea();
            controller.listTransactions(request(true), CicsAid.DFHPF12, first);
            forward(1);
            WorkArea second = new WorkArea();
            controller.listTransactions(request(true), CicsAid.DFHENTER, second);
            assertThat(first.isErrFlgOn()).isTrue();
            assertThat(second.isErrFlgOn()).as("no state leaks between calls").isFalse();
        }

        @Test
        @DisplayName("the cursor is placed on TRNIDIN, and the length item stays metadata (:105)")
        void cursorIsPlacedOnTheInputField() {
            TransactionListRequest request = request(true);
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHPF12, ws);

            assertThat(ws.cursorField()).isEqualTo(TransactionListRequest.TRNIDIN_FIELD);
            assertThat(ws.cursorPositions()).isEqualTo(2);
            assertThat(request.getCursorPositionField())
                    .isEqualTo(TransactionListRequest.TRNIDIN_FIELD);
        }
    }

    // =============================================================================================
    // The HTTP adapter, and the structural properties of the file itself.
    // =============================================================================================

    @Nested
    @DisplayName("The HTTP adapter and the file's own properties")
    class HttpAndStructure {

        private MockMvc mockMvc() {
            return MockMvcBuilders.standaloneSetup(controller).build();
        }

        @Test
        @DisplayName("GET /api/transactions with no body is the cold start")
        void coldStartOverHttp() throws Exception {
            mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COSGN00C"));
        }

        @Test
        @DisplayName("GET /api/transactions with a payload and an eibaid paints a page")
        void pagedListOverHttp() throws Exception {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            String body = new ObjectMapper().writeValueAsString(request(true));

            mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .param(TransactionMenuController.EIBAID_PARAM,
                                    String.valueOf(ENTER_PARAM)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value("CT00"))
                    .andExpect(jsonPath("$.pagenum").value("00000001"))
                    .andExpect(jsonPath("$.trnid01").value(idOf(1)))
                    .andExpect(jsonPath("$.trnid10").value(idOf(10)));
        }

        @Test
        @DisplayName("either accepted spelling of the AID parameter names the same key, and a "
                + "contradiction between them is refused")
        void eitherAidSpellingNamesTheSameKey() throws Exception {
            String body = new ObjectMapper().writeValueAsString(request(true));
            String enter = String.valueOf(ENTER_PARAM);

            // The alternate spelling was declared by two sibling routes and by none of the three that
            // declared the canonical one, so a client that used it here had its key discarded by Spring
            // and the request executed as ENTER with nothing saying so.
            for (String name : com.vsergeychik.carddemo.common.AidRequestParameter.ACCEPTED_NAMES) {
                // Re-stubbed per request: the browse a page reads is consumed by the request that reads
                // it, so a second request against the same stub would paint an empty page and prove
                // nothing about the parameter name.
                forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);

                mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .param(name, enter))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.trnid01").value(idOf(1)));
            }

            // The refusals are asserted at the method seam rather than over this harness, which registers
            // no controller advice: in the running application CobolErrorHandler turns each of these into
            // a 400, and WebConfigErrorContractTest owns that mapping.
            //
            // An out-of-range value now reaches the guard through either spelling. Through the spelling
            // this route did not bind it used to be discarded, and the request ran as ENTER.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controller.getTransactions(request(true), 300, null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controller.getTransactions(request(true), null, 300));
            // And two spellings naming different keys is a contradiction rather than a preference.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controller.getTransactions(request(true), ENTER_PARAM, 243));
        }

        @Test
        @DisplayName("the route is exactly the one the AAP assigns this transaction")
        void theRouteIsTheAssignedOne() {
            assertThat(TransactionMenuController.TRANSACTIONS_PATH).isEqualTo("/api/transactions");
            assertThat(TransactionMenuController.class.getAnnotation(
                    org.springframework.web.bind.annotation.RestController.class)).isNotNull();
        }

        @Test
        @DisplayName("the metadata travels beside the screen, keyed by DFHMDF prefix")
        void theMetadataTravelsBesideTheScreen() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);

            ScreenResponse<TransactionListResponse> answer =
                    controller.getTransactions(request(true), ENTER_PARAM, null);

            ScreenMetadata metadata = answer.screenMetadata();
            assertThat(metadata.cursorField())
                    .as("MOVE -1 TO TRNIDINL is the cursor request, an xxxL item and not a payload "
                            + "member")
                    .isEqualTo(TransactionListResponse.TRNIDIN);
            assertThat(metadata.fields())
                    .hasSize(answer.screen().allAttributes().size());
            assertThat(metadata.field(TransactionListResponse.ERRMSG)).isNotNull();
            assertThat(metadata.messageColour()).isEqualTo(Byte.toUnsignedInt(
                    answer.screen().attributesOf(TransactionListResponse.ERRMSG).colour()));
            assertThat(metadata.resetAllOutputFields())
                    .as("WS-SEND-ERASE-FLG chooses SEND ... ERASE at :533-549, and had no other route")
                    .isTrue();
        }

        @Test
        @DisplayName("the envelope leaves the screen flat and adds exactly one sibling member")
        void theEnvelopeLeavesTheScreenFlat() throws Exception {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            String body = new ObjectMapper().writeValueAsString(request(true));

            mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .param(TransactionMenuController.EIBAID_PARAM,
                                    String.valueOf(ENTER_PARAM)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnid01").value(idOf(1)))
                    .andExpect(jsonPath("$.screenMetadata.cursorField")
                            .value(TransactionListResponse.TRNIDIN))
                    .andExpect(jsonPath("$.screenMetadata.resetAllOutputFields").value(true))
                    .andExpect(jsonPath("$.screen").doesNotExist())
                    .andExpect(jsonPath("$.cursorField").doesNotExist());
        }

        @Test
        @DisplayName("no server-side conversation state of any kind appears in the code (gate G37)")
        void noServerSideState() {
            assertThat(controllerCode()).doesNotContain("HttpSession", "@SessionAttributes",
                    "getSession(", "ThreadLocal", "HttpServletRequest", "@Scope",
                    "ConcurrentHashMap", "@Cacheable", "static java.util", "@RequestScope");
        }

        @Test
        @DisplayName("the page size is a constant here and nowhere else (gate G39)")
        void thePageSizeIsNotConfigurable() {
            assertThat(controllerSource()).contains("private static final int PAGE_SIZE = 10;");

            String code = controllerCode();
            assertThat(code).doesNotContain("@Value", "@ConfigurationProperties", "Environment");
            // The only request parameter this endpoint accepts is the attention identifier - never a
            // page number, a page size or an offset. app/cbl/COTRN00C.cbl paints ten rows always.
            //
            // It is accepted under both of its two spellings, which is why there are two @RequestParam
            // sites rather than one: binding a single spelling let Spring discard the other and run the
            // request as ENTER without saying so. Both are named here, so a third parameter of any kind
            // still fails this assertion.
            assertThat(code.split("@RequestParam", -1)).hasSize(3);
            assertThat(code).contains("@RequestParam(name = EIBAID_PARAM, required = false)");
            assertThat(code).contains("@RequestParam(name = EIBAID_PARAM_ALIAS, required = false)");

            assertThat(configurationSource()).doesNotContain("page-size", "pageSize", "page_size",
                    "PAGE_SIZE");
        }

        @Test
        @DisplayName("no dataset literal, no wildcard import, no floating point, no wrong rounding")
        void theForbiddenTokensAreAbsent() {
            String code = controllerCode();
            assertThat(code).doesNotContain("AWS.M2.CARDDEMO");                            // G46
            assertThat(code).doesNotContain(".*;");                                         // G52
            assertThat(code).doesNotContain("double", "float", "Double.", "Float.");        // G22
            assertThat(code).doesNotContain("RoundingMode", "HALF_", "CEILING");            // G24
            assertThat(code).contains("CobolDecimal.store(");         // the single numeric seam
            assertThat(code).doesNotContain("NumberFormat", "DecimalFormat", "DateTimeFormatter",
                    "String.format", "toUpperCase", "toLowerCase", "equalsIgnoreCase", "Locale");
            assertThat(code).doesNotContain("@PreAuthorize", "@Secured", "@RolesAllowed",
                    "PasswordEncoder", "BCrypt", "Principal");                              // G41
            assertThat(code).doesNotContain("WebFlux", "Mono<", "Flux<");                    // B2
            assertThat(controllerSource()).doesNotContain("TODO", "FIXME", "NotImplemented",
                    "not implemented", "placeholder");
        }

        @Test
        @DisplayName("no logging call passes a throwable, so no driver text can escape")
        void loggingPassesNoThrowable() {
            String code = controllerCode();
            assertThat(code).contains("LOG.error(");
            assertThat(code).doesNotContain("LOG.error(e", "LOG.error(ex", ", refusal)",
                    ", unreadable)", ", failure)", "getMessage()", "printStackTrace");
        }

        @Test
        @DisplayName("the name divergence is documented in this class, not silently satisfied (B4)")
        void theNameDivergenceIsDocumented() {
            String source = controllerSource();
            assertThat(source).contains("List Transactions from TRANSACT file");
            assertThat(source).contains("R1");
            assertThat(source).contains("No user rules provided");
            // The documentation is allowed - required, in fact - to name what the code must not do.
            // That is why the token scans above run over the comment-stripped view and this one does
            // not: prose about an absent construct is evidence of B4, not a violation of G37.
            assertThat(source).contains("HttpSession");
            assertThat(controllerCode()).doesNotContain("HttpSession");
        }

        @Test
        @DisplayName("the comment stripper the scans depend on keeps code and drops prose")
        void theStripperIsTrustworthy() {
            assertThat(stripComments("a // HttpSession\nb")).isEqualTo("a \nb");
            assertThat(stripComments("a /* HttpSession */ b")).isEqualTo("a  b");
            assertThat(stripComments("a /** x\n * HttpSession\n */\nb")).isEqualTo("a \nb");
            assertThat(stripComments("\"a // b\"")).isEqualTo("\"a // b\"");
            assertThat(stripComments("'/' + \"/*\" // x")).isEqualTo("'/' + \"/*\" ");
            assertThat(stripComments("\"\\\\\" // x")).isEqualTo("\"\\\\\" ");
            assertThat(stripComments("'\\''  // x")).isEqualTo("'\\''  ");
        }

        private String controllerSource() {
            return read("app/java/src/main/java/com/vsergeychik/carddemo/transaction/"
                    + "TransactionMenuController.java");
        }

        private String controllerCode() {
            return stripComments(controllerSource());
        }

        private String configurationSource() {
            return read("app/java/src/main/resources/application.yml");
        }

        /**
         * Returns {@code source} with every line and block comment removed and every string and
         * character literal preserved.
         *
         * <p>The token scans above are assertions about <em>code</em>. This class's documentation is
         * required to name the constructs the code must not use - that is what practice B4 asks for -
         * so scanning the raw text would make the documentation and the gate contradict one another.
         * Stripping first resolves that: prose may say {@code HttpSession}, code may not.
         */
        private String stripComments(String source) {
            assertThat(source).as("the stripper does not model text blocks").doesNotContain("\"\"\"");
            StringBuilder code = new StringBuilder(source.length());
            int cursor = 0;
            int end = source.length();
            while (cursor < end) {
                char here = source.charAt(cursor);
                boolean pairAvailable = cursor + 1 < end;
                if (here == '/' && pairAvailable && source.charAt(cursor + 1) == '/') {
                    while (cursor < end && source.charAt(cursor) != '\n') {
                        cursor++;
                    }
                } else if (here == '/' && pairAvailable && source.charAt(cursor + 1) == '*') {
                    cursor += 2;
                    while (cursor + 1 < end
                            && !(source.charAt(cursor) == '*' && source.charAt(cursor + 1) == '/')) {
                        cursor++;
                    }
                    cursor = Math.min(cursor + 2, end);
                } else if (here == '"' || here == '\'') {
                    code.append(here);
                    cursor++;
                    while (cursor < end) {
                        char inside = source.charAt(cursor);
                        code.append(inside);
                        cursor++;
                        if (inside == '\\' && cursor < end) {
                            code.append(source.charAt(cursor));
                            cursor++;
                        } else if (inside == here) {
                            break;
                        }
                    }
                } else {
                    code.append(here);
                    cursor++;
                }
            }
            return code.toString();
        }

        private String read(String repositoryRelativePath) {
            java.nio.file.Path candidate = java.nio.file.Path.of("").toAbsolutePath();
            while (candidate != null) {
                java.nio.file.Path resolved = candidate.resolve(repositoryRelativePath);
                if (java.nio.file.Files.exists(resolved)) {
                    try {
                        return java.nio.file.Files.readString(resolved);
                    } catch (java.io.IOException unreadable) {
                        throw new java.io.UncheckedIOException("Could not read " + resolved,
                                unreadable);
                    }
                }
                candidate = candidate.getParent();
            }
            throw new IllegalStateException("Could not find " + repositoryRelativePath + " at or "
                    + "above " + java.nio.file.Path.of("").toAbsolutePath());
        }
    }

    // =============================================================================================
    // LOW-VALUES against SPACES, and the paragraphs reached on their own.
    //
    // COBOL writes `NOT = SPACES AND LOW-VALUES` because the two are genuinely different bytes: a
    // field the operator cleared holds spaces, a field the map never populated holds nulls. Every
    // guard in the program tests both, so both have to be driven, and a screen field really can
    // arrive as nulls - the DTO accepts them. These cases also reach PROCESS-PAGE-BACKWARD on its
    // own, which is the only way to see it with NEXT-PAGE-NO: its one caller sets NEXT-PAGE-YES
    // first (:242). That is practice B10 earning its keep - the paragraph is a method, so it can be
    // put in a state its caller cannot produce.
    // =============================================================================================

    @Nested
    @DisplayName("LOW-VALUES against SPACES, and the paragraphs entered on their own")
    class LowValuesAndDirectEntry {

        private static final String LOW_VALUES_16 = "\u0000".repeat(TranRecord.TRAN_ID_LENGTH);
        private static final String LOW_VALUES_8 = "\u0000".repeat(8);

        @Test
        @DisplayName("the one-argument entry point takes the AID from the payload's own token")
        void theOneArgumentEntryPointReadsThePayloadToken() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            TransactionListRequest onEnter = request(true);
            onEnter.setAid(PfKeyResolver.aidImage(CicsAid.DFHENTER));

            TransactionListResponse painted = controller.listTransactions(onEnter);

            assertThat(painted.getRowTransactionId(1)).isEqualTo(idOf(1));
            assertThat(painted.getPagenumO()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("the one-argument entry point honours a function key token")
        void theOneArgumentEntryPointHonoursAFunctionKey() {
            TransactionListRequest request = request(true);
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF3));

            assertThat(controller.listTransactions(request).getNextProgram()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("the one-argument entry point still recognises a cold start")
        void theOneArgumentEntryPointRecognisesAColdStart() {
            assertThat(controller.listTransactions(null).getNextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("a typed key of nulls browses from the start, exactly as spaces do (:206)")
        void nullsInTheTypedKeyBrowseFromTheStart() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            TransactionListRequest request = request(true);
            request.setTrnidin(LOW_VALUES_16);
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request, CicsAid.DFHENTER, ws);

            assertThat(ws.ridfld().kind()).isEqualTo(RidfldKind.LOW_VALUES);
            assertThat(ws.isErrFlgOff()).isTrue();
            assertThat(rowIds(response)).containsExactlyElementsOf(
                    List.of(idOf(1), idOf(2), idOf(3), idOf(4), idOf(5),
                            idOf(6), idOf(7), idOf(8), idOf(9), idOf(10)));
        }

        @Test
        @DisplayName("PF7 with a first key of nulls anchors on LOW-VALUES (:237)")
        void pf7WithNullsAnchorsLow() {
            backward(9, 8, 7, 6, 5, 4, 3, 2, 1);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(4, LOW_VALUES_16, idOf(20), false, "", blank16()));
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHPF7, ws);

            assertThat(ws.ridfld().kind()).isEqualTo(RidfldKind.LOW_VALUES);
        }

        @Test
        @DisplayName("PF8 with a last key of nulls anchors on HIGH-VALUES (:260)")
        void pf8WithNullsAnchorsHigh() {
            forward(1, 2, 3);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(1, blank16(), LOW_VALUES_16, true, "", blank16()));
            WorkArea ws = new WorkArea();

            controller.listTransactions(request, CicsAid.DFHPF8, ws);

            assertThat(ws.ridfld().kind()).isEqualTo(RidfldKind.HIGH_VALUES);
        }

        @Test
        @DisplayName("a target of nulls falls back to the sign-on program, as spaces do (:512-513)")
        void aTargetOfNullsFallsBackToSignon() {
            WorkArea ws = new WorkArea();
            set(ws, "commarea", NavigationContext.empty().withToProgram(LOW_VALUES_8));
            TransactionListResponse response = new TransactionListResponse();

            controller.returnToPrevScreen(ws, response);

            assertThat(response.getNextProgram()).isEqualTo("COSGN00C");
            assertThat(response.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            // XCTL names a program and nothing else, so no mapset is invented for the target: both are
            // blanked and the target program names its own, exactly as this one named COTRN00 / COTRN0A
            // for itself.
            assertThat(response.getNextMapset()).isBlank();
            assertThat(response.getNextMap()).isBlank();
        }

        @Test
        @DisplayName("a target that names a program is left alone (:512)")
        void aNamedTargetIsLeftAlone() {
            WorkArea ws = new WorkArea();
            set(ws, "commarea", NavigationContext.empty().withToProgram("COADM01C"));
            TransactionListResponse response = new TransactionListResponse();

            controller.returnToPrevScreen(ws, response);

            assertThat(response.getNextProgram()).isEqualTo("COADM01C");
            assertThat(response.getNavigationContext().fromProgram()).isEqualTo("COTRN00C");
            assertThat(response.getNavigationContext().fromTranid()).isEqualTo("CT00");
        }

        @Test
        @DisplayName("a refused backward positioning stops the paragraph at :337")
        void aRefusedBackwardPositioningStopsTheParagraph() {
            stub(false, failure(), failure());
            TransactionListRequest request = request(true);
            request.setCursor(cursor(3, idOf(21), idOf(30), false, "", blank16()));
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request, CicsAid.DFHPF7, ws);

            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.startbrOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(response.getErrmsgO())
                    .isEqualTo(CODEC.movePicX("Unable to lookup transaction...", 78));
            // :337 returned, so the page was never blanked and no row was written.
            assertThat(rowIds(response)).allMatch(id -> id.equals(blank16()));
        }

        @Test
        @DisplayName("page backward with NEXT-PAGE-NO leaves the page number where it was (:361)")
        void pageBackwardWithNoNextPageLeavesThePageNumberAlone() {
            backward(20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10);
            WorkArea ws = backwardWorkArea(6, false);
            TransactionListResponse response = seededResponse(ws);

            controller.processPageBackward(request(true), ws, response);

            assertThat(ws.cursor().getPageNum()).isEqualTo(6);
            assertThat(response.getPagenumO()).isEqualTo("00000006");
        }

        @Test
        @DisplayName("page backward with NEXT-PAGE-YES steps back one page (:362-364)")
        void pageBackwardStepsBackOnePage() {
            backward(20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10);
            WorkArea ws = backwardWorkArea(6, true);
            TransactionListResponse response = seededResponse(ws);

            controller.processPageBackward(request(true), ws, response);

            assertThat(ws.cursor().getPageNum()).isEqualTo(5);
            assertThat(response.getPagenumO()).isEqualTo("00000005");
        }

        @Test
        @DisplayName("page backward clamps to page one rather than stepping below it (:366)")
        void pageBackwardClampsToPageOne() {
            backward(20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10);
            WorkArea ws = backwardWorkArea(1, true);
            TransactionListResponse response = seededResponse(ws);

            controller.processPageBackward(request(true), ws, response);

            assertThat(ws.cursor().getPageNum()).isEqualTo(1);
            assertThat(response.getPagenumO()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("a look-ahead that fails leaves NEXT-PAGE-NO and the page it already counted")
        void aFailedForwardLookAheadSetsNextPageNo() {
            stub(true, found(1), found(2), found(3), found(4), found(5), found(6), found(7),
                    found(8), found(9), found(10), failure(), failure());
            WorkArea ws = new WorkArea();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            // :306 counted the page before :308 read, and :308 then failed: the count stands, the
            // flag says there is nothing beyond, and the operator is told the lookup failed.
            assertThat(ws.cursor().getPageNum()).isEqualTo(1);
            assertThat(ws.cursor().isNextPageYes()).isFalse();
            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.isTransactNotEof()).isTrue();
            assertThat(response.getErrmsgO())
                    .isEqualTo(CODEC.movePicX("Unable to lookup transaction...", 78));
            assertThat(rowIds(response).get(9)).isEqualTo(idOf(10));
        }

        @Test
        @DisplayName("a backward look-ahead that fails clamps the page number to one (:366)")
        void aFailedBackwardLookAheadClampsToPageOne() {
            stub(false, found(20), found(19), found(18), found(17), found(16), found(15), found(14),
                    found(13), found(12), found(11), failure(), failure());
            WorkArea ws = backwardWorkArea(6, true);
            TransactionListResponse response = seededResponse(ws);

            controller.processPageBackward(request(true), ws, response);

            // :362 is a three-part condition and the error flag is the part that fails here, so the
            // ELSE at :366 runs even though the page number was six.
            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(ws.isTransactNotEof()).isTrue();
            assertThat(ws.cursor().getPageNum()).isEqualTo(1);
            assertThat(response.getPagenumO()).isEqualTo("00000001");
        }

        /**
         * A work area positioned for a backward browse that can actually fill a page: the anchor is
         * HIGH-VALUES, which is the only boundary a READPREV can start from, and the AID is
         * {@code DFHENTER} so {@code :339} takes no skip read.
         */
        private WorkArea backwardWorkArea(int pageNum, boolean nextPage) {
            WorkArea ws = new WorkArea();
            set(ws, "ridfld", Ridfld.highValues());
            TransactionListCursor seed = TransactionMenuController.cursorOf(
                    cursor(pageNum, idOf(11), idOf(20), nextPage, "", blank16()));
            set(ws, "cursor", seed);
            return ws;
        }

        private TransactionListResponse seededResponse(WorkArea ws) {
            TransactionListResponse response = new TransactionListResponse();
            response.setCursor(ws.cursor());
            set(ws, "cursor", response.getCursor());
            return response;
        }
    }

    // =============================================================================================
    // The screen-wide gates.
    //
    // Every case below asserts a property of the WHOLE screen rather than of one paragraph, and each
    // one is a gate the assignment names outright:
    //
    //   G39  ten rows, and no property, parameter or payload member can make it anything else;
    //   G37  the commarea, the attention identifier and the 58-byte cursor travel in the payload,
    //        and the server keeps nothing between two requests;
    //   G9   fifty-nine payload members, named verbatim, typed String, with the length, flag and
    //        attribute items kept off the wire;
    //   G33  the 1-based COBOL row index mapped onto 0-based Java rows, with 0 and 11 unreachable;
    //   G34  the REDEFINES pair round-tripping over one backing span;
    //   G38  the CSSETATY highlight belonging to the re-enter path and to no other;
    //   G50  both truth states of the 88-levels these properties rest on.
    //
    // The class name still says "Menu" and the program still lists transactions - rule R1, and the
    // divergence register at AAP 0.8.4. Everything here asserts a paged LIST.
    // =============================================================================================

    @Nested
    @DisplayName("The screen-wide gates")
    class ScreenWideGates {

        /**
         * {@code app/cpy-bms/COTRN00.CPY}'s fifty-nine {@code xxxI} items, transcribed verbatim and in
         * copybook order: eight heading and control fields, then ten rows of five, then the error line.
         *
         * <p>Transcribed rather than generated on purpose. A generated list would agree with whatever
         * rule the production code happens to use, including a wrong one; this list agrees with the
         * copybook, which is the contract. It is what makes the three inconsistent suffix widths -
         * {@code SEL0001} on four digits, {@code TRNID01} on two and {@code TAMT001} on three -
         * assertable rather than assumed (practice B4).
         */
        private List<String> copybookFieldNames() {
            return List.of(
                    "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "PAGENUM",
                    "TRNIDIN",
                    "SEL0001", "TRNID01", "TDATE01", "TDESC01", "TAMT001",
                    "SEL0002", "TRNID02", "TDATE02", "TDESC02", "TAMT002",
                    "SEL0003", "TRNID03", "TDATE03", "TDESC03", "TAMT003",
                    "SEL0004", "TRNID04", "TDATE04", "TDESC04", "TAMT004",
                    "SEL0005", "TRNID05", "TDATE05", "TDESC05", "TAMT005",
                    "SEL0006", "TRNID06", "TDATE06", "TDESC06", "TAMT006",
                    "SEL0007", "TRNID07", "TDATE07", "TDESC07", "TAMT007",
                    "SEL0008", "TRNID08", "TDATE08", "TDESC08", "TAMT008",
                    "SEL0009", "TRNID09", "TDATE09", "TDESC09", "TAMT009",
                    "SEL0010", "TRNID10", "TDATE10", "TDESC10", "TAMT010",
                    "ERRMSG");
        }

        /**
         * The declared {@code PIC X(n)} width of each name above, in the same order.
         *
         * <p>{@code 4, 40, 8, 8, 40, 8, 8, 16} for the heading and control fields, then
         * {@code 1, 16, 8, 26, 12} ten times for the rows, then {@code 78} for the error line -
         * {@code app/cpy-bms/COTRN00.CPY:24-372}.
         */
        private List<Integer> copybookFieldWidths() {
            return List.of(
                    4, 40, 8, 8, 40, 8, 8, 16,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    1, 16, 8, 26, 12,
                    78);
        }

        private MockMvc mockMvc() {
            return MockMvcBuilders.standaloneSetup(controller).build();
        }

        /** Twelve consecutive transactions, so a full page and its look-ahead can both be served. */
        private void aFullPageAndMore() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        }

        // -----------------------------------------------------------------------------------------
        // Gate G39 - the page size is behaviour, not configuration.
        //
        // Ten is written into the source five times: :290 PERFORM VARYING ... UNTIL WS-IDX > 10,
        // :297 UNTIL WS-IDX >= 11, :344 UNTIL WS-IDX > 10, :349 MOVE 10 TO WS-IDX and :351
        // UNTIL WS-IDX <= 0. None of the five reads anything an operator or an administrator could
        // set, so nothing outside the program may be able to change the page either.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("a full page is exactly ten rows and no property can make it anything else "
                + "(gate G39)")
        void noPropertyCanChangeThePageSize() {
            // Gate G39 asks for more than the absence of @Value: it asks that the size be unreachable
            // from outside. So every property name a configurable implementation would plausibly have
            // read is actually set, to a value that is not ten, before the page is painted.
            List<String> plausible = List.of(
                    "carddemo.transactions.page-size",
                    "carddemo.transaction.list.page-size",
                    "carddemo.page-size",
                    "spring.data.web.pageable.default-page-size",
                    "PAGE_SIZE");
            Map<String, String> restore = new LinkedHashMap<>();
            try {
                for (String name : plausible) {
                    restore.put(name, System.getProperty(name));
                    System.setProperty(name, "3");
                }
                aFullPageAndMore();

                TransactionListResponse response =
                        controller.listTransactions(request(true), CicsAid.DFHENTER);

                assertThat(rowIds(response)).containsExactly(idOf(1), idOf(2), idOf(3), idOf(4),
                        idOf(5), idOf(6), idOf(7), idOf(8), idOf(9), idOf(10));
                assertThat(TransactionListResponse.PAGE_SIZE).isEqualTo(10);
                assertThat(TransactionListRequest.PAGE_SIZE).isEqualTo(10);
                assertThat(TransactionListResponse.LAST_ROW - TransactionListResponse.FIRST_ROW + 1)
                        .isEqualTo(10);
            } finally {
                // Restored whatever the outcome. A test that leaves a system property behind changes
                // the environment of every test after it, and this suite has to be deterministic in
                // any order (practice B7).
                restore.forEach((name, value) -> {
                    if (value == null) {
                        System.clearProperty(name);
                    } else {
                        System.setProperty(name, value);
                    }
                });
            }
        }

        @Test
        @DisplayName("no query parameter and no request-body member can change the page size (G39)")
        void noRequestCanChangeThePageSize() throws Exception {
            aFullPageAndMore();
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode body = mapper.valueToTree(request(true));
            // Members no COTRN00 field declares. This route binds the attention identifier and
            // nothing else, so these are ignored rather than honoured - which is the assertion.
            body.put("pageSize", 3);
            body.put("rowCount", 3);
            body.put("size", 3);
            body.put("limit", 3);

            mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body))
                            .param(TransactionMenuController.EIBAID_PARAM,
                                    String.valueOf(ENTER_PARAM))
                            .param("pageSize", "3")
                            .param("size", "3")
                            .param("limit", "3")
                            .param("page", "2")
                            .param("offset", "5"))
                    .andExpect(status().isOk())
                    // Rows four to ten would be blank had any of the above been honoured.
                    .andExpect(jsonPath("$.trnid01").value(idOf(1)))
                    .andExpect(jsonPath("$.trnid03").value(idOf(3)))
                    .andExpect(jsonPath("$.trnid04").value(idOf(4)))
                    .andExpect(jsonPath("$.trnid10").value(idOf(10)))
                    // And there is no eleventh row to honour a larger size with.
                    .andExpect(jsonPath("$.trnid11O").doesNotExist())
                    .andExpect(jsonPath("$.pagenum").value("00000001"));
        }

        @Test
        @DisplayName("the row slots a short page leaves empty are spaces, never a rendered zero "
                + "(:452-505)")
        void blankedRowSlotsAreSpacesAndNotZeros() {
            forward(1, 2, 3);

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER);

            // The three rows the file could fill carry the edited pair ...
            assertThat(response.getRowAmount(1)).isEqualTo("+00000001.01");
            assertThat(response.getRowAmount(3)).isEqualTo("+00000003.01");
            // ... and INITIALIZE-TRAN-DATA moved SPACES into the other seven, never zeros. A blanked
            // amount rendered as +00000000.00 would put a figure on the screen that the file does not
            // contain - COTRN00C:457 moves SPACES, and that is the whole difference.
            for (int row = 4; row <= TransactionListResponse.LAST_ROW; row++) {
                assertThat(response.getRowTransactionId(row)).as("TRNID row %d", row)
                        .isEqualTo(CODEC.movePicX("", TransactionListResponse.TRNID_LENGTH));
                assertThat(response.getRowTransactionDate(row)).as("TDATE row %d", row)
                        .isEqualTo(CODEC.movePicX("", TransactionListResponse.TDATE_LENGTH));
                assertThat(response.getRowDescription(row)).as("TDESC row %d", row)
                        .isEqualTo(CODEC.movePicX("", TransactionListResponse.TDESC_LENGTH));
                assertThat(response.getRowAmount(row)).as("TAMT row %d", row)
                        .isEqualTo(CODEC.movePicX("", TransactionListResponse.TAMT_LENGTH))
                        .hasSize(TransactionListResponse.TAMT_LENGTH)
                        .isNotEqualTo("+00000000.00")
                        .doesNotContain("0")
                        .isBlank();
            }
        }

        // -----------------------------------------------------------------------------------------
        // Gate G37 - the conversation travels in the payload and the server keeps nothing.
        //
        // COTRN00C:62-70 declares CDEMO-CT00-INFO immediately after COPY COCOM01Y, so the commarea a
        // CICS RETURN passes is the 160-byte COMMAREA plus a 58-byte extension = 218 bytes. The
        // extension belongs to this screen alone; the 160 bytes belong to all seventeen.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("the commarea stays 160 bytes and the cursor is a separate 58-byte extension")
        void theCommareaIsNeverWidenedByTheCursor() {
            // COCOM01Y sums to 160: 34 general + 84 customer + 12 account + 16 card + 14 more.
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(NavigationContext.empty().toFixedWidth(CODEC)).hasSize(160);

            // CDEMO-CT00-INFO, field by field - COTRN00C:63-70.
            assertThat(TransactionListCursor.TRNID_FIRST_LENGTH).isEqualTo(16);
            assertThat(TransactionListCursor.TRNID_LAST_LENGTH).isEqualTo(16);
            assertThat(TransactionListCursor.PAGE_NUM_LENGTH).isEqualTo(8);
            assertThat(TransactionListCursor.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(TransactionListCursor.TRN_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(TransactionListCursor.TRN_SELECTED_LENGTH).isEqualTo(16);
            assertThat(TransactionListCursor.CURSOR_LENGTH).isEqualTo(16 + 16 + 8 + 1 + 1 + 16)
                    .isEqualTo(58);
            assertThat(TransactionListCursor.COMMAREA_WITH_CURSOR_LENGTH).isEqualTo(160 + 58)
                    .isEqualTo(218);
            assertThat(PaginationCursor.CURSOR_LENGTH).isEqualTo(58);
            assertThat(PaginationCursor.COMMAREA_LENGTH).isEqualTo(218);

            // The extension is a nested type of each payload and never a member of the commarea
            // record, because widening COCOM01Y would change a layout the other sixteen screens share.
            assertThat(TransactionListCursor.class.getEnclosingClass())
                    .isEqualTo(TransactionListResponse.class);
            assertThat(PaginationCursor.class.getEnclosingClass())
                    .isEqualTo(TransactionListRequest.class);
            List<String> commareaComponents = new ArrayList<>();
            for (RecordComponent component : NavigationContext.class.getRecordComponents()) {
                commareaComponents.add(component.getName());
            }
            assertThat(commareaComponents).hasSize(16)
                    .doesNotContain("trnidFirst", "trnidLast", "pageNum", "nextPageFlg", "trnSelFlg",
                            "trnSelected", "cursor");

            // And what a request reports as EIBCALEN is the sum, not either half.
            TransactionListRequest carrying = request(true);
            carrying.setCursor(cursor(2, idOf(11), idOf(20), true, " ", blank16()));
            assertThat(carrying.commareaLength()).isEqualTo(218);
            assertThat(new TransactionListRequest().commareaLength())
                    .as("no commarea at all is EIBCALEN = 0, the cold start at :107")
                    .isZero();
        }

        @Test
        @DisplayName("all six fields of CDEMO-CT00-INFO travel in the request and in the response")
        void theSixCursorFieldsTravelInBothPayloads() {
            forward(40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51);
            TransactionListRequest request = request(true);
            request.setCursor(cursor(4, idOf(31), idOf(40), true, "S", idOf(35)));
            ObjectMapper mapper = new ObjectMapper();
            JsonNode sent = mapper.valueToTree(request).get("cursor");

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHPF8);
            JsonNode returned = mapper.valueToTree(response).get("cursor");

            // Six members on the way in and six on the way out - nothing about the browse position is
            // held anywhere else.
            for (JsonNode carried : List.of(sent, returned)) {
                assertThat(carried).isNotNull();
                assertThat(carried.fieldNames()).toIterable().containsExactlyInAnyOrder(
                        "trnidFirst", "trnidLast", "pageNum", "nextPageFlg", "trnSelFlg",
                        "trnSelected");
                assertThat(carried.get("trnidFirst").asText())
                        .hasSize(TransactionListCursor.TRNID_FIRST_LENGTH);
                assertThat(carried.get("trnidLast").asText())
                        .hasSize(TransactionListCursor.TRNID_LAST_LENGTH);
                assertThat(carried.get("nextPageFlg").asText())
                        .hasSize(TransactionListCursor.NEXT_PAGE_FLG_LENGTH);
                assertThat(carried.get("trnSelFlg").asText())
                        .hasSize(TransactionListCursor.TRN_SEL_FLG_LENGTH);
                assertThat(carried.get("trnSelected").asText())
                        .hasSize(TransactionListCursor.TRN_SELECTED_LENGTH);
                // PIC 9(08) is a number in the payload and eight characters on the screen: :324
                // moves it into PAGENUMI, which is X(8).
                assertThat(carried.get("pageNum").isNumber()).isTrue();
            }
            assertThat(sent.get("trnidLast").asText()).isEqualTo(idOf(40));
            assertThat(returned.get("pageNum").asInt()).isEqualTo(5);
            assertThat(response.getPagenumO()).isEqualTo("00000005")
                    .hasSize(TransactionListResponse.PAGENUM_LENGTH);
        }

        @Test
        @DisplayName("no session is created, and two identical requests get identical answers (G37)")
        void nothingIsRetainedBetweenRequests() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionListRequest request = request(true);
            request.setCursor(cursor(4, idOf(31), idOf(40), true, " ", blank16()));
            String body = mapper.writeValueAsString(request);

            // Re-stubbed before each request: a browse is consumed by the request that reads it, so
            // the second request gets its own file exactly as a second terminal would.
            forward(40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51);
            MvcResult first = mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .param(TransactionMenuController.EIBAID_PARAM,
                                    String.valueOf(CicsAid.DFHPF8 & 0xFF)))
                    .andExpect(status().isOk())
                    .andReturn();

            forward(40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51);
            MvcResult second = mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .param(TransactionMenuController.EIBAID_PARAM,
                                    String.valueOf(CicsAid.DFHPF8 & 0xFF)))
                    .andExpect(status().isOk())
                    .andReturn();

            // No session was created on either request, and none could have been consulted.
            assertThat(first.getRequest().getSession(false)).isNull();
            assertThat(second.getRequest().getSession(false)).isNull();
            // Byte-identical answers. The clock is fixed, so the two heading fields agree too; had any
            // part of the position been remembered on the server, the second page would differ.
            assertThat(second.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());
            assertThat(first.getResponse().getContentAsString()).contains(idOf(41), idOf(50));
        }

        @Test
        @DisplayName("a hand-crafted cursor is honoured with no prior page to have produced it")
        void aHandCraftedCursorIsHonoured() {
            forward(40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51);
            TransactionListRequest request = request(true);
            // Page four of a browse this instance has never served: nothing on the server knows that
            // transaction 40 was ever displayed, and it does not need to.
            request.setCursor(cursor(4, idOf(31), idOf(40), true, " ", blank16()));

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHPF8);

            // :260-262 anchors on CDEMO-CT00-TRNID-LAST, :285-287 steps over the record at that key,
            // and :306-307 counts the page that follows it.
            verify(repository).startBrowse(idOf(40), BrowseDirection.FORWARD);
            assertThat(rowIds(response)).containsExactly(idOf(41), idOf(42), idOf(43), idOf(44),
                    idOf(45), idOf(46), idOf(47), idOf(48), idOf(49), idOf(50));
            assertThat(response.getCursor().getPageNum()).isEqualTo(5);
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo(idOf(41));
            assertThat(response.getCursor().getTrnidLast()).isEqualTo(idOf(50));
            assertThat(response.getCursor().isNextPageYes())
                    .as("transaction 51 was read as the look-ahead at :308")
                    .isTrue();
        }

        @Test
        @DisplayName("both 88-levels of CDEMO-CT00-NEXT-PAGE-FLG are driven (:307, :309-313, G50)")
        void bothStatesOfTheNextPageFlagAreDriven() {
            // 'Y' - the look-ahead at :308 found a record, so :310 SET NEXT-PAGE-YES.
            aFullPageAndMore();
            TransactionListResponse more =
                    controller.listTransactions(request(true), CicsAid.DFHENTER);
            assertThat(more.getCursor().isNextPageYes()).isTrue();
            assertThat(more.getCursor().isNextPageNo()).isFalse();
            assertThat(more.getCursor().getNextPageFlg()).isEqualTo(TransactionListCursor.NEXT_PAGE_YES)
                    .isEqualTo("Y");

            // 'N' - the look-ahead reported end of file, so :312 SET NEXT-PAGE-NO.
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            TransactionListResponse last =
                    controller.listTransactions(request(true), CicsAid.DFHENTER);
            assertThat(last.getCursor().isNextPageNo()).isTrue();
            assertThat(last.getCursor().isNextPageYes()).isFalse();
            assertThat(last.getCursor().getNextPageFlg()).isEqualTo(TransactionListCursor.NEXT_PAGE_NO)
                    .isEqualTo("N");
            // And 'N' is the declared VALUE at :66, which is what a fresh work area starts from.
            assertThat(new TransactionListCursor().getNextPageFlg()).isEqualTo("N");
        }

        // -----------------------------------------------------------------------------------------
        // Gate G9 - the fifty-nine payload members, named and typed exactly as the copybook has them.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("the fifty-nine fields are the copybook's, verbatim, in order and at width")
        void theFieldsAreTheCopybooksVerbatim() {
            List<String> names = copybookFieldNames();
            List<Integer> widths = copybookFieldWidths();
            assertThat(names).hasSize(59);
            assertThat(widths).hasSize(59);

            assertThat(TransactionListResponse.fieldPrefixes()).containsExactlyElementsOf(names);
            assertThat(TransactionListRequest.FIELD_NAMES).containsExactlyElementsOf(names);
            assertThat(TransactionListResponse.FIELD_COUNT).isEqualTo(59);
            assertThat(TransactionListRequest.FIELD_COUNT).isEqualTo(59);

            for (int index = 0; index < names.size(); index++) {
                assertThat(TransactionListResponse.declaredLength(names.get(index)))
                        .as("%s is PIC X(%d)", names.get(index), widths.get(index))
                        .isEqualTo(widths.get(index));
            }

            // Eight heading and control fields, ten rows of five, one error line - and the widths sum
            // to the payload the group item carries.
            assertThat(TransactionListResponse.HEADER_FIELD_COUNT).isEqualTo(8);
            assertThat(TransactionListResponse.ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(TransactionListResponse.ERROR_FIELD_COUNT).isEqualTo(1);
            int declared = 0;
            for (Integer width : widths) {
                declared += width;
            }
            assertThat(TransactionListResponse.PAYLOAD_WIDTH_TOTAL).isEqualTo(declared);
        }

        @Test
        @DisplayName("the three inconsistent suffix widths are preserved and never harmonised (B4)")
        void theInconsistentSuffixesArePreserved() {
            List<String> names = copybookFieldNames();

            // SEL0001..SEL0010 carry FOUR digits; TRNID01, TDATE01 and TDESC01 carry TWO; TAMT001
            // carries THREE. The copybook is inconsistent and the contract is the copybook.
            assertThat(names.stream().filter(name -> name.startsWith("SEL")).toList())
                    .hasSize(10)
                    .allSatisfy(name -> assertThat(name).matches("SEL\\d{4}").hasSize(7));
            assertThat(names.stream().filter(name -> name.startsWith("TRNID")
                            && !name.equals("TRNIDIN")).toList())
                    .hasSize(10)
                    .allSatisfy(name -> assertThat(name).matches("TRNID\\d{2}").hasSize(7));
            assertThat(names.stream().filter(name -> name.startsWith("TDATE")).toList())
                    .hasSize(10)
                    .allSatisfy(name -> assertThat(name).matches("TDATE\\d{2}").hasSize(7));
            assertThat(names.stream().filter(name -> name.startsWith("TDESC")).toList())
                    .hasSize(10)
                    .allSatisfy(name -> assertThat(name).matches("TDESC\\d{2}").hasSize(7));
            assertThat(names.stream().filter(name -> name.startsWith("TAMT")).toList())
                    .hasSize(10)
                    .allSatisfy(name -> assertThat(name).matches("TAMT\\d{3}").hasSize(7));

            // The plausible harmonised forms are not fields, and asking for one fails loudly rather
            // than reading or writing nothing at all.
            for (String harmonised : List.of("TAMT01", "TAMT0001", "SEL01", "SEL001", "TRNID001",
                    "TDATE001", "TDESC001")) {
                assertThatIllegalArgumentException()
                        .as("%s is not a field of this map", harmonised)
                        .isThrownBy(() -> TransactionListResponse.declaredLength(harmonised));
            }
        }

        @Test
        @DisplayName("every one of the fifty-nine members is a String on both sides of the wire")
        void everyPayloadMemberIsAString() throws Exception {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            TransactionListRequest request = request(true);
            request.setTrnidin(idOf(4));
            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHENTER);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode requestNode = mapper.valueToTree(request);
            JsonNode responseNode = mapper.valueToTree(response);

            for (String prefix : copybookFieldNames()) {
                // PIC X(n) is characters, so every item is a String: an amount that arrived as a JSON
                // number would have lost the edit mask, and a page number as a number would have lost
                // its zero fill.
                assertThat(response.payloadValue(prefix)).as("response %s", prefix)
                        .isInstanceOf(String.class);
                assertThat(request.getPayloadValue(prefix)).as("request %s", prefix)
                        .isInstanceOf(String.class);
                assertThat(getterOf(TransactionListResponse.class, prefix, "O"))
                        .as("response getter for %s", prefix)
                        .isEqualTo(String.class);
                assertThat(getterOf(TransactionListRequest.class, prefix, ""))
                        .as("request getter for %s", prefix)
                        .isEqualTo(String.class);
                assertThat(memberOf(responseNode, prefix).isTextual())
                        .as("serialized response %s", prefix).isTrue();
                assertThat(memberOf(requestNode, prefix).isTextual())
                        .as("serialized request %sI", prefix).isTrue();
            }

            // The mask is twelve characters of PIC +99999999.99 - WS-TRAN-AMT at :56, moved into
            // TAMT00nI at :396-442 - and never the record's own PIC S9(09)V99.
            assertThat(response.getRowAmount(1)).isEqualTo("+00000001.01")
                    .hasSize(TransactionListResponse.TAMT_LENGTH);
            // Published as "tamt001" - the xxxI item in lower case, per AAP 0.6.3.
            assertThat(memberOf(responseNode, "TAMT001").asText()).isEqualTo("+00000001.01");
        }

        /** The declared return type of a payload accessor, so its Java type is assertable. */
        private Class<?> getterOf(Class<?> payload, String fieldPrefix, String suffix)
                throws ReflectiveOperationException {
            String camel = fieldPrefix.substring(0, 1)
                    + fieldPrefix.substring(1).toLowerCase(Locale.ROOT);
            return payload.getMethod("get" + camel + suffix).getReturnType();
        }

        /** One serialized member, located without depending on the accessor's capitalisation. */
        private JsonNode memberOf(JsonNode payload, String itemName) {
            List<String> matches = new ArrayList<>();
            payload.fieldNames().forEachRemaining(member -> {
                if (member.equalsIgnoreCase(itemName)) {
                    matches.add(member);
                }
            });
            assertThat(matches).as("exactly one member named %s", itemName).hasSize(1);
            return payload.get(matches.get(0));
        }

        @Test
        @DisplayName("the xxxL length item is signed, because the program moves -1 into it")
        void theLengthItemIsSigned() throws Exception {
            // COMP PIC S9(4) - app/cpy-bms/COTRN00.CPY:19 and its fifty-eight siblings. The S is not
            // decoration: COTRN00C moves -1 into TRNIDINL at thirteen sites - :105, :131, :201, :216,
            // :221, :243, :265, :610, :617, :644, :651, :678 and :685 - and an unsigned item could not
            // hold it.
            assertThat(TransactionListRequest.CURSOR_POSITION_REQUEST).isEqualTo((short) -1);
            assertThat(TransactionListRequest.class.getField("CURSOR_POSITION_REQUEST").getType())
                    .isEqualTo(short.class);
            assertThat(FieldMetadata.class.getMethod("getLengthItem").getReturnType())
                    .isEqualTo(short.class);
            assertThat(FieldMetadata.LENGTH_ITEM_BYTES).as("COMP PIC S9(4) is two bytes").isEqualTo(2);

            TransactionListRequest request = request(true);
            FieldMetadata metadata = request.getMetadata(TransactionListRequest.TRNIDIN_FIELD);
            assertThat(metadata.getLengthItem()).isEqualTo(FieldMetadata.LENGTH_ITEM_NONE);
            assertThat(metadata.isCursorPositionRequested()).isFalse();

            metadata.requestCursorPosition();

            assertThat(metadata.getLengthItem()).isEqualTo((short) -1);
            assertThat(metadata.isCursorPositionRequested()).isTrue();

            // And the run itself performs the move, on the field :105 names.
            TransactionListRequest running = request(true);
            controller.listTransactions(running, CicsAid.DFHPF12);
            assertThat(running.getMetadata(TransactionListRequest.TRNIDIN_FIELD).getLengthItem())
                    .isEqualTo((short) -1);
            assertThat(running.getCursorPositionField())
                    .isEqualTo(TransactionListRequest.TRNIDIN_FIELD);
        }

        // -----------------------------------------------------------------------------------------
        // Gate G33 - the OCCURS hazard. COBOL rows are 1-based, Java rows are 0-based, and the two
        // EVALUATE WS-IDX blocks at :390-445 and :452-505 are where the conversion has to be right.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("COBOL row 1 is Java row 0 and COBOL row 10 is Java row 9 (gate G33)")
        void theFirstAndLastRowsMapExactly() {
            aFullPageAndMore();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER);

            // rowIds walks FIRST_ROW..LAST_ROW, so its list index is the Java index and its position
            // in the walk is the COBOL WS-IDX.
            List<String> ids = rowIds(response);
            assertThat(TransactionListResponse.FIRST_ROW).isEqualTo(1);
            assertThat(TransactionListResponse.LAST_ROW).isEqualTo(10);
            assertThat(ids.get(0)).as("WHEN 1 at :391 - the first record read")
                    .isEqualTo(response.getRowTransactionId(TransactionListResponse.FIRST_ROW))
                    .isEqualTo(idOf(1));
            assertThat(ids.get(9)).as("WHEN 10 at :438 - the tenth record read")
                    .isEqualTo(response.getRowTransactionId(TransactionListResponse.LAST_ROW))
                    .isEqualTo(idOf(10));

            // The first row's five prefixes are row one's, not row zero's.
            assertThat(TransactionListResponse.rowFieldPrefixes(1))
                    .containsExactly("SEL0001", "TRNID01", "TDATE01", "TDESC01", "TAMT001");
            assertThat(TransactionListResponse.rowFieldPrefixes(10))
                    .containsExactly("SEL0010", "TRNID10", "TDATE10", "TDESC10", "TAMT010");
            // And row one's fields are the first five after the eight heading and control fields.
            assertThat(TransactionListResponse.fieldPrefixes()
                    .subList(TransactionListResponse.HEADER_FIELD_COUNT,
                            TransactionListResponse.HEADER_FIELD_COUNT
                                    + TransactionListResponse.ROW_FIELD_COUNT))
                    .containsExactlyElementsOf(TransactionListResponse.rowFieldPrefixes(1));
        }

        @ParameterizedTest(name = "row {0} is on the page and carries its own record")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("each of the ten WHEN arms fills its own row and no other (:390-442)")
        void everyRowArmFillsItsOwnRow(int oneBasedRow) {
            aFullPageAndMore();

            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHENTER);

            assertThat(response.getRowTransactionId(oneBasedRow)).isEqualTo(idOf(oneBasedRow));
            assertThat(response.getRowAmount(oneBasedRow))
                    .isEqualTo(controller.editedAmount(new BigDecimal(oneBasedRow + ".01")));
            assertThat(response.getRowDescription(oneBasedRow))
                    .isEqualTo(CODEC.movePicX("TRANSACTION " + oneBasedRow,
                            TransactionListResponse.TDESC_LENGTH));
            assertThat(response.getRowTransactionDate(oneBasedRow)).isEqualTo("07/19/22");
        }

        @ParameterizedTest(name = "row {0} is off the page")
        @ValueSource(ints = {0, 11})
        @DisplayName("COBOL indexes 0 and 11 are unreachable: rejected here, CONTINUE there (G33)")
        void rowsZeroAndElevenAreUnreachable(int offPage) {
            TransactionListResponse response = new TransactionListResponse();

            // The accessors reject, because they have no WHEN OTHER to reproduce and returning the
            // wrong row silently is the failure this gate exists to catch.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.getRowTransactionId(offPage));
            assertThatIllegalArgumentException().isThrownBy(() -> response.getRowAmount(offPage));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionListResponse.rowFieldPrefixes(offPage));
            // The request side refuses a subscript the same way, and names it a subscript: its guard
            // raises IndexOutOfBoundsException, which is what an OCCURS subscript outside its range is.
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.requireValidRow(offPage))
                    .withMessageContaining("1-based");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.selectionFieldName(offPage));
            assertThat(TransactionListRequest.requireValidRow(TransactionListResponse.FIRST_ROW))
                    .as("the guard returns the subscript unchanged so it reads inline")
                    .isEqualTo(1);
            assertThat(TransactionListRequest.requireValidRow(TransactionListResponse.LAST_ROW))
                    .isEqualTo(10);

            // The two paragraph reproductions do NOT reject: :443 and :503 are WHEN OTHER CONTINUE, so
            // an index off the page changes nothing at all.
            Map<String, String> before = new LinkedHashMap<>(response.payloadFieldValues());
            response.initializeTranData(offPage);
            response.populateTranData(CODEC, offPage, idOf(1), "07/19/22", "TRANSACTION 1",
                    "+00000001.01");
            assertThat(response.payloadFieldValues()).isEqualTo(before);

            // The loop bound itself is ten: the eleventh index is where PROCESS-PAGE-FORWARD stops.
            aFullPageAndMore();
            WorkArea ws = new WorkArea();
            controller.listTransactions(request(true), CicsAid.DFHENTER, ws);
            assertThat(ws.idx()).as(":297 leaves WS-IDX at eleven, one past the last row")
                    .isEqualTo(11);
        }

        // -----------------------------------------------------------------------------------------
        // Gate G34 - COTRN0AO REDEFINES COTRN0AI (COTRN00.CPY:373): two typed views, one backing span.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("the REDEFINES pair round-trips through both views over one backing span (G34)")
        void theRedefinesPairRoundTrips() {
            forward(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            TransactionListRequest request = request(true);
            request.setTrnidin(idOf(7));
            request.setSelection(3, "S");
            request.setTransactionId(3, idOf(3));
            request.setCursor(cursor(2, idOf(11), idOf(20), true, "S", idOf(13)));

            // The input view, written and read back over its own image.
            byte[] requestImage = request.toFixedWidth(StandardCharsets.US_ASCII);
            assertThat(requestImage).hasSize(TransactionListRequest.SYMBOLIC_MAP_LENGTH);
            TransactionListRequest requestAgain =
                    TransactionListRequest.fromFixedWidth(requestImage, StandardCharsets.US_ASCII);
            for (String prefix : copybookFieldNames()) {
                assertThat(requestAgain.getPayloadValue(prefix)).as("xxxI %s", prefix)
                        .isEqualTo(request.getPayloadValue(prefix));
            }

            // The output view, over its own image, with every item preserved.
            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHENTER);
            byte[] responseImage = response.toFixedWidth(CODEC);
            assertThat(responseImage).hasSize(TransactionListResponse.RECORD_LENGTH);
            TransactionListResponse responseAgain =
                    TransactionListResponse.fromFixedWidth(responseImage, StandardCharsets.US_ASCII);
            for (String prefix : copybookFieldNames()) {
                assertThat(responseAgain.payloadValue(prefix)).as("xxxO %s", prefix)
                        .isEqualTo(response.payloadValue(prefix));
            }

            // And the two views of one field really are one span: what RECEIVE MAP put into TRNIDINI is
            // what the TRNIDINO accessor reads, before :325 clears it.
            TransactionListResponse received = new TransactionListResponse();
            controller.receiveTrnlstScreen(request, new WorkArea(), received);
            assertThat(received.getTrnidinO()).isEqualTo(request.getTrnidin()).isEqualTo(idOf(7));
            assertThat(received.getRowSelection(3)).isEqualTo(request.getSelection(3)).isEqualTo("S");
        }

        @Test
        @DisplayName("the cursor and the commarea round-trip over their own spans too (G34)")
        void theCursorAndCommareaRoundTrip() {
            TransactionListCursor cursor = TransactionMenuController.cursorOf(
                    cursor(9, idOf(81), idOf(90), true, "S", idOf(85)));

            byte[] cursorImage = cursor.toFixedWidth(CODEC);
            assertThat(cursorImage).hasSize(TransactionListCursor.CURSOR_LENGTH);
            TransactionListCursor cursorAgain =
                    TransactionListCursor.fromFixedWidth(cursorImage, StandardCharsets.US_ASCII);
            assertThat(cursorAgain.getTrnidFirst()).isEqualTo(idOf(81));
            assertThat(cursorAgain.getTrnidLast()).isEqualTo(idOf(90));
            assertThat(cursorAgain.getPageNum()).isEqualTo(9);
            assertThat(cursorAgain.getNextPageFlg()).isEqualTo("Y");
            assertThat(cursorAgain.isNextPageYes()).isTrue();
            assertThat(cursorAgain.getTrnSelFlg()).isEqualTo("S");
            assertThat(cursorAgain.getTrnSelected()).isEqualTo(idOf(85));
            assertThat(cursorAgain.isRowSelected()).isTrue();

            PaginationCursor inbound = cursor(9, idOf(81), idOf(90), true, "S", idOf(85));
            byte[] inboundImage = inbound.toFixedWidth(StandardCharsets.US_ASCII);
            assertThat(inboundImage).hasSize(PaginationCursor.CURSOR_LENGTH);
            assertThat(PaginationCursor.fromFixedWidth(inboundImage, StandardCharsets.US_ASCII))
                    .isEqualTo(inbound);

            NavigationContext context = NavigationContext.empty()
                    .withPgmReenter()
                    .withFromTranid(TransactionMenuController.LIT_THIS_TRANID)
                    .withFromProgram(TransactionMenuController.LIT_THIS_PGM)
                    .withToProgram(TransactionMenuController.LIT_TRAN_VIEW_PGM);
            byte[] contextImage = context.toFixedWidth(CODEC);
            assertThat(contextImage).hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(CODEC, contextImage)).isEqualTo(context);
        }

        // -----------------------------------------------------------------------------------------
        // Gates G38 and G30 - the highlight belongs to the re-enter path, and the invalid-key text is
        // the copybook's fifty bytes and not the forty-byte title literal that reads like it.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("the CSSETATY highlight is DFHRED plus '*' and belongs to re-entry alone (G38)")
        void theHighlightBelongsToReentryAlone() {
            // The shared rule: a field that is not OK and blank is highlighted when, and only when,
            // the program is being re-entered - app/cpy/CSSETATY.cpy:18-26.
            FieldHighlight onReenter = FieldAttributeSetter.resolveFromFlags(true, true, true,
                    TransactionListResponse.TRNIDIN, TransactionListResponse.MAP_NAME);
            assertThat(onReenter.colourItemAssigned()).isTrue();
            assertThat(onReenter.outputItemAssigned()).isTrue();
            assertThat(onReenter.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(onReenter.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK)
                    .isEqualTo("*");
            assertThat(onReenter.colourItemName())
                    .isEqualTo(TransactionListResponse.TRNIDIN
                            + FieldAttributeSetter.COLOUR_ITEM_SUFFIX);

            FieldHighlight onEnter = FieldAttributeSetter.resolveFromFlags(true, true, false,
                    TransactionListResponse.TRNIDIN, TransactionListResponse.MAP_NAME);
            assertThat(onEnter.untouched()).as("the same blank field, painted on first entry").isTrue();
            assertThat(onEnter.colourItemAssigned()).isFalse();
            assertThat(onEnter.outputItemAssigned()).isFalse();

            // What COTRN00C does with that rule is nothing: it copies neither CSSETATY nor DFHATTR, so
            // no path of this screen writes a colour. Preserving that is the parity requirement - a
            // highlight this program never applies would be a new behaviour (practice B5).
            forward(1);
            TransactionListResponse painted =
                    controller.listTransactions(request(true), CicsAid.DFHENTER);
            for (String prefix : copybookFieldNames()) {
                assertThat(painted.isFieldHighlighted(prefix)).as("%s", prefix).isFalse();
                assertThat(painted.attributesOf(prefix).isColourRed()).as("%s", prefix).isFalse();
            }

            // And MOVE LOW-VALUES TO COTRN0AO at :114 is on the ENTER path alone, so a highlight that
            // arrived on the payload survives a re-entry and is cleared by a first entry.
            TransactionListResponse carried = new TransactionListResponse();
            carried.applyHighlight(TransactionListResponse.TRNIDIN, onReenter);
            assertThat(carried.isFieldHighlighted(TransactionListResponse.TRNIDIN)).isTrue();
            assertThat(carried.attributesOf(TransactionListResponse.TRNIDIN).isColourRed()).isTrue();
            controller.moveLowValuesToOutputMap(carried);
            assertThat(carried.isFieldHighlighted(TransactionListResponse.TRNIDIN))
                    .as(":114 resets every attribute, and it runs on the ENTER path only")
                    .isFalse();
            assertThat(carried.attributesOf(TransactionListResponse.TRNIDIN).isLowValues()).isTrue();
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY is the copybook's fifty bytes, and not the X(40) title")
        void theInvalidKeyTextIsTheCopybooksFiftyBytes() {
            // app/cpy/CSMSG01Y.cpy:20-21 - forty characters of content, nine spaces inside the quotes
            // and one more supplied by PIC X(50).
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .isEqualTo("Invalid key pressed. Please see below..." + " ".repeat(10))
                    .hasSize(50);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.strip())
                    .isEqualTo("Invalid key pressed. Please see below...")
                    .hasSize(40);

            // Deliberately distinct from COTTL01Y's X(40) courtesy line, which is a different literal
            // in a different copybook and belongs to no path of this screen.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isEqualTo("Thank you for using CCDA application... ")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(40)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);

            // And the WHEN OTHER arm at :129-133 puts exactly those fifty bytes on the error line,
            // space-padded into ERRMSG's X(78) and truncated nowhere.
            WorkArea ws = new WorkArea();
            TransactionListResponse response =
                    controller.listTransactions(request(true), CicsAid.DFHPF12, ws);

            assertThat(ws.isErrFlgOn()).isTrue();
            assertThat(response.getErrmsgO())
                    .hasSize(TransactionListResponse.ERRMSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .isEqualTo(CODEC.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                            TransactionListResponse.ERRMSG_LENGTH));
            assertThat(response.getErrmsgO().substring(0, 50))
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("the selection navigates by naming COTRN01C in the payload, never by forwarding")
        void theSelectionNamesTheNextProgramInThePayload() {
            TransactionListRequest request = request(true);
            request.setSelection(3, "S");
            request.setTransactionId(3, idOf(3));

            TransactionListResponse response = controller.listTransactions(request, CicsAid.DFHENTER);

            // :188-195 is an XCTL, and the stateless projection of an XCTL is a response field the
            // client acts on (gate G40). The sibling screen's payload types are deliberately not
            // imported here: the target travels as a plain program name.
            assertThat(response.getNextProgram().strip()).isEqualTo("COTRN01C")
                    .isEqualTo(TransactionMenuController.LIT_TRAN_VIEW_PGM);
            assertThat(response.getCursor().getTrnSelFlg()).isEqualTo("S");
            assertThat(response.getCursor().getTrnSelected()).isEqualTo(idOf(3));
            assertThat(response.getNavigationContext().isEnter())
                    .as(":191 MOVE 0 TO CDEMO-PGM-CONTEXT - the next screen is entered fresh")
                    .isTrue();
            // Nothing was browsed: the transfer happens before PROCESS-ENTER-KEY reaches the browse.
            verify(repository, never()).startBrowse(any(BrowseDirection.class));
            verify(repository, never()).startBrowse(anyString(), any(BrowseDirection.class));
        }
    }


    /**
     * A mocked {@code TRANSACT} browse whose {@code STARTBR} positioned successfully.
     *
     * <p>{@link TransactionRepository#startBrowse(TransactionRepository.BrowseDirection)} issues the
     * position as a real operation and reports what it found, so a handle carries a positioning outcome
     * that its caller's {@code EVALUATE WS-RESP-CD} branches on. A bare mock reports {@code null} for it,
     * which is not a state a real handle can be in - so every mock is built here with the successful arm
     * stubbed, and a test that wants {@code NOTFND} or {@code WHEN OTHER} re-stubs it.
     *
     * @return the mock; never {@code null}
     */
    private static Browse positionedBrowse() {
        Browse handle = mock(Browse.class);
        when(handle.positioningResult()).thenReturn(
                TransactionRepository.ReadResult.found(TransactionRepository.CICS_FILE_NAME,
                        new com.vsergeychik.carddemo.transaction.model.TranRecord(
                                java.nio.charset.StandardCharsets.US_ASCII)));
        when(handle.positioningOutcome()).thenReturn(Outcome.OK);
        when(handle.isStarted()).thenReturn(true);
        return handle;
    }

}
