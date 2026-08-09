package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
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
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.TransactionListCursor;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        browse = mock(Browse.class);
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

            assertThat(ws.startbrOutcome()).isEqualTo(Outcome.END_OF_FILE);
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
        @DisplayName("a duplicate-key response is neither NORMAL nor ENDFILE, so it is WHEN OTHER")
        void duplicateIsWhenOther() {
            stub(true, ReadResult.duplicate(FILE, tran(1)), endOfFile());
            WorkArea ws = new WorkArea();

            controller.listTransactions(request(true), CicsAid.DFHENTER, ws);

            assertThat(ws.startbrOutcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(ws.isErrFlgOn()).isTrue();
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
            request.setAid(AidKey.PFK07.token());
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
        @DisplayName("an unrecognised token is no key at all, which is the WHEN OTHER arm")
        void unrecognisedTokenIsNoKey() {
            assertThat(controller.aidByteOfToken("ZZZZZ")).isEqualTo(CicsAid.DFHNULL);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL)).isEmpty();
        }

        @ParameterizedTest(name = "{0} round-trips")
        @EnumSource(AidKey.class)
        @DisplayName("every one of the sixteen tokens maps to a byte the resolver maps back")
        void everyTokenRoundTrips(AidKey key) {
            byte resolved = controller.aidByteOfToken(key.token());
            assertThat(PfKeyResolver.resolve(resolved)).contains(key);
        }

        @Test
        @DisplayName("a short token is padded by the PIC X move rule before it is matched")
        void shortTokenIsPadded() {
            assertThat(controller.aidByteOfToken("PA1")).isEqualTo(CicsAid.DFHPA1);
            assertThat(AidKey.PA1.token()).isEqualTo("PA1  ");
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

            assertThat(handle.positioningOutcome().isEndOfFile()).isTrue();
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
                    com.vsergeychik.carddemo.common.FieldAttributeSetter.resolveFromFlags(
                            true, true, false, TransactionListResponse.TRNIDIN,
                            TransactionListResponse.MAP_NAME));

            controller.moveLowValuesToOutputMap(response);

            assertThat(TransactionListResponse.fieldPrefixes())
                    .hasSize(TransactionListResponse.FIELD_COUNT);
            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                assertThat(response.payloadValue(prefix))
                        .as("%s", prefix)
                        .isEqualTo(CODEC.movePicX("",
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
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> responseMembers = membersOf(mapper, new TransactionListResponse());
            List<String> requestMembers = membersOf(mapper, request(true));

            assertThat(TransactionListResponse.fieldPrefixes()).hasSize(59);
            assertThat(TransactionListRequest.FIELD_NAMES)
                    .containsExactlyElementsOf(TransactionListResponse.fieldPrefixes());

            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                // The xxxI item is the request member and the xxxO item is the response member: those
                // two carry the field's value and its authoritative PIC X(n) width.
                assertThat(named(requestMembers, prefix)).as("request payload %s", prefix).hasSize(1);
                assertThat(named(responseMembers, prefix + "O")).as("response payload %s", prefix)
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

        private List<String> membersOf(com.fasterxml.jackson.databind.ObjectMapper mapper,
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
            String body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(request(true));

            mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(body)
                            .param(TransactionMenuController.EIBAID_PARAM,
                                    String.valueOf(ENTER_PARAM)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnnameO").value("CT00"))
                    .andExpect(jsonPath("$.pagenumO").value("00000001"))
                    .andExpect(jsonPath("$.trnid01O").value(idOf(1)))
                    .andExpect(jsonPath("$.trnid10O").value(idOf(10)));
        }

        @Test
        @DisplayName("either accepted spelling of the AID parameter names the same key, and a "
                + "contradiction between them is refused")
        void eitherAidSpellingNamesTheSameKey() throws Exception {
            String body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(request(true));
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
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(body)
                                .param(name, enter))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.trnid01O").value(idOf(1)));
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
            String body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(request(true));

            mockMvc().perform(get(TransactionMenuController.TRANSACTIONS_PATH)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(body)
                            .param(TransactionMenuController.EIBAID_PARAM,
                                    String.valueOf(ENTER_PARAM)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnid01O").value(idOf(1)))
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
            onEnter.setAid(AidKey.ENTER.token());

            TransactionListResponse painted = controller.listTransactions(onEnter);

            assertThat(painted.getRowTransactionId(1)).isEqualTo(idOf(1));
            assertThat(painted.getPagenumO()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("the one-argument entry point honours a function key token")
        void theOneArgumentEntryPointHonoursAFunctionKey() {
            TransactionListRequest request = request(true);
            request.setAid(AidKey.PFK03.token());

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
}
