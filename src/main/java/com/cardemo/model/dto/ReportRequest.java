package com.cardemo.model.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Size;

/**
 * DTO capturing report generation criteria from the BMS symbolic map CORPT00.CPY
 * (report submission screen). Used by {@code ReportController.POST /api/reports/submit}.
 *
 * <p>The COBOL program CORPT00C.cbl writes report parameters to a CICS TDQ
 * (Transient Data Queue) named JOBS; in the Java migration, this triggers an
 * SQS message via {@code ReportSubmissionService} that launches a Spring Batch job.</p>
 *
 * <h3>COBOL Field Mapping (CORPT0AI input view)</h3>
 * <ul>
 *   <li>{@code monthly}  ← MONTHLYI PIC X(1) (line 60)</li>
 *   <li>{@code yearly}   ← YEARLYI  PIC X(1) (line 66)</li>
 *   <li>{@code custom}   ← CUSTOMI  PIC X(1) (line 72)</li>
 *   <li>{@code startDate} ← SDTMMI PIC X(2) + SDTDDI PIC X(2) + SDTYYYYI PIC X(4) (lines 78, 84, 90)</li>
 *   <li>{@code endDate}   ← EDTMMI PIC X(2) + EDTDDI PIC X(2) + EDTYYYYI PIC X(4) (lines 96, 102, 108)</li>
 *   <li>{@code confirm}  ← CONFIRMI PIC X(1) (line 114)</li>
 * </ul>
 *
 * <p>Rather than carrying individual month/day/year fields (as in the COBOL BMS map),
 * this DTO consolidates them into {@link LocalDate} instances for idiomatic Java
 * date handling while preserving all semantic intent.</p>
 */
public class ReportRequest {

    // ------------------------------------------------------------------ Fields

    /**
     * Monthly report selector.
     * Maps MONTHLYI PIC X(1) from CORPT00.CPY line 60.
     * In COBOL, any non-space character in MONTHLYI means the monthly report is selected.
     */
    private boolean monthly;

    /**
     * Yearly report selector.
     * Maps YEARLYI PIC X(1) from CORPT00.CPY line 66.
     * In COBOL, any non-space character in YEARLYI means the yearly report is selected.
     */
    private boolean yearly;

    /**
     * Custom date-range report selector.
     * Maps CUSTOMI PIC X(1) from CORPT00.CPY line 72.
     * When {@code true}, {@link #startDate} and {@link #endDate} must be provided.
     */
    private boolean custom;

    /**
     * Start date for custom date-range reports.
     * Composed from SDTMMI (month), SDTDDI (day), and SDTYYYYI (year) fields
     * at CORPT00.CPY lines 78, 84, and 90 respectively.
     * Required when {@link #custom} is {@code true}; may be {@code null} otherwise.
     */
    private LocalDate startDate;

    /**
     * End date for custom date-range reports.
     * Composed from EDTMMI (month), EDTDDI (day), and EDTYYYYI (year) fields
     * at CORPT00.CPY lines 96, 102, and 108 respectively.
     * Required when {@link #custom} is {@code true}; may be {@code null} otherwise.
     */
    private LocalDate endDate;

    /**
     * Confirmation indicator.
     * Maps CONFIRMI PIC X(1) from CORPT00.CPY line 114.
     * Must be {@code "Y"} to confirm submission or {@code "N"} to cancel.
     */
    @Size(max = 1, message = "Confirm must be a single character")
    private String confirm;

    // ------------------------------------------------------------- Constructors

    /**
     * No-args constructor required for Jackson deserialization and framework
     * instantiation (e.g., Spring MVC {@code @RequestBody} binding).
     */
    public ReportRequest() {
        // Default no-args constructor
    }

    /**
     * All-args constructor for programmatic construction and test convenience.
     *
     * @param monthly   {@code true} to request a monthly report
     * @param yearly    {@code true} to request a yearly report
     * @param custom    {@code true} to request a custom date-range report
     * @param startDate start date for custom reports (nullable for monthly/yearly)
     * @param endDate   end date for custom reports (nullable for monthly/yearly)
     * @param confirm   confirmation indicator ({@code "Y"} or {@code "N"})
     */
    public ReportRequest(boolean monthly, boolean yearly, boolean custom,
                         LocalDate startDate, LocalDate endDate, String confirm) {
        this.monthly = monthly;
        this.yearly = yearly;
        this.custom = custom;
        this.startDate = startDate;
        this.endDate = endDate;
        this.confirm = confirm;
    }

    // ------------------------------------------------------ Getters and Setters

    /**
     * Returns whether a monthly report is requested.
     *
     * @return {@code true} if the monthly report type is selected
     */
    public boolean isMonthly() {
        return monthly;
    }

    /**
     * Sets the monthly report selection flag.
     *
     * @param monthly {@code true} to select the monthly report type
     */
    public void setMonthly(boolean monthly) {
        this.monthly = monthly;
    }

    /**
     * Returns whether a yearly report is requested.
     *
     * @return {@code true} if the yearly report type is selected
     */
    public boolean isYearly() {
        return yearly;
    }

    /**
     * Sets the yearly report selection flag.
     *
     * @param yearly {@code true} to select the yearly report type
     */
    public void setYearly(boolean yearly) {
        this.yearly = yearly;
    }

    /**
     * Returns whether a custom date-range report is requested.
     *
     * @return {@code true} if the custom report type is selected
     */
    public boolean isCustom() {
        return custom;
    }

    /**
     * Sets the custom date-range report selection flag.
     *
     * @param custom {@code true} to select the custom date-range report type
     */
    public void setCustom(boolean custom) {
        this.custom = custom;
    }

    /**
     * Returns the start date for a custom date-range report.
     *
     * @return the start date, or {@code null} if not a custom report
     */
    public LocalDate getStartDate() {
        return startDate;
    }

    /**
     * Sets the start date for a custom date-range report.
     *
     * @param startDate the start date for the custom range
     */
    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    /**
     * Returns the end date for a custom date-range report.
     *
     * @return the end date, or {@code null} if not a custom report
     */
    public LocalDate getEndDate() {
        return endDate;
    }

    /**
     * Sets the end date for a custom date-range report.
     *
     * @param endDate the end date for the custom range
     */
    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    /**
     * Returns the confirmation indicator.
     *
     * @return the confirmation string ({@code "Y"} or {@code "N"})
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets the confirmation indicator.
     *
     * @param confirm the confirmation value ({@code "Y"} to confirm, {@code "N"} to cancel)
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    // ---------------------------------------------------------------- toString

    /**
     * Returns a string representation of this report request, including all fields.
     * Useful for logging and diagnostics.
     *
     * @return a descriptive string containing all field values
     */
    @Override
    public String toString() {
        return "ReportRequest{" +
                "monthly=" + monthly +
                ", yearly=" + yearly +
                ", custom=" + custom +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", confirm='" + confirm + '\'' +
                '}';
    }
}
