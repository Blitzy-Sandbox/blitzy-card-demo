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
package com.blitzy.carddemo.application.signon;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.commarea.UserType;
import com.blitzy.carddemo.domain.port.UserSecurityRepository;
import com.blitzy.carddemo.domain.record.SecUserData;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.text.SystemMessages;
import com.blitzy.carddemo.domain.validation.DateConstants;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Translation of COBOL program {@code COSGN00C}: the signon screen handler for
 * CICS transaction {@code CC00}.
 *
 * <p>This program authenticates a user against the {@code USRSEC} dataset and
 * routes the authenticated user to either the admin menu ({@code COADM01C}) or
 * the main user menu ({@code COMEN01C}) based on the {@code SEC-USR-TYPE} field
 * of the matched user record (the {@link UserType} sealed taxonomy from AAP
 * &sect;0.6.10).
 *
 * <h2>Source program metadata</h2>
 * <pre>
 *   PROGRAM-ID:      COSGN00C       (see {@link #PROGRAM_NAME})
 *   TRANSACTION:     CC00           (see {@link #TRANSACTION_ID})
 *   USRSEC dataset:  'USRSEC  '     (see {@link #USRSEC_FILE})
 *   Admin target:    COADM01C       (see {@link #ADMIN_MENU_PROGRAM})
 *   User target:     COMEN01C       (see {@link #USER_MENU_PROGRAM})
 *   WS-USER-ID len:  8              (PIC X(08))
 *   WS-USER-PWD len: 8              (PIC X(08))
 * </pre>
 *
 * <h2>COBOL &rarr; Java translation rules applied</h2>
 * <ul>
 *   <li>{@code WORKING-STORAGE} variables &rarr; {@code public static final} or
 *       {@code private static final} constants on this class (translated from
 *       {@code VALUE} clauses).</li>
 *   <li>{@code LINKAGE SECTION DFHCOMMAREA OCCURS DEPENDING ON EIBCALEN}
 *       &rarr; nullable {@link CardDemoCommarea} parameter. A {@code null}
 *       commarea encodes the COBOL {@code EIBCALEN = 0} initial-entry case.</li>
 *   <li>{@code EXEC CICS RECEIVE MAP('COSGN0A')} &rarr; {@link CoSgn00Input}
 *       parameter to {@link #process(AidKey, CoSgn00Input, CardDemoCommarea)}.</li>
 *   <li>{@code EXEC CICS SEND MAP('COSGN0A')} &rarr; {@link CoSgn00Output}
 *       wrapped in an {@link Outcome.Render} return value.</li>
 *   <li>{@code EXEC CICS SEND TEXT} (PF3 thank-you path, followed by an
 *       {@code EXEC CICS RETURN} without a TRANSID) &rarr; {@link Outcome.Goodbye}
 *       return variant — the composition root reads the message and ends the
 *       CICS session.</li>
 *   <li>{@code EXEC CICS XCTL PROGRAM(WS-PGMNAME) COMMAREA(CARDDEMO-COMMAREA)}
 *       &rarr; {@link ProgramRegistry#invoke(String, CardDemoCommarea)} +
 *       {@link Outcome.Dispatched} return variant.</li>
 *   <li>{@code EXEC CICS READ DATASET('USRSEC')} &rarr;
 *       {@link UserSecurityRepository#findById(String)} returning
 *       {@link Optional}{@code <SecUserData>}.</li>
 *   <li>{@code EVALUATE WS-RESP-CD} branches &rarr; explicit handling of
 *       {@link Optional#isEmpty()} (NOTFND, RESP=13) and try/catch around
 *       {@link RuntimeException} (OTHER, any I/O error).</li>
 *   <li>{@code EIBAID} comparisons &rarr; exhaustive pattern-matching switch
 *       over the 16 permits of the sealed {@link AidKey} hierarchy, with no
 *       {@code default} branch (compiler-enforced exhaustiveness per AAP
 *       &sect;0.6.7).</li>
 *   <li>{@code FUNCTION UPPER-CASE} intrinsic &rarr;
 *       {@link String#toUpperCase(Locale)} with {@link Locale#ROOT} to avoid
 *       Turkish/Azeri-locale issues with the dotless 'i' transformation.</li>
 *   <li>{@code FUNCTION CURRENT-DATE} intrinsic &rarr; {@link LocalDate#now(Clock)}
 *       and {@link LocalTime#now(Clock)} on the constructor-injected
 *       {@link Clock}, formatted via
 *       {@link DateConstants#formatMmDdYy(LocalDate)} and
 *       {@link DateConstants#formatHhMmSs(LocalTime)}.</li>
 *   <li>{@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} &rarr; {@link PgmContext#ENTER}
 *       sealed constant (zero indicator).</li>
 * </ul>
 *
 * <h2>Preserved COBOL behavior (AAP &sect;0.7.1 minimal-change clause)</h2>
 * <ul>
 *   <li>Plaintext password comparison: {@code SEC-USR-PWD} is stored as
 *       {@code PIC X(08)} plaintext per {@code app/cpy/CSUSR01Y.cpy}. The
 *       Java translation also compares plaintext (after trim and uppercase
 *       normalization, mirroring COBOL space-padded EBCDIC compares of the
 *       uppercased {@code WS-USER-PWD}). See AAP &sect;0.1.3 — any migration
 *       to BCrypt/Argon2 is a separate effort documented in
 *       {@code java/MIGRATION_NOTES.md}.</li>
 *   <li>Uppercase normalization of user-typed user id and password
 *       (COBOL {@code FUNCTION UPPER-CASE}).</li>
 *   <li>Three failure-message variants:
 *       "Wrong Password. Try again ..." (WS-RESP-CD=0, password mismatch),
 *       "User not found. Try again ..." (WS-RESP-CD=13, NOTFND),
 *       "Unable to verify the User ..." (WS-RESP-CD=OTHER, any I/O error).</li>
 *   <li>Validation order: user-id blank check runs before password blank
 *       check; on either failure no USRSEC lookup is performed. Only one
 *       error message is shown at a time — the first error wins.</li>
 *   <li>PF3 sends "Thank you for using CardDemo application..." as
 *       {@link SystemMessages#THANK_YOU_MSG} and ends the CICS session.</li>
 *   <li>All AID keys other than ENTER (PFK03 / PF3) and ENTER itself produce
 *       {@link SystemMessages#INVALID_KEY_MSG} ("Invalid key pressed. Please
 *       see below...").</li>
 *   <li>BMS field-level cursor-positioning ({@code MOVE -1 TO USERIDL} /
 *       {@code MOVE -1 TO PASSWDL}) is deferred to the BMS adapter layer; the
 *       {@link CoSgn00Output} record does not currently expose a cursor-pos
 *       field. See {@code MIGRATION_NOTES.md} for the deviation entry.</li>
 * </ul>
 *
 * <h2>Threading and state</h2>
 * <p>Instances of this class are intended to be created per-request by the
 * composition root (a {@code carddemo-app} entry point). The class is
 * effectively stateless aside from its three constructor-injected
 * collaborators; all per-call state flows through method parameters and the
 * {@link Outcome} return value. Per AAP &sect;0.7.4 this class does NOT use
 * {@code ThreadLocal}; cross-method context propagation (when needed by
 * future revisions) MUST use {@link java.lang.ScopedValue}.
 *
 * <h2>Forbidden constructs (AAP &sect;0.7.4)</h2>
 * <p>No Spring/Lombok/Hibernate/JPA annotations; no SLF4J/log4j imports
 * (would pull in unlisted external dependencies, and plaintext passwords
 * must never appear in any log surface — AAP &sect;0.7.2); no
 * {@code java.util.Date} / {@code java.util.Calendar}; no
 * {@code java.io.File}; no reflection or dynamic proxies; no
 * {@code double}/{@code float}; no preview features
 * (JEP 502 / 505 / 507 / 512); no {@code default} branch in the
 * pattern-matching switch over the {@link AidKey} sealed hierarchy.
 *
 * @see CoSgn00Input
 * @see CoSgn00Output
 * @see ProgramRegistry
 * @see UserSecurityRepository
 * @see AidKey
 * @see UserType
 * @see PgmContext
 */
@CobolProgram(
        value = "COSGN00C",
        sourcePath = "app/cbl/COSGN00C.cbl",
        translationDate = "2025-09-16",
        notes = "Signon screen handler for CICS transaction CC00; authenticates against "
                + "USRSEC and routes to admin (COADM01C) or user (COMEN01C) menu via the "
                + "UserType sealed taxonomy (AAP §0.6.10). The @CobolProgram annotation "
                + "does not expose a transactionId element; the transaction id 'CC00' is "
                + "recorded here in notes and via the public TRANSACTION_ID constant."
)
public final class CoSgn00C {

    // ====================================================================
    // Public static constants — translated from WORKING-STORAGE VALUE clauses
    // ====================================================================

    /**
     * COBOL {@code 05 WS-PGMNAME PIC X(08) VALUE 'COSGN00C'}.
     *
     * <p>Eight-character right-padded program name; consumed by callers
     * that need to record this program as the {@code CDEMO-FROM-PROGRAM}
     * on the outgoing commarea or echo it into the BMS header.
     */
    public static final String PROGRAM_NAME = "COSGN00C";

    /**
     * COBOL {@code 05 WS-TRANID PIC X(04) VALUE 'CC00'}.
     *
     * <p>Four-character CICS transaction id; consumed by callers that need
     * to record this transaction as the {@code CDEMO-FROM-TRANID} on the
     * outgoing commarea or echo it into the BMS header's {@code TRNNAME}
     * field.
     */
    public static final String TRANSACTION_ID = "CC00";

    /**
     * COBOL {@code 05 WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} (right
     * space-padded to 8 characters).
     *
     * <p>The CICS dataset name used for the
     * {@code EXEC CICS READ DATASET(WS-USRSEC-FILE)} construct. The Java
     * port routes the lookup through {@link UserSecurityRepository}; this
     * constant is retained for traceability and for diagnostic logging
     * (the dataset name is also the COBOL-side identity of the resource).
     */
    public static final String USRSEC_FILE = "USRSEC  ";

    /**
     * Target COBOL {@code PROGRAM-ID} for admin users
     * ({@code SEC-USR-TYPE = 'A'}). Used as the first argument to
     * {@link ProgramRegistry#invoke(String, CardDemoCommarea)} on the
     * admin-user signon-success path, replicating the COBOL
     * {@code MOVE 'COADM01C' TO WS-PGMNAME} + {@code EXEC CICS XCTL} pair.
     */
    public static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /**
     * Target COBOL {@code PROGRAM-ID} for regular (non-admin) users
     * ({@code SEC-USR-TYPE != 'A'}). Used as the first argument to
     * {@link ProgramRegistry#invoke(String, CardDemoCommarea)} on the
     * regular-user signon-success path, replicating the COBOL
     * {@code MOVE 'COMEN01C' TO WS-PGMNAME} + {@code EXEC CICS XCTL} pair.
     */
    public static final String USER_MENU_PROGRAM = "COMEN01C";

    // ====================================================================
    // Private static constants — header defaults and user-facing messages
    // ====================================================================

    /**
     * Default {@code EIBAPPLID} value populated into the BMS header
     * {@code APPLID} field. The COBOL source obtains this from the CICS
     * Exec Interface Block at runtime; the Java port uses a static default
     * because no CICS runtime is present. May be made configurable via
     * {@code application.properties} in a future revision; documented in
     * {@code MIGRATION_NOTES.md}.
     */
    private static final String DEFAULT_APPL_ID = "CARDDEMO";

    /**
     * Default {@code EIBSYSID} value populated into the BMS header
     * {@code SYSID} field. Eight spaces matches the COBOL {@code PIC X(08)}
     * declared length when no system id is known.
     */
    private static final String DEFAULT_SYS_ID = "        ";

    /**
     * COBOL "Please enter User ID ..." validation message from
     * {@code PROCESS-ENTER-KEY} when {@code USERIDI} is blank.
     */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /**
     * COBOL "Please enter Password ..." validation message from
     * {@code PROCESS-ENTER-KEY} when {@code PASSWDI} is blank.
     */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /**
     * COBOL "Wrong Password. Try again ..." message from
     * {@code READ-USER-SEC-FILE} when {@code SEC-USR-PWD != WS-USER-PWD}
     * (WS-RESP-CD = 0 but password mismatch).
     */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * COBOL "User not found. Try again ..." message from
     * {@code READ-USER-SEC-FILE} when the {@code USRSEC} lookup returns
     * {@code WS-RESP-CD = 13} (DFHRESP NOTFND).
     */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * COBOL "Unable to verify the User ..." message from
     * {@code READ-USER-SEC-FILE} when the {@code USRSEC} lookup returns
     * any other (non-zero, non-13) {@code WS-RESP-CD}.
     */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * BMS color attribute byte for "red" (the COBOL {@code DFHRED}
     * constant from {@code DFHBMSCA}). The Java port treats this as a
     * single-character string; the BMS adapter layer translates the
     * abstract color code into the actual BMS attribute byte at SEND time.
     */
    private static final String COLOR_RED = "R";

    /**
     * BMS color attribute byte for the neutral / default color (no
     * override). Used on the initial signon screen and after a successful
     * dispatch when no error is being reported.
     */
    private static final String COLOR_NEUTRAL = " ";

    // ====================================================================
    // Outcome — sealed return type
    // ====================================================================

    /**
     * Outcome of {@link #process(AidKey, CoSgn00Input, CardDemoCommarea)}.
     *
     * <p>The three permits replicate the three terminal actions performed
     * by the COBOL {@code MAIN-PARA}:
     *
     * <ul>
     *   <li>{@link Render} &mdash; re-display the signon screen
     *       (initial entry, blank-input validation error, authentication
     *       failure, or invalid AID key). Replicates the COBOL
     *       {@code PERFORM SEND-SIGNON-SCREEN} &rarr;
     *       {@code EXEC CICS SEND MAP('COSGN0A')} &rarr;
     *       {@code EXEC CICS RETURN TRANSID('CC00') COMMAREA(...)} cycle.</li>
     *   <li>{@link Dispatched} &mdash; authentication succeeded; control
     *       was transferred to either {@link #ADMIN_MENU_PROGRAM} or
     *       {@link #USER_MENU_PROGRAM} via
     *       {@link ProgramRegistry#invoke(String, CardDemoCommarea)}.
     *       The carried commarea is the value returned by that downstream
     *       program. Replicates the COBOL
     *       {@code EXEC CICS XCTL PROGRAM(WS-PGMNAME) COMMAREA(CARDDEMO-COMMAREA)}.</li>
     *   <li>{@link Goodbye} &mdash; PF3 path: display the plain-text
     *       thank-you message and end the CICS session. Replicates the
     *       COBOL {@code PERFORM SEND-PLAIN-TEXT} &rarr;
     *       {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(80) ERASE FREEKB}
     *       &rarr; {@code EXEC CICS RETURN} (no TRANSID, so the
     *       transaction ends).</li>
     * </ul>
     */
    public sealed interface Outcome permits Outcome.Render, Outcome.Dispatched, Outcome.Goodbye {

        /**
         * Re-display the signon screen with the given commarea (carried
         * forward verbatim for the next request cycle) and the populated
         * BMS output map.
         *
         * @param commarea the commarea to be returned to the BMS adapter
         *                 as the {@code RETURN ... COMMAREA(...)} payload;
         *                 never {@code null} (use
         *                 {@link CardDemoCommarea#empty()} for the
         *                 initial-entry case)
         * @param output   the populated output map; never {@code null}
         */
        record Render(CardDemoCommarea commarea, CoSgn00Output output) implements Outcome {
            /**
             * Compact canonical constructor enforcing the non-null contract on
             * both components (JEP 513 Flexible Constructor Bodies pattern).
             *
             * @throws NullPointerException if {@code commarea} or
             *                              {@code output} is {@code null}
             */
            public Render {
                Objects.requireNonNull(commarea, "commarea");
                Objects.requireNonNull(output, "output");
            }
        }

        /**
         * Authentication succeeded; control was transferred to a
         * downstream program (admin or user menu) via the
         * {@link ProgramRegistry}. The carried commarea is the value
         * returned by that downstream program — typically updated with
         * the routing target ({@code CDEMO-TO-PROGRAM} /
         * {@code CDEMO-TO-TRANID}).
         *
         * @param commarea the commarea returned by the downstream
         *                 program; never {@code null}
         */
        record Dispatched(CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact canonical constructor enforcing the non-null contract.
             *
             * @throws NullPointerException if {@code commarea} is
             *                              {@code null}
             */
            public Dispatched {
                Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * PF3 ("end session") path: display the plain-text thank-you
         * message and end the CICS session. The composition root reads
         * {@link #message()}, writes it to the terminal as
         * {@code SEND TEXT}, and issues {@code RETURN} without a TRANSID.
         *
         * @param message the 50-character thank-you message
         *                ({@link SystemMessages#THANK_YOU_MSG}); never
         *                {@code null}
         */
        record Goodbye(String message) implements Outcome {
            /**
             * Compact canonical constructor enforcing the non-null contract.
             *
             * @throws NullPointerException if {@code message} is
             *                              {@code null}
             */
            public Goodbye {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    // ====================================================================
    // Instance state — constructor-injected collaborators only
    // ====================================================================

    /**
     * Strategy/registry target for COBOL {@code EXEC CICS XCTL} dispatch.
     * Constructor-injected. The Java analogue of the COBOL dynamic
     * program-name dispatch (the {@code MOVE 'COADM01C' / 'COMEN01C' TO
     * WS-PGMNAME} + {@code XCTL} pair). See AAP &sect;0.3.2
     * (Strategy/registry pattern).
     */
    private final ProgramRegistry programRegistry;

    /**
     * Port for reading the {@code USRSEC} dataset, replacing the COBOL
     * {@code EXEC CICS READ DATASET('USRSEC') INTO(SEC-USER-DATA)
     * RIDFLD(WS-USER-ID)} construct. Constructor-injected. See AAP
     * &sect;0.3.2 (Repository pattern — port + adapter).
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Time source replacing the COBOL {@code FUNCTION CURRENT-DATE}
     * intrinsic. Constructor-injected to make the header-date-and-time
     * deterministic in tests and to avoid global JVM state (per AAP
     * &sect;0.6.4 mandate to use {@code java.time} exclusively).
     */
    private final Clock clock;

    // ====================================================================
    // Constructor
    // ====================================================================

    /**
     * Constructs a signon handler.
     *
     * <p>All three collaborators are required. The composition root
     * (typically a {@code carddemo-app} main class) constructs one
     * {@code CoSgn00C} per request — instances are intended to be
     * short-lived and effectively stateless beyond the three injected
     * fields above.
     *
     * @param programRegistry        the registry used to invoke the
     *                               admin/user menu programs (Java
     *                               analogue of CICS
     *                               {@code XCTL PROGRAM(...)
     *                               COMMAREA(...)}); never {@code null}
     * @param userSecurityRepository the port for reading the
     *                               {@code USRSEC} dataset (Java analogue
     *                               of CICS
     *                               {@code READ DATASET('USRSEC')});
     *                               never {@code null}
     * @param clock                  the {@link Clock} used to populate
     *                               the BMS header's date and time
     *                               (Java analogue of
     *                               {@code FUNCTION CURRENT-DATE});
     *                               never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CoSgn00C(ProgramRegistry programRegistry,
                    UserSecurityRepository userSecurityRepository,
                    Clock clock) {
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository,
                "userSecurityRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ====================================================================
    // process — public entry method (translation of MAIN-PARA)
    // ====================================================================

    /**
     * Main entry point — translation of the COBOL {@code MAIN-PARA}
     * paragraph.
     *
     * <p>Dispatches based on the AID key and the presence of a commarea:
     *
     * <ol>
     *   <li>If {@code commarea} is {@code null} (COBOL
     *       {@code IF EIBCALEN = 0}) &mdash; render the initial signon
     *       screen with an empty commarea, no error message, and a
     *       neutral color attribute. This corresponds to the COBOL
     *       {@code MOVE LOW-VALUES TO COSGN0AO} +
     *       {@code SET ERR-FLG-OFF} + {@code PERFORM SEND-SIGNON-SCREEN}
     *       sequence.</li>
     *   <li>Otherwise, exhaustively switch on the {@link AidKey}:
     *     <ul>
     *       <li>{@link AidKey.Enter} &rarr;
     *           {@link #processEnterKey(CoSgn00Input, CardDemoCommarea)}.
     *           </li>
     *       <li>{@link AidKey.PfKey03} (PF3) &rarr; {@link #processPf3()}.
     *           </li>
     *       <li>Any other key (Clear, PA1, PA2, PFK01, PFK02, PFK04..PFK12)
     *           &rarr; {@link #renderInvalidKey(CardDemoCommarea)}.</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>The pattern-matching switch is exhaustive without a
     * {@code default} branch — the Java compiler enforces that every
     * permit of {@link AidKey} has a matching case label. If a new permit
     * is added to {@link AidKey} in the future (e.g., a hypothetical
     * {@code PA3}), this file will fail to compile until a corresponding
     * case is added; this is the intended safety guarantee per AAP
     * &sect;0.6.7.
     *
     * @param aidKey   the decoded AID key from {@code EIBAID} (typically
     *                 produced by the BMS-adapter's PfKeyDecoder); never
     *                 {@code null}
     * @param input    the BMS RECEIVE MAP input (the contents of
     *                 {@code COSGN0AI} after the COBOL
     *                 {@code EXEC CICS RECEIVE MAP('COSGN0A')}); never
     *                 {@code null} — use {@link CoSgn00Input#empty()}
     *                 when no map has been received
     * @param commarea the COBOL {@code DFHCOMMAREA} that flows between
     *                 CICS transactions, or {@code null} when
     *                 {@code EIBCALEN = 0} (the initial entry from the
     *                 CICS sign-on screen)
     * @return the {@link Outcome} for the composition root to render
     *         (signon screen redisplay), dispatch (admin/user menu), or
     *         terminate (PF3 goodbye)
     * @throws NullPointerException if {@code aidKey} or {@code input} is
     *                              {@code null}
     */
    public Outcome process(AidKey aidKey, CoSgn00Input input, CardDemoCommarea commarea) {
        Objects.requireNonNull(aidKey, "aidKey");
        Objects.requireNonNull(input, "input");

        // COBOL: IF EIBCALEN = 0
        //          MOVE LOW-VALUES TO COSGN0AO
        //          SET ERR-FLG-OFF
        //          PERFORM SEND-SIGNON-SCREEN
        //
        // The initial entry from the CICS sign-on screen carries no
        // commarea — the Java port encodes this as a null commarea
        // argument. We render an empty signon screen with no error and
        // a neutral color attribute, carrying forward an empty commarea
        // so the next request cycle starts from a clean slate.
        if (commarea == null) {
            return new Outcome.Render(CardDemoCommarea.empty(),
                    buildOutput("", COLOR_NEUTRAL));
        }

        // COBOL: EVALUATE TRUE
        //          WHEN EIBAID = DFHENTER ... PROCESS-ENTER-KEY
        //          WHEN EIBAID = DFHPF3   ... thank-you / SEND TEXT
        //          WHEN OTHER             ... invalid-key / SEND-SIGNON-SCREEN
        //
        // Pattern-matching switch with compiler-enforced exhaustiveness
        // over the sixteen permits of the AidKey sealed hierarchy:
        // {Enter, Clear, Pa1, Pa2, PfKey01..PfKey12}. NO default branch —
        // if AidKey grows new permits this switch must be updated.
        // (AAP §0.6.7)
        return switch (aidKey) {
            case AidKey.Enter()   -> processEnterKey(input, commarea);
            case AidKey.PfKey03() -> processPf3();
            case AidKey.Clear()   -> renderInvalidKey(commarea);
            case AidKey.Pa1()     -> renderInvalidKey(commarea);
            case AidKey.Pa2()     -> renderInvalidKey(commarea);
            case AidKey.PfKey01() -> renderInvalidKey(commarea);
            case AidKey.PfKey02() -> renderInvalidKey(commarea);
            case AidKey.PfKey04() -> renderInvalidKey(commarea);
            case AidKey.PfKey05() -> renderInvalidKey(commarea);
            case AidKey.PfKey06() -> renderInvalidKey(commarea);
            case AidKey.PfKey07() -> renderInvalidKey(commarea);
            case AidKey.PfKey08() -> renderInvalidKey(commarea);
            case AidKey.PfKey09() -> renderInvalidKey(commarea);
            case AidKey.PfKey10() -> renderInvalidKey(commarea);
            case AidKey.PfKey11() -> renderInvalidKey(commarea);
            case AidKey.PfKey12() -> renderInvalidKey(commarea);
        };
    }

    // ====================================================================
    // processEnterKey — translation of PROCESS-ENTER-KEY paragraph
    // ====================================================================

    /**
     * Translation of the COBOL {@code PROCESS-ENTER-KEY} paragraph.
     *
     * <p>The full COBOL paragraph reads:
     *
     * <pre>
     * PROCESS-ENTER-KEY.
     *     EXEC CICS RECEIVE MAP('COSGN0A')
     *                       MAPSET('COSGN00')
     *                       INTO(COSGN0AI)
     *     END-EXEC.
     *     IF USERIDI = LOW-VALUES OR SPACES OR '_'
     *         MOVE 'Please enter User ID ...' TO WS-MESSAGE
     *         SET ERR-FLG-ON
     *     END-IF.
     *     IF PASSWDI = LOW-VALUES OR SPACES OR '_'
     *         MOVE 'Please enter Password ...' TO WS-MESSAGE
     *         SET ERR-FLG-ON
     *     END-IF.
     *     IF ERR-FLG-ON
     *         PERFORM SEND-SIGNON-SCREEN
     *     ELSE
     *         MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID
     *         MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD
     *         PERFORM READ-USER-SEC-FILE
     *     END-IF.
     * </pre>
     *
     * <p>The Java translation preserves the COBOL "first error wins"
     * semantics: the user-id blank check runs before the password blank
     * check, and on either failure no USRSEC lookup is performed. Both
     * inputs are uppercased via {@link String#toUpperCase(Locale)} with
     * {@link Locale#ROOT} (avoiding Turkish/Azeri dotless-i issues).
     *
     * <p>On a successful authentication the commarea is updated with the
     * authenticated user's identity ({@code SEC-USR-ID}), the
     * authenticated user's type ({@code SEC-USR-TYPE} mapped to
     * {@link UserType}), the {@code CDEMO-FROM-PROGRAM} and
     * {@code CDEMO-FROM-TRANID} routing-source markers, and the
     * {@code CDEMO-PGM-CONTEXT} set to {@link PgmContext#ENTER}. The
     * outbound commarea is then handed to
     * {@link ProgramRegistry#invoke(String, CardDemoCommarea)} which
     * synchronously walks into the registered admin or user menu
     * program; that program's return value becomes the
     * {@link Outcome.Dispatched#commarea()} payload.
     *
     * <h3>{@code EVALUATE WS-RESP-CD} branches</h3>
     * <ul>
     *   <li>{@code WS-RESP-CD = 0} (DFHRESP NORMAL) &amp; passwords match
     *       &rarr; {@link Outcome.Dispatched}.</li>
     *   <li>{@code WS-RESP-CD = 0} (DFHRESP NORMAL) &amp; passwords differ
     *       &rarr; {@link Outcome.Render} with
     *       {@value #MSG_WRONG_PASSWORD}.</li>
     *   <li>{@code WS-RESP-CD = 13} (DFHRESP NOTFND), i.e.
     *       {@link Optional#empty()} from {@link UserSecurityRepository}
     *       &rarr; {@link Outcome.Render} with
     *       {@value #MSG_USER_NOT_FOUND}.</li>
     *   <li>{@code WS-RESP-CD = OTHER} (any other I/O error), i.e. any
     *       {@link RuntimeException} from {@link UserSecurityRepository}
     *       &rarr; {@link Outcome.Render} with
     *       {@value #MSG_UNABLE_TO_VERIFY}.</li>
     * </ul>
     *
     * @param input    the BMS RECEIVE MAP input; non-null
     * @param commarea the inbound commarea; non-null (the {@code null}
     *                 case is filtered out by
     *                 {@link #process(AidKey, CoSgn00Input, CardDemoCommarea)})
     * @return either an {@link Outcome.Render} (validation failure,
     *         authentication failure, or any USRSEC error) or an
     *         {@link Outcome.Dispatched} (successful authentication)
     */
    private Outcome processEnterKey(CoSgn00Input input, CardDemoCommarea commarea) {
        // ----------------------------------------------------------------
        // Step 1: Validate non-blank user id (COBOL first error wins)
        // COBOL: IF USERIDI = LOW-VALUES OR SPACES OR '_'
        //          MOVE 'Please enter User ID ...' TO WS-MESSAGE
        //          SET ERR-FLG-ON
        // ----------------------------------------------------------------
        String userIdRaw = input.userId();
        if (isBlank(userIdRaw)) {
            return new Outcome.Render(commarea, buildOutput(MSG_ENTER_USER_ID, COLOR_RED));
        }

        // ----------------------------------------------------------------
        // Step 2: Validate non-blank password
        // COBOL: IF PASSWDI = LOW-VALUES OR SPACES OR '_'
        //          MOVE 'Please enter Password ...' TO WS-MESSAGE
        //          SET ERR-FLG-ON
        // ----------------------------------------------------------------
        String passwdRaw = input.passwd();
        if (isBlank(passwdRaw)) {
            return new Outcome.Render(commarea, buildOutput(MSG_ENTER_PASSWORD, COLOR_RED));
        }

        // ----------------------------------------------------------------
        // Step 3: Uppercase normalization (COBOL FUNCTION UPPER-CASE)
        // Locale.ROOT avoids locale-specific case rules (e.g. Turkish
        // dotless i / dotted I in tr_TR locale).
        // COBOL: MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID
        //        MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD
        // ----------------------------------------------------------------
        String userIdNorm = userIdRaw.trim().toUpperCase(Locale.ROOT);
        String passwdNorm = passwdRaw.trim().toUpperCase(Locale.ROOT);

        // ----------------------------------------------------------------
        // Step 4: USRSEC lookup via the constructor-injected repository.
        // COBOL: EXEC CICS READ DATASET(WS-USRSEC-FILE)
        //                       INTO(SEC-USER-DATA)
        //                       RIDFLD(WS-USER-ID)
        //                       RESP(WS-RESP-CD)
        //                       RESP2(WS-REAS-CD)
        // The RESP/RESP2 mechanism prevents an abend on NOTFND; the Java
        // port encodes NOTFND through Optional.empty() and any other I/O
        // error through a caught RuntimeException.
        // ----------------------------------------------------------------
        Optional<SecUserData> userOpt;
        try {
            userOpt = userSecurityRepository.findById(userIdNorm);
        } catch (RuntimeException re) {
            // COBOL: WHEN OTHER → MOVE 'Unable to verify the User ...' TO WS-MESSAGE
            return new Outcome.Render(commarea, buildOutput(MSG_UNABLE_TO_VERIFY, COLOR_RED));
        }

        if (userOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → MOVE 'User not found. Try again ...'
            return new Outcome.Render(commarea, buildOutput(MSG_USER_NOT_FOUND, COLOR_RED));
        }

        SecUserData user = userOpt.get();

        // ----------------------------------------------------------------
        // Step 5: Plaintext password comparison.
        // PRESERVED PER AAP §0.1.3 — SEC-USR-PWD is PIC X(08) plaintext;
        // BCrypt/Argon2 migration is OUT OF SCOPE for this refactor.
        // COBOL space-padded EBCDIC compare: WS-USER-PWD = SEC-USR-PWD
        //   (both fields are 8 chars; trim() handles cases where input
        //   was shorter than 8, mirroring COBOL's right-space-padding
        //   semantics).
        // ----------------------------------------------------------------
        String storedPwd = user.secUsrPwd().trim().toUpperCase(Locale.ROOT);
        if (!passwdNorm.equals(storedPwd)) {
            // COBOL: WHEN DFHRESP(NORMAL) but SEC-USR-PWD != WS-USER-PWD
            //          MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
            //          MOVE -1 TO PASSWDL  (cursor positioning — deferred
            //                              to BMS adapter layer; see
            //                              MIGRATION_NOTES.md)
            return new Outcome.Render(commarea, buildOutput(MSG_WRONG_PASSWORD, COLOR_RED));
        }

        // ----------------------------------------------------------------
        // Step 6: Authentication succeeded — determine routing target.
        // COBOL: IF SEC-USR-TYPE = 'A'
        //          MOVE 'COADM01C' TO WS-PGMNAME
        //        ELSE
        //          MOVE 'COMEN01C' TO WS-PGMNAME
        //
        // UserType.fromIndicator(char) throws IllegalArgumentException for
        // any value that is neither 'A' nor 'U'; the COBOL ELSE branch
        // accepts any non-'A' value as a regular user (the COBOL has no
        // explicit defensive check for unknown type codes). To preserve
        // that behavior we fall through to UserType.USER on the exception
        // path — this matches the COBOL ELSE semantics exactly.
        // ----------------------------------------------------------------
        UserType userType;
        try {
            userType = UserType.fromIndicator(user.secUsrType());
        } catch (IllegalArgumentException ex) {
            userType = UserType.USER;
        }
        String targetProgram = userType.isAdmin() ? ADMIN_MENU_PROGRAM : USER_MENU_PROGRAM;

        // ----------------------------------------------------------------
        // Step 7: Update commarea before XCTL.
        // COBOL: MOVE SEC-USR-ID    TO CDEMO-USER-ID
        //        MOVE SEC-USR-TYPE  TO CDEMO-USER-TYPE
        //        MOVE WS-PGMNAME    TO CDEMO-FROM-PROGRAM   (i.e. 'COSGN00C')
        //        MOVE WS-TRANID     TO CDEMO-FROM-TRANID    (i.e. 'CC00')
        //        MOVE ZEROS         TO CDEMO-PGM-CONTEXT    (= ENTER)
        //
        // CdemoGeneralInfo is a plain record with strict length validation
        // (validateFixedLengthAscii) — every string component must be
        // exactly its declared PIC X(N) width. We pad SEC-USR-ID to 8
        // chars defensively (it should already be 8 per the SecUserData
        // record contract). The existing toTranId/toProgram fields are
        // preserved from the inbound commarea — the downstream program
        // will overwrite them as needed.
        // ----------------------------------------------------------------
        CardDemoCommarea.CdemoGeneralInfo inboundGi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo outboundGi = new CardDemoCommarea.CdemoGeneralInfo(
                TRANSACTION_ID,                                       // fromTranId  (4 chars "CC00")
                PROGRAM_NAME,                                         // fromProgram (8 chars "COSGN00C")
                inboundGi.toTranId(),                                 // toTranId    (preserve)
                inboundGi.toProgram(),                                // toProgram   (preserve)
                padRight(user.secUsrId().trim(),
                        CardDemoCommarea.LENGTH_USER_ID),             // userId      (8 chars)
                userType,                                             // userType    (sealed)
                PgmContext.ENTER);                                    // pgmContext  (ENTER = 0)
        CardDemoCommarea outboundCommarea = commarea.withCdemoGeneralInfo(outboundGi);

        // ----------------------------------------------------------------
        // Step 8: XCTL via ProgramRegistry.
        // COBOL: EXEC CICS XCTL PROGRAM(WS-PGMNAME)
        //                       COMMAREA(CARDDEMO-COMMAREA)
        // XCTL is non-returning under CICS; the Java port models the
        // dispatch synchronously and propagates the downstream program's
        // returned commarea through Outcome.Dispatched. If no handler is
        // registered for the target program name, ProgramRegistry throws
        // UnknownProgramException (analogous to CICS PGMIDERR abend);
        // that exception propagates out of this method unchanged — the
        // composition root is responsible for surfacing it as a
        // configuration error.
        // ----------------------------------------------------------------
        CardDemoCommarea afterDispatch = programRegistry.invoke(targetProgram, outboundCommarea);
        return new Outcome.Dispatched(afterDispatch);
    }

    // ====================================================================
    // processPf3 — translation of the DFHPF3 branch in MAIN-PARA
    // ====================================================================

    /**
     * Translation of the COBOL {@code MAIN-PARA} branch handling
     * {@code EIBAID = DFHPF3}:
     *
     * <pre>
     * WHEN EIBAID = DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     * </pre>
     *
     * <p>The {@code SEND-PLAIN-TEXT} paragraph performs
     * {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(80) ERASE FREEKB}
     * followed by an {@code EXEC CICS RETURN} <em>without</em> a TRANSID,
     * which ends the CICS transaction (no further work is expected). The
     * Java port models this terminal action as {@link Outcome.Goodbye},
     * which carries the message text only; the composition root is
     * responsible for emitting it to the terminal and tearing down the
     * session.
     *
     * @return a {@link Outcome.Goodbye} carrying
     *         {@link SystemMessages#THANK_YOU_MSG} (50 characters,
     *         space-padded)
     */
    private Outcome processPf3() {
        return new Outcome.Goodbye(SystemMessages.THANK_YOU_MSG);
    }

    // ====================================================================
    // renderInvalidKey — translation of the ELSE branch in MAIN-PARA
    // ====================================================================

    /**
     * Translation of the COBOL {@code MAIN-PARA} {@code WHEN OTHER}
     * branch (i.e. AID key is neither {@code DFHENTER} nor
     * {@code DFHPF3}):
     *
     * <pre>
     * WHEN OTHER
     *     SET ERR-FLG-ON
     *     MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     *
     * <p>This branch handles fourteen of the sixteen {@link AidKey}
     * permits: {@code Clear}, {@code Pa1}, {@code Pa2},
     * {@code PfKey01}, {@code PfKey02}, and {@code PfKey04} through
     * {@code PfKey12}. It re-renders the signon screen with the
     * "Invalid key pressed..." message in red.
     *
     * @param commarea the inbound commarea (carried forward to the next
     *                 request); never {@code null}
     * @return an {@link Outcome.Render} with
     *         {@link SystemMessages#INVALID_KEY_MSG} (50 characters) in
     *         red
     */
    private Outcome renderInvalidKey(CardDemoCommarea commarea) {
        return new Outcome.Render(commarea, buildOutput(SystemMessages.INVALID_KEY_MSG, COLOR_RED));
    }

    // ====================================================================
    // buildOutput — translation of SEND-SIGNON-SCREEN + POPULATE-HEADER-INFO
    // ====================================================================

    /**
     * Builds a fully-populated {@link CoSgn00Output} record carrying the
     * BMS header (titles, current date and time, APPLID, SYSID, program
     * name, transaction name) and the supplied error message and color
     * attribute.
     *
     * <p>This method combines two COBOL paragraphs into a single Java
     * helper:
     *
     * <ul>
     *   <li>{@code POPULATE-HEADER-INFO} — moves
     *       {@code WS-TRANID}, {@code CCDA-TITLE01},
     *       {@code CCDA-TITLE02}, {@code WS-PGMNAME},
     *       {@code FUNCTION CURRENT-DATE} (formatted as
     *       {@code MM/DD/YY} and {@code HH:MM:SS}), {@code EIBAPPLID},
     *       and {@code EIBSYSID} into their respective output fields.</li>
     *   <li>{@code SEND-SIGNON-SCREEN} — moves {@code WS-MESSAGE} to
     *       {@code ERRMSGO} and, if {@code ERR-FLG-ON}, moves
     *       {@code DFHRED} to {@code ERRMSGC}.</li>
     * </ul>
     *
     * <p>All header fields are space-padded to their BMS-declared widths
     * via {@link #padRight(String, int)} (the BMS map declares
     * {@code TRNNAME LEN=4}, {@code TITLE01 / TITLE02 LEN=40},
     * {@code CURDATE LEN=8}, {@code PGMNAME LEN=8}, {@code CURTIME LEN=9},
     * {@code APPLID / SYSID LEN=8}, {@code ERRMSG LEN=78}). The USER-ID
     * and PASSWORD fields are deliberately cleared on each render — the
     * COBOL {@code MOVE LOW-VALUES TO COSGN0AO} idiom at the top of every
     * SEND clears the form values, and the BMS map's {@code USERID}
     * field has {@code IC} (initial-cursor) so it ends up holding the
     * next input cycle's value anyway.
     *
     * @param errorMessage the message to display in the ERRMSG field;
     *                     may be empty for the initial screen with no
     *                     error
     * @param errMsgColor  the BMS color attribute byte for ERRMSG
     *                     ({@link #COLOR_RED} for errors,
     *                     {@link #COLOR_NEUTRAL} otherwise)
     * @return a fully-populated output record ready for the BMS adapter
     *         to render via {@code EXEC CICS SEND MAP}
     */
    private CoSgn00Output buildOutput(String errorMessage, String errMsgColor) {
        // COBOL: FUNCTION CURRENT-DATE
        // The injected Clock makes time deterministic in tests and avoids
        // calling the global system clock; DateConstants formats the
        // result in the same MM/DD/YY and HH:MM:SS forms as the COBOL
        // slicing logic in POPULATE-HEADER-INFO.
        LocalDate today = LocalDate.now(clock);
        LocalTime nowTime = LocalTime.now(clock);

        return CoSgn00Output.empty()
                .withTrnName(padRight(TRANSACTION_ID, CoSgn00Output.TRN_NAME_WIDTH))
                .withTitle01(padRight(ScreenTitle.TITLE_01, CoSgn00Output.TITLE_WIDTH))
                .withTitle02(padRight(ScreenTitle.TITLE_02, CoSgn00Output.TITLE_WIDTH))
                .withCurDate(padRight(DateConstants.formatMmDdYy(today),
                        CoSgn00Output.DATE_WIDTH))
                .withPgmName(padRight(PROGRAM_NAME, CoSgn00Output.PGM_NAME_WIDTH))
                .withCurTime(padRight(DateConstants.formatHhMmSs(nowTime),
                        CoSgn00Output.TIME_WIDTH))
                .withApplId(padRight(DEFAULT_APPL_ID, CoSgn00Output.APPL_ID_WIDTH))
                .withSysId(padRight(DEFAULT_SYS_ID, CoSgn00Output.SYS_ID_WIDTH))
                .withUserId("")              // COBOL: MOVE LOW-VALUES TO COSGN0AO clears USERIDI
                .withPasswd("")              // COBOL: PASSWDI cleared on every SEND (DRK attr)
                .withErrMsg(padRight(errorMessage, CoSgn00Output.ERRMSG_WIDTH))
                .withErrMsgColor(errMsgColor);
    }

    // ====================================================================
    // padRight — translation of COBOL implicit right-space-padding
    // ====================================================================

    /**
     * Pads or truncates a string to exactly the specified width using
     * trailing ASCII spaces (0x20). Translates the COBOL convention where
     * alphanumeric fields are right-space-padded to the {@code PIC X(N)}
     * declared length on every {@code MOVE} operation.
     *
     * <ul>
     *   <li>If {@code s} is {@code null} &rarr; returns a string of
     *       exactly {@code width} spaces.</li>
     *   <li>If {@code s.length() == width} &rarr; returns {@code s}
     *       unchanged.</li>
     *   <li>If {@code s.length() < width} &rarr; right-pads with spaces
     *       to the target width.</li>
     *   <li>If {@code s.length() > width} &rarr; truncates to the first
     *       {@code width} characters (matching the COBOL
     *       {@code MOVE} semantic of silently truncating an oversized
     *       source).</li>
     * </ul>
     *
     * @param s     the string to pad or truncate; may be {@code null}
     * @param width the target width; must be non-negative
     * @return a string of exactly {@code width} characters (never
     *         {@code null})
     */
    private static String padRight(String s, int width) {
        if (s == null) {
            return " ".repeat(width);
        }
        int actual = s.length();
        if (actual == width) {
            return s;
        }
        if (actual > width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - actual);
    }

    // ====================================================================
    // isBlank — translation of COBOL "LOW-VALUES OR SPACES OR '_'" check
    // ====================================================================

    /**
     * Returns {@code true} if the string is null, empty, all whitespace,
     * or consists entirely of underscore characters.
     *
     * <p>Translates the COBOL validation:
     *
     * <pre>
     * IF USERIDI = LOW-VALUES OR SPACES OR '_'
     * </pre>
     *
     * Where:
     * <ul>
     *   <li>{@code LOW-VALUES} = 0x00 bytes (interpreted as null in
     *       cross-platform port; an empty or null Java string covers
     *       this case).</li>
     *   <li>{@code SPACES} = ASCII 0x20 bytes (the
     *       {@link String#trim()} call collapses any combination of
     *       leading/trailing whitespace to empty).</li>
     *   <li>{@code '_'} = the BMS map's INITIAL placeholder text — the
     *       map declares the input fields with INITIAL='________' (or
     *       similar) so that the user sees underscores; if the user
     *       submits without typing anything the field contains the
     *       initial value. The COBOL treats this as "blank".</li>
     * </ul>
     *
     * <p>The Java translation accepts any combination of these as
     * "blank" — for example {@code "  __  "} (spaces plus underscores)
     * counts as blank.
     *
     * @param s the string to test; may be {@code null}
     * @return {@code true} if {@code s} is considered blank by the COBOL
     *         semantics described above; {@code false} otherwise
     */
    private static boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        // All-underscore check — handles "_", "________", and any length
        // in between (and any mix of leading/trailing spaces around them
        // since trim() ran first).
        for (int i = 0, n = trimmed.length(); i < n; i++) {
            if (trimmed.charAt(i) != '_') {
                return false;
            }
        }
        return true;
    }
}
