/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.transaction;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.commarea.PgmContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Java translation of the {@code COTRN00C} CICS online program at
 * {@code app/cbl/COTRN00C.cbl} ("List Transactions from TRANSACT file").
 *
 * <h2>Program purpose</h2>
 * <p>Displays 10 transaction summaries per page (TRAN-ID, TRAN-ORIG-TS date,
 * TRAN-DESC, TRAN-AMT). ENTER repositions and pages forward; PF7 pages
 * backward; PF8 pages forward; selecting a row with 'S' XCTLs to COTRN01C
 * for detail view.
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:  COTRN00C
 *   WS-PGMNAME:  'COTRN00C'
 *   WS-TRANID:   'CT00'
 * </pre>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: EIBCALEN check, first-time vs re-enter dispatch,
 *       AID-key EVALUATE (ENTER / PF3 / PF7 / PF8 / OTHER).</li>
 *   <li>{@code PROCESS-ENTER-KEY}: scan SEL0001..SEL0010 for 'S'/'s'; on
 *       match, XCTL to COTRN01C with the selected TRAN-ID; on invalid SEL,
 *       error "Invalid selection. Valid value is S"; otherwise validate
 *       TRNIDINI is numeric and forward-page.</li>
 *   <li>{@code PROCESS-PF7-KEY}: backward-paginate from CDEMO-CT00-TRNID-FIRST;
 *       if at page 1, "You are already at the top of the page...".</li>
 *   <li>{@code PROCESS-PF8-KEY}: forward-paginate from CDEMO-CT00-TRNID-LAST;
 *       if NEXT-PAGE-NO, "You are already at the bottom of the page...".</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} / {@code PROCESS-PAGE-BACKWARD}:
 *       STARTBR/READNEXT/READPREV/ENDBR translated to stream-based
 *       sequential scan + skip/take.</li>
 *   <li>{@code POPULATE-TRAN-DATA} / {@code INITIALIZE-TRAN-DATA}: 10-row
 *       slot-population via index lookup.</li>
 *   <li>{@code STARTBR-TRANSACT-FILE} / {@code READNEXT-TRANSACT-FILE} /
 *       {@code READPREV-TRANSACT-FILE} / {@code ENDBR-TRANSACT-FILE}.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN}: XCTL with COMEN01C default.</li>
 * </ul>
 *
 * <h2>Pagination strategy</h2>
 * <p>The COBOL VSAM STARTBR/READNEXT/READPREV cursor model is translated
 * into a stream-based scan: each PF7/PF8/ENTER call re-opens the
 * {@link TransactionRepository#streamSequential()} cursor, skips to the
 * positioning key, and collects up to 10 records.
 *
 * <h2>Pagination context (CDEMO-CT00-INFO)</h2>
 * <p>The COBOL program persists pagination state via CDEMO-CT00-PAGE-NUM,
 * CDEMO-CT00-TRNID-FIRST, CDEMO-CT00-TRNID-LAST in the commarea extension.
 * Since {@link CardDemoCommarea} does not model these, they are exposed as
 * a {@link PageState} parameter that the caller round-trips.
 */
@CobolProgram(
        value = "COTRN00C",
        sourcePath = "app/cbl/COTRN00C.cbl",
        notes = "Transaction list with PF7/PF8 pagination. STARTBR/READNEXT/"
                + "READPREV translated to stream-based sequential scan with "
                + "skip/take. Pagination state via separate PageState parameter."
)
public final class CoTrn00C {

    private static final Logger log = LoggerFactory.getLogger(CoTrn00C.class);

    public static final String PROGRAM_ID = "COTRN00C";
    public static final String TRANSACTION_ID = "CT00";

    public static final String TITLE_01 = "AWS Mainframe Modernization";
    public static final String TITLE_02 = "CardDemo";
    public static final int ROWS_PER_PAGE = 10;

    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";
    /** "Invalid selection. Valid value is S" preserved verbatim per AAP §0.7.1. */
    public static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";
    /** "Tran ID must be Numeric ..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_TRAN_NUMERIC = "Tran ID must be Numeric ...";
    /** "You are already at the top of the page..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_AT_TOP = "You are already at the top of the page...";
    /** "You are already at the bottom of the page..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_AT_BOTTOM = "You are already at the bottom of the page...";
    /** "You are at the top of the page..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_TOP = "You are at the top of the page...";
    /** "You have reached the bottom of the page..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";
    /** "You have reached the top of the page..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_REACHED_TOP = "You have reached the top of the page...";
    /** "Unable to lookup transaction..." preserved verbatim per AAP §0.7.1 (lowercase 't'!). */
    public static final String MSG_LOOKUP_ERROR = "Unable to lookup transaction...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TransactionRepository transactions;
    private final ProgramRegistry programRegistry;

    public CoTrn00C(TransactionRepository transactions, ProgramRegistry programRegistry) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    /**
     * AID keys consumed by COTRN00C. Translates {@code EVALUATE EIBAID}
     * at COBOL lines 122-138.
     */
    public enum AidKey {
        /** {@code DFHENTER}: select row or position to TRNIDINI and forward-page. */
        ENTER,
        /** {@code DFHPF3}: XCTL to COMEN01C. */
        PF03_BACK,
        /** {@code DFHPF7}: page backward. */
        PF07_PREV,
        /** {@code DFHPF8}: page forward. */
        PF08_NEXT,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid key error). */
        OTHER
    }

    /**
     * Pagination context — replaces CDEMO-CT00-INFO commarea extension.
     */
    public record PageState(int pageNum,
                            String trnIdFirst,
                            String trnIdLast,
                            boolean nextPageYes) {

        public static PageState initial() {
            return new PageState(0, "", "", false);
        }
    }

    public record Result(CoTrn00Output output, CardDemoCommarea commarea,
                         PageState pageState, String xctlTo) {

        public static Result sendMap(CoTrn00Output output, CardDemoCommarea commarea,
                                     PageState pageState) {
            return new Result(output, commarea, pageState, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea,
                                  PageState pageState) {
            return new Result(null, commarea, pageState, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    /**
     * Entry point. Mirrors COBOL {@code MAIN-PARA}.
     */
    public Result run(CoTrn00Input input, CardDemoCommarea commarea, PageState pageState) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound, PageState.initial());
        }
        if (pageState == null) {
            pageState = PageState.initial();
        }

        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            // First-time entry — PERFORM PROCESS-ENTER-KEY then SEND
            return processEnterKey(CoTrn00Input.empty(), reentered, pageState);
        }

        if (input == null) {
            return Result.sendMap(buildScreen(CoTrn00Input.empty(), MSG_INVALID_KEY, pageState),
                                  commarea, pageState);
        }

        return switch (input.aidKey()) {
            case ENTER -> processEnterKey(input, commarea, pageState);
            case PF3 -> returnToTarget(commarea, ProgramRegistry.CO_MEN_01C, pageState);
            case PF7 -> processPf7Key(input, commarea, pageState);
            case PF8 -> processPf8Key(input, commarea, pageState);
            case CLEAR, PA1, PA2, PF1, PF2, PF4, PF5, PF6, PF9, PF10, PF11, PF12, OTHER
                    -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY, pageState),
                                      commarea, pageState);
        };
    }

    /**
     * Paragraph {@code PROCESS-ENTER-KEY}: row-selection scan or
     * TRNIDINI repositioning.
     */
    private Result processEnterKey(CoTrn00Input input, CardDemoCommarea commarea,
                                   PageState pageState) {
        // Scan SEL0001I..SEL0010I for first non-blank selection
        int sel = input.firstSelectedRow();
        if (sel > 0) {
            String selFlag = selFlagAtRow(input, sel);
            String trnId = trnIdAtRow(input, sel);
            if (("S".equals(selFlag) || "s".equals(selFlag)) && trnId != null && !trnId.isBlank()) {
                // XCTL to COTRN01C with the selected TRAN-ID
                return returnToTarget(commarea, ProgramRegistry.CO_TRN_01C, pageState);
            }
            // Invalid selection
            return Result.sendMap(buildScreen(input, MSG_INVALID_SELECTION, pageState),
                                  commarea, pageState);
        }

        // Validate TRNIDINI is numeric (if present)
        String startKey = "";
        String trnIdIn = input.trnIdIn();
        if (trnIdIn != null && !trnIdIn.isBlank()) {
            if (!isNumeric(trnIdIn.trim())) {
                return Result.sendMap(buildScreen(input, MSG_TRAN_NUMERIC, pageState),
                                      commarea, pageState);
            }
            startKey = trnIdIn.trim();
        }

        // Forward page from startKey (or beginning if blank)
        PageState reset = new PageState(0, pageState.trnIdFirst(), pageState.trnIdLast(),
                                        pageState.nextPageYes());
        return processPageForward(input, commarea, reset, startKey);
    }

    /**
     * Paragraph {@code PROCESS-PF7-KEY}: paginate backward.
     */
    private Result processPf7Key(CoTrn00Input input, CardDemoCommarea commarea,
                                 PageState pageState) {
        if (pageState.pageNum() > 1) {
            String startKey = pageState.trnIdFirst() == null || pageState.trnIdFirst().isBlank()
                    ? "" : pageState.trnIdFirst();
            return processPageBackward(input, commarea, pageState, startKey);
        } else {
            return Result.sendMap(buildScreen(input, MSG_AT_TOP, pageState),
                                  commarea, pageState);
        }
    }

    /**
     * Paragraph {@code PROCESS-PF8-KEY}: paginate forward.
     */
    private Result processPf8Key(CoTrn00Input input, CardDemoCommarea commarea,
                                 PageState pageState) {
        if (pageState.nextPageYes()) {
            String startKey = pageState.trnIdLast() == null || pageState.trnIdLast().isBlank()
                    ? "" : pageState.trnIdLast();
            return processPageForward(input, commarea, pageState, startKey);
        } else {
            return Result.sendMap(buildScreen(input, MSG_AT_BOTTOM, pageState),
                                  commarea, pageState);
        }
    }

    /**
     * Paragraph {@code PROCESS-PAGE-FORWARD}: stream-based forward scan.
     */
    private Result processPageForward(CoTrn00Input input, CardDemoCommarea commarea,
                                      PageState pageState, String startKey) {
        List<TranRecord> page;
        boolean morePages;
        try (Stream<TranRecord> stream = transactions.streamSequential()) {
            var iter = stream.iterator();
            // Skip records up to and including startKey (COBOL READNEXT skips past STARTBR)
            if (!startKey.isBlank()) {
                while (iter.hasNext()) {
                    TranRecord r = iter.next();
                    if (compareTranId(r.tranId(), startKey) > 0) {
                        // Push back conceptually — collect this record and continue
                        page = new ArrayList<>();
                        page.add(r);
                        while (iter.hasNext() && page.size() < ROWS_PER_PAGE) {
                            page.add(iter.next());
                        }
                        morePages = iter.hasNext();
                        return buildPageResult(input, commarea, pageState, page, morePages, /*forward=*/ true);
                    }
                }
                // Past end
                return Result.sendMap(buildScreen(input, MSG_REACHED_BOTTOM, pageState),
                                      commarea, pageState);
            }
            // Start from beginning
            page = new ArrayList<>();
            while (iter.hasNext() && page.size() < ROWS_PER_PAGE) {
                page.add(iter.next());
            }
            morePages = iter.hasNext();
        } catch (RuntimeException re) {
            log.warn("CoTrn00C: TRANSACT stream failed for startKey={}", startKey, re);
            return Result.sendMap(buildScreen(input, MSG_LOOKUP_ERROR, pageState),
                                  commarea, pageState);
        }
        return buildPageResult(input, commarea, pageState, page, morePages, /*forward=*/ true);
    }

    /**
     * Paragraph {@code PROCESS-PAGE-BACKWARD}: stream-based backward scan.
     *
     * <p>Java streams don't support backward iteration, so we collect all
     * records sequentially and locate the previous page by index.
     */
    private Result processPageBackward(CoTrn00Input input, CardDemoCommarea commarea,
                                       PageState pageState, String endKey) {
        List<TranRecord> all;
        try (Stream<TranRecord> stream = transactions.streamSequential()) {
            all = stream.toList();
        } catch (RuntimeException re) {
            log.warn("CoTrn00C: TRANSACT stream failed for endKey={}", endKey, re);
            return Result.sendMap(buildScreen(input, MSG_LOOKUP_ERROR, pageState),
                                  commarea, pageState);
        }
        // Find the position of endKey (first record matching)
        int endIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (compareTranId(all.get(i).tranId(), endKey) >= 0) {
                endIdx = i;
                break;
            }
        }
        if (endIdx < 0) {
            endIdx = all.size();
        }
        // Take the 10 records immediately preceding endIdx
        int start = Math.max(0, endIdx - ROWS_PER_PAGE);
        List<TranRecord> page = new ArrayList<>(all.subList(start, endIdx));
        boolean morePages = endIdx < all.size();
        boolean isAtTop = start == 0;
        if (page.isEmpty() && !isAtTop) {
            return Result.sendMap(buildScreen(input, MSG_REACHED_TOP, pageState),
                                  commarea, pageState);
        }
        int newPageNum = Math.max(1, pageState.pageNum() - 1);
        PageState newState = new PageState(
                newPageNum,
                page.isEmpty() ? "" : page.getFirst().tranId(),
                page.isEmpty() ? "" : page.getLast().tranId(),
                morePages);
        CoTrn00Output output = buildPageScreen(input, "", newState, page);
        return Result.sendMap(output, commarea, newState);
    }

    /**
     * Builds a page result (forward direction).
     */
    private Result buildPageResult(CoTrn00Input input, CardDemoCommarea commarea,
                                   PageState pageState, List<TranRecord> page,
                                   boolean morePages, boolean forward) {
        if (page.isEmpty()) {
            return Result.sendMap(buildScreen(input, MSG_REACHED_BOTTOM, pageState),
                                  commarea, pageState);
        }
        int newPageNum = forward ? pageState.pageNum() + 1 : Math.max(1, pageState.pageNum() - 1);
        PageState newState = new PageState(
                newPageNum,
                page.getFirst().tranId(),
                page.getLast().tranId(),
                morePages);
        CoTrn00Output output = buildPageScreen(input, "", newState, page);
        return Result.sendMap(output, commarea, newState);
    }

    /**
     * Paragraph {@code RETURN-TO-PREV-SCREEN}.
     */
    private Result returnToTarget(CardDemoCommarea commarea, String target, PageState pageState) {
        CardDemoCommarea outbound = withTarget(commarea, target);
        log.info("CoTrn00C: XCTL to {}", target);
        CardDemoCommarea finalCommarea = outbound;
        if (programRegistry.isRegistered(target)) {
            finalCommarea = programRegistry.invoke(target, outbound);
        }
        return Result.xctl(target, finalCommarea, pageState);
    }

    /**
     * Builds the screen with no transaction rows (error path).
     */
    private CoTrn00Output buildScreen(CoTrn00Input input, String message, PageState pageState) {
        return buildPageScreen(input, message, pageState, List.of());
    }

    /**
     * Builds the populated 10-row output screen (paragraph
     * {@code POPULATE-TRAN-DATA} loop + {@code POPULATE-HEADER-INFO}).
     */
    private CoTrn00Output buildPageScreen(CoTrn00Input input, String message,
                                          PageState pageState, List<TranRecord> page) {
        String[] trnId = new String[ROWS_PER_PAGE];
        String[] tDate = new String[ROWS_PER_PAGE];
        String[] tDesc = new String[ROWS_PER_PAGE];
        String[] tAmt = new String[ROWS_PER_PAGE];
        String[] sel = new String[ROWS_PER_PAGE];
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            trnId[i] = ""; tDate[i] = ""; tDesc[i] = ""; tAmt[i] = ""; sel[i] = "";
        }
        for (int i = 0; i < page.size() && i < ROWS_PER_PAGE; i++) {
            TranRecord t = page.get(i);
            trnId[i] = t.tranId();
            tDate[i] = formatTimestampAsDate(t.tranOrigTs());
            tDesc[i] = t.tranDesc();
            tAmt[i] = formatTranAmount(t.tranAmt());
        }
        String pageNumStr = String.format("%08d", pageState.pageNum());
        return new CoTrn00Output(
                TRANSACTION_ID, TITLE_01, todayDate(), PROGRAM_ID, TITLE_02, nowTime(),
                pageNumStr,
                input.trnIdIn(),
                sel[0], trnId[0], tDate[0], tDesc[0], tAmt[0],
                sel[1], trnId[1], tDate[1], tDesc[1], tAmt[1],
                sel[2], trnId[2], tDate[2], tDesc[2], tAmt[2],
                sel[3], trnId[3], tDate[3], tDesc[3], tAmt[3],
                sel[4], trnId[4], tDate[4], tDesc[4], tAmt[4],
                sel[5], trnId[5], tDate[5], tDesc[5], tAmt[5],
                sel[6], trnId[6], tDate[6], tDesc[6], tAmt[6],
                sel[7], trnId[7], tDate[7], tDesc[7], tAmt[7],
                sel[8], trnId[8], tDate[8], tDesc[8], tAmt[8],
                sel[9], trnId[9], tDate[9], tDesc[9], tAmt[9],
                message == null ? "" : message);
    }

    // -- helpers ----------------------------------------------------------

    private static String selFlagAtRow(CoTrn00Input input, int row) {
        return switch (row) {
            case 1 -> input.sel0001();
            case 2 -> input.sel0002();
            case 3 -> input.sel0003();
            case 4 -> input.sel0004();
            case 5 -> input.sel0005();
            case 6 -> input.sel0006();
            case 7 -> input.sel0007();
            case 8 -> input.sel0008();
            case 9 -> input.sel0009();
            case 10 -> input.sel0010();
            default -> "";
        };
    }

    private static String trnIdAtRow(CoTrn00Input input, int row) {
        return switch (row) {
            case 1 -> input.trnId01();
            case 2 -> input.trnId02();
            case 3 -> input.trnId03();
            case 4 -> input.trnId04();
            case 5 -> input.trnId05();
            case 6 -> input.trnId06();
            case 7 -> input.trnId07();
            case 8 -> input.trnId08();
            case 9 -> input.trnId09();
            case 10 -> input.trnId10();
            default -> "";
        };
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static int compareTranId(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b);
    }

    /**
     * Formats the date portion of a {@link java.time.LocalDateTime}
     * timestamp as {@code MM/DD/YY} for the BMS detail rows. A
     * {@code null} timestamp (the COBOL all-spaces sentinel) is rendered
     * as an empty string. AAP &sect;0.6.4 mandates {@link java.time}
     * types so the conversion path is direct (no string substring).
     */
    private static String formatTimestampAsDate(java.time.LocalDateTime ts) {
        if (ts == null) return "";
        try {
            return ts.toLocalDate().format(DATE_FORMATTER);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String formatTranAmount(BigDecimal amt) {
        if (amt == null) return "+00000000.00";
        BigDecimal scaled = amt.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal abs = scaled.abs();
        String sign = scaled.signum() < 0 ? "-" : "+";
        long wholePart = abs.toBigInteger().longValueExact();
        long fracPart = abs.subtract(new BigDecimal(wholePart)).movePointRight(2).longValueExact();
        return String.format("%s%08d.%02d", sign, wholePart, fracPart);
    }

    private static CardDemoCommarea withTarget(CardDemoCommarea commarea, String toProgram) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                TRANSACTION_ID, PROGRAM_ID, gi.toTranId(), toProgram,
                gi.userId(), gi.userType(), PgmContext.ENTER);
        return commarea.withGeneralInfo(updated);
    }

    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                gi.fromTranId(), gi.fromProgram(), gi.toTranId(), gi.toProgram(),
                gi.userId(), gi.userType(), ctx);
        return commarea.withGeneralInfo(updated);
    }

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
