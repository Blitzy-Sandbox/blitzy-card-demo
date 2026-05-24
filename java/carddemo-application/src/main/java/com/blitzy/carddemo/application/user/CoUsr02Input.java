/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.user;

import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record for the {@code COUSR02 / COUSR2A} update-user map
 * (COBOL transaction {@code CU02}, program {@code COUSR02C}).
 *
 * <p>Literal field-for-field projection of the input view of the
 * {@code 01 COUSR2AI} group in {@code app/cpy-bms/COUSR02.CPY}: one
 * {@link String} field per {@code "I"}-suffixed BMS leaf that carries
 * application data plus an {@link AidKey} discriminator.
 *
 * <p><strong>Password handling</strong>: this record carries the
 * {@link #password() password} as plaintext. AAP &sect;0.1.3 mandates
 * preserving the COBOL behavior (SEC-USR-PWD PIC X(08) plaintext storage in
 * USRSEC). The {@link #toString()} override masks it as {@code ********}
 * to prevent leak in logs.
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
     * field is the empty {@link String} and the AID key is {@link AidKey#ENTER}.
     */
    public static CoUsr02Input blank() {
        return new CoUsr02Input("", "", "", "", "", AidKey.ENTER);
    }

    /**
     * Returns a copy of this record with a different {@code userId}.
     */
    public CoUsr02Input withUserId(String newUserId) {
        return new CoUsr02Input(newUserId, this.firstName, this.lastName,
                                this.password, this.userType, this.aidKey);
    }

    /**
     * Returns a copy of this record with a different {@link AidKey}.
     */
    public CoUsr02Input withAidKey(AidKey newAidKey) {
        return new CoUsr02Input(this.userId, this.firstName, this.lastName,
                                this.password, this.userType, newAidKey);
    }

    /**
     * Mask the password in the auto-generated record {@code toString()}
     * to prevent credential leak in logs.
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
