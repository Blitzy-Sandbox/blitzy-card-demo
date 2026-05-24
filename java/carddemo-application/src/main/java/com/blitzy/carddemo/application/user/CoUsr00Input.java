/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blitzy.carddemo.application.user;

// JEP 511 (finalized in Java 25): single-statement import of every package
// exported by the java.base module. Provides java.lang.String for every
// PIC X(n) component, java.util.List for the row list, java.util.ArrayList
// for the empty-row factory, and java.util.Objects for null-defensive
// helpers used by the canonical constructor.
import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record for the {@code COUSR0A / COUSR00} list-users screen
 * (COBOL transaction {@code CU00}, program {@code COUSR00C}).
 *
 * <p>This record is a field-for-field projection of the input view of
 * the {@code 01 COUSR0AI} group in {@code app/cpy-bms/COUSR00.CPY}:
 * the user-id search/filter input {@code USRIDINI PIC X(8)}, plus ten
 * row-tuples of (selection, user-id, first-name, last-name, user-type)
 * &mdash; the row data is normally written by the program before
 * SEND-MAP, but the BMS map allows the operator to type a single-letter
 * selection ({@code 'U'} for update or {@code 'D'} for delete) into any
 * row's SEL0nnI field, which is the primary way the operator interacts
 * with this screen.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COUSR00.bms}
 *       (mapset {@code COUSR00}, map {@code COUSR0A}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COUSR00.CPY}
 *       (group {@code 01 COUSR0AI}). Editable input leaves:
 *       {@code USRIDINI PIC X(8)} and 10 rows of
 *       {@code SEL00nnI PIC X(1)}, {@code USRID0nI PIC X(8)},
 *       {@code FNAME0nI PIC X(20)}, {@code LNAME0nI PIC X(20)},
 *       {@code UTYPE0nI PIC X(1)}.</li>
 *   <li>Translated COBOL program: {@code app/cbl/COUSR00C.cbl}.
 *       The {@code EVALUATE EIBAID} block at lines 118-132 enumerates
 *       the AID keys: {@code DFHENTER} (process selection),
 *       {@code DFHPF3} (back to admin menu), {@code DFHPF7}
 *       (page back), {@code DFHPF8} (page forward), and OTHER
 *       (invalid key).</li>
 * </ul>
 *
 * <p>The {@code aidKey} discriminator is captured separately from the
 * BMS field data; the controller dispatches on it via a
 * pattern-matching switch.
 *
 * @param userIdSearch  USRIDINI PIC X(8) — operator filter / starting key
 * @param pageNum       PAGENUMI PIC X(8) — echoed page number
 * @param rows          10 row tuples (selection, user-id, first-name,
 *                      last-name, user-type)
 * @param aidKey        AID key (ENTER / PF03_BACK / PF07_PREV / PF08_NEXT
 *                      / OTHER)
 */
@CobolProgram(
        value = "COUSR00C",
        sourcePath = "app/cpy-bms/COUSR00.CPY",
        notes = "BMS input DTO for the list-users screen. Operator may " +
                "type 'U' / 'u' / 'D' / 'd' selection into any row's " +
                "SEL0nnI field; controller scans rows 1-10 in order."
)
public record CoUsr00Input(
        String userIdSearch,
        String pageNum,
        List<Row> rows,
        AidKey aidKey
) {

    /** Fixed page size — 10 user rows per BMS map. */
    public static final int ROW_COUNT = 10;

    /**
     * Compact (canonical) constructor that defensively wraps the row
     * list and pads it to exactly {@link #ROW_COUNT} rows. {@code null}
     * strings are coerced to {@code ""} (COBOL SPACES default).
     */
    public CoUsr00Input {
        userIdSearch = clamp(orEmpty(userIdSearch), 8);
        pageNum = clamp(orEmpty(pageNum), 8);
        if (aidKey == null) {
            aidKey = AidKey.ENTER;
        }
        // Always normalize to a list of exactly ROW_COUNT.
        ArrayList<Row> normalized = new ArrayList<>(ROW_COUNT);
        if (rows != null) {
            for (int i = 0; i < rows.size() && i < ROW_COUNT; i++) {
                Row r = rows.get(i);
                normalized.add(r == null ? Row.empty() : r);
            }
        }
        while (normalized.size() < ROW_COUNT) {
            normalized.add(Row.empty());
        }
        rows = List.copyOf(normalized);
    }

    /**
     * One row of the COUSR00 selection panel. The selection field is
     * the primary input the operator edits; the other four fields are
     * program-populated read-only echoes that the BMS map carries on
     * the input side as well (CICS RECEIVE-MAP returns the entire
     * symbolic-map record, edited or not).
     */
    public record Row(String selection, String userId, String firstName,
                      String lastName, String userType) {

        public Row {
            selection = clamp(orEmpty(selection), 1);
            userId = clamp(orEmpty(userId), 8);
            firstName = clamp(orEmpty(firstName), 20);
            lastName = clamp(orEmpty(lastName), 20);
            userType = clamp(orEmpty(userType), 1);
        }

        public static Row empty() {
            return new Row("", "", "", "", "");
        }
    }

    /**
     * AID-key alias enum scoped to this Input record.
     *
     * <p>Translates the {@code EVALUATE EIBAID} dispatch block at lines
     * 118-132 of {@code app/cbl/COUSR00C.cbl}.
     */
    public enum AidKey {
        /** {@code DFHENTER}: process row selection. */
        ENTER,
        /** {@code DFHPF3}: return to the admin menu. */
        PF03_BACK,
        /** {@code DFHPF7}: page backward. */
        PF07_PREV,
        /** {@code DFHPF8}: page forward. */
        PF08_NEXT,
        /** {@code WHEN OTHER}: any unmapped AID key. */
        OTHER
    }

    /**
     * Factory returning a fully blank input &mdash; userIdSearch
     * {@code ""}, pageNum {@code ""}, 10 blank rows, and aidKey
     * {@link AidKey#ENTER}.
     */
    public static CoUsr00Input blank() {
        return new CoUsr00Input("", "", List.of(), AidKey.ENTER);
    }

    /**
     * Returns the 1-based index of the first row whose
     * {@link Row#selection()} is non-blank, or {@code -1} if no row
     * has a selection. Mirrors the COBOL {@code EVALUATE TRUE} cascade
     * at lines 147-189 of {@code app/cbl/COUSR00C.cbl}.
     */
    public int firstSelectedRow() {
        for (int i = 0; i < rows.size(); i++) {
            String sel = rows.get(i).selection();
            if (sel != null && !sel.isEmpty() && !sel.trim().isEmpty()) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Returns a copy of this record with a different {@link AidKey}. */
    public CoUsr00Input withAidKey(AidKey newAidKey) {
        return new CoUsr00Input(userIdSearch, pageNum, rows, newAidKey);
    }

    /** Returns a copy with a different {@code userIdSearch}. */
    public CoUsr00Input withUserIdSearch(String newUserIdSearch) {
        return new CoUsr00Input(newUserIdSearch, pageNum, rows, aidKey);
    }

    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    private static String clamp(String s, int maxLen) {
        return (s.length() > maxLen) ? s.substring(0, maxLen) : s;
    }
}
