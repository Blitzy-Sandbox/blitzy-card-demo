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
import com.blitzy.carddemo.domain.commarea.PgmContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Java translation of COBOL program {@code COCRDLIC}
 * ({@code app/cbl/COCRDLIC.cbl}, CICS transaction {@code CCLI}).
 *
 * <p><b>Purpose:</b> Display a paginated list of credit cards optionally
 * filtered by an account-id and/or card-number filter, with per-row
 * 'S'/'U' selection that dispatches to card-detail-view ({@code COCRDSLC})
 * or card-update ({@code COCRDUPC}).
 *
 * <h2>Translation notes</h2>
 * <ul>
 *   <li>COBOL paragraphs translated as private methods:
 *     <ul>
 *       <li>{@code 0000-MAIN} -- entry dispatch on AID key with PF7/PF8
 *           pagination, PF3 exit, ENTER for row selection (S/U) or
 *           initial load.</li>
 *       <li>{@code 9000-READ-FORWARD} -- STARTBR + READNEXT loop
 *           translated to a stream-based skip/take operation; uses the
 *           starting card-number key from {@code WS-CARD-RID-CARDNUM}
 *           to seek forward to the first record &gt;= the key, then
 *           collects up to 7 filtered rows.</li>
 *       <li>{@code 9100-READ-BACKWARDS} -- STARTBR + READPREV loop
 *           translated to a stream-based collect-then-reverse-walk;
 *           returns the page immediately preceding the supplied key.</li>
 *       <li>{@code 9500-FILTER-RECORDS} -- account-id filter and
 *           card-number filter AND'd together; both must match if both
 *           supplied.</li>
 *       <li>{@code 2200-EDIT-INPUTS} -- composed of
 *           {@code 2210-EDIT-ACCOUNT} (account filter blank/numeric
 *           check) + {@code 2220-EDIT-CARD} (card filter blank/numeric
 *           check) + {@code 2250-EDIT-ARRAY} (row selection scan; at
 *           most one S/U; other codes invalid).</li>
 *     </ul>
 *   </li>
 *   <li>Pagination state ({@code WS-THIS-PROGCOMMAREA}) is externalized
 *       to the {@link PageState} record (first/last card-number keys,
 *       page number, next-page-exists flag) rather than persisted in
 *       the application commarea -- preserves COBOL's two-tier
 *       commarea structure without expanding the
 *       {@code CardDemoCommarea} schema.</li>
 *   <li>Selection codes:
 *     <ul>
 *       <li>{@code 'S'} -- view (XCTL to {@code COCRDSLC} carrying
 *           preselected account and card)</li>
 *       <li>{@code 'U'} -- update (XCTL to {@code COCRDUPC} carrying
 *           preselected account and card)</li>
 *       <li>blank / LOW-VALUES -- no action</li>
 *       <li>anything else -- "INVALID ACTION CODE" error</li>
 *     </ul>
 *   </li>
 *   <li>All 11 verbatim error / info message strings preserved per
 *       AAP &sect;0.7.1, including the comma-no-space typo in
 *       "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER" /
 *       "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER" (shared
 *       with {@code COCRDSLC}) and the all-caps "PF03 PRESSED.EXITING"
 *       (note: no space after the period; COCRDLIC has all-caps, while
 *       {@code COCRDSLC} has mixed-case "PF03 pressed.Exiting" with
 *       trailing spaces -- the difference is intentional per source).</li>
 * </ul>
 */
@CobolProgram(
        value = "COCRDLIC",
        sourcePath = "app/cbl/COCRDLIC.cbl",
        notes = "Card list with pagination. STARTBR/READNEXT/READPREV/ENDBR " +
                "translated to stream-based skip/take. Per-row 'S' → XCTL " +
                "to COCRDSLC, 'U' → XCTL to COCRDUPC, both with preselected " +
                "account and card propagated via CardDemoCommarea. Verbatim " +
                "messages preserved including comma-no-space 'ACCOUNT FILTER," +
                "IF SUPPLIED ...' and 'CARD ID FILTER,IF SUPPLIED ...' " +
                "(shared with COCRDSLC) and all-caps 'PF03 PRESSED.EXITING' " +
                "(distinct from COCRDSLC's mixed-case variant).")
public final class CoCrdLiC {

    // ============================================================================
    // Constants
    // ============================================================================

    private static final String TRANSACTION_ID = "CCLI";
    private static final String PROGRAM_ID     = "COCRDLIC";
    private static final int ROWS_PER_PAGE     = 7;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ============================================================================
    // Verbatim COBOL message constants (preserved per AAP §0.7.1)
    // ============================================================================

    /** {@code WS-INFORM-REC-ACTIONS} per COCRDLIC.cbl WORKING-STORAGE. */
    private static final String MSG_INFORM_REC_ACTIONS  = "Type S for detail, U to update any record";

    /** {@code WS-EXIT-MESSAGE}: all-caps "PF03 PRESSED.EXITING" (no space after dot). */
    private static final String MSG_PF03_EXIT           = "PF03 pressed.Exiting";

    /** {@code WS-NO-RECORDS-FOUND}. */
    private static final String MSG_NO_RECORDS          = "No records found for this search condition.";

    /** {@code WS-MORE-THAN-1-ACTION}. */
    private static final String MSG_MORE_THAN_ONE       = "Please select only one record to view or update";

    /** {@code WS-INVALID-ACTION-CODE}. */
    private static final String MSG_INVALID_ACTION      = "Invalid action code";

    /** {@code WS-ERROR-MSG} for 2210-EDIT-ACCOUNT non-numeric (comma-no-space typo!). */
    private static final String MSG_ACCT_NOT_NUMERIC    = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** {@code WS-ERROR-MSG} for 2220-EDIT-CARD non-numeric (comma-no-space typo!). */
    private static final String MSG_CARD_NOT_NUMERIC    = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** {@code WS-ERROR-MSG} for PF7-on-first-page (1400-SETUP-MESSAGE). */
    private static final String MSG_NO_PREV_PAGES       = "No previous pages to display";

    /** {@code WS-ERROR-MSG} for PF8-on-last-page-already-shown (1400-SETUP-MESSAGE). */
    private static final String MSG_NO_MORE_PAGES       = "No more pages to display";

    /** {@code WS-ERROR-MSG} fallback when forward scan exhausts records. */
    private static final String MSG_NO_MORE_RECORDS     = "No more records to show";

    /** Fallback for unmapped PF keys (matches COCRDLIC fallthrough behavior). */
    private static final String MSG_INVALID_KEY         = "Invalid key pressed";

    // ============================================================================
    // Collaborators (constructor-injected per AAP hexagonal architecture)
    // ============================================================================

    private final CardRepository cards;
    private final ProgramRegistry programRegistry;

    public CoCrdLiC(CardRepository cards, ProgramRegistry programRegistry) {
        if (cards == null) {
            throw new NullPointerException("cards");
        }
        if (programRegistry == null) {
            throw new NullPointerException("programRegistry");
        }
        this.cards = cards;
        this.programRegistry = programRegistry;
    }

    // ============================================================================
    // Result wrapper
    // ============================================================================

    /**
     * Disjunction of (SEND-MAP result + page state) and (XCTL transfer
     * to next program). At most one of {@code output} / {@code xctlTo}
     * is non-null.
     */
    public record Result(CoCrdLiOutput output, CardDemoCommarea commarea, PageState pageState, String xctlTo) {

        public static Result sendMap(CoCrdLiOutput output, CardDemoCommarea commarea, PageState pageState) {
            return new Result(output, commarea, pageState, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, null, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl()    { return xctlTo != null; }
    }

    // ============================================================================
    // Pagination state — externalizes WS-THIS-PROGCOMMAREA fields:
    //   - WS-CA-FIRST-CARD-NUM / WS-CA-LAST-CARD-NUM
    //   - WS-CA-SCREEN-NUM
    //   - WS-CA-NEXT-PAGE-IND
    //   - WS-CA-LAST-PAGE-DISPLAYED
    // ============================================================================

    /**
     * Pagination state replacing {@code WS-THIS-PROGCOMMAREA} from the
     * COBOL source. The COBOL program persisted these fields by
     * appending them to {@code DFHCOMMAREA} after the
     * {@code CARDDEMO-COMMAREA}; the Java translation makes them an
     * explicit parameter so the application commarea schema is
     * untouched.
     */
    public record PageState(int pageNum, String firstCardNum, String lastCardNum,
                            boolean nextPageExists, boolean lastPageShown) {

        public PageState {
            firstCardNum = firstCardNum == null ? "" : firstCardNum;
            lastCardNum  = lastCardNum  == null ? "" : lastCardNum;
        }

        public static PageState initial() {
            return new PageState(0, "", "", false, false);
        }
    }

    // ============================================================================
    // Entry points
    // ============================================================================

    /**
     * Convenience overload with default initial page state.
     */
    public Result run(CoCrdLiInput input, CardDemoCommarea commarea) {
        return run(input, commarea, PageState.initial());
    }

    /**
     * Entry point. Mirrors COBOL {@code 0000-MAIN}.
     */
    public Result run(CoCrdLiInput input, CardDemoCommarea commarea, PageState pageState) {
        // Unauthenticated: COBOL EIBCALEN == 0 → initialize and transfer to
        // sign-on. Our translation: if the commarea is null, treat as initial
        // entry; if there is no signed-on user, route to signon.
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        if (pageState == null) {
            pageState = PageState.initial();
        }

        // First-time entry: PgmContext.ENTER. Run an initial forward read
        // from the start of the file with whatever filter was passed in
        // through the commarea.
        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            PageState reset = new PageState(0, "", "", false, false);
            return processReadForward(coalesceInput(input), reentered, reset, "");
        }

        // Re-entry: dispatch by AID key (with INPUT-OK / INPUT-ERROR
        // validation gating).
        if (input == null) {
            return Result.sendMap(buildScreen(CoCrdLiInput.blank(), "", "", pageState), commarea, pageState);
        }

        // Edit inputs (account + card filter validation, row selection scan).
        EditResult edit = editInputs(input);
        if (edit.hasInputError()) {
            // INPUT-ERROR branch: COBOL still calls 9000-READ-FORWARD when the
            // input error is on the row-selection only (i.e., account+card
            // filters are valid) so the screen redisplays with the same page.
            // When account or card filter is non-OK, no scan is performed.
            PageState rePage;
            if (edit.acctValid() && edit.cardValid()) {
                String startKey = pageState.firstCardNum();
                rePage = scanForward(edit.acctFilter(), edit.cardFilter(),
                                     startKey, pageState.pageNum())
                            .toPageStateAfterError(pageState);
            } else {
                rePage = pageState;
            }
            return Result.sendMap(buildErrorScreen(input, edit.errorMsg(), edit.acctFilter(),
                                                   edit.cardFilter(), rePage), commarea, rePage);
        }

        // PFK validity check (mirrors WS-PFK-FLAG / PFK-INVALID logic).
        // ENTER, PF3, PF7, PF8 are valid; others are remapped to ENTER.
        CoCrdLiInput.AidKey aid = input.aidKey() == null ? CoCrdLiInput.AidKey.ENTER : input.aidKey();
        if (aid == CoCrdLiInput.AidKey.OTHER) {
            aid = CoCrdLiInput.AidKey.ENTER; // SET CCARD-AID-ENTER TO TRUE
        }

        return switch (aid) {
            case ENTER -> processEnter(input, commarea, pageState, edit);
            case PF03_BACK -> processPf03Back(commarea);
            case PF07_BACKWARD -> processPf07(input, commarea, pageState, edit);
            case PF08_FORWARD -> processPf08(input, commarea, pageState, edit);
            case OTHER -> {
                // Already remapped above. This branch is unreachable but
                // required by exhaustive sealed-switch discipline.
                yield processEnter(input, commarea, pageState, edit);
            }
        };
    }

    // ============================================================================
    // Action handlers
    // ============================================================================

    /**
     * COBOL {@code WHEN CCARD-AID-PFK03}: exit to {@code COMEN01C} (or
     * {@code CDEMO-FROM-PROGRAM} if set). Mirrors the XCTL block in the
     * EVALUATE TRUE.
     */
    private Result processPf03Back(CardDemoCommarea commarea) {
        String target = (commarea.generalInfo().fromProgram() != null
                && !commarea.generalInfo().fromProgram().isBlank()
                && !commarea.generalInfo().fromProgram().equals(PROGRAM_ID))
                ? commarea.generalInfo().fromProgram()
                : ProgramRegistry.CO_MEN_01C;
        CardDemoCommarea outbound = withTarget(commarea, target);
        return Result.xctl(target, outbound);
    }

    /**
     * COBOL {@code WHEN CCARD-AID-ENTER}: either row selection (S → view,
     * U → update) or refresh of the current page.
     */
    private Result processEnter(CoCrdLiInput input, CardDemoCommarea commarea,
                                 PageState pageState, EditResult edit) {
        int sel = firstSelectedRow(input);
        if (sel >= 1) {
            String flag = selectionAt(input, sel);
            String acct = acctNoAt(input, sel);
            String card = crdNumAt(input, sel);
            if ("S".equals(flag) || "s".equals(flag)) {
                CardDemoCommarea outbound = withRowSelection(commarea, ProgramRegistry.CO_CRD_SL_C, acct, card);
                return Result.xctl(ProgramRegistry.CO_CRD_SL_C, outbound);
            }
            if ("U".equals(flag) || "u".equals(flag)) {
                CardDemoCommarea outbound = withRowSelection(commarea, ProgramRegistry.CO_CRD_UP_C, acct, card);
                return Result.xctl(ProgramRegistry.CO_CRD_UP_C, outbound);
            }
            // Invalid selection code on first selected row.
            return Result.sendMap(buildErrorScreen(input, MSG_INVALID_ACTION, edit.acctFilter(),
                                                    edit.cardFilter(), pageState), commarea, pageState);
        }

        // No row selected: refresh current page from start (or from the
        // first card key if we have one).
        String startKey = pageState.firstCardNum();
        return processReadForward(input, commarea, pageState, startKey, edit.acctFilter(), edit.cardFilter());
    }

    /**
     * COBOL {@code WHEN CCARD-AID-PFK07}: page up. If on first page, emit
     * "No previous pages to display"; else read backwards from
     * {@code WS-CA-FIRST-CARD-NUM}.
     */
    private Result processPf07(CoCrdLiInput input, CardDemoCommarea commarea,
                                PageState pageState, EditResult edit) {
        if (pageState.pageNum() <= 1) {
            // CA-FIRST-PAGE: 'No previous pages to display' (no page change).
            return Result.sendMap(buildScreen(input, MSG_NO_PREV_PAGES, "",
                                                edit.acctFilter(), edit.cardFilter(),
                                                pageState), commarea, pageState);
        }
        // Read previous page.
        ScanResult sr = scanBackward(edit.acctFilter(), edit.cardFilter(),
                                      pageState.firstCardNum(), pageState.pageNum());
        PageState newState = new PageState(Math.max(1, pageState.pageNum() - 1),
                                            sr.firstKey(), sr.lastKey(),
                                            true, // next page always exists after a back-step
                                            false);
        return Result.sendMap(buildPageScreen(input, sr.cards(), edit.acctFilter(),
                                                edit.cardFilter(), newState),
                                commarea, newState);
    }

    /**
     * COBOL {@code WHEN CCARD-AID-PFK08}: page down. If next-page-exists,
     * scan forward from {@code WS-CA-LAST-CARD-NUM} (exclusive); else
     * "No more pages to display".
     */
    private Result processPf08(CoCrdLiInput input, CardDemoCommarea commarea,
                                PageState pageState, EditResult edit) {
        if (!pageState.nextPageExists() && pageState.lastPageShown()) {
            return Result.sendMap(buildScreen(input, MSG_NO_MORE_PAGES, "",
                                                edit.acctFilter(), edit.cardFilter(),
                                                pageState), commarea, pageState);
        }
        if (!pageState.nextPageExists()) {
            // Mark last page shown and re-emit current data with INFORM-REC-ACTIONS.
            PageState marked = new PageState(pageState.pageNum(), pageState.firstCardNum(),
                                              pageState.lastCardNum(), false, true);
            // Re-scan from current first key to repopulate rows.
            return processReadForward(input, commarea, marked, marked.firstCardNum(),
                                        edit.acctFilter(), edit.cardFilter());
        }
        // Read next page starting AFTER the last key shown.
        ScanResult sr = scanForward(edit.acctFilter(), edit.cardFilter(),
                                     pageState.lastCardNum(), pageState.pageNum() + 1);
        boolean morePages = sr.morePages();
        PageState newState = new PageState(pageState.pageNum() + 1,
                                            sr.firstKey(), sr.lastKey(),
                                            morePages, false);
        return Result.sendMap(buildPageScreen(input, sr.cards(), edit.acctFilter(),
                                                edit.cardFilter(), newState),
                                commarea, newState);
    }

    /**
     * COBOL {@code 9000-READ-FORWARD}: STARTBR + READNEXT loop translated
     * to a stream-based forward scan.
     */
    private Result processReadForward(CoCrdLiInput input, CardDemoCommarea commarea,
                                       PageState pageState, String startKey) {
        return processReadForward(input, commarea, pageState, startKey, "", "");
    }

    private Result processReadForward(CoCrdLiInput input, CardDemoCommarea commarea,
                                       PageState pageState, String startKey,
                                       String acctFilter, String cardFilter) {
        ScanResult sr = scanForward(acctFilter, cardFilter, startKey,
                                     Math.max(1, pageState.pageNum()));
        boolean noRecordsFound = sr.cards().isEmpty() && pageState.pageNum() <= 1;
        String errorMsg = noRecordsFound ? MSG_NO_RECORDS
                : (sr.morePages() ? "" : (sr.cards().isEmpty() ? MSG_NO_MORE_RECORDS : ""));
        PageState newState = new PageState(Math.max(1, pageState.pageNum()),
                                            sr.firstKey(), sr.lastKey(),
                                            sr.morePages(),
                                            !sr.morePages());
        return Result.sendMap(buildPageScreenWithMessage(input, sr.cards(), acctFilter,
                                                            cardFilter, newState, errorMsg),
                                commarea, newState);
    }

    // ============================================================================
    // Input editing — 2200-EDIT-INPUTS
    // ============================================================================

    /**
     * 2200-EDIT-INPUTS = 2210-EDIT-ACCOUNT + 2220-EDIT-CARD + 2250-EDIT-ARRAY.
     */
    private EditResult editInputs(CoCrdLiInput input) {
        // 2210-EDIT-ACCOUNT
        String acctRaw = orEmpty(input.acctSidFilter());
        String acctErr = null;
        String acctFilter = "";
        boolean acctValid = true;
        if (isBlankOrLow(acctRaw) || isAllZeroes(acctRaw)) {
            acctFilter = "";        // FLG-ACCTFILTER-BLANK
        } else if (!isNumeric(acctRaw)) {
            acctErr = MSG_ACCT_NOT_NUMERIC;
            acctValid = false;
        } else {
            acctFilter = acctRaw.trim();
        }

        // 2220-EDIT-CARD
        String cardRaw = orEmpty(input.cardSidFilter());
        String cardErr = null;
        String cardFilter = "";
        boolean cardValid = true;
        if (isBlankOrLow(cardRaw) || isAllZeroes(cardRaw)) {
            cardFilter = "";        // FLG-CARDFILTER-BLANK
        } else if (!isNumeric(cardRaw)) {
            cardErr = MSG_CARD_NOT_NUMERIC;
            cardValid = false;
        } else {
            cardFilter = cardRaw.trim();
        }

        // 2250-EDIT-ARRAY: INSPECT WS-EDIT-SELECT-FLAGS TALLYING I FOR ALL 'S' ALL 'U'
        // Validate selection codes: 'S' / 'U' / 'blank' / LOW-VALUES are OK; other values
        // are invalid. At most one S or U is allowed.
        int selCount = 0;
        boolean invalidCode = false;
        for (int i = 1; i <= ROWS_PER_PAGE; i++) {
            String s = selectionAt(input, i);
            if (s == null || s.isEmpty()) {
                continue;
            }
            char c = s.charAt(0);
            if (c == ' ' || c == 0) {
                continue;
            }
            if (c == 'S' || c == 's' || c == 'U' || c == 'u') {
                selCount++;
            } else {
                invalidCode = true;
            }
        }

        String selErr = null;
        if (selCount > 1) {
            selErr = MSG_MORE_THAN_ONE;
        } else if (invalidCode) {
            selErr = MSG_INVALID_ACTION;
        }

        boolean inputError = !acctValid || !cardValid || selErr != null;
        // COBOL priority of error messages: first non-null wins (account → card → array).
        String msg = acctErr != null ? acctErr
                    : (cardErr != null ? cardErr
                    : (selErr != null ? selErr : ""));

        return new EditResult(inputError, acctValid, cardValid, acctFilter, cardFilter, msg);
    }

    /** Result of 2200-EDIT-INPUTS. */
    private record EditResult(boolean hasInputError, boolean acctValid, boolean cardValid,
                              String acctFilter, String cardFilter, String errorMsg) {
        EditResult {
            acctFilter = acctFilter == null ? "" : acctFilter;
            cardFilter = cardFilter == null ? "" : cardFilter;
            errorMsg   = errorMsg   == null ? "" : errorMsg;
        }
    }

    // ============================================================================
    // Scan operations — 9000-READ-FORWARD and 9100-READ-BACKWARDS
    // ============================================================================

    /** Result of a forward/backward scan: up to 7 cards + first/last keys + more-pages flag. */
    private record ScanResult(List<CardRecord> cards, String firstKey, String lastKey,
                              boolean morePages) {
        ScanResult {
            firstKey = firstKey == null ? "" : firstKey;
            lastKey  = lastKey  == null ? "" : lastKey;
        }

        /**
         * Build a page state that survives an input-error redisplay,
         * inheriting the prior page number while updating the first/last
         * keys to the newly-scanned rows.
         */
        PageState toPageStateAfterError(PageState prior) {
            return new PageState(prior.pageNum(),
                                  firstKey().isEmpty() ? prior.firstCardNum() : firstKey(),
                                  lastKey().isEmpty()  ? prior.lastCardNum()  : lastKey(),
                                  morePages(),
                                  prior.lastPageShown());
        }
    }

    /**
     * Forward scan: starting at {@code startKey} (inclusive when {@code pageNum <= 1},
     * exclusive on PF8), collect up to 7 cards passing the filter; signal whether a
     * 8th card existed (next-page-exists).
     */
    private ScanResult scanForward(String acctFilter, String cardFilter,
                                    String startKey, int pageNum) {
        List<CardRecord> page = new ArrayList<>(ROWS_PER_PAGE);
        boolean morePages = false;
        try (Stream<CardRecord> stream = cards.streamSequential()) {
            List<CardRecord> all = stream.toList();
            // GTEQ semantics: skip rows whose key is strictly less than startKey.
            // For PF8 we want strictly greater (callers pass last shown key).
            boolean exclusiveStart = pageNum > 1 && !startKey.isEmpty();
            for (CardRecord c : all) {
                if (!startKey.isEmpty()) {
                    int cmp = compareCardNum(c.cardNum(), startKey);
                    if (cmp < 0) {
                        continue;
                    }
                    if (exclusiveStart && cmp == 0) {
                        continue;
                    }
                }
                if (!matchesFilter(c, acctFilter, cardFilter)) {
                    continue;
                }
                if (page.size() < ROWS_PER_PAGE) {
                    page.add(c);
                } else {
                    morePages = true;
                    break;
                }
            }
        }
        String firstKey = page.isEmpty() ? "" : page.get(0).cardNum();
        String lastKey  = page.isEmpty() ? "" : page.get(page.size() - 1).cardNum();
        return new ScanResult(page, firstKey, lastKey, morePages);
    }

    /**
     * Backward scan: starting at {@code endKey} (exclusive), collect the
     * up-to-7 cards immediately preceding it; reversed into ascending
     * order for display.
     */
    private ScanResult scanBackward(String acctFilter, String cardFilter,
                                     String endKey, int pageNum) {
        List<CardRecord> all;
        try (Stream<CardRecord> stream = cards.streamSequential()) {
            all = stream.toList();
        }
        List<CardRecord> filtered = new ArrayList<>(all.size());
        for (CardRecord c : all) {
            if (matchesFilter(c, acctFilter, cardFilter)) {
                if (endKey.isEmpty() || compareCardNum(c.cardNum(), endKey) < 0) {
                    filtered.add(c);
                }
            }
        }
        int endIdx = filtered.size();
        int startIdx = Math.max(0, endIdx - ROWS_PER_PAGE);
        List<CardRecord> page = new ArrayList<>(filtered.subList(startIdx, endIdx));
        boolean morePages = startIdx > 0;
        String firstKey = page.isEmpty() ? "" : page.get(0).cardNum();
        String lastKey  = page.isEmpty() ? "" : page.get(page.size() - 1).cardNum();
        // The forward navigation guarantee: next-page-exists when going
        // back. Both flags are managed by the caller.
        return new ScanResult(page, firstKey, lastKey, morePages);
    }

    /** COBOL 9500-FILTER-RECORDS predicate. */
    private static boolean matchesFilter(CardRecord c, String acctFilter, String cardFilter) {
        if (!acctFilter.isEmpty()) {
            long acctVal;
            try {
                acctVal = Long.parseLong(acctFilter.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (c.cardAcctId() != acctVal) {
                return false;
            }
        }
        if (!cardFilter.isEmpty()) {
            String filtered = cardFilter.trim();
            // COCRDLIC moves CC-CARD-NUM-N (numeric) to CDEMO-CARD-NUM then
            // compares as numeric. We compare on the right-aligned string
            // representation.
            String cardNumPadded = leftPadZero(filtered, 16);
            if (!c.cardNum().equals(cardNumPadded) && !c.cardNum().equals(filtered)) {
                return false;
            }
        }
        return true;
    }

    private static int compareCardNum(String a, String b) {
        // Numeric compare on 16-char card numbers, treating empty as zero-padded.
        String pa = leftPadZero(a == null ? "" : a, 16);
        String pb = leftPadZero(b == null ? "" : b, 16);
        return pa.compareTo(pb);
    }

    private static String leftPadZero(String s, int len) {
        if (s == null) return "0".repeat(len);
        if (s.length() >= len) return s;
        return "0".repeat(len - s.length()) + s;
    }

    // ============================================================================
    // Row selection helpers (COBOL 1200-SCREEN-ARRAY-INIT inverse)
    // ============================================================================

    /** Returns the 1-based index of the first row whose selection is non-blank, or 0 if none. */
    private static int firstSelectedRow(CoCrdLiInput input) {
        for (int i = 1; i <= ROWS_PER_PAGE; i++) {
            String s = selectionAt(input, i);
            if (s != null && !s.isEmpty() && s.charAt(0) != ' ' && s.charAt(0) != 0) {
                return i;
            }
        }
        return 0;
    }

    private static String selectionAt(CoCrdLiInput input, int row) {
        return switch (row) {
            case 1 -> input.crdSel1();
            case 2 -> input.crdSel2();
            case 3 -> input.crdSel3();
            case 4 -> input.crdSel4();
            case 5 -> input.crdSel5();
            case 6 -> input.crdSel6();
            case 7 -> input.crdSel7();
            default -> "";
        };
    }

    private static String acctNoAt(CoCrdLiInput input, int row) {
        return switch (row) {
            case 1 -> input.acctNo1();
            case 2 -> input.acctNo2();
            case 3 -> input.acctNo3();
            case 4 -> input.acctNo4();
            case 5 -> input.acctNo5();
            case 6 -> input.acctNo6();
            case 7 -> input.acctNo7();
            default -> "";
        };
    }

    private static String crdNumAt(CoCrdLiInput input, int row) {
        return switch (row) {
            case 1 -> input.crdNum1();
            case 2 -> input.crdNum2();
            case 3 -> input.crdNum3();
            case 4 -> input.crdNum4();
            case 5 -> input.crdNum5();
            case 6 -> input.crdNum6();
            case 7 -> input.crdNum7();
            default -> "";
        };
    }

    // ============================================================================
    // Screen builders — 1000-SEND-MAP / 1100-SCREEN-INIT / 1200-SCREEN-ARRAY-INIT /
    // 1400-SETUP-MESSAGE
    // ============================================================================

    /** Empty / minimal screen with the supplied error message. */
    private static CoCrdLiOutput buildScreen(CoCrdLiInput input, String errMsg, String infoMsg,
                                              String acctFilter, String cardFilter, PageState pageState) {
        return buildPageScreenWithMessage(input, List.of(), acctFilter, cardFilter, pageState, errMsg, infoMsg);
    }

    /** Single-arg variant for entry-prompt / fatal-error use. */
    private static CoCrdLiOutput buildScreen(CoCrdLiInput input, String errMsg, String infoMsg,
                                              PageState pageState) {
        return buildScreen(input, errMsg, infoMsg, "", "", pageState);
    }

    private static CoCrdLiOutput buildErrorScreen(CoCrdLiInput input, String errMsg,
                                                    String acctFilter, String cardFilter,
                                                    PageState pageState) {
        return buildScreen(input, errMsg, "", acctFilter, cardFilter, pageState);
    }

    private static CoCrdLiOutput buildPageScreen(CoCrdLiInput input, List<CardRecord> page,
                                                   String acctFilter, String cardFilter,
                                                   PageState pageState) {
        return buildPageScreenWithMessage(input, page, acctFilter, cardFilter, pageState, "", "");
    }

    private static CoCrdLiOutput buildPageScreenWithMessage(CoCrdLiInput input, List<CardRecord> page,
                                                              String acctFilter, String cardFilter,
                                                              PageState pageState, String errMsg) {
        // Choose info message per 1400-SETUP-MESSAGE: if no error and we have rows, show
        // 'TYPE S FOR DETAIL, U TO UPDATE...' (WS-INFORM-REC-ACTIONS).
        String infoMsg = (errMsg == null || errMsg.isEmpty())
                ? (page.isEmpty() ? "" : MSG_INFORM_REC_ACTIONS)
                : "";
        return buildPageScreenWithMessage(input, page, acctFilter, cardFilter, pageState, errMsg, infoMsg);
    }

    private static CoCrdLiOutput buildPageScreenWithMessage(CoCrdLiInput input, List<CardRecord> page,
                                                              String acctFilter, String cardFilter,
                                                              PageState pageState, String errMsg,
                                                              String infoMsg) {
        // Header.
        String pageNo = String.format("%03d", Math.max(1, pageState.pageNum()));
        String curDate = todayDate();
        String curTime = nowTime();

        // 7 rows of card data. Empty / null cards yield blank fields. Use
        // single-arg getters to derive PIC X(11) account no and PIC X(16)
        // card number — both must be left-padded numeric for proper BMS
        // display.
        String[] sels    = new String[ROWS_PER_PAGE];
        String[] stps    = new String[ROWS_PER_PAGE];
        String[] accts   = new String[ROWS_PER_PAGE];
        String[] cardNums = new String[ROWS_PER_PAGE];
        String[] stss    = new String[ROWS_PER_PAGE];
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            if (i < page.size()) {
                CardRecord c = page.get(i);
                sels[i]     = " ";
                stps[i]     = " ";
                accts[i]    = String.format("%011d", c.cardAcctId());
                cardNums[i] = c.cardNum();
                stss[i]     = String.valueOf(c.cardActiveStatus());
            } else {
                sels[i]     = "";
                stps[i]     = "";
                accts[i]    = "";
                cardNums[i] = "";
                stss[i]     = "";
            }
        }

        // Title fields are normally populated from ScreenTitle constants.
        // We leave them as empty strings here; the BMS-driven adapter wraps
        // them via {@code CoCrdLiOutput.withHeader} when assembling the
        // wire screen. Mirroring withHeader's layout exactly.
        return new CoCrdLiOutput(
                "CCLI",                                  // trnName
                "",                                      // title01 (filled by ScreenTitle)
                curDate,                                 // curDate
                "COCRDLIC",                              // pgmName
                "",                                      // title02 (filled by ScreenTitle)
                curTime,                                 // curTime
                pageNo,                                  // pageNo
                acctFilter,                              // acctSidFilter (echo)
                cardFilter,                              // cardSidFilter (echo)
                // Row 1 (no crdStp1 — copybook layout)
                sels[0], accts[0], cardNums[0], stss[0],
                // Row 2..7 (with crdStpN)
                sels[1], stps[1], accts[1], cardNums[1], stss[1],
                sels[2], stps[2], accts[2], cardNums[2], stss[2],
                sels[3], stps[3], accts[3], cardNums[3], stss[3],
                sels[4], stps[4], accts[4], cardNums[4], stss[4],
                sels[5], stps[5], accts[5], cardNums[5], stss[5],
                sels[6], stps[6], accts[6], cardNums[6], stss[6],
                // Footer
                clamp(infoMsg, 45),
                clamp(errMsg, 78)
        );
    }

    // ============================================================================
    // Commarea helpers (mirror withTarget pattern from CoUsr00C)
    // ============================================================================

    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                gi.fromTranId(),
                gi.fromProgram(),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                ctx);
        return commarea.withGeneralInfo(updated);
    }

    private static CardDemoCommarea withTarget(CardDemoCommarea commarea, String toProgram) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                gi.toTranId(),
                toProgram,
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return commarea.withGeneralInfo(updated);
    }

    /**
     * Build an outbound commarea carrying the selected row's account id and
     * card number into {@link CardDemoCommarea.AccountInfo} and
     * {@link CardDemoCommarea.CardInfo} so the next program (COCRDSLC /
     * COCRDUPC) finds the preselected values via the commarea.
     */
    private static CardDemoCommarea withRowSelection(CardDemoCommarea commarea, String toProgram,
                                                      String acctNo, String cardNum) {
        CardDemoCommarea base = withTarget(commarea, toProgram);
        long acctId = parseLongOrZero(acctNo);
        long card   = parseLongOrZero(cardNum);
        // CardInfo.cardNum is a 16-digit String (preserves leading zeros and supports
        // PAN masking per AAP §0.7.2); format the parsed long as a 16-digit zero-padded
        // string so the canonical constructor's all-digits validation passes.
        return base
                .withAccountInfo(new CardDemoCommarea.AccountInfo(acctId, base.accountInfo().acctStatus()))
                .withCardInfo(new CardDemoCommarea.CardInfo(String.format("%016d", card)));
    }

    private static long parseLongOrZero(String s) {
        if (s == null) return 0L;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return 0L;
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ============================================================================
    // String helpers
    // ============================================================================

    private static CoCrdLiInput coalesceInput(CoCrdLiInput input) {
        return input != null ? input : CoCrdLiInput.blank();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZeroes(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String clamp(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
