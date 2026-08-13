package com.vsergeychik.carddemo.admin;

import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuInput;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.MainMenuRequest;
import com.vsergeychik.carddemo.admin.dto.MainMenuResponse;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
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
 * CICS transaction {@code CM00} - program {@code COMEN01C}, "Main Menu for the Regular users" - as a
 * stateless REST resource on {@link #MAIN_MENU_PATH}.
 *
 * <p>Two consequences a reviewer should read as intended, not as omissions: Injecting the security-user
 * repository here would invent a {@code USRSEC} read the COBOL never performs.
 */
@RestController
public class MainMenuController {
    public static final String MAIN_MENU_PATH = "/api/menu";

    private static final String SPACE = " ";

    private static final String NO_CURSOR_REQUEST = null;

    // COBOL WORKING-STORAGE must not become static Java state: this bean is a singleton serving concurrent
    // requests, so per-request values live on the stack and only immutable collaborators live on the
    // instance.

    private final MainMenuService mainMenuService;

    private final Clock clock;

    private final FixedWidthCodec codec;

    private final String optionNotTransmitted;

    private final MainMenuRequest coldStartRequest;

    public MainMenuController(final MainMenuService mainMenuService, final Clock clock) {
        this.mainMenuService = Objects.requireNonNull(mainMenuService,
                "A MainMenuService is required: it is the translated COMEN01C, and this controller "
                        + "makes no decision of its own");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO at app/cbl/COMEN01C.cbl:212-231 renders the "
                        + "date and time header, and reading the wall clock directly would make that "
                        + "header impossible to assert byte for byte");
        this.codec = this.mainMenuService.codec();
        this.optionNotTransmitted = ScreenFieldImage.unpainted(MainMenuRequest.OPTION_LENGTH);
        this.coldStartRequest = coldStartRequest(this.codec);
    }

    private static MainMenuRequest coldStartRequest(final FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to size the cold-start payload");
        final String transactionField = ScreenFieldImage.unpainted(MainMenuRequest.TRN_NAME_LENGTH);
        final String titleField = ScreenFieldImage.unpainted(MainMenuRequest.TITLE_LENGTH);
        final String dateField = ScreenFieldImage.unpainted(MainMenuRequest.CUR_DATE_LENGTH);
        final String programField = ScreenFieldImage.unpainted(MainMenuRequest.PGM_NAME_LENGTH);
        final String timeField = ScreenFieldImage.unpainted(MainMenuRequest.CUR_TIME_LENGTH);
        final String optionLine = ScreenFieldImage.unpainted(MainMenuRequest.OPTION_LINE_LENGTH);
        final String optionField = ScreenFieldImage.unpainted(MainMenuRequest.OPTION_LENGTH);
        final String messageField = ScreenFieldImage.unpainted(MainMenuRequest.ERR_MSG_LENGTH);
        return new MainMenuRequest(transactionField,
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
     * Paints the main menu: {@code GET} {@link #MAIN_MENU_PATH}, transaction {@code CM00}, program
     * {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}, twenty fields.
     *
     * <p>The parameter is {@code required = false}, so a plain {@code GET} arrives {@code null} and is
     * treated as {@code EIBCALEN = 0} - the transaction typed at a clear screen, which
     * {@code app/cbl/COMEN01C.cbl:82-84} answers by diverting to the sign-on screen.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @return the painted screen, the communication area to send back next time, the navigation triple
     *     naming where the client goes if control transferred, and - beside the screen rather than inside it
     */
    @GetMapping(path = MAIN_MENU_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<MainMenuResponse> getMainMenu(
            @Valid @RequestBody(required = false) final MainMenuRequest request) {
        final MainMenuResponse painted =
                showMainMenu(Objects.requireNonNullElse(request, coldStartRequest));
        return ScreenResponse.of(painted, screenMetadata(painted));
    }

    MainMenuResponse showMainMenu(final MainMenuRequest received) {
        Objects.requireNonNull(received, "A payload is required here; an absent body is represented by "
                + "the cold-start request, whose communication area is null, and not by a null "
                + "payload");
        return toResponse(mainMenuService.handle(toInput(received)));
    }

    MainMenuInput toInput(final MainMenuRequest received) {
        final NavigationContext inboundCommarea = received.navigationContext();
        return new MainMenuInput(inboundCommarea,
                received.eibAid(),
                Objects.requireNonNullElse(received.option(), optionNotTransmitted));
    }

    MainMenuResponse toResponse(final MainMenuOutcome outcome) {
        Objects.requireNonNull(outcome, "An outcome is required: MainMenuService.handle always "
                + "returns one, on every path COMEN01C can take");

        // DateHeader owns all of it - including the '/' and ':' separators, the WS-CURDATE-YEAR(3:2)
        // two-digit year and the truncation of the intrinsic's 21-character result to the 16-byte receiver
        // - and reads the injected clock exactly once, so the date and the time cannot straddle a second
        // boundary.
        final DateHeader header = DateHeader.from(codec, clock);

        return MainMenuResponse.builder()
                .trnName(MainMenuService.TRANSACTION_ID)
                // Byte-exact from app/cpy/COTTL01Y.cpy:18-19, six leading and seven trailing spaces, never
                // retyped here.
                .title01(ScreenTitles.CCDA_TITLE01)
                .curDate(header.wsCurdateMmDdYy())
                .pgmName(MainMenuService.PROGRAM_NAME)
                // The LIVE line of COTTL01Y is L22 - the alternative at L21 directly above it is commented
                // out in the copybook and must not be used. ScreenTitles has already resolved that; it is
                // not second-guessed here with a hand-typed literal.
                .title02(ScreenTitles.CCDA_TITLE02)
                .curTime(header.wsCurtimeHhMmSs())

                .optionLine(1, outcome.optionLine(1))
                .optionLine(2, outcome.optionLine(2))
                .optionLine(3, outcome.optionLine(3))
                .optionLine(4, outcome.optionLine(4))
                .optionLine(5, outcome.optionLine(5))
                .optionLine(6, outcome.optionLine(6))
                .optionLine(7, outcome.optionLine(7))
                .optionLine(8, outcome.optionLine(8))
                .optionLine(9, outcome.optionLine(9))
                .optionLine(10, outcome.optionLine(10))
                .optionLine(11, outcome.optionLine(11))
                .optionLine(12, outcome.optionLine(12))

                .option(outcome.option())

                .errMsg(codec.movePicX(outcome.message(), MainMenuResponse.ERR_MSG_LENGTH))

                // The area as the program leaves it - with CDEMO-PGM-CONTEXT already advanced to re-enter
                // on the first-entry path, which is what makes the next keystroke take the other branch,
                // and with CDEMO-USER-TYPE exactly as it arrived, because L150 is commented out.
                .navigationContext(commareaHandedOn(outcome))

                .nextProgram(outcome.nextProgram())
                .nextMapset(outcome.mapsetName())
                .nextMap(outcome.mapName())

                // The byte is carried as the attribute metadata the copybook declares it to be, and is
                // passed through exactly as the service resolved it.
                .errMsgColor(outcome.messageColour())
                .resetAllOutputFields(outcome.resetAllOutputFields())
                .build();
    }

    private static NavigationContext commareaHandedOn(final MainMenuOutcome outcome) {
        if (outcome.hasNextProgram() && !outcome.nextProgramCarriesCommarea()) {
            return null;
        }
        return outcome.navigationContext();
    }

    private static ScreenMetadata screenMetadata(final MainMenuResponse painted) {
        return ScreenMetadata.of(NO_CURSOR_REQUEST,
                painted.errMsgColor(),
                painted.resetAllOutputFields());
    }
}
