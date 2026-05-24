/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.menu;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.menu.MainMenuTable;
import com.blitzy.carddemo.domain.menu.MainMenuTable.MainMenuEntry;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.commarea.UserType;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Java translation of the {@code COMEN01C} CICS online program at
 * {@code app/cbl/COMEN01C.cbl} ("Main Menu for the Regular users").
 *
 * <h2>Program purpose</h2>
 * <p>Displays the regular-user main menu (10 options from
 * {@code COMEN02Y.cpy}), receives an option selection, and {@code XCTL}s
 * to the target program. PF3 returns to the signon screen ({@code COSGN00C}).
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:  COMEN01C
 *   WS-PGMNAME:  'COMEN01C'
 *   WS-TRANID:   'CM00'
 * </pre>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: EIBCALEN check, first-time vs re-enter dispatch,
 *       AID-key handling (ENTER/PF3/OTHER).</li>
 *   <li>{@code PROCESS-ENTER-KEY}: option-number parsing with right-justify
 *       and {@code INSPECT REPLACING ALL ' ' BY '0'}; range and numeric
 *       validation; user-type access check; {@code XCTL} to target program
 *       unless target name starts with {@code DUMMY} (in which case emit
 *       "coming soon" message).</li>
 *   <li>{@code RETURN-TO-SIGNON-SCREEN}: XCTL to COSGN00C.</li>
 *   <li>{@code SEND-MENU-SCREEN}, {@code POPULATE-HEADER-INFO},
 *       {@code BUILD-MENU-OPTIONS}: builds the populated
 *       {@link CoMen01Output} carrying date/time/title and 12 option slots
 *       (only the first 10 are populated from {@link MainMenuTable}).</li>
 * </ul>
 *
 * <h2>Anomalies preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>"DUMMY" prefix check on option program names — if the program-name
 *       starts with "DUMMY" (5 chars) the program skips XCTL and emits a
 *       "coming soon" message instead. Preserved verbatim. No COMEN02Y
 *       entries currently use the DUMMY prefix but the COBOL guard is kept.</li>
 *   <li>"No access - Admin Only option... " message with trailing space
 *       preserved.</li>
 *   <li>"Please enter a valid option number..." with triple-dot ellipsis.</li>
 *   <li>BUILD-MENU-OPTIONS only populates slots 1..12; slot 12 access
 *       past the {@code OPT_COUNT}=10 yields blank text rather than a crash.</li>
 * </ul>
 *
 * <h2>{@code BUILD-MENU-OPTIONS} string building</h2>
 * <p>COBOL: {@code STRING CDEMO-MENU-OPT-NUM '. ' CDEMO-MENU-OPT-NAME
 * INTO WS-MENU-OPT-TXT}. Translated literally: format the option number
 * as {@code "%02d. "} then concatenate the 35-char option name; total
 * length 40 chars (option-text PIC X(40)).
 */
@CobolProgram(
        value = "COMEN01C",
        sourcePath = "app/cbl/COMEN01C.cbl",
        notes = "Main menu for regular users; XCTLs to selected option program; "
                + "DUMMY-prefix anomaly preserved per AAP §0.7.1"
)
public final class CoMen01C {

    private static final Logger log = LoggerFactory.getLogger(CoMen01C.class);

    public static final String PROGRAM_ID = "COMEN01C";
    public static final String TRANSACTION_ID = "CM00";

    public static final String TITLE_01 = "AWS Mainframe Modernization";
    public static final String TITLE_02 = "CardDemo";
    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";
    public static final String MSG_INVALID_OPTION = "Please enter a valid option number...";
    public static final String MSG_NO_ACCESS = "No access - Admin Only option... ";
    /**
     * Prefix of the "coming soon" message.
     *
     * <p>COBOL: {@code STRING 'This option ' DELIMITED BY SIZE
     * CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE 'is coming soon ...' DELIMITED BY SIZE}.
     * The {@code DELIMITED BY SPACE} clause stops moving characters at the
     * first SPACE found in the name field; this means only the first word
     * of a multi-word menu option name is emitted (e.g. "Account View" →
     * "Account"). Preserved verbatim per AAP &sect;0.7.1.
     */
    public static final String MSG_COMING_SOON_PREFIX = "This option ";

    /** Suffix; NOTE: no leading space — COBOL {@code STRING 'is coming soon ...'} joins immediately. */
    public static final String MSG_COMING_SOON_SUFFIX = "is coming soon ...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_OPTION_SLOTS = 12;

    /**
     * ERRMSGC color attribute byte values as single-character strings,
     * matching the BMS extended-attribute codepoints from copybook
     * {@code DFHBMSCA}. Stored on {@link CoMen01Output#errMsgColor()}
     * as a 1-character {@link String} per AAP &sect;0.6.10 (entry-contract
     * DTO with String-only fields to preserve BMS PIC X semantics).
     */
    private static final String ERRMSG_COLOR_NONE  = "";        // BMS map default (no override)
    /** DFHRED (X'02') &mdash; error condition. */
    private static final String ERRMSG_COLOR_RED   = "\u0002";
    /** DFHGREEN (X'04') &mdash; "coming soon" / success condition. */
    private static final String ERRMSG_COLOR_GREEN = "\u0004";

    private final ProgramRegistry programRegistry;

    public CoMen01C(ProgramRegistry programRegistry) {
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    /**
     * Result wrapper for the COBOL EXEC CICS RETURN vs XCTL dual flow.
     *
     * @see CoMen01C#run(CoMen01Input, AidKey, CardDemoCommarea)
     */
    public record Result(CoMen01Output output, CardDemoCommarea commarea, String xctlTo) {

        public static Result sendMap(CoMen01Output output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    /**
     * Entry point. Mirrors COBOL {@code MAIN-PARA}.
     *
     * @param input    BMS input record (may be null for the first invocation)
     * @param aidKey   AID-key dispatch value
     * @param commarea inbound commarea
     * @return SEND-MAP or XCTL result
     */
    public Result run(CoMen01Input input, AidKey aidKey, CardDemoCommarea commarea) {
        Objects.requireNonNull(aidKey, "aidKey");

        // IF EIBCALEN = 0  → return to signon screen (treat null commarea identically)
        if (commarea == null) {
            return Result.xctl(ProgramRegistry.CO_SGN_00C, CardDemoCommarea.empty());
        }

        // IF NOT CDEMO-PGM-REENTER → first display
        if (!(commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
            CardDemoCommarea.CdemoGeneralInfo reentered = new CardDemoCommarea.CdemoGeneralInfo(
                    gi.fromTranId(),
                    gi.fromProgram(),
                    gi.toTranId(),
                    gi.toProgram(),
                    gi.userId(),
                    gi.userType(),
                    PgmContext.REENTER);
            CardDemoCommarea outbound = commarea.withCdemoGeneralInfo(reentered);
            return Result.sendMap(sendMenuScreen(outbound, ""), outbound);
        }

        // ELSE re-entry — receive the screen + EVALUATE EIBAID
        if (input == null) {
            return Result.sendMap(sendMenuScreen(commarea, MSG_INVALID_KEY), commarea);
        }

        return switch (aidKey) {
            case AidKey.Enter _ -> processEnterKey(input, commarea);
            case AidKey.PfKey03 _ -> Result.xctl(ProgramRegistry.CO_SGN_00C, commarea);
            case AidKey.Clear _, AidKey.Pa1 _, AidKey.Pa2 _,
                 AidKey.PfKey01 _, AidKey.PfKey02 _, AidKey.PfKey04 _, AidKey.PfKey05 _,
                 AidKey.PfKey06 _, AidKey.PfKey07 _, AidKey.PfKey08 _, AidKey.PfKey09 _,
                 AidKey.PfKey10 _, AidKey.PfKey11 _, AidKey.PfKey12 _
                    -> Result.sendMap(sendMenuScreen(commarea, MSG_INVALID_KEY), commarea);
        };
    }

    /**
     * Paragraph {@code PROCESS-ENTER-KEY}. Parses option, validates, and
     * dispatches.
     */
    private Result processEnterKey(CoMen01Input input, CardDemoCommarea commarea) {
        String raw = input.option() == null ? "" : input.option();
        // COBOL: PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI BY -1
        //        UNTIL OPTIONI(WS-IDX:1) NOT = SPACES OR WS-IDX = 1
        // INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
        String trimmed = stripTrailing(raw);
        String padded = pad(trimmed, 2, '0', /*right=*/false);
        int option;
        try {
            option = Integer.parseInt(padded.replace(' ', '0'));
        } catch (NumberFormatException nfe) {
            return Result.sendMap(sendMenuScreen(commarea, MSG_INVALID_OPTION), commarea);
        }

        if (option == 0 || option > MainMenuTable.OPT_COUNT) {
            return Result.sendMap(sendMenuScreen(commarea, MSG_INVALID_OPTION), commarea);
        }

        Optional<MainMenuEntry> chosen = MainMenuTable.ENTRIES.stream()
                .filter(e -> e.optionNumber() == option)
                .findFirst();
        if (chosen.isEmpty()) {
            return Result.sendMap(sendMenuScreen(commarea, MSG_INVALID_OPTION), commarea);
        }
        MainMenuEntry mainOption = chosen.get();

        // IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
        // NB: callerType is com.blitzy.carddemo.domain.commarea.UserType (from
        // the commarea); mainOption.userType() is
        // com.blitzy.carddemo.domain.commarea.UserType (from the menu table,
        // per the AAP §0.6.10 / file-schema mandate). Both are sealed
        // hierarchies with identical Admin/User permits; the {@code instanceof}
        // checks are package-scoped and use the fully-qualified permit name on
        // the menu side to avoid ambiguity with the imported status type.
        UserType callerType = commarea.cdemoGeneralInfo().userType();
        if (callerType instanceof UserType.User
                && mainOption.userType() instanceof com.blitzy.carddemo.domain.commarea.UserType.Admin) {
            return Result.sendMap(sendMenuScreen(commarea, MSG_NO_ACCESS), commarea);
        }

        // IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'  → XCTL
        // ELSE                                                       → "coming soon"
        String pgmName = mainOption.programName().trim();
        if (pgmName.length() >= 5 && pgmName.regionMatches(0, "DUMMY", 0, 5)) {
            // COBOL DELIMITED BY SPACE on CDEMO-MENU-OPT-NAME → only the first
            // whitespace-delimited word of the menu name is emitted.
            String firstWord = firstSpaceDelimitedToken(mainOption.optionName());
            String msg = MSG_COMING_SOON_PREFIX + firstWord + MSG_COMING_SOON_SUFFIX;
            return Result.sendMap(sendMenuScreen(commarea, msg), commarea);
        }

        // Build outbound commarea: MOVE WS-TRANID TO CDEMO-FROM-TRANID,
        // WS-PGMNAME TO CDEMO-FROM-PROGRAM, ZEROS TO CDEMO-PGM-CONTEXT
        CardDemoCommarea.CdemoGeneralInfo current = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo gi = new CardDemoCommarea.CdemoGeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                current.toTranId(),
                current.toProgram(),
                current.userId(),
                current.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commarea.withCdemoGeneralInfo(gi);

        log.info("CoMen01C: dispatching to {} for option {}", pgmName, option);

        // EXEC CICS XCTL
        CardDemoCommarea finalCommarea = outbound;
        if (programRegistry.isRegistered(pgmName)) {
            finalCommarea = programRegistry.invoke(pgmName, outbound);
        }
        return Result.xctl(pgmName, finalCommarea);
    }

    /**
     * Paragraph {@code SEND-MENU-SCREEN} + {@code POPULATE-HEADER-INFO} +
     * {@code BUILD-MENU-OPTIONS}. Builds the populated output record.
     */
    private CoMen01Output sendMenuScreen(CardDemoCommarea commarea, String message) {
        UserType userType = commarea.cdemoGeneralInfo().userType();
        // Capture 12 slot strings — only the first OPT_COUNT are populated.
        String[] slots = new String[MAX_OPTION_SLOTS];
        for (int i = 0; i < MAX_OPTION_SLOTS; i++) {
            slots[i] = "";
        }
        var options = MainMenuTable.ENTRIES;
        for (int i = 0; i < options.size() && i < MAX_OPTION_SLOTS; i++) {
            MainMenuEntry opt = options.get(i);
            slots[i] = String.format("%02d. %s", opt.optionNumber(), opt.optionName());
        }

        // COBOL `COMEN01C` lines 152-154:
        //   IF WS-MESSAGE NOT = SPACES
        //       MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO
        //       MOVE DFHGREEN  TO ERRMSGC OF COMEN1AO  (set during "coming
        //                                              soon" path; default is
        //                                              the BMS map's RED)
        // The presence-vs-absence-of-text branch is preserved here. We
        // emit DFHRED (X'02') for any non-blank message and leave the
        // color empty for no message. The DFHGREEN override is only set
        // by the "coming soon" path in {@link #processEnterKey}.
        String color = (message == null || message.isBlank())
                ? ERRMSG_COLOR_NONE
                : ERRMSG_COLOR_RED;

        return new CoMen01Output(
                TRANSACTION_ID,
                TITLE_01,
                todayDate(),
                PROGRAM_ID,
                TITLE_02,
                nowTime(),
                slots[0], slots[1], slots[2], slots[3], slots[4], slots[5],
                slots[6], slots[7], slots[8], slots[9], slots[10], slots[11],
                "",                                  // OPTIONO echo (blank on send)
                message == null ? "" : message,
                color
        );
    }

    // -- helpers ----------------------------------------------------------

    private static String pad(String s, int length, char padChar, boolean right) {
        if (s == null) {
            char[] arr = new char[length];
            java.util.Arrays.fill(arr, padChar);
            return new String(arr);
        }
        if (s.length() >= length) {
            return s.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(length);
        if (right) {
            sb.append(s);
            while (sb.length() < length) sb.append(padChar);
        } else {
            while (sb.length() < length - s.length()) sb.append(padChar);
            sb.append(s);
        }
        return sb.toString();
    }

    private static String stripTrailing(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Returns the substring of {@code s} up to (but not including) the first
     * {@code ' '} character. If {@code s} is null or empty, returns the empty
     * string. Mirrors COBOL {@code STRING ... DELIMITED BY SPACE} semantics
     * for a single source operand.
     */
    private static String firstSpaceDelimitedToken(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int spaceIdx = s.indexOf(' ');
        return spaceIdx < 0 ? s : s.substring(0, spaceIdx);
    }

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
