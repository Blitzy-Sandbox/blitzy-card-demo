/*
 * ******************************************************************
 * Program     : FixtureLoader.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Reads the nine fixed-width ASCII seed fixtures off the
 *               test classpath and exposes their records, their field
 *               slices and their zoned-decimal signed numerics, so that
 *               every unit tier asserts against the byte image of the
 *               legacy datasets rather than against a hand-typed
 *               literal. Strictly read only: it never writes, copies,
 *               moves, edits or creates a file of any kind.
 * Source      : app/data/ASCII/ - acctdata.txt, carddata.txt,
 *               cardxref.txt, custdata.txt, dailytran.txt,
 *               discgrp.txt, tcatbal.txt, trancatg.txt, trantype.txt
 *               (copied byte for byte into src/test/resources by
 *               another agent and read here by classpath name only),
 *               plus app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *               CVCUS01Y.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy,
 *               CVTRA03Y.cpy, CVTRA04Y.cpy and CVTRA06Y.cpy - the PIC
 *               clauses that make every field offset position aware
 *               frozen at commit 7756d89
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the nine frozen fixed-width ASCII fixtures off the test classpath.
 *
 * <h2>What it does</h2>
 *
 * <p>The nine files under {@code app/data/ASCII} are the seed and parity data of the legacy system: fixed
 * width records, no delimiters, trailing padding that is part of the layout, and signed numerics carried as
 * zoned-decimal trailing-sign overpunch. They are copied byte for byte into {@code src/test/resources} and
 * this class reads them from there <strong>by classpath resource name only</strong>. It exposes three things
 * and nothing else: the records of a fixture, a field slice taken at a PIC-derived column, and the decoded
 * {@code BigDecimal} value of a signed numeric field.
 *
 * <p>Reading the byte image matters because the alternative - retyping a record into a Java string literal -
 * proves only that the literal agrees with itself. Anchoring on the fixture means an assertion is anchored in
 * the system of record.
 *
 * <h2>Why this class lives in this package</h2>
 *
 * <p>Placement is deliberate and constrained from two directions. A helper package such as
 * {@code unit/support}, {@code unit/fixtures} or {@code unit/common} is not an approved package, and a class
 * sitting directly at the {@code unit} level is not permitted either, so {@code unit/model} - the tier that
 * owns record and layout concerns - is the correct and only home. The class is {@code public} rather than
 * package private because the sibling tiers {@code unit/validation}, {@code unit/batch} and
 * {@code unit/service} read the same nine files; one shared loader here replaces four near-identical private
 * copies, which is the whole point of the repository convention against duplication.
 *
 * <p>The build path is part of that contract. Surefire 3.5.4 collects {@code *Test} and {@code *Tests} under
 * {@code src/test/java} with the integration and end-to-end trees excluded by path. This class is support, not
 * a test, so it is compiled and consumed but never collected - which is correct. Moving it out of
 * {@code src/test/java} would be silent: it would compile, no plugin would complain, and every test depending
 * on it would stop being able to see it.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Compile and run the unit tier with {@code ./mvnw -B -ntp test}; compile only with
 * {@code ./mvnw -B -ntp test-compile}. Test compilation runs under {@code -Xlint:all -Werror} with
 * {@code failOnWarning}, so a single raw type, unchecked cast or deprecated call in this file fails the whole build
 * rather than printing a warning. An unused import does not: {@code javac} 25 publishes no {@code unused} lint key,
 * so Rule 1 Clause B's prohibition on one is enforced at review. Nothing here needs a container, a Spring context, a
 * database or a network endpoint: it is a pure JVM classpath read, so it runs identically on a developer machine and
 * in CI.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>There is no configuration to set, which is intentional. Three defaults are fixed in code because leaving
 * them to the environment is exactly how this class of loader breaks:
 *
 * <ul>
 *   <li><strong>Charset is always {@code US_ASCII}, never the platform default.</strong> The fixtures are
 *       pure 7-bit ASCII; decoding them under a platform default that happened to be UTF-8 would silently
 *       mangle the overpunch byte {@code 0x7D} on a non-UTF-8 host and shift every subsequent column.</li>
 *   <li><strong>No trimming, ever.</strong> Records are sliced from the raw bytes with their trailing spaces
 *       intact. See the geometry table below for why.</li>
 *   <li><strong>Nothing is cached in mutable static state.</strong> Every method is pure and re-reads its
 *       resource; {@link FixtureData} is an immutable snapshot a caller may hold for as long as it likes.</li>
 *   </ul>
 *
 * <p>That last default is a deliberate tradeoff and worth stating plainly. Re-reading is slightly more work
 * than a shared cache would be, and the reasons it is still the right default are that the nine fixtures total
 * 161,476 bytes - the largest is 105,300 - and are read from {@code target/test-classes} through the operating
 * system's page cache, so a repeat read costs a memory copy rather than disk seek; that a mutable static cache
 * is exactly the global mutable state the code-quality convention prohibits; and that an eagerly populated
 * static cache would turn a missing fixture into an {@code ExceptionInInitializerError} thrown from class
 * initialisation, which is materially harder to diagnose than the named {@code IllegalArgumentException}
 * raised here and would make the absent-resource path untestable. A caller that genuinely needs one read per
 * test class holds a {@link FixtureData} in a field of its own - which keeps the lifetime decision, and any
 * state, with the caller rather than in this class.</p>
 *
 * <h2>Verified geometry</h2>
 *
 * <p>Every fixture satisfies one arithmetic invariant, {@code byteCount == recordCount * (recordWidth + 1)},
 * and {@link #load(Fixture)} refuses to return a fixture that does not. That single check simultaneously
 * detects CRLF conversion, trailing-whitespace trimming, a missing final newline and row loss - four failures
 * that are otherwise invisible until a parity comparison disagrees for no apparent reason.
 *
 * <pre>
 *   fixture         bytes    records  width   max trailing-space run
 *   trantype.txt      427          7     60    0
 *   trancatg.txt     1098         18     60    0
 *   cardxref.txt     1850         50     36    0
 *   tcatbal.txt      2550         50     50    0
 *   discgrp.txt      2601         51     50    0
 *   carddata.txt     7550         50    150   59
 *   acctdata.txt    15050         50    300  188
 *   custdata.txt    25050         50    500  168
 *   dailytran.txt  105300        300    350   46
 * </pre>
 *
 * <p>The trailing-space column is the reason no method here trims. An {@code acctdata} record ends in 188
 * consecutive spaces - a ten-space {@code ACCT-GROUP-ID} followed by a 178-byte {@code FILLER} - so a single
 * {@code strip()} anywhere on the read path would shorten the record, break the invariant above and move
 * every field offset past the cut. {@code custdata} ends in 168, {@code carddata} in 59 and
 * {@code dailytran} in 46.
 *
 * <p>{@code cardxref.txt} is genuinely 36 bytes wide, not 50. {@code CVACT03Y.cpy} declares
 * {@code XREF-CARD-NUM X(16)} plus {@code XREF-CUST-ID 9(09)} plus {@code XREF-ACCT-ID 9(11)} for 36
 * populated bytes inside a 50-byte cluster slot, and the trailing 14-byte {@code FILLER} is simply absent
 * from the fixture. Padding it to 50 to match the catalogued cluster record size would corrupt it.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The fixture is {@code dailytran.txt}, spelled in full.</strong> {@code dalytran.txt} does not
 *       exist. The mainframe DD name and dataset are {@code DALYTRAN}, but the ASCII fixture spells the word
 *       out, so code written from the DD name gets a null resource stream. This is the single most common trap
 *       in this area and it fails as an {@code IllegalArgumentException} naming the resource.</li>
 *   <li><strong>{@code IllegalArgumentException} naming a resource that is not on the classpath</strong>
 *       usually means the resource copy step has not run. Run {@code ./mvnw -B -ntp test-compile}, which
 *       copies {@code src/test/resources} to {@code target/test-classes}, and check the nine files are at the
 *       classpath root.</li>
 *   <li><strong>{@code IllegalStateException} reporting a record width that is one greater than expected, or
 *       a stray {@code 0x0D}</strong>, means the fixture was converted to CRLF - by a checkout on a Windows
 *       host, or by an editor. The fixtures are pinned to LF and to no trailing-whitespace trimming; restore
 *       them from {@code app/data/ASCII} rather than editing them.</li>
 *   <li><strong>{@code IllegalStateException} reporting a malformed overpunch</strong> almost always means the
 *       column offset is wrong rather than the data. Overpunch decoding is position aware: check the offset
 *       against the PIC clause in the copybook named in the banner above.</li>
 *   <li><strong>A build failure with no test failure</strong> is the {@code -Werror} gate, not this class.</li>
 * </ul>
 *
 * <h2>Privacy</h2>
 *
 * <p>{@code custdata.txt} carries synthetic personally identifiable data: the social security number at
 * columns 280-288, two telephone numbers spanning columns 250-279 and a date of birth at columns 309-318.
 * <strong>No method here ever puts field content into an exception message.</strong> Diagnostics name the
 * fixture, the record index, the column offset and the length, and quote at most a single character - and
 * then only when that character is a non-digit, which by construction is a sign symbol rather than payload.
 * Callers must hold to the same rule in their own assertion messages.
 */
public final class FixtureLoader {

    /** Scale of every decoded signed numeric: two decimal places, from the {@code V99} of the PIC clause. */
    public static final int DECIMAL_SCALE = 2;

    /** Width of an account money field, {@code PIC S9(10)V99}, which maps to {@code NUMERIC(12,2)}. */
    public static final int MONEY_FIELD_WIDTH = 12;

    /**
     * Width of a transaction amount or category balance, {@code PIC S9(09)V99}, mapping to
     * {@code NUMERIC(11,2)}. Eleven characters, not twelve: collapsing it onto the account money width
     * shifts every following column.
     */
    public static final int AMOUNT_FIELD_WIDTH = 11;

    /** Width of the disclosure interest rate, {@code PIC S9(04)V99}, which maps to {@code NUMERIC(6,2)}. */
    public static final int RATE_FIELD_WIDTH = 6;

    /** The record terminator. Every fixture uses a bare line feed and ends with one. */
    private static final byte LINE_FEED = 0x0A;

    /** A carriage return, which must never appear: its presence means the fixture was converted to CRLF. */
    private static final byte CARRIAGE_RETURN = 0x0D;

    /** Lowest permitted printable byte, the space that pads every fixed-width field. */
    private static final byte FIRST_PRINTABLE = 0x20;

    /** Highest permitted printable byte. The fixtures reach {@code 0x7D}, the negative-zero overpunch. */
    private static final byte LAST_PRINTABLE = 0x7E;

    /**
     * Positive overpunch characters in value order: <code>&#123;</code> carries +0 and {@code A} through
     * {@code I} carry +1 to +9. The index of a character in this string <em>is</em> its digit value.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * Negative overpunch characters in value order: <code>&#125;</code> carries -0 and {@code J} through
     * {@code R} carry -1 to -9. The index of a character in this string <em>is</em> its digit value.
     */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    private FixtureLoader() {
        throw new AssertionError("test support type, not instantiable");
    }

    /**
     * The nine fixtures, each carrying the census that {@link FixtureLoader#load(Fixture)} enforces.
     *
     * <p>Declaring the expected byte and record counts alongside the resource name turns the fixture set into
     * a self-checking catalogue. A fixture that gains, loses or reshapes a record stops matching its own
     * declared census and is refused at load, rather than quietly feeding short data into a parity assertion.
     *
     * <p>Constants are named for the domain record they carry rather than for the file, because the file names
     * are legacy dataset abbreviations. The mapping is spelled out on each constant so neither name has to be
     * guessed from the other - {@link #DAILY_TRANSACTION} in particular resolves to {@code dailytran.txt} and
     * <strong>not</strong> to {@code dalytran.txt}, which does not exist.
     */
    public enum Fixture {

        /** Account master, {@code app/cpy/CVACT01Y.cpy}, 300-byte records. Five signed money fields. */
        ACCOUNT("acctdata.txt", 15_050, 50, 300),

        /** Card master, {@code app/cpy/CVACT02Y.cpy}, 150-byte records. No signed field at all. */
        CARD("carddata.txt", 7_550, 50, 150),

        /**
         * Card-to-customer cross reference, {@code app/cpy/CVACT03Y.cpy}. Records are 36 bytes, not the
         * catalogued 50: the trailing 14-byte {@code FILLER} is absent from the fixture.
         */
        CARD_XREF("cardxref.txt", 1_850, 50, 36),

        /**
         * Customer master, {@code app/cpy/CVCUS01Y.cpy}, 500-byte records. No signed field, but this is the
         * one fixture carrying synthetic personally identifiable data - see the class privacy note.
         */
        CUSTOMER("custdata.txt", 25_050, 50, 500),

        /**
         * Daily transaction input, {@code app/cpy/CVTRA06Y.cpy}, 350-byte records. The file name spells
         * "daily" in full even though the mainframe DD name is {@code DALYTRAN}.
         */
        DAILY_TRANSACTION("dailytran.txt", 105_300, 300, 350),

        /**
         * Disclosure group interest rates, {@code app/cpy/CVTRA02Y.cpy}, 50-byte records. Fifty-one records,
         * not fifty: three groups of seventeen.
         */
        DISCLOSURE_GROUP("discgrp.txt", 2_601, 51, 50),

        /** Transaction category balances, {@code app/cpy/CVTRA01Y.cpy}, 50-byte records. */
        TRANSACTION_CATEGORY_BALANCE("tcatbal.txt", 2_550, 50, 50),

        /** Transaction category descriptions, {@code app/cpy/CVTRA04Y.cpy}, 60-byte records. */
        TRANSACTION_CATEGORY("trancatg.txt", 1_098, 18, 60),

        /** Transaction type descriptions, {@code app/cpy/CVTRA03Y.cpy}, 60-byte records. */
        TRANSACTION_TYPE("trantype.txt", 427, 7, 60);

        private final String resourceName;
        private final int expectedByteCount;
        private final int expectedRecordCount;
        private final int recordWidth;

        /**
         * Binds one fixture to the geometry its catalogue entry declares.
         *
         * @param resourceName the classpath resource name, which is how the fixture is reached.
         * @param expectedByteCount the total byte count the file must have.
         * @param expectedRecordCount the number of fixed-width records it must hold.
         * @param recordWidth the width of each record in bytes.
         */
        Fixture(final String resourceName, final int expectedByteCount, final int expectedRecordCount,
                final int recordWidth) {
            this.resourceName = resourceName;
            this.expectedByteCount = expectedByteCount;
            this.expectedRecordCount = expectedRecordCount;
            this.recordWidth = recordWidth;
        }

        /**
         * Returns the classpath resource name of this fixture.
         *
         * <p>A bare file name with no directory part: the nine fixtures are copied to the root of
         * {@code target/test-classes}, so this resolves as an absolute classpath resource. It is never a
         * filesystem path and never resolves into {@code app/}.
         *
         * @return the resource name, for example {@code dailytran.txt}
         */
        public String resourceName() {
            return resourceName;
        }

        /**
         * Returns the exact byte count this fixture must have, terminators included.
         *
         * @return the expected size in bytes
         */
        public int expectedByteCount() {
            return expectedByteCount;
        }

        /**
         * Returns the exact number of records this fixture must contain.
         *
         * @return the expected record count
         */
        public int expectedRecordCount() {
            return expectedRecordCount;
        }

        /**
         * Returns the fixed width of one record in characters, excluding its terminator.
         *
         * <p>Trailing padding is included in this width because it is part of the layout, so a record string
         * of this length may end in a long run of spaces.
         *
         * @return the record width in characters
         */
        public int recordWidth() {
            return recordWidth;
        }

        /**
         * Returns the byte count implied by the record census, which is the invariant being asserted.
         *
         * <p>This is {@code expectedRecordCount() * (recordWidth() + 1)} - the plus one being the single line
         * feed that terminates every record, including the last. It equals {@link #expectedByteCount()} for
         * all nine fixtures, and {@link FixtureLoader#load(Fixture)} proves it on every load.
         *
         * @return the byte count the record census implies
         */
        public int impliedByteCount() {
            return expectedRecordCount * (recordWidth + 1);
        }
    }

    /**
     * An immutable, validated snapshot of one fixture.
     *
     * <p>This is the type a caller holds. It is deeply immutable - the record list is copied defensively on
     * construction and {@code List.copyOf} yields an unmodifiable list - so a test may keep one in a
     * {@code static} field of its own class without any risk of a neighbouring test observing a mutation. That
     * is the reason this loader keeps no cache of its own: the caller decides the lifetime, and no mutable
     * state is ever shared between callers.
     *
     * <p>Records are stored exactly as the bytes read, <strong>trailing spaces included</strong>. Nothing is
     * trimmed, stripped or normalised at any point.
     *
     * @param resourceName the classpath resource this snapshot was read from
     * @param recordWidth  the fixed record width in characters, excluding the terminator
     * @param byteCount    the total size of the resource in bytes, terminators included
     * @param records      the records in file order, each exactly {@code recordWidth} characters
     */
    public record FixtureData(String resourceName, int recordWidth, int byteCount, List<String> records) {

        /**
         * Validates and defensively copies.
         *
         * @throws NullPointerException     if {@code resourceName} or {@code records} is {@code null}
         * @throws IllegalArgumentException if {@code resourceName} is blank, if {@code recordWidth} is not
         *                                  positive, or if {@code byteCount} is negative
         */
        public FixtureData {
            Objects.requireNonNull(resourceName, "resourceName");
            Objects.requireNonNull(records, "records");
            if (resourceName.isBlank()) {
                throw new IllegalArgumentException("resourceName must not be blank");
            }
            if (recordWidth <= 0) {
                throw new IllegalArgumentException(
                        "recordWidth must be positive but was " + recordWidth + " for " + resourceName);
            }
            if (byteCount < 0) {
                throw new IllegalArgumentException(
                        "byteCount must not be negative but was " + byteCount + " for " + resourceName);
            }
            records = List.copyOf(records);
        }

        /**
         * Returns the number of records read.
         *
         * @return the record count
         */
        public int recordCount() {
            return records.size();
        }

        /**
         * Returns the byte count the record census implies, which must equal {@link #byteCount()}.
         *
         * @return {@code recordCount() * (recordWidth() + 1)}
         */
        public int impliedByteCount() {
            return records.size() * (recordWidth + 1);
        }

        /**
         * Returns one whole record, trailing spaces intact.
         *
         * @param recordIndex the zero-based record index
         * @return the record, exactly {@link #recordWidth()} characters long
         * @throws IndexOutOfBoundsException if {@code recordIndex} is outside the fixture; the message names
         *                                   the resource and the record count but no record content
         */
        public String recordAt(final int recordIndex) {
            if (recordIndex < 0 || recordIndex >= records.size()) {
                throw new IndexOutOfBoundsException("record index " + recordIndex + " is outside "
                        + resourceName + ", which holds " + records.size() + " records");
            }
            return records.get(recordIndex);
        }

        /**
         * Returns one field, sliced at a COBOL column.
         *
         * <p>Columns are one-based and inclusive of the start, matching the way a PIC clause offset is read
         * off a copybook, so {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at columns 330-332 is
         * {@code field(row, 330, 3)}. The slice is returned verbatim: padding is preserved and no conversion
         * of any kind is applied.
         *
         * <p>Side effects: none. This method neither reads nor writes anything outside the snapshot.
         *
         * @param recordIndex the zero-based record index
         * @param startColumn the one-based first column of the field
         * @param length      the field width in characters
         * @return the raw field text
         * @throws IndexOutOfBoundsException if {@code recordIndex} is outside the fixture
         * @throws IllegalArgumentException  if {@code startColumn} is below one, if {@code length} is not
         *                                   positive, or if the slice would run past the record width; the
         *                                   message reports offsets and widths only, never field content
         */
        public String field(final int recordIndex, final int startColumn, final int length) {
            final String record = recordAt(recordIndex);
            if (startColumn < 1) {
                throw new IllegalArgumentException("startColumn is one-based and must be at least 1, but was "
                        + startColumn + " for " + resourceName);
            }
            if (length <= 0) {
                throw new IllegalArgumentException(
                        "length must be positive but was " + length + " for " + resourceName);
            }
            final int endColumn = startColumn + length - 1;
            if (endColumn > record.length()) {
                throw new IllegalArgumentException("field at columns " + startColumn + "-" + endColumn
                        + " runs past the " + record.length() + "-character record " + recordIndex + " of "
                        + resourceName + "; check the offset against the copybook PIC clause");
            }
            return record.substring(startColumn - 1, endColumn);
        }

        /**
         * Returns one signed numeric field, decoded from zoned-decimal overpunch.
         *
         * <p>Slices the field at the given COBOL column and decodes it through
         * {@link FixtureLoader#decodeZonedDecimal(String, int)}. The {@code width} argument is what makes the
         * decode position aware: it must come from the field's own PIC clause, because the overpunch is the
         * <em>last</em> character of the field and a width that is off by one reads a neighbouring column as
         * the sign. Pass {@link FixtureLoader#MONEY_FIELD_WIDTH}, {@link FixtureLoader#AMOUNT_FIELD_WIDTH} or
         * {@link FixtureLoader#RATE_FIELD_WIDTH} rather than a bare number wherever one of the three standard
         * pictures applies.
         *
         * <p>Side effects: none.
         *
         * @param recordIndex the zero-based record index
         * @param startColumn the one-based first column of the field
         * @param width       the field width in characters, from the PIC clause
         * @return the decoded value, always at scale {@link FixtureLoader#DECIMAL_SCALE}, negative where the
         *         overpunch is negative
         * @throws IndexOutOfBoundsException if {@code recordIndex} is outside the fixture
         * @throws IllegalArgumentException  if the slice is out of bounds
         * @throws IllegalStateException     if the field is not a valid zoned decimal; the message names the
         *                                   resource, the record index and the column, and quotes at most the
         *                                   single offending non-digit character
         */
        public BigDecimal signedDecimal(final int recordIndex, final int startColumn, final int width) {
            final String raw = field(recordIndex, startColumn, width);
            try {
                return decodeZonedDecimal(raw, DECIMAL_SCALE);
            } catch (final IllegalArgumentException malformed) {
                throw new IllegalStateException("record " + recordIndex + " of " + resourceName
                        + " does not hold a valid zoned decimal at columns " + startColumn + "-"
                        + (startColumn + width - 1) + ": " + malformed.getMessage(), malformed);
            }
        }
    }

    /**
     * Loads one fixture and proves it against its declared census.
     *
     * <p>Reads the resource named by the constant, parses it as fixed-width records and then checks three
     * things: that the byte count is exactly what the constant declares, that the record count is exactly what
     * the constant declares, and that {@code byteCount == recordCount * (recordWidth + 1)}. A fixture that has
     * been reflowed, trimmed, converted to CRLF or truncated fails at least one of them.
     *
     * <p>Side effects: none. One read of one classpath resource; nothing is written, cached in shared state,
     * or copied to disk.
     *
     * @param fixture the fixture to load
     * @return an immutable snapshot with every record intact, trailing spaces included
     * @throws NullPointerException     if {@code fixture} is {@code null}
     * @throws IllegalArgumentException if the resource is not on the classpath
     * @throws IllegalStateException    if the fixture is empty, holds a carriage return or a non-ASCII byte,
     *                                  does not end in a line feed, holds a record of the wrong width, or
     *                                  disagrees with its declared census
     * @throws UncheckedIOException     if the resource cannot be read; the underlying cause is preserved
     */
    public static FixtureData load(final Fixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        final FixtureData data = loadResource(fixture.resourceName(), fixture.recordWidth());
        if (data.byteCount() != fixture.expectedByteCount()) {
            throw new IllegalStateException(fixture.resourceName() + " holds " + data.byteCount()
                    + " bytes but " + fixture + " declares " + fixture.expectedByteCount()
                    + "; the fixture has been altered since it was copied from app/data/ASCII");
        }
        if (data.recordCount() != fixture.expectedRecordCount()) {
            throw new IllegalStateException(fixture.resourceName() + " holds " + data.recordCount()
                    + " records but " + fixture + " declares " + fixture.expectedRecordCount());
        }
        if (data.byteCount() != fixture.impliedByteCount()) {
            throw new IllegalStateException(fixture.resourceName() + " breaks the geometry invariant: "
                    + data.byteCount() + " bytes is not " + fixture.expectedRecordCount() + " records of "
                    + fixture.recordWidth() + " characters plus one terminator each, which would be "
                    + fixture.impliedByteCount());
        }
        return data;
    }

    /**
     * Loads all nine fixtures, each proved against its declared census.
     *
     * <p>This is the one-call form of the geometry check: if it returns, every fixture on the classpath is
     * present, is pure 7-bit ASCII, is line-feed terminated, holds uniform-width records and satisfies
     * {@code byteCount == recordCount * (recordWidth + 1)}.
     *
     * <p>Side effects: none.
     *
     * @return an immutable list of nine snapshots, in {@link Fixture} declaration order
     * @throws IllegalArgumentException if any fixture is missing from the classpath
     * @throws IllegalStateException    if any fixture fails validation
     * @throws UncheckedIOException     if any fixture cannot be read
     */
    public static List<FixtureData> loadAll() {
        final Fixture[] fixtures = Fixture.values();
        final List<FixtureData> loaded = new ArrayList<>(fixtures.length);
        for (final Fixture fixture : fixtures) {
            loaded.add(load(fixture));
        }
        return List.copyOf(loaded);
    }

    /**
     * Loads any fixed-width classpath resource, without a declared census to check it against.
     *
     * <p>{@link #load(Fixture)} delegates here and then adds its census checks. It is exposed directly so that
     * the structural validations below can be exercised against a deliberately malformed probe resource: a
     * guard that is only believed to work is not a guard.
     *
     * <p>The name is resolved as an absolute classpath resource and nothing else. It is never treated as a
     * filesystem path, never resolved relative to the working directory and never reaches into
     * {@code app/}, so the frozen corpus cannot be read - or touched - through this method.
     *
     * <p>Side effects: none.
     *
     * @param resourceName the classpath resource name, with no leading slash, for example
     *                     {@code dailytran.txt}
     * @param recordWidth  the expected fixed record width in characters, excluding the terminator
     * @return an immutable snapshot
     * @throws NullPointerException     if {@code resourceName} is {@code null}
     * @throws IllegalArgumentException if {@code resourceName} is blank, if {@code recordWidth} is not
     *                                 positive, or if no such classpath resource exists
     * @throws IllegalStateException    if the resource is empty, holds a carriage return or a byte outside
     *                                 printable 7-bit ASCII, does not end in a line feed, or holds a record
     *                                 whose width is not {@code recordWidth}
     * @throws UncheckedIOException     if the resource cannot be read; the cause is preserved
     */
    public static FixtureData loadResource(final String resourceName, final int recordWidth) {
        Objects.requireNonNull(resourceName, "resourceName");
        if (resourceName.isBlank()) {
            throw new IllegalArgumentException("resourceName must not be blank");
        }
        if (recordWidth <= 0) {
            throw new IllegalArgumentException(
                    "recordWidth must be positive but was " + recordWidth + " for " + resourceName);
        }
        final byte[] content = readClasspathBytes(resourceName);
        validateBytes(resourceName, content);
        final List<String> records = splitRecords(resourceName, content, recordWidth);
        final FixtureData data = new FixtureData(resourceName, recordWidth, content.length, records);
        if (data.byteCount() != data.impliedByteCount()) {
            throw new IllegalStateException(resourceName + " breaks the geometry invariant: " + data.byteCount()
                    + " bytes is not " + data.recordCount() + " records of " + recordWidth
                    + " characters plus one terminator each, which would be " + data.impliedByteCount()
                    + "; this indicates a defect in this parser rather than in the fixture");
        }
        return data;
    }

    /**
     * Decodes a zoned-decimal trailing-sign overpunch field into an exact decimal.
     *
     * <p>A signed COBOL numeric written to a text file carries its sign in the final character, overpunched
     * onto the last digit: <code>&#123;</code> is +0 and {@code A} through {@code I} are +1 to +9;
     * <code>&#125;</code> is -0 and {@code J} through {@code R} are -1 to -9. A plain digit in that position is
     * read as unsigned and therefore positive. So {@code "00000001940&#123;"} is +194.00 and {@code "0000009190&#125;"}
     * is -919.00.
     *
     * <p><strong>Decoding is position aware and must stay that way.</strong> This method decodes a field that
     * the caller has already sliced at its PIC-derived offset; it must never be applied to a whole record, and
     * the letters it decodes must never be substituted globally. {@code A} through {@code R} occur legitimately
     * as text throughout the corpus - inside customer names, embossed card names, transaction descriptions,
     * merchant names and cities, and in {@code ACCT-ADDR-ZIP}, which is the literal {@code A000000000} on every
     * account record. A blanket replacement would corrupt all of them. Two fixtures, {@code carddata.txt} and
     * {@code custdata.txt}, contain no signed field at all, so no overpunch decode belongs anywhere near them.
     *
     * <p>The result is always a {@code BigDecimal} and never a {@code float} or {@code double}: binary floating
     * point cannot represent a decimal money value exactly, and the migration forbids it in any financial
     * field. Compare results with {@code compareTo} rather than {@code equals}, because
     * {@code BigDecimal.equals} also compares scale. The decode itself is exact - it shifts a decimal point and
     * never divides - so the closing {@code HALF_EVEN} rescale never actually rounds; it is there to pin the
     * returned scale so that every value from this method is directly comparable to every other.
     *
     * <p>Side effects: none. This is a pure function of its arguments.
     *
     * @param field the raw field text, already sliced to its PIC-declared width, sign character last
     * @param scale the number of implied decimal places, from the {@code V} of the PIC clause; two for every
     *              signed field in these nine fixtures
     * @return the exact signed value at the requested scale
     * @throws NullPointerException     if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is empty, if {@code scale} is negative, if any
     *                                 character before the sign position is not a digit, or if the sign
     *                                 position holds neither a digit nor a recognised overpunch. The message
     *                                 reports a position rather than content for a bad digit, and quotes only
     *                                 the single offending character for a bad sign - which is structurally a
     *                                 sign symbol, never field payload
     */
    public static BigDecimal decodeZonedDecimal(final String field, final int scale) {
        Objects.requireNonNull(field, "field");
        if (field.isEmpty()) {
            throw new IllegalArgumentException("a zoned decimal field must hold at least the sign position");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("scale must not be negative but was " + scale);
        }
        final int signIndex = field.length() - 1;
        for (int index = 0; index < signIndex; index++) {
            final char digit = field.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("every character before the sign position must be a digit, "
                        + "but position " + (index + 1) + " of this " + field.length()
                        + "-character field is not; the field content is withheld because it may be "
                        + "personally identifiable, so check the offset against the copybook PIC clause");
            }
        }
        final char sign = field.charAt(signIndex);
        final int lowOrderDigit;
        final boolean negative;
        if (sign >= '0' && sign <= '9') {
            lowOrderDigit = sign - '0';
            negative = false;
        } else {
            final int positive = POSITIVE_OVERPUNCH.indexOf(sign);
            final int minus = NEGATIVE_OVERPUNCH.indexOf(sign);
            if (positive >= 0) {
                lowOrderDigit = positive;
                negative = false;
            } else if (minus >= 0) {
                lowOrderDigit = minus;
                negative = true;
            } else {
                throw new IllegalArgumentException("'" + sign + "' at the sign position is not a zoned-decimal "
                        + "overpunch; expected a digit, one of " + POSITIVE_OVERPUNCH + " for a positive value "
                        + "or one of " + NEGATIVE_OVERPUNCH + " for a negative one");
            }
        }
        final BigDecimal magnitude =
                new BigDecimal(field.substring(0, signIndex) + lowOrderDigit).movePointLeft(scale);
        final BigDecimal value = negative ? magnitude.negate() : magnitude;
        return value.setScale(scale, RoundingMode.HALF_EVEN);
    }

    /**
     * Reads a classpath resource in full, by name only.
     *
     * @param resourceName the resource name without a leading slash
     * @return the raw bytes
     * @throws IllegalArgumentException if no such resource is on the classpath
     * @throws UncheckedIOException     if reading fails, wrapping and preserving the cause
     */
    private static byte[] readClasspathBytes(final String resourceName) {
        try (InputStream stream = FixtureLoader.class.getResourceAsStream("/" + resourceName)) {
            if (stream == null) {
                throw new IllegalArgumentException("no classpath resource named '" + resourceName
                        + "'. The nine fixtures are copied from src/test/resources to the root of "
                        + "target/test-classes by the resources plugin, so run test-compile first. Note that "
                        + "the daily transaction fixture is dailytran.txt, spelled in full - dalytran.txt "
                        + "does not exist even though the legacy DD name is DALYTRAN");
            }
            return stream.readAllBytes();
        } catch (final IOException failure) {
            throw new UncheckedIOException("cannot read classpath resource '" + resourceName + "'", failure);
        }
    }

    /**
     * Rejects any byte that would mean the fixture is no longer a byte-exact copy.
     *
     * <p>Three separate corruptions are caught here, each with the byte offset that proves it: a carriage
     * return, which means CRLF conversion; a byte outside printable 7-bit ASCII, which means a charset
     * conversion; and a final byte that is not a line feed, which means the terminating newline was dropped.
     *
     * @param resourceName the resource being validated, for diagnostics
     * @param content      the raw bytes
     * @throws IllegalStateException if the content is empty or holds any of the three defects
     */
    private static void validateBytes(final String resourceName, final byte[] content) {
        if (content.length == 0) {
            throw new IllegalStateException(resourceName + " is empty, so it holds no records at all");
        }
        for (int offset = 0; offset < content.length; offset++) {
            final byte value = content[offset];
            if (value == CARRIAGE_RETURN) {
                throw new IllegalStateException(resourceName + " holds a carriage return at byte offset "
                        + offset + ", so it has been converted to CRLF. Every fixture is line-feed terminated; "
                        + "restore it from app/data/ASCII rather than editing it");
            }
            if (value != LINE_FEED && (value < FIRST_PRINTABLE || value > LAST_PRINTABLE)) {
                throw new IllegalStateException(resourceName + " holds byte 0x"
                        + String.format("%02X", value) + " at offset " + offset
                        + ", which is outside printable 7-bit ASCII. The fixtures are pure ASCII, so this "
                        + "indicates a charset conversion on the file");
            }
        }
        if (content[content.length - 1] != LINE_FEED) {
            throw new IllegalStateException(resourceName + " does not end in a line feed, so its final record "
                    + "is unterminated and the geometry invariant cannot hold");
        }
    }

    /**
     * Splits validated content into fixed-width records, preserving every trailing space.
     *
     * <p>Deliberately does not use a reader's line-reading method: that would silently tolerate a missing
     * final terminator and strip the terminators before they could be counted, which is precisely what the
     * geometry invariant exists to detect. Splitting the decoded text at each line feed instead makes both a
     * short record and a stray blank line fail loudly.
     *
     * @param resourceName the resource being split, for diagnostics
     * @param content      the validated raw bytes, known to end in a line feed
     * @param recordWidth  the width every record must have
     * @return the records in file order
     * @throws IllegalStateException if any record is not exactly {@code recordWidth} characters wide
     */
    private static List<String> splitRecords(final String resourceName, final byte[] content,
            final int recordWidth) {
        final String text = new String(content, StandardCharsets.US_ASCII);
        final List<String> records = new ArrayList<>(content.length / (recordWidth + 1) + 1);
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '\n') {
                continue;
            }
            final String candidate = text.substring(start, index);
            if (candidate.length() != recordWidth) {
                throw new IllegalStateException("record " + records.size() + " of " + resourceName + " is "
                        + candidate.length() + " characters wide but every record must be exactly "
                        + recordWidth + ". Trailing padding is part of the layout, so a short record usually "
                        + "means trailing whitespace was trimmed; a zero-width one means a blank line");
            }
            records.add(candidate);
            start = index + 1;
        }
        return records;
    }
}
