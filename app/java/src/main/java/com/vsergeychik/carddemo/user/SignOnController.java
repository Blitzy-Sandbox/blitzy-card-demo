package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.user.SignOnService.CursorField;
import com.vsergeychik.carddemo.user.SignOnService.MapInputArea;
import com.vsergeychik.carddemo.user.SignOnService.SignOnInput;
import com.vsergeychik.carddemo.user.SignOnService.SignOnOutcome;
import com.vsergeychik.carddemo.user.dto.SignOnRequest;
import com.vsergeychik.carddemo.user.dto.SignOnResponse;
import jakarta.validation.Valid;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of {@code app/cbl/COSGN00C.cbl} - CICS transaction {@code CC00}, mapset {@code COSGN00},
 * map {@code COSGN0A}, the CardDemo sign-on screen.
 *
 * <p>{@code COSGN00C} is the odd one out among the five {@code user} programs: the four {@code COUSR0x}
 * programs test {@code IF NOT CDEMO-PGM-REENTER}, and {@code COSGN00C} never reads
 * {@code CDEMO-PGM-CONTEXT} at all.
 */
@RestController
public class SignOnController {
    // One route, and no others - app/csd/CARDDEMO.CSD:378 defines exactly one transaction against this
    // program.

    /**
     * {@code POST /api/signon} - the single route this controller publishes.
     */
    public static final String SIGNON_PATH = "/api/signon";

    static final String AID_MEMBER = "aid";

    /**
     * Query parameter carrying the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value.
     */
    public static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    public static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final int AID_MIN = 0;

    static final int AID_MAX = 255;

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    /**
     * Configuration key supplying {@code APPLIDO} - {@code carddemo.cics.applid}.
     */
    public static final String APPLID_PROPERTY = "carddemo.cics.applid";

    /**
     * Configuration key supplying {@code SYSIDO} - {@code carddemo.cics.sysid}.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:202-204} obtains this field from {@code EXEC CICS ASSIGN SYSID(...)},
     * likewise a region property, and likewise required with no default.
     */
    public static final String SYSID_PROPERTY = "carddemo.cics.sysid";

    public static final String APPLID_ENVIRONMENT_VARIABLE = "CARDDEMO_CICS_APPLID";

    public static final String SYSID_ENVIRONMENT_VARIABLE = "CARDDEMO_CICS_SYSID";

    /**
     * The widest value {@code EXEC CICS ASSIGN APPLID} can report: {@value}.
     *
     * <p>The <em>source</em> contract, which is a different thing from the receiving field's width even
     * where the two numbers agree. {@code APPLIDO} is {@code PIC X(8)}
     * [{@code app/cpy-bms/COSGN00.CPY:128}, {@code app/bms/COSGN00.bms:80-83}] and a region's
     * application identifier is at most eight characters, so here they coincide - which is exactly why
     * this constant is declared separately from {@link SignOnResponse#APPLID_LENGTH} rather than reusing
     * it as if the coincidence were the reason. {@link #SYSID_SOURCE_LENGTH} is the case that proves the
     * distinction matters.
     */
    public static final int APPLID_SOURCE_LENGTH = 8;

    /**
     * The widest value {@code EXEC CICS ASSIGN SYSID} can report: {@value}.
     *
     * <p>Four, not eight. {@code SYSIDO} is {@code PIC X(8)}
     * [{@code app/cpy-bms/COSGN00.CPY:134}] and {@code app/bms/COSGN00.bms:89-93} declares the field
     * eight columns wide, but a CICS system identifier is a <strong>four</strong>-character name, and
     * {@code EXEC CICS ASSIGN SYSID(SYSIDO OF COSGN0AO)} at {@code app/cbl/COSGN00C.cbl:202-204} moves
     * that four-character value into the eight-byte field, which space-fills the remaining four bytes.
     * {@code application.yml} states the same thing in prose - "the SYSID is its 4-character system
     * identifier" - and {@code application-test.yml} pins {@code AWS1}, four characters, which every
     * {@code COSGN00C} parity case then expects painted as {@code "AWS1    "}.
     *
     * <p>The consequence is the reason this constant exists: a deployment configuring five or more
     * characters is describing something no region could have reported. Truncating it to the field width
     * would paint eight characters of a name that never existed into one of the eleven fields a
     * field-for-field diff compares. So it is refused - see
     * {@link #requireRegionIdentity(String, int, Charset, String, String)}.
     */
    public static final int SYSID_SOURCE_LENGTH = 4;

    /**
     * The code page applied to the {@code PIC X} move rule, {@link StandardCharsets#US_ASCII}.
     */
    public static final Charset DEFAULT_WORKING_STORAGE_CHARSET = StandardCharsets.US_ASCII;

    private static final String SPACES = " ";

    /**
     * The {@code DFHMDF} label of the user-id field, {@code USERID} - {@code app/bms/COSGN00.bms:156}.
     */
    public static final String CURSOR_USERID = "USERID";

    /**
     * The {@code DFHMDF} label of the password field, {@code PASSWD} - {@code app/bms/COSGN00.bms:175}.
     */
    public static final String CURSOR_PASSWD = "PASSWD";

    private final SignOnService signOnService;

    private final Clock clock;

    private final FixedWidthCodec codec;

    /**
     * {@code APPLIDO}, exactly {@value SignOnResponse#APPLID_LENGTH} characters.
     *
     * <p>{@code app/cbl/COSGN00C.cbl:198-200} fills this field from {@code EXEC CICS ASSIGN
     * APPLID(APPLIDO OF COSGN0AO)}. There is no Java equivalent of that statement: the application
     * identifier is a property of the CICS region, which is precisely what this module replaces. Per
     * practice <strong>B12</strong> the field is therefore kept and populated from configuration rather
     * than dropped - it is one of the eleven named {@code DFHMDF} fields, and this is the only screen
     * in the application that carries it.
     *
     * <p><strong>There is no default, and spaces are not accepted.</strong> A deployment states its
     * region through {@value #APPLID_PROPERTY}, and one that states nothing is refused at startup
     * rather than serving a blank field: this is one of the eleven fields a field-for-field diff
     * compares, every {@code COSGN00C} parity case expects a real region name in it, and a blank is
     * indistinguishable on the wire from a region that answered nothing - so a silent default would
     * turn a missing deployment input into a wrong screen nobody notices. An invented literal is
     * equally out of the question: that is fabricated data in a compared field. A value longer than
     * {@value #APPLID_SOURCE_LENGTH} characters is refused for the same reason rather than truncated,
     * because {@code ASSIGN} could not have reported it and the truncated remainder would be
     * indistinguishable from a genuinely shorter name. What survives that check is brought to the
     * declared width by the {@code PIC X} rule, which here only ever pads on the right.
     */
    private final String applId;

    /**
     * {@code SYSIDO}, exactly {@value SignOnResponse#SYSID_LENGTH} characters.
     *
     * <p>From {@code EXEC CICS ASSIGN SYSID(SYSIDO OF COSGN0AO)} at
     * {@code app/cbl/COSGN00C.cbl:202-204}, and handled exactly as {@link #applId} is - required, with
     * no default - through {@value #SYSID_PROPERTY}. {@code app/bms/COSGN00.bms:89-93} does declare this
     * field {@code INITIAL='        '}, eight spaces, but that is the <em>unpainted</em> state of the
     * map before the program runs, not what {@code ASSIGN} puts there: the paragraph that sends the
     * screen always overwrites it, so spaces on a painted screen would describe a region that does not
     * exist.
     *
     * <p>One thing is handled differently from {@link #applId}, and the eight-column field is why it is
     * easy to get wrong: the configured value is validated against {@value #SYSID_SOURCE_LENGTH}
     * characters - what {@code ASSIGN SYSID} can report - and only then moved into the
     * {@value SignOnResponse#SYSID_LENGTH}-character field, where the {@code PIC X} rule space-fills the
     * remaining four bytes. So {@code AWS1} becomes {@code "AWS1    "}, and {@code AWS12} is refused at
     * startup rather than painted.
     */
    private final String sysId;

    private final SignOnRequest coldStartRequest;

    /**
     * The bean constructor, applying {@link #DEFAULT_WORKING_STORAGE_CHARSET}.
     *
     * @param signOnService the translated {@code COSGN00C}; must not be {@code null}
     * @param clock the source of {@code FUNCTION CURRENT-DATE}; must not be {@code null}
     * @param applId {@code EXEC CICS ASSIGN APPLID}, from {@link #APPLID_PROPERTY}; required, and must hold
     *     text
     * @param sysId {@code EXEC CICS ASSIGN SYSID}, from {@link #SYSID_PROPERTY}; required, and must hold
     *     text
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if either identifier holds no text
     */
    @Autowired
    public SignOnController(final SignOnService signOnService,
            final Clock clock,
            @Value("${" + APPLID_PROPERTY + "}") final String applId,
            @Value("${" + SYSID_PROPERTY + "}") final String sysId) {
        this(signOnService, clock, applId, sysId, DEFAULT_WORKING_STORAGE_CHARSET);
    }

    /**
     * The full constructor, taking the {@code WORKING-STORAGE} code page explicitly.
     *
     * @param signOnService the translated {@code COSGN00C}; must not be {@code null}
     * @param clock the source of {@code FUNCTION CURRENT-DATE}; must not be {@code null}
     * @param applId the application identifier; must not be {@code null}, must hold text, and is brought to
     *     {@value SignOnResponse#APPLID_LENGTH} characters by the {@code PIC X} rule
     * @param sysId the system identifier; must not be {@code null}, must hold text, and is brought to
     *     {@value SignOnResponse#SYSID_LENGTH} characters the same way
     * @param workingStorageCharset the code page applied to screen and message text; must not be
     *     {@code null}, and is never defaulted from the platform
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if either identifier holds no text
     */
    public SignOnController(final SignOnService signOnService,
            final Clock clock,
            final String applId,
            final String sysId,
            final Charset workingStorageCharset) {
        this.signOnService = Objects.requireNonNull(signOnService,
                "A SignOnService is required: it is the translated COSGN00C, and this controller makes "
                        + "no sign-on decision of its own");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO at app/cbl/COSGN00C.cbl:177-204 renders the "
                        + "date and time header, and reading the wall clock directly would make that "
                        + "header impossible to assert byte for byte");
        // The code page is settled FIRST because the two identity checks below need it: a character the
        // configured code page cannot encode is one of the ways a configured region identity is invalid.
        Objects.requireNonNull(workingStorageCharset, "A code page is required for the PIC X move rule; "
                + "it is never the platform default");
        requireRegionIdentity(applId, APPLID_SOURCE_LENGTH, workingStorageCharset, APPLID_PROPERTY,
                APPLID_ENVIRONMENT_VARIABLE);
        requireRegionIdentity(sysId, SYSID_SOURCE_LENGTH, workingStorageCharset, SYSID_PROPERTY,
                SYSID_ENVIRONMENT_VARIABLE);
        this.codec = new FixedWidthCodec(workingStorageCharset);
        this.applId = this.codec.movePicX(applId, SignOnResponse.APPLID_LENGTH);
        this.sysId = this.codec.movePicX(sysId, SignOnResponse.SYSID_LENGTH);
        this.coldStartRequest = coldStartRequest(this.codec);
    }

    /**
     * Refuses a region identifier that is absent or blank, naming what to set.
     *
     * <p>The check is here, in the one constructor both paths run through, rather than only on the
     * {@code @Value} path - so there is no seam through which a blank identifier can reach a painted
     * screen. It refuses <em>blank</em> as well as absent because the two are the same failure wearing
     * different clothes: an environment variable exported empty, a YAML key left with nothing after the
     * colon, and a placeholder that resolved to the empty string all arrive here as text with no
     * content, and all of them would put spaces in a field the parity harness compares against a real
     * region name.
     *
     * <p>It also refuses a value the <strong>source statement could not have produced</strong>, and this
     * is where it differs from the {@code PIC X} move rule that follows it. That rule truncates on the
     * right, and truncation is correct for a screen field: a terminal operator really can type nine
     * characters into an eight-column field, and CICS really does keep the leftmost eight. These two
     * values are not typed - they stand in for {@code EXEC CICS ASSIGN}, which reports at most
     * {@value #APPLID_SOURCE_LENGTH} characters of application identifier and at most
     * {@value #SYSID_SOURCE_LENGTH} of system identifier. A longer configured value therefore describes
     * a region that cannot exist, and truncating it would paint a name no execution of {@code COSGN00C}
     * could ever have painted into one of the eleven fields a field-for-field diff compares - silently,
     * because the truncated result looks exactly like a legitimately short name. So it is refused here,
     * before the move, rather than absorbed by it.
     *
     * <p>The last arm is representability. The {@code PIC X} move works on characters, so a character the
     * configured code page cannot encode survives the move untouched and is only lost later, when the
     * screen text is encoded and the encoder substitutes its replacement byte - {@code 0x6F}, a question
     * mark, under {@code IBM037}. That is a corrupted compared field discovered nowhere near its cause,
     * so an unencodable identity is refused at startup too, naming the code point and the code page.
     * The judgement is {@link FixedWidthCodec#firstUnrepresentableCodePoint(String)} - the same seam
     * the screen boundary asks, rather than a second implementation of the same question.
     *
     * <p>All four arms throw at construction, which for the bean path is context refresh: a deployment
     * that states an impossible region never serves a request with it.
     *
     * @param identity            the configured value; may be {@code null}, which is refused
     * @param sourceLength        the widest value the {@code EXEC CICS ASSIGN} statement behind this
     *                            field can report - {@link #APPLID_SOURCE_LENGTH} or
     *                            {@link #SYSID_SOURCE_LENGTH}, never the receiving field's width
     * @param codePage            the {@code WORKING-STORAGE} code page the value must be representable
     *                            in; must not be {@code null}
     * @param property            the property that supplies it
     * @param environmentVariable the environment variable {@code application.yml} reads it from
     * @throws NullPointerException  if {@code identity} or {@code codePage} is {@code null}
     * @throws IllegalStateException if {@code identity} holds no text, is longer than
     *                               {@code sourceLength}, or holds a character {@code codePage} cannot
     *                               encode
     */
    static void requireRegionIdentity(final String identity, final int sourceLength,
            final Charset codePage, final String property, final String environmentVariable) {
        Objects.requireNonNull(identity, "A CICS region identity is required for " + property
                + "; supply it through the environment variable " + environmentVariable + '.');
        Objects.requireNonNull(codePage, "A code page is required to check that the region identity "
                + property + " is representable; it is never the platform default");
        if (identity.isBlank()) {
            throw new IllegalStateException("The CICS region identity " + property + " is configured "
                    + "but holds no text, so the sign-on screen would paint spaces in a field every "
                    + "COSGN00C parity case expects a region name in. Set " + property + " - normally "
                    + "through the environment variable " + environmentVariable + " - to the value "
                    + "EXEC CICS ASSIGN would have reported. There is deliberately no default: this "
                    + "field is compared byte for byte, so neither a blank nor an invented literal is "
                    + "an acceptable stand-in.");
        }
        if (identity.length() > sourceLength) {
            throw new IllegalStateException("The CICS region identity " + property + " is configured "
                    + "with " + identity.length() + " characters, but EXEC CICS ASSIGN reports at most "
                    + sourceLength + " for this field, so no CICS region could have answered '"
                    + identity + "'. Set " + property + " - normally through the environment variable "
                    + environmentVariable + " - to at most " + sourceLength + " characters. It is "
                    + "refused rather than truncated: the surviving leading characters would be "
                    + "indistinguishable from a genuinely shorter name in a field that is compared "
                    + "byte for byte.");
        }
        // The judgement itself is not re-implemented here. FixedWidthCodec.firstUnrepresentableCodePoint
        // is the module's one seam for "which character can this code page not encode", already used at
        // the screen-input boundary, and it iterates by CODE POINT - so a surrogate pair is judged as
        // the one character it is rather than as two unrepresentable halves.
        final OptionalInt offending =
                new FixedWidthCodec(codePage).firstUnrepresentableCodePoint(identity);
        if (offending.isPresent()) {
            throw new IllegalStateException("The CICS region identity " + property + " holds a "
                    + "character the code page " + codePage.name() + " cannot encode - Unicode code "
                    + "point U+" + String.format("%04X", offending.getAsInt()) + ". Set " + property
                    + " - normally through the environment variable " + environmentVariable + " - to a "
                    + "value the region's code page can represent. It is refused rather than encoded: "
                    + "the encoder would substitute its replacement byte and the corrupted field would "
                    + "be discovered in a parity diff far from this cause.");
        }
    }

    private static SignOnRequest coldStartRequest(final FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to size the cold-start payload");
        return new SignOnRequest(ScreenFieldImage.unpainted(SignOnRequest.TRNNAME_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.TITLE01_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.CURDATE_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.PGMNAME_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.TITLE02_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.CURTIME_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.APPLID_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.SYSID_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.USERID_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.PASSWD_LENGTH),
                ScreenFieldImage.unpainted(SignOnRequest.ERRMSG_LENGTH),
                null,
                PfKeyResolver.AidKey.ENTER.token());
    }

    /**
     * Runs the sign-on transaction: {@code POST} {@link #SIGNON_PATH}, transaction {@code CC00}, program
     * {@code COSGN00C}, mapset {@code COSGN00}, map {@code COSGN0A}, eleven named fields.
     *
     * <p>The five-character token in the payload cannot say that, because the copybook folds {@code PF15}
     * onto {@code 'PFK03'}; a caller restricted to the token could only ever get the PF3 arm.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @param eibaid the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value, or {@code null}
     *     when the request names no key
     * @param eibAid the accepted alternate spelling of the same parameter
     * @return the painted screen and, beside it rather than inside it, the presentation metadata this
     *     program sets - the cursor request and the full-repaint instruction, neither of which is a
     *     {@code DFHMDF} field
     */
    @PostMapping(path = SIGNON_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<SignOnResponse> signOn(
            @Valid @RequestBody(required = false) final SignOnRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) final Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) final Integer eibAid) {
        return performSignOn(Objects.requireNonNullElse(request, coldStartRequest),
                AidRequestParameter.resolve(eibaid, eibAid));
    }

    ScreenResponse<SignOnResponse> performSignOn(final SignOnRequest received) {
        return performSignOn(received, null);
    }

    ScreenResponse<SignOnResponse> performSignOn(final SignOnRequest received,
            final Integer statedAid) {
        Objects.requireNonNull(received, "A payload is required here; an absent body is represented by "
                + "the cold-start request, whose communication area is null, and not by a null payload");
        return performSignOn(received, resolveEibAid(statedAid, received.aid()));
    }

    ScreenResponse<SignOnResponse> performSignOn(final SignOnRequest received, final byte eibAid) {
        Objects.requireNonNull(received, "A payload is required here; an absent body is represented by "
                + "the cold-start request, whose communication area is null, and not by a null payload");
        final SignOnOutcome outcome = signOnService.handle(toInput(received, eibAid));
        return ScreenResponse.of(toResponse(outcome), toMetadata(outcome));
    }

    SignOnInput toInput(final SignOnRequest received) {
        return toInput(received, null);
    }

    SignOnInput toInput(final SignOnRequest received, final Integer statedAid) {
        Objects.requireNonNull(received, "A payload is required to build a sign-on invocation");
        return toInput(received, resolveEibAid(statedAid, received.aid()));
    }

    SignOnInput toInput(final SignOnRequest received, final byte eibAid) {
        Objects.requireNonNull(received, "A payload is required to build a sign-on invocation");
        final NavigationContext inboundCommarea = received.navigationContext();
        return new SignOnInput(inboundCommarea,
                eibAid,
                received.userId(),
                received.passwd());
    }

    byte resolveEibAid(final Integer statedAid, final String aidImage) {
        if (statedAid == null) {
            return toEibAid(aidImage);
        }
        return AidRequestParameter.requireStatedAid(AID_MEMBER, statedAid, aidImage, codec);
    }

    byte toEibAid(final String aidImage) {
        if (aidImage == null || aidImage.length() != RAW_AID_LENGTH) {
            return CicsAid.DFHNULL;
        }
        char stated = aidImage.charAt(0);
        if (stated > MAX_AID_CODE_POINT) {
            return CicsAid.DFHNULL;
        }
        return (byte) stated;
    }

    SignOnResponse toResponse(final SignOnOutcome outcome) {
        Objects.requireNonNull(outcome, "An outcome is required to project the sign-on screen");
        final boolean screenPainted = outcome.screenPainted();
        if (!screenPainted) {
            final SignOnResponse unsent = SignOnResponse.empty()
                    .withErrMsg(codec.movePicX(SPACES, SignOnResponse.ERRMSG_LENGTH))
                    .withNavigation(outcome.role(),
                            outcome.nextProgram(),
                            codec.movePicX(SPACES, SignOnResponse.NEXT_MAPSET_LENGTH),
                            codec.movePicX(SPACES, SignOnResponse.NEXT_MAP_LENGTH),
                            outcome.navigationContext());
            if (!outcome.plainTextSent()) {
                return unsent;
            }
            return unsent.withPlainText(
                    codec.movePicX(outcome.message(), SignOnResponse.PLAIN_TEXT_LENGTH));
        }
        // :179 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - read once, from the injected clock, so the
        // date and the time renderings cannot straddle a second boundary.
        final DateHeader header = DateHeader.from(codec, clock);
        final MapInputArea mapArea = outcome.mapInputArea();
        return SignOnResponse.empty()
                // Raw images: :132-137 upper-cases into WS-USER-ID and WS-USER-PWD, never back into the
                // map.
                .withReceivedMapArea(mapArea.useridi(), mapArea.passwdi())
                .withHeader(codec.movePicX(SignOnResponse.TRANID, SignOnResponse.TRNNAME_LENGTH),
                        codec.movePicX(ScreenTitles.CCDA_TITLE01, SignOnResponse.TITLE01_LENGTH),
                        codec.movePicX(header.wsCurdateMmDdYy(), SignOnResponse.CURDATE_LENGTH),
                        codec.movePicX(SignOnResponse.PROGRAM_NAME, SignOnResponse.PGMNAME_LENGTH),
                        codec.movePicX(ScreenTitles.CCDA_TITLE02, SignOnResponse.TITLE02_LENGTH),
                        codec.movePicX(header.wsCurtimeHhMmSs(), SignOnResponse.CURTIME_LENGTH),
                        applId,
                        sysId)
                // :149 MOVE WS-MESSAGE TO ERRMSGO - PIC X(80) into PIC X(78), truncating on the right.
                .withErrMsg(codec.movePicX(outcome.message(), SignOnResponse.ERRMSG_LENGTH))
                .withNavigation(outcome.role(),
                        outcome.nextProgram(),
                        SignOnResponse.MAPSET_NAME,
                        SignOnResponse.MAP_NAME,
                        outcome.navigationContext());
    }

    ScreenMetadata toMetadata(final SignOnOutcome outcome) {
        Objects.requireNonNull(outcome, "An outcome is required to project the screen metadata");
        return new ScreenMetadata(cursorLabel(outcome.cursorField()),
                null,
                outcome.resetAllOutputFields(),
                Map.of(),
                ScreenMetadata.PASSWORD_IS_NON_DISPLAY);
    }

    static String cursorLabel(final CursorField cursorField) {
        Objects.requireNonNull(cursorField, "A cursor target is required; CursorField.NONE is the "
                + "value for a path that performs no MOVE -1");
        return switch (cursorField) {
            case NONE -> null;
            case USER_ID -> CURSOR_USERID;
            case PASSWORD -> CURSOR_PASSWD;
        };
    }
}
