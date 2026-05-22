/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Printable report-line DTO mirroring the report data structures defined
 * in {@code app/cpy/CVTRA07Y.cpy} (titled in its banner comment
 * &quot;Reporting data structure for transaction report&quot;).
 *
 * <p>The original COBOL copybook defined a family of <b>parallel
 * 01-level layouts</b>, each representing one printable line variant of
 * a transaction report or customer statement.  In the COBOL source these
 * are mutually-exclusive sibling structures that the writer moves into
 * the file-description record area ({@code FD-REPTFILE-REC}) one at a
 * time before invoking {@code WRITE FD-REPTFILE-REC} (see
 * {@code app/cbl/CBTRN03C.cbl} paragraphs {@code 1110-WRITE-PAGE-TOTALS},
 * {@code 1120-WRITE-HEADERS}, {@code 1120-WRITE-DETAIL}).  In the Java
 * target, the equivalent dispatch is collapsed into a <b>discriminated
 * union</b>: a single {@code ReportLineDto} record carries a
 * {@link #lineType} discriminator and the superset of every variant's
 * fields, with the unused fields left {@code null} and elided from JSON
 * output by {@code @JsonInclude(NON_NULL)}.
 *
 * <p><b>COBOL CVTRA07Y.cpy layout family (verbatim from
 * {@code app/cpy/CVTRA07Y.cpy}):</b>
 * <pre>{@code
 *   01  REPORT-NAME-HEADER.
 *       05  REPT-SHORT-NAME      PIC X(38) VALUE 'DALYREPT'.
 *       05  REPT-LONG-NAME       PIC X(41) VALUE 'Daily Transaction Report'.
 *       05  REPT-DATE-HEADER     PIC X(12) VALUE 'Date Range: '.
 *       05  REPT-START-DATE      PIC X(10) VALUE SPACES.
 *       05  FILLER               PIC X(04) VALUE ' to '.
 *       05  REPT-END-DATE        PIC X(10) VALUE SPACES.
 *
 *   01  TRANSACTION-DETAIL-REPORT.
 *       05  TRAN-REPORT-TRANS-ID    PIC X(16).
 *       05  TRAN-REPORT-ACCOUNT-ID  PIC X(11).
 *       05  TRAN-REPORT-TYPE-CD     PIC X(02).
 *       05  TRAN-REPORT-TYPE-DESC   PIC X(15).
 *       05  TRAN-REPORT-CAT-CD      PIC 9(04).
 *       05  TRAN-REPORT-CAT-DESC    PIC X(29).
 *       05  TRAN-REPORT-SOURCE      PIC X(10).
 *       05  TRAN-REPORT-AMT         PIC -ZZZ,ZZZ,ZZZ.ZZ.
 *
 *   01  TRANSACTION-HEADER-1.    (column-header literal line)
 *   01  TRANSACTION-HEADER-2  PIC X(133) VALUE ALL '-'.
 *
 *   01  REPORT-PAGE-TOTALS.
 *       05  FILLER               PIC X(11) VALUE 'Page Total'.
 *       05  FILLER               PIC X(86) VALUE ALL '.'.
 *       05  REPT-PAGE-TOTAL      PIC +ZZZ,ZZZ,ZZZ.ZZ.
 *
 *   01  REPORT-ACCOUNT-TOTALS.
 *       05  FILLER               PIC X(13) VALUE 'Account Total'.
 *       05  FILLER               PIC X(84) VALUE ALL '.'.
 *       05  REPT-ACCOUNT-TOTAL   PIC +ZZZ,ZZZ,ZZZ.ZZ.
 *
 *   01  REPORT-GRAND-TOTALS.
 *       05  FILLER               PIC X(11) VALUE 'Grand Total'.
 *       05  FILLER               PIC X(86) VALUE ALL '.'.
 *       05  REPT-GRAND-TOTAL     PIC +ZZZ,ZZZ,ZZZ.ZZ.
 * }</pre>
 *
 * <p><b>Line-variant to discriminator mapping:</b>
 * <pre>{@code
 *   COBOL 01-level layout            lineType value      Populated fields
 *   ----------------------------     ----------------    -----------------------------
 *   REPORT-NAME-HEADER               "HEADER"            reportName, startDate, endDate,
 *                                                       (optional pageNumber for paginated
 *                                                        re-prints of the header)
 *   TRANSACTION-DETAIL-REPORT        "DETAIL"            accountId, maskedCardNumber,
 *                                                       transactionDate, transactionType,
 *                                                       transactionCategory,
 *                                                       transactionSource, description,
 *                                                       amount
 *   REPORT-PAGE-TOTALS               "PAGE_TOTAL"        totalLabel ("Page Total"),
 *                                                       amount, (optional pageNumber)
 *   REPORT-ACCOUNT-TOTALS            "ACCOUNT_TOTAL"     accountId, totalLabel
 *                                                       ("Account Total"), amount
 *   REPORT-GRAND-TOTALS              "GRAND_TOTAL"       totalLabel ("Grand Total"),
 *                                                       amount
 *   TRANSACTION-HEADER-2             "SEPARATOR"        (no scalar fields &mdash; rendered
 *                                                        as a horizontal rule)
 * }</pre>
 *
 * <p>Each instance of {@code ReportLineDto} corresponds to <b>one line</b>
 * of printable output.  The producer service (either
 * {@code com.awsm2.carddemo.service.TransactionReportService} &mdash; the
 * port of {@code app/cbl/CBTRN03C.cbl} transaction-report writer &mdash;
 * or {@code com.awsm2.carddemo.service.StatementGenerationService}
 * &mdash; the port of {@code app/cbl/CBSTM03A.CBL} text-statement
 * generator and {@code app/cbl/CBSTM03B.CBL} HTML-statement variant)
 * constructs one DTO per line, populates only the subset of fields
 * appropriate to the line variant, and hands the instance off to the
 * {@code com.awsm2.carddemo.adapter.S3OutputService} (AAP &sect;0.4.1
 * &mdash; replaces sequential file {@code WRITE} to the GDG-generation
 * dataset {@code TRANREPT.YYYYMMDD.GNNNNVNN}) for serialization to the
 * configured S3 output object.  The same DTO type is also consumed by the
 * Spring Batch {@code TransactionReportJob} / {@code StatementGenerationJob}
 * {@code ItemWriter} stages defined in
 * {@code com.awsm2.carddemo.batch}.
 *
 * <p><b>Decimal precision (AAP &sect;0.6.1):</b>  The COBOL display-edit
 * pictures {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} (on {@code TRAN-REPORT-AMT}) and
 * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} (on {@code REPT-PAGE-TOTAL},
 * {@code REPT-ACCOUNT-TOTAL}, {@code REPT-GRAND-TOTAL}) are <b>edit
 * masks</b> &mdash; they specify how a numeric value is rendered for
 * print, not how it is stored.  In COBOL the source numeric values
 * ({@code TRAN-AMT PIC S9(09)V99} from {@code app/cpy/CVTRA05Y.cpy},
 * {@code WS-PAGE-TOTAL}, {@code WS-ACCT-TOTAL}, {@code WS-GRAND-TOTAL}
 * accumulators) carry the actual fixed-point decimal data; the
 * {@code MOVE} into the edit-picture field is what generates the
 * comma-and-sign-edited display string.
 *
 * <p>The Java target separates these concerns cleanly:
 * <ul>
 *   <li><b>Data layer (this DTO):</b> {@link #amount} is a
 *       {@link BigDecimal} carrying the raw decimal value with
 *       {@code scale=2} per AAP &sect;0.6.1.  The producer service must
 *       set scale to 2 using
 *       {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding)
 *       to preserve COBOL {@code PIC 9} fixed-point arithmetic semantics
 *       exactly.  Use of {@code float} or {@code double} for any
 *       monetary value is <b>forbidden</b> by the AAP and is not used
 *       here.</li>
 *   <li><b>Presentation layer (downstream of this DTO):</b> a separate
 *       formatter (the S3 line writer, a JSON consumer, or a
 *       human-readable text/HTML renderer in
 *       {@code StatementGenerationService}'s template-method pair)
 *       applies the {@code -ZZZ,ZZZ,ZZZ.ZZ} or
 *       {@code +ZZZ,ZZZ,ZZZ.ZZ} edit pattern to produce the comma-edited
 *       string for display.  The DTO itself never carries the edited
 *       string &mdash; that is intentional, so the same DTO can drive
 *       both plain-text and HTML statement output without duplication.</li>
 * </ul>
 *
 * <p><b>Date handling (AAP &sect;0.5.2 / &sect;0.6.1):</b>  COBOL
 * {@code REPT-START-DATE}, {@code REPT-END-DATE} are {@code PIC X(10)}
 * literals (typically formatted {@code YYYY-MM-DD} by the producer).  In
 * the Java target these are {@link LocalDate} values, replacing the
 * retired LE date services ({@code CEEDAYS}, {@code CEELOCT}) entirely.
 * The {@link JsonFormat} annotation on each {@link LocalDate} field
 * pins the wire format to ISO-8601 {@code yyyy-MM-dd} so the JSON
 * representation matches the source COBOL display format byte-for-byte.
 *
 * <p><b>PCI-DSS handling (AAP &sect;0.7.1 / &sect;0.6.6):</b>  The
 * source COBOL transaction-report writer ({@code CBTRN03C.cbl}) does
 * <b>not</b> include the card number on the detail line &mdash;
 * {@code TRANSACTION-DETAIL-REPORT} carries {@code TRAN-REPORT-TRANS-ID}
 * and {@code TRAN-REPORT-ACCOUNT-ID} but no PAN.  However, this DTO
 * exposes a {@link #maskedCardNumber} field for use by
 * {@code StatementGenerationService} (which <i>does</i> render the
 * masked PAN on customer statements per the COBOL programs
 * {@code CBSTM03A.CBL} / {@code CBSTM03B.CBL}).  The contract is that
 * <b>the producer service masks the PAN to last-4</b> (or a
 * regulator-acceptable equivalent &mdash; the industry-standard
 * {@code ************NNNN} format) <b>before</b> populating
 * {@link #maskedCardNumber}.  The DTO accepts only the already-masked
 * string and never the full 16-digit PAN, eliminating any risk of
 * accidental full-PAN leakage in S3 objects, OpenSearch indexes, or
 * CloudWatch Logs.  For the transaction-report use case
 * ({@code TransactionReportService}), the producer simply leaves
 * {@link #maskedCardNumber} {@code null} (and {@code @JsonInclude(NON_NULL)}
 * elides it from output).
 *
 * <p><b>JSON serialization shape:</b>  Because each line variant
 * populates only a subset of the union fields, and because
 * {@code @JsonInclude(NON_NULL)} is applied at the record level, the
 * emitted JSON for each line is the <i>minimum</i> projection: a HEADER
 * line emits {@code {lineType, reportName, startDate, endDate}}; a
 * DETAIL line emits {@code {lineType, accountId, maskedCardNumber?,
 * transactionDate, transactionType, transactionCategory,
 * transactionSource, description, amount}}; a PAGE_TOTAL line emits
 * {@code {lineType, totalLabel, amount, pageNumber?}}; etc.  This gives
 * S3 consumers (Athena queries, OpenSearch indexers, Glue ETL jobs) a
 * compact JSON-lines representation that mirrors the COBOL line-by-line
 * report structure while remaining queryable and schema-discoverable.
 *
 * <p>This record is immutable, response-only, and carries no Jakarta
 * Bean Validation constraints &mdash; validation of the underlying
 * report data is performed at the source ({@code Transaction} JPA
 * entity column constraints + reporting-time filters in the producer
 * service) before any line DTO is constructed.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>Copybook: {@code app/cpy/CVTRA07Y.cpy} (report data structures
 *       &mdash; HEADER, DETAIL, TOTALS variants)</li>
 *   <li>Used by:
 *     <ul>
 *       <li>{@code app/cbl/CBTRN03C.cbl} (transaction-report writer;
 *           paragraphs {@code 1110-WRITE-PAGE-TOTALS},
 *           {@code 1120-WRITE-HEADERS}, {@code 1120-WRITE-DETAIL})</li>
 *       <li>{@code app/cbl/CBSTM03A.CBL} (text statement generator)</li>
 *       <li>{@code app/cbl/CBSTM03B.CBL} (HTML statement variant)</li>
 *     </ul>
 *   </li>
 *   <li>JCL replaced: {@code app/jcl/TRANREPT.jcl} (transaction-report
 *       job step) and {@code app/jcl/CREASTMT.JCL} (statement-generation
 *       job step) &mdash; both replaced by the {@code TransactionReportJob}
 *       and {@code StatementGenerationJob} Spring Batch jobs orchestrated
 *       by the {@code eod-batch-pipeline.asl.json} Step Functions state
 *       machine per AAP &sect;0.4.1.</li>
 *   <li>Output target: previously sequential PS / GDG-generation
 *       datasets {@code TRANREPT.YYYYMMDD.GNNNNVNN} and
 *       {@code STMTFILE.YYYYMMDD.GNNNNVNN}; in the Java target,
 *       versioned S3 objects under
 *       {@code s3://${S3_OUTPUT_BUCKET}/reports/} and
 *       {@code s3://${S3_OUTPUT_BUCKET}/statements/} with SSE-KMS
 *       encryption and S3 lifecycle policies replacing GDG generations
 *       per AAP &sect;0.6.2 and &sect;0.7.2.</li>
 * </ul>
 *
 * @param lineType            the line-variant discriminator (one of
 *                            {@code "HEADER"}, {@code "DETAIL"},
 *                            {@code "PAGE_TOTAL"}, {@code "ACCOUNT_TOTAL"},
 *                            {@code "GRAND_TOTAL"}, {@code "SEPARATOR"});
 *                            tells consumers which subset of the union
 *                            fields below is populated.  The naming
 *                            convention mirrors the COBOL 01-level
 *                            structure names in {@code CVTRA07Y.cpy}
 *                            ({@code REPORT-NAME-HEADER &rarr; "HEADER"},
 *                            {@code TRANSACTION-DETAIL-REPORT &rarr;
 *                            "DETAIL"}, etc.)
 * @param reportName          the human-readable report name on HEADER
 *                            lines (e.g. {@code "Daily Transaction
 *                            Report"}); composes the COBOL
 *                            {@code REPT-SHORT-NAME PIC X(38)} and
 *                            {@code REPT-LONG-NAME PIC X(41)} literals
 *                            from {@code REPORT-NAME-HEADER} in
 *                            {@code CVTRA07Y.cpy}
 * @param startDate           the inclusive lower bound of the report's
 *                            date window on HEADER lines; maps to
 *                            COBOL {@code REPT-START-DATE PIC X(10)}
 *                            from {@code REPORT-NAME-HEADER}.
 *                            Serialized as ISO-8601 {@code yyyy-MM-dd}
 * @param endDate             the inclusive upper bound of the report's
 *                            date window on HEADER lines; maps to
 *                            COBOL {@code REPT-END-DATE PIC X(10)}.
 *                            Serialized as ISO-8601 {@code yyyy-MM-dd}
 * @param accountId           the 11-digit account identifier on DETAIL
 *                            and ACCOUNT_TOTAL lines; maps to COBOL
 *                            {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}
 *                            from {@code TRANSACTION-DETAIL-REPORT}
 *                            (the producer parses this verbatim from
 *                            the source COBOL field, which holds the
 *                            account ID looked up via the
 *                            {@code XREF-ACCT-ID} cross-reference)
 * @param maskedCardNumber    the masked Primary Account Number (PAN)
 *                            on DETAIL lines for statement output
 *                            ({@code CBSTM03A.CBL} / {@code CBSTM03B.CBL}).
 *                            <b>Must be already masked by the producer
 *                            service</b> to last-4 or an industry-standard
 *                            {@code ************NNNN} pattern per
 *                            PCI-DSS Requirement 3.3.  For
 *                            transaction-report output
 *                            ({@code CBTRN03C.cbl}) this field is
 *                            {@code null} and elided from JSON
 * @param transactionDate     the transaction's posting date on DETAIL
 *                            lines (derived from {@code TRAN-PROC-TS}
 *                            in the COBOL {@code TRAN-RECORD} of
 *                            {@code app/cpy/CVTRA05Y.cpy} by the
 *                            producer's projection logic).  Serialized
 *                            as ISO-8601 {@code yyyy-MM-dd}
 * @param transactionType     the 2-character transaction type code on
 *                            DETAIL lines; maps to COBOL
 *                            {@code TRAN-REPORT-TYPE-CD PIC X(02)} from
 *                            {@code TRANSACTION-DETAIL-REPORT}
 *                            (joinable to the
 *                            {@code TransactionType.typeCode} reference
 *                            table seeded by Flyway
 *                            {@code V013__seed_transaction_type.sql})
 * @param transactionCategory the 4-digit transaction category code on
 *                            DETAIL lines; maps to COBOL
 *                            {@code TRAN-REPORT-CAT-CD PIC 9(04)} from
 *                            {@code TRANSACTION-DETAIL-REPORT}
 *                            (joinable to the
 *                            {@code TransactionCategory.categoryCode}
 *                            reference table seeded by Flyway
 *                            {@code V014__seed_transaction_category.sql})
 * @param transactionSource   the 10-character transaction source
 *                            channel on DETAIL lines (e.g.
 *                            {@code "ONLINE"}, {@code "POS"},
 *                            {@code "BATCH"}); maps to COBOL
 *                            {@code TRAN-REPORT-SOURCE PIC X(10)} from
 *                            {@code TRANSACTION-DETAIL-REPORT}
 * @param description         the free-text descriptive text on DETAIL
 *                            lines.  The COBOL source carries this in
 *                            two narrower fields &mdash;
 *                            {@code TRAN-REPORT-TYPE-DESC PIC X(15)}
 *                            and {@code TRAN-REPORT-CAT-DESC PIC X(29)}
 *                            joined to the type / category reference
 *                            tables &mdash; concatenated by the producer
 *                            into a single descriptive string, or
 *                            optionally the verbatim
 *                            {@code TRAN-DESC PIC X(100)} from
 *                            {@code CVTRA05Y.cpy}
 * @param amount              the line-item monetary amount on DETAIL,
 *                            PAGE_TOTAL, ACCOUNT_TOTAL, and GRAND_TOTAL
 *                            lines.  Maps to COBOL
 *                            {@code TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ}
 *                            on DETAIL lines and to
 *                            {@code REPT-PAGE-TOTAL} /
 *                            {@code REPT-ACCOUNT-TOTAL} /
 *                            {@code REPT-GRAND-TOTAL}
 *                            {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} on the
 *                            respective total lines.  Carried as a
 *                            {@link BigDecimal} with {@code scale=2}
 *                            per AAP &sect;0.6.1; the COBOL edit-picture
 *                            comma/sign formatting is applied
 *                            <b>downstream</b> of this DTO by the
 *                            S3 line formatter or statement template
 * @param totalLabel          the human-readable label for total-line
 *                            variants (one of {@code "Page Total"},
 *                            {@code "Account Total"}, {@code "Grand Total"});
 *                            maps to the leading {@code FILLER}
 *                            literals of {@code REPORT-PAGE-TOTALS},
 *                            {@code REPORT-ACCOUNT-TOTALS}, and
 *                            {@code REPORT-GRAND-TOTALS} in
 *                            {@code CVTRA07Y.cpy}.  Carried as a typed
 *                            field rather than re-emitted as part of
 *                            {@code lineType} so consumers (Athena /
 *                            OpenSearch / Glue) can index it without
 *                            string parsing
 * @param pageNumber          the optional 1-based page number on HEADER
 *                            and PAGE_TOTAL lines for paginated print
 *                            output.  Has no direct COBOL field in
 *                            {@code CVTRA07Y.cpy} (in the source the
 *                            COBOL writer maintains {@code WS-PAGE-NUM}
 *                            in working storage and renders it as part
 *                            of the running header literal); preserved
 *                            here as an explicit DTO field so REST and
 *                            S3 consumers can paginate without parsing
 *                            literal header text
 *
 * @see com.awsm2.carddemo.dto.StatementTransactionDto for the
 *      denormalized per-transaction projection used inside a DETAIL
 *      line's source data
 */
@Schema(name = "ReportLineDto",
        description = "Printable report-line DTO &mdash; one line of "
                + "transaction-report or customer-statement output. "
                + "Discriminated-union representation of the line-variant "
                + "family defined in COBOL copybook app/cpy/CVTRA07Y.cpy "
                + "(HEADER, DETAIL, PAGE_TOTAL, ACCOUNT_TOTAL, "
                + "GRAND_TOTAL, SEPARATOR variants).  Each DTO instance "
                + "represents one printable line, with the lineType "
                + "discriminator indicating which subset of the union "
                + "fields is populated.  Produced by "
                + "TransactionReportService (port of CBTRN03C.cbl) and "
                + "StatementGenerationService (port of CBSTM03A.CBL / "
                + "CBSTM03B.CBL); serialized to versioned S3 objects via "
                + "S3OutputService, replacing the COBOL sequential WRITE "
                + "to GDG-generation datasets per AAP &sect;0.4.1.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportLineDto(

        @Schema(description = "Line-variant discriminator. Tells "
                + "consumers which subset of the union fields below is "
                + "populated for this line. Naming mirrors the COBOL "
                + "01-level structure names in CVTRA07Y.cpy: 'HEADER' "
                + "for REPORT-NAME-HEADER, 'DETAIL' for "
                + "TRANSACTION-DETAIL-REPORT, 'PAGE_TOTAL' for "
                + "REPORT-PAGE-TOTALS, 'ACCOUNT_TOTAL' for "
                + "REPORT-ACCOUNT-TOTALS, 'GRAND_TOTAL' for "
                + "REPORT-GRAND-TOTALS, 'SEPARATOR' for the "
                + "TRANSACTION-HEADER-2 horizontal-rule line.",
                example = "DETAIL",
                allowableValues = {"HEADER", "DETAIL", "PAGE_TOTAL",
                        "ACCOUNT_TOTAL", "GRAND_TOTAL", "SEPARATOR"})
        @JsonProperty("lineType")
        String lineType,

        // ============================================================
        // ===== HEADER fields (lineType == "HEADER") =================
        // COBOL: REPORT-NAME-HEADER in CVTRA07Y.cpy (lines 4-13)
        // ============================================================

        @Schema(description = "Human-readable report name on HEADER "
                + "lines. Composes the COBOL REPT-SHORT-NAME PIC X(38) "
                + "(e.g. 'DALYREPT') and REPT-LONG-NAME PIC X(41) "
                + "(e.g. 'Daily Transaction Report') literals from "
                + "REPORT-NAME-HEADER in CVTRA07Y.cpy. Null on non-HEADER "
                + "lines and elided from JSON by @JsonInclude(NON_NULL).",
                example = "Daily Transaction Report",
                maxLength = 80)
        @JsonProperty("reportName")
        String reportName,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Inclusive lower bound of the report's "
                + "date window on HEADER lines. Maps to COBOL "
                + "REPT-START-DATE PIC X(10) from REPORT-NAME-HEADER in "
                + "CVTRA07Y.cpy. Replaces the retired LE date services "
                + "(CEEDAYS / CEELOCT) per AAP &sect;0.5.2. Serialized "
                + "as ISO-8601 yyyy-MM-dd.",
                example = "2026-01-01", format = "date")
        @JsonProperty("startDate")
        LocalDate startDate,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Inclusive upper bound of the report's "
                + "date window on HEADER lines. Maps to COBOL "
                + "REPT-END-DATE PIC X(10) from REPORT-NAME-HEADER in "
                + "CVTRA07Y.cpy. Replaces the retired LE date services "
                + "(CEEDAYS / CEELOCT) per AAP &sect;0.5.2. Serialized "
                + "as ISO-8601 yyyy-MM-dd.",
                example = "2026-01-31", format = "date")
        @JsonProperty("endDate")
        LocalDate endDate,

        // ============================================================
        // ===== DETAIL fields (lineType == "DETAIL") =================
        // COBOL: TRANSACTION-DETAIL-REPORT in CVTRA07Y.cpy (lines 15-31)
        // ============================================================

        @Schema(description = "11-digit account identifier on DETAIL "
                + "and ACCOUNT_TOTAL lines. Maps to COBOL "
                + "TRAN-REPORT-ACCOUNT-ID PIC X(11) from "
                + "TRANSACTION-DETAIL-REPORT in CVTRA07Y.cpy. In the "
                + "source COBOL the producer (CBTRN03C.cbl paragraph "
                + "1120-WRITE-DETAIL) sets this from the XREF-ACCT-ID "
                + "cross-reference lookup. The Java target carries this "
                + "as a Long (the underlying RDS account_id column is "
                + "NUMERIC(11)).",
                example = "10000000001")
        @JsonProperty("accountId")
        Long accountId,

        @Schema(description = "Masked Primary Account Number (PAN) on "
                + "DETAIL lines for statement output (CBSTM03A.CBL / "
                + "CBSTM03B.CBL). MUST be already masked by the "
                + "producer service to last-4 (industry-standard "
                + "'************NNNN' pattern) per PCI-DSS Requirement "
                + "3.3 &mdash; the DTO never accepts a full 16-digit "
                + "PAN. Null on transaction-report (CBTRN03C.cbl) "
                + "DETAIL lines and elided from JSON.",
                example = "************0001",
                maxLength = 16)
        @JsonProperty("maskedCardNumber")
        String maskedCardNumber,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Transaction posting date on DETAIL "
                + "lines. Derived by the producer service from "
                + "TRAN-PROC-TS PIC X(26) in the COBOL TRAN-RECORD "
                + "(app/cpy/CVTRA05Y.cpy); the producer extracts the "
                + "10-character yyyy-MM-dd date portion of the 26-char "
                + "timestamp. Replaces the retired LE date services "
                + "(CEEDAYS / CEELOCT) per AAP &sect;0.5.2.",
                example = "2026-01-15", format = "date")
        @JsonProperty("transactionDate")
        LocalDate transactionDate,

        @Schema(description = "Transaction type code on DETAIL lines. "
                + "Maps to COBOL TRAN-REPORT-TYPE-CD PIC X(02) from "
                + "TRANSACTION-DETAIL-REPORT in CVTRA07Y.cpy. Joinable "
                + "to the TransactionType reference table (seeded by "
                + "Flyway V013__seed_transaction_type.sql from "
                + "app/data/ASCII/trantype.txt).",
                example = "01",
                maxLength = 2)
        @JsonProperty("transactionType")
        String transactionType,

        @Schema(description = "Transaction category code on DETAIL "
                + "lines. Maps to COBOL TRAN-REPORT-CAT-CD PIC 9(04) "
                + "from TRANSACTION-DETAIL-REPORT in CVTRA07Y.cpy. "
                + "Joinable to the TransactionCategory reference table "
                + "(seeded by Flyway "
                + "V014__seed_transaction_category.sql from "
                + "app/data/ASCII/trancatg.txt).",
                example = "5411")
        @JsonProperty("transactionCategory")
        Integer transactionCategory,

        @Schema(description = "Transaction source channel on DETAIL "
                + "lines. Maps to COBOL TRAN-REPORT-SOURCE PIC X(10) "
                + "from TRANSACTION-DETAIL-REPORT in CVTRA07Y.cpy. "
                + "Indicates the origination channel of the transaction "
                + "(e.g. 'ONLINE' from the CICS-equivalent REST "
                + "endpoints, 'POS' from point-of-sale, 'BATCH' from "
                + "end-of-day posting).",
                example = "ONLINE",
                maxLength = 10)
        @JsonProperty("transactionSource")
        String transactionSource,

        @Schema(description = "Free-text descriptive text on DETAIL "
                + "lines. The COBOL source carries this in two narrower "
                + "fields &mdash; TRAN-REPORT-TYPE-DESC PIC X(15) "
                + "(joined to the TransactionType reference table) and "
                + "TRAN-REPORT-CAT-DESC PIC X(29) (joined to the "
                + "TransactionCategory reference table) &mdash; "
                + "concatenated by the producer into a single "
                + "descriptive string, or optionally the verbatim "
                + "TRAN-DESC PIC X(100) from CVTRA05Y.cpy for statement "
                + "output.",
                example = "GROCERY STORE PURCHASE",
                maxLength = 100)
        @JsonProperty("description")
        String description,

        @Schema(description = "Line-item monetary amount on DETAIL, "
                + "PAGE_TOTAL, ACCOUNT_TOTAL, and GRAND_TOTAL lines. "
                + "Maps to COBOL TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ "
                + "on DETAIL lines (from TRANSACTION-DETAIL-REPORT) and "
                + "to REPT-PAGE-TOTAL / REPT-ACCOUNT-TOTAL / "
                + "REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ on the "
                + "respective total lines (from REPORT-PAGE-TOTALS, "
                + "REPORT-ACCOUNT-TOTALS, REPORT-GRAND-TOTALS) in "
                + "CVTRA07Y.cpy. Carried as a BigDecimal with scale=2; "
                + "the producer service is responsible for setting the "
                + "scale to 2 with RoundingMode.HALF_EVEN (banker's "
                + "rounding) per AAP &sect;0.6.1. Use of float or "
                + "double for monetary values is forbidden by the AAP. "
                + "The COBOL edit-picture comma/sign formatting is "
                + "applied downstream of this DTO by the S3 line "
                + "formatter or statement template.",
                example = "123.45")
        @JsonProperty("amount")
        BigDecimal amount,

        // ============================================================
        // ===== TOTAL fields (lineType == "PAGE_TOTAL" /             =
        // =====                "ACCOUNT_TOTAL" / "GRAND_TOTAL")      =
        // COBOL: REPORT-PAGE-TOTALS, REPORT-ACCOUNT-TOTALS,
        //        REPORT-GRAND-TOTALS in CVTRA07Y.cpy (lines 50-66)
        // ============================================================

        @Schema(description = "Human-readable label for total-line "
                + "variants. One of 'Page Total', 'Account Total', or "
                + "'Grand Total'. Maps to the leading FILLER literals "
                + "of REPORT-PAGE-TOTALS (PIC X(11) VALUE 'Page Total'), "
                + "REPORT-ACCOUNT-TOTALS (PIC X(13) VALUE 'Account "
                + "Total'), and REPORT-GRAND-TOTALS (PIC X(11) VALUE "
                + "'Grand Total') in CVTRA07Y.cpy. Carried as a typed "
                + "field rather than re-emitted as part of lineType so "
                + "consumers (Athena / OpenSearch / Glue ETL) can index "
                + "it without string parsing.",
                example = "Account Total",
                maxLength = 20)
        @JsonProperty("totalLabel")
        String totalLabel,

        @Schema(description = "Optional 1-based page number on HEADER "
                + "and PAGE_TOTAL lines for paginated print output. Has "
                + "no direct COBOL field in CVTRA07Y.cpy (the source "
                + "writer maintains WS-PAGE-NUM in working storage and "
                + "renders it as part of the running header literal); "
                + "preserved here as an explicit DTO field so REST and "
                + "S3 consumers can paginate without parsing literal "
                + "header text. Null on lines that do not carry "
                + "pagination context.",
                example = "1")
        @JsonProperty("pageNumber")
        Integer pageNumber
) {
}
