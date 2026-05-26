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
 * Java 25 translation of the COBOL {@code COUSR03C} program
 * ({@code app/cbl/COUSR03C.cbl}) — &ldquo;Delete a user from USRSEC file&rdquo;.
 *
 * <h2>Program purpose</h2>
 * <p>Two-phase delete flow for the USRSEC dataset, surfaced through the
 * COUSR03 BMS map (transaction id {@code CU03}). The operator enters a
 * user id and presses {@code ENTER} to fetch and display the user's
 * first name, last name, and user type for visual confirmation; then
 * presses {@code PF5} to commit the deletion. {@code PF3} returns to the
 * previous program (admin menu by default); {@code PF4} clears the screen;
 * {@code PF12} cancels to the admin menu.
 *
 * <h2>Translation source</h2>
 * <ul>
 *   <li>{@code app/cbl/COUSR03C.cbl}        — main program (&#x223C; 360 LOC)</li>
 *   <li>{@code app/bms/COUSR03.bms}         — BMS map definition</li>
 *   <li>{@code app/cpy-bms/COUSR03.CPY}     — symbolic map copybook</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy}        — CARDDEMO-COMMAREA</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy}        — SEC-USER-DATA layout</li>
 *   <li>{@code app/cpy/COTTL01Y.cpy}        — banner-title constants</li>
 *   <li>{@code app/cpy/CSDAT01Y.cpy}        — date/time work fields</li>
 *   <li>{@code app/cpy/CSMSG01Y.cpy}        — CCDA-MSG-INVALID-KEY</li>
 * </ul>
 *
 * <h2>NO PASSWORD ON SCREEN</h2>
 * <p>The {@code COUSR03} BMS map does NOT contain a {@code PASSWD} field
 * (see {@code app/bms/COUSR03.bms} lines 1-154 — only {@code USRIDIN},
 * {@code FNAME}, {@code LNAME}, {@code USRTYPE} are present). The
 * delete-confirmation flow exposes only the first name, last name, and
 * user type. The {@link SecUserData#secUsrPwd()} component is NEVER read
 * or displayed by this program, and is NEVER logged on any path. This
 * intentional omission aligns with AAP &sect;0.6.7 and &sect;0.7.4
 * (&ldquo;no card PAN logged in full; mask all but last 4 digits in
 * logs&rdquo;) and with the parallel COUSR02C update-screen
 * password-display safety design.
 *
 * <h2>COBOL paragraph &harr; Java method mapping</h2>
 * <table>
 *   <caption>1:1 translation table</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td><td>{@link #execute}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>{@link #returnToPrevScreen}</td></tr>
 *   <tr><td>{@code SEND-USRDEL-SCREEN}</td><td>{@link #buildSendMap}</td></tr>
 *   <tr><td>{@code RECEIVE-USRDEL-SCREEN}</td><td>(via input parameter)</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>{@link #processInputs} &rarr; {@link #fetchUser}</td></tr>
 *   <tr><td>{@code DELETE-USER-INFO}</td><td>{@link #processDelete}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}</td><td>{@link #fetchUser} (via {@link UserSecurityRepository#findById})</td></tr>
 *   <tr><td>{@code DELETE-USER-SEC-FILE}</td><td>{@link #processDelete} (via {@link UserSecurityRepository#delete})</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>(inlined in {@link #buildSendMap})</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td><td>(handled by {@link AidKey#PF04_CLEAR} branch)</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td><td>(handled by {@link CoUsr03Input#blank()})</td></tr>
 * </table>
 *
 * <h2>Messages preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>{@code "User ID can NOT be empty..."}</li>
 *   <li>{@code "Press PF5 key to delete this user ..."}</li>
 *   <li>{@code "User ID NOT found..."}</li>
 *   <li>{@code "Unable to lookup User..."}</li>
 *   <li>{@code "Unable to Update User..."} — verbatim COBOL bug (says
 *       &ldquo;Update&rdquo; in DELETE path; carried verbatim per
 *       AAP &sect;0.7.1 &ldquo;If a COBOL paragraph contains dead code or
 *       obvious bugs, translate it faithfully and flag it in
 *       MIGRATION_NOTES.md; do not 'fix' it in this refactor&rdquo;).</li>
 *   <li>{@code "User <id> has been deleted ..."} — with COBOL
 *       {@code DELIMITED BY SPACE} semantics: only the first
 *       whitespace-delimited token of {@code SEC-USR-ID} is used.</li>
 *   <li>{@code "Invalid key pressed. Please see below..."} —
 *       {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}
 *       line 21.</li>
 * </ul>
 *
 * <h2>Banner titles</h2>
 * <p>{@link #TITLE_01} and {@link #TITLE_02} are private 40-character
 * constants reproducing the exact COBOL {@code CCDA-TITLE01} and
 * {@code CCDA-TITLE02} values from {@code app/cpy/COTTL01Y.cpy}.
 *
 * <h2>{@code CDEMO-CU03-INFO} preselect path</h2>
 * <p>{@code app/cbl/COUSR03C.cbl} lines 50-58 extend the commarea with
 * {@code CDEMO-CU03-INFO} containing {@code CDEMO-CU03-USR-SELECTED}.
 * When the operator navigates from COUSR00C (user list) with a row
 * selected, the COBOL program automatically pre-populates {@code USRIDINI}
 * from {@code CDEMO-CU03-USR-SELECTED} and immediately performs
 * {@code PROCESS-ENTER-KEY} (lines 99-104). The Java translation models
 * this commarea extension as a {@code progSpecificBytes} payload (the
 * 89-byte {@link Cu03State} encoding) carried across pseudo-conversational
 * cycles by the JCL/CICS shell.
 *
 * @see CoUsr03Input
 * @see CoUsr03Output
 * @see UserSecurityRepository
 * @see CardDemoCommarea
 */
@CobolProgram(
        value = "COUSR03C",
        sourcePath = "app/cbl/COUSR03C.cbl",
        translationDate = "2025-01-15",
        notes = "Delete user online; transaction id CU03. Two-phase flow: (1) fetch by user-id "
              + "(ENTER) for display-confirmation, (2) PF5 to DELETE. Read-only display of "
              + "firstName/lastName/userType — password NOT shown. Auto-populate USRIDINI from "
              + "CDEMO-CU03-USR-SELECTED when entered from COUSR00C. "
              + "Keys: ENTER=Fetch, PF3=Back, PF4=Clear, PF5=Delete, PF12=Cancel→COADM01C."
)
public final class CoUsr03C {

    // ========================================================================
    // Class identity constants — translated from WS-LITERALS in COUSR03C.cbl
    // ========================================================================

    /**
     * COBOL {@code WS-PGMNAME} (line 36 of {@code app/cbl/COUSR03C.cbl}).
     * Identifies this program in the commarea {@code CDEMO-FROM-PROGRAM}
     * field on outbound calls.
     */
    public static final String LIT_THIS_PGM = "COUSR03C";

    /**
     * COBOL {@code WS-TRANID} (line 37 of {@code app/cbl/COUSR03C.cbl}).
     * Transaction id under which COUSR03C is invoked; placed in the
     * commarea {@code CDEMO-FROM-TRANID} field on outbound calls.
     */
    public static final String LIT_THIS_TRAN_ID = "CU03";

    /**
     * Identifier of the BMS mapset for this program (matches the
     * {@code MAPSET} clause of {@code EXEC CICS SEND/RECEIVE MAP} on
     * lines 219-238 of {@code app/cbl/COUSR03C.cbl}).
     */
    public static final String LIT_THIS_MAPSET = "COUSR03";

    /**
     * Identifier of the BMS map for this program (matches the {@code MAP}
     * clause of {@code EXEC CICS SEND/RECEIVE MAP} on lines 219-238 of
     * {@code app/cbl/COUSR03C.cbl}).
     */
    public static final String LIT_THIS_MAP = "COUSR3A";

    /**
     * COBOL program-id of the admin menu — {@code COADM01C}. Target for
     * the {@code PF12} cancel path (line 124 of {@code app/cbl/COUSR03C.cbl})
     * and the default {@code PF3} back path when {@code CDEMO-FROM-PROGRAM}
     * is blank (lines 112-117).
     */
    public static final String LIT_ADMIN_MENU_PGM = "COADM01C";

    /**
     * Transaction id under which {@code COADM01C} is invoked
     * ({@code CA00}). Set on the outbound commarea
     * {@code CDEMO-TO-TRANID} when XCTL'ing to the admin menu.
     */
    public static final String LIT_ADMIN_MENU_TRAN_ID = "CA00";

    /**
     * COBOL program-id of the user-list screen — {@code COUSR00C}. Source
     * of the {@code CDEMO-CU03-USR-SELECTED} preselect on the first-time
     * entry path (lines 99-104 of {@code app/cbl/COUSR03C.cbl}).
     */
    public static final String LIT_USR_LIST_PGM = "COUSR00C";

    /**
     * Transaction id under which {@code COUSR00C} is invoked
     * ({@code CU00}).
     */
    public static final String LIT_USR_LIST_TRAN_ID = "CU00";

    /**
     * COBOL {@code WS-USRSEC-FILE} (line 39 of {@code app/cbl/COUSR03C.cbl}).
     * Dataset name passed to {@code EXEC CICS READ}/{@code DELETE} via the
     * {@link UserSecurityRepository} port; preserved here as documentation
     * of the underlying VSAM KSDS dataset name.
     */
    public static final String LIT_USRSEC_FILE = "USRSEC";

    /**
     * Maximum length of a user-id field — COBOL {@code PIC X(08)}.
     * Mirrors {@link SecUserData#SEC_USR_ID_LENGTH} and the
     * {@code LENGTH=8} attribute of the {@code USRIDIN} BMS field
     * (line 51 of {@code app/bms/COUSR03.bms}).
     */
    public static final int USR_ID_LENGTH = 8;

    // ========================================================================
    // Private banner-title constants — verbatim 40-char values from COTTL01Y
    // ========================================================================

    /**
     * Banner title 01 — exact 40-character value of {@code CCDA-TITLE01}
     * from {@code app/cpy/COTTL01Y.cpy} (6 leading spaces + "AWS Mainframe
     * Modernization" + 7 trailing spaces). Populated into {@code TITLE01O}
     * of the COUSR03 BMS map by {@code POPULATE-HEADER-INFO} (line 247 of
     * {@code app/cbl/COUSR03C.cbl}).
     */
    private static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * Banner title 02 — exact 40-character value of {@code CCDA-TITLE02}
     * from {@code app/cpy/COTTL01Y.cpy} (14 leading spaces + "CardDemo" +
     * 18 trailing spaces). Populated into {@code TITLE02O} of the COUSR03
     * BMS map by {@code POPULATE-HEADER-INFO} (line 248 of
     * {@code app/cbl/COUSR03C.cbl}).
     */
    private static final String TITLE_02 = "              CardDemo                  ";

    // ========================================================================
    // Verbatim COBOL messages preserved per AAP §0.7.1
    // ========================================================================

    /** {@code 'User ID can NOT be empty...'} (lines 147, 179). */
    private static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

    /** {@code 'Press PF5 key to delete this user ...'} (line 283). */
    private static final String MSG_PRESS_PF5 = "Press PF5 key to delete this user ...";

    /** {@code 'User ID NOT found...'} (lines 289, 325). */
    private static final String MSG_NOT_FOUND = "User ID NOT found...";

    /** {@code 'Unable to lookup User...'} (line 296). */
    private static final String MSG_LOOKUP_ERROR = "Unable to lookup User...";

    /**
     * {@code 'Unable to Update User...'} (line 332). Verbatim COBOL bug
     * — the message in the DELETE-USER-SEC-FILE failure path mentions
     * &ldquo;Update&rdquo; rather than &ldquo;Delete&rdquo;, evidently
     * copy-pasted from {@code COUSR02C.cbl}. Preserved verbatim per
     * AAP &sect;0.7.1 (&ldquo;If a COBOL paragraph contains dead code or
     * obvious bugs, translate it faithfully and flag it in
     * MIGRATION_NOTES.md; do not 'fix' it in this refactor&rdquo;).
     */
    private static final String MSG_DELETE_ERROR = "Unable to Update User...";

    /**
     * {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy} line 21.
     * 50-character COBOL constant; trimmed of its trailing spaces here
     * since the BMS {@code ERRMSGO} field is {@code PIC X(78)} (right-pad
     * is applied by the BMS layer).
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    // ========================================================================
    // AidKey enum — public, per schema exports
    // ========================================================================

    /**
     * AID-key dispatch enum, scoped to {@link CoUsr03C}.
     *
     * <p>Translates the {@code EVALUATE EIBAID} dispatch block at lines
     * 108-130 of {@code app/cbl/COUSR03C.cbl} into a typed enum that the
     * controller's pattern-matching switch consumes exhaustively. Each
     * constant maps one-to-one to a COBOL {@code DFHxxx} constant from
     * {@code COPY DFHAID} (line 67 of {@code COUSR03C.cbl}):
     *
     * <ul>
     *   <li>{@link #ENTER}       &mdash; {@code DFHENTER}: fetch the user
     *       for delete-confirmation display (paragraph
     *       {@code PROCESS-ENTER-KEY}, lines 142-169).</li>
     *   <li>{@link #PF03_BACK}   &mdash; {@code DFHPF3}: return to the
     *       previous program (admin menu by default).</li>
     *   <li>{@link #PF04_CLEAR}  &mdash; {@code DFHPF4}: clear the
     *       current screen (paragraph {@code CLEAR-CURRENT-SCREEN},
     *       lines 341-344).</li>
     *   <li>{@link #PF05_DELETE} &mdash; {@code DFHPF5}: commit the
     *       deletion of the user identified by {@code USRIDINI}
     *       (paragraph {@code DELETE-USER-INFO}, lines 174-192).</li>
     *   <li>{@link #PF12_CANCEL} &mdash; {@code DFHPF12}: cancel and
     *       return to the admin menu (line 124).</li>
     *   <li>{@link #OTHER}       &mdash; {@code WHEN OTHER}: any
     *       unmapped AID key; surfaces {@code CCDA-MSG-INVALID-KEY}
     *       (line 128).</li>
     * </ul>
     *
     * <p>Pattern-matching switches over this enum MUST be exhaustive;
     * no {@code default} branch is permitted (AAP &sect;0.7.3).
     */
    public enum AidKey {
        /** {@code DFHENTER}: fetch user for delete-confirmation display. */
        ENTER,
        /** {@code DFHPF3}: return to the previous program (admin menu by default). */
        PF03_BACK,
        /** {@code DFHPF4}: clear the current screen — reset all fields. */
        PF04_CLEAR,
        /** {@code DFHPF5}: confirm and commit the user delete. */
        PF05_DELETE,
        /** {@code DFHPF12}: cancel and return to the admin menu. */
        PF12_CANCEL,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid key error). */
        OTHER
    }

    // ========================================================================
    // DeleteState sealed interface — closed delete-workflow taxonomy
    // ========================================================================

    /**
     * Closed taxonomy of delete-workflow states for COUSR03C.
     *
     * <p>Translates the implicit fetch-then-confirm-delete state machine
     * embedded in {@code app/cbl/COUSR03C.cbl}:
     * <ul>
     *   <li>{@link NotFetched} &mdash; user has not yet entered a user id,
     *       the lookup has not yet been performed, or the previous lookup
     *       returned {@code DFHRESP(NOTFND)}.</li>
     *   <li>{@link Fetched}    &mdash; user-id has been looked up
     *       successfully and the record is on display for visual
     *       confirmation (the COBOL &ldquo;Press PF5 ...&rdquo; state at
     *       line 283 of {@code app/cbl/COUSR03C.cbl}).</li>
     *   <li>{@link Deleted}    &mdash; the {@code DELETE-USER-SEC-FILE}
     *       call succeeded (the COBOL &ldquo;User &lt;id&gt; has been
     *       deleted ...&rdquo; state at lines 318-321).</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.1.3 (&ldquo;closed business taxonomies are
     * exhaustive&rdquo;) and &sect;0.7.3 (&ldquo;no {@code default}
     * branches that hide missing cases&rdquo;), pattern-matching switches
     * over {@code DeleteState} MUST be exhaustive without a
     * {@code default} branch. The compiler enforces this once all three
     * permits are covered.
     *
     * <p>Each permit defines an {@link #indicator()} method returning the
     * single ASCII character used to round-trip the state through the
     * program-specific byte payload (byte index 88 of {@link Cu03State}).
     */
    public sealed interface DeleteState
            permits DeleteState.NotFetched, DeleteState.Fetched, DeleteState.Deleted {

        /**
         * Returns the single-character ASCII indicator used to encode this
         * state into the {@link Cu03State#encode()} byte payload.
         *
         * <ul>
         *   <li>{@code 'N'} for {@link NotFetched}</li>
         *   <li>{@code 'F'} for {@link Fetched}</li>
         *   <li>{@code 'D'} for {@link Deleted}</li>
         * </ul>
         *
         * @return single-character indicator, never {@code 0}
         */
        char indicator();

        /**
         * Initial delete-workflow state &mdash; no user id has been
         * fetched yet (or the previous lookup returned NOTFND). The
         * BMS screen displays {@code USRIDIN} as the editable field
         * and blanks for {@code FNAME}, {@code LNAME}, {@code USRTYPE}.
         */
        record NotFetched() implements DeleteState {
            /**
             * Canonical singleton instance &mdash; this record has no
             * components and is naturally a singleton. Prefer
             * {@link #INSTANCE} to {@code new NotFetched()} to avoid
             * unnecessary allocations.
             */
            public static final NotFetched INSTANCE = new NotFetched();
            @Override public char indicator() { return 'N'; }
        }

        /**
         * Post-fetch delete-confirmation state &mdash; the user record
         * has been retrieved from USRSEC and is displayed in
         * {@code FNAME}/{@code LNAME}/{@code USRTYPE} for the operator's
         * visual confirmation. The BMS message line shows
         * {@code "Press PF5 key to delete this user ..."}. PF5 in this
         * state actually commits the delete.
         */
        record Fetched() implements DeleteState {
            /**
             * Canonical singleton instance &mdash; this record has no
             * components and is naturally a singleton.
             */
            public static final Fetched INSTANCE = new Fetched();
            @Override public char indicator() { return 'F'; }
        }

        /**
         * Post-delete success state &mdash; the {@code DELETE-USER-SEC-FILE}
         * call returned {@code DFHRESP(NORMAL)}. The BMS message line
         * displays {@code "User <id> has been deleted ..."} in the GREEN
         * color attribute (line 317 of {@code COUSR03C.cbl}).
         */
        record Deleted() implements DeleteState {
            /**
             * Canonical singleton instance &mdash; this record has no
             * components and is naturally a singleton.
             */
            public static final Deleted INSTANCE = new Deleted();
            @Override public char indicator() { return 'D'; }
        }

        /**
         * Discriminator-based factory mapping a single ASCII indicator
         * character to its canonical {@link DeleteState} permit.
         *
         * <p>The {@code default} branch on the raw {@code char} surfaces
         * unknown indicators as {@link NotFetched} (safest default for
         * a corrupted or first-time-entry state buffer). This is NOT a
         * switch over a sealed type — switches over {@code DeleteState}
         * itself remain exhaustive without a {@code default}.
         *
         * @param c indicator character (case-insensitive)
         * @return {@link Fetched#INSTANCE} for {@code 'F'/'f'};
         *         {@link Deleted#INSTANCE} for {@code 'D'/'d'};
         *         {@link NotFetched#INSTANCE} for any other input
         *         (including {@code '\0'}, space, and unknown letters)
         */
        static DeleteState fromIndicator(char c) {
            return switch (Character.toUpperCase(c)) {
                case 'F' -> Fetched.INSTANCE;
                case 'D' -> Deleted.INSTANCE;
                default  -> NotFetched.INSTANCE;
            };
        }
    }

    // ========================================================================
    // Outcome sealed interface — translation of CICS SEND/XCTL return paths
    // ========================================================================

    /**
     * Outcome of a single pseudo-conversational invocation of
     * {@link #execute}.
     *
     * <p>Translates the COBOL {@code EXEC CICS SEND MAP} &rarr;
     * {@code EXEC CICS RETURN} flow versus the {@code EXEC CICS XCTL}
     * flow into a single typed return value. The caller (a JCL main
     * class, a CICS shell, or a test harness) pattern-matches over
     * {@code Outcome} exhaustively without a {@code default} branch
     * (AAP &sect;0.7.3).
     *
     * <p>Each permit carries the {@code progSpecificBytes} payload that
     * the JCL/CICS shell threads through the pseudo-conversational
     * RETURN/RECEIVE cycle. This is the Java equivalent of the COBOL
     * {@code CDEMO-CU03-INFO} commarea extension (lines 50-58 of
     * {@code app/cbl/COUSR03C.cbl}); see {@link Cu03State} for the
     * 89-byte encoding.
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * Translation of {@code EXEC CICS SEND MAP MAPSET('COUSR03')
         * MAP('COUSR3A') FROM(COUSR3AO) ERASE CURSOR} (lines 219-225 of
         * {@code app/cbl/COUSR03C.cbl}) followed by {@code EXEC CICS
         * RETURN TRANSID('CU03') COMMAREA(CARDDEMO-COMMAREA)}
         * (lines 134-137).
         *
         * @param output             the fully populated screen output
         *                           record &mdash; never {@code null}
         * @param commarea           the updated cross-program commarea
         *                           &mdash; never {@code null}
         * @param progSpecificBytes  the 89-byte program-specific state
         *                           payload (see {@link Cu03State}) &mdash;
         *                           never {@code null} (use {@code new byte[0]}
         *                           for empty state)
         */
        record SendMap(CoUsr03Output output, CardDemoCommarea commarea, byte[] progSpecificBytes)
                implements Outcome {
            /** Compact canonical constructor &mdash; defensive null checks. */
            public SendMap {
                Objects.requireNonNull(output, "output");
                Objects.requireNonNull(commarea, "commarea");
                Objects.requireNonNull(progSpecificBytes, "progSpecificBytes");
            }
        }

        /**
         * Translation of {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
         * COMMAREA(CARDDEMO-COMMAREA)} (lines 205-208 of
         * {@code app/cbl/COUSR03C.cbl}) &mdash; transfer control to the
         * named program.
         *
         * @param targetProgram      non-blank COBOL program-id of the XCTL
         *                           target (e.g.,
         *                           {@link #LIT_ADMIN_MENU_PGM})
         * @param commarea           the updated commarea carried to the
         *                           target program &mdash; never {@code null}
         * @param progSpecificBytes  the program-specific state payload
         *                           carried to the target &mdash; never
         *                           {@code null} (use {@code new byte[0]}
         *                           when transitioning to an unrelated
         *                           program that does not consume this
         *                           program's state)
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea, byte[] progSpecificBytes)
                implements Outcome {
            /** Compact canonical constructor &mdash; defensive null checks. */
            public Xctl {
                Objects.requireNonNull(targetProgram, "targetProgram");
                Objects.requireNonNull(commarea, "commarea");
                Objects.requireNonNull(progSpecificBytes, "progSpecificBytes");
                if (targetProgram.isBlank()) {
                    throw new IllegalArgumentException(
                            "targetProgram must not be blank");
                }
            }
        }
    }

    // ========================================================================
    // Instance state — constructor-injected collaborators + static utilities
    // ========================================================================

    /**
     * SLF4J logger. Used for error-level diagnostics on the
     * {@code WHEN OTHER} branches of the COBOL {@code EVALUATE WS-RESP-CD}
     * blocks in {@code READ-USER-SEC-FILE} and {@code DELETE-USER-SEC-FILE}
     * (lines 293-299 and 329-335 of {@code app/cbl/COUSR03C.cbl}). Per
     * AAP &sect;0.7.2, the password field is NEVER logged; only the user
     * id and the underlying exception are recorded.
     */
    private static final Logger log = LoggerFactory.getLogger(CoUsr03C.class);

    /**
     * Date formatter for the {@code CURDATEO} header field
     * (translating the {@code WS-CURDATE-MM-DD-YY} layout at lines
     * 30-35 of {@code app/cpy/CSDAT01Y.cpy}).
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");

    /**
     * Time formatter for the {@code CURTIMEO} header field
     * (translating the {@code WS-CURTIME-HH-MM-SS} layout at lines
     * 36-41 of {@code app/cpy/CSDAT01Y.cpy}).
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Port abstracting the USRSEC VSAM KSDS dataset. Constructor-injected;
     * mediates both the {@code READ-USER-SEC-FILE} fetch (line 267 of
     * {@code app/cbl/COUSR03C.cbl}) via {@link UserSecurityRepository#findById}
     * and the {@code DELETE-USER-SEC-FILE} commit (line 305) via
     * {@link UserSecurityRepository#delete}.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Dynamic program-dispatch registry. Constructor-injected so that any
     * future flow needing variable-name XCTL routing (translating
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} with a
     * commarea-supplied target) can route through
     * {@link ProgramRegistry#invoke}. The current COBOL flow uses literal
     * targets ({@link #LIT_ADMIN_MENU_PGM}, {@link ProgramRegistry#CO_SGN_00C})
     * and these are handled by returning {@link Outcome.Xctl} directly;
     * the registry remains in scope for symmetry with sibling translations
     * (see {@code CoActUpC}, {@code CoActVwC}).
     */
    private final ProgramRegistry programRegistry;

    /**
     * Constructs a {@code CoUsr03C} with explicit collaborator
     * dependencies (constructor injection — no framework required).
     *
     * @param userSecurityRepository non-null {@link UserSecurityRepository}
     *                               port abstracting the USRSEC dataset
     * @param programRegistry        non-null {@link ProgramRegistry} for
     *                               dynamic program dispatch
     * @throws NullPointerException if either argument is {@code null}
     */
    public CoUsr03C(UserSecurityRepository userSecurityRepository,
                    ProgramRegistry programRegistry) {
        this.userSecurityRepository =
                Objects.requireNonNull(userSecurityRepository, "userSecurityRepository");
        this.programRegistry =
                Objects.requireNonNull(programRegistry, "programRegistry");
    }

    // ========================================================================
    // execute() — translation of 0000-MAIN (lines 82-137 of COUSR03C.cbl)
    // ========================================================================

    /**
     * Entry point &mdash; translates COBOL {@code MAIN-PARA} (lines 82-137
     * of {@code app/cbl/COUSR03C.cbl}).
     *
     * <p>The COBOL paragraph performs three top-level branches:
     * <ol>
     *   <li><b>{@code EIBCALEN = 0}</b> (lines 90-92): no commarea
     *       inbound, {@code XCTL} to {@code COSGN00C}. In Java this
     *       corresponds to {@code commareaIn == null}; the method
     *       returns {@link Outcome.Xctl} pointing to
     *       {@link ProgramRegistry#CO_SGN_00C}.</li>
     *   <li><b>First-time entry</b> (lines 95-105): not currently in
     *       re-enter context. If {@code CDEMO-CU03-USR-SELECTED} is
     *       populated (line 99), MOVE it to {@code USRIDINI} and
     *       perform {@code PROCESS-ENTER-KEY}; then unconditionally
     *       perform {@code SEND-USRDEL-SCREEN}.</li>
     *   <li><b>Re-entry</b> (lines 106-130): RECEIVE-MAP, then
     *       {@code EVALUATE EIBAID} dispatches to one of
     *       {@code PROCESS-ENTER-KEY} / {@code RETURN-TO-PREV-SCREEN}
     *       (PF3 with {@code CDEMO-FROM-PROGRAM} fallback) /
     *       {@code CLEAR-CURRENT-SCREEN} (PF4) /
     *       {@code DELETE-USER-INFO} (PF5) /
     *       {@code RETURN-TO-PREV-SCREEN} to {@code COADM01C} (PF12)
     *       / {@code WHEN OTHER} (invalid key).</li>
     * </ol>
     *
     * @param commareaIn        inbound commarea (may be {@code null} for
     *                          the {@code EIBCALEN = 0} first-entry case)
     * @param progSpecificBytes program-specific state payload encoding
     *                          {@link Cu03State} (89 bytes); may be
     *                          {@code null} or empty for fresh entry
     * @param aidKey            non-null AID-key dispatch enum; pass
     *                          {@link AidKey#ENTER} for first-time entry
     * @param input             non-null input DTO from RECEIVE-MAP; pass
     *                          {@link CoUsr03Input#blank()} for
     *                          first-time entry before any input has been
     *                          received
     * @return non-null {@link Outcome} &mdash; either {@link Outcome.SendMap}
     *         (render the screen and RETURN) or {@link Outcome.Xctl}
     *         (transfer control to another program)
     * @throws NullPointerException if {@code aidKey} or {@code input} is
     *                              {@code null}
     */
    public Outcome execute(CardDemoCommarea commareaIn,
                           byte[] progSpecificBytes,
                           AidKey aidKey,
                           CoUsr03Input input) {
        Objects.requireNonNull(aidKey, "aidKey");
        Objects.requireNonNull(input, "input");

        // ---- Branch 1: EIBCALEN = 0 (no commarea) -> XCTL to COSGN00C ----
        if (commareaIn == null) {
            return xctlToSignon(CardDemoCommarea.empty());
        }

        // ---- Decode prior state (CDEMO-CU03-INFO equivalent) ----
        Cu03State state = Cu03State.decode(progSpecificBytes);

        // Read pgmContext / FROM-fields from the ORIGINAL commareaIn (before any
        // outbound stamping) so that the COBOL dispatch logic at lines 95 and
        // 112-117 sees the inbound values. FROM-* stamping happens only on XCTL
        // paths via {@link #xctlTo}; pgmContext = REENTER is applied to SEND-MAP
        // paths inside {@link #buildSendMap}.

        // ---- Branch 2: first-time entry — auto-populate from CDEMO-CU03-USR-SELECTED ----
        // COBOL line 95: "IF NOT CDEMO-PGM-REENTER". The Java equivalent inspects
        // the inbound commarea's PgmContext: ENTER means first-time entry.
        boolean firstTimeEntry = commareaIn.cdemoGeneralInfo().pgmContext().isEnter();
        if (firstTimeEntry && !state.selectedUserId().isEmpty() && trim(input.userId()).isEmpty()) {
            // MOVE CDEMO-CU03-USR-SELECTED TO USRIDINI; PERFORM PROCESS-ENTER-KEY
            String selected = state.selectedUserId();
            CoUsr03Input populated = input.withUserId(selected);
            // After fetchUser the state is either Fetched (success) or NotFetched (NOTFND/OTHER);
            // SEND-USRDEL-SCREEN happens inside fetchUser via buildSendMap.
            return fetchUser(commareaIn, selected, populated);
        }

        // ---- Branch 3: re-entry — EVALUATE EIBAID dispatch (lines 108-130) ----
        return switch (aidKey) {
            case ENTER       -> processInputs(commareaIn, state, input);
            case PF03_BACK   -> returnToPrevScreen(commareaIn);
            case PF04_CLEAR  -> clearCurrentScreen(commareaIn, state);
            case PF05_DELETE -> processDelete(commareaIn, state, input);
            case PF12_CANCEL -> returnToAdminMenu(commareaIn);
            case OTHER       -> buildSendMap(commareaIn, state, input,
                                            MSG_INVALID_KEY, /*green=*/ false);
        };
    }

    // ========================================================================
    // PROCESS-ENTER-KEY — lines 142-169 of app/cbl/COUSR03C.cbl
    // ========================================================================

    /**
     * Translation of {@code PROCESS-ENTER-KEY} (lines 142-169 of
     * {@code app/cbl/COUSR03C.cbl}). Validates {@code USRIDINI} (empty
     * check, line 145), then delegates to {@link #fetchUser} which
     * performs the {@code READ-USER-SEC-FILE} lookup and populates the
     * display fields.
     *
     * @param commarea outbound commarea with FROM-identity already
     *                 stamped
     * @param state    prior delete-workflow state (carried forward from
     *                 the prog-specific bytes)
     * @param input    operator-typed input from RECEIVE-MAP
     * @return {@link Outcome.SendMap} with either the empty-id error
     *         message or the populated user-record display
     */
    private Outcome processInputs(CardDemoCommarea commarea, Cu03State state, CoUsr03Input input) {
        String userId = trim(input.userId());
        if (userId.isEmpty()) {
            // EVALUATE TRUE WHEN USRIDINI = SPACES OR LOW-VALUES (line 145).
            // Reset to NotFetched and clear the display fields (FNAME/LNAME/USRTYPE)
            // to mirror the COBOL MOVE -1 TO USRIDINL behavior (cursor positioning
            // is BMS-layer; in Java we simply re-render with the error).
            return buildSendMap(
                    commarea,
                    state.withFetchedUser(null).withState(DeleteState.NotFetched.INSTANCE),
                    CoUsr03Input.blank(),
                    MSG_USERID_EMPTY,
                    /*green=*/ false);
        }
        return fetchUser(commarea, userId, input);
    }

    // ========================================================================
    // READ-USER-SEC-FILE — lines 267-300 of app/cbl/COUSR03C.cbl
    // ========================================================================

    /**
     * Translation of {@code READ-USER-SEC-FILE} (lines 267-300 of
     * {@code app/cbl/COUSR03C.cbl}). Performs the
     * {@code EXEC CICS READ DATASET('USRSEC') UPDATE} fetch and
     * dispatches on the response code:
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} (lines 281-286): record found.
     *       Build a {@link Cu03State.Fetched} state with the retrieved
     *       {@link SecUserData}; display {@code FNAMEI}/{@code LNAMEI}/
     *       {@code USRTYPEI} and the &ldquo;Press PF5 ...&rdquo;
     *       prompt.</li>
     *   <li>{@code DFHRESP(NOTFND)} (lines 287-292): record absent.
     *       Reset to {@link DeleteState.NotFetched}; display
     *       {@code "User ID NOT found..."}.</li>
     *   <li>{@code WHEN OTHER} (lines 293-299): unexpected I/O error.
     *       Reset to {@link DeleteState.NotFetched}; log RESP/REAS;
     *       display {@code "Unable to lookup User..."}.</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.7.2, the user's password is NEVER read or
     * logged on any path; only the user-id and exception details are
     * recorded.
     *
     * @param commarea outbound commarea with FROM-identity already
     *                 stamped
     * @param userId   the trimmed, non-empty user-id to look up
     * @param input    current input record (echo back on the rendered
     *                 screen)
     * @return {@link Outcome.SendMap} for one of the three response paths
     */
    private Outcome fetchUser(CardDemoCommarea commarea, String userId, CoUsr03Input input) {
        Optional<SecUserData> userOpt;
        try {
            userOpt = userSecurityRepository.findById(userId);
        } catch (RuntimeException re) {
            // EVALUATE WHEN OTHER (line 293): DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
            // Password is never logged (AAP §0.7.2).
            log.error("CoUsr03C: USRSEC read failed for userId='{}'", userId, re);
            return buildSendMap(
                    commarea,
                    Cu03State.initial(userId),
                    input.withUserId(userId),
                    MSG_LOOKUP_ERROR,
                    /*green=*/ false);
        }

        if (userOpt.isEmpty()) {
            // DFHRESP(NOTFND) (line 287)
            return buildSendMap(
                    commarea,
                    Cu03State.initial(userId),
                    CoUsr03Input.blank().withUserId(userId),
                    MSG_NOT_FOUND,
                    /*green=*/ false);
        }

        // DFHRESP(NORMAL) (line 281): MOVE SEC-USR-* TO COUSR3AI display fields;
        // MOVE 'Press PF5 ...' TO WS-MESSAGE.
        SecUserData fetched = userOpt.get();
        // Normalize the user-type indicator through the sealed UserType taxonomy:
        // valid 'A' (Admin) and 'U' (User) round-trip through their canonical
        // indicators; an out-of-range byte falls back to the raw character so the
        // BMS layer can surface the corruption rather than masking it. This
        // mirrors the COBOL convention that USRTYPEI displays the raw PIC X(01)
        // SEC-USR-TYPE field; the UserType validation here is a defense-in-depth
        // check that downstream tier (admin-menu XCTL) can rely on a known type.
        String userTypeDisplay = formatUserTypeDisplay(fetched.secUsrType());
        CoUsr03Input populated = new CoUsr03Input(
                userId,
                fetched.secUsrFname(),
                fetched.secUsrLname(),
                userTypeDisplay,
                input.aidKey());
        Cu03State newState = new Cu03State(userId, fetched, DeleteState.Fetched.INSTANCE);
        return buildSendMap(commarea, newState, populated, MSG_PRESS_PF5, /*green=*/ false);
    }

    /**
     * Normalizes a {@code SEC-USR-TYPE} byte through the sealed
     * {@link UserType} taxonomy and returns its canonical indicator
     * character. Validates that the byte is one of the documented
     * COBOL 88-level values ({@code 'A'} for {@code CDEMO-USRTYP-ADMIN},
     * {@code 'U'} for {@code CDEMO-USRTYP-USER} per
     * {@code app/cpy/COCOM01Y.cpy} line 16).
     *
     * <p>If the byte is not in the closed taxonomy, the raw character is
     * returned unchanged so the BMS layer can surface the data anomaly
     * to the operator rather than mask it. This degrades gracefully on
     * legacy or corrupt USRSEC records while preserving the COBOL
     * &ldquo;display the raw byte&rdquo; semantics on the happy path.
     *
     * @param secUsrType the raw {@code PIC X(01)} byte from SecUserData
     * @return single-character string: the canonical UserType indicator
     *         for valid bytes, the raw character otherwise
     */
    private static String formatUserTypeDisplay(char secUsrType) {
        try {
            UserType type = UserType.fromIndicator(secUsrType);
            return String.valueOf(type.indicator());
        } catch (IllegalArgumentException invalid) {
            return String.valueOf(secUsrType);
        }
    }

    // ========================================================================
    // DELETE-USER-INFO + DELETE-USER-SEC-FILE — lines 174-192, 305-336
    // ========================================================================

    /**
     * Translation of {@code DELETE-USER-INFO} (lines 174-192) and
     * {@code DELETE-USER-SEC-FILE} (lines 305-336) of
     * {@code app/cbl/COUSR03C.cbl}.
     *
     * <p>COBOL flow (lines 188-191):
     * <pre>
     *   IF NOT ERR-FLG-ON
     *       MOVE USRIDINI OF COUSR3AI TO SEC-USR-ID
     *       PERFORM READ-USER-SEC-FILE
     *       PERFORM DELETE-USER-SEC-FILE
     *   END-IF
     * </pre>
     *
     * <p>The Java translation follows the same READ-then-DELETE flow,
     * mapping responses as follows:
     * <ul>
     *   <li>READ NORMAL + DELETE NORMAL: build the &ldquo;User &lt;id&gt;
     *       has been deleted ...&rdquo; success message in the GREEN
     *       color attribute (line 317), with {@code DELIMITED BY SPACE}
     *       semantics on the SEC-USR-ID (lines 318-321) — only the
     *       first whitespace-delimited token of the user id is
     *       included.</li>
     *   <li>READ NORMAL + DELETE NOTFND: show &ldquo;User ID NOT
     *       found...&rdquo; (line 325). This window is essentially
     *       unreachable in single-threaded mainframe code but is
     *       preserved for fidelity to the COBOL source.</li>
     *   <li>READ NOTFND (before DELETE): show &ldquo;User ID NOT
     *       found...&rdquo; (line 289 path).</li>
     *   <li>Any OTHER response from either operation: show &ldquo;Unable
     *       to Update User...&rdquo; (line 332 — verbatim COBOL bug
     *       mentioning &ldquo;Update&rdquo; in the DELETE failure
     *       path).</li>
     * </ul>
     *
     * <p>Additional defensive guard: if the prior workflow state is not
     * {@link DeleteState.Fetched}, the PF5 dispatch was reached without
     * a preceding successful fetch. The operator may have changed the
     * user-id between ENTER and PF5; perform a fresh READ to validate
     * the current input.
     *
     * @param commarea outbound commarea
     * @param state    prior workflow state
     * @param input    input from RECEIVE-MAP
     * @return {@link Outcome.SendMap} with the appropriate success or
     *         error message
     */
    private Outcome processDelete(CardDemoCommarea commarea, Cu03State state, CoUsr03Input input) {
        String userId = trim(input.userId());
        if (userId.isEmpty()) {
            // EVALUATE TRUE WHEN USRIDINI = SPACES OR LOW-VALUES (line 177).
            return buildSendMap(
                    commarea,
                    state.withFetchedUser(null).withState(DeleteState.NotFetched.INSTANCE),
                    CoUsr03Input.blank(),
                    MSG_USERID_EMPTY,
                    /*green=*/ false);
        }

        // PERFORM READ-USER-SEC-FILE (line 190): READ UPDATE first to validate
        // the user exists. Any non-NORMAL response short-circuits the DELETE.
        Optional<SecUserData> existing;
        try {
            existing = userSecurityRepository.findById(userId);
        } catch (RuntimeException re) {
            log.error("CoUsr03C: USRSEC read-for-delete failed for userId='{}'", userId, re);
            return buildSendMap(
                    commarea,
                    state.withFetchedUser(null).withState(DeleteState.NotFetched.INSTANCE),
                    input.withUserId(userId),
                    MSG_LOOKUP_ERROR,
                    /*green=*/ false);
        }

        if (existing.isEmpty()) {
            // DFHRESP(NOTFND) on the READ — line 287 path.
            return buildSendMap(
                    commarea,
                    Cu03State.initial(userId),
                    CoUsr03Input.blank().withUserId(userId),
                    MSG_NOT_FOUND,
                    /*green=*/ false);
        }

        // PERFORM DELETE-USER-SEC-FILE (line 191): EXEC CICS DELETE DATASET('USRSEC')
        try {
            userSecurityRepository.delete(userId);
        } catch (NoSuchElementException nsee) {
            // DFHRESP(NOTFND) on the DELETE — line 323 path. Race window in COBOL,
            // realistic in Java (concurrent delete from another transaction).
            return buildSendMap(
                    commarea,
                    state.withFetchedUser(null).withState(DeleteState.NotFetched.INSTANCE),
                    CoUsr03Input.blank().withUserId(userId),
                    MSG_NOT_FOUND,
                    /*green=*/ false);
        } catch (RuntimeException re) {
            // DFHRESP(WHEN OTHER) on the DELETE — line 329 path.
            // Verbatim COBOL bug: message says "Update" not "Delete" (AAP §0.7.1).
            log.error("CoUsr03C: USRSEC delete failed for userId='{}'", userId, re);
            return buildSendMap(
                    commarea,
                    state,
                    input.withUserId(userId),
                    MSG_DELETE_ERROR,
                    /*green=*/ false);
        }

        // DFHRESP(NORMAL) on DELETE — lines 314-322.
        // PERFORM INITIALIZE-ALL-FIELDS (line 315): blank all input fields.
        // STRING 'User ' DELIMITED BY SIZE SEC-USR-ID DELIMITED BY SPACE
        //        ' has been deleted ...' DELIMITED BY SIZE INTO WS-MESSAGE.
        String firstToken = firstSpaceDelimitedToken(existing.get().secUsrId());
        String successMessage = "User " + firstToken + " has been deleted ...";
        Cu03State newState = new Cu03State(userId, null, DeleteState.Deleted.INSTANCE);
        return buildSendMap(
                commarea,
                newState,
                CoUsr03Input.blank(),
                successMessage,
                /*green=*/ true);
    }

    // ========================================================================
    // CLEAR-CURRENT-SCREEN — lines 341-344 of app/cbl/COUSR03C.cbl
    // ========================================================================

    /**
     * Translation of {@code CLEAR-CURRENT-SCREEN} (lines 341-344). Performs
     * {@code INITIALIZE-ALL-FIELDS} (line 343) — which blanks USRIDINI,
     * FNAMEI, LNAMEI, USRTYPEI, and WS-MESSAGE (lines 349-356) — then
     * {@code SEND-USRDEL-SCREEN}. The workflow state is reset to
     * {@link DeleteState.NotFetched} since the operator is effectively
     * starting over; the {@code selectedUserId} preselect is preserved
     * across the clear (the operator may still want to fetch the same
     * user with a different intent).
     *
     * @param commarea outbound commarea with FROM-identity already stamped
     * @param state    prior workflow state (only {@code selectedUserId} is
     *                 preserved)
     * @return {@link Outcome.SendMap} with all input fields blank and no
     *         message
     */
    private Outcome clearCurrentScreen(CardDemoCommarea commarea, Cu03State state) {
        Cu03State cleared = Cu03State.initial(state.selectedUserId());
        return buildSendMap(commarea, cleared, CoUsr03Input.blank(), /*message=*/ "", /*green=*/ false);
    }

    // ========================================================================
    // RETURN-TO-PREV-SCREEN — lines 197-208 of app/cbl/COUSR03C.cbl
    // ========================================================================

    /**
     * PF3 path — translation of the COBOL block at lines 111-118:
     * <pre>
     *   WHEN DFHPF3
     *       IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
     *           MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
     *       ELSE
     *           MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
     *       END-IF
     *       PERFORM RETURN-TO-PREV-SCREEN
     * </pre>
     *
     * <p>Then {@code RETURN-TO-PREV-SCREEN} (lines 197-208):
     * <pre>
     *   IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
     *       MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *   END-IF
     *   MOVE WS-TRANID  TO CDEMO-FROM-TRANID
     *   MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
     *   MOVE ZEROS      TO CDEMO-PGM-CONTEXT
     *   EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
     * </pre>
     *
     * @param commareaIn inbound commarea (already stamped with this
     *                   program's FROM-identity by {@link #stampFromIdentity})
     * @return {@link Outcome.Xctl} pointing to the previous program
     *         (admin menu by default, COSGN00C if both FROM and TO are
     *         blank)
     */
    private Outcome returnToPrevScreen(CardDemoCommarea commareaIn) {
        CardDemoCommarea.CdemoGeneralInfo gi = commareaIn.cdemoGeneralInfo();
        String fromProgram = trim(gi.fromProgram());
        // First: determine PF3 dispatch target per lines 112-117.
        String pf3Target = fromProgram.isEmpty() ? LIT_ADMIN_MENU_PGM : fromProgram;
        // Second: RETURN-TO-PREV-SCREEN default per line 199 — if TO-PROGRAM is empty,
        // default to COSGN00C. Since we just computed pf3Target as non-empty above,
        // this defaulting only applies if pf3Target itself is blank (edge case where
        // FROM-PROGRAM was set to spaces by upstream code).
        String target = pf3Target.isEmpty() ? ProgramRegistry.CO_SGN_00C : pf3Target;
        return xctlTo(commareaIn, target, defaultTranIdFor(target));
    }

    /**
     * PF12 path — translation of the COBOL block at lines 123-125:
     * <pre>
     *   WHEN DFHPF12
     *       MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
     *       PERFORM RETURN-TO-PREV-SCREEN
     * </pre>
     *
     * @param commareaIn inbound commarea
     * @return {@link Outcome.Xctl} pointing to {@code COADM01C}
     */
    private Outcome returnToAdminMenu(CardDemoCommarea commareaIn) {
        return xctlTo(commareaIn, LIT_ADMIN_MENU_PGM, LIT_ADMIN_MENU_TRAN_ID);
    }

    /**
     * EIBCALEN=0 path — translation of the COBOL block at lines 90-92:
     * <pre>
     *   IF EIBCALEN = 0
     *       MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *       PERFORM RETURN-TO-PREV-SCREEN
     * </pre>
     *
     * @param emptyCommarea a freshly minted empty commarea
     * @return {@link Outcome.Xctl} pointing to {@code COSGN00C}
     */
    private Outcome xctlToSignon(CardDemoCommarea emptyCommarea) {
        // The XCTL helper itself stamps the FROM-* fields per the
        // RETURN-TO-PREV-SCREEN paragraph (lines 202-203) — no pre-stamping.
        return xctlTo(emptyCommarea, ProgramRegistry.CO_SGN_00C,
                defaultTranIdFor(ProgramRegistry.CO_SGN_00C));
    }

    // ========================================================================
    // Common XCTL helper — translation of EXEC CICS XCTL
    // ========================================================================

    /**
     * Builds an {@link Outcome.Xctl} for the named program target.
     *
     * <p>Translation of {@code RETURN-TO-PREV-SCREEN} (lines 197-208 of
     * {@code app/cbl/COUSR03C.cbl}). Steps performed in COBOL order:
     * <ol>
     *   <li>If {@code CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES}, default to
     *       {@code 'COSGN00C'} (line 199). The caller is responsible for
     *       passing a non-blank {@code targetProgram}; this method's
     *       compact constructor on {@link Outcome.Xctl} enforces that.</li>
     *   <li>{@code MOVE WS-TRANID  TO CDEMO-FROM-TRANID}  (line 202) —
     *       stamp this program's transaction-id into the outbound
     *       commarea's FROM-TRANID field.</li>
     *   <li>{@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} (line 203) —
     *       stamp this program's program-id into the outbound commarea's
     *       FROM-PROGRAM field.</li>
     *   <li>{@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} (line 204) — reset
     *       the outbound commarea's {@link PgmContext} to
     *       {@link PgmContext#ENTER} so the target program sees a fresh
     *       (non-reenter) context on its first dispatch.</li>
     *   <li>Set {@code CDEMO-TO-TRANID} and {@code CDEMO-TO-PROGRAM}
     *       from the supplied target identifiers.</li>
     * </ol>
     *
     * <p>The {@code progSpecificBytes} payload is reset to empty since
     * each XCTL target program owns its own program-specific state
     * contract (the COBOL equivalent would be a different commarea
     * extension on the next program).
     *
     * @param commareaIn    the inbound commarea (read-only;
     *                      FROM-* fields are overwritten on the outbound
     *                      copy)
     * @param targetProgram non-blank target program-id (8 chars or
     *                      shorter; space-padded to 8)
     * @param targetTranId  4-char target transaction id (space-padded
     *                      to 4)
     * @return {@link Outcome.Xctl} ready for the JCL/CICS shell to
     *         dispatch
     */
    private Outcome xctlTo(CardDemoCommarea commareaIn, String targetProgram, String targetTranId) {
        CardDemoCommarea.CdemoGeneralInfo gi = commareaIn.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padOrTruncate(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padOrTruncate(LIT_THIS_PGM, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padOrTruncate(targetTranId, CardDemoCommarea.LENGTH_TO_TRANID),
                padOrTruncate(targetProgram, CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commareaIn.withCdemoGeneralInfo(updated);
        return new Outcome.Xctl(targetProgram, outbound, new byte[0]);
    }

    /**
     * Returns the canonical 4-char transaction id for the given
     * 8-char program-id, for the small set of XCTL targets relevant
     * to COUSR03C's flow. Falls back to {@code "    "} (4 spaces) for
     * any program not in the lookup — the BMS layer will pad on send
     * and the COBOL convention is to leave TO-TRANID blank for
     * non-routing dispatch targets.
     *
     * @param programId the COBOL program-id (already trimmed of padding)
     * @return the corresponding 4-char transaction id (untrimmed/unpadded
     *         here; padding is applied by the {@link CardDemoCommarea}
     *         field validator)
     */
    private static String defaultTranIdFor(String programId) {
        String trimmed = trim(programId);
        return switch (trimmed) {
            case "COADM01C" -> LIT_ADMIN_MENU_TRAN_ID;
            case "COUSR00C" -> LIT_USR_LIST_TRAN_ID;
            case "COSGN00C" -> "CC00";   // signon transaction id
            case "COUSR03C" -> LIT_THIS_TRAN_ID;
            default         -> "    ";
        };
    }

    // ========================================================================
    // SEND-USRDEL-SCREEN + POPULATE-HEADER-INFO — lines 213-262
    // ========================================================================

    /**
     * Translation of {@code SEND-USRDEL-SCREEN} (lines 213-225) and
     * {@code POPULATE-HEADER-INFO} (lines 243-262) of
     * {@code app/cbl/COUSR03C.cbl}, fused into a single output-record
     * factory. Builds a fully populated {@link CoUsr03Output} (11 fields)
     * and a corresponding {@link Outcome.SendMap}.
     *
     * <p>The {@code green} flag is informational only at this layer: the
     * COBOL program sets {@code ERRMSGC OF COUSR3AO} to {@code DFHGREEN}
     * for success messages (line 317) and to {@code DFHNEUTR} for the
     * &ldquo;Press PF5&rdquo; prompt (line 285); other error messages
     * use the default RED-BRT attribute defined on the BMS map (line 99
     * of {@code app/bms/COUSR03.bms}). The current {@link CoUsr03Output}
     * record does NOT carry a color attribute, so this flag is retained
     * here for future extension and consumed by the {@link Cu03State}
     * indicator (Deleted → green message implied).
     *
     * @param commarea outbound commarea (already stamped)
     * @param state    new workflow state to encode into progSpecificBytes
     * @param input    input record whose fields are echoed on the output
     * @param message  message text to populate {@code ERRMSGO} (PIC X(78));
     *                 must be {@code <= 78} characters
     * @param green    {@code true} if the message is a GREEN success
     *                 indicator; {@code false} for NEUTRAL/RED
     * @return {@link Outcome.SendMap} ready to render the screen
     */
    private Outcome buildSendMap(CardDemoCommarea commarea,
                                 Cu03State state,
                                 CoUsr03Input input,
                                 String message,
                                 boolean green) {
        // POPULATE-HEADER-INFO: current date + time + literals
        String today = LocalDate.now().format(DATE_FORMATTER);
        String now = LocalTime.now().format(TIME_FORMATTER);
        // Build the 11-field output per app/cpy-bms/COUSR03.CPY (symbolic copybook)
        // and the agent_prompt's strict field ordering.
        CoUsr03Output output = new CoUsr03Output(
                LIT_THIS_TRAN_ID,                       // TRNNAMEO  PIC X(4)
                TITLE_01,                                // TITLE01O  PIC X(40)
                today,                                   // CURDATEO  PIC X(8)
                LIT_THIS_PGM,                            // PGMNAMEO  PIC X(8)
                TITLE_02,                                // TITLE02O  PIC X(40)
                now,                                     // CURTIMEO  PIC X(8)
                trim(input.userId()),                    // USRIDINO  PIC X(8)
                trim(input.firstName()),                 // FNAMEO    PIC X(20)
                trim(input.lastName()),                  // LNAMEO    PIC X(20)
                trim(input.userType()),                  // USRTYPEO  PIC X(1)
                clampTo(message == null ? "" : message, 78));  // ERRMSGO PIC X(78)

        // CDEMO-LAST-MAP/CDEMO-LAST-MAPSET stamped on the outbound commarea so the
        // next dispatch can identify the last-rendered screen.
        CardDemoCommarea.CdemoMoreInfo more = new CardDemoCommarea.CdemoMoreInfo(
                padOrTruncate(LIT_THIS_MAP, CardDemoCommarea.LENGTH_LAST_MAP),
                padOrTruncate(LIT_THIS_MAPSET, CardDemoCommarea.LENGTH_LAST_MAPSET));
        // SET CDEMO-PGM-REENTER TO TRUE (line 96 of app/cbl/COUSR03C.cbl) — every
        // SEND-MAP path leaves the program in re-enter context so the next
        // pseudo-conversational dispatch lands on the EVALUATE EIBAID branch.
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo reenteredGi = new CardDemoCommarea.CdemoGeneralInfo(
                gi.fromTranId(),
                gi.fromProgram(),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                PgmContext.REENTER);
        CardDemoCommarea outboundCommarea = commarea
                .withCdemoGeneralInfo(reenteredGi)
                .withCdemoMoreInfo(more);

        // Mark green/non-green via the state — the DeleteState.Deleted indicator
        // unambiguously identifies the GREEN success path. The 'green' parameter
        // is retained for symmetry with sibling translations and to allow future
        // GREEN messages on non-Deleted states without state surgery.
        Cu03State stateForEncoding = green && !(state.state() instanceof DeleteState.Deleted)
                ? state.withState(DeleteState.Deleted.INSTANCE)
                : state;
        return new Outcome.SendMap(output, outboundCommarea, stateForEncoding.encode());
    }

    // ========================================================================
    // Cu03State — internal record carrying program-specific state
    // ========================================================================

    /**
     * Internal record holding the COUSR03C program-specific state across
     * pseudo-conversational cycles.
     *
     * <p>Encoded byte layout (89 bytes total):
     * <ul>
     *   <li>bytes 0&ndash;7  : {@code selectedUserId} — 8-char US-ASCII
     *       (translates {@code CDEMO-CU03-USR-SELECTED} from
     *       {@code app/cbl/COUSR03C.cbl} line 58)</li>
     *   <li>bytes 8&ndash;87 : {@code fetchedUser} — 80-byte
     *       {@link SecUserData} snapshot via {@link SecUserData#encode()}
     *       (all zeros if no user has been fetched yet)</li>
     *   <li>byte 88          : {@code state.indicator()} — single-character
     *       ASCII state indicator: {@code 'N'}/{@code 'F'}/{@code 'D'}</li>
     * </ul>
     *
     * <p>This is NOT a domain record — it is an internal projection of
     * the COBOL {@code CDEMO-CU03-INFO} commarea extension into the
     * {@code progSpecificBytes} channel of {@link Outcome}. It is
     * deliberately private to {@link CoUsr03C}.
     *
     * @param selectedUserId the preselected user-id from COUSR00C (may be
     *                       the empty string if no preselect)
     * @param fetchedUser    the fetched USRSEC record (may be {@code null}
     *                       if no successful fetch yet)
     * @param state          the current delete-workflow state (never
     *                       {@code null})
     */
    private record Cu03State(String selectedUserId, SecUserData fetchedUser, DeleteState state) {

        /** Total encoded length in bytes: 8 (userId) + 80 (SecUserData) + 1 (indicator). */
        private static final int ENCODED_LENGTH = 8 + SecUserData.RECORD_LENGTH + 1;

        /**
         * Canonical constructor — normalizes {@code selectedUserId} to
         * empty-string for {@code null}, truncates to 8 chars, and
         * coerces a {@code null} {@code state} to
         * {@link DeleteState.NotFetched}.
         */
        Cu03State {
            if (selectedUserId == null) {
                selectedUserId = "";
            } else if (selectedUserId.length() > 8) {
                selectedUserId = selectedUserId.substring(0, 8);
            }
            if (state == null) {
                state = DeleteState.NotFetched.INSTANCE;
            }
        }

        /**
         * Factory for a fresh state with the given preselect and no
         * fetched user.
         *
         * @param selectedUserId the preselected user-id (may be empty)
         * @return a state with {@code fetchedUser = null} and
         *         {@code state = DeleteState.NotFetched}
         */
        static Cu03State initial(String selectedUserId) {
            return new Cu03State(selectedUserId, null, DeleteState.NotFetched.INSTANCE);
        }

        /**
         * Returns a copy of this state with a different
         * {@link DeleteState}. {@code selectedUserId} and
         * {@code fetchedUser} are preserved.
         *
         * @param newState the new delete-workflow state
         * @return a new {@link Cu03State} instance
         */
        Cu03State withState(DeleteState newState) {
            return new Cu03State(this.selectedUserId, this.fetchedUser, newState);
        }

        /**
         * Returns a copy of this state with a different fetched user.
         * {@code selectedUserId} and {@code state} are preserved.
         *
         * @param newFetchedUser the new fetched user (may be {@code null}
         *                       to clear)
         * @return a new {@link Cu03State} instance
         */
        Cu03State withFetchedUser(SecUserData newFetchedUser) {
            return new Cu03State(this.selectedUserId, newFetchedUser, this.state);
        }

        /**
         * Decodes a byte payload (as produced by {@link #encode()}) back
         * into a {@link Cu03State}. Tolerant of {@code null}, empty, or
         * partial buffers — a missing or truncated buffer yields a fresh
         * {@link DeleteState.NotFetched} state. Corrupt
         * {@link SecUserData} bytes are silently treated as absent.
         *
         * @param bytes the byte payload (may be {@code null})
         * @return a non-null {@link Cu03State} instance
         */
        static Cu03State decode(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return new Cu03State("", null, DeleteState.NotFetched.INSTANCE);
            }
            String selId = "";
            int idLen = Math.min(8, bytes.length);
            if (idLen > 0) {
                selId = new String(bytes, 0, idLen, StandardCharsets.US_ASCII).trim();
            }
            SecUserData fetched = null;
            if (bytes.length >= 8 + SecUserData.RECORD_LENGTH) {
                byte[] secBytes = new byte[SecUserData.RECORD_LENGTH];
                System.arraycopy(bytes, 8, secBytes, 0, SecUserData.RECORD_LENGTH);
                // The all-zeros buffer represents "no user fetched" — skip parsing.
                if (!allZero(secBytes)) {
                    try {
                        fetched = SecUserData.parse(secBytes);
                    } catch (RuntimeException ignore) {
                        // Corrupt/legacy buffer — degrade gracefully.
                        fetched = null;
                    }
                }
            }
            char stateInd = (bytes.length >= ENCODED_LENGTH)
                    ? (char) (bytes[ENCODED_LENGTH - 1] & 0xFF)
                    : 'N';
            return new Cu03State(selId, fetched, DeleteState.fromIndicator(stateInd));
        }

        /**
         * Encodes this state into a fixed-width 89-byte payload suitable
         * for round-tripping through the {@code progSpecificBytes} channel
         * of {@link Outcome}. The 8-char {@code selectedUserId} is
         * space-padded; the {@code fetchedUser} slice is filled with
         * zeros if {@code fetchedUser == null}; the trailing byte holds
         * {@code state.indicator()}.
         *
         * @return a new 89-byte buffer
         */
        byte[] encode() {
            ByteBuffer buf = ByteBuffer.allocate(ENCODED_LENGTH);
            // Selected user id slice (8 bytes, space-padded).
            String padded = padOrTruncate(selectedUserId, 8);
            buf.put(padded.getBytes(StandardCharsets.US_ASCII));
            // Fetched-user slice (80 bytes; all-zero if absent).
            if (fetchedUser != null) {
                byte[] encoded = fetchedUser.encode();
                byte[] slice = new byte[SecUserData.RECORD_LENGTH];
                System.arraycopy(encoded, 0, slice, 0, Math.min(encoded.length, slice.length));
                buf.put(slice);
            } else {
                buf.put(new byte[SecUserData.RECORD_LENGTH]);
            }
            // State indicator byte.
            buf.put((byte) state.indicator());
            return buf.array();
        }

        /**
         * Returns {@code true} if every byte of the buffer is {@code 0}.
         * Used to detect the "no user fetched" sentinel slice during
         * decoding.
         *
         * @param bytes the buffer to inspect
         * @return {@code true} if all bytes are {@code 0}
         */
        private static boolean allZero(byte[] bytes) {
            for (byte b : bytes) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }
    }

    // ========================================================================
    // Static utility helpers
    // ========================================================================

    /**
     * Returns the trimmed value of the input, or empty string if
     * {@code null}. Used to translate the COBOL "SPACES or LOW-VALUES"
     * empty-check semantics consistently.
     *
     * @param s the input string (may be {@code null})
     * @return {@code ""} if {@code s} is {@code null}; otherwise
     *         {@code s.trim()}
     */
    private static String trim(String s) {
        return (s == null) ? "" : s.trim();
    }

    /**
     * Returns the substring of {@code s} up to (but not including) the
     * first {@code ' '} character. Mirrors the COBOL
     * {@code STRING ... DELIMITED BY SPACE} construct for a single source
     * operand (lines 318-320 of {@code app/cbl/COUSR03C.cbl}).
     *
     * @param s the input string (may be {@code null} or empty)
     * @return the prefix up to (excluding) the first space; {@code s}
     *         itself if no space is present; {@code ""} if {@code s} is
     *         {@code null} or empty
     */
    private static String firstSpaceDelimitedToken(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int spaceIdx = s.indexOf(' ');
        return (spaceIdx < 0) ? s : s.substring(0, spaceIdx);
    }

    /**
     * Returns {@code s} truncated to at most {@code maxLength} characters.
     * Used to guard against COBOL-side {@code STRING} concatenations
     * producing a message that exceeds the BMS {@code ERRMSGO PIC X(78)}
     * width and tripping the {@link CoUsr03Output} compact-constructor
     * length validator.
     *
     * @param s         the input string (may be {@code null})
     * @param maxLength the maximum length (must be {@code >= 0})
     * @return the (possibly truncated) string, never {@code null}
     */
    private static String clampTo(String s, int maxLength) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength);
    }

    /**
     * Returns the input space-padded or truncated to exactly
     * {@code width} characters. Used to populate the fixed-length ASCII
     * text fields of {@link CardDemoCommarea.CdemoGeneralInfo} and
     * {@link CardDemoCommarea.CdemoMoreInfo}, both of which have compact
     * constructors that reject non-exact-length values via
     * {@code validateFixedLengthAscii}.
     *
     * @param s     the input string (may be {@code null})
     * @param width the exact target width (must be {@code >= 0})
     * @return a string of exactly {@code width} characters
     */
    private static String padOrTruncate(String s, int width) {
        if (width <= 0) {
            return "";
        }
        String src = (s == null) ? "" : s;
        if (src.length() == width) {
            return src;
        }
        if (src.length() > width) {
            return src.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(src);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}



