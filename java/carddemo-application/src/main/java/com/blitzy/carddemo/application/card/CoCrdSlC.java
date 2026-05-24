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
import com.blitzy.carddemo.domain.status.PgmContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Java translation of COBOL program {@code COCRDSLC}
 * ({@code app/cbl/COCRDSLC.cbl}, CICS transaction {@code CCDL}).
 *
 * <p><b>Purpose:</b> Display a single credit card's details — cardholder
 * name, status, and expiry month/year — for a given account + card
 * combination. Read-only view.
 *
 * <h2>Translation notes</h2>
 * <ul>
 *   <li>COBOL paragraph {@code 9100-GETCARD-BYACCTCARD} actually reads
 *       the CARDFILE by primary key {@code CARDNUM} alone (the COBOL
 *       sets {@code KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)} = 16
 *       bytes), so we delegate to
 *       {@link CardRepository#findByCardNum(String)}.</li>
 *   <li>"   Displaying requested details" info message preserves the
 *       three leading spaces per AAP &sect;0.7.1.</li>
 *   <li>Account/card validation messages preserved verbatim:
 *       {@code ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER}
 *       and {@code CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER}
 *       (the comma-no-space artifact is verbatim).</li>
 *   <li>"Account number not provided" / "Card number not provided" /
 *       "No input received" / "Did not find this account in cards
 *       database" / "Did not find cards for this search condition" /
 *       "Error reading Card Data File" verbatim.</li>
 *   <li>"PF03 pressed.Exiting              " (no space after the dot,
 *       trailing spaces) verbatim per AAP &sect;0.7.1.</li>
 * </ul>
 */
@CobolProgram(
        value = "COCRDSLC",
        sourcePath = "app/cbl/COCRDSLC.cbl",
        notes = "Card detail view (read-only). PRIMARY KEY read on CARDNUM " +
                "(COBOL sets KEYLENGTH=16). Validation/error messages " +
                "preserved verbatim per AAP §0.7.1 including the comma-no-" +
                "space typo in 'ACCOUNT FILTER,IF SUPPLIED ...'. Three " +
                "leading spaces in '   Displaying requested details' " +
                "preserved verbatim."
)
public final class CoCrdSlC {

    private static final String PROGRAM_ID = "COCRDSLC";
    private static final String TRANSACTION_ID = "CCDL";

    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    // Verbatim error/prompt messages per AAP §0.7.1.
    private static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";
    private static final String MSG_FOUND_CARDS = "   Displaying requested details"; // 3 leading spaces!
    private static final String MSG_PF03_EXIT = "PF03 pressed.Exiting              "; // 14 trailing spaces!
    private static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";
    private static final String MSG_PROMPT_FOR_CARD = "Card number not provided";
    private static final String MSG_NO_INPUT = "No input received";
    private static final String MSG_ACCT_NOT_NUMERIC = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    private static final String MSG_CARD_NOT_NUMERIC = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    private static final String MSG_NOT_FOUND_ACCT = "Did not find this account in cards database";
    private static final String MSG_NOT_FOUND_COMBO = "Did not find cards for this search condition";
    private static final String MSG_READ_ERROR = "Error reading Card Data File";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    private final CardRepository cards;
    private final ProgramRegistry programRegistry;

    public CoCrdSlC(CardRepository cards, ProgramRegistry programRegistry) {
        this.cards = cards;
        this.programRegistry = programRegistry;
    }

    /**
     * Result wrapper signalling SEND-MAP or XCTL.
     */
    public record Result(CoCrdSlOutput output, CardDemoCommarea commarea, String xctlTo) {
        public static Result sendMap(CoCrdSlOutput output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }
        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }
        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    public Result run(CoCrdSlInput input, CardDemoCommarea commarea) {
        return run(input, commarea, null, null);
    }

    /**
     * Entry point with optional preselected account/card
     * (CDEMO-ACCT-ID / CDEMO-CARD-NUM equivalent).
     */
    public Result run(CoCrdSlInput input, CardDemoCommarea commarea,
                      String preselectedAcctId, String preselectedCardNum) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        boolean isReenter = commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter;
        CardDemoCommarea reentered = isReenter ? commarea : withPgmContext(commarea, PgmContext.REENTER);

        // PF3 handling — always honored.
        if (input != null && input.aidKey() == CoCrdSlInput.AidKey.PF03_BACK) {
            String fromProgram = commarea.generalInfo().fromProgram();
            String target = (fromProgram != null && !fromProgram.isBlank())
                    ? fromProgram
                    : ProgramRegistry.CO_MEN_01C;
            return Result.xctl(target, withTarget(commarea, target));
        }

        // First-time entry path.
        if (!isReenter) {
            // Case 1: account+card preselected (typically from COCRDLIC). Read and display.
            if (preselectedAcctId != null && !preselectedAcctId.isBlank()
                    && preselectedCardNum != null && !preselectedCardNum.isBlank()) {
                return readAndDisplay(preselectedAcctId, preselectedCardNum, reentered);
            }
            // Case 2: empty entry — prompt user for input.
            return Result.sendMap(buildPromptScreen(reentered), reentered);
        }

        // Re-entry: AID-key dispatch.
        CoCrdSlInput inputOrEmpty = (input != null) ? input : CoCrdSlInput.blank();
        return switch (inputOrEmpty.aidKey()) {
            case ENTER -> processEnterKey(inputOrEmpty, reentered);
            case PF03_BACK -> {
                // Already handled above; this branch is unreachable but the switch
                // is exhaustive.
                String target = ProgramRegistry.CO_MEN_01C;
                yield Result.xctl(target, withTarget(commarea, target));
            }
            case OTHER -> Result.sendMap(buildScreen(inputOrEmpty, MSG_INVALID_KEY, ""), reentered);
        };
    }

    /**
     * COBOL paragraph {@code 2000-PROCESS-INPUTS} + {@code 9000-READ-DATA}.
     * Validates account + card inputs then reads CARDFILE.
     */
    private Result processEnterKey(CoCrdSlInput input, CardDemoCommarea commarea) {
        String acctRaw = input.acctSid();
        String cardRaw = input.cardSid();
        // 2200-EDIT-MAP-INPUTS: replace '*' or SPACES with LOW-VALUES.
        String acctVal = normalizeStarOrSpaces(acctRaw);
        String cardVal = normalizeStarOrSpaces(cardRaw);

        // 2210-EDIT-ACCOUNT.
        // COBOL check: CC-ACCT-ID EQUAL LOW-VALUES OR SPACES OR
        // CC-ACCT-ID-N EQUAL ZEROS → BLANK (prompts for account number).
        // ELSE IF CC-ACCT-ID IS NOT NUMERIC → NOT-OK (numeric error).
        // ELSE VALID.
        boolean acctBlank = false;
        String acctError = null;
        if (isBlankOrLow(acctVal) || isAllZeroes(acctVal)) {
            acctBlank = true;
            acctError = MSG_PROMPT_FOR_ACCT;
        } else if (!isNumeric(acctVal)) {
            acctError = MSG_ACCT_NOT_NUMERIC;
        }

        // 2220-EDIT-CARD.
        boolean cardBlank = false;
        String cardError = null;
        if (isBlankOrLow(cardVal) || isAllZeroes(cardVal)) {
            cardBlank = true;
            cardError = MSG_PROMPT_FOR_CARD;
        } else if (!isNumeric(cardVal)) {
            cardError = MSG_CARD_NOT_NUMERIC;
        }

        // CROSS FIELD EDITS: if both blank, override with NO-SEARCH-CRITERIA-RECEIVED.
        if (acctBlank && cardBlank) {
            return Result.sendMap(buildScreen(input, MSG_NO_INPUT, ""), commarea);
        }

        // 2210 sets WS-RETURN-MSG only IF WS-RETURN-MSG-OFF — i.e., first error wins.
        // The COBOL order is: 2210 then 2220.
        String firstError = acctError != null ? acctError
                : cardError;

        if (firstError != null) {
            return Result.sendMap(buildScreen(input, firstError, ""), commarea);
        }

        // Both valid → read CARDFILE.
        return readAndDisplay(acctVal, cardVal, commarea);
    }

    /**
     * COBOL paragraph {@code 9100-GETCARD-BYACCTCARD}. Reads CARDFILE
     * by card number primary key.
     */
    private Result readAndDisplay(String acctIdStr, String cardNumStr, CardDemoCommarea commarea) {
        // Normalize: pad/truncate to the expected widths.
        String acctVal = acctIdStr.trim();
        String cardVal = cardNumStr.trim();

        Optional<CardRecord> cardOpt;
        try {
            cardOpt = cards.findByCardNum(cardVal);
        } catch (RuntimeException re) {
            return Result.sendMap(
                    buildScreenWithVals(MSG_READ_ERROR, "", acctVal, cardVal, "", "", "", ""),
                    commarea);
        }

        if (cardOpt.isEmpty()) {
            return Result.sendMap(
                    buildScreenWithVals(MSG_NOT_FOUND_COMBO, "", acctVal, cardVal, "", "", "", ""),
                    commarea);
        }

        CardRecord card = cardOpt.get();
        // Cross-check that the account matches; COBOL doesn't enforce this
        // because READ is by CARDNUM alone, but we record the value for display.
        String embossed = card.cardEmbossedName();
        LocalDate expiry = card.cardExpiraionDate();
        String expMon = expiry != null ? String.format("%02d", expiry.getMonthValue()) : "";
        String expYear = expiry != null ? String.format("%04d", expiry.getYear()) : "";
        String status = String.valueOf(card.cardActiveStatus());

        return Result.sendMap(
                buildScreenWithVals("", MSG_FOUND_CARDS, acctVal, cardVal,
                        embossed, status, expMon, expYear),
                commarea);
    }

    // ----- Screen builders --------------------------------------------------

    private CoCrdSlOutput buildPromptScreen(CardDemoCommarea commarea) {
        return buildScreenWithVals("", MSG_PROMPT_FOR_INPUT, "", "", "", "", "", "");
    }

    private CoCrdSlOutput buildScreen(CoCrdSlInput input, String errMsg, String infoMsg) {
        return buildScreenWithVals(errMsg, infoMsg,
                input.acctSid(), input.cardSid(),
                input.crdName(), input.crdStsCd(),
                input.expMon(), input.expYear());
    }

    private CoCrdSlOutput buildScreenWithVals(String errMsg, String infoMsg,
                                              String acctSid, String cardSid,
                                              String crdName, String crdStsCd,
                                              String expMon, String expYear) {
        return new CoCrdSlOutput(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "Credit Card View",
                nowTime(),
                clamp(acctSid, 11),
                clamp(cardSid, 16),
                clamp(crdName, 50),
                clamp(crdStsCd, 1),
                clamp(expMon, 2),
                clamp(expYear, 4),
                clamp(infoMsg, 40),
                clamp(errMsg, 80),
                "ENTER=Process F3=Exit");
    }

    // ----- Helpers ----------------------------------------------------------

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
