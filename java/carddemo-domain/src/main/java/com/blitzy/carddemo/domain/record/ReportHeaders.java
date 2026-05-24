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
package com.blitzy.carddemo.domain.record;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.util.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Faithful translation of the COBOL copybook {@code CVTRA07Y} &mdash; the
 * reporting data structures used by the Daily Transaction Report writer
 * ({@code app/cbl/CBTRN03C.cbl}).
 *
 * <p>Unlike most {@code carddemo-domain.record} types (which translate ONE
 * 01-level group), {@code CVTRA07Y} declares <strong>seven distinct 01-level
 * groups</strong>, one per report-line layout. To preserve byte-for-byte
 * fidelity for each line type, this class is modeled as a sealed holder of
 * five nested records (variable lines) and two static accessor methods
 * (constant lines, no per-call state). Each variable line exposes a
 * {@code byte[] encode()} that produces a single line of report output
 * byte-identical to what COBOL would produce.
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.4.1 (Copybooks &rarr; Java records table) &mdash;
 *       ReportHeaders &larr; CVTRA07Y.cpy</li>
 *   <li>AAP &sect;0.6.9 (Copybook-to-Record Translation Table &mdash;
 *       "Report header constants")</li>
 *   <li>AAP &sect;0.3.2 (Records pattern: every copybook 01-level group
 *       becomes a Java {@code record})</li>
 *   <li>AAP &sect;0.6.5 (File I/O Exactness &mdash; byte-for-byte
 *       fixed-width contract)</li>
 *   <li>AAP &sect;0.6.7 / &sect;0.7.3 ({@code BigDecimal} is the ONLY
 *       permitted monetary type)</li>
 *   <li>AAP &sect;0.6.9 (Decimal scale preservation: every monetary value
 *       is normalized to scale 2 via {@link Decimals#ROUNDED_MODE} prior to
 *       emission)</li>
 * </ul>
 *
 * <h2>COBOL layout (verbatim from {@code app/cpy/CVTRA07Y.cpy})</h2>
 * <pre>{@code
 * 01 REPORT-NAME-HEADER.                                      (115 bytes)
 *    05 REPT-SHORT-NAME      PIC X(38) VALUE 'DALYREPT'.       (38)
 *    05 REPT-LONG-NAME       PIC X(41) VALUE 'Daily Transaction Report'.  (41)
 *    05 REPT-DATE-HEADER     PIC X(12) VALUE 'Date Range: '.   (12)
 *    05 REPT-START-DATE      PIC X(10) VALUE SPACES.           (10)
 *    05 FILLER               PIC X(04) VALUE ' to '.           ( 4)
 *    05 REPT-END-DATE        PIC X(10) VALUE SPACES.           (10)
 *
 * 01 TRANSACTION-DETAIL-REPORT.                                (113 bytes)
 *    05 TRAN-REPORT-TRANS-ID         PIC X(16).                (16)
 *    05 FILLER                       PIC X(01) VALUE SPACES.   ( 1)
 *    05 TRAN-REPORT-ACCOUNT-ID       PIC X(11).                (11)
 *    05 FILLER                       PIC X(01) VALUE SPACES.   ( 1)
 *    05 TRAN-REPORT-TYPE-CD          PIC X(02).                ( 2)
 *    05 FILLER                       PIC X(01) VALUE '-'.      ( 1)
 *    05 TRAN-REPORT-TYPE-DESC        PIC X(15).                (15)
 *    05 FILLER                       PIC X(01) VALUE SPACES.   ( 1)
 *    05 TRAN-REPORT-CAT-CD           PIC 9(04).                ( 4)
 *    05 FILLER                       PIC X(01) VALUE '-'.      ( 1)
 *    05 TRAN-REPORT-CAT-DESC         PIC X(29).                (29)
 *    05 FILLER                       PIC X(01) VALUE SPACES.   ( 1)
 *    05 TRAN-REPORT-SOURCE           PIC X(10).                (10)
 *    05 FILLER                       PIC X(04) VALUE SPACES.   ( 4)
 *    05 TRAN-REPORT-AMT              PIC -ZZZ,ZZZ,ZZZ.ZZ.      (14)
 *    05 FILLER                       PIC X(02) VALUE SPACES.   ( 2)
 *
 * 01 TRANSACTION-HEADER-1.                                     (114 bytes)
 *    05 FILLER PIC X(17) VALUE 'Transaction ID'.               (17)
 *    05 FILLER PIC X(12) VALUE 'Account ID'.                   (12)
 *    05 FILLER PIC X(19) VALUE 'Transaction Type'.             (19)
 *    05 FILLER PIC X(35) VALUE 'Tran Category'.                (35)
 *    05 FILLER PIC X(14) VALUE 'Tran Source'.                  (14)
 *    05 FILLER PIC X     VALUE SPACES.                         ( 1)
 *    05 FILLER PIC X(16) VALUE '        Amount'.               (16)
 *
 * 01 TRANSACTION-HEADER-2  PIC X(133) VALUE ALL '-'.           (133 bytes)
 *
 * 01 REPORT-PAGE-TOTALS.                                       (112 bytes)
 *    05 FILLER PIC X(11) VALUE 'Page Total'.                   (11)
 *    05 FILLER PIC X(86) VALUE ALL '.'.                        (86)
 *    05 REPT-PAGE-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ.                  (15)
 *
 * 01 REPORT-ACCOUNT-TOTALS.                                    (112 bytes)
 *    05 FILLER PIC X(13) VALUE 'Account Total'.                (13)
 *    05 FILLER PIC X(84) VALUE ALL '.'.                        (84)
 *    05 REPT-ACCOUNT-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ.               (15)
 *
 * 01 REPORT-GRAND-TOTALS.                                      (112 bytes)
 *    05 FILLER PIC X(11) VALUE 'Grand Total'.                  (11)
 *    05 FILLER PIC X(86) VALUE ALL '.'.                        (86)
 *    05 REPT-GRAND-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ.                 (15)
 * }</pre>
 *
 * <h2>WRITE behavior in CBTRN03C</h2>
 * <p>{@code CBTRN03C} declares its report file as
 * {@code FD-REPTFILE-REC PIC X(133)} and writes via
 * {@code MOVE &lt;line-record&gt; TO FD-REPTFILE-REC} followed by
 * {@code WRITE FD-REPTFILE-REC}. The COBOL {@code MOVE} of a shorter
 * alphanumeric item to a longer one right-pads the destination with
 * spaces. The {@code encode()} methods on this class produce buffers at
 * the natural CVTRA07Y record length; the I/O adapter is responsible for
 * padding to 133 bytes via {@link FileLineWriter} or equivalent.
 *
 * <h2>Numeric edit pictures (Z suppression and sign control)</h2>
 * <ul>
 *   <li><strong>{@code PIC -ZZZ,ZZZ,ZZZ.ZZ}</strong> &mdash; sign-or-blank
 *       in the leftmost position, leading-zero suppression on the integer
 *       part with comma group separators, fixed decimal point, and two
 *       cents digits. Negative values show {@code '-'}; positive and zero
 *       values show a leading space. Per AAP &sect;0.6.1 the value is
 *       first normalized to scale 2 via banker's rounding
 *       ({@link Decimals#ROUNDED_MODE} = {@link RoundingMode#HALF_EVEN}).
 *       The spec-mandated emission width is 14 bytes
 *       ({@link #TRANSACTION_DETAIL_REPORT_LENGTH} = 113 with AMT field
 *       occupying positions 97..110); see Section 0.6.5 fidelity note
 *       below.</li>
 *   <li><strong>{@code PIC +ZZZ,ZZZ,ZZZ.ZZ}</strong> &mdash; explicit
 *       sign in the leftmost position (always {@code '+'} or {@code '-'},
 *       never a space), same digit/comma/decimal structure as above. The
 *       emission width is 15 bytes for each of the three totals lines.</li>
 * </ul>
 *
 * <h2>Section 0.6.5 fidelity note</h2>
 * <p>The COBOL picture string {@code -ZZZ,ZZZ,ZZZ.ZZ} contains 15
 * characters when counted byte-by-byte (1 sign + 9 Z digits + 2 commas +
 * 1 period + 2 cents Z), whereas the spec mandates a 14-byte emission
 * width for the {@code TRAN-REPORT-AMT} field within
 * {@code TRANSACTION-DETAIL-REPORT}. This class follows the spec width
 * exactly. Any byte-level mismatch surfaced by the golden-record harness
 * during fixture comparison against captured COBOL output must be logged
 * in {@code java/MIGRATION_NOTES.md} for review.
 *
 * <h2>Thread safety</h2>
 * <p>The holder class itself is stateless; all nested types are immutable
 * {@code record}s. Every method on this class is safe for concurrent use
 * by any number of platform or virtual threads.
 *
 * @see com.blitzy.carddemo.domain.util.Decimals
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA07Y",
        sourcePath = "app/cpy/CVTRA07Y.cpy",
        notes = "Reporting data structures for the Daily Transaction Report (CBTRN03C). "
                + "Holds seven 01-level groups: REPORT-NAME-HEADER, TRANSACTION-DETAIL-REPORT, "
                + "TRANSACTION-HEADER-1, TRANSACTION-HEADER-2, REPORT-PAGE-TOTALS, "
                + "REPORT-ACCOUNT-TOTALS, and REPORT-GRAND-TOTALS."
)
public final class ReportHeaders {

    // =====================================================================
    // Byte-length constants (one per 01-level group in CVTRA07Y).
    //
    // These constants are the contract between this class and the
    // carddemo-adapter-file FileLineWriter implementations: every encode()
    // method returns a buffer of exactly the corresponding length.
    // =====================================================================

    /**
     * Length in bytes of the encoded {@link ReportNameHeader} line as
     * declared by {@code 01 REPORT-NAME-HEADER} in {@code CVTRA07Y}:
     * 38 + 41 + 12 + 10 + 4 + 10 = 115.
     */
    public static final int REPORT_NAME_HEADER_LENGTH = 115;

    /**
     * Length in bytes of the encoded {@link TransactionDetailReport} line
     * as declared by {@code 01 TRANSACTION-DETAIL-REPORT} in
     * {@code CVTRA07Y}:
     * 16 + 1 + 11 + 1 + 2 + 1 + 15 + 1 + 4 + 1 + 29 + 1 + 10 + 4 + 14 + 2 = 113.
     */
    public static final int TRANSACTION_DETAIL_REPORT_LENGTH = 113;

    /**
     * Length in bytes of the encoded {@link #transactionHeader1()} line
     * as declared by {@code 01 TRANSACTION-HEADER-1} in {@code CVTRA07Y}:
     * 17 + 12 + 19 + 35 + 14 + 1 + 16 = 114.
     */
    public static final int TRANSACTION_HEADER_1_LENGTH = 114;

    /**
     * Length in bytes of the encoded {@link #transactionHeader2()} line
     * as declared by {@code 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}
     * in {@code CVTRA07Y}: 133 bytes of {@code 0x2D} ('-').
     */
    public static final int TRANSACTION_HEADER_2_LENGTH = 133;

    /**
     * Length in bytes of the encoded {@link ReportPageTotals} line as
     * declared by {@code 01 REPORT-PAGE-TOTALS} in {@code CVTRA07Y}:
     * 11 + 86 + 15 = 112.
     */
    public static final int REPORT_PAGE_TOTALS_LENGTH = 112;

    /**
     * Length in bytes of the encoded {@link ReportAccountTotals} line as
     * declared by {@code 01 REPORT-ACCOUNT-TOTALS} in {@code CVTRA07Y}:
     * 13 + 84 + 15 = 112.
     */
    public static final int REPORT_ACCOUNT_TOTALS_LENGTH = 112;

    /**
     * Length in bytes of the encoded {@link ReportGrandTotals} line as
     * declared by {@code 01 REPORT-GRAND-TOTALS} in {@code CVTRA07Y}:
     * 11 + 86 + 15 = 112.
     */
    public static final int REPORT_GRAND_TOTALS_LENGTH = 112;

    // =====================================================================
    // Internal byte-level constants for static line content.
    //
    // These are byte values, not strings, to avoid any Charset round-trip
    // overhead when assembling output buffers.
    // =====================================================================

    /** ASCII space (0x20) &mdash; the padding byte for all empty positions. */
    private static final byte SPACE = (byte) 0x20;

    /** ASCII hyphen-minus (0x2D) &mdash; the fill character for {@code TRANSACTION-HEADER-2}. */
    private static final byte DASH = (byte) '-';

    /** ASCII period (0x2E) &mdash; the fill character for the dotted lines in totals records. */
    private static final byte DOT = (byte) '.';

    /** REPT-SHORT-NAME literal value: {@code 'DALYREPT'}. */
    private static final String REPT_SHORT_NAME_VALUE = "DALYREPT";

    /** REPT-LONG-NAME literal value: {@code 'Daily Transaction Report'}. */
    private static final String REPT_LONG_NAME_VALUE = "Daily Transaction Report";

    /** REPT-DATE-HEADER literal value: {@code 'Date Range: '}. */
    private static final String REPT_DATE_HEADER_VALUE = "Date Range: ";

    /** FILLER between start and end dates: {@code ' to '}. */
    private static final String DATE_RANGE_SEPARATOR_VALUE = " to ";

    /** REPORT-PAGE-TOTALS label literal: {@code 'Page Total'} padded to 11 bytes. */
    private static final String PAGE_TOTAL_LABEL = "Page Total";

    /** REPORT-ACCOUNT-TOTALS label literal: {@code 'Account Total'} padded to 13 bytes. */
    private static final String ACCOUNT_TOTAL_LABEL = "Account Total";

    /** REPORT-GRAND-TOTALS label literal: {@code 'Grand Total'} padded to 11 bytes. */
    private static final String GRAND_TOTAL_LABEL = "Grand Total";

    /** Column header labels for {@code TRANSACTION-HEADER-1}. */
    private static final String HEADER_LABEL_TRANS_ID = "Transaction ID";
    private static final String HEADER_LABEL_ACCOUNT_ID = "Account ID";
    private static final String HEADER_LABEL_TRAN_TYPE = "Transaction Type";
    private static final String HEADER_LABEL_TRAN_CATEGORY = "Tran Category";
    private static final String HEADER_LABEL_TRAN_SOURCE = "Tran Source";
    /** Column header for amount; COBOL literal {@code '        Amount'} (8 leading spaces). */
    private static final String HEADER_LABEL_AMOUNT = "        Amount";

    /** Width of {@code -ZZZ,ZZZ,ZZZ.ZZ} field as mandated by the agent prompt (14 bytes). */
    private static final int DETAIL_AMOUNT_WIDTH = 14;

    /** Width of {@code +ZZZ,ZZZ,ZZZ.ZZ} field per CVTRA07Y picture count (15 bytes). */
    private static final int TOTAL_AMOUNT_WIDTH = 15;

    /**
     * Prevents instantiation. {@code ReportHeaders} is a holder class for
     * nested record types and static factory methods; it has no per-instance
     * state and is not meant to be subclassed or constructed.
     *
     * @throws UnsupportedOperationException always
     */
    private ReportHeaders() {
        throw new UnsupportedOperationException(
                "ReportHeaders is a holder class; do not instantiate");
    }

    // =====================================================================
    // Constant-line static accessor methods.
    //
    // These two static methods correspond to 01-level groups in CVTRA07Y
    // whose every field is a FILLER literal (no variable data); they are
    // exposed as static methods that return fresh byte[] buffers so that
    // callers cannot mutate a shared array (defensive copy each call).
    // =====================================================================

    /**
     * Returns the encoded {@code TRANSACTION-HEADER-1} line: column captions
     * for the daily transaction detail report.
     *
     * <p>The returned buffer is exactly {@link #TRANSACTION_HEADER_1_LENGTH}
     * (= 114) bytes. The column captions are written at fixed positions
     * (0..16 = 'Transaction ID', 17..28 = 'Account ID', 29..47 = 'Transaction
     * Type', 48..82 = 'Tran Category', 83..96 = 'Tran Source', 97 = space,
     * 98..113 = '        Amount').
     *
     * <p>Each call returns a fresh array to preserve immutability of the
     * class state. Callers receive an array they are free to mutate.
     *
     * @return a newly-allocated 114-byte ASCII buffer containing the
     *         encoded column-header line
     */
    public static byte[] transactionHeader1() {
        byte[] out = new byte[TRANSACTION_HEADER_1_LENGTH];
        Arrays.fill(out, SPACE);
        // FILLER PIC X(17) VALUE 'Transaction ID' (left-justified, space padded)
        writeString(out, HEADER_LABEL_TRANS_ID, 0, 17);
        // FILLER PIC X(12) VALUE 'Account ID'
        writeString(out, HEADER_LABEL_ACCOUNT_ID, 17, 12);
        // FILLER PIC X(19) VALUE 'Transaction Type'
        writeString(out, HEADER_LABEL_TRAN_TYPE, 29, 19);
        // FILLER PIC X(35) VALUE 'Tran Category'
        writeString(out, HEADER_LABEL_TRAN_CATEGORY, 48, 35);
        // FILLER PIC X(14) VALUE 'Tran Source'
        writeString(out, HEADER_LABEL_TRAN_SOURCE, 83, 14);
        // FILLER PIC X VALUE SPACES at position 97 (already filled with SPACE)
        // FILLER PIC X(16) VALUE '        Amount' (literal contains 8 leading spaces)
        writeString(out, HEADER_LABEL_AMOUNT, 98, 16);
        return out;
    }

    /**
     * Returns the encoded {@code TRANSACTION-HEADER-2} line: a separator
     * line of 133 ASCII hyphens (0x2D), exactly as declared by
     * {@code PIC X(133) VALUE ALL '-'} in CVTRA07Y.
     *
     * <p>The returned buffer is exactly {@link #TRANSACTION_HEADER_2_LENGTH}
     * (= 133) bytes, every byte equal to {@code 0x2D}. This matches the
     * natural length of {@code FD-REPTFILE-REC PIC X(133)} in CBTRN03C, so
     * no right-padding is required by the I/O adapter when this line is
     * written.
     *
     * <p>Each call returns a fresh array to preserve immutability of the
     * class state.
     *
     * @return a newly-allocated 133-byte ASCII buffer of hyphens
     */
    public static byte[] transactionHeader2() {
        byte[] out = new byte[TRANSACTION_HEADER_2_LENGTH];
        Arrays.fill(out, DASH);
        return out;
    }

    // =====================================================================
    // Nested record: REPORT-NAME-HEADER.
    // =====================================================================

    /**
     * Variable-data record translating COBOL {@code 01 REPORT-NAME-HEADER}
     * &mdash; the top-of-report banner line containing the report short
     * name, long name, date-range header label, and the configurable
     * start/end date strings.
     *
     * <p>The literal fields ({@code REPT-SHORT-NAME = 'DALYREPT'},
     * {@code REPT-LONG-NAME = 'Daily Transaction Report'}, etc.) are
     * declared as private constants on the enclosing class and inlined by
     * {@link #encode()}; only the variable date components are carried as
     * record components.
     *
     * <p>Both date components are restricted to a maximum length of 10
     * bytes (to match the COBOL {@code PIC X(10)} declarations). Shorter
     * strings are right-padded with ASCII spaces by {@link #encode()}.
     *
     * <p>Layout (115 bytes total):
     * <ul>
     *   <li>[ 0.. 37] REPT-SHORT-NAME &mdash; literal {@code 'DALYREPT'} (38)</li>
     *   <li>[38.. 78] REPT-LONG-NAME &mdash; literal {@code 'Daily Transaction Report'} (41)</li>
     *   <li>[79.. 90] REPT-DATE-HEADER &mdash; literal {@code 'Date Range: '} (12)</li>
     *   <li>[91..100] REPT-START-DATE &mdash; variable (10)</li>
     *   <li>[101..104] FILLER &mdash; literal {@code ' to '} (4)</li>
     *   <li>[105..114] REPT-END-DATE &mdash; variable (10)</li>
     * </ul>
     *
     * @param startDate the report start date string, max 10 bytes; typically
     *                  an ISO-8601 date like {@code "2024-01-01"} but the
     *                  format is determined by the caller (CBTRN03C reads
     *                  the parameter file and writes the value verbatim)
     * @param endDate   the report end date string, max 10 bytes
     */
    public static record ReportNameHeader(String startDate, String endDate) {

        /**
         * Compact canonical constructor enforcing CVTRA07Y field-width
         * invariants. Per AAP &sect;0.3.2 (Records pattern), this is the
         * COBOL-equivalent "validate before bind" pattern for translated
         * 01-level groups.
         *
         * @throws NullPointerException     if any argument is null
         * @throws IllegalArgumentException if either date exceeds 10 bytes
         *                                  when encoded as US-ASCII
         */
        public ReportNameHeader {
            Objects.requireNonNull(startDate, "startDate");
            Objects.requireNonNull(endDate, "endDate");
            if (startDate.length() > 10) {
                throw new IllegalArgumentException(
                        "startDate exceeds 10 bytes (length="
                                + startDate.length() + ", value='"
                                + startDate + "')");
            }
            if (endDate.length() > 10) {
                throw new IllegalArgumentException(
                        "endDate exceeds 10 bytes (length="
                                + endDate.length() + ", value='"
                                + endDate + "')");
            }
        }

        /**
         * Encodes this record into a byte-for-byte representation of the
         * COBOL {@code REPORT-NAME-HEADER} line. The returned buffer is
         * exactly {@link #REPORT_NAME_HEADER_LENGTH} (= 115) bytes.
         *
         * <p>Each call allocates a fresh buffer; the returned array is
         * owned by the caller and may be safely mutated.
         *
         * @return a newly-allocated 115-byte ASCII buffer
         */
        public byte[] encode() {
            byte[] out = new byte[REPORT_NAME_HEADER_LENGTH];
            Arrays.fill(out, SPACE);
            // REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'
            writeString(out, REPT_SHORT_NAME_VALUE, 0, 38);
            // REPT-LONG-NAME PIC X(41) VALUE 'Daily Transaction Report'
            writeString(out, REPT_LONG_NAME_VALUE, 38, 41);
            // REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '
            writeString(out, REPT_DATE_HEADER_VALUE, 79, 12);
            // REPT-START-DATE PIC X(10) -- variable
            writeString(out, startDate, 91, 10);
            // FILLER PIC X(04) VALUE ' to '
            writeString(out, DATE_RANGE_SEPARATOR_VALUE, 101, 4);
            // REPT-END-DATE PIC X(10) -- variable
            writeString(out, endDate, 105, 10);
            return out;
        }
    }

    // =====================================================================
    // Nested record: TRANSACTION-DETAIL-REPORT.
    // =====================================================================

    /**
     * Variable-data record translating COBOL
     * {@code 01 TRANSACTION-DETAIL-REPORT} &mdash; one row in the daily
     * transaction detail report, written once per posted transaction by
     * CBTRN03C.
     *
     * <p>Layout (113 bytes total):
     * <ul>
     *   <li>[  0.. 15] TRAN-REPORT-TRANS-ID &mdash; PIC X(16)</li>
     *   <li>[ 16] FILLER &mdash; PIC X(01) VALUE SPACES (1)</li>
     *   <li>[ 17.. 27] TRAN-REPORT-ACCOUNT-ID &mdash; PIC X(11)</li>
     *   <li>[ 28] FILLER &mdash; PIC X(01) VALUE SPACES (1)</li>
     *   <li>[ 29.. 30] TRAN-REPORT-TYPE-CD &mdash; PIC X(02)</li>
     *   <li>[ 31] FILLER &mdash; PIC X(01) VALUE '-' (1)</li>
     *   <li>[ 32.. 46] TRAN-REPORT-TYPE-DESC &mdash; PIC X(15)</li>
     *   <li>[ 47] FILLER &mdash; PIC X(01) VALUE SPACES (1)</li>
     *   <li>[ 48.. 51] TRAN-REPORT-CAT-CD &mdash; PIC 9(04), zero-padded</li>
     *   <li>[ 52] FILLER &mdash; PIC X(01) VALUE '-' (1)</li>
     *   <li>[ 53.. 81] TRAN-REPORT-CAT-DESC &mdash; PIC X(29)</li>
     *   <li>[ 82] FILLER &mdash; PIC X(01) VALUE SPACES (1)</li>
     *   <li>[ 83.. 92] TRAN-REPORT-SOURCE &mdash; PIC X(10)</li>
     *   <li>[ 93.. 96] FILLER &mdash; PIC X(04) VALUE SPACES (4)</li>
     *   <li>[ 97..110] TRAN-REPORT-AMT &mdash; PIC -ZZZ,ZZZ,ZZZ.ZZ (14)</li>
     *   <li>[111..112] FILLER &mdash; PIC X(02) VALUE SPACES (2)</li>
     * </ul>
     *
     * @param tranId    the transaction identifier (max 16 bytes); from
     *                  {@code TRAN-RECORD.TRAN-ID}
     * @param accountId the account identifier (max 11 bytes); from the
     *                  account-card cross-reference lookup
     * @param typeCd    the 2-byte transaction-type code (from TRANTYPE)
     * @param typeDesc  the transaction-type description (max 15 bytes)
     * @param catCd     the transaction-category code (0..9999) encoded as a
     *                  4-digit zero-padded numeric string
     * @param catDesc   the transaction-category description (max 29 bytes)
     * @param source    the transaction source (max 10 bytes; e.g., the
     *                  channel or terminal identifier)
     * @param amt       the transaction amount as a {@link BigDecimal};
     *                  normalized to scale 2 by {@link #encode()} via
     *                  banker's rounding ({@link Decimals#ROUNDED_MODE})
     *                  before emission as
     *                  {@code -ZZZ,ZZZ,ZZZ.ZZ}-edited text
     */
    public static record TransactionDetailReport(
            String tranId,
            String accountId,
            String typeCd,
            String typeDesc,
            int catCd,
            String catDesc,
            String source,
            BigDecimal amt
    ) {

        /**
         * Compact canonical constructor enforcing CVTRA07Y field-width
         * invariants and the {@code PIC 9(04)} numeric range for
         * {@code catCd}.
         *
         * @throws NullPointerException     if any non-int argument is null
         * @throws IllegalArgumentException if any string exceeds its max
         *                                  byte width or {@code catCd} is
         *                                  outside {@code [0, 9999]}
         */
        public TransactionDetailReport {
            Objects.requireNonNull(tranId, "tranId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(typeCd, "typeCd");
            Objects.requireNonNull(typeDesc, "typeDesc");
            Objects.requireNonNull(catDesc, "catDesc");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(amt, "amt");
            if (tranId.length() > 16) {
                throw new IllegalArgumentException(
                        "tranId exceeds 16 bytes (length="
                                + tranId.length() + ")");
            }
            if (accountId.length() > 11) {
                throw new IllegalArgumentException(
                        "accountId exceeds 11 bytes (length="
                                + accountId.length() + ")");
            }
            if (typeCd.length() > 2) {
                throw new IllegalArgumentException(
                        "typeCd exceeds 2 bytes (length="
                                + typeCd.length() + ")");
            }
            if (typeDesc.length() > 15) {
                throw new IllegalArgumentException(
                        "typeDesc exceeds 15 bytes (length="
                                + typeDesc.length() + ")");
            }
            if (catDesc.length() > 29) {
                throw new IllegalArgumentException(
                        "catDesc exceeds 29 bytes (length="
                                + catDesc.length() + ")");
            }
            if (source.length() > 10) {
                throw new IllegalArgumentException(
                        "source exceeds 10 bytes (length="
                                + source.length() + ")");
            }
            if (catCd < 0 || catCd > 9999) {
                throw new IllegalArgumentException(
                        "catCd must be in [0, 9999], got " + catCd);
            }
        }

        /**
         * Encodes this row into a byte-for-byte representation of the
         * COBOL {@code TRANSACTION-DETAIL-REPORT} line. The returned
         * buffer is exactly {@link #TRANSACTION_DETAIL_REPORT_LENGTH}
         * (= 113) bytes.
         *
         * @return a newly-allocated 113-byte ASCII buffer
         * @throws IllegalArgumentException if the amount value cannot fit
         *                                  in the {@code -ZZZ,ZZZ,ZZZ.ZZ}
         *                                  edit picture
         */
        public byte[] encode() {
            byte[] out = new byte[TRANSACTION_DETAIL_REPORT_LENGTH];
            Arrays.fill(out, SPACE);
            // [0..15] TRAN-REPORT-TRANS-ID
            writeString(out, tranId, 0, 16);
            // [16] FILLER SPACES (pre-filled)
            // [17..27] TRAN-REPORT-ACCOUNT-ID
            writeString(out, accountId, 17, 11);
            // [28] FILLER SPACES (pre-filled)
            // [29..30] TRAN-REPORT-TYPE-CD
            writeString(out, typeCd, 29, 2);
            // [31] FILLER '-'
            out[31] = DASH;
            // [32..46] TRAN-REPORT-TYPE-DESC
            writeString(out, typeDesc, 32, 15);
            // [47] FILLER SPACES (pre-filled)
            // [48..51] TRAN-REPORT-CAT-CD PIC 9(04) -- zero-padded numeric
            String catCdStr = String.format(Locale.ROOT, "%04d", catCd);
            byte[] catCdBytes = catCdStr.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(catCdBytes, 0, out, 48, 4);
            // [52] FILLER '-'
            out[52] = DASH;
            // [53..81] TRAN-REPORT-CAT-DESC
            writeString(out, catDesc, 53, 29);
            // [82] FILLER SPACES (pre-filled)
            // [83..92] TRAN-REPORT-SOURCE
            writeString(out, source, 83, 10);
            // [93..96] FILLER SPACES (pre-filled)
            // [97..110] TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ
            String amtFormatted = formatEditedNegativeOrBlank(amt, DETAIL_AMOUNT_WIDTH);
            byte[] amtBytes = amtFormatted.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(amtBytes, 0, out, 97, DETAIL_AMOUNT_WIDTH);
            // [111..112] FILLER SPACES (pre-filled)
            return out;
        }
    }

    // =====================================================================
    // Nested record: REPORT-PAGE-TOTALS.
    // =====================================================================

    /**
     * Variable-data record translating COBOL {@code 01 REPORT-PAGE-TOTALS}
     * &mdash; the per-page subtotal line written by CBTRN03C at the
     * bottom of each report page.
     *
     * <p>Layout (112 bytes total):
     * <ul>
     *   <li>[ 0.. 10] FILLER &mdash; literal {@code 'Page Total'} (11)</li>
     *   <li>[11.. 96] FILLER &mdash; 86 ASCII periods (dotted fill)</li>
     *   <li>[97..111] REPT-PAGE-TOTAL &mdash; PIC +ZZZ,ZZZ,ZZZ.ZZ (15)</li>
     * </ul>
     *
     * @param pageTotal the accumulated page total as a {@link BigDecimal};
     *                  normalized to scale 2 via banker's rounding before
     *                  emission with the explicit-sign edit picture
     */
    public static record ReportPageTotals(BigDecimal pageTotal) {

        /**
         * Compact canonical constructor enforcing the non-null contract.
         *
         * @throws NullPointerException if {@code pageTotal} is null
         */
        public ReportPageTotals {
            Objects.requireNonNull(pageTotal, "pageTotal");
        }

        /**
         * Encodes this record into a byte-for-byte representation of the
         * COBOL {@code REPORT-PAGE-TOTALS} line. The returned buffer is
         * exactly {@link #REPORT_PAGE_TOTALS_LENGTH} (= 112) bytes.
         *
         * @return a newly-allocated 112-byte ASCII buffer
         * @throws IllegalArgumentException if {@code pageTotal} cannot fit
         *                                  in the {@code +ZZZ,ZZZ,ZZZ.ZZ}
         *                                  edit picture
         */
        public byte[] encode() {
            byte[] out = new byte[REPORT_PAGE_TOTALS_LENGTH];
            Arrays.fill(out, SPACE);
            // [0..10] FILLER PIC X(11) VALUE 'Page Total'
            writeString(out, PAGE_TOTAL_LABEL, 0, 11);
            // [11..96] FILLER PIC X(86) VALUE ALL '.'
            Arrays.fill(out, 11, 97, DOT);
            // [97..111] REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ
            String totalStr = formatEditedExplicitSign(pageTotal, TOTAL_AMOUNT_WIDTH);
            byte[] totalBytes = totalStr.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(totalBytes, 0, out, 97, TOTAL_AMOUNT_WIDTH);
            return out;
        }
    }

    // =====================================================================
    // Nested record: REPORT-ACCOUNT-TOTALS.
    // =====================================================================

    /**
     * Variable-data record translating COBOL
     * {@code 01 REPORT-ACCOUNT-TOTALS} &mdash; the per-account subtotal
     * line written by CBTRN03C at the end of each account's run of
     * transaction rows.
     *
     * <p>Layout (112 bytes total):
     * <ul>
     *   <li>[ 0.. 12] FILLER &mdash; literal {@code 'Account Total'} (13)</li>
     *   <li>[13.. 96] FILLER &mdash; 84 ASCII periods (dotted fill)</li>
     *   <li>[97..111] REPT-ACCOUNT-TOTAL &mdash; PIC +ZZZ,ZZZ,ZZZ.ZZ (15)</li>
     * </ul>
     *
     * @param accountTotal the accumulated per-account total as a
     *                     {@link BigDecimal}; normalized to scale 2 via
     *                     banker's rounding before emission with the
     *                     explicit-sign edit picture
     */
    public static record ReportAccountTotals(BigDecimal accountTotal) {

        /**
         * Compact canonical constructor enforcing the non-null contract.
         *
         * @throws NullPointerException if {@code accountTotal} is null
         */
        public ReportAccountTotals {
            Objects.requireNonNull(accountTotal, "accountTotal");
        }

        /**
         * Encodes this record into a byte-for-byte representation of the
         * COBOL {@code REPORT-ACCOUNT-TOTALS} line. The returned buffer is
         * exactly {@link #REPORT_ACCOUNT_TOTALS_LENGTH} (= 112) bytes.
         *
         * @return a newly-allocated 112-byte ASCII buffer
         * @throws IllegalArgumentException if {@code accountTotal} cannot
         *                                  fit in the
         *                                  {@code +ZZZ,ZZZ,ZZZ.ZZ} edit
         *                                  picture
         */
        public byte[] encode() {
            byte[] out = new byte[REPORT_ACCOUNT_TOTALS_LENGTH];
            Arrays.fill(out, SPACE);
            // [0..12] FILLER PIC X(13) VALUE 'Account Total'
            writeString(out, ACCOUNT_TOTAL_LABEL, 0, 13);
            // [13..96] FILLER PIC X(84) VALUE ALL '.'
            Arrays.fill(out, 13, 97, DOT);
            // [97..111] REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ
            String totalStr = formatEditedExplicitSign(accountTotal, TOTAL_AMOUNT_WIDTH);
            byte[] totalBytes = totalStr.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(totalBytes, 0, out, 97, TOTAL_AMOUNT_WIDTH);
            return out;
        }
    }

    // =====================================================================
    // Nested record: REPORT-GRAND-TOTALS.
    // =====================================================================

    /**
     * Variable-data record translating COBOL
     * {@code 01 REPORT-GRAND-TOTALS} &mdash; the report-wide grand-total
     * line written by CBTRN03C as the final line of the report.
     *
     * <p>Layout (112 bytes total):
     * <ul>
     *   <li>[ 0.. 10] FILLER &mdash; literal {@code 'Grand Total'} (11)</li>
     *   <li>[11.. 96] FILLER &mdash; 86 ASCII periods (dotted fill)</li>
     *   <li>[97..111] REPT-GRAND-TOTAL &mdash; PIC +ZZZ,ZZZ,ZZZ.ZZ (15)</li>
     * </ul>
     *
     * @param grandTotal the report grand total as a {@link BigDecimal};
     *                   normalized to scale 2 via banker's rounding before
     *                   emission with the explicit-sign edit picture
     */
    public static record ReportGrandTotals(BigDecimal grandTotal) {

        /**
         * Compact canonical constructor enforcing the non-null contract.
         *
         * @throws NullPointerException if {@code grandTotal} is null
         */
        public ReportGrandTotals {
            Objects.requireNonNull(grandTotal, "grandTotal");
        }

        /**
         * Encodes this record into a byte-for-byte representation of the
         * COBOL {@code REPORT-GRAND-TOTALS} line. The returned buffer is
         * exactly {@link #REPORT_GRAND_TOTALS_LENGTH} (= 112) bytes.
         *
         * @return a newly-allocated 112-byte ASCII buffer
         * @throws IllegalArgumentException if {@code grandTotal} cannot
         *                                  fit in the
         *                                  {@code +ZZZ,ZZZ,ZZZ.ZZ} edit
         *                                  picture
         */
        public byte[] encode() {
            byte[] out = new byte[REPORT_GRAND_TOTALS_LENGTH];
            Arrays.fill(out, SPACE);
            // [0..10] FILLER PIC X(11) VALUE 'Grand Total'
            writeString(out, GRAND_TOTAL_LABEL, 0, 11);
            // [11..96] FILLER PIC X(86) VALUE ALL '.'
            Arrays.fill(out, 11, 97, DOT);
            // [97..111] REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ
            String totalStr = formatEditedExplicitSign(grandTotal, TOTAL_AMOUNT_WIDTH);
            byte[] totalBytes = totalStr.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(totalBytes, 0, out, 97, TOTAL_AMOUNT_WIDTH);
            return out;
        }
    }

    // =====================================================================
    // Shared helpers (package-private for testability; not part of the
    // public API surface).
    // =====================================================================

    /**
     * Writes a US-ASCII string into a byte buffer at a given offset,
     * truncating to {@code length} bytes if the string is longer and
     * leaving trailing positions untouched (typically pre-filled with
     * {@link #SPACE}) if the string is shorter. Used for COBOL
     * left-justified alphanumeric ({@code PIC X(n)}) emission.
     *
     * <p>The buffer must already be pre-filled with the desired padding
     * byte (this method does not pad). Strings longer than {@code length}
     * are silently truncated to match COBOL {@code MOVE alpha-source TO
     * alpha-dest} semantics where {@code source} is longer than
     * {@code dest}.
     *
     * @param out    the destination buffer (must not be null and must have
     *               capacity {@code offset + length})
     * @param value  the source string (must not be null)
     * @param offset the starting offset within {@code out}
     * @param length the maximum number of bytes to write
     */
    private static void writeString(byte[] out, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, out, offset, copyLen);
        // Trailing positions remain at their pre-filled value (SPACE).
    }

    /**
     * Formats a {@link BigDecimal} for COBOL
     * {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} emission: leading sign-or-blank in the
     * leftmost position, group-of-3 digits separated by commas, fixed
     * decimal point, and two cents digits. Negative values show
     * {@code '-'} in the leftmost position; positive and zero values show
     * a leading space.
     *
     * <p>The {@code value} is first normalized to scale 2 via
     * {@link Decimals#ROUNDED_MODE} (banker's rounding) so the emitted
     * cents digits match COBOL's {@code ROUNDED} semantics. The integer
     * portion is then formatted with US-style thousands separators (commas)
     * and left-padded with spaces to fill {@code width - 1} characters
     * (the remaining 1 character holds the leading sign-or-blank).
     *
     * <p>Edge cases:
     * <ul>
     *   <li>{@code value == 0}: output is {@code "          0.00"}
     *       (10 spaces + {@code "0.00"}, width 14). This differs from a
     *       strict COBOL BLANK-WHEN-ZERO interpretation that would emit
     *       all blanks; the spec specifies the explicit-zero rendering
     *       above.</li>
     *   <li>{@code value} smaller than 1.00 in magnitude (e.g.,
     *       {@code 0.05}): the leading zero is emitted (e.g.,
     *       {@code "          0.05"}) rather than COBOL-style suppressed.
     *       The {@code Z}-suppression deviation is intentional to keep
     *       the helper deterministic and is documented in the
     *       MIGRATION_NOTES.md fidelity note for CVTRA07Y.</li>
     *   <li>{@code value} whose formatted representation exceeds
     *       {@code width - 1} characters: this method throws
     *       {@link IllegalArgumentException} rather than silently
     *       truncating (which could produce a misleading sign or comma
     *       placement in the output).</li>
     * </ul>
     *
     * @param value the {@link BigDecimal} to format (must not be null)
     * @param width the total output width in bytes (must be {@code >= 4}
     *              to accommodate at least sign-or-blank + {@code "0.00"})
     * @return a fixed-width string of exactly {@code width} ASCII characters
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code width < 4} or if the
     *                                  formatted magnitude exceeds
     *                                  {@code width - 1} characters
     */
    static String formatEditedNegativeOrBlank(BigDecimal value, int width) {
        Objects.requireNonNull(value, "value");
        if (width < 4) {
            throw new IllegalArgumentException(
                    "width must be >= 4 to hold sign + '0.00'; got " + width);
        }
        BigDecimal scaled = value.setScale(2, Decimals.ROUNDED_MODE);
        boolean negative = scaled.signum() < 0;
        // Format absolute value with US-style comma grouping and exactly
        // 2 cents digits (Locale.ROOT pins the separators to comma and
        // period regardless of the JVM's default locale, which is the
        // BINDING requirement for byte-identical output across hosts).
        String digitsAndCommas = String.format(
                Locale.ROOT, "%,.2f", scaled.abs());
        int contentWidth = width - 1;
        if (digitsAndCommas.length() > contentWidth) {
            throw new IllegalArgumentException(
                    "value " + value + " exceeds picture width " + width
                            + " (formatted='" + digitsAndCommas
                            + "', max=" + contentWidth + ")");
        }
        // Left-pad with spaces to fill the content width.
        String padded = String.format(
                Locale.ROOT, "%" + contentWidth + "s", digitsAndCommas);
        char sign = negative ? '-' : ' ';
        return sign + padded;
    }

    // =====================================================================
    // Legacy interop layer (String-based API).
    //
    // The records-based API above (ReportNameHeader, TransactionDetailReport,
    // ReportPageTotals, ReportAccountTotals, ReportGrandTotals + the two
    // static byte[] header accessors) is the schema-mandated primary API
    // and is what new code SHOULD use. The constants and helper methods in
    // this section are a String-based interop layer kept for backward
    // compatibility with carddemo-application classes (notably CbTrn03C)
    // that were authored against earlier ReportHeaders revisions.
    //
    // These members are NOT redundant with the records-based API at the
    // byte level: they emit Strings (intended for downstream padding-to-133
    // and StringBuilder assembly), whereas the records emit fixed-width
    // byte[] buffers. Both APIs converge on identical content; they differ
    // only in representation.
    //
    // Per the agent_prompt and AAP §0.7.1 minimal-change clause, retaining
    // this compatibility layer avoids cross-module churn while the records
    // API rolls out.
    // =====================================================================

    /**
     * Full report-line width in bytes &mdash; the natural length of
     * {@code FD-REPTFILE-REC PIC X(133)} declared in CBTRN03C.
     * All report-line emissions are padded or truncated to this width by
     * the I/O adapter before being written to disk.
     */
    public static final int REPORT_LINE_WIDTH = 133;

    /** REPT-SHORT-NAME default literal: {@code "DALYREPT"} (38 bytes when padded). */
    public static final String DEFAULT_SHORT_NAME = REPT_SHORT_NAME_VALUE;

    /** REPT-LONG-NAME default literal: {@code "Daily Transaction Report"} (41 bytes when padded). */
    public static final String DEFAULT_LONG_NAME = REPT_LONG_NAME_VALUE;

    /** REPT-DATE-HEADER literal: {@code "Date Range: "} (12 bytes when padded). */
    public static final String DATE_HEADER_PREFIX = REPT_DATE_HEADER_VALUE;

    /** FILLER literal between start and end dates: {@code " to "} (4 bytes). */
    public static final String DATE_RANGE_SEPARATOR = DATE_RANGE_SEPARATOR_VALUE;

    // ----- REPORT-NAME-HEADER field widths -----

    /** Width of REPT-SHORT-NAME ({@code PIC X(38)}). */
    public static final int LEN_SHORT_NAME = 38;
    /** Width of REPT-LONG-NAME ({@code PIC X(41)}). */
    public static final int LEN_LONG_NAME = 41;
    /** Width of REPT-DATE-HEADER ({@code PIC X(12)}). */
    public static final int LEN_DATE_HEADER = 12;
    /** Width of REPT-START-DATE / REPT-END-DATE ({@code PIC X(10)}). */
    public static final int LEN_REPORT_DATE = 10;
    /** Width of the {@code ' to '} FILLER between the two dates ({@code PIC X(04)}). */
    public static final int LEN_DATE_SEPARATOR = 4;

    // ----- TRANSACTION-DETAIL-REPORT field widths -----

    /** Width of TRAN-REPORT-TRANS-ID ({@code PIC X(16)}). */
    public static final int LEN_TRANS_ID = 16;
    /** Width of TRAN-REPORT-ACCOUNT-ID ({@code PIC X(11)}). */
    public static final int LEN_ACCOUNT_ID = 11;
    /** Width of TRAN-REPORT-TYPE-CD ({@code PIC X(02)}). */
    public static final int LEN_TYPE_CD = 2;
    /** Width of TRAN-REPORT-TYPE-DESC ({@code PIC X(15)}). */
    public static final int LEN_TYPE_DESC = 15;
    /** Width of TRAN-REPORT-CAT-CD ({@code PIC 9(04)}). */
    public static final int LEN_CAT_CD = 4;
    /** Width of TRAN-REPORT-CAT-DESC ({@code PIC X(29)}). */
    public static final int LEN_CAT_DESC = 29;
    /** Width of TRAN-REPORT-SOURCE ({@code PIC X(10)}). */
    public static final int LEN_SOURCE = 10;

    // ----- TRANSACTION-HEADER-1 column captions -----

    /** Column caption literal at position 0..13 in TRANSACTION-HEADER-1. */
    public static final String HEADER_TRANSACTION_ID = HEADER_LABEL_TRANS_ID;
    /** Column caption literal at position 17..26 in TRANSACTION-HEADER-1. */
    public static final String HEADER_ACCOUNT_ID = HEADER_LABEL_ACCOUNT_ID;
    /** Column caption literal at position 29..44 in TRANSACTION-HEADER-1. */
    public static final String HEADER_TRANSACTION_TYPE = HEADER_LABEL_TRAN_TYPE;
    /** Column caption literal at position 48..60 in TRANSACTION-HEADER-1. */
    public static final String HEADER_TRAN_CATEGORY = HEADER_LABEL_TRAN_CATEGORY;
    /** Column caption literal at position 83..93 in TRANSACTION-HEADER-1. */
    public static final String HEADER_TRAN_SOURCE = HEADER_LABEL_TRAN_SOURCE;
    /** Column caption literal at position 98..111 in TRANSACTION-HEADER-1 (8 leading spaces preserved). */
    public static final String HEADER_AMOUNT = HEADER_LABEL_AMOUNT;

    // ----- Totals labels -----

    /** Page-totals label literal: {@code "Page Total"} (10 bytes; padded to 11 in record). */
    public static final String LABEL_PAGE_TOTAL = PAGE_TOTAL_LABEL;
    /** Account-totals label literal: {@code "Account Total"} (13 bytes exactly). */
    public static final String LABEL_ACCOUNT_TOTAL = ACCOUNT_TOTAL_LABEL;
    /** Grand-totals label literal: {@code "Grand Total"} (11 bytes exactly). */
    public static final String LABEL_GRAND_TOTAL = GRAND_TOTAL_LABEL;

    // ----- Edit-mask templates (for documentation / introspection) -----

    /** COBOL picture string for the transaction-detail amount field. */
    public static final String AMOUNT_EDIT_MASK_DETAIL = "-ZZZ,ZZZ,ZZZ.ZZ";
    /** COBOL picture string for the totals amount fields. */
    public static final String AMOUNT_EDIT_MASK_TOTAL = "+ZZZ,ZZZ,ZZZ.ZZ";

    /**
     * Returns a String of {@link #REPORT_LINE_WIDTH} (= 133) ASCII hyphens,
     * the String-equivalent of {@link #transactionHeader2()}. Used by
     * String-based assembly in {@code CbTrn03C} (legacy interop).
     *
     * @return a 133-character String of {@code '-'} characters
     */
    public static String separatorLine() {
        return "-".repeat(REPORT_LINE_WIDTH);
    }

    /**
     * Returns the concatenation of {@code label} and {@code totalDots}
     * ASCII period characters. Used by {@code CbTrn03C} to build the
     * dotted-fill lines for the totals records (page, account, grand)
     * via String assembly (legacy interop).
     *
     * @param label     the leading label text (must not be null)
     * @param totalDots the number of period characters to append
     *                  (must be non-negative)
     * @return {@code label} followed by exactly {@code totalDots} dots
     * @throws NullPointerException     if {@code label} is null
     * @throws IllegalArgumentException if {@code totalDots} is negative
     */
    public static String dottedLine(String label, int totalDots) {
        Objects.requireNonNull(label, "label");
        if (totalDots < 0) {
            throw new IllegalArgumentException(
                    "totalDots must be >= 0, got " + totalDots);
        }
        return label + ".".repeat(totalDots);
    }

    /**
     * Formats a monetary value using the transaction-detail edit picture
     * {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}: sign-or-blank in the leftmost position,
     * comma-grouped digits, fixed decimal point, and two cents digits.
     * Returns a fixed-width {@link String} of {@link #DETAIL_AMOUNT_WIDTH}
     * (= 14) characters.
     *
     * <p>This is the String-emitting variant of the amount field encoded
     * inside {@link TransactionDetailReport#encode()}. It uses the same
     * underlying {@link #formatEditedNegativeOrBlank(BigDecimal, int)}
     * helper and yields character-identical output, suitable for
     * downstream StringBuilder assembly in {@code CbTrn03C} (legacy
     * interop).
     *
     * @param value the {@link BigDecimal} to format (must not be null)
     * @return a 14-character ASCII-compatible String
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if the value's formatted magnitude
     *                                  exceeds 13 characters
     */
    public static String formatDetailAmount(BigDecimal value) {
        return formatEditedNegativeOrBlank(value, DETAIL_AMOUNT_WIDTH);
    }

    /**
     * Formats a monetary value using the totals edit picture
     * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}: explicit sign in the leftmost position,
     * comma-grouped digits, fixed decimal point, and two cents digits.
     * Returns a fixed-width {@link String} of {@link #TOTAL_AMOUNT_WIDTH}
     * (= 15) characters.
     *
     * <p>This is the String-emitting variant of the amount field encoded
     * inside {@link ReportPageTotals#encode()},
     * {@link ReportAccountTotals#encode()}, and
     * {@link ReportGrandTotals#encode()}. It uses the same underlying
     * {@link #formatEditedExplicitSign(BigDecimal, int)} helper and yields
     * character-identical output, suitable for downstream StringBuilder
     * assembly in {@code CbTrn03C} (legacy interop).
     *
     * @param value the {@link BigDecimal} to format (must not be null)
     * @return a 15-character ASCII-compatible String
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if the value's formatted magnitude
     *                                  exceeds 14 characters
     */
    public static String formatTotalAmount(BigDecimal value) {
        return formatEditedExplicitSign(value, TOTAL_AMOUNT_WIDTH);
    }

    /**
     * Formats a {@link BigDecimal} for COBOL
     * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} emission: explicit sign ({@code '+'} or
     * {@code '-'}, never a space) in the leftmost position, followed by
     * comma-grouped integer digits, a fixed decimal point, and two cents
     * digits.
     *
     * <p>Unlike {@link #formatEditedNegativeOrBlank(BigDecimal, int)},
     * positive and zero values render a literal {@code '+'} in the sign
     * position. This matches the COBOL {@code +} fixed-sign-insertion
     * picture symbol used in the three totals records of CVTRA07Y.
     *
     * <p>The rounding mode, locale handling, and overflow check are
     * identical to {@link #formatEditedNegativeOrBlank}.
     *
     * @param value the {@link BigDecimal} to format (must not be null)
     * @param width the total output width in bytes (must be {@code >= 4})
     * @return a fixed-width string of exactly {@code width} ASCII characters
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code width < 4} or if the
     *                                  formatted magnitude exceeds
     *                                  {@code width - 1} characters
     */
    static String formatEditedExplicitSign(BigDecimal value, int width) {
        Objects.requireNonNull(value, "value");
        if (width < 4) {
            throw new IllegalArgumentException(
                    "width must be >= 4 to hold sign + '0.00'; got " + width);
        }
        BigDecimal scaled = value.setScale(2, Decimals.ROUNDED_MODE);
        boolean negative = scaled.signum() < 0;
        String digitsAndCommas = String.format(
                Locale.ROOT, "%,.2f", scaled.abs());
        int contentWidth = width - 1;
        if (digitsAndCommas.length() > contentWidth) {
            throw new IllegalArgumentException(
                    "value " + value + " exceeds picture width " + width
                            + " (formatted='" + digitsAndCommas
                            + "', max=" + contentWidth + ")");
        }
        String padded = String.format(
                Locale.ROOT, "%" + contentWidth + "s", digitsAndCommas);
        char sign = negative ? '-' : '+';
        return sign + padded;
    }
}
