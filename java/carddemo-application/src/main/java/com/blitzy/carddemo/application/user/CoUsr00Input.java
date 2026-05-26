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
// exported by the java.base module. Provides java.lang.String for the
// searchUserId record component, java.util.List for the selections
// component and the List.copyOf(...) defensive immutable copy idiom,
// java.util.ArrayList for the mutable working list used inside the
// compact canonical constructor to pad/truncate the selections list to
// exactly ROW_COUNT entries, and java.lang.Enum support for the nested
// AidKey enum. Per AAP §0.7.3 this is the canonical module-import
// statement for files that touch many java.* packages, replacing
// individual java.lang.* and java.util.* import statements.
import module java.base;

// AAP §0.7.1 traceability requirement: every translated artefact must
// declare its COBOL / BMS origin via the @CobolProgram annotation so
// that downstream code searches starting from a COBOL identifier reach
// the Java translation in one hop. The annotation has
// RetentionPolicy.SOURCE; it disappears from the compiled .class file
// and adds no runtime cost.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input DTO record for the <strong>COUSR00 / COUSR0A</strong>
 * (List Users) screen &mdash; the entry-contract carrier for the user
 * fields received from a CICS {@code RECEIVE MAP} on the
 * {@link CoUsr00C} application class (COBOL transaction {@code CU00},
 * program {@code COUSR00C}).
 *
 * <h2>Source artefacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COUSR00.bms} (mapset
 *       {@code COUSR00}, map {@code COUSR0A}, 24x80).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COUSR00.CPY}
 *       (group {@code 01 COUSR0AI}).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COUSR00C.cbl} (the
 *       {@code EVALUATE EIBAID} dispatch block at lines 122-137 and
 *       the {@code PROCESS-ENTER-KEY} selection-scan paragraph at
 *       lines 149-186).</li>
 * </ul>
 *
 * <h2>Field-faithful symbolic-map design (M15 restoration)</h2>
 *
 * <p>The 3270 BMS symbolic map {@code 01 COUSR0AI} carries the standard
 * triplet pattern (length, flag, input value) per editable field, plus
 * header echoes and ten rows of (selection, user-id, first-name,
 * last-name, user-type). On a CICS {@code RECEIVE MAP} the whole
 * symbolic record is delivered to the program. To preserve byte-for-byte
 * symbolic-map fidelity per AAP &sect;0.7.1 (preserve record layouts,
 * field orderings, padding), this DTO carries every echoed BMS surface,
 * <em>not just</em> the user-editable ones:
 *
 * <ul>
 *   <li>{@code USRIDINI PIC X(8)} &mdash; an optional search prefix
 *       that positions the USRSEC browse before paging starts.
 *       Mapped to {@link #searchUserId()}.</li>
 *   <li>{@code SEL0001I .. SEL0010I PIC X(1)} &mdash; ten per-row
 *       selection characters where {@code 'U'} / {@code 'u'} means
 *       "update this user" (XCTL to {@code COUSR02C}), {@code 'D'} /
 *       {@code 'd'} means "delete this user" (XCTL to
 *       {@code COUSR03C}), and any other byte (typically SPACES or
 *       LOW-VALUES) means "no action". Mapped to a closed-taxonomy
 *       {@link CoUsr00C.UserAction} list at {@link #selections()}.</li>
 *   <li>{@code USRID0nI PIC X(8)}, {@code FNAME0nI PIC X(20)},
 *       {@code LNAME0nI PIC X(20)}, {@code UTYPE0nI PIC X(1)} for
 *       {@code n = 01..10} &mdash; the echoed user-id, first-name,
 *       last-name, and user-type bytes for each row. Although marked
 *       {@code ASKIP} on the BMS map (operator cannot edit), they are
 *       part of the symbolic-map input record and are preserved here
 *       as {@link #rows()} (a list of ten {@link Row} records). This
 *       preserves the COBOL contract that what was last rendered to the
 *       terminal also comes back on the next RECEIVE MAP.</li>
 * </ul>
 *
 * <p>The application class {@link CoUsr00C} ultimately drives selection
 * processing off the closed-taxonomy {@link #selections()} list (an
 * exhaustive sealed switch over {@link CoUsr00C.UserAction}), but the
 * raw per-row echoes in {@link #rows()} are preserved for byte-for-byte
 * symbolic-map parity tests and for any downstream consumer that needs
 * the literal BMS-echoed bytes (for example, a golden-record harness
 * comparing the Java RECEIVE MAP capture against the COBOL baseline).
 *
 * <h2>Field-by-field mapping</h2>
 * <table>
 *   <caption>Mapping from BMS symbolic copybook {@code COUSR0AI}
 *            (input view) to Java record components</caption>
 *   <tr><th>Symbolic BMS field</th><th>COBOL PIC</th><th>Java component</th></tr>
 *   <tr><td>USRIDINI</td><td>X(8)</td><td>{@link #searchUserId()}</td></tr>
 *   <tr><td>SEL0001I .. SEL0010I</td><td>X(1) each</td><td>{@link #selections()}
 *       (10 entries, each a {@link CoUsr00C.UserAction} permit)</td></tr>
 *   <tr><td>SEL0001I .. SEL0010I</td><td>X(1) each (raw byte)</td><td>{@link Row#selection()}
 *       (one per row in {@link #rows()})</td></tr>
 *   <tr><td>USRID01I .. USRID10I</td><td>X(8) each</td><td>{@link Row#userId()}
 *       (one per row in {@link #rows()})</td></tr>
 *   <tr><td>FNAME01I .. FNAME10I</td><td>X(20) each</td><td>{@link Row#firstName()}</td></tr>
 *   <tr><td>LNAME01I .. LNAME10I</td><td>X(20) each</td><td>{@link Row#lastName()}</td></tr>
 *   <tr><td>UTYPE01I .. UTYPE10I</td><td>X(1) each</td><td>{@link Row#userType()}</td></tr>
 * </table>
 *
 * <p>Plus {@link #aidKey()} &mdash; the AID-key state captured at
 * {@code RECEIVE MAP} time (decoded from {@code EIBAID} before
 * constructing this record). The full AID-key hierarchy is defined as a
 * sealed type in {@code carddemo-domain.text.CcWorkAreas.AidKey}; per
 * the agent prompt and the convention established by
 * {@link com.blitzy.carddemo.application.account.CoActVwInput.AidKey},
 * a minimal nested enum is used here that models only the keys COUSR00
 * actually responds to.
 *
 * <h2>AID-key dispatch (translates COBOL paragraph {@code MAIN-PARA})</h2>
 *
 * <p>Translates the {@code EVALUATE EIBAID} block at lines 122-137 of
 * {@code app/cbl/COUSR00C.cbl}:
 *
 * <ul>
 *   <li>{@code DFHENTER} &rarr; {@link AidKey#ENTER}: scan {@link #selections()}
 *       for a non-{@code None} entry (XCTL on first match) and otherwise
 *       fall through to a forward scan from {@link #searchUserId()}.</li>
 *   <li>{@code DFHPF3} &rarr; {@link AidKey#PF03_BACK}: XCTL to the admin
 *       menu ({@code COADM01C}).</li>
 *   <li>{@code DFHPF7} &rarr; {@link AidKey#PF07_PAGE_BACK}: page backward
 *       using the paging cursor's "first row" key.</li>
 *   <li>{@code DFHPF8} &rarr; {@link AidKey#PF08_PAGE_FWD}: page forward
 *       using the paging cursor's "last row" key.</li>
 *   <li>{@code WHEN OTHER} &rarr; {@link AidKey#OTHER}: emit
 *       {@code MSG_INVALID_KEY} and re-render the current page.</li>
 * </ul>
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 *
 * <p>In COBOL, an unfilled BMS {@code PIC X(n)} input field arrives as
 * SPACES (a fixed-length blank string), never as a null reference; there
 * is no null pointer in COBOL. To preserve that behavior precisely:
 *
 * <ul>
 *   <li>The compact constructor below replaces a {@code null}
 *       {@link String} {@code searchUserId} with the empty string
 *       {@code ""}.</li>
 *   <li>The compact constructor pads or truncates {@link #selections()}
 *       to exactly {@link #ROW_COUNT} entries; missing or {@code null}
 *       entries are replaced with {@link CoUsr00C.UserAction.None#INSTANCE}.</li>
 *   <li>The compact constructor pads or truncates {@link #rows()} to
 *       exactly {@link #ROW_COUNT} entries; missing or {@code null}
 *       entries are replaced with {@link Row#blank()}.</li>
 *   <li>A {@code null} {@code aidKey} is coerced to {@link AidKey#ENTER}
 *       (the default dispatch path).</li>
 * </ul>
 *
 * <p>Downstream consumers can therefore safely call {@link String#isBlank()},
 * {@link String#trim()}, {@code List#get(int)}, etc. on every component
 * without first checking for {@code null}.
 *
 * <h2>Constructor body discipline (JEP 513)</h2>
 *
 * <p>The compact constructor uses <strong>JEP 513 Flexible Constructor
 * Bodies</strong> (finalised in Java 25). Statements before the
 * canonical field-assignment perform input normalisation, which is
 * exactly the right place to capture COBOL-style "default to SPACES"
 * semantics. This satisfies the file-level mandate to use JEP 513 for
 * record validation and normalisation (AAP &sect;0.7.3).
 *
 * <h2>Immutability and thread-safety</h2>
 *
 * <p>Because this is a record, all components are {@code final} and
 * accessors are automatically generated; there are no setters, no
 * Lombok, no Spring annotations, no Jakarta validation annotations.
 * {@link #selections()} returns the result of {@link List#copyOf(java.util.Collection)},
 * which is an immutable view that callers cannot mutate. The instance
 * is safely shareable across virtual threads (per AAP &sect;0.6.6)
 * without synchronisation.
 *
 * <h2>Forbidden idioms (per AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written
 *       in the compact constructor.</li>
 *   <li>No setters &mdash; records are immutable.</li>
 *   <li>No {@code java.util.Date} &mdash; no date fields on this DTO.</li>
 *   <li>No {@code double} / {@code float} &mdash; no monetary fields on
 *       this DTO.</li>
 *   <li>No mutable collection fields &mdash; {@link #selections()} is
 *       wrapped in {@link List#copyOf(java.util.Collection)}.</li>
 * </ul>
 *
 * @param searchUserId optional 8-char search prefix the operator typed
 *                     into {@code USRIDINI} to position the USRSEC
 *                     browse; never {@code null} (a {@code null}
 *                     argument is normalised to {@code ""} by the
 *                     compact constructor); always exactly &le; 8 chars
 *                     long after construction (longer strings are
 *                     truncated to 8)
 * @param selections   the ten per-row selection actions, one per BMS
 *                     row {@code SEL0001I .. SEL0010I}, each one of the
 *                     {@link CoUsr00C.UserAction} permits
 *                     ({@code None}, {@code Update}, {@code Delete},
 *                     {@code Invalid}); never {@code null} and always
 *                     of size exactly {@link #ROW_COUNT} (a shorter
 *                     list is padded with
 *                     {@link CoUsr00C.UserAction.None#INSTANCE}; a
 *                     longer list is truncated to {@link #ROW_COUNT};
 *                     {@code null} elements are replaced with
 *                     {@link CoUsr00C.UserAction.None#INSTANCE}); the
 *                     returned list is immutable
 *                     ({@link List#copyOf(java.util.Collection)})
 * @param rows         the ten per-row BMS field echoes (each a
 *                     {@link Row} carrying selection, userId, firstName,
 *                     lastName, userType); never {@code null} and
 *                     always of size exactly {@link #ROW_COUNT}
 *                     (a shorter list is padded with {@link Row#blank()};
 *                     a longer list is truncated; {@code null} elements
 *                     are replaced with {@link Row#blank()}); the
 *                     returned list is immutable
 *                     ({@link List#copyOf(java.util.Collection)})
 * @param aidKey       AID key captured at {@code RECEIVE MAP} time;
 *                     never {@code null} (a {@code null} argument is
 *                     normalised to {@link AidKey#ENTER} by the compact
 *                     constructor)
 * @see com.blitzy.carddemo.application.user.CoUsr00C
 * @see com.blitzy.carddemo.application.user.CoUsr00C.UserAction
 * @see com.blitzy.carddemo.application.user.CoUsr00Output
 * @since 1.0.0
 */
@CobolProgram(
        value = "COUSR00C",
        sourcePath = "app/cpy-bms/COUSR00.CPY",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side) for the list-users screen "
              + "(COBOL PROGRAM-ID COUSR00C, BMS map COUSR00 / mapset COUSR00). "
              + "Field-faithful symbolic-map model: carries the search prefix "
              + "(USRIDINI), per-row selection actions (SEL0001I..SEL0010I), "
              + "AND the per-row echoed BMS field data "
              + "(USRID0nI/FNAME0nI/LNAME0nI/UTYPE0nI) as a Row sub-record "
              + "per AAP §0.7.1 preserve-as-is (record layouts, field "
              + "orderings, padding). The Row data is what the BMS terminal "
              + "echoes on a RECEIVE MAP and matches the symbolic copybook "
              + "01 COUSR0AI structure byte-for-byte. Per-row user-ids may "
              + "also be re-derived from the externalised PagingState as a "
              + "defensive fallback; both surfaces remain available."
)
public record CoUsr00Input(
        String searchUserId,
        List<CoUsr00C.UserAction> selections,
        List<Row> rows,
        AidKey aidKey
) {

    /**
     * Fixed page size &mdash; 10 user rows per BMS map, matching the
     * {@code SEL0001 .. SEL0010} row count in
     * {@code app/cpy-bms/COUSR00.CPY}. {@link #selections()} always
     * contains exactly this many entries after construction.
     */
    public static final int ROW_COUNT = 10;

    /**
     * Maximum length of the {@code USRIDINI} search-prefix field, in
     * characters. The BMS map declares {@code USRIDIN DFHMDF
     * ATTRB=(FSET,NORM,UNPROT) ... LENGTH=8} at lines 95-99 of
     * {@code app/bms/COUSR00.bms}; the symbolic copybook declares
     * {@code USRIDINI  PIC X(8)} at line 66 of
     * {@code app/cpy-bms/COUSR00.CPY}.
     */
    private static final int SEARCH_USER_ID_MAX = 8;

    /**
     * Compact (canonical) constructor &mdash; <strong>JEP 513 Flexible
     * Constructor Bodies</strong> (finalised in Java 25).
     *
     * <p>Statements below run <em>before</em> the canonical
     * field-assignment that the record implicitly emits at the end of
     * this body, which is exactly the right place to:
     *
     * <ul>
     *   <li>Coerce {@code null} {@link String} inputs to {@code ""}
     *       (COBOL SPACES default).</li>
     *   <li>Truncate the search prefix to {@link #SEARCH_USER_ID_MAX}
     *       characters (BMS {@code USRIDIN} length limit).</li>
     *   <li>Normalise {@link #selections()} to exactly
     *       {@link #ROW_COUNT} entries: pad with
     *       {@link CoUsr00C.UserAction.None#INSTANCE} when shorter,
     *       truncate when longer, and replace any {@code null} or
     *       missing entries with {@link CoUsr00C.UserAction.None#INSTANCE}.</li>
     *   <li>Wrap the normalised selections list in
     *       {@link List#copyOf(java.util.Collection)} so the record is
     *       deeply immutable.</li>
     *   <li>Default a {@code null} {@code aidKey} to
     *       {@link AidKey#ENTER}.</li>
     * </ul>
     */
    public CoUsr00Input {
        // --- Normalise searchUserId ------------------------------------
        searchUserId = orEmpty(searchUserId);
        if (searchUserId.length() > SEARCH_USER_ID_MAX) {
            searchUserId = searchUserId.substring(0, SEARCH_USER_ID_MAX);
        }

        // --- Normalise selections to exactly ROW_COUNT entries ----------
        // Use an ArrayList as the mutable working buffer; we always
        // return an immutable List.copyOf(...) at the end so the record
        // cannot leak a mutable reference. ROW_COUNT initial capacity
        // avoids any internal array growth for the canonical case.
        ArrayList<CoUsr00C.UserAction> normalizedSelections = new ArrayList<>(ROW_COUNT);
        if (selections != null) {
            // Copy up to ROW_COUNT entries from the caller's list,
            // replacing any null entry with None.INSTANCE. We use an
            // index-based loop (not an iterator) so that a caller-supplied
            // list whose size exceeds ROW_COUNT is silently truncated
            // rather than throwing IndexOutOfBoundsException later.
            int n = Math.min(selections.size(), ROW_COUNT);
            for (int i = 0; i < n; i++) {
                CoUsr00C.UserAction action = selections.get(i);
                normalizedSelections.add(action == null
                        ? CoUsr00C.UserAction.None.INSTANCE
                        : action);
            }
        }
        // Pad the tail with None.INSTANCE (the "no selection" sentinel,
        // matching COBOL SPACES / LOW-VALUES in SEL0nnI) until the list
        // reaches the canonical size of ROW_COUNT.
        while (normalizedSelections.size() < ROW_COUNT) {
            normalizedSelections.add(CoUsr00C.UserAction.None.INSTANCE);
        }
        // Defensive immutable copy: callers cannot mutate the record's
        // internal list, and downstream consumers always observe exactly
        // ROW_COUNT entries.
        selections = List.copyOf(normalizedSelections);

        // --- Normalise rows to exactly ROW_COUNT entries ----------------
        // Restored per M15 (BMS symbolic-map field fidelity). The Row
        // sub-record carries the echoed (selection, userId, firstName,
        // lastName, userType) for each BMS row USRID0nI/FNAME0nI/
        // LNAME0nI/UTYPE0nI as declared in app/cpy-bms/COUSR00.CPY.
        // null entries and short lists are normalised to a blank Row.
        ArrayList<Row> normalizedRows = new ArrayList<>(ROW_COUNT);
        if (rows != null) {
            int n = Math.min(rows.size(), ROW_COUNT);
            for (int i = 0; i < n; i++) {
                Row r = rows.get(i);
                normalizedRows.add(r == null ? Row.blank() : r);
            }
        }
        while (normalizedRows.size() < ROW_COUNT) {
            normalizedRows.add(Row.blank());
        }
        rows = List.copyOf(normalizedRows);

        // --- Default aidKey to ENTER ------------------------------------
        if (aidKey == null) {
            aidKey = AidKey.ENTER;
        }
    }

    /**
     * AID-key state captured at BMS {@code RECEIVE MAP} time for the
     * COUSR00 screen.
     *
     * <p>The COBOL {@code CSSTRPFY} copybook decodes the {@code EIBAID}
     * byte returned by CICS into a set of 88-level conditions. Per
     * AAP &sect;0.6.10, the <em>full</em> AID-key hierarchy (ENTER,
     * CLEAR, PA1, PA2, PFK01-PFK12) is defined as a sealed type in
     * {@code carddemo-domain.text.CcWorkAreas.AidKey}; that hierarchy
     * supports exhaustive pattern matching for screens that handle many
     * function keys.
     *
     * <p>COUSR00 handles only four AID keys; per the convention
     * established by
     * {@link com.blitzy.carddemo.application.account.CoActVwInput.AidKey},
     * this nested enum models exactly the keys COUSR00 actually responds
     * to. Any other AID value is captured as {@link #OTHER} and yields
     * the standard {@code "Invalid key pressed."} error message.
     *
     * <p>The matching {@code execute} entry method on
     * {@link CoUsr00C} declares its own {@link CoUsr00C.AidKey} enum
     * with the same five values, exposing the use-case-level dispatch
     * contract; conversion between {@code CoUsr00Input.AidKey} and
     * {@code CoUsr00C.AidKey} happens at the call boundary (in the
     * adapter or test harness).
     *
     * <p><strong>Why an enum rather than a sealed interface here?</strong>
     * Sealed interfaces are AAP-mandated only for COBOL constructs that
     * partition a value space &mdash; in particular {@code REDEFINES}
     * or 88-level taxonomies on data fields. The AID key on COUSR00 is
     * a closed 5-state dispatch (ENTER / PF03 / PF07 / PF08 / fallback)
     * with no associated payload, which is exactly the case for which a
     * plain {@code enum} is idiomatic and sufficient. The agent prompt
     * for this file explicitly mandates {@code enum} here.
     */
    public enum AidKey {

        /**
         * The operator pressed {@code ENTER}. Either:
         * <ul>
         *   <li>process a per-row selection (if {@link #selections()}
         *       contains a non-{@code None} entry); or</li>
         *   <li>refresh the page from the (possibly new)
         *       {@link #searchUserId()} prefix when no selection is
         *       present.</li>
         * </ul>
         */
        ENTER,

        /**
         * The operator pressed {@code PF03}. XCTL to the admin menu
         * program ({@code COADM01C}, transaction {@code CA00}).
         */
        PF03_BACK,

        /**
         * The operator pressed {@code PF07}. Page the USRSEC browse
         * backwards using the cursor's "first row" key.
         */
        PF07_PAGE_BACK,

        /**
         * The operator pressed {@code PF08}. Page the USRSEC browse
         * forwards using the cursor's "last row" key.
         */
        PF08_PAGE_FWD,

        /**
         * Any AID key not specifically recognised by COUSR00. The
         * application class responds with the standard
         * {@code "Invalid key pressed."} message and re-renders the
         * current page.
         */
        OTHER
    }

    /**
     * Factory returning a fully blank input &mdash; {@link #searchUserId()}
     * {@code ""}, {@link #selections()} of size {@link #ROW_COUNT} all
     * filled with {@link CoUsr00C.UserAction.None#INSTANCE},
     * {@link #rows()} of size {@link #ROW_COUNT} all filled with
     * {@link Row#blank()}, and {@link #aidKey()} {@link AidKey#ENTER}.
     *
     * <p>Typical use: the first dispatch into COUSR00 from the admin
     * menu when no search prefix or selection is yet known.
     *
     * @return an all-empty input record; never {@code null}
     */
    public static CoUsr00Input blank() {
        return new CoUsr00Input("", List.of(), List.of(), AidKey.ENTER);
    }

    /**
     * Factory returning an input with only the
     * {@link #searchUserId()} prefix populated; {@link #selections()}
     * defaults to {@link #ROW_COUNT} entries of
     * {@link CoUsr00C.UserAction.None#INSTANCE}, {@link #rows()}
     * defaults to {@link #ROW_COUNT} blank rows, and {@link #aidKey()}
     * defaults to {@link AidKey#ENTER}.
     *
     * <p>Typical use: programmatic re-entry into COUSR00 with a known
     * starting user-id prefix (e.g., from a unit test or from a sibling
     * program that wants to position the browse).
     *
     * <p>A {@code null} or over-length argument is normalised by the
     * compact constructor (treated as {@code ""} or truncated to
     * {@link #SEARCH_USER_ID_MAX} characters, respectively), so this
     * factory is safe to call with any caller-provided value.
     *
     * @param prefix the search prefix; may be {@code null} or longer
     *               than {@link #SEARCH_USER_ID_MAX} characters
     * @return an input record carrying just the search prefix; never
     *         {@code null}
     */
    public static CoUsr00Input withSearchPrefix(String prefix) {
        return new CoUsr00Input(prefix, List.of(), List.of(), AidKey.ENTER);
    }

    /**
     * Returns the 1-based row index (1..{@link #ROW_COUNT}) of the
     * first {@link #selections()} entry that is not
     * {@link CoUsr00C.UserAction.None}, or {@code 0} if no such row
     * exists. Restored per M15. Translates the COBOL paragraph
     * {@code PROCESS-ENTER-KEY} loop at lines 149-186 of
     * {@code app/cbl/COUSR00C.cbl}, which scans
     * {@code SEL0001I .. SEL0010I} top-to-bottom and breaks on the
     * first non-blank value.
     *
     * @return 1-based selected-row index, or {@code 0} when none
     */
    public int firstSelectedRow() {
        for (int i = 0; i < selections.size(); i++) {
            if (!(selections.get(i) instanceof CoUsr00C.UserAction.None)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Returns a copy of this input with the {@link #aidKey()} replaced
     * by {@code newAidKey}. Restored per M15 for parity with sibling
     * {@code CoUsr02Input.withAidKey} / {@code CoUsr03Input.withAidKey}.
     *
     * @param newAidKey the new AID key; {@code null} is normalised to
     *                  {@link AidKey#ENTER} by the compact constructor
     * @return a new {@link CoUsr00Input} carrying the same search
     *         prefix, selections, and rows but the supplied AID key;
     *         never {@code null}
     */
    public CoUsr00Input withAidKey(AidKey newAidKey) {
        return new CoUsr00Input(searchUserId, selections, rows, newAidKey);
    }

    /**
     * Returns a copy of this input with the {@link #searchUserId()}
     * replaced by {@code newSearchUserId}. Restored per M15 for
     * symmetry with {@link #withAidKey(AidKey)}.
     *
     * @param newSearchUserId the new search prefix; may be
     *                        {@code null} (normalised to {@code ""})
     *                        or longer than {@link #SEARCH_USER_ID_MAX}
     *                        characters (truncated)
     * @return a new {@link CoUsr00Input} carrying the same selections,
     *         rows, and AID key but the supplied search prefix; never
     *         {@code null}
     */
    public CoUsr00Input withUserIdSearch(String newSearchUserId) {
        return new CoUsr00Input(newSearchUserId, selections, rows, aidKey);
    }

    /**
     * Per-row BMS symbolic-map field group &mdash; the echoed terminal
     * data for one of the ten user-list rows. Restored per M15 (BMS
     * field fidelity).
     *
     * <p>Mirrors the four-field group declared once per row in the
     * symbolic copybook {@code app/cpy-bms/COUSR00.CPY}, lines 67-104
     * for row 01 (and analogous blocks for rows 02-10):</p>
     * <ul>
     *   <li>{@code SEL0nnI PIC X(1)} &rarr; {@link #selection()}</li>
     *   <li>{@code USRID0nI PIC X(8)} &rarr; {@link #userId()}</li>
     *   <li>{@code FNAME0nI PIC X(20)} &rarr; {@link #firstName()}</li>
     *   <li>{@code LNAME0nI PIC X(20)} &rarr; {@link #lastName()}</li>
     *   <li>{@code UTYPE0nI PIC X(1)} &rarr; {@link #userType()}</li>
     * </ul>
     *
     * <p>All five components are {@code String} typed because BMS
     * {@code PIC X(n)} fields arrive as fixed-width strings with
     * trailing-space padding. The compact constructor normalises
     * {@code null} references to empty strings preserving COBOL
     * SPACES default semantics.</p>
     *
     * <p>Note that {@link #selection()} is the raw single-character
     * BMS byte; the higher-level {@link CoUsr00C.UserAction} sealed
     * taxonomy lives on the parent record's {@link #selections()}
     * list, which is computed from these raw bytes by the application
     * class's input decoder. The redundancy is intentional &mdash; the
     * BMS-faithful raw bytes are preserved for byte-for-byte parity
     * tests, while the closed-taxonomy abstraction drives the
     * exhaustive pattern switches in {@link CoUsr00C}.</p>
     *
     * @param selection raw selection byte (1 char); blank/empty for
     *                  unselected, {@code "U"}/{@code "u"} for update,
     *                  {@code "D"}/{@code "d"} for delete; never
     *                  {@code null} (normalised to {@code ""})
     * @param userId    8-character user-id field echoed for this row;
     *                  never {@code null} (normalised to {@code ""})
     * @param firstName 20-character first-name field echoed for this
     *                  row; never {@code null}
     * @param lastName  20-character last-name field echoed for this
     *                  row; never {@code null}
     * @param userType  single-character user-type field echoed for
     *                  this row ({@code "A"} = admin, {@code "U"} =
     *                  user); never {@code null}
     */
    public record Row(
            String selection,
            String userId,
            String firstName,
            String lastName,
            String userType
    ) {
        /**
         * Compact constructor &mdash; normalises every {@code null}
         * component to the empty string per COBOL SPACES semantics.
         */
        public Row {
            selection = orEmpty(selection);
            userId    = orEmpty(userId);
            firstName = orEmpty(firstName);
            lastName  = orEmpty(lastName);
            userType  = orEmpty(userType);
        }

        /**
         * Returns a fully blank row &mdash; all five components are
         * the empty string. Used by the parent record's compact
         * constructor when padding {@link #rows()} to the canonical
         * {@link #ROW_COUNT} length.
         *
         * @return a blank row; never {@code null}
         */
        public static Row blank() {
            return new Row("", "", "", "", "");
        }
    }

    /**
     * Null-to-empty helper preserving COBOL "spaces by default"
     * semantics: a {@code null} reference becomes an empty
     * {@link String}, not a {@link NullPointerException}.
     *
     * @param s the input string (possibly {@code null})
     * @return {@code s} if non-{@code null}, otherwise the empty string
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }
}
