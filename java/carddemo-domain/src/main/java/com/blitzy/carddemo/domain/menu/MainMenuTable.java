/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.menu;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.status.UserType;

import java.util.List;
import java.util.Optional;

/**
 * Main menu options translated from {@code app/cpy/COMEN02Y.cpy}.
 *
 * <pre>{@code
 * 01 CARDDEMO-MAIN-MENU-OPTIONS.
 *    05 CDEMO-MENU-OPT-COUNT  PIC 9(02) VALUE 10.
 *    05 CDEMO-MENU-OPTIONS-DATA.
 *       10 (10 occurrences of (PIC 9(02) num + PIC X(35) name + PIC X(08) pgmname + PIC X(01) usrtype))
 *    05 CDEMO-MENU-OPTIONS REDEFINES CDEMO-MENU-OPTIONS-DATA.
 *       10 CDEMO-MENU-OPT OCCURS 12 TIMES.
 *          15 CDEMO-MENU-OPT-NUM       PIC 9(02).
 *          15 CDEMO-MENU-OPT-NAME      PIC X(35).
 *          15 CDEMO-MENU-OPT-PGMNAME   PIC X(08).
 *          15 CDEMO-MENU-OPT-USRTYPE   PIC X(01).
 * }</pre>
 *
 * <p>Per COBOL {@code COMEN02Y.cpy} the table has capacity OCCURS 12 TIMES
 * but only 10 entries are pre-populated, all marked for user type
 * {@code 'U'}. The menu name for option 8 ("Transaction Add") has a
 * commented-out alternative ("Transaction Add (Admin Only)") in the COBOL
 * source — only the active, uncommented name is preserved here.
 */
@CobolProgram(
        value = "COMEN02Y",
        sourcePath = "app/cpy/COMEN02Y.cpy",
        notes = "Main menu options table (10 entries, capacity 12 per OCCURS); user-type filterable"
)
public final class MainMenuTable {

    public static final int OPT_COUNT = 10;
    public static final int OPT_CAPACITY = 12;

    /**
     * Single main menu entry mirroring CDEMO-MENU-OPT.
     */
    public record MainOption(int optNum, String optName, String pgmName, UserType requiredType) {
        public MainOption {
            if (optNum < 0 || optNum > 99) {
                throw new IllegalArgumentException("optNum must be 0..99");
            }
            optName = optName == null ? "" : optName;
            pgmName = pgmName == null ? "" : pgmName;
            // requiredType may be null only for synthetic / unfilled slots
        }
    }

    private static final List<MainOption> OPTIONS = List.of(
            new MainOption(1,  "Account View                       ", "COACTVWC", UserType.USER),
            new MainOption(2,  "Account Update                     ", "COACTUPC", UserType.USER),
            new MainOption(3,  "Credit Card List                   ", "COCRDLIC", UserType.USER),
            new MainOption(4,  "Credit Card View                   ", "COCRDSLC", UserType.USER),
            new MainOption(5,  "Credit Card Update                 ", "COCRDUPC", UserType.USER),
            new MainOption(6,  "Transaction List                   ", "COTRN00C", UserType.USER),
            new MainOption(7,  "Transaction View                   ", "COTRN01C", UserType.USER),
            new MainOption(8,  "Transaction Add                    ", "COTRN02C", UserType.USER),
            new MainOption(9,  "Transaction Reports                ", "CORPT00C", UserType.USER),
            new MainOption(10, "Bill Payment                       ", "COBIL00C", UserType.USER)
    );

    private MainMenuTable() {
        // Utility class
    }

    /** Returns the read-only list of main menu options. */
    public static List<MainOption> options() {
        return OPTIONS;
    }

    /**
     * Returns options visible to the supplied user type. Per the COBOL
     * source, the {@code USRTYPE = 'U'} entries are visible to both admin
     * and regular users; pattern-matching switch enforces exhaustiveness.
     */
    public static List<MainOption> optionsFor(UserType userType) {
        if (userType == null) {
            return List.of();
        }
        return switch (userType) {
            case UserType.Admin a -> OPTIONS;
            case UserType.User u -> OPTIONS;
        };
    }

    /**
     * Returns the option with the supplied number, or empty if no such option.
     */
    public static Optional<MainOption> findByNum(int optNum) {
        return OPTIONS.stream().filter(o -> o.optNum == optNum).findFirst();
    }

    /**
     * Returns the option pointing at the supplied program, or empty if no
     * such option.
     */
    public static Optional<MainOption> findByProgram(String pgmName) {
        if (pgmName == null) {
            return Optional.empty();
        }
        String trimmed = pgmName.trim();
        return OPTIONS.stream()
                .filter(o -> o.pgmName.trim().equals(trimmed))
                .findFirst();
    }
}
