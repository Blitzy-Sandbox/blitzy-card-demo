/*
 * Test        : TransactionSourceTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test
 * Function    : Verifies com.cardemo.model.enums.TransactionSource against the frozen COBOL corpus:
 *               the two literal MOVE sites app/cbl/CBACT04C.cbl:L484 ('System') and
 *               app/cbl/COBIL00C.cbl:L222 ('POS TERM'), the PIC X(10) width declared by
 *               app/cpy/CVTRA05Y.cpy:L8 (TRAN-SOURCE) and app/cpy/CVTRA06Y.cpy:L8
 *               (DALYTRAN-SOURCE), and the observed source values of app/data/ASCII/dailytran.txt.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.enums.TransactionSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link TransactionSource} against the corpus rather than against itself.
 *
 * <p>Every claim this enum makes is checkable against a frozen file, so this class checks them there
 * instead of restating the enum's own constants back at it. The two literals are read out of the two
 * {@code MOVE} statements that assign them, the declared width is read out of the two copybooks through
 * {@link RecordLayoutCopybook}, and the recognition behaviour is exercised against the 300 rows of the
 * daily transaction fixture.
 *
 * <p>The fixture is the interesting part. Bytes 23 to 32 of {@code app/data/ASCII/dailytran.txt} hold
 * {@code 'POS TERM  '} on 250 rows and {@code 'OPERATOR  '} on the other 50, and {@code OPERATOR} has no
 * literal assignment site anywhere in {@code app/cbl}. That is not a gap in the enum: of the four
 * statements that write a source marker, only two carry a literal, and the other two copy a value in from
 * outside - {@code app/cbl/COTRN02C.cbl:L454} from the screen field {@code TRNSRCI} and
 * {@code app/cbl/CBTRN02C.cbl:L428} from {@code DALYTRAN-SOURCE}. So a value the corpus never names can
 * legitimately arrive as staged data, and {@code fromCode} is right to return an empty result for it
 * rather than inventing a constant. That behaviour is asserted here.
 */
@DisplayName("TransactionSource against the two COBOL literal sites and the daily transaction fixture")
final class TransactionSourceTest {

    /** The interest calculation program, whose {@code 1300-B-WRITE-TX} paragraph assigns the marker. */
    private static final String INTEREST_PROGRAM = "app/cbl/CBACT04C.cbl";

    /** The bill payment program, which assigns the marker on the online payment path. */
    private static final String BILL_PAYMENT_PROGRAM = "app/cbl/COBIL00C.cbl";

    /** The staged daily transaction input, 300 rows of 350 bytes. */
    private static final Path FIXTURE = Path.of("app", "data", "ASCII", "dailytran.txt");

    /** {@code MOVE 'literal' TO TRAN-SOURCE}, the only shape that binds a constant. */
    private static final Pattern LITERAL_MOVE =
            Pattern.compile("MOVE\\s+'([^']*)'\\s+TO\\s+TRAN-SOURCE");

    /** Any assignment to the source marker, literal or field to field. */
    private static final Pattern ANY_MOVE =
            Pattern.compile("TO\\s+(?:TRAN-SOURCE|DALYTRAN-SOURCE)");

    /** Byte offset of {@code TRAN-SOURCE} within the 350 byte record, one based in the copybook. */
    private static final int SOURCE_START = 23;

    /** The value staged on 50 fixture rows that the corpus never assigns from a literal. */
    private static final String UNASSIGNED_FIXTURE_VALUE = "OPERATOR";

    private static String read(final String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.ISO_8859_1);
        } catch (final IOException failure) {
            throw new AssertionError("cannot read the frozen corpus file " + path, failure);
        }
    }

    /** The literal each program assigns, keyed by program path, taken from the source itself. */
    private static Map<String, String> literalAssignments() {
        final Map<String, String> found = new LinkedHashMap<>();
        for (final String program : List.of(INTEREST_PROGRAM, BILL_PAYMENT_PROGRAM)) {
            final Matcher matcher = LITERAL_MOVE.matcher(read(program));
            if (matcher.find()) {
                found.put(program, matcher.group(1));
            }
        }
        return found;
    }

    /** The 300 fixture rows, split on newlines because the fixture is newline delimited. */
    private static List<String> fixtureRows() {
        final List<String> rows = new ArrayList<>();
        try {
            for (final String line : Files.readString(FIXTURE, StandardCharsets.ISO_8859_1).split("\n")) {
                if (!line.isBlank()) {
                    rows.add(line);
                }
            }
        } catch (final IOException failure) {
            throw new AssertionError("cannot read the fixture " + FIXTURE, failure);
        }
        return rows;
    }

    /** Bytes 23 to 32 of a fixture row, the external form of the source marker. */
    private static String sourceSliceOf(final String row) {
        return row.substring(SOURCE_START - 1, SOURCE_START - 1 + TransactionSource.FIELD_LENGTH);
    }

    @Nested
    @DisplayName("1. The constants are exactly the corpus's literal assignment sites")
    final class CorpusCorrespondence {

        @Test
        @DisplayName("only two statements in the corpus assign a literal source marker")
        void twoLiteralSitesExist() {
            final Map<String, String> assignments = literalAssignments();

            assertThat(assignments)
                    .as("the enum declares one constant per literal assignment site")
                    .hasSize(TransactionSource.values().length)
                    .containsEntry(INTEREST_PROGRAM, "System")
                    .containsEntry(BILL_PAYMENT_PROGRAM, "POS TERM");
        }

        @Test
        @DisplayName("the other two assignments copy a value in rather than naming one")
        void theRemainingSitesAreFieldToField() {
            // This is what lets a value the corpus never names reach the fixture, and therefore why
            // fromCode must return an empty result for OPERATOR instead of inventing a constant.
            final List<String> fieldToField = new ArrayList<>();
            for (final String program : List.of("app/cbl/COTRN02C.cbl", "app/cbl/CBTRN02C.cbl")) {
                final String text = read(program);
                assertThat(ANY_MOVE.matcher(text).find())
                        .as("%s must still assign the source marker", program).isTrue();
                assertThat(LITERAL_MOVE.matcher(text).find())
                        .as("%s must not assign a literal", program).isFalse();
                fieldToField.add(program);
            }

            assertThat(fieldToField).hasSize(2);
        }

        @Test
        @DisplayName("exactly two constants are declared, and no third has crept in")
        void constantCountIsPinned() {
            assertThat(TransactionSource.values())
                    .as("a new constant needs a new literal assignment site to justify it")
                    .hasSize(2)
                    .containsExactly(TransactionSource.SYSTEM, TransactionSource.POS_TERMINAL);
        }

        @Test
        @DisplayName("the declared width is the PIC X(10) both copybooks declare")
        void widthComesFromBothCopybooks() {
            final int online = RecordLayoutCopybook.of("CVTRA05Y").widthOf("TRAN-SOURCE");
            final int staged = RecordLayoutCopybook.of("CVTRA06Y").widthOf("DALYTRAN-SOURCE");

            assertThat(online).as("TRAN-SOURCE is PIC X(10)").isEqualTo(TransactionSource.FIELD_LENGTH);
            assertThat(staged).as("DALYTRAN-SOURCE must agree, or the staging copy would truncate")
                    .isEqualTo(TransactionSource.FIELD_LENGTH);
        }

        @Test
        @DisplayName("neither source field is numeric, so the marker is text throughout")
        void theSourceFieldIsText() {
            assertThat(RecordLayoutCopybook.of("CVTRA05Y").geometry("TRAN-SOURCE").numeric()).isFalse();
            assertThat(RecordLayoutCopybook.of("CVTRA06Y").geometry("DALYTRAN-SOURCE").numeric())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("2. Each constant carries its literal verbatim and pads it to the declared width")
    final class LiteralFidelity {

        @ParameterizedTest(name = "{0} carries {1}")
        @CsvSource({"SYSTEM,System,6", "POS_TERMINAL,POS TERM,8"})
        @DisplayName("the code is the literal byte for byte, case and internal space included")
        void codeIsVerbatim(final TransactionSource source, final String literal, final int length) {
            assertThat(source.getCode())
                    .as("%s must carry the literal exactly as the MOVE spells it", source)
                    .isEqualTo(literal)
                    .hasSize(length);
        }

        @Test
        @DisplayName("the mixed case of 'System' is preserved, not folded either way")
        void systemIsMixedCase() {
            assertThat(TransactionSource.SYSTEM.getCode())
                    .isEqualTo("System")
                    .isNotEqualTo("SYSTEM")
                    .isNotEqualTo("system");
        }

        @Test
        @DisplayName("the single internal space in 'POS TERM' survives")
        void posTerminalKeepsItsInternalSpace() {
            assertThat(TransactionSource.POS_TERMINAL.getCode())
                    .isEqualTo("POS TERM")
                    .contains(" ")
                    .isNotEqualTo("POSTERM");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TransactionSource.class)
        @DisplayName("the fixed width form is exactly ten characters, right padded with blanks")
        void fixedWidthFormIsPadded(final TransactionSource source) {
            final String padded = source.getFixedWidthValue();

            assertThat(padded).as("%s must fill PIC X(10) exactly", source)
                    .hasSize(TransactionSource.FIELD_LENGTH)
                    .startsWith(source.getCode())
                    .isEqualTo(source.getCode()
                            + " ".repeat(TransactionSource.FIELD_LENGTH - source.getCode().length()));
            assertThat(padded.strip())
                    .as("stripping the padding must give the literal back").isEqualTo(source.getCode());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TransactionSource.class)
        @DisplayName("the unpadded and padded forms are different, so the wrong one cannot pass unnoticed")
        void thePaddedFormIsWider(final TransactionSource source) {
            // Writing getCode() into a fixed width record would leave trailing garbage from the previous
            // content of the field, which is why the enum exposes the two forms separately.
            assertThat(source.getFixedWidthValue()).isNotEqualTo(source.getCode());
            assertThat(source.getCode().length()).isLessThan(TransactionSource.FIELD_LENGTH);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TransactionSource.class)
        @DisplayName("neither accessor returns null or blank")
        void accessorsAreAlwaysPopulated(final TransactionSource source) {
            assertThat(source.getCode()).isNotNull().isNotBlank();
            assertThat(source.getFixedWidthValue()).isNotNull().isNotBlank();
        }
    }

    @Nested
    @DisplayName("3. Case sensitive resolution accepts both external forms and invents nothing")
    final class CaseSensitiveResolution {

        @ParameterizedTest(name = "{0}")
        @EnumSource(TransactionSource.class)
        @DisplayName("the unpadded literal resolves back to its constant")
        void unpaddedResolves(final TransactionSource source) {
            assertThat(TransactionSource.fromCode(source.getCode())).contains(source);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TransactionSource.class)
        @DisplayName("the padded record slice resolves too, since that is how it arrives from a file")
        void paddedResolves(final TransactionSource source) {
            assertThat(TransactionSource.fromCode(source.getFixedWidthValue())).contains(source);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TransactionSource.class)
        @DisplayName("leading whitespace is stripped as well as trailing")
        void surroundingWhitespaceIsStripped(final TransactionSource source) {
            assertThat(TransactionSource.fromCode("  " + source.getCode() + "   ")).contains(source);
        }

        @Test
        @DisplayName("a null argument yields an empty result rather than throwing")
        void nullYieldsEmpty() {
            assertThat(TransactionSource.fromCode(null)).isEmpty();
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", " ", "          ", "\t", "\n", " \t \n "})
        @DisplayName("an empty or all whitespace argument yields an empty result")
        void blankYieldsEmpty(final String blank) {
            assertThat(TransactionSource.fromCode(blank)).isEmpty();
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"OPERATOR", "system", "SYSTEM", "pos term", "POSTERM", "POS  TERM",
            "Syste", "Systems", "BATCH", "ONLINE", "0", "-1"})
        @DisplayName("anything that is not one of the two literals yields an empty result")
        void unknownYieldsEmpty(final String unknown) {
            assertThat(TransactionSource.fromCode(unknown))
                    .as("[%s] must not resolve, and must not fall back to a default", unknown)
                    .isEmpty();
        }

        @Test
        @DisplayName("resolution is case sensitive, which is what lets 'System' stay mixed case")
        void resolutionIsCaseSensitive() {
            assertThat(TransactionSource.fromCode("System")).contains(TransactionSource.SYSTEM);
            assertThat(TransactionSource.fromCode("SYSTEM")).isEmpty();
            assertThat(TransactionSource.fromCode("system")).isEmpty();
        }
    }

    @Nested
    @DisplayName("4. Case insensitive resolution is tolerant of case only, never of content")
    final class CaseInsensitiveResolution {

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"System", "SYSTEM", "system", "sYsTeM", "  system  ", "SYSTEM    "})
        @DisplayName("any casing of the interest literal resolves")
        void anyCasingOfSystemResolves(final String candidate) {
            assertThat(TransactionSource.fromCodeIgnoreCase(candidate))
                    .contains(TransactionSource.SYSTEM);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"POS TERM", "pos term", "Pos Term", "pOs TeRm", "  pos term  ",
            "POS TERM  "})
        @DisplayName("any casing of the bill payment literal resolves, internal space required")
        void anyCasingOfPosTerminalResolves(final String candidate) {
            assertThat(TransactionSource.fromCodeIgnoreCase(candidate))
                    .contains(TransactionSource.POS_TERMINAL);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"POSTERM", "POS  TERM", "POS_TERM", "POS-TERM", "OPERATOR", "SYSTEMS"})
        @DisplayName("tolerance does not extend to the internal space or to unknown values")
        void contentIsStillExact(final String candidate) {
            assertThat(TransactionSource.fromCodeIgnoreCase(candidate))
                    .as("[%s] differs by more than case, so it must not resolve", candidate)
                    .isEmpty();
        }

        @Test
        @DisplayName("null and blank behave as they do on the case sensitive resolver")
        void nullAndBlankYieldEmpty() {
            assertThat(TransactionSource.fromCodeIgnoreCase(null)).isEmpty();
            assertThat(TransactionSource.fromCodeIgnoreCase("")).isEmpty();
            assertThat(TransactionSource.fromCodeIgnoreCase("   ")).isEmpty();
        }

        @Test
        @DisplayName("folding does not depend on the host default locale")
        void foldingIsLocaleIndependent() {
            // Neither literal contains a dotted or dotless i, so under a Turkish default locale the two
            // current constants would resolve either way. This is therefore a regression guard for a
            // future literal rather than a discriminating test today, and it is written as one
            // deliberately: the enum folds with Locale.ROOT and that promise should not lapse silently.
            final Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr"));

                assertThat(TransactionSource.fromCodeIgnoreCase("system"))
                        .contains(TransactionSource.SYSTEM);
                assertThat(TransactionSource.fromCodeIgnoreCase("pos term"))
                        .contains(TransactionSource.POS_TERMINAL);
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    @Nested
    @DisplayName("5. The 300 row daily transaction fixture resolves exactly as the corpus predicts")
    final class FixtureBehaviour {

        @Test
        @DisplayName("the fixture holds 300 rows and only two distinct source markers")
        void theFixtureCensusIsAsExpected() {
            final Map<String, Integer> census = new LinkedHashMap<>();
            for (final String row : fixtureRows()) {
                census.merge(sourceSliceOf(row), 1, Integer::sum);
            }

            assertThat(fixtureRows()).as("dailytran.txt stages 300 records").hasSize(300);
            assertThat(census)
                    .as("bytes %d to %d hold exactly two distinct values", SOURCE_START,
                            SOURCE_START + TransactionSource.FIELD_LENGTH - 1)
                    .hasSize(2)
                    .containsEntry("POS TERM  ", 250)
                    .containsEntry("OPERATOR  ", 50);
        }

        @Test
        @DisplayName("every slice is exactly ten characters, so the record geometry holds")
        void everySliceFillsTheField() {
            for (final String row : fixtureRows()) {
                assertThat(sourceSliceOf(row)).hasSize(TransactionSource.FIELD_LENGTH);
            }
        }

        @Test
        @DisplayName("the 250 bill payment rows resolve, and the 50 staged rows do not")
        void resolutionSplitsTheFixtureExactly() {
            int resolved = 0;
            int unresolved = 0;
            for (final String row : fixtureRows()) {
                final Optional<TransactionSource> source =
                        TransactionSource.fromCode(sourceSliceOf(row));
                if (source.isPresent()) {
                    assertThat(source).contains(TransactionSource.POS_TERMINAL);
                    resolved++;
                } else {
                    assertThat(sourceSliceOf(row).strip()).isEqualTo(UNASSIGNED_FIXTURE_VALUE);
                    unresolved++;
                }
            }

            assertThat(resolved).as("the bill payment literal is the only one staged").isEqualTo(250);
            assertThat(unresolved)
                    .as("%s has no literal assignment site, so it must not resolve",
                            UNASSIGNED_FIXTURE_VALUE)
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("the unassigned staged value stays unresolved under the tolerant resolver too")
        void theUnassignedValueResolvesUnderNeitherResolver() {
            // Tolerating case must not become tolerating unknown values: OPERATOR is real staged data
            // and the right answer is still "not one of ours".
            assertThat(TransactionSource.fromCode(UNASSIGNED_FIXTURE_VALUE)).isEmpty();
            assertThat(TransactionSource.fromCodeIgnoreCase(UNASSIGNED_FIXTURE_VALUE)).isEmpty();
            assertThat(TransactionSource.fromCodeIgnoreCase("operator")).isEmpty();
        }

        @Test
        @DisplayName("no fixture slice matches the interest literal, which only batch writes")
        void theInterestLiteralIsNeverStaged() {
            for (final String row : fixtureRows()) {
                assertThat(TransactionSource.fromCode(sourceSliceOf(row)))
                        .as("the interest marker is generated by CBACT04C, never staged as input")
                        .isNotEqualTo(Optional.of(TransactionSource.SYSTEM));
            }
        }
    }
}
