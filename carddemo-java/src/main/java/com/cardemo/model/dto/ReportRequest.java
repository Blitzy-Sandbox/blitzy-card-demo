package com.cardemo.model.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * REST request payload for the CardDemo <strong>Transaction Report</strong> screen.
 *
 * <p>This Data Transfer Object is the Java 25 / Spring Boot 3.x replacement for the
 * <em>input</em> fields that the legacy 3270 report-criteria map received. On the
 * mainframe the screen was BMS map {@code CORPT0A} (mapset {@code CORPT00}, online
 * transaction {@code CR00}); its symbolic map is defined by the copybook
 * {@code app/cpy-bms/CORPT00.CPY}. The online program {@code app/cbl/CORPT00C.cbl}
 * issued {@code RECEIVE MAP('CORPT0A')} to read the operator's keystrokes into the
 * symbolic input structure {@code CORPT0AI}: the user chose a <strong>Monthly</strong>,
 * <strong>Yearly</strong> or <strong>Custom</strong> transaction report (a custom
 * report supplying an explicit start/end date as separate month/day/year parts), and
 * on a {@code 'Y'} confirmation the program wrote a report-request record to the CICS
 * Transient Data Queue {@code JOBS}, which triggered JES batch submission.</p>
 *
 * <p>In the migrated system this DTO is bound by {@code controller/ReportController}
 * to {@code POST /api/reports/submit} (blueprint {@code docs/technical-specifications.md}
 * L651): Jackson deserializes the JSON request body into an instance, Jakarta Bean
 * Validation enforces the field constraints declared below (the {@code @Valid} gate on
 * the controller method), and the validated payload is handed to
 * {@code service/report/ReportSubmissionService} (the translation of {@code CORPT00C},
 * blueprint L631), which <strong>publishes a report-submission message to AWS SQS</strong>
 * (queue {@code carddemo-report-jobs.fifo}) to trigger the Spring Batch report job. That
 * SQS publish is the modern stand-in for the legacy {@code WRITEQ TD} to the {@code JOBS}
 * queue and is the system's <em>sole</em> online&rarr;batch bridge (AAP &sect;0.6.3). This
 * DTO is therefore a pure boundary type &mdash; it carries no business logic, no I/O and
 * no static state.</p>
 *
 * <h2>Original COBOL symbolic input map (CORPT00.CPY &mdash; {@code 01 CORPT0AI})</h2>
 * <p>The 3270 symbolic map declared seventeen on-screen fields, but only ten of them
 * were operator <em>inputs</em>; the remainder were presentation chrome, the error line,
 * or BMS per-field control bytes. The ten modeled inputs are:</p>
 * <pre>{@code
 * 01  CORPT0AI.
 *     ...
 *     02  MONTHLYI   PIC X(1).   <-- modeled here as monthly  (Y/N report-type flag)
 *     ...
 *     02  YEARLYI    PIC X(1).   <-- modeled here as yearly   (Y/N report-type flag)
 *     ...
 *     02  CUSTOMI    PIC X(1).   <-- modeled here as custom   (Y/N report-type flag)
 *     ...
 *     02  SDTMMI     PIC X(2).   <-- modeled here as startMonth (custom start MM)
 *     ...
 *     02  SDTDDI     PIC X(2).   <-- modeled here as startDay   (custom start DD)
 *     ...
 *     02  SDTYYYYI   PIC X(4).   <-- modeled here as startYear  (custom start YYYY)
 *     ...
 *     02  EDTMMI     PIC X(2).   <-- modeled here as endMonth   (custom end MM)
 *     ...
 *     02  EDTDDI     PIC X(2).   <-- modeled here as endDay     (custom end DD)
 *     ...
 *     02  EDTYYYYI   PIC X(4).   <-- modeled here as endYear    (custom end YYYY)
 *     ...
 *     02  CONFIRMI   PIC X(1).   <-- modeled here as confirm  (Y/N submit confirmation)
 *     ...
 * }</pre>
 *
 * <h2>Scope &mdash; exactly ten request fields are modeled (AAP &sect;0.4.2)</h2>
 * <p>The following symbolic-map members are <strong>deliberately excluded</strong> from
 * the request because they are 3270 presentation/control artifacts with no REST request
 * equivalent:</p>
 * <ul>
 *   <li><strong>Screen chrome (output-only):</strong> {@code TRNNAMEI} ({@code X(4)}),
 *       {@code TITLE01I}/{@code TITLE02I} ({@code X(40)}), {@code CURDATEI}
 *       ({@code X(8)}), {@code CURTIMEI} ({@code X(8)}) and {@code PGMNAMEI}
 *       ({@code X(8)}) &mdash; banner, titles, date/time and program identifiers painted
 *       by the program, never typed by the user.</li>
 *   <li><strong>Error line (output-only):</strong> {@code ERRMSGI} ({@code X(78)})
 *       &mdash; the message the program wrote back (for example
 *       {@code 'Please select a report type...'}); in REST this becomes the response /
 *       error body, not a request field.</li>
 *   <li><strong>BMS per-field control bytes:</strong> the {@code ...L} (length),
 *       {@code ...F}/{@code ...A} (attribute/flag), and the redefined output bytes
 *       {@code ...C}/{@code ...P}/{@code ...H}/{@code ...V}/{@code ...O} in the
 *       {@code CORPT0AO} output redefinition &mdash; 3270 datastream metadata with no
 *       REST analogue.</li>
 *   <li><strong>AID / PF keys:</strong> the {@code DFHAID} attention identifiers
 *       (ENTER, PF3, &hellip;) do not map onto a request body; each AID maps to a
 *       distinct REST endpoint instead (AAP &sect;0.4.2).</li>
 * </ul>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code RECEIVE MAP} &rarr; REST request DTO.</strong> The CICS
 *       map-receive that populated {@code CORPT0AI} is replaced by Jackson binding a JSON
 *       body to this object; every character input becomes a {@link String} property.</li>
 *   <li><strong>Report type as three Y/N flags (no enum).</strong> The screen offered
 *       three independent single-character choice fields ({@code MONTHLYI},
 *       {@code YEARLYI}, {@code CUSTOMI}); they are preserved here as three
 *       {@link String}-of-length-one flags exactly as the operator saw them. No
 *       {@code enum} type is introduced (AAP scope: {@code model/enums} holds only
 *       {@code UserType}/{@code FileStatus}/{@code RejectCode}/{@code TransactionSource});
 *       the {@code 'Y'}/{@code 'N'} interpretation and the &quot;exactly one type
 *       selected&quot; rule are applied downstream by {@code ReportSubmissionService}.</li>
 *   <li><strong>Date components preserved byte-faithfully (AAP &sect;0.4.2/&sect;0.7.1).</strong>
 *       The custom range arrives as six separate parts &mdash; {@code SDTMM}/{@code SDTDD}
 *       ({@code X(2)}) and {@code SDTYYYY} ({@code X(4)}) for the start date, and the
 *       matching {@code EDTMM}/{@code EDTDD}/{@code EDTYYYY} for the end date. They are
 *       kept piecewise (rather than collapsed into a single {@code java.time.LocalDate})
 *       to remain byte-exact with the screen and to support the monthly/yearly cases
 *       where the parts are left blank. Composition and calendar validation of these
 *       parts is delegated to {@code service/shared/DateValidationService}, the
 *       {@code java.time.LocalDate}-based replacement for the legacy {@code CSUTLDTC} /
 *       {@code CEEDAYS} date check &mdash; it is <strong>not</strong> performed in this
 *       boundary DTO.</li>
 *   <li><strong>{@code WRITEQ TD JOBS} &rarr; SQS publish.</strong> The legacy
 *       online&rarr;batch hand-off (write to the CICS {@code JOBS} TDQ, which triggered
 *       JES) becomes an SQS message publish performed by {@code ReportSubmissionService};
 *       this DTO merely carries the criteria and performs none of that messaging itself
 *       (AAP &sect;0.6.3).</li>
 *   <li><strong>Byte-faithful field lengths.</strong> The {@link Size @Size} constraints
 *       preserve each original {@code PIC X(n)} external-interface width exactly &mdash;
 *       {@code X(1)} for the flags and confirmation, {@code X(2)} for month/day parts,
 *       {@code X(4)} for year parts (AAP &sect;0.7.2).</li>
 *   <li><strong>No floating point.</strong> The report-criteria screen carries no
 *       monetary or decimal field, so there is no numeric type here at all; consistent
 *       with AAP &sect;0.7.3 there is no {@code float}/{@code double} (nor
 *       {@code BigDecimal}) anywhere in this DTO.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is never
 * copied into this repository.</p>
 *
 * @see jakarta.validation.constraints.Size
 * @see jakarta.validation.constraints.Pattern
 */
public class ReportRequest {

    // COBOL substitution: the legacy CORPT00C wrote a report request to the CICS TDQ
    // 'JOBS' (WRITEQ TD), which triggered JES batch submission; in the migration the
    // bound-and-validated instance of this DTO is published to AWS SQS by
    // ReportSubmissionService (queue carddemo-report-jobs.fifo) to trigger the Spring
    // Batch report job. The piecewise start/end date parts below are composed and
    // calendar-validated downstream by DateValidationService (CEEDAYS -> java.time).
    // This DTO holds none of that logic; it is a pure boundary type.

    /**
     * The "Monthly report" selection flag.
     *
     * <p>Migrated from {@code MONTHLYI PIC X(1)} &mdash; the single-character flag the
     * operator set (typically {@code 'Y'}) to request a monthly report. Modeled as a
     * {@link String} of length one to remain byte-faithful to the {@code PIC X(1)} field
     * (rather than a {@code Boolean} or {@code Character}); the {@code Y}/{@code N}
     * interpretation and the mutually-exclusive "exactly one report type" rule are
     * applied downstream by {@code ReportSubmissionService}. Only {@link Size @Size(max = 1)}
     * is enforced here so that, exactly like the COBOL program, an unset or unrecognized
     * value is re-prompted by the service rather than hard-rejected at the boundary.</p>
     */
    // MONTHLY PIC X(1) -> single-character Y/N report-type flag -> String (max length 1)
    @Size(max = 1, message = "Monthly flag must be a single character")
    private String monthly;

    /**
     * The "Yearly report" selection flag.
     *
     * <p>Migrated from {@code YEARLYI PIC X(1)} &mdash; the single-character flag the
     * operator set (typically {@code 'Y'}) to request a yearly report. Modeled as a
     * {@link String} of length one to remain byte-faithful to the {@code PIC X(1)} field;
     * the {@code Y}/{@code N} interpretation is performed downstream by
     * {@code ReportSubmissionService}. Only {@link Size @Size(max = 1)} is enforced here
     * so an unset or unrecognized value is re-prompted rather than hard-rejected.</p>
     */
    // YEARLY PIC X(1) -> single-character Y/N report-type flag -> String (max length 1)
    @Size(max = 1, message = "Yearly flag must be a single character")
    private String yearly;

    /**
     * The "Custom report" selection flag.
     *
     * <p>Migrated from {@code CUSTOMI PIC X(1)} &mdash; the single-character flag the
     * operator set (typically {@code 'Y'}) to request a custom-range report; when set,
     * the six start/end date parts below supply the range. Modeled as a {@link String}
     * of length one to remain byte-faithful to the {@code PIC X(1)} field; the
     * {@code Y}/{@code N} interpretation is performed downstream by
     * {@code ReportSubmissionService}. Only {@link Size @Size(max = 1)} is enforced here
     * so an unset or unrecognized value is re-prompted rather than hard-rejected.</p>
     */
    // CUSTOM PIC X(1) -> single-character Y/N report-type flag -> String (max length 1)
    @Size(max = 1, message = "Custom flag must be a single character")
    private String custom;

    /**
     * The custom-range <strong>start month</strong> component ({@code MM}).
     *
     * <p>Migrated from {@code SDTMMI PIC X(2)} &mdash; the two-character month part of the
     * custom start date. Kept as a fixed-width {@link String} (not an {@code int}) so any
     * leading zero (for example {@code "01"}) is preserved exactly and so blanks are
     * tolerated for the monthly/yearly cases. {@link Size @Size(max = 2)} preserves the
     * original field width and {@link Pattern @Pattern} constrains the value to at most
     * two digits; both are lenient (an empty or absent value passes) because calendar
     * validity is determined downstream by {@code DateValidationService}.</p>
     */
    // SDTMM PIC X(2) -> custom start-date month (MM) -> String (max length 2, 0..2 digits)
    @Size(max = 2, message = "Start month must not exceed 2 characters")
    @Pattern(regexp = "\\d{0,2}", message = "Start month must be numeric (up to 2 digits)")
    private String startMonth;

    /**
     * The custom-range <strong>start day</strong> component ({@code DD}).
     *
     * <p>Migrated from {@code SDTDDI PIC X(2)} &mdash; the two-character day part of the
     * custom start date. Kept as a fixed-width {@link String} so leading zeros are
     * preserved. {@link Size @Size(max = 2)} preserves the original width and
     * {@link Pattern @Pattern} constrains the value to at most two digits; both are
     * lenient so calendar validity is left to {@code DateValidationService}.</p>
     */
    // SDTDD PIC X(2) -> custom start-date day (DD) -> String (max length 2, 0..2 digits)
    @Size(max = 2, message = "Start day must not exceed 2 characters")
    @Pattern(regexp = "\\d{0,2}", message = "Start day must be numeric (up to 2 digits)")
    private String startDay;

    /**
     * The custom-range <strong>start year</strong> component ({@code YYYY}).
     *
     * <p>Migrated from {@code SDTYYYYI PIC X(4)} &mdash; the four-character year part of
     * the custom start date. Kept as a fixed-width {@link String} so the value is carried
     * byte-for-byte. {@link Size @Size(max = 4)} preserves the original width (a value
     * longer than four characters &mdash; for example a five-digit year &mdash; is
     * rejected at the boundary) and {@link Pattern @Pattern} constrains it to at most four
     * digits; both are lenient so calendar validity is left to
     * {@code DateValidationService}.</p>
     */
    // SDTYYYY PIC X(4) -> custom start-date year (YYYY) -> String (max length 4, 0..4 digits)
    @Size(max = 4, message = "Start year must not exceed 4 characters")
    @Pattern(regexp = "\\d{0,4}", message = "Start year must be numeric (up to 4 digits)")
    private String startYear;

    /**
     * The custom-range <strong>end month</strong> component ({@code MM}).
     *
     * <p>Migrated from {@code EDTMMI PIC X(2)} &mdash; the two-character month part of the
     * custom end date. Kept as a fixed-width {@link String} so leading zeros are
     * preserved. {@link Size @Size(max = 2)} preserves the original width and
     * {@link Pattern @Pattern} constrains the value to at most two digits; both are
     * lenient so calendar validity is left to {@code DateValidationService}.</p>
     */
    // EDTMM PIC X(2) -> custom end-date month (MM) -> String (max length 2, 0..2 digits)
    @Size(max = 2, message = "End month must not exceed 2 characters")
    @Pattern(regexp = "\\d{0,2}", message = "End month must be numeric (up to 2 digits)")
    private String endMonth;

    /**
     * The custom-range <strong>end day</strong> component ({@code DD}).
     *
     * <p>Migrated from {@code EDTDDI PIC X(2)} &mdash; the two-character day part of the
     * custom end date. Kept as a fixed-width {@link String} so leading zeros are
     * preserved. {@link Size @Size(max = 2)} preserves the original width and
     * {@link Pattern @Pattern} constrains the value to at most two digits; both are
     * lenient so calendar validity is left to {@code DateValidationService}.</p>
     */
    // EDTDD PIC X(2) -> custom end-date day (DD) -> String (max length 2, 0..2 digits)
    @Size(max = 2, message = "End day must not exceed 2 characters")
    @Pattern(regexp = "\\d{0,2}", message = "End day must be numeric (up to 2 digits)")
    private String endDay;

    /**
     * The custom-range <strong>end year</strong> component ({@code YYYY}).
     *
     * <p>Migrated from {@code EDTYYYYI PIC X(4)} &mdash; the four-character year part of
     * the custom end date. Kept as a fixed-width {@link String} so the value is carried
     * byte-for-byte. {@link Size @Size(max = 4)} preserves the original width and
     * {@link Pattern @Pattern} constrains it to at most four digits; both are lenient so
     * calendar validity is left to {@code DateValidationService}.</p>
     */
    // EDTYYYY PIC X(4) -> custom end-date year (YYYY) -> String (max length 4, 0..4 digits)
    @Size(max = 4, message = "End year must not exceed 4 characters")
    @Pattern(regexp = "\\d{0,4}", message = "End year must be numeric (up to 4 digits)")
    private String endYear;

    /**
     * The single-character submit confirmation flag.
     *
     * <p>Migrated from {@code CONFIRMI PIC X(1)} &mdash; the operator's confirmation
     * keystroke. In {@code CORPT00C} a {@code 'Y'} (or {@code 'y'}) authorized submission
     * of the report request to the {@code JOBS} TDQ, an {@code 'N'} (or {@code 'n'})
     * declined it, and any other value (including blank) caused the program to re-prompt.
     * Modeled as a {@link String} of length one to remain byte-faithful to the
     * {@code PIC X(1)} field (rather than a {@code Character} or {@code Boolean}); the
     * {@code Y}/{@code N} interpretation is performed downstream by
     * {@code ReportSubmissionService} immediately before the SQS publish. Only
     * {@link Size @Size(max = 1)} is enforced here so that, exactly like the COBOL
     * program, an unrecognized value is re-prompted by the service rather than
     * hard-rejected at the boundary.</p>
     */
    // CONFIRM PIC X(1) -> single-character Y/N submit confirmation -> String (max length 1)
    @Size(max = 1, message = "Confirmation flag must be a single character")
    private String confirm;

    /**
     * Default no-argument constructor required by the JSON binder (Jackson)
     * to instantiate this DTO reflectively before populating its properties.
     */
    public ReportRequest() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    /**
     * Constructs a fully-populated report request.
     *
     * <p>Parameter order mirrors the COBOL symbolic-map field order: the three
     * report-type flags ({@code MONTHLYI}, {@code YEARLYI}, {@code CUSTOMI}) first, then
     * the custom start-date parts ({@code SDTMMI}, {@code SDTDDI}, {@code SDTYYYYI}), then
     * the custom end-date parts ({@code EDTMMI}, {@code EDTDDI}, {@code EDTYYYYI}), and
     * finally the confirmation flag ({@code CONFIRMI}).</p>
     *
     * @param monthly    the monthly report flag ({@code MONTHLYI}); a single character
     * @param yearly     the yearly report flag ({@code YEARLYI}); a single character
     * @param custom     the custom report flag ({@code CUSTOMI}); a single character
     * @param startMonth the custom start month ({@code SDTMMI}); up to 2 digits
     * @param startDay   the custom start day ({@code SDTDDI}); up to 2 digits
     * @param startYear  the custom start year ({@code SDTYYYYI}); up to 4 digits
     * @param endMonth   the custom end month ({@code EDTMMI}); up to 2 digits
     * @param endDay     the custom end day ({@code EDTDDI}); up to 2 digits
     * @param endYear    the custom end year ({@code EDTYYYYI}); up to 4 digits
     * @param confirm    the submit confirmation flag ({@code CONFIRMI}); a single
     *                   character, {@code "Y"}/{@code "N"} (case-insensitive)
     */
    public ReportRequest(String monthly, String yearly, String custom,
                         String startMonth, String startDay, String startYear,
                         String endMonth, String endDay, String endYear,
                         String confirm) {
        this.monthly = monthly;
        this.yearly = yearly;
        this.custom = custom;
        this.startMonth = startMonth;
        this.startDay = startDay;
        this.startYear = startYear;
        this.endMonth = endMonth;
        this.endDay = endDay;
        this.endYear = endYear;
        this.confirm = confirm;
    }

    /**
     * Returns the monthly report flag ({@code MONTHLYI}).
     *
     * @return the single-character monthly flag, or {@code null} if unset
     */
    public String getMonthly() {
        return monthly;
    }

    /**
     * Sets the monthly report flag ({@code MONTHLYI}).
     *
     * @param monthly the single-character monthly flag to set
     */
    public void setMonthly(String monthly) {
        this.monthly = monthly;
    }

    /**
     * Returns the yearly report flag ({@code YEARLYI}).
     *
     * @return the single-character yearly flag, or {@code null} if unset
     */
    public String getYearly() {
        return yearly;
    }

    /**
     * Sets the yearly report flag ({@code YEARLYI}).
     *
     * @param yearly the single-character yearly flag to set
     */
    public void setYearly(String yearly) {
        this.yearly = yearly;
    }

    /**
     * Returns the custom report flag ({@code CUSTOMI}).
     *
     * @return the single-character custom flag, or {@code null} if unset
     */
    public String getCustom() {
        return custom;
    }

    /**
     * Sets the custom report flag ({@code CUSTOMI}).
     *
     * @param custom the single-character custom flag to set
     */
    public void setCustom(String custom) {
        this.custom = custom;
    }

    /**
     * Returns the custom start month ({@code SDTMMI}).
     *
     * @return the start-month component, or {@code null} if unset
     */
    public String getStartMonth() {
        return startMonth;
    }

    /**
     * Sets the custom start month ({@code SDTMMI}).
     *
     * @param startMonth the start-month component to set
     */
    public void setStartMonth(String startMonth) {
        this.startMonth = startMonth;
    }

    /**
     * Returns the custom start day ({@code SDTDDI}).
     *
     * @return the start-day component, or {@code null} if unset
     */
    public String getStartDay() {
        return startDay;
    }

    /**
     * Sets the custom start day ({@code SDTDDI}).
     *
     * @param startDay the start-day component to set
     */
    public void setStartDay(String startDay) {
        this.startDay = startDay;
    }

    /**
     * Returns the custom start year ({@code SDTYYYYI}).
     *
     * @return the start-year component, or {@code null} if unset
     */
    public String getStartYear() {
        return startYear;
    }

    /**
     * Sets the custom start year ({@code SDTYYYYI}).
     *
     * @param startYear the start-year component to set
     */
    public void setStartYear(String startYear) {
        this.startYear = startYear;
    }

    /**
     * Returns the custom end month ({@code EDTMMI}).
     *
     * @return the end-month component, or {@code null} if unset
     */
    public String getEndMonth() {
        return endMonth;
    }

    /**
     * Sets the custom end month ({@code EDTMMI}).
     *
     * @param endMonth the end-month component to set
     */
    public void setEndMonth(String endMonth) {
        this.endMonth = endMonth;
    }

    /**
     * Returns the custom end day ({@code EDTDDI}).
     *
     * @return the end-day component, or {@code null} if unset
     */
    public String getEndDay() {
        return endDay;
    }

    /**
     * Sets the custom end day ({@code EDTDDI}).
     *
     * @param endDay the end-day component to set
     */
    public void setEndDay(String endDay) {
        this.endDay = endDay;
    }

    /**
     * Returns the custom end year ({@code EDTYYYYI}).
     *
     * @return the end-year component, or {@code null} if unset
     */
    public String getEndYear() {
        return endYear;
    }

    /**
     * Sets the custom end year ({@code EDTYYYYI}).
     *
     * @param endYear the end-year component to set
     */
    public void setEndYear(String endYear) {
        this.endYear = endYear;
    }

    /**
     * Returns the submit confirmation flag ({@code CONFIRMI}).
     *
     * @return the single-character confirmation flag ({@code "Y"}/{@code "N"}), or
     *         {@code null} if unset
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets the submit confirmation flag ({@code CONFIRMI}).
     *
     * @param confirm the single-character confirmation flag to set
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * Value-based equality across all ten request fields.
     *
     * <p>Two instances are equal only when {@code o} is exactly a {@code ReportRequest}
     * and every modeled field &mdash; the three report-type flags, the six date-part
     * components, and the confirmation flag &mdash; matches.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an equal report request
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ReportRequest that = (ReportRequest) o;
        return Objects.equals(monthly, that.monthly)
                && Objects.equals(yearly, that.yearly)
                && Objects.equals(custom, that.custom)
                && Objects.equals(startMonth, that.startMonth)
                && Objects.equals(startDay, that.startDay)
                && Objects.equals(startYear, that.startYear)
                && Objects.equals(endMonth, that.endMonth)
                && Objects.equals(endDay, that.endDay)
                && Objects.equals(endYear, that.endYear)
                && Objects.equals(confirm, that.confirm);
    }

    /**
     * Hash code derived from all ten request fields, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this request
     */
    @Override
    public int hashCode() {
        return Objects.hash(monthly, yearly, custom,
                startMonth, startDay, startYear,
                endMonth, endDay, endYear, confirm);
    }

    /**
     * Diagnostic representation of this request.
     *
     * <p>All ten fields are non-sensitive report-selection criteria (single-character
     * flags and date-part digits), so each is shown verbatim to aid troubleshooting.</p>
     *
     * @return a human-readable description of this report request
     */
    @Override
    public String toString() {
        return "ReportRequest{"
                + "monthly='" + monthly + '\''
                + ", yearly='" + yearly + '\''
                + ", custom='" + custom + '\''
                + ", startMonth='" + startMonth + '\''
                + ", startDay='" + startDay + '\''
                + ", startYear='" + startYear + '\''
                + ", endMonth='" + endMonth + '\''
                + ", endDay='" + endDay + '\''
                + ", endYear='" + endYear + '\''
                + ", confirm='" + confirm + '\''
                + '}';
    }
}
