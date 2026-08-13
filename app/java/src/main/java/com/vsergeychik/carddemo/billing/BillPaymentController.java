package com.vsergeychik.carddemo.billing;

import com.vsergeychik.carddemo.billing.BillPaymentService.PaymentState;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CB00} - program {@code COBIL00C}, "Bill Payment" - as a stateless REST resource
 * on {@link #BILL_PAY_PATH}.
 *
 * <p>The program does not copy {@code CSSETATY}, never writes {@code DFHRED} and never writes an asterisk
 * into a field.
 */
@RestController
public final class BillPaymentController {
    /**
     * The route, {@value}: {@code POST} to it is one invocation of transaction {@code CB00}.
     */
    public static final String BILL_PAY_PATH = "/api/billpay";

    static final String AID_MEMBER = "aid";

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} - {@code app/cbl/COBIL00C.cbl:37}.
     */
    public static final String PROGRAM_NAME = BillPaymentService.WS_PGMNAME;

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CB00'} - {@code app/cbl/COBIL00C.cbl:38}.
     */
    public static final String TRANSACTION_ID = BillPaymentService.WS_TRANID;

    /**
     * {@code MAPSET('COBIL00')} - the operand of both the {@code SEND} at {@code app/cbl/COBIL00C.cbl:297}
     * and the {@code RECEIVE} at {@code :310}.
     */
    public static final String MAPSET_NAME = BillPaymentResponse.MAPSET_NAME;

    /**
     * {@code MAP('COBIL0A')} - the operand of both the {@code SEND} at {@code app/cbl/COBIL00C.cbl:296} and
     * the {@code RECEIVE} at {@code :309}.
     */
    public static final String MAP_NAME = BillPaymentResponse.MAP_NAME;

    /**
     * {@code 'COSGN00C'} - the sign-on program, and the target of two distinct arms.
     */
    public static final String SIGN_ON_PROGRAM = BillPaymentResponse.SIGN_ON_PROGRAM;

    /**
     * {@code 'COMEN01C'} - the main menu, and the {@code DFHPF3} fallback of
     * {@code app/cbl/COBIL00C.cbl:130} when {@code CDEMO-FROM-PROGRAM} names no caller to go back to.
     */
    public static final String MAIN_MENU_PROGRAM = BillPaymentResponse.MAIN_MENU_PROGRAM;

    public static final char SPACE = ' ';

    /**
     * The {@code LOW-VALUES} figurative constant, one character of it: {@code X'00'}.
     */
    public static final char LOW_VALUE = '\u0000';

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    private final BillPaymentService billPaymentService;

    private final Clock clock;

    private final FixedWidthCodec codec;

    public BillPaymentController(final BillPaymentService billPaymentService, final Clock clock) {
        this.billPaymentService = Objects.requireNonNull(billPaymentService,
                "A BillPaymentService is required: it is the translated PROCESS-ENTER-KEY of "
                        + "app/cbl/COBIL00C.cbl:154-244, and this controller makes no decision about "
                        + "the payment itself");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO at app/cbl/COBIL00C.cbl:319-338 renders the "
                        + "date and time header from FUNCTION CURRENT-DATE, and reading the wall clock "
                        + "directly would make that header impossible to assert byte for byte");
        this.codec = Objects.requireNonNull(billPaymentService.codec(),
                "The service reported no fixed-width codec. Every image this controller renders - the "
                        + "eleven-character account field, the seventy-eight-character message line, "
                        + "the eight-character date and time - is fixed-width, so the code page is "
                        + "always stated explicitly by configuration and never taken from the platform "
                        + "default");
    }

    /**
     * Runs one invocation of {@code COBIL00C}: {@code POST} {@link #BILL_PAY_PATH}, transaction
     * {@code CB00}, mapset {@code COBIL00}, map {@code COBIL0A}, ten fields.
     *
     * <p>Always answers {@code 200 OK}, because every path through {@code COBIL00C} ends in either
     * {@code EXEC CICS RETURN} or {@code EXEC CICS XCTL} and both are successful outcomes.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start;
     *     validated against the symbolic map's declared widths
     * @param eibaid the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value, or {@code null}
     *     when the request names no key
     * @param eibAid the accepted alternate spelling of the same parameter
     * @return the outbound screen: the ten map members, the communication area to send back next time, the
     *     six communication-area extension members
     */
    @PostMapping(path = BILL_PAY_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<BillPaymentResponse> payBill(
            @Valid @RequestBody(required = false) final BillPaymentRequest request,
            @RequestParam(name = AidRequestParameter.CANONICAL_NAME, required = false)
            final Integer eibaid,
            @RequestParam(name = AidRequestParameter.ALTERNATE_NAME, required = false)
            final Integer eibAid) {
        final BillPaymentRequest received =
                Objects.requireNonNullElse(request, new BillPaymentRequest());
        final BillPaymentResponse response =
                mainPara(received, AidRequestParameter.resolve(eibaid, eibAid)).response();
        return ScreenResponse.of(response, screenMetadataOf(response, received));
    }

    private static ScreenMetadata screenMetadataOf(final BillPaymentResponse response,
                                                   final BillPaymentRequest received) {
        final CursorField cursor = response.getCursorField();
        final String cursorLabel = cursor == null || cursor == CursorField.NONE ? null : cursor.name();
        final String highlight = response.getMessageHighlight();
        final byte colour = highlight == null || highlight.isEmpty()
                ? BmsAttributes.DFHDFCOL
                : (byte) highlight.charAt(0);
        final NavigationContext passed = received.getNavigationContext();
        final boolean lowValuesMoved = passed != null && !passed.isReenter();
        return ScreenMetadata.of(cursorLabel, colour, lowValuesMoved);
    }

    Invocation mainPara(final BillPaymentRequest request) {
        return mainPara(request, null);
    }

    Invocation mainPara(final BillPaymentRequest request, final Integer statedAid) {
        Objects.requireNonNull(request, "A payload is required: COBIL00C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it. An "
                + "absent body is represented by an empty payload whose communication area is null, "
                + "and not by a null payload");

        final BillPaymentResponse response = new BillPaymentResponse();
        response.setErrMsg(spaces(BillPaymentResponse.ERR_MSG_LENGTH));

        final NavigationContext passed = request.getNavigationContext();

        // No communication area travelled, so :111 never runs and CARDDEMO-COMMAREA stays in its
        // WORKING-STORAGE initial state. Note what else does not run: the symbolic map is never cleared and
        // POPULATE-HEADER-INFO is never performed, because SEND-BILLPAY-SCREEN is the only caller of it and
        // this arm does not send.
        if (passed == null) {
            final PaymentState state = new PaymentState(codec, NavigationContext.empty());
            final NavigationContext coldStart =
                    NavigationContext.empty().withToProgram(SIGN_ON_PROGRAM);
            response.setNavigationContext(returnToPrevScreen(response, coldStart));
            return new Invocation(response, state);
        }

        if (!passed.isReenter()) {
            return firstEntry(response, request, passed);
        }

        return reentry(response, request, passed, statedAid);
    }

    private Invocation firstEntry(final BillPaymentResponse response,
                                  final BillPaymentRequest request,
                                  final NavigationContext passed) {
        final NavigationContext commarea = passed.withPgmReenter();

        final PaymentState state = new PaymentState(codec, commarea);
        state.setActIdIn(lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH));
        state.setCurBal(lowValues(BillPaymentResponse.CUR_BAL_LENGTH));
        state.setConfirm(lowValues(BillPaymentResponse.CONFIRM_LENGTH));
        state.setErrMsg(lowValues(BillPaymentResponse.ERR_MSG_LENGTH));

        state.setCursorField(CursorField.ACTIDIN);

        final String trnSelected =
                materialise(request.getTrnSelected(), BillPaymentResponse.TRN_SELECTED_LENGTH);
        PaymentState terminal = state;
        if (!isSpacesOrLowValues(trnSelected)) {
            terminal = billPaymentService.processEnterKey(
                    codec.movePicX(trnSelected, BillPaymentResponse.ACT_ID_IN_LENGTH),
                    state.confirm(),
                    commarea);
        }

        billPaymentService.sendBillpayScreen(terminal);
        projectSentScreen(response, terminal);
        echoCommarea(response, request, commarea);
        return new Invocation(response, terminal);
    }

    private Invocation reentry(final BillPaymentResponse response,
                               final BillPaymentRequest request,
                               final NavigationContext passed,
                               final Integer statedAid) {
        final PaymentState state = new PaymentState(codec, passed);
        receiveBillpayScreen(response, state, request);

        final byte eibAid = resolveEibAid(statedAid, request.getAid());

        if (PfKeyResolver.isEnter(eibAid)) {
            final PaymentState afterEntry = billPaymentService.processEnterKey(
                    state.actIdIn(), state.confirm(), passed);
            projectSentScreen(response, afterEntry);
            echoCommarea(response, request, passed);
            return new Invocation(response, afterEntry);
        } else if (PfKeyResolver.isPf3(eibAid)) {
            NavigationContext commarea = passed;
            if (isSpacesOrLowValues(commarea.fromProgram())) {
                commarea = commarea.withToProgram(MAIN_MENU_PROGRAM);
            } else {
                commarea = commarea.withToProgram(commarea.fromProgram());
            }
            echoCommarea(response, request, returnToPrevScreen(response, commarea));
            return new Invocation(response, state);
        } else if (PfKeyResolver.isPf4(eibAid)) {
            billPaymentService.clearCurrentScreen(state);
            projectSentScreen(response, state);
            echoCommarea(response, request, passed);
            return new Invocation(response, state);
        } else {
            billPaymentService.invalidKeyPressed(state);
            projectSentScreen(response, state);
            echoCommarea(response, request, passed);
            return new Invocation(response, state);
        }
    }

    NavigationContext returnToPrevScreen(final BillPaymentResponse response,
                                         final NavigationContext commarea) {
        NavigationContext stamped = commarea;

        if (isSpacesOrLowValues(stamped.toProgram())) {
            stamped = stamped.withToProgram(SIGN_ON_PROGRAM);
        }

        stamped = stamped
                .withFromTranid(TRANSACTION_ID)
                .withFromProgram(PROGRAM_NAME)
                .withPgmEnter();

        response.setNextProgram(stamped.toProgram());
        return stamped;
    }

    void receiveBillpayScreen(final BillPaymentResponse response,
                              final PaymentState state,
                              final BillPaymentRequest request) {
        // Received because they carry FSET, and held on the payload rather than on the working storage
        // because the program has no working-storage item for any of them: it writes them from literals and
        // from FUNCTION CURRENT-DATE in POPULATE-HEADER-INFO and never reads them.
        response.setTrnName(materialise(request.getTrnName(), BillPaymentResponse.TRN_NAME_LENGTH));
        response.setTitle01(materialise(request.getTitle01(), BillPaymentResponse.TITLE01_LENGTH));
        response.setCurDate(materialise(request.getCurDate(), BillPaymentResponse.CUR_DATE_LENGTH));
        response.setPgmName(materialise(request.getPgmName(), BillPaymentResponse.PGM_NAME_LENGTH));
        response.setTitle02(materialise(request.getTitle02(), BillPaymentResponse.TITLE02_LENGTH));
        response.setCurTime(materialise(request.getCurTime(), BillPaymentResponse.CUR_TIME_LENGTH));

        state.setActIdIn(materialise(request.getActIdIn(), BillPaymentResponse.ACT_ID_IN_LENGTH));
        state.setCurBal(materialise(request.getCurBal(), BillPaymentResponse.CUR_BAL_LENGTH));
        state.setConfirm(materialise(request.getConfirm(), BillPaymentResponse.CONFIRM_LENGTH));
        state.setErrMsg(materialise(request.getErrMsg(), BillPaymentResponse.ERR_MSG_LENGTH));
        response.setActIdIn(state.actIdIn());
        response.setCurBal(state.curBal());
        response.setConfirm(state.confirm());
        response.setErrMsg(state.errMsg());

        state.setResponseCodes(FileStatus.NORMAL, FileStatus.NO_REASON_CODE);
    }

    void projectSentScreen(final BillPaymentResponse response, final PaymentState state) {
        populateHeaderInfo(response);

        response.setActIdIn(state.actIdIn());
        response.setCurBal(state.curBal());
        response.setConfirm(state.confirm());
        response.setErrMsg(state.errMsg());

        response.setCursorField(state.cursorField());
        response.setMessageHighlight(state.messageHighlight());

        response.setNextMapset(MAPSET_NAME);
        response.setNextMap(MAP_NAME);
    }

    void populateHeaderInfo(final BillPaymentResponse response) {
        final DateHeader header = DateHeader.from(codec, clock);

        response.setTitle01(ScreenTitles.CCDA_TITLE01);
        response.setTitle02(ScreenTitles.CCDA_TITLE02);
        response.setTrnName(TRANSACTION_ID);
        response.setPgmName(PROGRAM_NAME);
        response.setCurDate(header.wsCurdateMmDdYy());
        response.setCurTime(header.wsCurtimeHhMmSs());
    }

    void echoCommarea(final BillPaymentResponse response,
                      final BillPaymentRequest request,
                      final NavigationContext commarea) {
        response.setNavigationContext(commarea);
        response.setTrnIdFirst(echoAtWidth(request.getTrnIdFirst(),
                BillPaymentResponse.TRN_ID_FIRST_LENGTH));
        response.setTrnIdLast(echoAtWidth(request.getTrnIdLast(),
                BillPaymentResponse.TRN_ID_LAST_LENGTH));
        response.setPageNum(request.getPageNum());
        response.setNextPageFlg(echoAtWidth(request.getNextPageFlg(),
                BillPaymentResponse.NEXT_PAGE_FLG_LENGTH));
        response.setTrnSelFlg(echoAtWidth(request.getTrnSelFlg(),
                BillPaymentResponse.TRN_SEL_FLG_LENGTH));
        response.setTrnSelected(echoAtWidth(request.getTrnSelected(),
                BillPaymentResponse.TRN_SELECTED_LENGTH));
    }

    private String echoAtWidth(final String value, final int width) {
        if (value == null) {
            return spaces(width);
        }
        return codec.movePicX(value, width);
    }

    byte eibAidOf(final String aid) {
        if (aid == null || aid.isEmpty()) {
            return CicsAid.DFHENTER;
        }
        if (aid.length() != RAW_AID_LENGTH) {
            return CicsAid.DFHNULL;
        }
        final char stated = aid.charAt(0);
        if (stated > MAX_AID_CODE_POINT) {
            return CicsAid.DFHNULL;
        }
        return (byte) stated;
    }

    byte resolveEibAid(final Integer statedAid, final String aid) {
        if (statedAid == null) {
            return eibAidOf(aid);
        }
        return AidRequestParameter.requireStatedAid(AID_MEMBER, statedAid, aid, codec);
    }

    static boolean isSpacesOrLowValues(final String value) {
        if (value == null) {
            return true;
        }
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character != SPACE) {
                allSpaces = false;
            }
            if (character != LOW_VALUE) {
                allLowValues = false;
            }
        }
        return allSpaces || allLowValues;
    }

    private String materialise(final String value, final int width) {
        if (value == null) {
            return lowValues(width);
        }
        return codec.movePicX(value, width);
    }

    static String spaces(final int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    static String lowValues(final int width) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        return ScreenFieldImage.unpainted(width);
    }

    /**
     * The outcome of one invocation of {@code COBIL00C}: the payload the client receives, and the working
     * storage the program left behind.
     *
     * @param response the payload the client receives; never {@code null}
     * @param state the working storage as the program left it; never {@code null}
     */
    record Invocation(BillPaymentResponse response, PaymentState state) {
        Invocation {
            Objects.requireNonNull(response, "A response is required: every path through COBIL00C ends "
                    + "in either EXEC CICS RETURN or EXEC CICS XCTL, and both hand a payload back");
            Objects.requireNonNull(state, "A PaymentState is required: it holds the WORKING-STORAGE of "
                    + "app/cbl/COBIL00C.cbl:36-85 for this invocation, including the send count and the "
                    + "three flags the screen cannot show");
        }
    }
}
