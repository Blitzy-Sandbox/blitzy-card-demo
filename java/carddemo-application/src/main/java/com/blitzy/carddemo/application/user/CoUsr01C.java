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
import com.blitzy.carddemo.domain.commarea.PgmContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Java translation of the {@code COUSR01C} CICS online program at
 * {@code app/cbl/COUSR01C.cbl} ("Add a new Regular/Admin user to USRSEC file").
 *
 * <h2>Program purpose</h2>
 * <p>Displays the user-add form. Operator enters first/last name, user ID,
 * password, and user type. Each field is validated for emptiness in a fixed
 * order (FNAME → LNAME → USERID → PASSWD → USRTYPE). If all pass, the new
 * user record is written to USRSEC. PF3 returns to admin menu; PF4 clears
 * the form.
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:  COUSR01C
 *   WS-PGMNAME:  'COUSR01C'
 *   WS-TRANID:   'CU01'
 * </pre>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: EIBCALEN check, first-time vs re-enter dispatch,
 *       AID-key handling (ENTER/PF3/PF4/OTHER).</li>
 *   <li>{@code PROCESS-ENTER-KEY}: fixed-order emptiness validation of 5
 *       fields, then WRITE-USER-SEC-FILE.</li>
 *   <li>{@code WRITE-USER-SEC-FILE}: insert with success/duplicate/other
 *       outcome handling.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN}: XCTL to CDEMO-TO-PROGRAM.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN}: re-display with all fields blanked.</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS}: replace input fields with SPACES.</li>
 * </ul>
 *
 * <h2>Messages preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>"First Name can NOT be empty..." (triple-dot ellipsis)</li>
 *   <li>"Last Name can NOT be empty..."</li>
 *   <li>"User ID can NOT be empty..."</li>
 *   <li>"Password can NOT be empty..."</li>
 *   <li>"User Type can NOT be empty..."</li>
 *   <li>"User &lt;id&gt; has been added ..." (with {@code DELIMITED BY SPACE}
 *       semantics — first whitespace-delimited token of SEC-USR-ID only)</li>
 *   <li>"User ID already exist..." (note: "exist" not "exists" — preserved verbatim)</li>
 *   <li>"Unable to Add User..."</li>
 * </ul>
 *
 * <h2>Plaintext password (AAP &sect;0.1.3)</h2>
 * <p>The SEC-USER-DATA record stores passwords as plaintext PIC X(08).
 * The Java translation preserves this behaviour exactly — passwords are
 * written verbatim into USRSEC. Logging surfaces are protected by the
 * record's masked {@code toString()} and by the global PAN/credential
 * Logback filter.
 */
@CobolProgram(
        value = "COUSR01C",
        sourcePath = "app/cbl/COUSR01C.cbl",
        notes = "User add; fixed-order emptiness validation; "
                + "plaintext password preserved per AAP §0.1.3"
)
public final class CoUsr01C {

    private static final Logger log = LoggerFactory.getLogger(CoUsr01C.class);

    public static final String PROGRAM_ID = "COUSR01C";
    public static final String TRANSACTION_ID = "CU01";

    public static final String TITLE_01 = "AWS Mainframe Modernization";
    public static final String TITLE_02 = "CardDemo";
    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";

    public static final String MSG_FNAME_EMPTY    = "First Name can NOT be empty...";
    public static final String MSG_LNAME_EMPTY    = "Last Name can NOT be empty...";
    public static final String MSG_USERID_EMPTY   = "User ID can NOT be empty...";
    public static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";
    public static final String MSG_USRTYPE_EMPTY  = "User Type can NOT be empty...";
    public static final String MSG_DUPLICATE      = "User ID already exist...";
    public static final String MSG_ADD_ERROR      = "Unable to Add User...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final UserSecurityRepository userSecurity;
    private final ProgramRegistry programRegistry;

    public CoUsr01C(UserSecurityRepository userSecurity, ProgramRegistry programRegistry) {
        this.userSecurity = Objects.requireNonNull(userSecurity, "userSecurity");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    /**
     * Result wrapper for the COBOL EXEC CICS RETURN vs XCTL dual flow.
     */
    public record Result(CoUsr01Output output, CardDemoCommarea commarea, String xctlTo) {

        public static Result sendMap(CoUsr01Output output, CardDemoCommarea commarea) {
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
    public Result run(CoUsr01Input input, CardDemoCommarea commarea) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        if (!(commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            return Result.sendMap(buildScreen(CoUsr01Input.blank(), "", false, "FNAMEI"), reentered);
        }

        if (input == null) {
            return Result.sendMap(buildScreen(CoUsr01Input.blank(), MSG_INVALID_KEY, false, "FNAMEI"), commarea);
        }

        return switch (input.aidKey()) {
            case ENTER -> processEnterKey(input, commarea);
            case PF03_BACK -> returnToPrevScreen(commarea);
            case PF04_CLEAR -> clearCurrentScreen(commarea);
            case OTHER -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY, false, "FNAMEI"), commarea);
        };
    }

    /**
     * Paragraph {@code PROCESS-ENTER-KEY}: fixed-order emptiness checks then
     * delegate to {@link #writeUserSecFile(CoUsr01Input, CardDemoCommarea)}.
     */
    private Result processEnterKey(CoUsr01Input input, CardDemoCommarea commarea) {
        if (isBlank(input.firstName())) {
            return Result.sendMap(buildScreen(input, MSG_FNAME_EMPTY, false, "FNAMEI"), commarea);
        }
        if (isBlank(input.lastName())) {
            return Result.sendMap(buildScreen(input, MSG_LNAME_EMPTY, false, "LNAMEI"), commarea);
        }
        if (isBlank(input.userId())) {
            return Result.sendMap(buildScreen(input, MSG_USERID_EMPTY, false, "USERIDI"), commarea);
        }
        if (isBlank(input.password())) {
            return Result.sendMap(buildScreen(input, MSG_PASSWORD_EMPTY, false, "PASSWDI"), commarea);
        }
        if (isBlank(input.userType())) {
            return Result.sendMap(buildScreen(input, MSG_USRTYPE_EMPTY, false, "USRTYPEI"), commarea);
        }
        return writeUserSecFile(input, commarea);
    }

    /**
     * Paragraph {@code WRITE-USER-SEC-FILE}. EXEC CICS WRITE DATASET('USRSEC')
     * with NORMAL / DUPKEY / DUPREC / OTHER outcome handling.
     */
    private Result writeUserSecFile(CoUsr01Input input, CardDemoCommarea commarea) {
        // padFiller is a fixed 23-byte block of SPACEs to mirror SEC-USR-FILLER PIC X(23).
        // Defensive copy preserves the byte[]-component contract of records.
        byte[] filler = new byte[23];
        java.util.Arrays.fill(filler, (byte) ' ');
        // CoUsr01Input.userType is a String clamped to <=1 char (matching the BMS
        // PIC X(1) leaf USRTYPEI); SecUserData.secUsrType is a single char (the
        // COBOL PIC X(01) representation). Convert empty -> space to mirror COBOL
        // space-fill semantics for an unset 1-byte field.
        String userTypeStr = input.userType();
        char userTypeChar = userTypeStr.isEmpty() ? ' ' : userTypeStr.charAt(0);
        SecUserData newUser = new SecUserData(
                input.userId(),
                input.firstName(),
                input.lastName(),
                input.password(),
                userTypeChar,
                filler);

        try {
            userSecurity.insert(newUser);
            // STRING 'User ' DELIMITED BY SIZE SEC-USR-ID DELIMITED BY SPACE
            //        ' has been added ...' DELIMITED BY SIZE
            String firstWord = firstSpaceDelimitedToken(newUser.secUsrId());
            String successMessage = "User " + firstWord + " has been added ...";
            // PERFORM INITIALIZE-ALL-FIELDS — blank input on success
            return Result.sendMap(buildScreen(CoUsr01Input.blank(), successMessage, true, "FNAMEI"), commarea);
        } catch (IllegalStateException dupKey) {
            // DFHRESP(DUPKEY) / DFHRESP(DUPREC)
            log.info("CoUsr01C: duplicate user id on insert: {}", input.userId());
            return Result.sendMap(buildScreen(input, MSG_DUPLICATE, false, "USERIDI"), commarea);
        } catch (RuntimeException re) {
            log.warn("CoUsr01C: USRSEC write failed for userId={}", input.userId(), re);
            return Result.sendMap(buildScreen(input, MSG_ADD_ERROR, false, "FNAMEI"), commarea);
        }
    }

    /**
     * Paragraph {@code RETURN-TO-PREV-SCREEN}.
     * COBOL: PF3 hard-codes target = 'COADM01C'. We honour that.
     */
    private Result returnToPrevScreen(CardDemoCommarea commarea) {
        CardDemoCommarea outbound = withTarget(commarea, ProgramRegistry.CO_ADM_01C);
        log.info("CoUsr01C: XCTL to COADM01C (return-to-prev)");
        CardDemoCommarea finalCommarea = outbound;
        if (programRegistry.isRegistered(ProgramRegistry.CO_ADM_01C)) {
            finalCommarea = programRegistry.invoke(ProgramRegistry.CO_ADM_01C, outbound);
        }
        return Result.xctl(ProgramRegistry.CO_ADM_01C, finalCommarea);
    }

    /**
     * Paragraph {@code CLEAR-CURRENT-SCREEN}.
     */
    private Result clearCurrentScreen(CardDemoCommarea commarea) {
        return Result.sendMap(buildScreen(CoUsr01Input.blank(), "", false, "FNAMEI"), commarea);
    }

    /**
     * Builds the populated output record (paragraph {@code SEND-USRADD-SCREEN}
     * + {@code POPULATE-HEADER-INFO}).
     */
    private CoUsr01Output buildScreen(CoUsr01Input input, String message, boolean success, String focusField) {
        return new CoUsr01Output(
                TRANSACTION_ID,
                TITLE_01,
                todayDate(),
                PROGRAM_ID,
                TITLE_02,
                nowTime(),
                input.firstName(),
                input.lastName(),
                input.userId(),
                input.password(),
                input.userType(),
                message == null ? "" : message,
                success,
                focusField == null ? "" : focusField);
    }

    // -- helpers ----------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static CardDemoCommarea withTarget(CardDemoCommarea commarea, String toProgram) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                gi.toTranId(),
                toProgram,
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return commarea.withCdemoGeneralInfo(updated);
    }

    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                gi.fromTranId(),
                gi.fromProgram(),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                ctx);
        return commarea.withCdemoGeneralInfo(updated);
    }

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
