/*
 * ******************************************************************
 * Program     : MenuOptionCopybookTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the MenuOptionCopybook oracle before any menu
 *               test depends on it. Asserts the two frozen option
 *               tables are read exactly as declared, cross-checks the
 *               per-slot width against the REDEFINES group as an
 *               independent witness, confirms the commented-out option
 *               name at COMEN02Y.cpy:69 is NOT read, and probes every
 *               refusal path against a temporary directory so that no
 *               malformed member can be silently skipped.
 * Source      : app/cpy/COADM02Y.cpy:L20-L45  (4 slots, OCCURS 9)
 *               app/cpy/COMEN02Y.cpy:L21-L92  (10 slots, OCCURS 12,
 *                                              :L69 commented-out name)
 *                                                            @ 7756d89
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verification of {@link MenuOptionCopybook}.
 *
 * <p><strong>Why an oracle needs its own test.</strong> Two oracles written earlier in this remediation
 * each carried a silent-skip defect: a pattern that failed to match an unanticipated but legitimate
 * declaration shape, so a real field was dropped and every assertion built on it passed vacuously. Both
 * were found only by probing the refusal paths directly. This class therefore does that first, and does it
 * against a temporary directory, because {@code app/} is frozen and cannot host a malformed fixture.</p>
 *
 * <p><strong>The independent witness.</strong> Each copybook declares its slot geometry twice: once as the
 * {@code VALUE}-bearing {@code FILLER} fields of the data group, and once as the {@code 15}-level fields
 * of the {@code REDEFINES} group. The oracle reads the first.
 * {@link TheRedefinesGroupCorroboratesTheParse#perSlotWidthMatchesTheRedefinesGroup}
 * parses the second with a pattern local to this test and requires the two to agree, so a parsing error in
 * the oracle cannot be confirmed by the oracle itself.</p>
 */
@DisplayName("MenuOptionCopybook - the oracle over app/cpy/COADM02Y.cpy and app/cpy/COMEN02Y.cpy")
class MenuOptionCopybookTest {

    /** {@code app/cpy/COADM02Y.cpy}, the administrator option table. */
    private static final String ADMIN_MEMBER = "COADM02Y";

    /** {@code app/cpy/COMEN02Y.cpy}, the main option table. */
    private static final String MAIN_MEMBER = "COMEN02Y";

    /** {@code PIC X(35)} - the option name width both tables declare. */
    private static final int NAME_WIDTH = 35;

    /** {@code PIC X(08)} - the program name width both tables declare. */
    private static final int PROGRAM_WIDTH = 8;

    /** {@code PIC 9(02)} - the option number width both tables declare. */
    private static final int NUMBER_WIDTH = 2;

    /** A {@code 15}-level field of a {@code REDEFINES} group, used as the independent witness. */
    private static final Pattern REDEFINED_FIELD =
            Pattern.compile("^\\s*15\\s+[A-Z0-9-]+\\s+PIC\\s+([X9])\\((\\d+)\\)\\.\\s*$");

    // ==================================================================
    // 1. The administrator table.
    // ==================================================================

    @Nested
    @DisplayName("1. COADM02Y is read exactly as the corpus declares it")
    class TheAdministratorTable {

        @Test
        @DisplayName("the count is four and the capacity is nine, and they are different numbers")
        void theCountAndCapacityAreBothRead() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(ADMIN_MEMBER);

            assertThat(table.declaredCount())
                    .as("app/cpy/COADM02Y.cpy:L20 CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4")
                    .isEqualTo(4);
            assertThat(table.occursCapacity())
                    .as("app/cpy/COADM02Y.cpy CDEMO-ADMIN-OPT OCCURS 9 TIMES")
                    .isEqualTo(9);
            assertThat(table.occursCapacity())
                    .as("the two must not be conflated: the spare subscripts are unpopulated")
                    .isNotEqualTo(table.declaredCount());
        }

        @Test
        @DisplayName("four slots are parsed, numbered one through four in declaration order")
        void fourSlotsAreParsedInOrder() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(ADMIN_MEMBER);

            assertThat(table.slots()).hasSize(4);
            assertThat(table.slots().stream().map(MenuOptionCopybook.Slot::number).toList())
                    .containsExactly(1, 2, 3, 4);
        }

        @ParameterizedTest(name = "slot {0} carries the padded name and program the copybook declares")
        @CsvSource({"1, COUSR00C", "2, COUSR01C", "3, COUSR02C", "4, COUSR03C"})
        @DisplayName("each slot's program name is read verbatim")
        void eachSlotProgramIsRead(final int slot, final String program) {
            assertThat(MenuOptionCopybook.of(ADMIN_MEMBER).programOf(slot)).isEqualTo(program);
        }

        @Test
        @DisplayName("names retain the padding the quoted literal carries, which is the whole point")
        void namesRetainTheirPadding() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(ADMIN_MEMBER);

            assertThat(table.nameOf(1))
                    .as("the padding lives INSIDE the quotes at app/cpy/COADM02Y.cpy:L26")
                    .isEqualTo("User List (Security)               ")
                    .hasSize(NAME_WIDTH);
            assertThat(table.nameOf(4)).isEqualTo("User Delete (Security)             ");
            for (final MenuOptionCopybook.Slot slot : table.slots()) {
                assertThat(table.nameOf(slot.number()))
                        .as("slot %d must be exactly PIC X(35) wide", slot.number())
                        .hasSize(NAME_WIDTH);
            }
        }

        @Test
        @DisplayName("each slot declares exactly two character fields, and no user-type byte")
        void eachSlotDeclaresTwoCharacterFields() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(ADMIN_MEMBER);

            for (final MenuOptionCopybook.Slot slot : table.slots()) {
                assertThat(slot.fields())
                        .as("the administrator table declares a name and a program, and nothing else")
                        .hasSize(2);
                assertThat(slot.fields().stream().map(MenuOptionCopybook.OptionField::picture).toList())
                        .containsExactly("X(35)", "X(08)");
                assertThat(table.userTypeOf(slot.number()))
                        .as("COADM02Y declares no PIC X(01) user-type byte; COMEN02Y does")
                        .isEmpty();
            }
        }
    }

    // ==================================================================
    // 2. The main table, including the commented-out literal.
    // ==================================================================

    @Nested
    @DisplayName("2. COMEN02Y is read exactly as the corpus declares it, comments excluded")
    class TheMainTable {

        @Test
        @DisplayName("the count is ten and the capacity is twelve")
        void theCountAndCapacityAreBothRead() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(MAIN_MEMBER);

            assertThat(table.declaredCount())
                    .as("app/cpy/COMEN02Y.cpy:L21 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10")
                    .isEqualTo(10);
            assertThat(table.occursCapacity())
                    .as("app/cpy/COMEN02Y.cpy:L88 CDEMO-MENU-OPT OCCURS 12 TIMES")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("ten slots are parsed, numbered one through ten, each with three character fields")
        void tenSlotsAreParsedWithThreeFieldsEach() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(MAIN_MEMBER);

            assertThat(table.slots()).hasSize(10);
            assertThat(table.slots().stream().map(MenuOptionCopybook.Slot::number).toList())
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            for (final MenuOptionCopybook.Slot slot : table.slots()) {
                assertThat(slot.fields().stream().map(MenuOptionCopybook.OptionField::picture).toList())
                        .as("slot %d: a name, a program and a user-type byte", slot.number())
                        .containsExactly("X(35)", "X(08)", "X(01)");
            }
        }

        @ParameterizedTest(name = "slot {0} targets {1}")
        @CsvSource({"1, COACTVWC", "2, COACTUPC", "3, COCRDLIC", "4, COCRDSLC", "5, COCRDUPC",
            "6, COTRN00C", "7, COTRN01C", "8, COTRN02C", "9, CORPT00C", "10, COBIL00C"})
        @DisplayName("each slot's program name is read verbatim")
        void eachSlotProgramIsRead(final int slot, final String program) {
            assertThat(MenuOptionCopybook.of(MAIN_MEMBER).programOf(slot)).isEqualTo(program);
        }

        @Test
        @DisplayName("EVERY slot's user-type byte is 'U'; the copybook declares no 'A' anywhere")
        void everyUserTypeByteIsUser() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(MAIN_MEMBER);

            for (final MenuOptionCopybook.Slot slot : table.slots()) {
                assertThat(table.userTypeOf(slot.number()))
                        .as("slot %d", slot.number())
                        .contains("U");
            }
        }

        @Test
        @DisplayName("the COMMENTED-OUT option name at :69 is not read; the active one at :70 is")
        void theCommentedOutOptionNameIsNotRead() {
            // app/cpy/COMEN02Y.cpy declares slot 8's name twice: :69 carries an asterisk in column seven
            // and reads 'Transaction Add (Admin Only)       ', and :70 is active and reads
            // 'Transaction Add                    '. A parser that did not skip comment lines would read
            // the disabled literal, and would then report a table the corpus does not have.
            final MenuOptionCopybook table = MenuOptionCopybook.of(MAIN_MEMBER);

            assertThat(table.nameOf(8))
                    .isEqualTo("Transaction Add                    ")
                    .doesNotContain("Admin Only");
            assertThat(table.slots())
                    .as("the disabled literal must not have produced an eleventh slot either")
                    .hasSize(10);
        }

        @Test
        @DisplayName("slot 8 is nonetheless a USER option, which is why COMEN01C's 'A' gate never fires")
        void slotEightIsAUserOptionDespiteItsDisabledLabel() {
            // The disabled label said "(Admin Only)" and the active user-type byte says 'U'. The gate at
            // app/cbl/COMEN01C.cbl:136-137 is therefore live code whose triggering data was deliberately
            // removed from the table - retained under AAP 0.7.3.7 rather than deleted, and reachable in
            // Java only through the package-private option-table seam.
            final MenuOptionCopybook table = MenuOptionCopybook.of(MAIN_MEMBER);

            assertThat(table.userTypeOf(8)).contains("U");
            assertThat(table.slots().stream()
                    .filter(slot -> table.userTypeOf(slot.number()).filter("A"::equals).isPresent())
                    .toList())
                    .as("no populated slot is admin-only, so no canonical selection can trip the gate")
                    .isEmpty();
        }
    }

    // ==================================================================
    // 3. The independent witness: the REDEFINES group.
    // ==================================================================

    @Nested
    @DisplayName("3. The parsed geometry agrees with the REDEFINES group, an independent declaration")
    class TheRedefinesGroupCorroboratesTheParse {

        @ParameterizedTest(name = "{0}: the data group and the REDEFINES group declare the same slot width")
        @ValueSource(strings = {ADMIN_MEMBER, MAIN_MEMBER})
        @DisplayName("per-slot width matches the REDEFINES group")
        void perSlotWidthMatchesTheRedefinesGroup(final String member) {
            final MenuOptionCopybook table = MenuOptionCopybook.of(member);

            final int redefinedWidth = redefinedSlotWidth(member);
            assertThat(redefinedWidth)
                    .as("%s must declare a REDEFINES group whose 15-level fields can be summed", member)
                    .isPositive();

            for (final MenuOptionCopybook.Slot slot : table.slots()) {
                final int parsedWidth = NUMBER_WIDTH + slot.fields().stream()
                        .mapToInt(MenuOptionCopybook.OptionField::width)
                        .sum();
                assertThat(parsedWidth)
                        .as("%s slot %d: the VALUE-bearing FILLER fields and the REDEFINES declarations"
                                + " describe the same bytes, so their widths must agree", member,
                                slot.number())
                        .isEqualTo(redefinedWidth);
            }
        }

        @Test
        @DisplayName("the administrator slot is 45 bytes and the main slot 46, differing by the type byte")
        void theTwoSlotWidthsDifferByExactlyTheUserTypeByte() {
            final int adminWidth = redefinedSlotWidth(ADMIN_MEMBER);
            final int mainWidth = redefinedSlotWidth(MAIN_MEMBER);

            assertThat(adminWidth)
                    .as("2 + 35 + 8")
                    .isEqualTo(NUMBER_WIDTH + NAME_WIDTH + PROGRAM_WIDTH);
            assertThat(mainWidth - adminWidth)
                    .as("the main table adds exactly the PIC X(01) user-type byte")
                    .isEqualTo(1);
        }

        /**
         * Sums the {@code 15}-level field widths of the member's {@code REDEFINES} group, using a pattern
         * local to this test so that the oracle cannot corroborate itself.
         *
         * @param member the copybook member name
         * @return the per-slot width in bytes
         */
        private int redefinedSlotWidth(final String member) {
            int width = 0;
            for (final String line : readMember(member)) {
                if (line.startsWith("*") || line.length() > 6 && line.charAt(6) == '*') {
                    continue;
                }
                final Matcher field = REDEFINED_FIELD.matcher(line);
                if (field.matches()) {
                    width += Integer.parseInt(field.group(2));
                }
            }
            return width;
        }
    }

    // ==================================================================
    // 4. Fail loud: every refusal path, probed against a temporary member.
    // ==================================================================

    @Nested
    @DisplayName("4. A malformed member is refused, never silently skipped")
    class MalformedMembersAreRefused {

        @Test
        @DisplayName("an unreadable member raises rather than yielding an empty table")
        void anUnreadableMemberRaises(@TempDir final Path directory) {
            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("NOSUCH", directory))
                    .withMessageContaining("NOSUCH.cpy");
        }

        @Test
        @DisplayName("a VALUE clause the oracle cannot parse raises, so no slot is dropped")
        void anUnparsableValueClauseRaises(@TempDir final Path directory) throws IOException {
            // A level number written as a single digit is legal COBOL but outside what this oracle reads.
            // The point is that it must SAY SO rather than skip the line and report a shorter table.
            final Path member = write(directory, "PROBE01", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           5 FILLER                         PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            assertThat(member).exists();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE01", directory))
                    .withMessageContaining("cannot parse");
        }

        @Test
        @DisplayName("a literal shorter than its PIC raises, catching a mis-joined continuation line")
        void aLiteralShorterThanItsPictureRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE02", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "           10 FILLER                        PIC X(35) VALUE",
                    "               'Too short'.",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the padding is part of the value, so a short literal is a parse failure")
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE02", directory))
                    .withMessageContaining("joined incorrectly");
        }

        @Test
        @DisplayName("a member declaring no option count raises")
        void aMemberWithNoCountRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE03", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE03", directory))
                    .withMessageContaining("-OPT-COUNT");
        }

        @Test
        @DisplayName("a member declaring no OCCURS capacity raises")
        void aMemberWithNoOccursRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE04", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC 9(02) VALUE 1."));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE04", directory))
                    .withMessageContaining("OCCURS");
        }

        @Test
        @DisplayName("a count that disagrees with the number of slots raises")
        void aCountDisagreeingWithTheSlotsRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE05", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 2.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("this is the check that would have caught a dropped slot")
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE05", directory))
                    .withMessageContaining("double counted");
        }

        @Test
        @DisplayName("a named field other than the count carrying a VALUE raises")
        void anUnexpectedNamedFieldRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE06", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 SOMETHING-ELSE                PIC X(02) VALUE 'AB'.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE06", directory))
                    .withMessageContaining("unexpected data name");
        }

        @Test
        @DisplayName("a character field before any slot opener raises")
        void aCharacterFieldBeforeASlotOpenerRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE07", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC X(02) VALUE 'AB'.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE07", directory))
                    .withMessageContaining("before any");
        }

        @Test
        @DisplayName("a character field whose VALUE is not a quoted literal raises")
        void anUnquotedCharacterValueRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE08", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "           10 FILLER                        PIC X(02) VALUE SPACES.",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE08", directory))
                    .withMessageContaining("not a");
        }

        @Test
        @DisplayName("a continued VALUE with no continuation line raises")
        void aContinuationPastTheEndRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE09", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "           10 FILLER                        PIC X(35) VALUE"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE09", directory))
                    .withMessageContaining("past the end");
        }

        @Test
        @DisplayName("a continued VALUE followed by a blank line raises")
        void aContinuationOntoABlankLineRaises(@TempDir final Path directory) throws IOException {
            write(directory, "PROBE10", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "           10 FILLER                        PIC X(35) VALUE",
                    "",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> MenuOptionCopybook.of("PROBE10", directory))
                    .withMessageContaining("blank line");
        }

        @Test
        @DisplayName("a well-formed temporary member IS accepted, so the probes above discriminate")
        void aWellFormedTemporaryMemberIsAccepted(@TempDir final Path directory) throws IOException {
            // Without this, every refusal test above could be passing for the wrong reason.
            write(directory, "PROBE11", List.of(
                    "       01 PROBE.",
                    "         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 1.",
                    "         05 CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 FILLER                        PIC 9(02) VALUE 1.",
                    "           10 FILLER                        PIC X(04) VALUE 'Name'.",
                    "           10 FILLER                        PIC X(08) VALUE 'COUSR00C'.",
                    "         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.",
                    "           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES."));

            final MenuOptionCopybook table = MenuOptionCopybook.of("PROBE11", directory);

            assertThat(table.member()).isEqualTo("PROBE11");
            assertThat(table.declaredCount()).isEqualTo(1);
            assertThat(table.occursCapacity()).isEqualTo(9);
            assertThat(table.slots()).hasSize(1);
            assertThat(table.programOf(1)).isEqualTo("COUSR00C");
            assertThat(table.slot(1).fieldOfWidth(4).value()).isEqualTo("Name");
        }

        /**
         * Writes a probe member into the temporary directory.
         *
         * @param directory the temporary directory
         * @param member    the member name without its extension
         * @param lines     the source lines, written verbatim
         * @return the path written
         * @throws IOException if the write fails
         */
        private Path write(final Path directory, final String member, final List<String> lines)
                throws IOException {
            final Path path = directory.resolve(member + ".cpy");
            Files.write(path, lines, StandardCharsets.ISO_8859_1);
            return path;
        }
    }

    // ==================================================================
    // 5. Lookups fail loud on a miss.
    // ==================================================================

    @Nested
    @DisplayName("5. A lookup for something the table does not declare raises and says what it found")
    class LookupsFailLoud {

        @Test
        @DisplayName("an absent option number raises and lists the populated options")
        void anAbsentOptionNumberRaises() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(ADMIN_MEMBER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> table.slot(99))
                    .withMessageContaining("[1, 2, 3, 4]");
        }

        @Test
        @DisplayName("an absent field width raises and lists the pictures the slot declares")
        void anAbsentFieldWidthRaises() {
            final MenuOptionCopybook.Slot slot = MenuOptionCopybook.of(ADMIN_MEMBER).slot(1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the administrator table declares no PIC X(01), so this must not silently pass")
                    .isThrownBy(() -> slot.fieldOfWidth(1))
                    .withMessageContaining("X(35)");
        }

        @Test
        @DisplayName("a user-type lookup on a slot that declares none is empty, not an error")
        void anAbsentUserTypeIsEmptyRatherThanAnError() {
            // An absent user-type byte is a property of the administrator table, not a malformed member,
            // so it is reported as an empty Optional rather than as a throw.
            assertThat(MenuOptionCopybook.of(ADMIN_MEMBER).userTypeOf(1)).isEmpty();
            assertThat(MenuOptionCopybook.of(MAIN_MEMBER).userTypeOf(1)).isPresent();
        }

        @Test
        @DisplayName("the slot list and the field lists are unmodifiable")
        void theParsedStructureIsUnmodifiable() {
            final MenuOptionCopybook table = MenuOptionCopybook.of(MAIN_MEMBER);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> table.slots().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> table.slot(1).fields().clear());
        }
    }

    /**
     * Reads a frozen copybook member for the independent-witness parse.
     *
     * @param member the member name without its extension
     * @return the source lines
     */
    private static List<String> readMember(final String member) {
        final Path source = Path.of("app", "cpy", member + ".cpy");
        try {
            return new ArrayList<>(Files.readAllLines(source, StandardCharsets.ISO_8859_1));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read " + source.toAbsolutePath(), unreadable);
        }
    }
}
