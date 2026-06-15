package com.cardemo.service.menu;

import java.util.List;

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;

import org.springframework.stereotype.Service;

/**
 * Spring {@code @Service} that owns the <strong>regular-user Main Menu</strong>
 * routing metadata and reproduces the option-selection business logic of the
 * legacy AWS CardDemo CICS program {@code COMEN01C} ("Main Menu for the Regular
 * users", transaction {@code CM00}).
 *
 * <p>This service is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP &sect;0.4.1 (tech-spec&nbsp;L636: <em>{@code service/menu/MainMenuService.java}
 * CREATE &larr; {@code app/cbl/COMEN01C.cbl}, {@code app/cpy/COMEN02Y.cpy}
 * &mdash; "10-option routing metadata"</em>) and of AAP &sect;0.3.3
 * (Service-layer pattern: each online COBOL program's business logic becomes a
 * {@code @Service}, decoupled from I/O concerns). It carries no persistent
 * state &mdash; the menu table is fixed, compile-time-known reference data, so
 * the class performs no database access and depends on no repository or entity.</p>
 *
 * <h2>Authoritative COBOL source</h2>
 * <p>Two read-only mainframe artifacts define the behaviour reproduced here:</p>
 * <ul>
 *   <li><strong>{@code app/cpy/COMEN02Y.cpy}</strong> ({@code 01
 *       CARDDEMO-MAIN-MENU-OPTIONS}) &mdash; the static option table. The field
 *       {@code 05 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} fixes the menu at
 *       <strong>exactly 10 options</strong>; each entry
 *       ({@code CDEMO-MENU-OPT-NUM PIC 9(02)},
 *       {@code CDEMO-MENU-OPT-NAME PIC X(35)},
 *       {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)},
 *       {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)}) maps to one {@link MenuOption}.
 *       The {@code REDEFINES} table is declared {@code OCCURS 12 TIMES}, but that
 *       12 is mere array <em>capacity</em>: slots&nbsp;11 and&nbsp;12 carry no
 *       data and are deliberately ignored &mdash; there are only 10 real
 *       options.</li>
 *   <li><strong>{@code app/cbl/COMEN01C.cbl}</strong> &mdash; the CICS program.
 *       Its working storage fixes the program identity
 *       ({@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'},
 *       {@code WS-TRANID PIC X(04) VALUE 'CM00'}), surfaced here as
 *       {@link #PROGRAM_NAME} and {@link #TRANSACTION_ID}. Its
 *       {@code PROCESS-ENTER-KEY} paragraph is reproduced by
 *       {@link #selectOption(String, CommArea)}.</li>
 * </ul>
 *
 * <h2>{@code PROCESS-ENTER-KEY} &rarr; {@link #selectOption(String, CommArea)}</h2>
 * <p>The COBOL paragraph runs three steps in a fixed order; that order is
 * preserved exactly (AAP &sect;0.7.4), and COBOL paragraph fall-through is
 * replaced by explicit <em>early returns</em> so that no branch can leak into
 * the next:</p>
 * <ol>
 *   <li><strong>Validate</strong> &mdash; {@code IF WS-OPTION IS NOT NUMERIC OR
 *       WS-OPTION &gt; CDEMO-MENU-OPT-COUNT OR WS-OPTION = ZEROS} &rarr; the error
 *       message {@code "Please enter a valid option number..."}.</li>
 *   <li><strong>User-type gate</strong> &mdash; {@code IF CDEMO-USRTYP-USER AND
 *       CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'} &rarr; the error message
 *       {@code "No access - Admin Only option... "} (a regular user may not pick
 *       an admin-only option).</li>
 *   <li><strong>Navigate</strong> &mdash; when no error flag is set: if the
 *       target program is not a {@code 'DUMMY'} placeholder the COMMAREA hand-off
 *       fields are stamped and {@code EXEC CICS XCTL} transfers control to it;
 *       a {@code 'DUMMY'} placeholder instead yields the green informational
 *       message {@code "This option <name> is coming soon ..."}.</li>
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
 *       The COBOL {@code PERFORM SEND-MENU-SCREEN} that repainted the BMS map
 *       with {@code WS-MESSAGE} becomes a message string on the returned
 *       {@link MenuSelectionResult}. The COBOL red-error
 *       ({@code WS-ERR-FLG = 'Y'}) versus green-info ({@code DFHGREEN})
 *       distinction is preserved minimally as {@link MessageSeverity}.</li>
 *   <li><strong>BMS {@code RECEIVE MAP} field &rarr; method argument.</strong>
 *       The {@code OPTIONI} input field is delivered as the {@code rawOption}
 *       argument; its parsing faithfully reproduces the COBOL right-justify,
 *       space&rarr;{@code '0'} fill, and {@code PIC 9(02)} coercion.</li>
 *   <li><strong>No floating point (AAP &sect;0.7.3).</strong> The menu has no
 *       decimal field; there is no {@code float}, {@code double} or
 *       {@link java.math.BigDecimal} anywhere in this class.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see com.cardemo.model.dto.CommArea
 * @see com.cardemo.model.enums.UserType
 */
@Service
public class MainMenuService {

    /**
     * CICS transaction identifier of the regular-user Main Menu.
     *
     * <p>Mirrors {@code WS-TRANID PIC X(04) VALUE 'CM00'} in
     * {@code app/cbl/COMEN01C.cbl}. Written into {@link CommArea#setFromTranId(String)}
     * on navigation, exactly as the COBOL moved {@code WS-TRANID} into
     * {@code CDEMO-FROM-TRANID}.</p>
     */
    public static final String TRANSACTION_ID = "CM00";

    /**
     * COBOL program name of the regular-user Main Menu.
     *
     * <p>Mirrors {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} in
     * {@code app/cbl/COMEN01C.cbl}. Written into {@link CommArea#setFromProgram(String)}
     * on navigation, exactly as the COBOL moved {@code WS-PGMNAME} into
     * {@code CDEMO-FROM-PROGRAM}.</p>
     */
    public static final String PROGRAM_NAME = "COMEN01C";

    /**
     * Verbatim COBOL validation-error text (red error in the BMS map).
     *
     * <p>Preserved exactly from {@code COMEN01C} {@code PROCESS-ENTER-KEY}:
     * {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE}.</p>
     */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /**
     * Verbatim COBOL admin-only-access text (red error in the BMS map).
     *
     * <p>Preserved exactly from {@code COMEN01C} {@code PROCESS-ENTER-KEY}:
     * {@code MOVE 'No access - Admin Only option... ' TO WS-MESSAGE}. The trailing
     * space is part of the original literal and is retained for byte-faithful
     * external-interface parity (AAP &sect;0.7.2).</p>
     */
    private static final String MSG_ADMIN_ONLY = "No access - Admin Only option... ";

    /**
     * First-five-character marker that flags a placeholder target program.
     *
     * <p>Mirrors the COBOL guard
     * {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'}. None of the
     * 10 shipped options is a placeholder, so the "coming soon" branch never fires
     * for production data; the guard is preserved purely for behavioural fidelity.</p>
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
     * <p>Mirrors {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} in {@code COMEN01C}; the
     * {@code 0} value is the COBOL {@code 88 CDEMO-PGM-ENTER} (first-entry) state
     * that the target program inspects when it is entered.</p>
     */
    private static final int PGM_CONTEXT_ENTER = 0;

    /**
     * Immutable description of a single Main Menu option, mirroring one
     * {@code CDEMO-MENU-OPT} entry of {@code app/cpy/COMEN02Y.cpy}.
     *
     * @param number          1-based option number ({@code CDEMO-MENU-OPT-NUM
     *                         PIC 9(02)})
     * @param name            human-readable option name, trimmed of the COBOL
     *                        {@code PIC X(35)} space padding
     *                        ({@code CDEMO-MENU-OPT-NAME})
     * @param targetProgram   name of the COBOL program the option routes to
     *                        ({@code CDEMO-MENU-OPT-PGMNAME PIC X(08)})
     * @param allowedUserType the user type permitted to select the option
     *                        ({@code CDEMO-MENU-OPT-USRTYPE PIC X(01)}; {@code 'U'}
     *                        &rarr; {@link UserType#USER}, {@code 'A'} &rarr;
     *                        {@link UserType#ADMIN})
     */
    public record MenuOption(int number, String name, String targetProgram, UserType allowedUserType) {
    }

    /**
     * Minimal severity classification carried on a {@link MenuSelectionResult},
     * preserving the COBOL presentation distinction between a red error
     * ({@code WS-ERR-FLG = 'Y'}) and a green informational message
     * ({@code DFHGREEN}) without reproducing 3270 colour attributes.
     */
    public enum MessageSeverity {

        /** No message accompanies the result (a successful navigation). */
        NONE,

        /**
         * An error message (the COBOL red {@code WS-ERR-FLG} path): a failed
         * validation or a denied admin-only selection.
         */
        ERROR,

        /**
         * An informational message (the COBOL green {@code DFHGREEN} path): the
         * "coming soon" placeholder notice.
         */
        INFO
    }

    /**
     * Immutable outcome of {@link #selectOption(String, CommArea)}, modelling the
     * three observable results of {@code COMEN01C} {@code PROCESS-ENTER-KEY}.
     *
     * @param navigate      {@code true} when the selection routes to a target
     *                      program (the COBOL {@code XCTL} path); {@code false}
     *                      when the menu would be redisplayed with a message
     * @param targetProgram the program to route to when {@code navigate} is
     *                      {@code true}; {@code null} otherwise
     * @param message       the screen message to display when {@code navigate} is
     *                      {@code false} (validation error, admin-only denial, or
     *                      "coming soon" notice); {@code null} on navigation
     * @param severity      the {@link MessageSeverity} of {@code message}
     *                      ({@link MessageSeverity#NONE} on navigation)
     */
    public record MenuSelectionResult(boolean navigate, String targetProgram, String message,
            MessageSeverity severity) {
    }

    /**
     * The fixed, ordered table of the 10 regular-user Main Menu options.
     *
     * <p>This is the Java mirror of {@code 05 CDEMO-MENU-OPTIONS-DATA} in
     * {@code app/cpy/COMEN02Y.cpy}: exactly 10 entries, in option-number order
     * 1..10, with the names trimmed of their {@code PIC X(35)} padding, the
     * 8-character target program names verbatim, and every {@code usrtype} of
     * {@code 'U'} mapped to {@link UserType#USER}. (The COBOL {@code REDEFINES}
     * table's {@code OCCURS 12 TIMES} capacity is irrelevant &mdash; there is no
     * 11th or 12th option.) Built with {@link List#of} so the table is deeply
     * immutable and safe to share across all callers and threads.</p>
     */
    private static final List<MenuOption> MENU_OPTIONS = List.of(
            new MenuOption(1, "Account View", "COACTVWC", UserType.USER),
            new MenuOption(2, "Account Update", "COACTUPC", UserType.USER),
            new MenuOption(3, "Credit Card List", "COCRDLIC", UserType.USER),
            new MenuOption(4, "Credit Card View", "COCRDSLC", UserType.USER),
            new MenuOption(5, "Credit Card Update", "COCRDUPC", UserType.USER),
            new MenuOption(6, "Transaction List", "COTRN00C", UserType.USER),
            new MenuOption(7, "Transaction View", "COTRN01C", UserType.USER),
            new MenuOption(8, "Transaction Add", "COTRN02C", UserType.USER),
            new MenuOption(9, "Transaction Reports", "CORPT00C", UserType.USER),
            new MenuOption(10, "Bill Payment", "COBIL00C", UserType.USER));

    /**
     * Returns the fixed, ordered table of all 10 Main Menu options.
     *
     * <p>Mirrors the COBOL {@code BUILD-MENU-OPTIONS} behaviour, which displays
     * every option from 1 to {@code CDEMO-MENU-OPT-COUNT} regardless of the
     * signed-in user's type &mdash; per-option user-type eligibility is enforced
     * only at <em>selection</em> (see {@link #selectOption(String, CommArea)}),
     * never at display. The returned list is immutable.</p>
     *
     * @return the immutable, order-preserving list of 10 menu options
     */
    public List<MenuOption> getMenuOptions() {
        return MENU_OPTIONS;
    }

    /**
     * Returns the number of Main Menu options.
     *
     * <p>Equals the COBOL {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10}. Derived
     * from {@link #MENU_OPTIONS} size so the count and the table can never drift
     * apart; for the shipped data this is always {@code 10}.</p>
     *
     * @return the option count (10)
     */
    public int getOptionCount() {
        return MENU_OPTIONS.size();
    }

    /**
     * Reproduces {@code COMEN01C} {@code PROCESS-ENTER-KEY}: validates a raw menu
     * selection, applies the user-type gate, and either routes to the target
     * program or yields a screen message.
     *
     * <p>The three COBOL steps run in their original order &mdash; validate &rarr;
     * user-type gate &rarr; navigate &mdash; and each terminating branch is an
     * explicit early return, so control never falls through from one step into the
     * next (AAP &sect;0.7.4).</p>
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
        //   WS-OPTION > CDEMO-MENU-OPT-COUNT OR WS-OPTION = ZEROS.
        // The early return both reproduces the error path and guards the
        // out-of-range table subscript that the COBOL fall-through risked.
        if (option == NON_NUMERIC || option > getOptionCount() || option == 0) {
            // COBOL substitution: SEND MAP error text -> returned message (red error).
            return new MenuSelectionResult(false, null, MSG_INVALID_OPTION, MessageSeverity.ERROR);
        }

        // Option numbers are 1..10 by construction, so a validated option maps
        // directly to the (option - 1) zero-based list index.
        final MenuOption selected = MENU_OPTIONS.get(option - 1);

        // Step 2 -- user-type gate. COBOL: IF CDEMO-USRTYP-USER AND
        //   CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'. A regular user may not select
        //   an admin-only option. (No shipped option is admin-only, but the gate is
        //   preserved for fidelity.)
        if (commArea.getUserType() == UserType.USER && selected.allowedUserType() == UserType.ADMIN) {
            // COBOL substitution: SEND MAP "No access" text -> returned message (red error).
            return new MenuSelectionResult(false, null, MSG_ADMIN_ONLY, MessageSeverity.ERROR);
        }

        // Step 3a -- DUMMY placeholder guard. COBOL:
        //   IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY' ... (navigate)
        //   ELSE (implicitly) build the "coming soon" message. Because XCTL transfers
        //   control away, the "coming soon" text is reachable only for a DUMMY
        //   placeholder; none of the 10 shipped options is DUMMY, so this never fires
        //   for production data but is retained verbatim for fidelity.
        final String targetProgram = selected.targetProgram();
        if (targetProgram != null && targetProgram.startsWith(DUMMY_PROGRAM_PREFIX)) {
            // COBOL substitution: STRING ... INTO WS-MESSAGE + DFHGREEN
            //   -> returned informational (green) message.
            return new MenuSelectionResult(false, null,
                    "This option " + selected.name() + " is coming soon ...",
                    MessageSeverity.INFO);
        }

        // Step 3b -- navigate. COBOL: MOVE WS-TRANID -> CDEMO-FROM-TRANID,
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
     * Parses the raw menu-option input exactly as {@code COMEN01C}
     * {@code PROCESS-ENTER-KEY} does.
     *
     * <p>The COBOL right-justifies the trimmed input into {@code WS-OPTION-X
     * PIC X(02) JUST RIGHT}, runs {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY
     * '0'} (so a blank field becomes {@code "00"}), and moves the result into
     * {@code WS-OPTION PIC 9(02)}. The downstream test {@code IF WS-OPTION IS NOT
     * NUMERIC} then rejects any non-digit content. This method reproduces that
     * observable behaviour:</p>
     * <ul>
     *   <li>{@code null} or all-blank input &rarr; {@code 0} (the COBOL "00",
     *       which the caller's {@code = ZEROS} check rejects);</li>
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
