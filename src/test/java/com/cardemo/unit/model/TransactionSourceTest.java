/*
 * ******************************************************************
 * Program     : TransactionSourceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (pure JVM tier, no container)
 * Function    : Verifies com.cardemo.model.enums.TransactionSource
 *               against the frozen COBOL corpus rather than against
 *               itself: the two literal MOVE sites that justify its
 *               two constants, the PIC X(10) width and byte 23 offset
 *               declared by the two record-layout copybooks, and the
 *               300 staged rows of the daily transaction fixture -
 *               including the fifty rows carrying a value the corpus
 *               never names, which is why the enum is deliberately
 *               NOT the persisted type of the TRAN-SOURCE column.
 * Source      : app/cpy/CVTRA05Y.cpy:L8 @ 7756d89
 * Source      : app/cpy/CVTRA06Y.cpy:L8 @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L484 @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L222 @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L454 @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L428 @ 7756d89
 * Source      : app/data/ASCII/dailytran.txt @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * <h2>What this does</h2>
 *
 * <p>Every claim this enum makes is checkable against a frozen file, so this class checks them there
 * instead of restating the enum's own constants back at it. The two literals are read out of the two
 * {@code MOVE} statements that assign them, the declared width and byte offset are read out of the two
 * copybooks through {@link RecordLayoutCopybook}, and the recognition behaviour is exercised against the
 * 300 rows of the daily transaction fixture loaded through {@link FixtureLoader}. The locators verified
 * here, each confirmed at commit {@code 7756d89}, are:
 *
 * <ul>
 *   <li>{@code app/cbl/CBACT04C.cbl:L484} - {@code MOVE 'System' TO TRAN-SOURCE}, inside paragraph
 *       {@code 1300-B-WRITE-TX} which begins at {@code app/cbl/CBACT04C.cbl:L473}. Constant one.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:L222} - {@code MOVE 'POS TERM' TO TRAN-SOURCE}, on the online bill
 *       payment path that moves the <em>full</em> {@code ACCT-CURR-BAL} at
 *       {@code app/cbl/COBIL00C.cbl:L224}. Constant two.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl:L454} - a pass-through from the screen field {@code TRNSRCI}.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L428} - a pass-through from {@code DALYTRAN-SOURCE}.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy:L8} and {@code app/cpy/CVTRA06Y.cpy:L8} - {@code PIC X(10)} at bytes
 *       23 to 32 of the 350 byte record, right padded with blanks.</li>
 * </ul>
 *
 * <h2>Why the enum is NOT the persisted type of the column</h2>
 *
 * <p>Only two of the four assignment sites carry a literal. The other two copy a value in from outside,
 * so the column's runtime domain is strictly wider than the enum's constant set, and the enum names the
 * two known literals rather than enumerating the column. {@code Transaction.transactionSource} and
 * {@code DailyTransaction.transactionSource} therefore remain {@code String}, and that is asserted here
 * by reflection rather than merely asserted in prose. Typing either field as this enum would reject
 * legitimate staged data on load.
 *
 * <p>The fixture proves the point. Bytes 23 to 32 of {@code app/data/ASCII/dailytran.txt} hold
 * {@code 'POS TERM  '} on 250 rows and {@code 'OPERATOR  '} on the other 50.
 *
 * <p><strong>Finding, severity Medium.</strong> {@code OPERATOR} has no literal {@code MOVE} site
 * anywhere in {@code app/cbl}: it reaches the record purely as data, through the
 * {@code app/cbl/CBTRN02C.cbl:L428} pass-through. Promoting it to a constant would imply the enum is the
 * complete column domain and would invite an enum-typed mapping, which breaks the boundary parity and
 * fixture gates on the 50 rows that carry it. Remediation: leave the constant set at two and let
 * {@link TransactionSource#fromCode(String)} return an empty {@link java.util.Optional} for it, which is
 * what {@link FixtureBehaviour} asserts.
 *
 * <p><strong>Remediation note on surveying the corpus.</strong> {@code grep "TO TRAN-SOURCE" app/cbl/}
 * finds only three of the four sites, because {@code app/cbl/CBTRN02C.cbl:L428} separates the operand
 * from the keyword with four spaces. Use the extended form
 * {@code grep -rnE "TO +TRAN-SOURCE" app/cbl/} instead. {@link CorpusCorrespondence} encodes the
 * whitespace tolerant pattern for the same reason.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This class is bound to the Surefire tier by its path and its name together. It sits under
 * {@code src/test/java/com/cardemo/unit/}, which Failsafe excludes, and it ends in {@code Test.java},
 * which Surefire includes; a class that satisfied neither would be collected by no plugin at all and
 * would report success while never running. Run it with:
 *
 * <pre>
 *   ./mvnw -B -ntp -Ddependency-check.skip=true test -Dtest=TransactionSourceTest
 *   ./mvnw -B -ntp clean verify
 * </pre>
 *
 * <p>Evidence of collection is {@code target/surefire-reports/TEST-com.cardemo.unit.model.
 * TransactionSourceTest.xml} reporting a non-zero test count.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>None is needed, and that is itself the contract. {@link TransactionSource} is a pure value type: it
 * performs no I/O, reads no configuration, holds no mutable state and touches no clock, so this class
 * injects no {@link java.time.Clock} and declares no Mockito strictness setting. There is nothing to
 * stub, so there are no mocks, and consequently no lenient stubbing that Mockito's default strict stubs
 * policy could flag. Had a clock been required, {@code FixedClockProvider.canonicalClock()} in this same
 * package is the sanctioned source; the wall clock, the default locale and the default time zone are
 * never read. Reads of the frozen corpus resolve relative to the repository root because both test
 * plugins pin {@code workingDirectory} to {@code ${project.basedir}}.
 *
 * <h2>Common failure modes</h2>
 *
 * <ul>
 *   <li><strong>The build fails on an unused import.</strong> Test compilation runs under
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, so a single unused import or raw type is
 *       fatal rather than advisory. Delete the import; do not relax the flag.</li>
 *   <li><strong>The fixture cannot be found.</strong> The file is spelled {@code dailytran.txt}, with
 *       "daily" in full, even though the mainframe DD name and dataset are {@code DALYTRAN}. A path
 *       built as {@code dalytran.txt} resolves to nothing. {@link FixtureLoader} owns the correct name,
 *       which is the reason this class loads through it rather than naming the resource itself.</li>
 *   <li><strong>A citation stops resolving.</strong> Every {@code app/...} path named in this file must
 *       exist on disk; filename casing there is load bearing. Correct the citation rather than deleting
 *       the assertion that rests on it.</li>
 *   <li><strong>A count assertion drifts.</strong> The 250 and 50 row split is a property of the frozen
 *       fixture, so a change in it means the fixture or the slice offset moved, never that the
 *       expectation is stale.</li>
 * </ul>
 *
 * <p>Column type detail for the persisted {@code tran_source} column is deliberately not asserted here:
 * schema shape belongs to the migration's own tests, so from this class's perspective it is
 * <em>Not available</em>, and no SQL type is invented to fill the gap.
 */
@DisplayName("TransactionSource against the two COBOL literal sites and the daily transaction fixture")
final class TransactionSourceTest {

    /** The interest calculation program, whose {@code 1300-B-WRITE-TX} paragraph assigns the marker. */
    private static final String INTEREST_PROGRAM = "app/cbl/CBACT04C.cbl";

    /** The bill payment program, which assigns the marker on the online payment path. */
    private static final String BILL_PAYMENT_PROGRAM = "app/cbl/COBIL00C.cbl";

    /** The transaction add program, which passes the screen field {@code TRNSRCI} through unchanged. */
    private static final String TRANSACTION_ADD_PROGRAM = "app/cbl/COTRN02C.cbl";

    /** The posting program, which passes {@code DALYTRAN-SOURCE} through unchanged. */
    private static final String POSTING_PROGRAM = "app/cbl/CBTRN02C.cbl";

    /** The transaction record layout, whose {@code TRAN-SOURCE} field the enum describes. */
    private static final String ONLINE_COPYBOOK = "CVTRA05Y";

    /** The staging record layout, whose {@code DALYTRAN-SOURCE} field must agree byte for byte. */
    private static final String STAGING_COPYBOOK = "CVTRA06Y";

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

    /**
     * Reads one frozen corpus member whole, so a literal is asserted against the source itself
     * rather than against a transcription of it.
     *
     * @param path the repository-relative path of the member.
     * @return its text, decoded as ISO-8859-1 because the corpus is single-byte.
     */
    private static String read(final String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.ISO_8859_1);
        } catch (final IOException failure) {
            throw new AssertionError("cannot read the frozen corpus file " + path, failure);
        }
    }

    /**
     * The literal each program assigns, keyed by program path, taken from the source itself.
     * @return those pairs, one per assigning program.
     */
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

    /**
     * The ten character source marker of every staged row, sliced at the copybook offset.
     *
     * <p>Loaded through {@link FixtureLoader}, which owns the fixture's name and, on the way in, asserts
     * its byte count, its record count, its record width, the absence of any carriage return or non-ASCII
     * byte and the presence of a terminating line feed. Going through it rather than reading the file here
     * avoids duplicating logic that already exists once, and avoids restating the {@code dailytran.txt}
     * versus {@code DALYTRAN} name trap in a second place.
     *
     * <p>The fixture is re-read on each call rather than cached in a static field. That is a deliberate
     * tradeoff: caching would introduce exactly the shared mutable state that a test class should not
     * carry, and would couple every case to whichever one ran first, whereas the cost being avoided is one
     * in-memory pass over 105,300 bytes. Each test below calls this once, so the repetition is bounded by
     * the number of cases that genuinely need the fixture.
     *
     * @return one ten character slice per fixture row, in file order
     */
    private static List<String> sourceSlices() {
        final FixtureLoader.FixtureData fixture =
                FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
        final List<String> slices = new ArrayList<>(fixture.recordCount());
        for (int record = 0; record < fixture.recordCount(); record++) {
            slices.add(fixture.field(record, SOURCE_START, TransactionSource.FIELD_LENGTH));
        }
        return slices;
    }

    /**
     * Returns the declared type of one field of one class, located by name.
     *
     * <p>Reflection rather than a direct reference, because the entities are outside this test's declared
     * dependencies and the only claim being made about them is the type of a single field. Both lookup
     * failures are rethrown wrapped, with the original preserved as the cause, so a renamed field or a
     * moved class reports what was being looked for instead of surfacing a bare reflective error.
     *
     * @param className the fully qualified class name
     * @param fieldName the declared field name
     * @return the field's declared type
     */
    private static Class<?> declaredFieldType(final String className, final String fieldName) {
        final Class<?> owner;
        try {
            owner = Class.forName(className);
        } catch (final ClassNotFoundException absent) {
            throw new AssertionError(
                    "cannot resolve " + className + ", so its field type cannot be checked", absent);
        }
        try {
            return owner.getDeclaredField(fieldName).getType();
        } catch (final NoSuchFieldException absent) {
            throw new AssertionError(
                    className + " declares no field " + fieldName + "; the field carrying TRAN-SOURCE "
                            + "must keep that name or this claim cannot be checked", absent);
        }
    }

    /**
     * Derives a field's one-based start column by summing the widths the copybook declares before it.
     *
     * <p>This is what turns {@link #SOURCE_START} from an assumption into a checked claim. The sum is
     * exact for these two layouts because their only {@code FILLER} is the trailing one, and
     * {@link RecordLayoutCopybook#fieldNames()} omits {@code FILLER} while still counting it toward the
     * record length: a mid-record filler would make this derivation understate the offset, so it is
     * asserted only for the two members named here.
     *
     * @param member     the copybook member name, without extension
     * @param cobolField the field whose start column is wanted
     * @return the one-based start column of the field
     */
    private static int startColumnOf(final String member, final String cobolField) {
        final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(member);
        int startColumn = 1;
        for (final String declared : copybook.fieldNames()) {
            if (declared.equals(cobolField)) {
                return startColumn;
            }
            startColumn += copybook.widthOf(declared);
        }
        throw new AssertionError(member + " does not declare " + cobolField);
    }

    /**
     * Every frozen input the sections below read is present, and the fixture is spelled correctly.
     *
     * <p>Declared at the top level rather than inside a section for two reasons. It is a precondition of
     * all seven sections, not of any one of them, so it belongs to none of them. And a class whose every
     * test sits in a nested section reports {@code tests="0"} on the {@code testsuite} element of its own
     * Surefire report, with the individual cases attributed to the nested display names instead; one
     * top-level case makes the collection evidence unambiguous however the report is read, which matters
     * because a class collected by neither Surefire nor Failsafe fails silently and passes the build.
     *
     * <p>This also turns two documented failure modes into checked ones. The fixture is
     * {@code dailytran.txt} and never {@code dalytran.txt}, and every {@code app/...} path this file cites
     * as evidence has to exist for the claim resting on it to mean anything.
     */
    @Test
    @DisplayName("0. Every frozen input this class reads exists, and the fixture name is not misspelled")
    void everyFrozenInputThisClassReadsIsPresent() {
        for (final String program : List.of(INTEREST_PROGRAM, BILL_PAYMENT_PROGRAM,
                TRANSACTION_ADD_PROGRAM, POSTING_PROGRAM)) {
            assertThat(Path.of(program))
                    .as("%s is cited as evidence by this class, so it must exist", program)
                    .isRegularFile();
        }
        for (final String member : List.of(ONLINE_COPYBOOK, STAGING_COPYBOOK)) {
            assertThat(Path.of("app", "cpy", member + ".cpy"))
                    .as("the %s record layout supplies the width and offset asserted below", member)
                    .isRegularFile();
        }

        final String fixtureName = FixtureLoader.Fixture.DAILY_TRANSACTION.resourceName();
        assertThat(fixtureName)
                .as("the fixture spells \"daily\" in full even though the mainframe DD name is DALYTRAN; "
                        + "a path built as dalytran.txt resolves to nothing")
                .isEqualTo("dailytran.txt")
                .isNotEqualTo("dalytran.txt");
        assertThat(sourceSlices())
                .as("and it loads, at the record count the loader declares")
                .hasSize(FixtureLoader.Fixture.DAILY_TRANSACTION.expectedRecordCount());
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
            for (final String program : List.of(TRANSACTION_ADD_PROGRAM, POSTING_PROGRAM)) {
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

        @ParameterizedTest(name = "no constant named {0}")
        @ValueSource(strings = {"OPERATOR", "ONLINE", "BATCH", "UNKNOWN", "OTHER"})
        @DisplayName("none of the tempting extra names is declared as a constant")
        void noTemptingExtraNameIsDeclared(final String absentName) {
            // Naming these five explicitly rather than relying on the count above, because the count says
            // "two" while this says which two it is not. OPERATOR is the live temptation: it sits on 50
            // fixture rows. ONLINE, BATCH, UNKNOWN and OTHER are the invented categories a reader reaches
            // for when the enum looks incomplete; none appears as a literal anywhere in app/cbl.
            assertThat(TransactionSource.values())
                    .extracting(Enum::name)
                    .as("%s has no literal MOVE site in the corpus, so it must not be a constant",
                            absentName)
                    .doesNotContain(absentName);
        }

        @ParameterizedTest(name = "valueOf({0}) reports the absence")
        @ValueSource(strings = {"OPERATOR", "ONLINE", "BATCH", "UNKNOWN", "OTHER"})
        @DisplayName("valueOf refuses an undeclared name with a message that names it, and no hidden cause")
        void valueOfRefusesAnUndeclaredName(final String absentName) {
            // The two lookups return an empty Optional and never throw, so valueOf is the one entry point
            // on this type that raises. Asserting the type alone would not show that the failure explains
            // itself, so the message is asserted for both the enum and the offending name, and the absence
            // of a cause is asserted rather than left unexamined: the JDK reports the missing constant
            // directly, so there is no wrapped root cause here to preserve or to lose.
            assertThatThrownBy(() -> TransactionSource.valueOf(absentName))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(TransactionSource.class.getName())
                    .hasMessageContaining(absentName)
                    .hasNoCause();
        }

        @Test
        @DisplayName("the two declared names do resolve, so the refusal above is discriminating")
        void theTwoDeclaredNamesStillResolve() {
            assertThat(TransactionSource.valueOf("SYSTEM")).isSameAs(TransactionSource.SYSTEM);
            assertThat(TransactionSource.valueOf("POS_TERMINAL"))
                    .isSameAs(TransactionSource.POS_TERMINAL);
        }

        @Test
        @DisplayName("the declared width is the PIC X(10) both copybooks declare")
        void widthComesFromBothCopybooks() {
            final int online = RecordLayoutCopybook.of(ONLINE_COPYBOOK).widthOf("TRAN-SOURCE");
            final int staged = RecordLayoutCopybook.of(STAGING_COPYBOOK).widthOf("DALYTRAN-SOURCE");

            assertThat(online).as("TRAN-SOURCE is PIC X(10)").isEqualTo(TransactionSource.FIELD_LENGTH);
            assertThat(staged).as("DALYTRAN-SOURCE must agree, or the staging copy would truncate")
                    .isEqualTo(TransactionSource.FIELD_LENGTH);
        }

        @Test
        @DisplayName("the byte 23 offset is derived from the copybooks, not assumed")
        void theOffsetComesFromBothCopybooks() {
            // TRAN-ID X(16) + TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04) occupy bytes 1 to 22, so the source
            // marker starts at 23. Summing the declared widths proves it rather than trusting the constant
            // the fixture slice is taken at: had either copybook shifted a preceding field, every slice in
            // this class would silently read the wrong ten bytes and still be exactly ten bytes wide.
            assertThat(startColumnOf(ONLINE_COPYBOOK, "TRAN-SOURCE"))
                    .as("TRAN-SOURCE begins at byte %d of the 350 byte record", SOURCE_START)
                    .isEqualTo(SOURCE_START);
            assertThat(startColumnOf(STAGING_COPYBOOK, "DALYTRAN-SOURCE"))
                    .as("the staging layout must place its marker at the same offset, or the "
                            + "field to field copy at app/cbl/CBTRN02C.cbl:L428 would shift it")
                    .isEqualTo(SOURCE_START);
        }

        @Test
        @DisplayName("both layouts are 350 bytes, so the offset and width are read in the right frame")
        void bothLayoutsAreTheSameLength() {
            assertThat(RecordLayoutCopybook.of(ONLINE_COPYBOOK).recordLength()).isEqualTo(350);
            assertThat(RecordLayoutCopybook.of(STAGING_COPYBOOK).recordLength()).isEqualTo(350);
        }

        @Test
        @DisplayName("neither source field is numeric, so the marker is text throughout")
        void theSourceFieldIsText() {
            assertThat(RecordLayoutCopybook.of(ONLINE_COPYBOOK).geometry("TRAN-SOURCE").numeric())
                    .isFalse();
            assertThat(RecordLayoutCopybook.of(STAGING_COPYBOOK).geometry("DALYTRAN-SOURCE").numeric())
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

        @ParameterizedTest(name = "{0} renders as [{1}]")
        @CsvSource(quoteCharacter = '"', value = {"SYSTEM,\"System    \"", "POS_TERMINAL,\"POS TERM  \""})
        @DisplayName("the fixed width form is the literal ten character string, spelled out here")
        void fixedWidthFormIsTheSpelledOutTenCharacterString(final TransactionSource source,
                final String expected) {
            // Spelled out rather than recomputed on purpose. Asserting getCode() + " ".repeat(10 - length)
            // would re-derive the very expression the enum uses to build the value, so a fault in that
            // expression would satisfy the assertion. These two strings are read off the PIC X(10) clause
            // instead: 'System' with four trailing blanks, 'POS TERM' with two.
            assertThat(expected).as("the expectation itself must fill PIC X(10)")
                    .hasSize(TransactionSource.FIELD_LENGTH);
            assertThat(source.getFixedWidthValue())
                    .as("%s must render as the ten character external form of its literal", source)
                    .isEqualTo(expected);
            assertThat(source.getFixedWidthValue().length())
                    .as("%s must be exactly ten characters wide", source)
                    .isEqualTo(TransactionSource.FIELD_LENGTH);
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
    @DisplayName("5. Hostile and malformed input is refused by both resolvers alike")
    final class HostileInput {

        /**
         * Both resolvers, applied to the same untrusted value, so the pair cannot drift apart.
         *
         * <p>Written as one method over both entry points rather than as two parallel lists, because the
         * claim being made is about the pair: whatever a caller sends, neither resolver may invent a
         * constant for it. Every case is a value that could plausibly reach a field parser from a badly
         * transcoded record, a hand-edited file or a hostile caller.
         *
         * @param hostile the untrusted text to resolve
         */
        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {
            // Over-long in content, not merely in padding: each carries a non-blank character beyond the
            // PIC X(10) field, so no record could hold it and stripping cannot reduce it to a literal.
            // Extra TRAILING BLANKS are deliberately absent from this list - they strip away by design and
            // resolve correctly, which CaseSensitiveResolution asserts as the intended behaviour.
            "SystemSystem", "POS TERMPOS TERM", "System    x",
            "SystemSystemSystemSystemSystemSystemSystemSystemSystemSystemSystemSystemSystemSystem",
            // Non-ASCII look-alikes: Cyrillic u, full-width S, e-acute, CJK, and a non-breaking space,
            // which String.strip does not remove because it is not whitespace by Character.isWhitespace.
            "S\u0443stem", "\uFF33ystem", "Syst\u00E9m", "\u7CFB\u7EDF", "System\u00A0", "POS\u00A0TERM",
            // Structural noise around or instead of the literal.
            "System,POS TERM", "'System'", "\"POS TERM\"", "System\u0000", "-System", "10"})
        @DisplayName("neither resolver yields a constant for it, and neither throws")
        void neitherResolverAcceptsHostileInput(final String hostile) {
            assertThat(TransactionSource.fromCode(hostile))
                    .as("[%s] is not one of the two literals, so the case sensitive resolver must "
                            + "return empty rather than throwing or defaulting", hostile)
                    .isEmpty();
            assertThat(TransactionSource.fromCodeIgnoreCase(hostile))
                    .as("[%s] differs by more than case, so tolerance of case must not admit it",
                            hostile)
                    .isEmpty();
        }

        @Test
        @DisplayName("an input far longer than the field is refused rather than truncated to a match")
        void anOverLongInputIsNotTruncatedIntoAMatch() {
            // The failure this guards against is a resolver that slices to FIELD_LENGTH before matching,
            // which would turn any string merely starting with a literal into a false positive.
            final String overLong = "System".repeat(2_000);

            assertThat(overLong).hasSizeGreaterThan(TransactionSource.FIELD_LENGTH);
            assertThat(TransactionSource.fromCode(overLong)).isEmpty();
            assertThat(TransactionSource.fromCodeIgnoreCase(overLong)).isEmpty();
        }

        @Test
        @DisplayName("a non-breaking space is not treated as padding, so it cannot pass as the literal")
        void aNonBreakingSpaceIsNotPadding() {
            // PIC X(10) pads with ASCII blanks. U+00A0 is not whitespace to Character.isWhitespace, so
            // strip leaves it in place and the value correctly fails to match.
            final String nonBreaking = "System" + "\u00A0".repeat(4);

            assertThat(nonBreaking).hasSize(TransactionSource.FIELD_LENGTH);
            assertThat(nonBreaking).isNotEqualTo(TransactionSource.SYSTEM.getFixedWidthValue());
            assertThat(TransactionSource.fromCode(nonBreaking)).isEmpty();
        }
    }

    @Nested
    @DisplayName("6. The 300 row daily transaction fixture resolves exactly as the corpus predicts")
    final class FixtureBehaviour {

        @Test
        @DisplayName("the fixture holds 300 rows and only two distinct source markers")
        void theFixtureCensusIsAsExpected() {
            final List<String> slices = sourceSlices();
            final Map<String, Integer> census = new LinkedHashMap<>();
            for (final String slice : slices) {
                census.merge(slice, 1, Integer::sum);
            }

            assertThat(slices).as("dailytran.txt stages 300 records").hasSize(300);
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
            for (final String slice : sourceSlices()) {
                assertThat(slice).hasSize(TransactionSource.FIELD_LENGTH);
            }
        }

        @Test
        @DisplayName("the 250 bill payment rows resolve, and the 50 staged rows do not")
        void resolutionSplitsTheFixtureExactly() {
            int resolved = 0;
            int unresolved = 0;
            for (final String slice : sourceSlices()) {
                final Optional<TransactionSource> source = TransactionSource.fromCode(slice);
                if (source.isPresent()) {
                    assertThat(source).contains(TransactionSource.POS_TERMINAL);
                    resolved++;
                } else {
                    assertThat(slice.strip()).isEqualTo(UNASSIGNED_FIXTURE_VALUE);
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
            for (final String slice : sourceSlices()) {
                assertThat(TransactionSource.fromCode(slice))
                        .as("the interest marker is generated by CBACT04C, never staged as input")
                        .isNotEqualTo(Optional.of(TransactionSource.SYSTEM));
            }
        }
    }

    @Nested
    @DisplayName("7. The enum is not the persisted type of the source column")
    final class PersistedTypeBoundary {

        @ParameterizedTest(name = "{0}.transactionSource is a String")
        @CsvSource({
            "com.cardemo.model.entity.Transaction,transactionSource",
            "com.cardemo.model.entity.DailyTransaction,transactionSource"})
        @DisplayName("both entities keep the column as String, so staged data cannot be rejected on load")
        void bothEntitiesKeepTheColumnAsText(final String entityName, final String fieldName) {
            // The decisive consequence of there being two pass-through assignment sites. The column's
            // runtime domain is wider than the enum's constant set - the fixture proves it with 50 rows of
            // OPERATOR - so typing either field as TransactionSource would reject legitimate input the
            // corpus accepts. Reflected on by name rather than imported, because the entities are outside
            // this test's declared dependencies and only their field type is being claimed here.
            assertThat(declaredFieldType(entityName, fieldName))
                    .as("%s.%s must stay a String; the enum names two literals, it does not "
                            + "enumerate the column", entityName, fieldName)
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the enum carries no persistence or framework annotation of any kind")
        void theEnumCarriesNoPersistenceAnnotation() {
            assertThat(TransactionSource.class.getAnnotations())
                    .as("a pure value type: nothing maps it to a column, so nothing has to be unmapped "
                            + "when the column admits a value the enum does not name")
                    .isEmpty();
        }

        @Test
        @DisplayName("a value with no constant still round-trips as text at the declared width")
        void anUnnamedValueStillRoundTripsAsText() {
            // What "not the persisted type" buys, stated as behaviour: the 50 OPERATOR rows survive a
            // read at full fidelity even though no constant names them.
            final String staged = "OPERATOR  ";

            assertThat(staged).hasSize(TransactionSource.FIELD_LENGTH);
            assertThat(TransactionSource.fromCode(staged)).isEmpty();
            assertThat(sourceSlices()).as("and it is genuinely present in the frozen fixture")
                    .contains(staged);
        }
    }
}
