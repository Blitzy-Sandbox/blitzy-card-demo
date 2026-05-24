/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.signon;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.port.UserSecurityRepository;
import com.blitzy.carddemo.domain.record.SecUserData;
import com.blitzy.carddemo.domain.status.PgmContext;
import com.blitzy.carddemo.domain.status.UserType;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Java translation of the {@code COSGN00C} CICS online program at
 * {@code app/cbl/COSGN00C.cbl} ("Signon Screen for the CardDemo Application").
 *
 * <h2>Program purpose</h2>
 * <p>{@code COSGN00C} is the entry-point program for CICS transaction
 * {@code CC00}. It collects the user id and password from BMS map
 * {@code COSGN0A} (mapset {@code COSGN00}), authenticates the credentials
 * against {@code USRSEC}, and then {@code XCTL}s to {@code COMEN01C}
 * (regular menu) or {@code COADM01C} (admin menu) based on the
 * {@code CDEMO-USER-TYPE} sealed taxonomy (AAP &sect;0.6.10).
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:       COSGN00C
 *   WS-PGMNAME:       'COSGN00C'
 *   WS-TRANID:        'CC00'
 *   WS-USRSEC-FILE:   'USRSEC  '
 *   WS-USER-ID len:   8
 *   WS-USER-PWD len:  8
 * </pre>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: AID-key dispatch (ENTER=process / PF3=thank-you /
 *       OTHER=invalid-key) — replaced by exhaustive {@code switch} on
 *       {@link AidKey} permits.</li>
 *   <li>{@code PROCESS-ENTER-KEY}: validates non-empty user id and password
 *       (after FUNCTION UPPER-CASE), then calls {@link #readUserSecFile()}.</li>
 *   <li>{@code SEND-SIGNON-SCREEN}: emits a populated {@link CoSgn00Output}
 *       with the title/date/time/applid populated for the BMS adapter.</li>
 *   <li>{@code SEND-PLAIN-TEXT}: emits an output with only an error message;
 *       called by the PF3 "thank you" branch.</li>
 *   <li>{@code POPULATE-HEADER-INFO}: gathers titles, current date / time,
 *       APPLID, SYSID.</li>
 *   <li>{@code READ-USER-SEC-FILE}: looks up {@code SEC-USER-DATA} by
 *       {@code WS-USER-ID}; on success dispatches via {@link UserType} sealed
 *       switch to either {@code COADM01C} (admin) or {@code COMEN01C}.</li>
 * </ul>
 *
 * <h2>{@code EVALUATE WS-RESP-CD} translation</h2>
 * <p>The original COBOL handles three branches: {@code 0} (found),
 * {@code 13} (NOTFND), and {@code OTHER} (any I/O error). The Java
 * translation expresses NOTFND through the {@link Optional#empty()} return
 * value from {@link UserSecurityRepository#findById(String)} and any other
 * runtime failure through an exception caught and rendered to the COBOL
 * error message verbatim ("Unable to verify the User ...").
 *
 * <h2>Anomalies preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>Plaintext password comparison ({@code IF SEC-USR-PWD = WS-USER-PWD})
 *       preserved — the COBOL source mandates plaintext storage per
 *       AAP &sect;0.1.3; introducing BCrypt is a separate effort documented
 *       in {@code MIGRATION_NOTES.md}.</li>
 *   <li>Error message strings preserved verbatim including trailing periods
 *       and triple-dot ellipses.</li>
 *   <li>{@code FUNCTION UPPER-CASE} normalization preserved.</li>
 * </ul>
 *
 * <h2>XCTL replacement</h2>
 * <p>COBOL {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA)}
 * translates to a {@link ProgramRegistry#invoke(String, CardDemoCommarea)}
 * call. The dispatch happens immediately in this method and returns the
 * commarea that the called program wrote back. The COBOL {@code XCTL} is
 * non-returning; the Java equivalent returns the updated commarea so the
 * composition root can continue the request cycle.
 */
@CobolProgram(
        value = "COSGN00C",
        sourcePath = "app/cbl/COSGN00C.cbl",
        notes = "Signon screen for CICS transaction CC00; reads USRSEC; dispatches to "
                + "COADM01C/COMEN01C via UserType sealed switch per AAP §0.6.10"
)
public final class CoSgn00C {

    private static final Logger log = LoggerFactory.getLogger(CoSgn00C.class);

    /** WS-PGMNAME literal (COSGN00C). */
    public static final String PROGRAM_ID = "COSGN00C";
    /** WS-TRANID literal (CC00). */
    public static final String TRANSACTION_ID = "CC00";
    /** Length of user id and password per BMS map / PIC X(8). */
    public static final int CREDENTIAL_LENGTH = 8;

    /** CCDA-TITLE01 from CSMSG01Y — fixed BMS header. */
    public static final String TITLE_01 = "AWS Mainframe Modernization";
    /** CCDA-TITLE02 from CSMSG01Y. */
    public static final String TITLE_02 = "CardDemo";
    /** CCDA-MSG-THANK-YOU from CSMSG01Y. */
    public static final String MSG_THANK_YOU = "Thank you for using CardDemo Application !!!";
    /** CCDA-MSG-INVALID-KEY from CSMSG01Y. */
    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final UserSecurityRepository userSecurityRepository;
    private final ProgramRegistry programRegistry;
    private final String applId;
    private final String sysId;

    /**
     * Constructor injection.
     *
     * @param userSecurityRepository {@code USRSEC} port
     * @param programRegistry        registry used to dispatch to COMEN01C /
     *                               COADM01C after successful authentication
     * @param applId                 CICS APPLID (constant per region)
     * @param sysId                  CICS SYSID
     */
    public CoSgn00C(UserSecurityRepository userSecurityRepository,
                    ProgramRegistry programRegistry,
                    String applId,
                    String sysId) {
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository, "userSecurityRepository");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
        this.applId = applId == null ? "" : applId;
        this.sysId = sysId == null ? "" : sysId;
    }

    /**
     * Result wrapper for {@link #run(CoSgn00Input, AidKey, CardDemoCommarea)}.
     *
     * <p>The COBOL signon program has two terminal states:
     * <ul>
     *   <li>{@code SEND MAP} the signon screen (on error or PF3) — carries
     *       a populated {@link CoSgn00Output} and the unchanged commarea.</li>
     *   <li>{@code XCTL} to COMEN01C or COADM01C (on auth success) — carries
     *       the updated commarea (with {@code CDEMO-USER-ID},
     *       {@code CDEMO-USER-TYPE}, {@code CDEMO-FROM-TRANID},
     *       {@code CDEMO-FROM-PROGRAM} set) and no output (a successful
     *       XCTL means the menu program will produce the next visible
     *       screen).</li>
     * </ul>
     *
     * @param output    {@link CoSgn00Output} populated for SEND MAP, or
     *                  {@code null} on successful XCTL
     * @param commarea  the (possibly updated) commarea for the next program
     * @param xctlTo    the {@link ProgramRegistry} key of the next program
     *                  on XCTL, or {@code null} on SEND MAP
     */
    public record Result(CoSgn00Output output, CardDemoCommarea commarea, String xctlTo) {

        /** Result that triggers SEND MAP with the populated output. */
        public static Result sendMap(CoSgn00Output output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }

        /** Result that triggers XCTL to the supplied program with the updated commarea. */
        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }

        /** Returns true when the result represents a SEND MAP outcome. */
        public boolean isSendMap() {
            return output != null;
        }

        /** Returns true when the result represents an XCTL outcome. */
        public boolean isXctl() {
            return xctlTo != null;
        }
    }

    /**
     * Single entry point — combines the COBOL {@code MAIN-PARA},
     * {@code PROCESS-ENTER-KEY}, and {@code SEND-*} flows into one
     * synchronous Java method returning the populated {@link Result}.
     *
     * <p>The COBOL flow is:
     * <ol>
     *   <li>If {@code EIBCALEN = 0} (first invocation): send empty signon
     *       screen with cursor on user id.</li>
     *   <li>Else dispatch on {@code EIBAID}: ENTER → process, PF3 → thank
     *       you & terminate, OTHER → invalid key message.</li>
     *   <li>On ENTER: validate user id and password non-empty; UPPER-CASE
     *       both; look up in USRSEC; XCTL to admin or regular menu.</li>
     * </ol>
     *
     * @param input    BMS input record (or {@code null} for the first invocation)
     * @param aidKey   AID-key dispatch value
     * @param commarea inbound commarea (or {@link CardDemoCommarea#empty()})
     * @return a {@link Result} carrying either the populated output or the
     *         XCTL target program id + updated commarea
     */
    public Result run(CoSgn00Input input, AidKey aidKey, CardDemoCommarea commarea) {
        Objects.requireNonNull(aidKey, "aidKey");
        CardDemoCommarea cm = commarea == null ? CardDemoCommarea.empty() : commarea;

        // IF EIBCALEN = 0 → initial signon screen
        // The Java equivalent is "no input row" — we treat null input as the
        // first invocation. Set initial state and SEND-SIGNON-SCREEN.
        if (input == null) {
            return Result.sendMap(sendSignonScreen("", "", ""), cm);
        }

        // EVALUATE EIBAID
        return switch (aidKey) {
            case AidKey.Enter _ -> processEnterKey(input, cm);
            case AidKey.PfKey03 _ -> Result.sendMap(sendPlainText(MSG_THANK_YOU), cm);
            case AidKey.Clear _, AidKey.Pa1 _, AidKey.Pa2 _,
                 AidKey.PfKey01 _, AidKey.PfKey02 _, AidKey.PfKey04 _, AidKey.PfKey05 _,
                 AidKey.PfKey06 _, AidKey.PfKey07 _, AidKey.PfKey08 _, AidKey.PfKey09 _,
                 AidKey.PfKey10 _, AidKey.PfKey11 _, AidKey.PfKey12 _
                    -> Result.sendMap(sendSignonScreen(input.userId(), input.password(), MSG_INVALID_KEY), cm);
        };
    }

    // ====================================================================
    // PROCESS-ENTER-KEY
    // ====================================================================

    /**
     * Paragraph {@code PROCESS-ENTER-KEY}.
     *
     * <p>The COBOL flow validates that USERIDI and PASSWDI are non-blank,
     * upper-cases both, then either re-displays the screen with an error
     * message or calls {@code READ-USER-SEC-FILE}.
     */
    private Result processEnterKey(CoSgn00Input input, CardDemoCommarea commarea) {
        String userId = orEmpty(input.userId());
        String password = orEmpty(input.password());

        if (userId.isBlank()) {
            return Result.sendMap(sendSignonScreen("", "", "Please enter User ID ..."), commarea);
        }
        if (password.isBlank()) {
            return Result.sendMap(sendSignonScreen(userId, "", "Please enter Password ..."), commarea);
        }

        String upUserId = userId.toUpperCase();
        String upPassword = password.toUpperCase();

        return readUserSecFile(upUserId, upPassword, commarea);
    }

    // ====================================================================
    // READ-USER-SEC-FILE
    // ====================================================================

    /**
     * Paragraph {@code READ-USER-SEC-FILE}.
     *
     * <p>Looks up the user in {@code USRSEC}. The COBOL
     * {@code EVALUATE WS-RESP-CD} branches are:
     * <ul>
     *   <li>{@code 0} — found: compare passwords; on match dispatch via
     *       {@link UserType} sealed switch to COADM01C or COMEN01C; on
     *       mismatch re-display with "Wrong Password" message.</li>
     *   <li>{@code 13} — NOTFND: re-display with "User not found" message.</li>
     *   <li>{@code OTHER} — any other error: re-display with "Unable to
     *       verify the User ..." message.</li>
     * </ul>
     */
    private Result readUserSecFile(String userId, String password, CardDemoCommarea commarea) {
        Optional<SecUserData> userRecord;
        try {
            userRecord = userSecurityRepository.findById(userId);
        } catch (RuntimeException re) {
            log.error("USRSEC lookup failed for {}", userId, re);
            return Result.sendMap(sendSignonScreen(userId, "", "Unable to verify the User ..."), commarea);
        }

        if (userRecord.isEmpty()) {
            return Result.sendMap(sendSignonScreen(userId, "", "User not found. Try again ..."), commarea);
        }

        SecUserData user = userRecord.get();
        // Plaintext password comparison preserved per AAP §0.1.3.
        if (!Objects.equals(orEmpty(user.secUsrPwd()).trim(), password.trim())) {
            return Result.sendMap(sendSignonScreen(userId, "", "Wrong Password. Try again ..."), commarea);
        }

        // SUCCESS: dispatch to admin or regular menu via UserType sealed switch
        char typeCode = user.secUsrType().isEmpty() ? ' ' : user.secUsrType().charAt(0);
        UserType userType;
        try {
            userType = UserType.fromCode(typeCode);
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown CDEMO-USER-TYPE '{}' for user {}; defaulting to regular user", typeCode, userId);
            userType = UserType.USER;
        }

        // Build updated commarea: MOVE WS-TRANID, WS-PGMNAME, WS-USER-ID,
        // SEC-USR-TYPE, ZEROS TO CDEMO-* fields
        CardDemoCommarea.GeneralInfo gi = new CardDemoCommarea.GeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                "",                 // CDEMO-TO-TRANID (not set here)
                "",                 // CDEMO-TO-PROGRAM (set by destination)
                userId,
                userType,
                PgmContext.ENTER);  // MOVE ZEROS TO CDEMO-PGM-CONTEXT
        CardDemoCommarea outboundCommarea = commarea.withGeneralInfo(gi);

        // EXEC CICS XCTL via ProgramRegistry
        String targetProgram = switch (userType) {
            case UserType.Admin _ -> ProgramRegistry.CO_ADM_01C;
            case UserType.User _ -> ProgramRegistry.CO_MEN_01C;
        };

        log.info("Signon success for user '{}' (type='{}'); dispatching to {}",
                userId, userType.code(), targetProgram);

        // EXEC CICS XCTL is non-returning; we report the dispatch via Result.
        // If the registry has a handler, allow the composition root to
        // synchronously walk into it for end-to-end testability.
        CardDemoCommarea finalCommarea = outboundCommarea;
        if (programRegistry.isRegistered(targetProgram)) {
            finalCommarea = programRegistry.invoke(targetProgram, outboundCommarea);
        }

        return Result.xctl(targetProgram, finalCommarea);
    }

    // ====================================================================
    // SEND-SIGNON-SCREEN  /  SEND-PLAIN-TEXT
    // ====================================================================

    /**
     * Paragraph {@code SEND-SIGNON-SCREEN}. Builds the populated
     * {@link CoSgn00Output} carrying the header + error message + form
     * values.
     */
    private CoSgn00Output sendSignonScreen(String userId, String password, String message) {
        return buildHeaderOutput(userId, password, message);
    }

    /**
     * Paragraph {@code SEND-PLAIN-TEXT}. The COBOL writes a single line of
     * text and {@code EXEC CICS RETURN}s. Java equivalent: emit an output
     * with only the error message populated.
     */
    private CoSgn00Output sendPlainText(String message) {
        return new CoSgn00Output(
                TRANSACTION_ID, TITLE_01, todayDate(), PROGRAM_ID, TITLE_02, nowTime(),
                applId, sysId, "", "", message, CoSgn00Output.FieldColor.RED);
    }

    /**
     * Paragraph {@code POPULATE-HEADER-INFO} (plus the surrounding
     * SEND-SIGNON-SCREEN content) — gathers titles, date, time, APPLID, SYSID,
     * user-id, password, and error message into a fresh
     * {@link CoSgn00Output}.
     */
    private CoSgn00Output buildHeaderOutput(String userId, String password, String message) {
        return new CoSgn00Output(
                TRANSACTION_ID,
                TITLE_01,
                todayDate(),
                PROGRAM_ID,
                TITLE_02,
                nowTime(),
                applId,
                sysId,
                userId == null ? "" : userId,
                password == null ? "" : password,
                message == null ? "" : message,
                message == null || message.isBlank()
                        ? CoSgn00Output.FieldColor.GREEN
                        : CoSgn00Output.FieldColor.RED
        );
    }

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
