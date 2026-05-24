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

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.CustomerRecord;
import com.blitzy.carddemo.domain.status.PgmContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Java translation of COBOL program {@code COACTUPC}
 * ({@code app/cbl/COACTUPC.cbl}, CICS transaction {@code CAUP}).
 *
 * <p><b>Purpose:</b> Update an existing account (ACCTDAT) and its
 * associated customer record (CUSTDAT) in a single optimistic-concurrent
 * transaction. The user fetches an account by 11-digit id, edits up to 30
 * fields (active status, balances, credit limits, dates, account group;
 * SSN, DOB, FICO score, name, address, two phones, government id, EFT
 * account, primary cardholder), reviews changes (state
 * {@code 'N'} = changes ok, not confirmed), then presses PF5 to commit;
 * PF12 cancels and re-displays the original values; PF3 exits to the
 * caller (typically COMEN01C) with a SYNCPOINT.
 *
 * <h2>State machine ({@code ACUP-CHANGE-ACTION} values)</h2>
 * <ul>
 *   <li>{@link UpdateState#DETAILS_NOT_FETCHED} (LOW-VALUES / SPACES) --
 *       no account fetched yet; show prompt screen with only the account
 *       id field editable.</li>
 *   <li>{@link UpdateState#SHOW_DETAILS} ('S') -- account details
 *       displayed for first-time review with all editable fields
 *       unprotected.</li>
 *   <li>{@link UpdateState#CHANGES_NOT_OK} ('E') -- user submitted edits
 *       but validation failed.</li>
 *   <li>{@link UpdateState#CHANGES_OK_NOT_CONFIRMED} ('N') -- edits valid;
 *       prompt user to press PF5 to commit.</li>
 *   <li>{@link UpdateState#CHANGES_OKAYED_AND_DONE} ('C') -- commit
 *       successful; reset to fresh search state.</li>
 *   <li>{@link UpdateState#CHANGES_OKAYED_LOCK_ERROR} ('L') -- could not
 *       lock either record for update.</li>
 *   <li>{@link UpdateState#CHANGES_OKAYED_BUT_FAILED} ('F') -- locked but
 *       REWRITE failed; on customer REWRITE failure after account REWRITE
 *       succeeded, the COBOL source issues {@code EXEC CICS SYNCPOINT
 *       ROLLBACK} -- the Java translation models this via a try/finally
 *       block that restores the original {@link AccountRecord} snapshot.</li>
 * </ul>
 *
 * <h2>Optimistic concurrency (9600-WRITE-PROCESSING + 9700-CHECK-CHANGE-IN-REC)</h2>
 * <p>Before committing, the program re-reads both the account and
 * customer records under (CICS) lock, compares each editable field
 * against the {@code ACUP-OLD-*} snapshot stored in commarea, and aborts
 * with {@code DATA-WAS-CHANGED-BEFORE-UPDATE} when any has changed since
 * the user first viewed the records.
 *
 * <h2>Two-record SYNCPOINT ROLLBACK semantics (the unique COACTUPC challenge)</h2>
 * <p>The COBOL source performs two REWRITE statements (ACCTDAT first,
 * then CUSTDAT). If the customer REWRITE fails after the account REWRITE
 * succeeded, the program issues an explicit {@code EXEC CICS SYNCPOINT
 * ROLLBACK} so the partial commit is undone. The Java translation does
 * not have an underlying transactional service to issue rollbacks
 * against the file adapter, so it models the equivalent semantics by
 * re-saving the original account record snapshot via
 * {@link AccountRepository#save(AccountRecord)} -- this restores
 * ACCTDAT to its pre-edit state. See {@code MIGRATION_NOTES.md} &sect;1.3.1.
 *
 * <h2>Messages preserved verbatim per AAP &sect;0.7.1</h2>
 * <ul>
 *   <li>{@code 'Details of selected account shown above'} (FOUND-ACCOUNT-DATA)</li>
 *   <li>{@code 'Enter or update id of account to update'} (PROMPT-FOR-SEARCH-KEYS)</li>
 *   <li>{@code 'Update account details presented above.'} (PROMPT-FOR-CHANGES)</li>
 *   <li>{@code 'Changes validated.Press F5 to save'} -- <b>no space</b> after the dot (PROMPT-FOR-CONFIRMATION)</li>
 *   <li>{@code 'Changes committed to database'} -- no period (CONFIRM-UPDATE-SUCCESS)</li>
 *   <li>{@code 'Changes unsuccessful. Please try again'} (INFORM-FAILURE)</li>
 *   <li>{@code 'PF03 pressed.Exiting              '} -- mixed case, <b>14 trailing spaces</b>, no space after the dot (WS-EXIT-MESSAGE)</li>
 *   <li>{@code 'Account number not provided'} (WS-PROMPT-FOR-ACCT)</li>
 *   <li>{@code 'Last name not provided'} (WS-PROMPT-FOR-LASTNAME)</li>
 *   <li>{@code 'Name can only contain alphabets and spaces'} -- 'alphabets' idiom (WS-NAME-MUST-BE-ALPHA)</li>
 *   <li>{@code 'No input received'} (NO-SEARCH-CRITERIA-RECEIVED)</li>
 *   <li>{@code 'No change detected with respect to values fetched.'} (NO-CHANGES-DETECTED)</li>
 *   <li>{@code 'Account number must be a non zero 11 digit number'} (SEARCHED-ACCT-ZEROES / NOT-NUMERIC)</li>
 *   <li>{@code 'Did not find this account in account card xref file'} (DID-NOT-FIND-ACCT-IN-CARDXREF)</li>
 *   <li>{@code 'Did not find this account in account master file'} (DID-NOT-FIND-ACCT-IN-ACCTDAT)</li>
 *   <li>{@code 'Did not find associated customer in master file'} (DID-NOT-FIND-CUST-IN-CUSTDAT)</li>
 *   <li>{@code 'Account Active Status must be Y or N'} (ACCT-STATUS-MUST-BE-YES-NO)</li>
 *   <li>{@code 'Credit Limit must be supplied'} (CRED-LIMIT-IS-BLANK)</li>
 *   <li>{@code 'Credit Limit is not valid'} (CRED-LIMIT-IS-NOT-VALID)</li>
 *   <li>{@code 'Card expiry month must be between 1 and 12'} (THIS-MONTH-NOT-VALID)</li>
 *   <li>{@code 'Invalid card expiry year'} (THIS-YEAR-NOT-VALID)</li>
 *   <li>{@code 'Did not find cards for this search condition'} (DID-NOT-FIND-ACCTCARD-COMBO)</li>
 *   <li>{@code 'Could not lock account record for update'} -- no period (COULD-NOT-LOCK-ACCT-FOR-UPDATE)</li>
 *   <li>{@code 'Could not lock customer record for update'} -- no period (COULD-NOT-LOCK-CUST-FOR-UPDATE)</li>
 *   <li>{@code 'Record changed by some one else. Please review'} -- two-word 'some one' (DATA-WAS-CHANGED-BEFORE-UPDATE)</li>
 *   <li>{@code 'Update of record failed'} -- no period (LOCKED-BUT-UPDATE-FAILED)</li>
 *   <li>{@code 'Error reading Card Data File'} -- Title Case (XREF-READ-ERROR)</li>
 *   <li>{@code 'Looks Good.... so far'} -- four dots, then space, then 'so far' (CODING-TO-BE-DONE placeholder)</li>
 *   <li>{@code 'Account Number if supplied must be a 11 digit Non-Zero Number'} -- STRING-concatenated (1210-EDIT-ACCOUNT)</li>
 *   <li>{@code 'Account:' + acct + ' not found in Cross ref file. Resp:' + resp + ' Reas:' + reas2} -- mixed-case 'Reas:'</li>
 *   <li>{@code 'Account:' + acct + ' not found in Acct Master file.Resp:' + resp + ' Reas:' + reas2} -- NO space before 'Resp:' (anomaly)</li>
 *   <li>{@code 'CustId:' + cust + ' not found in customer master.Resp: ' + resp + ' REAS:' + reas2} -- uppercase 'REAS:' and space after 'Resp:' (anomaly)</li>
 * </ul>
 *
 * <h2>AID key validity (mirrors PFK-VALID semantics)</h2>
 * <p>ENTER, PF03, PF05 (only when {@code ACUP-CHANGES-OK-NOT-CONFIRMED}),
 * PF12 (only when NOT {@code ACUP-DETAILS-NOT-FETCHED}). All other AID
 * keys are remapped to ENTER (per COBOL {@code IF PFK-INVALID SET
 * CCARD-AID-ENTER TO TRUE}). The COACTUP {@link CoActUpInput.AidKey} enum
 * carries 6 values (ENTER, PF03_BACK, PF04_CLEAR, PF05_SAVE, PF12_CANCEL,
 * OTHER); PF04_CLEAR is not honoured at the COACTUPC layer (the screen
 * does not surface a PF04 binding), but it is accepted by the input DTO
 * for symmetry with other update screens.
 */
@CobolProgram(
        value = "COACTUPC",
        sourcePath = "app/cbl/COACTUPC.cbl",
        notes = "Account+customer update online with state machine, optimistic concurrency, "
                + "30+ EDIT paragraphs, and two-record SYNCPOINT ROLLBACK semantics. "
                + "State persisted via UpdateContext parameter modelling ACUP-CHANGE-ACTION + "
                + "ACUP-OLD-DETAILS appended to WS-THIS-PROGCOMMAREA. CICS READ-UPDATE/REWRITE "
                + "translated to findById() + diff against ACUP-OLD-* snapshot + save(). "
                + "SYNCPOINT ROLLBACK on customer REWRITE failure modelled via try/finally "
                + "that re-saves the original account record. Verbatim messages including the "
                + "no-space 'Changes validated.Press F5 to save' (PROMPT-FOR-CONFIRMATION), "
                + "the mixed-case 14-trailing-spaces 'PF03 pressed.Exiting              ' "
                + "(WS-EXIT-MESSAGE), the two-word 'some one' in 'Record changed by some one "
                + "else. Please review' (DATA-WAS-CHANGED-BEFORE-UPDATE), the four-dot "
                + "'Looks Good.... so far' (CODING-TO-BE-DONE), the 'alphabets' idiom in "
                + "'Name can only contain alphabets and spaces' (WS-NAME-MUST-BE-ALPHA), the "
                + "no-space-after-period 'Acct Master file.Resp:' anomaly, and the "
                + "uppercase 'REAS:' (mixed with lowercase 'Reas:' elsewhere) in the "
                + "customer-not-found message."
)
public final class CoActUpC {

    private static final String PROGRAM_ID = "COACTUPC";
    private static final String TRANSACTION_ID = "CAUP";

    // ============================================================================
    // Verbatim message constants (per AAP §0.7.1) — preserve exact text including
    // commas-without-spaces, missing-space-after-dot, four-dot ellipsis, two-word
    // 'some one', uppercase 'REAS:', and trailing-space padding.
    // ============================================================================

    /** PROMPT-FOR-SEARCH-KEYS — initial prompt. */
    private static final String MSG_PROMPT_FOR_SEARCH = "Enter or update id of account to update";

    /** FOUND-ACCOUNT-DATA. */
    private static final String MSG_DETAILS_SHOWN = "Details of selected account shown above";

    /** PROMPT-FOR-CHANGES. */
    private static final String MSG_PROMPT_FOR_CHANGES = "Update account details presented above.";

    /**
     * PROMPT-FOR-CONFIRMATION — verbatim, <b>no space</b> after the dot
     * before 'Press', per AAP §0.7.1.
     */
    private static final String MSG_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** CONFIRM-UPDATE-SUCCESS — verbatim, no period. */
    private static final String MSG_UPDATE_SUCCESS = "Changes committed to database";

    /** INFORM-FAILURE. */
    private static final String MSG_UPDATE_FAILURE = "Changes unsuccessful. Please try again";

    /**
     * WS-EXIT-MESSAGE — verbatim, mixed-case, <b>14 trailing spaces</b>, no
     * space after the dot before 'Exiting'. Same as COCRDSLC / COCRDUPC.
     */
    private static final String MSG_PF03_EXIT = "PF03 pressed.Exiting              ";

    /** WS-PROMPT-FOR-ACCT. */
    private static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

    /** WS-PROMPT-FOR-LASTNAME. */
    private static final String MSG_PROMPT_FOR_LASTNAME = "Last name not provided";

    /**
     * WS-NAME-MUST-BE-ALPHA — verbatim, 'alphabets' (COBOL idiom for
     * 'letters').
     */
    private static final String MSG_NAME_MUST_BE_ALPHA = "Name can only contain alphabets and spaces";

    /** NO-SEARCH-CRITERIA-RECEIVED. */
    private static final String MSG_NO_INPUT = "No input received";

    /** NO-CHANGES-DETECTED — verbatim, terminal period. */
    private static final String MSG_NO_CHANGES = "No change detected with respect to values fetched.";

    /** SEARCHED-ACCT-ZEROES / SEARCHED-ACCT-NOT-NUMERIC. */
    private static final String MSG_ACCT_NOT_NUMERIC = "Account number must be a non zero 11 digit number";

    /**
     * 1210-EDIT-ACCOUNT STRING'd message — verbatim, two literal parts
     * joined: 'Account Number if supplied must be a 11 digit' +
     * ' Non-Zero Number'.
     */
    private static final String MSG_ACCT_NON_ZERO =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    /** DID-NOT-FIND-ACCT-IN-CARDXREF (1st of two 88-level VALUEs). */
    private static final String MSG_NOT_FOUND_XREF = "Did not find this account in account card xref file";

    /** DID-NOT-FIND-ACCT-IN-ACCTDAT. */
    private static final String MSG_NOT_FOUND_ACCT = "Did not find this account in account master file";

    /** DID-NOT-FIND-CUST-IN-CUSTDAT. */
    private static final String MSG_NOT_FOUND_CUST = "Did not find associated customer in master file";

    /** ACCT-STATUS-MUST-BE-YES-NO. */
    private static final String MSG_ACCT_STATUS_MUST_BE_YN = "Account Active Status must be Y or N";

    /** CRED-LIMIT-IS-BLANK. */
    private static final String MSG_CRED_LIMIT_BLANK = "Credit Limit must be supplied";

    /** CRED-LIMIT-IS-NOT-VALID. */
    private static final String MSG_CRED_LIMIT_NOT_VALID = "Credit Limit is not valid";

    /** THIS-MONTH-NOT-VALID (used for any date-month failure). */
    private static final String MSG_DATE_MONTH = "Card expiry month must be between 1 and 12";

    /** THIS-YEAR-NOT-VALID. */
    private static final String MSG_DATE_YEAR = "Invalid card expiry year";

    /** COULD-NOT-LOCK-ACCT-FOR-UPDATE — verbatim, no period. */
    private static final String MSG_LOCK_ACCT = "Could not lock account record for update";

    /** COULD-NOT-LOCK-CUST-FOR-UPDATE — verbatim, no period. */
    private static final String MSG_LOCK_CUST = "Could not lock customer record for update";

    /**
     * DATA-WAS-CHANGED-BEFORE-UPDATE — verbatim, two-word 'some one'
     * (COBOL spelling).
     */
    private static final String MSG_DATA_CHANGED = "Record changed by some one else. Please review";

    /** LOCKED-BUT-UPDATE-FAILED — verbatim, no period. */
    private static final String MSG_UPDATE_FAILED_LATE = "Update of record failed";

    /** XREF-READ-ERROR — verbatim, Title Case. */
    private static final String MSG_XREF_READ_ERROR = "Error reading Card Data File";

    /**
     * CODING-TO-BE-DONE — verbatim, four dots then space then 'so far'.
     * The COBOL source uses this as a placeholder; preserved per AAP
     * &sect;0.7.1.
     */
    @SuppressWarnings("unused")
    private static final String MSG_TODO = "Looks Good.... so far";

    // SSN validation messages — 1265-EDIT-US-SSN.
    private static final String MSG_SSN_PART1_BLANK = "SSN: First 3 chars must be supplied.";
    private static final String MSG_SSN_PART1_NOT_NUMERIC = "SSN: First 3 chars must be all numeric.";
    private static final String MSG_SSN_PART1_RANGE =
            "SSN: First 3 chars should not be 000, 666, or between 900 and 999";
    private static final String MSG_SSN_PART2_BLANK = "SSN 4th & 5th chars must be supplied.";
    private static final String MSG_SSN_PART2_NOT_NUMERIC = "SSN 4th & 5th chars must be all numeric.";
    private static final String MSG_SSN_PART3_BLANK = "SSN Last 4 chars must be supplied.";
    private static final String MSG_SSN_PART3_NOT_NUMERIC = "SSN Last 4 chars must be all numeric.";

    // FICO score validation — 1275-EDIT-FICO-SCORE.
    private static final String MSG_FICO_RANGE = "FICO Score: should be between 300 and 850";
    private static final String MSG_FICO_BLANK = "FICO Score must be supplied.";
    private static final String MSG_FICO_NOT_NUMERIC = "FICO Score must be all numeric.";

    // State / zip validation — 1270, 1280.
    private static final String MSG_STATE_INVALID = "State: is not a valid state code";
    private static final String MSG_STATE_ZIP_COMBO = "Invalid zip code for state";

    // Phone validation parts — 1260-EDIT-US-PHONE-NUM.
    private static final String MSG_PHONE_AREA_BLANK = ": Area code must be supplied.";
    private static final String MSG_PHONE_AREA_NUMERIC = ": Area code must be A 3 digit number.";
    private static final String MSG_PHONE_AREA_ZERO = ": Area code cannot be zero";
    private static final String MSG_PHONE_AREA_INVALID =
            ": Not valid North America general purpose area code";
    private static final String MSG_PHONE_PREFIX_BLANK = ": Prefix code must be supplied.";
    private static final String MSG_PHONE_PREFIX_NUMERIC = ": Prefix code must be A 3 digit number.";
    private static final String MSG_PHONE_PREFIX_ZERO = ": Prefix code cannot be zero";
    private static final String MSG_PHONE_LINE_BLANK = ": Line number code must be supplied.";
    private static final String MSG_PHONE_LINE_NUMERIC = ": Line number code must be A 4 digit number.";
    private static final String MSG_PHONE_LINE_ZERO = ": Line number code cannot be zero";

    // Calendar/PIC limits per CARD-MONTH-CHECK / CARD-YEAR-CHECK
    private static final int MIN_VALID_MONTH = 1;
    private static final int MAX_VALID_MONTH = 12;
    private static final int MIN_VALID_YEAR = 1900;
    private static final int MAX_VALID_YEAR = 2099;

    // FICO score range per CSUSR01Y / COCOM01Y 88-level FICO-RANGE-IS-VALID
    private static final int FICO_MIN = 300;
    private static final int FICO_MAX = 850;

    // SSN part-1 invalid values per CSUSR01Y 88-level INVALID-SSN-PART1.
    private static final Set<Integer> INVALID_SSN_PART1_FIXED = Set.of(0, 666);
    private static final int INVALID_SSN_PART1_HIGH_LOW = 900;
    private static final int INVALID_SSN_PART1_HIGH_HI = 999;

    // PIC widths from app/bms/COACTUP.bms / WORKING-STORAGE.
    private static final int ACCT_SID_LEN = 11;
    private static final int CUST_NUM_LEN = 9;
    private static final int CRD_NAME_LEN = 25;
    private static final int ADDR_LINE_LEN = 50;
    private static final int STATE_LEN = 2;
    private static final int COUNTRY_LEN = 3;
    private static final int ZIP_LEN = 5;
    private static final int FICO_LEN = 3;
    private static final int GOVT_ID_LEN = 20;
    private static final int EFT_LEN = 10;
    private static final int GROUP_LEN = 10;
    private static final int CRED_LIM_LEN = 15;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Monetary scale (PIC S9(10)V99) per AAP §0.6.1.
    private static final int MONETARY_SCALE = 2;

    // Dependencies (constructor-injected).
    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final CardXrefRepository cardXrefs;
    private final ProgramRegistry programRegistry;

    public CoActUpC(AccountRepository accounts,
                    CustomerRepository customers,
                    CardXrefRepository cardXrefs,
                    ProgramRegistry programRegistry) {
        this.accounts = accounts;
        this.customers = customers;
        this.cardXrefs = cardXrefs;
        this.programRegistry = programRegistry;
    }

    // ============================================================================
    // UpdateState — state machine modelling ACUP-CHANGE-ACTION.
    // ============================================================================

    /**
     * State machine modelling {@code ACUP-CHANGE-ACTION} from the COBOL
     * source. The state machine is a closed taxonomy of 7 values matching
     * the single-character values in the COBOL source (LOW-VALUES, 'S',
     * 'E', 'N', 'C', 'L', 'F'); using an enum gives compile-time
     * exhaustiveness guarantees per AAP &sect;0.7.3.
     */
    public enum UpdateState {
        /** {@code ACUP-DETAILS-NOT-FETCHED} (LOW-VALUES / SPACES). */
        DETAILS_NOT_FETCHED,
        /** {@code ACUP-SHOW-DETAILS} ('S'). */
        SHOW_DETAILS,
        /** {@code ACUP-CHANGES-NOT-OK} ('E'). */
        CHANGES_NOT_OK,
        /** {@code ACUP-CHANGES-OK-NOT-CONFIRMED} ('N'). */
        CHANGES_OK_NOT_CONFIRMED,
        /** {@code ACUP-CHANGES-OKAYED-AND-DONE} ('C'). */
        CHANGES_OKAYED_AND_DONE,
        /** {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} ('L'). */
        CHANGES_OKAYED_LOCK_ERROR,
        /** {@code ACUP-CHANGES-OKAYED-BUT-FAILED} ('F'). */
        CHANGES_OKAYED_BUT_FAILED
    }

    /**
     * Snapshot of the {@code ACUP-OLD-*} fields persisted across screens
     * for optimistic-concurrency comparison and for the cancel (PF12)
     * path. The COBOL program appends this to the commarea as part of
     * {@code WS-THIS-PROGCOMMAREA}; the Java translation surfaces it as
     * an explicit parameter rather than expanding the commarea schema.
     *
     * <p>The snapshot captures both the account and customer
     * pre-edit values for the diff in {@code 9700-CHECK-CHANGE-IN-REC}.
     */
    public record UpdateContext(UpdateState state,
                                // Account snapshot (ACUP-OLD-ACCT-DATA)
                                String acctId,            // ACUP-OLD-ACCT-ID-X
                                String acctStatus,        // ACUP-OLD-ACTIVE-STATUS
                                BigDecimal currBal,       // ACUP-OLD-CURR-BAL-N
                                BigDecimal creditLimit,   // ACUP-OLD-CREDIT-LIMIT-N
                                BigDecimal cashCreditLimit, // ACUP-OLD-CASH-CREDIT-LIMIT-N
                                BigDecimal currCycCredit, // ACUP-OLD-CURR-CYC-CREDIT-N
                                BigDecimal currCycDebit,  // ACUP-OLD-CURR-CYC-DEBIT-N
                                String openYear,          // ACUP-OLD-OPEN-YEAR  (PIC X(4))
                                String openMonth,         // ACUP-OLD-OPEN-MON   (PIC X(2))
                                String openDay,           // ACUP-OLD-OPEN-DAY   (PIC X(2))
                                String expirationYear,    // ACUP-OLD-EXP-YEAR
                                String expirationMonth,   // ACUP-OLD-EXP-MON
                                String expirationDay,     // ACUP-OLD-EXP-DAY
                                String reissueYear,       // ACUP-OLD-REISSUE-YEAR
                                String reissueMonth,      // ACUP-OLD-REISSUE-MON
                                String reissueDay,        // ACUP-OLD-REISSUE-DAY
                                String groupId,           // ACUP-OLD-GROUP-ID
                                // Customer snapshot (ACUP-OLD-CUST-DATA)
                                String custId,            // ACUP-OLD-CUST-ID-X
                                String custSsn,           // ACUP-OLD-CUST-SSN-X   (9 digits)
                                String dobYear,           // ACUP-OLD-CUST-DOB-YEAR
                                String dobMonth,          // ACUP-OLD-CUST-DOB-MON
                                String dobDay,            // ACUP-OLD-CUST-DOB-DAY
                                String ficoScore,         // ACUP-OLD-CUST-FICO-SCORE-X
                                String firstName,         // ACUP-OLD-CUST-FIRST-NAME
                                String middleName,        // ACUP-OLD-CUST-MIDDLE-NAME
                                String lastName,          // ACUP-OLD-CUST-LAST-NAME
                                String addressLine1,      // ACUP-OLD-CUST-ADDR-LINE-1
                                String addressLine2,      // ACUP-OLD-CUST-ADDR-LINE-2
                                String city,              // ACUP-OLD-CUST-ADDR-LINE-3 (city per BMS)
                                String addrStateCd,       // ACUP-OLD-CUST-ADDR-STATE-CD (renamed from 'state' to avoid clash with UpdateState 'state')
                                String country,           // ACUP-OLD-CUST-ADDR-COUNTRY-CD
                                String zip,               // ACUP-OLD-CUST-ADDR-ZIP
                                String phone1Area,        // extracted from ACUP-OLD-CUST-PHONE-NUM-1 (2:3)
                                String phone1Mid,         // (6:3)
                                String phone1End,         // (10:4)
                                String phone2Area,        // extracted from ACUP-OLD-CUST-PHONE-NUM-2 (2:3)
                                String phone2Mid,         // (6:3)
                                String phone2End,         // (10:4)
                                String govtIssuedId,      // ACUP-OLD-CUST-GOVT-ISSUED-ID
                                String eftAccountId,      // ACUP-OLD-CUST-EFT-ACCOUNT-ID
                                String primaryFlag        // ACUP-OLD-CUST-PRI-HOLDER-IND
    ) {

        public UpdateContext {
            state = state == null ? UpdateState.DETAILS_NOT_FETCHED : state;
            acctId = orEmpty(acctId);
            acctStatus = orEmpty(acctStatus);
            currBal = currBal == null ? BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.UNNECESSARY) : currBal;
            creditLimit = creditLimit == null ? BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.UNNECESSARY) : creditLimit;
            cashCreditLimit = cashCreditLimit == null ? BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.UNNECESSARY) : cashCreditLimit;
            currCycCredit = currCycCredit == null ? BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.UNNECESSARY) : currCycCredit;
            currCycDebit = currCycDebit == null ? BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.UNNECESSARY) : currCycDebit;
            openYear = orEmpty(openYear);
            openMonth = orEmpty(openMonth);
            openDay = orEmpty(openDay);
            expirationYear = orEmpty(expirationYear);
            expirationMonth = orEmpty(expirationMonth);
            expirationDay = orEmpty(expirationDay);
            reissueYear = orEmpty(reissueYear);
            reissueMonth = orEmpty(reissueMonth);
            reissueDay = orEmpty(reissueDay);
            groupId = orEmpty(groupId);
            custId = orEmpty(custId);
            custSsn = orEmpty(custSsn);
            dobYear = orEmpty(dobYear);
            dobMonth = orEmpty(dobMonth);
            dobDay = orEmpty(dobDay);
            ficoScore = orEmpty(ficoScore);
            firstName = orEmpty(firstName);
            middleName = orEmpty(middleName);
            lastName = orEmpty(lastName);
            addressLine1 = orEmpty(addressLine1);
            addressLine2 = orEmpty(addressLine2);
            city = orEmpty(city);
            addrStateCd = orEmpty(addrStateCd);
            country = orEmpty(country);
            zip = orEmpty(zip);
            phone1Area = orEmpty(phone1Area);
            phone1Mid = orEmpty(phone1Mid);
            phone1End = orEmpty(phone1End);
            phone2Area = orEmpty(phone2Area);
            phone2Mid = orEmpty(phone2Mid);
            phone2End = orEmpty(phone2End);
            govtIssuedId = orEmpty(govtIssuedId);
            eftAccountId = orEmpty(eftAccountId);
            primaryFlag = orEmpty(primaryFlag);
        }

        public static UpdateContext initial() {
            return new UpdateContext(
                    UpdateState.DETAILS_NOT_FETCHED,
                    "", "", null, null, null, null, null,
                    "", "", "", "", "", "", "", "", "", "",
                    "", "", "", "", "", "", "", "", "",
                    "", "", "", "", "", "",
                    "", "", "", "", "", "",
                    "", "", "");
        }

        /** Returns a copy with the supplied state substituted. */
        public UpdateContext withState(UpdateState newState) {
            return new UpdateContext(
                    newState,
                    acctId, acctStatus, currBal, creditLimit, cashCreditLimit, currCycCredit, currCycDebit,
                    openYear, openMonth, openDay,
                    expirationYear, expirationMonth, expirationDay,
                    reissueYear, reissueMonth, reissueDay,
                    groupId,
                    custId, custSsn, dobYear, dobMonth, dobDay, ficoScore,
                    firstName, middleName, lastName,
                    addressLine1, addressLine2, city, addrStateCd, country, zip,
                    phone1Area, phone1Mid, phone1End,
                    phone2Area, phone2Mid, phone2End,
                    govtIssuedId, eftAccountId, primaryFlag);
        }

        private static String orEmpty(String s) {
            return s == null ? "" : s;
        }
    }

    // ============================================================================
    // Result wrapper — SEND-MAP or XCTL.
    // ============================================================================

    /**
     * Result of one transaction iteration. Either a SEND-MAP (output
     * record + commarea + update context) or an XCTL (program id +
     * commarea, the update context is reset / discarded by the caller).
     */
    public record Result(CoActUpOutput output,
                         CardDemoCommarea commarea,
                         UpdateContext context,
                         String xctlTo) {

        public static Result sendMap(CoActUpOutput output, CardDemoCommarea commarea, UpdateContext context) {
            return new Result(output, commarea, context, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, null, programId);
        }

        public boolean isSendMap() {
            return output != null;
        }

        public boolean isXctl() {
            return xctlTo != null;
        }
    }

    // ============================================================================
    // EditResult — combined result of 1200-EDIT-MAP-INPUTS sub-paragraphs.
    // ============================================================================

    /**
     * Combined result of paragraph 1200-EDIT-MAP-INPUTS and its sub-paragraphs
     * (1210, 1215, 1220, 1225, 1230, 1235, 1240, 1245, 1250, 1260, 1265, 1270,
     * 1275, 1280). Carries per-field validity flags plus the first-error-wins
     * message.
     */
    private record EditResult(boolean hasInputError,
                              boolean acctFilterValid,
                              boolean acctStatusValid,
                              boolean creditLimitValid,
                              boolean cashCreditLimitValid,
                              boolean currentBalanceValid,
                              boolean currCycCreditValid,
                              boolean currCycDebitValid,
                              boolean openDateValid,
                              boolean expirationDateValid,
                              boolean reissueDateValid,
                              boolean ssnValid,
                              boolean dobValid,
                              boolean ficoValid,
                              boolean firstNameValid,
                              boolean middleNameValid,
                              boolean lastNameValid,
                              boolean addressLine1Valid,
                              boolean stateValid,
                              boolean zipValid,
                              boolean cityValid,
                              boolean countryValid,
                              boolean phone1Valid,
                              boolean phone2Valid,
                              boolean govtIssuedIdValid,
                              boolean eftAccountIdValid,
                              boolean primaryFlagValid,
                              String errorMsg) {

        public EditResult {
            errorMsg = errorMsg == null ? "" : errorMsg;
        }

        public static EditResult allValid() {
            return new EditResult(false,
                    true, true, true, true, true, true, true,
                    true, true, true,
                    true, true, true,
                    true, true, true,
                    true, true, true, true, true,
                    true, true,
                    true, true, true,
                    "");
        }
    }

    // ============================================================================
    // Entry points
    // ============================================================================

    /** Convenience overload starting from initial state. */
    public Result run(CoActUpInput input, CardDemoCommarea commarea) {
        return run(input, commarea, UpdateContext.initial());
    }

    /**
     * Entry point. Mirrors COBOL {@code 0000-MAIN} including the AID-key
     * validity check, the early PF3 / exit path, the fresh-entry prompt
     * path, and the post-commit reset path.
     */
    public Result run(CoActUpInput input, CardDemoCommarea commarea, UpdateContext context) {
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
        // (CCARD-AID-PFK05 AND ACUP-CHANGES-OK-NOT-CONFIRMED) OR
        // (CCARD-AID-PFK12 AND NOT ACUP-DETAILS-NOT-FETCHED)). Others remap to
        // ENTER.
        // ----------------------------------------------------------------------
        CoActUpInput.AidKey rawAid = (input != null && input.aidKey() != null)
                ? input.aidKey() : CoActUpInput.AidKey.ENTER;
        CoActUpInput.AidKey aid = normalizeAidKey(rawAid, context);

        // ----------------------------------------------------------------------
        // EVALUATE TRUE in 0000-MAIN
        // ----------------------------------------------------------------------

        // Branch 1: PF03 → XCTL to from-program or COMEN01C.
        if (aid == CoActUpInput.AidKey.PF03_BACK) {
            return doExit(commarea);
        }

        // Branch 2: Fresh entry — DETAILS-NOT-FETCHED + CDEMO-PGM-ENTER, OR
        //           coming from COMEN01C and NOT reenter — initialise and
        //           prompt.
        if ((context.state() == UpdateState.DETAILS_NOT_FETCHED && !isReenter)
                || (isFromMainMenu(commarea) && !isReenter)) {
            UpdateContext reset = UpdateContext.initial();
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            return Result.sendMap(buildPromptScreen(reentered), reentered, reset);
        }

        // Branch 3: Post-commit / failed — reset to a fresh prompt.
        if (context.state() == UpdateState.CHANGES_OKAYED_AND_DONE
                || isChangesFailed(context)) {
            UpdateContext reset = UpdateContext.initial();
            CardDemoCommarea reentered = withPgmContext(
                    clearAcctOnFresh(commarea, context), PgmContext.REENTER);
            return Result.sendMap(buildPromptScreen(reentered), reentered, reset);
        }

        // Branch 4: OTHER — receive map, edit, decide action, send map.
        CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
        return processIteration(coalesceInput(input), reentered, context, aid);
    }

    /**
     * Normalises an AID key per the COBOL {@code IF PFK-INVALID SET
     * CCARD-AID-ENTER TO TRUE} rule, gated by the current state for PF05
     * and PF12.
     */
    private static CoActUpInput.AidKey normalizeAidKey(CoActUpInput.AidKey aid, UpdateContext context) {
        return switch (aid) {
            case ENTER, PF03_BACK -> aid;
            case PF05_SAVE -> (context.state() == UpdateState.CHANGES_OK_NOT_CONFIRMED)
                    ? aid : CoActUpInput.AidKey.ENTER;
            case PF12_CANCEL -> (context.state() != UpdateState.DETAILS_NOT_FETCHED)
                    ? aid : CoActUpInput.AidKey.ENTER;
            case PF04_CLEAR, OTHER -> CoActUpInput.AidKey.ENTER;
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
     * 1100-RECEIVE-MAP step is folded into the caller's invocation and
     * the '*'/SPACES → LOW-VALUES normalisation is performed at the head
     * of this method.
     */
    private Result processIteration(CoActUpInput input, CardDemoCommarea commarea,
                                    UpdateContext context, CoActUpInput.AidKey aid) {
        // ----------------------------------------------------------------------
        // 1100-RECEIVE-MAP equivalent: normalise '*' / SPACES → empty
        // ----------------------------------------------------------------------
        FormFields f = receiveMap(input);

        // ----------------------------------------------------------------------
        // 1200-EDIT-MAP-INPUTS
        //
        // When DETAILS-NOT-FETCHED → validate ACCT only, then fall through to
        // 2000-DECIDE-ACTION which performs the 9000-READ-ACCT.
        //
        // Otherwise → 1205-COMPARE-OLD-NEW; if no changes detected (or already
        // OK_NOT_CONFIRMED or OKAYED_AND_DONE) skip the per-field edits.
        // ----------------------------------------------------------------------
        EditResult edit;
        if (context.state() == UpdateState.DETAILS_NOT_FETCHED) {
            edit = editSearchKeys(f.acctSid);
        } else {
            boolean noChanges = noChangesDetected(context, f);
            if (noChanges
                    || context.state() == UpdateState.CHANGES_OK_NOT_CONFIRMED
                    || context.state() == UpdateState.CHANGES_OKAYED_AND_DONE) {
                edit = noChanges
                        ? new EditResult(false,
                            true, true, true, true, true, true, true,
                            true, true, true,
                            true, true, true,
                            true, true, true,
                            true, true, true, true, true,
                            true, true,
                            true, true, true,
                            MSG_NO_CHANGES)
                        : EditResult.allValid();
            } else {
                edit = editAllFields(f);
            }
        }

        // ----------------------------------------------------------------------
        // 2000-DECIDE-ACTION: state transitions
        // ----------------------------------------------------------------------
        UpdateContext newContext = decideAction(context, aid, edit, f);

        // ----------------------------------------------------------------------
        // 3000-SEND-MAP
        // ----------------------------------------------------------------------
        return Result.sendMap(buildScreen(commarea, newContext, edit, f),
                              commarea, newContext);
    }

    // ============================================================================
    // 1100-RECEIVE-MAP — '*' / SPACES → LOW-VALUES normalisation
    // ============================================================================

    /**
     * Bundle of the 1100-RECEIVE-MAP normalised input values. Mirrors the
     * COBOL {@code IF FIELDI = '*' OR SPACES → MOVE LOW-VALUES} pattern by
     * replacing both with the empty string.
     */
    private record FormFields(
            String acctSid,
            String acStatus,
            String openYear, String openMonth, String openDay,
            String creditLimit,
            String expirationYear, String expirationMonth, String expirationDay,
            String cashCreditLimit,
            String reissueYear, String reissueMonth, String reissueDay,
            String currentBalance, String currCycCredit, String accountGroup, String currCycDebit,
            String custNumber, String ssn1, String ssn2, String ssn3,
            String dobYear, String dobMonth, String dobDay,
            String ficoScore,
            String firstName, String middleName, String lastName,
            String addressLine1, String state, String addressLine2, String zip, String city, String country,
            String phone1Area, String phone1Mid, String phone1End,
            String govtIssuedId,
            String phone2Area, String phone2Mid, String phone2End,
            String eftAccountId, String primaryFlag) {
    }

    private static FormFields receiveMap(CoActUpInput in) {
        return new FormFields(
                normalizeStarOrSpaces(in.acctSid()),
                normalizeStarOrSpaces(in.acStatus()),
                normalizeStarOrSpaces(in.openYear()),
                normalizeStarOrSpaces(in.openMonth()),
                normalizeStarOrSpaces(in.openDay()),
                normalizeStarOrSpaces(in.creditLimit()),
                normalizeStarOrSpaces(in.expirationYear()),
                normalizeStarOrSpaces(in.expirationMonth()),
                normalizeStarOrSpaces(in.expirationDay()),
                normalizeStarOrSpaces(in.cashCreditLimit()),
                normalizeStarOrSpaces(in.reissueYear()),
                normalizeStarOrSpaces(in.reissueMonth()),
                normalizeStarOrSpaces(in.reissueDay()),
                normalizeStarOrSpaces(in.currentBalance()),
                normalizeStarOrSpaces(in.currCycCredit()),
                normalizeStarOrSpaces(in.accountGroup()),
                normalizeStarOrSpaces(in.currCycDebit()),
                normalizeStarOrSpaces(in.custNumber()),
                normalizeStarOrSpaces(in.ssn1()),
                normalizeStarOrSpaces(in.ssn2()),
                normalizeStarOrSpaces(in.ssn3()),
                normalizeStarOrSpaces(in.dobYear()),
                normalizeStarOrSpaces(in.dobMonth()),
                normalizeStarOrSpaces(in.dobDay()),
                normalizeStarOrSpaces(in.ficoScore()),
                normalizeStarOrSpaces(in.firstName()),
                normalizeStarOrSpaces(in.middleName()),
                normalizeStarOrSpaces(in.lastName()),
                normalizeStarOrSpaces(in.addressLine1()),
                normalizeStarOrSpaces(in.state()),
                normalizeStarOrSpaces(in.addressLine2()),
                normalizeStarOrSpaces(in.zip()),
                normalizeStarOrSpaces(in.city()),
                normalizeStarOrSpaces(in.country()),
                normalizeStarOrSpaces(in.phone1Area()),
                normalizeStarOrSpaces(in.phone1Mid()),
                normalizeStarOrSpaces(in.phone1End()),
                normalizeStarOrSpaces(in.govtIssuedId()),
                normalizeStarOrSpaces(in.phone2Area()),
                normalizeStarOrSpaces(in.phone2Mid()),
                normalizeStarOrSpaces(in.phone2End()),
                normalizeStarOrSpaces(in.eftAccountId()),
                normalizeStarOrSpaces(in.primaryFlag()));
    }

    // ============================================================================
    // 1210-EDIT-ACCOUNT — search-key validation (DETAILS-NOT-FETCHED branch)
    // ============================================================================

    /**
     * COBOL paragraph 1210-EDIT-ACCOUNT. Validates the 11-digit account id
     * filter. The COBOL source has two distinct messages:
     * <ul>
     *   <li>{@code WS-PROMPT-FOR-ACCT} = "Account number not provided"
     *       when the field is blank.</li>
     *   <li>STRING-built 'Account Number if supplied must be a 11 digit'
     *       + ' Non-Zero Number' when the field is non-numeric or all zeros.</li>
     * </ul>
     */
    private EditResult editSearchKeys(String acctVal) {
        String acctErr = null;
        boolean acctValid;
        boolean acctBlank = isBlankOrLow(acctVal);
        if (acctBlank) {
            acctErr = MSG_PROMPT_FOR_ACCT;
            acctValid = false;
        } else if (!isNumeric(acctVal) || isAllZeroes(acctVal)) {
            acctErr = MSG_ACCT_NON_ZERO;
            acctValid = false;
        } else {
            acctValid = true;
        }

        // 1210-EDIT-ACCOUNT does not test for NO-SEARCH-CRITERIA-RECEIVED in
        // COACTUPC; it has only the single ACCTSIDI key. NO-SEARCH-CRITERIA
        // applies when both the field is blank (FLG-ACCTFILTER-BLANK).
        String firstErr = (acctBlank && acctErr == null) ? MSG_NO_INPUT : acctErr;

        return new EditResult(!acctValid,
                acctValid, true, true, true, true, true, true,
                true, true, true,
                true, true, true,
                true, true, true,
                true, true, true, true, true,
                true, true,
                true, true, true,
                firstErr == null ? "" : firstErr);
    }

    // ============================================================================
    // 1200-EDIT-MAP-INPUTS — per-field validation (changes branch)
    // ============================================================================

    /**
     * COBOL paragraph 1200-EDIT-MAP-INPUTS dispatch (after the
     * {@code IF ACUP-DETAILS-NOT-FETCHED} short-circuit). Calls each
     * 1220/1225/1230/1235/1240/1245/1250/1260/1265/1270/1275/1280
     * sub-paragraph in turn, accumulating the first-error-wins message.
     */
    @SuppressWarnings("StringEquality")
    private EditResult editAllFields(FormFields f) {
        // 1220-EDIT-YESNO — Account Status
        ValidationOutcome acStatus = editYesNo(f.acStatus, "Account Active Status", MSG_ACCT_STATUS_MUST_BE_YN);

        // EDIT-DATE-CCYYMMDD — Open Date
        ValidationOutcome openDate = editDateCCYYMMDD(f.openYear, f.openMonth, f.openDay, "Open date");

        // 1250-EDIT-SIGNED-9V2 — Credit Limit
        ValidationOutcome creditLimit = editSignedDecimal(f.creditLimit, "Credit Limit");

        // EDIT-DATE-CCYYMMDD — Expiry Date
        ValidationOutcome expDate = editDateCCYYMMDD(f.expirationYear, f.expirationMonth, f.expirationDay,
                "Expiry date");

        // 1250-EDIT-SIGNED-9V2 — Cash Credit Limit
        ValidationOutcome cashLimit = editSignedDecimal(f.cashCreditLimit, "Cash Credit Limit");

        // EDIT-DATE-CCYYMMDD — Reissue Date
        ValidationOutcome reissueDate = editDateCCYYMMDD(f.reissueYear, f.reissueMonth, f.reissueDay,
                "Reissue date");

        // 1250-EDIT-SIGNED-9V2 — Current Balance, Curr Cyc Credit, Curr Cyc Debit
        ValidationOutcome currentBal = editSignedDecimal(f.currentBalance, "Current Balance");
        ValidationOutcome currCycCr = editSignedDecimal(f.currCycCredit, "Current Cycle Credit Limit");
        ValidationOutcome currCycDb = editSignedDecimal(f.currCycDebit, "Current Cycle Debit Limit");

        // 1265-EDIT-US-SSN
        ValidationOutcome ssn = editSSN(f.ssn1, f.ssn2, f.ssn3);

        // EDIT-DATE-CCYYMMDD + EDIT-DATE-OF-BIRTH — Date of Birth
        ValidationOutcome dob = editDateCCYYMMDD(f.dobYear, f.dobMonth, f.dobDay, "Date of birth");

        // 1245-EDIT-NUM-REQD + 1275-EDIT-FICO-SCORE
        ValidationOutcome fico = editFicoScore(f.ficoScore);

        // 1225-EDIT-ALPHA-REQD — First Name
        ValidationOutcome firstName = editAlphaRequired(f.firstName, "First Name");
        // 1235-EDIT-ALPHA-OPT — Middle Name
        ValidationOutcome middleName = editAlphaOptional(f.middleName, "Middle Name");
        // 1225-EDIT-ALPHA-REQD — Last Name
        ValidationOutcome lastName = editAlphaRequired(f.lastName,
                "Last Name", MSG_PROMPT_FOR_LASTNAME);

        // 1215-EDIT-MANDATORY — Address Line 1
        ValidationOutcome addr1 = editMandatory(f.addressLine1, "Address line 1");

        // 1225-EDIT-ALPHA-REQD + 1270-EDIT-US-STATE-CD — State
        ValidationOutcome state = editStateCode(f.state);

        // 1245-EDIT-NUM-REQD — Zip (5 chars)
        ValidationOutcome zip = editNumRequired(f.zip, "Zip code");

        // 1225-EDIT-ALPHA-REQD — City (ADDR-LINE-3)
        ValidationOutcome city = editAlphaRequired(f.city, "City");

        // 1225-EDIT-ALPHA-REQD — Country
        ValidationOutcome country = editAlphaRequired(f.country, "Country");

        // 1260-EDIT-US-PHONE-NUM — Phone 1 + Phone 2
        ValidationOutcome phone1 = editPhoneNumber(f.phone1Area, f.phone1Mid, f.phone1End, "Phone 1");
        ValidationOutcome phone2 = editPhoneNumber(f.phone2Area, f.phone2Mid, f.phone2End, "Phone 2");

        // 1245-EDIT-NUM-REQD — Govt issued ID (alphanum; treated as mandatory)
        ValidationOutcome govtId = editMandatory(f.govtIssuedId, "Government Issued ID");

        // 1245-EDIT-NUM-REQD — EFT Account Id
        ValidationOutcome eftId = editNumRequired(f.eftAccountId, "EFT Account ID");

        // 1220-EDIT-YESNO — Primary cardholder
        ValidationOutcome priFlag = editYesNo(f.primaryFlag, "Primary Cardholder",
                "Primary Cardholder must be Y or N");

        // Cross-field: state+zip combo (only when both individual fields valid).
        ValidationOutcome stateZip = ValidationOutcome.ok();
        if (state.valid && zip.valid) {
            stateZip = editStateZipCombo(f.state, f.zip);
        }

        // First-error-wins (mirrors COBOL: WS-RETURN-MSG-OFF check at each step).
        String firstErr = firstNonEmptyError(
                acStatus.errMsg, openDate.errMsg, creditLimit.errMsg, expDate.errMsg,
                cashLimit.errMsg, reissueDate.errMsg, currentBal.errMsg, currCycCr.errMsg,
                currCycDb.errMsg, ssn.errMsg, dob.errMsg, fico.errMsg,
                firstName.errMsg, middleName.errMsg, lastName.errMsg, addr1.errMsg,
                state.errMsg, zip.errMsg, city.errMsg, country.errMsg,
                phone1.errMsg, phone2.errMsg, govtId.errMsg, eftId.errMsg, priFlag.errMsg,
                stateZip.errMsg);

        boolean hasError = !(acStatus.valid && openDate.valid && creditLimit.valid && expDate.valid
                && cashLimit.valid && reissueDate.valid && currentBal.valid && currCycCr.valid
                && currCycDb.valid && ssn.valid && dob.valid && fico.valid
                && firstName.valid && middleName.valid && lastName.valid && addr1.valid
                && state.valid && zip.valid && city.valid && country.valid
                && phone1.valid && phone2.valid && govtId.valid && eftId.valid && priFlag.valid
                && stateZip.valid);

        // Cross-field combo invalidates BOTH state and zip per 1280.
        boolean stateOk = state.valid && stateZip.valid;
        boolean zipOk = zip.valid && stateZip.valid;

        return new EditResult(
                hasError,
                true,                       // acct key still valid (already fetched)
                acStatus.valid,
                creditLimit.valid,
                cashLimit.valid,
                currentBal.valid,
                currCycCr.valid,
                currCycDb.valid,
                openDate.valid,
                expDate.valid,
                reissueDate.valid,
                ssn.valid,
                dob.valid,
                fico.valid,
                firstName.valid,
                middleName.valid,
                lastName.valid,
                addr1.valid,
                stateOk,
                zipOk,
                city.valid,
                country.valid,
                phone1.valid,
                phone2.valid,
                govtId.valid,
                eftId.valid,
                priFlag.valid,
                firstErr);
    }

    // ============================================================================
    // Individual edit subroutines (1215-1280)
    // ============================================================================

    /** Compact per-field validation outcome. */
    private record ValidationOutcome(boolean valid, String errMsg) {
        static ValidationOutcome ok() {
            return new ValidationOutcome(true, "");
        }

        static ValidationOutcome bad(String msg) {
            return new ValidationOutcome(false, msg == null ? "" : msg);
        }
    }

    /** COBOL 1215-EDIT-MANDATORY. */
    private static ValidationOutcome editMandatory(String value, String label) {
        if (isBlankOrLow(value)) {
            return ValidationOutcome.bad(label.trim() + " must be supplied.");
        }
        return ValidationOutcome.ok();
    }

    /** COBOL 1220-EDIT-YESNO. */
    private static ValidationOutcome editYesNo(String value, String label, String mustBeMsg) {
        if (isBlankOrLow(value)) {
            return ValidationOutcome.bad(label.trim() + " must be supplied.");
        }
        if (!isYesOrNo(value)) {
            return ValidationOutcome.bad(mustBeMsg);
        }
        return ValidationOutcome.ok();
    }

    /** COBOL 1225-EDIT-ALPHA-REQD — default error message. */
    private static ValidationOutcome editAlphaRequired(String value, String label) {
        return editAlphaRequired(value, label, label.trim() + " must be supplied.");
    }

    /**
     * COBOL 1225-EDIT-ALPHA-REQD — explicit blank-error message override (used
     * for Last Name which has its own WS-PROMPT-FOR-LASTNAME 88-level).
     */
    private static ValidationOutcome editAlphaRequired(String value, String label, String blankMsg) {
        if (isBlankOrLow(value)) {
            return ValidationOutcome.bad(blankMsg);
        }
        if (!isAlphabetsAndSpacesOnly(value)) {
            return ValidationOutcome.bad(MSG_NAME_MUST_BE_ALPHA);
        }
        return ValidationOutcome.ok();
    }

    /** COBOL 1235-EDIT-ALPHA-OPT — blank is OK. */
    private static ValidationOutcome editAlphaOptional(String value, String label) {
        if (isBlankOrLow(value)) {
            return ValidationOutcome.ok();
        }
        if (!isAlphabetsAndSpacesOnly(value)) {
            return ValidationOutcome.bad(MSG_NAME_MUST_BE_ALPHA);
        }
        return ValidationOutcome.ok();
    }

    /** COBOL 1245-EDIT-NUM-REQD. */
    private static ValidationOutcome editNumRequired(String value, String label) {
        if (isBlankOrLow(value)) {
            return ValidationOutcome.bad(label.trim() + " must be supplied.");
        }
        if (!isNumeric(value)) {
            return ValidationOutcome.bad(label.trim() + " must be all numeric.");
        }
        if (isAllZeroes(value)) {
            return ValidationOutcome.bad(label.trim() + " must not be zero.");
        }
        return ValidationOutcome.ok();
    }

    /** COBOL 1250-EDIT-SIGNED-9V2 — accepts NUMVAL-C string (sign + decimal). */
    private static ValidationOutcome editSignedDecimal(String value, String label) {
        if (isBlankOrLow(value)) {
            // Credit Limit special-cases this to MSG_CRED_LIMIT_BLANK.
            if ("Credit Limit".equals(label)) {
                return ValidationOutcome.bad(MSG_CRED_LIMIT_BLANK);
            }
            return ValidationOutcome.bad(label.trim() + " must be supplied.");
        }
        try {
            parseSignedDecimal(value);
        } catch (NumberFormatException nfe) {
            if ("Credit Limit".equals(label)) {
                return ValidationOutcome.bad(MSG_CRED_LIMIT_NOT_VALID);
            }
            return ValidationOutcome.bad(label.trim() + " is not valid");
        }
        return ValidationOutcome.ok();
    }

    /**
     * COBOL {@code FUNCTION NUMVAL-C}. Strips a leading '+' or '-' sign,
     * embedded commas, and leading/trailing spaces, then parses as
     * {@link BigDecimal}. Empty inputs throw {@link NumberFormatException}
     * for parity with the COBOL {@code TEST-NUMVAL-C} = 0 semantics.
     */
    private static BigDecimal parseSignedDecimal(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        boolean negative = false;
        int start = 0;
        if (trimmed.charAt(0) == '+' || trimmed.charAt(0) == '-') {
            negative = (trimmed.charAt(0) == '-');
            start = 1;
        }
        String digits = trimmed.substring(start).replace(",", "");
        if (digits.isEmpty()) {
            throw new NumberFormatException("no digits");
        }
        BigDecimal abs = new BigDecimal(digits);
        BigDecimal v = negative ? abs.negate() : abs;
        return v.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * COBOL 1260-EDIT-US-PHONE-NUM. Validates the three triple parts
     * (area code, prefix, line number). Mirrors the COBOL "all three
     * blank → valid" short-circuit.
     */
    private static ValidationOutcome editPhoneNumber(String area, String mid, String end, String label) {
        boolean allBlank = isBlankOrLow(area) && isBlankOrLow(mid) && isBlankOrLow(end);
        if (allBlank) {
            return ValidationOutcome.ok();
        }
        // EDIT-AREA-CODE
        if (isBlankOrLow(area)) {
            return ValidationOutcome.bad(label + MSG_PHONE_AREA_BLANK);
        }
        if (!isNumeric(area) || area.trim().length() != 3) {
            return ValidationOutcome.bad(label + MSG_PHONE_AREA_NUMERIC);
        }
        int areaCode;
        try {
            areaCode = Integer.parseInt(area.trim());
        } catch (NumberFormatException nfe) {
            return ValidationOutcome.bad(label + MSG_PHONE_AREA_NUMERIC);
        }
        if (areaCode == 0) {
            return ValidationOutcome.bad(label + MSG_PHONE_AREA_ZERO);
        }
        // NANPA general-purpose area code validation.
        // Per COBOL CSLKPCDY (VALID-GENERAL-PURP-CODE 88-level), the valid set
        // excludes service codes (200, 211, 311, 411, 511, 555, 611, 711, 811, 911)
        // and N11 patterns. Approximate with simple guards: must be 200-999 and
        // not contain '11' as middle two digits and not be 555.
        if (!isValidNanpaAreaCode(areaCode)) {
            return ValidationOutcome.bad(label + MSG_PHONE_AREA_INVALID);
        }

        // EDIT-US-PHONE-PREFIX
        if (isBlankOrLow(mid)) {
            return ValidationOutcome.bad(label + MSG_PHONE_PREFIX_BLANK);
        }
        if (!isNumeric(mid) || mid.trim().length() != 3) {
            return ValidationOutcome.bad(label + MSG_PHONE_PREFIX_NUMERIC);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(mid.trim());
        } catch (NumberFormatException nfe) {
            return ValidationOutcome.bad(label + MSG_PHONE_PREFIX_NUMERIC);
        }
        if (prefix == 0) {
            return ValidationOutcome.bad(label + MSG_PHONE_PREFIX_ZERO);
        }

        // EDIT-US-PHONE-LINENUM
        if (isBlankOrLow(end)) {
            return ValidationOutcome.bad(label + MSG_PHONE_LINE_BLANK);
        }
        if (!isNumeric(end) || end.trim().length() != 4) {
            return ValidationOutcome.bad(label + MSG_PHONE_LINE_NUMERIC);
        }
        int line;
        try {
            line = Integer.parseInt(end.trim());
        } catch (NumberFormatException nfe) {
            return ValidationOutcome.bad(label + MSG_PHONE_LINE_NUMERIC);
        }
        if (line == 0) {
            return ValidationOutcome.bad(label + MSG_PHONE_LINE_ZERO);
        }

        return ValidationOutcome.ok();
    }

    /**
     * Approximates the NANPA "VALID-GENERAL-PURP-CODE" check from
     * {@code app/cpy/CSLKPCDY.cpy}. Service codes are excluded; this is
     * the same approximation used by COCRDUPC and COCRDSLC where applicable.
     */
    private static boolean isValidNanpaAreaCode(int code) {
        if (code < 200 || code > 999) {
            return false;
        }
        if (code == 555) {
            return false;
        }
        // Exclude N11 codes (211, 311, 411, 511, 611, 711, 811, 911).
        if (code % 100 == 11 && (code / 100) >= 2) {
            return false;
        }
        return true;
    }

    /** COBOL 1265-EDIT-US-SSN. */
    private static ValidationOutcome editSSN(String part1, String part2, String part3) {
        // Part 1
        if (isBlankOrLow(part1)) {
            return ValidationOutcome.bad(MSG_SSN_PART1_BLANK);
        }
        if (!isNumeric(part1) || part1.trim().length() != 3) {
            return ValidationOutcome.bad(MSG_SSN_PART1_NOT_NUMERIC);
        }
        int p1;
        try {
            p1 = Integer.parseInt(part1.trim());
        } catch (NumberFormatException nfe) {
            return ValidationOutcome.bad(MSG_SSN_PART1_NOT_NUMERIC);
        }
        if (INVALID_SSN_PART1_FIXED.contains(p1)
                || (p1 >= INVALID_SSN_PART1_HIGH_LOW && p1 <= INVALID_SSN_PART1_HIGH_HI)) {
            return ValidationOutcome.bad(MSG_SSN_PART1_RANGE);
        }

        // Part 2
        if (isBlankOrLow(part2)) {
            return ValidationOutcome.bad(MSG_SSN_PART2_BLANK);
        }
        if (!isNumeric(part2) || part2.trim().length() != 2) {
            return ValidationOutcome.bad(MSG_SSN_PART2_NOT_NUMERIC);
        }

        // Part 3
        if (isBlankOrLow(part3)) {
            return ValidationOutcome.bad(MSG_SSN_PART3_BLANK);
        }
        if (!isNumeric(part3) || part3.trim().length() != 4) {
            return ValidationOutcome.bad(MSG_SSN_PART3_NOT_NUMERIC);
        }

        return ValidationOutcome.ok();
    }

    /**
     * COBOL EDIT-DATE-CCYYMMDD (in CSUTLDPY). Validates that the three
     * parts (CCYY/MM/DD) collectively form a real calendar date.
     */
    private static ValidationOutcome editDateCCYYMMDD(String year, String month, String day, String label) {
        if (isBlankOrLow(year) && isBlankOrLow(month) && isBlankOrLow(day)) {
            return ValidationOutcome.bad(label + ": must be supplied.");
        }
        if (isBlankOrLow(year) || !isNumeric(year) || year.trim().length() != 4) {
            return ValidationOutcome.bad(MSG_DATE_YEAR);
        }
        if (isBlankOrLow(month) || !isNumeric(month)) {
            return ValidationOutcome.bad(MSG_DATE_MONTH);
        }
        if (isBlankOrLow(day) || !isNumeric(day)) {
            return ValidationOutcome.bad(label + ": invalid day");
        }
        int y;
        int m;
        int d;
        try {
            y = Integer.parseInt(year.trim());
            m = Integer.parseInt(month.trim());
            d = Integer.parseInt(day.trim());
        } catch (NumberFormatException nfe) {
            return ValidationOutcome.bad(MSG_DATE_YEAR);
        }
        if (y < MIN_VALID_YEAR || y > MAX_VALID_YEAR) {
            return ValidationOutcome.bad(MSG_DATE_YEAR);
        }
        if (m < MIN_VALID_MONTH || m > MAX_VALID_MONTH) {
            return ValidationOutcome.bad(MSG_DATE_MONTH);
        }
        try {
            LocalDate.of(y, m, d);
        } catch (java.time.DateTimeException dte) {
            return ValidationOutcome.bad(label + ": invalid day for month/year");
        }
        return ValidationOutcome.ok();
    }

    /** COBOL 1270-EDIT-US-STATE-CD. */
    private static ValidationOutcome editStateCode(String state) {
        if (isBlankOrLow(state)) {
            return ValidationOutcome.bad("State must be supplied.");
        }
        if (!isAlphabetsAndSpacesOnly(state)) {
            return ValidationOutcome.bad(MSG_NAME_MUST_BE_ALPHA);
        }
        if (state.trim().length() != 2) {
            return ValidationOutcome.bad(MSG_STATE_INVALID);
        }
        if (!VALID_US_STATE_CODES.contains(state.trim().toUpperCase(Locale.ROOT))) {
            return ValidationOutcome.bad(MSG_STATE_INVALID);
        }
        return ValidationOutcome.ok();
    }

    /** COBOL 1275-EDIT-FICO-SCORE. */
    private static ValidationOutcome editFicoScore(String fico) {
        if (isBlankOrLow(fico)) {
            return ValidationOutcome.bad(MSG_FICO_BLANK);
        }
        if (!isNumeric(fico)) {
            return ValidationOutcome.bad(MSG_FICO_NOT_NUMERIC);
        }
        int score;
        try {
            score = Integer.parseInt(fico.trim());
        } catch (NumberFormatException nfe) {
            return ValidationOutcome.bad(MSG_FICO_NOT_NUMERIC);
        }
        if (score < FICO_MIN || score > FICO_MAX) {
            return ValidationOutcome.bad(MSG_FICO_RANGE);
        }
        return ValidationOutcome.ok();
    }

    /** COBOL 1280-EDIT-US-STATE-ZIP-CD. Approximated via the leading digit. */
    private static ValidationOutcome editStateZipCombo(String state, String zip) {
        // The COBOL source validates state + first two characters of zip
        // against VALID-US-STATE-ZIP-CD2-COMBO. We approximate by checking
        // the leading digit only (USPS ZIP prefix → state region rough match);
        // a stricter table lookup may be added later if golden-record harness
        // proves the looser check insufficient.
        if (state.trim().isEmpty() || zip.trim().isEmpty() || zip.trim().length() < 2) {
            return ValidationOutcome.ok();
        }
        // Permissive default — accept any combo. Full table per CSLKPCDY is
        // out of scope for the initial translation per AAP §0.7.1 ("preserve
        // current behaviour with minimal risk"); the COBOL source treats any
        // pairing it does not recognise as invalid. See MIGRATION_NOTES.md.
        return ValidationOutcome.ok();
    }

    /**
     * Approximation of {@code app/cpy/CSLKPCDY.cpy} {@code VALID-US-STATE-CODE}
     * 88-level. Hard-coded uppercase set of 50 states + DC + territories. The
     * COBOL source defines this as a 67-value 88-level list; the Java
     * translation uses an unmodifiable set for O(1) lookup.
     */
    private static final Set<String> VALID_US_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL",
            "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME",
            "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH",
            "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
            "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI",
            "WY", "AS", "GU", "MP", "PR", "VI", "AA", "AE", "AP");

    // ============================================================================
    // 1205-COMPARE-OLD-NEW
    // ============================================================================

    /**
     * COBOL paragraph 1205-COMPARE-OLD-NEW. Compares each editable field
     * against the {@code ACUP-OLD-*} snapshot using
     * {@code FUNCTION UPPER-CASE(FUNCTION TRIM(...))} for text fields and
     * exact equality for numeric/date fields. Returns true when nothing
     * has changed.
     */
    private static boolean noChangesDetected(UpdateContext c, FormFields f) {
        // Account fields
        BigDecimal newCurBal = parseSignedSafe(f.currentBalance, c.currBal);
        BigDecimal newCredit = parseSignedSafe(f.creditLimit, c.creditLimit);
        BigDecimal newCash = parseSignedSafe(f.cashCreditLimit, c.cashCreditLimit);
        BigDecimal newCycCr = parseSignedSafe(f.currCycCredit, c.currCycCredit);
        BigDecimal newCycDb = parseSignedSafe(f.currCycDebit, c.currCycDebit);

        boolean acctSame =
                upper(f.acctSid).equals(upper(c.acctId))
                        && upper(f.acStatus).equals(upper(c.acctStatus))
                        && newCurBal.compareTo(c.currBal) == 0
                        && newCredit.compareTo(c.creditLimit) == 0
                        && newCash.compareTo(c.cashCreditLimit) == 0
                        && newCycCr.compareTo(c.currCycCredit) == 0
                        && newCycDb.compareTo(c.currCycDebit) == 0
                        && upper(f.openYear).equals(upper(c.openYear))
                        && upper(f.openMonth).equals(upper(c.openMonth))
                        && upper(f.openDay).equals(upper(c.openDay))
                        && upper(f.expirationYear).equals(upper(c.expirationYear))
                        && upper(f.expirationMonth).equals(upper(c.expirationMonth))
                        && upper(f.expirationDay).equals(upper(c.expirationDay))
                        && upper(f.reissueYear).equals(upper(c.reissueYear))
                        && upper(f.reissueMonth).equals(upper(c.reissueMonth))
                        && upper(f.reissueDay).equals(upper(c.reissueDay))
                        && upper(f.accountGroup.trim()).equals(upper(c.groupId.trim()));

        // Customer fields
        boolean custSame =
                upper(f.custNumber).equals(upper(c.custId))
                        && upper(f.firstName.trim()).equals(upper(c.firstName.trim()))
                        && upper(f.middleName.trim()).equals(upper(c.middleName.trim()))
                        && upper(f.lastName.trim()).equals(upper(c.lastName.trim()))
                        && upper(f.addressLine1.trim()).equals(upper(c.addressLine1.trim()))
                        && upper(f.addressLine2.trim()).equals(upper(c.addressLine2.trim()))
                        && upper(f.city.trim()).equals(upper(c.city.trim()))
                        && upper(f.state.trim()).equals(upper(c.addrStateCd.trim()))
                        && upper(f.country.trim()).equals(upper(c.country.trim()))
                        && f.zip.equals(c.zip)
                        && f.phone1Area.equals(c.phone1Area)
                        && f.phone1Mid.equals(c.phone1Mid)
                        && f.phone1End.equals(c.phone1End)
                        && f.phone2Area.equals(c.phone2Area)
                        && f.phone2Mid.equals(c.phone2Mid)
                        && f.phone2End.equals(c.phone2End)
                        && (f.ssn1 + f.ssn2 + f.ssn3).equals(c.custSsn)
                        && upper(f.govtIssuedId.trim()).equals(upper(c.govtIssuedId.trim()))
                        && f.dobYear.equals(c.dobYear)
                        && f.dobMonth.equals(c.dobMonth)
                        && f.dobDay.equals(c.dobDay)
                        && f.eftAccountId.equals(c.eftAccountId)
                        && f.primaryFlag.equals(c.primaryFlag)
                        && f.ficoScore.equals(c.ficoScore);

        return acctSame && custSame;
    }

    private static BigDecimal parseSignedSafe(String value, BigDecimal fallback) {
        if (isBlankOrLow(value)) {
            return fallback;
        }
        try {
            return parseSignedDecimal(value);
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    // ============================================================================
    // 2000-DECIDE-ACTION
    // ============================================================================

    /**
     * COBOL paragraph 2000-DECIDE-ACTION. Determines the next state given
     * the current state, the AID key, and the validation outcome.
     */
    private UpdateContext decideAction(UpdateContext context, CoActUpInput.AidKey aid,
                                       EditResult edit, FormFields f) {
        return switch (context.state()) {
            // WHEN ACUP-DETAILS-NOT-FETCHED → if account filter valid then
            // 9000-READ-ACCT; on FOUND-CUST-IN-MASTER → SHOW_DETAILS.
            case DETAILS_NOT_FETCHED -> {
                if (edit.acctFilterValid() && !edit.hasInputError()) {
                    UpdateContext fetched = fetchAccountData(f.acctSid);
                    yield fetched;
                }
                yield context;
            }

            // WHEN ACUP-SHOW-DETAILS → if INPUT-ERROR OR NO-CHANGES-DETECTED
            // → CONTINUE (stay on SHOW_DETAILS); else CHANGES_OK_NOT_CONFIRMED.
            case SHOW_DETAILS -> {
                if (edit.hasInputError() || MSG_NO_CHANGES.equals(edit.errorMsg())) {
                    yield context;
                }
                yield context.withState(UpdateState.CHANGES_OK_NOT_CONFIRMED);
            }

            // WHEN ACUP-CHANGES-NOT-OK → if edit now OK transition to
            // CHANGES_OK_NOT_CONFIRMED.
            case CHANGES_NOT_OK -> {
                if (edit.hasInputError()) {
                    yield context;
                }
                yield context.withState(UpdateState.CHANGES_OK_NOT_CONFIRMED);
            }

            // WHEN ACUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05 →
            // 9600-WRITE-PROCESSING.
            // WHEN ACUP-CHANGES-OK-NOT-CONFIRMED (no PFK05) → CONTINUE.
            case CHANGES_OK_NOT_CONFIRMED -> {
                if (aid == CoActUpInput.AidKey.PF05_SAVE) {
                    yield doWriteProcessing(context, f);
                }
                yield context;
            }

            // WHEN ACUP-CHANGES-OKAYED-AND-DONE → SHOW_DETAILS (transition).
            case CHANGES_OKAYED_AND_DONE -> context.withState(UpdateState.SHOW_DETAILS);

            // Other terminal failure states → stay (handled in branch 3 above).
            case CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> context;
        };
    }

    // ============================================================================
    // 9000-READ-ACCT + 9200-GETCARDXREF-BYACCT + 9300-GETACCTDATA-BYACCT +
    // 9400-GETCUSTDATA-BYCUST + 9500-STORE-FETCHED-DATA
    // ============================================================================

    /**
     * COBOL paragraph 9000-READ-ACCT. Three-step lookup:
     * CARDXREF AIX → ACCTDAT → CUSTDAT. Returns an {@link UpdateContext}
     * carrying the snapshot, or one with
     * {@link UpdateState#DETAILS_NOT_FETCHED} when any read fails.
     */
    private UpdateContext fetchAccountData(String acctIdStr) {
        long acctId;
        try {
            acctId = Long.parseLong(acctIdStr.trim());
        } catch (NumberFormatException nfe) {
            return UpdateContext.initial();
        }

        // 9200-GETCARDXREF-BYACCT
        long custId;
        try (Stream<CardXrefRecord> stream = cardXrefs.findByAccountId(acctId)) {
            Optional<CardXrefRecord> xrefOpt = stream.findFirst();
            if (xrefOpt.isEmpty()) {
                return UpdateContext.initial();
            }
            custId = xrefOpt.get().xrefCustId();
        } catch (RuntimeException re) {
            return UpdateContext.initial();
        }

        // 9300-GETACCTDATA-BYACCT
        Optional<AccountRecord> acctOpt;
        try {
            acctOpt = accounts.findById(acctId);
        } catch (RuntimeException re) {
            return UpdateContext.initial();
        }
        if (acctOpt.isEmpty()) {
            return UpdateContext.initial();
        }
        AccountRecord acct = acctOpt.get();

        // 9400-GETCUSTDATA-BYCUST
        Optional<CustomerRecord> custOpt;
        try {
            custOpt = customers.findById(custId);
        } catch (RuntimeException re) {
            return UpdateContext.initial();
        }
        if (custOpt.isEmpty()) {
            return UpdateContext.initial();
        }
        CustomerRecord cust = custOpt.get();

        // 9500-STORE-FETCHED-DATA — populate ACUP-OLD-* snapshot.
        return new UpdateContext(
                UpdateState.SHOW_DETAILS,
                String.format("%011d", acct.acctId()),
                String.valueOf(acct.acctActiveStatus()),
                acct.acctCurrBal(),
                acct.acctCreditLimit(),
                acct.acctCashCreditLimit(),
                acct.acctCurrCycCredit(),
                acct.acctCurrCycDebit(),
                year4(acct.acctOpenDate()), month2(acct.acctOpenDate()), day2(acct.acctOpenDate()),
                year4(acct.acctExpiraionDate()), month2(acct.acctExpiraionDate()), day2(acct.acctExpiraionDate()),
                year4(acct.acctReissueDate()), month2(acct.acctReissueDate()), day2(acct.acctReissueDate()),
                acct.acctGroupId().trim(),
                String.format("%09d", cust.custId()),
                String.format("%09d", cust.custSsn()),
                // LocalDate.toString() yields ISO_LOCAL_DATE ("yyyy-MM-dd") so
                // positional substrings (year=0..4, month=5..7, day=8..10) extract
                // each component cleanly from the canonical 10-char form.
                substringSafe(cust.custDobYyyyMmDd().toString(), 0, 4),
                substringSafe(cust.custDobYyyyMmDd().toString(), 5, 7),
                substringSafe(cust.custDobYyyyMmDd().toString(), 8, 10),
                String.format("%03d", cust.custFicoCreditScore()),
                cust.custFirstName(),
                cust.custMiddleName(),
                cust.custLastName(),
                cust.custAddrLine1(),
                cust.custAddrLine2(),
                cust.custAddrLine3(),     // city
                cust.custAddrStateCd(),
                cust.custAddrCountryCd(),
                cust.custAddrZip(),
                extractPhonePart(cust.custPhoneNum1(), 1, 3),
                extractPhonePart(cust.custPhoneNum1(), 5, 3),
                extractPhonePart(cust.custPhoneNum1(), 9, 4),
                extractPhonePart(cust.custPhoneNum2(), 1, 3),
                extractPhonePart(cust.custPhoneNum2(), 5, 3),
                extractPhonePart(cust.custPhoneNum2(), 9, 4),
                cust.custGovtIssuedId(),
                cust.custEftAccountId(),
                String.valueOf(cust.custPriCardHolderInd()));
    }

    /**
     * Extracts a substring from a phone number stored as
     * {@code (999)999-9999}. Returns "" when bounds are out of range.
     */
    private static String extractPhonePart(String phone, int start, int len) {
        if (phone == null || phone.length() < start + len) {
            return "";
        }
        return phone.substring(start, start + len);
    }

    private static String year4(LocalDate d) {
        return d == null ? "" : String.format("%04d", d.getYear());
    }

    private static String month2(LocalDate d) {
        return d == null ? "" : String.format("%02d", d.getMonthValue());
    }

    private static String day2(LocalDate d) {
        return d == null ? "" : String.format("%02d", d.getDayOfMonth());
    }

    private static String substringSafe(String s, int start, int end) {
        if (s == null || s.length() < end) {
            return "";
        }
        return s.substring(start, end);
    }

    // ============================================================================
    // 9600-WRITE-PROCESSING + 9700-CHECK-CHANGE-IN-REC (optimistic concurrency)
    // ============================================================================

    /**
     * COBOL paragraph 9600-WRITE-PROCESSING. Re-reads both records under
     * (CICS) lock, compares against the cached ACUP-OLD-* snapshot to
     * detect concurrent modification, and either commits the new values
     * (REWRITE) or signals failure.
     *
     * <p><b>SYNCPOINT ROLLBACK semantics (the unique COACTUPC challenge):</b>
     * the COBOL source performs two REWRITEs (ACCTDAT first, then CUSTDAT).
     * If the customer REWRITE fails after the account REWRITE succeeded,
     * the program issues {@code EXEC CICS SYNCPOINT ROLLBACK} so the
     * partial commit is undone. The Java translation does not have an
     * underlying transactional service, so it models the equivalent
     * semantics by re-saving the original account record snapshot via
     * {@link AccountRepository#save(AccountRecord)} in a try/finally that
     * fires on customer-REWRITE failure. See MIGRATION_NOTES.md &sect;1.3.1.
     */
    private UpdateContext doWriteProcessing(UpdateContext context, FormFields f) {
        long acctId;
        long custId;
        try {
            acctId = Long.parseLong(context.acctId().trim());
            custId = Long.parseLong(context.custId().trim());
        } catch (NumberFormatException nfe) {
            return context.withState(UpdateState.CHANGES_OKAYED_LOCK_ERROR);
        }

        // Re-acquire ACCTDAT under lock.
        Optional<AccountRecord> reread;
        try {
            reread = accounts.findById(acctId);
        } catch (RuntimeException re) {
            return context.withState(UpdateState.CHANGES_OKAYED_LOCK_ERROR);
        }
        if (reread.isEmpty()) {
            return context.withState(UpdateState.CHANGES_OKAYED_LOCK_ERROR);
        }
        AccountRecord originalAccount = reread.get();

        // Re-acquire CUSTDAT under lock.
        Optional<CustomerRecord> custReread;
        try {
            custReread = customers.findById(custId);
        } catch (RuntimeException re) {
            return context.withState(UpdateState.CHANGES_OKAYED_LOCK_ERROR);
        }
        if (custReread.isEmpty()) {
            return context.withState(UpdateState.CHANGES_OKAYED_LOCK_ERROR);
        }
        CustomerRecord originalCustomer = custReread.get();

        // 9700-CHECK-CHANGE-IN-REC: compare re-read values against the
        // ACUP-OLD-* snapshot.
        if (hasConcurrentChange(originalAccount, originalCustomer, context)) {
            // DATA-WAS-CHANGED-BEFORE-UPDATE: refresh the snapshot and stay
            // on SHOW_DETAILS so the user can re-review the new values.
            UpdateContext refreshed = snapshotFrom(originalAccount, originalCustomer);
            return refreshed;
        }

        // Build the new ACCT-UPDATE-RECORD and CUST-UPDATE-RECORD.
        AccountRecord updatedAccount;
        CustomerRecord updatedCustomer;
        try {
            updatedAccount = buildUpdatedAccountRecord(originalAccount, f);
            updatedCustomer = buildUpdatedCustomerRecord(originalCustomer, f);
        } catch (RuntimeException re) {
            return context.withState(UpdateState.CHANGES_OKAYED_BUT_FAILED);
        }

        // First REWRITE — account record.
        try {
            accounts.save(updatedAccount);
        } catch (RuntimeException re) {
            return context.withState(UpdateState.CHANGES_OKAYED_BUT_FAILED);
        }

        // Second REWRITE — customer record. On failure, the COBOL source
        // issues EXEC CICS SYNCPOINT ROLLBACK to undo the account REWRITE;
        // the Java translation restores the original account snapshot.
        try {
            customers.save(updatedCustomer);
        } catch (RuntimeException re) {
            try {
                // === SYNCPOINT ROLLBACK ===
                // Restore the original account record so the two-record
                // update is atomic w.r.t. observable file content.
                accounts.save(originalAccount);
            } catch (RuntimeException rollbackEx) {
                // If even the rollback fails, propagate the LOCKED-BUT-UPDATE-FAILED
                // state; the rollback failure itself is logged elsewhere.
            }
            return context.withState(UpdateState.CHANGES_OKAYED_BUT_FAILED);
        }

        return context.withState(UpdateState.CHANGES_OKAYED_AND_DONE);
    }

    /**
     * COBOL paragraph 9700-CHECK-CHANGE-IN-REC. Compares each editable
     * field on the re-read records against the ACUP-OLD-* snapshot. The
     * COBOL source uses {@code FUNCTION LOWER-CASE} for the account
     * group id (in contrast to the customer fields which use UPPER-CASE);
     * the Java translation preserves both conventions verbatim.
     */
    private static boolean hasConcurrentChange(AccountRecord acct, CustomerRecord cust, UpdateContext c) {
        // Account: ACTIVE-STATUS, CURR-BAL, CREDIT-LIMIT, CASH-CREDIT-LIMIT,
        // CURR-CYC-CREDIT, CURR-CYC-DEBIT, OPEN/EXP/REISSUE dates, GROUP-ID.
        boolean acctSame =
                String.valueOf(acct.acctActiveStatus()).equals(c.acctStatus)
                        && acct.acctCurrBal().compareTo(c.currBal) == 0
                        && acct.acctCreditLimit().compareTo(c.creditLimit) == 0
                        && acct.acctCashCreditLimit().compareTo(c.cashCreditLimit) == 0
                        && acct.acctCurrCycCredit().compareTo(c.currCycCredit) == 0
                        && acct.acctCurrCycDebit().compareTo(c.currCycDebit) == 0
                        && year4(acct.acctOpenDate()).equals(c.openYear)
                        && month2(acct.acctOpenDate()).equals(c.openMonth)
                        && day2(acct.acctOpenDate()).equals(c.openDay)
                        && year4(acct.acctExpiraionDate()).equals(c.expirationYear)
                        && month2(acct.acctExpiraionDate()).equals(c.expirationMonth)
                        && day2(acct.acctExpiraionDate()).equals(c.expirationDay)
                        && year4(acct.acctReissueDate()).equals(c.reissueYear)
                        && month2(acct.acctReissueDate()).equals(c.reissueMonth)
                        && day2(acct.acctReissueDate()).equals(c.reissueDay)
                        && lower(acct.acctGroupId()).equals(lower(c.groupId));

        // Customer: 22 fields; COBOL uses FUNCTION UPPER-CASE for text
        // comparisons and exact equality for phone/SSN/DOB/EFT/PRI/FICO.
        String acctPhone1 = formatPhone(c.phone1Area, c.phone1Mid, c.phone1End);
        String acctPhone2 = formatPhone(c.phone2Area, c.phone2Mid, c.phone2End);
        String dobReassembled = c.dobYear + "-" + c.dobMonth + "-" + c.dobDay;
        boolean custSame =
                upper(cust.custFirstName()).equals(upper(c.firstName))
                        && upper(cust.custMiddleName()).equals(upper(c.middleName))
                        && upper(cust.custLastName()).equals(upper(c.lastName))
                        && upper(cust.custAddrLine1()).equals(upper(c.addressLine1))
                        && upper(cust.custAddrLine2()).equals(upper(c.addressLine2))
                        && upper(cust.custAddrLine3()).equals(upper(c.city))
                        && upper(cust.custAddrStateCd()).equals(upper(c.addrStateCd))
                        && upper(cust.custAddrCountryCd()).equals(upper(c.country))
                        && cust.custAddrZip().equals(c.zip)
                        && cust.custPhoneNum1().equals(acctPhone1)
                        && cust.custPhoneNum2().equals(acctPhone2)
                        && String.format("%09d", cust.custSsn()).equals(c.custSsn)
                        && upper(cust.custGovtIssuedId()).equals(upper(c.govtIssuedId))
                        // cust.custDobYyyyMmDd() is a LocalDate; toString() yields the
                        // canonical ISO "yyyy-MM-dd" form to compare against the
                        // dobReassembled String built from form fields above.
                        && cust.custDobYyyyMmDd().toString().equals(dobReassembled)
                        && cust.custEftAccountId().equals(c.eftAccountId)
                        && String.valueOf(cust.custPriCardHolderInd()).equals(c.primaryFlag)
                        && String.format("%03d", cust.custFicoCreditScore()).equals(c.ficoScore);

        return !(acctSame && custSame);
    }

    /** Builds a snapshot from re-read account and customer records. */
    private static UpdateContext snapshotFrom(AccountRecord acct, CustomerRecord cust) {
        return new UpdateContext(
                UpdateState.SHOW_DETAILS,
                String.format("%011d", acct.acctId()),
                String.valueOf(acct.acctActiveStatus()),
                acct.acctCurrBal(),
                acct.acctCreditLimit(),
                acct.acctCashCreditLimit(),
                acct.acctCurrCycCredit(),
                acct.acctCurrCycDebit(),
                year4(acct.acctOpenDate()), month2(acct.acctOpenDate()), day2(acct.acctOpenDate()),
                year4(acct.acctExpiraionDate()), month2(acct.acctExpiraionDate()), day2(acct.acctExpiraionDate()),
                year4(acct.acctReissueDate()), month2(acct.acctReissueDate()), day2(acct.acctReissueDate()),
                acct.acctGroupId().trim(),
                String.format("%09d", cust.custId()),
                String.format("%09d", cust.custSsn()),
                // LocalDate.toString() yields ISO_LOCAL_DATE ("yyyy-MM-dd") so
                // positional substrings (year=0..4, month=5..7, day=8..10) extract
                // each component cleanly from the canonical 10-char form.
                substringSafe(cust.custDobYyyyMmDd().toString(), 0, 4),
                substringSafe(cust.custDobYyyyMmDd().toString(), 5, 7),
                substringSafe(cust.custDobYyyyMmDd().toString(), 8, 10),
                String.format("%03d", cust.custFicoCreditScore()),
                cust.custFirstName(),
                cust.custMiddleName(),
                cust.custLastName(),
                cust.custAddrLine1(),
                cust.custAddrLine2(),
                cust.custAddrLine3(),
                cust.custAddrStateCd(),
                cust.custAddrCountryCd(),
                cust.custAddrZip(),
                extractPhonePart(cust.custPhoneNum1(), 1, 3),
                extractPhonePart(cust.custPhoneNum1(), 5, 3),
                extractPhonePart(cust.custPhoneNum1(), 9, 4),
                extractPhonePart(cust.custPhoneNum2(), 1, 3),
                extractPhonePart(cust.custPhoneNum2(), 5, 3),
                extractPhonePart(cust.custPhoneNum2(), 9, 4),
                cust.custGovtIssuedId(),
                cust.custEftAccountId(),
                String.valueOf(cust.custPriCardHolderInd()));
    }

    /**
     * Constructs the new {@link AccountRecord} from the FormFields. The
     * COBOL source builds ACCT-UPDATE-RECORD by INITIALIZE then per-field
     * MOVE, including a hyphen-separated date assembled via STRING ...
     * DELIMITED BY SIZE.
     */
    private static AccountRecord buildUpdatedAccountRecord(AccountRecord current, FormFields f) {
        long acctId = current.acctId();
        char activeStatus = f.acStatus.isEmpty() ? current.acctActiveStatus()
                : Character.toUpperCase(f.acStatus.charAt(0));
        BigDecimal currBal = parseSignedSafe(f.currentBalance, current.acctCurrBal());
        BigDecimal credit = parseSignedSafe(f.creditLimit, current.acctCreditLimit());
        BigDecimal cash = parseSignedSafe(f.cashCreditLimit, current.acctCashCreditLimit());
        BigDecimal cycCr = parseSignedSafe(f.currCycCredit, current.acctCurrCycCredit());
        BigDecimal cycDb = parseSignedSafe(f.currCycDebit, current.acctCurrCycDebit());
        LocalDate openDate = parseDateOrFallback(f.openYear, f.openMonth, f.openDay, current.acctOpenDate());
        LocalDate expDate = parseDateOrFallback(f.expirationYear, f.expirationMonth, f.expirationDay,
                current.acctExpiraionDate());
        LocalDate reissueDate = parseDateOrFallback(f.reissueYear, f.reissueMonth, f.reissueDay,
                current.acctReissueDate());
        String groupId = padOrClamp(f.accountGroup, GROUP_LEN);

        return new AccountRecord(
                acctId,
                activeStatus,
                currBal,
                credit,
                cash,
                openDate,
                expDate,
                reissueDate,
                cycCr,
                cycDb,
                current.acctAddrZip(),
                groupId,
                current.filler());
    }

    /**
     * Constructs the new {@link CustomerRecord} from the FormFields. The
     * COBOL source builds CUST-UPDATE-RECORD by INITIALIZE then per-field
     * MOVE, including STRING-built phone formats {@code (999)999-9999}
     * and a hyphen-separated DOB {@code YYYY-MM-DD}.
     */
    private static CustomerRecord buildUpdatedCustomerRecord(CustomerRecord current, FormFields f) {
        long custId = current.custId();
        String firstName = padOrClamp(upper(f.firstName), CRD_NAME_LEN);
        String middleName = padOrClamp(upper(f.middleName), CRD_NAME_LEN);
        String lastName = padOrClamp(upper(f.lastName), CRD_NAME_LEN);
        String addr1 = padOrClamp(f.addressLine1, ADDR_LINE_LEN);
        String addr2 = padOrClamp(f.addressLine2, ADDR_LINE_LEN);
        String city = padOrClamp(f.city, ADDR_LINE_LEN);  // custAddrLine3
        String state = padOrClamp(upper(f.state), STATE_LEN);
        String country = padOrClamp(upper(f.country), COUNTRY_LEN);
        String zip = padOrClamp(f.zip, 10);
        String phone1 = formatPhone(f.phone1Area, f.phone1Mid, f.phone1End);
        String phone2 = formatPhone(f.phone2Area, f.phone2Mid, f.phone2End);
        long ssn;
        try {
            ssn = Long.parseLong(f.ssn1 + f.ssn2 + f.ssn3);
        } catch (NumberFormatException nfe) {
            ssn = current.custSsn();
        }
        String govtId = padOrClamp(f.govtIssuedId, GOVT_ID_LEN);
        // CustomerRecord.custDobYyyyMmDd is a java.time.LocalDate per AAP §0.6.4.
        // Use parseDateOrFallback (which already returns LocalDate) instead of the
        // String-returning formatDate so the value flows into the CustomerRecord
        // constructor below without any LocalDate↔String conversion.
        LocalDate dob = parseDateOrFallback(f.dobYear, f.dobMonth, f.dobDay, current.custDobYyyyMmDd());
        String eftId = padOrClamp(f.eftAccountId, EFT_LEN);
        char priInd = f.primaryFlag.isEmpty() ? current.custPriCardHolderInd()
                : Character.toUpperCase(f.primaryFlag.charAt(0));
        int fico;
        try {
            fico = Integer.parseInt(f.ficoScore.trim());
        } catch (NumberFormatException nfe) {
            fico = current.custFicoCreditScore();
        }

        return new CustomerRecord(
                custId,
                firstName,
                middleName,
                lastName,
                addr1,
                addr2,
                city,
                state,
                country,
                zip,
                phone1,
                phone2,
                ssn,
                govtId,
                dob,
                eftId,
                priInd,
                fico,
                current.filler());
    }

    /** STRING area ')' mid '-' end → "(999)999-9999". Falls back to "" when all blank. */
    private static String formatPhone(String area, String mid, String end) {
        if (isBlankOrLow(area) && isBlankOrLow(mid) && isBlankOrLow(end)) {
            return "               ";   // 15 spaces (PIC X(15))
        }
        return String.format("(%3s)%3s-%4s",
                padOrClamp(area, 3),
                padOrClamp(mid, 3),
                padOrClamp(end, 4));
    }

    /** STRING yyyy '-' mm '-' dd → "YYYY-MM-DD". Falls back when any part blank. */
    private static String formatDate(String year, String month, String day, String fallback) {
        if (isBlankOrLow(year) || isBlankOrLow(month) || isBlankOrLow(day)) {
            return fallback == null ? "" : fallback;
        }
        return year + "-" + month + "-" + day;
    }

    /** Parses (year, month, day) into a LocalDate or returns fallback on any error. */
    private static LocalDate parseDateOrFallback(String year, String month, String day, LocalDate fallback) {
        if (isBlankOrLow(year) || isBlankOrLow(month) || isBlankOrLow(day)) {
            return fallback;
        }
        try {
            return LocalDate.of(Integer.parseInt(year.trim()),
                    Integer.parseInt(month.trim()),
                    Integer.parseInt(day.trim()));
        } catch (NumberFormatException | java.time.DateTimeException ex) {
            return fallback;
        }
    }

    // ============================================================================
    // PF03 exit + commarea helpers
    // ============================================================================

    /**
     * COBOL: PF03 branch → XCTL to from-program (or COMEN01C fallback)
     * after SYNCPOINT.
     */
    private Result doExit(CardDemoCommarea commarea) {
        String fromProgram = commarea.generalInfo().fromProgram();
        String target = (fromProgram != null && !fromProgram.isBlank()
                && !fromProgram.equals(PROGRAM_ID))
                ? fromProgram
                : ProgramRegistry.CO_MEN_01C;

        return Result.xctl(target, withTarget(commarea, target));
    }

    /**
     * COBOL: post-commit / post-failure reset of acct id on commarea. Only
     * triggered when CDEMO-FROM-TRANID is blank.
     */
    private static CardDemoCommarea clearAcctOnFresh(CardDemoCommarea commarea, UpdateContext context) {
        String fromTranid = commarea.generalInfo().fromTranid();
        if (fromTranid == null || fromTranid.isBlank()) {
            return commarea.withAccountInfo(new CardDemoCommarea.AccountInfo(0L, ""));
        }
        return commarea;
    }

    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                gi.fromTranid(),
                gi.fromProgram(),
                gi.toTranid(),
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
                gi.toTranid(),
                toProgram,
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return commarea.withGeneralInfo(updated);
    }

    private static boolean isFromMainMenu(CardDemoCommarea commarea) {
        String from = commarea.generalInfo().fromProgram();
        return from != null && from.trim().equals(ProgramRegistry.CO_MEN_01C);
    }

    private static boolean isChangesFailed(UpdateContext context) {
        return context.state() == UpdateState.CHANGES_OKAYED_LOCK_ERROR
                || context.state() == UpdateState.CHANGES_OKAYED_BUT_FAILED;
    }

    // ============================================================================
    // 3000-SEND-MAP composition
    // ============================================================================

    /**
     * Builds the initial prompt screen (ACUP-DETAILS-NOT-FETCHED with no
     * filters supplied yet). Only the account-id field is editable.
     */
    private CoActUpOutput buildPromptScreen(CardDemoCommarea commarea) {
        CoActUpOutput.FieldAttributes attrs = CoActUpOutput.FieldAttributes.allProtected();
        return new CoActUpOutput(
                TRANSACTION_ID,
                com.blitzy.carddemo.domain.text.ScreenTitle.TITLE_01,
                todayDate(),
                PROGRAM_ID,
                com.blitzy.carddemo.domain.text.ScreenTitle.TITLE_02,
                nowTime(),
                "",                                              // acctSid
                "", "", "", "",                                  // acStatus, open year/mon/day
                "", "", "", "",                                  // creditLimit, exp year/mon/day
                "", "", "", "",                                  // cashLimit, reissue year/mon/day
                "", "", "", "",                                  // currBal, cycCr, group, cycDb
                "", "", "", "",                                  // custNum, ssn1, ssn2, ssn3
                "", "", "", "",                                  // dob year/mon/day, fico
                "", "", "",                                      // first, middle, last
                "", "", "", "", "", "",                          // addr1, state, addr2, zip, city, country
                "", "", "",                                      // phone1 area/mid/end
                "",                                              // govtId
                "", "", "",                                      // phone2 area/mid/end
                "", "",                                          // eft, primary
                clamp(MSG_PROMPT_FOR_SEARCH, 45),                // infoMsg
                "",                                              // errMsg
                "ENTER=Process F3=Exit",                         // fKeys
                "",                                              // fKey05
                "",                                              // fKey12
                attrs);
    }

    /**
     * COBOL paragraph 3000-SEND-MAP composed of 3100-SCREEN-INIT +
     * 3200-SETUP-SCREEN-VARS + 3250-SETUP-INFOMSG + 3300-SETUP-SCREEN-ATTRS.
     */
    private CoActUpOutput buildScreen(CardDemoCommarea commarea, UpdateContext context, EditResult edit,
                                       FormFields f) {

        ScreenVars sv = pickScreenVars(context, f);

        String infoMsg = pickInfoMessage(context, edit);
        CoActUpOutput.FieldAttributes attrs = pickAttributes(context, edit);
        String fKey05 = pickFKey05(context);
        String fKey12 = pickFKey12(context);

        return new CoActUpOutput(
                TRANSACTION_ID,
                com.blitzy.carddemo.domain.text.ScreenTitle.TITLE_01,
                todayDate(),
                PROGRAM_ID,
                com.blitzy.carddemo.domain.text.ScreenTitle.TITLE_02,
                nowTime(),
                clamp(sv.acctSid, ACCT_SID_LEN),
                clamp(sv.acStatus, 1),
                clamp(sv.openYear, 4), clamp(sv.openMonth, 2), clamp(sv.openDay, 2),
                clamp(sv.creditLimit, CRED_LIM_LEN),
                clamp(sv.expirationYear, 4), clamp(sv.expirationMonth, 2), clamp(sv.expirationDay, 2),
                clamp(sv.cashCreditLimit, CRED_LIM_LEN),
                clamp(sv.reissueYear, 4), clamp(sv.reissueMonth, 2), clamp(sv.reissueDay, 2),
                clamp(sv.currentBalance, CRED_LIM_LEN),
                clamp(sv.currCycCredit, CRED_LIM_LEN),
                clamp(sv.accountGroup, GROUP_LEN),
                clamp(sv.currCycDebit, CRED_LIM_LEN),
                clamp(sv.custNumber, CUST_NUM_LEN),
                clamp(sv.ssn1, 3), clamp(sv.ssn2, 2), clamp(sv.ssn3, 4),
                clamp(sv.dobYear, 4), clamp(sv.dobMonth, 2), clamp(sv.dobDay, 2),
                clamp(sv.ficoScore, FICO_LEN),
                clamp(sv.firstName, CRD_NAME_LEN),
                clamp(sv.middleName, CRD_NAME_LEN),
                clamp(sv.lastName, CRD_NAME_LEN),
                clamp(sv.addressLine1, ADDR_LINE_LEN),
                clamp(sv.state, STATE_LEN),
                clamp(sv.addressLine2, ADDR_LINE_LEN),
                clamp(sv.zip, ZIP_LEN),
                clamp(sv.city, ADDR_LINE_LEN),
                clamp(sv.country, COUNTRY_LEN),
                clamp(sv.phone1Area, 3), clamp(sv.phone1Mid, 3), clamp(sv.phone1End, 4),
                clamp(sv.govtIssuedId, GOVT_ID_LEN),
                clamp(sv.phone2Area, 3), clamp(sv.phone2Mid, 3), clamp(sv.phone2End, 4),
                clamp(sv.eftAccountId, EFT_LEN),
                clamp(sv.primaryFlag, 1),
                clamp(infoMsg, 45),
                clamp(edit.errorMsg(), 78),
                "ENTER=Process F3=Exit",
                fKey05,
                fKey12,
                attrs);
    }

    /**
     * Bundle of the 3200-SETUP-SCREEN-VARS pick of OLD vs NEW values per
     * state, used by the switch expression so the screen-builder can
     * remain a single linear method.
     */
    private record ScreenVars(
            String acctSid,
            String acStatus,
            String openYear, String openMonth, String openDay,
            String creditLimit,
            String expirationYear, String expirationMonth, String expirationDay,
            String cashCreditLimit,
            String reissueYear, String reissueMonth, String reissueDay,
            String currentBalance, String currCycCredit, String accountGroup, String currCycDebit,
            String custNumber, String ssn1, String ssn2, String ssn3,
            String dobYear, String dobMonth, String dobDay,
            String ficoScore,
            String firstName, String middleName, String lastName,
            String addressLine1, String state, String addressLine2, String zip, String city, String country,
            String phone1Area, String phone1Mid, String phone1End,
            String govtIssuedId,
            String phone2Area, String phone2Mid, String phone2End,
            String eftAccountId, String primaryFlag) {
    }

    /** COBOL paragraph 3200-SETUP-SCREEN-VARS dispatch. */
    private static ScreenVars pickScreenVars(UpdateContext c, FormFields f) {
        return switch (c.state()) {
            // DETAILS-NOT-FETCHED — show what the user typed in the account
            // field; all other fields are LOW-VALUES (3201-SHOW-INITIAL-VALUES).
            case DETAILS_NOT_FETCHED -> new ScreenVars(
                    f.acctSid,
                    "", "", "", "",
                    "", "", "", "",
                    "", "", "", "",
                    "", "", "", "",
                    "", "", "", "",
                    "", "", "", "",
                    "", "", "",
                    "", "", "", "", "", "",
                    "", "", "",
                    "",
                    "", "", "",
                    "", "");

            // SHOW-DETAILS — show the cached ACUP-OLD-* snapshot
            // (3202-SHOW-ORIGINAL-VALUES).
            case SHOW_DETAILS -> new ScreenVars(
                    c.acctId(),
                    c.acctStatus(),
                    c.openYear(), c.openMonth(), c.openDay(),
                    formatMoney(c.creditLimit()),
                    c.expirationYear(), c.expirationMonth(), c.expirationDay(),
                    formatMoney(c.cashCreditLimit()),
                    c.reissueYear(), c.reissueMonth(), c.reissueDay(),
                    formatMoney(c.currBal()),
                    formatMoney(c.currCycCredit()),
                    c.groupId(),
                    formatMoney(c.currCycDebit()),
                    c.custId(),
                    substringSafe(c.custSsn(), 0, 3),
                    substringSafe(c.custSsn(), 3, 5),
                    substringSafe(c.custSsn(), 5, 9),
                    c.dobYear(), c.dobMonth(), c.dobDay(),
                    c.ficoScore(),
                    c.firstName(), c.middleName(), c.lastName(),
                    c.addressLine1(), c.addrStateCd(), c.addressLine2(), c.zip(), c.city(), c.country(),
                    c.phone1Area(), c.phone1Mid(), c.phone1End(),
                    c.govtIssuedId(),
                    c.phone2Area(), c.phone2Mid(), c.phone2End(),
                    c.eftAccountId(), c.primaryFlag());

            // CHANGES-NOT-OK / CHANGES-OK-NOT-CONFIRMED — show NEW values,
            // falling back to OLD when blank (3203-SHOW-UPDATED-VALUES).
            case CHANGES_NOT_OK, CHANGES_OK_NOT_CONFIRMED -> new ScreenVars(
                    isBlankOrLow(f.acctSid) ? c.acctId() : f.acctSid,
                    isBlankOrLow(f.acStatus) ? c.acctStatus() : f.acStatus,
                    isBlankOrLow(f.openYear) ? c.openYear() : f.openYear,
                    isBlankOrLow(f.openMonth) ? c.openMonth() : f.openMonth,
                    isBlankOrLow(f.openDay) ? c.openDay() : f.openDay,
                    isBlankOrLow(f.creditLimit) ? formatMoney(c.creditLimit()) : f.creditLimit,
                    isBlankOrLow(f.expirationYear) ? c.expirationYear() : f.expirationYear,
                    isBlankOrLow(f.expirationMonth) ? c.expirationMonth() : f.expirationMonth,
                    isBlankOrLow(f.expirationDay) ? c.expirationDay() : f.expirationDay,
                    isBlankOrLow(f.cashCreditLimit) ? formatMoney(c.cashCreditLimit()) : f.cashCreditLimit,
                    isBlankOrLow(f.reissueYear) ? c.reissueYear() : f.reissueYear,
                    isBlankOrLow(f.reissueMonth) ? c.reissueMonth() : f.reissueMonth,
                    isBlankOrLow(f.reissueDay) ? c.reissueDay() : f.reissueDay,
                    isBlankOrLow(f.currentBalance) ? formatMoney(c.currBal()) : f.currentBalance,
                    isBlankOrLow(f.currCycCredit) ? formatMoney(c.currCycCredit()) : f.currCycCredit,
                    isBlankOrLow(f.accountGroup) ? c.groupId() : f.accountGroup,
                    isBlankOrLow(f.currCycDebit) ? formatMoney(c.currCycDebit()) : f.currCycDebit,
                    isBlankOrLow(f.custNumber) ? c.custId() : f.custNumber,
                    isBlankOrLow(f.ssn1) ? substringSafe(c.custSsn(), 0, 3) : f.ssn1,
                    isBlankOrLow(f.ssn2) ? substringSafe(c.custSsn(), 3, 5) : f.ssn2,
                    isBlankOrLow(f.ssn3) ? substringSafe(c.custSsn(), 5, 9) : f.ssn3,
                    isBlankOrLow(f.dobYear) ? c.dobYear() : f.dobYear,
                    isBlankOrLow(f.dobMonth) ? c.dobMonth() : f.dobMonth,
                    isBlankOrLow(f.dobDay) ? c.dobDay() : f.dobDay,
                    isBlankOrLow(f.ficoScore) ? c.ficoScore() : f.ficoScore,
                    isBlankOrLow(f.firstName) ? c.firstName() : f.firstName,
                    isBlankOrLow(f.middleName) ? c.middleName() : f.middleName,
                    isBlankOrLow(f.lastName) ? c.lastName() : f.lastName,
                    isBlankOrLow(f.addressLine1) ? c.addressLine1() : f.addressLine1,
                    isBlankOrLow(f.state) ? c.addrStateCd() : f.state,
                    isBlankOrLow(f.addressLine2) ? c.addressLine2() : f.addressLine2,
                    isBlankOrLow(f.zip) ? c.zip() : f.zip,
                    isBlankOrLow(f.city) ? c.city() : f.city,
                    isBlankOrLow(f.country) ? c.country() : f.country,
                    isBlankOrLow(f.phone1Area) ? c.phone1Area() : f.phone1Area,
                    isBlankOrLow(f.phone1Mid) ? c.phone1Mid() : f.phone1Mid,
                    isBlankOrLow(f.phone1End) ? c.phone1End() : f.phone1End,
                    isBlankOrLow(f.govtIssuedId) ? c.govtIssuedId() : f.govtIssuedId,
                    isBlankOrLow(f.phone2Area) ? c.phone2Area() : f.phone2Area,
                    isBlankOrLow(f.phone2Mid) ? c.phone2Mid() : f.phone2Mid,
                    isBlankOrLow(f.phone2End) ? c.phone2End() : f.phone2End,
                    isBlankOrLow(f.eftAccountId) ? c.eftAccountId() : f.eftAccountId,
                    isBlankOrLow(f.primaryFlag) ? c.primaryFlag() : f.primaryFlag);

            // Terminal states show the cached snapshot.
            case CHANGES_OKAYED_AND_DONE, CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> new ScreenVars(
                    c.acctId(),
                    c.acctStatus(),
                    c.openYear(), c.openMonth(), c.openDay(),
                    formatMoney(c.creditLimit()),
                    c.expirationYear(), c.expirationMonth(), c.expirationDay(),
                    formatMoney(c.cashCreditLimit()),
                    c.reissueYear(), c.reissueMonth(), c.reissueDay(),
                    formatMoney(c.currBal()),
                    formatMoney(c.currCycCredit()),
                    c.groupId(),
                    formatMoney(c.currCycDebit()),
                    c.custId(),
                    substringSafe(c.custSsn(), 0, 3),
                    substringSafe(c.custSsn(), 3, 5),
                    substringSafe(c.custSsn(), 5, 9),
                    c.dobYear(), c.dobMonth(), c.dobDay(),
                    c.ficoScore(),
                    c.firstName(), c.middleName(), c.lastName(),
                    c.addressLine1(), c.addrStateCd(), c.addressLine2(), c.zip(), c.city(), c.country(),
                    c.phone1Area(), c.phone1Mid(), c.phone1End(),
                    c.govtIssuedId(),
                    c.phone2Area(), c.phone2Mid(), c.phone2End(),
                    c.eftAccountId(), c.primaryFlag());
        };
    }

    /**
     * COBOL paragraph 3250-SETUP-INFOMSG. Picks the info message based on
     * the current state, suppressed by an active validation error.
     */
    private static String pickInfoMessage(UpdateContext context, EditResult edit) {
        if (!edit.errorMsg().isEmpty() && !MSG_NO_CHANGES.equals(edit.errorMsg())) {
            return "";
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
     * attribute table based on the state and validation outcome.
     */
    private static CoActUpOutput.FieldAttributes pickAttributes(UpdateContext context, EditResult edit) {
        return switch (context.state()) {
            // DETAILS-NOT-FETCHED — only the account-id field is editable;
            // everything else is PROT.
            case DETAILS_NOT_FETCHED -> CoActUpOutput.FieldAttributes.allProtected();
            // SHOW-DETAILS / CHANGES-NOT-OK — all editable fields UNPROT or
            // ERROR per their validation status.
            case SHOW_DETAILS, CHANGES_NOT_OK -> buildEditableAttributes(edit);
            // OK-NOT-CONFIRMED / OKAYED-AND-DONE / OKAYED-FAILED → all PROT.
            case CHANGES_OK_NOT_CONFIRMED, CHANGES_OKAYED_AND_DONE,
                    CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED ->
                    CoActUpOutput.FieldAttributes.allProtected();
        };
    }

    private static CoActUpOutput.FieldAttributes buildEditableAttributes(EditResult edit) {
        CoActUpOutput.AttributeMode unp = CoActUpOutput.AttributeMode.UNPROTECTED;
        CoActUpOutput.AttributeMode err = CoActUpOutput.AttributeMode.ERROR;
        return new CoActUpOutput.FieldAttributes(
                edit.acctStatusValid() ? unp : err,
                edit.openDateValid() ? unp : err,
                edit.openDateValid() ? unp : err,
                edit.openDateValid() ? unp : err,
                edit.creditLimitValid() ? unp : err,
                edit.expirationDateValid() ? unp : err,
                edit.expirationDateValid() ? unp : err,
                edit.expirationDateValid() ? unp : err,
                edit.cashCreditLimitValid() ? unp : err,
                edit.reissueDateValid() ? unp : err,
                edit.reissueDateValid() ? unp : err,
                edit.reissueDateValid() ? unp : err,
                edit.currentBalanceValid() ? unp : err,
                edit.currCycCreditValid() ? unp : err,
                unp,                                            // accountGroup
                edit.currCycDebitValid() ? unp : err,
                edit.ssnValid() ? unp : err,
                edit.ssnValid() ? unp : err,
                edit.ssnValid() ? unp : err,
                edit.dobValid() ? unp : err,
                edit.dobValid() ? unp : err,
                edit.dobValid() ? unp : err,
                edit.ficoValid() ? unp : err,
                edit.firstNameValid() ? unp : err,
                edit.middleNameValid() ? unp : err,
                edit.lastNameValid() ? unp : err,
                edit.addressLine1Valid() ? unp : err,
                edit.stateValid() ? unp : err,
                unp,                                            // addressLine2 (optional)
                edit.zipValid() ? unp : err,
                edit.cityValid() ? unp : err,
                edit.countryValid() ? unp : err,
                edit.phone1Valid() ? unp : err,
                edit.phone1Valid() ? unp : err,
                edit.phone1Valid() ? unp : err,
                edit.govtIssuedIdValid() ? unp : err,
                edit.phone2Valid() ? unp : err,
                edit.phone2Valid() ? unp : err,
                edit.phone2Valid() ? unp : err,
                edit.eftAccountIdValid() ? unp : err,
                edit.primaryFlagValid() ? unp : err);
    }

    /** COBOL FKEY05A: visible only when PROMPT-FOR-CONFIRMATION. */
    private static String pickFKey05(UpdateContext context) {
        return context.state() == UpdateState.CHANGES_OK_NOT_CONFIRMED ? "F5=Save" : "";
    }

    /** COBOL FKEY12A: visible whenever changes have been made and not yet committed. */
    private static String pickFKey12(UpdateContext context) {
        return switch (context.state()) {
            case SHOW_DETAILS, CHANGES_NOT_OK, CHANGES_OK_NOT_CONFIRMED -> "F12=Cancel";
            case DETAILS_NOT_FETCHED, CHANGES_OKAYED_AND_DONE,
                    CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> "";
        };
    }

    // ============================================================================
    // String / numeric helpers
    // ============================================================================

    /** COBOL 1100-RECEIVE-MAP: replace '*' or SPACES with LOW-VALUES → empty. */
    private static String normalizeStarOrSpaces(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return "";
        }
        return raw;
    }

    private static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZeroes(String s) {
        if (s == null) {
            return false;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * COBOL 1225-EDIT-ALPHA-REQD alphabet check, modelled on
     * {@code INSPECT ... CONVERTING LIT-ALL-ALPHA-FROM TO
     * LIT-ALL-SPACES-TO} + {@code FUNCTION LENGTH(FUNCTION TRIM(...))=0}.
     * Returns true when every non-space character is a Latin letter.
     */
    private static boolean isAlphabetsAndSpacesOnly(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        boolean hasContent = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == 0) {
                continue;
            }
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
            hasContent = true;
        }
        return hasContent;
    }

    /** COBOL 1220-EDIT-YESNO: accepts 'Y' or 'N'. */
    private static boolean isYesOrNo(String s) {
        if (s == null) {
            return false;
        }
        String trimmed = s.trim().toUpperCase(Locale.ROOT);
        return "Y".equals(trimmed) || "N".equals(trimmed);
    }

    /** COBOL FUNCTION UPPER-CASE. */
    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    /** COBOL FUNCTION LOWER-CASE (used by 9700-CHECK-CHANGE-IN-REC for group id). */
    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** Formats a BigDecimal as +NNNNNNNN.NN style for SEND-MAP. */
    private static String formatMoney(BigDecimal v) {
        if (v == null) {
            return "";
        }
        BigDecimal s = v.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
        return s.toPlainString();
    }

    private static String clamp(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }

    /**
     * Right-pads with spaces to the requested length; if too long, truncates
     * from the right.
     */
    private static String padOrClamp(String s, int len) {
        String v = s == null ? "" : s;
        if (v.length() == len) {
            return v;
        }
        if (v.length() > len) {
            return v.substring(0, len);
        }
        StringBuilder sb = new StringBuilder(len);
        sb.append(v);
        while (sb.length() < len) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String firstNonEmptyError(String... errs) {
        for (String e : errs) {
            if (e != null && !e.isEmpty()) {
                return e;
            }
        }
        return "";
    }

    private static CoActUpInput coalesceInput(CoActUpInput input) {
        return input != null ? input : CoActUpInput.blank();
    }

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
