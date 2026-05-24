/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.menu;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.util.List;
import java.util.Optional;

/**
 * Admin menu options translated from {@code app/cpy/COADM02Y.cpy}.
 *
 * <pre>{@code
 * 01 CARDDEMO-ADMIN-MENU-OPTIONS.
 *    05 CDEMO-ADMIN-OPT-COUNT  PIC 9(02) VALUE 4.
 *    05 CDEMO-ADMIN-OPTIONS-DATA.
 *       10 (4 occurrences of (PIC 9(02) num + PIC X(35) name + PIC X(08) pgmname))
 *    05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.
 *       10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.
 *          15 CDEMO-ADMIN-OPT-NUM       PIC 9(02).
 *          15 CDEMO-ADMIN-OPT-NAME      PIC X(35).
 *          15 CDEMO-ADMIN-OPT-PGMNAME   PIC X(08).
 * }</pre>
 *
 * <p>Per the COBOL definition the table has capacity OCCURS 9 TIMES but only
 * 4 entries are pre-populated. The Java translation preserves the option
 * count and the four canonical entries.
 */
@CobolProgram(
        value = "COADM02Y",
        sourcePath = "app/cpy/COADM02Y.cpy",
        notes = "Admin menu options table (4 entries, capacity 9 per OCCURS)"
)
public final class AdminMenuTable {

    public static final int OPT_COUNT = 4;
    public static final int OPT_CAPACITY = 9;

    /**
     * Single admin menu entry mirroring CDEMO-ADMIN-OPT.
     */
    public record AdminOption(int optNum, String optName, String pgmName) {
        public AdminOption {
            if (optNum < 0 || optNum > 99) {
                throw new IllegalArgumentException("optNum must be 0..99");
            }
            optName = optName == null ? "" : optName;
            pgmName = pgmName == null ? "" : pgmName;
        }
    }

    private static final List<AdminOption> OPTIONS = List.of(
            new AdminOption(1, "User List (Security)               ", "COUSR00C"),
            new AdminOption(2, "User Add (Security)                ", "COUSR01C"),
            new AdminOption(3, "User Update (Security)             ", "COUSR02C"),
            new AdminOption(4, "User Delete (Security)             ", "COUSR03C")
    );

    private AdminMenuTable() {
        // Utility class
    }

    /** Returns the read-only list of admin menu options (4 entries). */
    public static List<AdminOption> options() {
        return OPTIONS;
    }

    /**
     * Returns the option with the supplied number, or empty if no such option.
     */
    public static Optional<AdminOption> findByNum(int optNum) {
        return OPTIONS.stream().filter(o -> o.optNum == optNum).findFirst();
    }

    /**
     * Returns the option pointing at the supplied program, or empty if no
     * such option.
     */
    public static Optional<AdminOption> findByProgram(String pgmName) {
        if (pgmName == null) {
            return Optional.empty();
        }
        String trimmed = pgmName.trim();
        return OPTIONS.stream()
                .filter(o -> o.pgmName.trim().equals(trimmed))
                .findFirst();
    }
}
