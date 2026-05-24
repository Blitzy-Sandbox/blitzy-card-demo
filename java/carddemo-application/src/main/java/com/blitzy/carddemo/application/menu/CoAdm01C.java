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
import com.blitzy.carddemo.domain.menu.AdminMenuTable;
import com.blitzy.carddemo.domain.menu.AdminMenuTable.AdminMenuEntry;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Java translation of the {@code COADM01C} CICS online program at
 * {@code app/cbl/COADM01C.cbl} ("Admin Menu for Admin users").
 *
 * <h2>Program purpose</h2>
 * <p>Displays the admin main menu (4 options from {@code COADM02Y.cpy}),
 * receives an option selection, and {@code XCTL}s to the target program.
 * PF3 returns to the signon screen ({@code COSGN00C}).
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:  COADM01C
 *   WS-PGMNAME:  'COADM01C'
 *   WS-TRANID:   'CA00'
 * </pre>
 *
 * <h2>Notable differences from {@link CoMen01C}</h2>
 * <ul>
 *   <li>The admin menu table {@code COADM02Y} contains no
 *       {@code CDEMO-ADMIN-OPT-USRTYPE} column — admin programs are
 *       always reached from this screen, which is only itself routed to
 *       from a successful admin sign-on. Therefore there is no user-type
 *       access check at this level.</li>
 *   <li>"Coming soon" message in COADM01C has the option name
 *       <em>commented out</em> in the source, yielding the literal
 *       message {@code "This option is coming soon ..."} instead of
 *       interpolating the option name. Preserved verbatim per
 *       AAP &sect;0.7.1.</li>
 * </ul>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: EIBCALEN check, first-time vs re-enter dispatch,
 *       AID-key handling (ENTER/PF3/OTHER).</li>
 *   <li>{@code PROCESS-ENTER-KEY}: option-number parsing with right-justify
 *       and {@code INSPECT REPLACING ALL ' ' BY '0'}; range and numeric
 *       validation; XCTL to target program unless target name starts with
 *       {@code DUMMY}.</li>
 *   <li>{@code RETURN-TO-SIGNON-SCREEN}: XCTL to COSGN00C.</li>
 *   <li>{@code SEND-MENU-SCREEN}, {@code POPULATE-HEADER-INFO},
 *       {@code BUILD-MENU-OPTIONS}: builds the populated
 *       {@link CoAdm01Output}.</li>
 * </ul>
 */
@CobolProgram(
        value = "COADM01C",
        sourcePath = "app/cbl/COADM01C.cbl",
        notes = "Admin menu; XCTLs to selected admin program; "
                + "DUMMY-prefix anomaly + commented-out option name "
                + "preserved per AAP §0.7.1"
)
public final class CoAdm01C {

    private static final Logger log = LoggerFactory.getLogger(CoAdm01C.class);

    public static final String PROGRAM_ID = "COADM01C";
    public static final String TRANSACTION_ID = "CA00";

    public static final String TITLE_01 = "AWS Mainframe Modernization";
    public static final String TITLE_02 = "CardDemo";
    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";
    public static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /**
     * "Coming soon" message preserved verbatim from
     * {@code app/cbl/COADM01C.cbl:147-153}. The COBOL source contains a
     * commented-out reference to {@code CDEMO-ADMIN-OPT-NAME(WS-OPTION)},
     * leaving the literal {@code 'This option '} prefix joined directly to
     * the {@code 'is coming soon ...'} suffix with no space between. The
     * resulting message therefore reads
     * <strong>"This option is coming soon ..."</strong> with a single space
     * between "option" and "is" (from the prefix trailing space). Preserved
     * verbatim per AAP &sect;0.7.1.
     */
    public static final String MSG_COMING_SOON = "This option is coming soon ...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Number of {@code OPTN0nn} option slots surfaced by
     * {@link CoAdm01Output} (and {@link CoAdm01Input}). Bound to the
     * DTO's {@link CoAdm01Output#OPTION_LINE_COUNT} constant so the
     * loop bounds in {@link #sendMenuScreen} cannot drift out of sync
     * with the record's component count. The admin menu BMS source
     * {@code app/bms/COADM01.bms} declares twelve display fields but
     * the entry-contract DTO models only the ten that the
     * {@link AdminMenuTable} lookup can populate (see
     * {@link CoAdm01Output} class Javadoc for the rationale).
     */
    private static final int MAX_OPTION_SLOTS = CoAdm01Output.OPTION_LINE_COUNT;

    private final ProgramRegistry programRegistry;

    public CoAdm01C(ProgramRegistry programRegistry) {
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    /**
     * Result wrapper for the COBOL EXEC CICS RETURN vs XCTL dual flow.
     */
    public record Result(CoAdm01Output output, CardDemoCommarea commarea, String xctlTo) {

        public static Result sendMap(CoAdm01Output output, CardDemoCommarea commarea) {
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
     */
    public Result run(CoAdm01Input input, AidKey aidKey, CardDemoCommarea commarea) {
        Objects.requireNonNull(aidKey, "aidKey");

        // IF EIBCALEN = 0  → MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM, return to signon
        if (commarea == null) {
            return Result.xctl(ProgramRegistry.CO_SGN_00C, CardDemoCommarea.empty());
        }

        // IF NOT CDEMO-PGM-REENTER → first display
        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
            CardDemoCommarea.GeneralInfo reentered = new CardDemoCommarea.GeneralInfo(
                    gi.fromTranId(),
                    gi.fromProgram(),
                    gi.toTranId(),
                    gi.toProgram(),
                    gi.userId(),
                    gi.userType(),
                    PgmContext.REENTER);
            CardDemoCommarea outbound = commarea.withGeneralInfo(reentered);
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
     * Paragraph {@code PROCESS-ENTER-KEY}.
     */
    private Result processEnterKey(CoAdm01Input input, CardDemoCommarea commarea) {
        String raw = input.option() == null ? "" : input.option();
        String trimmed = stripTrailing(raw);
        String padded = pad(trimmed, 2, '0', /*right=*/false);
        int option;
        try {
            option = Integer.parseInt(padded.replace(' ', '0'));
        } catch (NumberFormatException nfe) {
            return Result.sendMap(sendMenuScreen(commarea, MSG_INVALID_OPTION), commarea);
        }

        if (option == 0 || option > AdminMenuTable.OPT_COUNT) {
            return Result.sendMap(sendMenuScreen(commarea, MSG_INVALID_OPTION), commarea);
        }

        // option has been validated to be in [1, OPT_COUNT]; AdminMenuTable's
        // static initializer guarantees ENTRIES.get(i) has optionNumber == i+1.
        AdminMenuEntry adminOption = AdminMenuTable.ENTRIES.get(option - 1);

        // IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'  → XCTL
        // ELSE                                                       → "coming soon"
        String pgmName = adminOption.programName().trim();
        if (pgmName.length() >= 5 && pgmName.regionMatches(0, "DUMMY", 0, 5)) {
            // NOTE: COBOL source has CDEMO-ADMIN-OPT-NAME commented out, so
            // the message contains no option name. Preserved verbatim.
            return Result.sendMap(sendMenuScreen(commarea, MSG_COMING_SOON), commarea);
        }

        // Build outbound commarea
        CardDemoCommarea.GeneralInfo current = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo gi = new CardDemoCommarea.GeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                current.toTranId(),
                current.toProgram(),
                current.userId(),
                current.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commarea.withGeneralInfo(gi);

        log.info("CoAdm01C: dispatching to {} for option {}", pgmName, option);

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
    private CoAdm01Output sendMenuScreen(CardDemoCommarea commarea, String message) {
        String[] slots = new String[MAX_OPTION_SLOTS];
        for (int i = 0; i < MAX_OPTION_SLOTS; i++) {
            slots[i] = "";
        }
        var options = AdminMenuTable.ENTRIES;
        for (int i = 0; i < options.size() && i < MAX_OPTION_SLOTS; i++) {
            AdminMenuEntry opt = options.get(i);
            slots[i] = String.format("%02d. %s", opt.optionNumber(), opt.optionName());
        }

        // ERRMSGC attribute byte: COBOL COADM01C does NOT programmatically
        // set ERRMSGC (unlike COMEN01C which uses MOVE DFHGREEN TO ERRMSGC
        // on the coming-soon path). We map the prior FieldColor semantics
        // onto the schema-mandated String errMsgColor component: "" means
        // "no override / leave at BMS compile-time COLOR=RED default", and
        // "R" emits the DFHRED single-char extended-color attribute when
        // a runtime error message is present.
        String errMsgColor = (message == null || message.isBlank()) ? "" : "R";

        return new CoAdm01Output(
                TRANSACTION_ID,
                TITLE_01,
                todayDate(),
                PROGRAM_ID,
                TITLE_02,
                nowTime(),
                slots[0], slots[1], slots[2], slots[3], slots[4],
                slots[5], slots[6], slots[7], slots[8], slots[9],
                "",                                  // OPTIONO echo (blank on send)
                message == null ? "" : message,
                errMsgColor
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

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
