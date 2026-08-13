package com.vsergeychik.carddemo.admin;

import com.vsergeychik.carddemo.admin.model.MenuOptions;
import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The decision core of {@code app/cbl/COMEN01C.cbl} - the CardDemo main menu for regular users, CICS
 * transaction {@code CM00}.
 */
@Service
public class MainMenuService {
    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} - {@code app/cbl/COMEN01C.cbl:36}.
     */
    public static final String PROGRAM_NAME = "COMEN01C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CM00'} - {@code app/cbl/COMEN01C.cbl:37}.
     */
    public static final String TRANSACTION_ID = "CM00";

    /**
     * {@code MAPSET('COMEN01')} - {@code app/cbl/COMEN01C.cbl:191} and {@code :203}.
     */
    public static final String MAPSET_NAME = "COMEN01";

    /**
     * {@code MAP('COMEN1A')} - {@code app/cbl/COMEN01C.cbl:190} and {@code :202}.
     */
    public static final String MAP_NAME = "COMEN1A";

    /**
     * {@code 'COSGN00C'} - the sign-on program, named at three separate sites.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code app/cbl/COMEN01C.cbl:38}.
     */
    public static final int MESSAGE_LENGTH = 80;

    /**
     * {@code WS-MENU-OPT-TXT PIC X(40) VALUE SPACES} - {@code app/cbl/COMEN01C.cbl:48}.
     */
    public static final int OPTION_TEXT_LENGTH = 40;

    /**
     * {@code WS-OPTION-X PIC X(02) JUST RIGHT} - {@code app/cbl/COMEN01C.cbl:45}.
     */
    public static final int OPTION_X_LENGTH = 2;

    /**
     * {@code WS-OPTION PIC 9(02) VALUE 0} - {@code app/cbl/COMEN01C.cbl:46}.
     */
    public static final int OPTION_DIGITS = 2;

    /**
     * {@code OPTIONI PIC X(2)} and {@code OPTIONO PIC X(2)} - {@code app/cpy-bms/COMEN01.CPY:132} and
     * {@code :254}, corroborated by {@code OPTION DFHMDF ... LENGTH=2} in
     * {@code app/bms/COMEN01.bms:145-149}.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * The twelve {@code OPTN001O} through {@code OPTN012O} lines of the map, each
     * {@value #OPTION_TEXT_LENGTH} bytes - {@code app/cpy-bms/COMEN01.CPY:182-248}.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * The number of arms in the {@code EVALUATE WS-IDX} of {@code BUILD-MENU-OPTIONS}: {@code WHEN 1}
     * through {@code WHEN 12} at {@code app/cbl/COMEN01C.cbl:249-272}, then {@code WHEN OTHER CONTINUE} at
     * {@code :273-274}.
     */
    public static final int OPTION_DISPATCH_ARM_COUNT = 12;

    /**
     * {@code WS-USRSEC-FILE PIC X(08)} - {@code app/cbl/COMEN01C.cbl:39}.
     */
    public static final int USRSEC_FILE_NAME_LENGTH = 8;

    /**
     * The five bytes {@code app/cbl/COMEN01C.cbl:146} compares:
     * {@code CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5)}.
     */
    public static final int DUMMY_PREFIX_LENGTH = 5;

    /**
     * {@code 'DUMMY'} - the sentinel prefix tested at {@code app/cbl/COMEN01C.cbl:146}.
     */
    public static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * {@code 'Please enter a valid option number...'} - {@code app/cbl/COMEN01C.cbl:131}, verified 37
     * characters, three trailing full stops and no trailing space.
     */
    public static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * {@code 'No access - Admin Only option... '} - {@code app/cbl/COMEN01C.cbl:140}, verified 33
     * characters including one trailing space.
     */
    public static final String NO_ACCESS_MESSAGE = "No access - Admin Only option... ";

    /**
     * {@code 'This option '} - the first {@code STRING} operand at {@code app/cbl/COMEN01C.cbl:159},
     * verified 12 characters including its trailing space, and specified {@code DELIMITED BY SIZE} so all
     * twelve are sent.
     */
    public static final String COMING_SOON_PREFIX = "This option ";

    /**
     * {@code 'is coming soon ...'} - the third {@code STRING} operand at {@code app/cbl/COMEN01C.cbl:162},
     * verified 18 characters, and specified {@code DELIMITED BY SIZE}.
     */
    public static final String COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * {@code '. '} - the second {@code STRING} operand of {@code BUILD-MENU-OPTIONS} at
     * {@code app/cbl/COMEN01C.cbl:244}, two characters: a full stop and a space.
     */
    public static final String OPTION_NUMBER_SEPARATOR = ". ";

    /**
     * {@code 88 ERR-FLG-ON VALUE 'Y'} - {@code app/cbl/COMEN01C.cbl:41}, the literal moved by line 130 and
     * the condition name {@code SET ERR-FLG-ON TO TRUE} asserts at line 138.
     */
    public static final String ERR_FLG_ON = "Y";

    /**
     * {@code 88 ERR-FLG-OFF VALUE 'N'} - {@code app/cbl/COMEN01C.cbl:42}, and the {@code VALUE} clause of
     * {@code WS-ERR-FLG} at line 40 that {@code SET ERR-FLG-OFF TO TRUE} at line 77 restores.
     */
    public static final String ERR_FLG_OFF = "N";

    /**
     * {@code ZEROS} as {@code app/cbl/COMEN01C.cbl:129} compares it: {@code WS-OPTION = ZEROS}.
     */
    public static final int ZERO_OPTION = 0;

    /**
     * {@code 'A'} - the one character {@code app/cbl/COMEN01C.cbl:137} compares
     * {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)} against.
     */
    public static final String ADMIN_ONLY_USRTYPE = "A";

    /**
     * The colour the map declares for {@code ERRMSG}: {@code COLOR=RED} at
     * {@code app/bms/COMEN01.bms:154-157}.
     */
    public static final byte MAP_MESSAGE_COLOUR = BmsAttributes.DFHRED;

    /**
     * {@code MOVE DFHGREEN TO ERRMSGC OF COMEN1AO} - {@code app/cbl/COMEN01C.cbl:158}, the program's one
     * and only attribute write.
     */
    public static final byte COMING_SOON_MESSAGE_COLOUR = BmsAttributes.DFHGREEN;

    /**
     * The {@code carddemo.datasets} key under which {@code application.yml} declares the security user
     * file, and the six characters {@code WS-USRSEC-FILE} holds before padding.
     */
    public static final String USRSEC_DATASET_KEY = "USRSEC";

    public static final String DEFAULT_MESSAGE_CHARSET_NAME = "US-ASCII";

    private static final Charset DEFAULT_MESSAGE_CHARSET =
            Charset.forName(DEFAULT_MESSAGE_CHARSET_NAME);

    private static final char SPACE = ' ';

    private static final char ZERO_DIGIT = '0';

    private static final char NINE_DIGIT = '9';

    private static final char LOW_VALUE = '\u0000';

    private final FixedWidthCodec codec;

    private final String usrSecFileName;

    private final SecUserRecord secUserData;

    /**
     * Creates the service over the module's dataset binding catalogue, and is the constructor the Spring
     * container selects.
     *
     * <p>The catalogue is required for one reason only: to resolve the dead {@code WS-USRSEC-FILE}
     * declaration from configuration instead of from a literal, so that no dataset name is written in Java
     * anywhere in this file.
     *
     * @param datasetBindings the {@code carddemo.datasets} catalogue; must declare
     *     {@link #USRSEC_DATASET_KEY}
     * @throws NullPointerException if {@code datasetBindings} is {@code null}
     * @throws IllegalStateException if {@link #USRSEC_DATASET_KEY} is not configured, or is configured with
     *     a record width other than the eighty bytes {@code app/cpy/CSUSR01Y.cpy} declares
     */
    @Autowired
    public MainMenuService(DatasetBindings datasetBindings) {
        this(datasetBindings, new FixedWidthCodec(DEFAULT_MESSAGE_CHARSET));
    }

    /**
     * Creates the service with the codec stated explicitly.
     *
     * @param datasetBindings the {@code carddemo.datasets} catalogue; must declare
     *     {@link #USRSEC_DATASET_KEY}
     * @param codec the fixed-width character codec this service composes every image with
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if {@link #USRSEC_DATASET_KEY} is not configured, or is configured with
     *     a record width other than the eighty bytes {@code app/cpy/CSUSR01Y.cpy} declares
     */
    public MainMenuService(DatasetBindings datasetBindings, FixedWidthCodec codec) {
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "app/cbl/COMEN01C.cbl:39 declares WS-USRSEC-FILE, and the name behind it is resolved "
                + "from configuration because dataset names are never written in Java");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: every image this "
                + "service composes is padded, truncated or concatenated through it so that the "
                + "direction of each operation is explicit at the call site");

        DatasetBinding usrSecBinding = datasetBindings.binding(USRSEC_DATASET_KEY);
        if (usrSecBinding.recordLength() != SecUserRecord.RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding '" + USRSEC_DATASET_KEY + "' declares a "
                    + "record length of " + usrSecBinding.recordLength() + ", but SEC-USER-DATA is "
                    + SecUserRecord.RECORD_LENGTH + " bytes - app/cpy/CSUSR01Y.cpy declares "
                    + "SEC-USR-ID X(08) plus SEC-USR-FNAME X(20), SEC-USR-LNAME X(20), SEC-USR-PWD "
                    + "X(08), SEC-USR-TYPE X(01) and SEC-USR-FILLER X(23). app/cbl/COMEN01C.cbl:58 "
                    + "copies that record, so a differently sized one would mean the two disagree "
                    + "about the same file. Correct carddemo.datasets." + USRSEC_DATASET_KEY
                    + ".record-length to " + SecUserRecord.RECORD_LENGTH + ".");
        }
        this.usrSecFileName = this.codec.movePicX(USRSEC_DATASET_KEY, USRSEC_FILE_NAME_LENGTH);
        this.secUserData = SecUserRecord.blank();
    }

    /**
     * Everything {@code COMEN01C} learns about one invocation: the communication area, the attention
     * identifier and the one screen field it reads.
     *
     * @param navigationContext {@code CARDDEMO-COMMAREA} as received, or {@code null} when no communication
     *     area accompanied the request
     * @param eibAid the raw attention-identifier byte, one of the {@link CicsAid} constants
     * @param option the {@code OPTIONI} field as received
     */
    public record MainMenuInput(NavigationContext navigationContext, byte eibAid, String option) {
        public MainMenuInput {
            Objects.requireNonNull(option, "OPTIONI is PIC X(2) (app/cpy-bms/COMEN01.CPY:132) and a "
                    + "COBOL field is never null; pass spaces for an empty screen field. Only the "
                    + "communication area may be absent, and its absence models EIBCALEN = 0");
        }

        /**
         * An invocation with no communication area - the Java form of {@code EIBCALEN = 0} at
         * {@code app/cbl/COMEN01C.cbl:82}, which is the transaction being typed at a clear screen rather
         * than transferred to.
         *
         * @param eibAid the attention identifier; immaterial on this path, because the program diverts to
         *     the sign-on screen before it ever reaches line 93
         * @param option the {@code OPTIONI} field; likewise never inspected on this path
         * @return the input, never {@code null}
         */
        public static MainMenuInput withoutCommarea(byte eibAid, String option) {
            return new MainMenuInput(null, eibAid, option);
        }

        /**
         * Whether a communication area accompanied this invocation - the negation of
         * {@code IF EIBCALEN = 0}.
         *
         * @return {@code true} when {@link #navigationContext()} is present, which is the {@code ELSE}
         *     branch at {@code app/cbl/COMEN01C.cbl:85}
         */
        public boolean isCommareaPresent() {
            return navigationContext != null;
        }

        /**
         * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds - {@code app/cpy/COCOM01Y.cpy:31}.
         *
         * @return {@code true} only when a communication area is present and its program context is the
         *     re-enter value
         */
        public boolean isReenter() {
            return navigationContext != null && navigationContext.isReenter();
        }
    }

    /**
     * The outcome of {@code RECEIVE-MENU-SCREEN} at {@code app/cbl/COMEN01C.cbl:199-207}, including the two
     * condition codes the source captures and never looks at.
     *
     * @param performed whether the paragraph ran at all
     * @param respCode {@code WS-RESP-CD PIC S9(09) COMP} at {@code app/cbl/COMEN01C.cbl:43}, captured and
     *     untested
     * @param reasonCode {@code WS-REAS-CD PIC S9(09) COMP} at {@code app/cbl/COMEN01C.cbl:44}, captured and
     *     untested
     */
    public record ReceiveOutcome(boolean performed, int respCode, int reasonCode) {
        /**
         * The {@code DFHRESP(NORMAL)} condition code, zero - the value CICS sets when a {@code RECEIVE MAP}
         * succeeds.
         */
        public static final int RESP_NORMAL = 0;

        public static final int RESP2_NONE = 0;

        /**
         * The state on every path that never reaches {@code RECEIVE-MENU-SCREEN}: not performed, with both
         * codes at the {@code VALUE ZEROS} their declarations give them at
         * {@code app/cbl/COMEN01C.cbl:43-44}.
         */
        public static final ReceiveOutcome NOT_PERFORMED =
                new ReceiveOutcome(false, RESP_NORMAL, RESP2_NONE);

        /**
         * A completed receive reporting {@code DFHRESP(NORMAL)}.
         */
        public static final ReceiveOutcome NORMAL =
                new ReceiveOutcome(true, RESP_NORMAL, RESP2_NONE);
    }

    /**
     * The main-menu option table as one invocation sees it: the {@code OCCURS} slots and the active count,
     * held as two separate facts.
     *
     * @param slots the {@code OCCURS} slots in COBOL declaration order, indexed from 0 in the Java sense,
     *     an absent slot being one the copybook gives no {@code VALUE}
     * @param activeCount {@code CDEMO-MENU-OPT-COUNT} - how many leading slots the menu offers
     */
    public record MainMenuOptionTable(List<Optional<MenuOption>> slots, int activeCount) {
        /**
         * Copies the slot list defensively and checks the two invariants a menu table must satisfy for the
         * program's subscripting to be meaningful.
         */
        public MainMenuOptionTable {
            Objects.requireNonNull(slots, "An OCCURS slot list is required; COMEN02Y.cpy declares "
                    + MenuOptions.TABLE_SIZE + " slots");
            slots = List.copyOf(slots);
            if (activeCount < 0 || activeCount > slots.size()) {
                throw new IllegalArgumentException("CDEMO-MENU-OPT-COUNT is " + activeCount
                        + ", which does not address a table of " + slots.size() + " slot(s). "
                        + "app/cbl/COMEN01C.cbl:128 compares the entered option against the count and "
                        + ":146 then subscripts the table with it, so a count beyond the table would "
                        + "admit an option the table cannot resolve.");
            }
            for (int subscript = 1; subscript <= activeCount; subscript++) {
                if (slots.get(subscript - 1).isEmpty()) {
                    throw new IllegalArgumentException("Slot " + subscript + " of "
                            + slots.size() + " is absent, yet CDEMO-MENU-OPT-COUNT is " + activeCount
                            + " and so offers it. app/cbl/COMEN01C.cbl:146 would subscript an "
                            + "unvalued entry: every slot up to the active count must carry a value.");
                }
            }
        }

        /**
         * The real table of {@code app/cpy/COMEN02Y.cpy}: {@value MenuOptions#TABLE_SIZE} slots of which
         * the first {@value MenuOptions#ACTIVE_OPTION_COUNT} carry values, with the active count the
         * copybook declares.
         *
         * @return the copybook table, never {@code null}
         */
        public static MainMenuOptionTable copybook() {
            return new MainMenuOptionTable(MenuOptions.options(), MenuOptions.ACTIVE_OPTION_COUNT);
        }

        /**
         * The entry a 1-based COBOL subscript addresses - the Java form of
         * {@code CDEMO-MENU-OPT(cobolSubscript)}, for a subscript a guard has already validated.
         *
         * <p>The conversion from the 1-based COBOL subscript to the 0-based Java index is written once,
         * here, and never inline at a call site: an off-by-one on an {@code OCCURS} table is the single
         * largest defect risk in this migration.
         *
         * @param cobolSubscript the COBOL subscript, from 1 to the number of slots inclusive
         * @return the addressed entry, or empty for a slot carrying no value
         * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
         *     {@link #slots()}{@code .size()}
         */
        public Optional<MenuOption> optionBySubscript(int cobolSubscript) {
            if (cobolSubscript < 1 || cobolSubscript > slots.size()) {
                throw new IndexOutOfBoundsException("COBOL subscript " + cobolSubscript
                        + " is outside 1.." + slots.size() + "; COMEN02Y.cpy declares "
                        + "CDEMO-MENU-OPT OCCURS " + slots.size() + " TIMES and COBOL has no "
                        + "subscript 0");
            }
            return slots.get(cobolSubscript - 1);
        }

        /**
         * The entry a 1-based COBOL subscript addresses, bounded to the table's declared {@code OCCURS}
         * range and never throwing - the accessor the authorisation filter at
         * {@code app/cbl/COMEN01C.cbl:137} needs.
         *
         * <p>Java would throw {@link IndexOutOfBoundsException} - a crash the COBOL does not produce, and
         * therefore itself a parity violation.
         *
         * @param cobolSubscript any integer, including one no COBOL table could address
         * @return the addressed entry, or empty when the subscript lies outside the declared {@code OCCURS}
         *     range or the slot carries no value
         */
        public Optional<MenuOption> optionWithinTable(int cobolSubscript) {
            if (cobolSubscript < 1 || cobolSubscript > slots.size()) {
                return Optional.empty();
            }
            return slots.get(cobolSubscript - 1);
        }
    }

    /**
     * The four-step normalisation of {@code app/cbl/COMEN01C.cbl:117-125}, with every intermediate
     * preserved so each step can be asserted on its own.
     *
     * @param wsIdx {@code WS-IDX} after the descending scan at lines 117 to 121: 2 when the second byte of
     *     {@code OPTIONI} is not a space, otherwise 1
     * @param receivedOptionI {@code OPTIONI} at its declared {@value #OPTION_LENGTH} characters - the image
     *     the scan ran over
     * @param justifiedOptionX {@code WS-OPTION-X} immediately after line 122's {@code JUSTIFIED RIGHT}
     *     move, before the {@code INSPECT}
     * @param optionX {@code WS-OPTION-X} after line 123's {@code INSPECT ... REPLACING ALL ' ' BY '0'}
     * @param option the value {@link #optionX()} denotes, or empty when it does not hold digits - which is
     *     the state line 127's {@code IS NOT NUMERIC} exists to detect
     */
    public record OptionNormalisation(int wsIdx,
                                      String receivedOptionI,
                                      String justifiedOptionX,
                                      String optionX,
                                      OptionalInt option) {
        /**
         * Requires every component and checks the two widths the copybook fixes.
         */
        public OptionNormalisation {
            Objects.requireNonNull(receivedOptionI, "The received OPTIONI image is required");
            Objects.requireNonNull(justifiedOptionX, "The post-JUST-RIGHT WS-OPTION-X image is "
                    + "required");
            Objects.requireNonNull(optionX, "The post-INSPECT WS-OPTION-X image is required");
            Objects.requireNonNull(option, "An OptionalInt is required; use OptionalInt.empty() for a "
                    + "field that does not hold digits rather than a sentinel integer");
            if (receivedOptionI.length() != OPTION_LENGTH) {
                throw new IllegalArgumentException("OPTIONI is PIC X(" + OPTION_LENGTH + ") but the "
                        + "image is " + receivedOptionI.length() + " character(s) wide");
            }
            if (justifiedOptionX.length() != OPTION_X_LENGTH || optionX.length() != OPTION_DIGITS) {
                throw new IllegalArgumentException("WS-OPTION-X is PIC X(0" + OPTION_X_LENGTH
                        + ") JUST RIGHT (app/cbl/COMEN01C.cbl:45) and WS-OPTION is PIC 9(0"
                        + OPTION_DIGITS + ") (app/cbl/COMEN01C.cbl:46), but an image is "
                        + justifiedOptionX.length() + "/" + optionX.length() + " character(s) wide");
            }
        }

        /**
         * Whether {@code WS-OPTION} holds digits - the negation of line 127's
         * {@code WS-OPTION IS NOT NUMERIC}.
         *
         * @return {@code true} when {@link #option()} is present
         */
        public boolean isNumeric() {
            return option.isPresent();
        }

        /**
         * The value line 125 moves into {@code OPTIONO OF COMEN1AO}, echoing the normalised option back to
         * the screen.
         *
         * @return exactly {@value #OPTION_LENGTH} characters
         */
        public String optionEcho() {
            return optionX;
        }
    }

    /**
     * Everything one invocation of {@code COMEN01C} produces.
     *
     * @param optionLines the twelve {@code OPTN001O} to {@code OPTN012O} lines in map order, index 0 being
     *     {@code OPTN001O}, each {@value #OPTION_TEXT_LENGTH} characters
     * @param message {@code WS-MESSAGE}, exactly {@value #MESSAGE_LENGTH} characters
     * @param messageColour {@code ERRMSGC OF COMEN1AO}: {@link #MAP_MESSAGE_COLOUR} unless line 158
     *     overrode it with {@link #COMING_SOON_MESSAGE_COLOUR}
     * @param errorFlag whether {@code 88 ERR-FLG-ON} holds at the moment the program leaves
     * @param option {@code OPTIONO OF COMEN1AO}, {@value #OPTION_LENGTH} characters
     * @param nextProgram the {@code EXEC CICS XCTL PROGRAM(...)} target at its declared
     *     {@value NavigationContext#TO_PROGRAM_LENGTH} characters, or spaces when the program returned to CICS
     *     instead of transferring
     * @param nextProgramCarriesCommarea whether that transfer passed the communication area
     * @param screenPainted whether {@code SEND-MENU-SCREEN} ran, that is whether {@code EXEC CICS SEND MAP}
     *     at lines 189 to 194 executed
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES TO COMEN1AO} at line 89 ran first,
     *     clearing every output field before the paint
     * @param navigationContext {@code CARDDEMO-COMMAREA} as it stands when the program leaves - the area
     *     line 109 hands back so the client can re-supply it
     * @param transactionId {@code TRANSID} on line 108, {@link #TRANSACTION_ID}
     * @param mapsetName {@code MAPSET('COMEN01')}
     * @param mapName {@code MAP('COMEN1A')}
     * @param receive the outcome of {@code RECEIVE-MENU-SCREEN}, including the two condition codes the
     *     source never tests
     */
    public record MainMenuOutcome(List<String> optionLines,
                                  String message,
                                  byte messageColour,
                                  boolean errorFlag,
                                  String option,
                                  String nextProgram,
                                  boolean nextProgramCarriesCommarea,
                                  boolean screenPainted,
                                  boolean resetAllOutputFields,
                                  NavigationContext navigationContext,
                                  String transactionId,
                                  String mapsetName,
                                  String mapName,
                                  ReceiveOutcome receive) {
        public MainMenuOutcome {
            Objects.requireNonNull(optionLines, "The twelve OPTN00nO lines are required");
            optionLines = List.copyOf(optionLines);
            Objects.requireNonNull(message, "WS-MESSAGE is PIC X(" + MESSAGE_LENGTH + ") and is never "
                    + "null; pass spaces for a blank message");
            Objects.requireNonNull(option, "OPTIONO is PIC X(" + OPTION_LENGTH + ") and is never null");
            Objects.requireNonNull(nextProgram, "The XCTL target is PIC X("
                    + NavigationContext.TO_PROGRAM_LENGTH + ") and is never null; pass spaces when the "
                    + "program returned to CICS rather than transferring");
            Objects.requireNonNull(navigationContext, "CARDDEMO-COMMAREA is required: line 109 hands "
                    + "it back on every return");
            Objects.requireNonNull(transactionId, "The RETURN TRANSID is required");
            Objects.requireNonNull(mapsetName, "The mapset name is required");
            Objects.requireNonNull(mapName, "The map name is required");
            Objects.requireNonNull(receive, "A receive outcome is required; use "
                    + "ReceiveOutcome.NOT_PERFORMED on a path that never receives the map");
            if (optionLines.size() != OPTION_LINE_COUNT) {
                throw new IllegalArgumentException("The map declares " + OPTION_LINE_COUNT
                        + " option lines, OPTN001O to OPTN012O (app/cpy-bms/COMEN01.CPY:182-248), but "
                        + optionLines.size() + " were supplied. All twelve are always carried; the ones "
                        + "this program does not write are spaces.");
            }
            for (int index = 0; index < optionLines.size(); index++) {
                String line = optionLines.get(index);
                if (line.length() != OPTION_TEXT_LENGTH) {
                    throw new IllegalArgumentException("Option line " + (index + 1) + " is "
                            + line.length() + " character(s) wide but OPTN00nO is PIC X("
                            + OPTION_TEXT_LENGTH + ")");
                }
            }
            if (message.length() != MESSAGE_LENGTH) {
                throw new IllegalArgumentException("WS-MESSAGE is PIC X(" + MESSAGE_LENGTH
                        + ") (app/cbl/COMEN01C.cbl:38) but the image is " + message.length()
                        + " character(s) wide");
            }
            if (option.length() != OPTION_LENGTH) {
                throw new IllegalArgumentException("OPTIONO is PIC X(" + OPTION_LENGTH
                        + ") but the image is " + option.length() + " character(s) wide");
            }
            if (nextProgram.length() != NavigationContext.TO_PROGRAM_LENGTH) {
                throw new IllegalArgumentException("An XCTL target is PIC X("
                        + NavigationContext.TO_PROGRAM_LENGTH + ") but the image is "
                        + nextProgram.length() + " character(s) wide");
            }
        }

        /**
         * Whether the program left through an {@code EXEC CICS XCTL} rather than through
         * {@code EXEC CICS RETURN}.
         *
         * @return {@code true} when {@link #nextProgram()} names a program rather than holding spaces
         */
        public boolean hasNextProgram() {
            return !nextProgram.isBlank();
        }

        /**
         * Whether {@code ERRMSGC} was overridden from the map's declared {@code COLOR=RED}.
         *
         * @return {@code true} only when {@link #messageColour()} is {@link #COMING_SOON_MESSAGE_COLOUR},
         *     which line 158 is the sole source of
         */
        public boolean messageColourOverridden() {
            return messageColour == COMING_SOON_MESSAGE_COLOUR;
        }

        /**
         * The one-character image of {@code WS-ERR-FLG} as the source stores it.
         *
         * @return {@link #ERR_FLG_ON} or {@link #ERR_FLG_OFF}
         */
        public String errFlgImage() {
            return errorFlag ? ERR_FLG_ON : ERR_FLG_OFF;
        }

        /**
         * One menu line by its 1-based COBOL subscript, the way {@code EVALUATE WS-IDX} at
         * {@code app/cbl/COMEN01C.cbl:248-275} addresses them: subscript 1 is {@code OPTN001O}.
         *
         * @param cobolSubscript from 1 to {@value #OPTION_LINE_COUNT} inclusive
         * @return the line, exactly {@value #OPTION_TEXT_LENGTH} characters
         * @throws IndexOutOfBoundsException if {@code cobolSubscript} is below 1 or above
         *     {@value #OPTION_LINE_COUNT}
         */
        public String optionLine(int cobolSubscript) {
            if (cobolSubscript < 1 || cobolSubscript > OPTION_LINE_COUNT) {
                throw new IndexOutOfBoundsException("COBOL subscript " + cobolSubscript
                        + " is outside 1.." + OPTION_LINE_COUNT + "; the map declares OPTN001O to "
                        + "OPTN012O and COBOL has no subscript 0");
            }
            return optionLines.get(cobolSubscript - 1);
        }
    }

    /**
     * Runs {@code COMEN01C} against the copybook option table - the entry point for production.
     *
     * @param input the invocation
     * @return the outcome
     * @throws NullPointerException if {@code input} is {@code null}
     */
    public MainMenuOutcome handle(MainMenuInput input) {
        return handle(input, MainMenuOptionTable.copybook());
    }

    /**
     * Runs {@code COMEN01C} against a stated option table, reproducing {@code MAIN-PARA} at
     * {@code app/cbl/COMEN01C.cbl:75-110} statement for statement and in source order.
     *
     * <p>The COBOL, with the line numbers this method's comments cite: Two details of that listing are easy
     * to read past and both are reproduced deliberately: Line 83 sets {@code CDEMO-FROM-PROGRAM}; line 97
     * sets {@code CDEMO-TO-PROGRAM}.
     *
     * @param input the invocation
     * @param optionTable the main-menu option table to offer and subscript
     * @return the outcome
     * @throws NullPointerException if either argument is {@code null}
     */
    public MainMenuOutcome handle(MainMenuInput input, MainMenuOptionTable optionTable) {
        Objects.requireNonNull(input, "An invocation is required");
        Objects.requireNonNull(optionTable, "An option table is required; "
                + "MainMenuOptionTable.copybook() supplies the one app/cpy/COMEN02Y.cpy declares");

        // WS-ERR-FLG is method-local, never a field: a singleton bean holding per-request working storage
        // would break request isolation.
        boolean errorFlag = false;

        String wsMessage = spaces(MESSAGE_LENGTH);

        if (!input.isCommareaPresent()) {
            NavigationContext coldStart = NavigationContext.empty().withFromProgram(SIGNON_PROGRAM);
            return returnToSignonScreen(coldStart, input, wsMessage, ReceiveOutcome.NOT_PERFORMED);
        }

        NavigationContext context = input.navigationContext();

        if (!input.isReenter()) {
            NavigationContext reentered = context.withPgmReenter();
            return sendMenuScreen(reentered,
                    errorFlag,
                    wsMessage,
                    MAP_MESSAGE_COLOUR,
                    ScreenFieldImage.unpainted(OPTION_LENGTH),
                    true,
                    optionTable,
                    ReceiveOutcome.NOT_PERFORMED);
        }

        ReceiveOutcome receive = receiveMenuScreen();

        // Comparing the byte through PfKeyResolver.isAid keeps the two identical for DFHENTER and DFHPF3
        // and nothing more, and keeps every other byte - resolvable or not - on WHEN OTHER, exactly as the
        // inline EVALUATE does.
        byte eibAid = input.eibAid();
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHENTER)) {
            return processEnterKey(input, context, optionTable, receive);
        }
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHPF3)) {
            NavigationContext leaving = context.withToProgram(SIGNON_PROGRAM);
            return returnToSignonScreen(leaving, input, wsMessage, receive);
        }

        errorFlag = true;
        // L101 MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE: a PIC X(50) sender into a PIC X(80) receiver, so
        // the fifty characters are left-justified and the remaining thirty are spaces. movePicX states that
        // direction at the call site; a plain assignment would neither pad nor truncate.
        wsMessage = codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY, MESSAGE_LENGTH);
        return sendMenuScreen(context,
                errorFlag,
                wsMessage,
                MAP_MESSAGE_COLOUR,
                receivedOptionImage(input),
                false,
                optionTable,
                receive);
    }

    /**
     * Normalises the received {@code OPTIONI} value exactly as {@code app/cbl/COMEN01C.cbl:117-125} does,
     * in four steps.
     *
     * @param receivedOption the {@code OPTIONI} value as received; moved into its declared
     *     {@value #OPTION_LENGTH}-character width first
     * @return every intermediate of the normalisation
     * @throws NullPointerException if {@code receivedOption} is {@code null}
     */
    public OptionNormalisation normaliseOption(String receivedOption) {
        Objects.requireNonNull(receivedOption, "OPTIONI is PIC X(" + OPTION_LENGTH + ") and is never "
                + "null; pass spaces for an empty screen field");

        // CICS delivers the received value into a field of exactly that width, so anything wider or
        // narrower is moved into it first, by the alphanumeric MOVE rule: pad on the right, truncate on the
        // right.
        String optionI = codec.movePicX(receivedOption, OPTION_LENGTH);

        int wsIdx = optionI.length();
        while (charAtOneBased(optionI, wsIdx) == SPACE && wsIdx != 1) {
            wsIdx = wsIdx - 1;
        }

        String sender = optionI.substring(0, wsIdx);

        String justifiedOptionX = movePicXJustifiedRight(sender, OPTION_X_LENGTH);

        String optionX = inspectReplacingSpacesByZeros(justifiedOptionX);

        // WS-OPTION-X is PIC X(02) and WS-OPTION is PIC 9(02): at equal widths an
        // alphanumeric-to-numeric-display MOVE copies the characters across, so the image is unchanged and
        // no zero-fill or truncation applies.
        OptionalInt option = isAllDigits(optionX)
                ? OptionalInt.of(codec.decodePic9AsInt(optionX))
                : OptionalInt.empty();

        return new OptionNormalisation(wsIdx, optionI, justifiedOptionX, optionX, option);
    }

    /**
     * Whether the entered option is restricted to administrators - the second conjunct of
     * {@code app/cbl/COMEN01C.cbl:137}, {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'}.
     *
     * @param optionTable the table whose authorisation column is consulted
     * @param wsOption {@code WS-OPTION} as {@link #normaliseOption(String)} left it
     * @return {@code true} only when the subscript addresses a valued entry whose
     *     {@code CDEMO-MENU-OPT-USRTYPE} is exactly {@link #ADMIN_ONLY_USRTYPE}
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean isAdminOnlyOption(MainMenuOptionTable optionTable, OptionalInt wsOption) {
        Objects.requireNonNull(optionTable, "An option table is required to read "
                + "CDEMO-MENU-OPT-USRTYPE");
        Objects.requireNonNull(wsOption, "An OptionalInt is required; OptionalInt.empty() is the "
                + "non-numeric WS-OPTION that line 137 would subscript with no usable value");

        if (wsOption.isEmpty()) {
            return false;
        }
        return optionTable.optionWithinTable(wsOption.getAsInt())
                .map(entry -> ADMIN_ONLY_USRTYPE.equals(entry.menuOptUsrType()))
                .orElse(false);
    }

    private MainMenuOutcome processEnterKey(MainMenuInput input,
            NavigationContext context,
            MainMenuOptionTable optionTable,
            ReceiveOutcome receive) {
        OptionNormalisation normalisation = normaliseOption(input.option());
        String optionEcho = normalisation.optionEcho();

        // WS-ERR-FLG and WS-MESSAGE, both method-local (never fields).
        boolean errorFlag = false;
        String wsMessage = spaces(MESSAGE_LENGTH);
        MainMenuOutcome painted = null;

        if (!normalisation.isNumeric()
                || normalisation.option().getAsInt() > optionTable.activeCount()
                || normalisation.option().getAsInt() == ZERO_OPTION) {
            errorFlag = true;
            wsMessage = codec.movePicX(INVALID_OPTION_MESSAGE, MESSAGE_LENGTH);
            painted = sendMenuScreen(context,
                    errorFlag,
                    wsMessage,
                    MAP_MESSAGE_COLOUR,
                    optionEcho,
                    false,
                    optionTable,
                    receive);
        }

        boolean usrTypeIsUser = context.isUser();
        // L137 - the second conjunct, bounded so an out-of-range subscript cannot throw where COBOL would
        // merely read stray bytes.
        boolean optionIsAdminOnly = isAdminOnlyOption(optionTable, normalisation.option());
        if (usrTypeIsUser && optionIsAdminOnly) {
            errorFlag = true;
            wsMessage = spaces(MESSAGE_LENGTH);
            wsMessage = codec.movePicX(NO_ACCESS_MESSAGE, MESSAGE_LENGTH);
            painted = sendMenuScreen(context,
                    errorFlag,
                    wsMessage,
                    MAP_MESSAGE_COLOUR,
                    optionEcho,
                    false,
                    optionTable,
                    receive);
        }

        if (!errorFlag) {
            int wsOption = normalisation.option().getAsInt();
            MenuOption entry = optionTable.optionBySubscript(wsOption).orElseThrow();
            String targetProgram = entry.menuOptPgmName();

            if (!DUMMY_PROGRAM_PREFIX.equals(targetProgram.substring(0, DUMMY_PREFIX_LENGTH))) {
                NavigationContext transferring = context
                        .withFromTranid(TRANSACTION_ID)
                        .withFromProgram(PROGRAM_NAME)
                        // L149 * MOVE WS-USER-ID TO CDEMO-USER-ID &lt;- COMMENTED OUT IN THE SOURCE L150 *
                        // MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE &lt;- COMMENTED OUT IN THE SOURCE Both are
                        // preserved as comments and NEITHER is implemented.
                        .withPgmEnter();

                // In CICS an XCTL never comes back: control leaves COMEN01C permanently and the statements
                // after END-IF on line 156 - MOVE SPACES TO WS-MESSAGE, MOVE DFHGREEN TO ERRMSGC, the
                // STRING, and PERFORM SEND-MENU-SCREEN - are reachable ONLY when line 146 found 'DUMMY' and
                // skipped the transfer.
                return transferToProgram(targetProgram,
                        transferring,
                        true,
                        wsMessage,
                        optionEcho,
                        receive);
            }

            wsMessage = codec.padToDeclaredWidth(
                    codec.concatenateDelimitedBySize(COMING_SOON_PREFIX,
                            stringDelimitedBySpace(entry.menuOptName()),
                            COMING_SOON_SUFFIX),
                    MESSAGE_LENGTH);
            painted = sendMenuScreen(context,
                    errorFlag,
                    wsMessage,
                    COMING_SOON_MESSAGE_COLOUR,
                    optionEcho,
                    false,
                    optionTable,
                    receive);
        }

        return Objects.requireNonNull(painted, "PROCESS-ENTER-KEY reached its end without painting a "
                + "screen. app/cbl/COMEN01C.cbl:127-165 is three IF statements whose union is total - "
                + "the third is guarded by NOT ERR-FLG-ON and the flag can only have been set by one "
                + "of the first two, each of which paints - so this cannot occur and indicates the "
                + "guard structure has been altered.");
    }

    private MainMenuOutcome returnToSignonScreen(NavigationContext context,
            MainMenuInput input,
            String wsMessage,
            ReceiveOutcome receive) {
        String toProgram = resolveSignonTarget(context.toProgram());

        return transferToProgram(toProgram,
                context.withToProgram(toProgram),
                false,
                wsMessage,
                receivedOptionImage(input),
                receive);
    }

    /**
     * The default of {@code app/cbl/COMEN01C.cbl:172-174}: supply {@code 'COSGN00C'} when
     * {@code CDEMO-TO-PROGRAM} names nothing.
     *
     * @param toProgram {@code CDEMO-TO-PROGRAM} as it stands; padded to its declared
     *     {@value NavigationContext#TO_PROGRAM_LENGTH} characters before the comparison, so a short value is
     *     not mistaken for a populated one
     * @return the transfer target: {@link #SIGNON_PROGRAM} padded to its declared width when the field is
     *     all low-values or all spaces, otherwise the field's own image
     * @throws NullPointerException if {@code toProgram} is {@code null}
     */
    public String resolveSignonTarget(String toProgram) {
        Objects.requireNonNull(toProgram, "CDEMO-TO-PROGRAM is PIC X("
                + NavigationContext.TO_PROGRAM_LENGTH + ") and is never null; pass spaces or "
                + "low-values for a field that names nothing");

        String image = codec.movePicX(toProgram, NavigationContext.TO_PROGRAM_LENGTH);
        if (isAllOf(image, LOW_VALUE) || isAllOf(image, SPACE)) {
            return codec.movePicX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH);
        }
        return image;
    }

    /**
     * Composes the twelve {@code OPTN00nO} lines of {@code BUILD-MENU-OPTIONS},
     * {@code app/cbl/COMEN01C.cbl:236-277}.
     *
     * <p>Java indices are 0-based, and the shift is applied in exactly one place here - the list write -
     * with the COBOL subscript kept as the loop variable so the source and this method count the same way.
     *
     * @param optionTable the table to compose from
     * @return exactly {@value #OPTION_LINE_COUNT} lines of {@value #OPTION_TEXT_LENGTH} characters, index 0
     *     being {@code OPTN001O}; every line the program does not write is spaces
     * @throws NullPointerException if {@code optionTable} is {@code null}
     */
    public List<String> buildMenuOptions(MainMenuOptionTable optionTable) {
        Objects.requireNonNull(optionTable, "An option table is required to compose the menu lines");

        List<String> lines = new ArrayList<>(blankOptionLines());

        for (int wsIdx = 1; wsIdx <= optionTable.activeCount(); wsIdx++) {
            MenuOption entry = optionTable.optionBySubscript(wsIdx).orElseThrow();

            String wsMenuOptTxt = codec.padToDeclaredWidth(
                    codec.concatenateDelimitedBySize(entry.menuOptNumImage(),
                            OPTION_NUMBER_SEPARATOR,
                            entry.menuOptName()),
                    OPTION_TEXT_LENGTH);

            if (wsIdx <= OPTION_DISPATCH_ARM_COUNT) {
                lines.set(wsIdx - 1, wsMenuOptTxt);
            }
        }
        return List.copyOf(lines);
    }

    private MainMenuOutcome sendMenuScreen(NavigationContext context,
            boolean errorFlag,
            String wsMessage,
            byte messageColour,
            String optionEcho,
            boolean resetAllOutputFields,
            MainMenuOptionTable optionTable,
            ReceiveOutcome receive) {
        return new MainMenuOutcome(buildMenuOptions(optionTable),
                wsMessage,
                messageColour,
                errorFlag,
                optionEcho,
                spaces(NavigationContext.TO_PROGRAM_LENGTH),
                false,
                true,
                resetAllOutputFields,
                context,
                TRANSACTION_ID,
                MAPSET_NAME,
                MAP_NAME,
                receive);
    }

    private MainMenuOutcome transferToProgram(String targetProgram,
            NavigationContext context,
            boolean carriesCommarea,
            String wsMessage,
            String optionEcho,
            ReceiveOutcome receive) {
        return new MainMenuOutcome(blankOptionLines(),
                wsMessage,
                MAP_MESSAGE_COLOUR,
                false,
                optionEcho,
                codec.movePicX(targetProgram, NavigationContext.TO_PROGRAM_LENGTH),
                carriesCommarea,
                false,
                false,
                context,
                TRANSACTION_ID,
                spaces(NavigationContext.LAST_MAPSET_LENGTH),
                spaces(NavigationContext.LAST_MAP_LENGTH),
                receive);
    }

    private ReceiveOutcome receiveMenuScreen() {
        return ReceiveOutcome.NORMAL;
    }

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC '} - {@code app/cbl/COMEN01C.cbl:39}, declared and
     * never referenced again anywhere in the program.
     *
     * @return exactly {@value #USRSEC_FILE_NAME_LENGTH} characters
     */
    public String usrSecFileName() {
        return usrSecFileName;
    }

    /**
     * {@code COPY CSUSR01Y.} - {@code app/cbl/COMEN01C.cbl:58}, which brings {@code 01 SEC-USER-DATA} into
     * working storage where not one of its six fields is read or written.
     *
     * <p>The copybook has twelve consumers across the application and this program is one of them, so the
     * declaration is preserved and mapped onto the single Java type that copybook owns rather than being
     * dropped as unused.
     *
     * @return a blank {@link SecUserRecord}, every character field spaces, exactly as un-valued COBOL
     *     working storage presents itself
     */
    public SecUserRecord secUserData() {
        return secUserData;
    }

    /**
     * The codec this service composes every image with.
     *
     * <p>Exposed so that the controller can perform the {@code PIC X(80)} to {@code PIC X(78)}
     * right-truncation of {@link MainMenuOutcome#message()} into {@code ERRMSGO} through the same codec,
     * rather than reaching for a substring and getting the direction wrong.
     *
     * @return the codec supplied at construction, never {@code null}
     */
    public FixedWidthCodec codec() {
        return codec;
    }

    private String stringDelimitedBySpace(String source) {
        int firstSpace = source.indexOf(SPACE);
        return firstSpace < 0 ? source : source.substring(0, firstSpace);
    }

    private String movePicXJustifiedRight(String source, int targetLength) {
        if (source.length() >= targetLength) {
            return source.substring(source.length() - targetLength);
        }
        return spaces(targetLength - source.length()) + source;
    }

    private String inspectReplacingSpacesByZeros(String image) {
        StringBuilder inspected = new StringBuilder(image.length());
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            inspected.append(character == SPACE ? ZERO_DIGIT : character);
        }
        return inspected.toString();
    }

    private static char charAtOneBased(String image, int oneBased) {
        return image.charAt(oneBased - 1);
    }

    private static boolean isAllOf(String image, char character) {
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllDigits(String image) {
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character < ZERO_DIGIT || character > NINE_DIGIT) {
                return false;
            }
        }
        return true;
    }

    private String receivedOptionImage(MainMenuInput input) {
        return codec.movePicX(input.option(), OPTION_LENGTH);
    }

    /**
     * The twelve {@code OPTN00nO} fields as {@code MOVE LOW-VALUES TO COMEN1AO} leaves them, and as they
     * remain on every path that never performs {@code BUILD-MENU-OPTIONS}.
     *
     * @return {@value #OPTION_LINE_COUNT} unpainted lines of {@value #OPTION_TEXT_LENGTH} characters
     */
    private static List<String> blankOptionLines() {
        return Collections.nCopies(OPTION_LINE_COUNT,
                ScreenFieldImage.unpainted(OPTION_TEXT_LENGTH));
    }

    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }
}
