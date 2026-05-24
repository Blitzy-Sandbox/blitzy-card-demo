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
package com.blitzy.carddemo.application.transaction;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.application.util.DateValidator;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.status.PgmContext;
import com.blitzy.carddemo.domain.util.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java translation of COBOL program {@code COTRN02C}
 * ({@code app/cbl/COTRN02C.cbl}, CICS transaction {@code CT02}).
 *
 * <p><b>Purpose:</b> Add a new transaction to the TRANSACT file with
 * full account/card validation, type & category code validation, amount
 * and date format validation, and confirmation prompt.
 *
 * <h2>Translation notes</h2>
 * <ul>
 *   <li>COBOL paragraphs {@code STARTBR-TRANSACT-FILE},
 *       {@code READPREV-TRANSACT-FILE}, {@code ENDBR-TRANSACT-FILE}
 *       (the "find max TRAN-ID then add 1" pattern) translated to
 *       {@link Stream}-based maximum scan.</li>
 *   <li>17 verbatim error messages preserved per AAP &sect;0.7.1.</li>
 *   <li>"Transaction added successfully. " + " Your Tran ID is " +
 *       TRAN-ID + "." (the COBOL STRING construct emits a single
 *       trailing space on the first literal and a leading space on
 *       the second &mdash; preserved verbatim).</li>
 *   <li>"Tran ID already exist..." typo preserved (also appears in
 *       COBIL00C; preserved per AAP &sect;0.7.1).</li>
 *   <li>CSUTLDTC call replaced by {@link DateValidator}.</li>
 * </ul>
 */
@CobolProgram(
        value = "COTRN02C",
        sourcePath = "app/cbl/COTRN02C.cbl",
        notes = "Add a new transaction with full validation. " +
                "STARTBR/READPREV translated to stream-based max scan. " +
                "17 verbatim error messages preserved. " +
                "'Tran ID already exist...' typo preserved per AAP §0.7.1. " +
                "PF5 copies last transaction's data into current input fields."
)
public final class CoTrn02C {

    private static final String PROGRAM_ID = "COTRN02C";
    private static final String TRANSACTION_ID = "CT02";

    // 17 verbatim error messages preserved from COBOL source per AAP §0.7.1.
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";
    private static final String MSG_ACCT_NUMERIC = "Account ID must be Numeric...";
    private static final String MSG_CARD_NUMERIC = "Card Number must be Numeric...";
    private static final String MSG_ACCT_OR_CARD_REQUIRED = "Account or Card Number must be entered...";
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";
    private static final String MSG_CARD_NOT_FOUND = "Card Number NOT found...";
    private static final String MSG_ACCT_XREF_LOOKUP_ERROR = "Unable to lookup Acct in XREF AIX file...";
    private static final String MSG_CARD_XREF_LOOKUP_ERROR = "Unable to lookup Card # in XREF file...";
    private static final String MSG_TRAN_LOOKUP_ERROR = "Unable to lookup Transaction...";
    private static final String MSG_TYPE_EMPTY = "Type CD can NOT be empty...";
    private static final String MSG_CAT_EMPTY = "Category CD can NOT be empty...";
    private static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";
    private static final String MSG_DESC_EMPTY = "Description can NOT be empty...";
    private static final String MSG_AMT_EMPTY = "Amount can NOT be empty...";
    private static final String MSG_ORIG_DT_EMPTY = "Orig Date can NOT be empty...";
    private static final String MSG_PROC_DT_EMPTY = "Proc Date can NOT be empty...";
    private static final String MSG_MID_EMPTY = "Merchant ID can NOT be empty...";
    private static final String MSG_MNAME_EMPTY = "Merchant Name can NOT be empty...";
    private static final String MSG_MCITY_EMPTY = "Merchant City can NOT be empty...";
    private static final String MSG_MZIP_EMPTY = "Merchant Zip can NOT be empty...";
    private static final String MSG_TYPE_NUMERIC = "Type CD must be Numeric...";
    private static final String MSG_CAT_NUMERIC = "Category CD must be Numeric...";
    private static final String MSG_AMT_FORMAT = "Amount should be in format -99999999.99";
    private static final String MSG_ORIG_DT_FORMAT = "Orig Date should be in format YYYY-MM-DD";
    private static final String MSG_PROC_DT_FORMAT = "Proc Date should be in format YYYY-MM-DD";
    private static final String MSG_ORIG_DT_INVALID = "Orig Date - Not a valid date...";
    private static final String MSG_PROC_DT_INVALID = "Proc Date - Not a valid date...";
    private static final String MSG_MID_NUMERIC = "Merchant ID must be Numeric...";
    private static final String MSG_CONFIRM_REQUIRED = "Confirm to add this transaction...";
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";
    private static final String MSG_TRAN_DUPLICATE = "Tran ID already exist...";  // typo preserved
    private static final String MSG_TRAN_ADD_ERROR = "Unable to Add Transaction...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("[+-]\\d{8}\\.\\d{2}");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final int MONETARY_SCALE = 2;

    private final CardXrefRepository cardXref;
    private final TransactionRepository transactions;
    private final DateValidator dateValidator;
    private final ProgramRegistry programRegistry;

    public CoTrn02C(CardXrefRepository cardXref, TransactionRepository transactions,
                    DateValidator dateValidator, ProgramRegistry programRegistry) {
        this.cardXref = cardXref;
        this.transactions = transactions;
        this.dateValidator = dateValidator;
        this.programRegistry = programRegistry;
    }

    /**
     * Result wrapper signalling SEND-MAP, XCTL, or success.
     */
    public record Result(CoTrn02Output output, CardDemoCommarea commarea, String xctlTo) {
        public static Result sendMap(CoTrn02Output output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }
        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }
        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    public Result run(CoTrn02Input input, CardDemoCommarea commarea) {
        return run(input, commarea, null);
    }

    /**
     * Entry point with optional preselected transaction id
     * (CDEMO-CT02-TRN-SELECTED equivalent).
     */
    public Result run(CoTrn02Input input, CardDemoCommarea commarea, String preselectedTranId) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        // First-time entry.
        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            if (preselectedTranId != null && !preselectedTranId.isBlank()) {
                CoTrn02Input prep = CoTrn02Input.empty();
                CoTrn02Input prepFilled = new CoTrn02Input(
                        prep.trnName(), prep.title01(), prep.curDate(), prep.pgmName(),
                        prep.title02(), prep.curTime(),
                        prep.accountId(),
                        preselectedTranId,                    // cardNumber pre-populated
                        prep.typeCode(), prep.categoryCode(), prep.source(), prep.description(),
                        prep.amount(), prep.origDate(), prep.procDate(),
                        prep.merchantId(), prep.merchantName(), prep.merchantCity(), prep.merchantZip(),
                        prep.confirmation(),
                        prep.errMsg(),
                        CoTrn02Input.AidKey.ENTER);
                return processEnterKey(prepFilled, reentered);
            }
            return Result.sendMap(buildScreen(CoTrn02Input.empty(), ""), reentered);
        }

        // Re-entry: dispatch by AID key.
        if (input == null) {
            return Result.sendMap(buildScreen(CoTrn02Input.empty(), ""), commarea);
        }

        return switch (input.aidKey()) {
            case ENTER -> processEnterKey(input, commarea);
            case PF3 -> {
                String target = (commarea.generalInfo().fromProgram() != null
                        && !commarea.generalInfo().fromProgram().isBlank())
                        ? commarea.generalInfo().fromProgram()
                        : ProgramRegistry.CO_MEN_01C;
                yield Result.xctl(target, withTarget(commarea, target));
            }
            case PF4 -> Result.sendMap(buildScreen(CoTrn02Input.empty(), ""), commarea);
            case PF5 -> copyLastTransactionData(input, commarea);
            case CLEAR, PA1, PA2, PF1, PF2, PF6, PF7, PF8, PF9, PF10, PF11, PF12, OTHER ->
                    Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
        };
    }

    /**
     * COBOL paragraph {@code PROCESS-ENTER-KEY}. Validates key fields,
     * then data fields, then dispatches based on confirmation flag.
     */
    private Result processEnterKey(CoTrn02Input input, CardDemoCommarea commarea) {
        // Validate input key (account or card).
        ValidationResult keyValidation = validateInputKeyFields(input);
        if (keyValidation.error != null) {
            return Result.sendMap(buildScreen(input, keyValidation.error), commarea);
        }
        CoTrn02Input withResolved = keyValidation.resolved;

        // Validate data fields.
        String dataErr = validateInputDataFields(withResolved);
        if (dataErr != null) {
            return Result.sendMap(buildScreen(withResolved, dataErr), commarea);
        }

        // Check confirmation.
        String confirm = withResolved.confirmation();
        if (confirm.equals("Y") || confirm.equals("y")) {
            return addTransaction(withResolved, commarea);
        }
        if (confirm.isBlank()) {
            return Result.sendMap(buildScreen(withResolved, MSG_CONFIRM_REQUIRED), commarea);
        }
        if (confirm.equals("N") || confirm.equals("n")) {
            return Result.sendMap(buildScreen(withResolved, MSG_CONFIRM_REQUIRED), commarea);
        }
        return Result.sendMap(buildScreen(withResolved, MSG_INVALID_CONFIRM), commarea);
    }

    /**
     * Holder for validation result + (possibly modified) input.
     */
    private record ValidationResult(String error, CoTrn02Input resolved) {}

    /**
     * COBOL paragraph {@code VALIDATE-INPUT-KEY-FIELDS}. The COBOL
     * EVALUATE TRUE chain: if ACCTID provided, look up CXACAIX and
     * populate CARDNUM; else if CARDNUM provided, look up CCXREF and
     * populate ACCTID; else error.
     */
    private ValidationResult validateInputKeyFields(CoTrn02Input input) {
        if (!isBlankOrLow(input.accountId())) {
            if (!isNumeric(input.accountId())) {
                return new ValidationResult(MSG_ACCT_NUMERIC, input);
            }
            long acctId;
            try {
                acctId = Long.parseLong(input.accountId().trim());
            } catch (NumberFormatException nfe) {
                return new ValidationResult(MSG_ACCT_NUMERIC, input);
            }
            Optional<CardXrefRecord> xref;
            try (Stream<CardXrefRecord> stream = cardXref.findByAccountId(acctId)) {
                xref = stream.findFirst();
            }
            if (xref.isEmpty()) {
                return new ValidationResult(MSG_ACCT_NOT_FOUND, input);
            }
            CoTrn02Input resolved = withCardNumber(input, xref.get().xrefCardNum());
            return new ValidationResult(null, resolved);
        }
        if (!isBlankOrLow(input.cardNumber())) {
            if (!isNumeric(input.cardNumber())) {
                return new ValidationResult(MSG_CARD_NUMERIC, input);
            }
            Optional<CardXrefRecord> xref = cardXref.findByCardNum(input.cardNumber().trim());
            if (xref.isEmpty()) {
                return new ValidationResult(MSG_CARD_NOT_FOUND, input);
            }
            CoTrn02Input resolved = withAccountId(input,
                    String.format("%011d", xref.get().xrefAcctId()));
            return new ValidationResult(null, resolved);
        }
        return new ValidationResult(MSG_ACCT_OR_CARD_REQUIRED, input);
    }

    /**
     * COBOL paragraph {@code VALIDATE-INPUT-DATA-FIELDS}. 11 empty
     * checks + 2 numeric checks + amount format check + 2 date format
     * checks + 2 date validity checks + merchant id numeric check.
     */
    private String validateInputDataFields(CoTrn02Input input) {
        // Empty checks (COBOL order preserved).
        if (isBlankOrLow(input.typeCode())) return MSG_TYPE_EMPTY;
        if (isBlankOrLow(input.categoryCode())) return MSG_CAT_EMPTY;
        if (isBlankOrLow(input.source())) return MSG_SOURCE_EMPTY;
        if (isBlankOrLow(input.description())) return MSG_DESC_EMPTY;
        if (isBlankOrLow(input.amount())) return MSG_AMT_EMPTY;
        if (isBlankOrLow(input.origDate())) return MSG_ORIG_DT_EMPTY;
        if (isBlankOrLow(input.procDate())) return MSG_PROC_DT_EMPTY;
        if (isBlankOrLow(input.merchantId())) return MSG_MID_EMPTY;
        if (isBlankOrLow(input.merchantName())) return MSG_MNAME_EMPTY;
        if (isBlankOrLow(input.merchantCity())) return MSG_MCITY_EMPTY;
        if (isBlankOrLow(input.merchantZip())) return MSG_MZIP_EMPTY;

        // Numeric checks.
        if (!isNumeric(input.typeCode())) return MSG_TYPE_NUMERIC;
        if (!isNumeric(input.categoryCode())) return MSG_CAT_NUMERIC;

        // Amount format.
        if (!AMOUNT_PATTERN.matcher(input.amount()).matches()) return MSG_AMT_FORMAT;

        // Date formats.
        if (!DATE_PATTERN.matcher(input.origDate()).matches()) return MSG_ORIG_DT_FORMAT;
        if (!DATE_PATTERN.matcher(input.procDate()).matches()) return MSG_PROC_DT_FORMAT;

        // Date validity via CSUTLDTC equivalent.
        DateValidator.Result origRes = dateValidator.validate(
                new DateValidator.Input(input.origDate(), "yyyy-MM-dd"));
        if (origRes.severity() != DateValidator.Severity.OK
                && !"2513".equals(origRes.msgNumber())) {
            return MSG_ORIG_DT_INVALID;
        }
        DateValidator.Result procRes = dateValidator.validate(
                new DateValidator.Input(input.procDate(), "yyyy-MM-dd"));
        if (procRes.severity() != DateValidator.Severity.OK
                && !"2513".equals(procRes.msgNumber())) {
            return MSG_PROC_DT_INVALID;
        }

        // Merchant ID numeric.
        if (!isNumeric(input.merchantId())) return MSG_MID_NUMERIC;

        return null;
    }

    /**
     * COBOL paragraph {@code ADD-TRANSACTION}. Find max TRAN-ID (via
     * STARTBR/READPREV/ENDBR), add 1, build TRAN-RECORD, WRITE.
     */
    private Result addTransaction(CoTrn02Input input, CardDemoCommarea commarea) {
        long maxId = 0;
        try (Stream<TranRecord> stream = transactions.streamSequential()) {
            for (TranRecord r : (Iterable<TranRecord>) stream::iterator) {
                try {
                    long id = Long.parseLong(r.tranId().trim());
                    if (id > maxId) maxId = id;
                } catch (NumberFormatException nfe) {
                    // ignore non-numeric trnIds
                }
            }
        }
        long newId = maxId + 1;
        String tranId = String.format("%016d", newId);

        BigDecimal tranAmount;
        try {
            tranAmount = new BigDecimal(input.amount().trim()).setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
        } catch (NumberFormatException nfe) {
            return Result.sendMap(buildScreen(input, MSG_AMT_FORMAT), commarea);
        }

        int catCd;
        long merchantId;
        try {
            catCd = Integer.parseInt(input.categoryCode().trim());
            merchantId = Long.parseLong(input.merchantId().trim());
        } catch (NumberFormatException nfe) {
            return Result.sendMap(buildScreen(input, MSG_TYPE_NUMERIC), commarea);
        }

        TranRecord newTran = new TranRecord(
                tranId,
                input.typeCode().trim(),
                catCd,
                input.source(),
                input.description(),
                tranAmount,
                merchantId,
                input.merchantName(),
                input.merchantCity(),
                input.merchantZip(),
                input.cardNumber(),
                input.origDate(),
                input.procDate(),
                new byte[TranRecord.LEN_FILLER]
        );

        try {
            transactions.append(newTran);
            // Success: COBOL STRING construct preserves the double space:
            // 'Transaction added successfully. ' DELIMITED BY SIZE
            // ' Your Tran ID is ' DELIMITED BY SIZE
            // TRAN-ID DELIMITED BY SPACE
            // '.' DELIMITED BY SIZE
            // The leading space on " Your" and trailing dot on TRAN-ID + "." yield
            // "Transaction added successfully.  Your Tran ID is " + token(TRAN-ID) + "."
            String msg = "Transaction added successfully.  Your Tran ID is "
                    + firstSpaceDelimitedToken(tranId) + ".";
            return Result.sendMap(buildSuccessScreen(commarea, msg), commarea);
        } catch (IllegalStateException dup) {
            // Some adapters throw IllegalStateException on duplicate key.
            return Result.sendMap(buildScreen(input, MSG_TRAN_DUPLICATE), commarea);
        } catch (RuntimeException re) {
            return Result.sendMap(buildScreen(input, MSG_TRAN_ADD_ERROR), commarea);
        }
    }

    /**
     * COBOL paragraph {@code COPY-LAST-TRAN-DATA} (PF5). Look up the
     * last transaction (max TRAN-ID) and populate the input fields.
     */
    private Result copyLastTransactionData(CoTrn02Input input, CardDemoCommarea commarea) {
        // Validate key first (same as enter key path).
        ValidationResult keyValidation = validateInputKeyFields(input);
        if (keyValidation.error != null) {
            return Result.sendMap(buildScreen(input, keyValidation.error), commarea);
        }
        CoTrn02Input withResolved = keyValidation.resolved;

        // Find last (highest) transaction.
        TranRecord last = null;
        long maxId = 0;
        try (Stream<TranRecord> stream = transactions.streamSequential()) {
            for (TranRecord r : (Iterable<TranRecord>) stream::iterator) {
                try {
                    long id = Long.parseLong(r.tranId().trim());
                    if (id > maxId) {
                        maxId = id;
                        last = r;
                    }
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            }
        }

        if (last == null) {
            // No prior transactions — proceed to enter key with current input.
            return processEnterKey(withResolved, commarea);
        }

        // Populate input fields from the last transaction, then re-process enter key.
        CoTrn02Input filled = new CoTrn02Input(
                withResolved.trnName(), withResolved.title01(), withResolved.curDate(),
                withResolved.pgmName(), withResolved.title02(), withResolved.curTime(),
                withResolved.accountId(), withResolved.cardNumber(),
                last.tranTypeCd(),
                String.format("%04d", last.tranCatCd()),
                last.tranSource(),
                last.tranDesc(),
                formatTranAmount(last.tranAmt()),
                last.tranOrigTs(),
                last.tranProcTs(),
                String.format("%09d", last.tranMerchantId()),
                last.tranMerchantName(),
                last.tranMerchantCity(),
                last.tranMerchantZip(),
                withResolved.confirmation(),
                withResolved.errMsg(),
                CoTrn02Input.AidKey.ENTER);

        return processEnterKey(filled, commarea);
    }

    // ----- Screen builders --------------------------------------------------

    private CoTrn02Output buildScreen(CoTrn02Input input, String errMsg) {
        return new CoTrn02Output(
                "AWS Mainframe Modernization with CardDemo Application",
                "Add Transaction",
                TRANSACTION_ID,
                PROGRAM_ID,
                todayDate(),
                nowTime(),
                input.accountId(),
                input.cardNumber(),
                input.typeCode(),
                input.categoryCode(),
                input.source(),
                input.description(),
                input.amount(),
                input.origDate(),
                input.procDate(),
                input.merchantId(),
                input.merchantName(),
                input.merchantCity(),
                input.merchantZip(),
                input.confirmation(),
                errMsg,
                CoTrn02Output.FieldColor.RED
        );
    }

    private CoTrn02Output buildSuccessScreen(CardDemoCommarea commarea, String successMsg) {
        // After successful write, INITIALIZE-ALL-FIELDS clears all input fields.
        return new CoTrn02Output(
                "AWS Mainframe Modernization with CardDemo Application",
                "Add Transaction",
                TRANSACTION_ID,
                PROGRAM_ID,
                todayDate(),
                nowTime(),
                "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                successMsg,
                CoTrn02Output.FieldColor.GREEN
        );
    }

    // ----- Helpers --------------------------------------------------------

    private static CoTrn02Input withCardNumber(CoTrn02Input input, String newCard) {
        return new CoTrn02Input(
                input.trnName(), input.title01(), input.curDate(), input.pgmName(),
                input.title02(), input.curTime(),
                input.accountId(), newCard,
                input.typeCode(), input.categoryCode(), input.source(), input.description(),
                input.amount(), input.origDate(), input.procDate(),
                input.merchantId(), input.merchantName(), input.merchantCity(), input.merchantZip(),
                input.confirmation(), input.errMsg(), input.aidKey());
    }

    private static CoTrn02Input withAccountId(CoTrn02Input input, String newAcct) {
        return new CoTrn02Input(
                input.trnName(), input.title01(), input.curDate(), input.pgmName(),
                input.title02(), input.curTime(),
                newAcct, input.cardNumber(),
                input.typeCode(), input.categoryCode(), input.source(), input.description(),
                input.amount(), input.origDate(), input.procDate(),
                input.merchantId(), input.merchantName(), input.merchantCity(), input.merchantZip(),
                input.confirmation(), input.errMsg(), input.aidKey());
    }

    private static String formatTranAmount(BigDecimal v) {
        BigDecimal scaled = v.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
        char sign = scaled.signum() < 0 ? '-' : '+';
        String abs = scaled.abs().toPlainString();  // e.g. 12345.67
        int dotIdx = abs.indexOf('.');
        String whole = (dotIdx < 0) ? abs : abs.substring(0, dotIdx);
        String frac = (dotIdx < 0) ? "00" : abs.substring(dotIdx + 1);
        // Pad to 8 digits whole + dot + 2 digits frac = total 12 chars (with sign).
        String paddedWhole = String.format("%08d", Long.parseLong(whole));
        return sign + paddedWhole + "." + frac;
    }

    private static String firstSpaceDelimitedToken(String s) {
        if (s == null || s.isEmpty()) return "";
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    private static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\0') return false;
        }
        return true;
    }

    private static boolean isNumeric(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        return NUMERIC.matcher(t).matches();
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
