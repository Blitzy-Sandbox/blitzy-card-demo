package com.vsergeychik.carddemo.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MenuOptions}, the single Java type for COBOL copybook
 * {@code app/cpy/COMEN02Y.cpy} - the {@code 01 CARDDEMO-MAIN-MENU-OPTIONS} main-menu option table of
 * transaction {@code CM00}.
 *
 * <h2>The unit under test and its only consumer</h2>
 * {@code app/cpy/COMEN02Y.cpy} is copied by exactly <strong>one</strong> program,
 * {@code app/cbl/COMEN01C.cbl}; a repository-wide search finds no other COBOL consumer. That program is
 * therefore the sole authority for how these bytes are observed, and every "why" comment below cites it
 * by line.
 *
 * <h2>Provenance of every expected value: statically derived (practice B12, AAP risk R-A)</h2>
 * No COBOL execution produced any expectation in this file. The legacy programs cannot be run in this
 * environment, so each literal, width and offset asserted here was transcribed by direct reading of
 * {@code app/cpy/COMEN02Y.cpy} and cross-checked against its consumer, and each one carries a
 * {@code COMEN02Y.cpy:<line>} or {@code COMEN01C.cbl:<line>} citation beside it. A reviewer can check
 * every number in this file against the copybook without leaving the file. The copybook itself is never
 * opened at run time and never written: it is the parity oracle (practice B3, gate G5).
 *
 * <h2>Governing rules</h2>
 * {@code review_rules} returns exactly one line, <strong>"No user rules provided."</strong> - there are
 * no user-specified rules for this file, none are invented here, and their absence is not treated as
 * licence to lower the bar. The enterprise-practice substitutes of AAP section 0.10.2 bind instead:
 * B1 (only the JUnit Jupiter and AssertJ APIs the pinned {@code spring-boot-starter-test} already
 * supplies, with no version declared anywhere and nothing added to the pom), B2 (nothing outside the
 * closed stack - no JUnit 4, no Hamcrest, no Testcontainers, and no Mockito, because this table has no
 * collaborator to stub), B3, B4 (the three traps below are documented, never quietly corrected),
 * B5 (slots 11 and 12 and the commented-out literal at {@code COMEN02Y.cpy:69} are preserved as they
 * are), B7 (plain deterministic JUnit; no watch mode and no default locale, timezone or charset is
 * consulted), B8 (every code page is an explicit argument and every import is individual - gate G52),
 * B9 (no mutable static state, fresh state per test, order independent - gate G53), B10, B11 (offsets,
 * widths and literals asserted explicitly; no copybook parser and no reflection-driven layout walker
 * standing in for them) and B12.
 *
 * <h2>What makes this table harder than its sibling</h2>
 * Four things, and each has its own assertions below:
 * <ol>
 *   <li>The table is {@code OCCURS} <strong>12</strong> ({@code COMEN02Y.cpy:88}) while only
 *       <strong>10</strong> slots carry a {@code VALUE} ({@code COMEN02Y.cpy:21}). Twelve is the number
 *       a reader does not expect, and conflating the two is the defect this file exists to prevent.</li>
 *   <li>An entry is <strong>46</strong> bytes, not the sibling admin table's 45. The whole difference is
 *       {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} at {@code COMEN02Y.cpy:92}, a user-type authorisation
 *       column {@code app/cpy/COADM02Y.cpy} does not declare at all.</li>
 *   <li>{@code COMEN02Y.cpy:69} holds a <strong>commented-out</strong> label directly above the live one
 *       on line 70, and it is <strong>exactly as long</strong> - 35 characters either way. Length cannot
 *       tell them apart; only the asterisk in column 7 can. A negative assertion pins the live value.</li>
 *   <li>An out-of-range subscript is <strong>genuinely reachable in production</strong> here, not merely
 *       defensive. See {@link SubscriptBoundsRejection} for the four-line source argument.</li>
 * </ol>
 *
 * <h2>Gates this file instruments</h2>
 * <ul>
 *   <li><strong>G21</strong> - total declared width, which fails immediately if a span is dropped or
 *       mis-sized. Asserted both ways: the layout's own self-check passes for the correct descriptor set
 *       and <em>fails</em> for one with the trailing one-byte {@code USRTYPE} span omitted, for one with
 *       an interior {@code USRTYPE} span omitted, and for one with a {@code PIC X(35)} span mis-sized.</li>
 *   <li><strong>G33</strong> - {@code OCCURS} subscripts are 1-based in COBOL and 0-based in Java. The
 *       first, a middle and the last slot are asserted at both ends of that shift, and out-of-range
 *       subscripts are proven to be <em>rejected</em> rather than clamped.</li>
 *   <li><strong>G34</strong> - {@code REDEFINES} is two typed views over one backing span, round-tripped
 *       in both directions, adding no byte to the group.</li>
 *   <li><strong>G22 / rule R4</strong>, in one point only: {@code CDEMO-MENU-OPT-NUM PIC 9(02)}
 *       ({@code COMEN02Y.cpy:89}) is scale-free, so the option number is an {@code int}. This copybook
 *       declares no decimal {@code PICTURE} at all.</li>
 *   <li><strong>G8</strong> - the verification counterpart of one one-type-per-copybook row.</li>
 *   <li><strong>G49</strong> - {@code app/java/pom.xml} applies its {@code BRANCH} >= 0.90 rule at
 *       {@code PACKAGE} granularity as well as {@code BUNDLE}, so {@code admin.model} is measured on its
 *       own and cannot hide behind {@code admin}. Together with {@code AdminMenuOptionsTest} this file
 *       must drive every decision in {@link MenuOptions}: each arm of the bounds check (below range, in
 *       range and valued, in range and unvalued, above range) and both sides of every internal
 *       predicate.</li>
 * </ul>
 *
 * <h2>Gates with no subject here - stated so nobody chases coverage by inventing tests</h2>
 * <ul>
 *   <li><strong>G50</strong> (both truth states of every {@code 88}-level condition name) has
 *       <strong>no subject</strong>: {@code COMEN02Y.cpy} declares <em>zero</em> {@code 88} levels across
 *       all 95 of its lines. There is no condition name here to drive either way.</li>
 *   <li><strong>G22-G29</strong> beyond the single integral-type point above: this copybook has no scaled
 *       decimal field, no rounding, no arithmetic and no monetary value.</li>
 *   <li><strong>G35</strong> (abend and return code), <strong>G37-G43</strong> (online behaviour,
 *       statelessness, pagination, {@code XCTL}, optimistic concurrency) and <strong>G44-G48</strong>
 *       (data access, file status, alternate indexes): none applies. This file consumes no
 *       {@code common.CobolDecimal}, no {@code common.FileStatus}, no {@code common.AbendException}, no
 *       repository, no {@code config} class and no Spring annotation - the unit under test is a
 *       compile-time constant table read from {@code WORKING-STORAGE}, not per-request or persisted
 *       data.</li>
 * </ul>
 *
 * <h2>Scope boundary: what this file deliberately does not assert</h2>
 * Only the model's own contract is asserted here - declared widths and offsets, the populated-versus-
 * declared split, the addressable tail slots, the 1-based accessor's bounds behaviour, the
 * {@code REDEFINES} accessor pair, the untrimmed literals and the two-byte option-number images.
 * Everything that is a <em>decision</em> belongs to the main-menu service and is asserted there, not
 * duplicated here: the {@code BUILD-MENU-OPTIONS} loop and its twelve-arm-plus-{@code WHEN OTHER} inner
 * {@code EVALUATE} ({@code COMEN01C.cbl:237-275}), the option-count validation
 * ({@code COMEN01C.cbl:127-134}), the <strong>user-type authorisation filter</strong>
 * ({@code COMEN01C.cbl:136-143}) and its {@code 'No access - Admin Only option... '} message, the
 * {@code 'DUMMY'} five-byte prefix branch ({@code COMEN01C.cbl:146}), the preserved-defect message
 * {@code 'This option Accountis coming soon ...'} with its missing space
 * ({@code COMEN01C.cbl:159-163}), {@code XCTL} / next-program resolution, the
 * {@code CDEMO-USER-TYPE} pass-through, the ENTER/REENTER split and {@code EIBAID} handling. This file
 * asserts only that the <em>accessor itself</em> rejects rather than clamps, and that the column's stored
 * value is {@code 'U'} on all ten populated slots.
 *
 * <h2>Three traps, recorded rather than resolved (practice B4)</h2>
 * <ol>
 *   <li><strong>The header comment lies.</strong> {@code COMEN02Y.cpy:2} reads
 *       {@code * CardDemo - Admin Menu Options} - byte-identical to {@code app/cpy/COADM02Y.cpy:2} - even
 *       though this copybook holds the <em>main</em> menu and its only consumer is the main-menu program.
 *       The two are distinguishable solely by their {@code 01} group name
 *       ({@code CARDDEMO-MAIN-MENU-OPTIONS} at {@code COMEN02Y.cpy:19} versus
 *       {@code CARDDEMO-ADMIN-MENU-OPTIONS}). A reader must never take the header as identification.</li>
 *   <li><strong>{@code COMEN02Y.cpy:67} is indented one column differently</strong> from its nine
 *       siblings - {@code 10 FILLER} carries an extra space before {@code PIC}. Cosmetic only, with no
 *       layout effect whatsoever; noted so nobody mistakes it for a structural difference.</li>
 *   <li>The group name {@code CDEMO-MENU-OPT} ({@code COMEN02Y.cpy:88}) is itself <strong>never
 *       referenced</strong> by {@code COMEN01C.cbl} - only its four child fields are, subscripted.
 *       Harmless, and noted rather than read as evidence that the group is unused.</li>
 * </ol>
 *
 * <h2>One adaptation, stated plainly</h2>
 * Slots 11 and 12 lie past the end of the group being redefined, so no {@code VALUE} clause reaches them
 * and {@link MenuOptions} reports them as <strong>absent</strong> rather than as fabricated entries
 * carrying option number 0 and blank text. "Present but empty" is therefore asserted here as what the
 * class actually guarantees: those subscripts are <em>addressable and accepted</em> - never rejected,
 * never clamped - the table stays twelve slots wide, and the bytes their spans occupy are emitted at
 * their declared widths of 35, 8 and 1. The byte content of those spans is this module's own reproducible
 * pad, and it is asserted as such, not as a claim about what the legacy program's storage holds.
 */
@DisplayName("MenuOptions - CARDDEMO-MAIN-MENU-OPTIONS of COMEN02Y")
class MenuOptionsTest {

    /** The code page of the ASCII fixtures, named explicitly - never a platform default (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary datasets, used to prove the encoding is the caller's choice. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    // =================================================================================================
    // The copybook's ten PIC X(35) literals, each written as its visible text plus an EXPLICIT trailing
    // space count so the padding is legible and countable rather than hidden inside an opaque
    // 35-character literal. Every visible length and every pad count below was measured against the
    // copybook line cited (practice B12); the assertions further prove each composes to exactly 35.
    // =================================================================================================

    /** {@code COMEN02Y.cpy:27} {@code 'Account View                       '}: 12 visible + 23 spaces. */
    private static final String OPTION_1_NAME = "Account View" + " ".repeat(23);

    /** {@code COMEN02Y.cpy:33} {@code 'Account Update                     '}: 14 visible + 21 spaces. */
    private static final String OPTION_2_NAME = "Account Update" + " ".repeat(21);

    /** {@code COMEN02Y.cpy:39} {@code 'Credit Card List                   '}: 16 visible + 19 spaces. */
    private static final String OPTION_3_NAME = "Credit Card List" + " ".repeat(19);

    /** {@code COMEN02Y.cpy:45} {@code 'Credit Card View                   '}: 16 visible + 19 spaces. */
    private static final String OPTION_4_NAME = "Credit Card View" + " ".repeat(19);

    /** {@code COMEN02Y.cpy:51} {@code 'Credit Card Update                 '}: 18 visible + 17 spaces. */
    private static final String OPTION_5_NAME = "Credit Card Update" + " ".repeat(17);

    /** {@code COMEN02Y.cpy:57} {@code 'Transaction List                   '}: 16 visible + 19 spaces. */
    private static final String OPTION_6_NAME = "Transaction List" + " ".repeat(19);

    /** {@code COMEN02Y.cpy:63} {@code 'Transaction View                   '}: 16 visible + 19 spaces. */
    private static final String OPTION_7_NAME = "Transaction View" + " ".repeat(19);

    /**
     * {@code COMEN02Y.cpy:70} {@code 'Transaction Add                    '}: 15 visible + 20 spaces.
     *
     * <p>This is the <strong>live</strong> literal. The line immediately above it, line 69, holds an
     * abandoned earlier wording that is commented out; see {@link #DECOY_OPTION_8_NAME}.
     */
    private static final String OPTION_8_NAME = "Transaction Add" + " ".repeat(20);

    /** {@code COMEN02Y.cpy:76} {@code 'Transaction Reports                '}: 19 visible + 16 spaces. */
    private static final String OPTION_9_NAME = "Transaction Reports" + " ".repeat(16);

    /** {@code COMEN02Y.cpy:82} {@code 'Bill Payment                       '}: 12 visible + 23 spaces. */
    private static final String OPTION_10_NAME = "Bill Payment" + " ".repeat(23);

    /**
     * The <strong>commented-out</strong> literal at {@code COMEN02Y.cpy:69}:
     * {@code 'Transaction Add (Admin Only)       '}, 28 visible + 7 spaces.
     *
     * <p>It carries an asterisk in column 7, which makes it a COBOL comment, and it sits directly above
     * the live value on line 70. It is transcribed here for <strong>one</strong> purpose: to be asserted
     * <em>against</em>, so the trap is regression-proof rather than merely commented. It is never offered
     * as an alternative value, never blended with the live one and never substituted for it (practice
     * B5).
     *
     * <p>It is exactly <strong>35 characters</strong> - the same width as the live literal - which is
     * precisely why a length check cannot discriminate between them and why the negative assertion in
     * {@link CommentedOutDecoy} has to compare the value itself.
     */
    private static final String DECOY_OPTION_8_NAME = "Transaction Add (Admin Only)" + " ".repeat(7);

    /**
     * The ten transcribed names in subscript order, so a test can walk them without re-typing one.
     *
     * <p>An immutable {@link List#of} of immutable strings, so it introduces no shared mutable state
     * (practice B9, gate G53).
     */
    private static final List<String> OPTION_NAMES = List.of(
            OPTION_1_NAME, OPTION_2_NAME, OPTION_3_NAME, OPTION_4_NAME, OPTION_5_NAME,
            OPTION_6_NAME, OPTION_7_NAME, OPTION_8_NAME, OPTION_9_NAME, OPTION_10_NAME);

    /**
     * The ten {@code CDEMO-MENU-OPT-PGMNAME} literals in subscript order -
     * {@code COMEN02Y.cpy:28}, {@code :34}, {@code :40}, {@code :46}, {@code :52}, {@code :58},
     * {@code :64}, {@code :71}, {@code :77}, {@code :83}. Every one is exactly 8 characters, which is
     * asserted rather than assumed so that a future seven-character name cannot slip through.
     */
    private static final List<String> OPTION_PROGRAM_NAMES = List.of(
            "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");

    /**
     * The user-type authorisation value every populated entry carries - {@code 'U'} -
     * {@code COMEN02Y.cpy:29}, {@code :35}, {@code :41}, {@code :47}, {@code :53}, {@code :59},
     * {@code :65}, {@code :72}, {@code :78} and {@code :84}.
     */
    private static final String USER_TYPE_USER = "U";

    /**
     * The administrator user-type value, transcribed only to be asserted <em>absent</em>: no populated
     * entry of this copybook carries it. Verified across all 95 lines.
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * The width of one entry in the sibling admin-menu table, {@code app/cpy/COADM02Y.cpy}, which
     * declares only {@code PIC 9(02)} + {@code PIC X(35)} + {@code PIC X(08)} and <strong>no</strong>
     * user-type column. Transcribed here so the one-byte divergence is provable rather than assumed; the
     * sibling type is deliberately not imported.
     */
    private static final int SIBLING_ADMIN_ENTRY_LENGTH = 45;

    /**
     * The natural wrong answer for {@code POPULATED_DATA_LENGTH}, asserted against.
     *
     * <p>{@code CDEMO-MENU-OPT-COUNT} at {@code COMEN02Y.cpy:21} is a level-05 item sitting
     * <strong>outside</strong> {@code CDEMO-MENU-OPTIONS-DATA}, which only begins at
     * {@code COMEN02Y.cpy:23}. Folding its two bytes into the populated area would give 462; the correct
     * figure is 460.
     */
    private static final int POPULATED_DATA_LENGTH_IF_COUNT_WERE_FOLDED_IN = 462;

    /**
     * Pads synthetic test text to {@code CDEMO-MENU-OPT-NAME}'s declared width. Used only for values this
     * file invents; the ten copybook literals above state their padding explicitly instead.
     *
     * <p>A pure static function over immutable values, so it introduces no shared state (practice B9).
     */
    private static String toNameWidth(String visible) {
        return visible + " ".repeat(MenuOptions.OPT_NAME_LENGTH - visible.length());
    }

    /** A fresh codec per call, so no test can hand mutable state to another (practice B9, gate G53). */
    private static FixedWidthCodec asciiCodec() {
        return new FixedWidthCodec(ASCII);
    }

    /**
     * A fresh, mutable copy of the layout's storage descriptors, for the tests that deliberately damage a
     * descriptor set and assert the self-check catches it. A copy, because {@code storageSpans()} is
     * unmodifiable and because no test may mutate state another test reads.
     */
    private static List<FieldSpan> mutableStorageSpans() {
        return new ArrayList<>(MenuOptions.GROUP_LAYOUT.storageSpans());
    }

    @Nested
    @DisplayName("Constants and widths, transcribed from the copybook rather than read off the class")
    class ConstantsAndWidths {

        @Test
        @DisplayName("each declared width is the PICTURE the copybook writes")
        void eachDeclaredWidthIsItsPicture() {
            // COMEN02Y.cpy:89 - 15 CDEMO-MENU-OPT-NUM     PIC 9(02).
            assertThat(MenuOptions.OPT_NUM_LENGTH).isEqualTo(2);
            // COMEN02Y.cpy:90 - 15 CDEMO-MENU-OPT-NAME    PIC X(35).
            assertThat(MenuOptions.OPT_NAME_LENGTH).isEqualTo(35);
            // COMEN02Y.cpy:91 - 15 CDEMO-MENU-OPT-PGMNAME PIC X(08).
            assertThat(MenuOptions.OPT_PGMNAME_LENGTH).isEqualTo(8);
            // COMEN02Y.cpy:92 - 15 CDEMO-MENU-OPT-USRTYPE PIC X(01).
            assertThat(MenuOptions.OPT_USRTYPE_LENGTH).isEqualTo(1);
            // COMEN02Y.cpy:21 - 05 CDEMO-MENU-OPT-COUNT   PIC 9(02) VALUE 10, two digits of storage.
            assertThat(MenuOptions.MENU_OPT_COUNT_LENGTH).isEqualTo(2);
            // COMEN02Y.cpy:89-92 - four subfields per entry and no more.
            assertThat(MenuOptions.SUBFIELDS_PER_ENTRY).isEqualTo(4);
            // COBOL subscripts start at 1, never at Java's 0.
            assertThat(MenuOptions.FIRST_SUBSCRIPT).isEqualTo(1);
            // PIC 9(02) is unsigned two-digit display, so it holds 0 to 99 inclusive and nothing else.
            assertThat(MenuOptions.MIN_OPT_NUM).isZero();
            assertThat(MenuOptions.MAX_OPT_NUM).isEqualTo(99);
        }

        @Test
        @DisplayName("an entry is 46 bytes: 2 + 35 + 8 + 1, one MORE than the sibling admin table's 45")
        void anEntryIsFortySixBytesComponentWise() {
            // Asserted component-wise, so the divergence from the sibling table is PROVABLE rather than
            // assumed. The whole difference is the fourth subfield, CDEMO-MENU-OPT-USRTYPE PIC X(01) at
            // COMEN02Y.cpy:92: app/cpy/COADM02Y.cpy declares no X(01) FILLER in its option quadruplets
            // and no -USRTYPE field in its OCCURS element, so its entry is 45 bytes. Assuming the two
            // interchangeable would shift every field of every entry after the first.
            assertThat(MenuOptions.OPT_NUM_LENGTH
                    + MenuOptions.OPT_NAME_LENGTH
                    + MenuOptions.OPT_PGMNAME_LENGTH
                    + MenuOptions.OPT_USRTYPE_LENGTH)
                    .isEqualTo(MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(46);
            assertThat(MenuOptions.ENTRY_LENGTH)
                    .as("46, not the sibling admin entry's 45; the extra byte is USRTYPE")
                    .isEqualTo(SIBLING_ADMIN_ENTRY_LENGTH + MenuOptions.OPT_USRTYPE_LENGTH)
                    .isNotEqualTo(SIBLING_ADMIN_ENTRY_LENGTH);
        }

        @Test
        @DisplayName("the table declares TWELVE slots while only TEN are active - two independent facts")
        void theTableSizeAndTheActiveCountAreIndependent() {
            // COMEN02Y.cpy:88 - 10 CDEMO-MENU-OPT OCCURS 12 TIMES. Twelve is the number a reader will not
            // expect, and it is corroborated by COMEN01C.cbl:237-275, whose EVALUATE WS-IDX dispatches
            // WHEN 1 through WHEN 12 before WHEN OTHER.
            assertThat(MenuOptions.TABLE_SIZE).isEqualTo(12);
            // COMEN02Y.cpy:21 - 05 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10.
            assertThat(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(10);
            // They must never be conflated. COMEN01C.cbl:238-239 bounds the BUILD-MENU-OPTIONS loop on
            // the COUNT (10), while every subscripted access is bounded by the TABLE SIZE (12) - which is
            // exactly why slots 11 and 12 are never populated.
            assertThat(MenuOptions.TABLE_SIZE).isGreaterThan(MenuOptions.ACTIVE_OPTION_COUNT);
            assertThat(MenuOptions.TABLE_SIZE - MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(2);
            // The first subscript the copybook values nothing for.
            assertThat(MenuOptions.SPECIFIED_OPTION_COUNT_PLUS_ONE).isEqualTo(11);
        }

        @Test
        @DisplayName("the populated area is 460 bytes, NOT 462: the count field sits outside the group")
        void thePopulatedAreaIsFourHundredAndSixty() {
            // The natural wrong answer is 462, arrived at by folding CDEMO-MENU-OPT-COUNT's two bytes into
            // the populated area. It does not belong there: COMEN02Y.cpy:21 declares the count as a
            // level-05 item, and CDEMO-MENU-OPTIONS-DATA only BEGINS at COMEN02Y.cpy:23. The populated
            // area is exactly the ten quadruplets of COMEN02Y.cpy:25-84.
            assertThat(MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(MenuOptions.ACTIVE_OPTION_COUNT * MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(460)
                    .isNotEqualTo(POPULATED_DATA_LENGTH_IF_COUNT_WERE_FOLDED_IN);
            // COMEN02Y.cpy:87-88 - the OCCURS overlay is twelve slots wide.
            assertThat(MenuOptions.TABLE_LENGTH)
                    .isEqualTo(MenuOptions.TABLE_SIZE * MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(552);
            // The whole 01 group of COMEN02Y.cpy:19 - the count plus the overlay it precedes.
            assertThat(MenuOptions.GROUP_LENGTH)
                    .isEqualTo(MenuOptions.MENU_OPT_COUNT_LENGTH + MenuOptions.TABLE_LENGTH)
                    .isEqualTo(554);
        }

        @Test
        @DisplayName("the subfield offsets follow the copybook's declaration order exactly")
        void theSubfieldOffsetsFollowDeclarationOrder() {
            // COMEN02Y.cpy:89-92, in order, with each offset the running total of the widths before it.
            assertThat(MenuOptions.OPT_NUM_OFFSET).isZero();
            assertThat(MenuOptions.OPT_NAME_OFFSET).isEqualTo(2);
            assertThat(MenuOptions.OPT_PGMNAME_OFFSET).isEqualTo(37);
            assertThat(MenuOptions.OPT_USRTYPE_OFFSET).isEqualTo(45);
            // The last subfield ends exactly at the entry width, so no byte of an entry is unaccounted for.
            assertThat(MenuOptions.OPT_USRTYPE_OFFSET + MenuOptions.OPT_USRTYPE_LENGTH)
                    .isEqualTo(MenuOptions.ENTRY_LENGTH);
            // The subfield positions within entryFieldSpans(int), in copybook order.
            assertThat(MenuOptions.NUM_SUBFIELD).isZero();
            assertThat(MenuOptions.NAME_SUBFIELD).isEqualTo(1);
            assertThat(MenuOptions.PGMNAME_SUBFIELD).isEqualTo(2);
            assertThat(MenuOptions.USRTYPE_SUBFIELD).isEqualTo(3);
        }

        @Test
        @DisplayName("the count field precedes the option area, and both views start right after it")
        void theCountFieldPrecedesTheOptionArea() {
            // COMEN02Y.cpy:21 then :23 - the count occupies bytes 0 and 1, and the option area starts at
            // byte 2. That single offset is what makes the two REDEFINES views two views of ONE span.
            assertThat(MenuOptions.MENU_OPT_COUNT_OFFSET).isZero();
            assertThat(MenuOptions.TABLE_OFFSET)
                    .isEqualTo(MenuOptions.MENU_OPT_COUNT_OFFSET + MenuOptions.MENU_OPT_COUNT_LENGTH)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the copybook item names are published verbatim, hyphens and all")
        void theCopybookItemNamesArePublishedVerbatim() {
            // The parity differ compares field by field BY NAME, so these spellings are contract.
            assertThat(MenuOptions.CARDDEMO_MAIN_MENU_OPTIONS)
                    .as("COMEN02Y.cpy:19 - and note it is MAIN, not ADMIN, despite the header on line 2")
                    .isEqualTo("CARDDEMO-MAIN-MENU-OPTIONS");
            assertThat(MenuOptions.CDEMO_MENU_OPT_COUNT).isEqualTo("CDEMO-MENU-OPT-COUNT");
            assertThat(MenuOptions.CDEMO_MENU_OPTIONS_DATA).isEqualTo("CDEMO-MENU-OPTIONS-DATA");
            assertThat(MenuOptions.CDEMO_MENU_OPTIONS).isEqualTo("CDEMO-MENU-OPTIONS");
            assertThat(MenuOptions.CDEMO_MENU_OPT).isEqualTo("CDEMO-MENU-OPT");
            assertThat(MenuOptions.CDEMO_MENU_OPT_NUM).isEqualTo("CDEMO-MENU-OPT-NUM");
            assertThat(MenuOptions.CDEMO_MENU_OPT_NAME).isEqualTo("CDEMO-MENU-OPT-NAME");
            assertThat(MenuOptions.CDEMO_MENU_OPT_PGMNAME).isEqualTo("CDEMO-MENU-OPT-PGMNAME");
            assertThat(MenuOptions.CDEMO_MENU_OPT_USRTYPE).isEqualTo("CDEMO-MENU-OPT-USRTYPE");
        }

        @Test
        @DisplayName("CDEMO-MENU-OPT-COUNT is readable as a number, as a two-byte image and as a span")
        void theCountFieldIsReadableEveryWay() {
            // COMEN02Y.cpy:21 - VALUE 10. PIC 9(02) means the image is two bytes wide, and 10 needs both,
            // so this is the one count in the pair that is not zero-filled.
            assertThat(MenuOptions.menuOptCount()).isEqualTo(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(10);
            assertThat(MenuOptions.menuOptCountImage()).isEqualTo("10").hasSize(2);

            FieldSpan count = MenuOptions.menuOptCountSpan();
            assertThat(count.name()).isEqualTo(MenuOptions.CDEMO_MENU_OPT_COUNT);
            assertThat(count.offset()).isZero();
            assertThat(count.length()).isEqualTo(2);
            assertThat(count.kind()).isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(count.redefinition())
                    .as("the count is real storage, not an overlay")
                    .isFalse();
            assertThat(count.hasInitialValue())
                    .as("COMEN02Y.cpy:21 declares VALUE 10, so the descriptor carries it")
                    .isTrue();
        }

        @Test
        @DisplayName("nothing mutable escapes: every published collection refuses modification")
        void nothingMutableEscapes() {
            // Practice B9 / gate G53. The table is read concurrently by request threads and batch steps,
            // so a caller must not be able to alter it, and no test may hand mutable state to another.
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.options().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.activeOptions().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.fieldSpans().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.entryFieldSpans(1).clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.GROUP_LAYOUT.spans().clear());
        }

        @Test
        @DisplayName("encode() hands back a fresh array each time, so no caller can alter the table")
        void encodeHandsBackAFreshArray() {
            byte[] first = MenuOptions.encode(ASCII);
            first[0] = (byte) '?';

            assertThat(MenuOptions.encode(ASCII))
                    .as("COMEN02Y.cpy:21's VALUE 10 still leads the image")
                    .isNotSameAs(first)
                    .startsWith((byte) '1', (byte) '0');
        }

        @Test
        @DisplayName("the class is a constant table and refuses to be instantiated")
        void theClassRefusesInstantiation() throws Exception {
            Constructor<MenuOptions> constructor = MenuOptions.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("The ten entries the copybook values, byte for byte")
    class PopulatedEntries {

        /**
         * Every populated slot, with its expected values and the copybook lines each came from.
         *
         * <p>The visible text and the trailing-space count are stated separately in the constants at the
         * top of this file; here the composed constant is compared whole and its length is asserted as
         * well, so a pad count that is one short fails on the value AND on the width.
         */
        @ParameterizedTest(name = "option {0} from COMEN02Y.cpy:{1}-:{2}")
        @DisplayName("each carries its transcribed literal at its full declared width")
        @CsvSource({
            // subscript, first copybook line of the quadruplet, last copybook line of the quadruplet.
            // The expected values themselves come from OPTION_NAMES / OPTION_PROGRAM_NAMES, indexed by
            // subscript minus one; the two line numbers are carried purely so a failure names the
            // copybook lines the expectation was read from (practice B12).
            "1,  25,  29",
            "2,  31,  35",
            "3,  37,  41",
            "4,  43,  47",
            "5,  49,  53",
            "6,  55,  59",
            "7,  61,  65",
            "8,  67,  72",
            "9,  74,  78",
            "10, 80,  84",
        })
        void eachCarriesItsLiteralAtFullDeclaredWidth(int subscript, int firstLine, int lastLine) {
            // firstLine and lastLine are the copybook's own quadruplet bounds, carried into the failure
            // message so a reviewer is pointed straight at the lines the expectation was read from.
            String where = "COMEN02Y.cpy:" + firstLine + "-:" + lastLine;
            MenuOption option = MenuOptions.optionBySubscript(subscript).orElseThrow();

            assertThat(option.menuOptNum()).as(where + " - CDEMO-MENU-OPT-NUM").isEqualTo(subscript);
            assertThat(option.menuOptNumImage())
                    .as(where + " - the PIC 9(02) image is always two bytes")
                    .hasSize(MenuOptions.OPT_NUM_LENGTH);
            assertThat(option.menuOptName())
                    .as(where + " - CDEMO-MENU-OPT-NAME, PIC X(35) per COMEN02Y.cpy:90")
                    .isEqualTo(OPTION_NAMES.get(subscript - 1))
                    .hasSize(MenuOptions.OPT_NAME_LENGTH);
            assertThat(option.menuOptPgmName())
                    .as(where + " - CDEMO-MENU-OPT-PGMNAME, PIC X(08) per COMEN02Y.cpy:91")
                    .isEqualTo(OPTION_PROGRAM_NAMES.get(subscript - 1))
                    .hasSize(MenuOptions.OPT_PGMNAME_LENGTH);
            assertThat(option.menuOptUsrType())
                    .as(where + " - CDEMO-MENU-OPT-USRTYPE, PIC X(01) per COMEN02Y.cpy:92")
                    .isEqualTo(USER_TYPE_USER)
                    .hasSize(MenuOptions.OPT_USRTYPE_LENGTH);
        }

        @Test
        @DisplayName("every one of the ten transcribed names composes to exactly 35 characters")
        void everyTranscribedNameIsExactlyThirtyFiveCharacters() {
            // The constants at the top of this file are written as visible text plus an explicit pad
            // count. This proves each arithmetic, so a miscount is caught here rather than surfacing as a
            // mysterious offset shift 400 bytes later.
            assertThat(OPTION_NAMES).hasSize(MenuOptions.ACTIVE_OPTION_COUNT);
            assertThat(OPTION_NAMES)
                    .allSatisfy(name -> assertThat(name).hasSize(MenuOptions.OPT_NAME_LENGTH));
        }

        @Test
        @DisplayName("every one of the ten program names is exactly 8 characters")
        void everyProgramNameIsExactlyEightCharacters() {
            // Asserted deliberately, so a future seven-character program name cannot slip through: the
            // 'DUMMY' test at COMEN01C.cbl:146 slices the leading five bytes out of this eight-byte
            // column, and the column's width is what makes that slice legal.
            assertThat(OPTION_PROGRAM_NAMES).hasSize(MenuOptions.ACTIVE_OPTION_COUNT);
            assertThat(OPTION_PROGRAM_NAMES)
                    .allSatisfy(name -> assertThat(name).hasSize(MenuOptions.OPT_PGMNAME_LENGTH));
            assertThat(MenuOptions.activeOptions())
                    .allSatisfy(option -> assertThat(option.menuOptPgmName())
                            .hasSize(MenuOptions.OPT_PGMNAME_LENGTH));
        }

        @Test
        @DisplayName("slot 4 pairs 'Credit Card View' with COCRDSLC - transcribed, never corrected")
        void slotFourPairsCreditCardViewWithTheSelectProgram() {
            // COMEN02Y.cpy:45 and :46. The label says View while the target program is COCRDSLC, which
            // this migration names CardSelectController - one of the divergences catalogued in AAP
            // section 0.8.4 and governed by rule R1: the name comes from the prompt, the behaviour from
            // the source. The copybook's literal is asserted exactly as it stands; a label is never
            // "fixed" to agree with a Java class name.
            MenuOption option = MenuOptions.optionBySubscript(4).orElseThrow();

            assertThat(option.menuOptName()).isEqualTo(OPTION_4_NAME);
            assertThat(option.menuOptPgmName()).isEqualTo("COCRDSLC");
        }

        @Test
        @DisplayName("slot 7 views with COTRN01C and slot 8 adds with COTRN02C - the inversion is kept")
        void slotsSevenAndEightCarryTheTransactionInversion() {
            // COMEN02Y.cpy:63-64 and :70-71. README.md:L213-231 independently documents CT01 as
            // Transaction View and CT02 as Transaction Add, and this copybook agrees with it. The
            // prompt's class names invert the pair (COTRN01C -> TransactionAddController,
            // COTRN02C -> TransactionViewController), which AAP section 0.1.8 flags as conflict set 1.
            // The copybook is the oracle for the data; rule R1 keeps the mandated names for the classes.
            assertThat(MenuOptions.optionBySubscript(7).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptName()).isEqualTo(OPTION_7_NAME);
                        assertThat(option.menuOptPgmName()).isEqualTo("COTRN01C");
                    });
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptName()).isEqualTo(OPTION_8_NAME);
                        assertThat(option.menuOptPgmName()).isEqualTo("COTRN02C");
                    });
        }

        @Test
        @DisplayName("activeOptions() is the ten of them, in subscript order, with no absent slot")
        void activeOptionsIsTheTenOfThemInOrder() {
            // COMEN02Y.cpy:25-84. This is the view a build loop bounded by CDEMO-MENU-OPT-COUNT walks -
            // COMEN01C.cbl:238-239 - so it must contain exactly the valued entries and never an empty.
            List<MenuOption> active = MenuOptions.activeOptions();

            assertThat(active).hasSize(MenuOptions.ACTIVE_OPTION_COUNT).hasSize(10);
            assertThat(active).extracting(MenuOption::menuOptNum)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(active).extracting(MenuOption::menuOptPgmName)
                    .containsExactlyElementsOf(OPTION_PROGRAM_NAMES);
            assertThat(active).extracting(MenuOption::menuOptName)
                    .containsExactlyElementsOf(OPTION_NAMES);
        }

        @ParameterizedTest
        @DisplayName("isSpecified() is true for each of the ten the copybook values")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void isSpecifiedIsTrueForEveryValuedSlot(int subscript) {
            // The in-range-and-valued arm of the bounds check (gate G49).
            assertThat(MenuOptions.isSpecified(subscript)).isTrue();
            assertThat(MenuOptions.optionBySubscript(subscript)).isPresent();
        }
    }

    @Nested
    @DisplayName("COMEN02Y.cpy:69 - the commented-out decoy, asserted AGAINST")
    class CommentedOutDecoy {

        @Test
        @DisplayName("slot 8 uses the ACTIVE line-70 label, not the commented-out '(Admin Only)' decoy")
        void slotEightUsesActiveLabelNotCommentedOutAdminOnlyDecoy() {
            // COMEN02Y.cpy:68-70 declares ONE PIC X(35) VALUE with TWO candidate literals beneath it:
            //   :69   *        'Transaction Add (Admin Only)       '.     <- asterisk in column 7: COMMENT
            //   :70            'Transaction Add                    '.     <- LIVE
            // Only the column-7 asterisk distinguishes them. The positive assertion pins the live value;
            // the negative one below is what makes the trap regression-proof, because a comment alone
            // would not stop a future edit from "restoring" the abandoned wording.
            String name = MenuOptions.optionBySubscript(8).orElseThrow().menuOptName();

            assertThat(name)
                    .as("COMEN02Y.cpy:70 - the LIVE literal")
                    .isEqualTo(OPTION_8_NAME);
            assertThat(name)
                    .as("COMEN02Y.cpy:69 - the commented-out literal must NEVER be the value")
                    .isNotEqualTo(DECOY_OPTION_8_NAME)
                    .doesNotContain("Admin Only");
        }

        @Test
        @DisplayName("the decoy is ALSO exactly 35 characters, so length cannot discriminate")
        void theDecoyIsAlsoThirtyFiveCharacters() {
            // This is why the assertion above has to compare the VALUE. Both literals fill PIC X(35)
            // exactly - the live one as 15 visible + 20 spaces (COMEN02Y.cpy:70), the dead one as 28
            // visible + 7 spaces (COMEN02Y.cpy:69) - so a width check would pass either way and a
            // width-only test would be worthless here.
            assertThat(DECOY_OPTION_8_NAME).hasSize(MenuOptions.OPT_NAME_LENGTH).hasSize(35);
            assertThat(OPTION_8_NAME).hasSize(MenuOptions.OPT_NAME_LENGTH).hasSize(35);
            assertThat(DECOY_OPTION_8_NAME).hasSameSizeAs(OPTION_8_NAME).isNotEqualTo(OPTION_8_NAME);
        }

        @Test
        @DisplayName("nothing is inferred from the dead comment: slot 8's user type is 'U', not 'A'")
        void nothingIsInferredFromTheDeadComment() {
            // The temptation the decoy creates is to read "Admin Only" as authorisation and set option 8's
            // CDEMO-MENU-OPT-USRTYPE to 'A'. The live column on COMEN02Y.cpy:72 is 'U'. Setting it to 'A'
            // would be a behaviour change, because COMEN01C.cbl:136-137 compares this column against 'A'
            // to DENY access - it would lock every regular user out of Transaction Add.
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptUsrType())
                    .as("COMEN02Y.cpy:72")
                    .isEqualTo(USER_TYPE_USER)
                    .isNotEqualTo(USER_TYPE_ADMIN);
        }

        @Test
        @DisplayName("the decoy appears nowhere in the encoded image either")
        void theDecoyAppearsNowhereInTheEncodedImage() {
            // Belt and braces at the byte level: if the dead literal ever reached storage it would show up
            // in the 554-byte image, wherever it had been written.
            String image = new String(MenuOptions.encode(ASCII), ASCII);

            assertThat(image).contains(OPTION_8_NAME).doesNotContain("Admin Only");
        }
    }

    @Nested
    @DisplayName("CDEMO-MENU-OPT-USRTYPE - the user-type column the sibling admin table lacks")
    class UserTypeColumn {

        @ParameterizedTest
        @DisplayName("it is exactly one character wide on every populated slot")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void itIsExactlyOneCharacterWide(int subscript) {
            // COMEN02Y.cpy:92 - 15 CDEMO-MENU-OPT-USRTYPE PIC X(01). One byte, and it is the whole reason
            // an entry here is 46 bytes rather than the sibling admin table's 45.
            assertThat(MenuOptions.optionBySubscript(subscript).orElseThrow().menuOptUsrType())
                    .hasSize(MenuOptions.OPT_USRTYPE_LENGTH)
                    .hasSize(1);
        }

        @Test
        @DisplayName("it is 'U' on all ten populated entries")
        void itIsUserOnAllTenPopulatedEntries() {
            // Cited individually: COMEN02Y.cpy:29, :35, :41, :47, :53, :59, :65, :72, :78 and :84 each
            // read 10 FILLER PIC X(01) VALUE 'U'.
            assertThat(MenuOptions.activeOptions())
                    .hasSize(10)
                    .extracting(MenuOption::menuOptUsrType)
                    .containsExactly(USER_TYPE_USER, USER_TYPE_USER, USER_TYPE_USER, USER_TYPE_USER,
                            USER_TYPE_USER, USER_TYPE_USER, USER_TYPE_USER, USER_TYPE_USER,
                            USER_TYPE_USER, USER_TYPE_USER);
        }

        @Test
        @DisplayName("'A' appears on NO populated slot, in the entries or in the bytes")
        void adminNeverAppearsOnAnyPopulatedSlot() {
            // Verified across all 95 lines of the copybook: there is no X(01) VALUE 'A' anywhere in it.
            // Stated here as the model-level fact it is - COMEN01C.cbl:137's
            //   CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
            // is therefore FALSE for every in-range populated subscript. The FILTER's behaviour, including
            // the 'No access - Admin Only option... ' message it guards at COMEN01C.cbl:136-143, belongs to
            // the main-menu service's own tests and is deliberately not duplicated here.
            assertThat(MenuOptions.activeOptions())
                    .extracting(MenuOption::menuOptUsrType)
                    .doesNotContain(USER_TYPE_ADMIN);

            // And at the byte level, so a mis-written span cannot smuggle one in: read the user-type
            // column of each of the ten valued slots out of the encoded image by name.
            Map<String, String> images = MenuOptions.fieldImages(MenuOptions.encode(ASCII), ASCII);
            for (int subscript = 1; subscript <= MenuOptions.ACTIVE_OPTION_COUNT; subscript++) {
                String key = MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE, subscript);
                assertThat(images.get(key))
                        .as(key + " - COMEN02Y.cpy's X(01) column for that slot")
                        .isEqualTo(USER_TYPE_USER)
                        .isNotEqualTo(USER_TYPE_ADMIN);
            }
        }

        @Test
        @DisplayName("the column occupies the entry's last byte, so omitting it would shift nothing else")
        void theColumnOccupiesTheEntrysLastByte() {
            // COMEN02Y.cpy:92 is the fourth and final subfield, at offset 45 of a 46-byte entry. Because it
            // is last WITHIN an entry but not last within the table, dropping it shifts every LATER
            // entry - which is exactly what TotalWidthSelfCheck proves for the interior case.
            List<FieldSpan> spans = MenuOptions.entryFieldSpans(1);
            FieldSpan usrType = spans.get(MenuOptions.USRTYPE_SUBFIELD);

            assertThat(usrType.name())
                    .isEqualTo(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE, 1));
            assertThat(usrType.length()).isEqualTo(1);
            assertThat(usrType.offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.OPT_USRTYPE_OFFSET)
                    .isEqualTo(47);
            assertThat(usrType.endOffsetExclusive())
                    .as("byte 48 is where slot 2's CDEMO-MENU-OPT-NUM begins")
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(48);
        }
    }

    @Nested
    @DisplayName("Untrimmed values - the padding is part of the field, and here it is load-bearing")
    class UntrimmedValues {

        @ParameterizedTest
        @DisplayName("CDEMO-MENU-OPT-NAME comes back untrimmed at its full 35 characters")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void theNameComesBackUntrimmed(int subscript) {
            // The SAME 35-byte field is consumed two DIFFERENT ways by the same program, which is the
            // decisive reason the accessor must not trim:
            //   COMEN01C.cbl:245     STRING ... CDEMO-MENU-OPT-NAME(WS-IDX)  DELIMITED BY SIZE
            //                        -> needs ALL 35 bytes, trailing spaces included.
            //   COMEN01C.cbl:160-161 STRING ... CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE
            //                        -> needs only the text up to the first space.
            // A model that trimmed the label would break the DELIMITED BY SIZE consumer outright. Note
            // that COADM01C.cbl:150 - the sibling program's equivalent second use - is COMMENTED OUT, so
            // this dual-consumption pattern is live ONLY for the menu table.
            String name = MenuOptions.optionBySubscript(subscript).orElseThrow().menuOptName();

            assertThat(name).hasSize(MenuOptions.OPT_NAME_LENGTH).hasSize(35);
            assertThat(name)
                    .as("every one of the ten literals is shorter than 35 visible characters")
                    .endsWith(" ");
            assertThat(name)
                    .as("the NEGATIVE assertion: a future 'helpful' trim inside the model fails here")
                    .isNotEqualTo(name.trim())
                    .isNotEqualTo(name.strip());
        }

        @Test
        @DisplayName("both consumer shapes are reproducible from the untrimmed value")
        void bothConsumerShapesAreReproducible() {
            // DELIMITED BY SIZE takes the field whole; DELIMITED BY SPACE stops at the first space. Both
            // are derivable from the untrimmed value, and only the first is derivable from a trimmed one.
            String name = MenuOptions.optionBySubscript(1).orElseThrow().menuOptName();

            // COMEN01C.cbl:245 - the whole 35 bytes.
            assertThat(name).isEqualTo(OPTION_1_NAME).hasSize(35);
            // COMEN01C.cbl:160-161 - up to the first space. For option 1 that is 'Account', which is what
            // produces the program's documented missing-space defect. The DEFECT ITSELF - the message
            // 'This option Accountis coming soon ...' - is the service's subject, not this file's; only the
            // field's own sliceability is asserted here.
            assertThat(name.substring(0, name.indexOf(' '))).isEqualTo("Account");
        }

        @ParameterizedTest
        @DisplayName("CDEMO-MENU-OPT-PGMNAME comes back untrimmed at its full 8 characters")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void theProgramNameComesBackUntrimmed(int subscript) {
            // All ten program-name literals fill PIC X(08) exactly, so - unlike the name column - there is
            // no trailing pad to compare against and an isNotEqualTo(trim()) assertion would be vacuous
            // here. What matters is that the declared width is fully occupied and the value is handed back
            // whole, because COMEN01C.cbl:146 slices its leading five bytes for the 'DUMMY' test.
            String programName = MenuOptions.optionBySubscript(subscript).orElseThrow().menuOptPgmName();

            assertThat(programName).hasSize(MenuOptions.OPT_PGMNAME_LENGTH).hasSize(8);
            assertThat(programName)
                    .as("COMEN02Y.cpy's PGMNAME literals contain no space at all")
                    .doesNotContain(" ")
                    .isEqualTo(programName.trim());
            assertThat(programName.substring(0, 5))
                    .as("COMEN01C.cbl:146 slices five bytes out of this column")
                    .hasSize(5);
        }

        @Test
        @DisplayName("the program-name column DOES pad, proven with a shorter synthetic value")
        void theProgramNameColumnDoesPad() {
            // The copybook's own ten literals happen to fill the column exactly, so the padding rule is
            // proven with a value this file invents rather than one it transcribes. A three-character
            // program name is stored right-space-padded to PIC X(08) and handed back that way.
            assertThat(MenuOption.of(1, toNameWidth("Synthetic"), "PGM", USER_TYPE_USER)
                    .menuOptPgmName())
                    .isEqualTo("PGM" + " ".repeat(5))
                    .hasSize(MenuOptions.OPT_PGMNAME_LENGTH);
        }

        @Test
        @DisplayName("the user-type column is handed back at its declared single byte")
        void theUserTypeColumnIsHandedBackWhole() {
            // COMEN02Y.cpy:92. One byte, fully occupied by 'U', so it is its own trim - asserted so the
            // contrast with the 35-byte name column is explicit rather than implied.
            String usrType = MenuOptions.optionBySubscript(1).orElseThrow().menuOptUsrType();

            assertThat(usrType).hasSize(1).isEqualTo(USER_TYPE_USER).isEqualTo(usrType.trim());
        }
    }

    @Nested
    @DisplayName("PIC 9(02) has BOTH an int value and a two-byte zero-filled image")
    class OptionNumberImage {

        @Test
        @DisplayName("option 1 renders '01', option 9 renders '09' and option 10 renders '10'")
        void optionTenRendersAsTwoCharacterImage() {
            // COMEN01C.cbl:243 does
            //     STRING CDEMO-MENU-OPT-NUM(WS-IDX) DELIMITED BY SIZE '. ' DELIMITED BY SIZE ...
            // which consumes the raw two-byte DISPLAY image directly, so option 1 renders '01. ' and
            // option 10 renders '10. ' on the screen. A model exposing only an int would lose the leading
            // zero and the composed line would be one byte short.
            assertThat(MenuOptions.optionBySubscript(1).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptNum()).isEqualTo(1);
                        assertThat(option.menuOptNumImage()).isEqualTo("01");
                    });
            assertThat(MenuOptions.optionBySubscript(9).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptNum()).isEqualTo(9);
                        assertThat(option.menuOptNumImage()).isEqualTo("09");
                    });
            // The case unique to this table: the sibling admin menu stops at 4, so only here does the
            // image cross the 9 -> 10 rollover, which is exactly where a naive formatter breaks.
            assertThat(MenuOptions.optionBySubscript(10).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptNum()).isEqualTo(10);
                        assertThat(option.menuOptNumImage()).isEqualTo("10");
                    });
        }

        @ParameterizedTest
        @DisplayName("the image is exactly two characters on every one of the ten populated slots")
        @CsvSource({"1, 01", "2, 02", "3, 03", "4, 04", "5, 05",
                    "6, 06", "7, 07", "8, 08", "9, 09", "10, 10"})
        void theImageIsAlwaysTwoCharacters(int subscript, String expectedImage) {
            // COMEN02Y.cpy:89 - PIC 9(02). Two bytes, always, zero-filled on the left.
            MenuOption option = MenuOptions.optionBySubscript(subscript).orElseThrow();

            assertThat(option.menuOptNumImage())
                    .isEqualTo(expectedImage)
                    .hasSize(MenuOptions.OPT_NUM_LENGTH);
            assertThat(option.menuOptNum()).isEqualTo(subscript);
            assertThat(Integer.parseInt(option.menuOptNumImage()))
                    .as("the two views of one PIC 9(02) field must agree")
                    .isEqualTo(option.menuOptNum());
        }

        @Test
        @DisplayName("the composed 'NN. ' + name line is derivable, across the 9 to 10 rollover")
        void theComposedOptionLineIsDerivable() {
            // COMEN01C.cbl:243-245 composes number + '. ' + name into WS-MENU-OPT-TXT PIC X(40). The
            // composition itself is the service's subject; what is asserted here is only that this model
            // supplies both parts in the shapes that STRING consumes - 2 + 2 + 35 = 39 bytes into a
            // 40-byte field.
            MenuOption ninth = MenuOptions.optionBySubscript(9).orElseThrow();
            MenuOption tenth = MenuOptions.optionBySubscript(10).orElseThrow();

            assertThat(ninth.menuOptNumImage() + ". " + ninth.menuOptName())
                    .startsWith("09. Transaction Reports")
                    .hasSize(39);
            assertThat(tenth.menuOptNumImage() + ". " + tenth.menuOptName())
                    .as("the rollover: two digits, still two bytes, so the line width does not change")
                    .startsWith("10. Bill Payment")
                    .hasSize(39);
        }

        @Test
        @DisplayName("the option number is an int - never a decimal type (rule R4, gate G22)")
        void theOptionNumberIsIntegral() throws Exception {
            // COMEN02Y.cpy:89 declares PIC 9(02) with no V and no S, so the field is SCALE-FREE. Rule R4
            // makes that an int: BigDecimal is for PIC 9...V... and COMP-3 fields, and double and float are
            // forbidden outright. This copybook declares no decimal PICTURE anywhere, so there is nothing
            // here for CobolDecimal to own - which is why this file consumes no such type.
            assertThat(MenuOption.class.getMethod("menuOptNum").getReturnType()).isEqualTo(int.class);

            assertThat(MenuOption.class.getRecordComponents())
                    .as("no component of the entry may be a floating-point or fixed-point decimal type")
                    .allSatisfy(component -> assertThat(component.getType())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class)
                            .isNotEqualTo(Double.class)
                            .isNotEqualTo(Float.class)
                            .isNotEqualTo(BigDecimal.class));
            assertThat(MenuOptions.class.getMethod("menuOptCount").getReturnType())
                    .isEqualTo(int.class);
        }
    }

    @Nested
    @DisplayName("Gate G33 - OCCURS is 1-based in COBOL and 0-based in Java")
    class OneBasedSubscripts {

        @Test
        @DisplayName("the FIRST element is subscript 1, and it is Account View / COACTVWC")
        void theFirstElementIsSubscriptOne() {
            // COMEN02Y.cpy:25-29 is the first quadruplet, and COMEN02Y.cpy:88's OCCURS makes it subscript
            // 1 - not 0. All four subfields are asserted, so a whole-entry shift cannot hide behind one
            // matching column.
            MenuOption first = MenuOptions.optionBySubscript(MenuOptions.FIRST_SUBSCRIPT).orElseThrow();

            assertThat(first.menuOptNum()).isEqualTo(1);
            assertThat(first.menuOptNumImage()).isEqualTo("01");
            assertThat(first.menuOptName()).isEqualTo(OPTION_1_NAME);
            assertThat(first.menuOptPgmName()).isEqualTo("COACTVWC");
            assertThat(first.menuOptUsrType()).isEqualTo(USER_TYPE_USER);
        }

        @Test
        @DisplayName("the LAST element is subscript 12, not 10 - and it is accepted and empty")
        void theLastElementIsSubscriptTwelve() {
            // OCCURS 12 TIMES at COMEN02Y.cpy:88 means the last VALID subscript is 12. It is emphatically
            // not 10: 10 is CDEMO-MENU-OPT-COUNT (COMEN02Y.cpy:21), the last POPULATED slot. Addressing 12
            // must succeed; what comes back is empty, because no VALUE clause reaches that far.
            assertThat(MenuOptions.TABLE_SIZE).isEqualTo(12);
            assertThatNoException()
                    .isThrownBy(() -> MenuOptions.optionBySubscript(MenuOptions.TABLE_SIZE));
            assertThat(MenuOptions.optionBySubscript(MenuOptions.TABLE_SIZE)).isEmpty();
            assertThat(MenuOptions.isSpecified(MenuOptions.TABLE_SIZE)).isFalse();
        }

        @Test
        @DisplayName("subscript 10 is the last POPULATED slot: Bill Payment / COBIL00C")
        void subscriptTenIsTheLastPopulatedSlot() {
            // COMEN02Y.cpy:80-84. This pins the populated / empty boundary from the populated side, so an
            // off-by-one at that boundary fails here rather than silently reporting slot 10 as empty.
            MenuOption tenth = MenuOptions.optionBySubscript(MenuOptions.ACTIVE_OPTION_COUNT).orElseThrow();

            assertThat(tenth.menuOptNum()).isEqualTo(10);
            assertThat(tenth.menuOptName()).isEqualTo(OPTION_10_NAME).hasSize(35);
            assertThat(tenth.menuOptPgmName()).isEqualTo("COBIL00C").hasSize(8);
            assertThat(tenth.menuOptUsrType()).isEqualTo(USER_TYPE_USER);
        }

        @Test
        @DisplayName("the 1-based to 0-based correspondence holds at both ends AND mid-table")
        void theCorrespondenceHoldsAtBothEndsAndMidTable() {
            List<Optional<MenuOption>> zeroBased = MenuOptions.options();

            assertThat(zeroBased).hasSize(MenuOptions.TABLE_SIZE).hasSize(12);
            // 1-based 1 <-> index 0.
            assertThat(MenuOptions.optionBySubscript(1)).isEqualTo(zeroBased.get(0));
            // 1-based 12 <-> index 11.
            assertThat(MenuOptions.optionBySubscript(12)).isEqualTo(zeroBased.get(11));
            // And mid-table, so an off-by-one cannot hide between the two ends: 1-based 10 <-> index 9,
            // the last populated slot.
            assertThat(MenuOptions.optionBySubscript(10)).isEqualTo(zeroBased.get(9));
        }

        @ParameterizedTest
        @DisplayName("every subscript 1 to 12 maps to list index subscript minus one")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        void everySubscriptMapsToItsIndex(int subscript) {
            // Walked exhaustively rather than sampled, because a single shifted element is the defect this
            // gate exists for and sampling three of twelve would not find it.
            assertThat(MenuOptions.optionBySubscript(subscript))
                    .isEqualTo(MenuOptions.options().get(subscript - MenuOptions.FIRST_SUBSCRIPT));
        }

        @Test
        @DisplayName("the element spans are derived through the shared one-based OCCURS primitive")
        void theElementSpansAreDerivedThroughTheSharedPrimitive() {
            // The conversion lives in FixedWidthRecord and is never written inline, so it cannot drift.
            // Subscript 1 lands on the table's own offset; subscript 12 lands 11 elements further on.
            assertThat(MenuOptions.entrySpan(1).offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET)
                    .isEqualTo(2);
            assertThat(MenuOptions.entrySpan(12).offset())
                    .isEqualTo(FixedWidthRecord.occursElementOffsetOneBased(MenuOptions.TABLE_OFFSET,
                            MenuOptions.ENTRY_LENGTH, MenuOptions.TABLE_SIZE, 12))
                    .isEqualTo(MenuOptions.TABLE_OFFSET + 11 * MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(508);
            assertThat(MenuOptions.entrySpan(12).endOffsetExclusive())
                    .as("the last element ends exactly at the end of the group")
                    .isEqualTo(MenuOptions.GROUP_LENGTH)
                    .isEqualTo(554);
            assertThat(MenuOptions.entrySpan(1).length())
                    .isEqualTo(MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(46);
            assertThat(MenuOptions.entrySpan(1).name()).isEqualTo(MenuOptions.CDEMO_MENU_OPT);
        }

        @ParameterizedTest
        @DisplayName("each entry's four subfield spans sit at their declared offsets within the element")
        @ValueSource(ints = {1, 6, 10, 11, 12})
        void eachEntrysSubfieldSpansSitAtTheirDeclaredOffsets(int subscript) {
            int base = MenuOptions.TABLE_OFFSET
                    + (subscript - MenuOptions.FIRST_SUBSCRIPT) * MenuOptions.ENTRY_LENGTH;
            List<FieldSpan> spans = MenuOptions.entryFieldSpans(subscript);

            assertThat(spans).hasSize(MenuOptions.SUBFIELDS_PER_ENTRY).hasSize(4);
            // COMEN02Y.cpy:89 - PIC 9(02), and a NUMERIC kind so it right-justifies and zero-pads.
            assertThat(spans.get(MenuOptions.NUM_SUBFIELD).offset()).isEqualTo(base);
            assertThat(spans.get(MenuOptions.NUM_SUBFIELD).length()).isEqualTo(2);
            assertThat(spans.get(MenuOptions.NUM_SUBFIELD).kind())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            // COMEN02Y.cpy:90 - PIC X(35).
            assertThat(spans.get(MenuOptions.NAME_SUBFIELD).offset()).isEqualTo(base + 2);
            assertThat(spans.get(MenuOptions.NAME_SUBFIELD).length()).isEqualTo(35);
            // COMEN02Y.cpy:91 - PIC X(08).
            assertThat(spans.get(MenuOptions.PGMNAME_SUBFIELD).offset()).isEqualTo(base + 37);
            assertThat(spans.get(MenuOptions.PGMNAME_SUBFIELD).length()).isEqualTo(8);
            // COMEN02Y.cpy:92 - PIC X(01), the column the sibling admin table does not declare.
            assertThat(spans.get(MenuOptions.USRTYPE_SUBFIELD).offset()).isEqualTo(base + 45);
            assertThat(spans.get(MenuOptions.USRTYPE_SUBFIELD).length()).isEqualTo(1);
            // All four are views over the OCCURS overlay, so all four are flagged as overlays.
            assertThat(spans).allSatisfy(span -> assertThat(span.redefinition()).isTrue());
        }

        @Test
        @DisplayName("subscripted names are spelled the way COBOL references them")
        void subscriptedNamesAreSpelledTheWayCobolReferencesThem() {
            // The parity differ keys on this exact form, so it is contract rather than cosmetics.
            assertThat(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 3))
                    .isEqualTo("CDEMO-MENU-OPT-NAME(3)");
            assertThat(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE, 12))
                    .isEqualTo("CDEMO-MENU-OPT-USRTYPE(12)");
            assertThatNullPointerException()
                    .isThrownBy(() -> MenuOptions.subscriptedName(null, 1));
        }
    }

    @Nested
    @DisplayName("Gate G33 - the accessor REJECTS an out-of-range subscript, and never clamps it")
    class SubscriptBoundsRejection {

        // -------------------------------------------------------------------------------------------
        // WHY REJECTION MATTERS HERE MORE THAN IN THE SIBLING TABLE - the reachability argument, verified
        // line by line in app/cbl/COMEN01C.cbl:
        //
        //   :46      05 WS-OPTION PIC 9(02) VALUE 0.        -> the reachable domain is exactly 0..99.
        //   :123     INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
        //                                                   -> blank input becomes '00', so subscript 0
        //                                                      is GENUINELY reachable, not hypothetical.
        //   :127-129 IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-MENU-OPT-COUNT OR WS-OPTION = ZEROS
        //                                                   -> sets the error flag and PERFORMs
        //                                                      SEND-MENU-SCREEN, but there is NO GO TO and
        //                                                      no early exit, so control FALLS THROUGH.
        //   :136-137 IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
        //                                                   -> subscripts the OCCURS 1..12 table with NO
        //                                                      IF NOT ERR-FLG-ON guard. Only the LATER
        //                                                      block at :145 is guarded.
        //
        // So an out-of-range subscript genuinely reaches the table in production. Contrast
        // app/cbl/COADM01C.cbl:137, which IS an IF NOT ERR-FLG-ON, so every admin subscript use is
        // guarded: this table's rejection is REACHABILITY-DRIVEN while the sibling's is defensive.
        //
        // Clamping to 1 or 12, silently returning slot 1 or slot 12, or handing back a null or blank entry
        // would each hide exactly the condition the consuming service has to handle. Bounding the access is
        // that service's decision - and its filter behaviour at :136-143 is its own tests' subject, not
        // this file's. All this file asserts is that the accessor rejects.
        // -------------------------------------------------------------------------------------------

        @Test
        @DisplayName("the LOWER boundary pair, adjacently: 0 is rejected and 1 is accepted")
        void theLowerBoundaryPairIsZeroRejectedAndOneAccepted() {
            // Asserted side by side so the off-by-one boundary is visible in one place. Subscript 0 is the
            // one COMEN01C.cbl:123's INSPECT manufactures out of blank input.
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(0))
                    .withMessageContaining("outside 1..12");
            assertThatNoException().isThrownBy(() -> MenuOptions.optionBySubscript(1));
            assertThat(MenuOptions.optionBySubscript(1)).isPresent();
        }

        @Test
        @DisplayName("the UPPER boundary pair, adjacently: 12 is accepted and 13 is rejected")
        void subscriptThirteenIsRejectedNotClamped() {
            // Also side by side. Twelve is inside OCCURS 12 TIMES (COMEN02Y.cpy:88) and must be accepted
            // even though it carries no value; thirteen is outside and must be refused rather than folded
            // back onto slot 12.
            assertThatNoException().isThrownBy(() -> MenuOptions.optionBySubscript(12));
            assertThat(MenuOptions.optionBySubscript(12)).isEmpty();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(13))
                    .withMessageContaining("outside 1..12");
        }

        @ParameterizedTest
        @DisplayName("every out-of-range subscript is rejected, never clamped and never blanked")
        @ValueSource(ints = {0, 13, 14, 99, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
        void everyOutOfRangeSubscriptIsRejected(int subscript) {
            // 99 is the TRUE upper reachable bound, because COMEN01C.cbl:46 declares WS-OPTION as
            // PIC 9(02): a subscript of 100 or more cannot arrive from production at all and is not the
            // point of this test. The negative and extreme values are included only to prove the guard is a
            // range check rather than a hard-coded list.
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("subscript " + subscript + " must be REFUSED")
                    .isThrownBy(() -> MenuOptions.optionBySubscript(subscript));
        }

        @ParameterizedTest
        @DisplayName("every subscript-taking accessor rejects consistently, so none is a back door")
        @ValueSource(ints = {0, 13, 99})
        void everySubscriptTakingAccessorRejectsConsistently(int subscript) {
            // A guard on one accessor and not another would let a caller reach the tail bytes by the wrong
            // door, so all five are asserted.
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.isSpecified(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.entrySpan(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.entryFieldSpans(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME,
                            subscript));
        }

        @Test
        @DisplayName("rejection is a throw, not a silent substitution of slot 1 or slot 12")
        void rejectionIsAThrowNotASilentSubstitution() {
            // Stated as its own assertion because "never clamped" is the actual requirement and a throw is
            // only its mechanism. If the accessor clamped, these calls would return a value; because it
            // rejects, no value is produced at all - which is what the assertion below captures.
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(0))
                    .withMessageContaining("COBOL subscripts are 1-based");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(13))
                    .withMessageContaining("COBOL subscripts are 1-based");
            // And for completeness, the in-range ends really do differ from one another, so a clamp to
            // either end would be observable rather than coincidentally correct.
            assertThat(MenuOptions.optionBySubscript(1))
                    .isNotEqualTo(MenuOptions.optionBySubscript(12));
        }
    }

    @Nested
    @DisplayName("Slots 11 and 12 - present as slots, absent as entries, and never fabricated")
    class UnspecifiedTailSlots {

        @ParameterizedTest
        @DisplayName("slots 11 and 12 are present but empty: accepted, addressable, and unvalued")
        @ValueSource(ints = {11, 12})
        void slotsElevenAndTwelveArePresentButEmpty(int subscript) {
            // The in-range-but-unvalued arm of the bounds check (gate G49). Rejecting these would be as
            // wrong as clamping: OCCURS 12 TIMES at COMEN02Y.cpy:88 makes them legal subscripts, and
            // COMEN01C.cbl:237-275 dispatches WHEN 11 and WHEN 12 explicitly.
            assertThatNoException().isThrownBy(() -> MenuOptions.optionBySubscript(subscript));
            assertThatNoException().isThrownBy(() -> MenuOptions.isSpecified(subscript));
            assertThatNoException().isThrownBy(() -> MenuOptions.entrySpan(subscript));

            assertThat(MenuOptions.isSpecified(subscript))
                    .as("COMEN02Y.cpy declares no VALUE for this slot")
                    .isFalse();
            assertThat(MenuOptions.optionBySubscript(subscript)).isEmpty();
        }

        @Test
        @DisplayName("the table stays TWELVE slots wide: the tail is absent, never trimmed away")
        void theTableStaysTwelveSlotsWide() {
            // Practice B5: dead storage is preserved, not tidied. Shrinking the table to ten would make
            // subscripts 11 and 12 unreachable and would contradict both COMEN02Y.cpy:88 and the twelve
            // display slots of app/bms/COMEN01.bms.
            List<Optional<MenuOption>> slots = MenuOptions.options();

            assertThat(slots).hasSize(12);
            assertThat(slots.subList(0, MenuOptions.ACTIVE_OPTION_COUNT))
                    .as("COMEN02Y.cpy:25-84 - the ten the copybook values")
                    .allSatisfy(slot -> assertThat(slot).isPresent());
            assertThat(slots.subList(MenuOptions.ACTIVE_OPTION_COUNT, MenuOptions.TABLE_SIZE))
                    .as("slots 11 and 12 - present as slots, absent as entries")
                    .hasSize(2)
                    .allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @Test
        @DisplayName("no accessor can be talked into reporting a value for them")
        void noAccessorReportsAValueForThem() {
            // The previous shape of this model handed back an entry claiming option number 0 with blank
            // text - a value indistinguishable from data and derived from nothing the copybook states.
            // Absence is the accurate answer, and orElseThrow is the proof a caller cannot read through it
            // by accident.
            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(11).orElseThrow());
            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(12).orElseThrow());
            assertThat(MenuOptions.activeOptions())
                    .as("activeOptions() never contains a fabricated tail entry")
                    .hasSize(MenuOptions.ACTIVE_OPTION_COUNT)
                    .extracting(MenuOption::menuOptNum)
                    .doesNotContain(0);
        }

        @Test
        @DisplayName("the tail's spans ARE addressable, at their declared widths of 35, 8 and 1")
        void theTailSpansAreAddressableAtTheirDeclaredWidths() {
            // The slots carry no value, but their BYTES are declared and emitted, so a caller inspecting an
            // out-of-range option subscript reads real bytes rather than trusting a synthesized entry. The
            // content below is this module's own reproducible pad - zeros for the PIC 9(02) column
            // (COMEN02Y.cpy:89) and spaces for the three PIC X columns (:90-:92) - and NOT a claim about
            // what the legacy program's storage holds.
            Map<String, String> images = MenuOptions.fieldImages(MenuOptions.encode(ASCII), ASCII);

            for (int subscript : new int[] {11, 12}) {
                assertThat(images.get(
                        MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NUM, subscript)))
                        .as("slot " + subscript + " CDEMO-MENU-OPT-NUM, PIC 9(02) - two bytes")
                        .hasSize(MenuOptions.OPT_NUM_LENGTH)
                        .isEqualTo("00");
                assertThat(images.get(
                        MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, subscript)))
                        .as("slot " + subscript + " CDEMO-MENU-OPT-NAME, PIC X(35) - space-filled")
                        .hasSize(MenuOptions.OPT_NAME_LENGTH)
                        .isEqualTo(" ".repeat(35));
                assertThat(images.get(
                        MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_PGMNAME, subscript)))
                        .as("slot " + subscript + " CDEMO-MENU-OPT-PGMNAME, PIC X(08) - space-filled")
                        .hasSize(MenuOptions.OPT_PGMNAME_LENGTH)
                        .isEqualTo(" ".repeat(8));
                assertThat(images.get(
                        MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE, subscript)))
                        .as("slot " + subscript + " CDEMO-MENU-OPT-USRTYPE, PIC X(01) - space-filled")
                        .hasSize(MenuOptions.OPT_USRTYPE_LENGTH)
                        .isEqualTo(" ");
            }
        }

        @Test
        @DisplayName("the unspecified tail descriptor names the 92 bytes the overlay adds")
        void theUnspecifiedTailDescriptorNamesTheNinetyTwoBytes() {
            // 552 bytes of CDEMO-MENU-OPTIONS (COMEN02Y.cpy:87-88) over 460 bytes of
            // CDEMO-MENU-OPTIONS-DATA (COMEN02Y.cpy:23-84) leaves 92 bytes with no declaring storage. The
            // descriptor's PICTURE kind is deliberately FILLER and it carries no initial value: it is a
            // region whose meaning is not this copybook's to give.
            FieldSpan tail = MenuOptions.unspecifiedTailSpan();

            assertThat(tail.name())
                    .isEqualTo(MenuOptions.CDEMO_MENU_OPTIONS + MenuOptions.UNSPECIFIED_TAIL_SUFFIX);
            assertThat(tail.offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(462);
            assertThat(tail.length())
                    .isEqualTo(MenuOptions.TABLE_LENGTH - MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo((MenuOptions.TABLE_SIZE - MenuOptions.ACTIVE_OPTION_COUNT)
                            * MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(92);
            assertThat(tail.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(tail.redefinition()).isTrue();
            assertThat(tail.hasInitialValue())
                    .as("the copybook declares no VALUE for these bytes, so the descriptor carries none")
                    .isFalse();
            assertThat(tail.endOffsetExclusive()).isEqualTo(MenuOptions.GROUP_LENGTH).isEqualTo(554);
        }

        @Test
        @DisplayName("decode reports the tail as absent even when its bytes WOULD parse")
        void decodeReportsTheTailAsAbsentEvenWhenParseable() {
            // This is the assertion that keeps the fabrication out. This module's own image pads the tail
            // with "00" and spaces, which would parse perfectly well as an entry - and parsing it would put
            // the invented value straight back. Nothing decoded there can be attributed to the copybook.
            byte[] image = MenuOptions.encode(ASCII);

            assertThat(new String(image, ASCII)
                    .substring(MenuOptions.unspecifiedTailSpan().offset(),
                            MenuOptions.unspecifiedTailSpan().offset() + MenuOptions.OPT_NUM_LENGTH))
                    .as("the tail's first two bytes really are parseable digits")
                    .isEqualTo("00");

            List<Optional<MenuOption>> decoded = MenuOptions.decode(image, ASCII);
            assertThat(decoded.get(10)).isEmpty();
            assertThat(decoded.get(11)).isEmpty();
        }

        @Test
        @DisplayName("decode survives a tail of GARBAGE, and the ten valued entries are unharmed")
        void decodeSurvivesAGarbageTail() {
            // Real storage may hold anything in those 92 bytes, including bytes that are not digits.
            // Decoding them as an entry would push them through decodePic9AsInt and fail the decode of an
            // otherwise perfectly valid group image - taking the ten valued entries down with it.
            byte[] image = MenuOptions.encode(ASCII);
            for (int offset = MenuOptions.unspecifiedTailSpan().offset(); offset < image.length; offset++) {
                image[offset] = (byte) '?';
            }

            List<Optional<MenuOption>> decoded = MenuOptions.decode(image, ASCII);

            assertThat(decoded).hasSize(MenuOptions.TABLE_SIZE);
            assertThat(decoded.subList(0, MenuOptions.ACTIVE_OPTION_COUNT))
                    .isEqualTo(MenuOptions.options().subList(0, MenuOptions.ACTIVE_OPTION_COUNT));
            assertThat(decoded.get(10)).isEmpty();
            assertThat(decoded.get(11)).isEmpty();
        }

        @Test
        @DisplayName("the garbage tail is still readable as BYTES, which is where it belongs")
        void theGarbageTailIsStillReadableAsBytes() {
            byte[] image = MenuOptions.encode(ASCII);
            for (int offset = MenuOptions.unspecifiedTailSpan().offset(); offset < image.length; offset++) {
                image[offset] = (byte) '?';
            }

            Map<String, String> images = MenuOptions.fieldImages(image, ASCII);

            assertThat(images).hasSize(1 + MenuOptions.TABLE_SIZE * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .hasSize(49);
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 11)))
                    .isEqualTo("?".repeat(MenuOptions.OPT_NAME_LENGTH));
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NUM, 12)))
                    .isEqualTo("??");
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 1)))
                    .as("a garbage tail cannot disturb a valued entry")
                    .isEqualTo(OPTION_1_NAME);
        }

        @Test
        @DisplayName("garbage in a SPECIFIED slot is still an error, because those bytes ARE claimed")
        void garbageInASpecifiedSlotIsStillAnError() {
            // The tolerance is confined to the tail. Bytes the copybook DOES value (COMEN02Y.cpy:25-84) are
            // claimed, so a non-digit in option 1's PIC 9(02) number is a genuine data or offset defect and
            // must be reported rather than absorbed.
            byte[] image = MenuOptions.encode(ASCII);
            image[MenuOptions.TABLE_OFFSET] = (byte) '?';

            assertThatIllegalArgumentException().isThrownBy(() -> MenuOptions.decode(image, ASCII));
        }
    }

    @Nested
    @DisplayName("Gate G21 - the total declared width, and the self-check that enforces it")
    class TotalWidthSelfCheck {

        @Test
        @DisplayName("the layout declares 554 bytes as 49 contiguous storage spans from offset 0")
        void theLayoutDeclaresEveryByteExactlyOnce() {
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;
            List<FieldSpan> storage = layout.storageSpans();

            assertThat(layout.recordLength()).isEqualTo(MenuOptions.GROUP_LENGTH).isEqualTo(554);
            assertThat(storage)
                    .as("the count span plus 12 slots x 4 subfields")
                    .hasSize(1 + MenuOptions.TABLE_SIZE * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .hasSize(49);
            assertThat(storage.stream().mapToInt(FieldSpan::length).sum())
                    .as("the storage spans sum to exactly the declared record length")
                    .isEqualTo(MenuOptions.GROUP_LENGTH);

            // Contiguity, walked explicitly: a gap or an overlap anywhere would move every later offset.
            int cursor = 0;
            for (FieldSpan span : storage) {
                assertThat(span.offset())
                        .as("no gap and no overlap before " + span.describe())
                        .isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(MenuOptions.GROUP_LENGTH).isEqualTo(554);
        }

        @Test
        @DisplayName("the FILLER spans of COMEN02Y.cpy:25-84 are EMITTED, which is what makes 460 reachable")
        void theFillerSpansAreEmittedRatherThanSkipped() {
            // The populated area is declared ENTIRELY from FILLER items - forty of them, four per slot
            // across COMEN02Y.cpy:25-84. FILLER is non-referable in COBOL, and the temptation is therefore
            // to skip it; skipping even one byte of it makes the 460-byte total unreachable and shifts every
            // subsequent offset. So the valued spans are counted and their bytes are totalled here.
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;
            List<FieldSpan> valued = layout.storageSpans().stream()
                    .filter(FieldSpan::hasInitialValue)
                    .toList();

            assertThat(valued)
                    .as("the count field plus 10 valued slots x 4 subfields")
                    .hasSize(1 + MenuOptions.ACTIVE_OPTION_COUNT * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .hasSize(41);
            assertThat(valued.stream().mapToInt(FieldSpan::length).sum())
                    .as("2 bytes of count (COMEN02Y.cpy:21) plus the 460 of COMEN02Y.cpy:25-84")
                    .isEqualTo(MenuOptions.MENU_OPT_COUNT_LENGTH + MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(462);
            // Which is exactly the 460 the populated area contributes, once the count is excluded - the
            // very distinction the "460, not 462" assertion in ConstantsAndWidths turns on.
            assertThat(valued.stream().mapToInt(FieldSpan::length).sum()
                    - MenuOptions.MENU_OPT_COUNT_LENGTH)
                    .isEqualTo(MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(460);
            // The eight subfields of slots 11 and 12 carry no literal, because the copybook gives them none.
            assertThat(MenuOptions.GROUP_LAYOUT.storageSpans().stream()
                    .filter(span -> !span.hasInitialValue())
                    .count())
                    .isEqualTo(2L * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .isEqualTo(8L);
        }

        @Test
        @DisplayName("FILLER may repeat, which is the only reason a 48-FILLER layout can exist at all")
        void fillerMayRepeatWhereAReferableNameMayNot() {
            // Every one of the forty-eight option sub-spans is named FILLER, because that is what
            // COMEN02Y.cpy:25-84 declares them as. The layout's duplicate-name rule exempts FILLER
            // precisely because FILLER is not a referable COBOL name; a referable name repeated would be
            // rejected. That also means layout.span(...) cannot address them, which is why the OCCURS
            // overlay exists - it is what gives those same bytes referable, subscripted names.
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;

            assertThat(layout.storageSpans().stream().filter(span -> "FILLER".equals(span.name())).count())
                    .isEqualTo((long) MenuOptions.TABLE_SIZE * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .isEqualTo(48L);
            assertThat(layout.hasSpan(MenuOptions.CDEMO_MENU_OPT_COUNT)).isTrue();
            assertThat(layout.hasSpan(MenuOptions.CDEMO_MENU_OPTIONS_DATA)).isTrue();
            assertThat(layout.hasSpan(MenuOptions.CDEMO_MENU_OPTIONS)).isTrue();
            assertThat(layout.hasSpan(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 1)))
                    .as("the storage spans are FILLER-named, so a subscripted name is not among them")
                    .isFalse();
        }

        @Test
        @DisplayName("the self-check PASSES for the correct descriptor set, storage only and complete")
        void theSelfCheckPassesForTheCorrectDescriptorSet() {
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;

            assertThatNoException().isThrownBy(
                    () -> new RecordLayout(MenuOptions.GROUP_LENGTH, layout.storageSpans()));
            assertThatNoException().isThrownBy(
                    () -> new RecordLayout(MenuOptions.GROUP_LENGTH, layout.spans()));
            assertThat(layout.spans())
                    .as("49 storage spans plus the two REDEFINES overlays")
                    .hasSize(49 + 2)
                    .hasSize(51);
        }

        @Test
        @DisplayName("the self-check FAILS when the TRAILING one-byte USRTYPE span is omitted")
        void theSelfCheckFailsWhenTheTrailingUsrtypeSpanIsOmitted() {
            // This is what gives G21 its teeth: proving the check would have caught a dropped span, not
            // merely that the correct set passes. The last storage span is slot 12's
            // CDEMO-MENU-OPT-USRTYPE (COMEN02Y.cpy:92) - a single byte at offset 553. Drop it and the layout
            // declares 553 bytes against a record length of 554 and refuses to exist.
            List<FieldSpan> spans = mutableStorageSpans();
            FieldSpan dropped = spans.remove(spans.size() - 1);

            assertThat(dropped.length())
                    .as("the trailing span is the one-byte user-type column of slot 12")
                    .isEqualTo(MenuOptions.OPT_USRTYPE_LENGTH);
            assertThat(dropped.offset()).isEqualTo(553);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH, spans))
                    .withMessageContaining("1 byte(s) short");
        }

        @Test
        @DisplayName("the self-check FAILS when an INTERIOR USRTYPE span is omitted, shifting later offsets")
        void theSelfCheckFailsWhenAnInteriorUsrtypeSpanIsOmitted() {
            // The sharper case, and the one the brief singles out: omitting the one-byte USRTYPE column of
            // an interior slot shifts EVERY later offset by one. Slot 1's USRTYPE (COMEN02Y.cpy:29) sits at
            // offset 47, immediately before slot 2's CDEMO-MENU-OPT-NUM at 48, so dropping it leaves a
            // one-byte hole that the check reports against the span that would have moved.
            List<FieldSpan> spans = mutableStorageSpans();
            FieldSpan dropped = spans.remove(1 + MenuOptions.USRTYPE_SUBFIELD);

            assertThat(dropped.offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.OPT_USRTYPE_OFFSET)
                    .isEqualTo(47);
            assertThat(dropped.length()).isEqualTo(MenuOptions.OPT_USRTYPE_LENGTH).isEqualTo(1);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH, spans))
                    .withMessageContaining("Layout gap of 1 byte(s) before")
                    .withMessageContaining("offset 48")
                    .withMessageContaining("the preceding spans end at byte 47");
        }

        @Test
        @DisplayName("the self-check FAILS when a PIC X(35) span is mis-sized, and names the shortfall")
        void theSelfCheckFailsWhenASpanIsMisSized() {
            // A width transcribed as 34 instead of the PIC X(35) of COMEN02Y.cpy:90 leaves a one-byte hole,
            // reported against the CDEMO-MENU-OPT-PGMNAME span at offset 39 that would have moved. The
            // literal is dropped along with the byte, because FieldSpan itself refuses a 35-character VALUE
            // in a 34-byte span - a second, independent guard on the same transcription error.
            List<FieldSpan> spans = mutableStorageSpans();
            int nameIndex = 1 + MenuOptions.NAME_SUBFIELD;
            FieldSpan original = spans.get(nameIndex);

            assertThat(original.length()).isEqualTo(MenuOptions.OPT_NAME_LENGTH).isEqualTo(35);
            assertThatIllegalArgumentException()
                    .as("FieldSpan alone rejects a 35-character literal in a 34-byte span")
                    .isThrownBy(() -> new FieldSpan(original.name(), original.offset(),
                            original.length() - 1, original.kind(), original.initialValue(), false))
                    .withMessageContaining("VALUE literal of 35 character(s)");

            spans.set(nameIndex, new FieldSpan(original.name(), original.offset(),
                    original.length() - 1, original.kind(), null, false));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH, spans))
                    .withMessageContaining("Layout gap of 1 byte(s) before")
                    .withMessageContaining("offset 39");
        }

        @Test
        @DisplayName("the self-check FAILS when the record length is overstated")
        void theSelfCheckFailsWhenTheRecordLengthIsOverstated() {
            // The other side of the same total: a layout whose spans are right but whose declared length is
            // wrong is refused too, so neither half of the pair can drift without notice.
            List<FieldSpan> spans = mutableStorageSpans();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH + 1, spans))
                    .withMessageContaining("1 byte(s) short");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH - 1, spans))
                    .withMessageContaining("1 byte(s) too long");
        }

        @Test
        @DisplayName("every named field of the group is readable, keyed by its COBOL reference form")
        void everyNamedFieldOfTheGroupIsReadable() {
            Map<String, String> images = MenuOptions.fieldImages(MenuOptions.encode(ASCII), ASCII);

            assertThat(images)
                    .as("the count plus 12 slots x 4 subfields")
                    .hasSize(1 + MenuOptions.TABLE_SIZE * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .hasSize(49);
            assertThat(MenuOptions.fieldSpans()).hasSameSizeAs(images.keySet());
            // COMEN02Y.cpy:21 - VALUE 10.
            assertThat(images.get(MenuOptions.CDEMO_MENU_OPT_COUNT)).isEqualTo("10");
            // COMEN02Y.cpy:27 and :83 - the first name and the last program name.
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 1)))
                    .isEqualTo(OPTION_1_NAME);
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_PGMNAME, 10)))
                    .isEqualTo("COBIL00C");
            // Copybook order, which a differ that walks this map depends on.
            assertThat(images.keySet()).first().isEqualTo(MenuOptions.CDEMO_MENU_OPT_COUNT);
            assertThat(images.keySet()).last()
                    .isEqualTo(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE,
                            MenuOptions.TABLE_SIZE));
        }
    }

    @Nested
    @DisplayName("Gate G34 - REDEFINES is two typed views over ONE backing span")
    class RedefinesViews {

        @Test
        @DisplayName("both views begin at the same offset, so they address one backing span")
        void bothViewsBeginAtTheSameOffset() {
            // COMEN02Y.cpy:87 - 05 CDEMO-MENU-OPTIONS REDEFINES CDEMO-MENU-OPTIONS-DATA. A REDEFINES item
            // occupies the SAME storage as the item it redefines, so a shared offset is not a coincidence
            // here; it is the definition.
            FieldSpan dataView = MenuOptions.populatedDataSpan();
            FieldSpan tableView = MenuOptions.tableSpan();

            assertThat(dataView.name()).isEqualTo(MenuOptions.CDEMO_MENU_OPTIONS_DATA);
            assertThat(tableView.name()).isEqualTo(MenuOptions.CDEMO_MENU_OPTIONS);
            assertThat(dataView.offset()).isEqualTo(tableView.offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET)
                    .isEqualTo(2);
            assertThat(dataView.redefinition()).isTrue();
            assertThat(tableView.redefinition()).isTrue();
        }

        @Test
        @DisplayName("the redefining view is declared over a SHORTER area: 552 over 460")
        void theRedefiningViewIsDeclaredOverAShorterArea() {
            // The copybook's documented asymmetry, and the reason POPULATED_DATA_LENGTH and TABLE_LENGTH are
            // two constants rather than one. COMEN02Y.cpy:23-84 spells out only ten entries, 460 bytes;
            // COMEN02Y.cpy:87-88 lays a twelve-slot, 552-byte overlay over them. Ninety-two bytes of that
            // overlay have no declaring storage beneath them at all - which is exactly why slots 11 and 12
            // exist and are empty. Recorded, not "fixed" (practice B4).
            assertThat(MenuOptions.POPULATED_DATA_LENGTH).isEqualTo(460);
            assertThat(MenuOptions.TABLE_LENGTH).isEqualTo(552);
            assertThat(MenuOptions.TABLE_LENGTH).isGreaterThan(MenuOptions.POPULATED_DATA_LENGTH);
            assertThat(MenuOptions.TABLE_LENGTH - MenuOptions.POPULATED_DATA_LENGTH).isEqualTo(92);
            assertThat(MenuOptions.populatedDataSpan().length())
                    .isEqualTo(MenuOptions.POPULATED_DATA_LENGTH);
            assertThat(MenuOptions.tableSpan().length()).isEqualTo(MenuOptions.TABLE_LENGTH);
        }

        @Test
        @DisplayName("the REDEFINES adds ZERO bytes to the group - it is an overlay, not an append")
        void theRedefinesAddsZeroBytesToTheGroup() {
            // If an overlay contributed to the total, the group would measure 554 + 460 + 552. It measures
            // 554: the count field plus the twelve-slot area, once. The two overlays together span 1012
            // bytes of alternative VIEWS over storage that the 49 storage spans already account for.
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;

            assertThat(layout.redefinitions()).hasSize(2);
            assertThat(layout.redefinitions().stream().mapToInt(FieldSpan::length).sum())
                    .isEqualTo(MenuOptions.POPULATED_DATA_LENGTH + MenuOptions.TABLE_LENGTH)
                    .isEqualTo(1012);
            assertThat(layout.storageSpans().stream().mapToInt(FieldSpan::length).sum())
                    .as("the overlays contribute nothing: storage alone is the record length")
                    .isEqualTo(layout.recordLength())
                    .isEqualTo(MenuOptions.GROUP_LENGTH);
            // And every overlay ends inside storage declared ahead of it, which is the other half of what
            // makes it an overlay rather than an extension.
            assertThat(layout.redefinitions())
                    .allSatisfy(overlay -> assertThat(overlay.endOffsetExclusive())
                            .isLessThanOrEqualTo(MenuOptions.GROUP_LENGTH));
        }

        @Test
        @DisplayName("reading through the table view returns exactly the bytes the -DATA view holds")
        void readingThroughTheTableViewReturnsTheDataViewBytes() {
            // Direction one of the round trip. encode() writes through the TABLE-view spans; the two view
            // descriptors are then read back off the same image, and the 552-byte table view must begin with
            // the 460 bytes the literal-storage view holds. Anything else would mean two spans rather than
            // two views of one.
            FixedWidthRecord area = FixedWidthRecord.copyOf(MenuOptions.encode(ASCII),
                    MenuOptions.GROUP_LENGTH, ASCII);

            String dataView = area.readSpan(MenuOptions.populatedDataSpan());
            String tableView = area.readSpan(MenuOptions.tableSpan());

            assertThat(dataView).hasSize(MenuOptions.POPULATED_DATA_LENGTH);
            assertThat(tableView).hasSize(MenuOptions.TABLE_LENGTH);
            assertThat(tableView)
                    .as("one backing span: the wider view starts with the narrower one's bytes")
                    .startsWith(dataView);

            // And those 460 bytes are precisely the ten entries laid end to end, in copybook field order.
            StringBuilder expected = new StringBuilder();
            for (MenuOption option : MenuOptions.activeOptions()) {
                expected.append(option.menuOptNumImage())
                        .append(option.menuOptName())
                        .append(option.menuOptPgmName())
                        .append(option.menuOptUsrType());
            }
            assertThat(dataView).isEqualTo(expected.toString());
        }

        @Test
        @DisplayName("writing through one view and reading through the other round-trips both ways")
        void writingThroughOneViewAndReadingThroughTheOtherRoundTrips() {
            // Direction two. declaredImage() populates the area from the copybook's own VALUE clauses on the
            // FILLER storage spans - a genuinely independent path from encode(), which writes the
            // already-formed entry images through the OCCURS overlay's spans. Reading the literal-path image
            // back THROUGH the table view must yield the entries, and the two images must be identical byte
            // for byte.
            byte[] literalPath = MenuOptions.declaredImage(ASCII);
            byte[] tableViewPath = MenuOptions.encode(ASCII);

            assertThat(literalPath).isEqualTo(tableViewPath).hasSize(MenuOptions.GROUP_LENGTH);
            assertThat(MenuOptions.decode(literalPath, ASCII)).isEqualTo(MenuOptions.options());
            assertThat(MenuOptions.decode(tableViewPath, ASCII)).isEqualTo(MenuOptions.options());

            // Re-encoding what was decoded returns the same bytes, closing the loop in the other direction.
            assertThat(MenuOptions.encode(ASCII)).isEqualTo(tableViewPath);
        }

        @Test
        @DisplayName("a byte written through the table view is visible through the -DATA view")
        void aByteWrittenThroughTheTableViewIsVisibleThroughTheDataView() {
            // The most direct statement of "one backing span" available: write through a subscripted
            // table-view span and read the change out of the literal-storage view, then do the reverse. The
            // record is a fresh local area, so nothing here mutates shared state (practice B9).
            FixedWidthCodec codec = asciiCodec();
            FixedWidthRecord area = codec.wrap(MenuOptions.encode(ASCII), MenuOptions.GROUP_LAYOUT);
            FieldSpan slotThreeName = MenuOptions.entryFieldSpans(3).get(MenuOptions.NAME_SUBFIELD);

            codec.writePicX(area, slotThreeName, toNameWidth("Overwritten"));

            // Slot 3's name begins at offset 2 + 2 x 46 + 2 = 96, which is byte 94 of the 460-byte -DATA
            // view - so the change must appear there and nowhere else.
            int offsetWithinDataView = slotThreeName.offset() - MenuOptions.TABLE_OFFSET;
            assertThat(offsetWithinDataView).isEqualTo(2 * MenuOptions.ENTRY_LENGTH
                    + MenuOptions.OPT_NAME_OFFSET);
            assertThat(area.readSpan(MenuOptions.populatedDataSpan())
                    .substring(offsetWithinDataView, offsetWithinDataView + MenuOptions.OPT_NAME_LENGTH))
                    .isEqualTo(toNameWidth("Overwritten"));

            // Now the reverse: restore it by writing through the -DATA view's byte range and read it back
            // through the table view.
            area.writeString(slotThreeName.offset(), MenuOptions.OPT_NAME_LENGTH, OPTION_3_NAME);
            assertThat(codec.readPicX(area, slotThreeName)).isEqualTo(OPTION_3_NAME);
            assertThat(MenuOptions.decode(area.toByteArray(), ASCII))
                    .as("and the whole table is back to its declared state")
                    .isEqualTo(MenuOptions.options());
        }

        @ParameterizedTest
        @DisplayName("slots 11 and 12 are addressable through the table view the -DATA view never reached")
        @ValueSource(ints = {11, 12})
        void theTailSlotsAreAddressableThroughTheTableView(int subscript) {
            // COMEN02Y.cpy:23-84 never populated these bytes, yet COMEN02Y.cpy:87-88's overlay names them,
            // and every one of the four subfields is declared at its full width - 2, 35, 8 and 1.
            FieldSpan element = MenuOptions.entrySpan(subscript);
            List<FieldSpan> spans = MenuOptions.entryFieldSpans(subscript);

            assertThat(element.offset())
                    .as("the tail lies entirely past the 460-byte literal-storage view")
                    .isGreaterThanOrEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.POPULATED_DATA_LENGTH);
            assertThat(element.length()).isEqualTo(MenuOptions.ENTRY_LENGTH);
            assertThat(spans).extracting(FieldSpan::length)
                    .containsExactly(MenuOptions.OPT_NUM_LENGTH, MenuOptions.OPT_NAME_LENGTH,
                            MenuOptions.OPT_PGMNAME_LENGTH, MenuOptions.OPT_USRTYPE_LENGTH)
                    .containsExactly(2, 35, 8, 1);
            assertThat(spans).allSatisfy(span -> assertThat(span.hasInitialValue())
                    .as(span.describe() + " carries no copybook VALUE")
                    .isFalse());
        }
    }

    @Nested
    @DisplayName("The byte image - offsets, the caller's code page, and what is not claimed")
    class ByteImage {

        @Test
        @DisplayName("encode() and declaredImage() agree, reached by two independent paths")
        void encodeAndDeclaredImageAgree() {
            // encode() writes the already-formed entry images through the OCCURS overlay; declaredImage()
            // applies the copybook's VALUE clauses to the FILLER storage spans. The two differ in more than
            // plumbing - the literal path stores VALUE 1 right-justified into PIC 9(02) while encode() writes
            // the formed image "01" - so agreement is a real cross-check that the transcribed entries and the
            // transcribed layout say the same thing.
            assertThat(MenuOptions.encode(ASCII))
                    .isEqualTo(MenuOptions.declaredImage(ASCII))
                    .hasSize(MenuOptions.GROUP_LENGTH)
                    .hasSize(554);
        }

        @Test
        @DisplayName("CDEMO-MENU-OPT-COUNT leads the image with '10' at offset 0")
        void theCountLeadsTheImage() {
            // COMEN02Y.cpy:21, and the reason MENU_OPT_COUNT_OFFSET is 0 while TABLE_OFFSET is 2.
            String image = new String(MenuOptions.encode(ASCII), ASCII);

            assertThat(image).hasSize(554).startsWith("10");
            assertThat(image.substring(MenuOptions.MENU_OPT_COUNT_OFFSET,
                    MenuOptions.MENU_OPT_COUNT_OFFSET + MenuOptions.MENU_OPT_COUNT_LENGTH))
                    .isEqualTo(MenuOptions.menuOptCountImage())
                    .isEqualTo("10");
            assertThat(image.substring(MenuOptions.TABLE_OFFSET,
                    MenuOptions.TABLE_OFFSET + MenuOptions.OPT_NUM_LENGTH))
                    .as("and the option area begins immediately after it, with slot 1's number")
                    .isEqualTo("01");
        }

        @Test
        @DisplayName("the ten valued entries land at their declared offsets, field by field")
        void theValuedEntriesLandAtTheirDeclaredOffsets() {
            String image = new String(MenuOptions.encode(ASCII), ASCII);

            for (int subscript = 1; subscript <= MenuOptions.ACTIVE_OPTION_COUNT; subscript++) {
                int base = MenuOptions.TABLE_OFFSET
                        + (subscript - MenuOptions.FIRST_SUBSCRIPT) * MenuOptions.ENTRY_LENGTH;
                MenuOption option = MenuOptions.optionBySubscript(subscript).orElseThrow();

                // COMEN02Y.cpy:89-92, in declaration order, at offsets 0, 2, 37 and 45 within the entry.
                assertThat(image.substring(base + MenuOptions.OPT_NUM_OFFSET,
                        base + MenuOptions.OPT_NUM_OFFSET + MenuOptions.OPT_NUM_LENGTH))
                        .isEqualTo(option.menuOptNumImage());
                assertThat(image.substring(base + MenuOptions.OPT_NAME_OFFSET,
                        base + MenuOptions.OPT_NAME_OFFSET + MenuOptions.OPT_NAME_LENGTH))
                        .isEqualTo(option.menuOptName());
                assertThat(image.substring(base + MenuOptions.OPT_PGMNAME_OFFSET,
                        base + MenuOptions.OPT_PGMNAME_OFFSET + MenuOptions.OPT_PGMNAME_LENGTH))
                        .isEqualTo(option.menuOptPgmName());
                assertThat(image.substring(base + MenuOptions.OPT_USRTYPE_OFFSET,
                        base + MenuOptions.OPT_USRTYPE_OFFSET + MenuOptions.OPT_USRTYPE_LENGTH))
                        .isEqualTo(option.menuOptUsrType());
            }
        }

        @Test
        @DisplayName("the code page is the CALLER'S: the same table encodes differently under IBM037")
        void theCodePageIsTheCallers() {
            // Practice B8. Both accessors take the Charset as an explicit parameter precisely so no platform
            // default can leak in, and the two code pages must therefore produce different bytes for the
            // same table while both measuring the declared width.
            byte[] ascii = MenuOptions.encode(ASCII);
            byte[] ebcdic = MenuOptions.encode(EBCDIC);

            assertThat(ebcdic).hasSize(MenuOptions.GROUP_LENGTH).isNotEqualTo(ascii);
            assertThat(MenuOptions.decode(ebcdic, EBCDIC))
                    .as("and each round-trips within its own code page")
                    .isEqualTo(MenuOptions.options());
            assertThat(MenuOptions.declaredImage(EBCDIC)).isEqualTo(ebcdic);
        }

        @Test
        @DisplayName("every byte accessor refuses a null code page rather than choosing one")
        void everyByteAccessorRefusesANullCodePage() {
            // Choosing a default here is the exact failure practice B8 exists to prevent, so the absence of a
            // charset is an error rather than an invitation.
            assertThatNullPointerException().isThrownBy(() -> MenuOptions.encode(null));
            assertThatNullPointerException().isThrownBy(() -> MenuOptions.declaredImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> MenuOptions.decode(MenuOptions.encode(ASCII), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> MenuOptions.fieldImages(MenuOptions.encode(ASCII), null));
        }

        @Test
        @DisplayName("decode rejects an image that is not exactly the declared width")
        void decodeRejectsAnImageOfTheWrongWidth() {
            // A record either measures its declared width or is not that record. Both directions are checked,
            // because a too-long image is as much a defect as a truncated one.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOptions.decode(new byte[MenuOptions.GROUP_LENGTH - 1], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOptions.decode(new byte[MenuOptions.GROUP_LENGTH + 1], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOptions.fieldImages(new byte[0], ASCII));
        }

        @Test
        @DisplayName("decode of this module's own image returns ten entries and two empties")
        void decodeOfOwnImageReturnsTenEntriesAndTwoEmpties() {
            List<Optional<MenuOption>> decoded = MenuOptions.decode(MenuOptions.encode(ASCII), ASCII);

            assertThat(decoded).hasSize(MenuOptions.TABLE_SIZE).isEqualTo(MenuOptions.options());
            assertThat(decoded.stream().filter(Optional::isPresent).count())
                    .isEqualTo(MenuOptions.ACTIVE_OPTION_COUNT);
        }
    }

    @Nested
    @DisplayName("MenuOption - the entry type, and the PICTURE rules it enforces")
    class EntryType {

        @Test
        @DisplayName("it pads a short literal to its declared width and refuses an over-long one")
        void itPadsShortAndRefusesLong() {
            // COMEN02Y.cpy:90 - PIC X(35). COBOL stores a VALUE literal shorter than its picture
            // right-space-padded; a literal LONGER than its picture is a transcription error the compiler
            // itself would reject, so it is refused here rather than truncated silently.
            MenuOption option = MenuOption.of(3, "Short", "PGM12345", USER_TYPE_USER);

            assertThat(option.menuOptName())
                    .isEqualTo("Short" + " ".repeat(30))
                    .hasSize(MenuOptions.OPT_NAME_LENGTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(3, "x".repeat(36), "PGM12345", USER_TYPE_USER))
                    .withMessageContaining("PIC X(35)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(3, "Name", "PGM123456", USER_TYPE_USER))
                    .withMessageContaining("PIC X(8)");
        }

        @Test
        @DisplayName("it bounds the option number to PIC 9(02), refusing rather than truncating")
        void itBoundsTheOptionNumber() {
            // COMEN02Y.cpy:89 - PIC 9(02) is unsigned, so it has no sign position at all, and it holds two
            // digits. Every caller of this factory is transcribing a copybook literal, so a value that does
            // not fit is a transcription error to report rather than one to left-truncate into range.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(-1, "Name", "PGM12345", USER_TYPE_USER))
                    .withMessageContainingAll("negative", "unsigned", "no sign position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(100, "Name", "PGM12345", USER_TYPE_USER))
                    .withMessageContainingAll("needs 3 digit(s)", "PIC 9(2)");
        }

        @Test
        @DisplayName("the canonical constructor bounds the number itself, not only the factory")
        void theCanonicalConstructorBoundsTheNumber() {
            // The factory refuses out-of-range input while forming the image, so the constructor's own guard
            // is only reachable directly. It still has to hold: decode() and any future caller construct
            // through it, and a PIC 9(02) that admitted 100 or -1 would encode to the wrong width or to a
            // sign position the field does not have.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(-1, "01", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContainingAll("PIC 9(02)", "0 to 99");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(100, "00", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContainingAll("PIC 9(02)", "0 to 99");
        }

        @Test
        @DisplayName("the canonical constructor rejects an image that disagrees with the number")
        void theCanonicalConstructorRejectsDisagreeingViews() {
            // Two views of one PIC 9(02) field. Letting them drift would let the raw image a parity diff
            // compares say something different from the number the branch logic tests.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(3, "04", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContainingAll("decodes to 4", "numeric form is 3");
            assertThatIllegalArgumentException()
                    .as("a character ABOVE '9'")
                    .isThrownBy(() -> new MenuOption(3, "0x", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContaining("digits only");
            assertThatIllegalArgumentException()
                    .as("and one BELOW '0' - a space is the byte a group MOVE most often leaves here")
                    .isThrownBy(() -> new MenuOption(3, "0 ", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContaining("digits only");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(3, "3", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContaining("declares exactly 2");
        }

        @Test
        @DisplayName("the canonical constructor enforces the exact width of every character column")
        void theCanonicalConstructorEnforcesExactWidths() {
            // Widths are exact rather than maximal: a value shorter than its declared width is REJECTED
            // rather than quietly padded, because these are transcribed copybook literals and a short one is
            // a transcription error, not data.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(1, "01", "Too short", "PGM12345", USER_TYPE_USER))
                    .withMessageContainingAll(MenuOptions.CDEMO_MENU_OPT_NAME, "declares exactly 35");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(1, "01", toNameWidth(""), "PGM", USER_TYPE_USER))
                    .withMessageContainingAll(MenuOptions.CDEMO_MENU_OPT_PGMNAME, "declares exactly 8");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(1, "01", toNameWidth(""), "PGM12345", "UU"))
                    .withMessageContainingAll(MenuOptions.CDEMO_MENU_OPT_USRTYPE, "declares exactly 1");
            assertThatNullPointerException()
                    .isThrownBy(() -> new MenuOption(1, "01", null, "PGM12345", USER_TYPE_USER));
        }

        @Test
        @DisplayName("the range bound is 0 to 99, and 0 makes no claim about unvalued storage")
        void theRangeBoundIsZeroToNinetyNine() {
            // MIN_OPT_NUM is a range bound and nothing more. It is deliberately NOT "the number an unvalued
            // slot reads back as": slots 11 and 12 carry no VALUE and lie beyond the 460 bytes the overlay
            // redefines, so asserting "00" there would be inventing a fact. That is why those slots are
            // reported absent rather than as an entry numbered 0.
            assertThat(MenuOptions.MIN_OPT_NUM).isZero();
            assertThat(MenuOptions.MAX_OPT_NUM).isEqualTo(99);
            assertThat(MenuOption.of(0, "Name", "PGM12345", USER_TYPE_USER).menuOptNumImage())
                    .isEqualTo("00");
            assertThat(MenuOption.of(99, "Name", "PGM12345", USER_TYPE_USER).menuOptNumImage())
                    .isEqualTo("99");
            assertThat(MenuOptions.options().subList(MenuOptions.ACTIVE_OPTION_COUNT,
                    MenuOptions.TABLE_SIZE))
                    .as("the tail is absent, not an entry numbered MIN_OPT_NUM")
                    .allSatisfy(slot -> assertThat(slot).isEmpty());
        }
    }
}
