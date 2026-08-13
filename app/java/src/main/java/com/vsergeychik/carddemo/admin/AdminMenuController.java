package com.vsergeychik.carddemo.admin;

import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuInput;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.AdminMenuRequest;
import com.vsergeychik.carddemo.admin.dto.AdminMenuResponse;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CA00} - program {@code COADM01C}, "Admin Menu for Admin users" - as a stateless
 * REST resource on {@link #ADMIN_MENU_PATH}.
 *
 * <p>{@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC '} is declared at line 39 and never referenced again,
 * and {@code COPY CSUSR01Y.} at line 58 brings in a record whose every field goes unused;
 * {@link AdminMenuService} preserves both as the dead declarations they are.
 */
@RestController
public class AdminMenuController {
    public static final String ADMIN_MENU_PATH = "/api/admin/menu";

    private static final String SPACE = " ";

    // COBOL WORKING-STORAGE must not become static Java state: this bean is a singleton serving concurrent
    // requests, so per- request values live on the stack and only immutable collaborators live on the
    // instance.

    private final AdminMenuService adminMenuService;

    private final Clock clock;

    private final FixedWidthCodec codec;

    private final String optionNotTransmitted;

    private final AdminMenuRequest coldStartRequest;

    public AdminMenuController(final AdminMenuService adminMenuService, final Clock clock) {
        this.adminMenuService = Objects.requireNonNull(adminMenuService,
                "An AdminMenuService is required: it is the translated COADM01C, and this controller "
                        + "makes no decision of its own");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO at app/cbl/COADM01C.cbl:202-221 renders the "
                        + "date and time header, and reading the wall clock directly would make that "
                        + "header impossible to assert byte for byte");
        this.codec = this.adminMenuService.codec();
        this.optionNotTransmitted = ScreenFieldImage.unpainted(AdminMenuRequest.OPTION_LENGTH);
        this.coldStartRequest = coldStartRequest(this.codec);
    }

    private static AdminMenuRequest coldStartRequest(final FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to size the cold-start payload");
        final String transactionField = ScreenFieldImage.unpainted(AdminMenuRequest.TRN_NAME_LENGTH);
        final String titleField = ScreenFieldImage.unpainted(AdminMenuRequest.TITLE_LENGTH);
        final String dateField = ScreenFieldImage.unpainted(AdminMenuRequest.CUR_DATE_LENGTH);
        final String programField = ScreenFieldImage.unpainted(AdminMenuRequest.PGM_NAME_LENGTH);
        final String timeField = ScreenFieldImage.unpainted(AdminMenuRequest.CUR_TIME_LENGTH);
        final String optionLine = ScreenFieldImage.unpainted(AdminMenuRequest.OPTION_LINE_LENGTH);
        final String optionField = ScreenFieldImage.unpainted(AdminMenuRequest.OPTION_LENGTH);
        final String messageField = ScreenFieldImage.unpainted(AdminMenuRequest.ERR_MSG_LENGTH);
        return new AdminMenuRequest(transactionField,
                titleField,
                dateField,
                programField,
                titleField,
                timeField,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionLine,
                optionField,
                messageField,
                null,
                CicsAid.DFHENTER);
    }

    /**
     * Paints the admin menu: {@code GET} {@link #ADMIN_MENU_PATH}, transaction {@code CA00}, program
     * {@code COADM01C}, mapset {@code COADM01}, map {@code COADM1A}, twenty fields.
     *
     * <p>The parameter is {@code required = false}, so a plain {@code GET} arrives {@code null} and is
     * treated as {@code EIBCALEN = 0} - the transaction typed at a clear screen, which
     * {@code app/cbl/COADM01C.cbl:82-84} answers by diverting to the sign-on screen.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @return the painted screen, the communication area to send back next time, the navigation triple
     *     naming where the client goes if control transferred, and - beside the screen rather than inside it
     */
    @GetMapping(path = ADMIN_MENU_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<AdminMenuResponse> getAdminMenu(
            @Valid @RequestBody(required = false) final AdminMenuRequest request) {
        AdminMenuResponse painted = showAdminMenu(Objects.requireNonNullElse(request, coldStartRequest));
        return ScreenResponse.of(painted, painted.screenMetadata());
    }

    AdminMenuResponse showAdminMenu(final AdminMenuRequest received) {
        Objects.requireNonNull(received, "A payload is required here; an absent body is represented by "
                + "the cold-start request, whose communication area is null, and not by a null "
                + "payload");
        return toResponse(adminMenuService.handle(toInput(received)));
    }

    AdminMenuInput toInput(final AdminMenuRequest received) {
        final NavigationContext inboundCommarea = received.navigationContext();
        return new AdminMenuInput(inboundCommarea,
                received.eibAid(),
                Objects.requireNonNullElse(received.option(), optionNotTransmitted));
    }

    AdminMenuResponse toResponse(final AdminMenuOutcome outcome) {
        Objects.requireNonNull(outcome, "An outcome is required: AdminMenuService.handle always "
                + "returns one, on every path COADM01C can take");

        // DateHeader owns all of it - including the '/' and ':' separators, the WS-CURDATE-YEAR(3:2)
        // two-digit year and the truncation of the intrinsic's 21-character result to the 16-byte receiver
        // - and reads the injected clock exactly once, so the date and the time cannot straddle a second
        // boundary.
        final DateHeader header = DateHeader.from(codec, clock);

        return AdminMenuResponse.builder()
                .trnName(AdminMenuService.TRANSACTION_ID)
                // Byte-exact from the copybook, six leading and seven trailing spaces, never retyped here.
                .title01(ScreenTitles.CCDA_TITLE01)
                .curDate(header.wsCurdateMmDdYy())
                .pgmName(AdminMenuService.PROGRAM_NAME)
                // The LIVE line of COTTL01Y - the alternative directly above it is commented out in the
                // copybook and must not be used. ScreenTitles has already resolved that; it is not
                // second-guessed with a hand-typed literal.
                .title02(ScreenTitles.CCDA_TITLE02)
                .curTime(header.wsCurtimeHhMmSs())

                // ---- BUILD-MENU-OPTIONS, app/cbl/COADM01C.cbl:226-263 The EVALUATE WS-IDX at L238-L261
                // has arms for subscripts 1 to 10 and WHEN OTHER CONTINUE, and CDEMO-ADMIN-OPT-COUNT is 4 -
                // so this program composes four lines, could reach ten, and can never write the last two.
                .optn001(outcome.optionLine(1))
                .optn002(outcome.optionLine(2))
                .optn003(outcome.optionLine(3))
                .optn004(outcome.optionLine(4))
                .optn005(outcome.optionLine(5))
                .optn006(outcome.optionLine(6))
                .optn007(outcome.optionLine(7))
                .optn008(outcome.optionLine(8))
                .optn009(outcome.optionLine(9))
                .optn010(outcome.optionLine(10))
                .optn011(outcome.optionLine(11))
                .optn012(outcome.optionLine(12))

                .option(outcome.option())

                .errMsg(codec.movePicX(outcome.message(), AdminMenuResponse.ERR_MSG_LENGTH))

                .navigationContext(commareaHandedOn(outcome))

                .nextProgram(outcome.nextProgram())
                .nextMapset(outcome.mapsetName())
                .nextMap(outcome.mapName())

                // The byte is carried as the attribute metadata the copybook declares it to be, and is
                // passed through exactly as the service resolved it.
                .messageColour(outcome.messageColour())
                .resetAllOutputFields(outcome.resetAllOutputFields())
                .build();
    }

    private static NavigationContext commareaHandedOn(final AdminMenuOutcome outcome) {
        if (outcome.hasNextProgram() && !outcome.nextProgramCarriesCommarea()) {
            return null;
        }
        return outcome.navigationContext();
    }
}
