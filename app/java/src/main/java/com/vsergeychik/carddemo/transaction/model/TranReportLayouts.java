package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * The five report line layouts of {@code app/cpy/CVTRA07Y.cpy}, the print image of the CardDemo
 * daily transaction report.
 *
 * <p>This is the single Java type for that copybook (gate G8). It is pure presentation bytes: every
 * literal, every space, every dot and every hyphen below is part of the contract, transcribed
 * character for character from the copybook rather than paraphrased. It is also the only type in
 * the module that has to implement COBOL <em>numeric-edited</em> {@code PICTURE} semantics, because
 * three of its fields are declared with zero-suppression masks rather than with {@code PIC 9}.
 *
 * <h2>Sole consumer</h2>
 *
 * <p>Exactly one COBOL program copies {@code CVTRA07Y}: {@code app/cbl/CBTRN03C.cbl}, whose output
 * is the {@code TRANREPT} dataset declared {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} in both
 * {@code app/jcl/TRANREPT.jcl:78} and {@code app/proc/TRANREPT.prc:76} (gate G20). The statements
 * this type exists to support are:
 *
 * <ul>
 *   <li>{@code :277-278} — {@code MOVE WS-START-DATE TO REPT-START-DATE} and
 *       {@code MOVE WS-END-DATE TO REPT-END-DATE}, both {@code PIC X(10)}, sourced from the
 *       {@code DATEPARM} record.</li>
 *   <li>{@code :294-295} — {@code MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL} then
 *       {@code MOVE REPORT-PAGE-TOTALS TO FD-REPTFILE-REC}.</li>
 *   <li>{@code :307-308} — the account-total pair; {@code :319-320} — the grand-total pair.</li>
 *   <li>{@code :325} — {@code MOVE REPORT-NAME-HEADER TO FD-REPTFILE-REC}; {@code :333} —
 *       {@code TRANSACTION-HEADER-1}; {@code :300}, {@code :312} and {@code :337} —
 *       {@code MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC}, three separate sites.</li>
 *   <li>{@code :362-371} — the detail line: {@code INITIALIZE TRANSACTION-DETAIL-REPORT} followed
 *       by eight {@code MOVE}s and then {@code MOVE TRANSACTION-DETAIL-REPORT TO
 *       FD-REPTFILE-REC}.</li>
 * </ul>
 *
 * <h2>The seven layouts and their column maps</h2>
 *
 * <p>{@code CVTRA07Y} declares five {@code 01}-level items, one of which — the three total lines —
 * is really three sibling declarations, so seven record areas are modelled. Columns below are
 * 1-based, exactly as a COBOL programmer counts them; the offsets in the code are the 0-based
 * equivalents.
 *
 * <h3>{@code REPORT-NAME-HEADER} — 115 bytes</h3>
 *
 * <pre>
 * cols   1- 38  REPT-SHORT-NAME    PIC X(38) VALUE 'DALYREPT'                  (literal 8, right-padded)
 * cols  39- 79  REPT-LONG-NAME     PIC X(41) VALUE 'Daily Transaction Report'  (literal 24, right-padded)
 * cols  80- 91  REPT-DATE-HEADER   PIC X(12) VALUE 'Date Range: '              (literal 12, exactly fills)
 * cols  92-101  REPT-START-DATE    PIC X(10) VALUE SPACES
 * cols 102-105  FILLER             PIC X(04) VALUE ' to '                      (literal 4, exactly fills)
 * cols 106-115  REPT-END-DATE      PIC X(10) VALUE SPACES
 * </pre>
 *
 * <h3>{@code TRANSACTION-DETAIL-REPORT} — 114 bytes</h3>
 *
 * <pre>
 * cols   1- 16  TRAN-REPORT-TRANS-ID    PIC X(16)
 * col       17  FILLER                  PIC X(01) VALUE SPACES
 * cols  18- 28  TRAN-REPORT-ACCOUNT-ID  PIC X(11)
 * col       29  FILLER                  PIC X(01) VALUE SPACES
 * cols  30- 31  TRAN-REPORT-TYPE-CD     PIC X(02)
 * col       32  FILLER                  PIC X(01) VALUE '-'      &lt;== separator, survives INITIALIZE
 * cols  33- 47  TRAN-REPORT-TYPE-DESC   PIC X(15)
 * col       48  FILLER                  PIC X(01) VALUE SPACES
 * cols  49- 52  TRAN-REPORT-CAT-CD      PIC 9(04)
 * col       53  FILLER                  PIC X(01) VALUE '-'      &lt;== separator, survives INITIALIZE
 * cols  54- 82  TRAN-REPORT-CAT-DESC    PIC X(29)
 * col       83  FILLER                  PIC X(01) VALUE SPACES
 * cols  84- 93  TRAN-REPORT-SOURCE      PIC X(10)
 * cols  94- 97  FILLER                  PIC X(04) VALUE SPACES
 * cols  98-112  TRAN-REPORT-AMT         PIC -ZZZ,ZZZ,ZZZ.ZZ      (numeric-edited, 15 bytes)
 * cols 113-114  FILLER                  PIC X(02) VALUE SPACES
 * </pre>
 *
 * <p><strong>Eight {@code FILLER}s, not four.</strong> This layout declares eight reserved spans:
 * two carrying {@code '-'} (columns 32 and 53) and <em>six</em> carrying {@code SPACES} (columns
 * 17, 29, 48, 83, 94-97 and 113-114). Secondary descriptions of this copybook have been seen to
 * say "four space fillers"; the copybook itself says six, and the copybook is authoritative. All
 * eight are declared here and all eight are asserted to survive {@link
 * #initializeTransactionDetailReport()}.
 *
 * <h3>{@code TRANSACTION-HEADER-1} — 114 bytes, every item a {@code FILLER}</h3>
 *
 * <pre>
 * cols   1- 17  FILLER PIC X(17) VALUE 'Transaction ID'    (literal 14)
 * cols  18- 29  FILLER PIC X(12) VALUE 'Account ID'        (literal 10)
 * cols  30- 48  FILLER PIC X(19) VALUE 'Transaction Type'  (literal 16)
 * cols  49- 83  FILLER PIC X(35) VALUE 'Tran Category'     (literal 13)
 * cols  84- 97  FILLER PIC X(14) VALUE 'Tran Source'       (literal 11)
 * col       98  FILLER PIC X     VALUE SPACES              (an implicit X(01))
 * cols  99-114  FILLER PIC X(16) VALUE '        Amount'    (literal 14: EXACTLY 8 leading spaces)
 * </pre>
 *
 * <p>Because the last literal carries exactly eight leading spaces and starts at column 99, the
 * word {@code Amount} lands on columns 107-112 — its final character sitting on column 112, the
 * last digit column of the amount mask below it. Change the space count and the report's amount
 * heading no longer lines up with its amounts.
 *
 * <h3>{@code TRANSACTION-HEADER-2} — 133 bytes</h3>
 *
 * <p>An <em>elementary</em> item with no sub-items: {@code 01 TRANSACTION-HEADER-2 PIC X(133) VALUE
 * ALL '-'}, that is exactly 133 hyphens. It is already the full record width, which is why {@code
 * TranReportWriter} passes it through unchanged.
 *
 * <h3>The three total lines — 112 bytes each</h3>
 *
 * <pre>
 * REPORT-PAGE-TOTALS      cols   1- 11  FILLER PIC X(11) VALUE 'Page Total'    (literal 10, one trailing space)
 *                         cols  12- 97  FILLER PIC X(86) VALUE ALL '.'
 *                         cols  98-112  REPT-PAGE-TOTAL     PIC +ZZZ,ZZZ,ZZZ.ZZ
 * REPORT-ACCOUNT-TOTALS   cols   1- 13  FILLER PIC X(13) VALUE 'Account Total' (literal 13, exactly fills)
 *                         cols  14- 97  FILLER PIC X(84) VALUE ALL '.'
 *                         cols  98-112  REPT-ACCOUNT-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ
 * REPORT-GRAND-TOTALS     cols   1- 11  FILLER PIC X(11) VALUE 'Grand Total'   (literal 11, exactly fills)
 *                         cols  12- 97  FILLER PIC X(86) VALUE ALL '.'
 *                         cols  98-112  REPT-GRAND-TOTAL    PIC +ZZZ,ZZZ,ZZZ.ZZ
 * </pre>
 *
 * <h2>The column-97 invariant, and why the dot leaders differ</h2>
 *
 * <p>In <strong>all four</strong> amount-bearing layouts the bytes ahead of the amount sum to
 * exactly <strong>97</strong>, so the 15-byte mask always occupies 1-based columns
 * <strong>98-112</strong>, that is 0-based offset {@value #AMOUNT_OFFSET}:
 *
 * <pre>
 * detail        16+1+11+1+2+1+15+1+4+1+29+1+10+4 = 97, then the mask
 * page total    11 + 86 = 97      account total  13 + 84 = 97      grand total  11 + 86 = 97
 * </pre>
 *
 * <p><strong>This is precisely why the three dot leaders are 86, 84 and 86 and not one shared
 * constant: they compensate for label widths of 11, 13 and 11.</strong> Normalising them to a
 * uniform width — which looks like tidying — silently shifts the account-total amount two columns
 * away from every other amount in the report and breaks column alignment throughout. The three
 * widths are therefore declared as three separate constants ({@link #PAGE_TOTAL_LEADER_LENGTH},
 * {@link #ACCOUNT_TOTAL_LEADER_LENGTH}, {@link #GRAND_TOTAL_LEADER_LENGTH}) and the invariant is
 * re-checked at class initialisation, so an attempt to unify them fails the build rather than the
 * report.
 *
 * <h2>Numeric-edited semantics</h2>
 *
 * <p>Both masks — {@value #DETAIL_AMOUNT_MASK} and {@value #TOTAL_AMOUNT_MASK} — are 15 characters
 * holding nine integer digit positions and two decimal positions, <em>every one of them</em> a
 * {@code Z}. The rules implemented in {@link #editDetailAmount(BigDecimal)} and {@link
 * #editTotalAmount(BigDecimal)} are taken from the <em>IBM Enterprise COBOL for z/OS Language
 * Reference</em>, sections "Zero suppression and replacement editing" and "Fixed insertion editing"
 * of the {@code PICTURE} clause:
 *
 * <ol>
 *   <li><b>Fixed sign in position 1.</b> A single leading {@code -} or {@code +} is a fixed
 *       insertion character occupying the leftmost position and does not float next to the first
 *       digit. {@code -} renders a minus for a negative value and a <em>space</em> for a positive
 *       one; {@code +} renders a plus for positive and a minus for negative.</li>
 *   <li><b>Leading-zero suppression.</b> {@code Z} replaces a leading zero with a space, up to but
 *       not including the first significant digit. Suppression terminates at the first non-zero
 *       digit or at the decimal point, whichever is encountered first.</li>
 *   <li><b>Insertion-character suppression.</b> A comma lying to the left of the first significant
 *       digit is part of the suppression string and is itself replaced by a space.</li>
 *   <li><b>The all-{@code Z} zero rule.</b> Because every digit position is a suppression symbol,
 *       a value of zero blanks the <em>entire</em> edited item — sign, both commas and the decimal
 *       point included. A zero page, account or grand total therefore prints a blank amount column,
 *       and a zero detail amount prints 15 spaces. This is behaviour, not cosmetics: {@code 0.00}
 *       and {@code .00} are both wrong.</li>
 * </ol>
 *
 * <p>The masks are rendered by explicit character placement into a 15-character buffer. {@code
 * String.format}, {@code DecimalFormat} and {@code NumberFormat} are deliberately not used: they
 * are locale-sensitive in their grouping separator, decimal separator and negative form, so they
 * would make the report vary by machine, and none of them implements {@code Z} suppression or the
 * all-{@code Z} zero rule at all.
 *
 * <p>The sending values are {@code PIC S9(09)V99} — {@code CBTRN03C:134-136} declares {@code
 * WS-PAGE-TOTAL}, {@code WS-ACCOUNT-TOTAL} and {@code WS-GRAND-TOTAL} that way, and {@code
 * TRAN-AMT} of {@code app/cpy/CVTRA05Y.cpy} likewise. Nine integer digits and two decimals match
 * the mask exactly, so no truncation occurs on the move; the value nonetheless arrives through
 * {@link CobolDecimal}, at scale 2 with {@link java.math.RoundingMode#DOWN}, because {@code
 * ROUNDED} appears zero times in all 28 programs and COBOL therefore truncates. Zero is detected
 * with {@code signum()}, never with {@code equals}, since {@code BigDecimal("0.00")} is not equal
 * to {@code BigDecimal.ZERO}.
 *
 * <h2>{@code INITIALIZE} semantics</h2>
 *
 * <p>{@code CBTRN03C:362} executes {@code INITIALIZE TRANSACTION-DETAIL-REPORT} immediately before
 * the eight detail moves. Per the same reference, section "Initializing a structure (INITIALIZE)",
 * an {@code INITIALIZE} without {@code REPLACING} implies {@code SPACE} for alphabetic,
 * alphanumeric and alphanumeric-edited items and {@code ZERO} for numeric <em>and
 * numeric-edited</em> items, and elementary items with an explicit or implicit {@code FILLER}
 * clause are not receiving operands unless the {@code FILLER} phrase is written. Reproduced exactly
 * by {@link #initializeTransactionDetailReport()}:
 *
 * <ul>
 *   <li>all eight {@code FILLER} spans keep their declared {@code VALUE}, so the {@code '-'}
 *       separators at columns 32 and 53 survive and must not be re-spaced;</li>
 *   <li>{@code TRAN-REPORT-CAT-CD PIC 9(04)} becomes {@code 0000};</li>
 *   <li>{@code TRAN-REPORT-AMT} is numeric-edited, so it receives {@code ZERO}, whose edited image
 *       under the all-{@code Z} rule is 15 spaces;</li>
 *   <li>the six named {@code PIC X} fields become spaces.</li>
 * </ul>
 *
 * <h2>Division of responsibility with {@code TranReportWriter}</h2>
 *
 * <p>Every {@code render…} method here returns the layout at its <strong>natural</strong> width —
 * 115, 114, 114, 133, 112, 112, 112 — and <strong>never</strong> pads to 133. {@code
 * transaction.TranReportWriter} owns that normalisation, reproducing {@code MOVE <layout> TO
 * FD-REPTFILE-REC PIC X(133)} (declared at {@code CBTRN03C:85}) by right-space-padding, and passing
 * the already-133-byte {@code TRANSACTION-HEADER-2} through unchanged. Padding here as well would
 * duplicate the writer's job and hide a width defect behind a correct-looking record.
 *
 * <p>Three items are consequently <strong>absent by design</strong>, because they belong to {@code
 * CBTRN03C}'s own {@code WORKING-STORAGE} and not to this copybook: {@code WS-BLANK-LINE PIC X(133)
 * VALUE SPACES} ({@code :133}, written at {@code :329}), {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE
 * 20} and {@code WS-LINE-COUNTER PIC 9(09) COMP-3} ({@code :130-131}). Pagination state lives in
 * {@code TransactionReportJob}. Do not add them here.
 *
 * <h2>Provenance of the expectations, and thread safety</h2>
 *
 * <p>The legacy COBOL <strong>cannot be executed in this environment</strong>: there is no z/OS
 * runtime, the available compiler has indexed file support disabled, no Language Environment {@code
 * CEE*} services exist and no CICS emulator is present. Every rule above is therefore
 * <em>statically derived</em> — read out of {@code app/cpy/CVTRA07Y.cpy} and {@code
 * app/cbl/CBTRN03C.cbl}, and cross-checked against the IBM Enterprise COBOL for z/OS Language
 * Reference sections named above — rather than captured from a live run. Each rule carries a
 * dedicated test so a misreading surfaces as a failing assertion rather than as a wrong byte in a
 * report.
 *
 * <p>An instance holds seven mutable record areas and is therefore <strong>not</strong>
 * thread-safe; give each report run its own, exactly as each COBOL run has its own {@code
 * WORKING-STORAGE}. Nothing {@code static} here is mutable: every constant is a primitive, a
 * {@code String}, an immutable {@code FieldSpan} or an immutable {@code RecordLayout}, the mask
 * position arithmetic is computed rather than held in an array, and every byte array handed out is
 * a fresh copy.
 *
 * @see CobolDecimal
 * @see FixedWidthRecord
 * @see FixedWidthCodec
 */
public final class TranReportLayouts {

    // =================================================================================================
    // Layout widths. Each is the arithmetic sum of the item widths declared below it, and each is
    // re-proved at class initialisation by RecordLayout's own geometry check plus verifyGeometry()
    // (gate G19). None of them is 133: normalising to the record width is TranReportWriter's job.
    // =================================================================================================

    /** {@code REPORT-NAME-HEADER} width: {@code 38 + 41 + 12 + 10 + 4 + 10}. */
    public static final int REPORT_NAME_HEADER_LENGTH = 115;

    /** {@code TRANSACTION-DETAIL-REPORT} width: eight named items plus eight {@code FILLER}s. */
    public static final int TRANSACTION_DETAIL_REPORT_LENGTH = 114;

    /** {@code TRANSACTION-HEADER-1} width: {@code 17 + 12 + 19 + 35 + 14 + 1 + 16}. */
    public static final int TRANSACTION_HEADER_1_LENGTH = 114;

    /** {@code TRANSACTION-HEADER-2} width: the elementary {@code PIC X(133)} itself. */
    public static final int TRANSACTION_HEADER_2_LENGTH = 133;

    /** {@code REPORT-PAGE-TOTALS} width: {@code 11 + 86 + 15}. */
    public static final int REPORT_PAGE_TOTALS_LENGTH = 112;

    /** {@code REPORT-ACCOUNT-TOTALS} width: {@code 13 + 84 + 15}. */
    public static final int REPORT_ACCOUNT_TOTALS_LENGTH = 112;

    /** {@code REPORT-GRAND-TOTALS} width: {@code 11 + 86 + 15}. */
    public static final int REPORT_GRAND_TOTALS_LENGTH = 112;

    // =================================================================================================
    // The amount column. The one geometric fact that ties all four amount-bearing layouts together.
    // =================================================================================================

    /** 1-based report column at which every amount mask begins. */
    public static final int AMOUNT_COLUMN_START = 98;

    /** 1-based report column at which every amount mask ends. */
    public static final int AMOUNT_COLUMN_END = 112;

    /** 0-based byte offset of every amount mask, that is {@code AMOUNT_COLUMN_START - 1}. */
    public static final int AMOUNT_OFFSET = 97;

    /** Width of both edit masks, in bytes. */
    public static final int AMOUNT_MASK_WIDTH = 15;

    /** Integer digit positions in both masks — the nine {@code Z}s left of the decimal point. */
    public static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Decimal digit positions in both masks — the two {@code Z}s right of the decimal point. */
    public static final int AMOUNT_FRACTION_DIGITS = 2;

    /** {@code TRAN-REPORT-AMT}'s mask: a fixed minus, then all-{@code Z} suppression. */
    public static final String DETAIL_AMOUNT_MASK = "-ZZZ,ZZZ,ZZZ.ZZ";

    /** The three total fields' mask: a fixed plus, then all-{@code Z} suppression. */
    public static final String TOTAL_AMOUNT_MASK = "+ZZZ,ZZZ,ZZZ.ZZ";

    // =================================================================================================
    // REPORT-NAME-HEADER item widths and VALUE literals, in copybook order. The literal lengths are
    // called out because three of them behave differently: two are shorter than their field and are
    // right-padded, and two fill their field exactly, which means their surrounding spaces are data.
    // =================================================================================================

    /** {@code REPT-SHORT-NAME PIC X(38)}, columns 1-38. */
    public static final int REPT_SHORT_NAME_LENGTH = 38;

    /** {@code REPT-SHORT-NAME VALUE 'DALYREPT'} — literal length 8, right-space-padded to 38. */
    public static final String REPT_SHORT_NAME_VALUE = "DALYREPT";

    /** {@code REPT-LONG-NAME PIC X(41)}, columns 39-79. */
    public static final int REPT_LONG_NAME_LENGTH = 41;

    /**
     * {@code REPT-LONG-NAME VALUE 'Daily Transaction Report'} — literal length 24, right-space-padded
     * to 41.
     */
    public static final String REPT_LONG_NAME_VALUE = "Daily Transaction Report";

    /** {@code REPT-DATE-HEADER PIC X(12)}, columns 80-91. */
    public static final int REPT_DATE_HEADER_LENGTH = 12;

    /**
     * {@code REPT-DATE-HEADER VALUE 'Date Range: '} — literal length 12, which
     * <strong>exactly fills</strong> the field. The trailing space is part of the literal, not
     * padding, and separates the caption from {@code REPT-START-DATE}.
     */
    public static final String REPT_DATE_HEADER_VALUE = "Date Range: ";

    /** {@code REPT-START-DATE PIC X(10) VALUE SPACES}, columns 92-101. */
    public static final int REPT_START_DATE_LENGTH = 10;

    /** {@code FILLER PIC X(04)} between the two dates, columns 102-105. */
    public static final int DATE_RANGE_SEPARATOR_LENGTH = 4;

    /**
     * {@code FILLER PIC X(04) VALUE ' to '} — literal length 4, which <strong>exactly fills</strong>
     * the field. Both the leading and the trailing space are part of the literal.
     */
    public static final String DATE_RANGE_SEPARATOR_VALUE = " to ";

    /** {@code REPT-END-DATE PIC X(10) VALUE SPACES}, columns 106-115. */
    public static final int REPT_END_DATE_LENGTH = 10;

    // =================================================================================================
    // TRANSACTION-DETAIL-REPORT item widths, in copybook order.
    // =================================================================================================

    /** {@code TRAN-REPORT-TRANS-ID PIC X(16)}, columns 1-16. Receives {@code TRAN-ID X(16)}. */
    public static final int TRAN_REPORT_TRANS_ID_LENGTH = 16;

    /**
     * {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}, columns 18-28. Receives {@code XREF-ACCT-ID PIC
     * 9(11)} of {@code app/cpy/CVACT03Y.cpy} — a numeric-to-alphanumeric move of equal width.
     */
    public static final int TRAN_REPORT_ACCOUNT_ID_LENGTH = 11;

    /**
     * {@code TRAN-REPORT-TYPE-CD PIC X(02)}, columns 30-31. Alphanumeric, not numeric: it receives
     * {@code TRAN-TYPE-CD OF TRAN-RECORD}, itself declared {@code PIC X(02)}.
     */
    public static final int TRAN_REPORT_TYPE_CD_LENGTH = 2;

    /**
     * {@code TRAN-REPORT-TYPE-DESC PIC X(15)}, columns 33-47. Receives {@code TRAN-TYPE-DESC PIC
     * X(50)} of {@code app/cpy/CVTRA03Y.cpy}, so the move keeps the leftmost 15 characters and
     * discards the rest.
     */
    public static final int TRAN_REPORT_TYPE_DESC_LENGTH = 15;

    /**
     * {@code TRAN-REPORT-CAT-CD PIC 9(04)}, columns 49-52. The only numeric item in the layout, and
     * therefore the only one that zero-fills on the left rather than space-padding on the right.
     */
    public static final int TRAN_REPORT_CAT_CD_LENGTH = 4;

    /**
     * {@code TRAN-REPORT-CAT-DESC PIC X(29)}, columns 54-82. Receives {@code TRAN-CAT-TYPE-DESC PIC
     * X(50)} of {@code app/cpy/CVTRA04Y.cpy}, so the move keeps the leftmost 29 characters.
     */
    public static final int TRAN_REPORT_CAT_DESC_LENGTH = 29;

    /** {@code TRAN-REPORT-SOURCE PIC X(10)}, columns 84-93. Receives {@code TRAN-SOURCE X(10)}. */
    public static final int TRAN_REPORT_SOURCE_LENGTH = 10;

    /**
     * The {@code FILLER PIC X(01) VALUE '-'} separators at columns 32 and 53. Declared as a literal
     * rather than a pad byte precisely so that {@link #initializeTransactionDetailReport()} leaves
     * them standing, which is what COBOL's {@code INITIALIZE} does to a {@code FILLER}.
     */
    public static final String DETAIL_SEPARATOR_VALUE = "-";

    // =================================================================================================
    // TRANSACTION-HEADER-1 item widths and VALUE literals, in copybook order. Every item is a FILLER,
    // so the whole line is constant and nothing in it is ever moved into at run time.
    // =================================================================================================

    /** {@code FILLER PIC X(17) VALUE 'Transaction ID'}, columns 1-17; literal length 14. */
    public static final int HEADER_1_TRANSACTION_ID_LENGTH = 17;

    /** The {@code 'Transaction ID'} caption, 14 characters, right-space-padded to 17. */
    public static final String HEADER_1_TRANSACTION_ID_VALUE = "Transaction ID";

    /** {@code FILLER PIC X(12) VALUE 'Account ID'}, columns 18-29; literal length 10. */
    public static final int HEADER_1_ACCOUNT_ID_LENGTH = 12;

    /** The {@code 'Account ID'} caption, 10 characters, right-space-padded to 12. */
    public static final String HEADER_1_ACCOUNT_ID_VALUE = "Account ID";

    /** {@code FILLER PIC X(19) VALUE 'Transaction Type'}, columns 30-48; literal length 16. */
    public static final int HEADER_1_TRANSACTION_TYPE_LENGTH = 19;

    /** The {@code 'Transaction Type'} caption, 16 characters, right-space-padded to 19. */
    public static final String HEADER_1_TRANSACTION_TYPE_VALUE = "Transaction Type";

    /** {@code FILLER PIC X(35) VALUE 'Tran Category'}, columns 49-83; literal length 13. */
    public static final int HEADER_1_TRAN_CATEGORY_LENGTH = 35;

    /** The {@code 'Tran Category'} caption, 13 characters, right-space-padded to 35. */
    public static final String HEADER_1_TRAN_CATEGORY_VALUE = "Tran Category";

    /** {@code FILLER PIC X(14) VALUE 'Tran Source'}, columns 84-97; literal length 11. */
    public static final int HEADER_1_TRAN_SOURCE_LENGTH = 14;

    /** The {@code 'Tran Source'} caption, 11 characters, right-space-padded to 14. */
    public static final String HEADER_1_TRAN_SOURCE_VALUE = "Tran Source";

    /**
     * {@code FILLER PIC X VALUE SPACES} at column 98 — an implicit {@code X(01)}, written in the
     * copybook without a length. It is what makes the captions above stop at column 97 and so keeps
     * the amount heading aligned with the amount column.
     */
    public static final int HEADER_1_GAP_LENGTH = 1;

    /** {@code FILLER PIC X(16) VALUE '        Amount'}, columns 99-114; literal length 14. */
    public static final int HEADER_1_AMOUNT_LENGTH = 16;

    /**
     * The amount caption, {@code '        Amount'}: <strong>exactly eight</strong> leading spaces
     * followed by {@code Amount}, 14 characters in all. Starting at column 99, that places {@code
     * Amount} on columns 107-112, right-aligning its final character on column 112 — the last digit
     * column of the amount mask. The space count is load-bearing.
     */
    public static final String HEADER_1_AMOUNT_VALUE = "        Amount";

    /** The number of leading spaces in {@link #HEADER_1_AMOUNT_VALUE}, asserted at initialisation. */
    public static final int HEADER_1_AMOUNT_LEADING_SPACES = 8;

    /** 1-based column at which the word {@code Amount} begins in {@code TRANSACTION-HEADER-1}. */
    public static final int HEADER_1_AMOUNT_WORD_COLUMN_START = 107;

    // =================================================================================================
    // TRANSACTION-HEADER-2 - the elementary rule line.
    // =================================================================================================

    /** The repeated character of {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}. */
    public static final char TRANSACTION_HEADER_2_RULE_CHARACTER = '-';

    /**
     * The full {@code TRANSACTION-HEADER-2} image: exactly {@value #TRANSACTION_HEADER_2_LENGTH}
     * hyphens. Already the record width, so {@code TranReportWriter} passes it through unpadded.
     */
    public static final String TRANSACTION_HEADER_2_IMAGE =
            String.valueOf(TRANSACTION_HEADER_2_RULE_CHARACTER).repeat(TRANSACTION_HEADER_2_LENGTH);

    // =================================================================================================
    // The three total lines. THE LEADER WIDTHS 86 / 84 / 86 ARE DELIBERATELY THREE SEPARATE CONSTANTS.
    // They compensate label widths 11 / 13 / 11 so that label + leader is 97 in every case and the
    // amount mask lands on columns 98-112 in all three. Unifying them breaks the report's alignment.
    // =================================================================================================

    /** The repeated character of every {@code FILLER ... VALUE ALL '.'} dot leader. */
    public static final char TOTAL_LEADER_CHARACTER = '.';

    /** {@code FILLER PIC X(11) VALUE 'Page Total'}, columns 1-11; literal length 10. */
    public static final int PAGE_TOTAL_LABEL_LENGTH = 11;

    /**
     * The {@code 'Page Total'} label, 10 characters, leaving <strong>one</strong> trailing space
     * inside its 11-byte field. It is the only one of the three labels that does not fill its field.
     */
    public static final String PAGE_TOTAL_LABEL_VALUE = "Page Total";

    /** {@code FILLER PIC X(86) VALUE ALL '.'}, columns 12-97. Pairs with an 11-byte label. */
    public static final int PAGE_TOTAL_LEADER_LENGTH = 86;

    /** {@code FILLER PIC X(13) VALUE 'Account Total'}, columns 1-13; literal length 13. */
    public static final int ACCOUNT_TOTAL_LABEL_LENGTH = 13;

    /**
     * The {@code 'Account Total'} label, 13 characters, which <strong>exactly fills</strong> its
     * field with no trailing space. This is why its dot leader is 84 rather than 86.
     */
    public static final String ACCOUNT_TOTAL_LABEL_VALUE = "Account Total";

    /**
     * {@code FILLER PIC X(84) VALUE ALL '.'}, columns 14-97. Two bytes shorter than the other two
     * leaders, compensating the two-byte-wider {@code 'Account Total'} label. Not a typo; not to be
     * normalised.
     */
    public static final int ACCOUNT_TOTAL_LEADER_LENGTH = 84;

    /** {@code FILLER PIC X(11) VALUE 'Grand Total'}, columns 1-11; literal length 11. */
    public static final int GRAND_TOTAL_LABEL_LENGTH = 11;

    /**
     * The {@code 'Grand Total'} label, 11 characters, which <strong>exactly fills</strong> its field
     * with no trailing space.
     */
    public static final String GRAND_TOTAL_LABEL_VALUE = "Grand Total";

    /** {@code FILLER PIC X(86) VALUE ALL '.'}, columns 12-97. Pairs with an 11-byte label. */
    public static final int GRAND_TOTAL_LEADER_LENGTH = 86;

    /**
     * {@code REPORT-PAGE-TOTALS}' dot leader: exactly {@value #PAGE_TOTAL_LEADER_LENGTH} dots.
     * Materialised separately from the other two so that a change to one cannot silently change
     * another.
     */
    public static final String PAGE_TOTAL_LEADER_IMAGE =
            String.valueOf(TOTAL_LEADER_CHARACTER).repeat(PAGE_TOTAL_LEADER_LENGTH);

    /** {@code REPORT-ACCOUNT-TOTALS}' dot leader: exactly {@value #ACCOUNT_TOTAL_LEADER_LENGTH} dots. */
    public static final String ACCOUNT_TOTAL_LEADER_IMAGE =
            String.valueOf(TOTAL_LEADER_CHARACTER).repeat(ACCOUNT_TOTAL_LEADER_LENGTH);

    /** {@code REPORT-GRAND-TOTALS}' dot leader: exactly {@value #GRAND_TOTAL_LEADER_LENGTH} dots. */
    public static final String GRAND_TOTAL_LEADER_IMAGE =
            String.valueOf(TOTAL_LEADER_CHARACTER).repeat(GRAND_TOTAL_LEADER_LENGTH);

    // =================================================================================================
    // Absolute 0-based byte offsets. Every one is written as "previous offset + previous width" rather
    // than as a literal, so the whole column map is arithmetically self-proving and a corrected width
    // propagates instead of silently disagreeing with an offset. Offsets of FILLER spans are private
    // because COBOL FILLER is not a referable name; the named items' offsets are part of the contract.
    // =================================================================================================

    /** Column 1. */
    public static final int REPT_SHORT_NAME_OFFSET = 0;

    /** Column 39. */
    public static final int REPT_LONG_NAME_OFFSET = REPT_SHORT_NAME_OFFSET + REPT_SHORT_NAME_LENGTH;

    /** Column 80. */
    public static final int REPT_DATE_HEADER_OFFSET = REPT_LONG_NAME_OFFSET + REPT_LONG_NAME_LENGTH;

    /** Column 92. */
    public static final int REPT_START_DATE_OFFSET =
            REPT_DATE_HEADER_OFFSET + REPT_DATE_HEADER_LENGTH;

    private static final int DATE_RANGE_SEPARATOR_OFFSET =
            REPT_START_DATE_OFFSET + REPT_START_DATE_LENGTH;

    /** Column 106. */
    public static final int REPT_END_DATE_OFFSET =
            DATE_RANGE_SEPARATOR_OFFSET + DATE_RANGE_SEPARATOR_LENGTH;

    /** Column 1. */
    public static final int TRAN_REPORT_TRANS_ID_OFFSET = 0;

    private static final int DETAIL_FILLER_AFTER_TRANS_ID_OFFSET =
            TRAN_REPORT_TRANS_ID_OFFSET + TRAN_REPORT_TRANS_ID_LENGTH;

    /** Column 18. */
    public static final int TRAN_REPORT_ACCOUNT_ID_OFFSET = DETAIL_FILLER_AFTER_TRANS_ID_OFFSET + 1;

    private static final int DETAIL_FILLER_AFTER_ACCOUNT_ID_OFFSET =
            TRAN_REPORT_ACCOUNT_ID_OFFSET + TRAN_REPORT_ACCOUNT_ID_LENGTH;

    /** Column 30. */
    public static final int TRAN_REPORT_TYPE_CD_OFFSET = DETAIL_FILLER_AFTER_ACCOUNT_ID_OFFSET + 1;

    /**
     * Column 32 — the {@code '-'} separator between the transaction type code and its description.
     * Private because it is a {@code FILLER}, but its rendered position is asserted directly.
     */
    private static final int DETAIL_TYPE_SEPARATOR_OFFSET =
            TRAN_REPORT_TYPE_CD_OFFSET + TRAN_REPORT_TYPE_CD_LENGTH;

    /** Column 33. */
    public static final int TRAN_REPORT_TYPE_DESC_OFFSET = DETAIL_TYPE_SEPARATOR_OFFSET + 1;

    private static final int DETAIL_FILLER_AFTER_TYPE_DESC_OFFSET =
            TRAN_REPORT_TYPE_DESC_OFFSET + TRAN_REPORT_TYPE_DESC_LENGTH;

    /** Column 49. */
    public static final int TRAN_REPORT_CAT_CD_OFFSET = DETAIL_FILLER_AFTER_TYPE_DESC_OFFSET + 1;

    /** Column 53 — the {@code '-'} separator between the category code and its description. */
    private static final int DETAIL_CAT_SEPARATOR_OFFSET =
            TRAN_REPORT_CAT_CD_OFFSET + TRAN_REPORT_CAT_CD_LENGTH;

    /** Column 54. */
    public static final int TRAN_REPORT_CAT_DESC_OFFSET = DETAIL_CAT_SEPARATOR_OFFSET + 1;

    private static final int DETAIL_FILLER_AFTER_CAT_DESC_OFFSET =
            TRAN_REPORT_CAT_DESC_OFFSET + TRAN_REPORT_CAT_DESC_LENGTH;

    /** Column 84. */
    public static final int TRAN_REPORT_SOURCE_OFFSET = DETAIL_FILLER_AFTER_CAT_DESC_OFFSET + 1;

    private static final int DETAIL_FILLER_BEFORE_AMOUNT_OFFSET =
            TRAN_REPORT_SOURCE_OFFSET + TRAN_REPORT_SOURCE_LENGTH;

    private static final int DETAIL_FILLER_BEFORE_AMOUNT_LENGTH = 4;

    /**
     * Column 98. Computed from the fourteen preceding widths, and therefore an independent proof that
     * they sum to 97; the class-initialisation geometry check asserts it equals {@link
     * #AMOUNT_OFFSET}.
     */
    public static final int TRAN_REPORT_AMT_OFFSET =
            DETAIL_FILLER_BEFORE_AMOUNT_OFFSET + DETAIL_FILLER_BEFORE_AMOUNT_LENGTH;

    private static final int DETAIL_TRAILING_FILLER_OFFSET =
            TRAN_REPORT_AMT_OFFSET + AMOUNT_MASK_WIDTH;

    private static final int DETAIL_TRAILING_FILLER_LENGTH = 2;

    private static final int HEADER_1_TRANSACTION_ID_OFFSET = 0;

    private static final int HEADER_1_ACCOUNT_ID_OFFSET =
            HEADER_1_TRANSACTION_ID_OFFSET + HEADER_1_TRANSACTION_ID_LENGTH;

    private static final int HEADER_1_TRANSACTION_TYPE_OFFSET =
            HEADER_1_ACCOUNT_ID_OFFSET + HEADER_1_ACCOUNT_ID_LENGTH;

    private static final int HEADER_1_TRAN_CATEGORY_OFFSET =
            HEADER_1_TRANSACTION_TYPE_OFFSET + HEADER_1_TRANSACTION_TYPE_LENGTH;

    private static final int HEADER_1_TRAN_SOURCE_OFFSET =
            HEADER_1_TRAN_CATEGORY_OFFSET + HEADER_1_TRAN_CATEGORY_LENGTH;

    private static final int HEADER_1_GAP_OFFSET =
            HEADER_1_TRAN_SOURCE_OFFSET + HEADER_1_TRAN_SOURCE_LENGTH;

    private static final int HEADER_1_AMOUNT_OFFSET = HEADER_1_GAP_OFFSET + HEADER_1_GAP_LENGTH;

    /** Column 1 — the elementary {@code TRANSACTION-HEADER-2} spans the whole line. */
    public static final int TRANSACTION_HEADER_2_OFFSET = 0;

    private static final int PAGE_TOTAL_LABEL_OFFSET = 0;

    private static final int PAGE_TOTAL_LEADER_OFFSET =
            PAGE_TOTAL_LABEL_OFFSET + PAGE_TOTAL_LABEL_LENGTH;

    /** Column 98, reached as {@code 11 + 86}. */
    public static final int REPT_PAGE_TOTAL_OFFSET =
            PAGE_TOTAL_LEADER_OFFSET + PAGE_TOTAL_LEADER_LENGTH;

    private static final int ACCOUNT_TOTAL_LABEL_OFFSET = 0;

    private static final int ACCOUNT_TOTAL_LEADER_OFFSET =
            ACCOUNT_TOTAL_LABEL_OFFSET + ACCOUNT_TOTAL_LABEL_LENGTH;

    /** Column 98, reached as {@code 13 + 84}. */
    public static final int REPT_ACCOUNT_TOTAL_OFFSET =
            ACCOUNT_TOTAL_LEADER_OFFSET + ACCOUNT_TOTAL_LEADER_LENGTH;

    private static final int GRAND_TOTAL_LABEL_OFFSET = 0;

    private static final int GRAND_TOTAL_LEADER_OFFSET =
            GRAND_TOTAL_LABEL_OFFSET + GRAND_TOTAL_LABEL_LENGTH;

    /** Column 98, reached as {@code 11 + 86}. */
    public static final int REPT_GRAND_TOTAL_OFFSET =
            GRAND_TOTAL_LEADER_OFFSET + GRAND_TOTAL_LEADER_LENGTH;

    // =================================================================================================
    // Field descriptors, one per copybook item, in copybook order.
    //
    // On the numeric-edited items: FixedWidthRecord.PictureKind has no NUMERIC_EDITED constant, and it
    // does not need one. A numeric-edited item is character data once edited - it holds a sign, digits,
    // commas and a decimal point, is left-justified and space-padded for alignment, and never carries a
    // zoned overpunch - so ALPHANUMERIC is the faithful category for TRAN-REPORT-AMT and the three
    // REPT-*-TOTAL fields. The editing itself is this class's own work, in editDetailAmount and
    // editTotalAmount; the record layer only stores the resulting 15 characters.
    //
    // The named items of REPORT-NAME-HEADER that declare VALUE SPACES carry no initialValue: SPACES is
    // exactly the alphanumeric pad byte FixedWidthRecord.initialise already writes for a span with no
    // literal, so declaring a ten-space string literal here would add a transcription risk without
    // changing a single byte.
    // =================================================================================================

    /** {@code REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'}, columns 1-38. */
    public static final FieldSpan REPT_SHORT_NAME =
            FieldSpan.alphanumeric("REPT-SHORT-NAME", REPT_SHORT_NAME_OFFSET, REPT_SHORT_NAME_LENGTH)
                    .withInitialValue(REPT_SHORT_NAME_VALUE);

    /** {@code REPT-LONG-NAME PIC X(41) VALUE 'Daily Transaction Report'}, columns 39-79. */
    public static final FieldSpan REPT_LONG_NAME =
            FieldSpan.alphanumeric("REPT-LONG-NAME", REPT_LONG_NAME_OFFSET, REPT_LONG_NAME_LENGTH)
                    .withInitialValue(REPT_LONG_NAME_VALUE);

    /** {@code REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '}, columns 80-91. */
    public static final FieldSpan REPT_DATE_HEADER =
            FieldSpan.alphanumeric("REPT-DATE-HEADER", REPT_DATE_HEADER_OFFSET,
                            REPT_DATE_HEADER_LENGTH)
                    .withInitialValue(REPT_DATE_HEADER_VALUE);

    /** {@code REPT-START-DATE PIC X(10) VALUE SPACES}, columns 92-101. */
    public static final FieldSpan REPT_START_DATE =
            FieldSpan.alphanumeric("REPT-START-DATE", REPT_START_DATE_OFFSET,
                    REPT_START_DATE_LENGTH);

    /** {@code FILLER PIC X(04) VALUE ' to '}, columns 102-105. */
    private static final FieldSpan DATE_RANGE_SEPARATOR = FieldSpan.filler(
            DATE_RANGE_SEPARATOR_OFFSET, DATE_RANGE_SEPARATOR_LENGTH, DATE_RANGE_SEPARATOR_VALUE);

    /** {@code REPT-END-DATE PIC X(10) VALUE SPACES}, columns 106-115. */
    public static final FieldSpan REPT_END_DATE =
            FieldSpan.alphanumeric("REPT-END-DATE", REPT_END_DATE_OFFSET, REPT_END_DATE_LENGTH);

    /** {@code TRAN-REPORT-TRANS-ID PIC X(16)}, columns 1-16. */
    public static final FieldSpan TRAN_REPORT_TRANS_ID =
            FieldSpan.alphanumeric("TRAN-REPORT-TRANS-ID", TRAN_REPORT_TRANS_ID_OFFSET,
                    TRAN_REPORT_TRANS_ID_LENGTH);

    private static final FieldSpan DETAIL_FILLER_AFTER_TRANS_ID =
            FieldSpan.filler(DETAIL_FILLER_AFTER_TRANS_ID_OFFSET, 1);

    /** {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}, columns 18-28. */
    public static final FieldSpan TRAN_REPORT_ACCOUNT_ID =
            FieldSpan.alphanumeric("TRAN-REPORT-ACCOUNT-ID", TRAN_REPORT_ACCOUNT_ID_OFFSET,
                    TRAN_REPORT_ACCOUNT_ID_LENGTH);

    private static final FieldSpan DETAIL_FILLER_AFTER_ACCOUNT_ID =
            FieldSpan.filler(DETAIL_FILLER_AFTER_ACCOUNT_ID_OFFSET, 1);

    /** {@code TRAN-REPORT-TYPE-CD PIC X(02)}, columns 30-31. */
    public static final FieldSpan TRAN_REPORT_TYPE_CD =
            FieldSpan.alphanumeric("TRAN-REPORT-TYPE-CD", TRAN_REPORT_TYPE_CD_OFFSET,
                    TRAN_REPORT_TYPE_CD_LENGTH);

    /** {@code FILLER PIC X(01) VALUE '-'}, column 32. */
    private static final FieldSpan DETAIL_TYPE_SEPARATOR =
            FieldSpan.filler(DETAIL_TYPE_SEPARATOR_OFFSET, 1, DETAIL_SEPARATOR_VALUE);

    /** {@code TRAN-REPORT-TYPE-DESC PIC X(15)}, columns 33-47. */
    public static final FieldSpan TRAN_REPORT_TYPE_DESC =
            FieldSpan.alphanumeric("TRAN-REPORT-TYPE-DESC", TRAN_REPORT_TYPE_DESC_OFFSET,
                    TRAN_REPORT_TYPE_DESC_LENGTH);

    private static final FieldSpan DETAIL_FILLER_AFTER_TYPE_DESC =
            FieldSpan.filler(DETAIL_FILLER_AFTER_TYPE_DESC_OFFSET, 1);

    /** {@code TRAN-REPORT-CAT-CD PIC 9(04)}, columns 49-52 — the layout's only numeric item. */
    public static final FieldSpan TRAN_REPORT_CAT_CD =
            FieldSpan.unsignedNumeric("TRAN-REPORT-CAT-CD", TRAN_REPORT_CAT_CD_OFFSET,
                    TRAN_REPORT_CAT_CD_LENGTH);

    /** {@code FILLER PIC X(01) VALUE '-'}, column 53. */
    private static final FieldSpan DETAIL_CAT_SEPARATOR =
            FieldSpan.filler(DETAIL_CAT_SEPARATOR_OFFSET, 1, DETAIL_SEPARATOR_VALUE);

    /** {@code TRAN-REPORT-CAT-DESC PIC X(29)}, columns 54-82. */
    public static final FieldSpan TRAN_REPORT_CAT_DESC =
            FieldSpan.alphanumeric("TRAN-REPORT-CAT-DESC", TRAN_REPORT_CAT_DESC_OFFSET,
                    TRAN_REPORT_CAT_DESC_LENGTH);

    private static final FieldSpan DETAIL_FILLER_AFTER_CAT_DESC =
            FieldSpan.filler(DETAIL_FILLER_AFTER_CAT_DESC_OFFSET, 1);

    /** {@code TRAN-REPORT-SOURCE PIC X(10)}, columns 84-93. */
    public static final FieldSpan TRAN_REPORT_SOURCE =
            FieldSpan.alphanumeric("TRAN-REPORT-SOURCE", TRAN_REPORT_SOURCE_OFFSET,
                    TRAN_REPORT_SOURCE_LENGTH);

    private static final FieldSpan DETAIL_FILLER_BEFORE_AMOUNT = FieldSpan.filler(
            DETAIL_FILLER_BEFORE_AMOUNT_OFFSET, DETAIL_FILLER_BEFORE_AMOUNT_LENGTH);

    /** {@code TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ}, columns 98-112 — numeric-edited, 15 bytes. */
    public static final FieldSpan TRAN_REPORT_AMT =
            FieldSpan.alphanumeric("TRAN-REPORT-AMT", TRAN_REPORT_AMT_OFFSET, AMOUNT_MASK_WIDTH);

    private static final FieldSpan DETAIL_TRAILING_FILLER =
            FieldSpan.filler(DETAIL_TRAILING_FILLER_OFFSET, DETAIL_TRAILING_FILLER_LENGTH);

    /** {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'} — an elementary {@code 01} item. */
    public static final FieldSpan TRANSACTION_HEADER_2 =
            FieldSpan.alphanumeric("TRANSACTION-HEADER-2", TRANSACTION_HEADER_2_OFFSET,
                            TRANSACTION_HEADER_2_LENGTH)
                    .withInitialValue(TRANSACTION_HEADER_2_IMAGE);

    /** {@code REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}, columns 98-112. */
    public static final FieldSpan REPT_PAGE_TOTAL =
            FieldSpan.alphanumeric("REPT-PAGE-TOTAL", REPT_PAGE_TOTAL_OFFSET, AMOUNT_MASK_WIDTH);

    /** {@code REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}, columns 98-112. */
    public static final FieldSpan REPT_ACCOUNT_TOTAL = FieldSpan.alphanumeric("REPT-ACCOUNT-TOTAL",
            REPT_ACCOUNT_TOTAL_OFFSET, AMOUNT_MASK_WIDTH);

    /** {@code REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}, columns 98-112. */
    public static final FieldSpan REPT_GRAND_TOTAL =
            FieldSpan.alphanumeric("REPT-GRAND-TOTAL", REPT_GRAND_TOTAL_OFFSET, AMOUNT_MASK_WIDTH);

    // =================================================================================================
    // The seven record layouts. RecordLayout's own compact constructor rejects duplicate names, gaps,
    // overlaps and any span list that does not sum to the declared record length, so each declaration
    // below is itself a width proof that fails at class-initialisation time rather than at report time
    // (gate G19). Every FILLER is declared: omit one and the layout no longer sums, which is the point.
    // =================================================================================================

    /** {@code 01 REPORT-NAME-HEADER}, 115 bytes. */
    public static final RecordLayout REPORT_NAME_HEADER_LAYOUT =
            RecordLayout.of(REPORT_NAME_HEADER_LENGTH, REPT_SHORT_NAME, REPT_LONG_NAME,
                    REPT_DATE_HEADER, REPT_START_DATE, DATE_RANGE_SEPARATOR, REPT_END_DATE);

    /** {@code 01 TRANSACTION-DETAIL-REPORT}, 114 bytes: eight named items and eight {@code FILLER}s. */
    public static final RecordLayout TRANSACTION_DETAIL_REPORT_LAYOUT = RecordLayout.of(
            TRANSACTION_DETAIL_REPORT_LENGTH, TRAN_REPORT_TRANS_ID, DETAIL_FILLER_AFTER_TRANS_ID,
            TRAN_REPORT_ACCOUNT_ID, DETAIL_FILLER_AFTER_ACCOUNT_ID, TRAN_REPORT_TYPE_CD,
            DETAIL_TYPE_SEPARATOR, TRAN_REPORT_TYPE_DESC, DETAIL_FILLER_AFTER_TYPE_DESC,
            TRAN_REPORT_CAT_CD, DETAIL_CAT_SEPARATOR, TRAN_REPORT_CAT_DESC,
            DETAIL_FILLER_AFTER_CAT_DESC, TRAN_REPORT_SOURCE, DETAIL_FILLER_BEFORE_AMOUNT,
            TRAN_REPORT_AMT, DETAIL_TRAILING_FILLER);

    /** {@code 01 TRANSACTION-HEADER-1}, 114 bytes, every item a {@code FILLER}. */
    public static final RecordLayout TRANSACTION_HEADER_1_LAYOUT = RecordLayout.of(
            TRANSACTION_HEADER_1_LENGTH,
            FieldSpan.filler(HEADER_1_TRANSACTION_ID_OFFSET, HEADER_1_TRANSACTION_ID_LENGTH,
                    HEADER_1_TRANSACTION_ID_VALUE),
            FieldSpan.filler(HEADER_1_ACCOUNT_ID_OFFSET, HEADER_1_ACCOUNT_ID_LENGTH,
                    HEADER_1_ACCOUNT_ID_VALUE),
            FieldSpan.filler(HEADER_1_TRANSACTION_TYPE_OFFSET, HEADER_1_TRANSACTION_TYPE_LENGTH,
                    HEADER_1_TRANSACTION_TYPE_VALUE),
            FieldSpan.filler(HEADER_1_TRAN_CATEGORY_OFFSET, HEADER_1_TRAN_CATEGORY_LENGTH,
                    HEADER_1_TRAN_CATEGORY_VALUE),
            FieldSpan.filler(HEADER_1_TRAN_SOURCE_OFFSET, HEADER_1_TRAN_SOURCE_LENGTH,
                    HEADER_1_TRAN_SOURCE_VALUE),
            FieldSpan.filler(HEADER_1_GAP_OFFSET, HEADER_1_GAP_LENGTH),
            FieldSpan.filler(HEADER_1_AMOUNT_OFFSET, HEADER_1_AMOUNT_LENGTH,
                    HEADER_1_AMOUNT_VALUE));

    /** {@code 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}, 133 bytes. */
    public static final RecordLayout TRANSACTION_HEADER_2_LAYOUT =
            RecordLayout.of(TRANSACTION_HEADER_2_LENGTH, TRANSACTION_HEADER_2);

    /** {@code 01 REPORT-PAGE-TOTALS}, 112 bytes: {@code 11 + 86 + 15}. */
    public static final RecordLayout REPORT_PAGE_TOTALS_LAYOUT = RecordLayout.of(
            REPORT_PAGE_TOTALS_LENGTH,
            FieldSpan.filler(PAGE_TOTAL_LABEL_OFFSET, PAGE_TOTAL_LABEL_LENGTH,
                    PAGE_TOTAL_LABEL_VALUE),
            FieldSpan.filler(PAGE_TOTAL_LEADER_OFFSET, PAGE_TOTAL_LEADER_LENGTH,
                    PAGE_TOTAL_LEADER_IMAGE),
            REPT_PAGE_TOTAL);

    /** {@code 01 REPORT-ACCOUNT-TOTALS}, 112 bytes: {@code 13 + 84 + 15}. */
    public static final RecordLayout REPORT_ACCOUNT_TOTALS_LAYOUT = RecordLayout.of(
            REPORT_ACCOUNT_TOTALS_LENGTH,
            FieldSpan.filler(ACCOUNT_TOTAL_LABEL_OFFSET, ACCOUNT_TOTAL_LABEL_LENGTH,
                    ACCOUNT_TOTAL_LABEL_VALUE),
            FieldSpan.filler(ACCOUNT_TOTAL_LEADER_OFFSET, ACCOUNT_TOTAL_LEADER_LENGTH,
                    ACCOUNT_TOTAL_LEADER_IMAGE),
            REPT_ACCOUNT_TOTAL);

    /** {@code 01 REPORT-GRAND-TOTALS}, 112 bytes: {@code 11 + 86 + 15}. */
    public static final RecordLayout REPORT_GRAND_TOTALS_LAYOUT = RecordLayout.of(
            REPORT_GRAND_TOTALS_LENGTH,
            FieldSpan.filler(GRAND_TOTAL_LABEL_OFFSET, GRAND_TOTAL_LABEL_LENGTH,
                    GRAND_TOTAL_LABEL_VALUE),
            FieldSpan.filler(GRAND_TOTAL_LEADER_OFFSET, GRAND_TOTAL_LEADER_LENGTH,
                    GRAND_TOTAL_LEADER_IMAGE),
            REPT_GRAND_TOTAL);

    // =================================================================================================
    // Mask geometry. The positions of the sign, the two commas, the decimal point and the eleven Z
    // digit slots inside "?ZZZ,ZZZ,ZZZ.ZZ" are derived arithmetically rather than held in a lookup
    // table, because a static array would be mutable static state however it was declared (practice
    // B9) and because the arithmetic is short enough to check by eye against the mask itself:
    //
    //   index  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14
    //   mask   ?  Z  Z  Z  ,  Z  Z  Z  ,  Z  Z  Z  .  Z  Z
    //
    // integer digit i (0..8) -> 1 + (i / 3) * 4 + (i % 3)   =>  1 2 3 5 6 7 9 10 11
    // group separator g (0,1) -> 1 + (g + 1) * 4 - 1        =>  4 8
    // =================================================================================================

    /** Position of the fixed insertion sign: the leftmost character of the mask, never floating. */
    private static final int SIGN_INDEX = 0;

    /** Position of the first integer digit slot, immediately right of the sign. */
    private static final int FIRST_INTEGER_DIGIT_INDEX = 1;

    /** Integer digit slots between one comma and the next. */
    private static final int DIGITS_PER_GROUP = 3;

    /** Mask positions consumed by one digit group plus its trailing comma. */
    private static final int GROUP_STRIDE = DIGITS_PER_GROUP + 1;

    /** Number of {@code ,} insertion characters, that is {@code 9 / 3 - 1}. */
    private static final int GROUP_SEPARATOR_COUNT = AMOUNT_INTEGER_DIGITS / DIGITS_PER_GROUP - 1;

    /** Position of the {@code .} special insertion character. */
    private static final int DECIMAL_POINT_INDEX =
            FIRST_INTEGER_DIGIT_INDEX + AMOUNT_INTEGER_DIGITS + GROUP_SEPARATOR_COUNT;

    /** Position of the first of the two fractional digit slots. */
    private static final int FIRST_FRACTION_DIGIT_INDEX = DECIMAL_POINT_INDEX + 1;

    /** Digit characters the sending value contributes: nine integer plus two fractional. */
    private static final int SENDING_DIGIT_COUNT = AMOUNT_INTEGER_DIGITS + AMOUNT_FRACTION_DIGITS;

    private static final char SPACE = ' ';

    private static final char ZERO_DIGIT = '0';

    private static final char GROUP_SEPARATOR = ',';

    private static final char DECIMAL_POINT = '.';

    private static final char MINUS_SIGN = '-';

    private static final char PLUS_SIGN = '+';

    /**
     * What {@value #DETAIL_AMOUNT_MASK} renders in position 1 for a non-negative value: a
     * <em>space</em>. A leading {@code -} is a fixed insertion character that shows the minus only,
     * leaving the position blank when the value is positive.
     */
    private static final char DETAIL_POSITIVE_SIGN = SPACE;

    /**
     * What {@value #TOTAL_AMOUNT_MASK} renders in position 1 for a non-negative value: a {@code +}. A
     * leading {@code +} shows the plus for a positive value and a minus for a negative one.
     */
    private static final char TOTAL_POSITIVE_SIGN = PLUS_SIGN;

    /**
     * The edited image of zero under either mask: {@value #AMOUNT_MASK_WIDTH} spaces. Every digit
     * position of both masks is a {@code Z} suppression symbol, so a value of zero blanks the whole
     * item — sign, commas and decimal point included. It is neither {@code 0.00} nor {@code .00}.
     */
    private static final String AMOUNT_ZERO_IMAGE = String.valueOf(SPACE).repeat(AMOUNT_MASK_WIDTH);

    /**
     * Every transcription invariant of this copybook, re-proved before the class can be used. Failing
     * here rather than at report time is the whole point: a mis-transcribed width or a "tidied" dot
     * leader can produce a plausible-looking report line that is silently mis-aligned, and that is the
     * most expensive class of defect this migration can ship.
     */
    static {
        verifyGeometry();
    }

    // =================================================================================================
    // Instance state: one record area per 01-level item, exactly as one COPY of CVTRA07Y gives
    // CBTRN03C one WORKING-STORAGE area apiece. Mutable, and therefore not thread-safe - give each
    // report run its own instance. Nothing static here is mutable (gate G53).
    // =================================================================================================

    private final FixedWidthCodec codec;

    private final FixedWidthRecord reportNameHeader;

    private final FixedWidthRecord transactionDetailReport;

    private final FixedWidthRecord transactionHeader1;

    private final FixedWidthRecord transactionHeader2;

    private final FixedWidthRecord reportPageTotals;

    private final FixedWidthRecord reportAccountTotals;

    private final FixedWidthRecord reportGrandTotals;

    /**
     * Allocates all seven record areas over the given charset, applying each item's declared {@code
     * VALUE} exactly as COBOL applies a {@code VALUE} clause on entry to {@code WORKING-STORAGE}. The
     * captions, the {@code ' to '} separator, the two {@code '-'} separators, the three dot leaders
     * and the 133-hyphen rule line are therefore all in place immediately.
     *
     * <p>The charset is an explicit parameter and is never defaulted (practice B8): the report's bytes
     * are {@code US-ASCII} when produced against the {@code app/data/ASCII} fixtures and {@code
     * IBM037} when produced against an EBCDIC dataset, and a platform default would make the output
     * depend on the machine that produced it.
     *
     * @param charset the charset the report's bytes are encoded in; must encode a space and a digit to
     *                a single byte each, which {@code US-ASCII} and {@code IBM037} both do
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for digits and spaces
     */
    public TranReportLayouts(Charset charset) {
        this(new FixedWidthCodec(Objects.requireNonNull(charset,
                "A charset is required to render the transaction report; name US-ASCII or IBM037 "
                        + "explicitly rather than relying on a platform default")));
    }

    /**
     * Allocates all seven record areas using a codec the caller already holds. Preferred inside a
     * batch step, where {@code TranReportWriter} and every repository share one codec and therefore
     * one explicitly chosen charset.
     *
     * @param codec the fixed-width codec whose charset the report is encoded in
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public TranReportLayouts(FixedWidthCodec codec) {
        this.codec = Objects.requireNonNull(codec,
                "A FixedWidthCodec is required; it carries the explicitly chosen charset");
        this.reportNameHeader = codec.newRecord(REPORT_NAME_HEADER_LAYOUT);
        this.transactionDetailReport = codec.newRecord(TRANSACTION_DETAIL_REPORT_LAYOUT);
        this.transactionHeader1 = codec.newRecord(TRANSACTION_HEADER_1_LAYOUT);
        this.transactionHeader2 = codec.newRecord(TRANSACTION_HEADER_2_LAYOUT);
        this.reportPageTotals = codec.newRecord(REPORT_PAGE_TOTALS_LAYOUT);
        this.reportAccountTotals = codec.newRecord(REPORT_ACCOUNT_TOTALS_LAYOUT);
        this.reportGrandTotals = codec.newRecord(REPORT_GRAND_TOTALS_LAYOUT);
    }

    /**
     * The charset every record area of this instance is encoded in.
     *
     * @return the charset supplied at construction, never {@code null}
     */
    public Charset charset() {
        return codec.charset();
    }

    // =================================================================================================
    // REPORT-NAME-HEADER. Only the two dates are ever moved into; the three captions and the ' to '
    // separator are VALUE literals that the report never changes.
    // =================================================================================================

    /**
     * {@code MOVE WS-START-DATE TO REPT-START-DATE} — {@code CBTRN03C:277}. Both items are {@code PIC
     * X(10)}, and {@code WS-START-DATE} is the first ten bytes of the {@code DATEPARM} record.
     *
     * <p>The move follows the alphanumeric rule: right-space-padded when shorter than ten characters,
     * truncated on the right when longer. That direction is deliberate and is applied by {@link
     * FixedWidthCodec#movePicX(String, int)} rather than by an inline substring.
     *
     * @param startDate the report range's start date, at most 10 characters
     * @throws NullPointerException if {@code startDate} is {@code null}
     */
    public void moveReptStartDate(String startDate) {
        codec.writePicX(reportNameHeader, REPT_START_DATE, startDate);
    }

    /**
     * {@code MOVE WS-END-DATE TO REPT-END-DATE} — {@code CBTRN03C:278}. {@code WS-END-DATE} is the
     * {@code DATEPARM} record's last ten bytes, after a one-byte {@code FILLER}.
     *
     * @param endDate the report range's end date, at most 10 characters
     * @throws NullPointerException if {@code endDate} is {@code null}
     */
    public void moveReptEndDate(String endDate) {
        codec.writePicX(reportNameHeader, REPT_END_DATE, endDate);
    }

    /**
     * {@code REPT-SHORT-NAME}, columns 1-38: {@code 'DALYREPT'} followed by 30 spaces.
     *
     * @return all 38 characters, untrimmed
     */
    public String reptShortName() {
        return codec.readPicX(reportNameHeader, REPT_SHORT_NAME);
    }

    /**
     * {@code REPT-LONG-NAME}, columns 39-79: {@code 'Daily Transaction Report'} followed by 17 spaces.
     *
     * @return all 41 characters, untrimmed
     */
    public String reptLongName() {
        return codec.readPicX(reportNameHeader, REPT_LONG_NAME);
    }

    /**
     * {@code REPT-DATE-HEADER}, columns 80-91: {@code 'Date Range: '}, all 12 bytes of it.
     *
     * @return all 12 characters, including the literal's trailing space
     */
    public String reptDateHeader() {
        return codec.readPicX(reportNameHeader, REPT_DATE_HEADER);
    }

    /**
     * {@code REPT-START-DATE}, columns 92-101.
     *
     * @return all 10 characters, untrimmed; spaces before the first {@link #moveReptStartDate(String)}
     */
    public String reptStartDate() {
        return codec.readPicX(reportNameHeader, REPT_START_DATE);
    }

    /**
     * {@code REPT-END-DATE}, columns 106-115.
     *
     * @return all 10 characters, untrimmed; spaces before the first {@link #moveReptEndDate(String)}
     */
    public String reptEndDate() {
        return codec.readPicX(reportNameHeader, REPT_END_DATE);
    }

    // =================================================================================================
    // TRANSACTION-DETAIL-REPORT. The eight moves of CBTRN03C:363-370, each preserving the truncation
    // direction its receiver's PICTURE dictates, and the INITIALIZE of :362 that precedes them.
    // =================================================================================================

    /**
     * COBOL {@code INITIALIZE TRANSACTION-DETAIL-REPORT} — {@code CBTRN03C:362}, executed immediately
     * before the eight detail moves.
     *
     * <p>Semantics taken from the IBM Enterprise COBOL for z/OS Language Reference, "Initializing a
     * structure (INITIALIZE)": with no {@code REPLACING} phrase, {@code SPACE} is the implied sending
     * item for alphanumeric and alphanumeric-edited items and {@code ZERO} for numeric <em>and
     * numeric-edited</em> items, and an item with an explicit or implicit {@code FILLER} clause is not
     * a receiving operand unless the {@code FILLER} phrase is written. So:
     *
     * <ul>
     *   <li>the six named {@code PIC X} items become spaces;</li>
     *   <li>{@code TRAN-REPORT-CAT-CD PIC 9(04)} becomes {@code 0000} — zeros, not spaces;</li>
     *   <li>{@code TRAN-REPORT-AMT} receives {@code ZERO}, and its all-{@code Z} mask renders zero as
     *       {@value #AMOUNT_MASK_WIDTH} spaces;</li>
     *   <li>all eight {@code FILLER} spans are left exactly as they are, so the {@code '-'}
     *       separators at columns 32 and 53 survive and the six space fillers stay spaces.</li>
     * </ul>
     *
     * <p>This is deliberately <em>not</em> {@link FixedWidthRecord#initialise(RecordLayout)}: that
     * method implements the {@code VALUE}-clause convention, which writes every {@code FILLER}'s
     * literal, whereas the {@code INITIALIZE} verb skips {@code FILLER} altogether. The two agree on
     * this layout only because its fillers are already holding their declared values; encoding the
     * verb's rule explicitly keeps them from being confused (practice B5).
     */
    public void initializeTransactionDetailReport() {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TRANS_ID, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_ACCOUNT_ID, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TYPE_CD, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TYPE_DESC, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_CAT_DESC, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_SOURCE, "");
        codec.writePic9(transactionDetailReport, TRAN_REPORT_CAT_CD, 0L);
        transactionDetailReport.writeSpan(TRAN_REPORT_AMT, AMOUNT_ZERO_IMAGE);
    }

    /**
     * {@code MOVE TRAN-ID TO TRAN-REPORT-TRANS-ID} — {@code CBTRN03C:363}. {@code TRAN-ID} of {@code
     * app/cpy/CVTRA05Y.cpy} is {@code PIC X(16)} and the receiver is {@code PIC X(16)}, so the widths
     * match and nothing is truncated.
     *
     * @param transactionId the transaction identifier, at most 16 characters
     * @throws NullPointerException if {@code transactionId} is {@code null}
     */
    public void moveTranReportTransId(String transactionId) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TRANS_ID, transactionId);
    }

    /**
     * {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID} — {@code CBTRN03C:364}. A
     * <strong>numeric-to-alphanumeric</strong> move: {@code XREF-ACCT-ID} of {@code
     * app/cpy/CVACT03Y.cpy} is {@code PIC 9(11)} and the receiver is {@code PIC X(11)}. COBOL treats
     * the sending numeric {@code DISPLAY} item as its own digit characters and moves them
     * left-justified, so the eleven zero-filled digits land unchanged.
     *
     * <p>This overload takes the sender's already-rendered digit image, which is the natural form when
     * the cross-reference record was read from a fixed-width dataset.
     *
     * @param accountIdDigits the sender's {@code PIC 9(11)} digit image, at most 11 characters
     * @throws NullPointerException if {@code accountIdDigits} is {@code null}
     */
    public void moveTranReportAccountId(String accountIdDigits) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_ACCOUNT_ID, accountIdDigits);
    }

    /**
     * {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID} from a numeric value, in two explicit steps
     * so that both halves of the move are visible: first the sender's own {@code PIC 9(11)} image is
     * formed by zero-filling on the left, then those characters are moved into the {@code PIC X(11)}
     * receiver left-justified.
     *
     * @param accountId the account identifier; must not be negative, because {@code PIC 9(11)} is
     *                  unsigned
     * @throws IllegalArgumentException if {@code accountId} is negative
     */
    public void moveTranReportAccountId(long accountId) {
        moveTranReportAccountId(codec.movePic9(accountId, TRAN_REPORT_ACCOUNT_ID_LENGTH));
    }

    /**
     * {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD} — {@code CBTRN03C:365}. Both
     * items are {@code PIC X(02)}: the type code is alphanumeric in {@code app/cpy/CVTRA05Y.cpy}, not
     * numeric, so it is not zero-filled.
     *
     * @param transactionTypeCode the two-character transaction type code
     * @throws NullPointerException if {@code transactionTypeCode} is {@code null}
     */
    public void moveTranReportTypeCd(String transactionTypeCode) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TYPE_CD, transactionTypeCode);
    }

    /**
     * {@code MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC} — {@code CBTRN03C:366}. {@code
     * TRAN-TYPE-DESC} of {@code app/cpy/CVTRA03Y.cpy} is {@code PIC X(50)} and the receiver is {@code
     * PIC X(15)}, so COBOL keeps the <strong>leftmost 15</strong> characters and discards the other
     * 35. The truncation is on the right because the receiver is alphanumeric.
     *
     * @param transactionTypeDescription the type description, of any length
     * @throws NullPointerException if {@code transactionTypeDescription} is {@code null}
     */
    public void moveTranReportTypeDesc(String transactionTypeDescription) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TYPE_DESC, transactionTypeDescription);
    }

    /**
     * {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO TRAN-REPORT-CAT-CD} — {@code CBTRN03C:367}. Both items
     * are {@code PIC 9(04)}, so the move zero-fills on the left and, being numeric, would truncate on
     * the <strong>left</strong> rather than the right.
     *
     * @param transactionCategoryCode the category code; must not be negative
     * @throws IllegalArgumentException if {@code transactionCategoryCode} is negative
     */
    public void moveTranReportCatCd(long transactionCategoryCode) {
        codec.writePic9(transactionDetailReport, TRAN_REPORT_CAT_CD, transactionCategoryCode);
    }

    /**
     * {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO TRAN-REPORT-CAT-CD} from the sender's digit image,
     * which is the natural form when the transaction record was read from a fixed-width dataset.
     *
     * @param transactionCategoryCodeDigits the sender's digits, non-empty and all digits
     * @throws NullPointerException     if {@code transactionCategoryCodeDigits} is {@code null}
     * @throws IllegalArgumentException if it is empty or holds a non-digit
     */
    public void moveTranReportCatCd(String transactionCategoryCodeDigits) {
        codec.writePic9(transactionDetailReport, TRAN_REPORT_CAT_CD, transactionCategoryCodeDigits);
    }

    /**
     * {@code MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC} — {@code CBTRN03C:368}. {@code
     * TRAN-CAT-TYPE-DESC} of {@code app/cpy/CVTRA04Y.cpy} is {@code PIC X(50)} and the receiver is
     * {@code PIC X(29)}, so the <strong>leftmost 29</strong> characters are kept.
     *
     * @param transactionCategoryDescription the category description, of any length
     * @throws NullPointerException if {@code transactionCategoryDescription} is {@code null}
     */
    public void moveTranReportCatDesc(String transactionCategoryDescription) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_CAT_DESC,
                transactionCategoryDescription);
    }

    /**
     * {@code MOVE TRAN-SOURCE TO TRAN-REPORT-SOURCE} — {@code CBTRN03C:369}. Both items are {@code PIC
     * X(10)}.
     *
     * @param transactionSource the transaction source, at most 10 characters
     * @throws NullPointerException if {@code transactionSource} is {@code null}
     */
    public void moveTranReportSource(String transactionSource) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_SOURCE, transactionSource);
    }

    /**
     * {@code MOVE TRAN-AMT TO TRAN-REPORT-AMT} — {@code CBTRN03C:370}. {@code TRAN-AMT} is {@code PIC
     * S9(09)V99} and the receiver is the numeric-edited {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}, so the value is
     * edited into 15 characters by {@link #editDetailAmount(BigDecimal)}: a minus in column 98 when
     * negative and a space when positive, leading zeros and the commas ahead of the first significant
     * digit suppressed to spaces, and the whole field blank when the amount is zero.
     *
     * @param transactionAmount the transaction amount; scaled and truncated to 2 decimal places
     * @throws NullPointerException if {@code transactionAmount} is {@code null}
     */
    public void moveTranReportAmt(BigDecimal transactionAmount) {
        transactionDetailReport.writeSpan(TRAN_REPORT_AMT, editDetailAmount(transactionAmount));
    }

    /**
     * {@code TRAN-REPORT-TRANS-ID}, columns 1-16.
     *
     * @return all 16 characters, untrimmed
     */
    public String tranReportTransId() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_TRANS_ID);
    }

    /**
     * {@code TRAN-REPORT-ACCOUNT-ID}, columns 18-28.
     *
     * @return all 11 characters, untrimmed
     */
    public String tranReportAccountId() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_ACCOUNT_ID);
    }

    /**
     * {@code TRAN-REPORT-TYPE-CD}, columns 30-31.
     *
     * @return both characters, untrimmed
     */
    public String tranReportTypeCd() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_TYPE_CD);
    }

    /**
     * {@code TRAN-REPORT-TYPE-DESC}, columns 33-47.
     *
     * @return all 15 characters, untrimmed
     */
    public String tranReportTypeDesc() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_TYPE_DESC);
    }

    /**
     * {@code TRAN-REPORT-CAT-CD}, columns 49-52, as its four stored digit characters. The digit image
     * rather than an {@code int}, because {@code 0000} and {@code 0} are the same number but not the
     * same four bytes, and the report compares bytes.
     *
     * @return exactly four digit characters
     */
    public String tranReportCatCd() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_CAT_CD);
    }

    /**
     * {@code TRAN-REPORT-CAT-CD} decoded as a number, for callers that need the value rather than the
     * image.
     *
     * @return the category code the four stored digits denote
     * @throws IllegalArgumentException if the span holds a non-digit, which would mean a corrupted
     *                                  record rather than a legitimate value
     */
    public int tranReportCatCdValue() {
        return codec.readPic9AsInt(transactionDetailReport, TRAN_REPORT_CAT_CD);
    }

    /**
     * {@code TRAN-REPORT-CAT-DESC}, columns 54-82.
     *
     * @return all 29 characters, untrimmed
     */
    public String tranReportCatDesc() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_CAT_DESC);
    }

    /**
     * {@code TRAN-REPORT-SOURCE}, columns 84-93.
     *
     * @return all 10 characters, untrimmed
     */
    public String tranReportSource() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_SOURCE);
    }

    /**
     * {@code TRAN-REPORT-AMT}, columns 98-112, as its edited image.
     *
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters; all spaces when the amount is zero
     */
    public String tranReportAmt() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_AMT);
    }

    // =================================================================================================
    // The three total lines. Each has exactly one item that is ever moved into - its amount - because
    // the label and the dot leader are VALUE literals.
    // =================================================================================================

    /**
     * {@code MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL} — {@code CBTRN03C:294}. {@code WS-PAGE-TOTAL} is
     * {@code PIC S9(09)V99 VALUE 0} ({@code CBTRN03C:134}); the receiver is the numeric-edited {@code
     * PIC +ZZZ,ZZZ,ZZZ.ZZ}, so a positive total shows {@code +} in column 98 and a negative one shows
     * {@code -}. A page whose transactions net to zero prints a blank amount, which is what the
     * all-{@code Z} rule requires.
     *
     * @param pageTotal the accumulated page total; scaled and truncated to 2 decimal places
     * @throws NullPointerException if {@code pageTotal} is {@code null}
     */
    public void moveReptPageTotal(BigDecimal pageTotal) {
        reportPageTotals.writeSpan(REPT_PAGE_TOTAL, editTotalAmount(pageTotal));
    }

    /**
     * {@code MOVE WS-ACCOUNT-TOTAL TO REPT-ACCOUNT-TOTAL} — {@code CBTRN03C:307}. {@code
     * WS-ACCOUNT-TOTAL} is {@code PIC S9(09)V99 VALUE 0} ({@code CBTRN03C:135}).
     *
     * @param accountTotal the accumulated account total; scaled and truncated to 2 decimal places
     * @throws NullPointerException if {@code accountTotal} is {@code null}
     */
    public void moveReptAccountTotal(BigDecimal accountTotal) {
        reportAccountTotals.writeSpan(REPT_ACCOUNT_TOTAL, editTotalAmount(accountTotal));
    }

    /**
     * {@code MOVE WS-GRAND-TOTAL TO REPT-GRAND-TOTAL} — {@code CBTRN03C:319}. {@code WS-GRAND-TOTAL} is
     * {@code PIC S9(09)V99 VALUE 0} ({@code CBTRN03C:136}).
     *
     * @param grandTotal the accumulated grand total; scaled and truncated to 2 decimal places
     * @throws NullPointerException if {@code grandTotal} is {@code null}
     */
    public void moveReptGrandTotal(BigDecimal grandTotal) {
        reportGrandTotals.writeSpan(REPT_GRAND_TOTAL, editTotalAmount(grandTotal));
    }

    /**
     * {@code REPT-PAGE-TOTAL}, columns 98-112, as its edited image.
     *
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters; all spaces before the first move, and
     *         all spaces again whenever the total is zero
     */
    public String reptPageTotal() {
        return codec.readPicX(reportPageTotals, REPT_PAGE_TOTAL);
    }

    /**
     * {@code REPT-ACCOUNT-TOTAL}, columns 98-112, as its edited image.
     *
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     */
    public String reptAccountTotal() {
        return codec.readPicX(reportAccountTotals, REPT_ACCOUNT_TOTAL);
    }

    /**
     * {@code REPT-GRAND-TOTAL}, columns 98-112, as its edited image.
     *
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     */
    public String reptGrandTotal() {
        return codec.readPicX(reportGrandTotals, REPT_GRAND_TOTAL);
    }

    // =================================================================================================
    // Rendering. Every method here returns the layout at its NATURAL width and never pads to 133.
    // transaction.TranReportWriter owns that normalisation, reproducing MOVE <layout> TO
    // FD-REPTFILE-REC PIC X(133) (CBTRN03C:85). Padding here too would duplicate the writer's job and
    // let a width defect hide behind a correct-looking 133-byte record.
    // =================================================================================================

    /**
     * {@code REPORT-NAME-HEADER} as its {@value #REPORT_NAME_HEADER_LENGTH}-character print image —
     * the layout written at {@code CBTRN03C:325}.
     *
     * @return exactly {@value #REPORT_NAME_HEADER_LENGTH} characters, not padded to 133
     */
    public String renderReportNameHeader() {
        return image(reportNameHeader);
    }

    /**
     * {@code TRANSACTION-DETAIL-REPORT} as its {@value #TRANSACTION_DETAIL_REPORT_LENGTH}-character
     * print image — the layout written at {@code CBTRN03C:371}.
     *
     * @return exactly {@value #TRANSACTION_DETAIL_REPORT_LENGTH} characters, not padded to 133
     */
    public String renderTransactionDetailReport() {
        return image(transactionDetailReport);
    }

    /**
     * {@code TRANSACTION-HEADER-1} as its {@value #TRANSACTION_HEADER_1_LENGTH}-character print image —
     * the layout written at {@code CBTRN03C:333}. Constant for the life of the instance, since every
     * one of its seven items is a {@code FILLER} carrying a {@code VALUE}.
     *
     * @return exactly {@value #TRANSACTION_HEADER_1_LENGTH} characters, not padded to 133
     */
    public String renderTransactionHeader1() {
        return image(transactionHeader1);
    }

    /**
     * {@code TRANSACTION-HEADER-2} as its {@value #TRANSACTION_HEADER_2_LENGTH}-character print image —
     * the rule line written at {@code CBTRN03C:300}, {@code :312} and {@code :337}. Already the full
     * record width, so {@code TranReportWriter} passes it through unchanged.
     *
     * @return exactly {@value #TRANSACTION_HEADER_2_LENGTH} hyphens
     */
    public String renderTransactionHeader2() {
        return image(transactionHeader2);
    }

    /**
     * {@code REPORT-PAGE-TOTALS} as its {@value #REPORT_PAGE_TOTALS_LENGTH}-character print image — the
     * layout written at {@code CBTRN03C:295}.
     *
     * @return exactly {@value #REPORT_PAGE_TOTALS_LENGTH} characters, not padded to 133
     */
    public String renderReportPageTotals() {
        return image(reportPageTotals);
    }

    /**
     * {@code REPORT-ACCOUNT-TOTALS} as its {@value #REPORT_ACCOUNT_TOTALS_LENGTH}-character print image
     * — the layout written at {@code CBTRN03C:308}.
     *
     * @return exactly {@value #REPORT_ACCOUNT_TOTALS_LENGTH} characters, not padded to 133
     */
    public String renderReportAccountTotals() {
        return image(reportAccountTotals);
    }

    /**
     * {@code REPORT-GRAND-TOTALS} as its {@value #REPORT_GRAND_TOTALS_LENGTH}-character print image —
     * the layout written at {@code CBTRN03C:320}.
     *
     * @return exactly {@value #REPORT_GRAND_TOTALS_LENGTH} characters, not padded to 133
     */
    public String renderReportGrandTotals() {
        return image(reportGrandTotals);
    }

    /**
     * {@code REPORT-NAME-HEADER} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #REPORT_NAME_HEADER_LENGTH} bytes
     */
    public byte[] renderReportNameHeaderBytes() {
        return reportNameHeader.toByteArray();
    }

    /**
     * {@code TRANSACTION-DETAIL-REPORT} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #TRANSACTION_DETAIL_REPORT_LENGTH} bytes
     */
    public byte[] renderTransactionDetailReportBytes() {
        return transactionDetailReport.toByteArray();
    }

    /**
     * {@code TRANSACTION-HEADER-1} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #TRANSACTION_HEADER_1_LENGTH} bytes
     */
    public byte[] renderTransactionHeader1Bytes() {
        return transactionHeader1.toByteArray();
    }

    /**
     * {@code TRANSACTION-HEADER-2} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #TRANSACTION_HEADER_2_LENGTH} bytes
     */
    public byte[] renderTransactionHeader2Bytes() {
        return transactionHeader2.toByteArray();
    }

    /**
     * {@code REPORT-PAGE-TOTALS} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #REPORT_PAGE_TOTALS_LENGTH} bytes
     */
    public byte[] renderReportPageTotalsBytes() {
        return reportPageTotals.toByteArray();
    }

    /**
     * {@code REPORT-ACCOUNT-TOTALS} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #REPORT_ACCOUNT_TOTALS_LENGTH} bytes
     */
    public byte[] renderReportAccountTotalsBytes() {
        return reportAccountTotals.toByteArray();
    }

    /**
     * {@code REPORT-GRAND-TOTALS} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #REPORT_GRAND_TOTALS_LENGTH} bytes
     */
    public byte[] renderReportGrandTotalsBytes() {
        return reportGrandTotals.toByteArray();
    }

    /**
     * The whole of a record area as characters. A record's own {@code readString} is used rather than
     * decoding {@link FixedWidthRecord#toByteArray()} here, so the charset the area was allocated with
     * is the charset the image is decoded with, always.
     *
     * @param record the area to read
     * @return every one of the area's characters
     */
    private static String image(FixedWidthRecord record) {
        return record.readString(0, record.recordLength());
    }

    // =================================================================================================
    // Numeric-edited rendering. The one piece of genuine COBOL PICTURE semantics in this file.
    // =================================================================================================

    /**
     * Edits a value through {@value #DETAIL_AMOUNT_MASK}, the mask of {@code TRAN-REPORT-AMT}.
     *
     * <p>The leading {@code -} is a fixed insertion character: a minus appears in position 1 when the
     * value is negative and a <strong>space</strong> when it is not. A zero value renders as
     * {@value #AMOUNT_MASK_WIDTH} spaces.
     *
     * @param value the sending {@code PIC S9(09)V99} value
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String editDetailAmount(BigDecimal value) {
        return editNumeric(value, DETAIL_POSITIVE_SIGN);
    }

    /**
     * Edits a value through {@value #TOTAL_AMOUNT_MASK}, the mask of {@code REPT-PAGE-TOTAL},
     * {@code REPT-ACCOUNT-TOTAL} and {@code REPT-GRAND-TOTAL}.
     *
     * <p>The leading {@code +} is a fixed insertion character: a plus appears in position 1 when the
     * value is not negative and a minus when it is. A zero value renders as
     * {@value #AMOUNT_MASK_WIDTH} spaces, so a total that nets to zero prints a blank amount column
     * rather than {@code +0.00}.
     *
     * @param value the sending {@code PIC S9(09)V99} value
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String editTotalAmount(BigDecimal value) {
        return editNumeric(value, TOTAL_POSITIVE_SIGN);
    }

    /**
     * The shared implementation of both masks. They differ in one character only — what position 1
     * holds when the value is not negative — so the sign for that case is the parameter.
     *
     * <p>Rendered by explicit character placement into a {@value #AMOUNT_MASK_WIDTH}-character buffer.
     * No {@code String.format}, no {@code DecimalFormat} and no {@code NumberFormat}: all three are
     * locale-sensitive in their grouping separator, decimal separator and negative form, which would
     * make the report differ between machines, and none of them implements {@code Z} suppression or
     * the all-{@code Z} zero rule.
     *
     * <p>Rules, from the IBM Enterprise COBOL for z/OS Language Reference:
     *
     * <ol>
     *   <li>All eleven digit positions are {@code Z}, so a value of zero blanks the entire item.
     *       Tested with {@code signum()} because {@code BigDecimal("0.00").equals(BigDecimal.ZERO)}
     *       is {@code false}.</li>
     *   <li>Otherwise, suppression replaces leading zeros with spaces and terminates at the first
     *       significant digit, or at the decimal point when the integer part is entirely zero. Hence
     *       {@code suppressUntil} is the mask position of the first significant integer digit, or the
     *       decimal point's position when there is none.</li>
     *   <li>A comma lying to the left of that boundary is part of the suppression string and becomes
     *       a space; a comma at or beyond it prints.</li>
     *   <li>The decimal point and the two fractional digits always print once the value is non-zero,
     *       because suppression cannot pass the decimal point.</li>
     * </ol>
     *
     * @param value       the sending value; scaled to 2 decimals and truncated with
     *                    {@link java.math.RoundingMode#DOWN} through {@link CobolDecimal}, because
     *                    {@code ROUNDED} appears nowhere in the 28 COBOL programs
     * @param positiveSign the character position 1 holds when {@code value} is not negative
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String editNumeric(BigDecimal value, char positiveSign) {
        Objects.requireNonNull(value, "An amount is required to edit a numeric-edited field; COBOL "
                + "has no null and the sending field is PIC S9(09)V99");
        BigDecimal stored =
                CobolDecimal.storeAtPicture(value, AMOUNT_INTEGER_DIGITS, AMOUNT_FRACTION_DIGITS);
        if (stored.signum() == 0) {
            return AMOUNT_ZERO_IMAGE;
        }

        String digits = leftPadWithZeros(stored.abs().unscaledValue().toString(),
                SENDING_DIGIT_COUNT);
        int suppressUntil = suppressionBoundary(digits);

        char[] edited = new char[AMOUNT_MASK_WIDTH];
        edited[SIGN_INDEX] = stored.signum() < 0 ? MINUS_SIGN : positiveSign;
        for (int digit = 0; digit < AMOUNT_INTEGER_DIGITS; digit++) {
            int position = integerDigitMaskIndex(digit);
            edited[position] = position < suppressUntil ? SPACE : digits.charAt(digit);
        }
        for (int group = 0; group < GROUP_SEPARATOR_COUNT; group++) {
            int position = groupSeparatorMaskIndex(group);
            edited[position] = position < suppressUntil ? SPACE : GROUP_SEPARATOR;
        }
        edited[DECIMAL_POINT_INDEX] = DECIMAL_POINT;
        for (int fraction = 0; fraction < AMOUNT_FRACTION_DIGITS; fraction++) {
            edited[FIRST_FRACTION_DIGIT_INDEX + fraction] =
                    digits.charAt(AMOUNT_INTEGER_DIGITS + fraction);
        }
        return new String(edited);
    }

    /**
     * The mask position at which zero suppression stops: that of the first significant integer digit,
     * or the decimal point's position when every integer digit is zero. Every position strictly to the
     * left of it — digit slot or comma alike — is replaced by a space.
     *
     * @param digits the sending value's {@value #SENDING_DIGIT_COUNT} digit characters, unsigned
     * @return a mask index in {@code [1, 12]}
     */
    private static int suppressionBoundary(String digits) {
        for (int digit = 0; digit < AMOUNT_INTEGER_DIGITS; digit++) {
            if (digits.charAt(digit) != ZERO_DIGIT) {
                return integerDigitMaskIndex(digit);
            }
        }
        return DECIMAL_POINT_INDEX;
    }

    /**
     * The mask position of an integer digit slot, {@code 1 + (i / 3) * 4 + (i % 3)}: 1, 2, 3, 5, 6, 7,
     * 9, 10, 11 for {@code i} of 0 to 8. Computed rather than looked up in a table, because a static
     * array would be mutable static state.
     *
     * @param digitIndex the integer digit's position in the sending value, most significant first,
     *                   from 0 to {@value #AMOUNT_INTEGER_DIGITS} exclusive
     * @return the digit's index within the {@value #AMOUNT_MASK_WIDTH}-character mask
     */
    private static int integerDigitMaskIndex(int digitIndex) {
        return FIRST_INTEGER_DIGIT_INDEX + digitIndex / DIGITS_PER_GROUP * GROUP_STRIDE
                + digitIndex % DIGITS_PER_GROUP;
    }

    /**
     * The mask position of a {@code ,} insertion character: 4 for the first group boundary and 8 for
     * the second, being one place past each group's last digit slot.
     *
     * @param group the group boundary, from 0 to {@value #GROUP_SEPARATOR_COUNT} exclusive
     * @return the comma's index within the {@value #AMOUNT_MASK_WIDTH}-character mask
     */
    private static int groupSeparatorMaskIndex(int group) {
        return integerDigitMaskIndex((group + 1) * DIGITS_PER_GROUP - 1) + 1;
    }

    /**
     * Left-pads a digit string with zeros to a fixed width, which is how a numeric sending item
     * presents fewer significant digits than its {@code PICTURE} declares.
     *
     * @param digits the significant digits
     * @param width  the declared digit count
     * @return {@code digits} widened on the left to {@code width}, or unchanged when already that wide
     */
    private static String leftPadWithZeros(String digits, int width) {
        int missing = width - digits.length();
        return missing <= 0 ? digits : String.valueOf(ZERO_DIGIT).repeat(missing) + digits;
    }

    // =================================================================================================
    // The transcription self-check. Runs once, at class initialisation, so a mis-transcribed width, a
    // shortened literal or a "tidied" dot leader fails the build instead of quietly mis-aligning a
    // report. Package-private rather than private so that the check itself is under test: a self-check
    // nobody exercises is no check at all.
    // =================================================================================================

    /**
     * Re-proves every geometric and literal invariant of {@code app/cpy/CVTRA07Y.cpy} against the
     * constants declared above.
     *
     * @throws IllegalStateException if any invariant does not hold
     */
    static void verifyGeometry() {
        verifyLayoutWidths();
        verifyAmountColumn();
        verifyMasks();
        verifyLiterals();
        verifyMaskIndexArithmetic();
    }

    /**
     * Asserts each layout's declared width and that {@link RecordLayout} agrees, which it can only do
     * if every item and every {@code FILLER} was declared and the widths sum exactly (gate G19).
     *
     * @throws IllegalStateException if a layout's width is not as transcribed
     */
    private static void verifyLayoutWidths() {
        requireGeometry(REPORT_NAME_HEADER_LENGTH, 115, "REPORT-NAME-HEADER's transcribed width");
        requireGeometry(TRANSACTION_DETAIL_REPORT_LENGTH, 114,
                "TRANSACTION-DETAIL-REPORT's transcribed width");
        requireGeometry(TRANSACTION_HEADER_1_LENGTH, 114,
                "TRANSACTION-HEADER-1's transcribed width");
        requireGeometry(TRANSACTION_HEADER_2_LENGTH, 133,
                "TRANSACTION-HEADER-2's transcribed width");
        requireGeometry(REPORT_PAGE_TOTALS_LENGTH, 112, "REPORT-PAGE-TOTALS' transcribed width");
        requireGeometry(REPORT_ACCOUNT_TOTALS_LENGTH, 112,
                "REPORT-ACCOUNT-TOTALS' transcribed width");
        requireGeometry(REPORT_GRAND_TOTALS_LENGTH, 112, "REPORT-GRAND-TOTALS' transcribed width");

        requireGeometry(REPORT_NAME_HEADER_LAYOUT.recordLength(), REPORT_NAME_HEADER_LENGTH,
                "REPORT-NAME-HEADER's item widths must sum to its declared width");
        requireGeometry(TRANSACTION_DETAIL_REPORT_LAYOUT.recordLength(),
                TRANSACTION_DETAIL_REPORT_LENGTH,
                "TRANSACTION-DETAIL-REPORT's item widths must sum to its declared width");
        requireGeometry(TRANSACTION_HEADER_1_LAYOUT.recordLength(), TRANSACTION_HEADER_1_LENGTH,
                "TRANSACTION-HEADER-1's item widths must sum to its declared width");
        requireGeometry(TRANSACTION_HEADER_2_LAYOUT.recordLength(), TRANSACTION_HEADER_2_LENGTH,
                "TRANSACTION-HEADER-2's single item must span its declared width");
        requireGeometry(REPORT_PAGE_TOTALS_LAYOUT.recordLength(), REPORT_PAGE_TOTALS_LENGTH,
                "REPORT-PAGE-TOTALS' item widths must sum to its declared width");
        requireGeometry(REPORT_ACCOUNT_TOTALS_LAYOUT.recordLength(), REPORT_ACCOUNT_TOTALS_LENGTH,
                "REPORT-ACCOUNT-TOTALS' item widths must sum to its declared width");
        requireGeometry(REPORT_GRAND_TOTALS_LAYOUT.recordLength(), REPORT_GRAND_TOTALS_LENGTH,
                "REPORT-GRAND-TOTALS' item widths must sum to its declared width");

        requireGeometry(REPORT_NAME_HEADER_LAYOUT.storageSpans().size(), 6,
                "REPORT-NAME-HEADER declares 5 named items and 1 FILLER");
        requireGeometry(TRANSACTION_DETAIL_REPORT_LAYOUT.storageSpans().size(), 16,
                "TRANSACTION-DETAIL-REPORT declares 8 named items and 8 FILLERs - six carrying "
                        + "SPACES and two carrying '-'");
        requireGeometry(TRANSACTION_HEADER_1_LAYOUT.storageSpans().size(), 7,
                "TRANSACTION-HEADER-1 declares 7 items, every one a FILLER");
        requireGeometry(TRANSACTION_HEADER_2_LAYOUT.storageSpans().size(), 1,
                "TRANSACTION-HEADER-2 is an elementary item with no sub-items");
        requireGeometry(REPORT_PAGE_TOTALS_LAYOUT.storageSpans().size(), 3,
                "REPORT-PAGE-TOTALS declares a label FILLER, a leader FILLER and one named amount");
        requireGeometry(REPORT_ACCOUNT_TOTALS_LAYOUT.storageSpans().size(), 3,
                "REPORT-ACCOUNT-TOTALS declares a label FILLER, a leader FILLER and one amount");
        requireGeometry(REPORT_GRAND_TOTALS_LAYOUT.storageSpans().size(), 3,
                "REPORT-GRAND-TOTALS declares a label FILLER, a leader FILLER and one amount");
    }

    /**
     * Asserts the column-97 invariant in all four amount-bearing layouts: the amount begins at 0-based
     * offset {@value #AMOUNT_OFFSET} and is {@value #AMOUNT_MASK_WIDTH} wide, so it occupies 1-based
     * columns 98-112. Also asserts that the three dot leaders remain 86, 84 and 86 and that each
     * label-plus-leader pair still sums to 97, which is what makes the invariant hold — and therefore
     * what fails if the leaders are ever normalised to a single width.
     *
     * @throws IllegalStateException if any amount is not at column 98, or a leader has been unified
     */
    private static void verifyAmountColumn() {
        requireGeometry(AMOUNT_OFFSET, AMOUNT_COLUMN_START - 1,
                "the amount mask's 0-based offset, one less than its 1-based column 98");
        requireGeometry(AMOUNT_COLUMN_END, AMOUNT_COLUMN_START + AMOUNT_MASK_WIDTH - 1,
                "the amount mask's last 1-based column, that is 98 + 15 - 1");
        requireAmountColumn(TRAN_REPORT_AMT);
        requireAmountColumn(REPT_PAGE_TOTAL);
        requireAmountColumn(REPT_ACCOUNT_TOTAL);
        requireAmountColumn(REPT_GRAND_TOTAL);

        requireGeometry(PAGE_TOTAL_LEADER_LENGTH, 86,
                "REPORT-PAGE-TOTALS' dot leader, which pairs with an 11-byte label");
        requireGeometry(ACCOUNT_TOTAL_LEADER_LENGTH, 84,
                "REPORT-ACCOUNT-TOTALS' dot leader - two shorter than the other two, compensating "
                        + "its two-byte-wider label. Never normalise it to 86");
        requireGeometry(GRAND_TOTAL_LEADER_LENGTH, 86,
                "REPORT-GRAND-TOTALS' dot leader, which pairs with an 11-byte label");
        requireGeometry(PAGE_TOTAL_LABEL_LENGTH + PAGE_TOTAL_LEADER_LENGTH, AMOUNT_OFFSET,
                "REPORT-PAGE-TOTALS' label plus leader, which must reach column 98");
        requireGeometry(ACCOUNT_TOTAL_LABEL_LENGTH + ACCOUNT_TOTAL_LEADER_LENGTH, AMOUNT_OFFSET,
                "REPORT-ACCOUNT-TOTALS' label plus leader, which must reach column 98");
        requireGeometry(GRAND_TOTAL_LABEL_LENGTH + GRAND_TOTAL_LEADER_LENGTH, AMOUNT_OFFSET,
                "REPORT-GRAND-TOTALS' label plus leader, which must reach column 98");
        requireGeometry(isRepetitionOf(PAGE_TOTAL_LEADER_IMAGE, TOTAL_LEADER_CHARACTER,
                        PAGE_TOTAL_LEADER_LENGTH),
                "REPORT-PAGE-TOTALS' leader is 86 '.' characters and nothing else");
        requireGeometry(isRepetitionOf(ACCOUNT_TOTAL_LEADER_IMAGE, TOTAL_LEADER_CHARACTER,
                        ACCOUNT_TOTAL_LEADER_LENGTH),
                "REPORT-ACCOUNT-TOTALS' leader is 84 '.' characters and nothing else");
        requireGeometry(isRepetitionOf(GRAND_TOTAL_LEADER_IMAGE, TOTAL_LEADER_CHARACTER,
                        GRAND_TOTAL_LEADER_LENGTH),
                "REPORT-GRAND-TOTALS' leader is 86 '.' characters and nothing else");
    }

    /**
     * Asserts one amount span sits exactly on 1-based columns 98-112.
     *
     * @param span the numeric-edited span to check
     * @throws IllegalStateException if it does not begin at offset 97 or is not 15 bytes wide
     */
    private static void requireAmountColumn(FieldSpan span) {
        requireGeometry(span.offset(), AMOUNT_OFFSET,
                span.name() + "'s 0-based offset, which must be column 98");
        requireGeometry(span.length(), AMOUNT_MASK_WIDTH,
                span.name() + "'s width, which must be the mask's 15 bytes");
    }

    /**
     * Asserts both masks are as the copybook writes them: 15 characters, the sign fixed in position 1,
     * nine integer and two fractional {@code Z} slots, commas at positions 4 and 8 and the decimal
     * point at position 12. Also asserts that the edited image of zero is 15 spaces.
     *
     * @throws IllegalStateException if a mask or the zero image is not as transcribed
     */
    private static void verifyMasks() {
        requireGeometry(DETAIL_AMOUNT_MASK.length(), AMOUNT_MASK_WIDTH,
                "the length of '-ZZZ,ZZZ,ZZZ.ZZ'");
        requireGeometry(TOTAL_AMOUNT_MASK.length(), AMOUNT_MASK_WIDTH,
                "the length of '+ZZZ,ZZZ,ZZZ.ZZ'");
        requireGeometry(DETAIL_AMOUNT_MASK.charAt(SIGN_INDEX), MINUS_SIGN,
                "the detail mask's fixed insertion sign, in position 1");
        requireGeometry(TOTAL_AMOUNT_MASK.charAt(SIGN_INDEX), PLUS_SIGN,
                "the total mask's fixed insertion sign, in position 1");
        requireGeometry(DETAIL_POSITIVE_SIGN, SPACE,
                "what a '-' mask renders in position 1 for a positive value: a space");
        requireGeometry(TOTAL_POSITIVE_SIGN, PLUS_SIGN,
                "what a '+' mask renders in position 1 for a positive value: a plus");
        requireGeometry(DETAIL_AMOUNT_MASK.substring(SIGN_INDEX + 1),
                TOTAL_AMOUNT_MASK.substring(SIGN_INDEX + 1),
                "the two masks past their sign character, which are identical");
        requireGeometry(SENDING_DIGIT_COUNT, 11,
                "the digit positions a PIC S9(09)V99 sender contributes");
        requireGeometry(countOf(DETAIL_AMOUNT_MASK, 'Z'), SENDING_DIGIT_COUNT,
                "the count of Z suppression symbols - all 11 of them, which is why a zero value "
                        + "blanks the whole item");
        requireGeometry(GROUP_SEPARATOR_COUNT, 2, "the number of ',' insertion characters");
        requireGeometry(countOf(DETAIL_AMOUNT_MASK, GROUP_SEPARATOR), GROUP_SEPARATOR_COUNT,
                "the commas actually present in the mask");
        requireGeometry(DECIMAL_POINT_INDEX, 12, "the '.' insertion character's mask index");
        requireGeometry(DETAIL_AMOUNT_MASK.charAt(DECIMAL_POINT_INDEX), DECIMAL_POINT,
                "the character at the decimal point's mask index");
        requireGeometry(FIRST_FRACTION_DIGIT_INDEX, 13, "the first fractional slot's mask index");
        requireGeometry(isRepetitionOf(AMOUNT_ZERO_IMAGE, SPACE, AMOUNT_MASK_WIDTH),
                "the edited image of zero is 15 spaces, not 0.00 and not .00");
    }

    /**
     * Asserts every {@code VALUE} literal byte for byte, including the four whose surrounding spaces
     * are data rather than padding and the one whose eight leading spaces align the amount heading.
     *
     * @throws IllegalStateException if a literal has been altered or is too wide for its field
     */
    private static void verifyLiterals() {
        requireGeometry(REPT_SHORT_NAME_VALUE, "DALYREPT", "REPT-SHORT-NAME's VALUE literal");
        requireGeometry(REPT_SHORT_NAME_VALUE.length(), 8,
                "'DALYREPT' is 8 characters, right-padded into X(38)");
        requireGeometry(REPT_SHORT_NAME_LENGTH, 38, "REPT-SHORT-NAME's field width");
        requireGeometry(REPT_LONG_NAME_VALUE, "Daily Transaction Report",
                "REPT-LONG-NAME's VALUE literal");
        requireGeometry(REPT_LONG_NAME_VALUE.length(), 24,
                "'Daily Transaction Report' is 24 characters, right-padded into X(41)");
        requireGeometry(REPT_LONG_NAME_LENGTH, 41, "REPT-LONG-NAME's field width");
        requireGeometry(REPT_DATE_HEADER_VALUE, "Date Range: ",
                "REPT-DATE-HEADER's VALUE literal, trailing space included");
        requireGeometry(REPT_DATE_HEADER_VALUE.length(), REPT_DATE_HEADER_LENGTH,
                "'Date Range: ' exactly fills X(12), so its trailing space is data not padding");
        requireGeometry(REPT_DATE_HEADER_VALUE.charAt(REPT_DATE_HEADER_LENGTH - 1), SPACE,
                "the last character of 'Date Range: '");
        requireGeometry(DATE_RANGE_SEPARATOR_VALUE, " to ",
                "the date-range separator's VALUE literal, both spaces included");
        requireGeometry(DATE_RANGE_SEPARATOR_VALUE.length(), DATE_RANGE_SEPARATOR_LENGTH,
                "' to ' exactly fills X(04), so both of its spaces are data not padding");
        requireGeometry(DATE_RANGE_SEPARATOR_VALUE.charAt(0), SPACE,
                "the first character of ' to '");
        requireGeometry(DATE_RANGE_SEPARATOR_VALUE.charAt(DATE_RANGE_SEPARATOR_LENGTH - 1), SPACE,
                "the last character of ' to '");
        requireGeometry(DETAIL_SEPARATOR_VALUE.length(), 1,
                "each detail separator FILLER holds a single character");
        requireGeometry(DETAIL_SEPARATOR_VALUE.charAt(0), MINUS_SIGN,
                "the detail separator character, at columns 32 and 53");

        requireGeometry(HEADER_1_TRANSACTION_ID_VALUE.length(), 14,
                "'Transaction ID' is 14 characters in an X(17) field");
        requireGeometry(HEADER_1_ACCOUNT_ID_VALUE.length(), 10,
                "'Account ID' is 10 characters in an X(12) field");
        requireGeometry(HEADER_1_TRANSACTION_TYPE_VALUE.length(), 16,
                "'Transaction Type' is 16 characters in an X(19) field");
        requireGeometry(HEADER_1_TRAN_CATEGORY_VALUE.length(), 13,
                "'Tran Category' is 13 characters in an X(35) field");
        requireGeometry(HEADER_1_TRAN_SOURCE_VALUE.length(), 11,
                "'Tran Source' is 11 characters in an X(14) field");
        requireGeometry(HEADER_1_AMOUNT_VALUE.length(), 14,
                "'        Amount' is 14 characters in an X(16) field");
        requireGeometry(HEADER_1_AMOUNT_LEADING_SPACES, 8,
                "the transcribed leading-space count of the amount caption");
        requireGeometry(countLeadingSpaces(HEADER_1_AMOUNT_VALUE), HEADER_1_AMOUNT_LEADING_SPACES,
                "the leading spaces actually present in '        Amount'");
        requireGeometry(HEADER_1_AMOUNT_WORD_COLUMN_START, 107,
                "the transcribed 1-based column at which the word 'Amount' begins");
        requireGeometry(HEADER_1_AMOUNT_OFFSET + HEADER_1_AMOUNT_LEADING_SPACES + 1,
                HEADER_1_AMOUNT_WORD_COLUMN_START,
                "where 'Amount' actually lands - columns 107-112, ending on the mask's last column");

        requireGeometry(PAGE_TOTAL_LABEL_VALUE, "Page Total", "REPORT-PAGE-TOTALS' label literal");
        requireGeometry(PAGE_TOTAL_LABEL_VALUE.length(), 10,
                "'Page Total' is 10 characters in an X(11) field, leaving one trailing space");
        requireGeometry(ACCOUNT_TOTAL_LABEL_VALUE, "Account Total",
                "REPORT-ACCOUNT-TOTALS' label literal");
        requireGeometry(ACCOUNT_TOTAL_LABEL_VALUE.length(), ACCOUNT_TOTAL_LABEL_LENGTH,
                "'Account Total' exactly fills X(13), with no trailing space");
        requireGeometry(GRAND_TOTAL_LABEL_VALUE, "Grand Total",
                "REPORT-GRAND-TOTALS' label literal");
        requireGeometry(GRAND_TOTAL_LABEL_VALUE.length(), GRAND_TOTAL_LABEL_LENGTH,
                "'Grand Total' exactly fills X(11), with no trailing space");
        requireGeometry(TRANSACTION_HEADER_2_RULE_CHARACTER, MINUS_SIGN,
                "TRANSACTION-HEADER-2's ALL literal character");
        requireGeometry(isRepetitionOf(TRANSACTION_HEADER_2_IMAGE,
                        TRANSACTION_HEADER_2_RULE_CHARACTER, TRANSACTION_HEADER_2_LENGTH),
                "TRANSACTION-HEADER-2 is 133 hyphens and nothing else");
    }

    /**
     * Asserts the mask position arithmetic that replaces a lookup table, at both ends of every run.
     *
     * @throws IllegalStateException if a digit slot or a comma resolves to the wrong mask position
     */
    private static void verifyMaskIndexArithmetic() {
        requireGeometry(SIGN_INDEX, 0, "the sign's mask index");
        requireGeometry(FIRST_INTEGER_DIGIT_INDEX, 1, "the first integer digit slot's mask index");
        requireGeometry(DIGITS_PER_GROUP, 3, "the digit slots between one comma and the next");
        requireGeometry(GROUP_STRIDE, 4, "the mask positions one digit group plus its comma spans");
        requireGeometry(integerDigitMaskIndex(0), 1, "integer digit 1's mask index");
        requireGeometry(integerDigitMaskIndex(2), 3, "integer digit 3's mask index");
        requireGeometry(integerDigitMaskIndex(3), 5, "integer digit 4's mask index");
        requireGeometry(integerDigitMaskIndex(5), 7, "integer digit 6's mask index");
        requireGeometry(integerDigitMaskIndex(6), 9, "integer digit 7's mask index");
        requireGeometry(integerDigitMaskIndex(8), 11, "integer digit 9's mask index");
        requireGeometry(groupSeparatorMaskIndex(0), 4, "the first ',' insertion character's index");
        requireGeometry(groupSeparatorMaskIndex(1), 8, "the second ',' insertion character's index");
        for (int digit = 0; digit < AMOUNT_INTEGER_DIGITS; digit++) {
            int position = integerDigitMaskIndex(digit);
            requireGeometry(DETAIL_AMOUNT_MASK.charAt(position), 'Z',
                    "the mask character at index " + position + ", which must be a Z digit slot");
        }
    }

    /**
     * The guard behind every invariant above. Package-private so the self-check is itself testable:
     * both outcomes have to be exercised, or the check is only assumed to work.
     *
     * @param satisfied   whether the invariant holds
     * @param description what was expected, phrased so the message reads as the requirement
     * @throws IllegalStateException if {@code satisfied} is {@code false}
     */
    static void requireGeometry(boolean satisfied, String description) {
        if (!satisfied) {
            throw new IllegalStateException(
                    "app/cpy/CVTRA07Y.cpy transcription check failed: " + description);
        }
    }

    /**
     * The integer-equality guard, which most of the self-check uses. Comparing through a named guard
     * rather than inline keeps every comparison in one testable place and puts the two numbers into the
     * failure message, so a mis-transcription says what it found as well as what it wanted.
     *
     * @param actual      the value as declared or computed
     * @param expected    the value the copybook requires
     * @param description what was expected, phrased so the message reads as the requirement
     * @throws IllegalStateException if the two differ
     */
    static void requireGeometry(int actual, int expected, String description) {
        if (actual != expected) {
            throw new IllegalStateException("app/cpy/CVTRA07Y.cpy transcription check failed: "
                    + description + " must be " + expected + " but is " + actual);
        }
    }

    /**
     * The character-equality guard, for the mask's sign, comma and decimal point positions and for the
     * literals whose surrounding spaces are data.
     *
     * @param actual      the character as declared
     * @param expected    the character the copybook requires
     * @param description what was expected, phrased so the message reads as the requirement
     * @throws IllegalStateException if the two differ
     */
    static void requireGeometry(char actual, char expected, String description) {
        if (actual != expected) {
            throw new IllegalStateException("app/cpy/CVTRA07Y.cpy transcription check failed: "
                    + description + " must be '" + expected + "' but is '" + actual + "'");
        }
    }

    /**
     * The string-equality guard, for the {@code VALUE} literals themselves.
     *
     * @param actual      the literal as declared
     * @param expected    the literal the copybook requires, byte for byte
     * @param description what was expected, phrased so the message reads as the requirement
     * @throws IllegalStateException if the two differ
     */
    static void requireGeometry(String actual, String expected, String description) {
        requireGeometry(expected.equals(actual), description + " must be \"" + expected
                + "\" but is \"" + actual + '"');
    }

    /**
     * Whether a string is exactly {@code length} repetitions of {@code character}. Package-private so
     * that all three of its outcomes — wrong length, wrong character, correct — are under test.
     *
     * @param candidate the string to inspect
     * @param character the only character it may contain
     * @param length    the exact length required
     * @return {@code true} only if both the length and every character match
     */
    static boolean isRepetitionOf(String candidate, char character, int length) {
        if (candidate.length() != length) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            if (candidate.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    /**
     * Counts occurrences of a character in a string. Package-private so both outcomes of its
     * per-character test are under test.
     *
     * @param candidate the string to inspect
     * @param character the character to count
     * @return how many positions hold {@code character}
     */
    static int countOf(String candidate, char character) {
        int occurrences = 0;
        for (int index = 0; index < candidate.length(); index++) {
            if (candidate.charAt(index) == character) {
                occurrences++;
            }
        }
        return occurrences;
    }

    /**
     * Counts the leading spaces of a string, which is how the amount caption's alignment is proved.
     * Package-private so both ways the scan can stop — on a non-space and on the end of the string —
     * are under test.
     *
     * @param candidate the string to inspect
     * @return the number of spaces before its first non-space character
     */
    static int countLeadingSpaces(String candidate) {
        int spaces = 0;
        while (spaces < candidate.length() && candidate.charAt(spaces) == SPACE) {
            spaces++;
        }
        return spaces;
    }
}
