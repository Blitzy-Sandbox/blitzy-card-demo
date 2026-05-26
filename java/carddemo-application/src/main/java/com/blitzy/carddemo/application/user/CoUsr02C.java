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

import module java.base;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.commarea.UserType;
import com.blitzy.carddemo.domain.port.UserSecurityRepository;
import com.blitzy.carddemo.domain.record.SecUserData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the {@code COUSR02C} CICS online program at
 * {@code app/cbl/COUSR02C.cbl} ("Update a user in USRSEC file").
 *
 * <h2>Program purpose</h2>
 * <p>Two-phase fetch-then-update flow for the USRSEC dataset:
 * <ol>
 *   <li>Phase 1 (state {@link UpdateState.NotFetched}): user enters a
 *       user-id and presses ENTER. The program reads the USRSEC record
 *       by primary key (SEC-USR-ID); on success the row is displayed in
 *       the editable fields and state transitions to
 *       {@link UpdateState.Fetched}.</li>
 *   <li>Phase 2 (state {@link UpdateState.Fetched} or
 *       {@link UpdateState.Modified}): user edits one or more of FNAME,
 *       LNAME, PASSWD, USRTYPE and presses ENTER, PF5 (save) or PF3
 *       (save&amp;exit). The program (a) validates non-emptiness of every
 *       field, (b) compares each post-edit field against the cached
 *       pre-edit snapshot to detect whether any modification occurred,
 *       and (c) issues a READ UPDATE + REWRITE against USRSEC. On
 *       success state transitions to {@link UpdateState.Saved} and a
 *       green confirmation is rendered; on PF3 the program XCTL's to
 *       the admin menu (COADM01C) after the save.</li>
 * </ol>
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:     COUSR02C
 *   WS-PGMNAME:     'COUSR02C'
 *   WS-TRANID:      'CU02'
 *   WS-USRSEC-FILE: 'USRSEC  '
 *   MAPSET:         COUSR02
 *   MAP:            COUSR2A
 * </pre>
 *
 * <h2>AID-key bindings (paragraph {@code MAIN-PARA})</h2>
 * <ul>
 *   <li>{@code DFHENTER} &rarr; {@link AidKey#ENTER}: fetch (state =
 *       NotFetched) or save (state = Fetched/Modified).</li>
 *   <li>{@code DFHPF3}   &rarr; {@link AidKey#PF03_SAVE_AND_EXIT}: save
 *       and XCTL to COADM01C.</li>
 *   <li>{@code DFHPF4}   &rarr; {@link AidKey#PF04_CLEAR}: clear screen,
 *       reset state to NotFetched.</li>
 *   <li>{@code DFHPF5}   &rarr; {@link AidKey#PF05_SAVE}: save in place
 *       (stay on screen).</li>
 *   <li>{@code DFHPF12}  &rarr; {@link AidKey#PF12_CANCEL}: discard
 *       changes and XCTL to COADM01C.</li>
 *   <li>{@code WHEN OTHER} &rarr; {@link AidKey#OTHER}: emit
 *       {@code "Invalid key pressed. Press valid key."} and re-render.</li>
 * </ul>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code 0000-MAIN} &rarr; {@link #execute(CardDemoCommarea, byte[],
 *       AidKey, CoUsr02Input)}.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; {@link #returnToAdminMenu}.</li>
 *   <li>{@code SEND-USRUPD-SCREEN} &rarr; {@link #buildSendMap}.</li>
 *   <li>{@code RECEIVE-USRUPD-SCREEN} &rarr; handled by the
 *       {@link CoUsr02Input} parameter (BMS RECEIVE-MAP semantics).</li>
 *   <li>{@code 1000-PROCESS-INPUTS} &rarr; {@link #processInputs}.</li>
 *   <li>{@code 1200-EDIT-MAP-INPUTS} &rarr; inline validations within
 *       {@link #processInputs}.</li>
 *   <li>{@code 1300-CHECK-CHANGE-IN-REC} &rarr; {@link #sameFields}.</li>
 *   <li>{@code 9000-READ-USER-SEC-FILE} &rarr; {@link #fetchUser}.</li>
 *   <li>{@code 9100-UPDATE-USER-SEC-FILE} &rarr; embedded call to
 *       {@code userSecurityRepository.update(...)} in
 *       {@link #processInputs}.</li>
 *   <li>{@code 9999-ABEND-PROGRAM} &rarr; {@link #abendRoutine}.</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} / {@code CLEAR-CURRENT-SCREEN}
 *       &rarr; handled by {@link CoUsr02Input#blank()} +
 *       {@link #buildSendMap}.</li>
 * </ul>
 *
 * <h2>Messages preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>"User ID can NOT be empty..."</li>
 *   <li>"First Name can NOT be empty..."</li>
 *   <li>"Last Name can NOT be empty..."</li>
 *   <li>"Password can NOT be empty..."</li>
 *   <li>"User Type can NOT be empty..."</li>
 *   <li>"User Type must be 'A' (Admin) or 'U' (User)"</li>
 *   <li>"User ID NOT found..."</li>
 *   <li>"Unable to lookup User..."</li>
 *   <li>"Please modify to update ..." (note: SINGLE space before the
 *       trailing ellipsis &mdash; preserved verbatim from COBOL L240)</li>
 *   <li>"Unable to Update User..."</li>
 *   <li>"Invalid key pressed. Press valid key."</li>
 *   <li>Success: "User &lt;id&gt; has been updated..." (in DFHGREEN)</li>
 * </ul>
 *
 * <h2>Plaintext password preservation (AAP &sect;0.1.3)</h2>
 * <p>SEC-USER-DATA stores passwords as plaintext PIC X(08); this
 * translation preserves that behaviour. The plaintext password value is
 * NEVER passed to any {@code log.*} call &mdash; only the user-id appears
 * in log messages.
 *
 * <h2>Java 25 features used</h2>
 * <ul>
 *   <li>JEP 511 module imports ({@code import module java.base;}).</li>
 *   <li>JEP 513 flexible constructor bodies in {@link Cu02State}.</li>
 *   <li>Sealed interfaces ({@link UpdateState}, {@link Outcome}) with
 *       compiler-enforced exhaustiveness (no {@code default} branch).</li>
 *   <li>Pattern-matching {@code switch} over {@link AidKey} and
 *       {@link UpdateState}.</li>
 *   <li>Records for {@link Outcome.SendMap}, {@link Outcome.Xctl}, and
 *       the internal {@link Cu02State}.</li>
 * </ul>
 *
 * @see CoUsr02Input  the BMS input DTO
 * @see CoUsr02Output the BMS output DTO
 * @see SecUserData   the 80-byte USRSEC row record
 * @see UserSecurityRepository the USRSEC port
 */
@CobolProgram(
        value = "COUSR02C",
        sourcePath = "app/cbl/COUSR02C.cbl",
        translationDate = "2025-01-15",
        notes = "Update user online; transaction id CU02. Two-phase flow: (1) fetch by user-id "
              + "(ENTER), (2) update fields and save (PF5 or PF3). READ UPDATE + REWRITE to USRSEC. "
              + "Auto-populate USRIDINI from CDEMO-CU02-USR-SELECTED when entered from COUSR00C. "
              + "Plaintext password preserved per AAP §0.1.3 — see MIGRATION_NOTES.md for BCrypt follow-up. "
              + "Keys: ENTER=Fetch, PF3=Save&Exit, PF4=Clear, PF5=Save, PF12=Cancel→COADM01C."
)
public final class CoUsr02C {

    // -----------------------------------------------------------------------
    // Class identity constants (translated from WS-LITERALS in COUSR02C.cbl)
    // -----------------------------------------------------------------------

    /** COBOL PROGRAM-ID for this class &mdash; literally {@code "COUSR02C"}. */
    public static final String LIT_THIS_PGM = "COUSR02C";

    /** CICS TRANID for this class &mdash; literally {@code "CU02"}. */
    public static final String LIT_THIS_TRAN_ID = "CU02";

    /** BMS mapset name for this class &mdash; literally {@code "COUSR02"}. */
    public static final String LIT_THIS_MAPSET = "COUSR02";

    /** BMS map name within the mapset &mdash; literally {@code "COUSR2A"}. */
    public static final String LIT_THIS_MAP = "COUSR2A";

    /** XCTL target PROGRAM-ID for the admin menu &mdash; {@code "COADM01C"}. */
    public static final String LIT_ADMIN_MENU_PGM = "COADM01C";

    /** XCTL target TRANID for the admin menu &mdash; {@code "CA00"}. */
    public static final String LIT_ADMIN_MENU_TRAN_ID = "CA00";

    /** XCTL target PROGRAM-ID for the user-list program &mdash; {@code "COUSR00C"}. */
    public static final String LIT_USR_LIST_PGM = "COUSR00C";

    /** XCTL target TRANID for the user-list program &mdash; {@code "CU00"}. */
    public static final String LIT_USR_LIST_TRAN_ID = "CU00";

    /** CICS FCT entry name of the USRSEC dataset &mdash; {@code "USRSEC"}. */
    public static final String LIT_USRSEC_FILE = "USRSEC";

    // -----------------------------------------------------------------------
    // Field-length constants (translated from BMS PIC widths & SEC-USER-DATA)
    // -----------------------------------------------------------------------

    /** Length of SEC-USR-ID / USRIDINI BMS field &mdash; COBOL PIC X(08). */
    public static final int USR_ID_LENGTH = 8;

    /** Length of SEC-USR-FNAME / FNAMEI BMS field &mdash; COBOL PIC X(20). */
    public static final int USR_FNAME_LENGTH = 20;

    /** Length of SEC-USR-LNAME / LNAMEI BMS field &mdash; COBOL PIC X(20). */
    public static final int USR_LNAME_LENGTH = 20;

    /** Length of SEC-USR-PWD / PASSWDI BMS field &mdash; COBOL PIC X(08). */
    public static final int USR_PWD_LENGTH = 8;

    // -----------------------------------------------------------------------
    // Message constants (verbatim per AAP §0.7.1)
    // -----------------------------------------------------------------------

    /** "User ID can NOT be empty..." */
    private static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

    /** "First Name can NOT be empty..." */
    private static final String MSG_FNAME_EMPTY = "First Name can NOT be empty...";

    /** "Last Name can NOT be empty..." */
    private static final String MSG_LNAME_EMPTY = "Last Name can NOT be empty...";

    /** "Password can NOT be empty..." */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** "User Type can NOT be empty..." */
    private static final String MSG_USRTYPE_EMPTY = "User Type can NOT be empty...";

    /** "User Type must be 'A' (Admin) or 'U' (User)" */
    private static final String MSG_USRTYPE_INVALID = "User Type must be 'A' (Admin) or 'U' (User)";

    /** "User ID NOT found..." */
    private static final String MSG_NOT_FOUND = "User ID NOT found...";

    /** "Unable to lookup User..." */
    private static final String MSG_LOOKUP_ERROR = "Unable to lookup User...";

    /**
     * "Please modify to update ..." (verbatim from COBOL
     * {@code app/cbl/COUSR02C.cbl:L240}, paragraph
     * {@code PROCESS-ENTER-KEY}). Note the SINGLE space before the
     * trailing {@code "..."} ellipsis &mdash; preserved byte-for-byte
     * per AAP &sect;0.1.3.
     */
    private static final String MSG_NO_MODIFICATION = "Please modify to update ...";

    /** "Unable to Update User..." */
    private static final String MSG_UPDATE_ERROR = "Unable to Update User...";

    /** "Invalid key pressed. Press valid key." */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Press valid key.";

    /** "Screen cleared" (PF4 path acknowledgement). */
    private static final String MSG_SCREEN_CLEARED = "Screen cleared";

    // -----------------------------------------------------------------------
    // Screen header constants (from COTTL01Y.cpy)
    // -----------------------------------------------------------------------

    /**
     * COBOL CCDA-TITLE01 from {@code app/cpy/COTTL01Y.cpy} &mdash; 40 chars
     * matching TITLE01O PIC X(40); preserved verbatim including leading and
     * trailing spaces.
     */
    private static final String CCDA_TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * COBOL CCDA-TITLE02 from {@code app/cpy/COTTL01Y.cpy} &mdash; 40 chars
     * matching TITLE02O PIC X(40); preserved verbatim including leading and
     * trailing spaces.
     */
    private static final String CCDA_TITLE02 = "              CardDemo                  ";

    /** Screen header sub-title for COUSR02C (PGMNAMEO field). */
    private static final String SCREEN_SUBTITLE = "Update User";

    /** Date format pattern for CURDATEO PIC X(8): {@code MM/dd/yy}. */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MM/dd/yy");

    /** Time format pattern for CURTIMEO PIC X(8): {@code HH:mm:ss}. */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** SLF4J logger; never receives the plaintext password (AAP §0.1.3, §0.7.4). */
    private static final Logger log = LoggerFactory.getLogger(CoUsr02C.class);

    // -----------------------------------------------------------------------
    // AidKey enum
    // -----------------------------------------------------------------------

    /**
     * AID-key dispatch alias for the COUSR02C transaction.
     *
     * <p>Translates the {@code EVALUATE EIBAID} block in
     * {@code app/cbl/COUSR02C.cbl} (MAIN-PARA paragraph). This enum is
     * declared at the {@code CoUsr02C} level (rather than reused from
     * {@link CoUsr02Input#aidKey()}) so that the public API of the use
     * case exposes a stable contract regardless of how the BMS input DTO
     * encodes the AID key &mdash; for example, the equivalent enum on
     * {@code CoUsr02Input} uses the legacy name {@code PF03_SAVE_BACK}
     * while this enum uses the schema-mandated name
     * {@link #PF03_SAVE_AND_EXIT}.
     */
    public enum AidKey {
        /** {@code DFHENTER}: fetch (state=NotFetched) or save (state=Fetched/Modified). */
        ENTER,
        /** {@code DFHPF3}: save and XCTL to admin menu. */
        PF03_SAVE_AND_EXIT,
        /** {@code DFHPF4}: clear screen and reset state to NotFetched. */
        PF04_CLEAR,
        /** {@code DFHPF5}: save changes (stay on screen). */
        PF05_SAVE,
        /** {@code DFHPF12}: discard changes and XCTL to admin menu. */
        PF12_CANCEL,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid key error). */
        OTHER
    }

    // -----------------------------------------------------------------------
    // UpdateState sealed interface
    // -----------------------------------------------------------------------

    /**
     * Update workflow state for COUSR02C.
     *
     * <p>Translates the implicit fetch-then-modify state machine in
     * {@code app/cbl/COUSR02C.cbl}:
     * <ul>
     *   <li>{@link NotFetched} &mdash; user has not yet entered a user id,
     *       or the entered id was not found</li>
     *   <li>{@link Fetched} &mdash; user id has been looked up and the
     *       record displayed; no edits yet</li>
     *   <li>{@link Modified} &mdash; user has edited one or more fields
     *       after the fetch</li>
     *   <li>{@link Saved} &mdash; REWRITE succeeded; show confirmation</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.1.3 closed taxonomies are sealed; switch over
     * {@code UpdateState} MUST be exhaustive with NO {@code default} branch.
     *
     * <p>Each permit exposes a one-character {@link #indicator()} byte used
     * by the internal {@link Cu02State} codec to round-trip the state
     * through the {@code progSpecificBytes} byte buffer.
     */
    public sealed interface UpdateState
            permits UpdateState.NotFetched, UpdateState.Fetched,
                    UpdateState.Modified, UpdateState.Saved {

        /**
         * Returns the single-character serialized indicator for this state.
         *
         * @return {@code 'N'} for NotFetched, {@code 'F'} for Fetched,
         *         {@code 'M'} for Modified, {@code 'S'} for Saved
         */
        char indicator();

        /** Initial state: user-id has not yet been looked up. */
        record NotFetched() implements UpdateState {
            /** Canonical singleton instance &mdash; this record has no state. */
            public static final NotFetched INSTANCE = new NotFetched();
            @Override public char indicator() { return 'N'; }
        }

        /** User-id has been looked up and the record displayed; no edits yet. */
        record Fetched() implements UpdateState {
            /** Canonical singleton instance &mdash; this record has no state. */
            public static final Fetched INSTANCE = new Fetched();
            @Override public char indicator() { return 'F'; }
        }

        /** User has edited one or more fields after the fetch. */
        record Modified() implements UpdateState {
            /** Canonical singleton instance &mdash; this record has no state. */
            public static final Modified INSTANCE = new Modified();
            @Override public char indicator() { return 'M'; }
        }

        /** REWRITE succeeded; the success confirmation is rendered. */
        record Saved() implements UpdateState {
            /** Canonical singleton instance &mdash; this record has no state. */
            public static final Saved INSTANCE = new Saved();
            @Override public char indicator() { return 'S'; }
        }

        /**
         * Decodes a single-character indicator (case-insensitive) back into
         * the corresponding {@code UpdateState} permit. Unknown characters
         * (including the zero byte that arises from an uninitialized
         * progSpecificBytes buffer) resolve to {@link NotFetched#INSTANCE}.
         *
         * @param c the indicator byte (typically {@code 'N'}, {@code 'F'},
         *          {@code 'M'} or {@code 'S'})
         * @return the matching {@code UpdateState} singleton; never
         *         {@code null}
         */
        static UpdateState fromIndicator(char c) {
            return switch (Character.toUpperCase(c)) {
                case 'F' -> Fetched.INSTANCE;
                case 'M' -> Modified.INSTANCE;
                case 'S' -> Saved.INSTANCE;
                default  -> NotFetched.INSTANCE;
            };
        }
    }

    // -----------------------------------------------------------------------
    // Outcome sealed interface
    // -----------------------------------------------------------------------

    /**
     * Result of a {@link #execute(CardDemoCommarea, byte[], AidKey,
     * CoUsr02Input)} invocation. Exactly one of the two permits is
     * returned per call.
     *
     * <p>Mirrors the CICS dispatch dichotomy in
     * {@code app/cbl/COUSR02C.cbl}:
     * <ul>
     *   <li>{@code EXEC CICS SEND MAP(...) MAPSET(...) ... RETURN}
     *       &rarr; {@link SendMap} carrying the populated {@link CoUsr02Output}
     *       to be sent to the 3270 terminal and the outbound commarea.</li>
     *   <li>{@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)}
     *       &rarr; {@link Xctl} carrying the target program id and the
     *       outbound commarea.</li>
     * </ul>
     *
     * <p>Both permits also carry the program-specific bytes
     * (see {@link Cu02State}) which the dispatch layer threads back into
     * the next call to preserve fetch-then-modify state across
     * RECEIVE-MAP / SEND-MAP cycles.
     *
     * <p>Per AAP &sect;0.7.4 switch over {@code Outcome} must be exhaustive
     * with NO {@code default} branch.
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * Outcome corresponding to an {@code EXEC CICS SEND MAP} followed
         * by {@code EXEC CICS RETURN TRANSID(LIT-THIS-TRAN-ID) COMMAREA(...)}.
         *
         * @param output            the populated BMS output DTO; non-null
         * @param commarea          the outbound commarea (FROM-TRANID /
         *                          FROM-PROGRAM / LAST-MAP / LAST-MAPSET
         *                          already set); non-null
         * @param progSpecificBytes the encoded program-specific state to
         *                          thread back through the next call;
         *                          non-null (may be empty)
         */
        record SendMap(CoUsr02Output output,
                       CardDemoCommarea commarea,
                       byte[] progSpecificBytes) implements Outcome { }

        /**
         * Outcome corresponding to an {@code EXEC CICS XCTL PROGRAM(...)
         * COMMAREA(...)}.
         *
         * @param targetProgram     the target COBOL PROGRAM-ID; non-null
         * @param commarea          the outbound commarea (TO-TRANID /
         *                          TO-PROGRAM / FROM-TRANID / FROM-PROGRAM
         *                          set); non-null
         * @param progSpecificBytes the encoded program-specific state;
         *                          non-null (typically empty after XCTL
         *                          since the target program has its own
         *                          state contract)
         */
        record Xctl(String targetProgram,
                    CardDemoCommarea commarea,
                    byte[] progSpecificBytes) implements Outcome { }
    }

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    /**
     * USRSEC repository port &mdash; translates VSAM file access
     * (READ / READ UPDATE / REWRITE) for the
     * {@code app/cbl/COUSR02C.cbl} program. Injected at construction
     * time; never re-bound at runtime.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Program registry for dynamic CALL dispatch. Held for consistency
     * with the canonical constructor signature shared by every translated
     * PROGRAM-ID (matching {@code CoUsr01C} / {@code CoUsr03C}); the
     * COUSR02C source uses only static XCTL targets (COADM01C, COUSR00C,
     * COSGN00C) so this field is reserved for future expansion.
     */
    @SuppressWarnings("unused")
    private final ProgramRegistry programRegistry;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@code CoUsr02C} bound to the supplied repository port
     * and program registry. Both arguments are required (no nullable
     * defaults).
     *
     * @param userSecurityRepository the USRSEC port; must be non-null
     * @param programRegistry        the program registry; must be non-null
     * @throws NullPointerException if either argument is {@code null}
     */
    public CoUsr02C(UserSecurityRepository userSecurityRepository,
                    ProgramRegistry programRegistry) {
        this.userSecurityRepository =
                Objects.requireNonNull(userSecurityRepository, "userSecurityRepository");
        this.programRegistry =
                Objects.requireNonNull(programRegistry, "programRegistry");
    }

    // -----------------------------------------------------------------------
    // Public entry method
    // -----------------------------------------------------------------------

    /**
     * Entry point translating COBOL paragraph {@code 0000-MAIN}.
     *
     * <p>Dispatch sequence:
     * <ol>
     *   <li>Materialize the inbound commarea (constructing
     *       {@link CardDemoCommarea#empty()} if {@code commareaIn} is
     *       {@code null}, mirroring COBOL's
     *       {@code IF EIBCALEN = 0 ... PERFORM SEND-USRUPD-SCREEN}).</li>
     *   <li>Decode the program-specific state via
     *       {@link Cu02State#decode(byte[])}, recovering both the
     *       pre-edit USRSEC snapshot and the prior {@link UpdateState}
     *       indicator.</li>
     *   <li>Detect the "first entry from COUSR00C" case: when
     *       {@code commareaIn != null} (so this is a re-entry from
     *       another program), the cached pre-edit user is empty (we have
     *       never fetched yet), and the selected user id is populated,
     *       short-circuit into an automatic fetch.</li>
     *   <li>Pattern-match on the AID key to dispatch to the appropriate
     *       state-handler method.</li>
     * </ol>
     *
     * <p>The 1300-CHECK-CHANGE-IN-REC paragraph's change-detection
     * comparison is implemented inside {@link #processInputs} by
     * comparing the post-edit fields against
     * {@link Cu02State#preEditUser()}.
     *
     * @param commareaIn        inbound commarea; may be {@code null} on
     *                          first-time entry ({@code EIBCALEN = 0})
     * @param progSpecificBytes encoded program-specific state from the
     *                          previous SEND-MAP cycle, or {@code null} /
     *                          empty for a fresh entry
     * @param aidKey            the AID key captured at RECEIVE-MAP time;
     *                          non-null
     * @param input             the input DTO from RECEIVE-MAP; non-null
     * @return a {@link Outcome.SendMap} or {@link Outcome.Xctl} carrying
     *         the next dispatch action; never {@code null}
     * @throws NullPointerException if {@code aidKey} or {@code input} is
     *                              {@code null}
     */
    public Outcome execute(CardDemoCommarea commareaIn,
                           byte[] progSpecificBytes,
                           AidKey aidKey,
                           CoUsr02Input input) {
        Objects.requireNonNull(aidKey, "aidKey");
        Objects.requireNonNull(input, "input");

        CardDemoCommarea commarea = (commareaIn == null)
                ? CardDemoCommarea.empty()
                : commareaIn;
        // Always stamp FROM-TRANID / FROM-PROGRAM on the outbound commarea so the
        // CICS XCTL chain can trace the caller path; translates the COBOL idiom
        // of MOVE WS-TRANID TO CDEMO-FROM-TRANID at the top of MAIN-PARA.
        commarea = stampFromIdentity(commarea);

        Cu02State state = Cu02State.decode(progSpecificBytes);

        // First entry from COUSR00C with a pre-selected user-id and no input
        // yet typed: emulate the COBOL auto-fetch that COUSR00C triggers when
        // it XCTLs to COUSR02C with CDEMO-CU02-USR-SELECTED populated.
        if (commareaIn != null
                && state.preEditUser() == null
                && !state.selectedUserId().isEmpty()
                && trim(input.userId()).isEmpty()) {
            CoUsr02Input seeded = input.withUserId(state.selectedUserId());
            return fetchUser(commarea, state.selectedUserId(), seeded);
        }

        return switch (aidKey) {
            case ENTER ->
                    processInputs(commarea, state, input, /* saveAndExit */ false);
            case PF03_SAVE_AND_EXIT ->
                    processInputs(commarea, state, input, /* saveAndExit */ true);
            case PF04_CLEAR ->
                    buildSendMap(commarea,
                            Cu02State.initial(state.selectedUserId()),
                            CoUsr02Input.blank(),
                            MSG_SCREEN_CLEARED,
                            /* greenMessage */ false,
                            "USERID");
            case PF05_SAVE ->
                    processInputs(commarea, state, input, /* saveAndExit */ false);
            case PF12_CANCEL ->
                    returnToAdminMenu(commarea);
            case OTHER ->
                    buildSendMap(commarea, state, input, MSG_INVALID_KEY,
                            /* greenMessage */ false, null);
        };
    }

    // -----------------------------------------------------------------------
    // 1000-PROCESS-INPUTS (state-machine driven)
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code 1000-PROCESS-INPUTS} together with
     * its nested {@code 1200-EDIT-MAP-INPUTS} validation and
     * {@code 1300-CHECK-CHANGE-IN-REC} diff-detection.
     *
     * <p>The state machine has two phases:
     * <ul>
     *   <li><b>{@link UpdateState.NotFetched}</b>: only the user-id is
     *       inspected. If empty, emit MSG_USERID_EMPTY and stay on the
     *       screen. Otherwise call {@link #fetchUser} to populate.</li>
     *   <li><b>{@link UpdateState.Fetched} / {@link UpdateState.Modified}
     *       / {@link UpdateState.Saved}</b>: validate every field for
     *       non-emptiness in fixed COBOL order (FNAME &rarr; LNAME &rarr;
     *       PASSWD &rarr; USRTYPE), then validate USRTYPE is 'A' or 'U',
     *       then compare the post-edit fields against the cached
     *       pre-edit snapshot. If no change &rarr; MSG_NO_MODIFICATION.
     *       Otherwise REWRITE and either XCTL (saveAndExit=true) or
     *       render the green confirmation (saveAndExit=false).</li>
     * </ul>
     *
     * @param commarea     the outbound commarea
     * @param state        the current program-specific state
     * @param input        the post-RECEIVE-MAP input DTO
     * @param saveAndExit  {@code true} for PF3, {@code false} for ENTER/PF5
     * @return the next dispatch outcome; never {@code null}
     */
    private Outcome processInputs(CardDemoCommarea commarea,
                                  Cu02State state,
                                  CoUsr02Input input,
                                  boolean saveAndExit) {
        String userIdEntered = trim(input.userId());

        // Phase 1: NotFetched - just attempt the lookup.
        if (state.state() instanceof UpdateState.NotFetched) {
            if (userIdEntered.isEmpty()) {
                return buildSendMap(commarea, state, input,
                        MSG_USERID_EMPTY, /* greenMessage */ false, "USERID");
            }
            return fetchUser(commarea, userIdEntered, input);
        }

        // Phase 2: Fetched / Modified / Saved - validate and save.
        String firstName  = trim(input.firstName());
        String lastName   = trim(input.lastName());
        String password   = trim(input.password());
        String userTypeIn = trim(input.userType());

        if (userIdEntered.isEmpty()) {
            return buildSendMap(commarea, state, input,
                    MSG_USERID_EMPTY, /* greenMessage */ false, "USERID");
        }
        if (firstName.isEmpty()) {
            return buildSendMap(commarea, state, input,
                    MSG_FNAME_EMPTY, /* greenMessage */ false, "FNAME");
        }
        if (lastName.isEmpty()) {
            return buildSendMap(commarea, state, input,
                    MSG_LNAME_EMPTY, /* greenMessage */ false, "LNAME");
        }
        if (password.isEmpty()) {
            return buildSendMap(commarea, state, input,
                    MSG_PASSWORD_EMPTY, /* greenMessage */ false, "PASSWD");
        }
        if (userTypeIn.isEmpty()) {
            return buildSendMap(commarea, state, input,
                    MSG_USRTYPE_EMPTY, /* greenMessage */ false, "USRTYPE");
        }
        char userTypeChar = Character.toUpperCase(userTypeIn.charAt(0));
        if (userTypeChar != 'A' && userTypeChar != 'U') {
            return buildSendMap(commarea, state, input,
                    MSG_USRTYPE_INVALID, /* greenMessage */ false, "USRTYPE");
        }

        // Pre-edit snapshot must exist before we can compare/REWRITE. If
        // somehow we are in a non-NotFetched state without a snapshot
        // (e.g. corrupted progSpecificBytes), fall back to a re-fetch.
        SecUserData preEdit = state.preEditUser();
        if (preEdit == null) {
            return fetchUser(commarea, userIdEntered, input);
        }

        // Build the updated record by overlaying the post-edit fields onto
        // the pre-edit record (we MUST preserve secUsrFiller exactly &mdash;
        // the COBOL REWRITE preserves it because the FILE record retains
        // the bytes outside the named fields).
        SecUserData updated;
        try {
            updated = new SecUserData(
                    preEdit.secUsrId(),
                    firstName,
                    lastName,
                    password,
                    userTypeChar,
                    preEdit.secUsrFiller());
        } catch (IllegalArgumentException oversize) {
            // Defensive: BMS input has already been clamped by
            // CoUsr02Input's compact constructor, but if a caller bypassed
            // that contract we surface a generic update error rather than
            // leaking the underlying IAE message (which could contain the
            // field value).
            log.warn("CoUsr02C: oversize update for userId={}", userIdEntered);
            return buildSendMap(commarea, state, input,
                    MSG_UPDATE_ERROR, /* greenMessage */ false, null);
        }

        // 1300-CHECK-CHANGE-IN-REC: detect any modification.
        boolean modified = !sameFields(preEdit, updated);
        if (!modified) {
            return buildSendMap(commarea, state, input,
                    MSG_NO_MODIFICATION, /* greenMessage */ false, null);
        }

        // 9100-UPDATE-USER-SEC-FILE: READ UPDATE + REWRITE.
        try {
            userSecurityRepository.update(updated);
        } catch (NoSuchElementException notFound) {
            // DFHRESP(NOTFND) on the REWRITE.
            log.info("CoUsr02C: USRSEC REWRITE NOTFND for userId={}", userIdEntered);
            Cu02State resetState = Cu02State.initial(userIdEntered);
            return buildSendMap(commarea, resetState, CoUsr02Input.blank(),
                    MSG_NOT_FOUND, /* greenMessage */ false, "USERID");
        } catch (RuntimeException unexpected) {
            log.error("CoUsr02C: unable to update user '{}'", userIdEntered, unexpected);
            return buildSendMap(commarea, state, input,
                    MSG_UPDATE_ERROR, /* greenMessage */ false, null);
        }

        if (saveAndExit) {
            return returnToAdminMenu(commarea);
        }

        // PF5 / ENTER: render green confirmation and remain on the screen.
        Cu02State savedState = state
                .withPreEditUser(updated)
                .withState(UpdateState.Saved.INSTANCE);
        String successMsg = "User " + userIdEntered + " has been updated...";
        return buildSendMap(commarea, savedState,
                CoUsr02Input.fromSecUserData(updated),
                successMsg, /* greenMessage */ true, null);
    }


    // -----------------------------------------------------------------------
    // 9000-READ-USER-SEC-FILE
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code 9000-READ-USER-SEC-FILE}: reads
     * the USRSEC record by primary key and either populates the screen
     * or emits {@code "User ID NOT found..."} / {@code "Unable to lookup
     * User..."}.
     *
     * <p>On success the state advances from {@link UpdateState.NotFetched}
     * to {@link UpdateState.Fetched} and the pre-edit snapshot is cached
     * in {@link Cu02State#preEditUser()} so the subsequent
     * 1300-CHECK-CHANGE-IN-REC paragraph can compare field-by-field.
     *
     * @param commarea the outbound commarea
     * @param userId   the trimmed user-id to look up; must be non-null
     *                 and non-empty (caller's responsibility)
     * @param input    the current input DTO (used to preserve any typing
     *                 if the lookup fails)
     * @return the next dispatch outcome; never {@code null}
     */
    private Outcome fetchUser(CardDemoCommarea commarea,
                              String userId,
                              CoUsr02Input input) {
        Optional<SecUserData> fetched;
        try {
            fetched = userSecurityRepository.findById(userId);
        } catch (RuntimeException unexpected) {
            log.error("CoUsr02C: unable to fetch user '{}'", userId, unexpected);
            Cu02State errState = Cu02State.initial(userId);
            return buildSendMap(commarea, errState, input,
                    MSG_LOOKUP_ERROR, /* greenMessage */ false, "USERID");
        }
        if (fetched.isEmpty()) {
            // 1100-RECEIVE-MAP NOTFND path: clear the edit fields and
            // re-render with the lookup-failed message.
            Cu02State notFoundState = Cu02State.initial(userId);
            CoUsr02Input redisplay = CoUsr02Input.blank().withUserId(userId);
            return buildSendMap(commarea, notFoundState, redisplay,
                    MSG_NOT_FOUND, /* greenMessage */ false, "USERID");
        }
        SecUserData user = fetched.get();
        Cu02State fetchedState =
                new Cu02State(userId, user, UpdateState.Fetched.INSTANCE);
        return buildSendMap(commarea, fetchedState,
                CoUsr02Input.fromSecUserData(user),
                null, /* greenMessage */ false, "FNAME");
    }

    // -----------------------------------------------------------------------
    // 1300-CHECK-CHANGE-IN-REC
    // -----------------------------------------------------------------------

    /**
     * Field-by-field equality check between the pre-edit USRSEC snapshot
     * (cached in {@link Cu02State#preEditUser()}) and the post-edit
     * record built from the BMS input. Translates COBOL paragraph
     * {@code 1300-CHECK-CHANGE-IN-REC}.
     *
     * <p>Note: SEC-USR-ID is intentionally NOT compared &mdash; the
     * user-id is the primary key and cannot change within a single
     * fetch-then-update transaction. The compared fields are FNAME,
     * LNAME, PWD, TYPE (exactly mirroring the COBOL paragraph).
     *
     * <p><b>Padding-aware comparison</b>: COBOL compares two {@code PIC
     * X(n)} fields with their trailing-space padding included. After
     * {@code MOVE FNAMEI OF COUSR2AI TO SEC-USR-FNAME}, both operands
     * are exactly {@code n} bytes wide, so {@code "Alice"} (with
     * 15 trailing spaces in a PIC X(20)) compares equal to
     * {@code "Alice"} (with 15 trailing spaces). In Java the pre-edit
     * snapshot may carry the space-padded form (as parsed from the
     * fixed-width storage) while the post-edit value is the trimmed
     * BMS-input form &mdash; the two are semantically equal in COBOL
     * so this method compares them after trimming trailing whitespace.
     *
     * @param pre the pre-edit USRSEC snapshot; if {@code null}, treated
     *            as "no snapshot" and the method returns {@code false}
     *            (i.e. the records are NOT equal so an update is required)
     * @param post the post-edit record built from the BMS input; if
     *             {@code null}, treated as "no input" and the method
     *             returns {@code false}
     * @return {@code true} iff every compared field (FNAME, LNAME, PWD,
     *         TYPE) of {@code pre} and {@code post} is identical after
     *         COBOL-style trailing-space normalization
     */
    private static boolean sameFields(SecUserData pre, SecUserData post) {
        if (pre == null || post == null) {
            return false;
        }
        return trimEqual(pre.secUsrFname(), post.secUsrFname())
                && trimEqual(pre.secUsrLname(), post.secUsrLname())
                && trimEqual(pre.secUsrPwd(),   post.secUsrPwd())
                && pre.secUsrType() == post.secUsrType();
    }

    /**
     * COBOL-style trailing-space-tolerant equality. Compares the two
     * strings after stripping trailing whitespace from each (an empty
     * string is treated as equivalent to a string of spaces). {@code null}
     * is treated as an empty string.
     *
     * @param a first string (may be {@code null})
     * @param b second string (may be {@code null})
     * @return {@code true} iff both strings are equal after trailing-space
     *         normalization
     */
    private static boolean trimEqual(String a, String b) {
        String sa = (a == null) ? "" : a;
        String sb = (b == null) ? "" : b;
        return stripTrailingSpaces(sa).equals(stripTrailingSpaces(sb));
    }

    /**
     * Strips trailing ASCII spaces (and other whitespace) from a string.
     * Centralizes the COBOL PIC X(n) trailing-space convention used by
     * {@link #trimEqual(String, String)}.
     *
     * @param s the input string (non-null)
     * @return the input with trailing whitespace removed
     */
    private static String stripTrailingSpaces(String s) {
        return s.stripTrailing();
    }

    // -----------------------------------------------------------------------
    // SEND-USRUPD-SCREEN + POPULATE-HEADER-INFO
    // -----------------------------------------------------------------------

    /**
     * Constructs an {@link Outcome.SendMap} carrying the populated
     * {@link CoUsr02Output} and the outbound commarea. Translates COBOL
     * paragraphs {@code SEND-USRUPD-SCREEN} and
     * {@code POPULATE-HEADER-INFO} (header fields TRNNAME, TITLE01,
     * CURDATE, PGMNAME, TITLE02, CURTIME).
     *
     * <p>The {@code greenMessage} flag drives the ERRMSG color attribute
     * (true=GREEN for success confirmations, false=RED for validation
     * / error messages); the BMS attribute mapping is the
     * adapter's responsibility &mdash; this method merely passes the
     * boolean through.
     *
     * <p>The {@code focusField} parameter positions the BMS IC (insert
     * cursor) attribute on the named field. When {@code null} or empty,
     * no cursor positioning is requested and the terminal falls back to
     * the field with the {@code IC} attribute from the BMS map
     * definition.
     *
     * @param commarea     the outbound commarea
     * @param state        the program-specific state to encode into
     *                     {@code progSpecificBytes}
     * @param current      the current input DTO (drives the editable
     *                     output fields USERID / FNAME / LNAME / PASSWD
     *                     / USRTYPE)
     * @param message      the ERRMSG body, or {@code null} for no message
     * @param greenMessage {@code true} to render in GREEN (success);
     *                     {@code false} to render in RED (error)
     * @param focusField   BMS field name to receive cursor focus, or
     *                     {@code null} / empty for default positioning
     * @return an {@link Outcome.SendMap} carrying the rendered output
     *         and the outbound commarea; never {@code null}
     */
    private Outcome buildSendMap(CardDemoCommarea commarea,
                                 Cu02State state,
                                 CoUsr02Input current,
                                 String message,
                                 boolean greenMessage,
                                 String focusField) {
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();

        CoUsr02Output output = new CoUsr02Output(
                LIT_THIS_TRAN_ID,
                CCDA_TITLE01,
                today.format(DATE_FORMATTER),
                LIT_THIS_PGM,
                CCDA_TITLE02,
                now.format(TIME_FORMATTER),
                current.userId(),
                current.firstName(),
                current.lastName(),
                current.password(),
                current.userType(),
                (message == null) ? "" : message,
                greenMessage,
                (focusField == null) ? "" : focusField);

        // Stamp the last-rendered map/mapset onto the outbound commarea so
        // the next-program inspector can correlate dispatch.
        CardDemoCommarea outbound = withLastMapInfo(commarea,
                LIT_THIS_MAP, LIT_THIS_MAPSET);

        return new Outcome.SendMap(output, outbound, state.encode());
    }

    // -----------------------------------------------------------------------
    // RETURN-TO-PREV-SCREEN (PF12 cancel, PF3 save-and-exit)
    // -----------------------------------------------------------------------

    /**
     * Builds the XCTL outcome to {@link #LIT_ADMIN_MENU_PGM}. Translates
     * COBOL paragraph {@code RETURN-TO-PREV-SCREEN} for both the PF12
     * cancel path and the PF3 successful-save-and-exit path.
     *
     * <p>The outbound commarea has TO-TRANID / TO-PROGRAM stamped to
     * {@code "CA00"} / {@code "COADM01C"} and the program context reset
     * to {@link PgmContext#ENTER} so the admin menu starts in its
     * first-time-entry state.
     *
     * @param commareaIn the inbound commarea (whose FROM-* / USER-ID /
     *                   USER-TYPE fields are preserved)
     * @return an {@link Outcome.Xctl} pointing to COADM01C; never
     *         {@code null}
     */
    private Outcome returnToAdminMenu(CardDemoCommarea commareaIn) {
        CardDemoCommarea.CdemoGeneralInfo gi = commareaIn.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID,
                        CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM,
                        CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padRight(LIT_ADMIN_MENU_TRAN_ID,
                        CardDemoCommarea.LENGTH_TO_TRANID),
                padRight(LIT_ADMIN_MENU_PGM,
                        CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commareaIn.withCdemoGeneralInfo(updated);
        return new Outcome.Xctl(LIT_ADMIN_MENU_PGM, outbound, new byte[0]);
    }

    // -----------------------------------------------------------------------
    // 9999-ABEND-PROGRAM
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code 9999-ABEND-PROGRAM}.
     * Logs the abend reason at ERROR level and throws a
     * {@link RuntimeException} carrying the reason and (optional)
     * underlying cause. Per AAP &sect;0.1.3 the plaintext password is
     * never included in the {@code reason} parameter and never logged.
     *
     * @param reason the human-readable reason for the abend
     * @param cause  the underlying exception, or {@code null} if the
     *               abend is a programmatic decision (e.g. unrecoverable
     *               validation failure)
     * @return this method never returns normally; the declared return
     *         type is {@code int} purely so that the call site can use
     *         {@code return abendRoutine(...)} as a never-returning
     *         expression
     * @throws RuntimeException always &mdash; the wrapped abend
     */
    @SuppressWarnings("SameReturnValue")
    private int abendRoutine(String reason, Throwable cause) {
        log.error("CICS abend in COUSR02C: {}", reason, cause);
        throw new RuntimeException("COUSR02C abend: " + reason, cause);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Stamps the outbound commarea with FROM-TRANID / FROM-PROGRAM
     * pointing at this program. Translates the COBOL idiom at the top
     * of MAIN-PARA:
     * <pre>
     *     MOVE WS-TRANID  TO CDEMO-FROM-TRANID
     *     MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
     * </pre>
     *
     * @param commarea the inbound commarea
     * @return a new commarea with FROM-TRANID / FROM-PROGRAM stamped;
     *         never {@code null}
     */
    private static CardDemoCommarea stampFromIdentity(CardDemoCommarea commarea) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM,     CardDemoCommarea.LENGTH_FROM_PROGRAM),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                gi.pgmContext());
        return commarea.withCdemoGeneralInfo(updated);
    }

    /**
     * Stamps the outbound commarea with the last-rendered MAP / MAPSET
     * names so the next dispatch step can correlate which screen was
     * shown.
     *
     * @param commarea the inbound commarea
     * @param lastMap    the BMS map name (7 chars before padding)
     * @param lastMapset the BMS mapset name (7 chars before padding)
     * @return a new commarea with MORE-INFO LAST-MAP / LAST-MAPSET set
     */
    private static CardDemoCommarea withLastMapInfo(CardDemoCommarea commarea,
                                                    String lastMap,
                                                    String lastMapset) {
        CardDemoCommarea.CdemoMoreInfo updated = new CardDemoCommarea.CdemoMoreInfo(
                padRight(lastMap,    CardDemoCommarea.LENGTH_LAST_MAP),
                padRight(lastMapset, CardDemoCommarea.LENGTH_LAST_MAPSET));
        return commarea.withCdemoMoreInfo(updated);
    }

    /**
     * Right-pads a string with ASCII spaces to the supplied length, or
     * truncates it if oversize. Used to satisfy {@link CardDemoCommarea}'s
     * fixed-length string contracts when constructing commarea components
     * from values that may have been entered in any length.
     *
     * @param s   the input string; {@code null} is treated as an empty
     *            string and yields a string of {@code length} spaces
     * @param len the target length (non-negative)
     * @return a string of exactly {@code len} characters; never
     *         {@code null}
     */
    private static String padRight(String s, int len) {
        String src = (s == null) ? "" : s;
        if (src.length() == len) {
            return src;
        }
        if (src.length() > len) {
            return src.substring(0, len);
        }
        StringBuilder sb = new StringBuilder(len);
        sb.append(src);
        for (int i = src.length(); i < len; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Trims leading and trailing whitespace, with {@code null} normalized
     * to an empty string. Used to translate the COBOL idiom of comparing
     * BMS input against {@code SPACES} after the space-padding of the
     * receiving PIC X(n) field has been stripped.
     *
     * @param s the input string (may be {@code null})
     * @return a non-null, trimmed string; never {@code null}
     */
    private static String trim(String s) {
        return (s == null) ? "" : s.trim();
    }


    // -----------------------------------------------------------------------
    // Cu02State: program-specific state codec
    // -----------------------------------------------------------------------

    /**
     * Internal record carrying the COUSR02C program-specific state across
     * RECEIVE-MAP / SEND-MAP cycles. Encoded as a fixed-width 89-byte
     * payload threaded back to the caller via
     * {@link Outcome.SendMap#progSpecificBytes()}.
     *
     * <p><b>Binary layout (89 bytes, US-ASCII)</b>:
     * <ul>
     *   <li>Bytes 0&ndash;7: {@code selectedUserId} &mdash; 8 ASCII bytes
     *       (space-padded). Carries CDEMO-CU02-USR-SELECTED when the
     *       program was XCTL'd from COUSR00C with a pre-selected user;
     *       also holds the current update target user-id while in
     *       {@link UpdateState.Fetched} / {@link UpdateState.Modified} /
     *       {@link UpdateState.Saved} states.</li>
     *   <li>Bytes 8&ndash;87: {@code preEditUser} &mdash; 80 ASCII bytes
     *       encoding the pre-edit {@link SecUserData} snapshot via
     *       {@link SecUserData#encode()}. All zero-bytes when no fetch
     *       has occurred yet (state is {@link UpdateState.NotFetched}).</li>
     *   <li>Byte 88: {@link UpdateState#indicator()} byte
     *       (one of {@code 'N'}, {@code 'F'}, {@code 'M'}, {@code 'S'}).</li>
     * </ul>
     *
     * <p>The codec is robust against:
     * <ul>
     *   <li>{@code null} or shorter-than-89-bytes input buffers (treated
     *       as fresh entry &mdash; selectedUserId = {@code ""},
     *       preEditUser = {@code null}, state =
     *       {@link UpdateState.NotFetched#INSTANCE}).</li>
     *   <li>A 80-byte preEditUser slice that fails to parse (treated as
     *       no snapshot &mdash; preEditUser = {@code null}).</li>
     *   <li>Oversize {@code selectedUserId} (truncated to 8 chars).</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.1.3 the encoded preEditUser bytes contain the
     * plaintext password &mdash; the dispatch layer MUST treat the
     * {@code progSpecificBytes} payload as sensitive material (no
     * logging, no persistence).
     *
     * @param selectedUserId the pre-selected or current target user-id;
     *                       trimmed and truncated to 8 chars by the
     *                       compact constructor (null normalized to "")
     * @param preEditUser    the pre-edit USRSEC snapshot for change
     *                       detection; {@code null} when no fetch has
     *                       occurred yet
     * @param state          the current {@link UpdateState}; null
     *                       normalized to {@link UpdateState.NotFetched#INSTANCE}
     *                       by the compact constructor
     */
    private record Cu02State(String selectedUserId,
                             SecUserData preEditUser,
                             UpdateState state) {

        /** Total encoded payload length in bytes. */
        private static final int ENCODED_LENGTH = 89;

        /** Byte offset of the selected user-id slice. */
        private static final int SELECTED_USER_ID_OFFSET = 0;

        /** Byte length of the selected user-id slice. */
        private static final int SELECTED_USER_ID_LENGTH = 8;

        /** Byte offset of the pre-edit user snapshot. */
        private static final int PRE_EDIT_USER_OFFSET = 8;

        /** Byte length of the pre-edit user snapshot. */
        private static final int PRE_EDIT_USER_LENGTH = 80;

        /** Byte offset of the UpdateState indicator. */
        private static final int STATE_INDICATOR_OFFSET = 88;

        /**
         * Canonical constructor with normalization (JEP 513 flexible
         * constructor body):
         * <ul>
         *   <li>{@code null} {@code selectedUserId} normalized to
         *       {@code ""}.</li>
         *   <li>Over-length {@code selectedUserId} truncated to
         *       {@value #SELECTED_USER_ID_LENGTH} chars.</li>
         *   <li>{@code null} {@code state} normalized to
         *       {@link UpdateState.NotFetched#INSTANCE}.</li>
         * </ul>
         */
        Cu02State {
            String sel = (selectedUserId == null) ? "" : selectedUserId;
            if (sel.length() > SELECTED_USER_ID_LENGTH) {
                sel = sel.substring(0, SELECTED_USER_ID_LENGTH);
            }
            selectedUserId = sel;
            if (state == null) {
                state = UpdateState.NotFetched.INSTANCE;
            }
        }

        /**
         * Returns a fresh state instance with the supplied selected
         * user-id, no pre-edit snapshot, and state
         * {@link UpdateState.NotFetched#INSTANCE}. Used by the PF4
         * clear-screen path and the NOTFND-on-fetch path.
         *
         * @param selectedUserId the selected user-id; may be empty or null
         * @return a new {@code Cu02State}; never {@code null}
         */
        static Cu02State initial(String selectedUserId) {
            return new Cu02State(selectedUserId, null,
                    UpdateState.NotFetched.INSTANCE);
        }

        /**
         * Returns a copy with the pre-edit snapshot replaced.
         *
         * @param u the new pre-edit snapshot; may be {@code null}
         * @return a new {@code Cu02State}; never {@code null}
         */
        Cu02State withPreEditUser(SecUserData u) {
            return new Cu02State(this.selectedUserId, u, this.state);
        }

        /**
         * Returns a copy with the {@link UpdateState} replaced.
         *
         * @param s the new state; null normalized to
         *          {@link UpdateState.NotFetched#INSTANCE} by the
         *          compact constructor
         * @return a new {@code Cu02State}; never {@code null}
         */
        Cu02State withState(UpdateState s) {
            return new Cu02State(this.selectedUserId, this.preEditUser, s);
        }

        /**
         * Decodes a {@code progSpecificBytes} payload into a
         * {@code Cu02State}. The codec is robust against {@code null},
         * shorter-than-89-byte, and unparseable SecUserData slices &mdash;
         * see the class-level Javadoc for the failure-mode contract.
         *
         * @param bytes the encoded payload (may be {@code null})
         * @return the decoded state; never {@code null}
         */
        static Cu02State decode(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return new Cu02State("", null, UpdateState.NotFetched.INSTANCE);
            }
            // Selected user-id slice (bytes 0-7), trimmed
            int idEnd = Math.min(SELECTED_USER_ID_LENGTH, bytes.length);
            String selId = new String(bytes, SELECTED_USER_ID_OFFSET, idEnd,
                    StandardCharsets.US_ASCII).trim();

            // Pre-edit user slice (bytes 8-87)
            SecUserData preEdit = null;
            if (bytes.length >= PRE_EDIT_USER_OFFSET + PRE_EDIT_USER_LENGTH) {
                byte[] secBytes = new byte[PRE_EDIT_USER_LENGTH];
                System.arraycopy(bytes, PRE_EDIT_USER_OFFSET,
                        secBytes, 0, PRE_EDIT_USER_LENGTH);
                // Detect "no snapshot yet" by checking all-zero buffer;
                // SecUserData.parse on an all-zero buffer would still
                // return a valid record (all NUL chars), so we must
                // discriminate explicitly.
                if (!isAllZero(secBytes)) {
                    try {
                        preEdit = SecUserData.parse(secBytes);
                    } catch (RuntimeException ignored) {
                        preEdit = null;
                    }
                }
            }

            // UpdateState indicator (byte 88)
            char stateInd = 'N';
            if (bytes.length > STATE_INDICATOR_OFFSET) {
                stateInd = (char) (bytes[STATE_INDICATOR_OFFSET] & 0xFF);
            }
            return new Cu02State(selId, preEdit, UpdateState.fromIndicator(stateInd));
        }

        /**
         * Encodes this state into a fixed-89-byte payload suitable for
         * threading through {@link Outcome.SendMap#progSpecificBytes()}.
         *
         * @return a new byte array of exactly {@value #ENCODED_LENGTH}
         *         bytes; never {@code null}
         */
        byte[] encode() {
            ByteBuffer buf = ByteBuffer.allocate(ENCODED_LENGTH);

            // Selected user-id (8 bytes, space-padded)
            String padded = padRight(selectedUserId, SELECTED_USER_ID_LENGTH);
            buf.put(padded.getBytes(StandardCharsets.US_ASCII));

            // Pre-edit user snapshot (80 bytes)
            byte[] secBytes80 = new byte[PRE_EDIT_USER_LENGTH];
            if (preEditUser != null) {
                byte[] encoded = preEditUser.encode();
                int n = Math.min(encoded.length, PRE_EDIT_USER_LENGTH);
                System.arraycopy(encoded, 0, secBytes80, 0, n);
            }
            buf.put(secBytes80);

            // UpdateState indicator (1 byte)
            buf.put((byte) state.indicator());

            return buf.array();
        }

        /**
         * Returns {@code true} iff every byte of the supplied array is
         * zero. Used by {@link #decode(byte[])} to distinguish an
         * uninitialized 80-byte slice (no fetch yet) from a parsed
         * SecUserData record.
         */
        private static boolean isAllZero(byte[] bytes) {
            for (byte b : bytes) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }
    }
}

