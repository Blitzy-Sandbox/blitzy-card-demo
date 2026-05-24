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

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.record.CardRecord;
import com.blitzy.carddemo.domain.commarea.PgmContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Java translation of COBOL program {@code COCRDUPC}
 * ({@code app/cbl/COCRDUPC.cbl}, CICS transaction {@code CCUP}).
 *
 * <p><b>Purpose:</b> Update an existing credit card record. The program is
 * driven by a state machine on a single character state field
 * ({@code CCUP-CHANGE-ACTION}) appended to the CardDemo commarea. The user
 * fetches a card by account+card, edits embossed name / card-active status /
 * expiration month / expiration year, reviews changes (state
 * {@code 'N'} = changes ok, not confirmed), then presses PF5 to commit; PF12
 * cancels and re-displays the original values; PF3 exits to the previous
 * program with a SYNCPOINT.
 *
 * <h2>State machine ({@code CCUP-CHANGE-ACTION} values)</h2>
 * <ul>
 *   <li>{@link UpdateState#DETAILS_NOT_FETCHED} (LOW-VALUES / SPACES) -- no
 *       card fetched yet; show prompt screen.</li>
 *   <li>{@link UpdateState#SHOW_DETAILS} ('S') -- card details displayed
 *       for first-time review.</li>
 *   <li>{@link UpdateState#CHANGES_NOT_OK} ('E') -- user submitted edits
 *       but validation failed.</li>
 *   <li>{@link UpdateState#CHANGES_OK_NOT_CONFIRMED} ('N') -- edits valid;
 *       prompt user to press PF5 to commit.</li>
 *   <li>{@link UpdateState#CHANGES_OKAYED_AND_DONE} ('C') -- commit
 *       successful; reset to fresh search state.</li>
 *   <li>{@link UpdateState#CHANGES_OKAYED_LOCK_ERROR} ('L') -- could not
 *       lock record for update.</li>
 *   <li>{@link UpdateState#CHANGES_OKAYED_BUT_FAILED} ('F') -- locked but
 *       update failed.</li>
 * </ul>
 *
 * <h2>Optimistic concurrency (9200-WRITE-PROCESSING + 9300-CHECK-CHANGE-IN-REC)</h2>
 * <p>Before committing, the program re-reads the card record under (CICS)
 * lock, compares each editable field against the {@code CCUP-OLD-*}
 * snapshot stored in commarea, and aborts with
 * {@link UpdateState#CHANGES_FAILED CHANGES_FAILED} if any has changed
 * since the user first viewed the record. The Java translation preserves
 * this semantic: {@code CardRepository.findByCardNumber(...)} re-fetches
 * the record, the application compares each editable field, then
 * {@code CardRepository.save(...)} performs the REWRITE.
 *
 * <h2>Messages preserved verbatim per AAP &sect;0.7.1</h2>
 * <ul>
 *   <li>{@code 'Details of selected card shown above'} (FOUND-CARDS-FOR-ACCOUNT)</li>
 *   <li>{@code 'Please enter Account and Card Number'} (PROMPT-FOR-SEARCH-KEYS)</li>
 *   <li>{@code 'Update card details presented above.'} (PROMPT-FOR-CHANGES)</li>
 *   <li>{@code 'Changes validated.Press F5 to save'} -- <b>no space</b> after the dot (PROMPT-FOR-CONFIRMATION)</li>
 *   <li>{@code 'Changes committed to database'} (CONFIRM-UPDATE-SUCCESS)</li>
 *   <li>{@code 'Changes unsuccessful. Please try again'} (INFORM-FAILURE)</li>
 *   <li>{@code 'PF03 pressed.Exiting              '} -- mixed case, 14 trailing spaces (same as COCRDSLC)</li>
 *   <li>{@code 'Account number not provided'}</li>
 *   <li>{@code 'Card number not provided'}</li>
 *   <li>{@code 'Card name not provided'}</li>
 *   <li>{@code 'Card name can only contain alphabets and spaces'} ('alphabets' COBOL idiom)</li>
 *   <li>{@code 'No input received'}</li>
 *   <li>{@code 'No change detected with respect to values fetched.'}</li>
 *   <li>{@code 'Account number must be a non zero 11 digit number'} -- preserved <b>but</b> the actual COBOL surface uses 'ACCOUNT FILTER,IF SUPPLIED...'</li>
 *   <li>{@code 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'} -- comma-no-space, same as COCRDLIC / COCRDSLC</li>
 *   <li>{@code 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'} -- comma-no-space, same as COCRDLIC / COCRDSLC</li>
 *   <li>{@code 'Card Active Status must be Y or N'}</li>
 *   <li>{@code 'Card expiry month must be between 1 and 12'}</li>
 *   <li>{@code 'Invalid card expiry year'}</li>
 *   <li>{@code 'Did not find this account in cards database'}</li>
 *   <li>{@code 'Did not find cards for this search condition'}</li>
 *   <li>{@code 'Could not lock record for update'}</li>
 *   <li>{@code 'Record changed by some one else. Please review'} -- two-word 'some one'</li>
 *   <li>{@code 'Update of record failed'}</li>
 *   <li>{@code 'Error reading Card Data File'}</li>
 *   <li>{@code 'Looks Good.... so far'} -- four dots, then space, then 'so far' (CODING-TO-BE-DONE; placeholder text retained for parity)</li>
 * </ul>
 *
 * <h2>AID key validity (mirrors PFK-VALID semantics)</h2>
 * <p>ENTER, PF03, PF05 (only when {@code CCUP-CHANGES-OK-NOT-CONFIRMED}),
 * PF12 (only when NOT {@code CCUP-DETAILS-NOT-FETCHED}). All other AID keys
 * are remapped to ENTER (per COBOL {@code IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE}).
 */
@CobolProgram(
        value = "COCRDUPC",
        sourcePath = "app/cbl/COCRDUPC.cbl",
        notes = "Card update online with state machine, optimistic concurrency, " +
                "and 6 EDIT paragraphs. State persisted via UpdateState parameter " +
                "modeling CCUP-CHANGE-ACTION. CICS READ-UPDATE/REWRITE translated " +
                "to findByCardNumber() + diff against CCUP-OLD-* snapshot + save(). " +
                "Verbatim messages including the no-space 'Changes validated.Press F5 " +
                "to save' (PROMPT-FOR-CONFIRMATION), the mixed-case 14-trailing-spaces " +
                "'PF03 pressed.Exiting              ' (same as COCRDSLC), the two-word " +
                "'some one' in 'Record changed by some one else. Please review', and " +
                "the four-dot 'Looks Good.... so far' (CODING-TO-BE-DONE). The " +
                "comma-no-space 'ACCOUNT FILTER,IF SUPPLIED ...' and 'CARD ID FILTER,IF " +
                "SUPPLIED ...' are shared verbatim with COCRDLIC / COCRDSLC. The " +
                "embossed-name field is normalised to upper-case (INSPECT ... " +
                "CONVERTING LIT-LOWER TO LIT-UPPER) before comparison."
)
public final class CoCrdUpC {

    private static final String PROGRAM_ID = "COCRDUPC";
    private static final String TRANSACTION_ID = "CCUP";

    private static final String MAPSET_CCLIST = "COCRDLI"; // LIT-CCLISTMAPSET

    // ============================================================================
    // Verbatim message constants (per AAP §0.7.1) — preserve exact text including
    // commas-without-spaces, missing-space-after-dot, four-dot ellipsis, two-word
    // 'some one', and trailing-space padding.
    // ============================================================================

    /** PROMPT-FOR-SEARCH-KEYS — initial prompt. */
    private static final String MSG_PROMPT_FOR_SEARCH = "Please enter Account and Card Number";

    /** FOUND-CARDS-FOR-ACCOUNT. */
    private static final String MSG_DETAILS_SHOWN = "Details of selected card shown above";

    /** PROMPT-FOR-CHANGES. */
    private static final String MSG_PROMPT_FOR_CHANGES = "Update card details presented above.";

    /**
     * PROMPT-FOR-CONFIRMATION — verbatim, <b>no space</b> after the dot
     * before 'Press', per AAP §0.7.1.
     */
    private static final String MSG_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** CONFIRM-UPDATE-SUCCESS. */
    private static final String MSG_UPDATE_SUCCESS = "Changes committed to database";

    /** INFORM-FAILURE. */
    private static final String MSG_UPDATE_FAILURE = "Changes unsuccessful. Please try again";

    /**
     * WS-EXIT-MESSAGE — verbatim, mixed-case, <b>14 trailing spaces</b>, no
     * space after the dot before 'Exiting'. Same as COCRDSLC.
     */
    private static final String MSG_PF03_EXIT = "PF03 pressed.Exiting              ";

    /** WS-PROMPT-FOR-ACCT. */
    private static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

    /** WS-PROMPT-FOR-CARD. */
    private static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

    /** WS-PROMPT-FOR-NAME. */
    private static final String MSG_PROMPT_FOR_NAME = "Card name not provided";

    /**
     * WS-NAME-MUST-BE-ALPHA — verbatim, 'alphabets' (COBOL idiom for
     * 'letters').
     */
    private static final String MSG_NAME_MUST_BE_ALPHA = "Card name can only contain alphabets and spaces";

    /** NO-SEARCH-CRITERIA-RECEIVED. */
    private static final String MSG_NO_INPUT = "No input received";

    /** NO-CHANGES-DETECTED. */
    private static final String MSG_NO_CHANGES = "No change detected with respect to values fetched.";

    /** SEARCHED-ACCT-ZEROES / SEARCHED-ACCT-NOT-NUMERIC. */
    private static final String MSG_ACCT_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** SEARCHED-CARD-NOT-NUMERIC. */
    private static final String MSG_CARD_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** CARD-STATUS-MUST-BE-YES-NO. */
    private static final String MSG_CARD_STATUS_MUST_BE_YN = "Card Active Status must be Y or N";

    /** CARD-EXPIRY-MONTH-NOT-VALID. */
    private static final String MSG_CARD_EXPIRY_MONTH = "Card expiry month must be between 1 and 12";

    /** CARD-EXPIRY-YEAR-NOT-VALID. */
    private static final String MSG_CARD_EXPIRY_YEAR = "Invalid card expiry year";

    /** DID-NOT-FIND-ACCT-IN-CARDXREF. */
    private static final String MSG_NOT_FOUND_ACCT = "Did not find this account in cards database";

    /** DID-NOT-FIND-ACCTCARD-COMBO. */
    private static final String MSG_NOT_FOUND_COMBO = "Did not find cards for this search condition";

    /** COULD-NOT-LOCK-FOR-UPDATE. */
    private static final String MSG_LOCK_ERROR = "Could not lock record for update";

    /**
     * DATA-WAS-CHANGED-BEFORE-UPDATE — verbatim, two-word 'some one' (COBOL
     * spelling).
     */
    private static final String MSG_DATA_CHANGED = "Record changed by some one else. Please review";

    /** LOCKED-BUT-UPDATE-FAILED. */
    private static final String MSG_UPDATE_FAILED_LATE = "Update of record failed";

    /** XREF-READ-ERROR. */
    private static final String MSG_READ_ERROR = "Error reading Card Data File";

    /** Invalid key message (mirrors INVALID-AID-KEY default). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    // Calendar/PIC limits per CARD-MONTH-CHECK / CARD-YEAR-CHECK
    private static final int MIN_VALID_MONTH = 1;
    private static final int MAX_VALID_MONTH = 12;
    private static final int MIN_VALID_YEAR = 1950;
    private static final int MAX_VALID_YEAR = 2099;

    // PIC widths from app/bms/COCRDUP.bms.
    private static final int ACCT_SID_LEN = 11;
    private static final int CARD_SID_LEN = 16;
    private static final int CRD_NAME_LEN = 50;
    private static final int CRD_STS_LEN = 1;
    private static final int EXP_MON_LEN = 2;
    private static final int EXP_YEAR_LEN = 4;
    private static final int EXP_DAY_LEN = 2;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Dependencies (constructor-injected).
    private final CardRepository cards;
    private final ProgramRegistry programRegistry;

    public CoCrdUpC(CardRepository cards, ProgramRegistry programRegistry) {
        this.cards = cards;
        this.programRegistry = programRegistry;
    }

    // ============================================================================
    // UpdateState — state-machine record replacing CCUP-CHANGE-ACTION
    // + CCUP-OLD-DETAILS appended to WS-THIS-PROGCOMMAREA.
    // ============================================================================

    /**
     * State machine modelling {@code CCUP-CHANGE-ACTION} from the COBOL
     * source. The state machine is a closed taxonomy of 7 values matching
     * the single-character values in the COBOL source (LOW-VALUES, 'S',
     * 'E', 'N', 'C', 'L', 'F'); using an enum gives compile-time
     * exhaustiveness guarantees per AAP &sect;0.7.3.
     */
    public enum UpdateState {
        /** {@code CCUP-DETAILS-NOT-FETCHED} (LOW-VALUES / SPACES). */
        DETAILS_NOT_FETCHED,
        /** {@code CCUP-SHOW-DETAILS} ('S'). */
        SHOW_DETAILS,
        /** {@code CCUP-CHANGES-NOT-OK} ('E'). */
        CHANGES_NOT_OK,
        /** {@code CCUP-CHANGES-OK-NOT-CONFIRMED} ('N'). */
        CHANGES_OK_NOT_CONFIRMED,
        /** {@code CCUP-CHANGES-OKAYED-AND-DONE} ('C'). */
        CHANGES_OKAYED_AND_DONE,
        /** {@code CCUP-CHANGES-OKAYED-LOCK-ERROR} ('L'). */
        CHANGES_OKAYED_LOCK_ERROR,
        /** {@code CCUP-CHANGES-OKAYED-BUT-FAILED} ('F'). */
        CHANGES_OKAYED_BUT_FAILED
    }

    /**
     * Snapshot of the {@code CCUP-OLD-*} fields persisted across screens
     * for optimistic-concurrency comparison and for the cancel (PF12)
     * path. The COBOL program appends this to the commarea as part of
     * {@code WS-THIS-PROGCOMMAREA}; the Java translation surfaces it as
     * an explicit parameter rather than expanding the commarea schema.
     *
     * @param state    the current state-machine value
     * @param acctId   the account id last fetched (CCUP-OLD-ACCTID)
     * @param cardNum  the card number last fetched (CCUP-OLD-CARDID)
     * @param cvvCd    the CVV last fetched (CCUP-OLD-CVV-CD)
     * @param crdName  the embossed name last fetched (CCUP-OLD-CRDNAME)
     * @param crdSts   the active status last fetched (CCUP-OLD-CRDSTCD)
     * @param expYear  the expiry year last fetched (CCUP-OLD-EXPYEAR)
     * @param expMon   the expiry month last fetched (CCUP-OLD-EXPMON)
     * @param expDay   the expiry day last fetched (CCUP-OLD-EXPDAY)
     */
    public record UpdateContext(UpdateState state,
                                String acctId,
                                String cardNum,
                                String cvvCd,
                                String crdName,
                                String crdSts,
                                String expYear,
                                String expMon,
                                String expDay) {

        public UpdateContext {
            state    = state == null ? UpdateState.DETAILS_NOT_FETCHED : state;
            acctId   = acctId == null ? "" : acctId;
            cardNum  = cardNum == null ? "" : cardNum;
            cvvCd    = cvvCd == null ? "" : cvvCd;
            crdName  = crdName == null ? "" : crdName;
            crdSts   = crdSts == null ? "" : crdSts;
            expYear  = expYear == null ? "" : expYear;
            expMon   = expMon == null ? "" : expMon;
            expDay   = expDay == null ? "" : expDay;
        }

        public static UpdateContext initial() {
            return new UpdateContext(UpdateState.DETAILS_NOT_FETCHED, "", "", "", "", "", "", "", "");
        }

        /** Returns a copy with the supplied state substituted. */
        public UpdateContext withState(UpdateState newState) {
            return new UpdateContext(newState, acctId, cardNum, cvvCd, crdName, crdSts, expYear, expMon, expDay);
        }
    }

    // ============================================================================
    // Result wrapper — SEND-MAP or XCTL or RETURN.
    // ============================================================================

    /**
     * Result of one transaction iteration. Either a SEND-MAP (output
     * record + commarea + update context) or an XCTL (program id +
     * commarea, the update context is reset / discarded by the caller).
     */
    public record Result(CoCrdUpOutput output,
                         CardDemoCommarea commarea,
                         UpdateContext context,
                         String xctlTo) {

        public static Result sendMap(CoCrdUpOutput output, CardDemoCommarea commarea, UpdateContext context) {
            return new Result(output, commarea, context, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, null, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl()    { return xctlTo != null; }
    }

    // ============================================================================
    // Entry points
    // ============================================================================

    /** Convenience overload starting from initial state. */
    public Result run(CoCrdUpInput input, CardDemoCommarea commarea) {
        return run(input, commarea, UpdateContext.initial());
    }

    /**
     * Entry point. Mirrors COBOL {@code 0000-MAIN} including the AID-key
     * validity check, the early PF3 / exit path, the from-COCRDLIC
     * shortcut, the fresh-entry prompt path, and the post-commit reset
     * path.
     */
    public Result run(CoCrdUpInput input, CardDemoCommarea commarea, UpdateContext context) {
        // Treat null commarea as unauthenticated — XCTL to sign-on.
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        if (context == null) {
            context = UpdateContext.initial();
        }

        boolean isReenter = commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter;

        // ----------------------------------------------------------------------
        // AID key validity check (per IF CCARD-AID-ENTER OR CCARD-AID-PFK03 OR
        // (CCARD-AID-PFK05 AND CCUP-CHANGES-OK-NOT-CONFIRMED) OR
        // (CCARD-AID-PFK12 AND NOT CCUP-DETAILS-NOT-FETCHED)). Others remap to
        // ENTER.
        // ----------------------------------------------------------------------
        CoCrdUpInput.AidKey rawAid = (input != null && input.aidKey() != null)
                ? input.aidKey() : CoCrdUpInput.AidKey.ENTER;
        CoCrdUpInput.AidKey aid = normalizeAidKey(rawAid, context);

        // ----------------------------------------------------------------------
        // EVALUATE TRUE in 0000-MAIN
        // ----------------------------------------------------------------------

        // Branch 1: PF03 / (CHANGES-OKAYED-AND-DONE + last-mapset=CCLI) /
        //           (CHANGES-FAILED + last-mapset=CCLI) → XCTL to from-program or COMEN01C.
        if (aid == CoCrdUpInput.AidKey.PF03_BACK
                || (context.state() == UpdateState.CHANGES_OKAYED_AND_DONE && isFromCcList(commarea))
                || (isChangesFailed(context) && isFromCcList(commarea))) {
            return doExit(commarea);
        }

        // Branch 2: ENTER from COCRDLIC (or PF12 from COCRDLIC) — fetch the
        //           preselected acct+card and show details.
        if ((!isReenter && isFromCcList(commarea))
                || (aid == CoCrdUpInput.AidKey.PF12_CANCEL && isFromCcList(commarea))) {
            String acctSid = preselectedAcctIdString(commarea);
            String cardSid = preselectedCardNumString(commarea);
            return fetchAndShow(acctSid, cardSid, commarea, context);
        }

        // Branch 3: Fresh entry — DETAILS-NOT-FETCHED + CDEMO-PGM-ENTER, OR
        //           coming from COMEN01C and NOT reenter — initialise and
        //           prompt.
        if ((context.state() == UpdateState.DETAILS_NOT_FETCHED && !isReenter)
                || (isFromMainMenu(commarea) && !isReenter)) {
            UpdateContext reset = UpdateContext.initial();
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            return Result.sendMap(buildPromptScreen(reentered), reentered, reset);
        }

        // Branch 4: Post-commit / failed — reset to a fresh prompt.
        if (context.state() == UpdateState.CHANGES_OKAYED_AND_DONE
                || isChangesFailed(context)) {
            UpdateContext reset = UpdateContext.initial();
            CardDemoCommarea reentered = withPgmContext(
                    clearAcctAndCardOnFresh(commarea, context), PgmContext.REENTER);
            return Result.sendMap(buildPromptScreen(reentered), reentered, reset);
        }

        // Branch 5: OTHER — receive map, edit, decide action, send map.
        CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
        return processIteration(coalesceInput(input), reentered, context, aid);
    }

    /**
     * Normalises an AID key per the COBOL {@code IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE}
     * rule, gated by the current state for PF05 and PF12.
     */
    private static CoCrdUpInput.AidKey normalizeAidKey(CoCrdUpInput.AidKey aid, UpdateContext context) {
        return switch (aid) {
            case ENTER, PF03_BACK -> aid;
            case PF05_SAVE -> (context.state() == UpdateState.CHANGES_OK_NOT_CONFIRMED)
                    ? aid : CoCrdUpInput.AidKey.ENTER;
            case PF12_CANCEL -> (context.state() != UpdateState.DETAILS_NOT_FETCHED)
                    ? aid : CoCrdUpInput.AidKey.ENTER;
            case PF04_CLEAR, OTHER -> CoCrdUpInput.AidKey.ENTER;
        };
    }

    // ============================================================================
    // Main iteration body — paragraph 0000-MAIN WHEN OTHER:
    //   1000-PROCESS-INPUTS (= 1100-RECEIVE-MAP + 1200-EDIT-MAP-INPUTS)
    //   2000-DECIDE-ACTION
    //   3000-SEND-MAP
    // ============================================================================

    /**
     * One iteration of the read-edit-decide-send loop. The input has
     * already been received (it's the parameter); the COBOL
     * 1100-RECEIVE-MAP step is folded into the caller's invocation.
     */
    private Result processIteration(CoCrdUpInput input, CardDemoCommarea commarea,
                                    UpdateContext context, CoCrdUpInput.AidKey aid) {
        // ----------------------------------------------------------------------
        // 1100-RECEIVE-MAP equivalent: normalise '*' / SPACES → empty
        // ----------------------------------------------------------------------
        String acctVal  = normalizeStarOrSpaces(input.acctSid());
        String cardVal  = normalizeStarOrSpaces(input.cardSid());
        String nameVal  = normalizeStarOrSpaces(input.crdName());
        String stsVal   = normalizeStarOrSpaces(input.crdStsCd());
        String monVal   = normalizeStarOrSpaces(input.expMon());
        String yearVal  = normalizeStarOrSpaces(input.expYear());
        String dayVal   = input.expDay() == null ? "" : input.expDay();

        // ----------------------------------------------------------------------
        // 1200-EDIT-MAP-INPUTS: switch on current state.
        //
        // When DETAILS-NOT-FETCHED → validate ACCT+CARD only, then fall through
        // to 2000-DECIDE-ACTION which performs the 9000-READ-DATA.
        //
        // Otherwise → diff against CCUP-OLD-* via FUNCTION UPPER-CASE; if no
        // changes detected (or already OK_NOT_CONFIRMED or OKAYED_AND_DONE)
        // skip the per-field edits.
        // ----------------------------------------------------------------------
        EditResult edit;
        if (context.state() == UpdateState.DETAILS_NOT_FETCHED) {
            edit = editSearchKeys(acctVal, cardVal);
        } else {
            // Compare new vs old via uppercase normalisation (FUNCTION UPPER-CASE).
            boolean noChanges = noChangesDetected(context, nameVal, stsVal, monVal, yearVal, dayVal);

            if (noChanges
                    || context.state() == UpdateState.CHANGES_OK_NOT_CONFIRMED
                    || context.state() == UpdateState.CHANGES_OKAYED_AND_DONE) {
                edit = EditResult.allValid();
                // Override: when DETAILS-NOT-FETCHED is false and no changes detected,
                // emit MSG_NO_CHANGES below in 3250-SETUP-INFOMSG translation.
                if (noChanges) {
                    edit = new EditResult(false, true, true, true, true, true, true, MSG_NO_CHANGES);
                }
            } else {
                edit = editAllCardFields(nameVal, stsVal, monVal, yearVal);
            }
        }

        // ----------------------------------------------------------------------
        // 2000-DECIDE-ACTION: state transitions
        // ----------------------------------------------------------------------
        UpdateContext newContext = decideAction(
                context, aid, edit, acctVal, cardVal, nameVal, stsVal, monVal, yearVal, dayVal);

        // ----------------------------------------------------------------------
        // 3000-SEND-MAP
        // ----------------------------------------------------------------------
        return Result.sendMap(buildScreen(commarea, newContext, edit, acctVal, cardVal,
                                          nameVal, stsVal, monVal, yearVal, dayVal),
                              commarea, newContext);
    }

    // ============================================================================
    // 1200-EDIT-MAP-INPUTS — search-key validation (DETAILS-NOT-FETCHED branch)
    // ============================================================================

    /**
     * COBOL paragraphs 1210-EDIT-ACCOUNT + 1220-EDIT-CARD. Validates
     * the search keys only (used when {@code CCUP-DETAILS-NOT-FETCHED}).
     */
    private EditResult editSearchKeys(String acctVal, String cardVal) {
        // 1210-EDIT-ACCOUNT
        Boolean acctValid;
        String acctErr = null;
        boolean acctBlank = isBlankOrLow(acctVal) || isAllZeroes(acctVal);
        if (acctBlank) {
            acctErr = MSG_PROMPT_FOR_ACCT;
            acctValid = false;
        } else if (!isNumeric(acctVal)) {
            acctErr = MSG_ACCT_NOT_NUMERIC;
            acctValid = false;
        } else {
            acctValid = true;
        }

        // 1220-EDIT-CARD
        String cardErr = null;
        boolean cardBlank = isBlankOrLow(cardVal) || isAllZeroes(cardVal);
        Boolean cardValid;
        if (cardBlank) {
            cardErr = MSG_PROMPT_FOR_CARD;
            cardValid = false;
        } else if (!isNumeric(cardVal)) {
            cardErr = MSG_CARD_NOT_NUMERIC;
            cardValid = false;
        } else {
            cardValid = true;
        }

        // CROSS-FIELD EDITS: both blank → NO-SEARCH-CRITERIA-RECEIVED
        String firstErr;
        if (acctBlank && cardBlank) {
            firstErr = MSG_NO_INPUT;
        } else {
            firstErr = acctErr != null ? acctErr : cardErr;
        }

        boolean hasError = !(acctValid && cardValid);

        return new EditResult(
                hasError,
                acctValid,
                cardValid,
                true,   // name not checked in search-key phase
                true,   // status not checked
                true,   // expmon not checked
                true,   // expyear not checked
                firstErr == null ? "" : firstErr);
    }

    // ============================================================================
    // 1200-EDIT-MAP-INPUTS — per-field validation (changes branch)
    // ============================================================================

    /**
     * COBOL paragraphs 1230-EDIT-NAME + 1240-EDIT-CARDSTATUS +
     * 1250-EDIT-EXPIRY-MON + 1260-EDIT-EXPIRY-YEAR. Validates the four
     * editable card-detail fields.
     */
    private EditResult editAllCardFields(String nameVal, String stsVal, String monVal, String yearVal) {
        // 1230-EDIT-NAME
        String nameErr = null;
        boolean nameValid = false;
        if (isBlankOrLow(nameVal) || isAllZeroes(nameVal)) {
            nameErr = MSG_PROMPT_FOR_NAME;
        } else if (!isAlphabetsAndSpacesOnly(nameVal)) {
            nameErr = MSG_NAME_MUST_BE_ALPHA;
        } else {
            nameValid = true;
        }

        // 1240-EDIT-CARDSTATUS
        String stsErr = null;
        boolean stsValid = false;
        if (isBlankOrLow(stsVal) || isAllZeroes(stsVal)) {
            stsErr = MSG_CARD_STATUS_MUST_BE_YN;
        } else if (!isYesOrNo(stsVal)) {
            stsErr = MSG_CARD_STATUS_MUST_BE_YN;
        } else {
            stsValid = true;
        }

        // 1250-EDIT-EXPIRY-MON
        String monErr = null;
        boolean monValid = false;
        if (isBlankOrLow(monVal) || isAllZeroes(monVal)) {
            monErr = MSG_CARD_EXPIRY_MONTH;
        } else if (!isNumeric(monVal)) {
            monErr = MSG_CARD_EXPIRY_MONTH;
        } else {
            int m;
            try {
                m = Integer.parseInt(monVal.trim());
            } catch (NumberFormatException e) {
                m = -1;
            }
            if (m < MIN_VALID_MONTH || m > MAX_VALID_MONTH) {
                monErr = MSG_CARD_EXPIRY_MONTH;
            } else {
                monValid = true;
            }
        }

        // 1260-EDIT-EXPIRY-YEAR
        String yearErr = null;
        boolean yearValid = false;
        if (isBlankOrLow(yearVal) || isAllZeroes(yearVal)) {
            yearErr = MSG_CARD_EXPIRY_YEAR;
        } else if (!isNumeric(yearVal)) {
            yearErr = MSG_CARD_EXPIRY_YEAR;
        } else {
            int y;
            try {
                y = Integer.parseInt(yearVal.trim());
            } catch (NumberFormatException e) {
                y = -1;
            }
            if (y < MIN_VALID_YEAR || y > MAX_VALID_YEAR) {
                yearErr = MSG_CARD_EXPIRY_YEAR;
            } else {
                yearValid = true;
            }
        }

        // First-error-wins (mirrors COBOL: WS-RETURN-MSG-OFF check at each step).
        String firstErr = nameErr != null ? nameErr
                : stsErr != null ? stsErr
                : monErr != null ? monErr
                : yearErr;

        boolean hasError = !(nameValid && stsValid && monValid && yearValid);

        return new EditResult(
                hasError,
                true,         // acct still valid (already fetched)
                true,         // card still valid (already fetched)
                nameValid,
                stsValid,
                monValid,
                yearValid,
                firstErr == null ? "" : firstErr);
    }

    // ============================================================================
    // 2000-DECIDE-ACTION
    // ============================================================================

    /**
     * COBOL paragraph 2000-DECIDE-ACTION. Determines the next state given
     * the current state, the AID key, and the validation outcome.
     */
    private UpdateContext decideAction(UpdateContext context, CoCrdUpInput.AidKey aid,
                                       EditResult edit, String acctVal, String cardVal,
                                       String nameVal, String stsVal, String monVal,
                                       String yearVal, String dayVal) {
        return switch (context.state()) {
            // WHEN CCUP-DETAILS-NOT-FETCHED → if both filters valid then
            // 9000-READ-DATA; on FOUND → SHOW_DETAILS.
            case DETAILS_NOT_FETCHED -> {
                if (edit.acctValid() && edit.cardValid()) {
                    UpdateContext fetched = fetchCardData(acctVal, cardVal);
                    yield fetched;
                }
                yield context;
            }

            // WHEN CCUP-SHOW-DETAILS → if INPUT-ERROR OR NO-CHANGES-DETECTED
            // → CONTINUE (stay on SHOW_DETAILS); else CHANGES_OK_NOT_CONFIRMED.
            case SHOW_DETAILS -> {
                if (edit.hasInputError() || MSG_NO_CHANGES.equals(edit.errorMsg())) {
                    yield context;
                }
                yield withNewDetails(context, nameVal, stsVal, monVal, yearVal, dayVal)
                        .withState(UpdateState.CHANGES_OK_NOT_CONFIRMED);
            }

            // WHEN CCUP-CHANGES-NOT-OK → CONTINUE; if edit now OK transition
            // to CHANGES_OK_NOT_CONFIRMED.
            case CHANGES_NOT_OK -> {
                if (edit.hasInputError()) {
                    yield withNewDetails(context, nameVal, stsVal, monVal, yearVal, dayVal);
                }
                yield withNewDetails(context, nameVal, stsVal, monVal, yearVal, dayVal)
                        .withState(UpdateState.CHANGES_OK_NOT_CONFIRMED);
            }

            // WHEN CCUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05 →
            // 9200-WRITE-PROCESSING; per outcome set LOCK_ERROR / BUT_FAILED /
            // SHOW_DETAILS (data changed) / OKAYED_AND_DONE.
            //
            // WHEN CCUP-CHANGES-OK-NOT-CONFIRMED (no PFK05) → CONTINUE.
            case CHANGES_OK_NOT_CONFIRMED -> {
                if (aid == CoCrdUpInput.AidKey.PF05_SAVE) {
                    yield doWriteProcessing(context);
                }
                yield context;
            }

            // WHEN CCUP-CHANGES-OKAYED-AND-DONE → SHOW_DETAILS (transition);
            // reset acct+card on fresh-tranid path.
            case CHANGES_OKAYED_AND_DONE -> context.withState(UpdateState.SHOW_DETAILS);

            // Other terminal failure states → stay (handled in branch 4 above).
            case CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> context;
        };
    }

    /**
     * Persists the new field values into the {@link UpdateContext}; useful
     * after edits succeed and we want to remember what the user is
     * proposing.
     */
    private static UpdateContext withNewDetails(UpdateContext context,
                                                String name, String sts, String mon, String year, String day) {
        return new UpdateContext(
                context.state(),
                context.acctId(),
                context.cardNum(),
                context.cvvCd(),
                upper(name),
                sts,
                year,
                mon,
                day);
    }

    // ============================================================================
    // 9000-READ-DATA + 9100-GETCARD-BYACCTCARD
    // ============================================================================

    /**
     * COBOL paragraph 9000-READ-DATA + 9100-GETCARD-BYACCTCARD. Reads the
     * card by primary-key cardNum (COBOL passes KEYLENGTH=16). Returns
     * an {@link UpdateContext} carrying the fetched data, or one with
     * {@link UpdateState#DETAILS_NOT_FETCHED} when the read fails.
     *
     * <p>Note: the COBOL search uses CARDFILE primary key alone
     * (CARDNUM); the account id is only used to validate the input.
     */
    private UpdateContext fetchCardData(String acctVal, String cardVal) {
        Optional<CardRecord> rec;
        try {
            rec = cards.findByCardNumber(cardVal.trim());
        } catch (RuntimeException re) {
            return UpdateContext.initial();
        }

        if (rec.isEmpty()) {
            return UpdateContext.initial();
        }

        CardRecord c = rec.get();
        String name = upper(c.cardEmbossedName());
        LocalDate expiry = c.cardExpiraionDate();
        String year = expiry != null ? String.format("%04d", expiry.getYear()) : "";
        String mon  = expiry != null ? String.format("%02d", expiry.getMonthValue()) : "";
        String day  = expiry != null ? String.format("%02d", expiry.getDayOfMonth()) : "";
        return new UpdateContext(
                UpdateState.SHOW_DETAILS,
                String.format("%011d", c.cardAcctId()),
                c.cardNum(),
                String.format("%03d", c.cardCvvCd()),
                name,
                String.valueOf(c.cardActiveStatus()),
                year,
                mon,
                day);
    }

    /**
     * Convenience for the from-COCRDLIC fetch path: takes the pre-
     * populated acct+card from the commarea, fetches, and emits an
     * appropriate SEND-MAP result.
     */
    private Result fetchAndShow(String acctSid, String cardSid, CardDemoCommarea commarea, UpdateContext priorContext) {
        UpdateContext fetched = fetchCardData(acctSid, cardSid);
        CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
        EditResult edit;
        if (fetched.state() == UpdateState.DETAILS_NOT_FETCHED) {
            // Reads failed: emit the not-found error and stay on prompt.
            edit = new EditResult(true, false, false, true, true, true, true, MSG_NOT_FOUND_COMBO);
        } else {
            edit = EditResult.allValid();
        }
        return Result.sendMap(
                buildScreen(reentered, fetched, edit, acctSid, cardSid,
                            fetched.crdName(), fetched.crdSts(),
                            fetched.expMon(), fetched.expYear(), fetched.expDay()),
                reentered, fetched);
    }

    // ============================================================================
    // 9200-WRITE-PROCESSING + 9300-CHECK-CHANGE-IN-REC (optimistic concurrency)
    // ============================================================================

    /**
     * COBOL paragraph 9200-WRITE-PROCESSING. Re-reads the record under
     * (CICS) lock, compares against the cached CCUP-OLD-* snapshot to
     * detect concurrent modification, and either commits the new values
     * (REWRITE) or signals failure.
     */
    private UpdateContext doWriteProcessing(UpdateContext context) {
        Optional<CardRecord> rec;
        try {
            rec = cards.findByCardNumber(context.cardNum().trim());
        } catch (RuntimeException re) {
            return context.withState(UpdateState.CHANGES_OKAYED_LOCK_ERROR);
        }

        if (rec.isEmpty()) {
            return context.withState(UpdateState.CHANGES_OKAYED_LOCK_ERROR);
        }

        CardRecord current = rec.get();
        // 9300-CHECK-CHANGE-IN-REC: upcase the embossed name, then compare
        // each editable field against the CCUP-OLD-* snapshot.
        String currentName = upper(current.cardEmbossedName());
        LocalDate currentExpiry = current.cardExpiraionDate();
        String currentYear = currentExpiry != null ? String.format("%04d", currentExpiry.getYear()) : "";
        String currentMon  = currentExpiry != null ? String.format("%02d", currentExpiry.getMonthValue()) : "";
        String currentDay  = currentExpiry != null ? String.format("%02d", currentExpiry.getDayOfMonth()) : "";
        String currentCvv  = String.format("%03d", current.cardCvvCd());

        boolean unchanged =
                currentCvv.equals(context.cvvCd())
                        && currentName.equals(context.crdName())
                        && currentYear.equals(context.expYear())
                        && currentMon.equals(context.expMon())
                        && currentDay.equals(context.expDay())
                        && String.valueOf(current.cardActiveStatus()).equals(context.crdSts());

        if (!unchanged) {
            // DATA-WAS-CHANGED-BEFORE-UPDATE: refresh the snapshot, transition
            // to SHOW_DETAILS so the user can re-review the new old values.
            UpdateContext refreshed = new UpdateContext(
                    UpdateState.SHOW_DETAILS,
                    context.acctId(),
                    context.cardNum(),
                    currentCvv,
                    currentName,
                    String.valueOf(current.cardActiveStatus()),
                    currentYear,
                    currentMon,
                    currentDay);
            return refreshed;
        }

        // PREPARE THE UPDATE — assemble the new CARD-UPDATE-RECORD from
        // CCUP-NEW-* values and REWRITE.
        try {
            CardRecord updated = buildUpdatedRecord(current, context);
            cards.save(updated);
            return context.withState(UpdateState.CHANGES_OKAYED_AND_DONE);
        } catch (RuntimeException re) {
            return context.withState(UpdateState.CHANGES_OKAYED_BUT_FAILED);
        }
    }

    /**
     * Constructs the new {@link CardRecord} from the current value plus
     * the CCUP-NEW-* fields. The COBOL source builds CARD-UPDATE-RECORD
     * by INITIALIZE then per-field MOVE, including a hyphen-separated
     * expiration date assembled via STRING ... DELIMITED BY SIZE.
     */
    private static CardRecord buildUpdatedRecord(CardRecord current, UpdateContext context) {
        // Account id remains the same (COBOL: CC-ACCT-ID-N → CARD-UPDATE-ACCT-ID).
        long acctId = current.cardAcctId();
        // CVV remains the same (COBOL: CCUP-NEW-CVV-CD → CARD-CVV-CD-X → CARD-UPDATE-CVV-CD;
        // but CCUP-NEW-CVV-CD is never edited on the BMS map, so it equals the snapshot).
        int cvv;
        try {
            cvv = Integer.parseInt(context.cvvCd().trim());
        } catch (NumberFormatException e) {
            cvv = current.cardCvvCd();
        }
        // Card name: CCUP-NEW-CRDNAME (already upper-cased).
        String crdName = padOrClamp(context.crdName(), CRD_NAME_LEN);
        // Expiration date: STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY.
        LocalDate expiry;
        try {
            int y = Integer.parseInt(context.expYear().trim());
            int m = Integer.parseInt(context.expMon().trim());
            int d = Integer.parseInt(context.expDay().trim());
            // Day might be 00 / invalid because the BMS hides it; fall back to day-1.
            if (d < 1 || d > 31) {
                d = 1;
            }
            expiry = LocalDate.of(y, m, d);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            expiry = current.cardExpiraionDate();
        }
        // Active status (Y/N).
        char activeStatus = context.crdSts().isEmpty() ? current.cardActiveStatus() : context.crdSts().charAt(0);

        return new CardRecord(
                current.cardNum(),
                acctId,
                cvv,
                crdName,
                expiry,
                activeStatus,
                current.filler());
    }

    // ============================================================================
    // PF03 exit / fresh-prompt helpers
    // ============================================================================

    /**
     * COBOL: PF03 / post-commit branches → XCTL to from-program (or
     * COMEN01C fallback) after SYNCPOINT. When the from-program is the
     * card-list (COCRDLIC), reset acct+card on the commarea before XCTL
     * per the COBOL source.
     */
    private Result doExit(CardDemoCommarea commarea) {
        String fromProgram = commarea.generalInfo().fromProgram();
        String target = (fromProgram != null && !fromProgram.isBlank()
                && !fromProgram.equals(PROGRAM_ID))
                ? fromProgram
                : ProgramRegistry.CO_MEN_01C;

        CardDemoCommarea outbound = withTarget(commarea, target);
        // If the from-program is the card-list, reset acct+card to zeros.
        if (isFromCcList(commarea)) {
            outbound = outbound
                    .withAccountInfo(new CardDemoCommarea.AccountInfo(0L, ""))
                    .withCardInfo(new CardDemoCommarea.CardInfo("0000000000000000"));
        }
        return Result.xctl(target, outbound);
    }

    /**
     * COBOL: post-commit / post-failure reset of acct+card on commarea.
     */
    private static CardDemoCommarea clearAcctAndCardOnFresh(CardDemoCommarea commarea, UpdateContext context) {
        // Only reset when CDEMO-FROM-TRANID is blank / LOW-VALUES, per the
        // COBOL source for CCUP-CHANGES-OKAYED-AND-DONE (3200-SETUP-SCREEN-VARS).
        String fromTranId = commarea.generalInfo().fromTranId();
        if (fromTranId == null || fromTranId.isBlank()) {
            return commarea
                    .withAccountInfo(new CardDemoCommarea.AccountInfo(0L, ""))
                    .withCardInfo(new CardDemoCommarea.CardInfo("0000000000000000"));
        }
        return commarea;
    }

    // ============================================================================
    // Screen builders — 3000-SEND-MAP composition
    // ============================================================================

    /**
     * Builds the initial prompt screen (CCUP-DETAILS-NOT-FETCHED with no
     * filters supplied yet).
     */
    private static CoCrdUpOutput buildPromptScreen(CardDemoCommarea commarea) {
        return new CoCrdUpOutput(
                TRANSACTION_ID,                                  // trnName
                com.blitzy.carddemo.domain.text.ScreenTitle.TITLE_01,
                todayDate(),
                PROGRAM_ID,                                      // pgmName
                com.blitzy.carddemo.domain.text.ScreenTitle.TITLE_02,
                nowTime(),
                "", "",                                          // acctSid, cardSid
                "", "", "", "", "",                              // crdName, crdStsCd, expMon, expYear, expDay
                clamp(MSG_PROMPT_FOR_SEARCH, 40),                // infoMsg
                "",                                              // errMsg
                "ENTER=Process F3=Exit",                         // fKeys
                "F5=Save F12=Cancel",                            // fKeysC
                CoCrdUpOutput.FieldAttributes.allUnprotected());
    }

    /**
     * COBOL paragraph 3000-SEND-MAP composed of 3100-SCREEN-INIT +
     * 3200-SETUP-SCREEN-VARS + 3250-SETUP-INFOMSG + 3300-SETUP-SCREEN-ATTRS.
     */
    private static CoCrdUpOutput buildScreen(CardDemoCommarea commarea,
                                             UpdateContext context, EditResult edit,
                                             String acctVal, String cardVal,
                                             String nameVal, String stsVal,
                                             String monVal, String yearVal, String dayVal) {

        // 3200-SETUP-SCREEN-VARS: pick OLD vs NEW vs supplied per state.
        // Local record to bundle the 7 outputs from the exhaustive switch
        // expression (avoids 'might not be initialized' warnings on the
        // un-annotated enum switch statement form).
        record ScreenVars(String acct, String card, String name, String sts,
                          String mon, String year, String day) {}

        ScreenVars sv = switch (context.state()) {
            case DETAILS_NOT_FETCHED -> new ScreenVars(
                    isAllZeroes(acctVal) || isBlankOrLow(acctVal) ? "" : acctVal,
                    isAllZeroes(cardVal) || isBlankOrLow(cardVal) ? "" : cardVal,
                    "", "", "", "", "");
            case SHOW_DETAILS -> new ScreenVars(
                    isAllZeroes(acctVal) || isBlankOrLow(acctVal) ? context.acctId() : acctVal,
                    isAllZeroes(cardVal) || isBlankOrLow(cardVal) ? context.cardNum() : cardVal,
                    context.crdName(),
                    context.crdSts(),
                    context.expMon(),
                    context.expYear(),
                    context.expDay());
            case CHANGES_NOT_OK, CHANGES_OK_NOT_CONFIRMED -> new ScreenVars(
                    isAllZeroes(acctVal) || isBlankOrLow(acctVal) ? context.acctId() : acctVal,
                    isAllZeroes(cardVal) || isBlankOrLow(cardVal) ? context.cardNum() : cardVal,
                    isBlankOrLow(nameVal) ? context.crdName() : upper(nameVal),
                    isBlankOrLow(stsVal) ? context.crdSts() : stsVal,
                    isBlankOrLow(monVal) ? context.expMon() : monVal,
                    isBlankOrLow(yearVal) ? context.expYear() : yearVal,
                    isBlankOrLow(dayVal) ? context.expDay() : dayVal);
            case CHANGES_OKAYED_AND_DONE, CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED ->
                    new ScreenVars(
                            context.acctId(),
                            context.cardNum(),
                            context.crdName(),
                            context.crdSts(),
                            context.expMon(),
                            context.expYear(),
                            context.expDay());
        };
        String acctOut = sv.acct();
        String cardOut = sv.card();
        String nameOut = sv.name();
        String stsOut = sv.sts();
        String monOut = sv.mon();
        String yearOut = sv.year();
        String dayOut = sv.day();

        // 3250-SETUP-INFOMSG.
        String infoMsg = pickInfoMessage(context, edit);

        // 3300-SETUP-SCREEN-ATTRS: protect / unprotect / error.
        CoCrdUpOutput.FieldAttributes attrs = pickAttributes(context, edit);

        return new CoCrdUpOutput(
                TRANSACTION_ID,
                com.blitzy.carddemo.domain.text.ScreenTitle.TITLE_01,
                todayDate(),
                PROGRAM_ID,
                com.blitzy.carddemo.domain.text.ScreenTitle.TITLE_02,
                nowTime(),
                clamp(acctOut, ACCT_SID_LEN),
                clamp(cardOut, CARD_SID_LEN),
                clamp(nameOut, CRD_NAME_LEN),
                clamp(stsOut, CRD_STS_LEN),
                clamp(monOut, EXP_MON_LEN),
                clamp(yearOut, EXP_YEAR_LEN),
                clamp(dayOut, EXP_DAY_LEN),
                clamp(infoMsg, 40),
                clamp(edit.errorMsg(), 80),
                "ENTER=Process F3=Exit",
                "F5=Save F12=Cancel",
                attrs);
    }

    /**
     * COBOL paragraph 3250-SETUP-INFOMSG. Picks the info message based
     * on the current state.
     */
    private static String pickInfoMessage(UpdateContext context, EditResult edit) {
        if (!edit.errorMsg().isEmpty() && !MSG_NO_CHANGES.equals(edit.errorMsg())) {
            return ""; // error suppresses info
        }
        return switch (context.state()) {
            case DETAILS_NOT_FETCHED -> MSG_PROMPT_FOR_SEARCH;
            case SHOW_DETAILS -> MSG_DETAILS_SHOWN;
            case CHANGES_NOT_OK -> MSG_PROMPT_FOR_CHANGES;
            case CHANGES_OK_NOT_CONFIRMED -> MSG_PROMPT_FOR_CONFIRMATION;
            case CHANGES_OKAYED_AND_DONE -> MSG_UPDATE_SUCCESS;
            case CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> MSG_UPDATE_FAILURE;
        };
    }

    /**
     * COBOL paragraph 3300-SETUP-SCREEN-ATTRS. Builds the per-field
     * attribute table (PROT vs UNPROT vs ERROR) based on the state and
     * the validation outcome.
     */
    private static CoCrdUpOutput.FieldAttributes pickAttributes(UpdateContext context, EditResult edit) {
        return switch (context.state()) {
            // DETAILS-NOT-FETCHED: keys UNPROT (FSET); detail fields PROT (PRF).
            case DETAILS_NOT_FETCHED -> new CoCrdUpOutput.FieldAttributes(
                    !edit.acctValid() ? CoCrdUpOutput.AttributeMode.ERROR
                            : CoCrdUpOutput.AttributeMode.UNPROTECTED,
                    !edit.cardValid() ? CoCrdUpOutput.AttributeMode.ERROR
                            : CoCrdUpOutput.AttributeMode.UNPROTECTED,
                    CoCrdUpOutput.AttributeMode.PROTECTED,
                    CoCrdUpOutput.AttributeMode.PROTECTED,
                    CoCrdUpOutput.AttributeMode.PROTECTED,
                    CoCrdUpOutput.AttributeMode.PROTECTED,
                    CoCrdUpOutput.AttributeMode.PROTECTED);
            // SHOW-DETAILS / CHANGES-NOT-OK: keys PROT, detail fields UNPROT/ERROR.
            case SHOW_DETAILS, CHANGES_NOT_OK -> new CoCrdUpOutput.FieldAttributes(
                    CoCrdUpOutput.AttributeMode.PROTECTED,
                    CoCrdUpOutput.AttributeMode.PROTECTED,
                    !edit.nameValid() ? CoCrdUpOutput.AttributeMode.ERROR
                            : CoCrdUpOutput.AttributeMode.UNPROTECTED,
                    !edit.stsValid() ? CoCrdUpOutput.AttributeMode.ERROR
                            : CoCrdUpOutput.AttributeMode.UNPROTECTED,
                    !edit.monValid() ? CoCrdUpOutput.AttributeMode.ERROR
                            : CoCrdUpOutput.AttributeMode.UNPROTECTED,
                    !edit.yearValid() ? CoCrdUpOutput.AttributeMode.ERROR
                            : CoCrdUpOutput.AttributeMode.UNPROTECTED,
                    CoCrdUpOutput.AttributeMode.PROTECTED);
            // CHANGES-OK-NOT-CONFIRMED / OKAYED-AND-DONE: all PROT.
            case CHANGES_OK_NOT_CONFIRMED, CHANGES_OKAYED_AND_DONE -> CoCrdUpOutput.FieldAttributes.allProtected();
            // OTHER terminal failure: all PROT.
            case CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> CoCrdUpOutput.FieldAttributes.allProtected();
        };
    }

    // ============================================================================
    // EditResult — combined result of 1200-EDIT-MAP-INPUTS sub-paragraphs.
    // ============================================================================

    /**
     * Combined result of paragraph 1200-EDIT-MAP-INPUTS and its sub-paragraphs
     * (1210/1220/1230/1240/1250/1260). Carries per-field validity flags plus
     * the first-error-wins message.
     */
    private record EditResult(boolean hasInputError,
                              boolean acctValid,
                              boolean cardValid,
                              boolean nameValid,
                              boolean stsValid,
                              boolean monValid,
                              boolean yearValid,
                              String errorMsg) {

        public EditResult {
            errorMsg = errorMsg == null ? "" : errorMsg;
        }

        public static EditResult allValid() {
            return new EditResult(false, true, true, true, true, true, true, "");
        }
    }

    // ============================================================================
    // Diff-detection (FUNCTION UPPER-CASE)
    // ============================================================================

    /**
     * Mirrors the COBOL {@code IF (FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA) EQUAL FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA))}
     * check, comparing each editable field against the OLD snapshot.
     */
    private static boolean noChangesDetected(UpdateContext context,
                                             String name, String sts, String mon, String year, String day) {
        return upper(name).equals(upper(context.crdName()))
                && upper(sts).equals(upper(context.crdSts()))
                && upper(mon).equals(upper(context.expMon()))
                && upper(year).equals(upper(context.expYear()))
                && upper(day).equals(upper(context.expDay()));
    }

    // ============================================================================
    // Commarea helpers
    // ============================================================================

    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                gi.fromTranId(),
                gi.fromProgram(),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                ctx);
        return commarea.withGeneralInfo(updated);
    }

    private static CardDemoCommarea withTarget(CardDemoCommarea commarea, String toProgram) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                gi.toTranId(),
                toProgram,
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return commarea.withGeneralInfo(updated);
    }

    private static boolean isFromCcList(CardDemoCommarea commarea) {
        // COBOL: CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM, or
        //        CDEMO-LAST-MAPSET EQUAL LIT-CCLISTMAPSET.
        String from = commarea.generalInfo().fromProgram();
        boolean fromPgm = from != null && from.trim().equals(ProgramRegistry.CO_CRD_LI_C);
        String lastMapset = commarea.moreInfo().lastMapset();
        boolean fromMapset = lastMapset != null && lastMapset.trim().equals(MAPSET_CCLIST);
        return fromPgm || fromMapset;
    }

    private static boolean isFromMainMenu(CardDemoCommarea commarea) {
        String from = commarea.generalInfo().fromProgram();
        return from != null && from.trim().equals(ProgramRegistry.CO_MEN_01C);
    }

    private static boolean isChangesFailed(UpdateContext context) {
        return context.state() == UpdateState.CHANGES_OKAYED_LOCK_ERROR
                || context.state() == UpdateState.CHANGES_OKAYED_BUT_FAILED;
    }

    private static String preselectedAcctIdString(CardDemoCommarea commarea) {
        long acct = commarea.accountInfo().acctId();
        return acct == 0L ? "" : String.format("%011d", acct);
    }

    private static String preselectedCardNumString(CardDemoCommarea commarea) {
        // CardInfo.cardNum is a 16-digit String (preserves leading zeros, supports
        // PAN masking per AAP §0.7.2). All-zeros indicates "no preselection".
        String card = commarea.cardInfo().cardNum();
        return "0000000000000000".equals(card) ? "" : card;
    }

    // ============================================================================
    // String / numeric helpers (mirrors CoCrdSlC's conventions)
    // ============================================================================

    /** COBOL 1100-RECEIVE-MAP: replace '*' or SPACES with LOW-VALUES → empty. */
    private static String normalizeStarOrSpaces(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return "";
        }
        return raw;
    }

    private static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZeroes(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * COBOL 1230-EDIT-NAME alphabet check, modelled on the
     * {@code INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM TO
     * LIT-ALL-SPACES-TO} + {@code FUNCTION LENGTH(FUNCTION TRIM(...))=0}
     * pattern. Returns true when every non-space character is a Latin
     * letter (A-Z or a-z).
     */
    private static boolean isAlphabetsAndSpacesOnly(String s) {
        if (s == null || s.isEmpty()) return false;
        boolean hasContent = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == 0) continue;
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
            hasContent = true;
        }
        return hasContent;
    }

    /**
     * COBOL 1240-EDIT-CARDSTATUS: FLG-YES-NO-CHECK accepts 'Y' or 'N'.
     */
    private static boolean isYesOrNo(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        return "Y".equals(trimmed) || "N".equals(trimmed);
    }

    /**
     * COBOL {@code INSPECT ... CONVERTING LIT-LOWER TO LIT-UPPER}. Returns
     * the upper-cased form of the supplied value; null becomes empty.
     */
    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.ROOT);
    }

    private static String clamp(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    /**
     * Right-pads with spaces to the requested length; if too long, truncates
     * from the right. Used for CRDNAMEO 50-byte field per BMS layout.
     */
    private static String padOrClamp(String s, int len) {
        String v = s == null ? "" : s;
        if (v.length() == len) return v;
        if (v.length() > len) return v.substring(0, len);
        StringBuilder sb = new StringBuilder(len);
        sb.append(v);
        while (sb.length() < len) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static CoCrdUpInput coalesceInput(CoCrdUpInput input) {
        return input != null ? input : CoCrdUpInput.blank();
    }

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
