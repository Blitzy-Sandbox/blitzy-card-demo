/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.user;

import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.record.SecUserData;

/**
 * BMS input record for the {@code COUSR02 / COUSR2A} update-user map
 * (COBOL transaction {@code CU02}, program {@code COUSR02C}).
 *
 * <p>Literal field-for-field projection of the input view of the
 * {@code 01 COUSR2AI} group in {@code app/cpy-bms/COUSR02.CPY}: one
 * {@link String} field per {@code "I"}-suffixed BMS leaf that carries
 * application data plus an {@link AidKey} discriminator. The five
 * BMS-data-carrying fields are:
 * <ul>
 *   <li>{@code USRIDINI} &mdash; user id key (PIC X(8); {@code IC}
 *       initial-cursor attribute on first render)</li>
 *   <li>{@code FNAMEI}   &mdash; first name (PIC X(20); populated by
 *       program after a successful USRSEC fetch, then editable)</li>
 *   <li>{@code LNAMEI}   &mdash; last name (PIC X(20))</li>
 *   <li>{@code PASSWDI}  &mdash; password (PIC X(8); BMS {@code DRK}
 *       non-display attribute on the screen, plaintext on the wire)</li>
 *   <li>{@code USRTYPEI} &mdash; user type (PIC X(1); {@code 'A'} for
 *       Admin, {@code 'U'} for regular User)</li>
 * </ul>
 *
 * <p><strong>Password handling</strong>: this record carries the
 * {@link #password() password} as plaintext. AAP &sect;0.1.3 mandates
 * preserving the COBOL behavior (SEC-USR-PWD PIC X(08) plaintext storage in
 * USRSEC). The {@link #toString()} override masks it as {@code ********}
 * to prevent leak in logs. Any move to BCrypt / Argon2 / KDF (JEP 510) is
 * a separate effort tracked in {@code java/MIGRATION_NOTES.md} and is
 * deliberately OUT OF SCOPE for this migration.
 *
 * <h2>Two-stage fetch-then-update flow</h2>
 * The COBOL program runs a two-stage flow: on the first {@code ENTER}
 * the user types only the {@code USRIDIN} key (8 chars); after the
 * fetch the remaining four fields ({@code FNAME}, {@code LNAME},
 * {@code PASSWD}, {@code USRTYPE}) are populated from the matched
 * USRSEC row and re-rendered as editable. The static factory
 * {@link #fromSecUserData(SecUserData)} is the bridge for that stage
 * transition: given a successfully fetched {@link SecUserData}, it
 * produces a fully populated {@code CoUsr02Input} with each PIC X(n)
 * field trimmed of its COBOL space padding.
 *
 * <h2>AID keys</h2>
 * <p>The {@code EVALUATE EIBAID} dispatch block at lines 110-135 of
 * {@code app/cbl/COUSR02C.cbl} enumerates 6 outcomes:
 * <ul>
 *   <li>{@code DFHENTER}  &rarr; {@link AidKey#ENTER}: lookup user record</li>
 *   <li>{@code DFHPF3}    &rarr; {@link AidKey#PF03_SAVE_BACK}: save and return</li>
 *   <li>{@code DFHPF4}    &rarr; {@link AidKey#PF04_CLEAR}: clear screen</li>
 *   <li>{@code DFHPF5}    &rarr; {@link AidKey#PF05_SAVE}: save (stay on screen)</li>
 *   <li>{@code DFHPF12}   &rarr; {@link AidKey#PF12_CANCEL}: cancel and return</li>
 *   <li>{@code WHEN OTHER}&rarr; {@link AidKey#OTHER}: invalid key</li>
 * </ul>
 *
 * <p>Per AAP &sect;0.7.4: NO Spring, NO Lombok, NO Bean Validation, NO
 * setters &mdash; the record is immutable and {@code withXxx} methods
 * return copies.
 *
 * @see com.blitzy.carddemo.application.user.CoUsr02C   the owning use-case program
 * @see com.blitzy.carddemo.application.user.CoUsr02Output  the paired output DTO
 * @see com.blitzy.carddemo.domain.record.SecUserData   the USRSEC row record
 */
@CobolProgram(
        value = "COUSR02C",
        sourcePath = "app/cpy-bms/COUSR02.CPY",
        notes = "BMS input DTO for the update-user screen. Plaintext password "
                + "preserved per AAP §0.1.3; masked in toString()."
)
public record CoUsr02Input(
        String userId,       // USRIDINI  PIC X(8)
        String firstName,    // FNAMEI    PIC X(20)
        String lastName,     // LNAMEI    PIC X(20)
        String password,     // PASSWDI   PIC X(8)  -- plaintext per AAP §0.1.3
        String userType,     // USRTYPEI  PIC X(1)
        AidKey aidKey
) {

    private static final String PASSWORD_MASK = "********";

    /**
     * Compact constructor enforcing COBOL "SPACES by default" semantics
     * on every {@link String} component and clamping over-length values.
     */
    public CoUsr02Input {
        userId    = clamp(orEmpty(userId),    8);   // USRIDINI PIC X(8)
        firstName = clamp(orEmpty(firstName), 20);  // FNAMEI   PIC X(20)
        lastName  = clamp(orEmpty(lastName),  20);  // LNAMEI   PIC X(20)
        password  = clamp(orEmpty(password),  8);   // PASSWDI  PIC X(8)
        userType  = clamp(orEmpty(userType),  1);   // USRTYPEI PIC X(1)
        if (aidKey == null) {
            aidKey = AidKey.ENTER;
        }
    }

    /**
     * AID-key alias enum scoped to this Input record.
     *
     * <p>Translates the {@code EVALUATE EIBAID} dispatch block at lines
     * 110-135 of {@code app/cbl/COUSR02C.cbl} into a typed enum that the
     * controller's pattern-matching switch can consume exhaustively.
     */
    public enum AidKey {
        /** {@code DFHENTER}: fetch the user record. */
        ENTER,
        /** {@code DFHPF3}: save and return to the previous program. */
        PF03_SAVE_BACK,
        /** {@code DFHPF4}: clear the current screen. */
        PF04_CLEAR,
        /** {@code DFHPF5}: save changes (stay on screen). */
        PF05_SAVE,
        /** {@code DFHPF12}: cancel and return to admin menu. */
        PF12_CANCEL,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid key error). */
        OTHER
    }

    /**
     * Factory returning a fully blank instance &mdash; every {@link String}
     * field is the empty {@link String} <code>""</code> and the AID key is
     * {@link AidKey#ENTER}. This is the canonical seed instance for both
     * the first render (when the user has not yet typed anything) and for
     * the post-{@code DFHPF4}-clear-screen path.
     *
     * @return a fresh blank input; never {@code null}
     */
    public static CoUsr02Input blank() {
        return new CoUsr02Input("", "", "", "", "", AidKey.ENTER);
    }

    /**
     * Factory that lifts a fetched {@link SecUserData} row into a fully
     * populated {@code CoUsr02Input} for the second stage of the
     * fetch-then-update flow described in {@code app/cbl/COUSR02C.cbl}.
     *
     * <p>After {@code processEnterKey} successfully reads the USRSEC row
     * for the typed {@code USRIDIN}, the COBOL program does five
     * {@code MOVE}s from {@code SEC-USER-DATA} into the BMS map's
     * {@code I} components (USRIDINI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI).
     * This factory is the Java equivalent of that block: each PIC X(n)
     * field is trimmed of its right-space padding so the in-memory
     * String represents the human-readable value (encoding the COBOL
     * convention that {@code "USR01   "} and {@code "USR01"} denote the
     * same user id). The AID key is reset to {@link AidKey#ENTER} since
     * the populated record is about to be re-rendered to the user for
     * review, not dispatched.
     *
     * <p>{@code SEC-USR-TYPE} is a single COBOL {@code PIC X(01)}
     * character that this Java record carries as a 1-char String; the
     * conversion is done via {@link String#valueOf(char)}.
     *
     * <p>If {@code u} is {@code null} (defensive guard against an
     * upstream lookup error) this factory degrades gracefully to
     * {@link #blank()} rather than throwing, matching the COBOL pattern
     * of returning to a cleared screen on error.
     *
     * @param u  the fetched USRSEC row; may be {@code null} (handled
     *           defensively by returning {@link #blank()})
     * @return   a fully populated input record with AID key reset to
     *           {@link AidKey#ENTER}, or {@link #blank()} if {@code u}
     *           is {@code null}; never {@code null}
     */
    public static CoUsr02Input fromSecUserData(SecUserData u) {
        if (u == null) {
            return blank();
        }
        // Trim the right-space padding that COBOL PIC X(n) MOVE leaves on
        // each field. The compact constructor will re-clamp every value
        // to its declared BMS length, so even if a SecUserData String
        // happens to be longer than the matching BMS field (which the
        // domain-record contract prevents), no overflow can reach the
        // BMS DTO.
        return new CoUsr02Input(
                u.secUsrId().trim(),
                u.secUsrFname().trim(),
                u.secUsrLname().trim(),
                u.secUsrPwd().trim(),
                String.valueOf(u.secUsrType()),
                AidKey.ENTER);
    }

    /**
     * Returns a copy of this record with a different {@code userId} and
     * every other field (including {@link #aidKey()}) preserved. Used by
     * the COBOL paragraph {@code PROCESS-ENTER-KEY} to seed the fetch
     * key from {@code CDEMO-CU02-USR-SELECTED} when COUSR00C XCTLs into
     * COUSR02C with a pre-selected user id.
     *
     * <p>Per AAP &sect;0.1.2 this hand-written {@code with*} method is
     * how a COBOL {@code MOVE ... TO USRIDINI OF COUSR2AI} translates
     * into idiomatic Java &mdash; finalized Java 25 records have no
     * built-in {@code with} syntax.
     *
     * @param newUserId  the replacement user id (will be coerced
     *                   to empty / clamped to 8 chars by the compact
     *                   constructor)
     * @return a new {@code CoUsr02Input} differing only in the
     *         {@link #userId() userId} component; never {@code null}
     */
    public CoUsr02Input withUserId(String newUserId) {
        return new CoUsr02Input(newUserId, this.firstName, this.lastName,
                                this.password, this.userType, this.aidKey);
    }

    /**
     * Returns a copy of this record with a different {@link AidKey} and
     * every other field preserved. Used by the COBOL paragraph
     * {@code MAIN-PARA} to re-dispatch with a different AID key
     * (e.g. converting {@code DFHENTER} into {@code OTHER} for an
     * unrecognized key path).
     *
     * @param newAidKey  the replacement AID key; {@code null} is coerced
     *                   to {@link AidKey#ENTER} by the compact constructor
     * @return a new {@code CoUsr02Input} differing only in the
     *         {@link #aidKey() aidKey} component; never {@code null}
     */
    public CoUsr02Input withAidKey(AidKey newAidKey) {
        return new CoUsr02Input(this.userId, this.firstName, this.lastName,
                                this.password, this.userType, newAidKey);
    }

    /**
     * Returns a debug-friendly string representation with the plaintext
     * {@link #password() password} MASKED. This override is mandatory
     * per AAP &sect;0.1.3 security mandate &mdash; the default
     * record-generated {@code toString} would emit the cleartext
     * password, which would leak credentials into any sink that
     * consumes {@code Object.toString} (loggers, AssertJ failure
     * messages, IDE debugger displays, exception {@code getMessage()},
     * stack traces, etc.).
     *
     * <p>The masking is purely a defense-in-depth measure; it does not
     * alter the stored password value, which remains accessible via the
     * {@link #password()} accessor for the COUSR02C
     * credential-comparison path.
     *
     * @return a String of the form
     *         {@code CoUsr02Input[userId=..., firstName=..., lastName=...,
     *         password=********, userType=..., aidKey=...]}
     */
    @Override
    public String toString() {
        return "CoUsr02Input["
                + "userId=" + userId
                + ", firstName=" + firstName
                + ", lastName=" + lastName
                + ", password=" + PASSWORD_MASK
                + ", userType=" + userType
                + ", aidKey=" + aidKey
                + ']';
    }

    /** Coerce {@code null} to {@code ""} (COBOL SPACES default). */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Truncate to declared BMS {@code PIC X(n)} width. */
    private static String clamp(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
