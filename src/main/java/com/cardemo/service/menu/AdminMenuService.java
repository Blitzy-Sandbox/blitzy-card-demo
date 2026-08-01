/*
 * ******************************************************************
 * Program     : AdminMenuService.java
 * Application : CardDemo
 * Type        : Spring Service (migrated from CICS COBOL Program)
 * Function    : Admin Menu for Admin users
 * Source      : app/cbl/COADM01C.cbl (268 lines, 7 paragraphs)
 *               + app/cpy/COADM02Y.cpy @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 ******************************************************************
 */
package com.cardemo.service.menu;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.MenuResponse;

/**
 * The administrator menu of the CardDemo application: the Java counterpart of CICS transaction {@code CA00},
 * which {@code app/csd/CARDDEMO.CSD:L327-L328} binds to program {@code COADM01C}.
 *
 * @see #getMenuScreen()
 * @see #selectOption(String)
 */
@Service
public class AdminMenuService {

    /**
     * Structured logger. Every call site below uses parameterised {@code {}} placeholders rather than
     * concatenation, and no call site logs a credential, a token, a hash or personally identifiable data,
     * because this class never receives any. Trace, span and correlation identifiers are contributed by
     * {@code com.cardemo.observability.CorrelationIdFilter} through MDC and are deliberately not set here.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AdminMenuService.class);

    /**
     * The name of the COBOL program this bean reproduces, from {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} at
     * {@code app/cbl/COADM01C.cbl:L36}.
     */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * The CICS transaction identifier this bean reproduces, from {@code WS-TRANID PIC X(04) VALUE 'CA00'} at
     * {@code app/cbl/COADM01C.cbl:L37}, bound to {@link #PROGRAM_NAME} by
     * {@code app/csd/CARDDEMO.CSD:L327-L328}.
     */
    public static final String TRANSACTION_ID = "CA00";

    /**
     * The sign-on program, from the three places the source names it:
     * {@code MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM} at {@code app/cbl/COADM01C.cbl:L83},
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at {@code :L97}, and the default applied at {@code :L163}.
     */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * The rejection message for an unusable selection, from
     * {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE} at
     * {@code app/cbl/COADM01C.cbl:L131-L132}.
     */
    public static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The informational notice for an option whose target is a placeholder, assembled by the {@code STRING} at
     * {@code app/cbl/COADM01C.cbl:L149-L153}.
     */
    public static final String COMING_SOON_MESSAGE = "This option is coming soon ...";

    /**
     * The placeholder prefix tested by {@code IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} at
     * {@code app/cbl/COADM01C.cbl:L138}.
     */
    private static final String PLACEHOLDER_PROGRAM_PREFIX = "DUMMY";

    /**
     * The declared width of the selection field, from {@code OPTIONI PIC X(2)} at
     * {@code app/cpy-bms/COADM01.CPY:L132}.
     */
    private static final int OPTION_FIELD_LENGTH = 2;

    /**
     * The width of the rendered option line, from {@code WS-ADMIN-OPT-TXT PIC X(40)} at
     * {@code app/cbl/COADM01C.cbl:L48}, matching the twelve {@code OPTN001I} through {@code OPTN012I} screen
     * slots of {@code app/cpy-bms/COADM01.CPY:L60-L126} which are each {@code PIC X(40)}.
     */
    private static final int OPTION_SLOT_LENGTH = 40;

    /**
     * The separator the source concatenates between the option number and the caption, from
     * {@code STRING ... '. ' DELIMITED BY SIZE} at {@code app/cbl/COADM01C.cbl:L234}.
     */
    private static final String OPTION_NUMBER_SEPARATOR = ". ";

    /**
     * The empty message, reproducing {@code MOVE SPACES TO WS-MESSAGE} at {@code app/cbl/COADM01C.cbl:L79} and
     * {@code :L147}.
     */
    private static final String NO_MESSAGE = "";

    /**
     * The absent navigation target, used when a selection resolves no route for the client to follow. Declared at
     * {@code app/cbl/COADM01C.cbl:L138}.
     */
    private static final String NO_TARGET_PROGRAM = "";

    /**
     * The name reported on a rejected selection. It identifies the offending input for a structured log and is
     * a developer-chosen identifier derived from the screen field {@code OPTIONI} at
     * {@code app/cpy-bms/COADM01.CPY:L132}; it is never request data.
     */
    private static final String OPTION_FIELD_NAME = "option";

    /**
     * The four administrative options, bounded by {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at
     * {@code app/cpy/COADM02Y.cpy:L20}.
     */
    private static final List<MenuResponse.AdminMenuOption> CANONICAL_ADMIN_OPTIONS =
            MenuResponse.ADMIN_MENU_OPTIONS;

    /**
     * The header date format, reproducing {@code WS-CURDATE-MM-DD-YY} at {@code app/cpy/CSDAT01Y.cpy:L30-L35}.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * The header time format, reproducing {@code WS-CURTIME-HH-MM-SS} at {@code app/cpy/CSDAT01Y.cpy:L36-L41},
     * whose separator is a colon per the {@code FILLER PIC X(01) VALUE ':'} declarations at {@code :L38} and
     * {@code :L40}.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The screen title, from {@code CCDA-TITLE01 PIC X(40)} at {@code app/cpy/COTTL01Y.cpy:L18-L19}, moved to
     * {@code TITLE01O OF COADM1AO} at {@code app/cbl/COADM01C.cbl:L206}. Carried blank-padded to the declared
     * forty characters exactly as the {@code VALUE} literal declares it.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * The screen subtitle, from {@code CCDA-TITLE02 PIC X(40)} at {@code app/cpy/COTTL01Y.cpy:L20-L22}, moved
     * to {@code TITLE02O OF COADM1AO} at {@code app/cbl/COADM01C.cbl:L207}.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * The clock the header furniture is read from, standing in for
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code app/cbl/COADM01C.cbl:L204}.
     */
    private final Clock clock;

    /**
     * The option table this instance serves. Immutable and never reassigned; in production it is always
     * {@link #CANONICAL_ADMIN_OPTIONS}.
     */
    private final List<MenuResponse.AdminMenuOption> adminMenuOptions;

    /**
     * Creates the production bean: the four canonical options of {@code app/cpy/COADM02Y.cpy} and a clock in
     * the system default zone.
     */
    public AdminMenuService() {
        this(Clock.systemDefaultZone(), CANONICAL_ADMIN_OPTIONS);
    }

    /**
     * Test seam. Creates an instance over a caller-supplied clock and option table.
     *
     * @param clock the clock the header date and time are read from.
     * @param adminMenuOptions the option table to serve, in menu order.
     * @throws IllegalArgumentException if {@code clock} is {@code null}, or if {@code adminMenuOptions} is
     * {@code null}, empty, contains a {@code null} element, or exceeds the populated option count
     */
    AdminMenuService(final Clock clock, final List<MenuResponse.AdminMenuOption> adminMenuOptions) {

        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (adminMenuOptions == null) {
            throw new IllegalArgumentException("adminMenuOptions must not be null");
        }
        if (adminMenuOptions.isEmpty()) {
            throw new IllegalArgumentException(
                    "adminMenuOptions must not be empty; app/cpy/COADM02Y.cpy:L20 populates four options");
        }

        final int populatedOptionCount = MenuResponse.MenuType.ADMIN.getPopulatedOptionCount();
        if (adminMenuOptions.size() > populatedOptionCount) {
            throw new IllegalArgumentException("adminMenuOptions holds " + adminMenuOptions.size()
                    + " entries, which exceeds the " + populatedOptionCount
                    + " populated by app/cpy/COADM02Y.cpy:L20; the OCCURS 9 capacity at :L45 is not a"
                    + " valid bound because its spare subscripts are unpopulated");
        }

        // Pre-sized to the exact final length: the bound above proves it cannot exceed the populated
        // option count, so the copy is allocated once and never resized.
        final List<MenuResponse.AdminMenuOption> defensiveCopy = new ArrayList<>(adminMenuOptions.size());
        for (final MenuResponse.AdminMenuOption option : adminMenuOptions) {
            if (option == null) {
                // defensiveCopy.size() is the index within adminMenuOptions of the rejected element.
                throw new IllegalArgumentException("adminMenuOptions must not contain a null element,"
                        + " but index " + defensiveCopy.size() + " was null");
            }
            defensiveCopy.add(option);
        }

        this.clock = clock;
        this.adminMenuOptions = List.copyOf(defensiveCopy);
    }

    /**
     * Returns the administrator menu as it is first presented, with no message.
     *
     * @return the menu view carrying the four options, their rendered lines and an empty message
     */
    public AdminMenuView getMenuScreen() {
        return sendMenuScreen(NO_MESSAGE);
    }

    /**
     * Resolves an operator's menu selection, reproducing the contract of {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/COADM01C.cbl:L115-L155}.
     *
     * @param option the raw selection as keyed into the {@code OPTIONI} field of
     * {@code app/cpy-bms/COADM01.CPY:L132}.
     * @return the resolved selection: either a real target with an empty message, or a placeholder target with
     * {@link #COMING_SOON_MESSAGE}
     * @throws ValidationException if the normalised selection is not a usable option number
     */
    public AdminMenuSelection selectOption(final String option) {
        return mainPara(option);
    }

    /**
     * Resolves the program the client navigates to when leaving this menu for the sign-on screen, reproducing
     * {@code RETURN-TO-SIGNON-SCREEN} at {@code app/cbl/COADM01C.cbl:L160-L167}.
     *
     * @param requestedProgram the caller's requested target, or {@code null} to take the default.
     * @return {@code requestedProgram} when it carries a value, otherwise {@link #SIGN_ON_PROGRAM}
     */
    public String signOnProgram(final String requestedProgram) {
        return returnToSignOnScreen(requestedProgram);
    }

    /**
     * {@code MAIN-PARA} - {@code app/cbl/COADM01C.cbl:L75-L110}.
     *
     * @param rawOption the unnormalised selection field content, forwarded from {@link #selectOption(String)}
     * @return the resolved selection
     * @throws ValidationException if the selection is not a usable option number
     */
    private AdminMenuSelection mainPara(final String rawOption) {

        // :L77 SET ERR-FLG-OFF TO TRUE, and :L79-L80 MOVE SPACES TO WS-MESSAGE / ERRMSGO OF COADM1AO.
        // Neither needs a statement here. The flag is method-local to processEnterKey, which is the only
        // paragraph that sets or tests it, and the message is a return value rather than a field, so
        // neither can leak between requests the way working storage did between pseudo-conversational
        // turns. Starting cleared is the default rather than an action.
        final String optionField = receiveMenuScreen(rawOption);

        // :L93-L95 EVALUATE EIBAID WHEN DFHENTER PERFORM PROCESS-ENTER-KEY. The other two arms have no
        // counterpart, as set out above.
        return processEnterKey(optionField);
    }

    /**
     * {@code PROCESS-ENTER-KEY} - {@code app/cbl/COADM01C.cbl:L115-L155}.
     *
     * @param optionField the two-character selection field content, as materialised by
     * {@link #receiveMenuScreen(String)}
     * @return the resolved selection
     * @throws ValidationException if the normalised selection is not numeric, exceeds the option count, or is
     * zero
     */
    private AdminMenuSelection processEnterKey(final String optionField) {

        // ---- :L117-L121  PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI BY -1 UNTIL non-space OR 1.
        // The scan starts at the declared field width and walks down, so it strips trailing spaces. It
        // stops at position one unconditionally, which is what makes an all-blank field yield a
        // single-space prefix rather than an empty one.
        int workIndex = OPTION_FIELD_LENGTH;
        while (workIndex > 1 && optionField.charAt(workIndex - 1) == ' ') {
            workIndex--;
        }

        // ---- :L122  MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X, whose PIC X(02) JUST RIGHT
        // right-aligns the prefix and left-pads it with spaces.
        final String scannedPrefix = optionField.substring(0, workIndex);
        final StringBuilder justifiedRight = new StringBuilder(OPTION_FIELD_LENGTH);
        for (int pad = scannedPrefix.length(); pad < OPTION_FIELD_LENGTH; pad++) {
            justifiedRight.append(' ');
        }
        justifiedRight.append(scannedPrefix);

        // ---- :L123  INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'.
        for (int position = 0; position < justifiedRight.length(); position++) {
            if (justifiedRight.charAt(position) == ' ') {
                justifiedRight.setCharAt(position, '0');
            }
        }

        // ---- :L124-L125  MOVE WS-OPTION-X TO WS-OPTION (PIC 9(02)), then echo it to the screen field.
        final String normalisedOption = justifiedRight.toString();

        // ---- :L127-L134  The three disjuncts, in source order.
        // Disjunct one, :L127 WS-OPTION IS NOT NUMERIC. Every character of the two-character field must
        // be a digit. Character.isDigit is deliberately not used: it accepts non-ASCII decimal digits,
        // which a PIC 9(02) field cannot hold, and accepting them would admit input the source rejects.
        boolean optionIsNumeric = true;
        for (int position = 0; position < normalisedOption.length(); position++) {
            final char digit = normalisedOption.charAt(position);
            if (digit < '0' || digit > '9') {
                optionIsNumeric = false;
                break;
            }
        }

        // A non-numeric field has no numeric value; -1 stands for "not evaluated" and is never compared,
        // because the short-circuit below rejects on the numeric disjunct first, exactly as the source's
        // OR does.
        final int optionNumber = optionIsNumeric ? Integer.parseInt(normalisedOption) : -1;

        // Disjunct two, :L128 WS-OPTION > CDEMO-ADMIN-OPT-COUNT: the populated count, four, never the
        // OCCURS 9 capacity. Disjunct three, :L129 WS-OPTION = ZEROS, which is also the path a blank or
        // absent selection arrives on.
        if (!optionIsNumeric || optionNumber > this.adminMenuOptions.size() || optionNumber == 0) {
            // :L130-L133 MOVE 'Y' TO WS-ERR-FLG, move the literal, re-send the screen. The flag exists to
            // suppress the dispatch below, which returning achieves directly.
            LOG.debug("Admin menu selection rejected for transaction {}: option field did not resolve to a"
                    + " usable option number in 1..{}", TRANSACTION_ID, this.adminMenuOptions.size());
            throw ValidationException.invalidField(OPTION_FIELD_NAME, INVALID_OPTION_MESSAGE);
        }

        // ---- :L134-L137  Two blank lines. No user-type gate exists in this program and none is added.

        // ---- :L137 IF NOT ERR-FLG-ON. Control reaches here only with the flag clear, because every path
        // that would have set it has already returned.
        // ---- :L138 the only subscripted reference in the program, safe by the bounds proof above. COBOL
        // subscripts are one-based, Java indices zero-based.
        final MenuResponse.AdminMenuOption selectedOption = this.adminMenuOptions.get(optionNumber - 1);
        final String targetProgram = selectedOption.programName();

        // ---- :L138 the placeholder test itself: the first five characters only, compared byte for byte
        // with no case folding. A name shorter than five characters cannot match, which is the same
        // outcome the reference modification produces for any non-'DUMMY' prefix.
        if (!targetProgram.startsWith(PLACEHOLDER_PROGRAM_PREFIX)) {

            // ---- :L139-L145  CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM and CDEMO-PGM-CONTEXT have no
            // counterpart, and EXEC CICS XCTL becomes URL-based navigation: the name below is inert data
            // and the client navigates. It is never used to load a class or look up a bean.
            LOG.debug("Admin menu option {} of transaction {} resolved to target program {}",
                    optionNumber, TRANSACTION_ID, targetProgram);

            // ---- THE XCTL FALL-THROUGH GUARD. Under CICS, XCTL never returns, so :L147-L154 is
            // unreachable from here. In Java the call would return, so returning now is what preserves
            // the source's behaviour; falling through would announce "coming soon" for every valid
            // selection. Recorded in DECISION_LOG.md as a mechanism substitution.
            return new AdminMenuSelection(optionNumber, selectedOption.optionName(), targetProgram,
                    NO_MESSAGE, false);
        }

        // ---- :L146-L154  Reached only when the target is a placeholder. :L147 blanks the message, :L148
        // colours it green - informational, not an error - and :L149-L153 assembles the notice from two
        // live operands, the option name having been commented out at :L150-L151. :L154 re-sends the
        // screen, which is the caller's business here.
        //
        // INTENTIONAL RETENTION: none of the four shipped program names begins with 'DUMMY', so this
        // branch is unreachable in production. It is retained verbatim for behavioural parity rather than
        // deleted, and it is tracked - not an untracked leftover - by a DECISION_LOG.md entry and by the
        // package-private test seam on this class, which is how it is covered without weakening the
        // coverage gate.
        LOG.debug("Admin menu option {} of transaction {} targets placeholder program prefix {};"
                + " returning the coming-soon notice", optionNumber, TRANSACTION_ID,
                PLACEHOLDER_PROGRAM_PREFIX);

        return new AdminMenuSelection(optionNumber, selectedOption.optionName(), NO_TARGET_PROGRAM,
                COMING_SOON_MESSAGE, true);
    }

    /**
     * The default at {@code :L162-L164} is retained one for one. {@code LOW-VALUES} is a field of binary zeros
     * and {@code SPACES} a field of blanks - the two ways a fixed-width field expresses "unset" - so the Java
     * test covers {@code null}, empty and all-blank, which are their counterparts. The transfer at
     * {@code :L165-L167} has no counterpart: the resolved name is returned and the client navigates. Retained
     * rather than consolidated into its callers, with a DECISION_LOG.md reference.
     *
     * @param requestedProgram the requested target, standing in for {@code CDEMO-TO-PROGRAM}.
     * @return the requested program when it carries a value, otherwise {@link #SIGN_ON_PROGRAM}
     */
    private String returnToSignOnScreen(final String requestedProgram) {

        // :L162-L164 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES -> MOVE 'COSGN00C'. isBlank() covers the
        // all-blank case and the empty case together; null is the Java-only third way to be unset.
        if (requestedProgram == null || requestedProgram.isBlank()) {
            return SIGN_ON_PROGRAM;
        }

        // :L165-L167 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM): no counterpart, the client navigates.
        return requestedProgram;
    }

    /**
     * The two {@code PERFORM} statements at {@code :L174-L175} are preserved in order, and the message move at
     * {@code :L177} becomes {@link AdminMenuView#message()}. The BMS {@code SEND} at {@code :L179-L184} has no
     * counterpart: the map and mapset names address a 3270 screen that is not reimplemented, and the controller
     * serialises the returned view instead. {@code ERASE} clears the physical screen before painting, which a
     * stateless response does implicitly by carrying the whole payload. Retained one for one with a
     * DECISION_LOG.md reference.
     *
     * @param message the message to carry, either empty or one of the two source literals
     * @return the assembled view, never {@code null}
     */
    private AdminMenuView sendMenuScreen(final String message) {

        // :L174 PERFORM POPULATE-HEADER-INFO. The six header fields are screen furniture with no payload
        // counterpart, so the rendered header is surfaced through the log rather than the response. The
        // paragraph is performed here, in the source's order, rather than being folded away.
        final String headerInfo = populateHeaderInfo();

        // :L175 PERFORM BUILD-MENU-OPTIONS.
        final List<String> optionLabels = buildMenuOptions();

        LOG.debug("Sending admin menu for transaction {} with {} options; screen header: {}",
                TRANSACTION_ID, optionLabels.size(), headerInfo);

        // :L177 MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO, and :L179-L184 the BMS SEND itself.
        return new AdminMenuView(MenuResponse.ofAdminMenu(this.adminMenuOptions), optionLabels, message);
    }

    /**
     * The paragraph's whole job is to materialise the input map, of which only {@code OPTIONI PIC X(2)} at
     * {@code app/cpy-bms/COADM01.CPY:L132} is read by any other paragraph. Its Java counterpart is therefore to
     * present the caller's raw value as that fixed-width field: left-aligned and blank-padded to two
     * characters, which is what a 3270 terminal delivers for a partially keyed field and precisely what the
     * backward scan in {@link #processEnterKey(String)} is written to strip.
     *
     * @param rawOption the caller's raw selection.
     * @return the two-character field content, never {@code null} and always exactly
     * {@link #OPTION_FIELD_LENGTH} characters
     * @throws ValidationException if {@code rawOption} is longer than the declared field width
     */
    private String receiveMenuScreen(final String rawOption) {

        // A null reference is the Java counterpart of a field the operator never keyed into: the terminal
        // would have delivered spaces, so that is what is substituted.
        if (rawOption == null) {
            return " ".repeat(OPTION_FIELD_LENGTH);
        }

        if (rawOption.length() > OPTION_FIELD_LENGTH) {
            LOG.debug("Admin menu selection rejected for transaction {}: field content of {} characters"
                    + " exceeds the declared OPTIONI PIC X({}) width", TRANSACTION_ID,
                    rawOption.length(), OPTION_FIELD_LENGTH);
            throw ValidationException.invalidField(OPTION_FIELD_NAME, INVALID_OPTION_MESSAGE);
        }

        // Left-aligned and blank-padded to the declared width, as the terminal delivers it. No trimming
        // and no case folding: the source performs neither, and the scan in processEnterKey expects the
        // padding to be present.
        final StringBuilder fieldContent = new StringBuilder(OPTION_FIELD_LENGTH);
        fieldContent.append(rawOption);
        while (fieldContent.length() < OPTION_FIELD_LENGTH) {
            fieldContent.append(' ');
        }
        return fieldContent.toString();
    }

    /**
     * {@code POPULATE-HEADER-INFO} - {@code app/cbl/COADM01C.cbl:L202-L221}.
     *
     * @return the rendered header as a single diagnostic line, never {@code null}
     */
    private String populateHeaderInfo() {

        // :L204 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA. Read once, so the date and the time cannot
        // straddle a midnight or second boundary between two separate reads of the clock.
        final LocalDateTime currentDateTime = LocalDateTime.now(this.clock);

        // :L211-L215 the date, and :L217-L221 the time. Separators and widths per the copybook above.
        final String currentDate = HEADER_DATE_FORMAT.format(currentDateTime);
        final String currentTime = HEADER_TIME_FORMAT.format(currentDateTime);

        // :L206-L209 the titles, the transaction identifier and the program name. Assembled into one
        // diagnostic line because no payload field carries them; the titles are trimmed of the padding
        // their PIC X(40) declarations carry, which matters only on a fixed-width screen.
        return SCREEN_TITLE_01.strip() + " | " + SCREEN_TITLE_02.strip()
                + " | tranid=" + TRANSACTION_ID + " | pgmname=" + PROGRAM_NAME
                + " | date=" + currentDate + " | time=" + currentTime;
    }

    /**
     * The loop bound at {@code :L228-L229} is {@code CDEMO-ADMIN-OPT-COUNT}, four, and never the
     * {@code OCCURS 9} capacity. Each line is assembled at {@code :L233-L236} from three operands, all
     * {@code DELIMITED BY SIZE}, meaning each contributes its full declared width: the two-digit zero-padded
     * number from {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)}, the two-character literal {@code '. '}, and
     * <strong>the whole 35-character blank-padded caption</strong>. Thirty-nine characters in all, moved into
     * {@code WS-ADMIN-OPT-TXT PIC X(40)}, which is why option one reads {@code 01. User List (Security)} and
     * option four {@code 04. User Delete (Security)} - note {@code 01.} and not {@code 1.}
     *
     * @return the rendered option lines in menu order, one per populated option, as an unmodifiable list
     */
    private List<String> buildMenuOptions() {

        // :L228-L229 PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT. Pre-sized
        // to the option count, so the list is allocated once.
        final List<String> optionLabels = new ArrayList<>(this.adminMenuOptions.size());

        for (final MenuResponse.AdminMenuOption option : this.adminMenuOptions) {

            // :L231 MOVE SPACES TO WS-ADMIN-OPT-TXT: the buffer is cleared before each assembly. A fresh
            // builder per iteration is the same guarantee, and it is why no state survives an iteration.
            final StringBuilder optionLine = new StringBuilder(OPTION_SLOT_LENGTH);

            // :L233 CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE, a PIC 9(02) so zero-padded to two
            // digits. Locale.ROOT is explicit so the digits cannot be localised.
            optionLine.append(String.format(Locale.ROOT, "%02d", option.optionNumber()));

            // :L234 '. ' DELIMITED BY SIZE.
            optionLine.append(OPTION_NUMBER_SEPARATOR);

            // :L235 CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE: the full declared width, padding
            // included, which is what DELIMITED BY SIZE means.
            optionLine.append(option.optionName());

            // :L236 INTO WS-ADMIN-OPT-TXT. Trailing padding stripped for the reason given above;
            // stripTrailing removes only trailing white space and is locale-independent.
            optionLabels.add(optionLine.toString().stripTrailing());
        }

        // :L238-L261 the EVALUATE into screen slots has no counterpart; order carries the same meaning.
        return List.copyOf(optionLabels);
    }

    /**
     * The administrator menu as presented, standing in for the output map {@code COADM1AO} that
     * {@code SEND-MENU-SCREEN} paints at {@code app/cbl/COADM01C.cbl:L179-L184}.
     *
     * @param menu the four options bounded by {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at
     * {@code app/cpy/COADM02Y.cpy:L20}, with captions carried at their declared {@code PIC X(35)} width exactly
     * as the copybook declares them.
     * @param optionLabels the rendered lines built by {@code BUILD-MENU-OPTIONS} at
     * {@code app/cbl/COADM01C.cbl:L226-L263}, one per option and in menu order, for example
     * {@code 01. User List (Security)}.
     * @param message the message field, standing in for {@code MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO} at
     * {@code app/cbl/COADM01C.cbl:L177}.
     */
    public record AdminMenuView(
            MenuResponse<MenuResponse.AdminMenuOption> menu,
            List<String> optionLabels,
            String message) {
    }

    /**
     * The outcome of a menu selection: the Java form of the two paths {@code PROCESS-ENTER-KEY} can leave by at
     * {@code app/cbl/COADM01C.cbl:L137-L155}.
     *
     * @param optionNumber the normalised selection, one through the option count, as echoed back to the screen
     * by {@code MOVE WS-OPTION TO OPTIONO OF COADM1AO} at {@code app/cbl/COADM01C.cbl:L125}
     * @param optionName the selected option's caption from {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} at
     * {@code app/cpy/COADM02Y.cpy:L47}, carried at its declared width exactly as the copybook declares it and
     * never {@code null}
     * @param targetProgram the eight-character target from {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at
     * {@code app/cpy/COADM02Y.cpy:L48} when a real target resolved, otherwise empty.
     * @param message {@link AdminMenuService#COMING_SOON_MESSAGE} on the placeholder path, otherwise empty.
     * @param comingSoon {@code true} only when the target's first five characters matched {@code 'DUMMY'} at
     * {@code app/cbl/COADM01C.cbl:L138}.
     */
    public record AdminMenuSelection(
            int optionNumber,
            String optionName,
            String targetProgram,
            String message,
            boolean comingSoon) {
    }
}
