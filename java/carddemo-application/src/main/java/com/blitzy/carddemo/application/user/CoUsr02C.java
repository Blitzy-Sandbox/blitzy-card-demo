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
import java.util.Objects;
import java.util.Optional;

/**
 * Java translation of the {@code COUSR02C} CICS online program at
 * {@code app/cbl/COUSR02C.cbl} ("Update a user in USRSEC file").
 *
 * <h2>Program purpose</h2>
 * <p>Displays the user-update screen. ENTER fetches the existing record;
 * PF5 commits an update; PF3 commits an update and returns to the previous
 * program; PF4 clears the screen; PF12 cancels (no save) and returns to
 * the admin menu.
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:  COUSR02C
 *   WS-PGMNAME:  'COUSR02C'
 *   WS-TRANID:   'CU02'
 *   WS-USRSEC-FILE: 'USRSEC  '
 * </pre>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: EIBCALEN check, first-time vs re-enter
 *       dispatch, AID-key EVALUATE.</li>
 *   <li>{@code PROCESS-ENTER-KEY}: USRIDINI empty-check, READ-USER-SEC-FILE,
 *       populate FNAMEI/LNAMEI/PASSWDI/USRTYPEI.</li>
 *   <li>{@code UPDATE-USER-INFO}: fixed-order EVALUATE TRUE validation
 *       chain (USRIDINI/FNAMEI/LNAMEI/PASSWDI/USRTYPEI), READ user, diff
 *       each field, REWRITE if modified; emit "Please modify to update ..."
 *       when nothing changed.</li>
 *   <li>{@code READ-USER-SEC-FILE}: WS-RESP-CD EVALUATE; "Press PF5 ...",
 *       "User ID NOT found...", "Unable to lookup User...".</li>
 *   <li>{@code UPDATE-USER-SEC-FILE}: REWRITE outcome EVALUATE; success
 *       message uses {@code STRING ... DELIMITED BY SPACE} → first-space
 *       semantics; otherwise "User ID NOT found..." or "Unable to Update User...".</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN}: XCTL with CDEMO-FROM-PROGRAM fallback
 *       to {@code COSGN00C} (when CDEMO-TO-PROGRAM blank) or admin menu.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} / {@code INITIALIZE-ALL-FIELDS}.</li>
 * </ul>
 *
 * <h2>Messages preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>"User ID can NOT be empty..."</li>
 *   <li>"First Name can NOT be empty..."</li>
 *   <li>"Last Name can NOT be empty..."</li>
 *   <li>"Password can NOT be empty..."</li>
 *   <li>"User Type can NOT be empty..."</li>
 *   <li>"Press PF5 key to save your updates ..."</li>
 *   <li>"User ID NOT found..."</li>
 *   <li>"Unable to lookup User..."</li>
 *   <li>"Please modify to update ..."</li>
 *   <li>"Unable to Update User..."</li>
 * </ul>
 *
 * <h2>Pre-selected user (CDEMO-CU02-USR-SELECTED)</h2>
 * <p>When invoked from COUSR00C (user list) with a row selected,
 * the COBOL program automatically performs PROCESS-ENTER-KEY at first entry.
 * Since {@link CardDemoCommarea} does not yet model this commarea extension,
 * the Java translation exposes it as a separate parameter on the run() overload.
 */
@CobolProgram(
        value = "COUSR02C",
        sourcePath = "app/cbl/COUSR02C.cbl",
        notes = "User update with diff-detection. Plaintext password "
                + "preserved per AAP §0.1.3. Preselected-user path via "
                + "separate parameter."
)
public final class CoUsr02C {

    private static final Logger log = LoggerFactory.getLogger(CoUsr02C.class);

    public static final String PROGRAM_ID = "COUSR02C";
    public static final String TRANSACTION_ID = "CU02";

    public static final String TITLE_01 = "AWS Mainframe Modernization";
    public static final String TITLE_02 = "CardDemo";
    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";

    /** "User ID can NOT be empty..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";
    /** "First Name can NOT be empty..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_FNAME_EMPTY = "First Name can NOT be empty...";
    /** "Last Name can NOT be empty..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_LNAME_EMPTY = "Last Name can NOT be empty...";
    /** "Password can NOT be empty..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";
    /** "User Type can NOT be empty..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_USRTYPE_EMPTY = "User Type can NOT be empty...";
    /** "Press PF5 key to save your updates ..." preserved verbatim. */
    public static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";
    /** "User ID NOT found..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_NOT_FOUND = "User ID NOT found...";
    /** "Unable to lookup User..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_LOOKUP_ERROR = "Unable to lookup User...";
    /** "Please modify to update ..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_NO_MODIFICATION = "Please modify to update ...";
    /** "Unable to Update User..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_UPDATE_ERROR = "Unable to Update User...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final UserSecurityRepository userSecurity;
    private final ProgramRegistry programRegistry;

    public CoUsr02C(UserSecurityRepository userSecurity, ProgramRegistry programRegistry) {
        this.userSecurity = Objects.requireNonNull(userSecurity, "userSecurity");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    public record Result(CoUsr02Output output, CardDemoCommarea commarea, String xctlTo) {

        public static Result sendMap(CoUsr02Output output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    /**
     * Convenience overload with no pre-selected user.
     */
    public Result run(CoUsr02Input input, CardDemoCommarea commarea) {
        return run(input, commarea, null);
    }

    /**
     * Entry point. Mirrors COBOL {@code MAIN-PARA}.
     *
     * @param input               BMS input record (may be null first-time)
     * @param commarea            inbound commarea
     * @param preselectedUserId   optional pre-populated user id (CDEMO-CU02-USR-SELECTED)
     */
    public Result run(CoUsr02Input input, CardDemoCommarea commarea, String preselectedUserId) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            if (preselectedUserId != null && !preselectedUserId.isBlank()) {
                CoUsr02Input populated = CoUsr02Input.blank().withUserId(preselectedUserId);
                return processEnterKey(populated, reentered);
            }
            return Result.sendMap(buildScreen(CoUsr02Input.blank(), "", false), reentered);
        }

        if (input == null) {
            return Result.sendMap(buildScreen(CoUsr02Input.blank(), MSG_INVALID_KEY, false), commarea);
        }

        return switch (input.aidKey()) {
            case ENTER -> processEnterKey(input, commarea);
            case PF03_SAVE_BACK -> saveAndReturnToPrev(input, commarea);
            case PF04_CLEAR -> clearCurrentScreen(commarea);
            case PF05_SAVE -> updateUserInfo(input, commarea, /*returnAfter=*/ false);
            case PF12_CANCEL -> returnToTarget(commarea, ProgramRegistry.CO_ADM_01C);
            case OTHER -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY, false), commarea);
        };
    }

    /**
     * Paragraph {@code PROCESS-ENTER-KEY}: validate user-id, fetch user.
     */
    private Result processEnterKey(CoUsr02Input input, CardDemoCommarea commarea) {
        if (input.userId() == null || input.userId().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_USERID_EMPTY, false), commarea);
        }

        try {
            Optional<SecUserData> userOpt = userSecurity.findById(input.userId().trim());
            if (userOpt.isEmpty()) {
                return Result.sendMap(buildScreen(input, MSG_NOT_FOUND, false), commarea);
            }
            SecUserData user = userOpt.get();
            // Populate FNAMEI/LNAMEI/PASSWDI/USRTYPEI from record.
            // SEC-USR-TYPE is a single COBOL X(01) char; CoUsr02Input.userType is the
            // 1-char BMS String. Wrap with String.valueOf for the conversion.
            CoUsr02Input populated = new CoUsr02Input(
                    user.secUsrId(),
                    user.secUsrFname(),
                    user.secUsrLname(),
                    user.secUsrPwd(),
                    String.valueOf(user.secUsrType()),
                    input.aidKey());
            return Result.sendMap(buildScreen(populated, MSG_PRESS_PF5, /*neutral=*/ true), commarea);
        } catch (RuntimeException re) {
            log.warn("CoUsr02C: USRSEC read failed for userId={}", input.userId(), re);
            return Result.sendMap(buildScreen(input, MSG_LOOKUP_ERROR, false), commarea);
        }
    }

    /**
     * PF3 path: save then XCTL to CDEMO-FROM-PROGRAM (or COADM01C fallback).
     */
    private Result saveAndReturnToPrev(CoUsr02Input input, CardDemoCommarea commarea) {
        Result updateResult = updateUserInfo(input, commarea, /*returnAfter=*/ true);
        if (updateResult.isXctl()) {
            return updateResult;
        }
        // If update produced an error sendMap, surface it (don't lose validation errors)
        if (updateResult.output() != null && updateResult.output().errorMessage() != null
                && !updateResult.output().errorMessage().isBlank()
                && !updateResult.output().successMessage()) {
            return updateResult;
        }
        // Successful update — XCTL away
        String fromProgram = commarea.generalInfo().fromProgram();
        String target = (fromProgram == null || fromProgram.isBlank())
                ? ProgramRegistry.CO_ADM_01C
                : fromProgram.trim();
        return returnToTarget(commarea, target);
    }

    /**
     * Paragraph {@code UPDATE-USER-INFO}: fixed-order validation chain,
     * diff-detection, REWRITE.
     *
     * @param returnAfter  if true (PF3 path), pass through on success;
     *                     if false (PF5 path), stay on screen with confirmation.
     */
    private Result updateUserInfo(CoUsr02Input input, CardDemoCommarea commarea, boolean returnAfter) {
        // Fixed-order validation (matches COBOL EVALUATE TRUE WHEN sequence)
        if (input.userId() == null || input.userId().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_USERID_EMPTY, false), commarea);
        }
        if (input.firstName() == null || input.firstName().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_FNAME_EMPTY, false), commarea);
        }
        if (input.lastName() == null || input.lastName().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_LNAME_EMPTY, false), commarea);
        }
        if (input.password() == null || input.password().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_PASSWORD_EMPTY, false), commarea);
        }
        if (input.userType() == null || input.userType().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_USRTYPE_EMPTY, false), commarea);
        }

        // Fetch existing record
        Optional<SecUserData> existingOpt;
        try {
            existingOpt = userSecurity.findById(input.userId().trim());
        } catch (RuntimeException re) {
            log.warn("CoUsr02C: USRSEC pre-update read failed for userId={}", input.userId(), re);
            return Result.sendMap(buildScreen(input, MSG_LOOKUP_ERROR, false), commarea);
        }
        if (existingOpt.isEmpty()) {
            return Result.sendMap(buildScreen(input, MSG_NOT_FOUND, false), commarea);
        }
        SecUserData existing = existingOpt.get();

        // Diff-detection
        boolean modified =
                !input.firstName().equals(existing.secUsrFname())
                        || !input.lastName().equals(existing.secUsrLname())
                        || !input.password().equals(existing.secUsrPwd())
                        || !input.userType().equals(existing.secUsrType());

        if (!modified) {
            return Result.sendMap(buildScreen(input, MSG_NO_MODIFICATION, false), commarea);
        }

        // REWRITE the user record with diff'd fields.
        // CoUsr02Input.userType is a 1-char BMS String (clamped to length 1 in the
        // compact constructor); SecUserData.secUsrType is the COBOL PIC X(01) char.
        // Convert empty -> space to mirror COBOL space-fill semantics.
        String inputUserTypeStr = input.userType();
        char inputUserTypeChar = inputUserTypeStr.isEmpty() ? ' ' : inputUserTypeStr.charAt(0);
        SecUserData updated = new SecUserData(
                existing.secUsrId(),
                input.firstName(),
                input.lastName(),
                input.password(),
                inputUserTypeChar,
                existing.secUsrFiller());

        try {
            userSecurity.update(updated);
            // Success message uses STRING ... DELIMITED BY SPACE → first whitespace
            // delimited token of SEC-USR-ID (8-char field, padded with spaces if shorter)
            String displayedUser = firstSpaceDelimitedToken(existing.secUsrId());
            String successMsg = "User " + displayedUser + " has been updated ...";
            return Result.sendMap(buildScreen(input, successMsg, true), commarea);
        } catch (IllegalStateException notFound) {
            // Simulated NOTFND on REWRITE
            log.warn("CoUsr02C: USRSEC REWRITE NOTFND for userId={}", input.userId(), notFound);
            return Result.sendMap(buildScreen(input, MSG_NOT_FOUND, false), commarea);
        } catch (RuntimeException re) {
            log.warn("CoUsr02C: USRSEC REWRITE failed for userId={}", input.userId(), re);
            return Result.sendMap(buildScreen(input, MSG_UPDATE_ERROR, false), commarea);
        }
    }

    /**
     * Paragraph {@code CLEAR-CURRENT-SCREEN}.
     */
    private Result clearCurrentScreen(CardDemoCommarea commarea) {
        return Result.sendMap(buildScreen(CoUsr02Input.blank(), "", false), commarea);
    }

    /**
     * XCTL to a target program (COADM01C / COSGN00C / CDEMO-FROM-PROGRAM).
     */
    private Result returnToTarget(CardDemoCommarea commarea, String target) {
        CardDemoCommarea outbound = withTarget(commarea, target);
        log.info("CoUsr02C: XCTL to {}", target);
        CardDemoCommarea finalCommarea = outbound;
        if (programRegistry.isRegistered(target)) {
            finalCommarea = programRegistry.invoke(target, outbound);
        }
        return Result.xctl(target, finalCommarea);
    }

    /**
     * Builds the populated output record (paragraph {@code SEND-USRUPD-SCREEN}
     * + {@code POPULATE-HEADER-INFO}).
     */
    private CoUsr02Output buildScreen(CoUsr02Input input, String message, boolean success) {
        return new CoUsr02Output(
                TRANSACTION_ID,
                TITLE_01,
                todayDate(),
                PROGRAM_ID,
                TITLE_02,
                nowTime(),
                input.userId(),
                input.firstName(),
                input.lastName(),
                input.password(),
                input.userType(),
                message == null ? "" : message,
                success,
                "");
    }

    // -- helpers ----------------------------------------------------------

    /**
     * Returns the substring up to the first space in {@code s}, mirroring
     * the COBOL {@code STRING ... DELIMITED BY SPACE} semantics that
     * truncates the source field at the first whitespace.
     */
    private static String firstSpaceDelimitedToken(String s) {
        if (s == null) {
            return "";
        }
        int idx = s.indexOf(' ');
        return idx < 0 ? s : s.substring(0, idx);
    }

    private static CardDemoCommarea withTarget(CardDemoCommarea commarea, String toProgram) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                gi.toTranid(),
                toProgram,
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return commarea.withGeneralInfo(updated);
    }

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

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
