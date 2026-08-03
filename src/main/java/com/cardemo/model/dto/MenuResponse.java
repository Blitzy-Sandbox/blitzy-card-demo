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
 * {@code app/cpy/COMEN02Y.cpy} or the four admin-menu options transcribed from {@code app/cpy/COADM02Y.cpy},
 * together with the count that bounds them.
 *
 * <p>One response type serves both screens because the two tables were consumed by two structurally parallel
 * programs and by nothing else in the corpus: {@code app/cbl/COMEN01C.cbl:51} states {@code COPY COMEN02Y} and
 * {@code app/cbl/COADM01C.cbl:51} states {@code COPY COADM02Y}. The {@link MenuType} discriminator keeps the
 * two apart, so a consumer never has to infer which menu a payload describes.
 *
 * <p>This is a pure data holder. It performs no I/O, reads no configuration, logs nothing, and depends on
 * exactly one other CardDemo type, the {@code UserType} enumeration that gives the main-menu gate byte a name.
 * It carries no personally identifiable information and no credential material: a menu option is a number, a
 * caption, a program name and, on the main menu only, a one-character eligibility code.
 *
 * <h2>Two different contracts live here, and conflating them loses fields</h2>
 *
 * <p>The option records transcribe the two {@code WORKING-STORAGE} <em>tables</em>. {@link MenuScreen}
 * transcribes the two symbolic <em>maps</em>. These are not the same contract and neither substitutes for
 * the other:</p>
 *
 * <ul>
 *   <li><strong>The tables</strong> - {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy} - hold
 *       the option number, the {@code PIC X(35)} caption, the {@code PIC X(08)} program name and, on the
 *       main menu only, the one-character eligibility code. They have no header, no error line and no
 *       selection field, because they are data the program owns rather than a screen the terminal sees.</li>
 *   <li><strong>The maps</strong> - {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} -
 *       each declare <strong>twenty</strong> input fields: six recurring header fields, twelve
 *       {@code PIC X(40)} caption slots, a {@code PIC X(2)} selection field and a {@code PIC X(78)} error
 *       line. The slot is wider than the caption because {@code app/cbl/COMEN01C.cbl:243-246} prefixes the
 *       option number and a {@code '. '} separator before moving the caption in.</li>
 *   </ul>
 *
 * <p>Representing only the tables therefore left all twenty map fields unrepresented, which is why
 * {@link MenuScreen} exists. It is additive: nothing about the option records changed, and a caller that
 * only needs the option list is unaffected.</p>
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
 *       literals never reach, so their contents are <strong>unpopulated - they hold no value at all</strong>. They
 *       are
 *       not options, they are not blank options, and nothing here fabricates content for them.</li>
 *   <li>The admin table repeats the pattern with five spare rows rather than two:
 *       {@code VALUE 4} at {@code app/cpy/COADM02Y.cpy:20} against
 *       {@code OCCURS 9 TIMES} at {@code app/cpy/COADM02Y.cpy:45}.</li>
 *   </ul>
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
 * build with {@code ./mvnw -B clean compile} under {@code -Xlint:all -Werror}, and its behaviour is asserted
 * by the unit tests that belong in {@code src/test/java/com/cardemo/unit/model}, run with
 * {@code ./mvnw -B test}. Those tests are the place to pin the two option counts, the fourteen caption and
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
     */
    public static final int OPTION_NAME_LENGTH = 35;

    /**
     * The declared width of an option's target program name, from {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} at
     * {@code app/cpy/COMEN02Y.cpy:91} and {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at
     * {@code app/cpy/COADM02Y.cpy:48}. All fourteen transcribed program names are exactly this long, so none is
     * padded.
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
     */
    public static final int SCREEN_OPTION_SLOT_LENGTH = 40;

    /**
     * Which of the two menus a response describes, and the two figures that describe its source table.
     */
    public enum MenuType {

        /**
         * The main menu of {@code app/cbl/COMEN01C.cbl}, whose option table is {@code app/cpy/COMEN02Y.cpy},
         * copied in at {@code app/cbl/COMEN01C.cbl:51}.
         */
        MAIN(10, 12),

        /**
         * The administrator menu of {@code app/cbl/COADM01C.cbl}, whose option table is
         * {@code app/cpy/COADM02Y.cpy}, copied in at {@code app/cbl/COADM01C.cbl:51}.
         */
        ADMIN(4, 9);

        /**
         * The value of the table's {@code PIC 9(02)} count field: 10 from {@code CDEMO-MENU-OPT-COUNT} at
         * {@code app/cpy/COMEN02Y.cpy:21}, or 4 from {@code CDEMO-ADMIN-OPT-COUNT} at
         * {@code app/cpy/COADM02Y.cpy:20}.
         */
        private final int populatedOptionCount;

        /**
         * The subscript extent of the table's {@code OCCURS} clause: 12 from
         * {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at {@code app/cpy/COMEN02Y.cpy:88}, or 9 from
         * {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} at {@code app/cpy/COADM02Y.cpy:45}. It exceeds the populated
         * count on both menus, so the count is what bounds a scan, never the capacity.
         */
        private final int declaredCapacity;

        /**
         * Binds a constant to the two figures its source table declares.
         *
         * @param populatedOptionCount the value of the table's count field, transcribed from
         * {@code app/cpy/COMEN02Y.cpy:21} or {@code app/cpy/COADM02Y.cpy:20}
         * @param declaredCapacity the extent of the table's OCCURS clause, transcribed from
         * {@code app/cpy/COMEN02Y.cpy:88} or {@code app/cpy/COADM02Y.cpy:45}
         */
        private MenuType(final int populatedOptionCount, final int declaredCapacity) {
            this.populatedOptionCount = populatedOptionCount;
            this.declaredCapacity = declaredCapacity;
        }

        /**
         * Returns how many options this menu's source table actually populates, which is the only valid bound
         * on an option list.
         *
         * @return 10 for {@link #MAIN} or 4 for {@link #ADMIN}
         */
        public int getPopulatedOptionCount() {
            return this.populatedOptionCount;
        }

        /**
         * Returns the subscript extent of this menu's OCCURS clause, which is <strong>never</strong> a valid
         * bound on an option list.
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
     */
    public sealed interface MenuOption permits MainMenuOption, AdminMenuOption {

        /**
         * Returns the option's one-based display number.
         *
         * @return the value of {@code CDEMO-MENU-OPT-NUM PIC 9(02)} at {@code app/cpy/COMEN02Y.cpy:89} or
         * {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} at {@code app/cpy/COADM02Y.cpy:46}, between 0 and
         * {@link MenuResponse#OPTION_NUMBER_MAX_VALUE}
         */
        int optionNumber();

        /**
         * Returns the option's caption exactly as held, including any blank padding.
         *
         * @return the value of {@code CDEMO-MENU-OPT-NAME PIC X(35)} at {@code app/cpy/COMEN02Y.cpy:90} or
         * {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} at {@code app/cpy/COADM02Y.cpy:47}, never {@code null}
         */
        String optionName();

        /**
         * Returns the name of the COBOL program the option transferred control to.
         *
         * @return the value of {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} at {@code app/cpy/COMEN02Y.cpy:91} or
         * {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at {@code app/cpy/COADM02Y.cpy:48}, never {@code null}
         */
        String programName();
    }

    /**
     * One main-menu entry: the four sub-fields of {@code CDEMO-MENU-OPT} at {@code app/cpy/COMEN02Y.cpy:88-92}.
     *
     * @param optionNumber the display number, {@code CDEMO-MENU-OPT-NUM PIC 9(02)} at
     * {@code app/cpy/COMEN02Y.cpy:89}.
     * @param optionName the caption, {@code CDEMO-MENU-OPT-NAME PIC X(35)} at {@code app/cpy/COMEN02Y.cpy:90}.
     * @param programName the target program, {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} at
     * {@code app/cpy/COMEN02Y.cpy:91}.
     * @param userTypeCode the eligibility gate, {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} at
     * {@code app/cpy/COMEN02Y.cpy:92}.
     */
    public record MainMenuOption(
            int optionNumber,
            String optionName,
            String programName,
            char userTypeCode) implements MenuOption {

        /**
         * Rejects the three states the source cannot produce, naming the offending component.
         */
        public MainMenuOption {
            requireOptionNumberInRange(optionNumber, "optionNumber");
            requireNonNullField(optionName, "optionName");
            requireWidthWithinLimit(optionName, OPTION_NAME_LENGTH, "optionName", "PIC X(35)");
            requireNonNullField(programName, "programName");
            requireWidthWithinLimit(programName, PROGRAM_NAME_LENGTH, "programName", "PIC X(08)");
        }

        /**
         * Returns the named user class this option is gated on, or {@code null} when the carried code is
         * outside the domain the copybook defines.
         *
         * @return {@code UserType.ADMIN} for {@code 'A'}, {@code UserType.USER} for {@code 'U'}, or
         * {@code null} for any other character
         */
        public UserType userType() {
            return UserType.fromCode(this.userTypeCode).orElse(null);
        }
    }

    /**
     * One admin-menu entry: the three sub-fields of {@code CDEMO-ADMIN-OPT} at
     * {@code app/cpy/COADM02Y.cpy:45-48}.
     *
     * @param optionNumber the display number, {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} at
     * {@code app/cpy/COADM02Y.cpy:46}.
     * @param optionName the caption, {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} at {@code app/cpy/COADM02Y.cpy:47}.
     * @param programName the target program, {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} at
     * {@code app/cpy/COADM02Y.cpy:48}.
     */
    public record AdminMenuOption(
            int optionNumber,
            String optionName,
            String programName) implements MenuOption {

        /**
         * Rejects the three states the source cannot produce, naming the offending component.
         */
        public AdminMenuOption {
            requireOptionNumberInRange(optionNumber, "optionNumber");
            requireNonNullField(optionName, "optionName");
            requireWidthWithinLimit(optionName, OPTION_NAME_LENGTH, "optionName", "PIC X(35)");
            requireNonNullField(programName, "programName");
            requireWidthWithinLimit(programName, PROGRAM_NAME_LENGTH, "programName", "PIC X(08)");
        }
    }

    /**
     * The rendered menu screen: all twenty input fields of {@code app/cpy-bms/COMEN01.CPY} and
     * {@code app/cpy-bms/COADM01.CPY}, in map order.
     *
     * <p><strong>Why this type exists alongside the option tables.</strong> The two option records above
     * transcribe the <em>source tables</em> {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy},
     * which are working-storage data. They are not the wire contract of the screen. The screen contract is
     * the symbolic map, and the map is a different shape: it carries six header fields the tables do not
     * have, twelve forty-byte caption slots rather than twelve thirty-five-byte captions, a two-character
     * selection field and a seventy-eight-character error line. Representing only the tables left the
     * map's twenty fields unrepresented, which is what this record closes.</p>
     *
     * <p><strong>Both maps, one type.</strong> {@code app/cpy-bms/COMEN01.CPY} and
     * {@code app/cpy-bms/COADM01.CPY} are byte-for-byte identical in shape: the same twenty field names,
     * the same twenty {@code PICTURE} clauses, at the same twenty line numbers, differing only in the
     * {@code 01} group name - {@code COMEN1AI} at {@code app/cpy-bms/COMEN01.CPY:17} against
     * {@code COADM1AI} at {@code app/cpy-bms/COADM01.CPY:17}. One record therefore serves both screens
     * faithfully, and {@link MenuResponse#getMenuType()} is what distinguishes which screen an instance
     * describes. This is a proven identity, not an assumed one, and it is the only reason a single type is
     * correct here.</p>
     *
     * <p><strong>The six header fields are declared inline, deliberately.</strong> They are not inherited
     * from a base class, an interface or a mixin, because the corpus does not support a shared header
     * abstraction: {@code CURTIMEI} is {@code PIC X(8)} on sixteen of the seventeen symbolic maps and
     * {@code PIC X(9)} on {@code app/cpy-bms/COSGN00.CPY:54} alone, so any shared type would have to assert
     * a single width the corpus does not have. {@code UserSecurityDto} reaches the same conclusion for the
     * same reason, and both types resolve it the same way.</p>
     *
     * <p><strong>Null, blank and marked are three distinct states.</strong> Every component may be
     * {@code null}, meaning the field was absent; an empty string means it was present and empty; and a
     * value of spaces or low-values means the legacy screen marked it. Nothing is trimmed, padded,
     * upper-cased or lower-cased, and {@code null} is never coerced to empty nor empty to {@code null}.</p>
     *
     * <p><strong>Validation.</strong> One rule, applied to all nineteen textual components: a non-{@code
     * null} value wider than the {@code PICTURE} clause of the field it transcribes is rejected at
     * construction, naming the component and quoting the clause. A {@code null} is accepted, nothing is
     * truncated, and no accessor can fail.</p>
     *
     * <p><strong>Rendering.</strong> The generated {@code toString} is retained. Every component is a
     * public caption, a header rendering, an option selection or an error line: none can disclose personal
     * data, an account identifier or a credential, which is why this record does not suppress its own
     * rendering the way {@code UserSecurityDto.UserRow} must.</p>
     *
     * @param transactionName the four-character transaction identifier, {@code TRNNAMEI PIC X(4)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:24}; may be {@code null} when absent
     * @param title01         the first header title line, {@code TITLE01I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:30}; may be {@code null} when absent
     * @param currentDate     the header date as the terminal rendered it, {@code CURDATEI PIC X(8)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:36}; may be {@code null} when absent
     * @param programName     the eight-character name of the program that painted the screen,
     *                        {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COMEN01.CPY:42}; opaque
     *                        legacy metadata that nothing dispatches on, and may be {@code null}
     * @param title02         the second header title line, {@code TITLE02I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:48}; may be {@code null} when absent
     * @param currentTime     the header time as the terminal rendered it, {@code CURTIMEI PIC X(8)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:54} - eight characters on this map, against the
     *                        nine of {@code app/cpy-bms/COSGN00.CPY:54}; may be {@code null} when absent
     * @param optionSlot01    the first rendered caption slot, {@code OPTN001I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:60}; may be {@code null} when unpainted
     * @param optionSlot02    the second slot, {@code OPTN002I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:66}; may be {@code null} when unpainted
     * @param optionSlot03    the third slot, {@code OPTN003I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:72}; may be {@code null} when unpainted
     * @param optionSlot04    the fourth slot, {@code OPTN004I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:78}; may be {@code null} when unpainted
     * @param optionSlot05    the fifth slot, {@code OPTN005I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:84}; may be {@code null} when unpainted
     * @param optionSlot06    the sixth slot, {@code OPTN006I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:90}; may be {@code null} when unpainted
     * @param optionSlot07    the seventh slot, {@code OPTN007I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:96}; may be {@code null} when unpainted
     * @param optionSlot08    the eighth slot, {@code OPTN008I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:102}; may be {@code null} when unpainted
     * @param optionSlot09    the ninth slot, {@code OPTN009I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:108}; may be {@code null} when unpainted
     * @param optionSlot10    the tenth slot, {@code OPTN010I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:114}; may be {@code null} when unpainted
     * @param optionSlot11    the eleventh slot, {@code OPTN011I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:120}; the main menu paints ten options and the
     *                        admin menu four, so this slot is unpainted on both screens as the tables
     *                        stand - it is declared because the map declares it, not because either menu
     *                        fills it
     * @param optionSlot12    the twelfth slot, {@code OPTN012I PIC X(40)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:126}; unpainted on both screens, for the same
     *                        reason as the eleventh
     * @param selectedOption  the operator's raw selection, {@code OPTIONI PIC X(2)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:132}; carried as the two characters the screen
     *                        holds rather than as a number, because {@code app/cbl/COMEN01C.cbl:127-129}
     *                        must be able to see a blank, a space-padded digit and a non-numeric value as
     *                        three different inputs; may be {@code null} when nothing was typed
     * @param errorMessage    the screen's error line, {@code ERRMSGI PIC X(78)} at
     *                        {@code app/cpy-bms/COMEN01.CPY:138}; may be {@code null} when the request
     *                        succeeded
     */
    public record MenuScreen(
            String transactionName,
            String title01,
            String currentDate,
            String programName,
            String title02,
            String currentTime,
            String optionSlot01,
            String optionSlot02,
            String optionSlot03,
            String optionSlot04,
            String optionSlot05,
            String optionSlot06,
            String optionSlot07,
            String optionSlot08,
            String optionSlot09,
            String optionSlot10,
            String optionSlot11,
            String optionSlot12,
            String selectedOption,
            String errorMessage) {

        /**
         * Declared width of {@code TRNNAMEI}, {@code PIC X(4)} at {@code app/cpy-bms/COMEN01.CPY}:24.
         */
        public static final int TRANSACTION_NAME_WIDTH = 4;

        /**
         * Declared width of {@code TITLE01I} and {@code TITLE02I}, {@code PIC X(40)} at
         * {@code app/cpy-bms/COMEN01.CPY}:30 and :48. The two title lines share a width because the map
         * declares the same clause twice, not because they are the same field.
         */
        public static final int TITLE_WIDTH = 40;

        /**
         * Declared width of {@code CURDATEI}, {@code PIC X(8)} at {@code app/cpy-bms/COMEN01.CPY}:36.
         */
        public static final int DATE_WIDTH = 8;

        /**
         * Declared width of {@code PGMNAMEI}, {@code PIC X(8)} at {@code app/cpy-bms/COMEN01.CPY}:42.
         *
         * <p>Numerically equal to {@link MenuResponse#PROGRAM_NAME_LENGTH} but a different contract: that
         * one bounds the table sub-field {@code CDEMO-MENU-OPT-PGMNAME}, this one bounds the screen header
         * field. They are declared separately so that a future divergence in either source cannot silently
         * change the other.</p>
         */
        public static final int PROGRAM_NAME_WIDTH = 8;

        /**
         * Declared width of {@code CURTIMEI} on this map, {@code PIC X(8)} at
         * {@code app/cpy-bms/COMEN01.CPY}:54.
         *
         * <p>Sixteen of the seventeen symbolic maps agree on eight; {@code app/cpy-bms/COSGN00.CPY}:54
         * alone declares nine. The disagreement is why no shared header type exists.</p>
         */
        public static final int TIME_WIDTH = 8;

        /**
         * Declared width of {@code OPTIONI}, {@code PIC X(2)} at {@code app/cpy-bms/COMEN01.CPY}:132.
         */
        public static final int SELECTION_WIDTH = 2;

        /**
         * Declared width of {@code ERRMSGI}, {@code PIC X(78)} at {@code app/cpy-bms/COMEN01.CPY}:138.
         */
        public static final int ERROR_MESSAGE_WIDTH = 78;

        /**
         * The number of recurring header fields the map declares ahead of the caption slots: six.
         *
         * <p>{@code TRNNAMEI}, {@code TITLE01I}, {@code CURDATEI}, {@code PGMNAMEI}, {@code TITLE02I} and
         * {@code CURTIMEI}, at {@code app/cpy-bms/COMEN01.CPY}:24, :30, :36, :42, :48 and :54.
         */
        public static final int HEADER_FIELD_COUNT = 6;

        /**
         * The number of caption slots the map declares: twelve.
         *
         * <p>{@code OPTN001I} through {@code OPTN012I} at {@code app/cpy-bms/COMEN01.CPY}:60, :66, :72,
         * :78, :84, :90, :96, :102, :108, :114, :120 and :126. Twelve is the <em>map's</em> arity and
         * coincides with the {@code OCCURS 12 TIMES} arity of {@code app/cpy/COMEN02Y.cpy}:88; the
         * populated table counts are ten and four, and those are published on {@link MenuType} instead.
         */
        public static final int OPTION_SLOT_COUNT = 12;

        /**
         * The total number of input fields each menu map declares: {@code 6 + 12 + 1 + 1 = 20}.
         *
         * <p>Derived from its parts rather than written as a literal, so the arithmetic that proves the
         * field contract is visible in the source and cannot drift. Verified by direct count against both
         * {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} at commit {@code 7756d89},
         * which declare the identical twenty fields.
         */
        public static final int MAP_FIELD_COUNT = HEADER_FIELD_COUNT + OPTION_SLOT_COUNT + 1 + 1;

        /**
         * Rejects any component wider than the fixed-width map field it transcribes.
         *
         * <p>Nineteen checks, one per textual component, each naming its own field and quoting its own
         * {@code PICTURE} clause so that a failure identifies the map line without further lookup. A
         * {@code null} passes every check, because absence is a legitimate state of a screen field.
         */
        public MenuScreen {
            requireScreenFieldWidth(transactionName, TRANSACTION_NAME_WIDTH, "transactionName", "PIC X(4)");
            requireScreenFieldWidth(title01, TITLE_WIDTH, "title01", "PIC X(40)");
            requireScreenFieldWidth(currentDate, DATE_WIDTH, "currentDate", "PIC X(8)");
            requireScreenFieldWidth(programName, PROGRAM_NAME_WIDTH, "programName", "PIC X(8)");
            requireScreenFieldWidth(title02, TITLE_WIDTH, "title02", "PIC X(40)");
            requireScreenFieldWidth(currentTime, TIME_WIDTH, "currentTime", "PIC X(8)");
            requireScreenFieldWidth(optionSlot01, SCREEN_OPTION_SLOT_LENGTH, "optionSlot01", "PIC X(40)");
            requireScreenFieldWidth(optionSlot02, SCREEN_OPTION_SLOT_LENGTH, "optionSlot02", "PIC X(40)");
            requireScreenFieldWidth(optionSlot03, SCREEN_OPTION_SLOT_LENGTH, "optionSlot03", "PIC X(40)");
            requireScreenFieldWidth(optionSlot04, SCREEN_OPTION_SLOT_LENGTH, "optionSlot04", "PIC X(40)");
            requireScreenFieldWidth(optionSlot05, SCREEN_OPTION_SLOT_LENGTH, "optionSlot05", "PIC X(40)");
            requireScreenFieldWidth(optionSlot06, SCREEN_OPTION_SLOT_LENGTH, "optionSlot06", "PIC X(40)");
            requireScreenFieldWidth(optionSlot07, SCREEN_OPTION_SLOT_LENGTH, "optionSlot07", "PIC X(40)");
            requireScreenFieldWidth(optionSlot08, SCREEN_OPTION_SLOT_LENGTH, "optionSlot08", "PIC X(40)");
            requireScreenFieldWidth(optionSlot09, SCREEN_OPTION_SLOT_LENGTH, "optionSlot09", "PIC X(40)");
            requireScreenFieldWidth(optionSlot10, SCREEN_OPTION_SLOT_LENGTH, "optionSlot10", "PIC X(40)");
            requireScreenFieldWidth(optionSlot11, SCREEN_OPTION_SLOT_LENGTH, "optionSlot11", "PIC X(40)");
            requireScreenFieldWidth(optionSlot12, SCREEN_OPTION_SLOT_LENGTH, "optionSlot12", "PIC X(40)");
            requireScreenFieldWidth(selectedOption, SELECTION_WIDTH, "selectedOption", "PIC X(2)");
            requireScreenFieldWidth(errorMessage, ERROR_MESSAGE_WIDTH, "errorMessage", "PIC X(78)");
        }

        /**
         * Returns the twelve caption slots in map order, slot one first.
         *
         * <p>A pure derivation over the twelve components, provided so that a caller iterating the slots
         * does not have to name each one and so that positional order is assertable. The list has exactly
         * {@link #OPTION_SLOT_COUNT} elements always, and an element is {@code null} when the
         * corresponding slot was unpainted - which is why the list is built over a null-tolerant backing
         * array rather than with {@code List.of}, whose elements may not be {@code null}.
         *
         * <p>Deliberately named without a {@code get} prefix so that it is not a bean property: it is a
         * view over components that are already on the wire, and emitting it as well would put every
         * caption on the payload twice. The same convention is used for the derived date-part accessors of
         * {@code AccountUpdateRequest}.
         *
         * <p>No side effects, no state, cannot fail, and the result depends only on the twelve components.
         *
         * @return an unmodifiable, order-preserving list of exactly {@link #OPTION_SLOT_COUNT} slots, any
         *         element of which may be {@code null}
         */
        public List<String> optionSlots() {
            final List<String> slots = new ArrayList<>(OPTION_SLOT_COUNT);
            slots.add(this.optionSlot01);
            slots.add(this.optionSlot02);
            slots.add(this.optionSlot03);
            slots.add(this.optionSlot04);
            slots.add(this.optionSlot05);
            slots.add(this.optionSlot06);
            slots.add(this.optionSlot07);
            slots.add(this.optionSlot08);
            slots.add(this.optionSlot09);
            slots.add(this.optionSlot10);
            slots.add(this.optionSlot11);
            slots.add(this.optionSlot12);
            return Collections.unmodifiableList(slots);
        }

        /**
         * Rejects a non-{@code null} screen field wider than its declared {@code PICTURE} clause.
         *
         * <p>The null-tolerant counterpart of
         * {@link MenuResponse#requireWidthWithinLimit(String, int, String, String)}, to which it delegates
         * once it has established that there is a value to measure. Keeping the {@code null} test here
         * rather than in the shared helper is what lets the option records treat {@code null} as a
         * separate, separately reported failure while this record treats it as an accepted state.
         *
         * @param value     the value to check, or {@code null} when the field was absent
         * @param maxLength the declared width of the map field
         * @param fieldName the component name to name in the failure message
         * @param picClause the source {@code PICTURE} clause to quote in the failure message
         * @throws IllegalArgumentException if {@code value} is non-{@code null} and longer than
         *                                  {@code maxLength}
         */
        private static void requireScreenFieldWidth(final String value, final int maxLength,
                final String fieldName, final String picClause) {
            if (value != null) {
                requireWidthWithinLimit(value, maxLength, fieldName, picClause);
            }
        }
    }

    /**
     * The ten main-menu options, transcribed from {@code CDEMO-MENU-OPTIONS-DATA} at
     * {@code app/cpy/COMEN02Y.cpy:25-84} in copybook order.
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
     */
    public static final List<AdminMenuOption> ADMIN_MENU_OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)               ", "COUSR00C"),
            new AdminMenuOption(2, "User Add (Security)                ", "COUSR01C"),
            new AdminMenuOption(3, "User Update (Security)             ", "COUSR02C"),
            new AdminMenuOption(4, "User Delete (Security)             ", "COUSR03C"));

    /**
     * Which menu this response describes, so a consumer never has to infer it. Never {@code null}. The legacy
     * system needed no such discriminator because the choice was made by which copybook a program copied,
     * {@code app/cpy/COMEN02Y.cpy} or {@code app/cpy/COADM02Y.cpy}.
     */
    private final MenuType menuType;

    /**
     * The options this response carries, in menu order, already defensively copied and wrapped unmodifiable.
     * Never {@code null}; possibly empty. The entries are the table rows of
     * {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at {@code app/cpy/COMEN02Y.cpy:88} or of
     * {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} at {@code app/cpy/COADM02Y.cpy:45}, bounded by the populated count.
     */
    private final List<T> options;

    /**
     * Constructs a response over a defensive copy of the supplied options.
     *
     * @param menuType which menu is being described.
     * @param options the options in menu order.
     * @throws IllegalArgumentException if any argument violates the conditions above.
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
     * @return a main-menu response carrying exactly ten options, never {@code null}
     */
    public static MenuResponse<MainMenuOption> mainMenu() {
        return new MenuResponse<>(MenuType.MAIN, MAIN_MENU_OPTIONS);
    }

    /**
     * Returns a main-menu response over the supplied options, which is how a filtered menu is built.
     *
     * @param options the main-menu options to carry, in menu order.
     * @return a main-menu response over a defensive copy of {@code options}, never {@code null}
     * @throws IllegalArgumentException if {@code options} is {@code null}, contains a {@code null} element, or
     * holds more than ten entries.
     */
    public static MenuResponse<MainMenuOption> ofMainMenu(final List<MainMenuOption> options) {
        return new MenuResponse<>(MenuType.MAIN, options);
    }

    /**
     * Returns the complete administrator menu: all four options of {@link #ADMIN_MENU_OPTIONS}.
     *
     * @return an admin-menu response carrying exactly four options, never {@code null}
     */
    public static MenuResponse<AdminMenuOption> adminMenu() {
        return new MenuResponse<>(MenuType.ADMIN, ADMIN_MENU_OPTIONS);
    }

    /**
     * Returns an admin-menu response over the supplied options.
     *
     * @param options the admin-menu options to carry, in menu order.
     * @return an admin-menu response over a defensive copy of {@code options}, never {@code null}
     * @throws IllegalArgumentException if {@code options} is {@code null}, contains a {@code null} element, or
     * holds more than four entries.
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
     * @return the number of options carried, between 0 and {@link MenuType#getPopulatedOptionCount()}
     */
    public int getOptionCount() {
        return this.options.size();
    }

    /**
     * Compares this response with another for value equality over the menu type and the options.
     *
     * @param other the object to compare with, which may be {@code null}
     * @return {@code true} when {@code other} is a response for the same menu carrying equal options in the
     * same order
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
     * @param optionNumber the value to check
     * @param fieldName the component name to name in the failure message
     * @throws IllegalArgumentException if {@code optionNumber} is negative or greater than
     * {@link #OPTION_NUMBER_MAX_VALUE}
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
     * @param value the value to check
     * @param fieldName the component name to name in the failure message
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static void requireNonNullField(final String value, final String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName
                    + " must not be null; supply the value the copybook literal declares, empty if genuinely absent");
        }
    }

    /**
     * Rejects a text component wider than the fixed-width source field it transcribes.
     *
     * <p>Shared by both option records so the one rule has one implementation. The bound is the declared
     * {@code PICTURE} width, and it is a genuine upper bound rather than an exact requirement: a shorter
     * value is accepted unchanged, because the canonical tables blank-pad their literals and a caller
     * holding an unpadded caption is describing the same option. Nothing is padded, nothing is trimmed and
     * nothing is case-folded - a fixed-width field cannot hold more bytes than it declares, so a longer
     * value is refused outright rather than silently truncated, which would lose data, or silently
     * accepted, which would let a value through that the source screen could never have carried.</p>
     *
     * <p>The failure message reports the declared limit and the offending length but never the offending
     * value. Captions and program names are not sensitive, so the omission is not a privacy control here;
     * it is uniformity with the rest of the DTO package, where the same helper shape guards values that
     * are.</p>
     *
     * @param value      the value to check; must already have been checked for {@code null}
     * @param maxLength  the declared width of the source field, either {@link #OPTION_NAME_LENGTH} or
     *                   {@link #PROGRAM_NAME_LENGTH}
     * @param fieldName  the component name to name in the failure message
     * @param picClause  the source {@code PICTURE} clause to quote in the failure message, so the reason
     *                   for the bound is legible without opening the copybook
     * @throws IllegalArgumentException if {@code value} is longer than {@code maxLength}
     */
    private static void requireWidthWithinLimit(final String value, final int maxLength,
            final String fieldName, final String picClause) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength
                    + " characters because the source field is " + picClause + ", but was "
                    + value.length() + " characters long");
        }
    }
}
