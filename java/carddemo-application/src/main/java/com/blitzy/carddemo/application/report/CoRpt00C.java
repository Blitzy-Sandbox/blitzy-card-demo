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
package com.blitzy.carddemo.application.report;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.application.util.DateValidator;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Java translation of COBOL program {@code CORPT00C}
 * ({@code app/cbl/CORPT00C.cbl}, CICS transaction {@code CR00}).
 *
 * <p><b>Purpose:</b> Solicit operator selection of a Monthly, Yearly, or
 * Custom-range transaction report and submit a batch job to print it.
 *
 * <h2>Translation notes — CICS TDQ JOBS replaced by direct invocation</h2>
 * <p>The COBOL program uses the CICS extra-partition Transient Data Queue
 * named {@code JOBS} to submit a JCL job stream to the z/OS internal
 * reader (see paragraph {@code WIRTE-JOBSUB-TDQ} at lines 510-535 of
 * {@code app/cbl/CORPT00C.cbl}; the typo &ldquo;WIRTE&rdquo; is preserved
 * verbatim in {@code MIGRATION_NOTES.md} &sect;1.4.4 per AAP &sect;0.7.1
 * idiom-for-idiom mandate). Per AAP &sect;0.6.7 and &sect;0.2.2, the
 * Blitzy refactor does not introduce a CICS TDQ replacement (mainframe
 * replacement orchestration is out of scope). The TDQ write is translated
 * to a logical equivalent: this controller returns a {@link Result} whose
 * {@code reportRequest} field captures the report name, start date, and
 * end date that the COBOL paragraph would have written to {@code JOBS}.
 * A downstream batch driver in {@code carddemo-batch} reads this request
 * and invokes the {@code TransactionReportApp} main class directly,
 * which is equivalent to what {@code STEP10 EXEC PROC=TRANREPT} in the
 * COBOL JCL would have done.
 *
 * <p>This deviation is documented in {@code MIGRATION_NOTES.md} &sect;1.3.2
 * (&ldquo;CORPT00C CICS TDQ write to direct method invocation&rdquo;).
 *
 * <h2>Verbatim error messages</h2>
 * <p>All error message constants below are preserved byte-for-byte from
 * the COBOL source per AAP &sect;0.7.1.
 *
 * @see DateValidator
 * @see CoRpt00Input
 * @see CoRpt00Output
 */
@CobolProgram(
        value = "CORPT00C",
        sourcePath = "app/cbl/CORPT00C.cbl",
        notes = "Transaction report submission. CICS TDQ JOBS write " +
                "translated to direct method invocation (Result.reportRequest " +
                "captures monthly/yearly/custom + start/end date) per " +
                "AAP §0.6.7 and §0.2.2 (no mainframe replacement orchestration)."
)
public final class CoRpt00C {

    private static final String PROGRAM_ID = "CORPT00C";
    private static final String TRANSACTION_ID = "CR00";

    // Verbatim error messages preserved from COBOL source per AAP §0.7.1.
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";
    private static final String MSG_SDT_MM_EMPTY = "Start Date - Month can NOT be empty...";
    private static final String MSG_SDT_DD_EMPTY = "Start Date - Day can NOT be empty...";
    private static final String MSG_SDT_YYYY_EMPTY = "Start Date - Year can NOT be empty...";
    private static final String MSG_EDT_MM_EMPTY = "End Date - Month can NOT be empty...";
    private static final String MSG_EDT_DD_EMPTY = "End Date - Day can NOT be empty...";
    private static final String MSG_EDT_YYYY_EMPTY = "End Date - Year can NOT be empty...";
    private static final String MSG_SDT_MM_INVALID = "Start Date - Not a valid Month...";
    private static final String MSG_SDT_DD_INVALID = "Start Date - Not a valid Day...";
    private static final String MSG_SDT_YYYY_INVALID = "Start Date - Not a valid Year...";
    private static final String MSG_EDT_MM_INVALID = "End Date - Not a valid Month...";
    private static final String MSG_EDT_DD_INVALID = "End Date - Not a valid Day...";
    private static final String MSG_EDT_YYYY_INVALID = "End Date - Not a valid Year...";
    private static final String MSG_SDT_INVALID = "Start Date - Not a valid date...";
    private static final String MSG_EDT_INVALID = "End Date - Not a valid date...";
    private static final String MSG_SELECT_TYPE = "Select a report type to print report...";
    private static final String MSG_TDQ_ERROR = "Unable to Write TDQ (JOBS)...";

    private static final String REPORT_MONTHLY = "Monthly";
    private static final String REPORT_YEARLY = "Yearly";
    private static final String REPORT_CUSTOM = "Custom";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");

    private final ProgramRegistry programRegistry;

    /**
     * Constructs a CORPT00C controller. {@code programRegistry} is required
     * (constructor injection; no Spring container per AAP &sect;0.3).
     *
     * <p>The {@link DateValidator} collaborator is no longer injected: per
     * its CSUTLDTC translation it is a utility class with only static
     * methods, so call sites invoke {@code DateValidator.validate(...)}
     * directly.
     *
     * @param programRegistry  dynamic dispatch routing for XCTL targets;
     *                         must be non-null
     */
    public CoRpt00C(ProgramRegistry programRegistry) {
        this.programRegistry = programRegistry;
    }

    /**
     * Result wrapper signalling either a SEND-MAP (with an output and updated
     * commarea), an XCTL (with the target program id and updated commarea), or
     * a successful report-submission request (with the captured parameters).
     *
     * <p>Mirrors {@code CoTrn01C.Result} convention plus a {@code reportRequest}
     * factory that captures what the COBOL paragraph {@code WIRTE-JOBSUB-TDQ}
     * would have written to the {@code JOBS} TDQ.
     */
    public record Result(
            CoRpt00Output output,
            CardDemoCommarea commarea,
            String xctlTo,
            ReportRequest reportRequest) {

        public static Result sendMap(CoRpt00Output output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId, null);
        }

        public static Result submitted(CoRpt00Output output, CardDemoCommarea commarea,
                                       ReportRequest request) {
            return new Result(output, commarea, null, request);
        }

        public boolean isSendMap() { return output != null && reportRequest == null; }
        public boolean isXctl() { return xctlTo != null; }
        public boolean isSubmitted() { return reportRequest != null; }
    }

    /**
     * Captured equivalent of the COBOL JCL job stream that {@code WIRTE-JOBSUB-TDQ}
     * would have written to the {@code JOBS} TDQ. Direct downstream
     * invocation of {@code TransactionReportApp} per AAP &sect;0.6.7
     * &mdash; equivalent observable outcome to JES submission.
     */
    public record ReportRequest(String reportName, LocalDate startDate, LocalDate endDate) { }

    /**
     * Convenience overload with no preselected report request.
     */
    public Result run(CoRpt00Input input, CardDemoCommarea commarea) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        // First-time entry (CDEMO-PGM-REENTER is false in COBOL):
        if (!(commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            return Result.sendMap(buildScreen(CoRpt00Input.empty(), ""), reentered);
        }

        // Re-entry: dispatch by AID key.
        if (input == null) {
            return Result.sendMap(buildScreen(CoRpt00Input.empty(), ""), commarea);
        }

        // EVALUATE EIBAID (lines 184-196 of app/cbl/CORPT00C.cbl):
        //   WHEN DFHENTER  → process selection
        //   WHEN DFHPF3    → return to previous program (default COMEN01C)
        //   WHEN OTHER     → invalid-key error
        // Translated to an exhaustive pattern-matching switch over the sealed
        // com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey hierarchy (16
        // record permits) per AAP §0.6.10. NO `default` branch (compiler-
        // enforced exhaustiveness).
        return switch (input.aidKey()) {
            case AidKey.Enter ignored -> processEnterKey(input, commarea);
            case AidKey.PfKey03 ignored -> {
                String target = (commarea.cdemoGeneralInfo().toProgram() != null
                        && !commarea.cdemoGeneralInfo().toProgram().isBlank())
                        ? commarea.cdemoGeneralInfo().toProgram()
                        : ProgramRegistry.CO_MEN_01C;
                yield Result.xctl(target, withTarget(commarea, target));
            }
            // WHEN OTHER: every AID key other than ENTER and PF3 falls
            // through to the "Invalid key pressed..." error path. Each
            // permit is enumerated to satisfy exhaustive-switch checking
            // over the sealed AidKey hierarchy without resorting to a
            // forbidden `default` branch.
            case AidKey.Clear ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.Pa1 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.Pa2 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey01 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey02 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey04 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey05 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey06 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey07 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey08 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey09 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey10 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey11 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
            case AidKey.PfKey12 ignored -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
        };
    }

    /**
     * COBOL paragraph {@code PROCESS-ENTER-KEY} (lines 207-453). The
     * EVALUATE TRUE chain dispatches by whichever report-type flag is
     * non-blank (MONTHLYI, YEARLYI, or CUSTOMI). Custom requires full
     * date validation. Confirm flag is validated by SUBMIT-JOB-TO-INTRDR.
     */
    private Result processEnterKey(CoRpt00Input input, CardDemoCommarea commarea) {
        // Determine which report type was selected (COBOL EVALUATE TRUE order).
        boolean monthlySelected = !isBlankOrLow(input.monthly());
        boolean yearlySelected = !isBlankOrLow(input.yearly());
        boolean customSelected = !isBlankOrLow(input.custom());

        if (!monthlySelected && !yearlySelected && !customSelected) {
            return Result.sendMap(buildScreen(input, MSG_SELECT_TYPE), commarea);
        }

        String reportName;
        LocalDate startDate;
        LocalDate endDate;

        if (monthlySelected) {
            reportName = REPORT_MONTHLY;
            // Start = first day of current month; End = last day of current month.
            LocalDate today = LocalDate.now();
            startDate = today.withDayOfMonth(1);
            endDate = today.withDayOfMonth(today.lengthOfMonth());
        } else if (yearlySelected) {
            reportName = REPORT_YEARLY;
            // Start = Jan 1 of current year; End = Dec 31 of current year.
            LocalDate today = LocalDate.now();
            startDate = today.withDayOfYear(1);
            endDate = today.withMonth(12).withDayOfMonth(31);
        } else {
            // CUSTOM — validate all six date pieces.
            reportName = REPORT_CUSTOM;
            // Empty checks (COBOL order preserved).
            if (isBlankOrLow(input.startMonth())) {
                return Result.sendMap(buildScreen(input, MSG_SDT_MM_EMPTY), commarea);
            }
            if (isBlankOrLow(input.startDay())) {
                return Result.sendMap(buildScreen(input, MSG_SDT_DD_EMPTY), commarea);
            }
            if (isBlankOrLow(input.startYear())) {
                return Result.sendMap(buildScreen(input, MSG_SDT_YYYY_EMPTY), commarea);
            }
            if (isBlankOrLow(input.endMonth())) {
                return Result.sendMap(buildScreen(input, MSG_EDT_MM_EMPTY), commarea);
            }
            if (isBlankOrLow(input.endDay())) {
                return Result.sendMap(buildScreen(input, MSG_EDT_DD_EMPTY), commarea);
            }
            if (isBlankOrLow(input.endYear())) {
                return Result.sendMap(buildScreen(input, MSG_EDT_YYYY_EMPTY), commarea);
            }
            // Numeric & range checks for each date piece.
            // COBOL: SDTMMI > '12' is a string comparison; numeric comparison required.
            if (!isValidMonth(input.startMonth())) {
                return Result.sendMap(buildScreen(input, MSG_SDT_MM_INVALID), commarea);
            }
            if (!isValidDay(input.startDay())) {
                return Result.sendMap(buildScreen(input, MSG_SDT_DD_INVALID), commarea);
            }
            if (!isNumeric(input.startYear())) {
                return Result.sendMap(buildScreen(input, MSG_SDT_YYYY_INVALID), commarea);
            }
            if (!isValidMonth(input.endMonth())) {
                return Result.sendMap(buildScreen(input, MSG_EDT_MM_INVALID), commarea);
            }
            if (!isValidDay(input.endDay())) {
                return Result.sendMap(buildScreen(input, MSG_EDT_DD_INVALID), commarea);
            }
            if (!isNumeric(input.endYear())) {
                return Result.sendMap(buildScreen(input, MSG_EDT_YYYY_INVALID), commarea);
            }

            // Composite date validation via CSUTLDTC equivalent.
            String startDateString = String.format("%04d-%02d-%02d",
                    Integer.parseInt(input.startYear().trim()),
                    Integer.parseInt(input.startMonth().trim()),
                    Integer.parseInt(input.startDay().trim()));
            String endDateString = String.format("%04d-%02d-%02d",
                    Integer.parseInt(input.endYear().trim()),
                    Integer.parseInt(input.endMonth().trim()),
                    Integer.parseInt(input.endDay().trim()));

            // CSUTLDTC equivalent — DateValidator.validate is now static
            // (utility class) and takes the COBOL format mask "YYYY-MM-DD".
            // The "2513" msgNo (FC-UNSUPP-RANGE) tolerance is preserved:
            // a year outside CEEDAYS' supported Gregorian range is allowed
            // through since the caller has already range-checked the year
            // digits and we still attempt LocalDate.parse below.
            var sdtRes = DateValidator.validate(startDateString, "YYYY-MM-DD");
            if (!sdtRes.isSuccess()
                    && !"2513".equals(sdtRes.msgNo().strip())) {
                return Result.sendMap(buildScreen(input, MSG_SDT_INVALID), commarea);
            }

            var edtRes = DateValidator.validate(endDateString, "YYYY-MM-DD");
            if (!edtRes.isSuccess()
                    && !"2513".equals(edtRes.msgNo().strip())) {
                return Result.sendMap(buildScreen(input, MSG_EDT_INVALID), commarea);
            }

            try {
                startDate = LocalDate.parse(startDateString, ISO_DATE);
                endDate = LocalDate.parse(endDateString, ISO_DATE);
            } catch (java.time.format.DateTimeParseException dtpe) {
                return Result.sendMap(buildScreen(input, MSG_SDT_INVALID), commarea);
            }
        }

        // CONFIRM check (COBOL SUBMIT-JOB-TO-INTRDR paragraph).
        return submitJobToIntrdr(input, commarea, reportName, startDate, endDate);
    }

    /**
     * COBOL paragraph {@code SUBMIT-JOB-TO-INTRDR} (lines 461-509).
     * Confirms the operator's intent before "submitting" the job to the
     * internal reader. Per AAP §0.6.7 / §0.2.2, the TDQ write loop is
     * replaced by capturing the report request and returning a
     * {@link Result#submitted}.
     */
    private Result submitJobToIntrdr(CoRpt00Input input, CardDemoCommarea commarea,
                                     String reportName, LocalDate startDate, LocalDate endDate) {
        if (isBlankOrLow(input.confirmation())) {
            String msg = "Please confirm to print the " + reportName + " report...";
            return Result.sendMap(buildScreen(input, msg), commarea);
        }

        String confirm = input.confirmation();
        if (confirm.equals("Y") || confirm.equals("y")) {
            // Submit: equivalent to writing the JCL stream to the JOBS TDQ.
            // Per AAP §0.6.7 — TDQ write becomes direct method invocation.
            CoRpt00Output output = buildSuccessScreen(input, reportName);
            return Result.submitted(output, commarea, new ReportRequest(reportName, startDate, endDate));
        }
        if (confirm.equals("N") || confirm.equals("n")) {
            // Cancel: clear all fields, redisplay screen.
            return Result.sendMap(buildScreen(CoRpt00Input.empty(), ""), commarea);
        }
        // Invalid confirmation value.
        String msg = "\"" + firstSpaceDelimitedToken(confirm)
                + "\" is not a valid value to confirm...";
        return Result.sendMap(buildScreen(input, msg), commarea);
    }

    /**
     * Builds the SEND-MAP output for an error or info path. Header fields
     * are populated by POPULATE-HEADER-INFO.
     */
    private CoRpt00Output buildScreen(CoRpt00Input input, String errMsg) {
        return new CoRpt00Output(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "Print Transaction Report",
                nowTime(),
                input.monthly(),
                input.yearly(),
                input.custom(),
                input.startMonth(),
                input.startDay(),
                input.startYear(),
                input.endMonth(),
                input.endDay(),
                input.endYear(),
                input.confirmation(),
                errMsg,
                errMsg.isEmpty() ? CoRpt00Output.FieldColor.RED : CoRpt00Output.FieldColor.RED,
                "MONTHLYL"
        );
    }

    /**
     * Builds the success SEND-MAP output. Note: COBOL uses
     * {@code MOVE DFHGREEN TO ERRMSGC} so the message renders in green.
     * Equivalent to {@link CoRpt00Output.FieldColor#GREEN}.
     *
     * <p>COBOL constructs the success message via {@code STRING}:
     * <pre>
     *   STRING WS-REPORT-NAME DELIMITED BY SPACE
     *          ' report submitted for printing ...' DELIMITED BY SIZE
     *          INTO WS-MESSAGE
     * </pre>
     * The {@code DELIMITED BY SPACE} extracts the first whitespace-delimited
     * token of the report name &mdash; verbatim semantics preserved.
     */
    private CoRpt00Output buildSuccessScreen(CoRpt00Input input, String reportName) {
        String msg = firstSpaceDelimitedToken(reportName) + " report submitted for printing ...";
        // After successful submit, INITIALIZE-ALL-FIELDS blanks every input field.
        return new CoRpt00Output(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "Print Transaction Report",
                nowTime(),
                "", "", "",         // monthly/yearly/custom cleared
                "", "", "",         // start date cleared
                "", "", "",         // end date cleared
                "",                 // confirmation cleared
                msg,
                CoRpt00Output.FieldColor.GREEN,
                "MONTHLYL"
        );
    }

    // ----- Helpers (paragraph translations & utilities) ---------------------

    private static String firstSpaceDelimitedToken(String s) {
        if (s == null || s.isEmpty()) return "";
        int spaceIdx = s.indexOf(' ');
        return spaceIdx < 0 ? s : s.substring(0, spaceIdx);
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
        return NUMERIC_PATTERN.matcher(t).matches();
    }

    /**
     * Checks SDTMMI / EDTMMI for "Not a valid Month" per COBOL semantics.
     * COBOL uses {@code IS NOT NUMERIC OR > '12'} (string compare) which
     * treats anything sorting above "12" as invalid; in practice this
     * means 13-99. We use a numeric range check, which is the same
     * observable outcome for valid two-digit inputs.
     */
    private static boolean isValidMonth(String s) {
        if (!isNumeric(s)) return false;
        int v = Integer.parseInt(s.trim());
        return v >= 1 && v <= 12;
    }

    /**
     * Checks SDTDDI / EDTDDI for "Not a valid Day". COBOL uses
     * {@code IS NOT NUMERIC OR > '31'}.
     */
    private static boolean isValidDay(String s) {
        if (!isNumeric(s)) return false;
        int v = Integer.parseInt(s.trim());
        return v >= 1 && v <= 31;
    }

    private static CardDemoCommarea withTarget(CardDemoCommarea commarea, String toProgram) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                gi.toTranId(),
                toProgram,
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return commarea.withCdemoGeneralInfo(updated);
    }

    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                gi.fromTranId(),
                gi.fromProgram(),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                ctx);
        return commarea.withCdemoGeneralInfo(updated);
    }

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
