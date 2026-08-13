package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Every decision {@code app/cbl/COSGN00C.cbl} makes - the CardDemo sign-on transaction {@code CC00}.
 *
 * <p>{@code CARDDEMO-COMMAREA} arrives on {@link SignOnInput#navigationContext()} and leaves on
 * {@link SignOnOutcome#navigationContext()}; {@link NavigationContext} is an immutable record, so the
 * caller's copy is never mutated underneath it.
 */
@Service
public class SignOnService {
    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'} [{@code app/cbl/COSGN00C.cbl:36}].
     */
    public static final String PROGRAM_NAME = "COSGN00C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CC00'} [{@code app/cbl/COSGN00C.cbl:37}].
     */
    public static final String TRANSACTION_ID = "CC00";

    /**
     * Declared width of {@code WS-MESSAGE PIC X(80)} [{@code app/cbl/COSGN00C.cbl:38}].
     */
    public static final int MESSAGE_LENGTH = 80;

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC '} [{@code app/cbl/COSGN00C.cbl:39}] - the
     * {@code DATASET} operand of the {@code EXEC CICS READ} at {@code :212}.
     */
    public static final String USRSEC_FILE_NAME = "USRSEC  ";

    /**
     * {@code 88 ERR-FLG-ON VALUE 'Y'} [{@code app/cbl/COSGN00C.cbl:41}] - the image
     * {@code WS-ERR-FLG PIC X(01)} holds when the condition is asserted.
     */
    public static final String ERR_FLG_ON = "Y";

    /**
     * {@code 88 ERR-FLG-OFF VALUE 'N'} [{@code app/cbl/COSGN00C.cbl:42}] - and the {@code VALUE 'N'} the
     * field is initialised to at {@code :40}, which {@code :75} re-asserts on entry.
     */
    public static final String ERR_FLG_OFF = "N";

    /**
     * Declared width of {@code WS-USER-ID PIC X(08)} [{@code app/cbl/COSGN00C.cbl:45}], which is also the
     * width of {@code USERIDI PIC X(8)} in {@code app/cpy-bms/COSGN00.CPY}, of {@code SEC-USR-ID PIC X(08)}
     * in {@code app/cpy/CSUSR01Y.cpy} and of {@code CDEMO-USER-ID} in {@code app/cpy/COCOM01Y.cpy}.
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Declared width of {@code WS-USER-PWD PIC X(08)} [{@code app/cbl/COSGN00C.cbl:46}], matching
     * {@code PASSWDI PIC X(8)} in {@code app/cpy-bms/COSGN00.CPY} and {@code SEC-USR-PWD PIC X(08)} in
     * {@code app/cpy/CSUSR01Y.cpy}.
     */
    public static final int PASSWORD_LENGTH = 8;

    /**
     * Declared width of {@code CDEMO-USER-TYPE PIC X(01)} [{@code app/cpy/COCOM01Y.cpy:26}], which is the
     * width of the role {@link SignOnOutcome#role()} carries.
     */
    public static final int ROLE_LENGTH = NavigationContext.USER_TYPE_LENGTH;

    /**
     * Declared width of an {@code XCTL PROGRAM} target, from {@code CDEMO-TO-PROGRAM PIC X(08)}
     * [{@code app/cpy/COCOM01Y.cpy:24}].
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * {@code MAPSET('COSGN00')} [{@code app/cbl/COSGN00C.cbl:112} and {@code :153}] - the only mapset this
     * program drives.
     */
    public static final String MAPSET_NAME = "COSGN00";

    /**
     * {@code MAP('COSGN0A')} [{@code app/cbl/COSGN00C.cbl:111} and {@code :152}].
     */
    public static final String MAP_NAME = "COSGN0A";

    /**
     * {@code EXEC CICS XCTL PROGRAM('COADM01C')} [{@code app/cbl/COSGN00C.cbl:231-232}] - where an
     * administrator goes after signing on.
     */
    public static final String ADMIN_PROGRAM = "COADM01C";

    /**
     * {@code EXEC CICS XCTL PROGRAM('COMEN01C')} [{@code app/cbl/COSGN00C.cbl:236-237}] - where every
     * non-administrator goes after signing on.
     */
    public static final String USER_PROGRAM = "COMEN01C";

    /**
     * The {@code WHEN 0} arm of {@code EVALUATE WS-RESP-CD} [{@code app/cbl/COSGN00C.cbl:222}].
     */
    public static final int RESP_NORMAL = FileStatus.NORMAL;

    /**
     * The {@code WHEN 13} arm of {@code EVALUATE WS-RESP-CD} [{@code app/cbl/COSGN00C.cbl:247}].
     */
    public static final int RESP_NOTFND = FileStatus.NOTFND;

    /**
     * The value {@code MOVE -1 TO ...L} writes into a symbolic-map length item to place the cursor on that
     * field - {@code app/cbl/COSGN00C.cbl:82}, {@code :121}, {@code :126}, {@code :244}, {@code :250} and
     * {@code :255}.
     */
    public static final int CURSOR_POSITION = -1;

    /**
     * {@code 'Please enter User ID ...'} [{@code app/cbl/COSGN00C.cbl:120}] - twenty-four characters, with
     * a single space between {@code ID} and the ellipsis.
     */
    public static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /**
     * {@code 'Please enter Password ...'} [{@code app/cbl/COSGN00C.cbl:125}] - twenty-five characters.
     */
    public static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /**
     * {@code 'Wrong Password. Try again ...'} [{@code app/cbl/COSGN00C.cbl:242-243}] - twenty-nine
     * characters, with a full stop after {@code Password} and a space before the ellipsis.
     */
    public static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * {@code 'User not found. Try again ...'} [{@code app/cbl/COSGN00C.cbl:249}] - twenty-nine characters.
     */
    public static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * {@code 'Unable to verify the User ...'} [{@code app/cbl/COSGN00C.cbl:254}] - twenty-nine characters.
     */
    public static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    private static final char SPACE = ' ';

    private static final char LOWER_CASE_A = 'a';

    private static final char LOWER_CASE_Z = 'z';

    private static final int CASE_FOLD_OFFSET = 'a' - 'A';

    private final SecUserRepository secUserRepository;

    /**
     * Wires the sign-on service to the security-user file.
     *
     * @param secUserRepository the {@code USRSEC} repository
     * @throws NullPointerException if {@code secUserRepository} is {@code null}
     */
    public SignOnService(SecUserRepository secUserRepository) {
        this.secUserRepository = Objects.requireNonNull(secUserRepository, "The USRSEC repository is "
                + "required: app/cbl/COSGN00C.cbl:211-219 reads the security-user file, and this "
                + "service has no other way to reach it");
    }

    /**
     * The {@code USRSEC} repository this service was wired to.
     *
     * @return the repository, never {@code null}
     */
    public SecUserRepository secUserRepository() {
        return secUserRepository;
    }

    /**
     * Which symbolic-map length item received the {@code MOVE -1} that places the cursor.
     */
    public enum CursorField {
        NONE(""),

        USER_ID("USERIDL"),

        PASSWORD("PASSWDL");

        private final String lengthItem;

        CursorField(String lengthItem) {
            this.lengthItem = lengthItem;
        }

        /**
         * The symbolic-map length item this target writes to, named exactly as
         * {@code app/cpy-bms/COSGN00.CPY} spells it.
         *
         * @return the item name, or an empty {@link Optional} for {@link #NONE}
         */
        public Optional<String> lengthItemName() {
            return isPositioned() ? Optional.of(lengthItem) : Optional.empty();
        }

        public boolean isPositioned() {
            return this != NONE;
        }
    }

    /**
     * How the task left - the three distinct exits {@code COSGN00C} has, which a stateless caller has to be
     * able to tell apart.
     */
    public enum Termination {
        /**
         * {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA)} at {@code :231-239} - control was
         * transferred, and {@link SignOnOutcome#nextProgram()} names the target.
         */
        XCTL,

        /**
         * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at {@code :98-102} - the
         * pseudo-conversation continues, and the next keystroke re-enters this same transaction with the
         * communication area the outcome carries.
         */
        RETURN_TRANSID,

        /**
         * The bare {@code EXEC CICS RETURN} inside {@code SEND-PLAIN-TEXT} at {@code :171-172} - no
         * {@code TRANSID}, so the conversation ends and the terminal is released.
         */
        RETURN_NO_TRANSID;

        /**
         * Whether the client should send the communication area back to transaction
         * {@value SignOnService#TRANSACTION_ID}.
         *
         * @return {@code true} only for {@link #RETURN_TRANSID}
         */
        public boolean conversationContinues() {
            return this == RETURN_TRANSID;
        }

        /**
         * The {@code TRANSID} the {@code EXEC CICS RETURN} carried.
         *
         * @return {@value SignOnService#TRANSACTION_ID} for {@link #RETURN_TRANSID}, or an empty
         *     {@link Optional} for the two exits that name no transaction
         */
        public Optional<String> returnTransid() {
            return conversationContinues() ? Optional.of(TRANSACTION_ID) : Optional.empty();
        }
    }

    /**
     * Everything {@code COSGN00C} learns about one invocation: the communication area, the attention
     * identifier and the two screen fields it reads.
     *
     * @param navigationContext {@code CARDDEMO-COMMAREA} as received, or {@code null} when no communication
     *     area accompanied the request
     * @param eibAid the raw EBCDIC attention-identifier byte the terminal sent
     * @param userId {@code USERIDI OF COSGN0AI} as received, or {@code null} for a field the terminal never
     *     transmitted
     * @param password {@code PASSWDI OF COSGN0AI} as received, or {@code null} on the same terms
     */
    public record SignOnInput(NavigationContext navigationContext,
                              byte eibAid,
                              String userId,
                              String password) {
        /**
         * Carries every component exactly as it arrives, {@code null} included.
         */
        public SignOnInput {
        }

        /**
         * An invocation with no communication area - the Java form of {@code IF EIBCALEN = 0} at
         * {@code app/cbl/COSGN00C.cbl:80}, which is the transaction being typed at a clear screen rather
         * than being returned to.
         *
         * @param eibAid the attention identifier; immaterial on this path, because the program paints the
         *     sign-on screen and returns before it ever reaches {@code :85}
         * @param userId {@code USERIDI}; likewise never inspected on this path
         * @param password {@code PASSWDI}; likewise never inspected on this path
         * @return the input, never {@code null}
         */
        public static SignOnInput withoutCommarea(byte eibAid, String userId, String password) {
            return new SignOnInput(null, eibAid, userId, password);
        }

        /**
         * Whether a communication area accompanied this invocation - the negation of
         * {@code IF EIBCALEN = 0}.
         *
         * @return {@code true} when {@link #navigationContext()} is present, which is the {@code ELSE}
         *     branch at {@code app/cbl/COSGN00C.cbl:84}
         */
        public boolean isCommareaPresent() {
            return navigationContext != null;
        }

        /**
         * The diagnostic rendering, with the password replaced by a fixed marker.
         *
         * @return the rendering; never contains the password
         */
        @Override
        public String toString() {
            return "SignOnInput[navigationContext=" + navigationContext
                    + ", eibAid=0x" + hexImage(eibAid)
                    + ", userId=" + userId
                    + ", password=" + NavigationContext.REDACTED + "]";
        }
    }

    /**
     * The outcome of the {@code EXEC CICS RECEIVE MAP} at {@code app/cbl/COSGN00C.cbl:110-115}, including
     * the two condition codes the source captures and never looks at.
     *
     * @param performed whether the {@code RECEIVE} ran at all
     * @param respCode {@code WS-RESP-CD PIC S9(09) COMP} at {@code :43}, captured and untested
     * @param reasonCode {@code WS-REAS-CD PIC S9(09) COMP} at {@code :44}, captured and untested
     */
    public record ReceiveOutcome(boolean performed, int respCode, int reasonCode) {
        public static final ReceiveOutcome NOT_PERFORMED = new ReceiveOutcome(false, 0, 0);

        /**
         * A {@code RECEIVE MAP} that reported {@code DFHRESP(NORMAL)} - the ordinary case, and the only one
         * this service can produce, because the map arrives already decoded on {@link SignOnInput}.
         *
         * @return the outcome, with {@link #performed()} true and both codes zero
         */
        public static ReceiveOutcome normal() {
            return new ReceiveOutcome(true, RESP_NORMAL, FileStatus.NO_REASON_CODE);
        }
    }

    /**
     * The two data spans of the symbolic map that {@code COSGN00C} both reads and transmits, as they stand
     * when the task leaves.
     *
     * @param useridi the {@code USERIDI}/{@code USERIDO} span, exactly
     *     {@value SignOnService#USER_ID_LENGTH} characters
     * @param passwdi the {@code PASSWDI}/{@code PASSWDO} span, exactly
     *     {@value SignOnService#PASSWORD_LENGTH} characters
     */
    public record MapInputArea(String useridi, String passwdi) {
        public MapInputArea {
            Objects.requireNonNull(useridi, "The USERIDI span is PIC X(" + USER_ID_LENGTH + ") and is "
                    + "never null; use UNTRANSMITTED where no RECEIVE ran");
            Objects.requireNonNull(passwdi, "The PASSWDI span is PIC X(" + PASSWORD_LENGTH + ") and is "
                    + "never null; use UNTRANSMITTED where no RECEIVE ran");
            if (useridi.length() != USER_ID_LENGTH) {
                throw new IllegalArgumentException("USERIDI is PIC X(" + USER_ID_LENGTH
                        + ") (app/cpy-bms/COSGN00.CPY:78) but the image is " + useridi.length()
                        + " character(s) wide");
            }
            if (passwdi.length() != PASSWORD_LENGTH) {
                throw new IllegalArgumentException("PASSWDI is PIC X(" + PASSWORD_LENGTH
                        + ") (app/cpy-bms/COSGN00.CPY:84) but the image is " + passwdi.length()
                        + " character(s) wide");
            }
        }

        /**
         * The spans on the three paths that never perform the {@code RECEIVE}: cold start, PF3 and the
         * invalid-key arm.
         */
        public static final MapInputArea UNTRANSMITTED =
                new MapInputArea(ScreenFieldImage.unpainted(USER_ID_LENGTH),
                        ScreenFieldImage.unpainted(PASSWORD_LENGTH));

        /**
         * The spans as {@code EXEC CICS RECEIVE MAP} at {@code :110-115} delivered them.
         *
         * @param useridi the {@code USERIDI} image, at its declared width
         * @param passwdi the {@code PASSWDI} image, at its declared width
         * @return the area, never {@code null}
         * @throws NullPointerException if either image is {@code null}
         * @throws IllegalArgumentException if either image departs from its declared width
         */
        public static MapInputArea received(String useridi, String passwdi) {
            return new MapInputArea(useridi, passwdi);
        }

        /**
         * A rendering that names the user id and withholds the password.
         *
         * @return a rendering safe to log, never {@code null}
         */
        @Override
        public String toString() {
            return "MapInputArea[useridi=" + useridi
                    + ", passwdi=" + SensitiveDiagnostics.REDACTED + ']';
        }
    }

    /**
     * Everything one run of {@code COSGN00C} produced, and everything the controller needs to build its
     * response - so that the controller itself makes no decision.
     *
     * @param signedOn whether the plaintext comparison at {@code :223} succeeded and the program
     *     transferred control
     * @param role {@code CDEMO-USER-TYPE} as the sign-on established it, from
     *     {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} at {@code :227}; a single space when no sign-on occurred
     * @param nextProgram the {@code EXEC CICS XCTL PROGRAM} target - {@code 'COADM01C'} at {@code :232} or
     *     {@code 'COMEN01C'} at {@code :237} - space-padded to {@value SignOnService#NEXT_PROGRAM_LENGTH}
     * @param message {@code WS-MESSAGE}, exactly {@value SignOnService#MESSAGE_LENGTH} characters,
     *     space-filled on the right
     * @param errorFlag whether {@code 88 ERR-FLG-ON} holds, that is whether {@code WS-ERR-FLG} was moved
     *     {@code 'Y'}
     * @param cursorField which length item received the {@code MOVE -1}
     * @param screenPainted whether {@code SEND-SIGNON-SCREEN} ({@code :145-157}) transmitted the map
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES TO COSGN0AO} at {@code :81} cleared every
     *     output field first
     * @param plainTextSent whether {@code SEND-PLAIN-TEXT} ({@code :162-172}) transmitted unformatted text
     * @param termination how the task left
     * @param navigationContext {@code CARDDEMO-COMMAREA} as it stands when the program leaves: the area
     *     {@code :100} hands back, or the area {@code :233} and {@code :238} pass to the next program
     * @param receive the outcome of the {@code RECEIVE MAP}
     * @param mapInputArea the {@code USERIDI}/{@code USERIDO} and {@code PASSWDI}/{@code PASSWDO} spans as
     *     they stand at exit - the images a repaint re-transmits, because {@code COSGN0AO REDEFINES COSGN0AI}
     * @param resolvedAid the token {@link PfKeyResolver} maps {@link SignOnInput#eibAid()} onto, or empty
     *     for a byte it recognises no mapping for
     * @param readOutcome how the {@code EXEC CICS READ} at {@code :211-219} classified, or empty on the
     *     four paths that never read the file
     */
    public record SignOnOutcome(boolean signedOn,
                                String role,
                                String nextProgram,
                                String message,
                                boolean errorFlag,
                                CursorField cursorField,
                                boolean screenPainted,
                                boolean resetAllOutputFields,
                                boolean plainTextSent,
                                Termination termination,
                                NavigationContext navigationContext,
                                ReceiveOutcome receive,
                                MapInputArea mapInputArea,
                                Optional<PfKeyResolver.AidKey> resolvedAid,
                                Optional<FileStatus.Outcome> readOutcome) {
        public SignOnOutcome {
            Objects.requireNonNull(role, "CDEMO-USER-TYPE is PIC X(" + ROLE_LENGTH + ") and is never "
                    + "null; pass a space when no sign-on established a role");
            Objects.requireNonNull(nextProgram, "An XCTL target is PIC X(" + NEXT_PROGRAM_LENGTH
                    + ") and is never null; pass spaces when the program did not transfer control");
            Objects.requireNonNull(message, "WS-MESSAGE is PIC X(" + MESSAGE_LENGTH + ") and is never "
                    + "null; pass spaces for a blank message");
            Objects.requireNonNull(cursorField, "A cursor target is required; use CursorField.NONE "
                    + "where the source performs no MOVE -1");
            Objects.requireNonNull(termination, "A termination is required: app/cbl/COSGN00C.cbl leaves "
                    + "through an XCTL, a RETURN TRANSID or a bare RETURN, and every path is one of them");
            Objects.requireNonNull(navigationContext, "CARDDEMO-COMMAREA is required: the source hands "
                    + "an area back or on, on every path");
            Objects.requireNonNull(receive, "A receive outcome is required; use "
                    + "ReceiveOutcome.NOT_PERFORMED on a path that never receives the map");
            Objects.requireNonNull(mapInputArea, "The map input area is required: COSGN0AO REDEFINES "
                    + "COSGN0AI, so those two spans are what a repaint transmits. Use "
                    + "MapInputArea.UNTRANSMITTED on a path that never receives the map");
            Objects.requireNonNull(resolvedAid, "The resolved AID is an Optional, never null");
            Objects.requireNonNull(readOutcome, "The read outcome is an Optional, never null; it is "
                    + "empty on the paths that never read USRSEC");
            if (role.length() != ROLE_LENGTH) {
                throw new IllegalArgumentException("CDEMO-USER-TYPE is PIC X(" + ROLE_LENGTH
                        + ") (app/cpy/COCOM01Y.cpy:26) but the image is " + role.length()
                        + " character(s) wide");
            }
            if (nextProgram.length() != NEXT_PROGRAM_LENGTH) {
                throw new IllegalArgumentException("An XCTL target is PIC X(" + NEXT_PROGRAM_LENGTH
                        + ") (app/cpy/COCOM01Y.cpy:24) but the image is " + nextProgram.length()
                        + " character(s) wide");
            }
            if (message.length() != MESSAGE_LENGTH) {
                throw new IllegalArgumentException("WS-MESSAGE is PIC X(" + MESSAGE_LENGTH
                        + ") (app/cbl/COSGN00C.cbl:38) but the image is " + message.length()
                        + " character(s) wide");
            }
            if (signedOn != (termination == Termination.XCTL)) {
                throw new IllegalArgumentException("A sign-on succeeds exactly when the task left "
                        + "through an XCTL (app/cbl/COSGN00C.cbl:231-239), but signedOn=" + signedOn
                        + " was given with termination=" + termination);
            }
            if (signedOn == nextProgram.isBlank()) {
                throw new IllegalArgumentException("An XCTL names its target program and no other exit "
                        + "does, but signedOn=" + signedOn + " was given with nextProgram='"
                        + nextProgram + "'");
            }
        }

        /**
         * The one-character image of {@code WS-ERR-FLG} as the source stores it.
         *
         * @return {@value SignOnService#ERR_FLG_ON} or {@value SignOnService#ERR_FLG_OFF}
         */
        public String errFlgImage() {
            return errorFlag ? ERR_FLG_ON : ERR_FLG_OFF;
        }

        /**
         * Whether the program left through an {@code EXEC CICS XCTL} naming a follow-on program.
         *
         * @return {@code true} when {@link #nextProgram()} names a program rather than holding spaces
         */
        public boolean hasNextProgram() {
            return !nextProgram.isBlank();
        }

        /**
         * Whether the established role is the administrator role - {@code 88 CDEMO-USRTYP-ADMIN} at
         * {@code app/cpy/COCOM01Y.cpy:27}.
         *
         * <p>Exact and case-sensitive, as a COBOL alphanumeric comparison is, and deliberately not the
         * negation of a regular-user test: a blank role satisfies neither.
         *
         * @return {@code true} only when {@link #role()} is exactly
         *     {@link NavigationContext#USER_TYPE_ADMIN}
         */
        public boolean isAdminRole() {
            return NavigationContext.USER_TYPE_ADMIN.equals(role);
        }

        /**
         * The {@code TRANSID} on the {@code EXEC CICS RETURN}, where the return carried one.
         *
         * @return {@value SignOnService#TRANSACTION_ID} when the pseudo-conversation continues, or an empty
         *     {@link Optional} after an {@code XCTL} or a bare {@code RETURN}
         */
        public Optional<String> returnTransid() {
            return termination.returnTransid();
        }
    }

    /**
     * Runs {@code COSGN00C} once - the single entry point, and the Java form of {@code MAIN-PARA}.
     *
     * @param input the invocation
     * @return everything the run produced; never {@code null}
     * @throws NullPointerException if {@code input} is {@code null}
     * @throws IllegalStateException if the security-user file is misconfigured to the point that it cannot
     *     be read at all
     */
    public SignOnOutcome handle(SignOnInput input) {
        Objects.requireNonNull(input, "An invocation is required");

        // WS-ERR-FLG is method-local, never a field: a singleton bean holding per-request working storage
        // would break request isolation.
        boolean errorFlag = false;

        String wsMessage = spaces(MESSAGE_LENGTH);

        Optional<PfKeyResolver.AidKey> resolvedAid = PfKeyResolver.resolve(input.eibAid());

        if (!input.isCommareaPresent()) {
            // Nothing observable turns on the difference here: COSGN00C never reads CDEMO-PGM-CONTEXT, and
            // empty() leaves it at the CDEMO-PGM-ENTER value the next entry would want anyway.
            return sendSignonScreen(NavigationContext.empty(),
                    errorFlag,
                    wsMessage,
                    CursorField.USER_ID,
                    true,
                    ReceiveOutcome.NOT_PERFORMED,
                    MapInputArea.UNTRANSMITTED,
                    resolvedAid,
                    Optional.empty());
        }

        NavigationContext context = input.navigationContext();

        if (PfKeyResolver.isEnter(input.eibAid())) {
            return processEnterKey(input, context, resolvedAid);
        }
        if (PfKeyResolver.isPf3(input.eibAid())) {
            wsMessage = movePicX(SystemMessages.CCDA_MSG_THANK_YOU, MESSAGE_LENGTH);
            // :90 PERFORM SEND-PLAIN-TEXT, which sends unformatted text and then issues a bare EXEC CICS
            // RETURN at :171-172 - no TRANSID, so the conversation ends here and :98 is never reached.
            return sendPlainText(context, wsMessage, resolvedAid);
        }

        errorFlag = true;
        wsMessage = movePicX(SystemMessages.CCDA_MSG_INVALID_KEY, MESSAGE_LENGTH);
        return sendSignonScreen(context,
                errorFlag,
                wsMessage,
                CursorField.NONE,
                false,
                ReceiveOutcome.NOT_PERFORMED,
                MapInputArea.UNTRANSMITTED,
                resolvedAid,
                Optional.empty());
    }

    private SignOnOutcome processEnterKey(SignOnInput input,
                                          NavigationContext context,
                                          Optional<PfKeyResolver.AidKey> resolvedAid) {
        // Its RESP and RESP2 are captured into WS-RESP-CD and WS-REAS-CD and never tested before :219
        // overwrites both, so nothing below branches on them.
        ReceiveOutcome receive = ReceiveOutcome.normal();

        // The two symbolic-map items as CICS delivers them: exactly their declared width, with an
        // untransmitted field arriving as LOW-VALUES.
        String useridi = receivedFieldImage(input.userId(), USER_ID_LENGTH);
        String passwdi = receivedFieldImage(input.password(), PASSWORD_LENGTH);

        MapInputArea mapInputArea = MapInputArea.received(useridi, passwdi);

        boolean errorFlag = false;
        String wsMessage = spaces(MESSAGE_LENGTH);
        CursorField cursorField = CursorField.NONE;

        if (isSpacesOrLowValues(useridi)) {
            errorFlag = true;
            wsMessage = movePicX(MSG_ENTER_USER_ID, MESSAGE_LENGTH);
            cursorField = CursorField.USER_ID;
        } else if (isSpacesOrLowValues(passwdi)) {
            errorFlag = true;
            wsMessage = movePicX(MSG_ENTER_PASSWORD, MESSAGE_LENGTH);
            cursorField = CursorField.PASSWORD;
        }

        // Both receivers are PIC X(08) and the sender is PIC X(8), so the move neither pads nor truncates.
        String wsUserId = upperCase(useridi);
        NavigationContext normalised = context.withUserId(wsUserId);

        String wsUserPwd = upperCase(passwdi);

        if (!errorFlag) {
            return readUserSecFile(normalised, wsUserId, wsUserPwd, receive, mapInputArea, resolvedAid);
        }

        return sendSignonScreen(normalised,
                errorFlag,
                wsMessage,
                cursorField,
                false,
                receive,
                mapInputArea,
                resolvedAid,
                Optional.empty());
    }

    private SignOnOutcome readUserSecFile(NavigationContext context,
                                          String wsUserId,
                                          String wsUserPwd,
                                          ReceiveOutcome receive,
                                          MapInputArea mapInputArea,
                                          Optional<PfKeyResolver.AidKey> resolvedAid) {
        SecUserRepository.ReadResult read = secUserRepository.read(wsUserId);
        Optional<FileStatus.Outcome> readOutcome = Optional.of(read.outcome());

        if (read.isFound()) {
            SecUserRecord secUserData = read.requireRecord();

            if (secUserData.secUsrPwd().equals(wsUserPwd)) {
                NavigationContext signedOn = context
                        .withFromTranid(TRANSACTION_ID)
                        .withFromProgram(PROGRAM_NAME)
                        .withUserId(wsUserId)
                        .withUserType(secUserData.secUsrType())
                        .withPgmEnter();

                return transferControl(signedOn, resolveNextProgram(signedOn), receive, mapInputArea,
                        resolvedAid, readOutcome);
            }

            // There is deliberately NO 'Y' TO WS-ERR-FLG here. Every other failure path in the program sets
            // the flag; this one does not, and the asymmetry is preserved rather than harmonised.
            return sendSignonScreen(context,
                    false,
                    movePicX(MSG_WRONG_PASSWORD, MESSAGE_LENGTH),
                    CursorField.PASSWORD,
                    false,
                    receive,
                    mapInputArea,
                    resolvedAid,
                    readOutcome);
        }

        if (read.isNotFound()) {
            return sendSignonScreen(context,
                    true,
                    movePicX(MSG_USER_NOT_FOUND, MESSAGE_LENGTH),
                    CursorField.USER_ID,
                    false,
                    receive,
                    mapInputArea,
                    resolvedAid,
                    readOutcome);
        }

        return sendSignonScreen(context,
                true,
                movePicX(MSG_UNABLE_TO_VERIFY, MESSAGE_LENGTH),
                CursorField.USER_ID,
                false,
                receive,
                mapInputArea,
                resolvedAid,
                readOutcome);
    }

    /**
     * {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...} at {@code app/cbl/COSGN00C.cbl:230-240} - which program a
     * signed-on user goes to.
     *
     * @param signedOn the communication area with {@code CDEMO-USER-TYPE} already set from
     *     {@code SEC-USR-TYPE}
     * @return {@link #ADMIN_PROGRAM} or {@link #USER_PROGRAM}, space-padded to {@link #NEXT_PROGRAM_LENGTH}
     *     characters
     * @throws NullPointerException if {@code signedOn} is {@code null}
     */
    public String resolveNextProgram(NavigationContext signedOn) {
        Objects.requireNonNull(signedOn, "A communication area is required to read CDEMO-USER-TYPE from");
        String target = signedOn.isAdmin() ? ADMIN_PROGRAM : USER_PROGRAM;
        return movePicX(target, NEXT_PROGRAM_LENGTH);
    }

    private SignOnOutcome sendSignonScreen(NavigationContext context,
                                           boolean errorFlag,
                                           String message,
                                           CursorField cursorField,
                                           boolean resetAllOutputFields,
                                           ReceiveOutcome receive,
                                           MapInputArea mapInputArea,
                                           Optional<PfKeyResolver.AidKey> resolvedAid,
                                           Optional<FileStatus.Outcome> readOutcome) {
        return new SignOnOutcome(false,
                spaces(ROLE_LENGTH),
                spaces(NEXT_PROGRAM_LENGTH),
                message,
                errorFlag,
                cursorField,
                true,
                resetAllOutputFields,
                false,
                Termination.RETURN_TRANSID,
                context,
                receive,
                mapInputArea,
                resolvedAid,
                readOutcome);
    }

    private SignOnOutcome sendPlainText(NavigationContext context,
                                        String message,
                                        Optional<PfKeyResolver.AidKey> resolvedAid) {
        return new SignOnOutcome(false,
                spaces(ROLE_LENGTH),
                spaces(NEXT_PROGRAM_LENGTH),
                message,
                false,
                CursorField.NONE,
                false,
                false,
                true,
                Termination.RETURN_NO_TRANSID,
                context,
                ReceiveOutcome.NOT_PERFORMED,
                MapInputArea.UNTRANSMITTED,
                resolvedAid,
                Optional.empty());
    }

    private SignOnOutcome transferControl(NavigationContext signedOn,
                                          String nextProgram,
                                          ReceiveOutcome receive,
                                          MapInputArea mapInputArea,
                                          Optional<PfKeyResolver.AidKey> resolvedAid,
                                          Optional<FileStatus.Outcome> readOutcome) {
        return new SignOnOutcome(true,
                signedOn.userType(),
                nextProgram,
                spaces(MESSAGE_LENGTH),
                false,
                CursorField.NONE,
                false,
                false,
                false,
                Termination.XCTL,
                signedOn,
                receive,
                mapInputArea,
                resolvedAid,
                readOutcome);
    }

    /**
     * {@code FUNCTION UPPER-CASE} as {@code app/cbl/COSGN00C.cbl:132} and {@code :135} apply it: fold
     * {@code a}-{@code z} to {@code A}-{@code Z} and leave every other character exactly as it is.
     *
     * <p>A COBOL {@code FUNCTION UPPER-CASE} cannot change a field's length: it returns the same number of
     * character positions it was given.
     *
     * @param value the value to fold; any width
     * @return the folded value, the same length as {@code value}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String upperCase(String value) {
        Objects.requireNonNull(value, "FUNCTION UPPER-CASE takes a field, and a COBOL field is never "
                + "null; pass spaces or LOW-VALUES for an empty one");
        StringBuilder folded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= LOWER_CASE_A && character <= LOWER_CASE_Z) {
                folded.append((char) (character - CASE_FOLD_OFFSET));
            } else {
                folded.append(character);
            }
        }
        return folded.toString();
    }

    /**
     * One symbolic-map {@code xxxI} item as CICS delivers it: exactly its declared width, with a field the
     * terminal never transmitted arriving as {@code LOW-VALUES}.
     *
     * @param received the value as received, or {@code null} if it was not supplied
     * @param width the item's declared width
     * @return the image, exactly {@code width} characters
     */
    private static String receivedFieldImage(String received, int width) {
        return received == null ? lowValues(width) : movePicX(received, width);
    }

    private static boolean isSpacesOrLowValues(String image) {
        return image.equals(spaces(image.length())) || image.equals(lowValues(image.length()));
    }

    /**
     * The COBOL alphanumeric {@code MOVE} rule for a {@code PIC X} receiver: left-justify the sender in the
     * receiver, pad on the right with spaces if the sender is shorter, and truncate on the right if it is
     * longer.
     *
     * @param value the sending value
     * @param width the receiver's declared width
     * @return the value at exactly {@code width} characters
     */
    private static String movePicX(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + spaces(width - value.length());
    }

    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * COBOL's {@code LOW-VALUES} figurative constant, filled to a width: the {@code X'00'} bytes CICS
     * leaves in a symbolic-map input item for a field the terminal never transmitted.
     *
     * @param width the number of characters
     * @return a run of low-values
     */
    private static String lowValues(int width) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        return ScreenFieldImage.unpainted(width);
    }

    private static String hexImage(byte value) {
        int unsigned = value & 0xFF;
        return String.valueOf(HEX_DIGITS.charAt(unsigned >>> 4))
                + HEX_DIGITS.charAt(unsigned & 0x0F);
    }

    private static final String HEX_DIGITS = "0123456789ABCDEF";
}
