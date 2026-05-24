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
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Java translation of COBOL program {@code COACTVWC}
 * ({@code app/cbl/COACTVWC.cbl}, CICS transaction {@code CAVW}).
 *
 * <p><b>Purpose:</b> Display detailed account information (balances, credit
 * limits, customer demographic details). Takes an account ID, looks up the
 * CARDXREF AIX (by account) to derive the customer id, then reads the
 * ACCOUNT master and CUSTOMER master.
 *
 * <h2>Translation notes</h2>
 * <ul>
 *   <li>COBOL paragraphs:
 *     <ul>
 *       <li>{@code 9200-GETCARDXREF-BYACCT}: AIX read on CARDAIX by
 *           ACCT-ID → derives CUST-ID and CARD-NUM. Translated to
 *           {@link CardXrefRepository#findByAccountId(long)}.</li>
 *       <li>{@code 9300-GETACCTDATA-BYACCT}: primary read on ACCTDAT by
 *           ACCT-ID → {@link AccountRepository#findById(long)}.</li>
 *       <li>{@code 9400-GETCUSTDATA-BYCUST}: primary read on CUSTDAT by
 *           CUST-ID → {@link CustomerRepository#findById(long)}.</li>
 *     </ul>
 *   </li>
 *   <li>Verbatim error messages:
 *     <ul>
 *       <li>{@code 'Account Filter must  be a non-zero 11 digit number'}
 *           (note the double space between "must" and "be" — preserved
 *           per AAP &sect;0.7.1).</li>
 *       <li>{@code 'Did not find this account in account card xref file'}</li>
 *       <li>{@code 'Did not find this account in account master file'}</li>
 *       <li>{@code 'Did not find associated customer in master file'}</li>
 *       <li>{@code 'Account number not provided'} /
 *           {@code 'No input received'} /
 *           {@code 'Enter or update id of account to display'} /
 *           {@code 'Displaying details of given Account'}.</li>
 *     </ul>
 *   </li>
 *   <li>SSN formatted as NNN-NN-NNNN via STRING construct preserved.</li>
 *   <li>Monetary fields formatted as +ZZZ,ZZZ,ZZZ.99 (15 chars).</li>
 *   <li>{@code 'PF03 pressed.Exiting              '} (no space after dot,
 *       trailing spaces) preserved per AAP &sect;0.7.1.</li>
 * </ul>
 */
@CobolProgram(
        value = "COACTVWC",
        sourcePath = "app/cbl/COACTVWC.cbl",
        notes = "Account view (read-only). Three-step lookup: CARDXREF AIX " +
                "by account id → CUST-ID → ACCTDAT primary + CUSTDAT " +
                "primary. The double-space 'must  be' in 'Account Filter " +
                "must  be a non-zero 11 digit number' preserved verbatim " +
                "per AAP §0.7.1."
)
public final class CoActVwC {

    private static final String PROGRAM_ID = "COACTVWC";
    private static final String TRANSACTION_ID = "CAVW";

    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    // Verbatim error messages per AAP §0.7.1.
    private static final String MSG_PROMPT_FOR_INPUT = "Enter or update id of account to display";
    private static final String MSG_INFORM_OUTPUT = "Displaying details of given Account";
    private static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";
    private static final String MSG_NO_INPUT = "No input received";
    private static final String MSG_ACCT_NOT_NUMERIC = "Account Filter must  be a non-zero 11 digit number"; // double space preserved!
    private static final String MSG_NOT_FOUND_XREF = "Did not find this account in account card xref file";
    private static final String MSG_NOT_FOUND_ACCT = "Did not find this account in account master file";
    private static final String MSG_NOT_FOUND_CUST = "Did not find associated customer in master file";
    private static final String MSG_PF03_EXIT = "PF03 pressed.Exiting              "; // trailing spaces preserved

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    private final AccountRepository accounts;
    private final CardXrefRepository cardXref;
    private final CustomerRepository customers;
    private final ProgramRegistry programRegistry;

    public CoActVwC(AccountRepository accounts, CardXrefRepository cardXref,
                    CustomerRepository customers, ProgramRegistry programRegistry) {
        this.accounts = accounts;
        this.cardXref = cardXref;
        this.customers = customers;
        this.programRegistry = programRegistry;
    }

    public record Result(CoActVwOutput output, CardDemoCommarea commarea, String xctlTo) {
        public static Result sendMap(CoActVwOutput output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }
        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }
        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    public Result run(CoActVwInput input, CardDemoCommarea commarea) {
        return run(input, commarea, null);
    }

    /**
     * Entry point with optional preselected account id (CDEMO-ACCT-ID
     * preselect from another program).
     */
    public Result run(CoActVwInput input, CardDemoCommarea commarea, String preselectedAcctId) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        boolean isReenter = commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter;
        CardDemoCommarea reentered = isReenter ? commarea : withPgmContext(commarea, PgmContext.REENTER);

        // PF3 handling — always honored.
        if (input != null && input.aidKey() == CoActVwInput.AidKey.PF03_BACK) {
            String fromProgram = commarea.generalInfo().fromProgram();
            String target = (fromProgram != null && !fromProgram.isBlank())
                    ? fromProgram
                    : ProgramRegistry.CO_MEN_01C;
            return Result.xctl(target, withTarget(commarea, target));
        }

        // First-time entry path.
        if (!isReenter) {
            if (preselectedAcctId != null && !preselectedAcctId.isBlank()) {
                return processAccountLookup(preselectedAcctId, reentered);
            }
            return Result.sendMap(buildPromptScreen(), reentered);
        }

        CoActVwInput inputOrEmpty = (input != null) ? input : CoActVwInput.blank();
        return switch (inputOrEmpty.aidKey()) {
            case ENTER -> processEnterKey(inputOrEmpty, reentered);
            case PF03_BACK -> {
                // Unreachable; handled above.
                String target = ProgramRegistry.CO_MEN_01C;
                yield Result.xctl(target, withTarget(commarea, target));
            }
            case PF04_CLEAR -> Result.sendMap(buildPromptScreen(), reentered);
            case OTHER -> Result.sendMap(buildErrorScreen(inputOrEmpty.acctSid(), MSG_INVALID_KEY), reentered);
        };
    }

    private Result processEnterKey(CoActVwInput input, CardDemoCommarea commarea) {
        String acctRaw = input.acctSid();
        // 2200-EDIT-MAP-INPUTS: replace '*' or SPACES with LOW-VALUES.
        String acctVal = normalizeStarOrSpaces(acctRaw);

        // 2210-EDIT-ACCOUNT (COACTVWC version).
        // BLANK: LOW-VALUES or SPACES → 'Account number not provided'
        // NOT NUMERIC or ZEROES → 'Account Filter must  be a non-zero 11 digit number'
        if (isBlankOrLow(acctVal)) {
            // CROSS FIELD EDIT: if FLG-ACCTFILTER-BLANK → NO-SEARCH-CRITERIA-RECEIVED ('No input received')
            return Result.sendMap(buildErrorScreen("", MSG_NO_INPUT), commarea);
        }
        if (!isNumeric(acctVal) || isAllZeroes(acctVal)) {
            return Result.sendMap(buildErrorScreen(acctVal, MSG_ACCT_NOT_NUMERIC), commarea);
        }

        return processAccountLookup(acctVal, commarea);
    }

    /**
     * COBOL paragraph {@code 9000-READ-ACCT}. Three-step lookup:
     * CARDXREF AIX → ACCTDAT → CUSTDAT.
     */
    private Result processAccountLookup(String acctIdStr, CardDemoCommarea commarea) {
        long acctId;
        try {
            acctId = Long.parseLong(acctIdStr.trim());
        } catch (NumberFormatException nfe) {
            return Result.sendMap(buildErrorScreen(acctIdStr, MSG_ACCT_NOT_NUMERIC), commarea);
        }

        // 9200-GETCARDXREF-BYACCT.
        Optional<CardXrefRecord> xrefOpt = cardXref.findByAccountId(acctId);
        if (xrefOpt.isEmpty()) {
            return Result.sendMap(buildErrorScreen(acctIdStr, MSG_NOT_FOUND_XREF), commarea);
        }
        long custId = xrefOpt.get().xrefCustId();

        // 9300-GETACCTDATA-BYACCT.
        Optional<AccountRecord> acctOpt = accounts.findById(acctId);
        if (acctOpt.isEmpty()) {
            return Result.sendMap(buildErrorScreen(acctIdStr, MSG_NOT_FOUND_ACCT), commarea);
        }
        AccountRecord acct = acctOpt.get();

        // 9400-GETCUSTDATA-BYCUST.
        Optional<CustomerRecord> custOpt = customers.findById(custId);
        if (custOpt.isEmpty()) {
            return Result.sendMap(buildErrorScreenWithAccount(acctIdStr, acct, MSG_NOT_FOUND_CUST), commarea);
        }
        CustomerRecord cust = custOpt.get();

        return Result.sendMap(buildFullScreen(acctIdStr, acct, cust, MSG_INFORM_OUTPUT), commarea);
    }

    // ----- Screen builders --------------------------------------------------

    private CoActVwOutput buildPromptScreen() {
        return new CoActVwOutput(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "View Account",
                nowTime(),
                "",                              // acctSid
                "", "", "", "", "", "", "", "", "", "",     // account fields (10)
                "", "", "", "", "", "", "",                  // customer identity (7)
                "", "", "", "", "", "", "", "", "", "",     // customer detail (10)
                "",                              // primaryFlag
                clamp(MSG_PROMPT_FOR_INPUT, 45), // infoMsg
                ""                               // errMsg
        );
    }

    private CoActVwOutput buildErrorScreen(String acctSid, String errMsg) {
        return new CoActVwOutput(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "View Account",
                nowTime(),
                clamp(acctSid, 11),
                "", "", "", "", "", "", "", "", "", "",
                "", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "",
                "",
                "",
                clamp(errMsg, 78)
        );
    }

    private CoActVwOutput buildErrorScreenWithAccount(String acctSid, AccountRecord acct, String errMsg) {
        return new CoActVwOutput(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "View Account",
                nowTime(),
                clamp(acctSid, 11),
                String.valueOf(acct.acctActiveStatus()),
                fmtDate(acct.acctOpenDate()),
                fmtMoney(acct.acctCreditLimit()),
                fmtDate(acct.acctExpiraionDate()),
                fmtMoney(acct.acctCashCreditLimit()),
                fmtDate(acct.acctReissueDate()),
                fmtMoney(acct.acctCurrBal()),
                fmtMoney(acct.acctCurrCycCredit()),
                clamp(acct.acctGroupId(), 10),
                fmtMoney(acct.acctCurrCycDebit()),
                "", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", "", "",
                "",
                "",
                clamp(errMsg, 78)
        );
    }

    private CoActVwOutput buildFullScreen(String acctSid, AccountRecord acct, CustomerRecord cust, String infoMsg) {
        return new CoActVwOutput(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "View Account",
                nowTime(),
                clamp(acctSid, 11),
                String.valueOf(acct.acctActiveStatus()),
                fmtDate(acct.acctOpenDate()),
                fmtMoney(acct.acctCreditLimit()),
                fmtDate(acct.acctExpiraionDate()),
                fmtMoney(acct.acctCashCreditLimit()),
                fmtDate(acct.acctReissueDate()),
                fmtMoney(acct.acctCurrBal()),
                fmtMoney(acct.acctCurrCycCredit()),
                clamp(acct.acctGroupId(), 10),
                fmtMoney(acct.acctCurrCycDebit()),
                String.format("%09d", cust.custId()),
                formatSsn(cust.custSsn()),
                // LocalDate.toString() yields ISO_LOCAL_DATE format ("yyyy-MM-dd"),
                // matching the 10-byte CUST-DOB-YYYY-MM-DD field width exactly.
                clamp(cust.custDobYyyyMmDd().toString(), 10),
                String.format("%03d", cust.custFicoCreditScore()),
                clamp(cust.custFirstName(), 25),
                clamp(cust.custMiddleName(), 25),
                clamp(cust.custLastName(), 25),
                clamp(cust.custAddrLine1(), 50),
                clamp(cust.custAddrStateCd(), 2),
                clamp(cust.custAddrLine2(), 50),
                clamp(cust.custAddrZip(), 5),
                clamp(cust.custAddrLine3(), 50),
                clamp(cust.custAddrCountryCd(), 3),
                clamp(cust.custPhoneNum1(), 13),
                clamp(cust.custGovtIssuedId(), 20),
                clamp(cust.custPhoneNum2(), 13),
                clamp(cust.custEftAccountId(), 10),
                String.valueOf(cust.custPriCardHolderInd()),
                clamp(infoMsg, 45),
                ""
        );
    }

    // ----- Helpers ----------------------------------------------------------

    /**
     * Format a {@link BigDecimal} as a 15-character +ZZZ,ZZZ,ZZZ.99
     * BMS PICOUT string. Mirrors COBOL DBI sign + comma-separated
     * thousands picture clause.
     */
    private static String fmtMoney(BigDecimal v) {
        if (v == null) return "";
        BigDecimal scaled = v.setScale(2, RoundingMode.HALF_EVEN);
        char sign = scaled.signum() < 0 ? '-' : '+';
        BigDecimal abs = scaled.abs();
        long whole = abs.longValue();
        long cents = abs.subtract(BigDecimal.valueOf(whole))
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValue();
        String wholeFmt = String.format("%,d", whole);
        // Build "+wholeFmt.cents" total width 15, pad left with spaces.
        String result = sign + wholeFmt + "." + String.format("%02d", cents);
        if (result.length() > 15) result = result.substring(result.length() - 15);
        return String.format("%15s", result);
    }

    private static String fmtDate(LocalDate d) {
        return d == null ? "" : d.format(ISO_DATE);
    }

    /**
     * Format a 9-digit SSN long as NNN-NN-NNNN. Mirrors COBOL STRING
     * construct in {@code 1200-SETUP-SCREEN-VARS}.
     */
    private static String formatSsn(long ssn) {
        String padded = String.format("%09d", ssn);
        return padded.substring(0, 3) + "-" + padded.substring(3, 5) + "-" + padded.substring(5);
    }

    private static String normalizeStarOrSpaces(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.equals("*")) return "";
        return s;
    }

    private static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\0') return false;
        }
        return true;
    }

    private static boolean isAllZeroes(String s) {
        if (s == null || s.isEmpty()) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != '0') return false;
        }
        return true;
    }

    private static boolean isNumeric(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        return NUMERIC.matcher(t).matches();
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
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

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
