/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.domain.menu;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.util.List;

/**
 * Java translation of the COBOL {@code CARDDEMO-ADMIN-MENU-OPTIONS} 01-level
 * group defined in {@code app/cpy/COADM02Y.cpy}. Provides the static, immutable
 * 4-entry administrative menu used by {@code CoAdm01C} (Admin Menu online
 * program) to dispatch menu-option selections to the four user-management
 * programs: {@code COUSR00C} (User List), {@code COUSR01C} (User Add),
 * {@code COUSR02C} (User Update), and {@code COUSR03C} (User Delete).
 *
 * <p>This is a constants holder class &mdash; the menu table is a compile-time
 * constant in COBOL (declared via {@code VALUE} clauses in
 * {@code CDEMO-ADMIN-OPTIONS-DATA}), not loaded from any external source. The
 * Java translation preserves this static-table semantics by exposing the
 * entries as a {@code public static final List<AdminMenuEntry>} populated via
 * {@link List#of(Object[])} (deeply immutable).
 *
 * <h2>COBOL Source Layout</h2>
 * The COBOL copybook declares {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}
 * with the underlying data area overlaid by
 * {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} (9 table slots). The Java translation
 * populates ONLY the 4 active entries; the remaining 5 slots (5&ndash;9) are
 * not represented because they are uninitialized filler in COBOL and have no
 * defined values.
 *
 * <h2>Iteration Order</h2>
 * The order of {@link #ENTRIES} matches the COBOL declaration order exactly
 * (option 1 first, option 4 last) per AAP &sect;0.7.1 "Preserve &hellip; sort
 * orders and batch sequencing." Any reordering would change observable
 * behavior and is FORBIDDEN.
 *
 * <h2>Label Width</h2>
 * Each {@code optionName} is exactly {@value #OPT_NAME_LENGTH} characters wide
 * (PIC X(35)) including trailing space padding. The COBOL source declares the
 * literal strings padded to 35 chars and the Java constants preserve this
 * padding verbatim. The application layer ({@code CoAdm01C}) renders these
 * strings into the BMS map fields; padding behavior at the BMS layer is
 * preserved by virtue of identical source bytes.
 *
 * <h2>No User-Type Indicator</h2>
 * Unlike {@link MainMenuTable}, {@code AdminMenuEntry} has NO {@code userType}
 * field. The COBOL admin-menu copybook {@code COADM02Y.cpy} defines exactly
 * three 15-level subfields per entry ({@code CDEMO-ADMIN-OPT-NUM},
 * {@code CDEMO-ADMIN-OPT-NAME}, {@code CDEMO-ADMIN-OPT-PGMNAME}) &mdash; and
 * no user-type &mdash; because the admin menu is inherently administrative
 * (only admin users can reach it via {@code CoAdm01C}). Adding a user-type
 * field here would be a behavior change beyond migration scope and is
 * FORBIDDEN per AAP &sect;0.7.1.
 *
 * <h2>Dispatch</h2>
 * Consumers dispatch a user-selected option to a target program by looking up
 * the entry with the matching {@code optionNumber}, then routing the
 * {@code programName} through {@code ProgramRegistry} (in
 * {@code carddemo-application}). This file does NOT contain any dispatch
 * logic; it is a pure data table.
 *
 * <p>Source version stamp from the COBOL footer (line 50):
 * {@code CardDemo_v1.0-26-g42273c1-79 Date: 2022-07-20 16:59:12 CDT}
 *
 * @see MainMenuTable
 * @see CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "COADM02Y",
        sourcePath = "app/cpy/COADM02Y.cpy",
        translationDate = "2025-10-15",
        notes = "CARDDEMO-ADMIN-MENU-OPTIONS 01-level group; 4 active entries "
                + "(COBOL CDEMO-ADMIN-OPT-COUNT VALUE 4) in a 9-slot table allocation "
                + "(OCCURS 9 TIMES). Maps to user-management programs COUSR00C-COUSR03C."
)
public final class AdminMenuTable {

    /**
     * Number of active menu entries. Matches COBOL
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}.
     */
    public static final int OPT_COUNT = 4;

    /**
     * Maximum capacity of the COBOL menu table allocation. Matches the
     * {@code OCCURS 9 TIMES} clause on {@code CDEMO-ADMIN-OPT}. Only the first
     * {@link #OPT_COUNT} slots are populated; slots {@code OPT_COUNT}&ndash;
     * {@code TABLE_SIZE - 1} are uninitialized filler in COBOL and are NOT
     * represented in Java.
     *
     * <p>This constant is documentation-only &mdash; it provides traceability
     * to the COBOL allocation but is not used by any Java consumer.
     */
    public static final int TABLE_SIZE = 9;

    /**
     * Width of each {@code optionNumber} field (PIC 9(02)). Two digits.
     */
    public static final int OPT_NUMBER_DIGITS = 2;

    /**
     * Width of each {@code optionName} field (PIC X(35)). 35 characters
     * including any trailing space padding.
     */
    public static final int OPT_NAME_LENGTH = 35;

    /**
     * Width of each {@code programName} field (PIC X(08)). 8 characters; all
     * program-IDs in this table happen to be exactly 8 chars (no padding
     * needed).
     */
    public static final int OPT_PROGRAM_LENGTH = 8;

    /**
     * Single administrative-menu entry. Translates one iteration of the COBOL
     * {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} table, comprising three 15-level
     * subfields: {@code CDEMO-ADMIN-OPT-NUM}, {@code CDEMO-ADMIN-OPT-NAME},
     * {@code CDEMO-ADMIN-OPT-PGMNAME}. Records are immutable per Java
     * semantics.
     *
     * @param optionNumber the COBOL {@code CDEMO-ADMIN-OPT-NUM} value
     *                     (range 1&ndash;{@link #OPT_COUNT}; PIC 9(02))
     * @param optionName   the COBOL {@code CDEMO-ADMIN-OPT-NAME} literal,
     *                     exactly {@link #OPT_NAME_LENGTH} characters
     *                     including trailing space padding (PIC X(35))
     * @param programName  the COBOL {@code CDEMO-ADMIN-OPT-PGMNAME} literal,
     *                     exactly {@link #OPT_PROGRAM_LENGTH} characters
     *                     (PIC X(08))
     */
    @CobolProgram(
            value = "COADM02Y",
            sourcePath = "app/cpy/COADM02Y.cpy",
            notes = "CDEMO-ADMIN-OPT OCCURS 9 TIMES (15-level subfields "
                    + "CDEMO-ADMIN-OPT-NUM, CDEMO-ADMIN-OPT-NAME, "
                    + "CDEMO-ADMIN-OPT-PGMNAME). No user-type subfield."
    )
    public record AdminMenuEntry(
            int optionNumber,
            String optionName,
            String programName
    ) {
        /**
         * Compact canonical constructor that validates field constraints per
         * the COBOL PIC declarations. Uses JEP 513 Flexible Constructor Bodies
         * (finalized in Java 25) to validate inputs before binding.
         *
         * @throws IllegalArgumentException if optionNumber is out of range,
         *         optionName length differs from {@link #OPT_NAME_LENGTH}, or
         *         programName length differs from {@link #OPT_PROGRAM_LENGTH}
         * @throws NullPointerException     if optionName or programName is null
         */
        public AdminMenuEntry {
            if (optionNumber < 1 || optionNumber > OPT_COUNT) {
                throw new IllegalArgumentException(
                        "optionNumber out of range [1," + OPT_COUNT + "]: "
                                + optionNumber);
            }
            if (optionName == null) {
                throw new NullPointerException("optionName");
            }
            if (optionName.length() != OPT_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "optionName length must be " + OPT_NAME_LENGTH
                                + ", was " + optionName.length()
                                + " (value=\"" + optionName + "\")");
            }
            if (programName == null) {
                throw new NullPointerException("programName");
            }
            if (programName.length() != OPT_PROGRAM_LENGTH) {
                throw new IllegalArgumentException(
                        "programName length must be " + OPT_PROGRAM_LENGTH
                                + ", was " + programName.length()
                                + " (value=\"" + programName + "\")");
            }
        }
    }

    /**
     * The 4 active administrative menu entries, in COBOL declaration order
     * (option 1 first, option 4 last). Immutable via {@link List#of(Object[])}.
     *
     * <p>Order MUST NOT change &mdash; consumers rely on positional ordering
     * for both iteration order on the rendered menu screen and for
     * option-number-to-program dispatch per AAP &sect;0.7.1 "Preserve &hellip;
     * sort orders and batch sequencing."
     *
     * <table>
     *   <caption>Admin Menu Mapping</caption>
     *   <tr><th>Option #</th><th>Label</th><th>Target Program</th></tr>
     *   <tr><td>1</td><td>User List (Security)</td><td>COUSR00C</td></tr>
     *   <tr><td>2</td><td>User Add (Security)</td><td>COUSR01C</td></tr>
     *   <tr><td>3</td><td>User Update (Security)</td><td>COUSR02C</td></tr>
     *   <tr><td>4</td><td>User Delete (Security)</td><td>COUSR03C</td></tr>
     * </table>
     */
    public static final List<AdminMenuEntry> ENTRIES = List.of(
            new AdminMenuEntry(1, "User List (Security)               ", "COUSR00C"),
            new AdminMenuEntry(2, "User Add (Security)                ", "COUSR01C"),
            new AdminMenuEntry(3, "User Update (Security)             ", "COUSR02C"),
            new AdminMenuEntry(4, "User Delete (Security)             ", "COUSR03C")
    );

    /*
     * Class-load-time validation: confirm the ENTRIES list matches the COBOL
     * CDEMO-ADMIN-OPT-COUNT (4 entries) and that option numbers are
     * consecutive starting at 1. Fail-fast at class load if any invariant has
     * drifted from the COBOL source.
     */
    static {
        if (ENTRIES.size() != OPT_COUNT) {
            throw new AssertionError(
                    "AdminMenuTable.ENTRIES must contain exactly " + OPT_COUNT
                            + " entries (matching COBOL CDEMO-ADMIN-OPT-COUNT VALUE), got "
                            + ENTRIES.size());
        }
        for (int i = 0; i < ENTRIES.size(); i++) {
            AdminMenuEntry entry = ENTRIES.get(i);
            int expectedOptionNumber = i + 1;
            if (entry.optionNumber() != expectedOptionNumber) {
                throw new AssertionError(
                        "AdminMenuTable.ENTRIES[" + i + "].optionNumber expected "
                                + expectedOptionNumber + ", got "
                                + entry.optionNumber());
            }
        }
        if (OPT_COUNT > TABLE_SIZE) {
            throw new AssertionError(
                    "OPT_COUNT (" + OPT_COUNT + ") must not exceed TABLE_SIZE ("
                            + TABLE_SIZE + ")");
        }
    }

    /**
     * Private constructor to prevent instantiation. {@code AdminMenuTable} is
     * a pure constants holder; consumers access entries via the static
     * {@link #ENTRIES} list.
     *
     * @throws AssertionError always; this class must not be instantiated
     */
    private AdminMenuTable() {
        throw new AssertionError(
                "AdminMenuTable is a static constants holder; do not instantiate.");
    }
}
