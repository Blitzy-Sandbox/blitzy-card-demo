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
// exported by the java.base module. Provides:
//   - java.lang.{String, Character, Math, RuntimeException, NullPointerException,
//     IllegalArgumentException, AutoCloseable}
//   - java.util.{Objects, List, ArrayList, Collections}
//   - java.nio.ByteBuffer
//   - java.nio.charset.StandardCharsets
//   - java.time.{LocalDate, LocalTime, LocalDateTime, format.DateTimeFormatter}
//   - java.util.stream.Stream
// Per AAP §0.7.3 this is the canonical module-import statement for files
// that touch many java.* packages, replacing individual java.lang.* and
// java.util.* import statements.
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
 * Java translation of the {@code COUSR00C} CICS online program at
 * {@code app/cbl/COUSR00C.cbl} ("List all users from USRSEC file").
 *
 * <h2>Program purpose</h2>
 * <p>Paginated browse of the USRSEC dataset with 10-row pages and a
 * search/filter input. The operator may type a single-letter selection
 * ({@code 'U'} for update or {@code 'D'} for delete) into any row's
 * {@code SELxxxx} field; the controller XCTLs to {@code COUSR02C} or
 * {@code COUSR03C} respectively, carrying the selected user-id in the
 * program-specific bytes. Function keys: {@code ENTER}=process selection
 * (or refresh from search), {@code PF3}=back to admin menu (COADM01C),
 * {@code PF7}=page backward, {@code PF8}=page forward.
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:     COUSR00C
 *   WS-PGMNAME:     'COUSR00C'
 *   WS-TRANID:      'CU00'
 *   WS-USRSEC-FILE: 'USRSEC  '
 *   MAPSET:         COUSR00
 *   MAP:            COUSR0A
 * </pre>
 *
 * <h2>AID-key bindings (paragraph {@code MAIN-PARA})</h2>
 * <ul>
 *   <li>{@code DFHENTER} &rarr; {@link AidKey#ENTER}: scan rows 1-10
 *       for a selection (XCTL on first non-blank to COUSR02C/COUSR03C);
 *       fall through to a forward scan from the USRIDIN search key
 *       (or LOW-VALUES if blank) starting at page 0.</li>
 *   <li>{@code DFHPF3}   &rarr; {@link AidKey#PF03_BACK}: XCTL to
 *       COADM01C with the PgmContext reset to ENTER.</li>
 *   <li>{@code DFHPF7}   &rarr; {@link AidKey#PF07_PAGE_BACK}: page
 *       backward using {@code CDEMO-CU00-USRID-FIRST} as the start key.
 *       If page-num is 1, emit {@code MSG_AT_TOP} and re-render.</li>
 *   <li>{@code DFHPF8}   &rarr; {@link AidKey#PF08_PAGE_FWD}: page
 *       forward using {@code CDEMO-CU00-USRID-LAST} as the start key.
 *       If next-page-flag is "N", emit {@code MSG_AT_BOTTOM} and re-render.</li>
 *   <li>{@code WHEN OTHER} &rarr; {@link AidKey#OTHER}: emit
 *       {@code MSG_INVALID_KEY} and re-render the current page.</li>
 * </ul>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code 0000-MAIN} &rarr; {@link #execute(CardDemoCommarea, byte[],
 *       AidKey, CoUsr00Input)}.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; {@link #returnToAdminMenu}
 *       and {@link #returnToSignon}.</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey}.</li>
 *   <li>{@code PROCESS-PF7-KEY} &rarr; {@link #processPageBack}.</li>
 *   <li>{@code PROCESS-PF8-KEY} &rarr; {@link #processPageForward}.</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} &rarr; {@link #readForwardPage}.</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} &rarr; {@link #readBackwardPage}.</li>
 *   <li>{@code POPULATE-USER-DATA} / {@code INITIALIZE-USER-DATA}
 *       &rarr; row-list population inside {@link #buildSendMap}.</li>
 *   <li>{@code POPULATE-HEADER-INFO} &rarr; {@link #buildSendMap}
 *       header construction.</li>
 *   <li>{@code SEND-USRLST-SCREEN} / {@code RECEIVE-USRLST-SCREEN}
 *       &rarr; carried by {@link Outcome.SendMap} / {@link CoUsr00Input}
 *       parameter respectively (BMS SEND/RECEIVE-MAP semantics).</li>
 *   <li>{@code STARTBR-USER-SEC-FILE} / {@code READNEXT-USER-SEC-FILE} /
 *       {@code READPREV-USER-SEC-FILE} / {@code ENDBR-USER-SEC-FILE}
 *       &rarr; replaced by try-with-resources on the Stream returned
 *       from {@link UserSecurityRepository#streamFrom(String)} /
 *       {@link UserSecurityRepository#streamSequential()}.</li>
 *   <li>{@code 9999-ABEND-PROGRAM} &rarr; {@link #abendRoutine}.</li>
 * </ul>
 *
 * <h2>Externalized paging state ({@link PagingState})</h2>
 * <p>COBOL stores the paging cursor (USRID-FIRST, USRID-LAST, PAGE-NUM,
 * NEXT-PAGE-FLG) in the {@code CDEMO-CU00-INFO} group embedded in the
 * shared commarea. The Java {@link CardDemoCommarea} does NOT carry a
 * program-specific extension area, so this state is encoded into the
 * {@code progSpecificBytes} parameter of {@link #execute} via the internal
 * {@link PagingState} codec (21 bytes per dispatch cycle).
 *
 * <h2>Idiom-for-idiom translation per AAP &sect;0.7.1</h2>
 * <p>Error messages are preserved verbatim from the COBOL source. No
 * behavior changes, no business-rule enhancements, no rounding changes.
 * The plaintext password ({@code SEC-USR-PWD}) is NEVER read by COUSR00C
 * (the COBOL screen displays only id/fname/lname/type) and is NEVER
 * logged by this class.
 *
 * @see com.blitzy.carddemo.application.user.CoUsr00Input
 * @see com.blitzy.carddemo.application.user.CoUsr00Output
 * @see com.blitzy.carddemo.application.user.CoUsr02C
 * @see com.blitzy.carddemo.application.user.CoUsr03C
 */
@CobolProgram(
        value = "COUSR00C",
        sourcePath = "app/cbl/COUSR00C.cbl",
        translationDate = "2025-01-15",
        notes = "List users online; transaction id CU00. "
              + "Paged STARTBR/READNEXT/READPREV browse of USRSEC; 10 rows per page; "
              + "Search by user-id prefix; Selectable action per row: 'U' XCTL to COUSR02C, "
              + "'D' XCTL to COUSR03C. Keys: ENTER=process, PF3=back to COADM01C, "
              + "PF7=page back, PF8=page forward. Paging state externalized to "
              + "progSpecificBytes (21-byte PagingState codec) instead of the COBOL "
              + "CDEMO-CU00-INFO commarea extension. Read-only program — no SYNCPOINT. "
              + "PERFORMANCE NOTE: paging-backward materializes USRSEC sequentially; "
              + "acceptable because USRSEC is small (validated at ~24 entries in "
              + "current fixtures per AAP §0.6.10)."
)
public final class CoUsr00C {

    // -----------------------------------------------------------------------
    // Class identity constants (from WS-LITERALS in app/cbl/COUSR00C.cbl)
    // -----------------------------------------------------------------------

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COUSR00C'}. */
    public static final String LIT_THIS_PGM = "COUSR00C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CU00'}. */
    public static final String LIT_THIS_TRAN_ID = "CU00";

    /** BMS mapset name (matches {@code COPY COUSR00}). */
    public static final String LIT_THIS_MAPSET = "COUSR00";

    /** BMS map name. */
    public static final String LIT_THIS_MAP = "COUSR0A";

    /** XCTL target: admin menu program. */
    public static final String LIT_ADMIN_MENU_PGM = "COADM01C";

    /** XCTL target: admin menu transaction id. */
    public static final String LIT_ADMIN_MENU_TRAN_ID = "CA00";

    /** XCTL target: update-user program. */
    public static final String LIT_USR_UPDATE_PGM = "COUSR02C";

    /** XCTL target: update-user transaction id. */
    public static final String LIT_USR_UPDATE_TRAN_ID = "CU02";

    /** XCTL target: delete-user program. */
    public static final String LIT_USR_DELETE_PGM = "COUSR03C";

    /** XCTL target: delete-user transaction id. */
    public static final String LIT_USR_DELETE_TRAN_ID = "CU03";

    /**
     * XCTL target: signon program. Used when {@code commareaIn == null}
     * (equivalent to the COBOL {@code IF EIBCALEN = 0} guard at lines
     * 109-111 of {@code app/cbl/COUSR00C.cbl}).
     */
    public static final String LIT_SIGNON_PGM = "COSGN00C";

    /** COBOL {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}. */
    public static final String LIT_USRSEC_FILE = "USRSEC";

    /** Number of user rows rendered per page (matches BMS layout). */
    public static final int PAGE_SIZE = 10;

    /** Width of the user-id PIC X(08) field. */
    private static final int LEN_USER_ID = 8;

    // -----------------------------------------------------------------------
    // Message constants (verbatim per AAP §0.7.1)
    // -----------------------------------------------------------------------

    /** "Invalid key pressed. Please see below..." (CCDA-MSG-INVALID-KEY). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** "Invalid selection. Valid values are U and D" (verbatim from COUSR00C.cbl line 215). */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid values are U and D";

    /** "You are already at the top of the page..." (verbatim from COUSR00C.cbl line 250). */
    private static final String MSG_AT_TOP = "You are already at the top of the page...";

    /** "You are already at the bottom of the page..." (verbatim from COUSR00C.cbl line 271). */
    private static final String MSG_AT_BOTTOM = "You are already at the bottom of the page...";

    /** "You have reached the bottom of the page..." (verbatim from READNEXT ENDFILE handler). */
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

    /** "You have reached the top of the page..." (verbatim from READPREV ENDFILE handler). */
    private static final String MSG_REACHED_TOP = "You have reached the top of the page...";

    /** "You are at the top of the page..." (verbatim from STARTBR NOTFND handler). */
    private static final String MSG_TOP = "You are at the top of the page...";

    /** "Unable to lookup User..." (verbatim from STARTBR/READNEXT/READPREV OTHER handlers). */
    private static final String MSG_LOOKUP_ERROR = "Unable to lookup User...";

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

    /** Date format pattern for CURDATEO PIC X(8): {@code MM/dd/yy}. */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MM/dd/yy");

    /** Time format pattern for CURTIMEO PIC X(8): {@code HH:mm:ss}. */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** SLF4J logger; never receives the plaintext password (AAP §0.1.3, §0.7.4). */
    private static final Logger log = LoggerFactory.getLogger(CoUsr00C.class);

    // -----------------------------------------------------------------------
    // UserAction sealed interface
    // -----------------------------------------------------------------------

    /**
     * Per-row selection action for COUSR00 user list rows.
     *
     * <p>Translates the COBOL {@code SELxxxx PIC X(1)} selection field
     * where:
     * <ul>
     *   <li>{@code ' '} (space) / LOW-VALUES &mdash; no action selected
     *       (default; maps to {@link None})</li>
     *   <li>{@code 'U'} / {@code 'u'} &mdash; update; XCTL to COUSR02C
     *       with the selected user-id (maps to {@link Update})</li>
     *   <li>{@code 'D'} / {@code 'd'} &mdash; delete; XCTL to COUSR03C
     *       with the selected user-id (maps to {@link Delete})</li>
     *   <li>Any OTHER non-blank character (e.g. {@code 'X'}, {@code '1'},
     *       {@code '?'}) &mdash; invalid selection; emit
     *       {@link #MSG_INVALID_SELECTION} and rerender (maps to
     *       {@link Invalid}, capturing the offending raw character for
     *       diagnostic / golden-record purposes)</li>
     * </ul>
     * Per AAP &sect;0.1.3 closed taxonomies are sealed; switch over
     * {@code UserAction} MUST be exhaustive with NO {@code default} branch.
     *
     * <p>This is a textbook application of the <strong>sealed-type
     * pattern</strong> mandated by AAP &sect;0.3.2: closed business
     * taxonomies are expressed as sealed hierarchies so the Java compiler
     * enforces every site that switches on them.
     *
     * <p><strong>M16 — COBOL WHEN OTHER fidelity (preserve-as-is).</strong>
     * Prior to checkpoint 5 remediation, this hierarchy had only three
     * permits ({@code None}, {@code Update}, {@code Delete}) and the
     * {@link #fromIndicator(char)} factory collapsed any non-{@code U}
     * non-{@code D} byte to {@code None}. That collapsed the COBOL
     * distinction between "no selection" (SPACES / LOW-VALUES &rarr;
     * silent fallthrough) and "invalid selection" (any other non-blank
     * byte &rarr; emit the WS-MESSAGE "Invalid selection. Valid values
     * are U and D" per {@code app/cbl/COUSR00C.cbl} lines 210-214), and
     * therefore silently swallowed operator typos. The {@link Invalid}
     * permit restores that distinction: a non-blank non-{@code U}
     * non-{@code D} byte is now modeled as {@code Invalid(indicator)},
     * and the {@link #processEnterKey} dispatch surfaces it via
     * {@link #MSG_INVALID_SELECTION} on the rerendered page.
     */
    public sealed interface UserAction
            permits UserAction.None, UserAction.Update, UserAction.Delete, UserAction.Invalid {

        /**
         * Returns the single-character COBOL indicator for this action
         * &mdash; {@code ' '}, {@code 'U'}, {@code 'D'}, or the raw
         * offending byte for {@link Invalid}.
         *
         * @return the indicator byte (uppercase for U/D, space for None,
         *         raw byte for Invalid)
         */
        char indicator();

        /**
         * Decodes a single-character indicator (case-insensitive) back
         * into the corresponding {@code UserAction} permit.
         *
         * <p>Mapping table (M16 — preserves COBOL WHEN OTHER fidelity):
         * <ul>
         *   <li>{@code ' '} (space, ASCII 0x20), {@code '\0'} (NUL,
         *       COBOL LOW-VALUES proxy on ASCII) &rarr;
         *       {@link None#INSTANCE} (silent fallthrough)</li>
         *   <li>{@code 'U'} / {@code 'u'} &rarr; {@link Update#INSTANCE}</li>
         *   <li>{@code 'D'} / {@code 'd'} &rarr; {@link Delete#INSTANCE}</li>
         *   <li>Any other byte &rarr; {@code new Invalid(c)} (preserving
         *       the raw byte for diagnostic and golden-record purposes)</li>
         * </ul>
         *
         * <p>The blank-vs-invalid distinction is critical: COBOL
         * {@code WHEN OTHER} (lines 210-214 of
         * {@code app/cbl/COUSR00C.cbl}) emits an error message ONLY for
         * the latter category, not for empty SPACES / LOW-VALUES selections.
         *
         * @param c the indicator byte (typically {@code ' '}, {@code 'U'},
         *          {@code 'u'}, {@code 'D'}, or {@code 'd'})
         * @return the matching {@code UserAction} permit; never
         *         {@code null}
         */
        static UserAction fromIndicator(char c) {
            // Blank or LOW-VALUES → silent fallthrough (no selection).
            // The compact constructor of CoUsr00Input already normalised
            // null Java strings to "" for raw BMS fields; an empty raw
            // selection string maps to a single ' ' character here at the
            // input-decoding layer.
            if (c == ' ' || c == '\0') {
                return None.INSTANCE;
            }
            return switch (Character.toUpperCase(c)) {
                case 'U' -> Update.INSTANCE;
                case 'D' -> Delete.INSTANCE;
                // Any other character (e.g. 'X', '1', '?') is an invalid
                // selection per COBOL WHEN OTHER. Preserve the original
                // (pre-uppercase) byte so diagnostic logging and golden
                // captures can show exactly what the operator typed.
                default  -> new Invalid(c);
            };
        }

        /** No selection. The row has SPACES or LOW-VALUES in the SEL column. */
        record None() implements UserAction {
            /** Canonical singleton instance. */
            public static final None INSTANCE = new None();

            @Override public char indicator() { return ' '; }
        }

        /** Update selection ({@code 'U'} / {@code 'u'}). XCTL to COUSR02C. */
        record Update() implements UserAction {
            /** Canonical singleton instance. */
            public static final Update INSTANCE = new Update();

            @Override public char indicator() { return 'U'; }
        }

        /** Delete selection ({@code 'D'} / {@code 'd'}). XCTL to COUSR03C. */
        record Delete() implements UserAction {
            /** Canonical singleton instance. */
            public static final Delete INSTANCE = new Delete();

            @Override public char indicator() { return 'D'; }
        }

        /**
         * Invalid selection &mdash; a non-blank, non-U/D byte. Restored
         * per M16 to preserve COBOL {@code WHEN OTHER} behaviour from
         * {@code app/cbl/COUSR00C.cbl} lines 210-214, which emit
         * {@code "Invalid selection. Valid values are U and D"} into
         * {@code WS-MESSAGE} when the {@code CDEMO-CU00-USR-SEL-FLG}
         * holds any byte other than space, LOW-VALUES, U, u, D, or d.
         *
         * <p>The original {@link #indicator} component carries the raw
         * (pre-uppercase) byte so that downstream consumers &mdash; in
         * particular byte-for-byte golden-record parity tests &mdash;
         * can observe and assert exactly what the operator typed.
         *
         * @param indicator the raw single-character byte the operator
         *                  typed into the {@code SEL0nnI} field; never
         *                  {@code ' '} or {@code '\0'} (those map to
         *                  {@link None}) and never {@code 'U'} /
         *                  {@code 'u'} / {@code 'D'} / {@code 'd'}
         *                  (those map to {@link Update} / {@link Delete})
         */
        record Invalid(char indicator) implements UserAction {
            // Compact canonical body validates the precondition that
            // Invalid is reserved for genuinely invalid bytes. The check
            // mirrors the fromIndicator factory's contract; if a caller
            // were to construct Invalid(' ') or Invalid('U') directly,
            // exhaustive switches would behave incorrectly downstream.
            public Invalid {
                if (indicator == ' ' || indicator == '\0') {
                    throw new IllegalArgumentException(
                        "Invalid permit reserved for non-blank bytes; got blank/LOW-VALUES");
                }
                char up = Character.toUpperCase(indicator);
                if (up == 'U' || up == 'D') {
                    throw new IllegalArgumentException(
                        "Invalid permit reserved for non-U/non-D bytes; got '" + indicator + "'");
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // AidKey enum
    // -----------------------------------------------------------------------

    /**
     * AID-key dispatch alias for the COUSR00C transaction.
     *
     * <p>Translates the {@code EVALUATE EIBAID} block in
     * {@code app/cbl/COUSR00C.cbl} (MAIN-PARA paragraph, lines 118-132).
     * This enum is declared at the {@code CoUsr00C} level (rather than
     * reused from {@link CoUsr00Input#aidKey()}) so that the public API
     * of the use case exposes a stable, schema-mandated contract
     * regardless of how the BMS input DTO encodes the AID key &mdash;
     * for example, the equivalent enum on {@code CoUsr00Input} uses the
     * legacy names {@code PF07_PREV} / {@code PF08_NEXT} while this
     * enum uses the schema-mandated names {@link #PF07_PAGE_BACK} /
     * {@link #PF08_PAGE_FWD}.
     */
    public enum AidKey {
        /** {@code DFHENTER}: process row selection / refresh from search. */
        ENTER,
        /** {@code DFHPF3}: back to admin menu (COADM01C). */
        PF03_BACK,
        /** {@code DFHPF7}: page backward. */
        PF07_PAGE_BACK,
        /** {@code DFHPF8}: page forward. */
        PF08_PAGE_FWD,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid key error). */
        OTHER
    }

    // -----------------------------------------------------------------------
    // Outcome sealed interface
    // -----------------------------------------------------------------------

    /**
     * Result of a {@link #execute(CardDemoCommarea, byte[], AidKey,
     * CoUsr00Input)} invocation. Exactly one of the two permits is
     * returned per call.
     *
     * <p>Mirrors the CICS dispatch dichotomy in
     * {@code app/cbl/COUSR00C.cbl}:
     * <ul>
     *   <li>{@code EXEC CICS SEND MAP(...) MAPSET(...) ... RETURN}
     *       &rarr; {@link SendMap} carrying the populated
     *       {@link CoUsr00Output} to be sent to the 3270 terminal and
     *       the outbound commarea.</li>
     *   <li>{@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)}
     *       &rarr; {@link Xctl} carrying the target program id and the
     *       outbound commarea.</li>
     * </ul>
     *
     * <p>Both permits also carry the program-specific bytes (see
     * {@link PagingState}) which the dispatch layer threads back into
     * the next call to preserve paging state across RECEIVE-MAP /
     * SEND-MAP cycles. For {@link Xctl} to {@code COUSR02C} /
     * {@code COUSR03C}, the bytes carry the selected user-id (8 ASCII
     * chars padded with trailing spaces).
     *
     * <p>Per AAP &sect;0.7.4 switch over {@code Outcome} must be
     * exhaustive with NO {@code default} branch.
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * Outcome corresponding to an {@code EXEC CICS SEND MAP} followed
         * by {@code EXEC CICS RETURN TRANSID(LIT-THIS-TRAN-ID) COMMAREA(...)}.
         *
         * @param output            the populated BMS output DTO; non-null
         * @param commarea          the outbound commarea (FROM-TRANID /
         *                          FROM-PROGRAM / LAST-MAP / LAST-MAPSET
         *                          / PgmContext already set); non-null
         * @param progSpecificBytes the encoded {@link PagingState} to
         *                          thread back through the next call;
         *                          non-null (may be empty if no paging
         *                          state)
         */
        record SendMap(CoUsr00Output output,
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
         *                          non-null. For XCTL to COUSR02C /
         *                          COUSR03C this carries the selected
         *                          user-id (8 ASCII chars). For XCTL to
         *                          COADM01C / COSGN00C this is empty
         *                          (the target programs do not consume
         *                          paging state).
         */
        record Xctl(String targetProgram,
                    CardDemoCommarea commarea,
                    byte[] progSpecificBytes) implements Outcome { }
    }

    // -----------------------------------------------------------------------
    // PagingState internal record
    // -----------------------------------------------------------------------

    /**
     * Internal record holding paging state for COUSR00 across multiple
     * RECEIVE-MAP / SEND-MAP cycles.
     *
     * <p>Translates the COBOL {@code CDEMO-CU00-INFO} 05-level group
     * (declared at {@code app/cbl/COUSR00C.cbl} lines 67-75) which the
     * COBOL program carries inside the shared {@code CARDDEMO-COMMAREA}.
     * The Java {@link CardDemoCommarea} record does NOT carry a
     * program-specific extension area (it has a fixed 160-byte layout
     * per AAP &sect;0.4.1), so this state is encoded into the
     * {@code progSpecificBytes} parameter of
     * {@link #execute(CardDemoCommarea, byte[], AidKey, CoUsr00Input)}
     * via the {@link #encode()} / {@link #decode(byte[])} codec.
     *
     * <p><strong>Encoding layout</strong> (21 bytes total, big-endian):
     * <pre>
     *   bytes  0..3  : pageNum (int, big-endian)
     *   bytes  4..11 : userIdFirst (8 chars ASCII, space-padded)
     *   bytes 12..19 : userIdLast  (8 chars ASCII, space-padded)
     *   byte  20     : nextPageYes (1 = true, 0 = false)
     * </pre>
     *
     * <p>Per AAP &sect;0.6.3 the compact constructor uses JEP 513
     * Flexible Constructor Bodies to validate and normalize fields
     * before binding.
     *
     * @param pageNum     the current 1-based page number (0 = uninitialized
     *                    / first-time entry)
     * @param userIdFirst the user-id of the first row on the current page
     *                    (corresponds to COBOL {@code CDEMO-CU00-USRID-FIRST});
     *                    space-padded to 8 chars; empty string for "no
     *                    first row known yet"
     * @param userIdLast  the user-id of the last row on the current page
     *                    (corresponds to COBOL {@code CDEMO-CU00-USRID-LAST});
     *                    space-padded to 8 chars; empty string for "no
     *                    last row known yet"
     * @param nextPageYes whether a next page is available (corresponds to
     *                    COBOL {@code NEXT-PAGE-YES}); used to short-circuit
     *                    PF8 when at the bottom
     */
    record PagingState(int pageNum,
                       String userIdFirst,
                       String userIdLast,
                       boolean nextPageYes) {

        /** Encoded byte length of a {@code PagingState} payload. */
        static final int ENCODED_LENGTH = 4 + LEN_USER_ID + LEN_USER_ID + 1;

        /**
         * Compact constructor normalizing fields. Per JEP 513 (Flexible
         * Constructor Bodies, finalized in Java 25), normalization runs
         * before the canonical field bindings.
         */
        PagingState {
            if (pageNum < 0) {
                pageNum = 0;
            }
            userIdFirst = (userIdFirst == null) ? "" : userIdFirst;
            userIdLast  = (userIdLast == null)  ? "" : userIdLast;
            if (userIdFirst.length() > LEN_USER_ID) {
                userIdFirst = userIdFirst.substring(0, LEN_USER_ID);
            }
            if (userIdLast.length() > LEN_USER_ID) {
                userIdLast = userIdLast.substring(0, LEN_USER_ID);
            }
        }

        /** Returns the canonical initial paging state (page 0, no keys). */
        static PagingState initial() {
            return new PagingState(0, "", "", false);
        }

        /**
         * Decodes a {@code PagingState} from a {@code progSpecificBytes}
         * payload. Returns {@link #initial()} if the buffer is
         * {@code null} or shorter than {@link #ENCODED_LENGTH}.
         *
         * @param bytes the payload (may be {@code null})
         * @return the decoded state; never {@code null}
         */
        static PagingState decode(byte[] bytes) {
            if (bytes == null || bytes.length < ENCODED_LENGTH) {
                return initial();
            }
            ByteBuffer buf = ByteBuffer.wrap(bytes, 0, ENCODED_LENGTH);
            int p = buf.getInt();
            byte[] firstBytes = new byte[LEN_USER_ID];
            buf.get(firstBytes);
            byte[] lastBytes = new byte[LEN_USER_ID];
            buf.get(lastBytes);
            boolean next = (buf.get() != 0);
            String first = stripTrailing(new String(firstBytes, StandardCharsets.US_ASCII));
            String last  = stripTrailing(new String(lastBytes,  StandardCharsets.US_ASCII));
            return new PagingState(p, first, last, next);
        }

        /**
         * Encodes this state to a {@code progSpecificBytes} payload of
         * exactly {@link #ENCODED_LENGTH} bytes.
         *
         * @return the encoded payload (newly allocated, never shared)
         */
        byte[] encode() {
            ByteBuffer buf = ByteBuffer.allocate(ENCODED_LENGTH);
            buf.putInt(pageNum);
            buf.put(padRight(userIdFirst, LEN_USER_ID).getBytes(StandardCharsets.US_ASCII));
            buf.put(padRight(userIdLast,  LEN_USER_ID).getBytes(StandardCharsets.US_ASCII));
            buf.put((byte) (nextPageYes ? 1 : 0));
            return buf.array();
        }

        /** Returns a copy of this state with a new page number. */
        PagingState withPageNum(int newPageNum) {
            return new PagingState(newPageNum, userIdFirst, userIdLast, nextPageYes);
        }

        /** Returns a copy of this state with new first/last keys. */
        PagingState withRange(String newFirst, String newLast) {
            return new PagingState(pageNum, newFirst, newLast, nextPageYes);
        }

        /** Returns a copy of this state with a new next-page flag. */
        PagingState withNextPageYes(boolean flag) {
            return new PagingState(pageNum, userIdFirst, userIdLast, flag);
        }
    }

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    /**
     * USRSEC repository port &mdash; translates VSAM file access
     * (STARTBR / READNEXT / READPREV / ENDBR) for the
     * {@code app/cbl/COUSR00C.cbl} program. Injected at construction
     * time; never re-bound at runtime.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Program registry for dynamic CALL dispatch. Held for consistency
     * with the canonical constructor signature shared by every
     * translated PROGRAM-ID (matching {@code CoUsr01C} / {@code CoUsr02C}
     * / {@code CoUsr03C}); the COUSR00C source uses only static XCTL
     * targets (COADM01C, COUSR02C, COUSR03C, COSGN00C) so this field is
     * reserved for future expansion. Per AAP &sect;0.7.1, kept as a
     * constructor parameter to preserve the program-level injection
     * contract.
     */
    @SuppressWarnings("unused")
    private final ProgramRegistry programRegistry;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@code CoUsr00C} bound to the supplied repository port
     * and program registry. Both arguments are required (no nullable
     * defaults) &mdash; per AAP &sect;0.7.3 there is no Spring container
     * and no field-injection.
     *
     * @param userSecurityRepository the USRSEC port; must be non-null
     * @param programRegistry        the program registry; must be non-null
     * @throws NullPointerException if either argument is {@code null}
     */
    public CoUsr00C(UserSecurityRepository userSecurityRepository,
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
     * <p>Dispatch sequence (matching {@code app/cbl/COUSR00C.cbl} lines
     * 96-145):
     * <ol>
     *   <li>If {@code commareaIn == null}: COBOL would XCTL back to
     *       {@code COSGN00C} (the {@code IF EIBCALEN = 0} guard at lines
     *       109-111). The Java equivalent returns
     *       {@link Outcome.Xctl} pointing at {@link #LIT_SIGNON_PGM}.</li>
     *   <li>Otherwise, materialize the inbound commarea and decode the
     *       program-specific state into a {@link PagingState}.</li>
     *   <li>If the PgmContext is NOT {@link PgmContext#REENTER} (this is
     *       a first-time entry from another program, e.g. the admin
     *       menu): set PgmContext to {@code REENTER}, run the initial
     *       forward scan from LOW-VALUES with page-num=0, and emit a
     *       SendMap (matching COBOL lines 113-119:
     *       {@code IF NOT CDEMO-PGM-REENTER ... PERFORM PROCESS-ENTER-KEY
     *       PERFORM SEND-USRLST-SCREEN}).</li>
     *   <li>Otherwise (the REENTER branch, COBOL lines 120-133): pattern-match
     *       on the AID key and dispatch to the appropriate state-handler
     *       method.</li>
     * </ol>
     *
     * <p>Per AAP &sect;0.7.3 the switch over {@link AidKey} uses
     * pattern-matching with no {@code default} branch &mdash; every
     * enum value is handled explicitly.
     *
     * @param commareaIn        inbound commarea; may be {@code null} on
     *                          first-time entry from a clear screen
     *                          (equivalent to {@code EIBCALEN = 0})
     * @param progSpecificBytes encoded {@link PagingState} from the
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
                           CoUsr00Input input) {
        Objects.requireNonNull(aidKey, "aidKey");
        Objects.requireNonNull(input, "input");

        // COBOL IF EIBCALEN = 0 guard: XCTL back to signon. This is a
        // CICS-era defense; in Java the dispatch is always programmatic,
        // but we preserve the faithful behavior per AAP §0.7.1.
        if (commareaIn == null) {
            return returnToSignon();
        }

        // Always stamp FROM-TRANID / FROM-PROGRAM on the outbound commarea
        // so the CICS XCTL chain can trace the caller path; translates the
        // COBOL idiom of MOVE WS-TRANID TO CDEMO-FROM-TRANID at the top
        // of MAIN-PARA.
        CardDemoCommarea commarea = stampFromIdentity(commareaIn);

        PagingState paging = PagingState.decode(progSpecificBytes);

        // First-time entry path: PgmContext is NOT Reenter. Set Reenter,
        // run the initial forward scan from LOW-VALUES with page-num=0,
        // and emit a SendMap.
        if (!commarea.cdemoGeneralInfo().pgmContext().isReenter()) {
            commarea = withPgmContext(commarea, PgmContext.REENTER);
            // INITIAL forward scan from LOW-VALUES (empty start key),
            // page-num=0, includeStart=true (first READNEXT after STARTBR
            // returns the first row >= start key, i.e. the lowest record).
            return readForwardPage(commarea,
                    paging.withPageNum(0),
                    /* startKey */ "",
                    /* skipStart */ false,
                    /* errorMessage */ null);
        }

        // Re-entry path: dispatch on the AID key.
        return switch (aidKey) {
            case ENTER          -> processEnterKey(commarea, paging, input);
            case PF03_BACK      -> returnToAdminMenu(commarea);
            case PF07_PAGE_BACK -> processPageBack(commarea, paging);
            case PF08_PAGE_FWD  -> processPageForward(commarea, paging);
            case OTHER          -> rerenderCurrentPage(commarea, paging,
                                                /* searchEcho */ input.searchUserId(),
                                                MSG_INVALID_KEY);
        };
    }

    // -----------------------------------------------------------------------
    // PROCESS-ENTER-KEY
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code PROCESS-ENTER-KEY} ({@code app/cbl/COUSR00C.cbl}
     * lines 147-232).
     *
     * <p>Two-step algorithm:
     * <ol>
     *   <li><strong>Selection scan</strong>: iterate rows 1-10 in order;
     *       find the FIRST row whose {@code selection} field is non-blank
     *       and non-low-values. The COBOL {@code EVALUATE TRUE} cascade
     *       at lines 152-186 enumerates all 10 rows individually, but
     *       the semantic is "first match wins".</li>
     *   <li><strong>Selection dispatch</strong>: if a selection was found
     *       AND the selected user-id is non-blank:
     *       <ul>
     *         <li>{@code 'U'} / {@code 'u'} &rarr; XCTL to {@link #LIT_USR_UPDATE_PGM}
     *             ({@code COUSR02C}) with the selected user-id in
     *             progSpecificBytes</li>
     *         <li>{@code 'D'} / {@code 'd'} &rarr; XCTL to {@link #LIT_USR_DELETE_PGM}
     *             ({@code COUSR03C}) with the selected user-id in
     *             progSpecificBytes</li>
     *         <li>Any other selection char &rarr; set
     *             {@link #MSG_INVALID_SELECTION} and fall through to a
     *             page-forward re-scan from {@code USRIDINI} (this is
     *             literally what the COBOL does: the
     *             {@code WHEN OTHER} branch sets the message but does
     *             NOT XCTL, so control falls through to the
     *             {@code IF USRIDINI = SPACES} check below).</li>
     *       </ul></li>
     * </ol>
     *
     * <p>After selection dispatch (or no selection found): the COBOL
     * code unconditionally does:
     * <pre>
     *   IF USRIDINI = SPACES OR LOW-VALUES
     *       MOVE LOW-VALUES TO SEC-USR-ID
     *   ELSE
     *       MOVE USRIDINI TO SEC-USR-ID
     *   END-IF
     *   MOVE 0 TO CDEMO-CU00-PAGE-NUM
     *   PERFORM PROCESS-PAGE-FORWARD
     * </pre>
     * I.e. a forward scan from the search key (or LOW-VALUES if blank),
     * with page-num reset to 0.
     *
     * @param commarea the outbound commarea
     * @param paging   the current paging state
     * @param input    the input DTO from RECEIVE-MAP
     * @return the next dispatch outcome; never {@code null}
     */
    private Outcome processEnterKey(CardDemoCommarea commarea,
                                    PagingState paging,
                                    CoUsr00Input input) {
        // ---- Step 1: scan rows 1-10 for first non-None selection ----
        // The selections list carries exactly PAGE_SIZE entries (per the
        // CoUsr00Input compact constructor); we look for the first entry
        // that is NOT UserAction.None — i.e. Update, Delete, or Invalid
        // (the latter restored per M16 to preserve COBOL WHEN OTHER
        // fidelity from app/cbl/COUSR00C.cbl lines 210-214).
        int selRow = firstSelectedRow(input);

        // M16 — buffer for an error message that defers to the forward
        // page scan rather than triggering an XCTL. Set when the first
        // selected row holds a UserAction.Invalid byte.
        String pendingErrorMessage = null;

        if (selRow >= 0) {
            // Recover the selected user-id by re-iterating the current
            // page from USRSEC (positioned at paging.userIdFirst()).
            // This replaces the COBOL idiom of reading USRID0nI from the
            // BMS symbolic map: the BMS echo carried the row labels
            // already shown to the operator, but we instead trust the
            // backing store and the externalised paging cursor, which
            // is robust against BMS / network anomalies and matches the
            // closed-taxonomy design of UserAction.
            CoUsr00C.UserAction action = input.selections().get(selRow);
            String selectedUserId = lookupUserIdAtRowIndex(paging, selRow);

            if (!selectedUserId.isEmpty()) {
                switch (action) {
                    case UserAction.Update u -> {
                        return xctlToUpdate(commarea, selectedUserId);
                    }
                    case UserAction.Delete d -> {
                        return xctlToDelete(commarea, selectedUserId);
                    }
                    case UserAction.Invalid inv -> {
                        // M16 — COBOL WHEN OTHER fidelity. Lines 210-214 of
                        // app/cbl/COUSR00C.cbl move the literal "Invalid
                        // selection. Valid values are U and D" into
                        // WS-MESSAGE and reposition the cursor to USRIDINL,
                        // but do NOT XCTL. Control falls through to the
                        // IF USRIDINI = SPACES / forward-scan block below.
                        // We mirror that exactly: set the deferred error
                        // message, then continue to Step 2 which rerenders
                        // the current page via readForwardPage.
                        pendingErrorMessage = MSG_INVALID_SELECTION;
                    }
                    case UserAction.None n -> {
                        // Unreachable: firstSelectedRow only returns
                        // indices of non-None entries. The compiler
                        // requires this case for exhaustiveness on the
                        // sealed UserAction hierarchy (AAP §0.1.3 closed
                        // taxonomies).
                    }
                }
            }
            // If selectedUserId was empty (the page produced fewer rows
            // than expected — e.g. a race with concurrent USRSEC delete),
            // fall through to a forward scan from the search prefix below.
            // This preserves the COBOL behavior where an unrecognized or
            // already-empty selection rolls forward into the page scan.
        }

        // ---- Step 2: forward scan from USRIDINI (or LOW-VALUES if blank) ----
        // page-num=0, includeStart=true (the first READNEXT after STARTBR
        // returns the first row >= start-key inclusive). When the operator
        // typed a search prefix, this is the GTEQ positioning. When blank,
        // start at LOW-VALUES (the file's first record).
        //
        // M16 — pendingErrorMessage is non-null only when the first
        // selected row's action permit was UserAction.Invalid. Passing it
        // here causes readForwardPage / finishForwardScan to overlay the
        // "Invalid selection. Valid values are U and D" message onto the
        // rerendered page output, exactly as the COBOL WHEN OTHER branch
        // does via WS-MESSAGE.
        String searchKey = trim(input.searchUserId());
        return readForwardPage(commarea,
                paging.withPageNum(0),
                /* startKey */ searchKey,
                /* skipStart */ false,
                /* errorMessage */ pendingErrorMessage);
    }

    /**
     * Returns the 0-based index of the first row in {@code input} whose
     * selection action is NOT {@link UserAction.None} &mdash; i.e. the
     * first row where the operator typed a non-blank, non-LOW-VALUES
     * character into the corresponding {@code SEL0nnI} field. Mirrors
     * the COBOL {@code EVALUATE TRUE} cascade at
     * {@code app/cbl/COUSR00C.cbl} lines 152-186 which uses
     * "first match wins" semantics.
     *
     * <p>Per the closed-taxonomy design (AAP &sect;0.1.3 + M16
     * restoration), a selection character maps as follows:
     * <ul>
     *   <li>{@code 'U'} / {@code 'u'} &rarr; {@link UserAction.Update}
     *       (selected, valid)</li>
     *   <li>{@code 'D'} / {@code 'd'} &rarr; {@link UserAction.Delete}
     *       (selected, valid)</li>
     *   <li>Any other non-blank, non-LOW-VALUES byte (e.g. {@code 'X'},
     *       {@code '1'}, {@code '?'}) &rarr; {@link UserAction.Invalid}
     *       (selected, but invalid &mdash; triggers
     *       {@link #MSG_INVALID_SELECTION} on the rerendered page per
     *       COBOL WHEN OTHER fidelity)</li>
     *   <li>SPACES / LOW-VALUES &rarr; {@link UserAction.None} (not selected)</li>
     * </ul>
     *
     * <p>The "first match" returned by this method is the first row that
     * is <em>selected</em> in any of those three valid-or-invalid senses
     * (Update, Delete, or Invalid). The caller ({@link #processEnterKey})
     * then exhaustively switches on the permit to decide between XCTL
     * (Update / Delete), error rerender (Invalid), and the impossible
     * None branch (kept only to satisfy compile-time exhaustiveness).
     *
     * @param input the input DTO
     * @return the 0-based row index of the first selected row, or
     *         {@code -1} if no row has any selection (all rows None)
     */
    private static int firstSelectedRow(CoUsr00Input input) {
        List<CoUsr00C.UserAction> selections = input.selections();
        for (int i = 0; i < selections.size(); i++) {
            // pattern-matching switch with the sealed UserAction
            // hierarchy: any permit other than None is a "selection"
            // (Update / Delete / Invalid all qualify).
            CoUsr00C.UserAction action = selections.get(i);
            if (!(action instanceof CoUsr00C.UserAction.None)) {
                return i;
            }
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // PROCESS-PF7-KEY (page backward)
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code PROCESS-PF7-KEY} ({@code app/cbl/COUSR00C.cbl}
     * lines 236-257):
     * <pre>
     *   IF CDEMO-CU00-USRID-FIRST = SPACES OR LOW-VALUES
     *       MOVE LOW-VALUES TO SEC-USR-ID
     *   ELSE
     *       MOVE CDEMO-CU00-USRID-FIRST TO SEC-USR-ID
     *   END-IF
     *   SET NEXT-PAGE-YES TO TRUE
     *   IF CDEMO-CU00-PAGE-NUM &gt; 1
     *       PERFORM PROCESS-PAGE-BACKWARD
     *   ELSE
     *       MOVE 'You are already at the top of the page...' TO WS-MESSAGE
     *       PERFORM SEND-USRLST-SCREEN
     *   END-IF
     * </pre>
     *
     * <p>The PF7 path always sets NEXT-PAGE-YES=TRUE before deciding (because
     * after a successful backward page, the next forward page is the page we
     * just came from, so it exists).
     */
    private Outcome processPageBack(CardDemoCommarea commarea, PagingState paging) {
        // SET NEXT-PAGE-YES TO TRUE (unconditional in COBOL).
        PagingState withNextYes = paging.withNextPageYes(true);

        // If currently on page 1 (or page 0 which is unusual), already at top.
        if (paging.pageNum() <= 1) {
            return buildSendMap(commarea, withNextYes, List.of(), "", MSG_AT_TOP);
        }

        // The start key is CDEMO-CU00-USRID-FIRST. Backward scan reads the 10
        // records with secUsrId() strictly LESS than this key (the records on
        // the previous page).
        String startKey = trim(paging.userIdFirst());
        return readBackwardPage(commarea, withNextYes, startKey);
    }

    // -----------------------------------------------------------------------
    // PROCESS-PF8-KEY (page forward)
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code PROCESS-PF8-KEY} ({@code app/cbl/COUSR00C.cbl}
     * lines 261-277):
     * <pre>
     *   IF CDEMO-CU00-USRID-LAST = SPACES OR LOW-VALUES
     *       MOVE HIGH-VALUES TO SEC-USR-ID
     *   ELSE
     *       MOVE CDEMO-CU00-USRID-LAST TO SEC-USR-ID
     *   END-IF
     *   IF NEXT-PAGE-YES
     *       PERFORM PROCESS-PAGE-FORWARD
     *   ELSE
     *       MOVE 'You are already at the bottom of the page...' TO WS-MESSAGE
     *       PERFORM SEND-USRLST-SCREEN
     *   END-IF
     * </pre>
     *
     * <p>The PROCESS-PAGE-FORWARD paragraph (called from here) skips the
     * first READNEXT result when the EIBAID is NOT one of
     * {@code DFHENTER / DFHPF7 / DFHPF3}, i.e. when it IS {@code DFHPF8}.
     * Translation: we pass {@code skipStart=true} so the forward scan
     * begins at the record STRICTLY GREATER than the start key.
     */
    private Outcome processPageForward(CardDemoCommarea commarea, PagingState paging) {
        // If NEXT-PAGE-NO (set by the previous PROCESS-PAGE-FORWARD when the
        // EOF was reached), already at bottom — no scan, just re-render.
        if (!paging.nextPageYes()) {
            return buildSendMap(commarea, paging, List.of(), "", MSG_AT_BOTTOM);
        }

        // The start key is CDEMO-CU00-USRID-LAST. PF8 forward scan reads the
        // 10 records with secUsrId() STRICTLY greater than this key (the next
        // page). If USRID-LAST is empty (blank/low-values), the COBOL moves
        // HIGH-VALUES which causes the STARTBR to position past end-of-file —
        // i.e. nothing to read. We emulate that with an empty start key and
        // skipStart=true: the dropWhile will exclude every record with id <=
        // empty (no records match, so we get an empty page).
        String startKey = trim(paging.userIdLast());
        return readForwardPage(commarea, paging,
                /* startKey */ startKey,
                /* skipStart */ true,
                /* errorMessage */ null);
    }

    // -----------------------------------------------------------------------
    // PROCESS-PAGE-FORWARD (STARTBR + READNEXT loop)
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code PROCESS-PAGE-FORWARD} ({@code app/cbl/COUSR00C.cbl}
     * lines 281-331). Reads up to 10 records forward from {@code startKey}
     * via {@link UserSecurityRepository#streamFrom(String)}, then peeks
     * one beyond to determine {@code NEXT-PAGE-YES}.
     *
     * <p>Equivalent COBOL CICS flow:
     * <pre>
     *   PERFORM STARTBR-USER-SEC-FILE                   (open browse)
     *   IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3
     *       PERFORM READNEXT-USER-SEC-FILE              (skip start key on PF8)
     *   PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10
     *       PERFORM INITIALIZE-USER-DATA                (blank all 10 rows)
     *   END-PERFORM
     *   MOVE 1 TO WS-IDX
     *   PERFORM UNTIL WS-IDX &gt;= 11 OR USER-SEC-EOF OR ERR-FLG-ON
     *       PERFORM READNEXT-USER-SEC-FILE              (read up to 10)
     *       IF USER-SEC-NOT-EOF
     *           PERFORM POPULATE-USER-DATA
     *           WS-IDX = WS-IDX + 1
     *   END-PERFORM
     *   IF USER-SEC-NOT-EOF                             (peek one more)
     *       PAGE-NUM = PAGE-NUM + 1
     *       PERFORM READNEXT-USER-SEC-FILE
     *       SET NEXT-PAGE-YES/NO based on result
     *   ELSE
     *       SET NEXT-PAGE-NO
     *       IF WS-IDX &gt; 1: PAGE-NUM = PAGE-NUM + 1
     *   END-IF
     *   PERFORM ENDBR-USER-SEC-FILE                     (close browse)
     *   PERFORM SEND-USRLST-SCREEN
     * </pre>
     *
     * <p>Java translation uses try-with-resources on the
     * {@link java.util.stream.Stream} returned from
     * {@link UserSecurityRepository#streamFrom(String)} (the
     * {@code STARTBR}+{@code ENDBR} pair becomes a single AutoCloseable
     * scope); the {@code READNEXT} loop is a take-while/limit on the
     * stream; the peek is the next element after the page.
     *
     * @param commarea     the outbound commarea
     * @param paging       the current paging state
     * @param startKey     the start key for the STARTBR (empty = LOW-VALUES)
     * @param skipStart    whether to skip the record at the start key
     *                     (PF8 = true; ENTER / first-entry = false)
     * @param errorMessage an optional pre-existing error message to
     *                     overlay (e.g. MSG_INVALID_SELECTION from
     *                     PROCESS-ENTER-KEY's fall-through); {@code null}
     *                     means no overlay
     * @return the SendMap outcome with the populated row list; never
     *         {@code null}
     */
    private Outcome readForwardPage(CardDemoCommarea commarea,
                                    PagingState paging,
                                    String startKey,
                                    boolean skipStart,
                                    String errorMessage) {
        List<SecUserData> users = new ArrayList<>(PAGE_SIZE);
        boolean hasMore;
        try (java.util.stream.Stream<SecUserData> stream =
                     userSecurityRepository.streamFrom(startKey)) {
            // Build a one-shot iterator-like consumption by collecting up to
            // PAGE_SIZE + 1 records (the +1 is the "peek" for NEXT-PAGE-YES).
            int collected = 0;
            for (SecUserData u : (Iterable<SecUserData>) stream::iterator) {
                if (skipStart && collected == 0
                        && !startKey.isEmpty()
                        && trim(u.secUsrId()).equals(startKey)) {
                    // Skip the start key itself (PF8 semantics).
                    collected++;
                    continue;
                }
                if (users.size() < PAGE_SIZE) {
                    users.add(u);
                    collected++;
                } else {
                    // We just hit the (PAGE_SIZE + 1)-th record — this is
                    // the COBOL "peek READNEXT" that sets NEXT-PAGE-YES.
                    return finishForwardScan(commarea, paging, users, true, errorMessage);
                }
            }
        } catch (RuntimeException unexpected) {
            log.error("CICS RESP unexpected on USRSEC forward browse from key '{}'",
                      startKey, unexpected);
            // COBOL "Unable to lookup User..." path — render whatever we got
            // plus the error message.
            return finishForwardScan(commarea, paging, users, false, MSG_LOOKUP_ERROR);
        }
        // Reached end-of-file before peeking a (PAGE_SIZE + 1)-th record.
        hasMore = false;
        return finishForwardScan(commarea, paging, users, hasMore, errorMessage);
    }

    /**
     * Finalizes a forward-scan dispatch: converts the {@link SecUserData}
     * list to {@link CoUsr00Output.UserRow}s, updates the paging state
     * (FIRST/LAST keys, PAGE-NUM, NEXT-PAGE-YES), and builds the SendMap
     * outcome.
     *
     * @param commarea        the outbound commarea
     * @param paging          the current paging state (page-num will be
     *                        incremented if any rows were collected)
     * @param users           the up-to-{@link #PAGE_SIZE} collected users
     *                        (in ascending key order)
     * @param hasMore         whether a peek beyond the page returned a
     *                        record (NEXT-PAGE-YES)
     * @param errorMessage    optional error message overlay
     * @return the SendMap outcome
     */
    private Outcome finishForwardScan(CardDemoCommarea commarea,
                                      PagingState paging,
                                      List<SecUserData> users,
                                      boolean hasMore,
                                      String errorMessage) {
        List<CoUsr00Output.UserRow> rows = toOutputRows(users);
        // COBOL: IF USER-SEC-NOT-EOF (we had a peek) increment page-num and
        // set NEXT-PAGE-YES; ELSE IF WS-IDX > 1 also increment page-num.
        // The two branches are equivalent when "any rows were collected"
        // and the next-page flag is the only difference.
        int newPageNum = paging.pageNum() + (users.isEmpty() ? 0 : 1);
        String first = users.isEmpty() ? "" : trim(users.get(0).secUsrId());
        String last  = users.isEmpty() ? "" : trim(users.get(users.size() - 1).secUsrId());

        // If no rows AND this was a PF8 path past EOF, surface "reached bottom".
        String message = errorMessage;
        if (users.isEmpty() && message == null) {
            message = MSG_REACHED_BOTTOM;
        }

        PagingState newPaging = new PagingState(newPageNum, first, last, hasMore);
        return buildSendMap(commarea, newPaging, rows, "", message);
    }

    // -----------------------------------------------------------------------
    // PROCESS-PAGE-BACKWARD (STARTBR + READPREV loop)
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code PROCESS-PAGE-BACKWARD}
     * ({@code app/cbl/COUSR00C.cbl} lines 334-377). VSAM
     * {@code READPREV} is not natively supported by the
     * {@link UserSecurityRepository} port (whose streams emit in
     * ascending key order), so this method materializes the entire
     * sequential stream and walks the resulting list backward from
     * {@code beforeKey}.
     *
     * <p>Per AAP &sect;0.7.1 this is a {@code DEVIATION} flagged in
     * {@code java/MIGRATION_NOTES.md}: paging-backward materializes the
     * entire USRSEC dataset. Acceptable because USRSEC is small
     * (validated at ~24 entries in current fixtures per AAP &sect;0.6.10).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Materialize the full sequential stream into an array list
     *       (ascending key order).</li>
     *   <li>Find the index {@code idx} of {@code beforeKey} (or the
     *       smallest key &gt;= beforeKey).</li>
     *   <li>Walk backward from {@code idx - 1} collecting up to
     *       {@link #PAGE_SIZE} records into a "page" list, in reverse
     *       (descending key) order.</li>
     *   <li>Reverse the page list so rows appear in ascending key order
     *       (matching how the COBOL POPULATE-USER-DATA sequence ends up
     *       arranging rows after the WS-IDX=10 backward loop).</li>
     *   <li>Peek one more position backward to determine if the previous
     *       page exists. (This is the COBOL "second READPREV" at lines
     *       365-373 used to set NEXT-PAGE-YES on the new page.)</li>
     * </ol>
     *
     * @param commarea  the outbound commarea
     * @param paging    the current paging state (page-num will be
     *                  decremented but not below 1)
     * @param beforeKey the start key (records strictly less than this
     *                  are considered)
     * @return the SendMap outcome with the populated row list; never
     *         {@code null}
     */
    private Outcome readBackwardPage(CardDemoCommarea commarea,
                                     PagingState paging,
                                     String beforeKey) {
        List<SecUserData> all;
        try (java.util.stream.Stream<SecUserData> stream =
                     userSecurityRepository.streamSequential()) {
            all = stream.toList();
        } catch (RuntimeException unexpected) {
            log.error("CICS RESP unexpected on USRSEC sequential browse for PF7", unexpected);
            return buildSendMap(commarea, paging, List.of(), "", MSG_LOOKUP_ERROR);
        }

        if (all.isEmpty()) {
            return buildSendMap(commarea, paging, List.of(), "", MSG_REACHED_TOP);
        }

        // Find the index of the FIRST record with key >= beforeKey (i.e. the
        // position of beforeKey itself, or the next key if beforeKey is not
        // present). We collect records strictly LESS than beforeKey.
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (trim(all.get(i).secUsrId()).compareTo(beforeKey) >= 0) {
                idx = i;
                break;
            }
        }
        // If beforeKey is empty (LOW-VALUES) or all records are >= beforeKey:
        // idx = 0 means no record is strictly less than beforeKey (page is
        // empty); for LOW-VALUES this is correct ("nothing before LOW-VALUES").
        if (idx == -1) {
            // Every record is < beforeKey: take the last PAGE_SIZE records.
            idx = all.size();
        }
        if (idx == 0) {
            // No records strictly less than beforeKey — already at top.
            return buildSendMap(commarea, paging, List.of(), "", MSG_REACHED_TOP);
        }

        // Collect records in the range [max(0, idx - PAGE_SIZE), idx).
        int start = Math.max(0, idx - PAGE_SIZE);
        List<SecUserData> page = new ArrayList<>(all.subList(start, idx));

        // Page is already in ascending key order (we took a contiguous slice).
        List<CoUsr00Output.UserRow> rows = toOutputRows(page);

        // Decrement page-num but not below 1 (COBOL: if CDEMO-CU00-PAGE-NUM > 1
        // SUBTRACT 1 FROM CDEMO-CU00-PAGE-NUM; ELSE MOVE 1 TO CDEMO-CU00-PAGE-NUM).
        int newPageNum = Math.max(1, paging.pageNum() - 1);

        String first = trim(page.get(0).secUsrId());
        String last  = trim(page.get(page.size() - 1).secUsrId());

        // NEXT-PAGE-YES on the new page: there is always a "next" page after
        // a backward step (it's the page we came from), unless the backward
        // page is somehow empty (already handled above). The COBOL keeps the
        // NEXT-PAGE-YES that PROCESS-PF7-KEY set unconditionally — preserve.
        boolean nextPageYes = true;

        PagingState newPaging = new PagingState(newPageNum, first, last, nextPageYes);
        return buildSendMap(commarea, newPaging, rows, "", null);
    }

    // -----------------------------------------------------------------------
    // SEND-USRLST-SCREEN (build SendMap outcome)
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code SEND-USRLST-SCREEN} (lines 524-545)
     * together with {@code POPULATE-HEADER-INFO} (lines 561-580): builds
     * the {@link CoUsr00Output} DTO and wraps it in an {@link Outcome.SendMap}.
     *
     * <p>Header field population (matching POPULATE-HEADER-INFO):
     * <ul>
     *   <li>{@code TITLE01O = CCDA-TITLE01} ({@value #CCDA_TITLE01}, 40 chars)</li>
     *   <li>{@code TITLE02O = CCDA-TITLE02} ({@value #CCDA_TITLE02}, 40 chars)</li>
     *   <li>{@code TRNNAMEO = WS-TRANID} ({@value #LIT_THIS_TRAN_ID})</li>
     *   <li>{@code PGMNAMEO = WS-PGMNAME} ({@value #LIT_THIS_PGM})</li>
     *   <li>{@code CURDATEO = current date}, format {@code MM/dd/yy}</li>
     *   <li>{@code CURTIMEO = current time}, format {@code HH:mm:ss}</li>
     *   <li>{@code PAGENUMO = page number}, formatted as 8-char left-padded</li>
     * </ul>
     *
     * <p>The outbound commarea is stamped with {@code LAST-MAP =} {@value #LIT_THIS_MAP}
     * and {@code LAST-MAPSET =} {@value #LIT_THIS_MAPSET} so the next
     * dispatch cycle can identify which screen was last rendered. The
     * PgmContext is set to {@link PgmContext#REENTER} so the next entry
     * goes through the AID-key dispatch branch instead of running the
     * initial scan again.
     *
     * @param commarea     the inbound commarea (FROM-* already stamped)
     * @param paging       the paging state to encode into progSpecificBytes
     * @param rows         the user rows (up to {@link #PAGE_SIZE}); shorter
     *                     lists are padded by {@link CoUsr00Output}'s
     *                     compact constructor
     * @param searchEcho   the value to echo in the USRIDINO field; the
     *                     COBOL behavior after a fresh scan is to set this
     *                     to SPACE (empty); pass {@code ""} or {@code null}
     *                     to suppress echo
     * @param errorMessage the message line text; {@code null} means no
     *                     message
     * @return the SendMap outcome; never {@code null}
     */
    private Outcome buildSendMap(CardDemoCommarea commarea,
                                 PagingState paging,
                                 List<CoUsr00Output.UserRow> rows,
                                 String searchEcho,
                                 String errorMessage) {
        // POPULATE-HEADER-INFO: capture current date and time once per
        // SEND-MAP, formatted into the BMS PIC X(8) widths.
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        String dateStr = today.format(DATE_FORMATTER);
        String timeStr = now.format(TIME_FORMATTER);

        // Format page-num as 8-char left-padded numeric (COBOL PIC X(8)
        // with PIC 9(08) in CDEMO-CU00-PAGE-NUM, displayed in PAGENUMO).
        String pageStr = formatPageNum(paging.pageNum());

        // Padding/truncating done by CoUsr00Output's compact constructor.
        List<CoUsr00Output.UserRow> safeRows =
                (rows == null) ? List.<CoUsr00Output.UserRow>of() : rows;

        CoUsr00Output output = new CoUsr00Output(
                LIT_THIS_TRAN_ID,                     // TRNNAMEO  PIC X(4)
                CCDA_TITLE01,                         // TITLE01O  PIC X(40)
                dateStr,                              // CURDATEO  PIC X(8)
                LIT_THIS_PGM,                         // PGMNAMEO  PIC X(8)
                CCDA_TITLE02,                         // TITLE02O  PIC X(40)
                timeStr,                              // CURTIMEO  PIC X(8)
                pageStr,                              // PAGENUMO  PIC X(8)
                clampLen(orEmpty(searchEcho), LEN_USER_ID),  // USRIDINO PIC X(8)
                safeRows,                             // 10 user rows
                clampLen(orEmpty(errorMessage), 78)   // ERRMSGO   PIC X(78)
        );

        // Stamp LAST-MAP / LAST-MAPSET in the outbound commarea.
        CardDemoCommarea outbound = withLastMapInfo(commarea, LIT_THIS_MAP, LIT_THIS_MAPSET);

        return new Outcome.SendMap(output, outbound, paging.encode());
    }

    // -----------------------------------------------------------------------
    // RETURN-TO-PREV-SCREEN
    // -----------------------------------------------------------------------

    /**
     * Builds the XCTL outcome to {@link #LIT_ADMIN_MENU_PGM} (translates
     * COBOL paragraph {@code RETURN-TO-PREV-SCREEN} for the PF3 path).
     * The outbound commarea has TO-TRANID / TO-PROGRAM stamped to
     * {@value #LIT_ADMIN_MENU_TRAN_ID} / {@value #LIT_ADMIN_MENU_PGM} and
     * the program context reset to {@link PgmContext#ENTER} so the admin
     * menu starts in its first-time-entry state.
     *
     * @param commareaIn the inbound commarea (whose FROM-* / USER-ID /
     *                   USER-TYPE fields are preserved)
     * @return an {@link Outcome.Xctl} pointing to COADM01C; never
     *         {@code null}
     */
    private Outcome returnToAdminMenu(CardDemoCommarea commareaIn) {
        CardDemoCommarea.CdemoGeneralInfo gi = commareaIn.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM,     CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padRight(LIT_ADMIN_MENU_TRAN_ID, CardDemoCommarea.LENGTH_TO_TRANID),
                padRight(LIT_ADMIN_MENU_PGM,     CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commareaIn.withCdemoGeneralInfo(updated);
        return new Outcome.Xctl(LIT_ADMIN_MENU_PGM, outbound, new byte[0]);
    }

    /**
     * Builds the XCTL outcome to {@link #LIT_SIGNON_PGM} when
     * {@code commareaIn == null} (translates the COBOL {@code IF EIBCALEN = 0}
     * guard at {@code app/cbl/COUSR00C.cbl} lines 109-111). The outbound
     * commarea is a fresh {@link CardDemoCommarea#empty()} with TO-TRANID
     * / TO-PROGRAM stamped for the signon program.
     *
     * @return an {@link Outcome.Xctl} pointing to COSGN00C; never {@code null}
     */
    private Outcome returnToSignon() {
        CardDemoCommarea empty = CardDemoCommarea.empty();
        CardDemoCommarea.CdemoGeneralInfo gi = empty.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM,     CardDemoCommarea.LENGTH_FROM_PROGRAM),
                gi.toTranId(),
                padRight(LIT_SIGNON_PGM,   CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = empty.withCdemoGeneralInfo(updated);
        return new Outcome.Xctl(LIT_SIGNON_PGM, outbound, new byte[0]);
    }

    /**
     * Builds the XCTL outcome to {@link #LIT_USR_UPDATE_PGM} (COUSR02C)
     * with the selected user-id encoded into the progSpecificBytes.
     * Translates the COBOL XCTL block at {@code app/cbl/COUSR00C.cbl}
     * lines 192-202.
     *
     * @param commareaIn the inbound commarea
     * @param userId     the user-id selected for update (8 chars or less)
     * @return an {@link Outcome.Xctl} pointing to COUSR02C; never {@code null}
     */
    private Outcome xctlToUpdate(CardDemoCommarea commareaIn, String userId) {
        CardDemoCommarea.CdemoGeneralInfo gi = commareaIn.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM,     CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padRight(LIT_USR_UPDATE_TRAN_ID, CardDemoCommarea.LENGTH_TO_TRANID),
                padRight(LIT_USR_UPDATE_PGM,     CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commareaIn.withCdemoGeneralInfo(updated);
        return new Outcome.Xctl(LIT_USR_UPDATE_PGM, outbound, encodeSelectedUser(userId));
    }

    /**
     * Builds the XCTL outcome to {@link #LIT_USR_DELETE_PGM} (COUSR03C)
     * with the selected user-id encoded into the progSpecificBytes.
     * Translates the COBOL XCTL block at {@code app/cbl/COUSR00C.cbl}
     * lines 203-213.
     *
     * @param commareaIn the inbound commarea
     * @param userId     the user-id selected for delete (8 chars or less)
     * @return an {@link Outcome.Xctl} pointing to COUSR03C; never {@code null}
     */
    private Outcome xctlToDelete(CardDemoCommarea commareaIn, String userId) {
        CardDemoCommarea.CdemoGeneralInfo gi = commareaIn.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM,     CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padRight(LIT_USR_DELETE_TRAN_ID, CardDemoCommarea.LENGTH_TO_TRANID),
                padRight(LIT_USR_DELETE_PGM,     CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commareaIn.withCdemoGeneralInfo(updated);
        return new Outcome.Xctl(LIT_USR_DELETE_PGM, outbound, encodeSelectedUser(userId));
    }

    // -----------------------------------------------------------------------
    // 9999-ABEND-PROGRAM
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code 9999-ABEND-PROGRAM}. Logs the
     * abend reason at ERROR level and throws a {@link RuntimeException}
     * carrying the reason and (optional) underlying cause. Per AAP
     * &sect;0.1.3 the plaintext password is never included in the
     * {@code reason} parameter and never logged.
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
    @SuppressWarnings({"SameReturnValue", "unused"})
    private int abendRoutine(String reason, Throwable cause) {
        log.error("CICS abend in COUSR00C: {}", reason, cause);
        throw new RuntimeException("COUSR00C abend: " + reason, cause);
    }

    // -----------------------------------------------------------------------
    // Commarea helpers
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
     * Returns a new commarea with the PgmContext component replaced.
     * Translates the COBOL idiom:
     * <pre>
     *     SET CDEMO-PGM-REENTER TO TRUE
     * </pre>
     *
     * @param commarea       the inbound commarea
     * @param newPgmContext  the new PgmContext value (e.g.
     *                       {@link PgmContext#REENTER})
     * @return a new commarea with the substituted PgmContext; never
     *         {@code null}
     */
    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea,
                                                   PgmContext newPgmContext) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                gi.fromTranId(),
                gi.fromProgram(),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                newPgmContext);
        return commarea.withCdemoGeneralInfo(updated);
    }

    /**
     * Stamps the outbound commarea with the last-rendered MAP / MAPSET
     * names so the next dispatch step can correlate which screen was
     * shown.
     *
     * @param commarea   the inbound commarea
     * @param lastMap    the BMS map name (7 chars before padding)
     * @param lastMapset the BMS mapset name (7 chars before padding)
     * @return a new commarea with MORE-INFO LAST-MAP / LAST-MAPSET set;
     *         never {@code null}
     */
    private static CardDemoCommarea withLastMapInfo(CardDemoCommarea commarea,
                                                    String lastMap,
                                                    String lastMapset) {
        CardDemoCommarea.CdemoMoreInfo updated = new CardDemoCommarea.CdemoMoreInfo(
                padRight(lastMap,    CardDemoCommarea.LENGTH_LAST_MAP),
                padRight(lastMapset, CardDemoCommarea.LENGTH_LAST_MAPSET));
        return commarea.withCdemoMoreInfo(updated);
    }

    // -----------------------------------------------------------------------
    // Row conversion helpers
    // -----------------------------------------------------------------------

    /**
     * Converts a list of {@link SecUserData} into a list of
     * {@link CoUsr00Output.UserRow}s for SEND-MAP rendering. Translates
     * the COBOL paragraph {@code POPULATE-USER-DATA} (lines 380-441):
     * <pre>
     *   MOVE SEC-USR-ID    TO USRID0nI OF COUSR0AI
     *   MOVE SEC-USR-FNAME TO FNAME0nI OF COUSR0AI
     *   MOVE SEC-USR-LNAME TO LNAME0nI OF COUSR0AI
     *   MOVE SEC-USR-TYPE  TO UTYPE0nI OF COUSR0AI
     * </pre>
     * The {@code SELxxxx} (selection) column is populated as empty since
     * COBOL output-side never sets a selection (the operator types it on
     * the input side).
     *
     * <p>Trailing whitespace in the {@link SecUserData} fixed-width
     * fields is stripped because the BMS rendering layer handles padding
     * via the PIC X(n) widths on the output DTO.
     *
     * @param users the source records (in ascending key order)
     * @return a list of up to {@link #PAGE_SIZE} {@link CoUsr00Output.UserRow}
     *         entries; never {@code null}
     */
    private static List<CoUsr00Output.UserRow> toOutputRows(List<SecUserData> users) {
        List<CoUsr00Output.UserRow> rows = new ArrayList<>(users.size());
        for (SecUserData u : users) {
            rows.add(new CoUsr00Output.UserRow(
                    /* selection */ "",
                    /* userId    */ trim(u.secUsrId()),
                    /* firstName */ trim(u.secUsrFname()),
                    /* lastName  */ trim(u.secUsrLname()),
                    /* userType  */ String.valueOf(u.secUsrType())));
        }
        return rows;
    }

    /**
     * Re-renders the <em>current</em> USRSEC page (i.e. the same 10 rows
     * that were last shown to the operator) and emits a {@link Outcome.SendMap}
     * with an optional overlay message. Used for the
     * {@link AidKey#OTHER} dispatch branch where the COBOL
     * {@code WHEN OTHER} handler simply sets the error message and
     * resends the screen (relying on the 3270 terminal to preserve the
     * row labels across the SEND-MAP / RECEIVE-MAP cycle).
     *
     * <p>The Java port cannot rely on BMS-side preservation because the
     * minimal {@link CoUsr00Input} model does not carry the echoed row
     * data (the operator never edits those cells; preserving them on the
     * input DTO would be redundant). Instead, this helper re-fetches the
     * page from USRSEC starting at {@code paging.userIdFirst()} (with
     * {@code skipStart=false}) and yields up to {@link #PAGE_SIZE} rows.
     *
     * <p>The {@code paging} cursor is passed through <em>unchanged</em>
     * &mdash; the operation does not advance {@code pageNum} or rewrite
     * {@code userIdFirst} / {@code userIdLast}; it is purely a re-render.
     *
     * @param commarea     the outbound commarea
     * @param paging       the current paging state (preserved verbatim)
     * @param searchEcho   the search-prefix value to echo into
     *                     {@code USRIDINO} (typically the operator's
     *                     previous input)
     * @param errorMessage the message to overlay (typically
     *                     {@link #MSG_INVALID_KEY})
     * @return the SendMap outcome; never {@code null}
     */
    private Outcome rerenderCurrentPage(CardDemoCommarea commarea,
                                        PagingState paging,
                                        String searchEcho,
                                        String errorMessage) {
        String startKey = trim(paging.userIdFirst());
        List<SecUserData> users = new ArrayList<>(PAGE_SIZE);
        try (java.util.stream.Stream<SecUserData> stream =
                     userSecurityRepository.streamFrom(startKey)) {
            for (SecUserData u : (Iterable<SecUserData>) stream::iterator) {
                if (users.size() >= PAGE_SIZE) {
                    break;
                }
                users.add(u);
            }
        } catch (RuntimeException unexpected) {
            log.error("CICS RESP unexpected on USRSEC re-render from key '{}'",
                      startKey, unexpected);
            // Fall through with whatever rows we managed to collect; the
            // error message overlay (MSG_INVALID_KEY) takes precedence.
        }
        List<CoUsr00Output.UserRow> rows = toOutputRows(users);
        return buildSendMap(commarea, paging, rows, searchEcho, errorMessage);
    }

    /**
     * Looks up the user-id at the given 0-based row index on the current
     * USRSEC page (positioned at {@code paging.userIdFirst()}). Used by
     * {@link #processEnterKey} to recover the {@code USRID0nI} value
     * that COBOL would have read directly from the BMS symbolic map.
     *
     * <p>The Java port re-iterates the backing store rather than trusting
     * the BMS echo because the minimal {@link CoUsr00Input} model
     * intentionally omits the per-row user-id columns &mdash; the
     * operator never edits them, so they are redundant on the input DTO
     * (AAP &sect;0.3.5).
     *
     * <p>If the page produces fewer than {@code rowIndex + 1} entries
     * (which can happen if USRSEC was concurrently mutated between the
     * previous SEND-MAP and the current RECEIVE-MAP), an empty string is
     * returned. The caller treats this as "no selection" and falls
     * through to the page-forward scan, preserving COBOL "selection
     * fall-through" semantics.
     *
     * @param paging   the current paging state
     * @param rowIndex the 0-based row index on the current page
     *                 ({@code 0} = first row, {@code PAGE_SIZE - 1} =
     *                 last row)
     * @return the trimmed user-id at the given row; empty string if the
     *         page did not produce that many rows
     */
    private String lookupUserIdAtRowIndex(PagingState paging, int rowIndex) {
        if (rowIndex < 0) {
            return "";
        }
        String startKey = trim(paging.userIdFirst());
        try (java.util.stream.Stream<SecUserData> stream =
                     userSecurityRepository.streamFrom(startKey)) {
            int idx = 0;
            for (SecUserData u : (Iterable<SecUserData>) stream::iterator) {
                if (idx == rowIndex) {
                    return trim(u.secUsrId());
                }
                idx++;
                if (idx >= PAGE_SIZE) {
                    break;
                }
            }
        } catch (RuntimeException unexpected) {
            log.error("CICS RESP unexpected on USRSEC row lookup at index {} from key '{}'",
                      rowIndex, startKey, unexpected);
        }
        return "";
    }

    /**
     * Encodes a selected user-id as 8 ASCII bytes (space-padded) for
     * passage in {@link Outcome.Xctl#progSpecificBytes()}. The receiving
     * program ({@code COUSR02C} or {@code COUSR03C}) decodes this back to
     * a {@link String} via its own selected-user codec.
     *
     * @param userId the selected user-id (may be shorter than 8 chars)
     * @return an 8-byte ASCII payload (space-padded if shorter)
     */
    private static byte[] encodeSelectedUser(String userId) {
        return padRight((userId == null) ? "" : userId, LEN_USER_ID)
                .getBytes(StandardCharsets.US_ASCII);
    }

    // -----------------------------------------------------------------------
    // String helpers
    // -----------------------------------------------------------------------

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
     * Strips trailing whitespace, with {@code null} normalized to an
     * empty string. Used to translate the COBOL idiom of comparing
     * BMS input against {@code SPACES} after the space-padding of the
     * receiving PIC X(n) field has been stripped.
     *
     * @param s the input string (may be {@code null})
     * @return the input with trailing whitespace removed, or {@code ""}
     *         if input was {@code null}
     */
    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return stripTrailing(s);
    }

    /**
     * Strips trailing whitespace from {@code s}. Equivalent to
     * {@link String#stripTrailing()} but extracted as a private helper
     * for use from within {@link PagingState#decode(byte[])} where the
     * receiver is not yet visible.
     *
     * @param s the input string (must be non-null)
     * @return the input with trailing whitespace removed
     */
    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return (end == s.length()) ? s : s.substring(0, end);
    }

    /**
     * Returns the input string with {@code null} mapped to {@code ""}.
     *
     * @param s the input string (may be {@code null})
     * @return {@code s} if non-null, otherwise {@code ""}
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Truncates {@code s} to at most {@code maxLen} characters. Used to
     * satisfy the BMS PIC X(n) width invariants in {@link CoUsr00Output}'s
     * compact constructor when constructing from values that may have
     * been longer than the declared width.
     *
     * @param s      the input string (must be non-null)
     * @param maxLen the maximum allowed length
     * @return {@code s} if shorter than or equal to {@code maxLen},
     *         otherwise the first {@code maxLen} characters
     */
    private static String clampLen(String s, int maxLen) {
        return (s.length() > maxLen) ? s.substring(0, maxLen) : s;
    }

    /**
     * Formats a page number as an 8-character right-padded numeric string
     * (matching the COBOL PIC 9(08) display semantics used for
     * PAGENUMI/PAGENUMO).
     *
     * @param pageNum the page number (non-negative)
     * @return an 8-char string with the page number left-aligned and
     *         space-padded on the right
     */
    private static String formatPageNum(int pageNum) {
        // COBOL would display as PIC 9(08) right-aligned zero-padded
        // (e.g. "00000001"), but the BMS field is PIC X(8). The COBOL
        // moves CDEMO-CU00-PAGE-NUM (numeric) into PAGENUMI (alpha) which
        // produces zero-padding. Use the same convention.
        int safe = Math.max(0, pageNum);
        return String.format("%08d", safe);
    }

}


