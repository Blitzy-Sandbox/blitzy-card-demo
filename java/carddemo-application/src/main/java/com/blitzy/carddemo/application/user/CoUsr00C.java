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
package com.blitzy.carddemo.application.user;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.record.SecUserData;
import com.blitzy.carddemo.domain.port.UserSecurityRepository;
import com.blitzy.carddemo.domain.commarea.PgmContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Java translation of COBOL program {@code COUSR00C}
 * ({@code app/cbl/COUSR00C.cbl}, CICS transaction {@code CU00}).
 *
 * <p><b>Purpose:</b> List all users from the USRSEC store with 10-row
 * pagination. Operator selects a row by typing 'U' (update) or 'D'
 * (delete) into a row's SEL column; controller XCTLs to COUSR02C or
 * COUSR03C respectively, carrying the selected user-id.
 *
 * <h2>Translation notes</h2>
 * <p>COBOL paragraphs {@code STARTBR-USER-SEC-FILE},
 * {@code READNEXT-USER-SEC-FILE}, {@code READPREV-USER-SEC-FILE},
 * {@code ENDBR-USER-SEC-FILE} (the VSAM cursor model) are translated
 * to stream-based scans with skip/take semantics, externalizing the
 * pagination state to a {@link PageState} parameter that replaces the
 * CDEMO-CU00-INFO commarea extension (no commarea schema change).
 *
 * <p>Verbatim error messages preserved per AAP &sect;0.7.1.
 */
@CobolProgram(
        value = "COUSR00C",
        sourcePath = "app/cbl/COUSR00C.cbl",
        notes = "User list with PF7/PF8 pagination. STARTBR/READNEXT/" +
                "READPREV/ENDBR translated to stream-based scan with " +
                "skip/take. Pagination state via separate PageState " +
                "parameter (no commarea schema change for CDEMO-CU00-INFO)."
)
public final class CoUsr00C {

    private static final String PROGRAM_ID = "COUSR00C";
    private static final String TRANSACTION_ID = "CU00";
    private static final int ROWS_PER_PAGE = 10;

    // Verbatim error messages preserved from COBOL source per AAP §0.7.1.
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid values are U and D";
    private static final String MSG_AT_TOP = "You are already at the top of the page...";
    private static final String MSG_AT_BOTTOM = "You are already at the bottom of the page...";
    private static final String MSG_TOP = "You are at the top of the page...";
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";
    private static final String MSG_REACHED_TOP = "You have reached the top of the page...";
    private static final String MSG_LOOKUP_ERROR = "Unable to lookup User...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final UserSecurityRepository userSecurity;
    private final ProgramRegistry programRegistry;

    public CoUsr00C(UserSecurityRepository userSecurity, ProgramRegistry programRegistry) {
        this.userSecurity = userSecurity;
        this.programRegistry = programRegistry;
    }

    /**
     * Result wrapper signalling either a SEND-MAP (output + updated
     * commarea + updated page state) or an XCTL (target program + updated
     * commarea).
     */
    public record Result(
            CoUsr00Output output,
            CardDemoCommarea commarea,
            PageState pageState,
            String xctlTo) {

        public static Result sendMap(CoUsr00Output output, CardDemoCommarea commarea,
                                     PageState pageState) {
            return new Result(output, commarea, pageState, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, null, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    /**
     * Externalized pagination state: replaces CDEMO-CU00-USRID-FIRST /
     * CDEMO-CU00-USRID-LAST / CDEMO-CU00-PAGE-NUM / CDEMO-CU00-NEXT-PAGE-FLG
     * (no commarea schema change).
     */
    public record PageState(int pageNum, String userIdFirst, String userIdLast,
                            boolean nextPageYes) {

        public PageState {
            userIdFirst = userIdFirst == null ? "" : userIdFirst;
            userIdLast = userIdLast == null ? "" : userIdLast;
        }

        public static PageState initial() {
            return new PageState(0, "", "", false);
        }
    }

    /**
     * Convenience overload with default initial page state.
     */
    public Result run(CoUsr00Input input, CardDemoCommarea commarea) {
        return run(input, commarea, PageState.initial());
    }

    /**
     * Entry point. Mirrors COBOL {@code MAIN-PARA}.
     */
    public Result run(CoUsr00Input input, CardDemoCommarea commarea, PageState pageState) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        if (pageState == null) {
            pageState = PageState.initial();
        }

        // First-time entry: process the "enter key" path which does an initial
        // forward scan from start (COBOL invokes PROCESS-ENTER-KEY before
        // showing the screen on first entry).
        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            PageState reset = new PageState(0, "", "", pageState.nextPageYes());
            // Initial scan from the start of the file with no filter.
            return processPageForward(CoUsr00Input.blank(), reentered, reset, "");
        }

        // Re-entry: dispatch by AID key.
        if (input == null) {
            return Result.sendMap(buildScreen(CoUsr00Input.blank(), "", pageState), commarea, pageState);
        }

        return switch (input.aidKey()) {
            case ENTER -> processEnterKey(input, commarea, pageState);
            case PF03_BACK -> {
                String target = (commarea.generalInfo().toProgram() != null
                        && !commarea.generalInfo().toProgram().isBlank())
                        ? commarea.generalInfo().toProgram()
                        : ProgramRegistry.CO_ADM_01C;
                yield Result.xctl(target, withTarget(commarea, target));
            }
            case PF07_PREV -> processPf7Key(input, commarea, pageState);
            case PF08_NEXT -> processPf8Key(input, commarea, pageState);
            case OTHER -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY, pageState), commarea, pageState);
        };
    }

    /**
     * COBOL paragraph {@code PROCESS-ENTER-KEY}. Scans rows 1-10 for a
     * selection; dispatches to COUSR02C ('U'/'u') or COUSR03C ('D'/'d');
     * otherwise re-scans the user list using USRIDINI as the start key.
     */
    private Result processEnterKey(CoUsr00Input input, CardDemoCommarea commarea, PageState pageState) {
        int sel = input.firstSelectedRow();
        if (sel >= 1) {
            CoUsr00Input.Row row = input.rows().get(sel - 1);
            String flag = row.selection();
            String selectedUserId = row.userId();
            if (flag != null && !flag.isEmpty() && !selectedUserId.isBlank()) {
                if (flag.equals("U") || flag.equals("u")) {
                    CardDemoCommarea outbound = withTarget(commarea, ProgramRegistry.CO_USR_02C);
                    return Result.xctl(ProgramRegistry.CO_USR_02C, outbound);
                }
                if (flag.equals("D") || flag.equals("d")) {
                    CardDemoCommarea outbound = withTarget(commarea, ProgramRegistry.CO_USR_03C);
                    return Result.xctl(ProgramRegistry.CO_USR_03C, outbound);
                }
                return Result.sendMap(buildScreen(input, MSG_INVALID_SELECTION, pageState),
                        commarea, pageState);
            }
        }

        // No row selected: re-scan starting from USRIDINI.
        String startKey = isBlankOrLow(input.userIdSearch()) ? "" : input.userIdSearch().trim();
        PageState reset = new PageState(0, "", "", pageState.nextPageYes());
        return processPageForward(input, commarea, reset, startKey);
    }

    /**
     * COBOL paragraph {@code PROCESS-PF7-KEY}.
     */
    private Result processPf7Key(CoUsr00Input input, CardDemoCommarea commarea, PageState pageState) {
        if (pageState.pageNum() > 1) {
            String startKey = isBlankOrLow(pageState.userIdFirst()) ? "" : pageState.userIdFirst().trim();
            return processPageBackward(input, commarea, pageState, startKey);
        }
        return Result.sendMap(buildScreen(input, MSG_AT_TOP, pageState), commarea, pageState);
    }

    /**
     * COBOL paragraph {@code PROCESS-PF8-KEY}.
     */
    private Result processPf8Key(CoUsr00Input input, CardDemoCommarea commarea, PageState pageState) {
        if (pageState.nextPageYes()) {
            String startKey = isBlankOrLow(pageState.userIdLast()) ? "\uFFFF" : pageState.userIdLast().trim();
            return processPageForward(input, commarea, pageState, startKey);
        }
        return Result.sendMap(buildScreen(input, MSG_AT_BOTTOM, pageState), commarea, pageState);
    }

    /**
     * COBOL paragraph {@code PROCESS-PAGE-FORWARD}. Translates the VSAM
     * STARTBR + READNEXT loop to a stream-based skip/take operation.
     */
    private Result processPageForward(CoUsr00Input input, CardDemoCommarea commarea,
                                       PageState pageState, String startKey) {
        List<SecUserData> users = new ArrayList<>(ROWS_PER_PAGE);
        boolean morePages = false;
        try (Stream<SecUserData> stream = userSecurity.streamSequential()) {
            List<SecUserData> all = stream
                    .filter(u -> startKey.isEmpty()
                            ? true
                            : compareUserId(u.secUsrId(), startKey) > 0
                                    || (compareUserId(u.secUsrId(), startKey) == 0 && pageState.pageNum() == 0))
                    .toList();
            // On first entry (pageNum=0) we want to include the start key itself;
            // on PF8 we want strictly greater. Above filter handles pageNum=0
            // inclusive case; for PF8 use exclusive (handled by startKey being last).
            for (int i = 0; i < all.size() && users.size() < ROWS_PER_PAGE; i++) {
                users.add(all.get(i));
            }
            morePages = all.size() > users.size();
        }

        if (users.isEmpty() && pageState.pageNum() == 0) {
            // No matching users (COBOL DFHRESP(NOTFND) path).
            return Result.sendMap(buildScreen(input, MSG_TOP, pageState), commarea, pageState);
        }

        String firstId = users.isEmpty() ? "" : users.get(0).secUsrId();
        String lastId = users.isEmpty() ? "" : users.get(users.size() - 1).secUsrId();
        int newPageNum = pageState.pageNum() + 1;
        PageState newState = new PageState(newPageNum, firstId, lastId, morePages);

        return Result.sendMap(buildPageScreen(input, users, newState), commarea, newState);
    }

    /**
     * COBOL paragraph {@code PROCESS-PAGE-BACKWARD}.
     */
    private Result processPageBackward(CoUsr00Input input, CardDemoCommarea commarea,
                                        PageState pageState, String endKey) {
        List<SecUserData> all;
        try (Stream<SecUserData> stream = userSecurity.streamSequential()) {
            all = stream.toList();
        }

        // Find the index of the row whose userId equals endKey, then take the
        // previous ROWS_PER_PAGE rows.
        int endIdx = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (compareUserId(all.get(i).secUsrId(), endKey) < 0) {
                endIdx = i + 1;
                break;
            }
        }
        if (endIdx < 0) {
            // No predecessors: at top.
            return Result.sendMap(buildScreen(input, MSG_REACHED_TOP, pageState), commarea, pageState);
        }

        int startIdx = Math.max(0, endIdx - ROWS_PER_PAGE);
        List<SecUserData> users = all.subList(startIdx, endIdx);
        String firstId = users.isEmpty() ? "" : users.get(0).secUsrId();
        String lastId = users.isEmpty() ? "" : users.get(users.size() - 1).secUsrId();
        int newPageNum = Math.max(1, pageState.pageNum() - 1);
        PageState newState = new PageState(newPageNum, firstId, lastId, pageState.nextPageYes());

        return Result.sendMap(buildPageScreen(input, users, newState), commarea, newState);
    }

    /**
     * Builds a SEND-MAP output that simply echoes the existing input and
     * an optional error message (no row data refresh).
     */
    private CoUsr00Output buildScreen(CoUsr00Input input, String errMsg, PageState pageState) {
        List<CoUsr00Output.UserRow> rows = new ArrayList<>(ROWS_PER_PAGE);
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            CoUsr00Input.Row r = input.rows().get(i);
            rows.add(new CoUsr00Output.UserRow(r.selection(), r.userId(), r.firstName(),
                    r.lastName(), r.userType()));
        }
        return new CoUsr00Output(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "List Users",
                nowTime(),
                String.format("%08d", pageState.pageNum()),
                input.userIdSearch(),
                rows,
                errMsg
        );
    }

    /**
     * Builds a SEND-MAP output with refreshed row data (after STARTBR /
     * READNEXT or READPREV scan).
     */
    private CoUsr00Output buildPageScreen(CoUsr00Input input, List<SecUserData> users,
                                          PageState pageState) {
        List<CoUsr00Output.UserRow> rows = new ArrayList<>(ROWS_PER_PAGE);
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            if (i < users.size()) {
                SecUserData u = users.get(i);
                rows.add(new CoUsr00Output.UserRow(
                        "",                       // selection cleared after refresh
                        u.secUsrId(),
                        u.secUsrFname(),
                        u.secUsrLname(),
                        // SEC-USR-TYPE is a single COBOL X(01) char; the BMS row carries
                        // it as a 1-char String to match the PIC X(1) BMS leaf.
                        String.valueOf(u.secUsrType())));
            } else {
                rows.add(CoUsr00Output.UserRow.empty());
            }
        }
        return new CoUsr00Output(
                TRANSACTION_ID,
                "AWS Mainframe Modernization with CardDemo Application",
                todayDate(),
                PROGRAM_ID,
                "List Users",
                nowTime(),
                String.format("%08d", pageState.pageNum()),
                "",                                // userIdSearch cleared after refresh
                rows,
                ""
        );
    }

    // ----- Helpers --------------------------------------------------------

    private static int compareUserId(String a, String b) {
        return (a == null ? "" : a.trim()).compareTo(b == null ? "" : b.trim());
    }

    private static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\0') return false;
        }
        return true;
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

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
