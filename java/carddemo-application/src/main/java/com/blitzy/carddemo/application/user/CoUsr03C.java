/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.user;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.port.UserSecurityRepository;
import com.blitzy.carddemo.domain.record.SecUserData;
import com.blitzy.carddemo.domain.status.PgmContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Java translation of the {@code COUSR03C} CICS online program at
 * {@code app/cbl/COUSR03C.cbl} ("Delete a user from USRSEC file").
 *
 * <h2>Program purpose</h2>
 * <p>Displays the user-delete screen. Operator enters a user ID and presses
 * ENTER to fetch and display the user's name and type; then presses PF5 to
 * confirm deletion. PF3 returns to the previous program; PF4 clears the
 * screen; PF12 cancels back to the admin menu.
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:  COUSR03C
 *   WS-PGMNAME:  'COUSR03C'
 *   WS-TRANID:   'CU03'
 * </pre>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: EIBCALEN check, first-time vs re-enter dispatch,
 *       AID-key handling (ENTER/PF3/PF4/PF5/PF12/OTHER).</li>
 *   <li>{@code PROCESS-ENTER-KEY}: USRIDINI empty-check, READ-USER-SEC-FILE,
 *       populate first/last name + user type fields, display "Press PF5 key
 *       to delete this user ..." prompt.</li>
 *   <li>{@code DELETE-USER-INFO}: empty-check, READ-USER-SEC-FILE,
 *       DELETE-USER-SEC-FILE.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN}: re-display with all fields blanked.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN}: XCTL to CDEMO-TO-PROGRAM (defaults
 *       to COSGN00C if empty).</li>
 *   <li>{@code READ-USER-SEC-FILE}: EXEC CICS READ DATASET('USRSEC')
 *       UPDATE; translated to {@link UserSecurityRepository#findById(String)}.</li>
 *   <li>{@code DELETE-USER-SEC-FILE}: EXEC CICS DELETE DATASET('USRSEC');
 *       translated to {@link UserSecurityRepository#delete(String)}.</li>
 * </ul>
 *
 * <h2>Messages preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>"User ID can NOT be empty..." (triple-dot ellipsis)</li>
 *   <li>"Press PF5 key to delete this user ..."</li>
 *   <li>"User ID NOT found..."</li>
 *   <li>"Unable to lookup User..."</li>
 *   <li>"Unable to Update User..." (note: appears in DELETE path, not Update)</li>
 *   <li>"User &lt;id&gt; has been deleted ..." (with {@code DELIMITED BY SPACE}
 *       semantics — first whitespace-delimited token of SEC-USR-ID only)</li>
 * </ul>
 *
 * <h2>{@code CDEMO-CU03-INFO} preselect path</h2>
 * <p>The COBOL program extends the commarea with {@code CDEMO-CU03-INFO}
 * containing {@code CDEMO-CU03-USR-SELECTED}. When the user navigates from
 * COUSR00C (user list) with a row selected, the COBOL program automatically
 * pre-populates USRIDINI and performs an immediate lookup. Since
 * {@link CardDemoCommarea} does not (yet) model this extension, the Java
 * translation exposes the preselected-user ID as a separate parameter on
 * {@link #run(CoUsr03Input, CardDemoCommarea, String) run}; if non-empty
 * and the call is first-time entry, the preselected ID is used to populate
 * the input and trigger {@code PROCESS-ENTER-KEY} immediately.
 */
@CobolProgram(
        value = "COUSR03C",
        sourcePath = "app/cbl/COUSR03C.cbl",
        notes = "User delete; PF5 confirms; preselected-user-from-list path "
                + "preserved via separate parameter (no commarea extension)"
)
public final class CoUsr03C {

    private static final Logger log = LoggerFactory.getLogger(CoUsr03C.class);

    public static final String PROGRAM_ID = "COUSR03C";
    public static final String TRANSACTION_ID = "CU03";

    public static final String TITLE_01 = "AWS Mainframe Modernization";
    public static final String TITLE_02 = "CardDemo";
    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";

    /** "User ID can NOT be empty..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

    /** "Press PF5 key to delete this user ..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_PRESS_PF5 = "Press PF5 key to delete this user ...";

    /** "User ID NOT found..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_NOT_FOUND = "User ID NOT found...";

    /** "Unable to lookup User..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_LOOKUP_ERROR = "Unable to lookup User...";

    /**
     * COBOL-faithful: the DELETE error path raises "Unable to Update User..."
     * (mentioning Update rather than Delete) due to a copy/paste of the
     * COUSR02C error message. Preserved verbatim per AAP &sect;0.7.1.
     */
    public static final String MSG_DELETE_ERROR = "Unable to Update User...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final UserSecurityRepository userSecurity;
    private final ProgramRegistry programRegistry;

    public CoUsr03C(UserSecurityRepository userSecurity, ProgramRegistry programRegistry) {
        this.userSecurity = Objects.requireNonNull(userSecurity, "userSecurity");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    /**
     * Result wrapper for the COBOL EXEC CICS RETURN vs XCTL dual flow.
     */
    public record Result(CoUsr03Output output, CardDemoCommarea commarea, String xctlTo) {

        public static Result sendMap(CoUsr03Output output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    /**
     * Convenience overload with no preselected-user.
     */
    public Result run(CoUsr03Input input, CardDemoCommarea commarea) {
        return run(input, commarea, null);
    }

    /**
     * Entry point. Mirrors COBOL {@code MAIN-PARA}.
     *
     * @param input              BMS input record (may be null first-time)
     * @param commarea           inbound commarea
     * @param preselectedUserId  optional pre-populated user id (translates the
     *                           CDEMO-CU03-USR-SELECTED first-time shortcut)
     */
    public Result run(CoUsr03Input input, CardDemoCommarea commarea, String preselectedUserId) {
        // IF EIBCALEN = 0  → return to signon screen
        if (commarea == null) {
            CardDemoCommarea outbound = CardDemoCommarea.empty();
            CardDemoCommarea.GeneralInfo gi = new CardDemoCommarea.GeneralInfo(
                    TRANSACTION_ID, PROGRAM_ID, "COSGN00C", "COSGN00C", "", null, PgmContext.ENTER);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound.withGeneralInfo(gi));
        }

        // IF NOT CDEMO-PGM-REENTER → first display
        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reenteredCommarea = withPgmContext(commarea, PgmContext.REENTER);
            CoUsr03Input freshInput = preselectedUserId != null && !preselectedUserId.isBlank()
                    ? CoUsr03Input.blank().withUserId(preselectedUserId)
                    : CoUsr03Input.blank();
            if (preselectedUserId != null && !preselectedUserId.isBlank()) {
                // PERFORM PROCESS-ENTER-KEY immediately
                return processEnterKey(freshInput, reenteredCommarea);
            }
            return Result.sendMap(buildScreen(freshInput, "", reenteredCommarea), reenteredCommarea);
        }

        // ELSE re-entry — receive the screen + EVALUATE EIBAID
        if (input == null) {
            return Result.sendMap(buildScreen(CoUsr03Input.blank(), MSG_INVALID_KEY, commarea), commarea);
        }

        return switch (input.aidKey()) {
            case ENTER -> processEnterKey(input, commarea);
            case PF03_BACK -> returnToPrevScreen(commarea, /*defaultProgramOnEmpty=*/null);
            case PF04_CLEAR -> clearCurrentScreen(commarea);
            case PF05_DELETE -> deleteUserInfo(input, commarea);
            case PF12_CANCEL -> returnToPrevScreen(commarea, ProgramRegistry.CO_ADM_01C);
            case OTHER -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY, commarea), commarea);
        };
    }

    /**
     * Paragraph {@code PROCESS-ENTER-KEY}. Looks up the user and displays
     * the "Press PF5 ..." prompt.
     */
    private Result processEnterKey(CoUsr03Input input, CardDemoCommarea commarea) {
        if (input.userId() == null || input.userId().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_USERID_EMPTY, commarea), commarea);
        }

        try {
            Optional<SecUserData> userOpt = userSecurity.findById(input.userId());
            if (userOpt.isEmpty()) {
                // DFHRESP(NOTFND)
                return Result.sendMap(buildScreen(input, MSG_NOT_FOUND, commarea), commarea);
            }
            // DFHRESP(NORMAL) — populate first/last name and user type
            SecUserData user = userOpt.get();
            CoUsr03Input populated = new CoUsr03Input(
                    user.secUsrId(),
                    user.secUsrFname(),
                    user.secUsrLname(),
                    user.secUsrType(),
                    input.aidKey());
            return Result.sendMap(buildScreen(populated, MSG_PRESS_PF5, commarea), commarea);
        } catch (RuntimeException re) {
            // WHEN OTHER — log RESP/REAS, "Unable to lookup User..."
            log.warn("CoUsr03C: USRSEC read failed for userId={}", input.userId(), re);
            return Result.sendMap(buildScreen(input, MSG_LOOKUP_ERROR, commarea), commarea);
        }
    }

    /**
     * Paragraph {@code DELETE-USER-INFO}. Validates, reads (for existence),
     * then deletes the user record.
     */
    private Result deleteUserInfo(CoUsr03Input input, CardDemoCommarea commarea) {
        if (input.userId() == null || input.userId().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_USERID_EMPTY, commarea), commarea);
        }

        // READ first to mirror the COBOL flow (READ UPDATE precedes DELETE)
        try {
            Optional<SecUserData> existing = userSecurity.findById(input.userId());
            if (existing.isEmpty()) {
                return Result.sendMap(buildScreen(input, MSG_NOT_FOUND, commarea), commarea);
            }
            // DELETE
            userSecurity.delete(input.userId());
            // STRING 'User ' DELIMITED BY SIZE SEC-USR-ID DELIMITED BY SPACE
            //        ' has been deleted ...' DELIMITED BY SIZE
            String firstWord = firstSpaceDelimitedToken(existing.get().secUsrId());
            String successMessage = "User " + firstWord + " has been deleted ...";
            // PERFORM INITIALIZE-ALL-FIELDS — blank input
            return Result.sendMap(
                    buildScreen(CoUsr03Input.blank(), successMessage, commarea),
                    commarea);
        } catch (NoSuchElementException nsee) {
            return Result.sendMap(buildScreen(input, MSG_NOT_FOUND, commarea), commarea);
        } catch (RuntimeException re) {
            log.warn("CoUsr03C: USRSEC delete failed for userId={}", input.userId(), re);
            return Result.sendMap(buildScreen(input, MSG_DELETE_ERROR, commarea), commarea);
        }
    }

    /**
     * Paragraph {@code CLEAR-CURRENT-SCREEN}: re-display with all fields blanked.
     */
    private Result clearCurrentScreen(CardDemoCommarea commarea) {
        return Result.sendMap(buildScreen(CoUsr03Input.blank(), "", commarea), commarea);
    }

    /**
     * Paragraph {@code RETURN-TO-PREV-SCREEN}.
     *
     * @param defaultProgramOnEmpty fallback if CDEMO-FROM-PROGRAM is empty
     *                              (COSGN00C for PF3, COADM01C for PF12)
     */
    private Result returnToPrevScreen(CardDemoCommarea commarea, String defaultProgramOnEmpty) {
        String fromProgram = commarea.generalInfo().fromProgram();
        String target;
        if (defaultProgramOnEmpty != null) {
            target = defaultProgramOnEmpty;
        } else {
            target = (fromProgram == null || fromProgram.isBlank())
                    ? ProgramRegistry.CO_SGN_00C
                    : fromProgram.trim();
        }

        CardDemoCommarea.GeneralInfo current = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo gi = new CardDemoCommarea.GeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                current.toTranid(),
                target,
                current.userId(),
                current.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commarea.withGeneralInfo(gi);

        log.info("CoUsr03C: XCTL to {} (return-to-prev)", target);

        CardDemoCommarea finalCommarea = outbound;
        if (programRegistry.isRegistered(target)) {
            finalCommarea = programRegistry.invoke(target, outbound);
        }
        return Result.xctl(target, finalCommarea);
    }

    /**
     * Builds the populated output record (paragraph {@code SEND-USRDEL-SCREEN}
     * + {@code POPULATE-HEADER-INFO}).
     */
    private CoUsr03Output buildScreen(CoUsr03Input input, String message, CardDemoCommarea commarea) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(commarea, "commarea");
        return new CoUsr03Output(
                TRANSACTION_ID,
                TITLE_01,
                todayDate(),
                PROGRAM_ID,
                TITLE_02,
                nowTime(),
                input.userId(),
                input.firstName(),
                input.lastName(),
                input.userType(),
                message == null ? "" : message);
    }

    // -- helpers ----------------------------------------------------------

    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                gi.fromTranid(),
                gi.fromProgram(),
                gi.toTranid(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                ctx);
        return commarea.withGeneralInfo(updated);
    }

    /**
     * Returns the substring of {@code s} up to (but not including) the first
     * {@code ' '} character. Mirrors COBOL {@code STRING ... DELIMITED BY
     * SPACE} for a single source operand.
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
