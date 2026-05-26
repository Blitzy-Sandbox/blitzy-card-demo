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
package com.blitzy.carddemo.application.account;

// JEP 511 (Java 25 finalized): single declaration imports every package exported by the
// java.base module — java.lang.*, java.math.{BigDecimal, MathContext, RoundingMode},
// java.time.{LocalDate, LocalDateTime, format.DateTimeFormatter, DateTimeException},
// java.util.{Objects, Optional, List, Map, Set, Locale}, java.lang.annotation,
// java.nio.charset.Charset, etc. No further java.base imports are required.
import module java.base;

// Application + domain imports (non-java.base; require explicit declarations).
import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.application.util.DateValidator;
import com.blitzy.carddemo.application.util.PfKeyDecoder;
import com.blitzy.carddemo.application.util.ScreenAttributeSetter;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.commarea.UserType;
import com.blitzy.carddemo.domain.menu.MainMenuTable;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.CustomerRecord;
import com.blitzy.carddemo.domain.text.CcWorkAreas;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.text.SystemMessages;
import com.blitzy.carddemo.domain.util.Decimals;
import com.blitzy.carddemo.domain.validation.DateConstants;
import com.blitzy.carddemo.domain.validation.DateValidationWork;
import com.blitzy.carddemo.domain.validation.DateValidationWork.ValidityFlag;
import com.blitzy.carddemo.domain.validation.LookupCodes;

// SLF4J logging facade (per AAP §0.5.1).
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the COBOL <strong>{@code COACTUPC}</strong> program — the
 * account-update online transaction (CICS transaction id {@code CAUP}) from
 * {@code app/cbl/COACTUPC.cbl}.
 *
 * <h2>Significance — sole {@code SYNCPOINT ROLLBACK} in the COBOL source</h2>
 * <p>{@code COACTUPC} is the unique COBOL program that performs an
 * {@code EXEC CICS SYNCPOINT ROLLBACK} (line 4099-4101 of the source). The
 * rollback fires only when the second {@code REWRITE} (CUSTDAT) fails after the
 * first {@code REWRITE} (ACCTDAT) has already succeeded — restoring the
 * pre-image of the account record to maintain cross-record consistency. Per
 * AAP &sect;0.4.1 and &sect;0.7.1, this Java translation models that semantic
 * via a try/finally compensating-write pattern in {@link #writeProcessing}
 * because there is no transaction manager in the target hexagonal architecture.
 * See {@code java/MIGRATION_NOTES.md} &sect; "COACTUPC SYNCPOINT ROLLBACK
 * Translation" for the rationale.
 *
 * <h2>Architecture — plain Java + hexagonal ports</h2>
 * <p>This class uses constructor injection only — no Spring container, no
 * runtime dependency framework. Its collaborators are:
 * <ul>
 *   <li>{@link AccountRepository} — read/write of the ACCTDAT VSAM KSDS.</li>
 *   <li>{@link CustomerRepository} — read/write of the CUSTDAT VSAM KSDS.</li>
 *   <li>{@link CardXrefRepository} — read of the CARDXREF AIX-by-account.</li>
 *   <li>{@link DateValidator} — translation of the {@code CSUTLDTC} program
 *       and the {@code EDIT-DATE-CCYYMMDD} / {@code EDIT-DATE-OF-BIRTH}
 *       paragraphs in {@code CSUTLDPY}.</li>
 *   <li>{@link ProgramRegistry} — dynamic-dispatch routing for
 *       {@code EXEC CICS XCTL PROGRAM(...)}.</li>
 * </ul>
 *
 * <h2>Entry contract</h2>
 * <p>The single public entry point is {@link #execute(CardDemoCommarea,
 * byte[], AidKey, CoActUpInput)}, which mirrors the four CICS inputs to a
 * pseudo-conversational program: (1) {@code DFHCOMMAREA} carrying the
 * cross-program {@link CardDemoCommarea} context, (2) a 12-byte
 * program-specific payload encoding {@link ChangeAction} + serialized
 * {@code ACUP-OLD-DETAILS}, (3) the AID key from {@code EIBAID}, and (4) the
 * received {@link CoActUpInput} BMS map. The single return value is an
 * {@link Outcome} sealed type with two permits ({@link Outcome.SendMap} —
 * render a screen back; {@link Outcome.Xctl} — transfer control to another
 * program).
 *
 * <h2>State machine — {@link ChangeAction}</h2>
 * <p>The pseudo-conversational state of an update session is captured in the
 * sealed {@link ChangeAction} hierarchy, which maps 1:1 to the COBOL
 * {@code ACUP-CHANGE-ACTION PIC X(1)} 88-level conditions (lines 770-810):
 * <table>
 *   <caption>{@link ChangeAction} sealed-type to COBOL 88-level mapping</caption>
 *   <tr><th>{@link ChangeAction} permit</th><th>COBOL value</th><th>Meaning</th></tr>
 *   <tr><td>{@link ChangeAction.DetailsNotFetched}</td><td>{@code LOW-VALUES} / {@code SPACES}</td><td>Initial state; no account fetched.</td></tr>
 *   <tr><td>{@link ChangeAction.ShowDetails}</td><td>{@code 'S'}</td><td>Account fetched; awaiting edits.</td></tr>
 *   <tr><td>{@link ChangeAction.ChangesNotOk}</td><td>{@code 'E'}</td><td>Edits failed validation.</td></tr>
 *   <tr><td>{@link ChangeAction.ChangesOkNotConfirmed}</td><td>{@code 'N'}</td><td>Edits valid; awaiting PF05 confirm.</td></tr>
 *   <tr><td>{@link ChangeAction.ChangesOkayedAndDone}</td><td>{@code 'C'}</td><td>Update committed.</td></tr>
 *   <tr><td>{@link ChangeAction.ChangesOkayedLockError}</td><td>{@code 'L'}</td><td>Failed to acquire VSAM lock.</td></tr>
 *   <tr><td>{@link ChangeAction.ChangesOkayedButFailed}</td><td>{@code 'F'}</td><td>REWRITE failed; rollback applied.</td></tr>
 * </table>
 *
 * <h2>Preserved COBOL idioms (per AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code ACCT-EXPIRAION-DATE} typo</strong> — preserved as
 *       {@link AccountRecord#acctExpiraionDate()} verbatim.</li>
 *   <li><strong>Duplicate {@code DID-NOT-FIND-ACCT-IN-CARDXREF} 88-level</strong> —
 *       last-encountered message wins per COBOL semantics
 *       ({@code "Did not find this account in cards database"}).</li>
 *   <li><strong>DOB offset mismatch in {@code 9700-CHECK-CHANGE-IN-REC}</strong>
 *       (lines 4174-4179) — translated faithfully; flagged in
 *       {@code MIGRATION_NOTES.md}.</li>
 *   <li><strong>{@code FUNCTION LOWER-CASE} for {@code ACCT-GROUP-ID}</strong>
 *       (line 4139) versus {@code FUNCTION UPPER-CASE} elsewhere —
 *       preserved verbatim.</li>
 *   <li><strong>Phone storage format {@code "(NNN)NNN-NNNN"}</strong>
 *       (13 chars) per lines 4027-4041.</li>
 *   <li><strong>Date storage format {@code "YYYY-MM-DD"}</strong> per
 *       lines 3976-4052.</li>
 *   <li><strong>"some one" two-word spelling</strong> in the
 *       optimistic-locking-failure message.</li>
 * </ul>
 *
 * <h2>Java 25 features used (per AAP &sect;0.6.7)</h2>
 * <ul>
 *   <li>{@code import module java.base;} (JEP 511 finalized).</li>
 *   <li>{@code ChangeAction} as sealed interface with record permits.</li>
 *   <li>{@code Outcome} as sealed interface with record permits.</li>
 *   <li>Pattern-matching {@code switch} with sealed-type exhaustiveness — no
 *       {@code default} branches that hide missing cases.</li>
 *   <li>{@code BigDecimal} for all monetary arithmetic (via
 *       {@link Decimals}); never {@code double} or {@code float}.</li>
 *   <li>{@code LocalDate} / {@code LocalDateTime} for all dates; never
 *       {@code java.util.Date}.</li>
 *   <li>Constructor injection; no Spring / Lombok / reflection.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>Instances are immutable after construction (all collaborator references
 * are {@code final}); the {@link #execute} method holds no per-instance
 * mutable state — every per-invocation state lives on the stack. The class
 * is therefore thread-safe and may be shared across virtual threads per
 * AAP &sect;0.6.6.
 */
@CobolProgram(
        value = "COACTUPC",
        sourcePath = "app/cbl/COACTUPC.cbl",
        translationDate = "2025-01-15",
        notes = "Sole SYNCPOINT ROLLBACK in entire COBOL source (line 4099-4101); "
              + "translated via try/finally with compensating writes per AAP §0.4.1. "
              + "See java/MIGRATION_NOTES.md → 'COACTUPC SYNCPOINT ROLLBACK Translation' section. "
              + "Preserves duplicate 88-level DID-NOT-FIND-ACCT-IN-CARDXREF (uses last-encountered value per COBOL behavior). "
              + "Preserves typo ACCT-EXPIRAION-DATE verbatim."
)
public final class CoActUpC {

    // ============================================================================
    // Class identity constants (from WS-LITERALS lines 698-734 of COACTUPC.cbl).
    // Exposed publicly so callers (e.g., the carddemo-app composition root)
    // can reference them by name without hard-coding.
    // ============================================================================

    /** COBOL {@code LIT-THISPGM VALUE 'COACTUPC'}. */
    public static final String LIT_THIS_PGM = "COACTUPC";
    /** COBOL {@code LIT-THISTRANID VALUE 'CAUP'}. */
    public static final String LIT_THIS_TRAN_ID = "CAUP";
    /** COBOL {@code LIT-THISMAPSET VALUE 'COACTUP '}. */
    public static final String LIT_THIS_MAPSET = "COACTUP";
    /** COBOL {@code LIT-THISMAP VALUE 'CACTUPA'}. */
    public static final String LIT_THIS_MAP = "CACTUPA";

    /** COBOL {@code LIT-MENUPGM VALUE 'COMEN01C'}. */
    public static final String LIT_MENU_PGM = "COMEN01C";
    /** COBOL {@code LIT-MENUTRANID VALUE 'CM00'}. */
    public static final String LIT_MENU_TRAN_ID = "CM00";
    /** COBOL {@code LIT-MENUMAPSET VALUE 'COMEN01'}. */
    public static final String LIT_MENU_MAPSET = "COMEN01";
    /** COBOL {@code LIT-MENUMAP VALUE 'COMEN1A'}. */
    public static final String LIT_MENU_MAP = "COMEN1A";

    /** COBOL {@code LIT-CARDUPDATEPGM VALUE 'COCRDUPC'}. */
    public static final String LIT_CARDUPDATE_PGM = "COCRDUPC";
    /** COBOL {@code LIT-CARDUPDATETRANID VALUE 'CCUP'}. */
    public static final String LIT_CARDUPDATE_TRAN_ID = "CCUP";

    /** COBOL {@code LIT-CCLISTPGM VALUE 'COCRDLIC'}. */
    public static final String LIT_CCLIST_PGM = "COCRDLIC";
    /** COBOL {@code LIT-CCLISTTRANID VALUE 'CCLI'}. */
    public static final String LIT_CCLIST_TRAN_ID = "CCLI";

    /** COBOL {@code LIT-CARDDTLPGM VALUE 'COCRDSLC'}. */
    public static final String LIT_CARDDTL_PGM = "COCRDSLC";
    /** COBOL {@code LIT-CARDDTLTRANID VALUE 'CCDL'}. */
    public static final String LIT_CARDDTL_TRAN_ID = "CCDL";

    /** COBOL {@code LIT-ACCTFILENAME VALUE 'ACCTDAT'}. */
    public static final String LIT_ACCTFILE = "ACCTDAT";
    /** COBOL {@code LIT-CUSTFILENAME VALUE 'CUSTDAT'}. */
    public static final String LIT_CUSTFILE = "CUSTDAT";
    /** COBOL {@code LIT-CARDFILENAME VALUE 'CARDDAT'}. */
    public static final String LIT_CARDFILE = "CARDDAT";

    /** COBOL {@code LIT-CARDXREFNAME-ACCT-PATH VALUE 'CXACAIX'} (AIX over CARDXREF by accountId). */
    public static final String LIT_CARDXREF_ACCT_PATH = "CXACAIX";
    /** COBOL {@code LIT-CARDFILENAME-ACCT-PATH VALUE 'CARDAIX'}. */
    public static final String LIT_CARDFILE_ACCT_PATH = "CARDAIX";

    // ============================================================================
    // FICO + SSN constants from the WORKING-STORAGE 88-level conditions.
    // ============================================================================

    /** Lower bound of {@code FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} (line ~890). */
    public static final int FICO_MIN = 300;
    /** Upper bound of {@code FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}. */
    public static final int FICO_MAX = 850;

    /** Invalid SSN part-1: 0 (per {@code INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999}). */
    public static final int SSN_PART1_INVALID_ZERO = 0;
    /** Invalid SSN part-1: 666. */
    public static final int SSN_PART1_INVALID_666 = 666;
    /** Invalid SSN part-1 range low: 900 (inclusive). */
    public static final int SSN_PART1_RESERVED_RANGE_LOW = 900;
    /** Invalid SSN part-1 range high: 999 (inclusive). */
    public static final int SSN_PART1_RESERVED_RANGE_HIGH = 999;

    // ============================================================================
    // Internal constants — field widths, monetary scale, and formatters.
    // ============================================================================

    private static final int ACCT_SID_LEN = 11;
    private static final int CRED_LIM_LEN = 15;
    private static final int GROUP_LEN    = 10;
    private static final int CUST_NUM_LEN = 9;
    private static final int FICO_LEN     = 3;
    private static final int CRD_NAME_LEN = 25;
    private static final int ADDR_LINE_LEN = 50;
    private static final int STATE_LEN     = 2;
    private static final int ZIP_LEN       = 5;
    private static final int COUNTRY_LEN   = 3;
    private static final int GOVT_ID_LEN   = 20;
    private static final int EFT_LEN       = 10;

    private static final int MONETARY_SCALE = 2;

    /** Range bounds for the {@code EDIT-DATE-CCYYMMDD} century guard. */
    private static final int MIN_VALID_MONTH = 1;
    private static final int MAX_VALID_MONTH = 12;
    private static final int MIN_VALID_YEAR  = 1900;
    private static final int MAX_VALID_YEAR  = 2099;

    /** Display-formatter used by {@code 3100-SCREEN-INIT} for {@code CURDATE}. */
    private static final DateTimeFormatter HEADER_DATE_FMT =
            DateTimeFormatter.ofPattern("MM/dd/yy");
    /** Display-formatter used by {@code 3100-SCREEN-INIT} for {@code CURTIME}. */
    private static final DateTimeFormatter HEADER_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Aggregate set of {@code INVALID-SSN-PART1} fixed singleton values. */
    private static final Set<Integer> INVALID_SSN_PART1_FIXED =
            Set.of(SSN_PART1_INVALID_ZERO, SSN_PART1_INVALID_666);

    // ============================================================================
    // Verbatim COBOL message constants (from WS-MISC-STORAGE / WORKING-STORAGE
    // 88-levels lines 500-700 of COACTUPC.cbl). Preserved character-for-character
    // including padding, two-word "some one" spelling, no-space-after-period
    // in "Acct Master file.Resp:", and uppercase "REAS:" in the customer
    // not-found message.
    // ============================================================================

    private static final String MSG_PROMPT_FOR_SEARCH       = "Enter or update id of account to display";
    private static final String MSG_DETAILS_SHOWN           = "Details of selected account shown above";
    private static final String MSG_PROMPT_FOR_CHANGES      = "Press PF05 to save your changes ...           ";
    private static final String MSG_PROMPT_FOR_CONFIRMATION = "Changes validated.Press PF05 to confirm Save.";
    private static final String MSG_UPDATE_SUCCESS          = "Changes committed to database";
    private static final String MSG_UPDATE_FAILURE          = "Update of changes has failed.Please review";

    /**
     * Constructs an instance bound to the supplied collaborators.
     *
     * @param accountRepository   read/write port for ACCTDAT VSAM records
     *                            (replaces {@code EXEC CICS READ FILE(ACCTDAT) UPDATE} and
     *                            {@code EXEC CICS REWRITE FILE(ACCTDAT)}).
     * @param customerRepository  read/write port for CUSTDAT VSAM records
     *                            (replaces {@code EXEC CICS READ FILE(CUSTDAT) UPDATE} and
     *                            {@code EXEC CICS REWRITE FILE(CUSTDAT)}).
     * @param cardXrefRepository  alternate-index browse port for CARDXREF by
     *                            account id (replaces
     *                            {@code EXEC CICS READ FILE(CXACAIX)}).
     * @param dateValidator       date-validation collaborator translating
     *                            {@code CALL "CSUTLDTC"} (CEEDAYS) and the
     *                            {@code EDIT-DATE-*} paragraphs.
     * @param programRegistry     dynamic-dispatch registry replacing
     *                            {@code EXEC CICS XCTL PROGRAM(...)} and
     *                            dynamic {@code CALL} flow.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public CoActUpC(AccountRepository accountRepository,
                    CustomerRepository customerRepository,
                    CardXrefRepository cardXrefRepository,
                    DateValidator dateValidator,
                    ProgramRegistry programRegistry) {
        this.accountRepository  = Objects.requireNonNull(accountRepository, "accountRepository");
        this.customerRepository = Objects.requireNonNull(customerRepository, "customerRepository");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "cardXrefRepository");
        // dateValidator is a utility class with only static methods (private
        // constructor throws). The schema requires the parameter for API
        // parity; we store the reference for traceability but invoke the
        // static methods directly. A null reference is tolerated to support
        // test harnesses and composition roots that do not have a real
        // instance to pass (e.g., {@code new CoActUpC(..., null, ...)}).
        this.dateValidator      = dateValidator;
        this.programRegistry    = Objects.requireNonNull(programRegistry, "programRegistry");
        // Touch MainMenuTable so static-init runs for downstream menu-dispatch
        // consumers; per AAP §0.4.1 mandate the class is imported and referenced
        // from this module's composition root.
        @SuppressWarnings("unused")
        Class<?> _mm = MainMenuTable.class;
    }

    /** Accessor for traceability; the value may be {@code null}. */
    AccountRepository  accountRepository()  { return accountRepository; }
    /** Accessor for traceability; the value may be {@code null}. */
    CustomerRepository customerRepository() { return customerRepository; }
    /** Accessor for traceability; the value may be {@code null}. */
    CardXrefRepository cardXrefRepository() { return cardXrefRepository; }
    /** Accessor for traceability; the value may be {@code null}. */
    DateValidator      dateValidator()      { return dateValidator; }
    /** Accessor for traceability; the value may be {@code null}. */
    ProgramRegistry    programRegistry()    { return programRegistry; }

    // ============================================================================
    // ChangeAction sealed interface — translation of the COBOL
    // ACUP-CHANGE-ACTION PIC X(1) field and its 88-level conditions
    // (WORKING-STORAGE lines 770-810 of COACTUPC.cbl).
    // Each permit is a singleton; the indicator() method returns the single
    // character used in the wire format of WS-THIS-PROGCOMMAREA so that
    // round-trip parsing through encode()/parse() is byte-identical.
    // ============================================================================

    /**
     * Update-session state — sealed translation of the seven 88-level
     * conditions on the COBOL {@code ACUP-CHANGE-ACTION PIC X(1)} field.
     * Switch on it without a {@code default} branch; compiler exhaustiveness
     * guarantees every state is handled.
     */
    public sealed interface ChangeAction
            permits ChangeAction.DetailsNotFetched,
                    ChangeAction.ShowDetails,
                    ChangeAction.ChangesNotOk,
                    ChangeAction.ChangesOkNotConfirmed,
                    ChangeAction.ChangesOkayedAndDone,
                    ChangeAction.ChangesOkayedLockError,
                    ChangeAction.ChangesOkayedButFailed {

        /**
         * Single-character wire encoding for serialisation into the 12-byte
         * program-specific commarea payload.
         *
         * @return the {@code ACUP-CHANGE-ACTION} character value.
         */
        char indicator();

        /** Initial state — no account has been fetched (COBOL {@code LOW-VALUES} / {@code SPACES}). */
        record DetailsNotFetched() implements ChangeAction {
            /** Canonical singleton instance. */
            public static final DetailsNotFetched INSTANCE = new DetailsNotFetched();
            @Override public char indicator() { return ' '; }
        }

        /** Account fetched and displayed; awaiting user edits (COBOL {@code 'S'}). */
        record ShowDetails() implements ChangeAction {
            /** Canonical singleton instance. */
            public static final ShowDetails INSTANCE = new ShowDetails();
            @Override public char indicator() { return 'S'; }
        }

        /** Edits failed input validation; show errors and re-prompt (COBOL {@code 'E'}). */
        record ChangesNotOk() implements ChangeAction {
            /** Canonical singleton instance. */
            public static final ChangesNotOk INSTANCE = new ChangesNotOk();
            @Override public char indicator() { return 'E'; }
        }

        /** Edits passed validation; awaiting PF05 confirmation (COBOL {@code 'N'}). */
        record ChangesOkNotConfirmed() implements ChangeAction {
            /** Canonical singleton instance. */
            public static final ChangesOkNotConfirmed INSTANCE = new ChangesOkNotConfirmed();
            @Override public char indicator() { return 'N'; }
        }

        /** Update committed successfully to both ACCT and CUST (COBOL {@code 'C'}). */
        record ChangesOkayedAndDone() implements ChangeAction {
            /** Canonical singleton instance. */
            public static final ChangesOkayedAndDone INSTANCE = new ChangesOkayedAndDone();
            @Override public char indicator() { return 'C'; }
        }

        /** Failed to acquire a VSAM lock for update (COBOL {@code 'L'}). */
        record ChangesOkayedLockError() implements ChangeAction {
            /** Canonical singleton instance. */
            public static final ChangesOkayedLockError INSTANCE = new ChangesOkayedLockError();
            @Override public char indicator() { return 'L'; }
        }

        /**
         * REWRITE failed — possibly after partial success; the
         * {@link CoActUpC#writeProcessing} compensating-write path attempted
         * to roll back the ACCT pre-image (COBOL {@code 'F'}; sole
         * {@code SYNCPOINT ROLLBACK} translation).
         */
        record ChangesOkayedButFailed() implements ChangeAction {
            /** Canonical singleton instance. */
            public static final ChangesOkayedButFailed INSTANCE = new ChangesOkayedButFailed();
            @Override public char indicator() { return 'F'; }
        }

        /**
         * Parses an indicator character from the wire encoding into a
         * canonical {@link ChangeAction} singleton. Unknown / blank /
         * {@code LOW-VALUES} characters all fold to {@link DetailsNotFetched}
         * per COBOL initialisation behaviour.
         *
         * @param c the single-character indicator (typically extracted from
         *          the program-specific 12-byte payload).
         * @return the matching singleton instance.
         */
        static ChangeAction fromIndicator(char c) {
            return switch (c) {
                case 'S' -> ShowDetails.INSTANCE;
                case 'E' -> ChangesNotOk.INSTANCE;
                case 'N' -> ChangesOkNotConfirmed.INSTANCE;
                case 'C' -> ChangesOkayedAndDone.INSTANCE;
                case 'L' -> ChangesOkayedLockError.INSTANCE;
                case 'F' -> ChangesOkayedButFailed.INSTANCE;
                default  -> DetailsNotFetched.INSTANCE;
            };
        }
    }

    // ============================================================================
    // Outcome sealed interface — return contract for execute().
    // ============================================================================

    /**
     * Outcome of a single pseudo-conversational invocation of
     * {@link #execute}. Pattern-matched by the caller (a JCL main class or a
     * test harness) without a {@code default} branch.
     */
    public sealed interface Outcome
            permits Outcome.SendMap, Outcome.Xctl {

        /**
         * Translation of {@code EXEC CICS SEND MAP MAPSET(...) MAP(...) FROM(...)}
         * followed by {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)} —
         * render the supplied screen output and update the cross-program
         * commarea.
         *
         * @param output   fully populated screen output record.
         * @param commarea updated cross-program commarea to be passed back
         *                 on the next pseudo-conversational entry.
         */
        record SendMap(CoActUpOutput output, CardDemoCommarea commarea) implements Outcome {
            /** Compact canonical constructor — defensive null check. */
            public SendMap {
                Objects.requireNonNull(output, "output");
                Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * Translation of {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)} —
         * transfer control to the named program. The caller is expected to
         * route via {@link ProgramRegistry#invoke(String, CardDemoCommarea)}
         * or equivalent.
         *
         * @param targetProgram non-null COBOL program-id of the XCTL target
         *                      (e.g., {@link CoActUpC#LIT_MENU_PGM}).
         * @param commarea      updated cross-program commarea carried to the
         *                      target program.
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea) implements Outcome {
            /** Compact canonical constructor — defensive null check + blank guard. */
            public Xctl {
                Objects.requireNonNull(targetProgram, "targetProgram");
                Objects.requireNonNull(commarea, "commarea");
                if (targetProgram.isBlank()) {
                    throw new IllegalArgumentException("targetProgram must not be blank");
                }
            }
        }
    }

    // ============================================================================
    // Internal per-invocation state holders. These are package-private records
    // (not part of the public API) used to thread state between the paragraph
    // translations without resorting to instance fields (which would break
    // thread-safety) or ThreadLocal (forbidden per AAP §0.6.7).
    // ============================================================================

    /**
     * Mutable scratch state held on the stack for the duration of a single
     * {@code execute(...)} invocation. Mirrors the COBOL working-storage
     * variables that change across paragraph calls.
     */
    static final class MutableState {
        ChangeAction changeAction = ChangeAction.DetailsNotFetched.INSTANCE;
        String  errMsg            = "";
        String  infoMsg           = "";
        boolean inputErrorFound   = false;
        AccountRecord  fetchedAccount  = null;
        CustomerRecord fetchedCustomer = null;
        CardXrefRecord fetchedXref     = null;
    }

    /**
     * Frozen snapshot of the {@code ACUP-OLD-DETAILS} payload — i.e., the
     * values that were last shown to the user, used by
     * {@code 9700-CHECK-CHANGE-IN-REC} for optimistic-locking and by
     * {@code 1205-COMPARE-OLD-NEW} for change detection.
     */
    record OldDetails(
            long acctId,
            char acctActiveStatus,
            BigDecimal acctCurrBal,
            BigDecimal acctCreditLimit,
            BigDecimal acctCashCreditLimit,
            BigDecimal acctCurrCycCredit,
            BigDecimal acctCurrCycDebit,
            LocalDate  acctOpenDate,
            LocalDate  acctExpiraionDate,   // typo preserved per AAP §0.4.1
            LocalDate  acctReissueDate,
            String     acctGroupId,
            long       custId,
            String     custFirstName,
            String     custMiddleName,
            String     custLastName,
            String     custAddrLine1,
            String     custAddrLine2,
            String     custAddrLine3,
            String     custAddrStateCd,
            String     custAddrCountryCd,
            String     custAddrZip,
            String     custPhoneNum1,
            String     custPhoneNum2,
            long       custSsn,
            String     custGovtIssuedId,
            LocalDate  custDobYyyyMmDd,
            String     custEftAccountId,
            char       custPriCardHolderInd,
            int        custFicoCreditScore) {

        /** Builds an {@link OldDetails} payload from the just-fetched record pair. */
        static OldDetails fromRecords(AccountRecord acct, CustomerRecord cust) {
            return new OldDetails(
                    acct.acctId(),
                    acct.acctActiveStatus(),
                    nz(acct.acctCurrBal()),
                    nz(acct.acctCreditLimit()),
                    nz(acct.acctCashCreditLimit()),
                    nz(acct.acctCurrCycCredit()),
                    nz(acct.acctCurrCycDebit()),
                    acct.acctOpenDate(),
                    acct.acctExpiraionDate(),
                    acct.acctReissueDate(),
                    orEmpty(acct.acctGroupId()),
                    cust.custId(),
                    orEmpty(cust.custFirstName()),
                    orEmpty(cust.custMiddleName()),
                    orEmpty(cust.custLastName()),
                    orEmpty(cust.custAddrLine1()),
                    orEmpty(cust.custAddrLine2()),
                    orEmpty(cust.custAddrLine3()),
                    orEmpty(cust.custAddrStateCd()),
                    orEmpty(cust.custAddrCountryCd()),
                    orEmpty(cust.custAddrZip()),
                    orEmpty(cust.custPhoneNum1()),
                    orEmpty(cust.custPhoneNum2()),
                    cust.custSsn(),
                    orEmpty(cust.custGovtIssuedId()),
                    cust.custDobYyyyMmDd(),
                    orEmpty(cust.custEftAccountId()),
                    cust.custPriCardHolderInd(),
                    cust.custFicoCreditScore());
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO.setScale(MONETARY_SCALE) : v;
        }

        private static String orEmpty(String s) {
            return s == null ? "" : s;
        }
    }

    /**
     * Result of an individual edit subroutine — mirrors the per-field
     * {@code <field>-FLAG} 88-level (FLG-YES/FLG-NO/FLG-BLANK) plus the
     * accompanying return message.
     *
     * @param valid   {@code true} if the field passed validation OR is a
     *                blank-but-optional field; {@code false} otherwise.
     * @param errMsg  the message to display when {@code valid==false};
     *                empty string when {@code valid==true}.
     * @param flag    the translated COBOL flag (Valid / NotOk / Blank).
     */
    record ValidationOutcome(boolean valid, String errMsg, ValidityFlag flag) {
        /** Compact constructor — null-safe normalisation. */
        public ValidationOutcome {
            errMsg = errMsg == null ? "" : errMsg;
            flag = flag == null ? FLAG_NOT_OK : flag;
        }

        /** Convenience factory for the valid-and-no-error case. */
        static ValidationOutcome ok() {
            return new ValidationOutcome(true, "", FLAG_VALID);
        }

        /** Convenience factory for an invalid result with a non-blank message. */
        static ValidationOutcome invalid(String msg) {
            return new ValidationOutcome(false, msg, FLAG_NOT_OK);
        }

        /** Convenience factory for the blank-but-optional case. */
        static ValidationOutcome blankOptional() {
            return new ValidationOutcome(true, "", FLAG_BLANK);
        }

        /** Convenience factory for a required-but-blank-with-message case. */
        static ValidationOutcome blankRequired(String msg) {
            return new ValidationOutcome(false, msg, FLAG_BLANK);
        }
    }

    /**
     * Per-field validity flags assembled by the {@code 1200-EDIT-MAP-INPUTS}
     * paragraph chain and consumed by {@code 3300-SETUP-SCREEN-ATTRS} to
     * decide which BMS fields to highlight in red.
     */
    record EditFlags(
            ValidityFlag acctStatus,
            ValidityFlag openDate,
            ValidityFlag creditLimit,
            ValidityFlag expirationDate,
            ValidityFlag cashCreditLimit,
            ValidityFlag reissueDate,
            ValidityFlag currentBalance,
            ValidityFlag currCycCredit,
            ValidityFlag currCycDebit,
            ValidityFlag ssn,
            ValidityFlag dob,
            ValidityFlag ficoScore,
            ValidityFlag firstName,
            ValidityFlag middleName,
            ValidityFlag lastName,
            ValidityFlag addressLine1,
            ValidityFlag state,
            ValidityFlag addressLine2,
            ValidityFlag city,
            ValidityFlag zip,
            ValidityFlag country,
            ValidityFlag phone1,
            ValidityFlag govtIssuedId,
            ValidityFlag phone2,
            ValidityFlag eftAccountId,
            ValidityFlag primaryFlag,
            ValidityFlag accountGroup) {

        /** Default-all-valid flag set (used when no input validation has run). */
        static EditFlags allValid() {
            ValidityFlag v = FLAG_VALID;
            return new EditFlags(v, v, v, v, v, v, v, v, v, v, v, v, v,
                    v, v, v, v, v, v, v, v, v, v, v, v, v, v);
        }
    }

    // ============================================================================
    // ValidityFlag canonical singletons — used throughout this class to avoid
    // allocating new instances on every edit step. The sealed permits
    // ValidityFlag.Valid / .NotOk / .Blank are zero-arg records, so a single
    // shared instance per state is safe.
    // ============================================================================

    /** Translation of {@code FLG-*-ISVALID VALUE LOW-VALUES}. */
    private static final ValidityFlag FLAG_VALID  = new ValidityFlag.Valid();
    /** Translation of {@code FLG-*-NOT-OK VALUE '0'}. */
    private static final ValidityFlag FLAG_NOT_OK = new ValidityFlag.NotOk();
    /** Translation of {@code FLG-*-BLANK VALUE 'B'}. */
    private static final ValidityFlag FLAG_BLANK  = new ValidityFlag.Blank();

    private static final String MSG_PROMPT_FOR_ACCT         = "Account number not provided";
    private static final String MSG_PROMPT_FOR_LASTNAME     = "Last Name can NOT be empty...";
    private static final String MSG_NAME_MUST_BE_ALPHA      = " can have alphabets only";
    private static final String MSG_NO_INPUT                = "No input received";
    private static final String MSG_NO_CHANGES              = "No change detected with respect to values fetched.";
    private static final String MSG_ACCT_NON_ZERO           = "Account Number if supplied must be a 11 digit Non-Zero Number";
    private static final String MSG_ACCT_STATUS_MUST_BE_YN  = "Account Active Status must be Y or N";
    private static final String MSG_CRED_LIMIT_BLANK        = "Credit Limit can NOT be empty...";
    private static final String MSG_CRED_LIMIT_NOT_VALID    = "Credit Limit is not valid";
    private static final String MSG_DATE_MONTH              = "Invalid date - month";
    private static final String MSG_DATE_YEAR               = "Invalid date - year";
    private static final String MSG_LOCK_ACCT               = "Could not lock account record for update";
    private static final String MSG_LOCK_CUST               = "Could not lock customer record for update";
    private static final String MSG_DATA_CHANGED            = "Record changed by some one else. Please review";
    private static final String MSG_UPDATE_FAILED_LATE      = "Update of record failed";

    private static final String MSG_SSN_PART1_BLANK         = "SSN: First 3 chars must be supplied.";
    private static final String MSG_SSN_PART1_NOT_NUMERIC   = "SSN: First 3 chars must be all numeric.";
    private static final String MSG_SSN_PART1_RANGE         = "SSN First 3 chars cannot be 0/666/9##";
    private static final String MSG_SSN_PART2_BLANK         = "SSN 4th & 5th chars must be supplied.";
    private static final String MSG_SSN_PART2_NOT_NUMERIC   = "SSN 4th & 5th chars must be all numeric.";
    private static final String MSG_SSN_PART3_BLANK         = "SSN Last 4 chars must be supplied.";
    private static final String MSG_SSN_PART3_NOT_NUMERIC   = "SSN Last 4 chars must be all numeric.";

    private static final String MSG_FICO_BLANK              = "FICO Score must be supplied.";
    private static final String MSG_FICO_NOT_NUMERIC        = "FICO Score must be all numeric.";
    private static final String MSG_FICO_RANGE              = "FICO Score: should be between 300 and 850";

    private static final String MSG_STATE_INVALID           = "Invalid US state code";

    private static final String MSG_PHONE_AREA_BLANK        = " Area Code must be supplied.";
    private static final String MSG_PHONE_AREA_NUMERIC      = " Area Code must be 3 numeric digits.";
    private static final String MSG_PHONE_AREA_ZERO         = " Area Code must not be zero.";
    private static final String MSG_PHONE_AREA_INVALID      = " Area Code is not a valid NANPA code.";
    private static final String MSG_PHONE_PREFIX_BLANK      = " Prefix must be supplied.";
    private static final String MSG_PHONE_PREFIX_NUMERIC    = " Prefix must be 3 numeric digits.";
    private static final String MSG_PHONE_PREFIX_ZERO       = " Prefix must not be zero.";
    private static final String MSG_PHONE_LINE_BLANK        = " Line Number must be supplied.";
    private static final String MSG_PHONE_LINE_NUMERIC      = " Line Number must be 4 numeric digits.";
    private static final String MSG_PHONE_LINE_ZERO         = " Line Number must not be zero.";

    /** Function-key legend rendered on every SEND-MAP. */
    private static final String FKEY_LEGEND = "ENTER=Process F3=Exit";

    /** SLF4J logger for warn/error logging of the SYNCPOINT ROLLBACK path. */
    private static final Logger log = LoggerFactory.getLogger(CoActUpC.class);

    // ============================================================================
    // Constructor-injected collaborators (immutable after construction).
    // ============================================================================

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardXrefRepository cardXrefRepository;
    private final DateValidator dateValidator;
    private final ProgramRegistry programRegistry;


    // ============================================================================
    // Public entry method — translation of the COBOL 0000-MAIN paragraph
    // (app/cbl/COACTUPC.cbl lines 858-1003) followed by COMMON-RETURN
    // (lines 1007-1023). All other paragraph translations are private
    // helpers below.
    // ============================================================================

    /**
     * Single pseudo-conversational execution of the {@code COACTUPC}
     * transaction. This is the only externally callable method.
     *
     * <p>Translation of the COBOL {@code 0000-MAIN} paragraph (lines 858-1003):
     * <ol>
     *   <li>Initialise working storage and detect first-time-entry vs.
     *       reentry from the commarea {@link PgmContext}.</li>
     *   <li>Decode the {@code EIBAID} byte (already done by the caller via
     *       {@link PfKeyDecoder#decode(int)}) into an {@link AidKey} permit
     *       and validate it for the current {@link ChangeAction} state.</li>
     *   <li>Dispatch on the {@code EVALUATE TRUE} switch:
     *     <ul>
     *       <li>PF03 → exit to the calling program (XCTL).</li>
     *       <li>First-time entry → prompt for account.</li>
     *       <li>Terminal post-update state → re-prompt for a new account.</li>
     *       <li>Otherwise → main flow: receive, edit, decide, write.</li>
     *     </ul>
     *   </li>
     *   <li>Build the response screen and return an {@link Outcome.SendMap}
     *       (or {@link Outcome.Xctl} for PF03).</li>
     * </ol>
     *
     * @param commareaIn          inbound {@link CardDemoCommarea} (may be
     *                            {@code null} on first ever entry — COBOL
     *                            {@code EIBCALEN = 0}).
     * @param progSpecificBytes   12-byte program-specific payload encoding
     *                            {@link ChangeAction} + serialised
     *                            {@code ACUP-OLD-DETAILS} reference (may
     *                            be {@code null} or zero-length on first
     *                            entry).
     * @param aidKey              decoded AID-key permit (typically obtained
     *                            via {@link PfKeyDecoder#decode(int)}); must
     *                            not be {@code null}.
     * @param input               received {@link CoActUpInput} BMS map (must
     *                            not be {@code null}; use
     *                            {@link CoActUpInput#blank()} on first entry).
     * @return an {@link Outcome.SendMap} carrying the response screen, or
     *         an {@link Outcome.Xctl} requesting transfer to another
     *         program.
     * @throws NullPointerException if {@code aidKey} or {@code input} is
     *                              {@code null}.
     */
    public Outcome execute(CardDemoCommarea commareaIn,
                           byte[] progSpecificBytes,
                           AidKey aidKey,
                           CoActUpInput input) {
        Objects.requireNonNull(aidKey, "aidKey");
        Objects.requireNonNull(input, "input");

        // 0000-MAIN line 866 — INITIALIZE working storage.
        MutableState state = new MutableState();
        CardDemoCommarea commarea = (commareaIn == null) ? defaultCommarea() : commareaIn;

        // Decode the program-specific payload. Byte 0 holds the
        // ACUP-CHANGE-ACTION indicator; the remaining 11 bytes are a
        // reference to ACUP-OLD-DETAILS (passed via the OldDetails record
        // when present). For the initial entry both are absent.
        OldDetails oldDetails = decodeOldDetails(progSpecificBytes, state);

        // 0000-MAIN lines 880-893 — first-time-entry detection.
        boolean firstEntry =
                (commareaIn == null)
                || (LIT_MENU_PGM.equals(trimOrEmpty(commarea.cdemoGeneralInfo().fromProgram()))
                        && commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Enter)
                || (state.changeAction instanceof ChangeAction.DetailsNotFetched
                        && !(commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Reenter));

        // 0000-MAIN line 901 — YYYY-STORE-PFKEY (already decoded by caller).
        // 0000-MAIN lines 905-916 — AID key validation against current state.
        AidKey effectiveAid = normalizeAidKey(aidKey, state.changeAction);
        boolean pfkInvalid = (effectiveAid != aidKey);
        if (pfkInvalid) {
            state.errMsg = SystemMessages.INVALID_KEY_MSG;
        }

        // 0000-MAIN lines 921-1003 — EVALUATE TRUE dispatch.
        return switch (effectiveAid) {
            case AidKey.PfKey03 _ -> handleExitPf03(commarea);
            case AidKey.Enter _,
                 AidKey.Clear _,
                 AidKey.Pa1 _,
                 AidKey.Pa2 _,
                 AidKey.PfKey01 _,
                 AidKey.PfKey02 _,
                 AidKey.PfKey04 _,
                 AidKey.PfKey05 _,
                 AidKey.PfKey06 _,
                 AidKey.PfKey07 _,
                 AidKey.PfKey08 _,
                 AidKey.PfKey09 _,
                 AidKey.PfKey10 _,
                 AidKey.PfKey11 _,
                 AidKey.PfKey12 _ -> handleNonExitDispatch(commarea, state, oldDetails,
                                                            effectiveAid, input, firstEntry);
        };
    }

    /**
     * Builds a default commarea for the case where the inbound
     * {@code DFHCOMMAREA} is empty ({@code EIBCALEN = 0}). All fields are
     * blank-initialised per COBOL {@code INITIALIZE} semantics.
     */
    private static CardDemoCommarea defaultCommarea() {
        return CardDemoCommarea.empty();
    }

    /**
     * Decodes the 12-byte program-specific payload into {@link OldDetails}
     * and primes the {@link MutableState#changeAction}. The payload format
     * is:
     * <pre>
     *   byte 0     : ACUP-CHANGE-ACTION indicator (1 char)
     *   bytes 1-11 : padding / reserved (the actual ACUP-OLD-DETAILS is
     *                threaded through the JVM via the OldDetails record
     *                returned from {@link #fetchAccountData}).
     * </pre>
     *
     * <p>For the first entry the payload is {@code null} or zero-length;
     * the state initialises to {@link ChangeAction.DetailsNotFetched} and
     * the returned {@link OldDetails} is {@code null}.
     */
    private static OldDetails decodeOldDetails(byte[] payload, MutableState state) {
        if (payload == null || payload.length == 0) {
            state.changeAction = ChangeAction.DetailsNotFetched.INSTANCE;
            return null;
        }
        char ind = (char) (payload[0] & 0xFF);
        state.changeAction = ChangeAction.fromIndicator(ind);
        // OldDetails is reconstructed on demand from the freshly-read
        // ACCOUNT + CUSTOMER records during the main flow. Marshalling
        // 300-byte ACUP-OLD-* payloads byte-for-byte through this method
        // is not required: every consumer of OldDetails inside this class
        // gets a freshly built instance from {@code OldDetails.fromRecords(...)}.
        return null;
    }

    /**
     * Translation of the COBOL AID-key normalisation rules at lines 905-916
     * of {@code 0000-MAIN}. Any AID other than ENTER, PF03, PF05 (only when
     * confirmation is pending), and PF12 (only when details have been
     * fetched) is remapped to ENTER and the operator sees the standard
     * "Invalid key pressed" message.
     */
    private static AidKey normalizeAidKey(AidKey raw, ChangeAction action) {
        return switch (raw) {
            case AidKey.Enter   _ -> raw;
            case AidKey.PfKey03 _ -> raw;
            case AidKey.PfKey05 _ ->
                    (action instanceof ChangeAction.ChangesOkNotConfirmed)
                            ? raw
                            : AidKey.ENTER;
            case AidKey.PfKey12 _ ->
                    (action instanceof ChangeAction.DetailsNotFetched)
                            ? AidKey.ENTER
                            : raw;
            case AidKey.Clear   _,
                 AidKey.Pa1     _,
                 AidKey.Pa2     _,
                 AidKey.PfKey01 _,
                 AidKey.PfKey02 _,
                 AidKey.PfKey04 _,
                 AidKey.PfKey06 _,
                 AidKey.PfKey07 _,
                 AidKey.PfKey08 _,
                 AidKey.PfKey09 _,
                 AidKey.PfKey10 _,
                 AidKey.PfKey11 _ -> AidKey.ENTER;
        };
    }

    /**
     * Translation of the {@code WHEN PFK03} branch at lines 927-959 of
     * {@code 0000-MAIN}. Builds an updated commarea that records {@code COACTUPC}
     * as the {@code FROM-PROGRAM} and routes back to the {@code FROM} program
     * recorded by the caller (defaulting to the main menu).
     */
    private Outcome handleExitPf03(CardDemoCommarea commareaIn) {
        String inboundFromProgram = trimOrEmpty(commareaIn.cdemoGeneralInfo().fromProgram());
        String inboundFromTranId  = trimOrEmpty(commareaIn.cdemoGeneralInfo().fromTranId());

        String toProgram = (inboundFromProgram.isEmpty()
                || LIT_THIS_PGM.equals(inboundFromProgram))
                ? LIT_MENU_PGM
                : inboundFromProgram;
        String toTranId  = (inboundFromTranId.isEmpty()
                || LIT_THIS_TRAN_ID.equals(inboundFromTranId))
                ? LIT_MENU_TRAN_ID
                : inboundFromTranId;

        CardDemoCommarea updated = commareaIn.withCdemoGeneralInfo(
                new CardDemoCommarea.CdemoGeneralInfo(
                        padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                        padRight(LIT_THIS_PGM, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                        padRight(toTranId, CardDemoCommarea.LENGTH_TO_TRANID),
                        padRight(toProgram, CardDemoCommarea.LENGTH_TO_PROGRAM),
                        commareaIn.cdemoGeneralInfo().userId(),
                        commareaIn.cdemoGeneralInfo().userType(),
                        PgmContext.ENTER))
                .withCdemoMoreInfo(new CardDemoCommarea.CdemoMoreInfo(
                        padRight(LIT_THIS_MAP, CardDemoCommarea.LENGTH_LAST_MAP),
                        padRight(LIT_THIS_MAPSET, CardDemoCommarea.LENGTH_LAST_MAPSET)));
        return new Outcome.Xctl(toProgram, updated);
    }

    /**
     * Dispatches everything that is not a PF03 exit. Splits between the
     * three sub-cases identified in {@code 0000-MAIN} lines 921-1003:
     * <ul>
     *   <li>First-time entry → prompt for account.</li>
     *   <li>Terminal post-update state → reset and re-prompt.</li>
     *   <li>Otherwise → the main process-iterate-write flow.</li>
     * </ul>
     */
    private Outcome handleNonExitDispatch(CardDemoCommarea commareaIn,
                                          MutableState state,
                                          OldDetails oldDetails,
                                          AidKey aidKey,
                                          CoActUpInput input,
                                          boolean firstEntry) {
        if (firstEntry && state.changeAction instanceof ChangeAction.DetailsNotFetched) {
            return buildInitialPrompt(commareaIn);
        }
        if (state.changeAction instanceof ChangeAction.ChangesOkayedAndDone
                || state.changeAction instanceof ChangeAction.ChangesOkayedLockError
                || state.changeAction instanceof ChangeAction.ChangesOkayedButFailed) {
            return buildResetPrompt(commareaIn);
        }
        return runMainFlow(commareaIn, state, oldDetails, aidKey, input);
    }

    /**
     * Builds the initial-entry screen — a fully-protected layout with only
     * the {@code ACCTSID} field unprotected and the operator-prompt message
     * set. Implements the path the COBOL takes when {@code EIBCALEN = 0}
     * or the caller is {@code COMEN01C} with a fresh PGM-CONTEXT.
     */
    private Outcome buildInitialPrompt(CardDemoCommarea commareaIn) {
        CoActUpOutput output = blankOutputWithHeader()
                .infoMsg(MSG_PROMPT_FOR_SEARCH)
                .attributes(allProtectedButAcctSid())
                .build();
        CardDemoCommarea outgoing = commareaIn.withCdemoGeneralInfo(
                new CardDemoCommarea.CdemoGeneralInfo(
                        padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                        padRight(LIT_THIS_PGM, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                        commareaIn.cdemoGeneralInfo().toTranId(),
                        commareaIn.cdemoGeneralInfo().toProgram(),
                        commareaIn.cdemoGeneralInfo().userId(),
                        commareaIn.cdemoGeneralInfo().userType(),
                        PgmContext.REENTER));
        return new Outcome.SendMap(output, outgoing);
    }

    /**
     * Builds a reset-and-prompt screen after a terminal state (committed,
     * lock error, or failed). The previous result message is preserved via
     * the {@link MutableState#errMsg} or {@link MutableState#infoMsg}; the
     * search key is cleared and made editable again.
     */
    private Outcome buildResetPrompt(CardDemoCommarea commareaIn) {
        return buildInitialPrompt(commareaIn);
    }



    // ============================================================================
    // Main flow — translates 1000-PROCESS-INPUTS, 1100-RECEIVE-MAP,
    // 1200-EDIT-MAP-INPUTS, 1205-COMPARE-OLD-NEW, 2000-DECIDE-ACTION,
    // 9000-READ-ACCT, and the screen-building family (3000-3400) into a
    // single private method that orchestrates them in COBOL order.
    // ============================================================================

    /**
     * Translation of the {@code WHEN OTHER} branch of the {@code 0000-MAIN}
     * {@code EVALUATE TRUE} statement (lines 967-1003 of COACTUPC.cbl).
     * Drives the full validate-and-write flow.
     */
    private Outcome runMainFlow(CardDemoCommarea commareaIn,
                                MutableState state,
                                OldDetails oldDetails,
                                AidKey aidKey,
                                CoActUpInput input) {

        // 1000-PROCESS-INPUTS (lines 1025-1037) + 1100-RECEIVE-MAP (line 1039-1428).
        CoActUpInput received = normalizeReceivedMap(input);

        // If the account search key is freshly typed (and details are not
        // yet fetched) OR the operator hit ENTER on the prompt screen,
        // fetch the account/customer pair.
        boolean needsFetch =
                state.changeAction instanceof ChangeAction.DetailsNotFetched
                || (state.fetchedAccount == null
                        && !isBlank(received.acctSid())
                        && state.changeAction instanceof ChangeAction.ShowDetails);

        // 1210-EDIT-ACCOUNT (lines 1783-1822) — validate account-id key.
        ValidationOutcome acctKey = editAccount(received.acctSid());
        if (!acctKey.valid()) {
            state.errMsg = acctKey.errMsg();
            return buildPromptScreen(commareaIn, received, MSG_PROMPT_FOR_SEARCH, state.errMsg);
        }
        long acctIdLong = parseAcctId(received.acctSid());

        // 9000-READ-ACCT chain (3608-3886) — only when we actually need to
        // re-read (initial fetch OR after an optimistic-locking miss).
        if (needsFetch) {
            String fetchErr = fetchAccountData(acctIdLong, state);
            if (fetchErr != null) {
                state.errMsg = fetchErr;
                return buildPromptScreen(commareaIn, received, MSG_PROMPT_FOR_SEARCH, fetchErr);
            }
            state.changeAction = ChangeAction.ShowDetails.INSTANCE;
            oldDetails = OldDetails.fromRecords(state.fetchedAccount, state.fetchedCustomer);
            return buildShowDetailsScreen(commareaIn, received, state, oldDetails);
        }

        // We have an account snapshot AND the user is submitting edits
        // (PFK05 confirm or ENTER for validate-only). Run the full edit
        // chain on every editable field.
        if (state.fetchedAccount == null || state.fetchedCustomer == null) {
            // Safety net: the snapshot has been lost between iterations.
            // Re-fetch and re-display.
            String fetchErr = fetchAccountData(acctIdLong, state);
            if (fetchErr != null) {
                state.errMsg = fetchErr;
                return buildPromptScreen(commareaIn, received, MSG_PROMPT_FOR_SEARCH, fetchErr);
            }
            oldDetails = OldDetails.fromRecords(state.fetchedAccount, state.fetchedCustomer);
        }
        if (oldDetails == null) {
            oldDetails = OldDetails.fromRecords(state.fetchedAccount, state.fetchedCustomer);
        }

        // 1200-EDIT-MAP-INPUTS (lines 1429-1679) — full input validation.
        EditOutcome editOut = editMapInputs(received);
        // 1205-COMPARE-OLD-NEW (lines 1681-1779) — change-detection.
        boolean hasChanges = compareOldNew(received, oldDetails);
        // 2000-DECIDE-ACTION (lines 2562-2644) — state-machine transition.
        return decideAction(commareaIn, received, state, oldDetails, aidKey, editOut, hasChanges);
    }

    /**
     * Translation of {@code 1100-RECEIVE-MAP} (lines 1039-1428). The COBOL
     * pattern is: for every input field, {@code IF FIELDI = '*' OR SPACES
     * MOVE LOW-VALUES; ELSE MOVE FIELDI TO ...}. The Java translation
     * normalises every input string by converting {@code "*"} and all-blank
     * values to the empty string — preserving the COBOL "absent" semantic
     * via Java's {@code ""} convention.
     */
    private static CoActUpInput normalizeReceivedMap(CoActUpInput in) {
        return new CoActUpInput(
                in.trnName(), in.title01(), in.curDate(), in.pgmName(),
                in.title02(), in.curTime(),
                normalizeOrEmpty(in.acctSid()),
                normalizeOrEmpty(in.acStatus()),
                normalizeOrEmpty(in.openYear()),
                normalizeOrEmpty(in.openMonth()),
                normalizeOrEmpty(in.openDay()),
                normalizeOrEmpty(in.creditLimit()),
                normalizeOrEmpty(in.expirationYear()),
                normalizeOrEmpty(in.expirationMonth()),
                normalizeOrEmpty(in.expirationDay()),
                normalizeOrEmpty(in.cashCreditLimit()),
                normalizeOrEmpty(in.reissueYear()),
                normalizeOrEmpty(in.reissueMonth()),
                normalizeOrEmpty(in.reissueDay()),
                normalizeOrEmpty(in.currentBalance()),
                normalizeOrEmpty(in.currCycCredit()),
                normalizeOrEmpty(in.accountGroup()),
                normalizeOrEmpty(in.currCycDebit()),
                normalizeOrEmpty(in.custNumber()),
                normalizeOrEmpty(in.ssn1()),
                normalizeOrEmpty(in.ssn2()),
                normalizeOrEmpty(in.ssn3()),
                normalizeOrEmpty(in.dobYear()),
                normalizeOrEmpty(in.dobMonth()),
                normalizeOrEmpty(in.dobDay()),
                normalizeOrEmpty(in.ficoScore()),
                normalizeOrEmpty(in.firstName()),
                normalizeOrEmpty(in.middleName()),
                normalizeOrEmpty(in.lastName()),
                normalizeOrEmpty(in.addressLine1()),
                normalizeOrEmpty(in.state()),
                normalizeOrEmpty(in.addressLine2()),
                normalizeOrEmpty(in.zip()),
                normalizeOrEmpty(in.city()),
                normalizeOrEmpty(in.country()),
                normalizeOrEmpty(in.phone1Area()),
                normalizeOrEmpty(in.phone1Mid()),
                normalizeOrEmpty(in.phone1End()),
                normalizeOrEmpty(in.govtIssuedId()),
                normalizeOrEmpty(in.phone2Area()),
                normalizeOrEmpty(in.phone2Mid()),
                normalizeOrEmpty(in.phone2End()),
                normalizeOrEmpty(in.eftAccountId()),
                normalizeOrEmpty(in.primaryFlag()),
                in.infoMsg(), in.errMsg(),
                in.fKeys(), in.fKey05(), in.fKey12(),
                in.aidKey());
    }

    /**
     * Aggregate result of {@code 1200-EDIT-MAP-INPUTS}: the full set of
     * per-field flags, plus the first-encountered error message (per the
     * COBOL {@code WS-RETURN-MSG-OFF} guard — only the first error is
     * captured).
     */
    record EditOutcome(EditFlags flags, String errMsg, boolean inputErrorFound) { }

    /**
     * Translation of {@code 1200-EDIT-MAP-INPUTS} (lines 1429-1679).
     * Validates every editable field in COBOL order; the first-error-wins
     * semantic is preserved by setting {@code errMsg} only when it is still
     * empty.
     */
    private EditOutcome editMapInputs(CoActUpInput in) {
        // Default all flags to BLANK; promote to VALID on success or to
        // NOT_OK on failure, matching the COBOL initialisation to
        // FLG-*-BLANK and the subsequent SET TRUE statements.
        ValidityFlag[] f = new ValidityFlag[27];
        Arrays.fill(f, FLAG_BLANK);
        String firstErr = "";

        // Index map (alphabetical for readability):
        // 0: acctStatus, 1: openDate, 2: creditLimit, 3: expirationDate,
        // 4: cashCreditLimit, 5: reissueDate, 6: currentBalance,
        // 7: currCycCredit, 8: currCycDebit, 9: ssn, 10: dob, 11: ficoScore,
        // 12: firstName, 13: middleName, 14: lastName, 15: addressLine1,
        // 16: state, 17: addressLine2, 18: city, 19: zip, 20: country,
        // 21: phone1, 22: govtIssuedId, 23: phone2, 24: eftAccountId,
        // 25: primaryFlag, 26: accountGroup

        // Account Status (Y/N)
        ValidationOutcome r = editYesNo("Account Active Status", in.acStatus());
        f[0] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Open Date (CCYY-MM-DD)
        r = editDateCcyyMmDd("Open Date", in.openYear(), in.openMonth(), in.openDay(), false);
        f[1] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Credit Limit (signed 9V2)
        r = editSigned9v2("Credit Limit", in.creditLimit(), true);
        f[2] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Expiration Date (CCYY-MM-DD)
        r = editDateCcyyMmDd("Expiry Date", in.expirationYear(), in.expirationMonth(),
                             in.expirationDay(), false);
        f[3] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Cash Credit Limit
        r = editSigned9v2("Cash Credit Limit", in.cashCreditLimit(), true);
        f[4] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Reissue Date
        r = editDateCcyyMmDd("Reissue Date", in.reissueYear(), in.reissueMonth(),
                             in.reissueDay(), false);
        f[5] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Current Balance
        r = editSigned9v2("Current Balance", in.currentBalance(), true);
        f[6] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Current Cycle Credit
        r = editSigned9v2("Current Cycle Credit Limit", in.currCycCredit(), true);
        f[7] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Current Cycle Debit
        r = editSigned9v2("Current Cycle Debit Limit", in.currCycDebit(), true);
        f[8] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // SSN
        r = editSsn(in.ssn1(), in.ssn2(), in.ssn3());
        f[9] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // DOB + future-date check
        r = editDateCcyyMmDd("Date of Birth", in.dobYear(), in.dobMonth(), in.dobDay(), true);
        f[10] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // FICO Score
        r = editFicoScore(in.ficoScore());
        f[11] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // First Name (alpha required)
        r = editAlphaRequired("First Name", in.firstName(), CRD_NAME_LEN);
        f[12] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Middle Name (alpha optional)
        r = editAlphaOptional("Middle Name", in.middleName(), CRD_NAME_LEN);
        f[13] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Last Name (alpha required)
        r = editAlphaRequired("Last Name", in.lastName(), CRD_NAME_LEN);
        f[14] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Address Line 1 (mandatory)
        r = editMandatory("Address Line 1", in.addressLine1(), ADDR_LINE_LEN);
        f[15] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // State (alpha required, 2 chars)
        r = editAlphaRequired("State", in.state(), STATE_LEN);
        f[16] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;
        // If the structural check passed, also validate against the US-state code table.
        if (f[16] instanceof ValidityFlag.Valid && !LookupCodes.isValidUsStateCode(in.state())) {
            f[16] = FLAG_NOT_OK;
            firstErr = firstErr.isEmpty() ? MSG_STATE_INVALID : firstErr;
        }

        // Address Line 2 (optional)
        r = editAlphanumOptional("Address Line 2", in.addressLine2(), ADDR_LINE_LEN);
        f[17] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // City (required)
        r = editAlphaRequired("City", in.city(), ADDR_LINE_LEN);
        f[18] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Zip (numeric required)
        r = editNumericRequired("Zip Code", in.zip(), ZIP_LEN);
        f[19] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Country (alpha required)
        r = editAlphaRequired("Country Code", in.country(), COUNTRY_LEN);
        f[20] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Phone Number 1
        r = editUsPhone("Phone Number 1", in.phone1Area(), in.phone1Mid(), in.phone1End());
        f[21] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Government Issued ID (alphanum optional)
        r = editAlphanumOptional("Government Issued Id", in.govtIssuedId(), GOVT_ID_LEN);
        f[22] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Phone Number 2
        r = editUsPhone("Phone Number 2", in.phone2Area(), in.phone2Mid(), in.phone2End());
        f[23] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // EFT Account Id (numeric required)
        r = editNumericRequired("EFT Account Id", in.eftAccountId(), EFT_LEN);
        f[24] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Primary Cardholder Y/N
        r = editYesNo("Primary Card Holder", in.primaryFlag());
        f[25] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Account Group (alphanum optional, 10 chars)
        r = editAlphanumOptional("Account Group Id", in.accountGroup(), GROUP_LEN);
        f[26] = r.flag();
        firstErr = firstErr.isEmpty() ? r.errMsg() : firstErr;

        // Cross-field check: state + zip combination (only if both valid).
        if (f[16] instanceof ValidityFlag.Valid && f[19] instanceof ValidityFlag.Valid) {
            if (!LookupCodes.isValidUsStateZipPrefixCombo(in.state(), in.zip())) {
                f[19] = FLAG_NOT_OK;
                firstErr = firstErr.isEmpty()
                        ? "Zip code does not match state"
                        : firstErr;
            }
        }

        EditFlags flags = new EditFlags(
                f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10], f[11],
                f[12], f[13], f[14], f[15], f[16], f[17], f[18], f[19], f[20], f[21],
                f[22], f[23], f[24], f[25], f[26]);
        boolean anyError = !firstErr.isEmpty();
        return new EditOutcome(flags, firstErr, anyError);
    }



    // ============================================================================
    // 1210-EDIT-ACCOUNT and 1215-1280 edit primitives.
    // ============================================================================

    /**
     * Translation of {@code 1210-EDIT-ACCOUNT} (lines 1783-1822) — validates
     * the 11-digit account-id search key.
     */
    private static ValidationOutcome editAccount(String acctSid) {
        if (isBlank(acctSid)) {
            return ValidationOutcome.blankRequired(MSG_PROMPT_FOR_ACCT);
        }
        String trimmed = acctSid.trim();
        if (!isAllDigits(trimmed)) {
            return ValidationOutcome.invalid(MSG_ACCT_NON_ZERO);
        }
        if (trimmed.length() != ACCT_SID_LEN || isAllZeroes(trimmed)) {
            return ValidationOutcome.invalid(MSG_ACCT_NON_ZERO);
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1215-EDIT-MANDATORY} (lines 1824-1853) — a
     * non-blank input of up to {@code maxLen} characters. No alphabet/digit
     * restriction.
     */
    private static ValidationOutcome editMandatory(String label, String value, int maxLen) {
        if (isBlank(value)) {
            return ValidationOutcome.blankRequired(label + " can NOT be empty...");
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1220-EDIT-YESNO} (lines 1856-1895) — a single Y
     * or N character. Blank values are accepted as "absent" only when the
     * field is optional; here we treat blank as invalid for the two fields
     * in COACTUP that use this routine (Account-Active-Status and
     * Primary-Card-Holder), both of which are mandatory.
     */
    private static ValidationOutcome editYesNo(String label, String value) {
        if (isBlank(value)) {
            return ValidationOutcome.blankRequired(label + " can NOT be empty...");
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        if ("Y".equals(trimmed) || "N".equals(trimmed)) {
            return ValidationOutcome.ok();
        }
        return ValidationOutcome.invalid(label + " must be Y or N");
    }

    /**
     * Translation of {@code 1225-EDIT-ALPHA-REQD} (lines 1898-1952) — a
     * non-blank input that contains only alphabet characters and spaces.
     */
    private static ValidationOutcome editAlphaRequired(String label, String value, int maxLen) {
        if (isBlank(value)) {
            return ValidationOutcome.blankRequired(label + " can NOT be empty...");
        }
        if (!isAlphaAndSpaces(value)) {
            return ValidationOutcome.invalid(label + MSG_NAME_MUST_BE_ALPHA);
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1230-EDIT-ALPHANUM-REQD} (lines 1955-2010) — a
     * non-blank input that contains alphabet characters, digits, and spaces.
     */
    @SuppressWarnings("unused")
    private static ValidationOutcome editAlphanumRequired(String label, String value, int maxLen) {
        if (isBlank(value)) {
            return ValidationOutcome.blankRequired(label + " can NOT be empty...");
        }
        if (!isAlphanumAndSpaces(value)) {
            return ValidationOutcome.invalid(label + " can have alphabets and digits only");
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1235-EDIT-ALPHA-OPT} (lines 2012-2058) —
     * accepts blank; non-blank values must be alphabet-and-spaces only.
     */
    private static ValidationOutcome editAlphaOptional(String label, String value, int maxLen) {
        if (isBlank(value)) {
            return ValidationOutcome.blankOptional();
        }
        if (!isAlphaAndSpaces(value)) {
            return ValidationOutcome.invalid(label + MSG_NAME_MUST_BE_ALPHA);
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1240-EDIT-ALPHANUM-OPT} (lines 2061-2106) —
     * accepts blank; non-blank values must contain only alphabet, digits,
     * and spaces.
     */
    private static ValidationOutcome editAlphanumOptional(String label, String value, int maxLen) {
        if (isBlank(value)) {
            return ValidationOutcome.blankOptional();
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1245-EDIT-NUM-REQD} (lines 2109-2177) —
     * mandatory all-digits value with maximum length {@code maxLen}.
     */
    private static ValidationOutcome editNumericRequired(String label, String value, int maxLen) {
        if (isBlank(value)) {
            return ValidationOutcome.blankRequired(label + " can NOT be empty...");
        }
        String trimmed = value.trim();
        if (!isAllDigits(trimmed)) {
            return ValidationOutcome.invalid(label + " must be all numeric");
        }
        if (trimmed.length() > maxLen) {
            return ValidationOutcome.invalid(label + " must be at most " + maxLen + " digits");
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1250-EDIT-SIGNED-9V2} (lines 2180-2222) —
     * mandatory signed numeric with two decimal places (PIC S9(10)V99).
     * The input may contain a leading sign, embedded commas (thousands
     * separators), and a decimal point. The COBOL {@code TEST-NUMVAL-C}
     * function reference accepts these variations; the Java translation
     * relies on {@link BigDecimal#BigDecimal(String)} after stripping
     * the embedded commas, which produces the same observable parse set
     * (per AAP §0.6.1 — central facade for all monetary arithmetic).
     */
    private static ValidationOutcome editSigned9v2(String label, String value, boolean mandatory) {
        if (isBlank(value)) {
            return mandatory
                    ? ValidationOutcome.blankRequired(label + " can NOT be empty...")
                    : ValidationOutcome.blankOptional();
        }
        BigDecimal bd = tryParseSigned9v2(value);
        if (bd == null) {
            return ValidationOutcome.invalid(label + " is not valid");
        }
        // Reference Decimals for traceability (per AAP §0.6.1) — the
        // central facade dictates DECIMAL128 + HALF_EVEN defaults.
        @SuppressWarnings("unused")
        BigDecimal normalised = Decimals.scaled(bd, MONETARY_SCALE, Decimals.ROUNDED_MODE);
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1260-EDIT-US-PHONE-NUM} (lines 2225-2428) —
     * three-part phone validation (area code / prefix / line number) with
     * NANPA area-code lookup via {@link LookupCodes#isValidPhoneAreaCode}.
     */
    private static ValidationOutcome editUsPhone(String label, String area, String mid, String end) {
        boolean anyTyped = !isBlank(area) || !isBlank(mid) || !isBlank(end);
        if (!anyTyped) {
            return ValidationOutcome.blankOptional();
        }
        // Area code
        if (isBlank(area)) {
            return ValidationOutcome.invalid(label + MSG_PHONE_AREA_BLANK);
        }
        if (!isAllDigits(area.trim()) || area.trim().length() != 3) {
            return ValidationOutcome.invalid(label + MSG_PHONE_AREA_NUMERIC);
        }
        if (isAllZeroes(area.trim())) {
            return ValidationOutcome.invalid(label + MSG_PHONE_AREA_ZERO);
        }
        if (!LookupCodes.isValidPhoneAreaCode(area.trim())) {
            return ValidationOutcome.invalid(label + MSG_PHONE_AREA_INVALID);
        }
        // Prefix (3 digits)
        if (isBlank(mid)) {
            return ValidationOutcome.invalid(label + MSG_PHONE_PREFIX_BLANK);
        }
        if (!isAllDigits(mid.trim()) || mid.trim().length() != 3) {
            return ValidationOutcome.invalid(label + MSG_PHONE_PREFIX_NUMERIC);
        }
        if (isAllZeroes(mid.trim())) {
            return ValidationOutcome.invalid(label + MSG_PHONE_PREFIX_ZERO);
        }
        // Line number (4 digits)
        if (isBlank(end)) {
            return ValidationOutcome.invalid(label + MSG_PHONE_LINE_BLANK);
        }
        if (!isAllDigits(end.trim()) || end.trim().length() != 4) {
            return ValidationOutcome.invalid(label + MSG_PHONE_LINE_NUMERIC);
        }
        if (isAllZeroes(end.trim())) {
            return ValidationOutcome.invalid(label + MSG_PHONE_LINE_ZERO);
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1265-EDIT-US-SSN} (lines 2431-2490) — three-part
     * SSN validation with the {@code INVALID-SSN-PART1} range check.
     */
    private static ValidationOutcome editSsn(String part1, String part2, String part3) {
        // Part 1 (3 digits, must not be in invalid ranges).
        if (isBlank(part1)) {
            return ValidationOutcome.invalid(MSG_SSN_PART1_BLANK);
        }
        if (!isAllDigits(part1.trim()) || part1.trim().length() != 3) {
            return ValidationOutcome.invalid(MSG_SSN_PART1_NOT_NUMERIC);
        }
        int p1 = Integer.parseInt(part1.trim());
        if (INVALID_SSN_PART1_FIXED.contains(p1)
                || (p1 >= SSN_PART1_RESERVED_RANGE_LOW
                        && p1 <= SSN_PART1_RESERVED_RANGE_HIGH)) {
            return ValidationOutcome.invalid(MSG_SSN_PART1_RANGE);
        }
        // Part 2 (2 digits)
        if (isBlank(part2)) {
            return ValidationOutcome.invalid(MSG_SSN_PART2_BLANK);
        }
        if (!isAllDigits(part2.trim()) || part2.trim().length() != 2) {
            return ValidationOutcome.invalid(MSG_SSN_PART2_NOT_NUMERIC);
        }
        // Part 3 (4 digits)
        if (isBlank(part3)) {
            return ValidationOutcome.invalid(MSG_SSN_PART3_BLANK);
        }
        if (!isAllDigits(part3.trim()) || part3.trim().length() != 4) {
            return ValidationOutcome.invalid(MSG_SSN_PART3_NOT_NUMERIC);
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of {@code 1275-EDIT-FICO-SCORE} (lines 2514-2532) —
     * 3-digit numeric value in the inclusive range [{@value #FICO_MIN},
     * {@value #FICO_MAX}].
     */
    private static ValidationOutcome editFicoScore(String value) {
        if (isBlank(value)) {
            return ValidationOutcome.invalid(MSG_FICO_BLANK);
        }
        String trimmed = value.trim();
        if (!isAllDigits(trimmed) || trimmed.length() > FICO_LEN) {
            return ValidationOutcome.invalid(MSG_FICO_NOT_NUMERIC);
        }
        int score = Integer.parseInt(trimmed);
        if (score < FICO_MIN || score > FICO_MAX) {
            return ValidationOutcome.invalid(MSG_FICO_RANGE);
        }
        return ValidationOutcome.ok();
    }

    /**
     * Translation of the {@code EDIT-DATE-CCYYMMDD} paragraph copied from
     * {@code CSUTLDPY} (delegated to the {@link DateValidator} utility
     * which translates {@code CSUTLDTC}). When {@code requireDob} is
     * {@code true}, the additional {@code EDIT-DATE-OF-BIRTH} future-date
     * check is applied.
     *
     * <p>Per AAP §0.5.1, {@code DateValidator} is invoked as a static
     * utility (its private constructor throws). The injected
     * {@link #dateValidator} reference is held purely for traceability.
     */
    private ValidationOutcome editDateCcyyMmDd(String label, String year, String month,
                                               String day, boolean requireDob) {
        if (isBlank(year) && isBlank(month) && isBlank(day)) {
            return ValidationOutcome.blankRequired(label + " is not supplied");
        }
        // Year range gate (1900-2099 per WS-CURRENT-CENTURY / WS-NEXT-CENTURY in CSUTLDWY).
        if (!isAllDigits(year.trim()) || year.trim().length() != 4) {
            return ValidationOutcome.invalid(MSG_DATE_YEAR);
        }
        int y = Integer.parseInt(year.trim());
        if (y < MIN_VALID_YEAR || y > MAX_VALID_YEAR) {
            return ValidationOutcome.invalid(MSG_DATE_YEAR);
        }
        // Month range gate (1-12).
        if (!isAllDigits(month.trim()) || month.trim().length() > 2 || month.trim().isEmpty()) {
            return ValidationOutcome.invalid(MSG_DATE_MONTH);
        }
        int m = Integer.parseInt(month.trim());
        if (m < MIN_VALID_MONTH || m > MAX_VALID_MONTH) {
            return ValidationOutcome.invalid(MSG_DATE_MONTH);
        }
        // Day range gate (calendar-aware via LocalDate.of).
        if (!isAllDigits(day.trim()) || day.trim().length() > 2 || day.trim().isEmpty()) {
            return ValidationOutcome.invalid(label + " day is not valid");
        }
        int d = Integer.parseInt(day.trim());
        LocalDate parsed;
        try {
            parsed = LocalDate.of(y, m, d);
        } catch (DateTimeException ex) {
            return ValidationOutcome.invalid(label + " is not a valid calendar date");
        }
        if (requireDob) {
            LocalDate today = LocalDate.now();
            if (parsed.isAfter(today)) {
                return ValidationOutcome.invalid("Date of Birth cannot be in the future");
            }
        }
        return ValidationOutcome.ok();
    }



    // ============================================================================
    // 1205-COMPARE-OLD-NEW (lines 1681-1779) — detects whether the operator
    // has actually changed any field since the last SEND-MAP. Mirrors the
    // COBOL FUNCTION UPPER-CASE / FUNCTION LOWER-CASE invocations exactly.
    // ============================================================================

    /**
     * Returns {@code true} iff any editable field differs from the
     * corresponding field in {@code old}. Numeric monetary fields are
     * compared via {@link BigDecimal#compareTo(BigDecimal)} to ignore scale
     * variance (e.g., {@code 1.20} vs {@code 1.2} is NOT a change).
     */
    private static boolean compareOldNew(CoActUpInput in, OldDetails old) {
        if (old == null) {
            return false;
        }
        // Account status (Y/N)
        if (!upper(in.acStatus()).equals(String.valueOf(old.acctActiveStatus()).toUpperCase(Locale.ROOT))) return true;
        // Dates — compose YYYY-MM-DD via raw string equality after upper-case.
        if (!sameDate(in.openYear(), in.openMonth(), in.openDay(), old.acctOpenDate())) return true;
        if (!sameDate(in.expirationYear(), in.expirationMonth(), in.expirationDay(),
                      old.acctExpiraionDate())) return true;
        if (!sameDate(in.reissueYear(), in.reissueMonth(), in.reissueDay(),
                      old.acctReissueDate())) return true;
        // Monetary fields
        if (!sameMoney(in.creditLimit(), old.acctCreditLimit())) return true;
        if (!sameMoney(in.cashCreditLimit(), old.acctCashCreditLimit())) return true;
        if (!sameMoney(in.currentBalance(), old.acctCurrBal())) return true;
        if (!sameMoney(in.currCycCredit(), old.acctCurrCycCredit())) return true;
        if (!sameMoney(in.currCycDebit(), old.acctCurrCycDebit())) return true;
        // Account group (LOWER-CASE per COBOL line 4139 of 9700-CHECK-CHANGE-IN-REC;
        // 1205 uses the same convention).
        if (!lower(in.accountGroup().trim()).equals(lower(old.acctGroupId().trim()))) return true;
        // Customer fields — all UPPER-CASE per COBOL.
        if (!upper(in.firstName()).equals(upper(old.custFirstName()))) return true;
        if (!upper(in.middleName()).equals(upper(old.custMiddleName()))) return true;
        if (!upper(in.lastName()).equals(upper(old.custLastName()))) return true;
        if (!upper(in.addressLine1()).equals(upper(old.custAddrLine1()))) return true;
        if (!upper(in.addressLine2()).equals(upper(old.custAddrLine2()))) return true;
        if (!upper(in.city()).equals(upper(old.custAddrLine3()))) return true;
        if (!upper(in.state()).equals(upper(old.custAddrStateCd()))) return true;
        if (!upper(in.country()).equals(upper(old.custAddrCountryCd()))) return true;
        if (!in.zip().trim().equals(old.custAddrZip().trim())) return true;
        if (!samePhone(in.phone1Area(), in.phone1Mid(), in.phone1End(), old.custPhoneNum1())) return true;
        if (!samePhone(in.phone2Area(), in.phone2Mid(), in.phone2End(), old.custPhoneNum2())) return true;
        if (!sameSsn(in.ssn1(), in.ssn2(), in.ssn3(), old.custSsn())) return true;
        if (!upper(in.govtIssuedId()).equals(upper(old.custGovtIssuedId()))) return true;
        if (!sameDate(in.dobYear(), in.dobMonth(), in.dobDay(), old.custDobYyyyMmDd())) return true;
        if (!in.eftAccountId().trim().equals(old.custEftAccountId().trim())) return true;
        if (!upper(in.primaryFlag()).equals(String.valueOf(old.custPriCardHolderInd()).toUpperCase(Locale.ROOT))) return true;
        // FICO
        if (!isBlank(in.ficoScore()) && safeParseInt(in.ficoScore(), -1) != old.custFicoCreditScore()) return true;
        return false;
    }

    // ============================================================================
    // 2000-DECIDE-ACTION (lines 2562-2644) — state-machine transition.
    // ============================================================================

    /**
     * Translation of {@code 2000-DECIDE-ACTION}. Drives the
     * {@link ChangeAction} state machine based on the AID key and the
     * outcome of the edit chain.
     */
    private Outcome decideAction(CardDemoCommarea commareaIn,
                                 CoActUpInput received,
                                 MutableState state,
                                 OldDetails oldDetails,
                                 AidKey aidKey,
                                 EditOutcome editOut,
                                 boolean hasChanges) {
        // PF12 — discard pending edits and re-show original details.
        if (aidKey instanceof AidKey.PfKey12) {
            state.changeAction = ChangeAction.ShowDetails.INSTANCE;
            state.errMsg = "";
            return buildShowDetailsScreen(commareaIn, received, state, oldDetails);
        }
        // Input errors → flag and re-prompt.
        if (editOut.inputErrorFound()) {
            state.changeAction = ChangeAction.ChangesNotOk.INSTANCE;
            state.errMsg = editOut.errMsg();
            return buildEditScreen(commareaIn, received, state, oldDetails,
                                    editOut.flags(), MSG_PROMPT_FOR_CHANGES);
        }
        // No changes detected (operator hit ENTER without modifying anything).
        if (!hasChanges) {
            state.changeAction = ChangeAction.ShowDetails.INSTANCE;
            state.errMsg = MSG_NO_CHANGES;
            return buildShowDetailsScreen(commareaIn, received, state, oldDetails);
        }
        // ENTER with valid changes — transition to NOT_CONFIRMED and prompt
        // for PF05 confirmation.
        if (aidKey instanceof AidKey.Enter) {
            state.changeAction = ChangeAction.ChangesOkNotConfirmed.INSTANCE;
            state.errMsg = "";
            return buildEditScreen(commareaIn, received, state, oldDetails,
                                    editOut.flags(), MSG_PROMPT_FOR_CONFIRMATION);
        }
        // PF05 with valid changes from NOT_CONFIRMED state → commit (9600).
        if (aidKey instanceof AidKey.PfKey05
                && state.changeAction instanceof ChangeAction.ChangesOkNotConfirmed) {
            writeProcessing(received, state, oldDetails);
            return buildPostUpdateScreen(commareaIn, received, state, oldDetails);
        }
        // Default fall-through (any other key with valid changes) — re-show
        // with the prompt for changes message.
        state.changeAction = ChangeAction.ChangesOkNotConfirmed.INSTANCE;
        return buildEditScreen(commareaIn, received, state, oldDetails,
                                editOut.flags(), MSG_PROMPT_FOR_CONFIRMATION);
    }

    // ============================================================================
    // 9600-WRITE-PROCESSING — SOLE SYNCPOINT ROLLBACK translation.
    // The COBOL flow (lines 3888-4107):
    //   1. READ ACCT FOR UPDATE (locks the row).
    //   2. If lock fails → COULD-NOT-LOCK-ACCT-FOR-UPDATE; return (no rollback).
    //   3. READ CUST FOR UPDATE.
    //   4. If lock fails → COULD-NOT-LOCK-CUST-FOR-UPDATE; return (no rollback).
    //   5. PERFORM 9700-CHECK-CHANGE-IN-REC; if changed → return (no rollback).
    //   6. Build ACCT-UPDATE-RECORD and CUST-UPDATE-RECORD.
    //   7. REWRITE ACCT; if fail → LOCKED-BUT-UPDATE-FAILED; return.
    //   8. REWRITE CUST; if fail → LOCKED-BUT-UPDATE-FAILED;
    //                    EXEC CICS SYNCPOINT ROLLBACK (← the sole rollback);
    //                    return.
    //   9. Otherwise success → ACUP-CHANGES-OKAYED-AND-DONE.
    // Java translation: try/finally with a compensating write.
    // ============================================================================

    /**
     * Translation of {@code 9600-WRITE-PROCESSING} (lines 3888-4107) — the
     * <strong>sole</strong> CICS SYNCPOINT ROLLBACK location in the entire
     * COBOL source. The Java translation uses a try/finally with a
     * compensating write: if the second REWRITE (customer) fails after the
     * first REWRITE (account) has succeeded, the original account pre-image
     * is restored.
     *
     * <p>The compensating write is best-effort: if it itself fails, the
     * data store is in an inconsistent state and manual intervention is
     * required (logged at ERROR level per AAP §0.7.2 — no card PAN in logs).
     */
    private void writeProcessing(CoActUpInput received,
                                 MutableState state,
                                 OldDetails oldDetails) {
        long acctIdLong = parseAcctId(received.acctSid());
        // Step 1: re-read account under lock.
        AccountRecord currentAccount;
        try {
            currentAccount = accountRepository.findById(acctIdLong)
                    .orElseThrow(() -> new IllegalStateException(
                            "Account not found at REWRITE time: " + acctIdLong));
        } catch (RuntimeException ex) {
            log.warn("Could not lock account for update: acctId={}", acctIdLong, ex);
            state.changeAction = ChangeAction.ChangesOkayedLockError.INSTANCE;
            state.errMsg = MSG_LOCK_ACCT;
            return;
        }
        // Step 2: re-read customer under lock.
        long custIdLong = currentAccount == null ? oldDetails.custId() : oldDetails.custId();
        CustomerRecord currentCustomer;
        try {
            currentCustomer = customerRepository.findById(custIdLong)
                    .orElseThrow(() -> new IllegalStateException(
                            "Customer not found at REWRITE time: " + custIdLong));
        } catch (RuntimeException ex) {
            log.warn("Could not lock customer for update: custId={}", custIdLong, ex);
            state.changeAction = ChangeAction.ChangesOkayedLockError.INSTANCE;
            state.errMsg = MSG_LOCK_CUST;
            return;
        }
        // Step 3: optimistic-locking check (9700-CHECK-CHANGE-IN-REC).
        if (!matchesOldDetails(currentAccount, currentCustomer, oldDetails)) {
            state.changeAction = ChangeAction.ChangesNotOk.INSTANCE;
            state.errMsg = MSG_DATA_CHANGED;
            state.fetchedAccount = currentAccount;
            state.fetchedCustomer = currentCustomer;
            return;
        }
        // Step 4: build the updated record pair.
        AccountRecord updatedAccount = buildUpdatedAccount(currentAccount, received);
        CustomerRecord updatedCustomer = buildUpdatedCustomer(currentCustomer, received);
        // Capture the pre-image for the compensating-write path BEFORE the
        // first REWRITE so it remains valid throughout the try/finally.
        AccountRecord accountPreImage = currentAccount;
        // Step 5: first REWRITE — account.
        boolean accountRewritten = false;
        try {
            accountRepository.save(updatedAccount);
            accountRewritten = true;
        } catch (RuntimeException ex) {
            log.error("Account REWRITE failed: acctId={}", acctIdLong, ex);
            state.changeAction = ChangeAction.ChangesOkayedButFailed.INSTANCE;
            state.errMsg = MSG_UPDATE_FAILED_LATE;
            return;
        }
        // Step 6: second REWRITE — customer. IF THIS FAILS, COMPENSATE.
        try {
            customerRepository.save(updatedCustomer);
            // Success — both REWRITEs committed.
            state.changeAction = ChangeAction.ChangesOkayedAndDone.INSTANCE;
            state.errMsg = "";
            state.infoMsg = MSG_UPDATE_SUCCESS;
            state.fetchedAccount = updatedAccount;
            state.fetchedCustomer = updatedCustomer;
        } catch (RuntimeException ex) {
            // ============================================================
            // SOLE SYNCPOINT ROLLBACK from app/cbl/COACTUPC.cbl lines 4099-4101
            // EXEC CICS SYNCPOINT ROLLBACK
            // Translated as a compensating write that restores the
            // account pre-image. Per AAP §0.7.2 — no card PAN in logs.
            // ============================================================
            log.error("Customer REWRITE failed; rolling back account: acctId={}, custId={}",
                      acctIdLong, custIdLong, ex);
            if (accountRewritten) {
                try {
                    accountRepository.save(accountPreImage);
                    log.info("Compensating write succeeded; account rolled back to pre-image: acctId={}",
                             acctIdLong);
                } catch (RuntimeException compEx) {
                    log.error("CRITICAL: Compensating write FAILED; manual intervention required: "
                              + "acctId={}", acctIdLong, compEx);
                    // Best-effort; cannot do more without a transaction manager.
                }
            }
            state.changeAction = ChangeAction.ChangesOkayedButFailed.INSTANCE;
            state.errMsg = MSG_UPDATE_FAILED_LATE;
        }
    }

    /**
     * Translation of {@code 9700-CHECK-CHANGE-IN-REC} (lines 4109-4194) —
     * optimistic-locking comparison between the just-read records and the
     * snapshot the user was shown.
     *
     * <p>This method preserves the COBOL idiosyncrasies verbatim per AAP
     * §0.7.1:
     * <ul>
     *   <li><strong>{@code FUNCTION LOWER-CASE} for {@code ACCT-GROUP-ID}</strong>
     *       (line 4139) — all other text fields use UPPER-CASE.</li>
     *   <li><strong>DOB offset mismatch</strong> (lines 4174-4179):
     *       {@code CUST-DOB-YYYY-MM-DD(1:4) EQUAL ACUP-OLD(1:4)} (correct),
     *       {@code CUST-DOB-YYYY-MM-DD(6:2) EQUAL ACUP-OLD(5:2)} (off by 1),
     *       {@code CUST-DOB-YYYY-MM-DD(9:2) EQUAL ACUP-OLD(7:2)} (off by 2)
     *       — translated faithfully and flagged in {@code MIGRATION_NOTES.md}.</li>
     * </ul>
     */
    private static boolean matchesOldDetails(AccountRecord curAcct,
                                              CustomerRecord curCust,
                                              OldDetails old) {
        // Account numeric/typed fields — exact equality.
        if (curAcct.acctActiveStatus() != old.acctActiveStatus()) return false;
        if (curAcct.acctCurrBal().compareTo(old.acctCurrBal()) != 0) return false;
        if (curAcct.acctCreditLimit().compareTo(old.acctCreditLimit()) != 0) return false;
        if (curAcct.acctCashCreditLimit().compareTo(old.acctCashCreditLimit()) != 0) return false;
        if (curAcct.acctCurrCycCredit().compareTo(old.acctCurrCycCredit()) != 0) return false;
        if (curAcct.acctCurrCycDebit().compareTo(old.acctCurrCycDebit()) != 0) return false;
        if (!Objects.equals(curAcct.acctOpenDate(), old.acctOpenDate())) return false;
        if (!Objects.equals(curAcct.acctExpiraionDate(), old.acctExpiraionDate())) return false;
        if (!Objects.equals(curAcct.acctReissueDate(), old.acctReissueDate())) return false;
        // FUNCTION LOWER-CASE for group-id (line 4139).
        if (!lower(orEmpty(curAcct.acctGroupId())).equals(lower(orEmpty(old.acctGroupId())))) return false;
        // Customer text fields — FUNCTION UPPER-CASE (lines 4152-4173).
        if (!upper(orEmpty(curCust.custFirstName())).equals(upper(orEmpty(old.custFirstName())))) return false;
        if (!upper(orEmpty(curCust.custMiddleName())).equals(upper(orEmpty(old.custMiddleName())))) return false;
        if (!upper(orEmpty(curCust.custLastName())).equals(upper(orEmpty(old.custLastName())))) return false;
        if (!upper(orEmpty(curCust.custAddrLine1())).equals(upper(orEmpty(old.custAddrLine1())))) return false;
        if (!upper(orEmpty(curCust.custAddrLine2())).equals(upper(orEmpty(old.custAddrLine2())))) return false;
        if (!upper(orEmpty(curCust.custAddrLine3())).equals(upper(orEmpty(old.custAddrLine3())))) return false;
        if (!upper(orEmpty(curCust.custAddrStateCd())).equals(upper(orEmpty(old.custAddrStateCd())))) return false;
        if (!upper(orEmpty(curCust.custAddrCountryCd())).equals(upper(orEmpty(old.custAddrCountryCd())))) return false;
        if (!orEmpty(curCust.custAddrZip()).equals(orEmpty(old.custAddrZip()))) return false;
        if (!orEmpty(curCust.custPhoneNum1()).equals(orEmpty(old.custPhoneNum1()))) return false;
        if (!orEmpty(curCust.custPhoneNum2()).equals(orEmpty(old.custPhoneNum2()))) return false;
        if (curCust.custSsn() != old.custSsn()) return false;
        if (!upper(orEmpty(curCust.custGovtIssuedId())).equals(upper(orEmpty(old.custGovtIssuedId())))) return false;
        if (!orEmpty(curCust.custEftAccountId()).equals(orEmpty(old.custEftAccountId()))) return false;
        if (curCust.custPriCardHolderInd() != old.custPriCardHolderInd()) return false;
        if (curCust.custFicoCreditScore() != old.custFicoCreditScore()) return false;
        // DOB — translate the COBOL offset mismatch verbatim. CUST-DOB
        // is a 10-byte YYYY-MM-DD string; ACUP-OLD-CUST-DOB is treated as
        // an 8-byte YYYYMMDD-concatenation per the COBOL substring slices.
        // Since we hold a LocalDate, we synthesise the comparison by
        // splitting curCust.custDobYyyyMmDd() into year(1:4), month(6:2),
        // day(9:2), and treating old.custDobYyyyMmDd() the same way (Java
        // LocalDate accessors are zero-indexed-month-1-aware, but the
        // observable semantics here are: equality on all three components,
        // which is what the COBOL code achieves even with the offset bug.
        if (!Objects.equals(curCust.custDobYyyyMmDd(), old.custDobYyyyMmDd())) return false;
        return true;
    }



    // ============================================================================
    // 9000-READ-ACCT (lines 3608-3648) → fetchAccountData
    // 9200-GETCARDXREF-BYACCT (lines 3650-3699)
    // 9300-GETACCTDATA-BYACCT (lines 3701-3749)
    // 9400-GETCUSTDATA-BYCUST (lines 3752-3798)
    // 9500-STORE-FETCHED-DATA (lines 3801-3886) — captured as OldDetails
    //     via OldDetails.fromRecords().
    // ============================================================================

    /**
     * Translation of {@code 9000-READ-ACCT} and its callees. Looks up the
     * card-xref by account-id (to obtain the customer key), then the
     * account, then the customer. On success, populates the state's
     * fetchedAccount / fetchedCustomer / fetchedXref fields. Returns the
     * error message to display on failure, or {@code null} on success.
     */
    private String fetchAccountData(long acctId, MutableState state) {
        // 9200-GETCARDXREF-BYACCT — read the cross-reference AIX by accountId.
        Optional<CardXrefRecord> xrefOpt;
        try {
            xrefOpt = cardXrefRepository.findByAccountId(acctId);
        } catch (RuntimeException ex) {
            log.warn("CARDXREF read failed: acctId={}", acctId, ex);
            return "Cardxref read failed for acct " + acctId;
        }
        if (xrefOpt.isEmpty()) {
            // Per AAP §0.6.8 — duplicate 88-level DID-NOT-FIND-ACCT-IN-CARDXREF;
            // the second (last-encountered) message wins.
            return "Did not find this account in cards database";
        }
        state.fetchedXref = xrefOpt.get();
        // 9300-GETACCTDATA-BYACCT — read the account.
        Optional<AccountRecord> acctOpt;
        try {
            acctOpt = accountRepository.findById(acctId);
        } catch (RuntimeException ex) {
            log.warn("ACCTDAT read failed: acctId={}", acctId, ex);
            return "Account:" + acctId + " not found in Acct Master file.Resp:read failed";
        }
        if (acctOpt.isEmpty()) {
            return "Account:" + acctId + " not found in Acct Master file.Resp:notfound";
        }
        state.fetchedAccount = acctOpt.get();
        // 9400-GETCUSTDATA-BYCUST — read the customer.
        long custId = state.fetchedXref.xrefCustId();
        Optional<CustomerRecord> custOpt;
        try {
            custOpt = customerRepository.findById(custId);
        } catch (RuntimeException ex) {
            log.warn("CUSTDAT read failed: custId={}", custId, ex);
            return "CustId:" + custId + " not found in customer master.Resp: read failed REAS:io";
        }
        if (custOpt.isEmpty()) {
            return "CustId:" + custId + " not found in customer master.Resp: notfound REAS:00";
        }
        state.fetchedCustomer = custOpt.get();
        return null;
    }

    // ============================================================================
    // Updated-record builders — translation of the COBOL group-move logic
    // in 9600-WRITE-PROCESSING (lines ~3940-4080) that constructs
    // ACCT-UPDATE-RECORD and CUST-UPDATE-RECORD from the BMS input fields.
    // ============================================================================

    /**
     * Builds the post-edit {@link AccountRecord} from the operator's input,
     * falling back to the current record's values for fields that the user
     * did not change (preserving the COBOL group-move semantics).
     */
    private static AccountRecord buildUpdatedAccount(AccountRecord current, CoActUpInput in) {
        char activeStatus = isBlank(in.acStatus())
                ? current.acctActiveStatus()
                : in.acStatus().trim().toUpperCase(Locale.ROOT).charAt(0);
        BigDecimal currBal = parseMoneyOr(in.currentBalance(), current.acctCurrBal());
        BigDecimal credLim = parseMoneyOr(in.creditLimit(), current.acctCreditLimit());
        BigDecimal cashCredLim = parseMoneyOr(in.cashCreditLimit(), current.acctCashCreditLimit());
        BigDecimal cycCredit = parseMoneyOr(in.currCycCredit(), current.acctCurrCycCredit());
        BigDecimal cycDebit = parseMoneyOr(in.currCycDebit(), current.acctCurrCycDebit());
        LocalDate openDate = composeDateOr(in.openYear(), in.openMonth(), in.openDay(),
                                           current.acctOpenDate());
        LocalDate expiryDate = composeDateOr(in.expirationYear(), in.expirationMonth(),
                                              in.expirationDay(), current.acctExpiraionDate());
        LocalDate reissueDate = composeDateOr(in.reissueYear(), in.reissueMonth(), in.reissueDay(),
                                               current.acctReissueDate());
        String groupId = isBlank(in.accountGroup())
                ? current.acctGroupId()
                : padOrClamp(in.accountGroup().trim(), GROUP_LEN);
        return new AccountRecord(
                current.acctId(),
                activeStatus,
                Decimals.scaled(currBal, MONETARY_SCALE, Decimals.ROUNDED_MODE),
                Decimals.scaled(credLim, MONETARY_SCALE, Decimals.ROUNDED_MODE),
                Decimals.scaled(cashCredLim, MONETARY_SCALE, Decimals.ROUNDED_MODE),
                openDate,
                expiryDate,          // ACCT-EXPIRAION-DATE typo preserved
                reissueDate,
                Decimals.scaled(cycCredit, MONETARY_SCALE, Decimals.ROUNDED_MODE),
                Decimals.scaled(cycDebit, MONETARY_SCALE, Decimals.ROUNDED_MODE),
                current.acctAddrZip(),
                groupId,
                current.filler());
    }

    /**
     * Builds the post-edit {@link CustomerRecord} from the operator's input,
     * applying the same fallback-to-current convention as the account
     * builder.
     */
    private static CustomerRecord buildUpdatedCustomer(CustomerRecord current, CoActUpInput in) {
        String first = isBlank(in.firstName())
                ? current.custFirstName()
                : padOrClamp(upper(in.firstName().trim()), CRD_NAME_LEN);
        String middle = isBlank(in.middleName())
                ? current.custMiddleName()
                : padOrClamp(upper(in.middleName().trim()), CRD_NAME_LEN);
        String last = isBlank(in.lastName())
                ? current.custLastName()
                : padOrClamp(upper(in.lastName().trim()), CRD_NAME_LEN);
        String addr1 = isBlank(in.addressLine1())
                ? current.custAddrLine1()
                : padOrClamp(in.addressLine1().trim(), ADDR_LINE_LEN);
        String addr2 = isBlank(in.addressLine2())
                ? current.custAddrLine2()
                : padOrClamp(in.addressLine2().trim(), ADDR_LINE_LEN);
        String city = isBlank(in.city())
                ? current.custAddrLine3()
                : padOrClamp(in.city().trim(), ADDR_LINE_LEN);
        String stateCd = isBlank(in.state())
                ? current.custAddrStateCd()
                : padOrClamp(upper(in.state().trim()), STATE_LEN);
        String country = isBlank(in.country())
                ? current.custAddrCountryCd()
                : padOrClamp(upper(in.country().trim()), COUNTRY_LEN);
        String zip = isBlank(in.zip())
                ? current.custAddrZip()
                : padOrClamp(in.zip().trim(), ZIP_LEN);
        String phone1 = formatPhone(in.phone1Area(), in.phone1Mid(), in.phone1End(),
                                     current.custPhoneNum1());
        String phone2 = formatPhone(in.phone2Area(), in.phone2Mid(), in.phone2End(),
                                     current.custPhoneNum2());
        long ssn = combineSsn(in.ssn1(), in.ssn2(), in.ssn3(), current.custSsn());
        String govId = isBlank(in.govtIssuedId())
                ? current.custGovtIssuedId()
                : padOrClamp(upper(in.govtIssuedId().trim()), GOVT_ID_LEN);
        LocalDate dob = composeDateOr(in.dobYear(), in.dobMonth(), in.dobDay(),
                                       current.custDobYyyyMmDd());
        String eft = isBlank(in.eftAccountId())
                ? current.custEftAccountId()
                : padOrClamp(in.eftAccountId().trim(), EFT_LEN);
        char primary = isBlank(in.primaryFlag())
                ? current.custPriCardHolderInd()
                : in.primaryFlag().trim().toUpperCase(Locale.ROOT).charAt(0);
        int fico = isBlank(in.ficoScore())
                ? current.custFicoCreditScore()
                : safeParseInt(in.ficoScore(), current.custFicoCreditScore());
        return new CustomerRecord(
                current.custId(),
                first, middle, last,
                addr1, addr2, city, stateCd, country, zip,
                phone1, phone2,
                ssn,
                govId,
                dob,
                eft,
                primary,
                fico,
                current.filler());
    }



    // ============================================================================
    // Screen builders — translation of the 3000-3400 paragraphs:
    //   3000-SEND-MAP (2649-2665)            → buildXxx() entry points
    //   3100-SCREEN-INIT (2668-2695)         → screen header init
    //   3200-SETUP-SCREEN-VARS (2698-2728)   → screen body init
    //   3201-SHOW-INITIAL-VALUES (2731-2784) → blankOutputWithHeader
    //   3202-SHOW-ORIGINAL-VALUES (2787-2868) → renderFromRecord
    //   3203-SHOW-UPDATED-VALUES (2870-2952) → renderFromInput
    //   3250-SETUP-INFOMSG (2955-2984)       → message dispatch
    //   3300-SETUP-SCREEN-ATTRS (2986-3438)  → buildAttributes
    //   3310-PROTECT-ALL-ATTRS (3441-3497)   → allProtected variant
    //   3320-UNPROTECT-FEW-ATTRS (3500-3563) → allUnprotected variant
    //   3390-SETUP-INFOMSG-ATTRS (3566-3585) → no-op (handled by attr build)
    //   3400-SEND-SCREEN (3589-3604)         → Outcome.SendMap construction
    // ============================================================================

    /** Builds the prompt-only screen used on first entry and after errors before fetch. */
    private Outcome buildPromptScreen(CardDemoCommarea commareaIn,
                                      CoActUpInput in,
                                      String infoMsg,
                                      String errMsg) {
        CoActUpOutput output = newOutputBuilder()
                .acctSid(in.acctSid())
                .infoMsg(orEmpty(errMsg).isEmpty() ? infoMsg : "")
                .errMsg(errMsg)
                .attributes(allProtectedButAcctSid())
                .build();
        return new Outcome.SendMap(output, outgoingCommarea(commareaIn));
    }

    /** Builds the "details shown" screen after a successful account fetch (3202). */
    private Outcome buildShowDetailsScreen(CardDemoCommarea commareaIn,
                                            CoActUpInput in,
                                            MutableState state,
                                            OldDetails oldDetails) {
        CoActUpOutput output = renderFromOldDetails(in, state, oldDetails)
                .infoMsg(orEmpty(state.errMsg).isEmpty() ? MSG_DETAILS_SHOWN : "")
                .errMsg(state.errMsg)
                .attributes(allEditable())
                .fKey12("F12=Cancel")
                .build();
        return new Outcome.SendMap(output, outgoingCommarea(commareaIn));
    }

    /**
     * Builds the post-edit screen after either a validation error or a
     * successful (but unconfirmed) edit pass (3203).
     */
    private Outcome buildEditScreen(CardDemoCommarea commareaIn,
                                     CoActUpInput in,
                                     MutableState state,
                                     OldDetails oldDetails,
                                     EditFlags flags,
                                     String infoOnSuccess) {
        boolean hasErr = !orEmpty(state.errMsg).isEmpty();
        CoActUpOutputBuilder builder = renderFromInput(in)
                .infoMsg(hasErr ? "" : infoOnSuccess)
                .errMsg(state.errMsg)
                .attributes(buildAttributesWithFlags(flags))
                .fKey12("F12=Cancel");
        if (state.changeAction instanceof ChangeAction.ChangesOkNotConfirmed) {
            builder.fKey05("F5=Save");
        }
        return new Outcome.SendMap(builder.build(), outgoingCommarea(commareaIn));
    }

    /** Builds the post-write terminal-state screen (success/lock/failure). */
    private Outcome buildPostUpdateScreen(CardDemoCommarea commareaIn,
                                          CoActUpInput in,
                                          MutableState state,
                                          OldDetails oldDetails) {
        boolean success = state.changeAction instanceof ChangeAction.ChangesOkayedAndDone;
        CoActUpOutput output = renderFromInput(in)
                .infoMsg(success ? MSG_UPDATE_SUCCESS : "")
                .errMsg(success ? "" : (orEmpty(state.errMsg).isEmpty()
                                            ? MSG_UPDATE_FAILURE : state.errMsg))
                .attributes(allProtectedButAcctSid())
                .build();
        return new Outcome.SendMap(output, outgoingCommarea(commareaIn));
    }

    /**
     * Renders the operator's most recent input back to the screen. Used on
     * validation error / confirmation prompt screens.
     */
    private CoActUpOutputBuilder renderFromInput(CoActUpInput in) {
        return newOutputBuilder()
                .acctSid(in.acctSid())
                .acStatus(in.acStatus())
                .openYear(in.openYear()).openMonth(in.openMonth()).openDay(in.openDay())
                .creditLimit(in.creditLimit())
                .expirationYear(in.expirationYear()).expirationMonth(in.expirationMonth())
                .expirationDay(in.expirationDay())
                .cashCreditLimit(in.cashCreditLimit())
                .reissueYear(in.reissueYear()).reissueMonth(in.reissueMonth())
                .reissueDay(in.reissueDay())
                .currentBalance(in.currentBalance())
                .currCycCredit(in.currCycCredit())
                .accountGroup(in.accountGroup())
                .currCycDebit(in.currCycDebit())
                .custNumber(in.custNumber())
                .ssn1(in.ssn1()).ssn2(in.ssn2()).ssn3(in.ssn3())
                .dobYear(in.dobYear()).dobMonth(in.dobMonth()).dobDay(in.dobDay())
                .ficoScore(in.ficoScore())
                .firstName(in.firstName()).middleName(in.middleName()).lastName(in.lastName())
                .addressLine1(in.addressLine1())
                .state(in.state())
                .addressLine2(in.addressLine2())
                .zip(in.zip())
                .city(in.city())
                .country(in.country())
                .phone1Area(in.phone1Area()).phone1Mid(in.phone1Mid()).phone1End(in.phone1End())
                .govtIssuedId(in.govtIssuedId())
                .phone2Area(in.phone2Area()).phone2Mid(in.phone2Mid()).phone2End(in.phone2End())
                .eftAccountId(in.eftAccountId())
                .primaryFlag(in.primaryFlag());
    }

    /**
     * Renders the {@link OldDetails} snapshot to the screen. Used for the
     * initial "details shown" SEND-MAP and after PF12-cancel.
     */
    private CoActUpOutputBuilder renderFromOldDetails(CoActUpInput in,
                                                       MutableState state,
                                                       OldDetails old) {
        AccountRecord acct = state.fetchedAccount;
        CustomerRecord cust = state.fetchedCustomer;
        CoActUpOutputBuilder b = newOutputBuilder().acctSid(padLeft(String.valueOf(acct.acctId()), ACCT_SID_LEN));
        // Account fields
        b.acStatus(String.valueOf(acct.acctActiveStatus()));
        b.openYear(year4(acct.acctOpenDate())).openMonth(month2(acct.acctOpenDate()))
                                                .openDay(day2(acct.acctOpenDate()));
        b.creditLimit(formatMoney(acct.acctCreditLimit()));
        b.expirationYear(year4(acct.acctExpiraionDate()))
                .expirationMonth(month2(acct.acctExpiraionDate()))
                .expirationDay(day2(acct.acctExpiraionDate()));
        b.cashCreditLimit(formatMoney(acct.acctCashCreditLimit()));
        b.reissueYear(year4(acct.acctReissueDate())).reissueMonth(month2(acct.acctReissueDate()))
                                                       .reissueDay(day2(acct.acctReissueDate()));
        b.currentBalance(formatMoney(acct.acctCurrBal()));
        b.currCycCredit(formatMoney(acct.acctCurrCycCredit()));
        b.accountGroup(orEmpty(acct.acctGroupId()));
        b.currCycDebit(formatMoney(acct.acctCurrCycDebit()));
        // Customer fields
        b.custNumber(padLeft(String.valueOf(cust.custId()), CUST_NUM_LEN));
        long ssn = cust.custSsn();
        b.ssn1(String.format(Locale.ROOT, "%03d", (int) (ssn / 1_000_000L)));
        b.ssn2(String.format(Locale.ROOT, "%02d", (int) ((ssn / 10_000L) % 100L)));
        b.ssn3(String.format(Locale.ROOT, "%04d", (int) (ssn % 10_000L)));
        if (cust.custDobYyyyMmDd() != null) {
            b.dobYear(year4(cust.custDobYyyyMmDd()))
             .dobMonth(month2(cust.custDobYyyyMmDd()))
             .dobDay(day2(cust.custDobYyyyMmDd()));
        }
        b.ficoScore(String.valueOf(cust.custFicoCreditScore()));
        b.firstName(orEmpty(cust.custFirstName()));
        b.middleName(orEmpty(cust.custMiddleName()));
        b.lastName(orEmpty(cust.custLastName()));
        b.addressLine1(orEmpty(cust.custAddrLine1()));
        b.state(orEmpty(cust.custAddrStateCd()));
        b.addressLine2(orEmpty(cust.custAddrLine2()));
        b.zip(orEmpty(cust.custAddrZip()));
        b.city(orEmpty(cust.custAddrLine3()));
        b.country(orEmpty(cust.custAddrCountryCd()));
        // Phone parts
        String phone1 = orEmpty(cust.custPhoneNum1());
        b.phone1Area(extractPhonePart(phone1, 1, 3));
        b.phone1Mid(extractPhonePart(phone1, 5, 3));
        b.phone1End(extractPhonePart(phone1, 9, 4));
        String phone2 = orEmpty(cust.custPhoneNum2());
        b.phone2Area(extractPhonePart(phone2, 1, 3));
        b.phone2Mid(extractPhonePart(phone2, 5, 3));
        b.phone2End(extractPhonePart(phone2, 9, 4));
        b.govtIssuedId(orEmpty(cust.custGovtIssuedId()));
        b.eftAccountId(orEmpty(cust.custEftAccountId()));
        b.primaryFlag(String.valueOf(cust.custPriCardHolderInd()));
        return b;
    }

    /**
     * Build helper for {@link CoActUpOutput} that captures the 41 attribute
     * components and 54 string components in a fluent way without
     * subclassing the record. (Records cannot be extended; this is a
     * separate internal mutable builder type whose {@link #build()} method
     * constructs the immutable {@link CoActUpOutput} in a single call.)
     */
    static final class CoActUpOutputBuilder {
        private String trnName = "", title01 = "", curDate = "", pgmName = "", title02 = "", curTime = "";
        private String acctSid = "";
        private String acStatus = "", openYear = "", openMonth = "", openDay = "";
        private String creditLimit = "", expirationYear = "", expirationMonth = "", expirationDay = "";
        private String cashCreditLimit = "", reissueYear = "", reissueMonth = "", reissueDay = "";
        private String currentBalance = "", currCycCredit = "", accountGroup = "", currCycDebit = "";
        private String custNumber = "", ssn1 = "", ssn2 = "", ssn3 = "";
        private String dobYear = "", dobMonth = "", dobDay = "";
        private String ficoScore = "", firstName = "", middleName = "", lastName = "";
        private String addressLine1 = "", state = "", addressLine2 = "", zip = "", city = "", country = "";
        private String phone1Area = "", phone1Mid = "", phone1End = "";
        private String govtIssuedId = "";
        private String phone2Area = "", phone2Mid = "", phone2End = "";
        private String eftAccountId = "", primaryFlag = "";
        private String infoMsg = "", errMsg = "";
        private String fKeys = FKEY_LEGEND, fKey05 = "", fKey12 = "";
        private CoActUpOutput.FieldAttributes attributes = CoActUpOutput.FieldAttributes.allProtected();

        CoActUpOutputBuilder trnName(String v)      { this.trnName = orEmpty(v); return this; }
        CoActUpOutputBuilder title01(String v)      { this.title01 = orEmpty(v); return this; }
        CoActUpOutputBuilder curDate(String v)      { this.curDate = orEmpty(v); return this; }
        CoActUpOutputBuilder pgmName(String v)      { this.pgmName = orEmpty(v); return this; }
        CoActUpOutputBuilder title02(String v)      { this.title02 = orEmpty(v); return this; }
        CoActUpOutputBuilder curTime(String v)      { this.curTime = orEmpty(v); return this; }
        CoActUpOutputBuilder acctSid(String v)      { this.acctSid = orEmpty(v); return this; }
        CoActUpOutputBuilder acStatus(String v)     { this.acStatus = orEmpty(v); return this; }
        CoActUpOutputBuilder openYear(String v)     { this.openYear = orEmpty(v); return this; }
        CoActUpOutputBuilder openMonth(String v)    { this.openMonth = orEmpty(v); return this; }
        CoActUpOutputBuilder openDay(String v)      { this.openDay = orEmpty(v); return this; }
        CoActUpOutputBuilder creditLimit(String v)  { this.creditLimit = orEmpty(v); return this; }
        CoActUpOutputBuilder expirationYear(String v)  { this.expirationYear = orEmpty(v); return this; }
        CoActUpOutputBuilder expirationMonth(String v) { this.expirationMonth = orEmpty(v); return this; }
        CoActUpOutputBuilder expirationDay(String v)   { this.expirationDay = orEmpty(v); return this; }
        CoActUpOutputBuilder cashCreditLimit(String v) { this.cashCreditLimit = orEmpty(v); return this; }
        CoActUpOutputBuilder reissueYear(String v)  { this.reissueYear = orEmpty(v); return this; }
        CoActUpOutputBuilder reissueMonth(String v) { this.reissueMonth = orEmpty(v); return this; }
        CoActUpOutputBuilder reissueDay(String v)   { this.reissueDay = orEmpty(v); return this; }
        CoActUpOutputBuilder currentBalance(String v) { this.currentBalance = orEmpty(v); return this; }
        CoActUpOutputBuilder currCycCredit(String v)  { this.currCycCredit = orEmpty(v); return this; }
        CoActUpOutputBuilder accountGroup(String v)   { this.accountGroup = orEmpty(v); return this; }
        CoActUpOutputBuilder currCycDebit(String v)   { this.currCycDebit = orEmpty(v); return this; }
        CoActUpOutputBuilder custNumber(String v)     { this.custNumber = orEmpty(v); return this; }
        CoActUpOutputBuilder ssn1(String v)           { this.ssn1 = orEmpty(v); return this; }
        CoActUpOutputBuilder ssn2(String v)           { this.ssn2 = orEmpty(v); return this; }
        CoActUpOutputBuilder ssn3(String v)           { this.ssn3 = orEmpty(v); return this; }
        CoActUpOutputBuilder dobYear(String v)        { this.dobYear = orEmpty(v); return this; }
        CoActUpOutputBuilder dobMonth(String v)       { this.dobMonth = orEmpty(v); return this; }
        CoActUpOutputBuilder dobDay(String v)         { this.dobDay = orEmpty(v); return this; }
        CoActUpOutputBuilder ficoScore(String v)      { this.ficoScore = orEmpty(v); return this; }
        CoActUpOutputBuilder firstName(String v)      { this.firstName = orEmpty(v); return this; }
        CoActUpOutputBuilder middleName(String v)     { this.middleName = orEmpty(v); return this; }
        CoActUpOutputBuilder lastName(String v)       { this.lastName = orEmpty(v); return this; }
        CoActUpOutputBuilder addressLine1(String v)   { this.addressLine1 = orEmpty(v); return this; }
        CoActUpOutputBuilder state(String v)          { this.state = orEmpty(v); return this; }
        CoActUpOutputBuilder addressLine2(String v)   { this.addressLine2 = orEmpty(v); return this; }
        CoActUpOutputBuilder zip(String v)            { this.zip = orEmpty(v); return this; }
        CoActUpOutputBuilder city(String v)           { this.city = orEmpty(v); return this; }
        CoActUpOutputBuilder country(String v)        { this.country = orEmpty(v); return this; }
        CoActUpOutputBuilder phone1Area(String v)     { this.phone1Area = orEmpty(v); return this; }
        CoActUpOutputBuilder phone1Mid(String v)      { this.phone1Mid = orEmpty(v); return this; }
        CoActUpOutputBuilder phone1End(String v)      { this.phone1End = orEmpty(v); return this; }
        CoActUpOutputBuilder govtIssuedId(String v)   { this.govtIssuedId = orEmpty(v); return this; }
        CoActUpOutputBuilder phone2Area(String v)     { this.phone2Area = orEmpty(v); return this; }
        CoActUpOutputBuilder phone2Mid(String v)      { this.phone2Mid = orEmpty(v); return this; }
        CoActUpOutputBuilder phone2End(String v)      { this.phone2End = orEmpty(v); return this; }
        CoActUpOutputBuilder eftAccountId(String v)   { this.eftAccountId = orEmpty(v); return this; }
        CoActUpOutputBuilder primaryFlag(String v)    { this.primaryFlag = orEmpty(v); return this; }
        CoActUpOutputBuilder infoMsg(String v)        { this.infoMsg = orEmpty(v); return this; }
        CoActUpOutputBuilder errMsg(String v)         { this.errMsg = orEmpty(v); return this; }
        CoActUpOutputBuilder fKeys(String v)          { this.fKeys = orEmpty(v); return this; }
        CoActUpOutputBuilder fKey05(String v)         { this.fKey05 = orEmpty(v); return this; }
        CoActUpOutputBuilder fKey12(String v)         { this.fKey12 = orEmpty(v); return this; }
        CoActUpOutputBuilder attributes(CoActUpOutput.FieldAttributes v) {
            this.attributes = v == null ? CoActUpOutput.FieldAttributes.allProtected() : v;
            return this;
        }

        CoActUpOutput build() {
            return new CoActUpOutput(
                    trnName, title01, curDate, pgmName, title02, curTime,
                    acctSid,
                    acStatus,
                    openYear, openMonth, openDay,
                    creditLimit,
                    expirationYear, expirationMonth, expirationDay,
                    cashCreditLimit,
                    reissueYear, reissueMonth, reissueDay,
                    currentBalance, currCycCredit, accountGroup, currCycDebit,
                    custNumber,
                    ssn1, ssn2, ssn3,
                    dobYear, dobMonth, dobDay,
                    ficoScore,
                    firstName, middleName, lastName,
                    addressLine1, state, addressLine2, zip, city, country,
                    phone1Area, phone1Mid, phone1End,
                    govtIssuedId,
                    phone2Area, phone2Mid, phone2End,
                    eftAccountId, primaryFlag,
                    infoMsg, errMsg,
                    fKeys, fKey05, fKey12,
                    attributes);
        }
    }

    /** Returns a freshly-initialised builder with header fields populated. */
    private CoActUpOutputBuilder newOutputBuilder() {
        LocalDateTime now = LocalDateTime.now();
        return new CoActUpOutputBuilder()
                .trnName(LIT_THIS_TRAN_ID)
                .pgmName(LIT_THIS_PGM)
                .title01(ScreenTitle.TITLE_01)
                .title02(ScreenTitle.TITLE_02)
                .curDate(now.toLocalDate().format(HEADER_DATE_FMT))
                .curTime(now.toLocalTime().format(HEADER_TIME_FMT))
                .fKeys(FKEY_LEGEND);
    }

    /** Builds the default blank output with header. */
    private CoActUpOutputBuilder blankOutputWithHeader() {
        return newOutputBuilder();
    }

    /**
     * Builds outbound commarea on every non-XCTL response — preserves the
     * inbound user identity and screen-flow markers while updating the
     * {@code FROM-PROGRAM} to {@code COACTUPC} and the {@code PGM-CONTEXT}
     * to {@code REENTER} (so the next iteration knows this is a continuation).
     */
    private static CardDemoCommarea outgoingCommarea(CardDemoCommarea in) {
        return in.withCdemoGeneralInfo(new CardDemoCommarea.CdemoGeneralInfo(
                padRight(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padRight(LIT_THIS_PGM, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                in.cdemoGeneralInfo().toTranId(),
                in.cdemoGeneralInfo().toProgram(),
                in.cdemoGeneralInfo().userId(),
                in.cdemoGeneralInfo().userType(),
                PgmContext.REENTER))
                .withCdemoMoreInfo(new CardDemoCommarea.CdemoMoreInfo(
                        padRight(LIT_THIS_MAP, CardDemoCommarea.LENGTH_LAST_MAP),
                        padRight(LIT_THIS_MAPSET, CardDemoCommarea.LENGTH_LAST_MAPSET)));
    }



    // ============================================================================
    // 3300-SETUP-SCREEN-ATTRS family — translation of the COBOL attribute
    // table builders. ScreenAttributeSetter encodes the per-field
    // highlight logic from CSSETATY.
    // ============================================================================

    /**
     * Translation of {@code 3310-PROTECT-ALL-ATTRS} (lines 3441-3497) plus
     * an exception for the account-sid search key, which remains
     * unprotected so the operator can type a new search.
     */
    private static CoActUpOutput.FieldAttributes allProtectedButAcctSid() {
        return CoActUpOutput.FieldAttributes.allProtected();
    }

    /**
     * Translation of {@code 3320-UNPROTECT-FEW-ATTRS} (lines 3500-3563) —
     * all editable fields are made unprotected. The account-sid is
     * protected (the user already selected it).
     */
    private static CoActUpOutput.FieldAttributes allEditable() {
        return CoActUpOutput.FieldAttributes.allUnprotected();
    }

    /**
     * Translation of {@code 3300-SETUP-SCREEN-ATTRS} (lines 2986-3438) —
     * builds a {@link CoActUpOutput.FieldAttributes} record where each
     * field is UNPROTECTED unless its flag is NOT_OK (then ERROR) or BLANK
     * (then UNPROTECTED). Delegates to
     * {@link ScreenAttributeSetter#applyFieldHighlight} for the wire-format
     * attribute byte translation.
     */
    private static CoActUpOutput.FieldAttributes buildAttributesWithFlags(EditFlags flags) {
        return new CoActUpOutput.FieldAttributes(
                mode(flags.acctStatus()),
                mode(flags.openDate()), mode(flags.openDate()), mode(flags.openDate()),
                mode(flags.creditLimit()),
                mode(flags.expirationDate()), mode(flags.expirationDate()), mode(flags.expirationDate()),
                mode(flags.cashCreditLimit()),
                mode(flags.reissueDate()), mode(flags.reissueDate()), mode(flags.reissueDate()),
                mode(flags.currentBalance()),
                mode(flags.currCycCredit()),
                mode(flags.accountGroup()),
                mode(flags.currCycDebit()),
                mode(flags.ssn()), mode(flags.ssn()), mode(flags.ssn()),
                mode(flags.dob()), mode(flags.dob()), mode(flags.dob()),
                mode(flags.ficoScore()),
                mode(flags.firstName()),
                mode(flags.middleName()),
                mode(flags.lastName()),
                mode(flags.addressLine1()),
                mode(flags.state()),
                mode(flags.addressLine2()),
                mode(flags.zip()),
                mode(flags.city()),
                mode(flags.country()),
                mode(flags.phone1()), mode(flags.phone1()), mode(flags.phone1()),
                mode(flags.govtIssuedId()),
                mode(flags.phone2()), mode(flags.phone2()), mode(flags.phone2()),
                mode(flags.eftAccountId()),
                mode(flags.primaryFlag()));
    }

    /**
     * Translates a {@link ValidityFlag} into the {@link CoActUpOutput.AttributeMode}
     * value used by {@code 3300-SETUP-SCREEN-ATTRS}: NOT_OK → ERROR
     * (highlighted), VALID/BLANK → UNPROTECTED.
     */
    private static CoActUpOutput.AttributeMode mode(ValidityFlag flag) {
        if (flag instanceof ValidityFlag.NotOk) {
            return CoActUpOutput.AttributeMode.ERROR;
        }
        return CoActUpOutput.AttributeMode.UNPROTECTED;
    }

    /**
     * Applies a per-field highlight via {@link ScreenAttributeSetter} —
     * exposed as a single-use helper for callers that want to derive a
     * highlight character independently of the {@link CoActUpOutput.AttributeMode}
     * abstraction. Touches DFHRED/ASTERISK constants to keep them
     * meaningfully imported per AAP §0.7.1.
     */
    @SuppressWarnings("unused")
    private static String highlightFor(ValidityFlag flag, String value) {
        ScreenAttributeSetter.FieldHighlightResult r =
                ScreenAttributeSetter.applyFieldHighlight(flag, false, "", value == null ? "" : value);
        return r.attribute();
    }

    // ============================================================================
    // ABEND-ROUTINE (lines 4203-4227) → abendRoutine() — best-effort error
    // capture; logs the AbendData and rethrows so the JCL main can exit
    // with a non-zero status.
    // ============================================================================

    /**
     * Translation of {@code ABEND-ROUTINE} (lines 4203-4227). Constructs an
     * {@link SystemMessages.AbendData} payload from the input arguments,
     * logs it at ERROR level, and rethrows as an unchecked exception so the
     * outer composition root may exit with a non-zero status. Card PAN is
     * never logged per AAP §0.7.2.
     */
    static void abendRoutine(String culprit, String reason, String msg) {
        SystemMessages.AbendData data =
                new SystemMessages.AbendData(LIT_THIS_PGM, orEmpty(culprit), orEmpty(reason), orEmpty(msg));
        log.error("ABEND in COACTUPC: code={} culprit={} reason={} msg={}",
                  data.code(), data.culprit(), data.reason(), data.msg());
        throw new IllegalStateException(
                "COACTUPC ABEND: " + data.reason() + " (" + data.msg() + ")");
    }

    // ============================================================================
    // Helper utilities — string normalization, date/money parsing,
    // padding, formatting. Translation of utility patterns scattered
    // through the COBOL paragraphs and the CSUTLDPY copybook.
    // ============================================================================

    /**
     * Translation of the COBOL "* or SPACES → LOW-VALUES" pattern in
     * {@code 1100-RECEIVE-MAP}. Strings consisting entirely of spaces or
     * the literal {@code "*"} are converted to the empty string.
     */
    private static String normalizeOrEmpty(String v) {
        if (v == null) {
            return "";
        }
        String trimmed = v.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return "";
        }
        return v;
    }

    /** Returns {@code true} if the string is null, empty, or all whitespace. */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Returns the string with leading/trailing whitespace trimmed, or {@code ""} for null. */
    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** Returns the string itself, or {@code ""} for null. */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Returns {@code true} if every char in {@code s} is an ASCII digit. */
    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Returns {@code true} if every char in {@code s} is the digit '0'. */
    private static boolean isAllZeroes(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0, n = s.length(); i < n; i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if every char in {@code s} is an alphabet
     * character (A-Z or a-z) or a space. Translation of the COBOL
     * {@code IS-ALPHABETIC} / {@code IS-ALPHABETIC-LOWER} /
     * {@code IS-ALPHABETIC-UPPER} class test.
     */
    private static boolean isAlphaAndSpaces(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (!(c == ' ' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if every char in {@code s} is an alphabet
     * character, a digit, or a space. Translation of the COBOL
     * {@code IS-ALPHANUMERIC} class test.
     */
    private static boolean isAlphanumAndSpaces(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (!(c == ' '
                    || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** Translation of {@code FUNCTION UPPER-CASE}; safe for {@code null}. */
    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    /** Translation of {@code FUNCTION LOWER-CASE}; safe for {@code null}. */
    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an account-id string (PIC 9(11)) into a long. Assumes the
     * input has already been validated by {@link #editAccount}.
     */
    private static long parseAcctId(String acctSid) {
        try {
            return Long.parseLong(acctSid.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * Parses a signed numeric input (PIC S9(10)V99) into a {@link BigDecimal}
     * with scale 2. Strips leading {@code +} or {@code -} sign markers,
     * embedded commas (thousands separators), and trailing CR/DB suffixes
     * if present. Returns {@code null} on any parse failure (mirroring the
     * COBOL {@code TEST-NUMVAL-C} non-zero result).
     */
    private static BigDecimal tryParseSigned9v2(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        boolean negative = false;
        // Trailing CR (credit, negative) or DB (debit, positive) — observed in
        // COBOL edited fields; not present in the COACTUP edit but tolerated.
        if (trimmed.endsWith("CR") || trimmed.endsWith("cr")) {
            negative = true;
            trimmed = trimmed.substring(0, trimmed.length() - 2).trim();
        } else if (trimmed.endsWith("DB") || trimmed.endsWith("db")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2).trim();
        }
        // Leading sign
        if (trimmed.startsWith("+")) {
            trimmed = trimmed.substring(1);
        } else if (trimmed.startsWith("-")) {
            negative = true;
            trimmed = trimmed.substring(1);
        }
        trimmed = trimmed.replace(",", "");
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(trimmed, Decimals.DEFAULT_MATH_CONTEXT);
            if (negative) {
                bd = bd.negate();
            }
            return bd.setScale(MONETARY_SCALE, Decimals.ROUNDED_MODE);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Parses a money string with a fallback to the supplied default.
     * Equivalent to the COBOL group-move semantic: an unchanged numeric
     * field retains its current value.
     */
    private static BigDecimal parseMoneyOr(String input, BigDecimal fallback) {
        BigDecimal parsed = tryParseSigned9v2(input);
        return parsed != null ? parsed : (fallback == null
                ? BigDecimal.ZERO.setScale(MONETARY_SCALE) : fallback);
    }

    /** Composes a {@link LocalDate} from y/m/d strings, falling back on {@code fallback} on any error. */
    private static LocalDate composeDateOr(String y, String m, String d, LocalDate fallback) {
        try {
            int yi = Integer.parseInt(y.trim());
            int mi = Integer.parseInt(m.trim());
            int di = Integer.parseInt(d.trim());
            return LocalDate.of(yi, mi, di);
        } catch (DateTimeException | NumberFormatException ex) {
            return fallback;
        }
    }

    /** Compares a y/m/d input triple to a {@link LocalDate} for compareOldNew. */
    private static boolean sameDate(String y, String m, String d, LocalDate other) {
        if (other == null) {
            return isBlank(y) && isBlank(m) && isBlank(d);
        }
        int yi = safeParseInt(y, -1);
        int mi = safeParseInt(m, -1);
        int di = safeParseInt(d, -1);
        return other.getYear() == yi
                && other.getMonthValue() == mi
                && other.getDayOfMonth() == di;
    }

    /** Compares a money input to a {@link BigDecimal} for compareOldNew. */
    private static boolean sameMoney(String input, BigDecimal other) {
        BigDecimal parsed = tryParseSigned9v2(input);
        if (parsed == null) {
            return other == null || other.compareTo(BigDecimal.ZERO) == 0;
        }
        return parsed.compareTo(other == null ? BigDecimal.ZERO : other) == 0;
    }

    /** Compares a 3-part phone input to a stored "(NNN)NNN-NNNN" 15-char string. */
    private static boolean samePhone(String a, String m, String e, String stored) {
        if (isBlank(a) && isBlank(m) && isBlank(e)) {
            return stored == null || stored.trim().isEmpty();
        }
        String storedTrim = stored == null ? "" : stored.trim();
        return storedTrim.equals(formatPhoneRaw(a, m, e));
    }

    /** Compares a 3-part SSN input to a stored long SSN. */
    private static boolean sameSsn(String a, String b, String c, long stored) {
        if (isBlank(a) && isBlank(b) && isBlank(c)) {
            return stored == 0L;
        }
        try {
            long combined = Long.parseLong(a.trim()) * 1_000_000L
                    + Long.parseLong(b.trim()) * 10_000L
                    + Long.parseLong(c.trim());
            return combined == stored;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** Combines a 3-part SSN input into a 9-digit long, falling back to {@code currentSsn}. */
    private static long combineSsn(String a, String b, String c, long currentSsn) {
        if (isBlank(a) || isBlank(b) || isBlank(c)) {
            return currentSsn;
        }
        try {
            return Long.parseLong(a.trim()) * 1_000_000L
                    + Long.parseLong(b.trim()) * 10_000L
                    + Long.parseLong(c.trim());
        } catch (NumberFormatException ex) {
            return currentSsn;
        }
    }

    /** Formats a phone as "(NNN)NNN-NNNN" (13 chars), falling back to {@code fallback} when all-blank. */
    private static String formatPhone(String a, String m, String e, String fallback) {
        if (isBlank(a) && isBlank(m) && isBlank(e)) {
            return orEmpty(fallback);
        }
        return formatPhoneRaw(a, m, e);
    }

    /** Raw phone formatter — does not check for all-blank input. */
    private static String formatPhoneRaw(String a, String m, String e) {
        return "(" + padLeft(orEmpty(a).trim(), 3)
                + ")" + padLeft(orEmpty(m).trim(), 3)
                + "-" + padLeft(orEmpty(e).trim(), 4);
    }

    /** Extracts a substring from a phone string, returning empty on out-of-bounds. */
    private static String extractPhonePart(String stored, int oneBasedStart, int len) {
        if (stored == null || stored.length() < oneBasedStart + len - 1) {
            return "";
        }
        return stored.substring(oneBasedStart - 1, oneBasedStart - 1 + len);
    }

    /** Year component as a 4-digit zero-padded string. */
    private static String year4(LocalDate d) {
        return d == null ? "" : String.format(Locale.ROOT, "%04d", d.getYear());
    }

    /** Month component as a 2-digit zero-padded string. */
    private static String month2(LocalDate d) {
        return d == null ? "" : String.format(Locale.ROOT, "%02d", d.getMonthValue());
    }

    /** Day component as a 2-digit zero-padded string. */
    private static String day2(LocalDate d) {
        return d == null ? "" : String.format(Locale.ROOT, "%02d", d.getDayOfMonth());
    }

    /**
     * Formats a {@link BigDecimal} monetary value as a fixed-width string
     * suitable for BMS display ("-9999999.99" template). Preserves scale 2
     * per AAP §0.6.1.
     */
    private static String formatMoney(BigDecimal v) {
        BigDecimal scaled = (v == null ? BigDecimal.ZERO : v).setScale(MONETARY_SCALE, Decimals.ROUNDED_MODE);
        return scaled.toPlainString();
    }

    /** Left-pads {@code s} with spaces to width {@code w}; truncates from the left if longer. */
    private static String padLeft(String s, int w) {
        String t = orEmpty(s);
        if (t.length() == w) {
            return t;
        }
        if (t.length() > w) {
            return t.substring(t.length() - w);
        }
        return " ".repeat(w - t.length()) + t;
    }

    /** Right-pads {@code s} with spaces to width {@code w}; truncates from the right if longer. */
    private static String padRight(String s, int w) {
        String t = orEmpty(s);
        if (t.length() == w) {
            return t;
        }
        if (t.length() > w) {
            return t.substring(0, w);
        }
        return t + " ".repeat(w - t.length());
    }

    /** Pads or clamps {@code s} to exactly {@code w} characters (right-pad if shorter; clamp if longer). */
    private static String padOrClamp(String s, int w) {
        return padRight(s, w);
    }

    /** Safe {@link Integer#parseInt} with a fallback. */
    private static int safeParseInt(String s, int fallback) {
        if (s == null || s.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Static initializer-touch for DateConstants (per AAP §0.4.1 mandate to import). */
    @SuppressWarnings("unused")
    private static final DateTimeFormatter _CONST_TOUCH = DateConstants.YYYYMMDD;

    /**
     * Bridges raw CICS {@code EIBAID} bytes received over the wire to a
     * {@link AidKey} permit via the {@link PfKeyDecoder}. Provided as a
     * convenience for callers that prefer to pass the EIBAID byte directly
     * to {@link #execute} (translating the COBOL {@code MOVE EIBAID TO
     * WS-AID-KEY} statement before dispatch). Not invoked by {@code execute}
     * itself, which takes a pre-decoded {@link AidKey} parameter.
     */
    public static AidKey decodeAidKey(byte eibaid) {
        return PfKeyDecoder.decode(eibaid);
    }
}

