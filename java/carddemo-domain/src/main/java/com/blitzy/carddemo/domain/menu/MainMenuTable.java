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
import com.blitzy.carddemo.domain.commarea.UserType;

import java.util.List;
import java.util.Objects;

/**
 * Java translation of the COBOL {@code CARDDEMO-MAIN-MENU-OPTIONS} 01-level
 * group defined in {@code app/cpy/COMEN02Y.cpy}. Provides the static, immutable
 * 10-entry main menu used by {@code CoMen01C} (Main Menu online program) to
 * dispatch menu-option selections to the ten online programs:
 * {@code COACTVWC} (Account View), {@code COACTUPC} (Account Update),
 * {@code COCRDLIC} (Credit Card List), {@code COCRDSLC} (Credit Card View),
 * {@code COCRDUPC} (Credit Card Update), {@code COTRN00C} (Transaction List),
 * {@code COTRN01C} (Transaction View), {@code COTRN02C} (Transaction Add),
 * {@code CORPT00C} (Transaction Reports), and {@code COBIL00C} (Bill Payment).
 *
 * <p>This is a constants holder class &mdash; the menu table is a compile-time
 * constant in COBOL (declared via {@code VALUE} clauses in
 * {@code CDEMO-MENU-OPTIONS-DATA}), not loaded from any external source. The
 * Java translation preserves this static-table semantics by exposing the
 * entries as a {@code public static final List<MainMenuEntry>} populated via
 * {@link List#of(Object[])} (deeply immutable).
 *
 * <h2>COBOL Source Layout</h2>
 * The COBOL copybook declares
 * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} with the underlying data
 * area overlaid by {@code CDEMO-MENU-OPT OCCURS 12 TIMES} (12 table slots).
 * The Java translation populates ONLY the 10 active entries; the remaining
 * 2 slots (11&ndash;12) are not represented because they are uninitialized
 * filler in COBOL and have no defined values.
 *
 * <h2>Iteration Order</h2>
 * The order of {@link #ENTRIES} matches the COBOL declaration order exactly
 * (option 1 first, option 10 last) per AAP &sect;0.7.1 "Preserve &hellip; sort
 * orders and batch sequencing." Any reordering would change observable
 * behavior and is FORBIDDEN.
 *
 * <h2>Label Width</h2>
 * Each {@code optionName} is exactly {@value #OPT_NAME_LENGTH} characters wide
 * (PIC X(35)) including trailing space padding. The COBOL source declares the
 * literal strings padded to 35 chars and the Java constants preserve this
 * padding verbatim. The application layer ({@code CoMen01C}) renders these
 * strings into the BMS map fields; padding behavior at the BMS layer is
 * preserved by virtue of identical source bytes.
 *
 * <h2>User-Type Indicator</h2>
 * Each entry carries a {@link UserType} indicator that constrains which user
 * type can access the option. In the current COBOL source, ALL 10 entries
 * have {@code 'U'} (standard user) &mdash; admin-only items are not currently
 * active in this menu. The {@code UserType} sealed type (from
 * {@link com.blitzy.carddemo.domain.commarea.UserType}) provides compile-time
 * exhaustiveness checking when consumers filter the menu by user type via
 * pattern-matching {@code switch} &mdash; see {@link UserType.Admin},
 * {@link UserType.User}.
 *
 * <h2>Historical Note (Entry #8)</h2>
 * The COBOL source contains a commented-out alternate label for entry #8:
 * {@code 'Transaction Add (Admin Only)       '}. This alternate is preserved
 * here as a historical note for traceability per AAP &sect;0.7.1 but is NOT
 * the active value. The active label is
 * {@code 'Transaction Add                    '}. Per AAP &sect;0.7.1
 * idiom-for-idiom translation rule, the Java translation MUST use the active
 * value only; the commented-out alternate must NOT be substituted.
 *
 * <h2>Pattern-Matching / Dispatch</h2>
 * Consumers dispatch a user-selected option to a target program by looking up
 * the entry with the matching {@code optionNumber}, then routing the
 * {@code programName} through {@code ProgramRegistry} (in
 * {@code carddemo-application}). User-type filtering is performed by the
 * caller using pattern-matching switch over the {@code userType} field. This
 * file does NOT contain any dispatch or filtering logic; it is a pure data
 * table.
 *
 * <p>Source version stamp from the COBOL footer (line 94):
 * {@code CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT}
 *
 * @see AdminMenuTable
 * @see UserType
 * @see CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "COMEN02Y",
        sourcePath = "app/cpy/COMEN02Y.cpy",
        translationDate = "2025-10-15",
        notes = "CARDDEMO-MAIN-MENU-OPTIONS 01-level group; 10 active entries "
                + "(COBOL CDEMO-MENU-OPT-COUNT VALUE 10) in a 12-slot table allocation "
                + "(OCCURS 12 TIMES). Maps to online programs COACTVWC, COACTUPC, "
                + "COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C, COTRN02C, "
                + "CORPT00C, COBIL00C. User-type indicator is UserType.User for all "
                + "10 current entries."
)
public final class MainMenuTable {

    /**
     * Number of active menu entries. Matches COBOL
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10}.
     */
    public static final int OPT_COUNT = 10;

    /**
     * Maximum capacity of the COBOL menu table allocation. Matches the
     * {@code OCCURS 12 TIMES} clause on {@code CDEMO-MENU-OPT}. Only the first
     * {@link #OPT_COUNT} slots are populated; slots {@code OPT_COUNT}&ndash;
     * {@code TABLE_SIZE - 1} are uninitialized filler in COBOL and are NOT
     * represented in Java.
     *
     * <p>This constant is documentation-only &mdash; it provides traceability
     * to the COBOL allocation but is not used by any Java consumer.
     */
    public static final int TABLE_SIZE = 12;

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
     * Width of each {@code userType} indicator field (PIC X(01)). 1 character;
     * the value space is {@code {'A', 'U'}} (Admin or User), represented in
     * Java by the {@link UserType} sealed type.
     */
    public static final int OPT_USRTYPE_LENGTH = 1;

    /**
     * Single main-menu entry. Translates one iteration of the COBOL
     * {@code CDEMO-MENU-OPT OCCURS 12 TIMES} table, comprising four 15-level
     * subfields: {@code CDEMO-MENU-OPT-NUM}, {@code CDEMO-MENU-OPT-NAME},
     * {@code CDEMO-MENU-OPT-PGMNAME}, {@code CDEMO-MENU-OPT-USRTYPE}. Records
     * are immutable per Java semantics.
     *
     * @param optionNumber the COBOL {@code CDEMO-MENU-OPT-NUM} value
     *                     (range 1&ndash;{@link #OPT_COUNT}; PIC 9(02))
     * @param optionName   the COBOL {@code CDEMO-MENU-OPT-NAME} literal,
     *                     exactly {@link #OPT_NAME_LENGTH} characters
     *                     including trailing space padding (PIC X(35))
     * @param programName  the COBOL {@code CDEMO-MENU-OPT-PGMNAME} literal,
     *                     exactly {@link #OPT_PROGRAM_LENGTH} characters
     *                     (PIC X(08))
     * @param userType     the COBOL {@code CDEMO-MENU-OPT-USRTYPE} sealed user
     *                     type ({@link UserType.Admin} or
     *                     {@link UserType.User}) mapped from the underlying
     *                     character ('A' or 'U') (PIC X(01))
     */
    @CobolProgram(
            value = "COMEN02Y",
            sourcePath = "app/cpy/COMEN02Y.cpy",
            translationDate = "2025-10-15",
            notes = "CDEMO-MENU-OPT OCCURS 12 TIMES (15-level subfields "
                    + "CDEMO-MENU-OPT-NUM, CDEMO-MENU-OPT-NAME, "
                    + "CDEMO-MENU-OPT-PGMNAME, CDEMO-MENU-OPT-USRTYPE). "
                    + "Includes user-type subfield (unlike AdminMenuEntry)."
    )
    public record MainMenuEntry(
            int optionNumber,
            String optionName,
            String programName,
            UserType userType
    ) {
        /**
         * Compact canonical constructor that validates field constraints per
         * the COBOL PIC declarations. Uses JEP 513 Flexible Constructor Bodies
         * (finalized in Java 25) to validate inputs before binding.
         *
         * @throws IllegalArgumentException if {@code optionNumber} is out of
         *         range, {@code optionName} length differs from
         *         {@link #OPT_NAME_LENGTH}, or {@code programName} length
         *         differs from {@link #OPT_PROGRAM_LENGTH}
         * @throws NullPointerException     if {@code optionName},
         *         {@code programName}, or {@code userType} is {@code null}
         */
        public MainMenuEntry {
            if (optionNumber < 1 || optionNumber > OPT_COUNT) {
                throw new IllegalArgumentException(
                        "optionNumber out of range [1," + OPT_COUNT + "]: "
                                + optionNumber);
            }
            Objects.requireNonNull(optionName, "optionName");
            if (optionName.length() != OPT_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "optionName length must be " + OPT_NAME_LENGTH
                                + ", was " + optionName.length()
                                + " (value=\"" + optionName + "\")");
            }
            Objects.requireNonNull(programName, "programName");
            if (programName.length() != OPT_PROGRAM_LENGTH) {
                throw new IllegalArgumentException(
                        "programName length must be " + OPT_PROGRAM_LENGTH
                                + ", was " + programName.length()
                                + " (value=\"" + programName + "\")");
            }
            Objects.requireNonNull(userType, "userType");
        }
    }

    /**
     * The 10 active main menu entries, in COBOL declaration order
     * (option 1 first, option 10 last). Immutable via
     * {@link List#of(Object[])}.
     *
     * <p>Order MUST NOT change &mdash; consumers rely on positional ordering
     * for both iteration order on the rendered menu screen and for
     * option-number-to-program dispatch per AAP &sect;0.7.1 "Preserve &hellip;
     * sort orders and batch sequencing."
     *
     * <table>
     *   <caption>Main Menu Mapping</caption>
     *   <tr><th>Option #</th><th>Label</th><th>Target Program</th><th>User Type</th></tr>
     *   <tr><td>1</td><td>Account View</td><td>COACTVWC</td><td>U</td></tr>
     *   <tr><td>2</td><td>Account Update</td><td>COACTUPC</td><td>U</td></tr>
     *   <tr><td>3</td><td>Credit Card List</td><td>COCRDLIC</td><td>U</td></tr>
     *   <tr><td>4</td><td>Credit Card View</td><td>COCRDSLC</td><td>U</td></tr>
     *   <tr><td>5</td><td>Credit Card Update</td><td>COCRDUPC</td><td>U</td></tr>
     *   <tr><td>6</td><td>Transaction List</td><td>COTRN00C</td><td>U</td></tr>
     *   <tr><td>7</td><td>Transaction View</td><td>COTRN01C</td><td>U</td></tr>
     *   <tr><td>8</td><td>Transaction Add</td><td>COTRN02C</td><td>U</td></tr>
     *   <tr><td>9</td><td>Transaction Reports</td><td>CORPT00C</td><td>U</td></tr>
     *   <tr><td>10</td><td>Bill Payment</td><td>COBIL00C</td><td>U</td></tr>
     * </table>
     *
     * <p>Entry #8 had a historically commented-out alternate label
     * {@code 'Transaction Add (Admin Only)       '} (see COBOL line 69 with
     * {@code *} in column 7). The current active label is
     * {@code 'Transaction Add                    '}. Per AAP &sect;0.7.1
     * idiom-for-idiom translation, only the active value is used.
     */
    public static final List<MainMenuEntry> ENTRIES = List.of(
            new MainMenuEntry(1,  "Account View                       ", "COACTVWC", UserType.USER),
            new MainMenuEntry(2,  "Account Update                     ", "COACTUPC", UserType.USER),
            new MainMenuEntry(3,  "Credit Card List                   ", "COCRDLIC", UserType.USER),
            new MainMenuEntry(4,  "Credit Card View                   ", "COCRDSLC", UserType.USER),
            new MainMenuEntry(5,  "Credit Card Update                 ", "COCRDUPC", UserType.USER),
            new MainMenuEntry(6,  "Transaction List                   ", "COTRN00C", UserType.USER),
            new MainMenuEntry(7,  "Transaction View                   ", "COTRN01C", UserType.USER),
            new MainMenuEntry(8,  "Transaction Add                    ", "COTRN02C", UserType.USER),
            new MainMenuEntry(9,  "Transaction Reports                ", "CORPT00C", UserType.USER),
            new MainMenuEntry(10, "Bill Payment                       ", "COBIL00C", UserType.USER)
    );

    /*
     * Class-load-time validation: confirm the ENTRIES list matches the COBOL
     * CDEMO-MENU-OPT-COUNT (10 entries) and that option numbers are
     * consecutive starting at 1. Fail-fast at class load if any invariant has
     * drifted from the COBOL source.
     */
    static {
        if (ENTRIES.size() != OPT_COUNT) {
            throw new AssertionError(
                    "MainMenuTable.ENTRIES must contain exactly " + OPT_COUNT
                            + " entries (matching COBOL CDEMO-MENU-OPT-COUNT VALUE), got "
                            + ENTRIES.size());
        }
        for (int i = 0; i < ENTRIES.size(); i++) {
            MainMenuEntry entry = ENTRIES.get(i);
            int expectedOptionNumber = i + 1;
            if (entry.optionNumber() != expectedOptionNumber) {
                throw new AssertionError(
                        "MainMenuTable.ENTRIES[" + i + "].optionNumber expected "
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
     * Private constructor to prevent instantiation. {@code MainMenuTable} is a
     * pure constants holder; consumers access entries via the static
     * {@link #ENTRIES} list.
     *
     * @throws AssertionError always; this class must not be instantiated
     */
    private MainMenuTable() {
        throw new AssertionError(
                "MainMenuTable is a static constants holder; do not instantiate.");
    }
}
