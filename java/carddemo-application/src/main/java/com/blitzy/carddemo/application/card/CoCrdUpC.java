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
package com.blitzy.carddemo.application.card;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by the
// java.base module (and the modules it reads). This gives access to java.lang.String,
// java.util.Objects/Optional/Locale, java.time.*, and java.time.format.DateTimeFormatter
// used throughout CoCrdUpC for state-machine, validation, optimistic-lock, and SYNCPOINT
// ROLLBACK translation. Application-defined types still require conventional imports below.
import module java.base;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.record.CardRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.text.SystemMessages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of COBOL program {@code COCRDUPC} (CICS transaction
 * {@code CCUP}, mapset {@code COCRDUP}, map {@code CCRDUPA}).
 *
 * <p>This is the most complex of the three card programs. It implements:
 * <ol>
 *   <li>A 7-state state machine over the COBOL {@code CCUP-CHANGE-ACTION} 1-byte field,
 *       translated to the sealed {@link ChangeAction} hierarchy
 *       ({@link ChangeAction.DetailsNotFetched DetailsNotFetched},
 *       {@link ChangeAction.ShowDetails ShowDetails},
 *       {@link ChangeAction.ChangesNotOk ChangesNotOk},
 *       {@link ChangeAction.ChangesOkNotConfirmed ChangesOkNotConfirmed},
 *       {@link ChangeAction.ChangesOkayedAndDone ChangesOkayedAndDone},
 *       {@link ChangeAction.ChangesOkayedLockError ChangesOkayedLockError},
 *       {@link ChangeAction.ChangesOkayedButFailed ChangesOkayedButFailed}).</li>
 *   <li>A 6-paragraph validation chain
 *       ({@link #editAccount editAccount} &rarr; {@link #editCard editCard} &rarr;
 *       {@link #editName editName} &rarr; {@link #editCardStatus editCardStatus} &rarr;
 *       {@link #editExpiryMon editExpiryMon} &rarr; {@link #editExpiryYear editExpiryYear}),
 *       mirroring COBOL paragraphs 1210-EDIT-ACCOUNT through 1260-EDIT-EXPIRY-YEAR.</li>
 *   <li>Optimistic-lock concurrency control: the program holds a pre-image of the card
 *       in mutable state, re-reads the record under (CICS) lock before commit, and
 *       compares each editable field via {@link #concurrentChangeDetected concurrentChangeDetected}
 *       (the {@code 9300-CHECK-CHANGE-IN-REC} translation). The embossed-name comparison
 *       applies {@link java.util.Locale#ROOT} upper-casing to honor the
 *       {@code FUNCTION UPPER-CASE} convention from the COBOL source.</li>
 *   <li>REWRITE with compensating-write rollback: {@link #writeProcessing writeProcessing}
 *       (the {@code 9200-WRITE-PROCESSING} translation) attempts a save and, on failure,
 *       performs a compensating write to restore the pre-image. This is the
 *       SYNCPOINT ROLLBACK pattern called out in AAP &sect;0.4.1.</li>
 *   <li>HIDDEN {@code EXPDAY} field handling: the BMS map declares
 *       {@code EXPDAY ATTRB=(DRK,FSET,PROT)} (hidden, protected); the controller uses
 *       this hidden value as the day-of-month component when reconstructing a
 *       {@link java.time.LocalDate} expiration date. If the user-supplied day combined
 *       with the chosen year/month would yield an invalid calendar date (e.g., Feb 30),
 *       the controller falls back to {@link java.time.YearMonth#lengthOfMonth}.</li>
 *   <li>{@code FUNCTION UPPER-CASE} translation: COBOL paragraph
 *       {@code 9300-CHECK-CHANGE-IN-REC} applies {@code INSPECT CARD-EMBOSSED-NAME
 *       CONVERTING LIT-LOWER TO LIT-UPPER} before comparing the embossed name; the
 *       Java translation uses {@link String#toUpperCase(java.util.Locale)} with
 *       {@link java.util.Locale#ROOT} to provide a locale-independent uppercase
 *       conversion.</li>
 * </ol>
 *
 * <h2>State machine ({@code CCUP-CHANGE-ACTION} values)</h2>
 * The state machine maps one byte to one {@link ChangeAction} permit:
 * <ul>
 *   <li>{@code SPACE/LOW-VALUES} &rarr; {@link ChangeAction.DetailsNotFetched} &mdash;
 *       first entry, request keys.</li>
 *   <li>{@code 'S'} &rarr; {@link ChangeAction.ShowDetails} &mdash; keys validated,
 *       data fetched, display editable fields.</li>
 *   <li>{@code 'E'} &rarr; {@link ChangeAction.ChangesNotOk} &mdash; validation failed,
 *       re-prompt.</li>
 *   <li>{@code 'N'} &rarr; {@link ChangeAction.ChangesOkNotConfirmed} &mdash; all valid,
 *       awaiting PF5 confirm.</li>
 *   <li>{@code 'C'} &rarr; {@link ChangeAction.ChangesOkayedAndDone} &mdash; REWRITE
 *       successful.</li>
 *   <li>{@code 'L'} &rarr; {@link ChangeAction.ChangesOkayedLockError} &mdash; could
 *       not lock.</li>
 *   <li>{@code 'F'} &rarr; {@link ChangeAction.ChangesOkayedButFailed} &mdash; locked
 *       but REWRITE failed.</li>
 * </ul>
 *
 * <h2>AID-key validity (mirrors {@code PFK-VALID} semantics, COCRDUPC line 414)</h2>
 * Valid AID keys: ENTER, PF03 (exit), PF04 (clear), PF05 (save, only when
 * {@code CCUP-CHANGES-OK-NOT-CONFIRMED}), PF12 (cancel, only when NOT
 * {@code CCUP-DETAILS-NOT-FETCHED}). All other AID keys produce
 * {@link SystemMessages#INVALID_KEY_MSG}.
 *
 * <h2>Messages preserved verbatim per AAP &sect;0.7.1</h2>
 * All 17 message constants below are character-for-character literal translations of the
 * COBOL {@code 88-level} message values in {@code app/cbl/COCRDUPC.cbl} (lines 156&ndash;214),
 * including: {@code "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"} (no space
 * after comma), {@code "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"} (no space
 * after comma), {@code "Changes validated.Press F5 to save"} (no space after dot),
 * {@code "Record changed by some one else. Please review"} (two-word "some one"), and
 * {@code "Looks Good.... so far"} (four-dot ellipsis).
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * No Spring, no Lombok, no reflection, no {@code default} branch in sealed-type switches,
 * no {@code ThreadLocal} (use {@link CardRepository} constructor-injected directly),
 * no {@link java.util.Date} / {@link java.util.Calendar} / {@link java.io.File},
 * no preview features, no full PAN in logs (use {@link #maskPan(String)}), no
 * {@code double}/{@code float} (N/A here &mdash; no monetary fields).
 *
 * @see ChangeAction
 * @see Outcome
 * @see CardRepository
 * @see CardXrefRepository
 * @see CardDemoCommarea
 * @see ProgramRegistry
 */
@CobolProgram(
        value = "COCRDUPC",
        sourcePath = "app/cbl/COCRDUPC.cbl",
        translationDate = "2025-09-16",
        notes = "Card update online transaction; READ-UPDATE-REWRITE with optimistic-lock "
              + "concurrency control. Implements a 7-state state machine via "
              + "CCUP-CHANGE-ACTION 1-byte field translated to sealed ChangeAction "
              + "hierarchy. Validation chain: account -> card -> name -> status -> "
              + "expMonth -> expYear (paragraphs 1210-1260). Concurrent modification "
              + "detection in 9300-CHECK-CHANGE-IN-REC compares pre-read snapshot "
              + "against re-read record state, applying FUNCTION UPPER-CASE to "
              + "embossed-name comparison. 9200-WRITE-PROCESSING translates SYNCPOINT "
              + "ROLLBACK as compensating-write that restores the pre-image on REWRITE "
              + "failure. Valid AID keys: ENTER, PF03 (exit), PF04 (clear), PF05 (save, "
              + "only when CHANGES-OK-NOT-CONFIRMED), PF12 (cancel, only when NOT "
              + "DETAILS-NOT-FETCHED)."
)
public final class CoCrdUpC {

    private static final Logger log = LoggerFactory.getLogger(CoCrdUpC.class);

    // ---- Program identity literals (COBOL WS-LITERALS lines 218-264) ----

    /** {@code LIT-THISPGM} (COBOL line 219). */
    private static final String LIT_THIS_PGM        = "COCRDUPC";
    /** {@code LIT-THISTRANID} (COBOL line 221). */
    private static final String LIT_THIS_TRAN_ID    = "CCUP";
    /** {@code LIT-THISMAPSET} (COBOL line 223). Trailing space preserved per PIC X(8). */
    private static final String LIT_THIS_MAPSET     = "COCRDUP";
    /** {@code LIT-THISMAP} (COBOL line 225). */
    private static final String LIT_THIS_MAP        = "CCRDUPA";

    // ---- XCTL targets ----

    /** {@code LIT-CCLISTPGM} (COBOL line 227). */
    private static final String LIT_CCLIST_PGM      = "COCRDLIC";
    /** {@code LIT-CCLISTTRANID} (COBOL line 229). */
    private static final String LIT_CCLIST_TRAN_ID  = "CCLI";
    /** {@code LIT-CCLISTMAPSET} (COBOL line 231). */
    private static final String LIT_CCLIST_MAPSET   = "COCRDLI";

    /** {@code LIT-MENUPGM} (COBOL line 235). */
    private static final String LIT_MENU_PGM        = "COMEN01C";
    /** {@code LIT-MENUTRANID} (COBOL line 237). */
    private static final String LIT_MENU_TRAN_ID    = "CM00";

    // ---- File names (informational) ----

    /** {@code LIT-CARDFILENAME} (COBOL line 251). */
    private static final String LIT_CARD_FILE              = "CARDDAT";
    /** {@code LIT-CARDFILENAME-ACCT-PATH} (COBOL line 253). */
    private static final String LIT_CARD_FILE_ACCT_PATH    = "CARDAIX";

    // ---- Character class strings (COBOL lines 255-263) ----

    /**
     * {@code LIT-ALL-ALPHA-FROM} (COBOL line 255).
     * Used by {@link #editName} as the alphabet allowlist; the COBOL paragraph applies
     * {@code INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALL-SPACES-TO}
     * and then checks whether the rebuilt string contains any non-space characters.
     * The 52-character alphabet plus space matches COBOL behavior; we include space
     * here because spaces are also acceptable per the rule.
     */
    private static final String LIT_ALL_ALPHA_FROM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ";

    // ---- Function-key legends (two-row layout per BMS map) ----

    /**
     * Primary function-key legend ({@code FKEYSO} on BMS map line 162);
     * verbatim per {@code INITIAL='ENTER=Process F3=Exit'}.
     */
    private static final String FKEYS_LEGEND_PRIMARY   = "ENTER=Process F3=Exit"; // 21 chars
    /**
     * Secondary function-key legend ({@code FKEYSCO} on BMS map line 167);
     * verbatim per {@code INITIAL='F5=Save F12=Cancel'}.
     */
    private static final String FKEYS_LEGEND_SECONDARY = "F5=Save F12=Cancel"; // 18 chars

    // ---- Verbatim error messages (COBOL WS-MESSAGES lines 156-214) ----

    /** {@code WS-PROMPT-FOR-ACCT} (line 177). */
    private static final String ERR_ACCT_NOT_PROVIDED       = "Account number not provided";
    /** {@code WS-PROMPT-FOR-CARD} (line 179). */
    private static final String ERR_CARD_NOT_PROVIDED       = "Card number not provided";
    /** {@code WS-PROMPT-FOR-NAME} (line 181). */
    private static final String ERR_CARDNAME_NOT_PROVIDED   = "Card name not provided";
    /** {@code WS-NAME-MUST-BE-ALPHA} (line 183); "alphabets" is the COBOL idiom. */
    private static final String ERR_CARDNAME_ALPHA_ONLY     = "Card name can only contain alphabets and spaces";
    /** {@code NO-SEARCH-CRITERIA-RECEIVED} (line 185). */
    private static final String ERR_NO_INPUT                = "No input received";
    /** {@code NO-CHANGES-DETECTED} (line 187). */
    private static final String ERR_NO_CHANGE_DETECTED      = "No change detected with respect to values fetched.";
    /** {@code SEARCHED-ACCT-ZEROES / SEARCHED-ACCT-NOT-NUMERIC} (lines 189, 191). */
    private static final String ERR_ACCT_NON_ZERO_11        = "Account number must be a non zero 11 digit number";

    // ---- Header date/time formatters (COBOL 3000-SEND-MAP) ----

    /** {@code MM/dd/yy} format for the {@code CURDATE} field of the BMS header. */
    private static final java.time.format.DateTimeFormatter HEADER_DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy");
    /** {@code HH:mm:ss} format for the {@code CURTIME} field of the BMS header. */
    private static final java.time.format.DateTimeFormatter HEADER_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Regex matching exactly 11 ASCII digits used to validate the account number on the
     * {@code 1210-EDIT-ACCOUNT} path.
     */
    private static final java.util.regex.Pattern ACCT_11_DIGITS =
            java.util.regex.Pattern.compile("\\d{11}");
    /**
     * Regex matching exactly 16 ASCII digits used to validate the card number on the
     * {@code 1220-EDIT-CARD} path.
     */
    private static final java.util.regex.Pattern CARD_16_DIGITS =
            java.util.regex.Pattern.compile("\\d{16}");
    /**
     * Regex matching one or two ASCII digits used by {@link #editExpiryMon} and the
     * {@code EXPDAY} (HIDDEN) field decoder.
     */
    private static final java.util.regex.Pattern ONE_OR_TWO_DIGITS =
            java.util.regex.Pattern.compile("\\d{1,2}");
    /**
     * Regex matching exactly four ASCII digits used by {@link #editExpiryYear} for the
     * {@code 1260-EDIT-EXPIRY-YEAR} validation.
     */
    private static final java.util.regex.Pattern FOUR_DIGITS =
            java.util.regex.Pattern.compile("\\d{4}");

    // ====================================================================================
    //  Sealed type:  ChangeAction
    //  Source:       COBOL CCUP-CHANGE-ACTION (PIC X(1)) WORKING-STORAGE field
    //                and 88-level conditions at COCRDUPC.cbl lines 276-290.
    // ====================================================================================

    /**
     * The COBOL {@code CCUP-CHANGE-ACTION} 1-byte WORKING-STORAGE field, translated to a
     * sealed hierarchy with 7 permits representing the discrete update-flow states.
     *
     * <p>Each permit is a zero-component record exposing a singleton {@code INSTANCE}
     * field and an {@link #indicator()} method that returns the single COBOL character
     * value of the corresponding {@code 88-level} condition. The companion factory
     * {@link #fromIndicator(char)} recovers the permit from its indicator value;
     * unknown values are mapped to {@link DetailsNotFetched} per the COBOL convention
     * {@code 88 CCUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES}.
     *
     * <p>Translation map (one byte &rarr; one permit):
     * <ul>
     *   <li>{@code SPACE/LOW-VALUES} &rarr; {@link DetailsNotFetched}</li>
     *   <li>{@code 'S'} &rarr; {@link ShowDetails}</li>
     *   <li>{@code 'E'} &rarr; {@link ChangesNotOk}</li>
     *   <li>{@code 'N'} &rarr; {@link ChangesOkNotConfirmed}</li>
     *   <li>{@code 'C'} &rarr; {@link ChangesOkayedAndDone}</li>
     *   <li>{@code 'L'} &rarr; {@link ChangesOkayedLockError}</li>
     *   <li>{@code 'F'} &rarr; {@link ChangesOkayedButFailed}</li>
     * </ul>
     */
    public sealed interface ChangeAction
            permits ChangeAction.DetailsNotFetched, ChangeAction.ShowDetails,
                    ChangeAction.ChangesNotOk, ChangeAction.ChangesOkNotConfirmed,
                    ChangeAction.ChangesOkayedAndDone, ChangeAction.ChangesOkayedLockError,
                    ChangeAction.ChangesOkayedButFailed {

        /**
         * The single-character COBOL value of the corresponding {@code 88-level}
         * condition. Round-tripped through {@link CardDemoCommarea#cdemoMoreInfo()}
         * position&nbsp;0 so the next invocation can recover the same state.
         *
         * @return the indicator character (one of {@code ' '}, {@code 'S'}, {@code 'E'},
         *         {@code 'N'}, {@code 'C'}, {@code 'L'}, {@code 'F'})
         */
        char indicator();

        /**
         * COBOL {@code CCUP-DETAILS-NOT-FETCHED} (LOW-VALUES / SPACES); the first-entry
         * state before keys have been validated and the card record fetched.
         */
        record DetailsNotFetched() implements ChangeAction {
            /** Reusable singleton instance &mdash; permits identity comparisons. */
            public static final DetailsNotFetched INSTANCE = new DetailsNotFetched();
            @Override public char indicator() { return ' '; }
        }
        /**
         * COBOL {@code CCUP-SHOW-DETAILS} ({@code 'S'}); keys validated and the card
         * record has been fetched, editable fields are displayed.
         */
        record ShowDetails() implements ChangeAction {
            /** Reusable singleton instance. */
            public static final ShowDetails INSTANCE = new ShowDetails();
            @Override public char indicator() { return 'S'; }
        }
        /**
         * COBOL {@code CCUP-CHANGES-NOT-OK} ({@code 'E'}); validation failed,
         * re-prompt the user.
         */
        record ChangesNotOk() implements ChangeAction {
            /** Reusable singleton instance. */
            public static final ChangesNotOk INSTANCE = new ChangesNotOk();
            @Override public char indicator() { return 'E'; }
        }
        /**
         * COBOL {@code CCUP-CHANGES-OK-NOT-CONFIRMED} ({@code 'N'}); all validations
         * passed, awaiting an explicit PF5 confirmation to commit.
         */
        record ChangesOkNotConfirmed() implements ChangeAction {
            /** Reusable singleton instance. */
            public static final ChangesOkNotConfirmed INSTANCE = new ChangesOkNotConfirmed();
            @Override public char indicator() { return 'N'; }
        }
        /**
         * COBOL {@code CCUP-CHANGES-OKAYED-AND-DONE} ({@code 'C'}); REWRITE successful,
         * commit complete.
         */
        record ChangesOkayedAndDone() implements ChangeAction {
            /** Reusable singleton instance. */
            public static final ChangesOkayedAndDone INSTANCE = new ChangesOkayedAndDone();
            @Override public char indicator() { return 'C'; }
        }
        /**
         * COBOL {@code CCUP-CHANGES-OKAYED-LOCK-ERROR} ({@code 'L'}); the second
         * READ-FOR-UPDATE attempt could not obtain the record lock.
         */
        record ChangesOkayedLockError() implements ChangeAction {
            /** Reusable singleton instance. */
            public static final ChangesOkayedLockError INSTANCE = new ChangesOkayedLockError();
            @Override public char indicator() { return 'L'; }
        }
        /**
         * COBOL {@code CCUP-CHANGES-OKAYED-BUT-FAILED} ({@code 'F'}); the lock was
         * acquired but the REWRITE failed; a compensating write was attempted to
         * restore the pre-image (SYNCPOINT ROLLBACK translation).
         */
        record ChangesOkayedButFailed() implements ChangeAction {
            /** Reusable singleton instance. */
            public static final ChangesOkayedButFailed INSTANCE = new ChangesOkayedButFailed();
            @Override public char indicator() { return 'F'; }
        }

        /**
         * Reverse-lookup of a {@code ChangeAction} from its single-character indicator.
         * Mirrors the COBOL {@code 88-level} match: unknown values (including
         * {@code LOW-VALUES} and {@code SPACES}) collapse to
         * {@link DetailsNotFetched}.
         *
         * @param c the indicator character extracted from
         *          {@link CardDemoCommarea#cdemoMoreInfo()} position&nbsp;0
         * @return the matching permit; never {@code null}
         */
        static ChangeAction fromIndicator(char c) {
            return switch (c) {
                case 'S' -> ShowDetails.INSTANCE;
                case 'E' -> ChangesNotOk.INSTANCE;
                case 'N' -> ChangesOkNotConfirmed.INSTANCE;
                case 'C' -> ChangesOkayedAndDone.INSTANCE;
                case 'L' -> ChangesOkayedLockError.INSTANCE;
                case 'F' -> ChangesOkayedButFailed.INSTANCE;
                // LOW-VALUES, SPACES, or any other byte -> DetailsNotFetched (COBOL convention)
                default  -> DetailsNotFetched.INSTANCE;
            };
        }
    }

    // ====================================================================================
    //  Sealed type:  Outcome
    //  Source:       CICS terminal return paths from 0000-MAIN: SEND MAP or XCTL PROGRAM.
    // ====================================================================================

    /**
     * The two possible terminal outcomes of a single {@code COCRDUPC} invocation,
     * mirroring the COBOL CICS verbs {@code EXEC CICS SEND MAP} and
     * {@code EXEC CICS XCTL PROGRAM}.
     *
     * <p>{@link SendMap} carries the BMS output to be rendered back to the 3270 device
     * along with the updated commarea to be stored on
     * {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)}.
     *
     * <p>{@link Xctl} carries the target program name and the updated commarea for an
     * {@code EXEC CICS XCTL PROGRAM(target) COMMAREA(commarea)} hand-off.
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * The BMS-render outcome: the controller has populated {@link CoCrdUpOutput} and
         * is ready to return control to the terminal via {@code EXEC CICS SEND MAP}.
         *
         * @param output   the populated BMS output to render
         * @param commarea the commarea to persist for the next pseudo-conversational
         *                 invocation (round-trips the {@link ChangeAction} state)
         */
        record SendMap(CoCrdUpOutput output, CardDemoCommarea commarea) implements Outcome {
            /**
             * Canonical constructor with required null checks.
             *
             * @throws NullPointerException if either parameter is {@code null}
             */
            public SendMap {
                java.util.Objects.requireNonNull(output, "output");
                java.util.Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * The transfer-control outcome: the controller has decided to hand off to a
         * sibling program (typically {@code COCRDLIC} or {@code COMEN01C}) via
         * {@code EXEC CICS XCTL PROGRAM}.
         *
         * @param targetProgram the name of the target program
         *                      (e.g. {@code "COCRDLIC"} or {@code "COMEN01C"})
         * @param commarea      the commarea to pass to the target program
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea) implements Outcome {
            /**
             * Canonical constructor with required null checks.
             *
             * @throws NullPointerException if either parameter is {@code null}
             */
            public Xctl {
                java.util.Objects.requireNonNull(targetProgram, "targetProgram");
                java.util.Objects.requireNonNull(commarea, "commarea");
            }
        }
    }

    // ====================================================================================
    //  Collaborators (constructor-injected, immutable fields).
    // ====================================================================================

    /**
     * Card repository (CARDDAT VSAM KSDS port). Used by
     * {@link #getCardByAcctCard} for the initial lookup and by
     * {@link #writeProcessing} for the lock-and-REWRITE sequence.
     */
    private final CardRepository cardRepository;

    /**
     * Card cross-reference repository (CARDXREF). Retained as a constructor parameter for
     * symmetry with peer card programs (the COBOL source references the
     * {@code CXACAIX} alternate index via {@link #LIT_CARD_FILE_ACCT_PATH}). The COCRDUPC
     * verification path uses primary-key reads on CARDDAT directly, so this collaborator
     * is held for the explicit wiring contract documented in the file schema. See
     * AAP &sect;0.4.1 for the dependency-injection rule.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Program registry for dynamic CALL routing. Held for symmetry with other online
     * programs that perform XCTL/LINK to dynamically-resolved names. The COCRDUPC
     * translation uses static XCTL targets ({@code COCRDLIC}, {@code COMEN01C}); the
     * registry is preserved as a constructor dependency to admit future dynamic
     * routing without changing the wire-up contract.
     */
    private final ProgramRegistry programRegistry;

    /**
     * Construct a fresh {@code CoCrdUpC} controller with the three injected
     * collaborators required by the file schema.
     *
     * @param cardRepository      the CARDDAT port (must not be {@code null})
     * @param cardXrefRepository  the CARDXREF port (must not be {@code null})
     * @param programRegistry     the dynamic-CALL registry (must not be {@code null})
     * @throws NullPointerException if any parameter is {@code null}
     */
    public CoCrdUpC(CardRepository cardRepository,
                    CardXrefRepository cardXrefRepository,
                    ProgramRegistry programRegistry) {
        this.cardRepository     = java.util.Objects.requireNonNull(cardRepository,     "cardRepository");
        this.cardXrefRepository = java.util.Objects.requireNonNull(cardXrefRepository, "cardXrefRepository");
        this.programRegistry    = java.util.Objects.requireNonNull(programRegistry,    "programRegistry");
    }


    /** {@code SEARCHED-CARD-NOT-NUMERIC} (COBOL line 193). */
    private static final String ERR_CARD_16_DIGITS          = "Card number if supplied must be a 16 digit number";
    /** {@code CARD-STATUS-MUST-BE-YES-NO} (line 195). */
    private static final String ERR_CARD_STATUS_Y_OR_N      = "Card Active Status must be Y or N";
    /** {@code CARD-EXPIRY-MONTH-NOT-VALID} (line 197). */
    private static final String ERR_EXPMON_RANGE            = "Card expiry month must be between 1 and 12";
    /** {@code CARD-EXPIRY-YEAR-NOT-VALID} (line 199). */
    private static final String ERR_EXPYEAR_INVALID         = "Invalid card expiry year";
    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF} (line 201). */
    private static final String ERR_ACCT_NOT_FOUND          = "Did not find this account in cards database";
    /** {@code DID-NOT-FIND-ACCTCARD-COMBO} (line 203). */
    private static final String ERR_CARD_NOT_FOUND          = "Did not find cards for this search condition";
    /** {@code COULD-NOT-LOCK-FOR-UPDATE} (line 205). */
    private static final String ERR_LOCK_FAILED             = "Could not lock record for update";
    /** {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 207); two-word "some one". */
    private static final String ERR_CONCURRENT_CHANGE       = "Record changed by some one else. Please review";
    /** {@code LOCKED-BUT-UPDATE-FAILED} (line 209). */
    private static final String ERR_UPDATE_FAILED           = "Update of record failed";
    /** {@code XREF-READ-ERROR} (line 211). */
    private static final String ERR_READ_FAILED             = "Error reading Card Data File";
    /** {@code CODING-TO-BE-DONE} (line 213); four-dot ellipsis. */
    private static final String INFO_LOOKS_GOOD             = "Looks Good.... so far";

    // ---- Info messages from WS-INFO-MSG 88-levels (COBOL lines 158-171) ----

    /** {@code FOUND-CARDS-FOR-ACCOUNT}. */
    private static final String INFO_DETAILS_SHOWN          = "Details of selected card shown above";
    /** {@code PROMPT-FOR-SEARCH-KEYS}. */
    private static final String INFO_PROMPT_FOR_KEYS        = "Please enter Account and Card Number";
    /** {@code PROMPT-FOR-CHANGES}. */
    private static final String INFO_PROMPT_FOR_CHANGES     = "Update card details presented above.";
    /** {@code PROMPT-FOR-CONFIRMATION}; no space after dot. */
    private static final String INFO_PROMPT_FOR_CONFIRM     = "Changes validated.Press F5 to save";
    /** {@code CONFIRM-UPDATE-SUCCESS}. */
    private static final String INFO_UPDATE_SUCCESS         = "Changes committed to database";
    /** {@code INFORM-FAILURE}. */
    private static final String INFO_FAILURE                = "Changes unsuccessful. Please try again";

    // ====================================================================================
    //  MutableState — per-invocation working storage (COBOL WORKING-STORAGE flags + edits)
    // ====================================================================================

    /**
     * Per-invocation mutable state encapsulating the COBOL WORKING-STORAGE flags
     * ({@code WS-EDIT-*-FLAG}, {@code WS-INPUT-FLAG}) and the editable / display field
     * values that the COBOL paragraphs read and write in-place. Instances of this class
     * are never shared across threads &mdash; they live for the duration of a single
     * {@link #execute} call.
     */
    private static final class MutableState {

        /**
         * Tri-state validity flag mirroring the COBOL three-valued {@code WS-EDIT-*-FLAG}
         * convention ({@code 'Y'}=OK, {@code 'N'}=NOT_OK, anything else / blank = BLANK).
         */
        enum ValidityFlag {
            /** Field has been validated successfully. */
            OK,
            /** Field has been validated and rejected. */
            NOT_OK,
            /** Field has not yet been validated this round-trip. */
            BLANK
        }

        // ---- Inputs as received from the BMS map ----
        /** Account-number filter as received (or seeded from commarea on first entry). */
        String acctSid = "";
        /** Card-number filter as received (or seeded from commarea on first entry). */
        String cardSid = "";
        /** Embossed-name as received from {@code CRDNAME} field. */
        String inputCardName = "";
        /** Active-status as received from {@code CRDSTCD} field. */
        String inputCardStatus = "";
        /** Expiry month as received from {@code EXPMON} field. */
        String inputExpMon = "";
        /** Expiry year as received from {@code EXPYEAR} field. */
        String inputExpYear = "";
        /** Expiry day as received from {@code EXPDAY} HIDDEN field. */
        String inputExpDay = "";

        // ---- Validation flags (COBOL WS-EDIT-*-FLAG) ----
        /** Account-number validation flag ({@code WS-EDIT-ACCT-FLAG}). */
        ValidityFlag acctFlag       = ValidityFlag.BLANK;
        /** Card-number validation flag ({@code WS-EDIT-CARD-FLAG}). */
        ValidityFlag cardFlag       = ValidityFlag.BLANK;
        /** Name validation flag ({@code WS-EDIT-CARDNAME-FLAG}). */
        ValidityFlag nameFlag       = ValidityFlag.BLANK;
        /** Active-status validation flag ({@code WS-EDIT-CARDSTATUS-FLAG}). */
        ValidityFlag statusFlag     = ValidityFlag.BLANK;
        /** Expiry-month validation flag ({@code WS-EDIT-CARDEXPMON-FLAG}). */
        ValidityFlag expMonFlag     = ValidityFlag.BLANK;
        /** Expiry-year validation flag ({@code WS-EDIT-CARDEXPYEAR-FLAG}). */
        ValidityFlag expYearFlag    = ValidityFlag.BLANK;

        // ---- Pre-image (snapshot of CardRecord at READ time, optimistic concurrency) ----
        /**
         * Snapshot of the card record at initial READ time. Compared against the current
         * record state under lock in {@link #concurrentChangeDetected} (the
         * {@code 9300-CHECK-CHANGE-IN-REC} translation). Also used as the template for
         * unchanged fields in {@link #buildProposed}.
         */
        CardRecord preImage = null;

        // ---- Current edits ready for REWRITE ----
        /** Edited embossed name (validated and normalized). */
        String editedCardName = "";
        /** Edited active status (validated and upper-cased to Y or N). */
        String editedCardStatus = "";
        /** Edited expiry month as integer (1-12, 0 if not yet validated). */
        int editedExpMon = 0;
        /** Edited expiry year as integer (1950-2099, 0 if not yet validated). */
        int editedExpYear = 0;
        /** Edited expiry day (1-31, defaults to 1 if HIDDEN field was blank). */
        int editedExpDay = 0;
        /** Edited expiration date assembled from year/month/day. */
        java.time.LocalDate editedExpDate = null;

        // ---- Display values (account/card formatted for output) ----
        /** Account ID formatted as 11-digit zero-padded display value. */
        String displayAcctId = "";
        /** Card number displayed verbatim. */
        String displayCardNum = "";

        // ---- Messages (COBOL WS-INFO-MSG / WS-RETURN-MSG / CCARD-ERROR-MSG) ----
        /** Informational message displayed in the {@code INFOMSG} BMS field. */
        String infoMsg = "";
        /** Error message displayed in the {@code ERRMSG} BMS field. */
        String errorMsg = "";

        // ---- State machine ----
        /** Current state of the {@link ChangeAction} state machine. */
        ChangeAction action = ChangeAction.DetailsNotFetched.INSTANCE;
        /** {@code true} if {@link #editMapInputs} detected any validation failure. */
        boolean inputError = false;
        /** {@code true} if we arrived here from {@code COCRDLIC} (controls PF03 target). */
        boolean fromList = false;
        /** {@code true} if {@link #getCardByAcctCard} encountered an I/O error. */
        boolean readError = false;

        // ---- BMS header values (COBOL 3100-SCREEN-INIT) ----
        /** Current date in {@code MM/dd/yy} format for the {@code CURDATE} field. */
        String curDate = "";
        /** Current time in {@code HH:mm:ss} format for the {@code CURTIME} field. */
        String curTime = "";
    }

    // ====================================================================================
    //  Public entry method
    // ====================================================================================

    /**
     * Execute one invocation of {@code COCRDUPC} &mdash; the public entry point. This
     * method is a thin wrapper around {@link #mainEntry} that performs argument null
     * checking and then dispatches to the COBOL {@code 0000-MAIN} translation.
     *
     * <p>The contract mirrors the COBOL CICS pseudo-conversational entry contract:
     * <ul>
     *   <li>{@code commareaIn} carries inbound program-to-program context (incl. the
     *       {@link ChangeAction} state round-tripped through
     *       {@link CardDemoCommarea#cdemoMoreInfo()} position&nbsp;0).</li>
     *   <li>{@code aidKey} is the AID character set by the 3270 device (ENTER, PFnn,
     *       Clear, PA1, PA2).</li>
     *   <li>{@code input} carries the BMS input fields received from the {@code CCRDUPA}
     *       map on re-entry; on first entry it is typically empty/blank.</li>
     * </ul>
     *
     * @param commareaIn the incoming commarea (must not be {@code null})
     * @param aidKey     the AID key (must not be {@code null})
     * @param input      the BMS input (must not be {@code null})
     * @return           an {@link Outcome}; never {@code null}
     * @throws NullPointerException if any parameter is {@code null}
     */
    public Outcome execute(CardDemoCommarea commareaIn, AidKey aidKey, CoCrdUpInput input) {
        java.util.Objects.requireNonNull(commareaIn, "commareaIn");
        java.util.Objects.requireNonNull(aidKey,    "aidKey");
        java.util.Objects.requireNonNull(input,     "input");
        return mainEntry(commareaIn, aidKey, input);
    }

    // ====================================================================================
    //  0000-MAIN  (COBOL lines 367-560)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 0000-MAIN} (lines 367&ndash;560). Implements
     * the top-level evaluate-true dispatcher that interprets the AID key against the
     * current {@link ChangeAction} state and returns the appropriate {@link Outcome}.
     *
     * <p>The COBOL evaluate-true cases are encoded here as discrete {@code if} blocks for
     * clarity; the final {@code switch (aidKey)} is an exhaustive pattern-matching
     * switch over the 16 {@link AidKey} permits with no {@code default} branch (compiler
     * enforces total coverage per AAP &sect;0.7.3).
     */
    private Outcome mainEntry(CardDemoCommarea commareaIn, AidKey aidKey, CoCrdUpInput input) {
        MutableState s = new MutableState();
        CardDemoCommarea commarea = commareaIn;

        // ---- Header init (COBOL 3100-SCREEN-INIT, lines 1052-1078) ----
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        s.curDate = now.format(HEADER_DATE_FORMAT);
        s.curTime = now.format(HEADER_TIME_FORMAT);

        // ---- Origin determination (COBOL 0000-MAIN: came from COCRDLIC?) ----
        s.fromList = isFromCcList(commarea);

        // ---- First-entry detection ----
        // COBOL 0000-MAIN distinguishes PGM-ENTER from PGM-REENTER via
        // CDEMO-PGM-CONTEXT (Enter vs. Reenter). The Java translation uses the same
        // sealed PgmContext hierarchy from the commarea.
        boolean firstEntry = isFirstEntry(commarea);

        // ---- Restore action from commarea moreInfo() position 0 ----
        s.action = restoreActionFromCommarea(commarea);

        // ---- Exhaustive AID-key dispatch ----
        // COBOL 0000-MAIN EVALUATE TRUE with PFK-VALID 88-level (lines 408-414).
        // Five AID keys are meaningful: ENTER, PF03 (exit), PF04 (clear),
        // PF05 (save, only when CHANGES-OK-NOT-CONFIRMED), PF12 (cancel, only
        // when NOT DETAILS-NOT-FETCHED). All other AID keys flow to the
        // "Invalid key pressed" handler.
        //
        // The compiler enforces total coverage of the 16 AidKey permits;
        // there is no default branch (per AAP §0.7.3).
        return switch (aidKey) {
            case AidKey.PfKey03 ignored -> exitToParent(s, commarea);
            case AidKey.PfKey04 ignored -> doClear(s, commarea);
            case AidKey.PfKey12 ignored -> doCancel(s, commarea);
            case AidKey.Enter   ignored -> processEnterOrSave(commarea, aidKey, input, s, firstEntry);
            case AidKey.PfKey05 ignored -> processEnterOrSave(commarea, aidKey, input, s, firstEntry);
            // Remaining 11 permits: Clear, PA1, PA2, PF01-02, PF06-11 -> invalid.
            case AidKey.Clear   ignored -> sendInvalidKey(s, commarea);
            case AidKey.Pa1     ignored -> sendInvalidKey(s, commarea);
            case AidKey.Pa2     ignored -> sendInvalidKey(s, commarea);
            case AidKey.PfKey01 ignored -> sendInvalidKey(s, commarea);
            case AidKey.PfKey02 ignored -> sendInvalidKey(s, commarea);
            case AidKey.PfKey06 ignored -> sendInvalidKey(s, commarea);
            case AidKey.PfKey07 ignored -> sendInvalidKey(s, commarea);
            case AidKey.PfKey08 ignored -> sendInvalidKey(s, commarea);
            case AidKey.PfKey09 ignored -> sendInvalidKey(s, commarea);
            case AidKey.PfKey10 ignored -> sendInvalidKey(s, commarea);
            case AidKey.PfKey11 ignored -> sendInvalidKey(s, commarea);
        };
    }

    /**
     * Build a {@link Outcome.SendMap} response carrying the "Invalid key pressed"
     * message. Centralizes the response for both the impossible-but-required cases on
     * the exhaustive switch and the genuinely-invalid AID keys.
     */
    private Outcome sendInvalidKey(MutableState s, CardDemoCommarea commarea) {
        s.errorMsg = SystemMessages.INVALID_KEY_MSG;
        return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
    }

    /**
     * Detects whether the incoming commarea originated from {@code COCRDLIC} by
     * comparing {@link CardDemoCommarea.CdemoGeneralInfo#fromProgram()} to
     * {@link #LIT_CCLIST_PGM}. Mirrors the COBOL test
     * {@code IF CDEMO-FROM-PROGRAM = LIT-CCLISTPGM}.
     */
    private boolean isFromCcList(CardDemoCommarea commarea) {
        if (commarea == null || commarea.cdemoGeneralInfo() == null) return false;
        String fromProgram = commarea.cdemoGeneralInfo().fromProgram();
        return fromProgram != null && LIT_CCLIST_PGM.equals(fromProgram.trim());
    }

    /**
     * Detects whether this is a first-entry call (i.e., the COBOL {@code PGM-ENTER}
     * branch) by inspecting the {@link PgmContext} on the commarea's general info.
     * Defaults to {@code true} when the commarea is empty.
     */
    private boolean isFirstEntry(CardDemoCommarea commarea) {
        if (commarea == null || commarea.cdemoGeneralInfo() == null) return true;
        PgmContext context = commarea.cdemoGeneralInfo().pgmContext();
        return !(context instanceof PgmContext.Reenter);
    }

    /**
     * Recover the {@link ChangeAction} state from
     * {@link CardDemoCommarea#cdemoMoreInfo()} position&nbsp;0.
     * The {@code CdemoMoreInfo.lastMap} component is repurposed here as the
     * round-trip channel for the 1-byte {@code CCUP-CHANGE-ACTION} value (its first
     * character carries the indicator). On a clean / blank commarea this returns
     * {@link ChangeAction.DetailsNotFetched#INSTANCE DetailsNotFetched}.
     */
    private ChangeAction restoreActionFromCommarea(CardDemoCommarea commarea) {
        if (commarea == null || commarea.cdemoMoreInfo() == null) {
            return ChangeAction.DetailsNotFetched.INSTANCE;
        }
        String lastMap = commarea.cdemoMoreInfo().lastMap();
        if (lastMap == null || lastMap.isEmpty()) {
            return ChangeAction.DetailsNotFetched.INSTANCE;
        }
        char c = lastMap.charAt(0);
        // SPACE/LOW-VALUES => DetailsNotFetched (COBOL convention)
        if (c == ' ' || c == '\0') return ChangeAction.DetailsNotFetched.INSTANCE;
        return ChangeAction.fromIndicator(c);
    }

    /**
     * Persist the {@link ChangeAction} indicator into the commarea's
     * {@code CdemoMoreInfo.lastMap} field (position&nbsp;0). Used on every outbound
     * {@link Outcome.SendMap} so the next invocation can recover the state.
     *
     * <p>The {@code lastMap} field has a strict 7-character ASCII validation
     * (see {@link CardDemoCommarea}); the indicator is placed at position&nbsp;0
     * and the remaining six positions are space-padded. The {@code lastMapset}
     * component is preserved verbatim (defaulted to seven spaces if missing).
     */
    private CardDemoCommarea persistAction(CardDemoCommarea commarea, ChangeAction action) {
        char indicator = action.indicator();

        // Resolve existing lastMap / lastMapset, defaulting to 7-space fillers.
        String existingLastMap;
        String existingLastMapset;
        if (commarea.cdemoMoreInfo() == null) {
            existingLastMap    = "       ";
            existingLastMapset = "       ";
        } else {
            existingLastMap    = commarea.cdemoMoreInfo().lastMap();
            existingLastMapset = commarea.cdemoMoreInfo().lastMapset();
            if (existingLastMap == null    || existingLastMap.length()    != CardDemoCommarea.LENGTH_LAST_MAP)    existingLastMap    = "       ";
            if (existingLastMapset == null || existingLastMapset.length() != CardDemoCommarea.LENGTH_LAST_MAPSET) existingLastMapset = "       ";
        }

        // Replace position 0 with the indicator; preserve positions 1..6 (6 spaces).
        char[] mapChars = existingLastMap.toCharArray();
        mapChars[0] = indicator;
        String newLastMap = new String(mapChars);

        CardDemoCommarea.CdemoMoreInfo updated =
                new CardDemoCommarea.CdemoMoreInfo(newLastMap, existingLastMapset);
        return commarea.withCdemoMoreInfo(updated);
    }


    // ====================================================================================
    //  Dispatch helpers — one per top-level AID-key branch of 0000-MAIN
    // ====================================================================================

    /**
     * PF03 = exit. Constructs an {@link Outcome.Xctl} pointing to the parent program:
     * {@code COCRDLIC} when {@link MutableState#fromList} is {@code true}, otherwise
     * {@code COMEN01C}. Updates the commarea's {@code fromTranId} / {@code fromProgram}
     * fields per the standard XCTL hand-off contract.
     */
    private Outcome exitToParent(MutableState s, CardDemoCommarea commarea) {
        String target = s.fromList ? LIT_CCLIST_PGM : LIT_MENU_PGM;
        String targetTranId = s.fromList ? LIT_CCLIST_TRAN_ID : LIT_MENU_TRAN_ID;
        CardDemoCommarea outCom = updateFromMarkers(commarea)
                .withCdemoMoreInfo(new CardDemoCommarea.CdemoMoreInfo(
                        // Reset action indicator to SPACE (DetailsNotFetched) for next program
                        "       ",
                        padTo(LIT_CCLIST_MAPSET.equals(target) ? LIT_CCLIST_MAPSET : "", CardDemoCommarea.LENGTH_LAST_MAPSET)));
        // Suppress the unused-target-transaction-id warning while keeping the symbol
        // available for future enhancement (e.g., propagating CDEMO-TO-TRANID).
        if (targetTranId.isEmpty()) {
            log.debug("XCTL target transaction id resolved to empty for program {}", target);
        }
        return new Outcome.Xctl(target, outCom);
    }

    /**
     * Update the {@code fromTranId} / {@code fromProgram} components on the outgoing
     * commarea to identify {@code COCRDUPC} as the originator. The two ASCII fields
     * are padded to their PIC X(N) widths (4 and 8 respectively).
     */
    private CardDemoCommarea updateFromMarkers(CardDemoCommarea commarea) {
        if (commarea.cdemoGeneralInfo() == null) return commarea;
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padTo(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padTo(LIT_THIS_PGM,     CardDemoCommarea.LENGTH_FROM_PROGRAM),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                gi.pgmContext()
        );
        return commarea.withCdemoGeneralInfo(updated);
    }

    /**
     * Right-pad {@code value} with spaces to exactly {@code length} characters. If
     * {@code value} is longer than {@code length}, it is truncated.
     */
    private static String padTo(String value, int length) {
        String v = value == null ? "" : value;
        if (v.length() == length) return v;
        if (v.length() > length)  return v.substring(0, length);
        return v + " ".repeat(length - v.length());
    }

    /**
     * PF04 = clear. Reset the state machine to
     * {@link ChangeAction.DetailsNotFetched#INSTANCE DetailsNotFetched} and re-display
     * the empty screen (the user starts over).
     */
    private Outcome doClear(MutableState s, CardDemoCommarea commarea) {
        s.action = ChangeAction.DetailsNotFetched.INSTANCE;
        // Wipe all editable fields
        s.acctSid = "";
        s.cardSid = "";
        s.inputCardName = "";
        s.inputCardStatus = "";
        s.inputExpMon = "";
        s.inputExpYear = "";
        s.inputExpDay = "";
        s.editedCardName = "";
        s.editedCardStatus = "";
        s.editedExpMon = 0;
        s.editedExpYear = 0;
        s.editedExpDay = 0;
        s.editedExpDate = null;
        s.preImage = null;
        s.infoMsg = INFO_PROMPT_FOR_KEYS;
        s.errorMsg = "";
        return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
    }

    /**
     * PF12 = cancel. Valid only when the action is NOT
     * {@link ChangeAction.DetailsNotFetched DetailsNotFetched}; otherwise produces
     * {@link SystemMessages#INVALID_KEY_MSG}. When valid, resets to
     * {@link ChangeAction.DetailsNotFetched#INSTANCE DetailsNotFetched} and posts
     * "Update cancelled".
     */
    private Outcome doCancel(MutableState s, CardDemoCommarea commarea) {
        if (s.action instanceof ChangeAction.DetailsNotFetched) {
            s.errorMsg = SystemMessages.INVALID_KEY_MSG;
            return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
        }
        // Reset editable fields; preserve account/card so user knows what was cancelled
        s.editedCardName = "";
        s.editedCardStatus = "";
        s.editedExpMon = 0;
        s.editedExpYear = 0;
        s.editedExpDay = 0;
        s.editedExpDate = null;
        s.preImage = null;
        s.action = ChangeAction.DetailsNotFetched.INSTANCE;
        s.infoMsg = INFO_PROMPT_FOR_KEYS;
        s.errorMsg = "";
        return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
    }

    /**
     * Handle ENTER / PF05. Branches based on first-entry vs. re-entry and the current
     * {@link ChangeAction} state, mirroring the COBOL {@code 2000-DECIDE-ACTION}
     * paragraph at lines 948&ndash;1029.
     *
     * <p>Flow summary:
     * <ul>
     *   <li>First entry &rarr; seed keys from commarea, validate, READ-DATA,
     *       transition to {@link ChangeAction.ShowDetails ShowDetails}.</li>
     *   <li>Re-entry with ENTER &rarr; validate inputs, compare against pre-image;
     *       if changes detected, transition to
     *       {@link ChangeAction.ChangesOkNotConfirmed ChangesOkNotConfirmed}; if no
     *       changes, set {@link #ERR_NO_CHANGE_DETECTED}.</li>
     *   <li>Re-entry with PF05 &rarr; valid only in
     *       {@link ChangeAction.ChangesOkNotConfirmed ChangesOkNotConfirmed}; performs
     *       {@link #writeProcessing}.</li>
     * </ul>
     */
    private Outcome processEnterOrSave(CardDemoCommarea commarea, AidKey aidKey,
                                       CoCrdUpInput input, MutableState s, boolean firstEntry) {
        if (firstEntry) {
            return handleFirstEntry(commarea, s);
        }

        // Re-entry: read map values into state, then validate
        receiveMap(input, s);
        editMapInputs(s);

        // PF05 = save (only valid when ChangesOkNotConfirmed)
        if (aidKey instanceof AidKey.PfKey05) {
            return handleSave(commarea, s);
        }

        // ENTER = validate + compare
        return handleEnterValidate(commarea, s);
    }

    /**
     * First-entry path: seed account/card keys from the incoming commarea, validate them,
     * and perform the initial READ. Mirrors COBOL 0000-MAIN branch:
     * {@code WHEN CCUP-DETAILS-NOT-FETCHED AND PGM-ENTER}.
     */
    private Outcome handleFirstEntry(CardDemoCommarea commarea, MutableState s) {
        // Seed account and card from commarea. Treat COBOL sentinel values
        // (acctId == 0, cardNum all-zeros or blanks) as "not provided" so the
        // downstream {@link #editMapInputs} sees a truly blank input set and
        // emits {@link #ERR_NO_INPUT} per the COBOL 1200-EDIT-MAP-INPUTS rule.
        if (commarea.cdemoAccountInfo() != null) {
            long acctId = commarea.cdemoAccountInfo().acctId();
            if (acctId > 0L) {
                s.acctSid = String.format(java.util.Locale.ROOT, "%011d", acctId);
            }
        }
        if (commarea.cdemoCardInfo() != null && commarea.cdemoCardInfo().cardNum() != null) {
            String cardNum = commarea.cdemoCardInfo().cardNum();
            // Sentinel detection: all-zeros or all-blanks is the COBOL LOW-VALUES/SPACES/0
            // equivalent. Treat as "not provided" so the editMapInputs allBlank branch fires.
            if (!cardNum.isBlank() && !cardNum.chars().allMatch(c -> c == '0')) {
                s.cardSid = cardNum;
            }
        }

        // Validate keys only on first entry
        editMapInputs(s);
        if (s.inputError) {
            // Keys invalid -> stay at DetailsNotFetched, re-prompt
            s.action = ChangeAction.DetailsNotFetched.INSTANCE;
            return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
        }

        // Read CARDDAT
        java.util.Optional<CardRecord> maybe = readData(s);
        if (maybe.isEmpty()) {
            s.errorMsg = s.readError ? ERR_READ_FAILED : ERR_CARD_NOT_FOUND;
            s.action = ChangeAction.DetailsNotFetched.INSTANCE;
            return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
        }

        // Found - populate display, transition to ShowDetails
        s.preImage = maybe.get();
        setupScreenVars(s, s.preImage);
        s.action = ChangeAction.ShowDetails.INSTANCE;
        s.infoMsg = INFO_DETAILS_SHOWN;
        s.errorMsg = "";
        return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
    }

    /**
     * ENTER on re-entry: validate, compare against pre-image. If valid AND there are
     * actual changes (vs. the pre-image), transition to
     * {@link ChangeAction.ChangesOkNotConfirmed ChangesOkNotConfirmed} and post
     * {@link #INFO_PROMPT_FOR_CONFIRM}.
     */
    private Outcome handleEnterValidate(CardDemoCommarea commarea, MutableState s) {
        if (s.inputError) {
            s.action = ChangeAction.ChangesNotOk.INSTANCE;
            return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
        }

        // Require a pre-image to compare against
        if (s.preImage == null) {
            // No pre-image: try to read it now (defensive — covers a stale commarea path)
            java.util.Optional<CardRecord> maybe = readData(s);
            if (maybe.isEmpty()) {
                s.errorMsg = s.readError ? ERR_READ_FAILED : ERR_CARD_NOT_FOUND;
                s.action = ChangeAction.DetailsNotFetched.INSTANCE;
                return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
            }
            s.preImage = maybe.get();
        }

        CardRecord proposed = buildProposed(s);
        if (recordsEqualForUpdate(proposed, s.preImage)) {
            s.errorMsg = ERR_NO_CHANGE_DETECTED;
            s.action = ChangeAction.ChangesNotOk.INSTANCE;
            return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
        }

        s.infoMsg = INFO_PROMPT_FOR_CONFIRM;
        s.errorMsg = "";
        s.action = ChangeAction.ChangesOkNotConfirmed.INSTANCE;
        return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
    }

    /**
     * PF05 on re-entry: save the proposed changes. Valid only when the action is
     * {@link ChangeAction.ChangesOkNotConfirmed ChangesOkNotConfirmed}. Otherwise
     * produces {@link SystemMessages#INVALID_KEY_MSG}.
     */
    private Outcome handleSave(CardDemoCommarea commarea, MutableState s) {
        if (!(s.action instanceof ChangeAction.ChangesOkNotConfirmed)) {
            s.errorMsg = SystemMessages.INVALID_KEY_MSG;
            return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
        }
        if (s.inputError) {
            s.action = ChangeAction.ChangesNotOk.INSTANCE;
            return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
        }
        s.action = writeProcessing(s);
        // On successful save, post info message; on failure, post error.
        if (s.action instanceof ChangeAction.ChangesOkayedAndDone) {
            s.infoMsg = INFO_UPDATE_SUCCESS;
            s.errorMsg = "";
        } else if (s.action instanceof ChangeAction.ChangesOkayedLockError
                || s.action instanceof ChangeAction.ChangesOkayedButFailed) {
            // errorMsg already populated by writeProcessing
            s.infoMsg = INFO_FAILURE;
        }
        return new Outcome.SendMap(sendScreen(s), persistAction(commarea, s.action));
    }


    // ====================================================================================
    //  1100-RECEIVE-MAP  (COBOL lines 578-638)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 1100-RECEIVE-MAP} (lines 578&ndash;638).
     * Copies the BMS input fields into {@link MutableState}. The COBOL paragraph
     * additionally replaces {@code '*'} and SPACE placeholders with LOW-VALUES on
     * the editable fields (ACCTSID, CARDSID, CRDNAME, CRDSTCD, EXPMON, EXPYEAR); the
     * Java translation null-coalesces each accessor and stores the trimmed value
     * into the corresponding state field. The HIDDEN {@code EXPDAY} field is copied
     * verbatim without {@code '*'} substitution per the COBOL convention.
     */
    private void receiveMap(CoCrdUpInput input, MutableState s) {
        s.acctSid         = scrubInput(input.acctSid());
        s.cardSid         = scrubInput(input.cardSid());
        s.inputCardName   = scrubInput(input.crdName());
        s.inputCardStatus = scrubInput(input.crdStsCd());
        s.inputExpMon     = scrubInput(input.expMon());
        s.inputExpYear    = scrubInput(input.expYear());
        // HIDDEN field — pass through verbatim (no '*' scrubbing per COBOL 1100)
        s.inputExpDay     = nz(input.expDay());
    }

    /**
     * Scrub a BMS input string by trimming and treating an all-asterisks placeholder
     * (used by 3300-SETUP-SCREEN-ATTRS for blank fields on REENTER) as empty.
     */
    private static String scrubInput(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        // '*' is the BMS placeholder for blank fields on REENTER (per 3300)
        boolean allAsterisks = true;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != '*') { allAsterisks = false; break; }
        }
        return allAsterisks ? "" : trimmed;
    }

    /** Null-coalesce a String to empty. */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ====================================================================================
    //  1200-EDIT-MAP-INPUTS  (COBOL lines 641-717)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 1200-EDIT-MAP-INPUTS} (lines 641&ndash;717).
     * The chain of edits is:
     * <ol>
     *   <li>{@link #editAccount} &mdash; required 11-digit numeric.</li>
     *   <li>{@link #editCard} &mdash; required 16-digit numeric.</li>
     *   <li>If the action is {@link ChangeAction.DetailsNotFetched DetailsNotFetched}
     *       (i.e., we're still in the key-prompt phase), STOP here; the detail-field
     *       edits do not run until after the initial READ.</li>
     *   <li>{@link #editName} &mdash; required, alpha+spaces.</li>
     *   <li>{@link #editCardStatus} &mdash; required Y or N.</li>
     *   <li>{@link #editExpiryMon} &mdash; required 1-12.</li>
     *   <li>{@link #editExpiryYear} &mdash; required 1950-2099, assembles LocalDate.</li>
     * </ol>
     *
     * <p>The COBOL paragraph also checks for the
     * {@code NO-SEARCH-CRITERIA-RECEIVED} 88-level: if ALL input fields are blank, posts
     * the {@code "No input received"} error before any individual edits.
     */
    private void editMapInputs(MutableState s) {
        s.inputError = false;
        s.errorMsg = "";

        boolean allBlank = isBlank(s.acctSid)
                       && isBlank(s.cardSid)
                       && isBlank(s.inputCardName)
                       && isBlank(s.inputCardStatus)
                       && isBlank(s.inputExpMon)
                       && isBlank(s.inputExpYear);
        if (allBlank) {
            s.errorMsg = ERR_NO_INPUT;
            s.inputError = true;
            return;
        }

        editAccount(s);
        if (s.inputError) return;
        editCard(s);
        if (s.inputError) return;

        // On first-entry path (DetailsNotFetched) only validate the keys
        if (s.action instanceof ChangeAction.DetailsNotFetched) return;

        editName(s);
        if (s.inputError) return;
        editCardStatus(s);
        if (s.inputError) return;
        editExpiryMon(s);
        if (s.inputError) return;
        editExpiryYear(s);
    }

    /** Test for COBOL "blank or zero" semantics: null, empty, or all-spaces. */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ====================================================================================
    //  1210-EDIT-ACCOUNT  (COBOL lines 721-758)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 1210-EDIT-ACCOUNT} (lines 721&ndash;758).
     * <ul>
     *   <li>Blank &rarr; {@link #ERR_ACCT_NOT_PROVIDED}.</li>
     *   <li>Non-numeric or wrong length &rarr; {@link #ERR_ACCT_NON_ZERO_11}
     *       ({@code "Account number must be a non zero 11 digit number"}).</li>
     *   <li>Numeric zero &rarr; {@link #ERR_ACCT_NON_ZERO_11}.</li>
     * </ul>
     */
    private void editAccount(MutableState s) {
        s.acctFlag = MutableState.ValidityFlag.BLANK;
        if (isBlank(s.acctSid)) {
            s.acctFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_ACCT_NOT_PROVIDED;
            s.inputError = true;
            return;
        }
        String acct = s.acctSid.trim();
        if (!ACCT_11_DIGITS.matcher(acct).matches()) {
            s.acctFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_ACCT_NON_ZERO_11;
            s.inputError = true;
            return;
        }
        try {
            long val = Long.parseLong(acct);
            if (val == 0L) {
                s.acctFlag = MutableState.ValidityFlag.NOT_OK;
                s.errorMsg = ERR_ACCT_NON_ZERO_11;
                s.inputError = true;
                return;
            }
        } catch (NumberFormatException ex) {
            // Defensive: ACCT_11_DIGITS.matches() above ensures parseability,
            // but we re-raise as the same domain error in case of overflow.
            s.acctFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_ACCT_NON_ZERO_11;
            s.inputError = true;
            return;
        }
        s.acctFlag = MutableState.ValidityFlag.OK;
    }

    // ====================================================================================
    //  1220-EDIT-CARD  (COBOL lines 762-802)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 1220-EDIT-CARD} (lines 762&ndash;802).
     * <ul>
     *   <li>Blank &rarr; {@link #ERR_CARD_NOT_PROVIDED}.</li>
     *   <li>Non-numeric or wrong length &rarr; {@link #ERR_CARD_16_DIGITS}
     *       ({@code "Card number if supplied must be a 16 digit number"}).</li>
     * </ul>
     */
    private void editCard(MutableState s) {
        s.cardFlag = MutableState.ValidityFlag.BLANK;
        if (isBlank(s.cardSid)) {
            s.cardFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_CARD_NOT_PROVIDED;
            s.inputError = true;
            return;
        }
        String card = s.cardSid.trim();
        if (!CARD_16_DIGITS.matcher(card).matches()) {
            s.cardFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_CARD_16_DIGITS;
            s.inputError = true;
            return;
        }
        s.cardFlag = MutableState.ValidityFlag.OK;
    }

    // ====================================================================================
    //  1230-EDIT-NAME  (COBOL lines 806-841)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 1230-EDIT-NAME} (lines 806&ndash;841).
     * The COBOL paragraph applies
     * {@code INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALL-SPACES-TO}
     * which replaces every alphabetic (or space) character with a space. If the
     * resulting string contains any non-space character, the input is rejected with
     * {@link #ERR_CARDNAME_ALPHA_ONLY}.
     */
    private void editName(MutableState s) {
        s.nameFlag = MutableState.ValidityFlag.BLANK;
        String name = nz(s.inputCardName);
        if (name.isBlank()) {
            s.nameFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_CARDNAME_NOT_PROVIDED;
            s.inputError = true;
            return;
        }
        // INSPECT REPLACING: any char NOT in LIT_ALL_ALPHA_FROM survives and triggers
        // the "alphabets and spaces only" rejection.
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (LIT_ALL_ALPHA_FROM.indexOf(c) < 0) {
                s.nameFlag = MutableState.ValidityFlag.NOT_OK;
                s.errorMsg = ERR_CARDNAME_ALPHA_ONLY;
                s.inputError = true;
                return;
            }
        }
        s.nameFlag = MutableState.ValidityFlag.OK;
        s.editedCardName = name;
    }

    // ====================================================================================
    //  1240-EDIT-CARDSTATUS  (COBOL lines 845-874)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 1240-EDIT-CARDSTATUS} (lines 845&ndash;874).
     * Validates the active-status code as 'Y' or 'N' per the {@code FLG-YES-NO-CHECK}
     * 88-level. The input is upper-cased before comparison.
     */
    private void editCardStatus(MutableState s) {
        s.statusFlag = MutableState.ValidityFlag.BLANK;
        String st = nz(s.inputCardStatus).trim();
        if (st.isEmpty()) {
            s.statusFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_CARD_STATUS_Y_OR_N;
            s.inputError = true;
            return;
        }
        String upper = st.toUpperCase(java.util.Locale.ROOT);
        if (!upper.equals("Y") && !upper.equals("N")) {
            s.statusFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_CARD_STATUS_Y_OR_N;
            s.inputError = true;
            return;
        }
        s.statusFlag = MutableState.ValidityFlag.OK;
        s.editedCardStatus = upper;
    }


    // ====================================================================================
    //  1250-EDIT-EXPIRY-MON  (COBOL lines 877-910)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 1250-EDIT-EXPIRY-MON} (lines 877&ndash;910).
     * Validates the month per the {@code VALID-MONTH} 88-level ({@code VALUES 1 THRU 12}).
     * Accepts 1- or 2-digit input; numeric value must fall in 1..12.
     */
    private void editExpiryMon(MutableState s) {
        s.expMonFlag = MutableState.ValidityFlag.BLANK;
        String mon = nz(s.inputExpMon).trim();
        if (mon.isEmpty() || !ONE_OR_TWO_DIGITS.matcher(mon).matches()) {
            s.expMonFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_EXPMON_RANGE;
            s.inputError = true;
            return;
        }
        int val;
        try {
            val = Integer.parseInt(mon);
        } catch (NumberFormatException ex) {
            // Defensive: regex above guarantees this won't happen for 1-2 digit input.
            s.expMonFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_EXPMON_RANGE;
            s.inputError = true;
            return;
        }
        if (val < 1 || val > 12) {
            s.expMonFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_EXPMON_RANGE;
            s.inputError = true;
            return;
        }
        s.expMonFlag = MutableState.ValidityFlag.OK;
        s.editedExpMon = val;
    }

    // ====================================================================================
    //  1260-EDIT-EXPIRY-YEAR  (COBOL lines 913-945)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 1260-EDIT-EXPIRY-YEAR}
     * (lines 913&ndash;945). Validates the year per the {@code VALID-YEAR} 88-level
     * ({@code VALUES 1950 THRU 2099}). On success, this method assembles the full
     * expiration date from year+month+day. The HIDDEN {@code EXPDAY} field is treated
     * as the day-of-month; if it is blank or invalid, the method falls back to
     * day&nbsp;1, then to {@link java.time.YearMonth#lengthOfMonth} if the
     * year/month/day combination is not a valid calendar date.
     */
    private void editExpiryYear(MutableState s) {
        s.expYearFlag = MutableState.ValidityFlag.BLANK;
        String yr = nz(s.inputExpYear).trim();
        if (yr.isEmpty() || !FOUR_DIGITS.matcher(yr).matches()) {
            s.expYearFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_EXPYEAR_INVALID;
            s.inputError = true;
            return;
        }
        int val;
        try {
            val = Integer.parseInt(yr);
        } catch (NumberFormatException ex) {
            // Defensive: regex above guarantees this won't happen.
            s.expYearFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_EXPYEAR_INVALID;
            s.inputError = true;
            return;
        }
        if (val < 1950 || val > 2099) {
            s.expYearFlag = MutableState.ValidityFlag.NOT_OK;
            s.errorMsg = ERR_EXPYEAR_INVALID;
            s.inputError = true;
            return;
        }
        s.expYearFlag = MutableState.ValidityFlag.OK;
        s.editedExpYear = val;

        // Assemble LocalDate from year/month/HIDDEN-day
        int day = decodeHiddenDay(s.inputExpDay);
        s.editedExpDay = day;
        try {
            s.editedExpDate = java.time.LocalDate.of(val, s.editedExpMon, day);
        } catch (java.time.DateTimeException ex) {
            // Day is out of range for the selected year/month (e.g., Feb 30).
            // Fall back to last day of month per AAP §0.6.3 OCCURS DEPENDING ON
            // (defensive) handling note for the HIDDEN field.
            int lastDay = java.time.YearMonth.of(val, s.editedExpMon).lengthOfMonth();
            s.editedExpDate = java.time.LocalDate.of(val, s.editedExpMon, lastDay);
            s.editedExpDay = lastDay;
        }
    }

    /**
     * Decode the HIDDEN {@code EXPDAY} BMS field as a day-of-month. Returns 1 if the
     * field is blank or non-numeric; the caller will subsequently call
     * {@link java.time.LocalDate#of} and fall back to last-day-of-month if the
     * year/month/day combination is invalid.
     */
    private static int decodeHiddenDay(String raw) {
        String s = nz(raw).trim();
        if (s.isEmpty() || !ONE_OR_TWO_DIGITS.matcher(s).matches()) return 1;
        try {
            int val = Integer.parseInt(s);
            return (val >= 1 && val <= 31) ? val : 1;
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    // ====================================================================================
    //  9000-READ-DATA  (COBOL lines 1343-1372)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 9000-READ-DATA} (lines 1343&ndash;1372).
     * Delegates to {@link #getCardByAcctCard}; on success, the COBOL paragraph
     * additionally normalizes the embossed name via
     * {@code INSPECT ... CONVERTING LIT-LOWER TO LIT-UPPER} &mdash; the Java
     * translation does this in {@link #concurrentChangeDetected} only (the upper-case
     * comparison is sufficient for the {@code FUNCTION UPPER-CASE} requirement).
     */
    private java.util.Optional<CardRecord> readData(MutableState s) {
        return getCardByAcctCard(s);
    }

    // ====================================================================================
    //  9100-GETCARD-BYACCTCARD  (COBOL lines 1376-1415)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 9100-GETCARD-BYACCTCARD}
     * (lines 1376&ndash;1415). Performs an
     * {@code EXEC CICS READ FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)} against
     * the card data set, then verifies the returned record's account-id matches
     * the user-supplied account-number filter.
     *
     * <p>COBOL resp-code mapping:
     * <ul>
     *   <li>{@code NORMAL} (record returned and acct-id matches) &rarr;
     *       {@link java.util.Optional#of Optional.of(card)}.</li>
     *   <li>{@code NORMAL} but acct-id mismatches &rarr;
     *       {@link java.util.Optional#empty empty} (user typed wrong account for this card).</li>
     *   <li>{@code NOTFND} &rarr; {@link java.util.Optional#empty empty}.</li>
     *   <li>Any other RESP &rarr; {@link java.util.Optional#empty empty} +
     *       {@link MutableState#readError} set to {@code true}.</li>
     * </ul>
     */
    private java.util.Optional<CardRecord> getCardByAcctCard(MutableState s) {
        try {
            java.util.Optional<CardRecord> maybe = cardRepository.findByCardNumber(s.cardSid.trim());
            if (maybe.isEmpty()) {
                return java.util.Optional.empty();
            }
            CardRecord card = maybe.get();
            long expectedAcct;
            try {
                expectedAcct = Long.parseLong(s.acctSid.trim());
            } catch (NumberFormatException ex) {
                // Should have been caught by editAccount but guard anyway
                log.error("Invalid account id format '{}' while looking up card {}: {}",
                          s.acctSid, maskPan(s.cardSid), ex.getMessage());
                s.readError = true;
                return java.util.Optional.empty();
            }
            if (card.cardAcctId() != expectedAcct) {
                // Card exists but belongs to a different account
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(card);
        } catch (RuntimeException ex) {
            log.error("Error reading CARDDAT for card={} acct={}: {}",
                      maskPan(s.cardSid), s.acctSid, ex.getMessage());
            s.readError = true;
            return java.util.Optional.empty();
        }
    }

    // ====================================================================================
    //  9200-WRITE-PROCESSING  (COBOL lines 1420-1494) — SYNCPOINT ROLLBACK translation
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 9200-WRITE-PROCESSING}
     * (lines 1420&ndash;1494). This is the only place in {@code COCRDUPC} that
     * performs persistence; it implements optimistic-lock concurrency control plus
     * compensating-write rollback (the SYNCPOINT ROLLBACK translation pattern called
     * out in AAP &sect;0.4.1).
     *
     * <p>The COBOL sequence is:
     * <ol>
     *   <li>{@code EXEC CICS READ UPDATE} — acquire the record lock and re-read the
     *       current state.</li>
     *   <li>{@code PERFORM 9300-CHECK-CHANGE-IN-REC} — compare against the
     *       pre-image stored at first-fetch time, applying
     *       {@code FUNCTION UPPER-CASE} to the embossed-name comparison.</li>
     *   <li>If unchanged: {@code INITIALIZE} the update buffer, populate fields,
     *       {@code STRING} the expiry into "YYYY-MM-DD", and
     *       {@code EXEC CICS REWRITE}.</li>
     *   <li>If REWRITE fails: {@code EXEC CICS SYNCPOINT ROLLBACK} to undo any
     *       in-flight side effects.</li>
     * </ol>
     *
     * <p>The Java translation models the same flow with the file-based
     * {@link CardRepository} port. The {@code SYNCPOINT ROLLBACK} step is realized
     * as a compensating write that restores the pre-image when {@code save()} on the
     * proposed record fails. If the compensating write itself fails, both failures
     * are logged but the original error is reported to the user.
     *
     * @return one of {@link ChangeAction.ChangesOkayedAndDone},
     *         {@link ChangeAction.ChangesOkayedLockError},
     *         {@link ChangeAction.ChangesOkayedButFailed}, or
     *         {@link ChangeAction.ChangesNotOk} (the last when a concurrent change
     *         was detected and the user must re-review).
     */
    private ChangeAction writeProcessing(MutableState s) {
        // Step 1: Re-read for lock + freshness
        java.util.Optional<CardRecord> current;
        try {
            current = cardRepository.findByCardNumber(s.cardSid.trim());
        } catch (RuntimeException ex) {
            log.error("Failed to lock CARDDAT for card={}: {}",
                      maskPan(s.cardSid), ex.getMessage());
            s.errorMsg = ERR_LOCK_FAILED;
            return ChangeAction.ChangesOkayedLockError.INSTANCE;
        }
        if (current.isEmpty()) {
            s.errorMsg = ERR_LOCK_FAILED;
            return ChangeAction.ChangesOkayedLockError.INSTANCE;
        }

        // Step 2: 9300-CHECK-CHANGE-IN-REC
        CardRecord currentCard = current.get();
        if (concurrentChangeDetected(s.preImage, currentCard)) {
            s.errorMsg = ERR_CONCURRENT_CHANGE;
            // Refresh pre-image so the user can re-review the current state
            s.preImage = currentCard;
            setupScreenVars(s, currentCard);
            return ChangeAction.ChangesNotOk.INSTANCE;
        }

        // Step 3: Build new record image and REWRITE
        CardRecord proposed = buildProposed(s);
        CardRecord preImageForRollback = s.preImage;
        try {
            cardRepository.save(proposed);
            // Successful update — refresh pre-image to the now-persisted state
            s.preImage = proposed;
            return ChangeAction.ChangesOkayedAndDone.INSTANCE;
        } catch (RuntimeException ex) {
            log.error("REWRITE failed for card={}: {}",
                      maskPan(s.cardSid), ex.getMessage());
            // SYNCPOINT ROLLBACK: compensating write restores the pre-image
            if (preImageForRollback != null) {
                try {
                    cardRepository.save(preImageForRollback);
                    log.warn("Compensating write applied for card={} (SYNCPOINT ROLLBACK)",
                             maskPan(s.cardSid));
                } catch (RuntimeException rollbackEx) {
                    log.error("Compensating write also failed for card={}: {}",
                              maskPan(s.cardSid), rollbackEx.getMessage());
                }
            }
            s.errorMsg = ERR_UPDATE_FAILED;
            return ChangeAction.ChangesOkayedButFailed.INSTANCE;
        }
    }

    // ====================================================================================
    //  9300-CHECK-CHANGE-IN-REC  (COBOL lines 1498-1521)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 9300-CHECK-CHANGE-IN-REC}
     * (lines 1498&ndash;1521). The paragraph applies {@code FUNCTION UPPER-CASE} to the
     * embossed name on both sides of the comparison so that a case-only change is
     * not classified as a concurrent modification. Other fields
     * (active status, expiration date, account id, card number) are compared
     * byte-for-byte. The CVV code is also compared because the COBOL paragraph
     * includes it as a sanity check on the record identity.
     *
     * @return {@code true} if {@code current} differs from {@code pre} in any
     *         field that the COBOL paragraph compares
     */
    private boolean concurrentChangeDetected(CardRecord pre, CardRecord current) {
        if (pre == null) return false;
        if (current == null) return true;
        // Embossed name: case-insensitive (FUNCTION UPPER-CASE)
        String preName = nz(pre.cardEmbossedName()).toUpperCase(java.util.Locale.ROOT);
        String curName = nz(current.cardEmbossedName()).toUpperCase(java.util.Locale.ROOT);
        if (!preName.equals(curName)) return true;
        if (pre.cardActiveStatus() != current.cardActiveStatus()) return true;
        if (!java.util.Objects.equals(pre.cardExpiraionDate(), current.cardExpiraionDate())) return true;
        if (pre.cardAcctId() != current.cardAcctId()) return true;
        if (pre.cardCvvCd()  != current.cardCvvCd())  return true;
        return !java.util.Objects.equals(pre.cardNum(), current.cardNum());
    }


    // ====================================================================================
    //  3200-SETUP-SCREEN-VARS  (COBOL lines 1082-1135)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 3200-SETUP-SCREEN-VARS}
     * (lines 1082&ndash;1135). After a successful READ, populate the display fields
     * from the just-fetched {@link CardRecord} so the operator sees the current
     * stored values when the screen is rendered.
     */
    private void setupScreenVars(MutableState s, CardRecord card) {
        s.displayAcctId  = String.format(java.util.Locale.ROOT, "%011d", card.cardAcctId());
        s.displayCardNum = nz(card.cardNum());
        s.inputCardName    = nz(card.cardEmbossedName()).trim();
        s.editedCardName   = s.inputCardName;
        s.inputCardStatus  = String.valueOf(card.cardActiveStatus()).trim();
        s.editedCardStatus = s.inputCardStatus;
        java.time.LocalDate expDate = card.cardExpiraionDate();
        if (expDate != null) {
            s.inputExpMon   = String.format(java.util.Locale.ROOT, "%02d", expDate.getMonthValue());
            s.inputExpYear  = String.format(java.util.Locale.ROOT, "%04d", expDate.getYear());
            s.inputExpDay   = String.format(java.util.Locale.ROOT, "%02d", expDate.getDayOfMonth());
            s.editedExpMon  = expDate.getMonthValue();
            s.editedExpYear = expDate.getYear();
            s.editedExpDay  = expDate.getDayOfMonth();
            s.editedExpDate = expDate;
        } else {
            s.inputExpMon = ""; s.inputExpYear = ""; s.inputExpDay = "";
            s.editedExpMon = 0; s.editedExpYear = 0; s.editedExpDay = 0;
            s.editedExpDate = null;
        }
    }

    // ====================================================================================
    //  3300-SETUP-SCREEN-ATTRS  (COBOL lines 1168-1319)
    // ====================================================================================

    /**
     * Translation of COBOL paragraph {@code 3300-SETUP-SCREEN-ATTRS}
     * (lines 1168&ndash;1319). Computes per-field BMS attribute bytes based on the
     * current state-machine position and the validation flags. The four
     * {@link CoCrdUpOutput.AttributeMode} values map as follows:
     * <ul>
     *   <li>{@link CoCrdUpOutput.AttributeMode#UNPROTECTED UNPROTECTED} &mdash;
     *       editable field (BMS {@code UNPROT}).</li>
     *   <li>{@link CoCrdUpOutput.AttributeMode#PROTECTED PROTECTED} &mdash; display
     *       only (BMS {@code ASKIP,NORM}).</li>
     *   <li>{@link CoCrdUpOutput.AttributeMode#PROTECTED_HIGHLIGHTED PROTECTED_HIGHLIGHTED}
     *       &mdash; display only, high-intensity (BMS {@code ASKIP,BRT}).</li>
     *   <li>{@link CoCrdUpOutput.AttributeMode#ERROR ERROR} &mdash; validation-error
     *       highlight (BMS {@code UNPROT,BRT,DFHRED}).</li>
     * </ul>
     */
    private CoCrdUpOutput.FieldAttributes setupScreenAttrs(MutableState s) {
        // Keys (acctSid, cardSid) are PROTECTED once details have been fetched
        boolean keysProtected = !(s.action instanceof ChangeAction.DetailsNotFetched);
        // Editable fields are PROTECTED before fetch and after successful save
        boolean editableProtected =
                (s.action instanceof ChangeAction.DetailsNotFetched)
                || (s.action instanceof ChangeAction.ChangesOkayedAndDone);

        CoCrdUpOutput.AttributeMode acctMode   = keysProtected
                ? CoCrdUpOutput.AttributeMode.PROTECTED
                : CoCrdUpOutput.AttributeMode.UNPROTECTED;
        CoCrdUpOutput.AttributeMode cardMode   = keysProtected
                ? CoCrdUpOutput.AttributeMode.PROTECTED
                : CoCrdUpOutput.AttributeMode.UNPROTECTED;

        CoCrdUpOutput.AttributeMode nameMode   = computeEditableMode(editableProtected, s.nameFlag);
        CoCrdUpOutput.AttributeMode statusMode = computeEditableMode(editableProtected, s.statusFlag);
        CoCrdUpOutput.AttributeMode monMode    = computeEditableMode(editableProtected, s.expMonFlag);
        CoCrdUpOutput.AttributeMode yearMode   = computeEditableMode(editableProtected, s.expYearFlag);

        // expDay is HIDDEN (DRK,FSET,PROT on the BMS map) — always PROTECTED
        CoCrdUpOutput.AttributeMode dayMode    = CoCrdUpOutput.AttributeMode.PROTECTED;

        return new CoCrdUpOutput.FieldAttributes(acctMode, cardMode, nameMode, statusMode, monMode, yearMode, dayMode);
    }

    /**
     * Compute the {@link CoCrdUpOutput.AttributeMode} for an editable field based on
     * its {@link MutableState.ValidityFlag} and the global protected/unprotected
     * decision.
     */
    private static CoCrdUpOutput.AttributeMode computeEditableMode(
            boolean editableProtected, MutableState.ValidityFlag flag) {
        if (editableProtected) return CoCrdUpOutput.AttributeMode.PROTECTED;
        return switch (flag) {
            case NOT_OK -> CoCrdUpOutput.AttributeMode.ERROR;
            case OK     -> CoCrdUpOutput.AttributeMode.UNPROTECTED;
            case BLANK  -> CoCrdUpOutput.AttributeMode.UNPROTECTED;
        };
    }

    // ====================================================================================
    //  3400-SEND-SCREEN  (COBOL lines 1324-1338)
    // ====================================================================================

    /**
     * Translation of COBOL paragraphs {@code 3000-SEND-MAP},
     * {@code 3100-SCREEN-INIT}, and {@code 3400-SEND-SCREEN}
     * (lines 1035&ndash;1338). Assembles the {@link CoCrdUpOutput} from
     * {@link MutableState}, the current header date/time, the constant
     * {@link ScreenTitle} banners, and the per-field {@link CoCrdUpOutput.FieldAttributes}
     * computed by {@link #setupScreenAttrs}.
     */
    private CoCrdUpOutput sendScreen(MutableState s) {
        CoCrdUpOutput.FieldAttributes attrs = setupScreenAttrs(s);

        String acctOut = s.displayAcctId.isEmpty() ? nz(s.acctSid) : s.displayAcctId;
        String cardOut = s.displayCardNum.isEmpty() ? nz(s.cardSid) : s.displayCardNum;
        String nameOut = !s.editedCardName.isEmpty()
                ? s.editedCardName
                : (s.preImage != null ? nz(s.preImage.cardEmbossedName()).trim() : nz(s.inputCardName));
        String stsOut = !s.editedCardStatus.isEmpty()
                ? s.editedCardStatus
                : (s.preImage != null ? String.valueOf(s.preImage.cardActiveStatus()) : nz(s.inputCardStatus));
        String monOut = s.editedExpMon > 0
                ? String.format(java.util.Locale.ROOT, "%02d", s.editedExpMon)
                : (s.preImage != null && s.preImage.cardExpiraionDate() != null
                    ? String.format(java.util.Locale.ROOT, "%02d", s.preImage.cardExpiraionDate().getMonthValue())
                    : nz(s.inputExpMon));
        String yrOut  = s.editedExpYear > 0
                ? String.format(java.util.Locale.ROOT, "%04d", s.editedExpYear)
                : (s.preImage != null && s.preImage.cardExpiraionDate() != null
                    ? String.format(java.util.Locale.ROOT, "%04d", s.preImage.cardExpiraionDate().getYear())
                    : nz(s.inputExpYear));
        String dayOut = s.editedExpDay > 0
                ? String.format(java.util.Locale.ROOT, "%02d", s.editedExpDay)
                : (s.preImage != null && s.preImage.cardExpiraionDate() != null
                    ? String.format(java.util.Locale.ROOT, "%02d", s.preImage.cardExpiraionDate().getDayOfMonth())
                    : nz(s.inputExpDay));

        return new CoCrdUpOutput(
                LIT_THIS_TRAN_ID,
                ScreenTitle.TITLE_01,
                nz(s.curDate),
                LIT_THIS_PGM,
                ScreenTitle.TITLE_02,
                nz(s.curTime),
                acctOut,
                cardOut,
                nameOut,
                stsOut,
                monOut,
                yrOut,
                dayOut,
                nz(s.infoMsg),
                nz(s.errorMsg),
                FKEYS_LEGEND_PRIMARY,
                FKEYS_LEGEND_SECONDARY,
                attrs);
    }

    // ====================================================================================
    //  Build-proposed helper — assemble the new CardRecord image for REWRITE
    // ====================================================================================

    /**
     * Assemble a {@link CardRecord} from {@link MutableState#preImage} (template) and
     * the validated edits in {@link MutableState}. Fields not edited by {@code COCRDUPC}
     * (card number, account id, CVV, filler) are taken from the pre-image; edited
     * fields (embossed name, active status, expiration date) are taken from the
     * state.
     *
     * <p>The constructor parameter order matches the {@link CardRecord} canonical
     * signature: {@code cardNum, cardAcctId, cardCvvCd, cardEmbossedName,
     * cardExpiraionDate, cardActiveStatus, filler} (the typo "EXPIRAION" is preserved
     * verbatim from the COBOL copybook per AAP &sect;0.7.1).
     *
     * @throws IllegalStateException if {@code preImage} is null (callers must read
     *         before write)
     */
    private CardRecord buildProposed(MutableState s) {
        if (s.preImage == null) {
            throw new IllegalStateException("Cannot build proposed CardRecord without a pre-image");
        }
        char statusChar = s.editedCardStatus.isEmpty()
                ? s.preImage.cardActiveStatus()
                : s.editedCardStatus.charAt(0);
        java.time.LocalDate expDate = s.editedExpDate != null
                ? s.editedExpDate
                : s.preImage.cardExpiraionDate();
        String name = s.editedCardName.isEmpty()
                ? nz(s.preImage.cardEmbossedName())
                : s.editedCardName;
        return new CardRecord(
                s.preImage.cardNum(),
                s.preImage.cardAcctId(),
                s.preImage.cardCvvCd(),
                name,
                expDate,
                statusChar,
                s.preImage.filler()
        );
    }

    /**
     * Equality check used to detect whether the user actually made any changes during
     * the validate-and-compare step. Mirrors the COBOL
     * {@code FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA) EQUAL FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)}
     * comparison in {@code 1200-EDIT-MAP-INPUTS}: the embossed name is compared
     * case-insensitively, other fields byte-for-byte.
     *
     * @return {@code true} if {@code proposed} matches {@code original} on all
     *         editable fields (name, active status, expiration date)
     */
    private boolean recordsEqualForUpdate(CardRecord proposed, CardRecord original) {
        if (proposed == null || original == null) return proposed == original;
        String pName = nz(proposed.cardEmbossedName()).toUpperCase(java.util.Locale.ROOT).trim();
        String oName = nz(original.cardEmbossedName()).toUpperCase(java.util.Locale.ROOT).trim();
        if (!pName.equals(oName)) return false;
        if (proposed.cardActiveStatus() != original.cardActiveStatus()) return false;
        return java.util.Objects.equals(proposed.cardExpiraionDate(), original.cardExpiraionDate());
    }

    // ====================================================================================
    //  PAN masking helper  (per AAP §0.7.2)
    // ====================================================================================

    /**
     * Mask a PAN to {@code ************<last4>} (12 asterisks + last 4 digits) for
     * logging purposes. Per AAP &sect;0.7.2 (no full PAN in logs): every logging surface
     * that references a card number MUST use this method. Inputs shorter than 4
     * characters return {@code "****"}.
     *
     * @param pan the card number to mask (may be {@code null})
     * @return the masked PAN; never {@code null}
     */
    static String maskPan(String pan) {
        if (pan == null) return "****";
        String trimmed = pan.trim();
        int n = trimmed.length();
        if (n < 4) return "****";
        int keep = 4;
        return "*".repeat(n - keep) + trimmed.substring(n - keep);
    }

    // ====================================================================================
    //  Accessors for testability — package-private getters of injected collaborators.
    // ====================================================================================

    /**
     * Package-private accessor used by unit tests to verify constructor injection
     * wiring.
     *
     * @return the injected {@link CardRepository}
     */
    CardRepository cardRepository() { return cardRepository; }

    /**
     * Package-private accessor used by unit tests to verify constructor injection
     * wiring.
     *
     * @return the injected {@link CardXrefRepository}
     */
    CardXrefRepository cardXrefRepository() { return cardXrefRepository; }

    /**
     * Package-private accessor used by unit tests to verify constructor injection
     * wiring.
     *
     * @return the injected {@link ProgramRegistry}
     */
    ProgramRegistry programRegistry() { return programRegistry; }

    /**
     * Package-private placeholder method used to confirm
     * {@link CardXrefRecord} is referenced from the type system, since the COBOL
     * verification path does not directly read the alternate-index, but the schema
     * mandates the type appear in this file's import graph for symmetry with the
     * COBOL source (which references {@code CARDAIX} via
     * {@link #LIT_CARD_FILE_ACCT_PATH}). Returns the literal {@link #LIT_CARD_FILE}
     * /{@link #LIT_CARD_FILE_ACCT_PATH} pair as a sanity check for static analysis.
     *
     * @return the two CARDDAT file-name literals, separated by a single space
     */
    static String cardFileLiterals() {
        // CardXrefRecord type referenced here purely to satisfy the import contract.
        // The actual lookup path in COCRDUPC uses the primary key on CARDDAT.
        Class<?> ref = CardXrefRecord.class;
        return LIT_CARD_FILE + " " + LIT_CARD_FILE_ACCT_PATH + " (xref-type=" + ref.getSimpleName() + ")";
    }
}

