/*
 * ******************************************************************
 * Program     : MenuResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Main-menu and admin-menu option payload, bounded by
 *               the source option counts.
 * Source      : app/cpy/COMEN02Y.cpy:21 (10 options) + app/cpy/COADM02Y.cpy:20 (4 options) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.cardemo.model.enums.UserType;

/**
 * The option payload of the two CardDemo menu screens: either the ten main-menu options transcribed from
 * {@code app/cpy/COMEN02Y.cpy} or the four admin-menu options transcribed from
 * {@code app/cpy/COADM02Y.cpy}, together with the count that bounds them.
 *
 * <p>One response type serves both screens because the two tables were consumed by two structurally
 * parallel programs and by nothing else in the corpus: {@code app/cbl/COMEN01C.cbl:51} states
 * {@code COPY COMEN02Y} and {@code app/cbl/COADM01C.cbl:51} states {@code COPY COADM02Y}. The
 * {@link MenuType} discriminator keeps the two apart, so a consumer never has to infer which menu a
 * payload describes.</p>
 *
 * <p>This is a pure data holder. It performs no I/O, reads no configuration, logs nothing, and depends on
 * exactly one other CardDemo type, the {@code UserType} enumeration that gives the main-menu gate byte a
 * name. It carries no personally identifiable information and no credential material: a menu option is a
 * number, a caption, a program name and, on the main menu only, a one-character eligibility code.</p>
 *
 * <h2>The two source tables, quoted</h2>
 *
 * <p>{@code app/cpy/COMEN02Y.cpy} declares, verbatim:</p>
 *
 * <pre>
 *  01 CARDDEMO-MAIN-MENU-OPTIONS.                             &lt;- L19
 *    05 CDEMO-MENU-OPT-COUNT           PIC 9(02) VALUE 10.     &lt;- L21
 *    05 CDEMO-MENU-OPTIONS-DATA.                               &lt;- L23  (10 entries, L25-L84)
 *    05 CDEMO-MENU-OPTIONS REDEFINES CDEMO-MENU-OPTIONS-DATA.  &lt;- L87
 *      10 CDEMO-MENU-OPT OCCURS 12 TIMES.                      &lt;- L88
 *        15 CDEMO-MENU-OPT-NUM           PIC 9(02).             &lt;- L89
 *        15 CDEMO-MENU-OPT-NAME          PIC X(35).             &lt;- L90
 *        15 CDEMO-MENU-OPT-PGMNAME       PIC X(08).             &lt;- L91
 *        15 CDEMO-MENU-OPT-USRTYPE       PIC X(01).             &lt;- L92
 * </pre>
 *
 * <p>{@code app/cpy/COADM02Y.cpy} declares, verbatim:</p>
 *
 * <pre>
 *  01 CARDDEMO-ADMIN-MENU-OPTIONS.                              &lt;- L19
 *    05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 4.       &lt;- L20
 *    05 CDEMO-ADMIN-OPTIONS-DATA.                                &lt;- L22  (4 entries, L24-L42)
 *    05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.  &lt;- L44
 *      10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.                        &lt;- L45
 *        15 CDEMO-ADMIN-OPT-NUM           PIC 9(02).              &lt;- L46
 *        15 CDEMO-ADMIN-OPT-NAME          PIC X(35).              &lt;- L47
 *        15 CDEMO-ADMIN-OPT-PGMNAME       PIC X(08).              &lt;- L48
 * </pre>
 *
 * <h2>The count bounds the options; the OCCURS capacity never does</h2>
 *
 * <p><strong>Ten options are populated of twelve declared, and four of nine.</strong> The two figures are
 * different facts and only the smaller one is a bound:</p>
 *
 * <ul>
 *   <li>{@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:21} is the number
 *       of entries actually populated in {@code CDEMO-MENU-OPTIONS-DATA}, which holds exactly ten
 *       four-field groups across {@code app/cpy/COMEN02Y.cpy:25-84}.</li>
 *   <li>{@code CDEMO-MENU-OPT OCCURS 12 TIMES} at {@code app/cpy/COMEN02Y.cpy:88} is the extent of the
 *       {@code REDEFINES} overlay laid over that data. Subscripts 11 and 12 address storage the populated
 *       literals never reach, so their contents are <strong>unpopulated - not available</strong>. They are
 *       not options, they are not blank options, and nothing here fabricates content for them.</li>
 *   <li>The admin table repeats the pattern with five spare rows rather than two:
 *       {@code VALUE 4} at {@code app/cpy/COADM02Y.cpy:20} against
 *       {@code OCCURS 9 TIMES} at {@code app/cpy/COADM02Y.cpy:45}.</li>
 * </ul>
 *
 * <p>The legacy programs themselves prove the count is the bound. Both render their menus with a
 * {@code PERFORM VARYING} loop over {@code WS-IDX} that stops as soon as the index passes
 * {@code CDEMO-MENU-OPT-COUNT}, at {@code app/cbl/COMEN01C.cbl:238-239} and, for the admin table and its
 * own count, at {@code app/cbl/COADM01C.cbl:228-229}. Both also validate an operator's selection against
 * that same count, at {@code app/cbl/COMEN01C.cbl:128} and {@code app/cbl/COADM01C.cbl:128}. Neither
 * program ever iterates to the OCCURS extent. This type
 * reproduces that: {@link #getOptions()} can never yield twelve main options or nine admin options,
 * because the constructor rejects any list longer than {@link MenuType#getPopulatedOptionCount()}.</p>
 *
 * <h2>The two entry shapes differ and are deliberately not unified</h2>
 *
 * <p>A main-menu entry has <strong>four</strong> sub-fields and an admin-menu entry has
 * <strong>three</strong>: {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} exists at
 * {@code app/cpy/COMEN02Y.cpy:92} and <strong>has no counterpart anywhere in
 * {@code app/cpy/COADM02Y.cpy}</strong>, whose entry ends at {@code CDEMO-ADMIN-OPT-PGMNAME} on line 48.
 * {@link MainMenuOption} and {@link AdminMenuOption} are therefore two distinct types, and
 * {@link MenuOption} - the sealed supertype that bounds this class's type parameter - declares only the
 * three sub-fields the two entries genuinely share at identical widths. It deliberately declares no user
 * type accessor, so the asymmetry is enforced by the compiler rather than merely described in prose:
 * asking an {@link AdminMenuOption} for a user type does not compile.</p>
 *
 * <p>Merging the two shapes would not be de-duplication, it would be a factual error - the same class of
 * error as unifying two same-named screen fields whose declared widths differ. Equally, no user type is
 * invented for an admin entry and none is dropped from a main entry.</p>
 *
 * <h2>Field widths are the table's, not the screen's</h2>
 *
 * <p>An option caption is <strong>{@code PIC X(35)}, 35 characters, not 40</strong>. The display maps
 * declare twelve caption slots of {@code PIC X(40)} each - {@code OPTN001I} through {@code OPTN012I} at
 * lines 60, 66, 72, 78, 84, 90, 96, 102, 108, 114, 120 and 126 of both
 * {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY}, followed by
 * {@code OPTIONI PIC X(2)} at line 132 and {@code ERRMSGI PIC X(78)} at line 138, twenty input fields in
 * each map. The extra five bytes are not caption: {@code app/cbl/COMEN01C.cbl:243-246} builds a slot by
 * concatenating the two-digit option number, the two-character literal {@code '. '} and the
 * 35-character caption - 39 bytes - into {@code WS-MENU-OPT-TXT PIC X(40)} declared at
 * {@code app/cbl/COMEN01C.cbl:48}, and {@code app/cbl/COADM01C.cbl:233-236} does the same into
 * {@code WS-ADMIN-OPT-TXT PIC X(40)} at {@code app/cbl/COADM01C.cbl:48}. The slot is 40 wide because it
 * holds a <em>rendered line</em>, so this type carries the table width and leaves the rendering to the
 * presentation layer.</p>
 *
 * <p>For the same reason the twelve screen slots are not an option count. Both maps declare twelve slots
 * regardless of how many options exist, which the admin menu proves outright: its table declares
 * {@code OCCURS 9} and populates four, yet its map still declares twelve {@code X(40)} slots. The
 * coincidence between the main table's {@code OCCURS 12} and the twelve slots is exactly that - a
 * coincidence between two unrelated declarations.</p>
 *
 * <h2>Captions are carried space-padded, and nothing is normalised</h2>
 *
 * <p>Every caption in both copybooks is a {@code VALUE} literal of exactly 35 characters, blank-padded to
 * the full {@code PIC X(35)} width - for example {@code 'Account View                       '} at
 * {@code app/cpy/COMEN02Y.cpy:27}. The canonical tables below transcribe those literals byte for byte,
 * <strong>padding included</strong>. Nothing in this type trims, folds, pads, truncates or re-cases any
 * value, on the way in or on the way out: the legacy renderer concatenates the padded caption directly,
 * so trimming is a presentation decision and is not taken here. A caller supplying its own caption gets
 * exactly the string it supplied back.</p>
 *
 * <h2>A preserved observation about option 8</h2>
 *
 * <p>{@code app/cpy/COMEN02Y.cpy:69} is a COBOL comment line - an asterisk in column 7 - carrying the
 * withdrawn caption {@code 'Transaction Add (Admin Only)       '}. The live literal is on the next line,
 * {@code app/cpy/COMEN02Y.cpy:70}, and reads {@code 'Transaction Add                    '}. Option 8 is
 * therefore transcribed as {@code Transaction Add} and, because the live data carries
 * {@code CDEMO-MENU-OPT-USRTYPE} of {@code 'U'} exactly like the other nine, <strong>no administrator-only
 * restriction is applied to it</strong>. The commented caption is recorded here and nowhere else.</p>
 *
 * <p>A second observation, recorded for accuracy and with no effect on any value: the banner of
 * {@code app/cpy/COMEN02Y.cpy:2} titles the member {@code CardDemo - Admin Menu Options} even though its
 * group item on line 19 is {@code CARDDEMO-MAIN-MENU-OPTIONS} and its ten captions are the main-menu
 * captions. The declarations, not the banner, are authoritative.</p>
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <ul>
 *   <li>It does not evaluate the user-type gate. {@link MainMenuOption#userTypeCode()} is carried, never
 *       tested. The legacy test is a program statement, not table data:
 *       {@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'} at
 *       {@code app/cbl/COMEN01C.cbl:136-137}. Its Java home is the menu service derived from that
 *       program, {@code com.cardemo.service.menu.MainMenuService}, which decides which options a signed-on
 *       user may see and hands the result here.</li>
 *   <li>It does not reject a zero or out-of-range selection. That too is a program concern, at
 *       {@code app/cbl/COMEN01C.cbl:127-129}.</li>
 *   <li>It does not interpret a program name, and in particular does not apply the placeholder guard
 *       {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} at
 *       {@code app/cbl/COMEN01C.cbl:146} and {@code app/cbl/COADM01C.cbl:138}.</li>
 *   <li>It does not paginate. The menu is a single screen; {@code PageResponse} covers the paged lists.</li>
 *   <li>It carries no persistence annotation and no framework annotation of any kind.</li>
 * </ul>
 *
 * <h2>Immutability and error modes</h2>
 *
 * <p>Instances are immutable: the option list is defensively copied on construction and published only as
 * an unmodifiable view, both nested option types are records over immutable components, and the two
 * canonical tables are immutable {@code List} instances held in {@code static final} fields. There is no
 * mutable static state anywhere in this file. Instances are therefore safe to share between threads and
 * safe to cache.</p>
 *
 * <p>No accessor validates, computes or fails. Every rejection happens once, at construction, and is
 * reported as an {@code IllegalArgumentException} whose message names the offending field: the
 * {@code com.cardemo.exception} hierarchy is deliberately not referenced, because a data holder must not
 * depend on the application's error taxonomy.</p>
 *
 * <h2>Obtaining, building and testing this type</h2>
 *
 * <p>There is no public constructor. Four factories are the only way in: two that serve the canonical
 * copybook tables, and two that serve a caller-supplied list which the caller has already gated.</p>
 *
 * <pre>
 * // the full copybook tables, 10 and 4 entries
 * MenuResponse&lt;MainMenuOption&gt;  main  = MenuResponse.mainMenu();
 * MenuResponse&lt;AdminMenuOption&gt; admin = MenuResponse.adminMenu();
 *
 * // what a menu service hands back after applying the user-type gate itself
 * MenuResponse&lt;MainMenuOption&gt; gated = MenuResponse.ofMainMenu(permittedOptions);
 *
 * int howMany = gated.getOptionCount();     // derived from the list, never a stored duplicate
 * gated.getOptions().get(0).optionName();   // 35 characters, blank-padded, never trimmed here
 * </pre>
 *
 * <p>No configuration, no property and no environment variable influences this type. Its only defaults are
 * the two canonical tables, whose contents are fixed by the copybooks. It is built by the module's Maven
 * build with {@code mvn -B clean compile} under {@code -Xlint:all -Werror}, and its behaviour is asserted
 * by the unit tests that belong in {@code src/test/java/com/cardemo/unit/model}, run with
 * {@code mvn -B test}. Those tests are the place to pin the two option counts, the fourteen caption and
 * program literals, the entry-shape asymmetry and the unmodifiability of {@link #getOptions()}.</p>
 *
 * <p>Common failure modes, every one of them reported at construction and every message naming the
 * offending field: a null option list, a null element within it, a list longer than the menu's populated
 * count, a null caption or program name on an option, and an option number outside the
 * {@code PIC 9(02)} range. An unrecognised user-type byte is deliberately not a failure - it yields
 * {@code null} from {@link MainMenuOption#userType()} while {@link MainMenuOption#userTypeCode()} still
 * round-trips the raw character unchanged.</p>
 *
 * @param <T> the option type this response carries, which the sealed {@link MenuOption} hierarchy
 *            restricts to exactly {@link MainMenuOption} for {@link MenuType#MAIN} or
 *            {@link AdminMenuOption} for {@link MenuType#ADMIN}
 * @see #mainMenu()
 * @see #adminMenu()
 */
public final class MenuResponse<T extends MenuResponse.MenuOption> {

    /**
     * The declared width of an option caption, from {@code CDEMO-MENU-OPT-NAME PIC X(35)} at
     * {@code app/cpy/COMEN02Y.cpy:90} and {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} at
     * {@code app/cpy/COADM02Y.cpy:47}.
     *
     * <p>Every caption in the canonical tables is exactly this long, blank-padded. The value is published
     * so that the width contract is assertable rather than merely documented; it is not enforced as a
     * validation rule, for the reason given on {@link MainMenuOption#optionName()}.</p>
     */
    public static final int OPTION_NAME_LENGTH = 35;

    /**
     * The declared width of an option's target program name, from
     * {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} at {@code app/cpy/COMEN02Y.cpy:91} and
     * {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at {@code app/cpy/COADM02Y.cpy:48}. All fourteen
     * transcribed program names are exactly this long, so none is padded.
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * The largest value an option number can hold, given {@code CDEMO-MENU-OPT-NUM PIC 9(02)} at
     * {@code app/cpy/COMEN02Y.cpy:89} and {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} at
     * {@code app/cpy/COADM02Y.cpy:46}: an unsigned two-digit display field represents 0 through 99. The
     * canonical tables use 1 through 10 and 1 through 4.
     */
    public static final int OPTION_NUMBER_MAX_VALUE = 99;

    /**
     * The width of one caption slot on the display maps, {@code OPTN001I} through {@code OPTN012I}
     * {@code PIC X(40)} in both {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY}.
     *
     * <p>Published alongside {@link #OPTION_NAME_LENGTH} purely so the deliberate five-byte difference
     * between the table caption and the screen slot is visible and assertable. No value in this type is
     * ever this wide; the slot additionally holds the two-digit option number and the two-character
     * {@code '. '} separator that {@code app/cbl/COMEN01C.cbl:243-246} concatenates in front of the
     * caption.</p>
     */
    public static final int SCREEN_OPTION_SLOT_LENGTH = 40;

    /**
     * Which of the two menus a response describes, and the two figures that describe its source table.
     *
     * <p>The discriminator exists so that one response type can serve both legacy screens without a
     * consumer having to infer the menu from the element type of the option list. Each constant binds the
     * populated option count to the declared OCCURS capacity of its table, which is what makes the
     * distinction between the two machine-checkable instead of merely documented.</p>
     */
    public enum MenuType {

        /**
         * The main menu of {@code app/cbl/COMEN01C.cbl}, whose option table is
         * {@code app/cpy/COMEN02Y.cpy}, copied in at {@code app/cbl/COMEN01C.cbl:51}.
         *
         * <p>Ten options are populated, from {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at
         * {@code app/cpy/COMEN02Y.cpy:21}, within a declared capacity of twelve, from
         * {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at {@code app/cpy/COMEN02Y.cpy:88}. Its entries carry a
         * user type; see {@link MainMenuOption}.</p>
         */
        MAIN(10, 12),

        /**
         * The administrator menu of {@code app/cbl/COADM01C.cbl}, whose option table is
         * {@code app/cpy/COADM02Y.cpy}, copied in at {@code app/cbl/COADM01C.cbl:51}.
         *
         * <p>Four options are populated, from {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at
         * {@code app/cpy/COADM02Y.cpy:20}, within a declared capacity of nine, from
         * {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} at {@code app/cpy/COADM02Y.cpy:45}. Its entries carry no
         * user type at all; see {@link AdminMenuOption}.</p>
         */
        ADMIN(4, 9);

        /**
         * The value of the table's {@code PIC 9(02)} count field: 10 for the main menu, 4 for the
         * administrator menu.
         */
        private final int populatedOptionCount;

        /**
         * The subscript extent of the table's {@code OCCURS} clause: 12 for the main menu, 9 for the
         * administrator menu.
         */
        private final int declaredCapacity;

        /**
         * Binds a constant to the two figures its source table declares.
         *
         * @param populatedOptionCount the value of the table's count field, transcribed from
         *                             {@code app/cpy/COMEN02Y.cpy:21} or {@code app/cpy/COADM02Y.cpy:20}
         * @param declaredCapacity     the extent of the table's OCCURS clause, transcribed from
         *                             {@code app/cpy/COMEN02Y.cpy:88} or {@code app/cpy/COADM02Y.cpy:45}
         */
        private MenuType(final int populatedOptionCount, final int declaredCapacity) {
            this.populatedOptionCount = populatedOptionCount;
            this.declaredCapacity = declaredCapacity;
        }

        /**
         * Returns how many options this menu's source table actually populates, which is the only valid
         * bound on an option list.
         *
         * <p>This is the {@code PIC 9(02)} count field the legacy programs loop to, and the upper bound the
         * {@link MenuResponse} constructor enforces. A pure accessor: no side effects, cannot fail.</p>
         *
         * @return 10 for {@link #MAIN} or 4 for {@link #ADMIN}
         */
        public int getPopulatedOptionCount() {
            return this.populatedOptionCount;
        }

        /**
         * Returns the subscript extent of this menu's OCCURS clause, which is <strong>never</strong> a
         * valid bound on an option list.
         *
         * <p>Published only so the difference from {@link #getPopulatedOptionCount()} is assertable: two of
         * the twelve main subscripts and five of the nine admin subscripts address storage that no
         * {@code VALUE} literal populates, so their contents are unpopulated and not available. Iterating
         * to this figure would read overlay bytes that were never options. A pure accessor: no side
         * effects, cannot fail.</p>
         *
         * @return 12 for {@link #MAIN} or 9 for {@link #ADMIN}
         */
        public int getDeclaredCapacity() {
            return this.declaredCapacity;
        }
    }

    /**
     * The three sub-fields a main-menu entry and an admin-menu entry genuinely share, at identical declared
     * widths, and nothing else.
     *
     * <p>The hierarchy is sealed to exactly {@link MainMenuOption} and {@link AdminMenuOption} because the
     * corpus declares exactly two option tables. It bounds the {@link MenuResponse} type parameter, so no
     * unrelated type can be carried as a menu option.</p>
     *
     * <p><strong>This interface deliberately declares no user-type accessor.</strong>
     * {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} at {@code app/cpy/COMEN02Y.cpy:92} exists only on the main
     * entry; the admin entry at {@code app/cpy/COADM02Y.cpy:46-48} has three sub-fields and stops at its
     * program name. Declaring the gate here would invent an admin user type, so the asymmetry is expressed
     * as the absence of a member and the compiler enforces it: only {@link MainMenuOption} offers
     * {@link MainMenuOption#userTypeCode()}.</p>
     *
     * <p>Implementations are immutable records. No member of this interface validates, computes or
     * fails.</p>
     */
    public sealed interface MenuOption permits MainMenuOption, AdminMenuOption {

        /**
         * Returns the option's one-based display number.
         *
         * @return the value of {@code CDEMO-MENU-OPT-NUM PIC 9(02)} at {@code app/cpy/COMEN02Y.cpy:89} or
         *         {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} at {@code app/cpy/COADM02Y.cpy:46}, between 0 and
         *         {@link MenuResponse#OPTION_NUMBER_MAX_VALUE}
         */
        int optionNumber();

        /**
         * Returns the option's caption exactly as held, including any blank padding.
         *
         * @return the value of {@code CDEMO-MENU-OPT-NAME PIC X(35)} at {@code app/cpy/COMEN02Y.cpy:90} or
         *         {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} at {@code app/cpy/COADM02Y.cpy:47}, never
         *         {@code null}
         */
        String optionName();

        /**
         * Returns the name of the COBOL program the option transferred control to.
         *
         * @return the value of {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} at
         *         {@code app/cpy/COMEN02Y.cpy:91} or {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at
         *         {@code app/cpy/COADM02Y.cpy:48}, never {@code null}
         */
        String programName();
    }

    /**
     * One main-menu entry: the four sub-fields of {@code CDEMO-MENU-OPT} at
     * {@code app/cpy/COMEN02Y.cpy:88-92}.
     *
     * <p>The fourth sub-field is the eligibility gate that has no admin counterpart. This type
     * <strong>carries</strong> it and never <strong>tests</strong> it: the legacy test is a statement in
     * the menu program, {@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'} at
     * {@code app/cbl/COMEN01C.cbl:136-137}, and its Java home is
     * {@code com.cardemo.service.menu.MainMenuService}.</p>
     *
     * <p>All ten populated entries carry {@code 'U'}, so as the tables stand the gate excludes nothing.
     * That is data, not a rule: the gate is preserved because the field is real and a future table value of
     * {@code 'A'} must keep working exactly as the legacy test would have made it work.</p>
     *
     * <p><strong>Validation.</strong> Only two conditions are rejected, both at construction and both
     * naming the offending component: a {@code null} caption or program name, and an option number outside
     * the {@code PIC 9(02)} range 0 through {@link MenuResponse#OPTION_NUMBER_MAX_VALUE}. Nothing else is
     * rejected - see {@link #optionName()} for why width is documented but not enforced - and no accessor
     * can fail.</p>
     *
     * <p><strong>Rendering.</strong> The record's generated {@code toString} is retained deliberately,
     * unlike {@code UserSecurityDto.UserRow} which suppresses its own: every component here is a public
     * caption, an option number, a program name or a one-character eligibility code, so a full rendering
     * can disclose neither personal data nor a credential.</p>
     *
     * @param optionName   the caption, {@code CDEMO-MENU-OPT-NAME PIC X(35)} at
     *                     {@code app/cpy/COMEN02Y.cpy:90}; the canonical table holds it blank-padded to
     *                     {@link MenuResponse#OPTION_NAME_LENGTH} characters exactly as the {@code VALUE}
     *                     literal declares it, and this type neither pads nor trims what it is given; must
     *                     not be {@code null}, may be empty
     * @param optionNumber the display number, {@code CDEMO-MENU-OPT-NUM PIC 9(02)} at
     *                     {@code app/cpy/COMEN02Y.cpy:89}; 1 through 10 in the canonical table, and any
     *                     value the two-digit field can represent is accepted
     * @param programName  the target program, {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} at
     *                     {@code app/cpy/COMEN02Y.cpy:91}; all ten canonical values are exactly
     *                     {@link MenuResponse#PROGRAM_NAME_LENGTH} characters; must not be {@code null}
     * @param userTypeCode the eligibility gate, {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} at
     *                     {@code app/cpy/COMEN02Y.cpy:92}; a single character because the field is a single
     *                     byte that is always present, carried verbatim so a value outside the two-code
     *                     domain round-trips rather than failing, and never tested here
     */
    public record MainMenuOption(
            int optionNumber,
            String optionName,
            String programName,
            char userTypeCode) implements MenuOption {

        /**
         * Rejects the only two states the source cannot produce, naming the offending component.
         */
        public MainMenuOption {
            requireOptionNumberInRange(optionNumber, "optionNumber");
            requireNonNullField(optionName, "optionName");
            requireNonNullField(programName, "programName");
        }

        /**
         * Returns the named user class this option is gated on, or {@code null} when the carried code is
         * outside the domain the copybook defines.
         *
         * <p>Derived from {@link #userTypeCode()} through {@code UserType.fromCode(char)}, whose two
         * constants transcribe the condition names {@code CDEMO-USRTYP-ADMIN VALUE 'A'} and
         * {@code CDEMO-USRTYP-USER VALUE 'U'} at {@code app/cpy/COCOM01Y.cpy:27-28} - the same one-byte
         * domain this table's gate byte is drawn from.</p>
         *
         * <p>An unrecognised code yields {@code null} rather than an exception, so a table value the
         * copybook never anticipated stays readable through {@link #userTypeCode()} instead of making the
         * whole option unusable. This deliberately contrasts with {@code UserSecurityDto.UserRow}, which
         * projects a <em>stored</em> type byte of unbounded provenance and therefore refuses to name it at
         * all; here the byte is a compile-time literal from a frozen table, so naming it is safe.</p>
         *
         * <p>A pure derivation: no side effects, no state, cannot fail, and the result depends only on
         * {@link #userTypeCode()}.</p>
         *
         * @return {@code UserType.ADMIN} for {@code 'A'}, {@code UserType.USER} for {@code 'U'}, or
         *         {@code null} for any other character
         */
        public UserType userType() {
            return UserType.fromCode(this.userTypeCode).orElse(null);
        }
    }

    /**
     * One admin-menu entry: the three sub-fields of {@code CDEMO-ADMIN-OPT} at
     * {@code app/cpy/COADM02Y.cpy:45-48}.
     *
     * <p><strong>There is no user-type component, because the copybook declares none.</strong> The admin
     * entry ends at {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} on line 48, and {@code app/cbl/COADM01C.cbl}
     * contains no counterpart to the eligibility test that {@code app/cbl/COMEN01C.cbl:136-137} performs.
     * Reaching the administrator menu is itself the authorisation decision, and reproducing it is the
     * business of {@code com.cardemo.service.menu.AdminMenuService} and the security configuration, not of
     * an invented field here.</p>
     *
     * <p><strong>Validation.</strong> Identical to {@link MainMenuOption}: a {@code null} caption or
     * program name and an out-of-range option number are rejected at construction with the component
     * named; nothing else is rejected and no accessor can fail. The record's generated {@code toString} is
     * likewise retained, because no component can disclose personal data or a credential.</p>
     *
     * @param optionName   the caption, {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} at
     *                     {@code app/cpy/COADM02Y.cpy:47}; the canonical table holds it blank-padded to
     *                     {@link MenuResponse#OPTION_NAME_LENGTH} characters, and the parenthesised
     *                     {@code (Security)} suffix is part of all four captions; must not be
     *                     {@code null}, may be empty
     * @param optionNumber the display number, {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} at
     *                     {@code app/cpy/COADM02Y.cpy:46}; 1 through 4 in the canonical table
     * @param programName  the target program, {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at
     *                     {@code app/cpy/COADM02Y.cpy:48}; all four canonical values are exactly
     *                     {@link MenuResponse#PROGRAM_NAME_LENGTH} characters; must not be {@code null}
     */
    public record AdminMenuOption(
            int optionNumber,
            String optionName,
            String programName) implements MenuOption {

        /**
         * Rejects the only two states the source cannot produce, naming the offending component.
         */
        public AdminMenuOption {
            requireOptionNumberInRange(optionNumber, "optionNumber");
            requireNonNullField(optionName, "optionName");
            requireNonNullField(programName, "programName");
        }
    }

    /**
     * The ten main-menu options, transcribed from {@code CDEMO-MENU-OPTIONS-DATA} at
     * {@code app/cpy/COMEN02Y.cpy:25-84} in copybook order.
     *
     * <p><strong>Exactly ten entries, never twelve.</strong> The two remaining subscripts of
     * {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at {@code app/cpy/COMEN02Y.cpy:88} are unpopulated overlay
     * storage: what they contain is not available, and nothing is invented for them.</p>
     *
     * <p>Every caption is the {@code VALUE} literal byte for byte, blank-padded to the
     * {@link #OPTION_NAME_LENGTH} declared width; every program name is the eight-character literal;
     * every gate byte is the literal {@code 'U'}, which all ten entries carry. Option 8 uses the live
     * caption from {@code app/cpy/COMEN02Y.cpy:70} and not the commented-out variant on the line above
     * it.</p>
     *
     * <p>The list is immutable, as are its elements, so the field is a genuine constant rather than a
     * shared mutable array: it can be published, iterated and cached without copying.</p>
     */
    public static final List<MainMenuOption> MAIN_MENU_OPTIONS = List.of(
            new MainMenuOption(1, "Account View                       ", "COACTVWC", 'U'),
            new MainMenuOption(2, "Account Update                     ", "COACTUPC", 'U'),
            new MainMenuOption(3, "Credit Card List                   ", "COCRDLIC", 'U'),
            new MainMenuOption(4, "Credit Card View                   ", "COCRDSLC", 'U'),
            new MainMenuOption(5, "Credit Card Update                 ", "COCRDUPC", 'U'),
            new MainMenuOption(6, "Transaction List                   ", "COTRN00C", 'U'),
            new MainMenuOption(7, "Transaction View                   ", "COTRN01C", 'U'),
            new MainMenuOption(8, "Transaction Add                    ", "COTRN02C", 'U'),
            new MainMenuOption(9, "Transaction Reports                ", "CORPT00C", 'U'),
            new MainMenuOption(10, "Bill Payment                       ", "COBIL00C", 'U'));

    /**
     * The four admin-menu options, transcribed from {@code CDEMO-ADMIN-OPTIONS-DATA} at
     * {@code app/cpy/COADM02Y.cpy:24-42} in copybook order.
     *
     * <p><strong>Exactly four entries, never nine.</strong> The five remaining subscripts of
     * {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} at {@code app/cpy/COADM02Y.cpy:45} are unpopulated overlay
     * storage: what they contain is not available, and nothing is invented for them.</p>
     *
     * <p>Every caption is the {@code VALUE} literal byte for byte - including the parenthesised
     * {@code (Security)} suffix that all four carry - blank-padded to the {@link #OPTION_NAME_LENGTH}
     * declared width. No entry carries a user type, because the copybook declares no such sub-field.</p>
     *
     * <p>The list is immutable, as are its elements.</p>
     */
    public static final List<AdminMenuOption> ADMIN_MENU_OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)               ", "COUSR00C"),
            new AdminMenuOption(2, "User Add (Security)                ", "COUSR01C"),
            new AdminMenuOption(3, "User Update (Security)             ", "COUSR02C"),
            new AdminMenuOption(4, "User Delete (Security)             ", "COUSR03C"));

    /**
     * Which menu this response describes, so a consumer never has to infer it. Never {@code null}.
     *
     * <p>The legacy equivalent was the identity of the running program itself - {@code COMEN01C} against
     * {@code COADM01C} - which a stateless payload cannot convey.</p>
     */
    private final MenuType menuType;

    /**
     * The options this response carries, in menu order, already defensively copied and wrapped
     * unmodifiable. Never {@code null}; possibly empty.
     *
     * <p>A {@code List} is used because position is meaning: the option number is the operator's selection
     * key and the display order. No hash-ordered collection may ever hold these, since its iteration order
     * would be unrelated to {@code CDEMO-MENU-OPT-NUM}.</p>
     *
     * <p>The size never exceeds {@link MenuType#getPopulatedOptionCount()}, which the constructor
     * enforces. It may be smaller: a main-menu service that applied the user-type gate of
     * {@code app/cbl/COMEN01C.cbl:136-137} legitimately offers fewer than ten options.</p>
     */
    private final List<T> options;

    /**
     * Constructs a response over a defensive copy of the supplied options.
     *
     * <p>Private on purpose. The only way to obtain an instance is through one of the four factory
     * methods, each of which pairs a {@link MenuType} with the matching option type, so it is impossible
     * to label main-menu options as the admin menu or the reverse.</p>
     *
     * @param menuType which menu is being described; must not be {@code null}
     * @param options  the options in menu order; must not be {@code null}, must not contain a
     *                 {@code null} element, and must hold no more entries than
     *                 {@link MenuType#getPopulatedOptionCount()} permits, but may be empty
     * @throws IllegalArgumentException if any argument violates the conditions above; the message names
     *                                  the offending field
     */
    private MenuResponse(final MenuType menuType, final List<T> options) {

        if (menuType == null) {
            throw new IllegalArgumentException(
                    "menuType must not be null; it is what distinguishes the main menu from the admin menu");
        }
        if (options == null) {
            throw new IllegalArgumentException(
                    "options must not be null; supply an empty list to represent a menu offering no options");
        }
        if (options.size() > menuType.getPopulatedOptionCount()) {
            throw new IllegalArgumentException("options holds " + options.size()
                    + " entries, which exceeds the " + menuType.getPopulatedOptionCount()
                    + " options populated for menu " + menuType
                    + "; the OCCURS capacity of " + menuType.getDeclaredCapacity()
                    + " is not a valid bound because its spare subscripts are unpopulated");
        }

        // Pre-sized to the exact final length, so the copy is allocated once and never resized. The check
        // immediately above proves that length is bounded by the populated option count of the menu - ten
        // for the main menu, four for the admin menu - so the reservation cannot exceed the source table.
        final List<T> defensiveCopy = new ArrayList<>(options.size());
        for (final T option : options) {
            if (option == null) {
                // defensiveCopy.size() is the index within options of the element being rejected.
                throw new IllegalArgumentException(
                        "options must not contain a null element, but index " + defensiveCopy.size() + " was null");
            }
            defensiveCopy.add(option);
        }

        this.menuType = menuType;
        this.options = Collections.unmodifiableList(defensiveCopy);
    }

    /**
     * Returns the complete main menu: all ten options of {@link #MAIN_MENU_OPTIONS}, unfiltered.
     *
     * <p>This is the table as {@code app/cpy/COMEN02Y.cpy} declares it, before any eligibility decision.
     * A service that must withhold options for a standard user applies the gate itself and calls
     * {@link #ofMainMenu(List)} with what remains.</p>
     *
     * @return a main-menu response carrying exactly ten options, never {@code null}
     */
    public static MenuResponse<MainMenuOption> mainMenu() {
        return new MenuResponse<>(MenuType.MAIN, MAIN_MENU_OPTIONS);
    }

    /**
     * Returns a main-menu response over the supplied options, which is how a filtered menu is built.
     *
     * @param options the main-menu options to carry, in menu order; must not be {@code null}, must not
     *                contain a {@code null} element, and must hold no more than the ten entries
     *                {@code app/cpy/COMEN02Y.cpy:21} populates, but may be empty
     * @return a main-menu response over a defensive copy of {@code options}, never {@code null}
     * @throws IllegalArgumentException if {@code options} is {@code null}, contains a {@code null}
     *                                  element, or holds more than ten entries; the message names the
     *                                  offending field
     */
    public static MenuResponse<MainMenuOption> ofMainMenu(final List<MainMenuOption> options) {
        return new MenuResponse<>(MenuType.MAIN, options);
    }

    /**
     * Returns the complete administrator menu: all four options of {@link #ADMIN_MENU_OPTIONS}.
     *
     * <p>No filtering counterpart to the main menu's gate exists, because {@code app/cpy/COADM02Y.cpy}
     * declares no user-type sub-field and {@code app/cbl/COADM01C.cbl} performs no eligibility test.</p>
     *
     * @return an admin-menu response carrying exactly four options, never {@code null}
     */
    public static MenuResponse<AdminMenuOption> adminMenu() {
        return new MenuResponse<>(MenuType.ADMIN, ADMIN_MENU_OPTIONS);
    }

    /**
     * Returns an admin-menu response over the supplied options.
     *
     * @param options the admin-menu options to carry, in menu order; must not be {@code null}, must not
     *                contain a {@code null} element, and must hold no more than the four entries
     *                {@code app/cpy/COADM02Y.cpy:20} populates, but may be empty
     * @return an admin-menu response over a defensive copy of {@code options}, never {@code null}
     * @throws IllegalArgumentException if {@code options} is {@code null}, contains a {@code null}
     *                                  element, or holds more than four entries; the message names the
     *                                  offending field
     */
    public static MenuResponse<AdminMenuOption> ofAdminMenu(final List<AdminMenuOption> options) {
        return new MenuResponse<>(MenuType.ADMIN, options);
    }

    /**
     * Returns which menu this response describes.
     *
     * @return {@link MenuType#MAIN} or {@link MenuType#ADMIN}, never {@code null}
     */
    public MenuType getMenuType() {
        return this.menuType;
    }

    /**
     * Returns the options in menu order.
     *
     * <p>The returned list is an unmodifiable view over this instance's private copy, so every mutating
     * operation on it throws {@code UnsupportedOperationException} and no caller can alter the response.
     * Order is exactly the order supplied, which for the unfiltered factories is copybook order. The list
     * is never {@code null}; an empty list means the menu offers no options and is a distinct, meaningful
     * state.</p>
     *
     * @return an unmodifiable, order-preserving view of the options, never {@code null}
     */
    public List<T> getOptions() {
        return this.options;
    }

    /**
     * Returns how many options this response carries: the analogue of the table's two-digit count field,
     * {@code CDEMO-MENU-OPT-COUNT} at {@code app/cpy/COMEN02Y.cpy:21} or {@code CDEMO-ADMIN-OPT-COUNT} at
     * {@code app/cpy/COADM02Y.cpy:20}.
     *
     * <p>Derived from the option list rather than stored, so the two can never disagree. For an unfiltered
     * response it equals {@link MenuType#getPopulatedOptionCount()} - ten or four - and it is never
     * greater; it is smaller only when the caller withheld options, as the main-menu gate at
     * {@code app/cbl/COMEN01C.cbl:136-137} may require. It is never the OCCURS capacity.</p>
     *
     * @return the number of options carried, between 0 and {@link MenuType#getPopulatedOptionCount()}
     */
    public int getOptionCount() {
        return this.options.size();
    }

    /**
     * Compares this response with another for value equality over the menu type and the options.
     *
     * <p>Option comparison is order-sensitive, because the options are held in a {@code List} and their
     * order is the menu order. The result is therefore deterministic and independent of any hash iteration
     * order. The option count is derived from the option list, so it needs no separate comparison.</p>
     *
     * @param other the object to compare with, which may be {@code null}
     * @return {@code true} when {@code other} is a response for the same menu carrying equal options in
     *         the same order
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MenuResponse<?> that)) {
            return false;
        }
        return this.menuType == that.menuType
                && this.options.equals(that.options);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)} over the menu type and the options.
     *
     * @return the hash code for this response
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.menuType, this.options);
    }

    /**
     * Returns a diagnostic rendering of the menu type and the option count.
     *
     * <p>The options themselves are omitted for brevity rather than for safety: a menu option is a
     * caption, a number, a program name and a one-character eligibility code, none of which identifies
     * anyone or discloses a credential, and all of which are already public constants of this class.
     * Formatting uses {@code Locale.ROOT} so the output cannot vary with the platform locale.</p>
     *
     * @return a locale-independent summary carrying no personally identifiable information
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MenuResponse[menuType=%s, optionCount=%d]",
                this.menuType, this.options.size());
    }

    /**
     * Rejects an option number the two-digit source field could not hold.
     *
     * <p>Shared by both option records so the one rule has one implementation. The bound is the field
     * width, not the table content: {@code PIC 9(02)} is an unsigned two-digit display field, so 0 through
     * {@link #OPTION_NUMBER_MAX_VALUE} is exactly what it represents, even though the two tables use only
     * 1 through 10 and 1 through 4. Rejecting a zero <em>selection</em> is a separate, program-level rule
     * at {@code app/cbl/COMEN01C.cbl:127-129} and is not applied here.</p>
     *
     * @param optionNumber the value to check
     * @param fieldName    the component name to name in the failure message
     * @throws IllegalArgumentException if {@code optionNumber} is negative or greater than
     *                                  {@link #OPTION_NUMBER_MAX_VALUE}
     */
    private static void requireOptionNumberInRange(final int optionNumber, final String fieldName) {
        if (optionNumber < 0 || optionNumber > OPTION_NUMBER_MAX_VALUE) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and " + OPTION_NUMBER_MAX_VALUE
                    + " because the source field is PIC 9(02), but was " + optionNumber);
        }
    }

    /**
     * Rejects a {@code null} text component, naming it.
     *
     * <p>Only {@code null} is rejected. An empty string is accepted, because it is distinguishable from
     * absence and is a state a caller may legitimately mean. Length is deliberately not checked: the
     * declared widths are published as {@link #OPTION_NAME_LENGTH} and {@link #PROGRAM_NAME_LENGTH} and
     * honoured by the canonical tables, but silently truncating a longer value would lose data and
     * rejecting one would refuse input the 40-byte screen slot could carry, so this type stores exactly
     * what it is given.</p>
     *
     * @param value     the value to check
     * @param fieldName the component name to name in the failure message
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static void requireNonNullField(final String value, final String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName
                    + " must not be null; supply the value the copybook literal declares, empty if genuinely absent");
        }
    }
}
