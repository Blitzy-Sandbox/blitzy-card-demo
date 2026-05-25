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
 * Java translation of the {@code COUSR01C} CICS online program at
 * {@code app/cbl/COUSR01C.cbl} (&quot;Add a new Regular/Admin user to USRSEC
 * file&quot;).
 *
 * <h2>Program purpose</h2>
 * <p>Displays the user-add BMS form (mapset {@code COUSR01}, map
 * {@code COUSR1A}). The operator enters first/last name, user ID, password,
 * and user type ({@code 'A'} or {@code 'U'}); each field is validated for
 * emptiness in the fixed COBOL order (FNAME &rarr; LNAME &rarr; USERID
 * &rarr; PASSWD &rarr; USRTYPE), stopping at the first blank. When every
 * field is populated the program issues an
 * {@code EXEC CICS WRITE DATASET('USRSEC')} which is translated to
 * {@link UserSecurityRepository#insert(SecUserData)}. The success path
 * blanks the input fields and renders &quot;User &lt;id&gt; has been added
 * ...&quot; (GREEN attribute via the {@link CoUsr01Output#successMessage()}
 * flag); duplicate keys (COBOL {@code DFHRESP(DUPKEY)} or
 * {@code DFHRESP(DUPREC)}) surface as &quot;User ID already exist...&quot;
 * (the misspelling &quot;exist&quot; instead of &quot;exists&quot; is
 * preserved verbatim from the COBOL source).
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:     COUSR01C
 *   WS-PGMNAME:     'COUSR01C'
 *   WS-TRANID:      'CU01'
 *   WS-USRSEC-FILE: 'USRSEC  '
 *   MAPSET:         COUSR01
 *   MAP:            COUSR1A
 * </pre>
 *
 * <h2>AID-key bindings (paragraph {@code MAIN-PARA})</h2>
 * <ul>
 *   <li>{@code DFHENTER} &rarr; {@link AidKey#ENTER}: validate + WRITE to
 *       USRSEC.</li>
 *   <li>{@code DFHPF3}   &rarr; {@link AidKey#PF03_BACK}: XCTL to
 *       {@link #LIT_ADMIN_MENU_PGM} (admin menu).</li>
 *   <li>{@code DFHPF4}   &rarr; {@link AidKey#PF04_CLEAR}: clear the
 *       on-screen fields and re-send.</li>
 *   <li>{@link AidKey#PF12_EXIT} &rarr; XCTL to {@link #LIT_SIGNON_PGM}
 *       (signon). NOTE: PF12 is NOT explicitly handled in the COBOL
 *       {@code EVALUATE EIBAID}; the COBOL falls through to
 *       {@code WHEN OTHER} (invalid key error). The Java translation
 *       elevates PF12 to an explicit exit per the schema mandate. This
 *       deviation is documented in {@code MIGRATION_NOTES.md}.</li>
 *   <li>{@code WHEN OTHER} &rarr; {@link AidKey#OTHER}: render
 *       {@code CCDA-MSG-INVALID-KEY} (&quot;Invalid key pressed. Please
 *       see below...&quot;) and re-send with cursor on FNAME.</li>
 * </ul>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr;
 *       {@link #execute(CardDemoCommarea, AidKey, CoUsr01Input)}.</li>
 *   <li>{@code PROCESS-ENTER-KEY} (validation cascade) &rarr;
 *       {@link #processInputs}.</li>
 *   <li>{@code WRITE-USER-SEC-FILE} &rarr; {@link #writeUserSecurity}.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} (PF3 / PF12) &rarr;
 *       {@link #returnToPrevScreen}.</li>
 *   <li>{@code SEND-USRADD-SCREEN} +
 *       {@code POPULATE-HEADER-INFO} &rarr; {@link #buildSendMap}.</li>
 *   <li>{@code RECEIVE-USRADD-SCREEN} &rarr; absorbed by the
 *       {@link CoUsr01Input} parameter (BMS RECEIVE-MAP semantics are
 *       performed by the calling CICS shell).</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} / {@code INITIALIZE-ALL-FIELDS}
 *       &rarr; handled by {@link CoUsr01Input#blank()} combined with
 *       {@link #buildSendMap}.</li>
 *   <li>{@code 9999-ABEND-PROGRAM} &rarr; {@link #abendRoutine}.</li>
 * </ul>
 *
 * <h2>Messages preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>&quot;First Name can NOT be empty...&quot; (triple-dot ellipsis)</li>
 *   <li>&quot;Last Name can NOT be empty...&quot;</li>
 *   <li>&quot;User ID can NOT be empty...&quot;</li>
 *   <li>&quot;Password can NOT be empty...&quot;</li>
 *   <li>&quot;User Type can NOT be empty...&quot;</li>
 *   <li>&quot;User &lt;id&gt; has been added ...&quot; (with COBOL
 *       {@code STRING ... DELIMITED BY SPACE} semantics &mdash; the
 *       inserted id is truncated at the first space)</li>
 *   <li>&quot;User ID already exist...&quot; (NOTE: &quot;exist&quot; not
 *       &quot;exists&quot; &mdash; preserved verbatim)</li>
 *   <li>&quot;Unable to Add User...&quot;</li>
 *   <li>&quot;Invalid key pressed. Please see below...&quot; (from
 *       {@code CCDA-MSG-INVALID-KEY} in {@code app/cpy/CSMSG01Y.cpy})</li>
 *   <li>&quot;User Type must be 'A' (Admin) or 'U' (User)&quot; &mdash;
 *       NEW message produced by the Java translation when the operator
 *       submits a user-type character other than {@code 'A'} or
 *       {@code 'U'}. The COBOL source does NOT validate the user type
 *       (it silently writes whatever character was entered); the Java
 *       translation enforces the closed-set {@link UserType} taxonomy
 *       per AAP &sect;0.6.10. Documented in {@code MIGRATION_NOTES.md}.</li>
 * </ul>
 *
 * <h2>Plaintext password preservation (AAP &sect;0.1.3)</h2>
 * <p>The {@code SEC-USER-DATA} record (copybook {@code app/cpy/CSUSR01Y.cpy})
 * stores {@code SEC-USR-PWD} as a {@code PIC X(08)} plaintext field. The
 * Java translation preserves this behaviour exactly &mdash; passwords flow
 * verbatim from {@link CoUsr01Input#password()} through {@link SecUserData}
 * into the USRSEC repository. NO hashing, NO encryption, NO Base64 encoding
 * is applied. The decision to migrate USRSEC to BCrypt/Argon2 is an
 * explicit follow-up effort flagged in {@code MIGRATION_NOTES.md} and is
 * OUT OF SCOPE for this refactor.
 *
 * <p>Per AAP &sect;0.7.4 security mandate, password values are NEVER passed
 * to any {@code log.*} call. The {@link Logger} usages in this class
 * reference only the {@code userId} field. The mask-aware
 * {@link CoUsr01Input#toString()} and {@link CoUsr01Output#toString()} and
 * {@link SecUserData#toString()} provide defence-in-depth against
 * accidental password leakage in stack traces or debugging output.
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless apart from its two immutable constructor-
 * injected collaborators ({@link UserSecurityRepository} and
 * {@link ProgramRegistry}). All per-call state is passed in via method
 * parameters or returned via {@link Outcome}. Multiple concurrent invocations
 * of {@link #execute} are safe provided the injected
 * {@link UserSecurityRepository} is itself thread-safe.
 */
@CobolProgram(
        value = "COUSR01C",
        sourcePath = "app/cbl/COUSR01C.cbl",
        translationDate = "2025-01-15",
        notes = "Add user online; transaction id CU01. Validates "
              + "FNAMEI/LNAMEI/USERIDI/PASSWDI/USRTYPEI not blank in COBOL "
              + "order, then EXEC CICS WRITE to USRSEC. Plaintext password "
              + "preserved per AAP §0.1.3 — SEC-USR-PWD is PIC X(08) plaintext "
              + "in CSUSR01Y.cpy; any move to BCrypt/Argon2 is OUT OF SCOPE "
              + "for this refactor and flagged in MIGRATION_NOTES.md. Keys: "
              + "ENTER=Add User, PF3=back to COADM01C, PF4=clear screen, "
              + "PF12=exit to COSGN00C (Java extension; COBOL falls through "
              + "to invalid-key error)."
)
public final class CoUsr01C {

    // -----------------------------------------------------------------------
    // Class identity literals (translating WS-LITERALS in app/cbl/COUSR01C.cbl)
    // -----------------------------------------------------------------------

    /** COBOL {@code WS-PGMNAME = 'COUSR01C'} (PIC X(8)). */
    public static final String LIT_THIS_PGM = "COUSR01C";

    /** COBOL {@code WS-TRANID = 'CU01'} (PIC X(4)). */
    public static final String LIT_THIS_TRAN_ID = "CU01";

    /** COBOL BMS {@code MAPSET('COUSR01')} (PIC X(7)). */
    public static final String LIT_THIS_MAPSET = "COUSR01";

    /** COBOL BMS {@code MAP('COUSR1A')} (PIC X(7)). */
    public static final String LIT_THIS_MAP = "COUSR1A";

    /** Admin menu PROGRAM-ID (COBOL {@code XCTL PROGRAM('COADM01C')}). */
    public static final String LIT_ADMIN_MENU_PGM = "COADM01C";

    /** Admin menu TRANSID (COBOL {@code TRANSID('CA00')}). */
    public static final String LIT_ADMIN_MENU_TRAN_ID = "CA00";

    /** Signon PROGRAM-ID (COBOL {@code XCTL PROGRAM('COSGN00C')}). */
    public static final String LIT_SIGNON_PGM = "COSGN00C";

    /** Signon TRANSID (COBOL {@code TRANSID('CC00')}). */
    public static final String LIT_SIGNON_TRAN_ID = "CC00";

    /** USRSEC dataset name (COBOL {@code WS-USRSEC-FILE = 'USRSEC  '}). */
    public static final String LIT_USRSEC_FILE = "USRSEC";

    // -----------------------------------------------------------------------
    // Field-length constraints (preserved from COBOL PIC clauses in CSUSR01Y.cpy)
    // -----------------------------------------------------------------------

    /** Length of {@code SEC-USR-ID} (PIC X(08)). */
    public static final int USR_ID_LENGTH = 8;

    /** Length of {@code SEC-USR-FNAME} (PIC X(20)). */
    public static final int USR_FNAME_LENGTH = 20;

    /** Length of {@code SEC-USR-LNAME} (PIC X(20)). */
    public static final int USR_LNAME_LENGTH = 20;

    /** Length of {@code SEC-USR-PWD} (PIC X(08)). */
    public static final int USR_PWD_LENGTH = 8;

    // -----------------------------------------------------------------------
    // Internal constants (NOT part of the public schema)
    // -----------------------------------------------------------------------

    /**
     * Length of {@code SEC-USR-FILLER} (PIC X(23)) &mdash; the fixed
     * trailing pad on every 80-byte USRSEC record. Used when constructing
     * the {@link SecUserData} write payload.
     */
    private static final int SEC_USR_FILLER_LENGTH = 23;

    /**
     * COBOL {@code CCDA-TITLE01} from {@code app/cpy/COTTL01Y.cpy} (PIC X(40),
     * 40 ASCII bytes including the COBOL-supplied leading and trailing
     * padding). Preserved verbatim so the rendered BMS frame matches the
     * COBOL byte stream exactly per AAP &sect;0.6.5.
     */
    private static final String CCDA_TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * COBOL {@code CCDA-TITLE02} from {@code app/cpy/COTTL01Y.cpy} (PIC X(40),
     * 40 ASCII bytes including padding). Preserved verbatim.
     */
    private static final String CCDA_TITLE02 = "              CardDemo                  ";

    // ---- Validation error messages (preserved verbatim from COBOL) ----

    /** &quot;First Name can NOT be empty...&quot; (PROCESS-ENTER-KEY). */
    private static final String MSG_FNAME_EMPTY = "First Name can NOT be empty...";

    /** &quot;Last Name can NOT be empty...&quot; (PROCESS-ENTER-KEY). */
    private static final String MSG_LNAME_EMPTY = "Last Name can NOT be empty...";

    /** &quot;User ID can NOT be empty...&quot; (PROCESS-ENTER-KEY). */
    private static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

    /** &quot;Password can NOT be empty...&quot; (PROCESS-ENTER-KEY). */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** &quot;User Type can NOT be empty...&quot; (PROCESS-ENTER-KEY). */
    private static final String MSG_USRTYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * &quot;User Type must be 'A' (Admin) or 'U' (User)&quot; &mdash; emitted
     * when the operator types a user-type character outside the closed
     * {@link UserType} taxonomy. This is a Java translation extension; the
     * COBOL source does NOT validate the user-type value before
     * {@code MOVE USRTYPEI TO SEC-USR-TYPE}. See class-level Javadoc.
     */
    private static final String MSG_USRTYPE_INVALID = "User Type must be 'A' (Admin) or 'U' (User)";

    /**
     * &quot;User ID already exist...&quot; (WRITE-USER-SEC-FILE on
     * {@code DFHRESP(DUPKEY)} or {@code DFHRESP(DUPREC)}). NOTE:
     * &quot;exist&quot; (not &quot;exists&quot;) is preserved verbatim
     * from the COBOL source.
     */
    private static final String MSG_DUPLICATE_USER = "User ID already exist...";

    /**
     * &quot;Unable to Add User...&quot; (WRITE-USER-SEC-FILE on
     * {@code WHEN OTHER} response from the dataset write).
     */
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    /**
     * &quot;Invalid key pressed. Please see below...&quot; &mdash; from
     * {@code CCDA-MSG-INVALID-KEY} in {@code app/cpy/CSMSG01Y.cpy}. The
     * COBOL constant is a 50-char fixed-length value with trailing spaces;
     * the trailing spaces are restored by the renderer's
     * {@code PIC X(78)} space-pad of {@code ERRMSGO}.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    // ---- BMS field-name tokens used for cursor positioning ----
    //
    // The COBOL POPULATE-HEADER-INFO + INITIALIZE-ALL-FIELDS idiom of
    // MOVE -1 TO <FIELD>L OF COUSR1AI is translated into a focus-field
    // marker on the CoUsr01Output DTO. The base BMS field names are used
    // (without the L / I / O suffix). All names fit the PIC X(8) cap on
    // CoUsr01Output#focusField.

    /** Cursor on the FIRST-NAME field (FNAMEL/-1 in COBOL). */
    private static final String FOCUS_FNAME = "FNAME";

    /** Cursor on the LAST-NAME field (LNAMEL/-1 in COBOL). */
    private static final String FOCUS_LNAME = "LNAME";

    /** Cursor on the USER-ID field (USERIDL/-1 in COBOL). */
    private static final String FOCUS_USERID = "USERID";

    /** Cursor on the PASSWORD field (PASSWDL/-1 in COBOL). */
    private static final String FOCUS_PASSWD = "PASSWD";

    /** Cursor on the USER-TYPE field (USRTYPEL/-1 in COBOL). */
    private static final String FOCUS_USRTYPE = "USRTYPE";

    // ---- Date / time formatters ----
    //
    // Translates COBOL POPULATE-HEADER-INFO:
    //   MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM   (zero-padded 2 digits)
    //   MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD   (zero-padded 2 digits)
    //   MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY   (2-digit suffix of 4-digit year)
    // Composed into MM/DD/YY (8 chars including the '/' delimiters) for
    // CURDATEO of COUSR1AO (PIC X(8)). Similarly for time: HH:MM:SS for
    // CURTIMEO of COUSR1AO.

    /** {@code MM/dd/yy} formatter matching COBOL {@code WS-CURDATE-MM-DD-YY}. */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MM/dd/yy");

    /** {@code HH:mm:ss} formatter matching COBOL {@code WS-CURTIME-HH-MM-SS}. */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // -----------------------------------------------------------------------
    // Logger
    // -----------------------------------------------------------------------

    /**
     * SLF4J logger for this class. Per AAP &sect;0.7.4 security mandate, the
     * password field is NEVER passed as a logger argument. Only {@code userId}
     * appears in log messages.
     */
    private static final Logger log = LoggerFactory.getLogger(CoUsr01C.class);

    // -----------------------------------------------------------------------
    // AID-key taxonomy
    // -----------------------------------------------------------------------

    /**
     * AID-key taxonomy supported by COUSR01C's {@code EVALUATE EIBAID}
     * block. The values translate the COBOL {@code DFHENTER}, {@code DFHPF3},
     * {@code DFHPF4} permits plus the Java-only {@link #PF12_EXIT} extension
     * mandated by the schema.
     */
    public enum AidKey {
        /** {@code DFHENTER}: validate and WRITE to USRSEC. */
        ENTER,
        /** {@code DFHPF3}: XCTL to {@link CoUsr01C#LIT_ADMIN_MENU_PGM admin menu}. */
        PF03_BACK,
        /** {@code DFHPF4}: clear the on-screen fields and re-send. */
        PF04_CLEAR,
        /**
         * Java-only extension: PF12 exits to
         * {@link CoUsr01C#LIT_SIGNON_PGM signon}. The COBOL source does
         * not include this branch; PF12 falls through to {@code WHEN OTHER}
         * (invalid-key error) in {@code app/cbl/COUSR01C.cbl}.
         */
        PF12_EXIT,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid-key error). */
        OTHER
    }

    // -----------------------------------------------------------------------
    // Outcome sealed type
    // -----------------------------------------------------------------------

    /**
     * Sealed return type for {@link #execute(CardDemoCommarea, AidKey, CoUsr01Input)}.
     * Translates the COBOL dual flow of {@code EXEC CICS RETURN COMMAREA}
     * (continue the conversation, render a BMS map) vs
     * {@code EXEC CICS XCTL COMMAREA} (transfer control to another program).
     *
     * <p>Per AAP &sect;0.7.4, switch expressions over {@code Outcome} MUST
     * be exhaustive (no {@code default} branch) so that the compiler
     * enforces handling of every permit.
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * Outcome corresponding to an {@code EXEC CICS SEND MAP} followed
         * by {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(...)}.
         *
         * @param output   the populated BMS output DTO; non-null
         * @param commarea the outbound commarea (FROM-TRANID /
         *                 FROM-PROGRAM / LAST-MAP / LAST-MAPSET already
         *                 stamped); non-null
         */
        record SendMap(CoUsr01Output output, CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact constructor enforcing non-null fields.
             *
             * @throws NullPointerException if either field is {@code null}
             */
            public SendMap {
                Objects.requireNonNull(output, "output");
                Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * Outcome corresponding to an
         * {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)}.
         *
         * @param targetProgram the target COBOL PROGRAM-ID; non-null and
         *                      8 characters or fewer
         * @param commarea      the outbound commarea (TO-TRANID /
         *                      TO-PROGRAM / FROM-TRANID / FROM-PROGRAM
         *                      stamped); non-null
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact constructor enforcing non-null fields.
             *
             * @throws NullPointerException if either field is {@code null}
             */
            public Xctl {
                Objects.requireNonNull(targetProgram, "targetProgram");
                Objects.requireNonNull(commarea, "commarea");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    /**
     * USRSEC repository port &mdash; translates VSAM file access
     * (specifically {@code EXEC CICS WRITE DATASET('USRSEC') FROM(SEC-USER-DATA)
     * RIDFLD(SEC-USR-ID)}) for the {@code app/cbl/COUSR01C.cbl} program.
     * Injected at construction time; never re-bound at runtime.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Program registry for dynamic CALL dispatch. Held for consistency with
     * the canonical constructor signature shared by every translated
     * PROGRAM-ID (matching {@code CoUsr02C} / {@code CoUsr03C}); the
     * {@code COUSR01C} source uses only static XCTL targets (COADM01C admin
     * menu, COSGN00C signon) so this field is reserved for future expansion.
     */
    @SuppressWarnings("unused")
    private final ProgramRegistry programRegistry;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Constructs a new {@code CoUsr01C} use case instance.
     *
     * @param userSecurityRepository the USRSEC repository port; non-null
     * @param programRegistry        the dynamic CALL registry; non-null
     *                               (held for canonical consistency only)
     * @throws NullPointerException if either argument is {@code null}
     */
    public CoUsr01C(UserSecurityRepository userSecurityRepository,
                    ProgramRegistry programRegistry) {
        this.userSecurityRepository = Objects.requireNonNull(
                userSecurityRepository, "userSecurityRepository");
        this.programRegistry = Objects.requireNonNull(
                programRegistry, "programRegistry");
    }



    // -----------------------------------------------------------------------
    // Public entry point: 0000-MAIN
    // -----------------------------------------------------------------------

    /**
     * Entry point &mdash; translates COBOL paragraph {@code MAIN-PARA} from
     * {@code app/cbl/COUSR01C.cbl}.
     *
     * <p>Flow:
     * <ol>
     *   <li>If {@code commareaIn} is {@code null} (COBOL
     *       {@code EIBCALEN = 0} case) &rarr; XCTL to
     *       {@link #LIT_SIGNON_PGM} immediately, matching the COBOL
     *       fallback {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM /
     *       PERFORM RETURN-TO-PREV-SCREEN}.</li>
     *   <li>Otherwise, stamp {@code CDEMO-FROM-TRANID} = {@code 'CU01'} and
     *       {@code CDEMO-FROM-PROGRAM} = {@code 'COUSR01C'} on the inbound
     *       commarea (mirrors the COBOL {@code RETURN-TO-PREV-SCREEN}
     *       stamping pattern).</li>
     *   <li>Normalize a {@code null} {@code aidKey} as {@link AidKey#OTHER}
     *       (defensive coding; in practice every BMS caller supplies a
     *       valid AID key).</li>
     *   <li>Normalize a {@code null} {@code input} as {@link CoUsr01Input#blank()}
     *       so the validation cascade can still rely on non-null record
     *       components.</li>
     *   <li>Dispatch on {@code aidKey} via a pattern-matching switch
     *       (no {@code default} branch per AAP &sect;0.7.4).</li>
     * </ol>
     *
     * @param commareaIn the inbound CICS commarea; {@code null} indicates
     *                   COBOL {@code EIBCALEN = 0} (first invocation with
     *                   no prior context)
     * @param aidKey     the AID key captured at RECEIVE-MAP time; {@code null}
     *                   is treated as {@link AidKey#OTHER}
     * @param input      the input DTO from the BMS map RECEIVE-MAP;
     *                   {@code null} is treated as {@link CoUsr01Input#blank()}
     * @return the dispatch outcome (SendMap or Xctl); never {@code null}
     */
    public Outcome execute(CardDemoCommarea commareaIn,
                           AidKey aidKey,
                           CoUsr01Input input) {
        // EIBCALEN = 0 case: COBOL fallback redirects to COSGN00C signon.
        // app/cbl/COUSR01C.cbl L78-L80:
        //   IF EIBCALEN = 0
        //       MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        //       PERFORM RETURN-TO-PREV-SCREEN
        if (commareaIn == null) {
            return new Outcome.Xctl(LIT_SIGNON_PGM,
                    applyToTarget(CardDemoCommarea.empty(),
                            LIT_SIGNON_TRAN_ID, LIT_SIGNON_PGM));
        }

        // Stamp FROM-TRANID = 'CU01' and FROM-PROGRAM = 'COUSR01C' on the
        // outbound commarea so the CICS XCTL chain can trace this program's
        // identity (mirrors RETURN-TO-PREV-SCREEN MOVE WS-TRANID / WS-PGMNAME).
        CardDemoCommarea commarea = stampFromIdentity(commareaIn);

        // Defensive null-normalization. Real BMS callers always supply
        // non-null values, but unit tests may probe these paths.
        AidKey effectiveAid = (aidKey == null) ? AidKey.OTHER : aidKey;
        CoUsr01Input safeInput = (input == null) ? CoUsr01Input.blank() : input;

        return switch (effectiveAid) {
            case ENTER ->
                    processInputs(commarea, safeInput);
            case PF03_BACK ->
                    returnToPrevScreen(commarea,
                            LIT_ADMIN_MENU_PGM, LIT_ADMIN_MENU_TRAN_ID);
            case PF04_CLEAR ->
                    // CLEAR-CURRENT-SCREEN: INITIALIZE-ALL-FIELDS, blank
                    // input fields, position cursor on FNAME, re-send.
                    buildSendMap(commarea, CoUsr01Input.blank(),
                            "", /* successMessage */ false, FOCUS_FNAME);
            case PF12_EXIT ->
                    returnToPrevScreen(commarea,
                            LIT_SIGNON_PGM, LIT_SIGNON_TRAN_ID);
            case OTHER ->
                    // EVALUATE EIBAID WHEN OTHER:
                    //   MOVE -1 TO FNAMEL OF COUSR1AI
                    //   MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
                    //   PERFORM SEND-USRADD-SCREEN
                    buildSendMap(commarea, safeInput, MSG_INVALID_KEY,
                            /* successMessage */ false, FOCUS_FNAME);
        };
    }

    // -----------------------------------------------------------------------
    // PROCESS-ENTER-KEY: fixed-order emptiness validation cascade
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code PROCESS-ENTER-KEY}. The COBOL
     * {@code EVALUATE TRUE} block validates each field in declaration
     * order and stops at the FIRST blank field (rather than collecting
     * all errors), per AAP &sect;0.7.1 preserve-as-is. After a clean
     * validation pass, control transfers to {@link #writeUserSecurity}.
     *
     * <p>Cursor positioning: each error path sets the cursor on the
     * offending field, matching the COBOL {@code MOVE -1 TO <FIELD>L}
     * idiom.
     *
     * @param commarea the outbound commarea (FROM-* already stamped)
     * @param input    the post-RECEIVE-MAP input DTO; non-null
     * @return the dispatch outcome; never {@code null}
     */
    private Outcome processInputs(CardDemoCommarea commarea, CoUsr01Input input) {
        String firstName  = trim(input.firstName());
        String lastName   = trim(input.lastName());
        String userId     = trim(input.userId());
        String password   = input.password();    // do NOT trim password (preserve spaces)
        String userTypeStr = trim(input.userType());

        // COBOL EVALUATE TRUE — first blank wins.
        // app/cbl/COUSR01C.cbl L117-L151:
        //   WHEN FNAMEI OF COUSR1AI = SPACES OR LOW-VALUES   ...
        //   WHEN LNAMEI OF COUSR1AI = SPACES OR LOW-VALUES   ...
        //   WHEN USERIDI OF COUSR1AI = SPACES OR LOW-VALUES  ...
        //   WHEN PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES  ...
        //   WHEN USRTYPEI OF COUSR1AI = SPACES OR LOW-VALUES ...
        if (firstName.isEmpty()) {
            return buildSendMap(commarea, input, MSG_FNAME_EMPTY,
                    /* successMessage */ false, FOCUS_FNAME);
        }
        if (lastName.isEmpty()) {
            return buildSendMap(commarea, input, MSG_LNAME_EMPTY,
                    /* successMessage */ false, FOCUS_LNAME);
        }
        if (userId.isEmpty()) {
            return buildSendMap(commarea, input, MSG_USERID_EMPTY,
                    /* successMessage */ false, FOCUS_USERID);
        }
        // The password field is treated as blank when its trimmed value is
        // empty (mirrors the COBOL SPACES-OR-LOW-VALUES test which collapses
        // both all-space and binary-zero content into a single "blank" idiom).
        if (password == null || password.isBlank()) {
            return buildSendMap(commarea, input, MSG_PASSWORD_EMPTY,
                    /* successMessage */ false, FOCUS_PASSWD);
        }
        if (userTypeStr.isEmpty()) {
            return buildSendMap(commarea, input, MSG_USRTYPE_EMPTY,
                    /* successMessage */ false, FOCUS_USRTYPE);
        }

        // User-type validation. Java translation extension: the COBOL
        // source does NOT validate the user-type value before MOVE-ing
        // it to SEC-USR-TYPE; the Java translation enforces the closed
        // {@link UserType} taxonomy ('A' or 'U') per AAP §0.6.10.
        //
        // The validation is a pure character-set check: if the upper-
        // cased first character of USRTYPEI is neither 'A' nor 'U', emit
        // MSG_USRTYPE_INVALID. The legal values exactly correspond to
        // UserType.ADMIN (Admin permit) and UserType.USER (User permit)
        // on the sealed UserType interface (see app/cpy/COCOM01Y.cpy
        // 88-level conditions ADMIN VALUE 'A' and USER VALUE 'U').
        char userTypeChar = Character.toUpperCase(userTypeStr.charAt(0));
        UserType resolvedType = switch (userTypeChar) {
            case 'A' -> UserType.ADMIN;
            case 'U' -> UserType.USER;
            default -> null;
        };
        if (resolvedType == null) {
            return buildSendMap(commarea, input, MSG_USRTYPE_INVALID,
                    /* successMessage */ false, FOCUS_USRTYPE);
        }
        // Trace: log only the resolved UserType permit name (not the raw
        // character) for audit-trail consistency with sibling programs.
        // The userId is the only personally-identifying token in the
        // log line; the password is NEVER logged.
        log.debug("CoUsr01C: USRSEC add for userId='{}' resolvedType='{}'",
                userId, resolvedType.isAdmin() ? "ADMIN" : "USER");

        return writeUserSecurity(commarea, input,
                userId, firstName, lastName, password, userTypeChar);
    }



    // -----------------------------------------------------------------------
    // WRITE-USER-SEC-FILE: EXEC CICS WRITE on USRSEC
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code WRITE-USER-SEC-FILE} from
     * {@code app/cbl/COUSR01C.cbl} (lines 238-274).
     *
     * <p>The COBOL paragraph constructs the {@code SEC-USER-DATA} group from
     * the BMS input fields, then issues:
     * <pre>
     *   EXEC CICS WRITE
     *       DATASET (WS-USRSEC-FILE)
     *       FROM    (SEC-USER-DATA)
     *       RIDFLD  (SEC-USR-ID)
     *       RESP    (WS-RESP-CD)
     *       RESP2   (WS-REAS-CD)
     *   END-EXEC.
     * </pre>
     *
     * <p>The response-code switch is mapped to Java exception handling:
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} &rarr; success path: blank input, build
     *       confirmation message with COBOL {@code STRING ... DELIMITED BY
     *       SPACE} semantics, render GREEN.</li>
     *   <li>{@code DFHRESP(DUPKEY)} or {@code DFHRESP(DUPREC)} &rarr;
     *       {@link IllegalStateException} thrown by
     *       {@link UserSecurityRepository#insert} &rarr; emit
     *       {@link #MSG_DUPLICATE_USER}, cursor on USERID.</li>
     *   <li>Any other {@link RuntimeException} &rarr; emit
     *       {@link #MSG_UNABLE_TO_ADD}, cursor on FNAME, log the unexpected
     *       exception (without the password).</li>
     * </ul>
     *
     * @param commarea     the outbound commarea (FROM-* already stamped)
     * @param input        the original input DTO (for re-display on error)
     * @param userId       the trimmed user-id (non-empty, &le; 8 chars)
     * @param firstName    the trimmed first-name (non-empty, &le; 20 chars)
     * @param lastName     the trimmed last-name (non-empty, &le; 20 chars)
     * @param password     the raw password value (non-empty, &le; 8 chars;
     *                     stored verbatim per AAP &sect;0.1.3)
     * @param userTypeChar the upper-cased user-type character (validated
     *                     to be {@code 'A'} or {@code 'U'} by the caller)
     * @return the dispatch outcome; never {@code null}
     */
    private Outcome writeUserSecurity(CardDemoCommarea commarea,
                                      CoUsr01Input input,
                                      String userId,
                                      String firstName,
                                      String lastName,
                                      String password,
                                      char userTypeChar) {

        // Construct the 80-byte SEC-USER-DATA record. The fixed 23-byte
        // SEC-USR-FILLER is initialised to ASCII spaces to match the COBOL
        // group-MOVE semantics (FILLER fields inherit the program's working-
        // storage default of SPACES).
        byte[] filler = new byte[SEC_USR_FILLER_LENGTH];
        Arrays.fill(filler, (byte) ' ');

        // SecUserData is constructed via its canonical constructor; the
        // record's compact constructor validates field lengths and clones
        // the filler array (defensive copy). Plaintext password is passed
        // verbatim per AAP §0.1.3.
        SecUserData newUser = new SecUserData(
                userId,
                firstName,
                lastName,
                password,
                userTypeChar,
                filler);

        try {
            userSecurityRepository.insert(newUser);
        } catch (IllegalStateException duplicate) {
            // DFHRESP(DUPKEY) or DFHRESP(DUPREC) — per UserSecurityRepository
            // port contract, IllegalStateException signals primary-key
            // collision on insert.
            log.info("CoUsr01C: USRSEC duplicate-key on add for userId='{}'", userId);
            return buildSendMap(commarea, input, MSG_DUPLICATE_USER,
                    /* successMessage */ false, FOCUS_USERID);
        } catch (RuntimeException re) {
            // Any other DFHRESP value (file disabled, I/O error, NOT-OPEN,
            // etc.) — log the unexpected exception (with userId but NOT
            // password) and render the generic "Unable to Add User..." message.
            log.error("CoUsr01C: USRSEC insert failed for userId='{}'",
                    userId, re);
            return buildSendMap(commarea, input, MSG_UNABLE_TO_ADD,
                    /* successMessage */ false, FOCUS_FNAME);
        }

        // SUCCESS — COBOL WRITE-USER-SEC-FILE on DFHRESP(NORMAL):
        //   PERFORM INITIALIZE-ALL-FIELDS
        //   MOVE DFHGREEN TO ERRMSGC OF COUSR1AO
        //   STRING 'User ' DELIMITED BY SIZE
        //          SEC-USR-ID DELIMITED BY SPACE
        //          ' has been added ...' DELIMITED BY SIZE
        //     INTO WS-MESSAGE
        //   PERFORM SEND-USRADD-SCREEN
        String firstToken = firstSpaceDelimitedToken(userId);
        String successMessage = "User " + firstToken + " has been added ...";

        // INITIALIZE-ALL-FIELDS: blank the input fields so the operator can
        // immediately add another user.
        return buildSendMap(commarea, CoUsr01Input.blank(), successMessage,
                /* successMessage */ true, FOCUS_FNAME);
    }

    // -----------------------------------------------------------------------
    // SEND-USRADD-SCREEN + POPULATE-HEADER-INFO
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraphs {@code SEND-USRADD-SCREEN} (lines 184-196)
     * and {@code POPULATE-HEADER-INFO} (lines 214-233) from
     * {@code app/cbl/COUSR01C.cbl}.
     *
     * <p>{@code POPULATE-HEADER-INFO} fields:
     * <pre>
     *   MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
     *   MOVE CCDA-TITLE01           TO TITLE01O OF COUSR1AO
     *   MOVE CCDA-TITLE02           TO TITLE02O OF COUSR1AO
     *   MOVE WS-TRANID              TO TRNNAMEO OF COUSR1AO
     *   MOVE WS-PGMNAME             TO PGMNAMEO OF COUSR1AO
     *   MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COUSR1AO
     *   MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COUSR1AO
     * </pre>
     *
     * <p>The body fields (FNAME, LNAME, USERID, PASSWD, USRTYPE) are
     * populated from the supplied {@code current} input record. The
     * {@code message} parameter is moved to {@code ERRMSGO}, and the
     * {@code successMessage} flag toggles the GREEN attribute per the
     * COBOL {@code MOVE DFHGREEN TO ERRMSGC} directive on the success path.
     *
     * <p>The outbound commarea has {@code LAST-MAP} and {@code LAST-MAPSET}
     * stamped to {@link #LIT_THIS_MAP} / {@link #LIT_THIS_MAPSET} so the
     * next-program dispatch context can correlate the rendered screen.
     *
     * @param commarea       the outbound commarea (FROM-* already stamped)
     * @param current        the input DTO whose values populate the form;
     *                       non-null
     * @param message        the error message (empty string for none);
     *                       {@code null} normalized to empty
     * @param successMessage the GREEN flag for {@code ERRMSGC} attribute
     *                       (true if this is a success message)
     * @param focusField     the BMS field name to position the cursor on;
     *                       {@code null} normalized to empty
     * @return the SendMap outcome; never {@code null}
     */
    private Outcome.SendMap buildSendMap(CardDemoCommarea commarea,
                                         CoUsr01Input current,
                                         String message,
                                         boolean successMessage,
                                         String focusField) {
        // POPULATE-HEADER-INFO — date / time portion
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();

        // Constructed BMS output DTO. Field order MUST match the
        // CoUsr01Output record component order.
        CoUsr01Output output = new CoUsr01Output(
                LIT_THIS_TRAN_ID,                     // tranName    PIC X(4)
                CCDA_TITLE01,                          // title01     PIC X(40)
                today.format(DATE_FORMATTER),          // currentDate PIC X(8)
                LIT_THIS_PGM,                          // pgmName     PIC X(8)
                CCDA_TITLE02,                          // title02     PIC X(40)
                now.format(TIME_FORMATTER),            // currentTime PIC X(8)
                current.firstName(),                   // firstName   PIC X(20)
                current.lastName(),                    // lastName    PIC X(20)
                current.userId(),                      // userId      PIC X(8)
                current.password(),                    // password    PIC X(8)
                current.userType(),                    // userType    PIC X(1)
                (message == null) ? "" : message,      // errorMessage PIC X(78)
                successMessage,                        // successMessage (GREEN flag)
                (focusField == null) ? "" : focusField // focusField  (cursor field)
        );

        // Stamp LAST-MAP / LAST-MAPSET on the outbound commarea so the
        // next-program dispatch context can correlate which BMS screen
        // was rendered.
        CardDemoCommarea outbound = withLastMapInfo(commarea,
                LIT_THIS_MAP, LIT_THIS_MAPSET);

        return new Outcome.SendMap(output, outbound);
    }

    // -----------------------------------------------------------------------
    // RETURN-TO-PREV-SCREEN: PF3 -> COADM01C and PF12 -> COSGN00C
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code RETURN-TO-PREV-SCREEN} (lines 165-178)
     * from {@code app/cbl/COUSR01C.cbl}.
     *
     * <pre>
     *   IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
     *       MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *   END-IF
     *   MOVE WS-TRANID    TO CDEMO-FROM-TRANID
     *   MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
     *   MOVE ZEROS        TO CDEMO-PGM-CONTEXT
     *   EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
     *                  COMMAREA(CARDDEMO-COMMAREA)
     * </pre>
     *
     * <p>The {@code targetProgram} and {@code targetTranId} are stamped onto
     * the outbound commarea's {@code TO-PROGRAM} / {@code TO-TRANID} fields,
     * and {@code PGM-CONTEXT} is reset to {@link PgmContext#ENTER} so the
     * target program starts in its first-time-entry state.
     *
     * @param commareaIn    the inbound commarea (FROM-* already stamped)
     * @param targetProgram the XCTL target PROGRAM-ID (8 chars or fewer)
     * @param targetTranId  the XCTL target TRANSID    (4 chars or fewer)
     * @return the XCTL outcome; never {@code null}
     */
    private Outcome.Xctl returnToPrevScreen(CardDemoCommarea commareaIn,
                                            String targetProgram,
                                            String targetTranId) {
        CardDemoCommarea outbound = applyToTarget(commareaIn,
                targetTranId, targetProgram);
        log.info("CoUsr01C: XCTL to program='{}', tranId='{}'",
                targetProgram, targetTranId);
        return new Outcome.Xctl(targetProgram, outbound);
    }

    // -----------------------------------------------------------------------
    // 9999-ABEND-PROGRAM
    // -----------------------------------------------------------------------

    /**
     * Translates COBOL paragraph {@code 9999-ABEND-PROGRAM}. The COBOL
     * paragraph issues {@code EXEC CICS ABEND ABCODE('CARD')} to terminate
     * the transaction with a 4-character abend code. The Java translation
     * logs the reason at ERROR level and throws a {@link RuntimeException}.
     *
     * <p>Per AAP &sect;0.1.3 / &sect;0.7.4 the plaintext password is NEVER
     * included in the {@code reason} parameter and NEVER logged.
     *
     * <p>The declared return type is {@code int} purely so the call site
     * can use {@code return abendRoutine(...)} as a never-returning
     * expression in a {@code switch} arm without producing a "missing
     * return statement" compile error.
     *
     * @param reason the human-readable reason for the abend (must not
     *               include sensitive data such as passwords)
     * @param cause  the underlying exception, or {@code null} if the abend
     *               is a programmatic decision
     * @return this method never returns normally
     * @throws RuntimeException always &mdash; the wrapped abend
     */
    @SuppressWarnings("SameReturnValue")
    private int abendRoutine(String reason, Throwable cause) {
        log.error("CICS abend in {}: {}", LIT_THIS_PGM, reason, cause);
        throw new RuntimeException(LIT_THIS_PGM + " abend: " + reason, cause);
    }

    // -----------------------------------------------------------------------
    // Commarea-construction helpers
    // -----------------------------------------------------------------------

    /**
     * Stamps {@code CDEMO-FROM-TRANID} and {@code CDEMO-FROM-PROGRAM} on the
     * commarea, leaving every other field of {@code CdemoGeneralInfo}
     * unchanged. Translates the COBOL idiom seen in
     * {@code RETURN-TO-PREV-SCREEN}:
     * <pre>
     *   MOVE WS-TRANID    TO CDEMO-FROM-TRANID
     *   MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
     * </pre>
     *
     * <p>Both {@link #LIT_THIS_TRAN_ID} ({@code 'CU01'}) and
     * {@link #LIT_THIS_PGM} ({@code 'COUSR01C'}) are exactly the required
     * length ({@code LENGTH_FROM_TRANID = 4} and
     * {@code LENGTH_FROM_PROGRAM = 8}) so no padding is needed; the
     * helper passes them through {@link #padRight} for defence-in-depth
     * in case future renames change their length.
     *
     * @param commarea the inbound commarea (non-null)
     * @return a new commarea with FROM-TRANID / FROM-PROGRAM stamped;
     *         never {@code null}
     */
    private static CardDemoCommarea stampFromIdentity(CardDemoCommarea commarea) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                gi.pgmContext());
        return commarea.withCdemoGeneralInfo(updated);
    }

    /**
     * Stamps the outbound {@code TO-TRANID} and {@code TO-PROGRAM}, resets
     * {@code PGM-CONTEXT} to {@link PgmContext#ENTER}, and also stamps
     * {@code FROM-TRANID} / {@code FROM-PROGRAM} to this program's identity
     * (mirrors the full COBOL {@code RETURN-TO-PREV-SCREEN} sequence).
     *
     * <p>Used by both {@link #returnToPrevScreen} (PF3 / PF12 paths) and by
     * the {@link #execute} fallback for the {@code EIBCALEN = 0} case.
     *
     * @param commareaIn    the inbound commarea (non-null)
     * @param targetTranId  the new {@code TO-TRANID} (4 chars or fewer)
     * @param targetProgram the new {@code TO-PROGRAM} (8 chars or fewer)
     * @return a new commarea with TO-* / FROM-* / PGM-CONTEXT updated;
     *         never {@code null}
     */
    private static CardDemoCommarea applyToTarget(CardDemoCommarea commareaIn,
                                                  String targetTranId,
                                                  String targetProgram) {
        CardDemoCommarea.CdemoGeneralInfo gi = commareaIn.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padRight(targetTranId, CardDemoCommarea.LENGTH_TO_TRANID),
                padRight(targetProgram, CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return commareaIn.withCdemoGeneralInfo(updated);
    }

    /**
     * Stamps the outbound {@code LAST-MAP} and {@code LAST-MAPSET} fields on
     * the commarea's {@code CdemoMoreInfo} component so the next-program
     * dispatch context can correlate which BMS screen was rendered.
     *
     * <p>The COBOL source of {@code COUSR01C} does NOT explicitly populate
     * these fields, but the canonical Java translation pattern (matching
     * {@code CoUsr02C}) does so for consistent screen-flow telemetry.
     * This is a benign extension since {@code LAST-MAP} / {@code LAST-MAPSET}
     * are not consumed by {@code COUSR01C} itself and are merely a
     * pass-through field for downstream callers.
     *
     * @param commarea   the inbound commarea (non-null)
     * @param lastMap    the BMS map name (7 chars before padding)
     * @param lastMapset the BMS mapset name (7 chars before padding)
     * @return a new commarea with MORE-INFO LAST-MAP / LAST-MAPSET set;
     *         never {@code null}
     */
    private static CardDemoCommarea withLastMapInfo(CardDemoCommarea commarea,
                                                    String lastMap,
                                                    String lastMapset) {
        CardDemoCommarea.CdemoMoreInfo updated = new CardDemoCommarea.CdemoMoreInfo(
                padRight(lastMap, CardDemoCommarea.LENGTH_LAST_MAP),
                padRight(lastMapset, CardDemoCommarea.LENGTH_LAST_MAPSET));
        return commarea.withCdemoMoreInfo(updated);
    }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /**
     * Right-pads a string with ASCII spaces to the supplied length, or
     * truncates it if oversize. Used to satisfy the strict fixed-length
     * string contracts on {@link CardDemoCommarea.CdemoGeneralInfo} and
     * {@link CardDemoCommarea.CdemoMoreInfo} when constructing commarea
     * components from values that may have been entered in any length.
     *
     * <p>{@code null} is treated as an empty string and yields a string of
     * {@code len} ASCII spaces.
     *
     * @param s   the input string (may be {@code null})
     * @param len the target length (non-negative)
     * @return a string of exactly {@code len} characters; never {@code null}
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
     * to an empty string. Used to translate the COBOL idiom of comparing a
     * BMS input field against {@code SPACES OR LOW-VALUES} after the
     * space-padding of the receiving {@code PIC X(n)} field has been
     * stripped.
     *
     * @param s the input string (may be {@code null})
     * @return a non-null, trimmed string; never {@code null}
     */
    private static String trim(String s) {
        return (s == null) ? "" : s.trim();
    }

    /**
     * Returns the substring up to but not including the first ASCII space
     * character. Translates COBOL {@code STRING ... DELIMITED BY SPACE}
     * semantics &mdash; specifically the success-message construction in
     * paragraph {@code WRITE-USER-SEC-FILE}:
     * <pre>
     *   STRING 'User '     DELIMITED BY SIZE
     *          SEC-USR-ID  DELIMITED BY SPACE
     *          ' has been added ...' DELIMITED BY SIZE
     *     INTO WS-MESSAGE
     * </pre>
     *
     * <p>For a {@code null} or empty input, returns an empty string. For an
     * input containing no space, returns the input unchanged.
     *
     * @param s the input string (may be {@code null})
     * @return the first whitespace-delimited token; never {@code null}
     */
    private static String firstSpaceDelimitedToken(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int spaceIdx = s.indexOf(' ');
        return (spaceIdx < 0) ? s : s.substring(0, spaceIdx);
    }
}

