package com.vsergeychik.carddemo.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.admin.model.AdminMenuOptions.AdminMenuOption;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link AdminMenuOptions}, the single Java type for COBOL copybook
 * {@code app/cpy/COADM02Y.cpy} - the {@code 01 CARDDEMO-ADMIN-MENU-OPTIONS} administrator menu option
 * table of transaction {@code CA00}.
 *
 * <h2>The unit under test and its only consumer</h2>
 * {@code app/cpy/COADM02Y.cpy} is copied by exactly <strong>one</strong> program,
 * {@code app/cbl/COADM01C.cbl} (the {@code COPY COADM02Y.} at {@code COADM01C.cbl:51}); a
 * repository-wide search finds no other COBOL consumer. That program is therefore the sole authority for
 * how these bytes are observed, and every "why" comment below cites it by line.
 *
 * <h2>Provenance of every expected value: statically derived (practice B12, AAP risk R-A)</h2>
 * No COBOL execution produced any expectation in this file. The legacy programs cannot be run in this
 * environment, so each literal, width and offset asserted here was transcribed by direct reading of
 * {@code app/cpy/COADM02Y.cpy} and cross-checked against its consumer, and each one carries a
 * {@code COADM02Y.cpy:<line>} or {@code COADM01C.cbl:<line>} citation beside it. A reviewer can check
 * every number in this file against the copybook without leaving the file. The copybook itself is never
 * opened at run time and never written: it is the parity oracle (practice B3, gate G5).
 *
 * <h2>Governing rules</h2>
 * {@code review_rules} returns exactly one line, <strong>"No user rules provided."</strong> - there are
 * no user-specified rules for this file, none are invented here, and their absence is not treated as
 * licence to lower the bar. The enterprise-practice substitutes of AAP section 0.10.2 bind instead:
 * B1 (only the JUnit Jupiter and AssertJ APIs the pinned {@code spring-boot-starter-test} already
 * supplies, no version declared anywhere), B2 (nothing outside the closed stack - no JUnit 4, no
 * Hamcrest, and no Mockito, because this table has no collaborator to stub), B3, B4 (the traps below are
 * documented, never quietly corrected), B5 (the five unvalued slots and the commented-out use at
 * {@code COADM01C.cbl:150-151} are preserved as they are), B7 (plain deterministic JUnit; no default
 * locale, timezone or charset is consulted), B8 (every code page is an explicit argument and every
 * import is individual - gate G52), B9 (no mutable static state, fresh state per test, order
 * independent - gate G53), B10, B11 (offsets, widths and literals asserted explicitly; no copybook
 * parser and no reflection-driven layout walker standing in for them) and B12.
 *
 * <h2>Gates this file instruments</h2>
 * <ul>
 *   <li><strong>G21</strong> - total declared width, which fails immediately if a span is dropped or
 *       mis-sized. Asserted both ways: the layout's own self-check passes for the correct descriptor set
 *       and <em>fails</em> for a set with a span omitted and for one with a span mis-sized.</li>
 *   <li><strong>G33</strong> - {@code OCCURS} subscripts are 1-based in COBOL and 0-based in Java. The
 *       first, a middle and the last slot are asserted at both ends of that shift, and out-of-range
 *       subscripts are proven to be <em>rejected</em> rather than clamped.</li>
 *   <li><strong>G34</strong> - {@code REDEFINES} is two typed views over one backing span, round-tripped
 *       in both directions, adding no byte to the group.</li>
 *   <li><strong>G22 / rule R4</strong>, in one point only: {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} is
 *       scale-free, so the option number is an {@code int}. This copybook declares no decimal
 *       {@code PICTURE} at all.</li>
 *   <li><strong>G8</strong> - the verification counterpart of one one-type-per-copybook row.</li>
 *   <li><strong>G49</strong> - {@code app/java/pom.xml} applies its {@code BRANCH} >= 0.90 rule at
 *       {@code PACKAGE} granularity as well as {@code BUNDLE}, so {@code admin.model} is measured on its
 *       own. Together with {@code MenuOptionsTest} this file must drive every decision in
 *       {@link AdminMenuOptions}: each arm of the bounds check (below range, in range and valued, in
 *       range and unvalued, above range) and both sides of every internal predicate.</li>
 * </ul>
 *
 * <h2>Gates with no subject here - stated so nobody chases coverage by inventing tests</h2>
 * <ul>
 *   <li><strong>G50</strong> (both truth states of every {@code 88}-level condition name) has
 *       <strong>no subject</strong>: {@code COADM02Y.cpy} declares <em>zero</em> {@code 88} levels
 *       across all 51 of its lines. There is no condition name here to drive either way.</li>
 *   <li><strong>G22-G29</strong> beyond the single integral-type point above: this copybook has no
 *       scaled decimal field, no rounding, no arithmetic and no monetary value.</li>
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
 * Everything that is a <em>decision</em> belongs to the admin menu service and is asserted there, not
 * duplicated here: the {@code BUILD-MENU-OPTIONS} loop and its inner {@code EVALUATE} arms
 * ({@code COADM01C.cbl:226-245}), the option-count validation ({@code COADM01C.cbl:127-134}), the
 * {@code 'DUMMY'} five-byte prefix branch ({@code COADM01C.cbl:138}), the "coming soon" message text
 * ({@code COADM01C.cbl:149-153}), the {@code EIBCALEN = 0} route, the ENTER/REENTER split, the
 * {@code EVALUATE EIBAID} arms and {@code XCTL} / next-program resolution.
 *
 * <h2>Three traps, recorded rather than resolved (practice B4)</h2>
 * <ol>
 *   <li>{@code COADM01C.cbl:150-151} is <strong>commented out</strong>. Those two lines would have put
 *       {@code CDEMO-ADMIN-OPT-NAME(WS-OPTION)} into the "coming soon" {@code STRING}, but they are
 *       comment lines, so the program emits {@code 'This option '} followed directly by
 *       {@code 'is coming soon ...'} with no option name in it. Nothing is implemented for those lines
 *       here or anywhere (practice B5), and the commented-out text is not treated as a value.</li>
 *   <li><strong>The header comment does not identify the copybook.</strong> {@code COADM02Y.cpy:2} reads
 *       {@code * CardDemo - Admin Menu Options} - and so does {@code COMEN02Y.cpy:2}, byte for byte. The
 *       two copybooks are not distinguishable by their headers; only the {@code 01} group name is
 *       ({@code CARDDEMO-ADMIN-MENU-OPTIONS} at {@code COADM02Y.cpy:19} versus
 *       {@code CARDDEMO-MAIN-MENU-OPTIONS}). A reader must never take the header as identification.</li>
 *   <li>The group name {@code CDEMO-ADMIN-OPT} ({@code COADM02Y.cpy:45}) is itself
 *       <strong>never referenced</strong> by {@code COADM01C.cbl} - only its three child fields are,
 *       subscripted. Harmless, and noted rather than read as evidence that the group is unused.</li>
 * </ol>
 *
 * <h2>One adaptation, stated plainly</h2>
 * Slots 5 to 9 lie past the end of the group being redefined, so no {@code VALUE} clause reaches them
 * and {@link AdminMenuOptions} reports them as <strong>absent</strong> rather than as fabricated entries
 * carrying option number 0 and blank names. "Present but empty" is therefore asserted here as what the
 * class actually guarantees: those subscripts are <em>addressable and accepted</em> - never rejected,
 * never clamped - the table stays nine slots wide, and the bytes their spans occupy are emitted at their
 * declared widths. The byte content of those spans is this module's own reproducible pad, and it is
 * asserted as such, not as a claim about what the legacy program's storage holds.
 */
@DisplayName("AdminMenuOptions - CARDDEMO-ADMIN-MENU-OPTIONS of COADM02Y")
class AdminMenuOptionsTest {

    /** The code page of the ASCII fixtures, named explicitly - never a platform default (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary datasets, used to prove the encoding is the caller's choice. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    // =================================================================================================
    // The copybook's four PIC X(35) literals, each written as its visible text plus an EXPLICIT trailing
    // space count so the padding is legible and countable rather than hidden inside an opaque
    // 35-character literal. Every one was measured against the copybook line cited (practice B12).
    // =================================================================================================

    /** {@code COADM02Y.cpy:26} {@code 'User List (Security)               '}: 20 visible + 15 spaces. */
    private static final String OPTION_1_NAME = "User List (Security)" + " ".repeat(15);

    /** {@code COADM02Y.cpy:31} {@code 'User Add (Security)                '}: 19 visible + 16 spaces. */
    private static final String OPTION_2_NAME = "User Add (Security)" + " ".repeat(16);

    /** {@code COADM02Y.cpy:36} {@code 'User Update (Security)             '}: 22 visible + 13 spaces. */
    private static final String OPTION_3_NAME = "User Update (Security)" + " ".repeat(13);

    /** {@code COADM02Y.cpy:41} {@code 'User Delete (Security)             '}: 22 visible + 13 spaces. */
    private static final String OPTION_4_NAME = "User Delete (Security)" + " ".repeat(13);

    /** {@code COADM02Y.cpy:27} - the security user-list program, exactly 8 characters. */
    private static final String OPTION_1_PGMNAME = "COUSR00C";

    /** {@code COADM02Y.cpy:42} - the security user-delete program, exactly 8 characters. */
    private static final String OPTION_4_PGMNAME = "COUSR03C";

    /**
     * Pads synthetic test text to {@code CDEMO-ADMIN-OPT-NAME}'s declared width. Used only for values
     * this file invents; the four copybook literals above state their padding explicitly instead.
     *
     * <p>A pure static function over immutable values, so it introduces no shared state (practice B9).
     */
    private static String toNameWidth(String visible) {
        return visible + " ".repeat(AdminMenuOptions.OPT_NAME_LENGTH - visible.length());
    }

    /** A fresh codec per call, so no test can hand mutable state to another (practice B9, gate G53). */
    private static FixedWidthCodec asciiCodec() {
        return new FixedWidthCodec(ASCII);
    }

    @Nested
    @DisplayName("Constants and widths, transcribed from the copybook rather than read off the class")
    class ConstantsAndWidths {

        @Test
        @DisplayName("each declared width is the PICTURE the copybook writes")
        void eachDeclaredWidthIsItsPicture() {
            // COADM02Y.cpy:20 - 05 CDEMO-ADMIN-OPT-COUNT   PIC 9(02) VALUE 4.
            assertThat(AdminMenuOptions.OPT_COUNT_LENGTH).isEqualTo(2);
            // COADM02Y.cpy:46 - 15 CDEMO-ADMIN-OPT-NUM     PIC 9(02).
            assertThat(AdminMenuOptions.OPT_NUM_LENGTH).isEqualTo(2);
            // COADM02Y.cpy:47 - 15 CDEMO-ADMIN-OPT-NAME    PIC X(35).
            assertThat(AdminMenuOptions.OPT_NAME_LENGTH).isEqualTo(35);
            // COADM02Y.cpy:48 - 15 CDEMO-ADMIN-OPT-PGMNAME PIC X(08).
            assertThat(AdminMenuOptions.OPT_PGMNAME_LENGTH).isEqualTo(8);
            // COADM02Y.cpy:45 - 10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.
            assertThat(AdminMenuOptions.TABLE_SIZE).isEqualTo(9);
            // COADM02Y.cpy:20 - the VALUE of the count field.
            assertThat(AdminMenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(4);
            // The first subscript the copybook values nothing for.
            assertThat(AdminMenuOptions.SPECIFIED_OPTION_COUNT_PLUS_ONE).isEqualTo(5);
            // PIC 9(02) is an unsigned two-digit display field, so 99 is the largest it can hold.
            assertThat(AdminMenuOptions.MAX_OPT_NUM).isEqualTo(99);
        }

        @Test
        @DisplayName("an entry is 45 bytes: 2 + 35 + 8, with NO user-type column")
        void anEntryIsFortyFiveBytesComponentWise() {
            // Asserted component-wise, so the difference from the sibling table is PROVABLE rather than
            // assumed. COADM02Y.cpy:46-48 declares exactly three subfields per entry: there is no
            // PIC X(01) FILLER and no -USRTYPE field anywhere in the copybook (a repository-wide search
            // for CDEMO-ADMIN-OPT-USRTYPE returns nothing). That single missing column is precisely why
            // the admin entry is 45 bytes while COMEN02Y's main-menu entry is 46.
            assertThat(AdminMenuOptions.OPT_NUM_LENGTH
                    + AdminMenuOptions.OPT_NAME_LENGTH
                    + AdminMenuOptions.OPT_PGMNAME_LENGTH)
                    .as("2 + 35 + 8 = 45, the whole of CDEMO-ADMIN-OPT")
                    .isEqualTo(45);
            assertThat(AdminMenuOptions.ENTRY_LENGTH).isEqualTo(45);
        }

        @Test
        @DisplayName("the populated area is 180 bytes - NOT 182: the count field sits outside it")
        void thePopulatedAreaIsOneHundredAndEightyNotOneHundredAndEightyTwo() {
            // The natural wrong answer is 182, and it is worth stating why it is wrong.
            // CDEMO-ADMIN-OPT-COUNT is a level-05 item at COADM02Y.cpy:20, a SIBLING of
            // CDEMO-ADMIN-OPTIONS-DATA rather than a child of it: the group only opens at
            // COADM02Y.cpy:22, after the count. So the populated area is the four valued entries and
            // nothing else - 4 x 45 = 180 - and the count's two bytes are counted once, in the group
            // width, never inside the data area.
            assertThat(AdminMenuOptions.POPULATED_DATA_LENGTH)
                    .as("4 entries x 45 bytes, with the COADM02Y.cpy:20 count field excluded")
                    .isEqualTo(180)
                    .isEqualTo(AdminMenuOptions.ACTIVE_OPTION_COUNT * AdminMenuOptions.ENTRY_LENGTH)
                    .isNotEqualTo(AdminMenuOptions.ACTIVE_OPTION_COUNT * AdminMenuOptions.ENTRY_LENGTH
                            + AdminMenuOptions.OPT_COUNT_LENGTH);
            // The count is the FIRST two bytes of the group, so the option area starts at offset 2.
            assertThat(AdminMenuOptions.OPTIONS_OFFSET).isEqualTo(AdminMenuOptions.OPT_COUNT_LENGTH);
            assertThat(AdminMenuOptions.OPTIONS_OFFSET).isEqualTo(2);
        }

        @Test
        @DisplayName("the declared table is 405 bytes and is WIDER than the 180 it redefines")
        void theDeclaredTableIsWiderThanThePopulatedArea() {
            // COADM02Y.cpy:45 declares OCCURS 9 TIMES over storage COADM02Y.cpy:22-42 values only four
            // entries of, which is why these are two separate constants and must never be conflated.
            assertThat(AdminMenuOptions.TABLE_LENGTH)
                    .isEqualTo(405)
                    .isEqualTo(AdminMenuOptions.TABLE_SIZE * AdminMenuOptions.ENTRY_LENGTH)
                    .isGreaterThan(AdminMenuOptions.POPULATED_DATA_LENGTH);
            assertThat(AdminMenuOptions.TABLE_LENGTH - AdminMenuOptions.POPULATED_DATA_LENGTH)
                    .as("five unvalued slots of 45 bytes each")
                    .isEqualTo(225);
        }

        @Test
        @DisplayName("the active count 4 and the table size 9 are independent values")
        void theActiveCountAndTheTableSizeAreIndependent() {
            // COADM01C.cbl:228-229 bounds its loop on the COUNT
            // (PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT), and
            // COADM01C.cbl:128 validates against the COUNT too - while the accessor's bound is the
            // TABLE SIZE. That is exactly why slots 5 to 9 are never populated: nothing iterates to 9.
            assertThat(AdminMenuOptions.ACTIVE_OPTION_COUNT)
                    .as("the COADM02Y.cpy:20 literal, not the COADM02Y.cpy:45 OCCURS count")
                    .isEqualTo(4)
                    .isLessThan(AdminMenuOptions.TABLE_SIZE);
            assertThat(AdminMenuOptions.activeOptions()).hasSize(4);
            assertThat(AdminMenuOptions.options()).hasSize(9);
        }

        @Test
        @DisplayName("the group is 407 bytes: the REDEFINES overlay adds none of its own")
        void theRedefinesOverlayAddsNoBytes() {
            // A REDEFINES is an overlay, not an append. The 01 group at COADM02Y.cpy:19 is sized by its
            // LARGEST redefinition, so it is the count plus the OCCURS table - and emphatically not the
            // count plus the table plus the 180 bytes the table redefines.
            assertThat(AdminMenuOptions.GROUP_LENGTH)
                    .isEqualTo(407)
                    .isEqualTo(AdminMenuOptions.OPT_COUNT_LENGTH + AdminMenuOptions.TABLE_LENGTH)
                    .isNotEqualTo(AdminMenuOptions.OPT_COUNT_LENGTH + AdminMenuOptions.TABLE_LENGTH
                            + AdminMenuOptions.POPULATED_DATA_LENGTH);
            assertThat(AdminMenuOptions.encode(ASCII))
                    .as("the emitted image is the group width, overlay included but not added")
                    .hasSize(AdminMenuOptions.GROUP_LENGTH);
        }

        @Test
        @DisplayName("the copybook's own field names survive verbatim, misspellings and all")
        void theCopybookFieldNamesSurviveVerbatim() {
            // The parity differ compares field by field BY NAME, so a renamed field would make a real
            // difference invisible. Each name below is the copybook's spelling at the line cited.
            assertThat(AdminMenuOptions.ADMIN_OPT_COUNT_FIELD).isEqualTo("CDEMO-ADMIN-OPT-COUNT");
            assertThat(AdminMenuOptions.ADMIN_OPTIONS_DATA_FIELD).isEqualTo("CDEMO-ADMIN-OPTIONS-DATA");
            assertThat(AdminMenuOptions.ADMIN_OPTIONS_FIELD).isEqualTo("CDEMO-ADMIN-OPTIONS");
            assertThat(AdminMenuOptions.ADMIN_OPT_FIELD).isEqualTo("CDEMO-ADMIN-OPT");
            assertThat(AdminMenuOptions.ADMIN_OPT_NUM_FIELD).isEqualTo("CDEMO-ADMIN-OPT-NUM");
            assertThat(AdminMenuOptions.ADMIN_OPT_NAME_FIELD).isEqualTo("CDEMO-ADMIN-OPT-NAME");
            assertThat(AdminMenuOptions.ADMIN_OPT_PGMNAME_FIELD).isEqualTo("CDEMO-ADMIN-OPT-PGMNAME");
        }

        @Test
        @DisplayName("the class is a constant table and refuses to be instantiated")
        void theClassRefusesInstantiation() throws Exception {
            Constructor<AdminMenuOptions> constructor =
                    AdminMenuOptions.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("The four entries the copybook values, byte for byte")
    class PopulatedEntries {

        /**
         * Each row is the copybook's own data for one entry: the {@code VALUE} of its
         * {@code PIC 9(02)} at {@code COADM02Y.cpy:24}, {@code :29}, {@code :34} and {@code :39}; the
         * visible text and the <em>measured</em> trailing-space count of its {@code PIC X(35)} at
         * {@code :26}, {@code :31}, {@code :36} and {@code :41}; and its {@code PIC X(08)} program name
         * at {@code :27}, {@code :32}, {@code :37} and {@code :42}.
         */
        @ParameterizedTest(name = "CDEMO-ADMIN-OPT({0}) is {1} -> {4}")
        @DisplayName("each carries its literal at its full declared width")
        @CsvSource({
            "1, User List (Security),   20, 15, COUSR00C",
            "2, User Add (Security),    19, 16, COUSR01C",
            "3, User Update (Security), 22, 13, COUSR02C",
            "4, User Delete (Security), 22, 13, COUSR03C",
        })
        void eachEntryCarriesItsLiteral(int subscript, String visibleText, int visibleLength,
                                       int trailingSpaces, String programName) {
            // The measured arithmetic first, so a mis-transcribed row fails here rather than downstream.
            assertThat(visibleText).hasSize(visibleLength);
            assertThat(visibleLength + trailingSpaces)
                    .as("visible characters plus padding is exactly PIC X(35)")
                    .isEqualTo(AdminMenuOptions.OPT_NAME_LENGTH);
            assertThat(programName)
                    .as("PIC X(08) needs no padding for any of the four - and a future 7-character "
                            + "name must not slip through unnoticed")
                    .hasSize(AdminMenuOptions.OPT_PGMNAME_LENGTH);

            AdminMenuOption option = AdminMenuOptions.optionBySubscript(subscript).orElseThrow();

            assertThat(option.adminOptNum()).isEqualTo(subscript);
            assertThat(option.adminOptNumImage()).hasSize(2).isEqualTo("0" + subscript);
            assertThat(option.adminOptName())
                    .hasSize(AdminMenuOptions.OPT_NAME_LENGTH)
                    .isEqualTo(visibleText + " ".repeat(trailingSpaces));
            assertThat(option.adminOptPgmName())
                    .hasSize(AdminMenuOptions.OPT_PGMNAME_LENGTH)
                    .isEqualTo(programName);
        }

        @Test
        @DisplayName("the four literals in copybook order, against the transcribed constants")
        void theFourLiteralsInCopybookOrder() {
            // A second, independent transcription of the same four values: the CsvSource above states
            // them as visible text plus a space count, these constants state them as composed strings.
            // Both must agree with the copybook, so a mistake in either form is caught.
            assertThat(AdminMenuOptions.activeOptions())
                    .extracting(AdminMenuOption::adminOptName)
                    .containsExactly(OPTION_1_NAME, OPTION_2_NAME, OPTION_3_NAME, OPTION_4_NAME);
            assertThat(AdminMenuOptions.activeOptions())
                    .extracting(AdminMenuOption::adminOptPgmName)
                    .containsExactly(OPTION_1_PGMNAME, "COUSR01C", "COUSR02C", OPTION_4_PGMNAME);
            assertThat(AdminMenuOptions.activeOptions())
                    .extracting(AdminMenuOption::adminOptNum)
                    .containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("the FIRST slot, reached by the 1-based subscript COBOL uses")
        void theFirstSlotIsOptionOne() {
            AdminMenuOption first = AdminMenuOptions.optionBySubscript(1).orElseThrow();

            assertThat(first.adminOptNumImage()).isEqualTo("01");
            assertThat(first.adminOptName()).isEqualTo(OPTION_1_NAME);
            assertThat(first.adminOptPgmName()).isEqualTo(OPTION_1_PGMNAME);
        }

        @ParameterizedTest
        @DisplayName("isSpecified() is true for each valued slot, and each is present")
        @ValueSource(ints = {1, 2, 3, 4})
        void isSpecifiedIsTrueForEachValuedSlot(int subscript) {
            assertThat(AdminMenuOptions.isSpecified(subscript)).isTrue();
            assertThat(AdminMenuOptions.optionBySubscript(subscript)).isPresent();
        }
    }

    @Nested
    @DisplayName("Untrimmed values - the padding is part of the field")
    class UntrimmedValues {

        /**
         * {@code COADM01C.cbl:235} moves the whole field:
         * {@code STRING ... CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE ... INTO WS-ADMIN-OPT-TXT},
         * and {@code DELIMITED BY SIZE} means all 35 bytes including every trailing space. So the
         * padding is observable output, not incidental whitespace. (The second use of this field, at
         * {@code COADM01C.cbl:150}, is a comment line - see the trap list in this class's header.)
         */
        @ParameterizedTest
        @DisplayName("CDEMO-ADMIN-OPT-NAME is 35 characters, space-padded and never trimmed")
        @ValueSource(ints = {1, 2, 3, 4})
        void theNameIsUntrimmedThirtyFiveCharacters(int subscript) {
            String name = AdminMenuOptions.optionBySubscript(subscript).orElseThrow().adminOptName();

            assertThat(name).hasSize(AdminMenuOptions.OPT_NAME_LENGTH).endsWith(" ");
            // The negative assertion that makes a future "helpful" trim inside the model fail loudly:
            // every one of the four literals has significant trailing padding, so none of them can
            // legitimately equal its own trimmed form.
            assertThat(name)
                    .as("a trim inside the model would silently drop bytes COADM01C:235 emits")
                    .isNotEqualTo(name.trim());
        }

        @Test
        @DisplayName("reading the name span back gives the same untrimmed 35 characters")
        void readingTheNameSpanBackIsUntrimmed() {
            FixedWidthCodec codec = asciiCodec();
            FixedWidthRecord area =
                    codec.wrap(AdminMenuOptions.encode(ASCII), AdminMenuOptions.GROUP_LAYOUT);

            String stored = codec.readPicX(area, AdminMenuOptions.optNameSpanBySubscript(3));

            assertThat(stored)
                    .hasSize(AdminMenuOptions.OPT_NAME_LENGTH)
                    .isEqualTo(OPTION_3_NAME)
                    .endsWith(" ")
                    .isNotEqualTo(stored.trim());
        }

        @Test
        @DisplayName("CDEMO-ADMIN-OPT-PGMNAME is 8 characters and is not trimmed either")
        void theProgramNameIsUntrimmedEightCharacters() {
            // All four copybook program names happen to fill PIC X(08) exactly, so for those four there
            // is no padding to lose - stated here rather than left to look like a trim would be safe.
            assertThat(AdminMenuOptions.activeOptions())
                    .allSatisfy(option -> assertThat(option.adminOptPgmName())
                            .hasSize(AdminMenuOptions.OPT_PGMNAME_LENGTH)
                            .isEqualTo(option.adminOptPgmName().trim()));

            // And the accessor itself does not trim: a shorter program name is held at its full declared
            // width, padded on the right, exactly as COADM01C:138's five-byte reference modification
            // CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) requires the eight bytes to be.
            String padded = AdminMenuOption.of(5, toNameWidth("Short pgm"), "COX").adminOptPgmName();

            assertThat(padded)
                    .hasSize(AdminMenuOptions.OPT_PGMNAME_LENGTH)
                    .isEqualTo("COX" + " ".repeat(5))
                    .endsWith(" ")
                    .isNotEqualTo(padded.trim());
        }
    }

    @Nested
    @DisplayName("PIC 9(02) has BOTH an int value and a two-byte zero-filled image")
    class OptionNumberImage {

        @ParameterizedTest
        @DisplayName("option n renders as the two-byte image 0n, leading zero intact")
        @CsvSource({"1, 01", "2, 02", "3, 03", "4, 04"})
        void optionRendersAsATwoByteImage(int subscript, String expectedImage) {
            // COADM01C.cbl:233 does
            //   STRING CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE '. ' DELIMITED BY SIZE ...
            // which consumes the stored two-byte display image directly, so option 1 renders on screen
            // as "01. " and not "1. ". A model exposing only an int would lose that leading zero.
            AdminMenuOption option = AdminMenuOptions.optionBySubscript(subscript).orElseThrow();

            assertThat(option.adminOptNumImage())
                    .hasSize(AdminMenuOptions.OPT_NUM_LENGTH)
                    .isEqualTo(expectedImage);
            assertThat(option.adminOptNum())
                    .as("the same field, as the scale-free integer it also is")
                    .isEqualTo(subscript);
        }

        @Test
        @DisplayName("CDEMO-ADMIN-OPT-COUNT renders as 04, and its value is 4")
        void theCountRendersAsZeroFour() {
            // COADM02Y.cpy:20 writes VALUE 4; COBOL right-aligns a numeric VALUE in its field and
            // zero-fills to the left, so the two stored bytes are "04".
            assertThat(AdminMenuOptions.adminOptCountImage())
                    .hasSize(AdminMenuOptions.OPT_COUNT_LENGTH)
                    .isEqualTo("04");
            assertThat(AdminMenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(4);
        }

        @Test
        @DisplayName("the image is zero-filled at the bottom of the range and full at the top")
        void theImageIsZeroFilledAtBothEndsOfTheRange() {
            assertThat(AdminMenuOption.of(0, toNameWidth("Zero"), "COUSR00C").adminOptNumImage())
                    .isEqualTo("00");
            assertThat(AdminMenuOption.of(99, toNameWidth("Top"), "COUSR00C").adminOptNumImage())
                    .as("PIC 9(02)'s largest value needs no padding at all")
                    .isEqualTo("99");
        }

        @Test
        @DisplayName("the option number is an int - scale-free PIC 9(02), never a scaled decimal")
        void theOptionNumberIsAnInt() throws Exception {
            // Rule R4 / gate G22, in the one point where they have a subject here: COADM02Y.cpy:46
            // declares PIC 9(02) with no V and no S, so the field is a scale-free integer. The whole
            // copybook declares no scaled PICTURE, so no fixed-point decimal type belongs anywhere in
            // this table and no binary approximation of one may appear either.
            assertThat(AdminMenuOption.class.getMethod("adminOptNum").getReturnType())
                    .isEqualTo(int.class);
            assertThat(AdminMenuOption.class.getRecordComponents())
                    .extracting(component -> component.getType().getName())
                    .containsExactly("int", "java.lang.String", "java.lang.String");
            assertThat(AdminMenuOptions.optNumSpanBySubscript(1).kind())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
        }
    }

    @Nested
    @DisplayName("Gate G33 - OCCURS is 1-based in COBOL and 0-based in Java")
    class OneBasedSubscripts {

        @Test
        @DisplayName("subscript 1 is index 0, subscript 9 is index 8, and 4 is 3 in between")
        void theSubscriptShiftHoldsAtBothEndsAndInTheMiddle() {
            // The single largest defect risk in this migration is this one-element shift. Both ends are
            // asserted, and a middle slot with them, so an off-by-one cannot hide between the ends.
            assertThat(AdminMenuOptions.zeroBasedIndexFor(1)).isZero();
            assertThat(AdminMenuOptions.zeroBasedIndexFor(4)).isEqualTo(3);
            assertThat(AdminMenuOptions.zeroBasedIndexFor(AdminMenuOptions.TABLE_SIZE))
                    .isEqualTo(AdminMenuOptions.TABLE_SIZE - 1)
                    .isEqualTo(8);
        }

        @ParameterizedTest(name = "CDEMO-ADMIN-OPT({0}) is list element {1}")
        @DisplayName("the 1-based accessor and the 0-based list agree at both ends and in the middle")
        @CsvSource({"1, 0", "4, 3", "9, 8"})
        void theAccessorAndTheListAgree(int subscript, int index) {
            List<Optional<AdminMenuOption>> slots = AdminMenuOptions.options();

            assertThat(AdminMenuOptions.zeroBasedIndexFor(subscript)).isEqualTo(index);
            assertThat(AdminMenuOptions.optionBySubscript(subscript)).isEqualTo(slots.get(index));
        }

        @Test
        @DisplayName("the table is exactly nine slots, the OCCURS count and no more")
        void theTableIsExactlyNineSlots() {
            assertThat(AdminMenuOptions.options()).hasSize(AdminMenuOptions.TABLE_SIZE).hasSize(9);
        }

        @Test
        @DisplayName("the boundary pair, side by side: subscript 9 is accepted and 10 is rejected")
        void theBoundaryPairIsNineAcceptedAndTenRejected() {
            // COADM02Y.cpy:45 is OCCURS 9 TIMES, so the last valid subscript is 9 - not 4, which is only
            // the count - and 10 is one past the end. Asserted adjacently so the boundary is visible in
            // one place rather than inferred from two distant tests.
            assertThatNoException().isThrownBy(() -> AdminMenuOptions.optionBySubscript(9));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.optionBySubscript(10));
        }

        /**
         * Out-of-range subscripts are <strong>rejected, never clamped</strong>.
         *
         * <p>An honest note on why this is a contract-level guarantee here rather than a reachability
         * one. {@code COADM01C.cbl:46} declares {@code WS-OPTION PIC 9(02)}, whose domain is 0 to 99,
         * and {@code COADM01C.cbl:123}'s {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'} makes 0
         * reachable - but {@code COADM01C.cbl:137} is {@code IF NOT ERR-FLG-ON}, and <em>every</em>
         * admin subscript use ({@code :138} and {@code :143}) sits inside that guard, after
         * {@code :127-129} has already rejected 0 and anything above the count. So in the admin program
         * an out-of-range subscript does not in fact reach this table. The sibling program has no such
         * guard - {@code COMEN01C.cbl:136-137} subscripts {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)}
         * before its own {@code IF NOT ERR-FLG-ON} at {@code :145} - so for the main-menu table the same
         * rejection is reachability-driven. Here it is <em>defensive</em>, and this file does not claim
         * otherwise. Rejecting is still the only correct behaviour: substituting slot 1 or slot 9 would
         * invent an answer to a question the caller has to decide.
         */
        @ParameterizedTest
        @DisplayName("subscripts outside 1..9 are rejected, never clamped to an end of the table")
        @ValueSource(ints = {0, 10, 13, 99})
        void outOfRangeSubscriptsAreRejected(int subscript) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("COBOL has no subscript 0 and no tenth element of an OCCURS 9 table")
                    .isThrownBy(() -> AdminMenuOptions.optionBySubscript(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.zeroBasedIndexFor(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.isSpecified(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.optionSpanBySubscript(subscript));
        }

        @Test
        @DisplayName("a field name cannot be subscripted for a slot the table does not have")
        void aFieldNameCannotBeSubscriptedOutOfRange() {
            // COADM01C.cbl:143 spells a subscripted reference as
            // CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION); the rendered key follows that spelling exactly.
            assertThat(AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NUM_FIELD, 9))
                    .isEqualTo("CDEMO-ADMIN-OPT-NUM(9)");
            assertThat(AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_PGMNAME_FIELD, 1))
                    .isEqualTo("CDEMO-ADMIN-OPT-PGMNAME(1)");
            assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(
                    () -> AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NUM_FIELD, 10));
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOptions.subscriptedName(null, 1));
        }

        @ParameterizedTest(name = "CDEMO-ADMIN-OPT({0}) starts at byte {1}")
        @DisplayName("every slot's span is 45 bytes at the offset its subscript addresses")
        @CsvSource({"1, 2", "2, 47", "3, 92", "4, 137", "5, 182", "6, 227", "7, 272", "8, 317",
            "9, 362"})
        void everySlotSpanIsFortyFiveBytesAtItsOffset(int subscript, int expectedOffset) {
            FieldSpan entry = AdminMenuOptions.optionSpanBySubscript(subscript);

            assertThat(entry.length()).isEqualTo(AdminMenuOptions.ENTRY_LENGTH);
            assertThat(entry.offset())
                    .as("OPTIONS_OFFSET + (subscript - 1) x 45, the 1-based shift applied once")
                    .isEqualTo(expectedOffset)
                    .isEqualTo(AdminMenuOptions.OPTIONS_OFFSET
                            + (subscript - 1) * AdminMenuOptions.ENTRY_LENGTH);
            // The three subfields tile the entry exactly: 2 + 35 + 8, in copybook order.
            assertThat(AdminMenuOptions.optNumSpanBySubscript(subscript).offset())
                    .isEqualTo(expectedOffset);
            assertThat(AdminMenuOptions.optNameSpanBySubscript(subscript).offset())
                    .isEqualTo(expectedOffset + AdminMenuOptions.OPT_NUM_LENGTH);
            assertThat(AdminMenuOptions.optPgmNameSpanBySubscript(subscript).offset())
                    .isEqualTo(expectedOffset + AdminMenuOptions.OPT_NUM_LENGTH
                            + AdminMenuOptions.OPT_NAME_LENGTH);
            assertThat(AdminMenuOptions.optPgmNameSpanBySubscript(subscript).endOffsetExclusive())
                    .isEqualTo(expectedOffset + AdminMenuOptions.ENTRY_LENGTH);
        }
    }

    @Nested
    @DisplayName("The five slots the copybook does not value - addressable, and never fabricated")
    class UnvaluedTailSlots {

        @ParameterizedTest
        @DisplayName("slots 5 to 9 are ACCEPTED - rejecting them would be as wrong as clamping")
        @ValueSource(ints = {5, 6, 7, 8, 9})
        void slotsFiveToNineAreAccepted(int subscript) {
            // In range but unvalued. COADM02Y.cpy:45 declares nine slots, so all nine are addressable;
            // COADM02Y.cpy:22-42 values only the first four, so these five carry nothing.
            assertThatNoException().isThrownBy(() -> AdminMenuOptions.optionBySubscript(subscript));
            assertThat(AdminMenuOptions.isSpecified(subscript)).isFalse();
            assertThat(AdminMenuOptions.optionBySubscript(subscript))
                    .as("addressable, and empty because no VALUE clause reaches these bytes")
                    .isEmpty();
            assertThatNoException()
                    .isThrownBy(() -> AdminMenuOptions.optNameSpanBySubscript(subscript));
        }

        @Test
        @DisplayName("the table is still nine slots wide: the five are absent, not trimmed away")
        void theTableIsStillNineSlotsWide() {
            // Practice B5: the unvalued slots are preserved as slots. Shrinking the table to four, or
            // looping to nine as though all nine were populated, are the two opposite defects here.
            List<Optional<AdminMenuOption>> slots = AdminMenuOptions.options();

            assertThat(slots).hasSize(9);
            assertThat(slots.subList(0, AdminMenuOptions.ACTIVE_OPTION_COUNT))
                    .allSatisfy(slot -> assertThat(slot).isPresent());
            assertThat(slots.subList(AdminMenuOptions.ACTIVE_OPTION_COUNT, AdminMenuOptions.TABLE_SIZE))
                    .allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @ParameterizedTest
        @DisplayName("their spans are still emitted at 2, 35 and 8 bytes, space and zero padded")
        @ValueSource(ints = {5, 6, 7, 8, 9})
        void theirSpansAreStillEmittedAtTheirDeclaredWidths(int subscript) {
            // What is asserted here is that the BYTES are present at their declared widths - which is
            // what keeps every later offset correct - and that this module's pad is reproducible. The
            // pad itself is this module's own choice: COADM02Y.cpy gives these bytes no VALUE at all, so
            // no claim is made here about what the legacy program's storage would hold.
            Map<String, String> images =
                    AdminMenuOptions.fieldImages(AdminMenuOptions.encode(ASCII), ASCII);

            assertThat(images.get(
                    AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NUM_FIELD, subscript)))
                    .isEqualTo("00");
            assertThat(images.get(
                    AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NAME_FIELD, subscript)))
                    .hasSize(AdminMenuOptions.OPT_NAME_LENGTH)
                    .isEqualTo(" ".repeat(AdminMenuOptions.OPT_NAME_LENGTH));
            assertThat(images.get(AdminMenuOptions.subscriptedName(
                    AdminMenuOptions.ADMIN_OPT_PGMNAME_FIELD, subscript)))
                    .hasSize(AdminMenuOptions.OPT_PGMNAME_LENGTH)
                    .isEqualTo(" ".repeat(AdminMenuOptions.OPT_PGMNAME_LENGTH));
        }

        @Test
        @DisplayName("the unspecified tail is a 225-byte FILLER descriptor carrying no VALUE")
        void theUnspecifiedTailIsAFillerDescriptor() {
            FieldSpan tail = AdminMenuOptions.unspecifiedTailSpan();

            assertThat(tail.offset())
                    .as("immediately after the 180 valued bytes")
                    .isEqualTo(AdminMenuOptions.OPTIONS_OFFSET
                            + AdminMenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(182);
            assertThat(tail.length())
                    .isEqualTo(AdminMenuOptions.TABLE_LENGTH - AdminMenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(225);
            assertThat(tail.endOffsetExclusive()).isEqualTo(AdminMenuOptions.GROUP_LENGTH);
            assertThat(tail.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(tail.hasInitialValue())
                    .as("the copybook declares no VALUE for these bytes, so the descriptor carries none")
                    .isFalse();
            assertThat(tail.name()).contains(AdminMenuOptions.UNSPECIFIED_TAIL_SUFFIX);
        }

        @Test
        @DisplayName("decode reports the tail as absent even though its padded bytes would parse")
        void decodeReportsTheTailAsAbsentEvenThoughItWouldParse() {
            // The assertion that keeps a fabrication out: slot 5's number span holds "00", which WOULD
            // parse as an entry, and parsing it would put an invented value straight into the table.
            byte[] group = AdminMenuOptions.encode(ASCII);
            int tailStart = AdminMenuOptions.unspecifiedTailSpan().offset();

            assertThat(new String(group, ASCII).substring(tailStart, tailStart + 2))
                    .as("slot 5's number span really does hold parseable digits")
                    .isEqualTo("00");
            assertThat(AdminMenuOptions.decode(group, ASCII)
                    .get(AdminMenuOptions.zeroBasedIndexFor(5)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Gate G21 - the total declared width, and the self-check that enforces it")
    class TotalWidthSelfCheck {

        @Test
        @DisplayName("the layout declares 407 bytes as 28 contiguous storage spans from offset 0")
        void theLayoutDeclaresEveryByteExactlyOnce() {
            RecordLayout layout = AdminMenuOptions.GROUP_LAYOUT;
            List<FieldSpan> storage = layout.storageSpans();

            assertThat(layout.recordLength()).isEqualTo(AdminMenuOptions.GROUP_LENGTH).isEqualTo(407);
            assertThat(storage)
                    .as("the count span plus 9 slots x 3 subfields")
                    .hasSize(1 + AdminMenuOptions.TABLE_SIZE * 3)
                    .hasSize(28);
            assertThat(storage.stream().mapToInt(FieldSpan::length).sum())
                    .as("the storage spans sum to exactly the declared record length")
                    .isEqualTo(AdminMenuOptions.GROUP_LENGTH);

            // Contiguity, walked explicitly: a gap or an overlap anywhere would move every later offset.
            int cursor = 0;
            for (FieldSpan span : storage) {
                assertThat(span.offset())
                        .as("no gap and no overlap before " + span.describe())
                        .isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(AdminMenuOptions.GROUP_LENGTH);
        }

        @Test
        @DisplayName("the populated area's 12 spans carry the copybook's VALUE literals and total 180")
        void thePopulatedAreaSpansAreEmittedWithTheirValues() {
            // COADM02Y.cpy:24-42 declares the populated area entirely from FILLER items, and those bytes
            // MUST be emitted rather than skipped - that is exactly what makes the 180-byte total
            // reachable. In this layout the same bytes are named by the OCCURS elements, and each of the
            // twelve carries the copybook literal declared on the corresponding FILLER item.
            RecordLayout layout = AdminMenuOptions.GROUP_LAYOUT;
            int valuedBytes = 0;
            for (int subscript = 1; subscript <= AdminMenuOptions.ACTIVE_OPTION_COUNT; subscript++) {
                for (String field : List.of(AdminMenuOptions.ADMIN_OPT_NUM_FIELD,
                        AdminMenuOptions.ADMIN_OPT_NAME_FIELD,
                        AdminMenuOptions.ADMIN_OPT_PGMNAME_FIELD)) {
                    FieldSpan span =
                            layout.span(AdminMenuOptions.subscriptedName(field, subscript));
                    assertThat(span.hasInitialValue())
                            .as(span.describe() + " must carry its copybook VALUE")
                            .isTrue();
                    valuedBytes += span.length();
                }
            }

            assertThat(valuedBytes)
                    .as("12 spans covering 4 x 45 bytes")
                    .isEqualTo(AdminMenuOptions.POPULATED_DATA_LENGTH);

            // And the count span itself, which is the thirteenth valued span and the only one outside
            // the option area (COADM02Y.cpy:20).
            FieldSpan count = layout.span(AdminMenuOptions.ADMIN_OPT_COUNT_FIELD);
            assertThat(count.offset()).isZero();
            assertThat(count.length()).isEqualTo(AdminMenuOptions.OPT_COUNT_LENGTH);
            assertThat(count.hasInitialValue()).isTrue();
        }

        @Test
        @DisplayName("the self-check PASSES for the correct descriptor set, storage only and complete")
        void theSelfCheckPassesForTheCorrectDescriptorSet() {
            RecordLayout layout = AdminMenuOptions.GROUP_LAYOUT;

            assertThatNoException().isThrownBy(
                    () -> new RecordLayout(AdminMenuOptions.GROUP_LENGTH, layout.storageSpans()));
            assertThatNoException().isThrownBy(
                    () -> new RecordLayout(AdminMenuOptions.GROUP_LENGTH, layout.spans()));
        }

        @Test
        @DisplayName("the self-check FAILS when a span is omitted, and names the shortfall")
        void theSelfCheckFailsWhenASpanIsOmitted() {
            // This is what gives G21 its teeth: proving the check would have caught a dropped span, not
            // merely that the correct set passes. Drop the trailing CDEMO-ADMIN-OPT-PGMNAME(9) and the
            // layout is 8 bytes short of 407 and refuses to exist.
            List<FieldSpan> spans = new ArrayList<>(AdminMenuOptions.GROUP_LAYOUT.storageSpans());
            FieldSpan dropped = spans.remove(spans.size() - 1);

            assertThat(dropped.name()).isEqualTo(AdminMenuOptions.subscriptedName(
                    AdminMenuOptions.ADMIN_OPT_PGMNAME_FIELD, AdminMenuOptions.TABLE_SIZE));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(AdminMenuOptions.GROUP_LENGTH, spans))
                    .withMessageContaining("8 byte(s) short");
        }

        @Test
        @DisplayName("the self-check FAILS when a span is mis-sized, and names the offending descriptor")
        void theSelfCheckFailsWhenASpanIsMisSized() {
            // A width transcribed as 34 instead of PIC X(35) leaves a one-byte hole, which the check
            // reports against the span that would have moved.
            List<FieldSpan> spans = new ArrayList<>(AdminMenuOptions.GROUP_LAYOUT.storageSpans());
            String lastNameField = AdminMenuOptions.subscriptedName(
                    AdminMenuOptions.ADMIN_OPT_NAME_FIELD, AdminMenuOptions.TABLE_SIZE);
            int index = -1;
            for (int position = 0; position < spans.size(); position++) {
                if (spans.get(position).name().equals(lastNameField)) {
                    index = position;
                }
            }
            assertThat(index).as("the layout must declare " + lastNameField).isNotNegative();
            FieldSpan original = spans.get(index);
            spans.set(index, FieldSpan.alphanumeric(original.name(), original.offset(),
                    original.length() - 1));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(AdminMenuOptions.GROUP_LENGTH, spans))
                    .withMessageContaining("Layout gap of 1 byte(s) before")
                    .withMessageContaining(AdminMenuOptions.subscriptedName(
                            AdminMenuOptions.ADMIN_OPT_PGMNAME_FIELD, AdminMenuOptions.TABLE_SIZE));
        }

        @Test
        @DisplayName("every named field of the group is readable, keyed by its copybook name")
        void everyNamedFieldOfTheGroupIsReadable() {
            Map<String, String> images =
                    AdminMenuOptions.fieldImages(AdminMenuOptions.encode(ASCII), ASCII);

            assertThat(images)
                    .as("the count, 9 slots x 3 subfields, and the two REDEFINES views")
                    .hasSize(1 + AdminMenuOptions.TABLE_SIZE * 3 + 2)
                    .hasSize(30);
            assertThat(images.get(AdminMenuOptions.ADMIN_OPT_COUNT_FIELD)).isEqualTo("04");
            assertThat(images.get(AdminMenuOptions.subscriptedName(
                    AdminMenuOptions.ADMIN_OPT_NAME_FIELD, 1))).isEqualTo(OPTION_1_NAME);
            assertThat(images.get(AdminMenuOptions.subscriptedName(
                    AdminMenuOptions.ADMIN_OPT_PGMNAME_FIELD, 4))).isEqualTo(OPTION_4_PGMNAME);
            assertThat(images.get(AdminMenuOptions.ADMIN_OPTIONS_DATA_FIELD))
                    .hasSize(AdminMenuOptions.POPULATED_DATA_LENGTH);
            assertThat(images.get(AdminMenuOptions.ADMIN_OPTIONS_FIELD))
                    .hasSize(AdminMenuOptions.TABLE_LENGTH);
        }
    }

    @Nested
    @DisplayName("Gate G34 - REDEFINES is two typed views over ONE backing span")
    class RedefinesViews {

        @Test
        @DisplayName("both views are overlays starting at the same offset over the same bytes")
        void bothViewsStartAtTheSameOffset() {
            FieldSpan dataView = AdminMenuOptions.ADMIN_OPTIONS_DATA_SPAN;
            FieldSpan tableView = AdminMenuOptions.ADMIN_OPTIONS_SPAN;

            // COADM02Y.cpy:44 - 05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.
            assertThat(dataView.name()).isEqualTo(AdminMenuOptions.ADMIN_OPTIONS_DATA_FIELD);
            assertThat(tableView.name()).isEqualTo(AdminMenuOptions.ADMIN_OPTIONS_FIELD);
            assertThat(dataView.offset()).isEqualTo(AdminMenuOptions.OPTIONS_OFFSET);
            assertThat(tableView.offset()).isEqualTo(AdminMenuOptions.OPTIONS_OFFSET);
            assertThat(dataView.length()).isEqualTo(AdminMenuOptions.POPULATED_DATA_LENGTH);
            assertThat(tableView.length()).isEqualTo(AdminMenuOptions.TABLE_LENGTH);
            assertThat(dataView.redefinition()).isTrue();
            assertThat(tableView.redefinition()).isTrue();
            assertThat(AdminMenuOptions.GROUP_LAYOUT.redefinitions())
                    .as("exactly the two views the copybook declares")
                    .containsExactly(dataView, tableView);
        }

        @Test
        @DisplayName("the overlay adds ZERO bytes: it is a view, not an append")
        void theOverlayAddsZeroBytes() {
            RecordLayout layout = AdminMenuOptions.GROUP_LAYOUT;
            int overlayBytes = layout.redefinitions().stream().mapToInt(FieldSpan::length).sum();

            assertThat(overlayBytes)
                    .as("the two views describe 180 + 405 bytes of alternative naming")
                    .isEqualTo(585);
            assertThat(layout.storageSpans().stream().mapToInt(FieldSpan::length).sum())
                    .as("yet the record is still exactly the storage total, overlays excluded")
                    .isEqualTo(layout.recordLength());
            assertThat(AdminMenuOptions.ADMIN_OPTIONS_SPAN.endOffsetExclusive())
                    .as("the larger view ends at the end of the group, not past it")
                    .isEqualTo(AdminMenuOptions.GROUP_LENGTH);
        }

        @Test
        @DisplayName("the 180-byte view is exactly the leading 180 characters of the 405-byte view")
        void theSmallerViewIsThePrefixOfTheLarger() {
            byte[] group = AdminMenuOptions.encode(ASCII);

            String dataImage = AdminMenuOptions.adminOptionsDataImage(group, ASCII);
            String tableImage = AdminMenuOptions.adminOptionsImage(group, ASCII);

            assertThat(dataImage).hasSize(AdminMenuOptions.POPULATED_DATA_LENGTH);
            assertThat(tableImage).hasSize(AdminMenuOptions.TABLE_LENGTH).startsWith(dataImage);
            assertThat(tableImage.substring(0, AdminMenuOptions.POPULATED_DATA_LENGTH))
                    .as("one set of bytes, read through two names")
                    .isEqualTo(dataImage);
        }

        @Test
        @DisplayName("a write through the OCCURS view is visible byte-for-byte through the DATA view")
        void aWriteThroughTheTableViewIsVisibleThroughTheDataView() {
            FixedWidthCodec codec = asciiCodec();
            FixedWidthRecord area =
                    codec.wrap(AdminMenuOptions.encode(ASCII), AdminMenuOptions.GROUP_LAYOUT);
            FieldSpan slotTwoName = AdminMenuOptions.optNameSpanBySubscript(2);
            String replacement = toNameWidth("Rewritten through the OCCURS view");

            codec.writePicX(area, slotTwoName, replacement);

            // Read back through the OTHER view, and locate the field by arithmetic within it.
            int nameStartInDataView = AdminMenuOptions.ENTRY_LENGTH + AdminMenuOptions.OPT_NUM_LENGTH;
            String dataImage = codec.readPicX(area, AdminMenuOptions.ADMIN_OPTIONS_DATA_SPAN);
            assertThat(dataImage.substring(nameStartInDataView,
                    nameStartInDataView + AdminMenuOptions.OPT_NAME_LENGTH))
                    .isEqualTo(replacement);

            // The same claim at byte level, which is the form the parity differ works in.
            byte[] dataViewBytes = area.readSpanBytes(AdminMenuOptions.ADMIN_OPTIONS_DATA_SPAN);
            assertThat(area.readSpanBytes(slotTwoName))
                    .isEqualTo(Arrays.copyOfRange(dataViewBytes, nameStartInDataView,
                            nameStartInDataView + AdminMenuOptions.OPT_NAME_LENGTH));
        }

        @Test
        @DisplayName("and a write through the DATA view is visible byte-for-byte through the OCCURS view")
        void aWriteThroughTheDataViewIsVisibleThroughTheTableView() {
            FixedWidthCodec codec = asciiCodec();
            FixedWidthRecord area =
                    codec.wrap(AdminMenuOptions.encode(ASCII), AdminMenuOptions.GROUP_LAYOUT);
            FieldSpan slotThreePgmName = AdminMenuOptions.optPgmNameSpanBySubscript(3);
            int pgmStartInDataView = 2 * AdminMenuOptions.ENTRY_LENGTH
                    + AdminMenuOptions.OPT_NUM_LENGTH + AdminMenuOptions.OPT_NAME_LENGTH;
            String replacement = "COZZZZZC";

            String dataImage = codec.readPicX(area, AdminMenuOptions.ADMIN_OPTIONS_DATA_SPAN);
            codec.writePicX(area, AdminMenuOptions.ADMIN_OPTIONS_DATA_SPAN,
                    dataImage.substring(0, pgmStartInDataView) + replacement
                            + dataImage.substring(pgmStartInDataView + replacement.length()));

            assertThat(codec.readPicX(area, slotThreePgmName)).isEqualTo(replacement);
            assertThat(area.readSpanBytes(slotThreePgmName))
                    .isEqualTo(replacement.getBytes(ASCII));
            assertThat(codec.readPicX(area, AdminMenuOptions.optNameSpanBySubscript(3)))
                    .as("the neighbouring field is untouched, so the write landed at one offset only")
                    .isEqualTo(OPTION_3_NAME);
        }

        @Test
        @DisplayName("decode of an encoded table is lossless, and re-encoding reproduces the bytes")
        void theTableRoundTripsInBothDirections() {
            byte[] group = AdminMenuOptions.encode(ASCII);

            List<Optional<AdminMenuOption>> decoded = AdminMenuOptions.decode(group, ASCII);

            assertThat(decoded).hasSize(AdminMenuOptions.TABLE_SIZE)
                    .isEqualTo(AdminMenuOptions.options());
            assertThat(AdminMenuOptions.encodeSlots(decoded, ASCII))
                    .as("bytes -> entries -> bytes is byte-identical")
                    .isEqualTo(group);
        }
    }

    @Nested
    @DisplayName("The byte image - offsets, the caller's code page, and what is not claimed")
    class ByteImage {

        @Test
        @DisplayName("the count and the four valued entries land at their declared offsets")
        void theValuedEntriesLandAtTheirOffsets() {
            String image = new String(AdminMenuOptions.encode(ASCII), ASCII);

            assertThat(image).hasSize(AdminMenuOptions.GROUP_LENGTH).startsWith("04");
            for (int subscript = 1; subscript <= AdminMenuOptions.ACTIVE_OPTION_COUNT; subscript++) {
                AdminMenuOption option = AdminMenuOptions.optionBySubscript(subscript).orElseThrow();
                int base = AdminMenuOptions.OPTIONS_OFFSET
                        + (subscript - 1) * AdminMenuOptions.ENTRY_LENGTH;
                int nameStart = base + AdminMenuOptions.OPT_NUM_LENGTH;
                int pgmStart = nameStart + AdminMenuOptions.OPT_NAME_LENGTH;

                assertThat(image.substring(base, nameStart)).isEqualTo(option.adminOptNumImage());
                assertThat(image.substring(nameStart, pgmStart)).isEqualTo(option.adminOptName());
                assertThat(image.substring(pgmStart, base + AdminMenuOptions.ENTRY_LENGTH))
                        .isEqualTo(option.adminOptPgmName());
            }
        }

        @Test
        @DisplayName("the code page is always the caller's: the same table differs under IBM037")
        void theCodePageIsAlwaysTheCallers() {
            // Practice B8: no platform default is ever consulted, so the same table encodes to different
            // bytes under the two code pages this migration uses - IBM037 for the EBCDIC datasets and
            // US-ASCII for the text fixtures - and each round-trips under its own.
            byte[] ebcdic = AdminMenuOptions.encode(EBCDIC);

            assertThat(ebcdic)
                    .hasSize(AdminMenuOptions.GROUP_LENGTH)
                    .isNotEqualTo(AdminMenuOptions.encode(ASCII));
            assertThat(AdminMenuOptions.decode(ebcdic, EBCDIC)).isEqualTo(AdminMenuOptions.options());
            assertThat(AdminMenuOptions.adminOptionsDataImage(ebcdic, EBCDIC))
                    .isEqualTo(AdminMenuOptions.adminOptionsDataImage(
                            AdminMenuOptions.encode(ASCII), ASCII));
        }

        @Test
        @DisplayName("a decode survives a tail of bytes that are not even well formed")
        void aDecodeSurvivesAMalformedTail() {
            // Real storage may hold anything in those 225 bytes, and the copybook claims nothing about
            // them. Decoding them as entries would put them through a numeric read, so a single
            // non-digit would fail the decode of an otherwise perfectly valid group image and take the
            // four valued entries down with it.
            byte[] group = AdminMenuOptions.encode(ASCII);
            for (int offset = AdminMenuOptions.unspecifiedTailSpan().offset();
                    offset < group.length; offset++) {
                group[offset] = (byte) '?';
            }

            List<Optional<AdminMenuOption>> decoded = AdminMenuOptions.decode(group, ASCII);

            assertThat(decoded).hasSize(AdminMenuOptions.TABLE_SIZE);
            assertThat(decoded.subList(0, AdminMenuOptions.ACTIVE_OPTION_COUNT))
                    .isEqualTo(AdminMenuOptions.options()
                            .subList(0, AdminMenuOptions.ACTIVE_OPTION_COUNT));
            assertThat(decoded.subList(AdminMenuOptions.ACTIVE_OPTION_COUNT,
                    AdminMenuOptions.TABLE_SIZE))
                    .allSatisfy(slot -> assertThat(slot).isEmpty());

            // The bytes stay readable as bytes, which is where unclaimed storage belongs.
            Map<String, String> images = AdminMenuOptions.fieldImages(group, ASCII);
            assertThat(images.get(AdminMenuOptions.subscriptedName(
                    AdminMenuOptions.ADMIN_OPT_NAME_FIELD, 5)))
                    .isEqualTo("?".repeat(AdminMenuOptions.OPT_NAME_LENGTH));
            assertThat(images.get(AdminMenuOptions.subscriptedName(
                    AdminMenuOptions.ADMIN_OPT_NAME_FIELD, 1)))
                    .as("a malformed tail cannot disturb a valued entry")
                    .isEqualTo(OPTION_1_NAME);
        }

        @Test
        @DisplayName("but a malformed byte in a VALUED slot is still an error, because it is claimed")
        void aMalformedByteInAValuedSlotIsStillAnError() {
            // The tolerance is confined to the tail. Bytes COADM02Y.cpy:24-42 does value are claimed, so
            // a non-digit in option 1's number span is a genuine data or offset defect and is reported.
            byte[] group = AdminMenuOptions.encode(ASCII);
            group[AdminMenuOptions.OPTIONS_OFFSET] = (byte) '?';

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AdminMenuOptions.decode(group, ASCII));
        }

        @Test
        @DisplayName("a short or over-long image is refused rather than read at drifting offsets")
        void aWrongWidthImageIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AdminMenuOptions.decode(
                            new byte[AdminMenuOptions.GROUP_LENGTH - 1], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AdminMenuOptions.adminOptionsImage(
                            new byte[AdminMenuOptions.GROUP_LENGTH + 1], ASCII));
        }
    }

    @Nested
    @DisplayName("encodeSlots - the fixed-size area a caller supplies")
    class EncodeSlotsContract {

        @Test
        @DisplayName("it round-trips a caller's own table")
        void itRoundTripsACallersTable() {
            List<Optional<AdminMenuOption>> slots = new ArrayList<>(AdminMenuOptions.options());
            slots.set(0, Optional.of(AdminMenuOption.of(7, "Replaced", "COTEST1C")));

            List<Optional<AdminMenuOption>> decoded =
                    AdminMenuOptions.decode(AdminMenuOptions.encodeSlots(slots, ASCII), ASCII);

            assertThat(decoded.get(0).orElseThrow().adminOptNum()).isEqualTo(7);
            assertThat(decoded.get(0).orElseThrow().adminOptName())
                    .isEqualTo(toNameWidth("Replaced"));
            assertThat(decoded.subList(AdminMenuOptions.ACTIVE_OPTION_COUNT,
                    AdminMenuOptions.TABLE_SIZE))
                    .allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @Test
        @DisplayName("exactly nine slots are required - the unvalued ones included, never omitted")
        void exactlyNineSlotsAreRequired() {
            List<Optional<AdminMenuOption>> tooFew = List.copyOf(
                    AdminMenuOptions.options().subList(0, AdminMenuOptions.ACTIVE_OPTION_COUNT));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AdminMenuOptions.encodeSlots(tooFew, ASCII))
                    .withMessageContaining("OCCURS 9 TIMES");
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOptions.encodeSlots(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOptions.encode(null, ASCII));
        }

        @Test
        @DisplayName("a null element is refused: an absent slot is Optional.empty(), not null")
        void aNullElementIsRefused() {
            List<Optional<AdminMenuOption>> withNull = new ArrayList<>(AdminMenuOptions.options());
            withNull.set(AdminMenuOptions.TABLE_SIZE - 1, null);

            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOptions.encodeSlots(withNull, ASCII))
                    .withMessageContaining("Optional.empty()");
        }

        @Test
        @DisplayName("the List<AdminMenuOption> overload reads a null element as an absent slot")
        void theNullTolerantOverloadReadsNullAsAbsent() {
            List<AdminMenuOption> nullTailed = new ArrayList<>();
            for (int subscript = 1; subscript <= AdminMenuOptions.TABLE_SIZE; subscript++) {
                nullTailed.add(AdminMenuOptions.optionBySubscript(subscript).orElse(null));
            }

            assertThat(AdminMenuOptions.encode(nullTailed, ASCII))
                    .isEqualTo(AdminMenuOptions.encode(ASCII));
        }
    }

    @Nested
    @DisplayName("AdminMenuOption - the entry type, and the PICTURE rules it enforces")
    class EntryType {

        @Test
        @DisplayName("PIC X pads a short value on the right and truncates an over-wide one on the right")
        void picXPadsRightAndTruncatesRight() {
            // COBOL fills a PIC X receiver from its leftmost position and discards the overflow, so the
            // surviving characters are the LEADING ones - the opposite of a numeric receiver.
            assertThat(AdminMenuOption.of(3, "Short", "PGM").adminOptName())
                    .isEqualTo("Short" + " ".repeat(30));
            assertThat(AdminMenuOption.of(3, "x".repeat(40), "PGM12345").adminOptName())
                    .isEqualTo("x".repeat(AdminMenuOptions.OPT_NAME_LENGTH));
            assertThat(AdminMenuOption.of(3, toNameWidth("Name"), "COUSR00CXX").adminOptPgmName())
                    .isEqualTo("COUSR00C");
        }

        @ParameterizedTest
        @DisplayName("the option number is bounded to what PIC 9(02) can hold")
        @ValueSource(ints = {-1, 100})
        void theOptionNumberIsBoundedToItsPicture(int outOfRange) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AdminMenuOption.of(outOfRange, "Name", "PGM12345"))
                    .withMessageContaining("PIC 9(02)");
        }

        @Test
        @DisplayName("the canonical constructor requires both names at their full declared widths")
        void theCanonicalConstructorRequiresFullDeclaredWidths() {
            // A value that is not exactly its declared width has already lost or gained bytes, so it is
            // refused where the entry is built rather than where a later read comes back wrong.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AdminMenuOption(1, "short", OPTION_1_PGMNAME))
                    .withMessageContaining("PIC X(35)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AdminMenuOption(1, OPTION_1_NAME, "short"))
                    .withMessageContaining("PIC X(08)");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuOption(1, null, OPTION_1_PGMNAME));
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuOption(1, OPTION_1_NAME, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOption.of(1, null, OPTION_1_PGMNAME));
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOption.of(1, "Name", null));
        }

        @Test
        @DisplayName("an entry built from the copybook's own literals equals the table's own")
        void anEntryBuiltFromTheCopybookLiteralsEqualsTheTables() {
            // Value equality, so a transcription drift in either direction fails: the entry built here
            // from the copybook literals must be the very entry the class publishes for subscript 1.
            AdminMenuOption transcribed = new AdminMenuOption(1, OPTION_1_NAME, OPTION_1_PGMNAME);

            assertThat(AdminMenuOptions.optionBySubscript(1)).contains(transcribed);
            assertThat(AdminMenuOption.of(1, "User List (Security)", OPTION_1_PGMNAME))
                    .as("padding the visible text to PIC X(35) reproduces the copybook literal")
                    .isEqualTo(transcribed);
        }
    }
}
