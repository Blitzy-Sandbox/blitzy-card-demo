package com.cardemo.service.menu;

import java.util.List;

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;

import org.springframework.stereotype.Service;

/**
 * Spring {@code @Service} that owns the <strong>administrator Admin Menu</strong>
 * routing metadata and reproduces the option-selection business logic of the
 * legacy AWS CardDemo CICS program {@code COADM01C} ("Admin Menu for Admin
 * users", transaction {@code CA00}).
 *
 * <p>This service is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP &sect;0.4.1 (tech-spec&nbsp;L637: <em>{@code service/menu/AdminMenuService.java}
 * CREATE &larr; {@code app/cbl/COADM01C.cbl}, {@code app/cpy/COADM02Y.cpy}
 * &mdash; "4-option routing metadata"</em>) and of AAP &sect;0.3.3 (Service-layer
 * pattern: each online COBOL program's business logic becomes a {@code @Service},
 * decoupled from I/O concerns). It carries no persistent state &mdash; the menu
 * table is fixed, compile-time-known reference data, so the class performs no
 * database access and depends on no repository or entity.</p>
 *
 * <p>It is the <strong>administrator twin</strong> of {@link MainMenuService},
 * and the two classes are deliberately structured identically so they read as a
 * matched pair. Three faithful differences distinguish this admin variant from
 * the regular-user main menu, each dictated by the COBOL source and called out
 * where it occurs:</p>
 * <ol>
 *   <li>The table has exactly <strong>4 options</strong>
 *       ({@code COUSR00C}..{@code COUSR03C}), not 10.</li>
 *   <li>The option record has <strong>no per-option user-type field</strong> and
 *       {@link #selectOption(String, CommArea)} has <strong>no per-option
 *       user-type gate</strong> &mdash; the {@code COADM01C}
 *       {@code PROCESS-ENTER-KEY} paragraph contains none. The whole menu is
 *       ADMIN-only, a restriction enforced upstream at sign-on
 *       ({@code COSGN00C}) and at the controller / security boundary; it is
 *       represented here purely as documented metadata
 *       ({@link #REQUIRED_USER_TYPE}, {@link #isAccessibleBy(UserType)}), never
 *       as a new in-flow check.</li>
 *   <li>The "coming soon" placeholder message <strong>omits the option name</strong>
 *       (the COBOL {@code STRING} statement has the {@code CDEMO-ADMIN-OPT-NAME}
 *       operand commented out), yielding exactly
 *       {@code "This option is coming soon ..."}.</li>
 * </ol>
 *
 * <h2>Authoritative COBOL source</h2>
 * <p>Two read-only mainframe artifacts define the behaviour reproduced here:</p>
 * <ul>
 *   <li><strong>{@code app/cpy/COADM02Y.cpy}</strong> ({@code 01
 *       CARDDEMO-ADMIN-MENU-OPTIONS}) &mdash; the static option table. The field
 *       {@code 05 CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} fixes the menu at
 *       <strong>exactly 4 options</strong>; each entry
 *       ({@code CDEMO-ADMIN-OPT-NUM PIC 9(02)},
 *       {@code CDEMO-ADMIN-OPT-NAME PIC X(35)},
 *       {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)}) maps to one
 *       {@link AdminMenuOption}. Unlike the regular-user menu copybook
 *       ({@code COMEN02Y}), the admin entry has <strong>no {@code usrtype}
 *       field</strong> &mdash; every admin option is implicitly ADMIN-only. The
 *       {@code REDEFINES} table is declared {@code OCCURS 9 TIMES}, but that 9 is
 *       mere array <em>capacity</em>: slots&nbsp;5..9 carry no data and are
 *       deliberately ignored &mdash; there are only 4 real options.</li>
 *   <li><strong>{@code app/cbl/COADM01C.cbl}</strong> &mdash; the CICS program.
 *       Its working storage fixes the program identity
 *       ({@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'},
 *       {@code WS-TRANID PIC X(04) VALUE 'CA00'}), surfaced here as
 *       {@link #PROGRAM_NAME} and {@link #TRANSACTION_ID}. Its
 *       {@code PROCESS-ENTER-KEY} paragraph is reproduced by
 *       {@link #selectOption(String, CommArea)}.</li>
 * </ul>
 *
 * <h2>{@code PROCESS-ENTER-KEY} &rarr; {@link #selectOption(String, CommArea)}</h2>
 * <p>The COBOL paragraph runs two steps in a fixed order; that order is preserved
 * exactly (AAP &sect;0.7.4), and COBOL paragraph fall-through is replaced by
 * explicit <em>early returns</em> so that no branch can leak into the next:</p>
 * <ol>
 *   <li><strong>Validate</strong> &mdash; {@code IF WS-OPTION IS NOT NUMERIC OR
 *       WS-OPTION &gt; CDEMO-ADMIN-OPT-COUNT OR WS-OPTION = ZEROS} &rarr; the error
 *       message {@code "Please enter a valid option number..."}.</li>
 *   <li><strong>Navigate</strong> &mdash; when no error flag is set: if the target
 *       program is not a {@code 'DUMMY'} placeholder the COMMAREA hand-off fields
 *       are stamped and {@code EXEC CICS XCTL} transfers control to it; a
 *       {@code 'DUMMY'} placeholder instead yields the green informational message
 *       {@code "This option is coming soon ..."}. (There is intentionally
 *       <em>no</em> user-type gate step between validate and navigate; see the
 *       class note above.)</li>
 * </ol>
 *
 * <h2>COBOL &rarr; Java substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>CICS COMMAREA + {@code XCTL} &rarr; {@link CommArea} context
 *       propagation (AAP &sect;0.1.2).</strong> The pseudo-conversational
 *       {@code EXEC CICS XCTL PROGRAM(target) COMMAREA(CARDDEMO-COMMAREA)} that
 *       both threaded state and transferred control in-process is replaced by
 *       mutating the propagated {@link CommArea} (the stateless / token-based
 *       session context) and <em>returning</em> the navigation target to the
 *       caller (a REST controller) rather than transferring control here.</li>
 *   <li><strong>{@code SEND MAP} screen text &rarr; returned message.</strong>
 *       The COBOL {@code PERFORM SEND-MENU-SCREEN} that repainted the BMS map with
 *       {@code WS-MESSAGE} becomes a message string on the returned
 *       {@link MenuSelectionResult}. The COBOL red-error ({@code WS-ERR-FLG = 'Y'})
 *       versus green-info ({@code DFHGREEN}) distinction is preserved minimally as
 *       {@link MessageSeverity}.</li>
 *   <li><strong>BMS {@code RECEIVE MAP} field &rarr; method argument.</strong> The
 *       {@code OPTIONI} input field is delivered as the {@code rawOption} argument;
 *       its parsing faithfully reproduces the COBOL right-justify,
 *       space&rarr;{@code '0'} fill, and {@code PIC 9(02)} coercion.</li>
 *   <li><strong>No floating point (AAP &sect;0.7.3).</strong> The menu has no
 *       decimal field; there is no {@code float}, {@code double} or
 *       {@link java.math.BigDecimal} anywhere in this class.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and
 * is never copied into this repository.</p>
 *
 * @see com.cardemo.model.dto.CommArea
 * @see com.cardemo.model.enums.UserType
 * @see MainMenuService
 */
@Service
public class AdminMenuService {

    /**
     * CICS transaction identifier of the administrator Admin Menu.
     *
     * <p>Mirrors {@code WS-TRANID PIC X(04) VALUE 'CA00'} in
     * {@code app/cbl/COADM01C.cbl}. Written into {@link CommArea#setFromTranId(String)}
     * on navigation, exactly as the COBOL moved {@code WS-TRANID} into
     * {@code CDEMO-FROM-TRANID}.</p>
     */
    public static final String TRANSACTION_ID = "CA00";

    /**
     * COBOL program name of the administrator Admin Menu.
     *
     * <p>Mirrors {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} in
     * {@code app/cbl/COADM01C.cbl}. Written into {@link CommArea#setFromProgram(String)}
     * on navigation, exactly as the COBOL moved {@code WS-PGMNAME} into
     * {@code CDEMO-FROM-PROGRAM}.</p>
     */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * The single user type permitted to reach this menu: {@link UserType#ADMIN}.
     *
     * <p>This is <strong>documented metadata</strong> expressing the menu-level
     * ADMIN restriction, not an in-flow gate. The legacy {@code COADM01C} program
     * is only ever reached because sign-on ({@code COSGN00C}) routes
     * administrators to it; the program's {@code PROCESS-ENTER-KEY} therefore
     * contains no per-option user-type check, and neither does
     * {@link #selectOption(String, CommArea)}. In the migrated system the actual
     * access enforcement lives at the controller / security boundary
     * ({@code SecurityConfig} / method security); {@link #isAccessibleBy(UserType)}
     * exposes this expectation for callers that wish to assert it.</p>
     */
    public static final UserType REQUIRED_USER_TYPE = UserType.ADMIN;

    /**
     * Verbatim COBOL validation-error text (red error in the BMS map).
     *
     * <p>Preserved exactly from {@code COADM01C} {@code PROCESS-ENTER-KEY}:
     * {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE}.</p>
     */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /**
     * Verbatim COBOL "coming soon" placeholder text (green info in the BMS map).
     *
     * <p>Preserved exactly from {@code COADM01C} {@code PROCESS-ENTER-KEY}. The
     * COBOL builds this with a {@code STRING} statement whose option-name operand
     * is <em>commented out</em>:</p>
     * <pre>{@code
     * STRING 'This option '       DELIMITED BY SIZE
     * *           CDEMO-ADMIN-OPT-NAME(WS-OPTION) DELIMITED BY SIZE
     *        'is coming soon ...' DELIMITED BY SIZE
     *   INTO WS-MESSAGE
     * }</pre>
     * <p>so the concatenation is {@code 'This option '} + {@code 'is coming soon
     * ...'} = {@code "This option is coming soon ..."} with <strong>no option
     * name</strong>. This deliberately differs from the regular-user
     * {@link MainMenuService}, whose equivalent message includes the option name.
     * The exact wording and spacing are retained for byte-faithful
     * external-interface parity (AAP &sect;0.7.2).</p>
     */
    private static final String MSG_COMING_SOON = "This option is coming soon ...";

    /**
     * First-five-character marker that flags a placeholder target program.
     *
     * <p>Mirrors the COBOL guard
     * {@code IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'}. None of the
     * 4 shipped options is a placeholder, so the "coming soon" branch never fires
     * for production data; the guard is preserved purely for behavioural
     * fidelity.</p>
     */
    private static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * Sentinel returned by {@link #parseOption(String)} for input that COBOL would
     * classify as {@code IS NOT NUMERIC}. Chosen so it can never collide with a
     * real 1-based option number (which are always {@code >= 1}).
     */
    private static final int NON_NUMERIC = -1;

    /**
     * Program-context value written on navigation.
     *
     * <p>Mirrors {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} in {@code COADM01C}; the
     * {@code 0} value is the COBOL {@code 88 CDEMO-PGM-ENTER} (first-entry) state
     * that the target program inspects when it is entered.</p>
     */
    private static final int PGM_CONTEXT_ENTER = 0;

    /**
     * Immutable description of a single Admin Menu option, mirroring one
     * {@code CDEMO-ADMIN-OPT} entry of {@code app/cpy/COADM02Y.cpy}.
     *
     * <p>Unlike the regular-user {@code MainMenuService.MenuOption}, this record has
     * <strong>no {@code allowedUserType} field</strong>: the COBOL admin option
     * group ({@code CDEMO-ADMIN-OPT-NUM} / {@code CDEMO-ADMIN-OPT-NAME} /
     * {@code CDEMO-ADMIN-OPT-PGMNAME}) carries no {@code usrtype} byte, because
     * every admin option is implicitly ADMIN-only (the menu-level restriction is
     * expressed by {@link #REQUIRED_USER_TYPE} / {@link #isAccessibleBy(UserType)}).</p>
     *
     * @param number        1-based option number ({@code CDEMO-ADMIN-OPT-NUM
     *                      PIC 9(02)})
     * @param name          human-readable option name, trimmed of the COBOL
     *                      {@code PIC X(35)} space padding
     *                      ({@code CDEMO-ADMIN-OPT-NAME}); the parenthetical
     *                      "(Security)" suffix is part of the canonical text
     * @param targetProgram name of the COBOL program the option routes to
     *                      ({@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)})
     */
    public record AdminMenuOption(int number, String name, String targetProgram) {
    }

    /**
     * Minimal severity classification carried on a {@link MenuSelectionResult},
     * preserving the COBOL presentation distinction between a red error
     * ({@code WS-ERR-FLG = 'Y'}) and a green informational message
     * ({@code DFHGREEN}) without reproducing 3270 colour attributes. Mirrors the
     * equivalent type on {@link MainMenuService} for cross-service consistency.
     */
    public enum MessageSeverity {

        /** No message accompanies the result (a successful navigation). */
        NONE,

        /** An error message (the COBOL red {@code WS-ERR-FLG} path): a failed validation. */
        ERROR,

        /**
         * An informational message (the COBOL green {@code DFHGREEN} path): the
         * "coming soon" placeholder notice.
         */
        INFO
    }

    /**
     * Immutable outcome of {@link #selectOption(String, CommArea)}, modelling the
     * observable results of {@code COADM01C} {@code PROCESS-ENTER-KEY}. Mirrors the
     * equivalent record on {@link MainMenuService} for cross-service consistency.
     *
     * @param navigate      {@code true} when the selection routes to a target
     *                      program (the COBOL {@code XCTL} path); {@code false} when
     *                      the menu would be redisplayed with a message
     * @param targetProgram the program to route to when {@code navigate} is
     *                      {@code true}; {@code null} otherwise
     * @param message       the screen message to display when {@code navigate} is
     *                      {@code false} (validation error or "coming soon" notice);
     *                      {@code null} on navigation
     * @param severity      the {@link MessageSeverity} of {@code message}
     *                      ({@link MessageSeverity#NONE} on navigation)
     */
    public record MenuSelectionResult(boolean navigate, String targetProgram, String message,
            MessageSeverity severity) {
    }

    /**
     * The fixed, ordered table of the 4 administrator Admin Menu options.
     *
     * <p>This is the Java mirror of {@code 05 CDEMO-ADMIN-OPTIONS-DATA} in
     * {@code app/cpy/COADM02Y.cpy}: exactly 4 entries, in option-number order
     * 1..4, with the names trimmed of their {@code PIC X(35)} padding (the
     * "(Security)" suffix retained verbatim) and the 8-character target program
     * names verbatim. (The COBOL {@code REDEFINES} table's {@code OCCURS 9 TIMES}
     * capacity is irrelevant &mdash; there is no 5th..9th option.) Built with
     * {@link List#of} so the table is deeply immutable and safe to share across
     * all callers and threads.</p>
     */
    private static final List<AdminMenuOption> ADMIN_MENU_OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)", "COUSR00C"),
            new AdminMenuOption(2, "User Add (Security)", "COUSR01C"),
            new AdminMenuOption(3, "User Update (Security)", "COUSR02C"),
            new AdminMenuOption(4, "User Delete (Security)", "COUSR03C"));

    /**
     * Returns the fixed, ordered table of all 4 Admin Menu options.
     *
     * <p>Mirrors the COBOL {@code BUILD-MENU-OPTIONS} behaviour, which displays
     * every option from 1 to {@code CDEMO-ADMIN-OPT-COUNT}. The returned list is
     * immutable.</p>
     *
     * @return the immutable, order-preserving list of 4 admin menu options
     */
    public List<AdminMenuOption> getMenuOptions() {
        return ADMIN_MENU_OPTIONS;
    }

    /**
     * Returns the number of Admin Menu options.
     *
     * <p>Equals the COBOL {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}. Derived
     * from {@link #ADMIN_MENU_OPTIONS} size so the count and the table can never
     * drift apart; for the shipped data this is always {@code 4}.</p>
     *
     * @return the option count (4)
     */
    public int getOptionCount() {
        return ADMIN_MENU_OPTIONS.size();
    }

    /**
     * Indicates whether the given user type is permitted to reach this
     * ADMIN-only menu.
     *
     * <p>This exposes the menu-level restriction documented on
     * {@link #REQUIRED_USER_TYPE} as a convenience predicate; it is equivalent to
     * {@code userType != null && userType.isAdmin()}. It is <strong>not</strong>
     * invoked inside {@link #selectOption(String, CommArea)} &mdash; the legacy
     * {@code COADM01C} {@code PROCESS-ENTER-KEY} performs no per-option user-type
     * check, and the real access enforcement lives at the controller / security
     * boundary. Callers (for example a controller pre-check) may use this to
     * assert eligibility before invoking the menu.</p>
     *
     * @param userType the user type to test (may be {@code null})
     * @return {@code true} only when {@code userType} is {@link UserType#ADMIN};
     *         {@code false} for {@link UserType#USER} or {@code null}
     */
    public boolean isAccessibleBy(final UserType userType) {
        // Null-safe: null == ADMIN is false, so a missing user type is never admin.
        return userType == REQUIRED_USER_TYPE;
    }

    /**
     * Reproduces {@code COADM01C} {@code PROCESS-ENTER-KEY}: validates a raw menu
     * selection and either routes to the target program or yields a screen
     * message.
     *
     * <p>The COBOL steps run in their original order &mdash; validate &rarr;
     * navigate &mdash; and each terminating branch is an explicit early return, so
     * control never falls through from one step into the next (AAP &sect;0.7.4).
     * There is deliberately <strong>no user-type gate</strong> between validation
     * and navigation: {@code COADM01C} contains none (the menu is ADMIN-only by
     * virtue of sign-on routing, enforced upstream), so none is added here.</p>
     *
     * @param rawOption the raw option text from the menu input field (the BMS
     *                  {@code OPTIONI} field); {@code null}, blank and non-numeric
     *                  values are handled exactly as the COBOL did
     * @param commArea  the propagated pseudo-conversational context (the COMMAREA
     *                  replacement); on a successful navigation its hand-off fields
     *                  are updated in place
     * @return the {@link MenuSelectionResult} describing the outcome
     */
    public MenuSelectionResult selectOption(final String rawOption, final CommArea commArea) {
        // COBOL substitution: the BMS RECEIVE MAP field OPTIONI arrives as a String
        // argument; parsing mirrors WS-OPTION-X PIC X(02) JUST RIGHT + INSPECT
        // REPLACING ALL ' ' BY '0' + MOVE TO WS-OPTION PIC 9(02).
        final int option = parseOption(rawOption);

        // Step 1 -- validation. COBOL: IF WS-OPTION IS NOT NUMERIC OR
        //   WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR WS-OPTION = ZEROS.
        // The early return both reproduces the error path and guards the
        // out-of-range table subscript that the COBOL fall-through risked.
        if (option == NON_NUMERIC || option > getOptionCount() || option == 0) {
            // COBOL substitution: SEND MAP error text -> returned message (red error).
            return new MenuSelectionResult(false, null, MSG_INVALID_OPTION, MessageSeverity.ERROR);
        }

        // Option numbers are 1..4 by construction, so a validated option maps
        // directly to the (option - 1) zero-based list index.
        final AdminMenuOption selected = ADMIN_MENU_OPTIONS.get(option - 1);

        // (No user-type gate step -- faithful to COADM01C, which omits it.)

        // Step 2a -- DUMMY placeholder guard. COBOL:
        //   IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY' ... (navigate)
        //   ELSE (implicitly) build the "coming soon" message. Because XCTL transfers
        //   control away, the "coming soon" text is reachable only for a DUMMY
        //   placeholder; none of the 4 shipped options is DUMMY, so this never fires
        //   for production data but is retained verbatim for fidelity.
        final String targetProgram = selected.targetProgram();
        if (targetProgram != null && targetProgram.startsWith(DUMMY_PROGRAM_PREFIX)) {
            // COBOL substitution: STRING ... INTO WS-MESSAGE + DFHGREEN
            //   -> returned informational (green) message. Note: the admin variant
            //   omits the option name (the COBOL name operand is commented out).
            return new MenuSelectionResult(false, null, MSG_COMING_SOON, MessageSeverity.INFO);
        }

        // Step 2b -- navigate. COBOL: MOVE WS-TRANID -> CDEMO-FROM-TRANID,
        //   WS-PGMNAME -> CDEMO-FROM-PROGRAM, ZEROS -> CDEMO-PGM-CONTEXT, then
        //   EXEC CICS XCTL PROGRAM(target) COMMAREA(CARDDEMO-COMMAREA).
        // COBOL substitution: the COMMAREA hand-off + in-process XCTL transfer is
        //   replaced by mutating the propagated CommArea context (stateless REST,
        //   AAP §0.1.2 / §0.7) and returning the navigation target to the caller.
        commArea.setFromTranId(TRANSACTION_ID);
        commArea.setFromProgram(PROGRAM_NAME);
        commArea.setProgramContext(PGM_CONTEXT_ENTER);
        commArea.setToProgram(targetProgram);
        return new MenuSelectionResult(true, targetProgram, null, MessageSeverity.NONE);
    }

    /**
     * Parses the raw menu-option input exactly as {@code COADM01C}
     * {@code PROCESS-ENTER-KEY} does.
     *
     * <p>The COBOL right-justifies the trimmed input into {@code WS-OPTION-X
     * PIC X(02) JUST RIGHT}, runs {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY
     * '0'} (so a blank field becomes {@code "00"}), and moves the result into
     * {@code WS-OPTION PIC 9(02)}. The downstream test {@code IF WS-OPTION IS NOT
     * NUMERIC} then rejects any non-digit content. This method reproduces that
     * observable behaviour:</p>
     * <ul>
     *   <li>{@code null} or all-blank input &rarr; {@code 0} (the COBOL "00", which
     *       the caller's {@code = ZEROS} check rejects);</li>
     *   <li>any input containing a non-{@code 0-9} character &rarr;
     *       {@link #NON_NUMERIC} (the COBOL {@code IS NOT NUMERIC} case);</li>
     *   <li>otherwise the numeric value of the digits.</li>
     * </ul>
     *
     * @param rawOption the raw option text (may be {@code null})
     * @return the parsed option value, {@code 0} for blank, or {@link #NON_NUMERIC}
     *         for non-numeric input
     */
    private static int parseOption(final String rawOption) {
        final String trimmed = (rawOption == null) ? "" : rawOption.trim();
        if (trimmed.isEmpty()) {
            // COBOL: a blank field becomes "00" -> 0 (rejected later by the = ZEROS check).
            return 0;
        }
        // COBOL "IS NUMERIC" accepts only the digits 0-9; reproduce that exactly
        // rather than using Character.isDigit (which also accepts non-ASCII digits).
        for (int i = 0; i < trimmed.length(); i++) {
            final char ch = trimmed.charAt(i);
            if (ch < '0' || ch > '9') {
                return NON_NUMERIC;
            }
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (final NumberFormatException ex) {
            // An all-digit string too large for int overflows the 2-digit COBOL
            // field; treat it as non-numeric so range validation rejects it.
            return NON_NUMERIC;
        }
    }
}
