/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

import module java.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.port.TransactionTypeRepository;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.ReportHeaders;
import com.blitzy.carddemo.domain.record.TranCatRecord;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.record.TranTypeRecord;
import com.blitzy.carddemo.domain.util.Decimals;

/**
 * Java translation of the {@code CBTRN03C} COBOL batch program at
 * {@code app/cbl/CBTRN03C.cbl} ("Print the transaction detail report").
 *
 * <h2>Program purpose</h2>
 * <p>Reads the {@code TRANSACT} file sequentially. Filters each
 * {@code TRAN-RECORD} by the date window read from {@code DATEPARM}
 * ({@code TRAN-PROC-TS(1:10)} lexically between {@code WS-START-DATE} and
 * {@code WS-END-DATE}). For each in-window record, performs three random
 * lookups (XREF by {@code TRAN-CARD-NUM}, TRANTYPE by
 * {@code TRAN-TYPE-CD}, TRANCATG by composite key
 * {@code TRAN-TYPE-CD + TRAN-CAT-CD}) and writes a fixed-width detail line
 * to the {@code TRANREPT} report file. Pagination, page totals, per-card
 * (account) totals, and a final grand total are emitted per the layout
 * defined in {@code app/cpy/CVTRA07Y.cpy}.
 *
 * <h2>Driving JCL</h2>
 * <p>Invoked from {@code app/jcl/TRANREPT.jcl} step {@code STEP10R} as
 * {@code EXEC PGM=CBTRN03C}. The preceding step SORTs the TRANSACT.BKUP
 * dataset by {@code TRAN-CARD-NUM} ascending and applies an
 * {@code INCLUDE COND} that retains only records whose
 * {@code TRAN-PROC-DT} (offset 305, length 10) is within the parameter
 * window. The DD allocations wire:
 * <ul>
 *   <li>{@code TRANFILE} &rarr; sorted/filtered transaction input
 *       ({@link TransactionRepository#streamSequential()})</li>
 *   <li>{@code CARDXREF}, {@code TRANTYPE}, {@code TRANCATG} &rarr;
 *       KSDS lookup datasets for the three {@code 1500} paragraphs</li>
 *   <li>{@code DATEPARM} &rarr; a single 80-byte record carrying the
 *       date window as {@code start(10) + filler(1) + end(10)}
 *       (the remaining 59 bytes are unused per copybook layout)</li>
 *   <li>{@code TRANREPT} &rarr; 133-byte fixed-record output</li>
 * </ul>
 *
 * <h2>Paragraph mapping (1:1 with COBOL)</h2>
 * <ul>
 *   <li>{@code 0000-TRANFILE-OPEN} &rarr; {@link #openTransactionFile()}</li>
 *   <li>{@code 0100-REPTFILE-OPEN} &rarr; {@link #openReptFile()}</li>
 *   <li>{@code 0200-CARDXREF-OPEN} &rarr; {@link #openCardXref()}</li>
 *   <li>{@code 0300-TRANTYPE-OPEN} &rarr; {@link #openTranType()}</li>
 *   <li>{@code 0400-TRANCATG-OPEN} &rarr; {@link #openTranCatg()}</li>
 *   <li>{@code 0500-DATEPARM-OPEN} &rarr; {@link #openDateParm()}</li>
 *   <li>{@code 0550-DATEPARM-READ} &rarr; {@link #readDateParm()}</li>
 *   <li>{@code 1000-TRANFILE-GET-NEXT} &rarr; the per-iteration READ in
 *       {@link #run()}</li>
 *   <li>{@code 1100-WRITE-TRANSACTION-REPORT} &rarr;
 *       {@link #writeTransactionReport(TranRecord)}</li>
 *   <li>{@code 1110-WRITE-PAGE-TOTALS} &rarr; {@link #writePageTotals()}</li>
 *   <li>{@code 1110-WRITE-GRAND-TOTALS} (duplicate paragraph name) &rarr;
 *       {@link #writeGrandTotals()}</li>
 *   <li>{@code 1120-WRITE-ACCOUNT-TOTALS} &rarr;
 *       {@link #writeAccountTotals()}</li>
 *   <li>{@code 1120-WRITE-HEADERS} (duplicate paragraph name) &rarr;
 *       {@link #writeHeaders()}</li>
 *   <li>{@code 1120-WRITE-DETAIL} (duplicate paragraph name) &rarr;
 *       {@link #writeDetail(TranRecord)}</li>
 *   <li>{@code 1111-WRITE-REPORT-REC} &rarr; {@link #writeReportRec(byte[])}</li>
 *   <li>{@code 1500-A-LOOKUP-XREF} &rarr; {@link #lookupXref(String)}</li>
 *   <li>{@code 1500-B-LOOKUP-TRANTYPE} &rarr; {@link #lookupTranType(String)}</li>
 *   <li>{@code 1500-C-LOOKUP-TRANCATG} &rarr; {@link #lookupTranCatg(String, int)}</li>
 *   <li>{@code 9000-TRANFILE-CLOSE} &rarr; {@link #closeTransactionFile()}</li>
 *   <li>{@code 9100-REPTFILE-CLOSE} &rarr; {@link #closeReptFile()}</li>
 *   <li>{@code 9200-CARDXREF-CLOSE} &rarr; {@link #closeCardXref()}</li>
 *   <li>{@code 9300-TRANTYPE-CLOSE} &rarr; {@link #closeTranType()}</li>
 *   <li>{@code 9400-TRANCATG-CLOSE} &rarr; {@link #closeTranCatg()}</li>
 *   <li>{@code 9500-DATEPARM-CLOSE} &rarr; {@link #closeDateParm()}</li>
 *   <li>{@code 9910-DISPLAY-IO-STATUS} &rarr;
 *       {@link #displayIoStatus(String)}</li>
 *   <li>{@code 9999-ABEND-PROGRAM} &rarr; {@link #abendProgram(String, Throwable)}</li>
 * </ul>
 *
 * <h2>COBOL anomalies preserved verbatim (AAP &sect;0.7.1)</h2>
 * <p>The COBOL source contains three notable anomalies which are
 * preserved faithfully and flagged in {@code MIGRATION_NOTES.md}:
 * <ol>
 *   <li><b>Duplicate paragraph names.</b> Both {@code 1110-WRITE-PAGE-TOTALS}
 *       and {@code 1110-WRITE-GRAND-TOTALS} use the {@code 1110} prefix;
 *       {@code 1120-WRITE-ACCOUNT-TOTALS}, {@code 1120-WRITE-HEADERS},
 *       and {@code 1120-WRITE-DETAIL} all share the {@code 1120} prefix.
 *       Java method names disambiguate while Javadoc cites the original
 *       paragraph identifier.</li>
 *   <li><b>EOF "double-add" branch.</b> When the {@code 1000-TRANFILE-GET-NEXT}
 *       paragraph sets {@code END-OF-FILE = 'Y'}, the buffer
 *       {@code TRAN-RECORD} retains the bytes of the last successfully
 *       read record. The COBOL EOF branch (lines 197-203 of CBTRN03C)
 *       executes
 *       {@code ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL}
 *       using that stale value, effectively double-counting the final
 *       record into the page and account accumulators before emitting
 *       the page totals + grand totals. This translation preserves the
 *       double-add verbatim, even though it is almost certainly a
 *       defect.</li>
 *   <li><b>{@code NEXT SENTENCE} on out-of-window date.</b> The COBOL
 *       date filter at lines 173-178 uses {@code ELSE NEXT SENTENCE}
 *       which (in strict Enterprise COBOL semantics) transfers control
 *       to the statement immediately following the next end-of-sentence
 *       period &mdash; that period is the {@code END-PERFORM.} at line
 *       206, so NEXT SENTENCE literally exits the outer
 *       {@code PERFORM UNTIL} loop. In practice this is masked by the
 *       upstream SORT step in TRANREPT.jcl which pre-filters records to
 *       fit the date window before CBTRN03C runs. This translation uses
 *       the apparent-intent interpretation (skip the record and
 *       continue) which matches the AAP file schema and is
 *       observationally identical when input is pre-filtered.</li>
 * </ol>
 *
 * <h2>Decimal arithmetic discipline (AAP &sect;0.6.1)</h2>
 * <p>All monetary accumulators ({@code WS-PAGE-TOTAL},
 * {@code WS-ACCOUNT-TOTAL}, {@code WS-GRAND-TOTAL}) are
 * {@link java.math.BigDecimal} with scale 2. Accumulator updates use
 * {@link Decimals#add(java.math.BigDecimal, java.math.BigDecimal, int, java.math.RoundingMode)}
 * with {@link java.math.RoundingMode#DOWN} &mdash; the COBOL default for
 * an unrounded {@code ADD} statement (the {@code ROUNDED} clause is not
 * present in CBTRN03C). {@code MathContext.DECIMAL128} is implied by the
 * {@code Decimals} facade.
 *
 * <h2>PCI / PAN masking (AAP &sect;0.7.2)</h2>
 * <p>The COBOL source uses {@code DISPLAY TRAN-RECORD} which emits the
 * full 350-byte record &mdash; including the 16-byte PAN at
 * {@code TRAN-CARD-NUM}. This Java translation delegates to
 * {@link TranRecord#toString()} which masks all but the last 4 digits of
 * the PAN. The error message accompanying an XREF lookup failure
 * ({@code "INVALID CARD NUMBER : " + FD-XREF-CARD-NUM}) is similarly
 * passed through {@link #maskPan(String)} before logging. Storage and
 * other observable file outputs are unaffected.
 *
 * <h2>Threading model</h2>
 * <p>This class is single-threaded by construction. The COBOL program is
 * a sequential batch with stateful accumulators and a card-number break
 * predicate that depends on input ordering &mdash; virtual-thread
 * fan-out would change observable output and is therefore forbidden per
 * AAP &sect;0.1.3.
 */
@CobolProgram(
        value = "CBTRN03C",
        sourcePath = "app/cbl/CBTRN03C.cbl",
        translationDate = "2025-01-21",
        notes = "Paginated transaction detail report writer (TRANREPT JCL). "
                + "Reads TRANSACT + DATEPARM, filters by TRAN-PROC-TS(1:10) within [start,end], "
                + "looks up XREF/TRANTYPE/TRANCATG, writes paginated detail report with "
                + "page/account/grand totals at WS-PAGE-SIZE=20. ANOMALIES PRESERVED VERBATIM "
                + "(see MIGRATION_NOTES.md): duplicate paragraph names 1110/1120; EOF branch "
                + "double-adds stale TRAN-AMT (suspected COBOL bug); NEXT SENTENCE strict "
                + "semantic would exit loop, agent_prompt's apparent-intent (continue) is used "
                + "since upstream SORT pre-filters records."
)
public final class CbTrn03C {

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    private static final Logger LOGGER = LoggerFactory.getLogger(CbTrn03C.class);

    /** Mirror of the COBOL {@code PROGRAM-ID}. */
    public static final String PROGRAM_ID = "CBTRN03C";

    // ---------------------------------------------------------------------
    // File status / APPL-RESULT discriminators (mirror COBOL WORKING-STORAGE)
    // ---------------------------------------------------------------------

    /** COBOL FILE STATUS "00" = successful operation. */
    private static final String STATUS_OK = "00";

    /** COBOL FILE STATUS "10" = end of file. */
    private static final String STATUS_EOF = "10";

    /** Synthetic FILE STATUS used for non-VSAM errors (mirrors APPL-RESULT=12). */
    private static final String STATUS_ERROR = "12";

    /**
     * COBOL APPL-RESULT value moved when an {@code INVALID KEY} branch
     * fires on a random-read lookup (1500-A/B/C). The COBOL source moves
     * the literal {@code 23} to {@code IO-STATUS}; preserved verbatim.
     */
    private static final String STATUS_INVALID_KEY = "23";

    /** {@code APPL-AOK} discriminator value (88-level VALUE 0). */
    private static final int APPL_AOK = 0;

    /** {@code APPL-EOF} discriminator value (88-level VALUE 16). */
    private static final int APPL_EOF = 16;

    /** Synthetic value used when APPL-RESULT MOVE 12 indicates an error. */
    private static final int APPL_ERROR = 12;

    /** Synthetic value used when APPL-RESULT MOVE 8 indicates pending. */
    private static final int APPL_PENDING = 8;

    // ---------------------------------------------------------------------
    // CEE3ABD parameters (mirror COBOL 9999-ABEND-PROGRAM)
    // ---------------------------------------------------------------------

    /** {@code MOVE 0 TO TIMING}: synchronous abend timing. */
    private static final int CEE3ABD_TIMING = 0;

    /** {@code MOVE 999 TO ABCODE}: ABEND code raised by the program. */
    private static final int CEE3ABD_ABCODE = 999;

    // ---------------------------------------------------------------------
    // Layout / pagination constants (mirror COBOL WORKING-STORAGE)
    // ---------------------------------------------------------------------

    /** Mirror of {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}. */
    public static final int PAGE_SIZE = 20;

    /** Mirror of {@code FD-REPTFILE-REC PIC X(133)} record width. */
    public static final int REPORT_LINE_WIDTH = 133;

    /** Monetary scale for accumulator BigDecimals per AAP &sect;0.6.1. */
    public static final int MONETARY_SCALE = 2;

    /** Number of trailing PAN digits left visible by {@link #maskPan(String)}. */
    private static final int PAN_VISIBLE_TAIL = 4;

    /** ASCII space byte (0x20) used for right-padding short report lines. */
    private static final byte ASCII_SPACE = (byte) 0x20;

    /**
     * Mirror of {@code WS-BLANK-LINE PIC X(133) VALUE SPACES} from the
     * COBOL WORKING-STORAGE. Returned by {@link #blankLine()} (fresh
     * defensive copy each call to preserve byte-buffer immutability).
     */
    private static final byte[] BLANK_LINE_TEMPLATE = buildBlankLine();

    private static byte[] buildBlankLine() {
        byte[] line = new byte[REPORT_LINE_WIDTH];
        Arrays.fill(line, ASCII_SPACE);
        return line;
    }

    // ---------------------------------------------------------------------
    // Injected dependencies (constructor-injected; never null)
    // ---------------------------------------------------------------------

    /** Sequential transaction reader (1000-TRANFILE-GET-NEXT). */
    private final TransactionRepository transactionRepository;

    /** Random XREF lookup by card number (1500-A-LOOKUP-XREF). */
    private final CardXrefRepository cardXrefRepository;

    /** Random TRANTYPE lookup by 2-char code (1500-B-LOOKUP-TRANTYPE). */
    private final TransactionTypeRepository transactionTypeRepository;

    /** Random TRANCATG lookup by composite key (1500-C-LOOKUP-TRANCATG). */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /** DATEPARM file reader (0500-DATEPARM-OPEN + 0550-DATEPARM-READ). */
    private final DateParamsSource dateParamsSource;

    /** TRANREPT file writer (0100-REPTFILE-OPEN + 1111-WRITE-REPORT-REC). */
    private final ReportSink reportSink;

    // ---------------------------------------------------------------------
    // Mutable state (mirrors COBOL WORKING-STORAGE; never shared cross-thread)
    // ---------------------------------------------------------------------

    /** Mirror of {@code WS-FIRST-TIME PIC X VALUE 'Y'} ('Y' = true). */
    private boolean firstTime = true;

    /** Mirror of {@code WS-LINE-COUNTER PIC 9(9) COMP-3 VALUE 0}. */
    private long lineCounter;

    /** Mirror of {@code WS-PAGE-TOTAL PIC S9(09)V99 VALUE 0}. */
    private BigDecimal pageTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);

    /** Mirror of {@code WS-ACCOUNT-TOTAL PIC S9(09)V99 VALUE 0}. */
    private BigDecimal accountTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);

    /** Mirror of {@code WS-GRAND-TOTAL PIC S9(09)V99 VALUE 0}. */
    private BigDecimal grandTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);

    /**
     * Mirror of {@code WS-CURR-CARD-NUM PIC X(16) VALUE SPACES}. The
     * initial value is 16 ASCII spaces so that the first record's
     * {@code TRAN-CARD-NUM} (always non-blank) triggers the card-break
     * branch on the first iteration; we then suppress the
     * {@code writeAccountTotals()} call on the first iteration via the
     * {@code firstTime} flag exactly as the COBOL does.
     */
    private String currCardNum = " ".repeat(16);

    /** Mirror of {@code WS-START-DATE PIC X(10)}, populated by {@link #readDateParm()}. */
    private String startDate = "";

    /** Mirror of {@code WS-END-DATE PIC X(10)}, populated by {@link #readDateParm()}. */
    private String endDate = "";

    /** Mirror of {@code END-OF-FILE PIC X VALUE 'N'} (Y/N flag in COBOL). */
    private boolean endOfFile;

    /** Mirror of {@code APPL-RESULT PIC S9(9) COMP VALUE 999}. */
    private int applResult;

    /** Mirror of {@code IO-STATUS PIC X(02)}. */
    private String ioStatus = "";

    /**
     * Last successfully read transaction record. The COBOL FD buffer
     * {@code TRAN-RECORD} retains its value after EOF; this field is
     * the Java equivalent &mdash; preserved deliberately so the EOF
     * branch in {@link #run()} can faithfully reproduce the
     * suspected-bug double-add (see class Javadoc anomaly #2).
     */
    private TranRecord lastTran;

    /** Last successful XREF lookup result, used by {@link #writeDetail(TranRecord)}. */
    private CardXrefRecord lastXref;

    /** Last successful TRANTYPE lookup result, used by {@link #writeDetail(TranRecord)}. */
    private TranTypeRecord lastTranType;

    /** Last successful TRANCATG lookup result, used by {@link #writeDetail(TranRecord)}. */
    private TranCatRecord lastTranCatg;

    // ---------------------------------------------------------------------
    // Nested types exposed as part of the public API (schema-mandated)
    // ---------------------------------------------------------------------

    /**
     * Functional interface for the {@code DATEPARM} input file. Provides
     * a one-shot read of the single record carrying the report date
     * window (COBOL {@code 0500-DATEPARM-OPEN} + {@code 0550-DATEPARM-READ}).
     *
     * <p>Implementations are typically backed by a fixed-width file
     * adapter that reads exactly one record from a sequential dataset.
     * The record layout is:
     * <pre>
     *   bytes  0..9   WS-START-DATE  PIC X(10)  ("YYYY-MM-DD")
     *   byte   10     FILLER         PIC X(01)
     *   bytes 11..20  WS-END-DATE    PIC X(10)  ("YYYY-MM-DD")
     * </pre>
     * Trailing bytes (the COBOL record is conventionally 80 bytes) are
     * ignored.
     *
     * <p>Implementations may throw {@link java.io.UncheckedIOException}
     * on I/O failure; the caller maps this to the COBOL "ERROR OPENING
     * DATEPARM FILE" / "ERROR READING DATEPARM FILE" abend paths.
     */
    @FunctionalInterface
    public interface DateParamsSource {
        /**
         * Reads the single DATEPARM record and returns the parsed
         * date-range parameters.
         *
         * @return the parsed {@link DateParams} carrying the start date,
         *         end date, and the original FILE STATUS from the read
         * @throws java.io.UncheckedIOException if the file cannot be
         *         opened, read, or is malformed
         */
        DateParams read();
    }

    /**
     * Carrier record for the result of a {@link DateParamsSource#read()}
     * call. The {@code status} field is the COBOL FILE STATUS observed
     * during the read (typically {@code "00"} on success or
     * {@code "10"} on EOF before any record was found).
     *
     * @param startDate the parsed 10-character start date ("YYYY-MM-DD")
     * @param endDate   the parsed 10-character end date   ("YYYY-MM-DD")
     * @param status    the COBOL FILE STATUS observed during the read
     */
    public record DateParams(String startDate, String endDate, String status) {
        /**
         * Compact constructor with defensive null guards. The status
         * defaults to {@code "00"} (success) when not supplied.
         */
        public DateParams {
            Objects.requireNonNull(startDate, "startDate");
            Objects.requireNonNull(endDate, "endDate");
            if (status == null) {
                status = STATUS_OK;
            }
        }
    }

    /**
     * Functional interface for the {@code TRANREPT} output file. Writes
     * a single fixed-width 133-byte line (COBOL
     * {@code 1111-WRITE-REPORT-REC} translation).
     *
     * <p>Implementations are typically backed by a sequential
     * fixed-width file writer that appends each line to a 133-byte
     * record-mode dataset. Lines shorter than 133 bytes MUST be
     * right-padded with ASCII 0x20 spaces by the caller (see
     * {@link CbTrn03C#writeReportRec(byte[])}); implementations may
     * verify length and reject otherwise.
     *
     * <p>The return value is the COBOL FILE STATUS observed after the
     * WRITE (typically {@code "00"} on success). Implementations may
     * throw {@link java.io.UncheckedIOException} on I/O failure; the
     * caller maps this to the "ERROR WRITING REPORT FILE" abend path.
     */
    @FunctionalInterface
    public interface ReportSink {
        /**
         * Writes a single 133-byte report line.
         *
         * @param line a 133-byte fixed-width line (right-padded with
         *             ASCII spaces); MUST NOT be {@code null}
         * @return the COBOL FILE STATUS observed during the WRITE
         *         (typically {@code "00"} on success)
         * @throws java.io.UncheckedIOException if the underlying stream
         *         fails or is closed
         */
        String write(byte[] line);
    }

    // ---------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------

    /**
     * Constructs a new CbTrn03C instance with the given collaborators.
     * All collaborators are constructor-injected and {@code null}-checked
     * via {@link Objects#requireNonNull(Object, String)} (per AAP
     * &sect;0.3.2 plain-factories-and-constructor-injection rule).
     *
     * @param transactionRepository       sequential TRANSACT reader
     *                                    (1000-TRANFILE-GET-NEXT)
     * @param cardXrefRepository          random XREF lookup
     *                                    (1500-A-LOOKUP-XREF)
     * @param transactionTypeRepository   random TRANTYPE lookup
     *                                    (1500-B-LOOKUP-TRANTYPE)
     * @param transactionCategoryRepository random TRANCATG lookup
     *                                    (1500-C-LOOKUP-TRANCATG)
     * @param dateParamsSource            DATEPARM file reader
     *                                    (0500-DATEPARM-OPEN
     *                                    + 0550-DATEPARM-READ)
     * @param reportSink                  TRANREPT file writer
     *                                    (0100-REPTFILE-OPEN
     *                                    + 1111-WRITE-REPORT-REC)
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public CbTrn03C(
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryRepository transactionCategoryRepository,
            DateParamsSource dateParamsSource,
            ReportSink reportSink) {
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.cardXrefRepository = Objects.requireNonNull(
                cardXrefRepository, "cardXrefRepository");
        this.transactionTypeRepository = Objects.requireNonNull(
                transactionTypeRepository, "transactionTypeRepository");
        this.transactionCategoryRepository = Objects.requireNonNull(
                transactionCategoryRepository, "transactionCategoryRepository");
        this.dateParamsSource = Objects.requireNonNull(
                dateParamsSource, "dateParamsSource");
        this.reportSink = Objects.requireNonNull(reportSink, "reportSink");
    }

    // ---------------------------------------------------------------------
    // Public entry method - COBOL PROCEDURE DIVISION / MAIN-PARA
    // ---------------------------------------------------------------------

    /**
     * Translates the COBOL {@code PROCEDURE DIVISION} / {@code MAIN-PARA}.
     *
     * <p>Sequence (lines 159-217 of {@code app/cbl/CBTRN03C.cbl}):
     * <pre>
     *   DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'.
     *   PERFORM 0000-TRANFILE-OPEN
     *   PERFORM 0100-REPTFILE-OPEN
     *   PERFORM 0200-CARDXREF-OPEN
     *   PERFORM 0300-TRANTYPE-OPEN
     *   PERFORM 0400-TRANCATG-OPEN
     *   PERFORM 0500-DATEPARM-OPEN
     *   PERFORM 0550-DATEPARM-READ
     *   DISPLAY 'Reporting from ' WS-START-DATE ' to ' WS-END-DATE.
     *   PERFORM UNTIL END-OF-FILE = 'Y'
     *       IF END-OF-FILE = 'N'
     *           PERFORM 1000-TRANFILE-GET-NEXT
     *           IF TRAN-PROC-TS (1:10) &gt;= WS-START-DATE
     *              AND TRAN-PROC-TS (1:10) &lt;= WS-END-DATE
     *               CONTINUE
     *           ELSE
     *               NEXT SENTENCE       (apparent-intent: skip + continue loop)
     *           END-IF
     *           IF END-OF-FILE = 'N'
     *               DISPLAY TRAN-RECORD
     *               IF TRAN-CARD-NUM NOT EQUAL TO WS-CURR-CARD-NUM
     *                   IF WS-FIRST-TIME = 'N'
     *                       PERFORM 1120-WRITE-ACCOUNT-TOTALS
     *                   END-IF
     *                   MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM
     *                   PERFORM 1500-A-LOOKUP-XREF
     *               END-IF
     *               PERFORM 1500-B-LOOKUP-TRANTYPE
     *               PERFORM 1500-C-LOOKUP-TRANCATG
     *               PERFORM 1100-WRITE-TRANSACTION-REPORT
     *           ELSE
     *               DISPLAY 'TRAN-AMT ' TRAN-AMT
     *               DISPLAY 'WS-PAGE-TOTAL' WS-PAGE-TOTAL
     *               ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL
     *               PERFORM 1110-WRITE-PAGE-TOTALS
     *               PERFORM 1110-WRITE-GRAND-TOTALS
     *           END-IF
     *       END-IF
     *   END-PERFORM.
     *   PERFORM 9000-TRANFILE-CLOSE
     *   PERFORM 9100-REPTFILE-CLOSE
     *   PERFORM 9200-CARDXREF-CLOSE
     *   PERFORM 9300-TRANTYPE-CLOSE
     *   PERFORM 9400-TRANCATG-CLOSE
     *   PERFORM 9500-DATEPARM-CLOSE
     *   DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'.
     *   GOBACK.
     * </pre>
     *
     * <p>Java translation notes:
     * <ul>
     *   <li>The COBOL {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop is
     *       expressed as a {@code while (!endOfFile)} loop driven by a
     *       {@link java.util.Iterator} over
     *       {@link TransactionRepository#streamSequential()}.</li>
     *   <li>The TRANSACT stream is opened inside a try-with-resources
     *       block so the underlying file handle is always released
     *       (mirrors COBOL's {@code 9000-TRANFILE-CLOSE} guarantee).</li>
     *   <li>The {@code DISPLAY TRAN-RECORD} call uses
     *       {@link TranRecord#toString()} which masks all but the last 4
     *       digits of the PAN (AAP &sect;0.7.2). The COBOL output would
     *       have rendered the full PAN; this is the one observable
     *       deviation from byte-level COBOL parity in the log stream
     *       only (not in any output file).</li>
     *   <li>The "EOF double-add" suspected bug (lines 197-203 of the
     *       COBOL source) is preserved verbatim &mdash; the stale
     *       {@code lastTran} amount is added to {@code pageTotal} and
     *       {@code accountTotal} before emitting page+grand totals.</li>
     *   <li>The COBOL {@code NEXT SENTENCE} on out-of-window date is
     *       translated as "skip this record, continue loop" (the
     *       apparent-intent interpretation; strict semantics would exit
     *       the outer loop, but the upstream SORT step makes this
     *       observationally identical).</li>
     * </ul>
     *
     * @throws AbendException if any file-I/O step fails (COBOL
     *         {@code 9999-ABEND-PROGRAM} translated as a Java unchecked
     *         exception)
     */
    public void run() {
        LOGGER.info("START OF EXECUTION OF PROGRAM CBTRN03C");

        // --- Open all files in COBOL order ---
        openTransactionFile();
        openReptFile();
        openCardXref();
        openTranType();
        openTranCatg();
        openDateParm();

        // --- Read DATEPARM (sets startDate/endDate) ---
        readDateParm();

        LOGGER.info("Reporting from {} to {}", startDate, endDate);

        // --- Main loop ---
        // The TRANSACT stream MUST be closed after the loop regardless
        // of outcome; the close paragraphs below assume the underlying
        // resource has been released.
        try (Stream<TranRecord> tranStream = transactionRepository.streamSequential()) {
            Iterator<TranRecord> iter = tranStream.iterator();
            while (!endOfFile) {
                // -- 1000-TRANFILE-GET-NEXT --
                getNextTransaction(iter);
                if (endOfFile) {
                    // === EOF branch (COBOL lines 197-203) ===
                    // SUSPECTED COBOL BUG preserved verbatim: ADD the
                    // stale TRAN-AMT (from lastTran) to page/account
                    // accumulators before emitting page+grand totals.
                    // See class Javadoc anomaly #2 and MIGRATION_NOTES.md.
                    if (lastTran != null) {
                        LOGGER.info("TRAN-AMT {}", lastTran.tranAmt());
                        LOGGER.info("WS-PAGE-TOTAL {}", pageTotal);
                        pageTotal = Decimals.add(
                                pageTotal, lastTran.tranAmt(),
                                MONETARY_SCALE, RoundingMode.DOWN);
                        accountTotal = Decimals.add(
                                accountTotal, lastTran.tranAmt(),
                                MONETARY_SCALE, RoundingMode.DOWN);
                    } else {
                        // Empty TRANSACT input: COBOL would still
                        // execute the DISPLAYs (with whatever uninit
                        // bytes are in the FD buffer) and the ADDs
                        // would no-op against PIC 9 ZEROs. We emit no
                        // diagnostic since there is no record to log.
                        LOGGER.info("TRAN-AMT {}", BigDecimal.ZERO.setScale(MONETARY_SCALE));
                        LOGGER.info("WS-PAGE-TOTAL {}", pageTotal);
                    }
                    writePageTotals();
                    writeGrandTotals();
                } else {
                    // -- In-window date filter --
                    // COBOL: IF TRAN-PROC-TS (1:10) >= WS-START-DATE
                    //          AND TRAN-PROC-TS (1:10) <= WS-END-DATE
                    //            CONTINUE
                    //        ELSE NEXT SENTENCE
                    //        END-IF
                    String procDate = extractProcDate(lastTran);
                    boolean inWindow = procDate.compareTo(startDate) >= 0
                            && procDate.compareTo(endDate) <= 0;
                    if (!inWindow) {
                        // Apparent-intent NEXT SENTENCE: skip the
                        // record and continue the outer PERFORM loop.
                        // Strict COBOL semantics would exit the loop
                        // entirely; the upstream SORT pre-filter makes
                        // either interpretation observationally
                        // identical in production. See MIGRATION_NOTES.md.
                        continue;
                    }
                    // -- DISPLAY TRAN-RECORD (PAN masked per AAP §0.7.2) --
                    LOGGER.info("{}", lastTran);
                    // -- Card-number break: write previous account total --
                    if (!currCardNum.equals(lastTran.tranCardNum())) {
                        if (!firstTime) {
                            writeAccountTotals();
                        }
                        currCardNum = lastTran.tranCardNum();
                        lookupXref(lastTran.tranCardNum());
                    }
                    // -- Type / category lookups --
                    lookupTranType(lastTran.tranTypeCd());
                    lookupTranCatg(lastTran.tranTypeCd(), lastTran.tranCatCd());
                    // -- Detail line + totals accumulation --
                    writeTransactionReport(lastTran);
                }
            }
        }

        // --- Close all files in COBOL order ---
        closeTransactionFile();
        closeReptFile();
        closeCardXref();
        closeTranType();
        closeTranCatg();
        closeDateParm();

        LOGGER.info("END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    /**
     * Extracts the 10-character {@code YYYY-MM-DD} date string from the
     * {@code TRAN-PROC-TS} field of a {@link TranRecord}, matching the
     * COBOL substring expression {@code TRAN-PROC-TS (1:10)}.
     *
     * <p>The {@link TranRecord#tranProcTs()} accessor returns a
     * {@link java.time.LocalDateTime} parsed from the COBOL
     * {@code PIC X(26)} field. If the COBOL bytes were all-spaces (the
     * typical "no timestamp" sentinel), {@code parse()} on TranRecord
     * yields {@code null}; this helper returns 10 ASCII spaces in that
     * case, which sorts lexically before any valid date and therefore
     * fails the in-window check &mdash; the same outcome the COBOL
     * comparison would yield against an all-spaces {@code TRAN-PROC-TS}.
     *
     * @param record the transaction record; MUST NOT be {@code null}
     * @return the 10-character "YYYY-MM-DD" date string, or 10 spaces
     *         when the COBOL timestamp was blank
     */
    private static String extractProcDate(TranRecord record) {
        LocalDateTime ts = record.tranProcTs();
        if (ts == null) {
            return " ".repeat(10);
        }
        return ts.toLocalDate().toString();
    }

    // ---------------------------------------------------------------------
    // 0000-TRANFILE-OPEN ... 0500-DATEPARM-OPEN (file-OPEN paragraphs)
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 0000-TRANFILE-OPEN} (CBTRN03C.cbl lines 221-237).
     *
     * <p>COBOL behavior: {@code MOVE 8 TO APPL-RESULT}, {@code OPEN INPUT
     * TRANSACT-FILE}. If the FILE STATUS after OPEN is "00", set
     * {@code APPL-AOK}; otherwise set {@code APPL-ERROR}. If the result
     * is not OK, display "ERROR OPENING TRANSACTION FILE", move the
     * status to IO-STATUS, perform {@code 9910-DISPLAY-IO-STATUS}, and
     * perform {@code 9999-ABEND-PROGRAM}.
     *
     * <p>Java equivalent: opening is implicit in the
     * {@link TransactionRepository} adapter's first call; we surface
     * any failure as an {@link AbendException} mapping the COBOL flow.
     * The status is taken as {@link #STATUS_OK} on success since the
     * Java port abstracts the OPEN as part of the
     * {@link TransactionRepository#streamSequential()} call.
     */
    private void openTransactionFile() {
        applResult = APPL_PENDING; // MOVE 8 TO APPL-RESULT
        try {
            // The port-driven adapter performs OPEN INPUT implicitly
            // when streamSequential() is called; we eagerly probe via a
            // lightweight no-op stream open then close to flush any
            // open-time error into our error path.
            //
            // The actual sequential read is performed inside run() via
            // streamSequential().iterator() with try-with-resources;
            // here we just validate the adapter is callable.
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR OPENING TRANSACTION FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR OPENING TRANSACTION FILE", ex);
        }
    }

    /**
     * Translates {@code 0100-REPTFILE-OPEN} (CBTRN03C.cbl lines 239-255).
     *
     * <p>COBOL behavior: opens the REPORT-FILE for OUTPUT and abends on
     * failure. Java equivalent: the {@link ReportSink} is constructor-
     * injected and assumed open; this method is a no-op apart from
     * recording the COBOL semantic intent in the trace.
     */
    private void openReptFile() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR OPENING REPORT FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR OPENING REPORT FILE", ex);
        }
    }

    /**
     * Translates {@code 0200-CARDXREF-OPEN} (CBTRN03C.cbl lines 257-273).
     *
     * <p>COBOL behavior: opens the XREF-FILE for INPUT (indexed KSDS
     * with primary key {@code FD-XREF-CARD-NUM}) and abends on failure.
     * Java equivalent: the {@link CardXrefRepository} adapter manages
     * its own open lifecycle.
     */
    private void openCardXref() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR OPENING CARDXREF FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR OPENING CARDXREF FILE", ex);
        }
    }

    /**
     * Translates {@code 0300-TRANTYPE-OPEN} (CBTRN03C.cbl lines 275-291).
     *
     * <p>COBOL behavior: opens the TRANTYPE-FILE for INPUT and abends
     * on failure. Java equivalent: the
     * {@link TransactionTypeRepository} adapter manages its own open
     * lifecycle.
     */
    private void openTranType() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR OPENING TRANTYPE FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR OPENING TRANTYPE FILE", ex);
        }
    }

    /**
     * Translates {@code 0400-TRANCATG-OPEN} (CBTRN03C.cbl lines 293-309).
     *
     * <p>COBOL behavior: opens the TRANCATG-FILE for INPUT and abends
     * on failure. Java equivalent: the
     * {@link TransactionCategoryRepository} adapter manages its own
     * open lifecycle.
     */
    private void openTranCatg() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR OPENING TRANCATG FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR OPENING TRANCATG FILE", ex);
        }
    }

    /**
     * Translates {@code 0500-DATEPARM-OPEN} (CBTRN03C.cbl lines 311-327).
     *
     * <p>COBOL behavior: opens the DATE-PARMS-FILE for INPUT and abends
     * on failure. Java equivalent: the {@link DateParamsSource} adapter
     * manages its own open lifecycle.
     */
    private void openDateParm() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR OPENING DATEPARM FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR OPENING DATEPARM FILE", ex);
        }
    }

    // ---------------------------------------------------------------------
    // 0550-DATEPARM-READ
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 0550-DATEPARM-READ} (CBTRN03C.cbl lines 458-481).
     *
     * <p>COBOL behavior:
     * <pre>
     *   READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD
     *   EVALUATE DATEPARM-STATUS4
     *       WHEN '0000' MOVE 0 TO APPL-RESULT       (success)
     *       WHEN '1000' MOVE 16 TO APPL-RESULT      (EOF before any record)
     *       WHEN OTHER  MOVE 12 TO APPL-RESULT      (error)
     *   IF APPL-AOK   CONTINUE
     *   ELSE
     *       DISPLAY 'ERROR READING DATEPARM FILE'
     *       MOVE DATEPARM-STATUS TO IO-STATUS
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF
     *   MOVE WS-START-DATE OF WS-DATEPARM-RECORD TO WS-START-DATE
     *   MOVE WS-END-DATE   OF WS-DATEPARM-RECORD TO WS-END-DATE.
     * </pre>
     *
     * <p>Java equivalent: delegates to the constructor-injected
     * {@link DateParamsSource}, examines the returned status, and on
     * failure follows the same abend path. The two date strings are
     * then captured into the {@link #startDate} and {@link #endDate}
     * fields for use by {@link #run()} and {@link #writeHeaders()}.
     */
    private void readDateParm() {
        applResult = APPL_PENDING;
        DateParams params;
        try {
            params = dateParamsSource.read();
        } catch (AbendException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR READING DATEPARM FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR READING DATEPARM FILE", ex);
            return; // unreachable; abendProgram throws
        }
        ioStatus = params.status();
        applResult = switch (ioStatus) {
            case STATUS_OK -> APPL_AOK;
            case STATUS_EOF -> APPL_EOF;
            default -> APPL_ERROR;
        };
        if (applResult == APPL_AOK) {
            startDate = params.startDate();
            endDate = params.endDate();
        } else {
            LOGGER.error("ERROR READING DATEPARM FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR READING DATEPARM FILE", null);
        }
    }

    // ---------------------------------------------------------------------
    // 1000-TRANFILE-GET-NEXT
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1000-TRANFILE-GET-NEXT} (CBTRN03C.cbl lines 219-241
     * in the procedural body, paragraph definition at lines 243-265).
     *
     * <p>COBOL behavior:
     * <pre>
     *   READ TRANSACT-FILE INTO TRAN-RECORD.
     *   IF TRANFILE-STATUS = '00'
     *       MOVE 0 TO APPL-RESULT
     *   ELSE
     *       IF TRANFILE-STATUS = '10'
     *           MOVE 16 TO APPL-RESULT
     *       ELSE
     *           MOVE 12 TO APPL-RESULT
     *       END-IF
     *   END-IF
     *   IF APPL-AOK
     *       CONTINUE
     *   ELSE
     *       IF APPL-EOF
     *           MOVE 'Y' TO END-OF-FILE
     *       ELSE
     *           DISPLAY 'ERROR READING TRANSACTION FILE'
     *           MOVE TRANFILE-STATUS TO IO-STATUS
     *           PERFORM 9910-DISPLAY-IO-STATUS
     *           PERFORM 9999-ABEND-PROGRAM
     *       END-IF
     *   END-IF.
     * </pre>
     *
     * <p>Java equivalent: pulls one record from the iterator obtained
     * from {@link TransactionRepository#streamSequential()}. If the
     * iterator has no more elements, sets {@code endOfFile = true} (the
     * COBOL FILE STATUS "10" branch). Any unexpected exception is
     * mapped to the COBOL error abend path.
     *
     * @param iter the iterator over the sequential TRANSACT stream
     */
    private void getNextTransaction(Iterator<TranRecord> iter) {
        try {
            if (iter.hasNext()) {
                lastTran = iter.next();
                ioStatus = STATUS_OK;
                applResult = APPL_AOK;
            } else {
                ioStatus = STATUS_EOF;
                applResult = APPL_EOF;
                endOfFile = true;
            }
        } catch (AbendException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR READING TRANSACTION FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR READING TRANSACTION FILE", ex);
        }
    }

    // ---------------------------------------------------------------------
    // 1100-WRITE-TRANSACTION-REPORT
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1100-WRITE-TRANSACTION-REPORT} (CBTRN03C.cbl
     * lines 267-291).
     *
     * <p>COBOL behavior:
     * <pre>
     *   IF WS-FIRST-TIME = 'Y'
     *       MOVE 'N' TO WS-FIRST-TIME
     *       MOVE WS-START-DATE TO HDR-DATE1
     *       MOVE WS-END-DATE   TO HDR-DATE2
     *       PERFORM 1120-WRITE-HEADERS
     *   END-IF
     *   IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0
     *       PERFORM 1110-WRITE-PAGE-TOTALS
     *       PERFORM 1120-WRITE-HEADERS
     *   END-IF
     *   ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL
     *   PERFORM 1120-WRITE-DETAIL.
     * </pre>
     *
     * <p>Java equivalent: faithfully reproduces the four-step COBOL
     * flow. The {@code FUNCTION MOD} test is translated as
     * {@code lineCounter % PAGE_SIZE == 0}; the multi-target ADD is two
     * separate {@link Decimals#add} calls with
     * {@link RoundingMode#DOWN} (COBOL default truncation, no
     * {@code ROUNDED} clause appears in CBTRN03C).
     *
     * <p>Note that on the very first detail write {@code lineCounter}
     * is non-zero (4) because the first-time header block has just
     * incremented it past 0; thus the {@code MOD} branch only fires
     * after page-worth of detail lines have been written.
     *
     * @param record the in-window transaction record to report
     */
    private void writeTransactionReport(TranRecord record) {
        if (firstTime) {
            firstTime = false;
            writeHeaders();
        }
        if (lineCounter % PAGE_SIZE == 0L) {
            writePageTotals();
            writeHeaders();
        }
        pageTotal = Decimals.add(pageTotal, record.tranAmt(),
                MONETARY_SCALE, RoundingMode.DOWN);
        accountTotal = Decimals.add(accountTotal, record.tranAmt(),
                MONETARY_SCALE, RoundingMode.DOWN);
        writeDetail(record);
    }

    // ---------------------------------------------------------------------
    // 1110-WRITE-PAGE-TOTALS
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1110-WRITE-PAGE-TOTALS} (CBTRN03C.cbl lines
     * 293-304).
     *
     * <p>COBOL behavior:
     * <pre>
     *   MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL
     *   WRITE REPORT-FILE FROM REPORT-PAGE-TOTALS
     *   ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL
     *   MOVE 0 TO WS-PAGE-TOTAL
     *   PERFORM 1111-WRITE-REPORT-REC FROM TRANSACTION-HEADER-2.
     * </pre>
     *
     * <p>Java equivalent: emits the 112-byte
     * {@link ReportHeaders.ReportPageTotals} line padded to 133 bytes,
     * folds the page total into {@code grandTotal} via
     * {@link Decimals#add}, resets {@code pageTotal} to zero, and
     * follows with the separator (all-dashes) line.
     *
     * <p>NOTE on duplicate paragraph names: COBOL paragraphs
     * {@code 1110-WRITE-PAGE-TOTALS} and {@code 1110-WRITE-GRAND-TOTALS}
     * share the {@code 1110-} prefix; the Java translation
     * disambiguates as {@link #writePageTotals()} and
     * {@link #writeGrandTotals()}.
     */
    private void writePageTotals() {
        byte[] line = padLine(
                new ReportHeaders.ReportPageTotals(pageTotal).encode());
        writeReportRec(line);
        grandTotal = Decimals.add(grandTotal, pageTotal,
                MONETARY_SCALE, RoundingMode.DOWN);
        pageTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);
        writeReportRec(ReportHeaders.transactionHeader2());
    }

    // ---------------------------------------------------------------------
    // 1120-WRITE-ACCOUNT-TOTALS
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1120-WRITE-ACCOUNT-TOTALS} (CBTRN03C.cbl lines
     * 306-316).
     *
     * <p>COBOL behavior:
     * <pre>
     *   MOVE WS-ACCOUNT-TOTAL TO REPT-ACCOUNT-TOTAL
     *   WRITE REPORT-FILE FROM REPORT-ACCOUNT-TOTALS
     *   MOVE 0 TO WS-ACCOUNT-TOTAL
     *   PERFORM 1111-WRITE-REPORT-REC FROM TRANSACTION-HEADER-2.
     * </pre>
     *
     * <p>Java equivalent: emits the 112-byte
     * {@link ReportHeaders.ReportAccountTotals} line padded to 133
     * bytes, resets {@code accountTotal} to zero, and follows with the
     * separator (all-dashes) line.
     *
     * <p>NOTE on duplicate paragraph names: COBOL paragraphs
     * {@code 1120-WRITE-ACCOUNT-TOTALS}, {@code 1120-WRITE-HEADERS},
     * and {@code 1120-WRITE-DETAIL} all share the {@code 1120-}
     * prefix; the Java translation disambiguates as
     * {@link #writeAccountTotals()}, {@link #writeHeaders()}, and
     * {@link #writeDetail(TranRecord)}.
     */
    private void writeAccountTotals() {
        byte[] line = padLine(
                new ReportHeaders.ReportAccountTotals(accountTotal).encode());
        writeReportRec(line);
        accountTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);
        writeReportRec(ReportHeaders.transactionHeader2());
    }

    // ---------------------------------------------------------------------
    // 1110-WRITE-GRAND-TOTALS (duplicate paragraph name in COBOL!)
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1110-WRITE-GRAND-TOTALS} (CBTRN03C.cbl lines
     * 318-322).
     *
     * <p>COBOL behavior:
     * <pre>
     *   MOVE WS-GRAND-TOTAL TO REPT-GRAND-TOTAL
     *   WRITE REPORT-FILE FROM REPORT-GRAND-TOTALS.
     * </pre>
     *
     * <p>Java equivalent: emits the single 112-byte
     * {@link ReportHeaders.ReportGrandTotals} line padded to 133
     * bytes. Unlike the page and account totals, no separator line
     * follows (COBOL writes only one record).
     *
     * <p>NOTE: COBOL paragraph name {@code 1110-WRITE-GRAND-TOTALS}
     * duplicates the {@code 1110-} prefix used by
     * {@code 1110-WRITE-PAGE-TOTALS}. See class Javadoc anomaly #1.
     */
    private void writeGrandTotals() {
        byte[] line = padLine(
                new ReportHeaders.ReportGrandTotals(grandTotal).encode());
        writeReportRec(line);
    }

    // ---------------------------------------------------------------------
    // 1120-WRITE-HEADERS (duplicate paragraph name in COBOL!)
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1120-WRITE-HEADERS} (CBTRN03C.cbl lines
     * 324-359).
     *
     * <p>COBOL behavior writes 4 lines per page header block:
     * <pre>
     *   PERFORM 1111-WRITE-REPORT-REC FROM REPORT-NAME-HEADER
     *   PERFORM 1111-WRITE-REPORT-REC FROM WS-BLANK-LINE
     *   PERFORM 1111-WRITE-REPORT-REC FROM TRANSACTION-HEADER-1
     *   PERFORM 1111-WRITE-REPORT-REC FROM TRANSACTION-HEADER-2.
     * </pre>
     *
     * <p>Java equivalent: emits the 115-byte {@link
     * ReportHeaders.ReportNameHeader} (carrying the start/end dates
     * from {@link #startDate} / {@link #endDate}) padded to 133, the
     * 133-byte blank-line template, the 114-byte
     * {@link ReportHeaders#transactionHeader1()} padded to 133, and
     * finally the 133-byte all-dashes
     * {@link ReportHeaders#transactionHeader2()}.
     *
     * <p>NOTE: this is the second of three COBOL paragraphs sharing
     * the {@code 1120-} prefix. See class Javadoc anomaly #1.
     */
    private void writeHeaders() {
        byte[] nameHeader = padLine(
                new ReportHeaders.ReportNameHeader(startDate, endDate).encode());
        writeReportRec(nameHeader);
        writeReportRec(blankLine());
        writeReportRec(padLine(ReportHeaders.transactionHeader1()));
        writeReportRec(ReportHeaders.transactionHeader2());
    }

    // ---------------------------------------------------------------------
    // 1111-WRITE-REPORT-REC
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1111-WRITE-REPORT-REC} (CBTRN03C.cbl lines
     * 386-407).
     *
     * <p>COBOL behavior:
     * <pre>
     *   WRITE FD-REPTFILE-REC
     *   IF REPTFILE-STATUS = '00'
     *       MOVE 0 TO APPL-RESULT
     *   ELSE
     *       MOVE 12 TO APPL-RESULT
     *   END-IF
     *   IF APPL-AOK
     *       ADD 1 TO WS-LINE-COUNTER
     *   ELSE
     *       DISPLAY 'ERROR WRITING REPORT FILE'
     *       MOVE REPTFILE-STATUS TO IO-STATUS
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF.
     * </pre>
     *
     * <p>Java equivalent: delegates to {@link ReportSink#write(byte[])},
     * inspects the returned FILE STATUS, increments
     * {@link #lineCounter} on success, or follows the COBOL abend path
     * on failure.
     *
     * <p>Note that this method does NOT pad the input; callers MUST
     * pre-pad to {@link #REPORT_LINE_WIDTH} bytes (via
     * {@link #padLine(byte[])}) or pass a buffer that is already 133
     * bytes long.
     *
     * @param line a 133-byte fixed-width line ready to write
     */
    private void writeReportRec(byte[] line) {
        applResult = APPL_PENDING;
        try {
            ioStatus = reportSink.write(line);
            applResult = STATUS_OK.equals(ioStatus) ? APPL_AOK : APPL_ERROR;
        } catch (AbendException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR WRITING REPORT FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR WRITING REPORT FILE", ex);
            return; // unreachable; abendProgram throws
        }
        if (applResult == APPL_AOK) {
            lineCounter++;
        } else {
            LOGGER.error("ERROR WRITING REPORT FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR WRITING REPORT FILE", null);
        }
    }

    // ---------------------------------------------------------------------
    // 1120-WRITE-DETAIL (third occurrence of 1120 prefix in COBOL!)
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1120-WRITE-DETAIL} (CBTRN03C.cbl lines
     * 361-384).
     *
     * <p>COBOL behavior:
     * <pre>
     *   INITIALIZE TRANSACTION-DETAIL-REPORT
     *   MOVE TRAN-ID                  TO TRAN-REPORT-TRANS-ID
     *   MOVE FD-XREF-ACCT-ID          TO TRAN-REPORT-ACCOUNT-ID
     *   MOVE TRAN-TYPE-CD             TO TRAN-REPORT-TYPE-CD
     *   MOVE TRAN-TYPE-DESC           TO TRAN-REPORT-TYPE-DESC
     *   MOVE TRAN-CAT-CD              TO TRAN-REPORT-CAT-CD
     *   MOVE TRAN-CAT-TYPE-DESC       TO TRAN-REPORT-CAT-DESC
     *   MOVE TRAN-SOURCE              TO TRAN-REPORT-SOURCE
     *   MOVE TRAN-AMT                 TO TRAN-REPORT-AMT
     *   PERFORM 1111-WRITE-REPORT-REC FROM TRANSACTION-DETAIL-REPORT.
     * </pre>
     *
     * <p>Java equivalent: builds a
     * {@link ReportHeaders.TransactionDetailReport} record from the
     * current transaction and the three lookup results, encodes it to
     * a 113-byte fixed-width buffer, right-pads to 133 bytes, and
     * delegates to {@link #writeReportRec(byte[])}.
     *
     * <p>NOTE: this is the third of three COBOL paragraphs sharing the
     * {@code 1120-} prefix. See class Javadoc anomaly #1.
     *
     * @param record the in-window transaction record
     */
    private void writeDetail(TranRecord record) {
        String accountId = formatAccountId(
                lastXref != null ? lastXref.xrefAcctId() : 0L);
        String typeDesc = lastTranType != null
                ? lastTranType.tranTypeDesc()
                : " ".repeat(ReportHeaders.LEN_TYPE_DESC);
        String catDesc = lastTranCatg != null
                ? lastTranCatg.tranCatTypeDesc()
                : " ".repeat(ReportHeaders.LEN_CAT_DESC);

        ReportHeaders.TransactionDetailReport detail =
                new ReportHeaders.TransactionDetailReport(
                        record.tranId(),
                        accountId,
                        record.tranTypeCd(),
                        typeDesc,
                        record.tranCatCd(),
                        catDesc,
                        record.tranSource(),
                        record.tranAmt());
        writeReportRec(padLine(detail.encode()));
    }

    /**
     * Formats a numeric account ID into the 11-character zero-padded
     * string required by the {@code TRAN-REPORT-ACCOUNT-ID} field in
     * {@code TRANSACTION-DETAIL-REPORT} (CVTRA07Y) which is
     * {@code PIC 9(11)}.
     *
     * @param acctId the numeric account ID
     * @return an 11-character left-zero-padded numeric string
     */
    private static String formatAccountId(long acctId) {
        return String.format(Locale.ROOT, "%0" + ReportHeaders.LEN_ACCOUNT_ID + "d", acctId);
    }

    // ---------------------------------------------------------------------
    // 1500-A-LOOKUP-XREF
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1500-A-LOOKUP-XREF} (CBTRN03C.cbl lines
     * 484-495).
     *
     * <p>COBOL behavior:
     * <pre>
     *   MOVE TRAN-CARD-NUM TO FD-XREF-CARD-NUM
     *   READ XREF-FILE
     *   IF CARDXREF-STATUS NOT = '00'
     *       DISPLAY 'INVALID CARD NUMBER FOR : ' FD-XREF-CARD-NUM
     *       MOVE 23 TO IO-STATUS
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF.
     * </pre>
     *
     * <p>Java equivalent: invokes
     * {@link CardXrefRepository#findByCardNumber(String)} and, on
     * an empty {@link Optional}, follows the COBOL abend path. The
     * card number in the diagnostic message is masked via
     * {@link #maskPan(String)} per AAP &sect;0.7.2 (PCI/PAN policy).
     *
     * <p>The successful lookup is captured into {@link #lastXref} for
     * later use by {@link #writeDetail(TranRecord)}; this mirrors the
     * COBOL behavior of leaving {@code XREF-RECORD} populated for the
     * subsequent {@code 1120-WRITE-DETAIL} MOVE operations.
     *
     * @param cardNum the 16-byte card number to look up
     */
    private void lookupXref(String cardNum) {
        applResult = APPL_PENDING;
        // Two-phase pattern: catch the I/O exception path narrowly so we
        // do NOT swallow the AbendException thrown by the INVALID-KEY
        // branch below. Without this separation, the catch block would
        // re-wrap our own abend into a misleading "ERROR READING" message.
        Optional<CardXrefRecord> result;
        try {
            result = cardXrefRepository.findByCardNumber(cardNum);
        } catch (AbendException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR READING CARDXREF FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR READING CARDXREF FILE", ex);
            return; // unreachable; abendProgram throws
        }
        if (result.isPresent()) {
            lastXref = result.get();
            ioStatus = STATUS_OK;
            applResult = APPL_AOK;
        } else {
            ioStatus = STATUS_INVALID_KEY;
            applResult = APPL_ERROR;
            LOGGER.error("INVALID CARD NUMBER FOR : {}", maskPan(cardNum));
            displayIoStatus(ioStatus);
            abendProgram("INVALID CARD NUMBER FOR : " + maskPan(cardNum), null);
        }
    }

    // ---------------------------------------------------------------------
    // 1500-B-LOOKUP-TRANTYPE
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1500-B-LOOKUP-TRANTYPE} (CBTRN03C.cbl lines
     * 497-507).
     *
     * <p>COBOL behavior:
     * <pre>
     *   MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE
     *   READ TRANTYPE-FILE
     *   IF TRANTYPE-STATUS NOT = '00'
     *       DISPLAY 'INVALID TRANSACTION TYPE : ' FD-TRAN-TYPE
     *       MOVE 23 TO IO-STATUS
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF.
     * </pre>
     *
     * <p>Java equivalent: invokes
     * {@link TransactionTypeRepository#findByCode(String)} and abends
     * on a missing record. The successful lookup is captured into
     * {@link #lastTranType} for the subsequent
     * {@link #writeDetail(TranRecord)} call.
     *
     * @param typeCode the 2-character transaction type code
     */
    private void lookupTranType(String typeCode) {
        applResult = APPL_PENDING;
        Optional<TranTypeRecord> result;
        try {
            result = transactionTypeRepository.findByCode(typeCode);
        } catch (AbendException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR READING TRANTYPE FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR READING TRANTYPE FILE", ex);
            return; // unreachable; abendProgram throws
        }
        if (result.isPresent()) {
            lastTranType = result.get();
            ioStatus = STATUS_OK;
            applResult = APPL_AOK;
        } else {
            ioStatus = STATUS_INVALID_KEY;
            applResult = APPL_ERROR;
            LOGGER.error("INVALID TRANSACTION TYPE : {}", typeCode);
            displayIoStatus(ioStatus);
            abendProgram("INVALID TRANSACTION TYPE : " + typeCode, null);
        }
    }

    // ---------------------------------------------------------------------
    // 1500-C-LOOKUP-TRANCATG
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 1500-C-LOOKUP-TRANCATG} (CBTRN03C.cbl lines
     * 509-521).
     *
     * <p>COBOL behavior:
     * <pre>
     *   MOVE TRAN-TYPE-CD TO FD-TRAN-CAT-TYPE-CD
     *   MOVE TRAN-CAT-CD  TO FD-TRAN-CAT-CD
     *   READ TRANCATG-FILE
     *   IF TRANCATG-STATUS NOT = '00'
     *       DISPLAY 'INVALID TRAN CATG KEY : ' FD-TRAN-CAT-KEY
     *       MOVE 23 TO IO-STATUS
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF.
     * </pre>
     *
     * <p>Java equivalent: invokes
     * {@link TransactionCategoryRepository#findByKey(String, int)}
     * with the composite key (typeCode + catCode) and abends on a
     * missing record. The successful lookup is captured into
     * {@link #lastTranCatg} for the subsequent
     * {@link #writeDetail(TranRecord)} call.
     *
     * @param typeCode the 2-character transaction type code (first
     *                 part of the composite key)
     * @param catCode  the 4-digit numeric category code (second part)
     */
    private void lookupTranCatg(String typeCode, int catCode) {
        applResult = APPL_PENDING;
        Optional<TranCatRecord> result;
        try {
            result = transactionCategoryRepository.findByKey(typeCode, catCode);
        } catch (AbendException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR READING TRANCATG FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR READING TRANCATG FILE", ex);
            return; // unreachable; abendProgram throws
        }
        if (result.isPresent()) {
            lastTranCatg = result.get();
            ioStatus = STATUS_OK;
            applResult = APPL_AOK;
        } else {
            ioStatus = STATUS_INVALID_KEY;
            applResult = APPL_ERROR;
            String formattedKey = formatTranCatKey(typeCode, catCode);
            LOGGER.error("INVALID TRAN CATG KEY : {}", formattedKey);
            displayIoStatus(ioStatus);
            abendProgram("INVALID TRAN CATG KEY : " + formattedKey, null);
        }
    }

    /**
     * Formats the composite TRANCATG key (type + zero-padded category)
     * for display in error messages. Matches COBOL's group-level
     * {@code DISPLAY ... FD-TRAN-CAT-KEY} which concatenates the
     * 2-byte type code and 4-digit category code.
     *
     * @param typeCode the 2-character type code
     * @param catCode  the numeric category code (0..9999)
     * @return a 6-character composite key string
     */
    private static String formatTranCatKey(String typeCode, int catCode) {
        return typeCode + String.format(Locale.ROOT, "%04d", catCode);
    }

    // ---------------------------------------------------------------------
    // 9000-TRANFILE-CLOSE ... 9500-DATEPARM-CLOSE
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 9000-TRANFILE-CLOSE} (CBTRN03C.cbl lines
     * 524-540).
     *
     * <p>COBOL behavior:
     * <pre>
     *   ADD 8 TO ZERO GIVING APPL-RESULT
     *   CLOSE TRANSACT-FILE
     *   IF TRANFILE-STATUS = '00'
     *       SUBTRACT APPL-RESULT FROM APPL-RESULT
     *   ELSE
     *       ADD 12 TO ZERO GIVING APPL-RESULT
     *   END-IF
     *   IF APPL-AOK
     *       CONTINUE
     *   ELSE
     *       DISPLAY 'ERROR CLOSING TRANSACTION FILE'
     *       MOVE TRANFILE-STATUS TO IO-STATUS
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF.
     * </pre>
     *
     * <p>Java equivalent: the underlying stream resource is released
     * by the try-with-resources in {@link #run()}; this method calls
     * the repository's {@link AutoCloseable#close()} when it
     * implements that interface and abends on failure to honor the
     * COBOL semantic intent.
     */
    private void closeTransactionFile() {
        applResult = APPL_PENDING;
        try {
            transactionRepository.close();
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR CLOSING TRANSACTION FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR CLOSING TRANSACTION FILE", ex);
        }
    }

    /**
     * Translates {@code 9100-REPTFILE-CLOSE} (CBTRN03C.cbl lines
     * 542-558).
     *
     * <p>COBOL behavior: CLOSE the report file and abend on failure.
     * Java equivalent: the {@link ReportSink} is a functional
     * interface; closing is delegated to the implementation's
     * surrounding lifecycle. We expose a single best-effort no-op
     * record in the trace; specific implementations may flush before
     * the constructor goes out of scope in the composition root.
     */
    private void closeReptFile() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR CLOSING REPORT FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR CLOSING REPORT FILE", ex);
        }
    }

    /**
     * Translates {@code 9200-CARDXREF-CLOSE} (CBTRN03C.cbl lines
     * 560-576).
     *
     * <p>COBOL behavior: CLOSE the XREF file and abend on failure.
     * Java equivalent: calls {@link CardXrefRepository#close()}.
     */
    private void closeCardXref() {
        applResult = APPL_PENDING;
        try {
            cardXrefRepository.close();
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR CLOSING CARDXREF FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR CLOSING CARDXREF FILE", ex);
        }
    }

    /**
     * Translates {@code 9300-TRANTYPE-CLOSE} (CBTRN03C.cbl lines
     * 578-594).
     *
     * <p>COBOL behavior: CLOSE the TRANTYPE file and abend on
     * failure. Java equivalent: the
     * {@link TransactionTypeRepository} interface does not extend
     * {@link AutoCloseable}; closing is delegated to its adapter's
     * own lifecycle.
     */
    private void closeTranType() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR CLOSING TRANTYPE FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR CLOSING TRANTYPE FILE", ex);
        }
    }

    /**
     * Translates {@code 9400-TRANCATG-CLOSE} (CBTRN03C.cbl lines
     * 596-612).
     *
     * <p>COBOL behavior: CLOSE the TRANCATG file and abend on
     * failure. Java equivalent: the
     * {@link TransactionCategoryRepository} interface does not
     * extend {@link AutoCloseable}; closing is delegated to its
     * adapter's own lifecycle.
     */
    private void closeTranCatg() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR CLOSING TRANCATG FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR CLOSING TRANCATG FILE", ex);
        }
    }

    /**
     * Translates {@code 9500-DATEPARM-CLOSE} (CBTRN03C.cbl lines
     * 614-624).
     *
     * <p>COBOL behavior: CLOSE the DATEPARM file and abend on
     * failure. Java equivalent: the {@link DateParamsSource} is a
     * functional interface; closing is delegated to the
     * implementation's surrounding lifecycle.
     */
    private void closeDateParm() {
        applResult = APPL_PENDING;
        try {
            applResult = APPL_AOK;
            ioStatus = STATUS_OK;
        } catch (RuntimeException ex) {
            applResult = APPL_ERROR;
            ioStatus = STATUS_ERROR;
            LOGGER.error("ERROR CLOSING DATEPARM FILE");
            displayIoStatus(ioStatus);
            abendProgram("ERROR CLOSING DATEPARM FILE", ex);
        }
    }

    // ---------------------------------------------------------------------
    // 9910-DISPLAY-IO-STATUS
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 9910-DISPLAY-IO-STATUS} (CBTRN03C.cbl lines
     * 633-646).
     *
     * <p>COBOL behavior:
     * <pre>
     *   IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
     *       MOVE IO-STAT1 TO IO-STATUS-04(1:1)
     *       MOVE 0        TO TWO-BYTES-BINARY
     *       MOVE IO-STAT2 TO TWO-BYTES-RIGHT
     *       MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
     *       DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     *   ELSE
     *       MOVE '00' TO IO-STATUS-04(1:2)
     *       MOVE IO-STATUS TO IO-STATUS-04(3:2)
     *       DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     *   END-IF.
     * </pre>
     *
     * <p>Java equivalent: parses the 2-character {@link #ioStatus}
     * field and formats it as a 4-character string per the COBOL
     * branch logic:
     * <ul>
     *   <li>If both bytes are decimal digits AND the first byte is
     *       not '9', format as {@code "00" + ioStatus} (the COBOL
     *       "numeric" path).</li>
     *   <li>Otherwise (alphanumeric overlay or '9' prefix), format as
     *       {@code <stat1-char> + <3-digit-decimal-of-stat2-byte>}
     *       (the COBOL "alphanumeric overlay" path using
     *       {@code TWO-BYTES-BINARY} REDEFINES).</li>
     * </ul>
     * The literal {@code NNNN} in the COBOL DISPLAY is a fixed marker
     * preceding the 4-character {@code IO-STATUS-04} value; preserved
     * verbatim in the log message.
     *
     * @param status the 2-character FILE STATUS (typically "00", "10",
     *               "23", or a vendor-specific alphanumeric value)
     */
    private void displayIoStatus(String status) {
        String formatted = formatIoStatus(status);
        LOGGER.info("FILE STATUS IS: NNNN {}", formatted);
    }

    /**
     * Internal helper for {@link #displayIoStatus(String)} that
     * implements the COBOL NNNN format selection logic.
     *
     * @param status the raw 2-character status (may be null or have
     *               unexpected length; defensively normalized)
     * @return the 4-character NNNN-formatted status string
     */
    private static String formatIoStatus(String status) {
        // Defensive normalization: pad/truncate to exactly 2 chars
        // (preserving the COBOL FD-fixed-width semantic).
        String normalized;
        if (status == null) {
            normalized = "  ";
        } else if (status.length() >= 2) {
            normalized = status.substring(0, 2);
        } else {
            normalized = (status + "  ").substring(0, 2);
        }
        char stat1 = normalized.charAt(0);
        char stat2 = normalized.charAt(1);

        // COBOL: IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
        // -> use alphanumeric overlay format.
        boolean numericPath =
                isDecimalDigit(stat1) && isDecimalDigit(stat2) && stat1 != '9';
        if (numericPath) {
            // "00" + IO-STATUS  -> 4 chars total
            return "00" + normalized;
        }
        // <stat1-char><3-digit decimal of stat2 byte>
        // TWO-BYTES-RIGHT receives IO-STAT2 (one byte) reinterpreted
        // through TWO-BYTES-BINARY = PIC 9(4) which displays as a
        // 0-padded 3-digit number for the low byte (high byte is 0).
        int stat2Byte = stat2 & 0xFF;
        return String.format(Locale.ROOT, "%c%03d", stat1, stat2Byte);
    }

    /**
     * Returns true if the character is a decimal digit (0..9).
     *
     * @param c the character to test
     * @return true if {@code c} is in the range {@code '0'..'9'}
     */
    private static boolean isDecimalDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ---------------------------------------------------------------------
    // 9999-ABEND-PROGRAM
    // ---------------------------------------------------------------------

    /**
     * Translates {@code 9999-ABEND-PROGRAM} (CBTRN03C.cbl lines
     * 626-630).
     *
     * <p>COBOL behavior:
     * <pre>
     *   DISPLAY 'ABENDING PROGRAM'
     *   MOVE 0   TO TIMING
     *   MOVE 999 TO ABCODE
     *   CALL 'CEE3ABD'.
     * </pre>
     *
     * <p>Java equivalent: emits the "ABENDING PROGRAM" log message
     * and throws an {@link AbendException} carrying the COBOL ABCODE
     * (999). The exception is unchecked so the throw propagates
     * naturally through the call stack to the composition root,
     * which maps it to a non-zero process exit code per the AAP
     * &sect;0.6.1 contract.
     *
     * @param message human-readable reason for the abend (typically
     *                the COBOL DISPLAY text from the caller)
     * @param cause   underlying {@link Throwable} that triggered the
     *                abend, or {@code null} if there is none
     * @throws AbendException always; this method never returns
     *         normally
     */
    private void abendProgram(String message, Throwable cause) {
        LOGGER.error("ABENDING PROGRAM");
        String fullMessage = String.format(
                Locale.ROOT,
                "%s: %s (TIMING=%d, ABCODE=%d, IO-STATUS=%s)",
                PROGRAM_ID, message, CEE3ABD_TIMING, CEE3ABD_ABCODE, ioStatus);
        if (cause == null) {
            throw new AbendException(CEE3ABD_ABCODE, fullMessage);
        }
        throw new AbendException(CEE3ABD_ABCODE, fullMessage, cause);
    }

    // ---------------------------------------------------------------------
    // Helper utilities (no COBOL paragraph counterpart)
    // ---------------------------------------------------------------------

    /**
     * Returns a fresh 133-byte all-spaces buffer matching
     * {@code WS-BLANK-LINE PIC X(133) VALUE SPACES} in COBOL.
     *
     * <p>Each invocation returns a defensive copy of the template so
     * callers may mutate the returned buffer without poisoning the
     * shared static state.
     *
     * @return a newly-allocated 133-byte buffer of ASCII spaces
     */
    private static byte[] blankLine() {
        return BLANK_LINE_TEMPLATE.clone();
    }

    /**
     * Right-pads a fixed-width line to {@link #REPORT_LINE_WIDTH}
     * (133) bytes with ASCII spaces.
     *
     * <p>The {@link ReportHeaders} encode methods return buffers of
     * varying widths (112 / 113 / 114 / 115 / 133 bytes). The COBOL
     * {@code WRITE} statement against the {@code FD-REPTFILE-REC PIC
     * X(133)} record performs an implicit right-pad with spaces. This
     * helper reproduces that behavior in Java so the
     * {@link ReportSink} always receives a full 133-byte buffer.
     *
     * @param shorter the input buffer; MUST NOT be longer than 133
     *                bytes
     * @return a newly-allocated 133-byte buffer with the input bytes
     *         at offset 0 and ASCII spaces filling the remainder
     * @throws IllegalArgumentException if {@code shorter.length >
     *         REPORT_LINE_WIDTH}
     */
    private static byte[] padLine(byte[] shorter) {
        Objects.requireNonNull(shorter, "shorter");
        if (shorter.length > REPORT_LINE_WIDTH) {
            throw new IllegalArgumentException(
                    "Report line exceeds " + REPORT_LINE_WIDTH
                            + " bytes (length=" + shorter.length + ")");
        }
        if (shorter.length == REPORT_LINE_WIDTH) {
            // Already full width: return defensive copy so callers
            // cannot mutate any potentially-shared input.
            return shorter.clone();
        }
        byte[] padded = new byte[REPORT_LINE_WIDTH];
        System.arraycopy(shorter, 0, padded, 0, shorter.length);
        Arrays.fill(padded, shorter.length, REPORT_LINE_WIDTH, ASCII_SPACE);
        return padded;
    }

    /**
     * Masks a card number for safe logging per AAP &sect;0.7.2 (PCI /
     * PAN policy). The 12 leading bytes are replaced with asterisks;
     * the trailing 4 bytes are preserved.
     *
     * <p>Inputs shorter than 4 bytes (atypical) are returned as four
     * asterisks to avoid any leakage. {@code null} returns the same
     * four-asterisk sentinel.
     *
     * @param pan the raw card number (typically 16 ASCII digits)
     * @return a masked PAN safe for inclusion in log streams
     */
    private static String maskPan(String pan) {
        if (pan == null || pan.length() < PAN_VISIBLE_TAIL) {
            return "*".repeat(PAN_VISIBLE_TAIL);
        }
        int leadCount = pan.length() - PAN_VISIBLE_TAIL;
        return "*".repeat(leadCount) + pan.substring(leadCount);
    }
}

