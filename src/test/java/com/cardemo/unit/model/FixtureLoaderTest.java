/*
 * ******************************************************************
 * Program     : FixtureLoaderTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Executes every guard FixtureLoader declares, so that
 *               the canonical fixture reader is proved rather than
 *               believed. Covers all nine frozen ASCII fixtures, the
 *               census and geometry invariants, the byte-level
 *               corruption guards (carriage return, non-ASCII byte,
 *               missing terminal line feed, short record, blank line,
 *               empty resource), one-based column slicing with padding
 *               preserved, and the complete positive and negative
 *               zoned-decimal overpunch alphabets together with the
 *               position awareness that makes the decode correct.
 * Source      : app/data/ASCII/ - acctdata.txt, carddata.txt,
 *               cardxref.txt, custdata.txt, dailytran.txt,
 *               discgrp.txt, tcatbal.txt, trancatg.txt, trantype.txt
 *               @ 7756d89
 * Source      : app/cpy/CVACT01Y.cpy (ACCT-CURR-BAL S9(10)V99 at
 *               columns 13-24), app/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL
 *               S9(09)V99 at columns 18-28), app/cpy/CVTRA02Y.cpy
 *               (DIS-INT-RATE S9(04)V99 at columns 17-22),
 *               app/cpy/CVTRA06Y.cpy (DALYTRAN-AMT S9(09)V99 at
 *               columns 133-143) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:547-552 (a negative amount is
 *               added to the cycle DEBIT accumulator, which is why the
 *               fixture's negative signs must never be normalised)
 *               @ 7756d89
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cardemo.unit.model.FixtureLoader.Fixture;
import com.cardemo.unit.model.FixtureLoader.FixtureData;

/**
 * Proves {@link FixtureLoader}, the canonical reader for the nine frozen fixed-width ASCII fixtures.
 *
 * <h2>What it does</h2>
 *
 * <p>{@link FixtureLoader} is the one place in the test tree that knows how a legacy dataset is shaped: its
 * census, its byte-level invariants, its one-based column geometry and its zoned-decimal trailing-sign
 * overpunch. Every one of those is a guard, and a guard that never runs is not a guard - it is an
 * assumption with a method name. This class executes all of them, including the six byte-level corruptions
 * that are impossible to provoke with a real fixture and therefore need a deliberately malformed probe.
 *
 * <p>Two consumers depend on the guards being correct rather than merely present:
 * {@code com.cardemo.integration.repository.AbstractRepositoryIntegrationTest} and
 * {@code com.cardemo.integration.batch.AbstractBatchIntegrationTest} both resolve {@code readFixture}
 * through this loader, so a defect here would surface as a wrong parity assertion two tiers away rather
 * than as a failure at the point of the defect.
 *
 * <h2>How the malformed probes work, and why they are safe</h2>
 *
 * <p>{@link FixtureLoader#loadResource(String, int)} resolves a name as an absolute classpath resource, so
 * a corruption can only be injected by putting a genuinely corrupt resource on the classpath. The probes
 * are therefore written into the <em>build output</em> directory that already backs the test classpath -
 * {@code target/test-classes} - under a name prefix no real resource uses, and are deleted again in
 * {@link #removeProbeResources()}. Nothing under {@code src/} is written, nothing under {@code app/} is
 * read or touched, and no probe survives the run, so there is nothing for a later build to inherit and
 * nothing for a commit to pick up. This is the exercise {@code FixtureLoader}'s own documentation
 * anticipates when it explains why {@code loadResource} is exposed at all.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp test} runs it with the rest of the unit tier; {@code ./mvnw -B -ntp -Dtest=
 * FixtureLoaderTest test} runs it alone; {@code ./mvnw -B -ntp test-compile} is the fastest check that it
 * still satisfies {@code -Xlint:all -Werror} at release 25. It is collected by Surefire because it lives
 * under {@code .../unit/...} and ends in {@code Test}; it needs no Docker socket, no database and no
 * network.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>None. This class reads no system property, no environment variable and no configuration file. The
 * nine fixtures are flat direct children of {@code src/test/resources}, so they resolve from the classpath
 * root by bare name. The only literals it holds are the census figures and the column offsets, and both
 * are quoted from the frozen corpus in the banner above.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>A census assertion fails.</em> A fixture was edited, reflowed, trimmed or converted to CRLF.
 *       Restore it byte for byte from {@code app/data/ASCII}; never adjust the expected figure, because the
 *       figure is the contract.</li>
 *   <li><em>A probe test fails with "cannot locate the test classpath output directory".</em> The tests
 *       were launched with a classpath whose root is not a directory, for example from inside a shaded
 *       jar. Run them through Maven.</li>
 *   <li><em>A decode assertion is off by a factor of ten, or has the wrong sign.</em> The field width used
 *       for the slice does not match the PIC clause. The overpunch is the <em>last</em> character of the
 *       field, so a width that is off by one reads a neighbouring column as the sign; see
 *       {@link PositionAwareness}.</li>
 *   </ul>
 *
 * <h2>Privacy</h2>
 *
 * <p>{@code custdata.txt} carries synthetic personally identifiable data and {@code carddata.txt} carries
 * embossed names. Clause D of the project's single rule names tests explicitly, so no assertion here
 * quotes a customer name, address, telephone number, social security number or card holder name, and
 * {@link Diagnostics} asserts that the loader's own failure messages do not either.
 */
@DisplayName("FixtureLoader: the canonical reader for the nine frozen app/data/ASCII fixtures")
class FixtureLoaderTest {

    /** Name prefix every deliberately malformed probe resource carries, so cleanup cannot over-reach. */
    private static final String PROBE_PREFIX = "fixtureloader-probe-";

    /** Every positive overpunch character, in the order that maps to low-order digits zero through nine. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Every negative overpunch character, in the order that maps to low-order digits zero through nine. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** One-based first column of {@code ACCT-CURR-BAL}, {@code app/cpy/CVACT01Y.cpy}. */
    private static final int ACCT_CURR_BAL_COLUMN = 13;

    /** One-based first column of {@code ACCT-CREDIT-LIMIT}, {@code app/cpy/CVACT01Y.cpy}. */
    private static final int ACCT_CREDIT_LIMIT_COLUMN = 25;

    /** One-based first column of {@code ACCT-CASH-CREDIT-LIMIT}, {@code app/cpy/CVACT01Y.cpy}. */
    private static final int ACCT_CASH_CREDIT_LIMIT_COLUMN = 37;

    /** One-based first column of {@code ACCT-ADDR-ZIP}, {@code app/cpy/CVACT01Y.cpy}. */
    private static final int ACCT_ADDR_ZIP_COLUMN = 103;

    /** Width of {@code ACCT-ADDR-ZIP PIC X(10)}. */
    private static final int ACCT_ADDR_ZIP_WIDTH = 10;

    /** One-based first column of {@code TRAN-CAT-BAL}, {@code app/cpy/CVTRA01Y.cpy}. */
    private static final int TCATBAL_BALANCE_COLUMN = 18;

    /** One-based first column of {@code DIS-INT-RATE}, {@code app/cpy/CVTRA02Y.cpy}. */
    private static final int DISCGRP_RATE_COLUMN = 17;

    /** Zero-based index of the first {@code DEFAULT} disclosure group row, {@code discgrp.txt:L18}. */
    private static final int DISCGRP_DEFAULT_ROW = 17;

    /** One-based first column of {@code DALYTRAN-AMT}, {@code app/cpy/CVTRA06Y.cpy}. */
    private static final int DALYTRAN_AMOUNT_COLUMN = 133;

    /** Directory backing the test classpath root, resolved once so a probe can be published to it. */
    private static Path classpathRoot;

    /** Every probe resource this class published, so {@link #removeProbeResources()} can delete each one. */
    private static final List<Path> PUBLISHED_PROBES = new ArrayList<>();

    /**
     * Resolves the directory that backs the test classpath root, once, before any probe is published.
     *
     * @throws IllegalStateException if the classpath root is not a readable directory, which means the
     *                               suite was launched with a packaged classpath and the probe mechanism
     *                               cannot work; stating that is better than skipping the guards silently
     */
    @BeforeAll
    static void resolveClasspathRoot() {
        URL root = FixtureLoaderTest.class.getResource("/");
        if (root == null) {
            throw new IllegalStateException("cannot locate the test classpath output directory: the "
                    + "classpath root is not addressable, so a deliberately malformed probe resource "
                    + "cannot be published. Run the suite through Maven.");
        }
        try {
            classpathRoot = Path.of(root.toURI());
        } catch (final URISyntaxException malformed) {
            throw new IllegalStateException("cannot locate the test classpath output directory", malformed);
        }
        if (!Files.isDirectory(classpathRoot)) {
            throw new IllegalStateException("cannot locate the test classpath output directory: " + root
                    + " is not a directory");
        }
    }

    /**
     * Deletes every probe resource published during the run, leaving the build output as it was found.
     *
     * @throws IOException if a probe cannot be deleted, which is reported rather than swallowed so that a
     *                     stale probe cannot be inherited by a later build
     */
    @AfterAll
    static void removeProbeResources() throws IOException {
        for (final Path probe : PUBLISHED_PROBES) {
            Files.deleteIfExists(probe);
        }
        PUBLISHED_PROBES.clear();
    }

    /**
     * Publishes a byte-exact probe resource onto the test classpath and returns its bare resource name.
     *
     * @param suffix  a name suffix unique to the calling test
     * @param content the exact bytes to publish, corruption included
     * @return the bare classpath resource name the probe is addressable by
     */
    private static String publishProbe(final String suffix, final byte[] content) {
        final String resourceName = PROBE_PREFIX + suffix + ".txt";
        final Path probe = classpathRoot.resolve(resourceName);
        try {
            Files.write(probe, content);
        } catch (final IOException failure) {
            throw new UncheckedIOException("cannot publish the probe resource " + resourceName, failure);
        }
        PUBLISHED_PROBES.add(probe);
        return resourceName;
    }

    /**
     * Publishes a probe from US-ASCII text, which is the common case.
     *
     * @param suffix a name suffix unique to the calling test
     * @param text   the exact text to publish, terminators included
     * @return the bare classpath resource name the probe is addressable by
     */
    private static String publishTextProbe(final String suffix, final String text) {
        return publishProbe(suffix, text.getBytes(StandardCharsets.US_ASCII));
    }

    @Nested
    @DisplayName("1. The nine-fixture census - the figures are the contract, not an estimate")
    class Census {

        @Test
        @DisplayName("there are exactly nine fixtures and no tenth")
        void thereAreExactlyNineFixtures() {
            assertThat(Fixture.values())
                    .as("app/data/ASCII holds nine ASCII fixtures; a tenth constant would mean an invented "
                            + "dataset and a ninth-and-a-half would mean one was dropped")
                    .hasSize(9);
        }

        @Test
        @DisplayName("every resource name is a bare classpath name with no directory segment")
        void everyResourceNameIsABareClasspathName() {
            for (final Fixture fixture : Fixture.values()) {
                assertThat(fixture.resourceName())
                        .as("%s must resolve from the classpath root", fixture)
                        .doesNotContain("/")
                        .doesNotContain("\\")
                        .doesNotStartWith(".")
                        .endsWith(".txt");
            }
        }

        @Test
        @DisplayName("the daily transaction fixture spells 'daily' in full, not the DALYTRAN DD name")
        void theDailyTransactionFixtureSpellsDailyInFull() {
            // The canonical mistake: the mainframe DD name and dataset are DALYTRAN, so dalytran.txt is the
            // natural guess. It compiles cleanly and fails only when opened.
            assertThat(Fixture.DAILY_TRANSACTION.resourceName()).isEqualTo("dailytran.txt");
            assertThat(Fixture.DAILY_TRANSACTION.resourceName()).isNotEqualTo("dalytran.txt");
        }

        @ParameterizedTest(name = "{0} declares {1} bytes, {2} records of {3} characters")
        @CsvSource({
            "ACCOUNT,                      15050, 50,  300",
            "CARD,                          7550, 50,  150",
            "CARD_XREF,                     1850, 50,   36",
            "CUSTOMER,                     25050, 50,  500",
            "DAILY_TRANSACTION,           105300, 300, 350",
            "DISCLOSURE_GROUP,              2601, 51,   50",
            "TRANSACTION_CATEGORY_BALANCE,  2550, 50,   50",
            "TRANSACTION_CATEGORY,          1098, 18,   60",
            "TRANSACTION_TYPE,               427,  7,   60",
        })
        @DisplayName("each constant declares the census the frozen dataset actually has")
        void eachConstantDeclaresItsCensus(final Fixture fixture, final int bytes, final int records,
                final int width) {
            assertThat(fixture.expectedByteCount()).isEqualTo(bytes);
            assertThat(fixture.expectedRecordCount()).isEqualTo(records);
            assertThat(fixture.recordWidth()).isEqualTo(width);
        }

        @ParameterizedTest
        @EnumSource(Fixture.class)
        @DisplayName("the declared byte count equals records x (width + 1) for every fixture")
        void theDeclaredGeometryIsSelfConsistent(final Fixture fixture) {
            assertThat(fixture.impliedByteCount())
                    .as("%s: one line feed terminates every record, including the last", fixture)
                    .isEqualTo(fixture.expectedRecordCount() * (fixture.recordWidth() + 1))
                    .isEqualTo(fixture.expectedByteCount());
        }

        @Test
        @DisplayName("the cross reference fixture is 36 bytes wide, not the catalogued cluster's 50")
        void theCrossReferenceFixtureIsThirtySixBytesWide() {
            // 16 + 9 + 11 = 36 populated bytes. The cluster's record size is 50; the trailing 14-byte FILLER
            // is absent from the ASCII fixture, and asserting 50 here would fail on correct data.
            assertThat(Fixture.CARD_XREF.recordWidth()).isEqualTo(36);
        }

        @Test
        @DisplayName("the disclosure group fixture holds fifty-one rows, not fifty")
        void theDisclosureGroupFixtureHoldsFiftyOneRows() {
            assertThat(Fixture.DISCLOSURE_GROUP.expectedRecordCount()).isEqualTo(51);
        }
    }

    @Nested
    @DisplayName("2. load and loadAll - every fixture proved against its own census on every read")
    class Loading {

        @ParameterizedTest
        @EnumSource(Fixture.class)
        @DisplayName("load proves the byte count, the record count and the geometry invariant")
        void loadProvesTheCensus(final Fixture fixture) {
            final FixtureData data = FixtureLoader.load(fixture);

            assertThat(data.resourceName()).isEqualTo(fixture.resourceName());
            assertThat(data.recordWidth()).isEqualTo(fixture.recordWidth());
            assertThat(data.byteCount()).isEqualTo(fixture.expectedByteCount());
            assertThat(data.recordCount()).isEqualTo(fixture.expectedRecordCount());
            assertThat(data.impliedByteCount()).isEqualTo(data.byteCount());
        }

        @ParameterizedTest
        @EnumSource(Fixture.class)
        @DisplayName("every record is exactly the declared width, trailing padding included")
        void everyRecordIsExactlyTheDeclaredWidth(final Fixture fixture) {
            final FixtureData data = FixtureLoader.load(fixture);

            for (int index = 0; index < data.recordCount(); index++) {
                assertThat(data.recordAt(index).length())
                        .as("record %d of %s", index, fixture.resourceName())
                        .isEqualTo(fixture.recordWidth());
            }
        }

        @ParameterizedTest
        @EnumSource(Fixture.class)
        @DisplayName("no record carries a carriage return or a byte outside printable 7-bit ASCII")
        void noRecordCarriesACarriageReturnOrANonAsciiByte(final Fixture fixture) {
            final FixtureData data = FixtureLoader.load(fixture);

            for (final String record : data.records()) {
                for (int position = 0; position < record.length(); position++) {
                    final char character = record.charAt(position);
                    assertThat(character).isNotEqualTo('\r').isNotEqualTo('\n');
                    assertThat((int) character).isBetween(0x20, 0x7E);
                }
            }
        }

        @Test
        @DisplayName("loadAll returns nine snapshots in declaration order")
        void loadAllReturnsNineSnapshotsInDeclarationOrder() {
            final List<FixtureData> all = FixtureLoader.loadAll();

            assertThat(all).hasSize(9);
            for (int index = 0; index < all.size(); index++) {
                assertThat(all.get(index).resourceName())
                        .isEqualTo(Fixture.values()[index].resourceName());
            }
        }

        @Test
        @DisplayName("trailing spaces are data: the account group identifier is ten blanks, not absent")
        void trailingSpacesAreData() {
            // Trimming would truncate acctdata from 300 columns to 112 and every position-based read after
            // the first short row would then be wrong. ACCT-GROUP-ID at columns 113-122 is blank on row one,
            // which is only observable if nothing was trimmed.
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThat(accounts.field(0, 113, 10)).isEqualTo("          ");
            assertThat(accounts.recordAt(0)).endsWith(" ");
            assertThat(accounts.recordAt(0)).hasSize(300);
        }

        @Test
        @DisplayName("two loads return equal but independent snapshots, so nothing is cached or shared")
        void twoLoadsReturnEqualButIndependentSnapshots() {
            final FixtureData first = FixtureLoader.load(Fixture.TRANSACTION_TYPE);
            final FixtureData second = FixtureLoader.load(Fixture.TRANSACTION_TYPE);

            assertThat(first).isEqualTo(second);
            assertThat(first).isNotSameAs(second);
            assertThat(first.records()).isNotSameAs(second.records());
        }

        @Test
        @DisplayName("a null fixture is refused rather than treated as a missing resource")
        void aNullFixtureIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixtureLoader.load(null));
        }
    }

    @Nested
    @DisplayName("3. loadResource - the argument guards and the absent-resource diagnostic")
    class ResourceArgumentGuards {

        @Test
        @DisplayName("a null resource name is refused")
        void aNullResourceNameIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(null, 10));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("a blank resource name is refused")
        void aBlankResourceNameIsRefused(final String blank) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(blank, 10))
                    .withMessageContaining("must not be blank");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -350})
        @DisplayName("a non-positive record width is refused, naming the width and the resource")
        void aNonPositiveRecordWidthIsRefused(final int width) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource("dailytran.txt", width))
                    .withMessageContaining("recordWidth must be positive")
                    .withMessageContaining("dailytran.txt");
        }

        @Test
        @DisplayName("an absent resource names the resource and the dailytran spelling trap")
        void anAbsentResourceNamesTheResourceAndTheSpellingTrap() {
            final IllegalArgumentException absent = catchThrowableOfType(IllegalArgumentException.class,
                    () -> FixtureLoader.loadResource("dalytran.txt", 350));

            assertThat(absent).isNotNull();
            assertThat(absent).hasMessageContaining("dalytran.txt")
                    .hasMessageContaining("dailytran.txt");
        }

        @Test
        @DisplayName("a real fixture loads through loadResource with the same result as load")
        void aRealFixtureLoadsThroughLoadResource() {
            final FixtureData viaConstant = FixtureLoader.load(Fixture.TRANSACTION_CATEGORY);
            final FixtureData viaName = FixtureLoader.loadResource("trancatg.txt", 60);

            assertThat(viaName).isEqualTo(viaConstant);
        }
    }

    @Nested
    @DisplayName("4. The byte-level corruption guards, each proved against a malformed probe")
    class CorruptionGuards {

        @Test
        @DisplayName("an empty resource is refused: it holds no records at all")
        void anEmptyResourceIsRefused() {
            final String probe = publishProbe("empty", new byte[0]);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(probe, 10))
                    .withMessageContaining(probe)
                    .withMessageContaining("is empty");
        }

        @Test
        @DisplayName("a carriage return is refused and its byte offset is reported")
        void aCarriageReturnIsRefused() {
            // CRLF conversion is the corruption a text-mode checkout or an editor introduces, and it moves
            // every subsequent byte offset by one, so a position-based read after the first record is wrong.
            final String probe = publishTextProbe("carriage-return", "0123456789\r\n");

            final IllegalStateException refused = catchThrowableOfType(IllegalStateException.class,
                    () -> FixtureLoader.loadResource(probe, 10));

            assertThat(refused).isNotNull();
            assertThat(refused).hasMessageContaining("carriage return")
                    .hasMessageContaining("byte offset 10")
                    .hasMessageContaining("app/data/ASCII");
        }

        @Test
        @DisplayName("a byte outside printable 7-bit ASCII is refused with its hexadecimal value")
        void aNonAsciiByteIsRefused() {
            final byte[] content = {'0', '1', '2', '3', (byte) 0x80, '5', '6', '7', '8', '9', '\n'};
            final String probe = publishProbe("non-ascii", content);

            final IllegalStateException refused = catchThrowableOfType(IllegalStateException.class,
                    () -> FixtureLoader.loadResource(probe, 10));

            assertThat(refused).isNotNull();
            assertThat(refused).hasMessageContaining("0x80")
                    .hasMessageContaining("offset 4")
                    .hasMessageContaining("printable 7-bit ASCII");
        }

        @Test
        @DisplayName("a control byte below the space is refused too, not only a high byte")
        void aControlByteBelowSpaceIsRefused() {
            final byte[] content = {'0', '1', 0x07, '3', '4', '\n'};
            final String probe = publishProbe("control-byte", content);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(probe, 5))
                    .withMessageContaining("0x07");
        }

        @Test
        @DisplayName("a missing terminal line feed is refused: the final record is unterminated")
        void aMissingTerminalLineFeedIsRefused() {
            final String probe = publishTextProbe("no-terminator", "0123456789");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(probe, 10))
                    .withMessageContaining("does not end in a line feed")
                    .withMessageContaining("geometry invariant");
        }

        @Test
        @DisplayName("a short record is refused, reporting both the actual and the required width")
        void aShortRecordIsRefused() {
            // The usual cause is trailing whitespace being trimmed, which is exactly the corruption a
            // fixed-width parity assertion cannot survive.
            final String probe = publishTextProbe("short-record", "0123456789\n01234\n");

            final IllegalStateException refused = catchThrowableOfType(IllegalStateException.class,
                    () -> FixtureLoader.loadResource(probe, 10));

            assertThat(refused).isNotNull();
            assertThat(refused).hasMessageContaining("record 1")
                    .hasMessageContaining("5 characters wide")
                    .hasMessageContaining("exactly 10")
                    .hasMessageContaining("trailing whitespace was trimmed");
        }

        @Test
        @DisplayName("a blank line is refused as a zero-width record")
        void aBlankLineIsRefused() {
            final String probe = publishTextProbe("blank-line", "0123456789\n\n0123456789\n");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(probe, 10))
                    .withMessageContaining("record 1")
                    .withMessageContaining("0 characters wide");
        }

        @Test
        @DisplayName("a long record is refused as firmly as a short one")
        void aLongRecordIsRefused() {
            final String probe = publishTextProbe("long-record", "0123456789\n0123456789ABC\n");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(probe, 10))
                    .withMessageContaining("13 characters wide");
        }

        @Test
        @DisplayName("a well-formed probe loads, so the guards above reject only what is wrong")
        void aWellFormedProbeLoads() {
            final String probe = publishTextProbe("well-formed", "0123456789\nABCDEFGHIJ\n");

            final FixtureData data = FixtureLoader.loadResource(probe, 10);

            assertThat(data.recordCount()).isEqualTo(2);
            assertThat(data.byteCount()).isEqualTo(22);
            assertThat(data.impliedByteCount()).isEqualTo(22);
            assertThat(data.records()).containsExactly("0123456789", "ABCDEFGHIJ");
        }

        @Test
        @DisplayName("a probe whose declared census disagrees with the file fails load, not loadResource")
        void aCensusDisagreementFailsLoadRatherThanLoadResource() {
            // loadResource has no census to check against, so the extra three checks belong to load. This
            // separation is what lets a probe exercise the structural guards without inventing a constant.
            final String probe = publishTextProbe("census", "0123456789\n");

            assertThat(FixtureLoader.loadResource(probe, 10).recordCount()).isOne();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(probe, 4))
                    .as("a width the file does not have is a record-width failure, not a census failure")
                    .withMessageContaining("10 characters wide");
        }
    }

    @Nested
    @DisplayName("5. FixtureData - immutability, bounds and one-based column slicing")
    class Snapshot {

        @Test
        @DisplayName("the record list is unmodifiable, so a snapshot cannot be perturbed by its holder")
        void theRecordListIsUnmodifiable() {
            final FixtureData data = FixtureLoader.load(Fixture.TRANSACTION_TYPE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> data.records().add("intruder"));
        }

        @Test
        @DisplayName("the constructor copies defensively, so a later mutation of the caller's list is not seen")
        void theConstructorCopiesDefensively() {
            final List<String> mutable = new ArrayList<>(List.of("0123456789"));
            final FixtureData data = new FixtureData("probe.txt", 10, 11, mutable);

            mutable.add("ABCDEFGHIJ");

            assertThat(data.records()).containsExactly("0123456789");
            assertThat(data.recordCount()).isOne();
        }

        @Test
        @DisplayName("the constructor refuses a null name, a null record list, a blank name, a "
                + "non-positive width and a negative byte count")
        void theConstructorRefusesEveryInvalidComponent() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FixtureData(null, 10, 11, List.of()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FixtureData("probe.txt", 10, 11, null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FixtureData("  ", 10, 11, List.of()))
                    .withMessageContaining("must not be blank");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FixtureData("probe.txt", 0, 11, List.of()))
                    .withMessageContaining("recordWidth must be positive");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FixtureData("probe.txt", 10, -1, List.of()))
                    .withMessageContaining("byteCount must not be negative");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 7, 8, 99})
        @DisplayName("recordAt outside the fixture reports the resource and the count, never a record")
        void recordAtOutsideTheFixtureIsRefused(final int index) {
            final FixtureData types = FixtureLoader.load(Fixture.TRANSACTION_TYPE);

            final IndexOutOfBoundsException refused = catchThrowableOfType(
                    IndexOutOfBoundsException.class, () -> types.recordAt(index));

            assertThat(refused).isNotNull();
            assertThat(refused).hasMessageContaining("trantype.txt").hasMessageContaining("7 records");
        }

        @Test
        @DisplayName("field slices at one-based COBOL columns, inclusive of the start")
        void fieldSlicesAtOneBasedColumns() {
            // ACCT-ID PIC X(11) at columns 1-11 and ACCT-ACTIVE-STATUS PIC X(01) at column 12.
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThat(accounts.field(0, 1, 11)).isEqualTo("00000000001");
            assertThat(accounts.field(0, 12, 1)).isEqualTo("Y");
            assertThat(accounts.field(0, 1, 1)).isEqualTo("0");
        }

        @Test
        @DisplayName("field reads the last column of the record without running off the end")
        void fieldReadsTheLastColumn() {
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThat(accounts.field(0, 300, 1)).isEqualTo(" ");
            assertThat(accounts.field(0, 291, 10)).isEqualTo("          ");
        }

        @Test
        @DisplayName("a zero or negative start column is refused, because columns are one-based")
        void aZeroOrNegativeStartColumnIsRefused() {
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> accounts.field(0, 0, 1))
                    .withMessageContaining("one-based");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> accounts.field(0, -5, 1))
                    .withMessageContaining("one-based");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        @DisplayName("a non-positive length is refused")
        void aNonPositiveLengthIsRefused(final int length) {
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> accounts.field(0, 1, length))
                    .withMessageContaining("length must be positive");
        }

        @Test
        @DisplayName("a slice that runs past the record is refused and names the copybook as the remedy")
        void aSliceThatRunsPastTheRecordIsRefused() {
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> accounts.field(0, 300, 2))
                    .withMessageContaining("columns 300-301")
                    .withMessageContaining("300-character record")
                    .withMessageContaining("PIC clause");
        }

        @Test
        @DisplayName("field on a record index outside the fixture reports the bound, not a slice")
        void fieldOnAnOutOfRangeRecordIsRefused() {
            final FixtureData types = FixtureLoader.load(Fixture.TRANSACTION_TYPE);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> types.field(7, 1, 2));
        }
    }

    @Nested
    @DisplayName("6. The complete overpunch alphabets - ten positive, ten negative, and plain digits")
    class OverpunchAlphabets {

        @ParameterizedTest(name = "sign ''{0}'' yields low-order digit {1} and a positive value")
        @CsvSource({"{,0", "A,1", "B,2", "C,3", "D,4", "E,5", "F,6", "G,7", "H,8", "I,9"})
        @DisplayName("every positive overpunch character decodes to its digit with a positive sign")
        void everyPositiveOverpunchDecodes(final char sign, final int digit) {
            final BigDecimal decoded = FixtureLoader.decodeZonedDecimal("0000000001" + sign, 2);

            assertThat(decoded).isEqualByComparingTo(new BigDecimal("0.1" + digit));
            assertThat(decoded.signum()).isEqualTo(digit == 0 ? 1 : 1);
        }

        @ParameterizedTest(name = "sign ''{0}'' yields low-order digit {1} and a negative value")
        @CsvSource({"},0", "J,1", "K,2", "L,3", "M,4", "N,5", "O,6", "P,7", "Q,8", "R,9"})
        @DisplayName("every negative overpunch character decodes to its digit with a negative sign")
        void everyNegativeOverpunchDecodes(final char sign, final int digit) {
            final BigDecimal decoded = FixtureLoader.decodeZonedDecimal("0000000001" + sign, 2);

            assertThat(decoded).isEqualByComparingTo(new BigDecimal("-0.1" + digit));
            assertThat(decoded.signum()).isNegative();
        }

        @Test
        @DisplayName("the two alphabets are exactly ten characters each and share no character")
        void theTwoAlphabetsAreTenEachAndDisjoint() {
            assertThat(POSITIVE_OVERPUNCH).hasSize(10);
            assertThat(NEGATIVE_OVERPUNCH).hasSize(10);
            for (int index = 0; index < POSITIVE_OVERPUNCH.length(); index++) {
                assertThat(NEGATIVE_OVERPUNCH)
                        .doesNotContain(String.valueOf(POSITIVE_OVERPUNCH.charAt(index)));
            }
        }

        @Test
        @DisplayName("negative zero decodes to a value that compares equal to zero")
        void negativeZeroComparesEqualToZero() {
            // '}' is -0. The magnitude is zero, so the negation is still zero; asserting with compareTo
            // rather than equals is what makes that true regardless of how the sign is carried.
            assertThat(FixtureLoader.decodeZonedDecimal("0000000000}", 2))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(FixtureLoader.decodeZonedDecimal("0000000000{", 2))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @ParameterizedTest
        @ValueSource(chars = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'})
        @DisplayName("a plain digit in the sign position reads as unsigned, therefore positive")
        void aPlainDigitInTheSignPositionIsPositive(final char digit) {
            final BigDecimal decoded = FixtureLoader.decodeZonedDecimal("0000000001" + digit, 2);

            assertThat(decoded).isEqualByComparingTo(new BigDecimal("0.1" + digit));
            assertThat(decoded.signum()).isNotNegative();
        }

        @Test
        @DisplayName("the returned scale is always the requested scale, so results are comparable")
        void theReturnedScaleIsAlwaysTheRequestedScale() {
            assertThat(FixtureLoader.decodeZonedDecimal("00001940{", 0).scale()).isZero();
            assertThat(FixtureLoader.decodeZonedDecimal("00001940{", 2).scale()).isEqualTo(2);
            assertThat(FixtureLoader.decodeZonedDecimal("00001940{", 4).scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("the decode shifts a decimal point and never divides, so nothing is ever rounded")
        void theDecodeNeverRounds() {
            // Scale 4 on a five-digit magnitude cannot round: 19400 becomes 1.9400 exactly.
            assertThat(FixtureLoader.decodeZonedDecimal("1940{", 4))
                    .isEqualByComparingTo(new BigDecimal("1.9400"));
            assertThat(FixtureLoader.decodeZonedDecimal("1940{", 0))
                    .isEqualByComparingTo(new BigDecimal("19400"));
        }

        @Test
        @DisplayName("a single-character field is the sign position alone and decodes")
        void aSingleCharacterFieldIsTheSignPositionAlone() {
            assertThat(FixtureLoader.decodeZonedDecimal("E", 0)).isEqualByComparingTo(new BigDecimal("5"));
            assertThat(FixtureLoader.decodeZonedDecimal("N", 0)).isEqualByComparingTo(new BigDecimal("-5"));
        }

        @Test
        @DisplayName("the result is a BigDecimal, never a binary floating point value")
        void theResultIsAlwaysBigDecimal() {
            // Clause D of the migration's numeric rule: no float or double in any financial field. A
            // BigDecimal round trip through a double would lose the exact cent.
            final BigDecimal decoded = FixtureLoader.decodeZonedDecimal("00000001940{", 2);

            assertThat(decoded).isExactlyInstanceOf(BigDecimal.class);
            assertThat(decoded.toPlainString()).isEqualTo("194.00");
        }
    }

    @Nested
    @DisplayName("7. Malformed zoned decimals are refused, and the refusal quotes no payload")
    class MalformedZonedDecimals {

        @Test
        @DisplayName("a null field is refused")
        void aNullFieldIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal(null, 2));
        }

        @Test
        @DisplayName("an empty field is refused: there is not even a sign position")
        void anEmptyFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal("", 2))
                    .withMessageContaining("at least the sign position");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -2})
        @DisplayName("a negative scale is refused")
        void aNegativeScaleIsRefused(final int scale) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal("00001940{", scale))
                    .withMessageContaining("scale must not be negative");
        }

        @ParameterizedTest(name = "a non-digit at position {1} is refused")
        @CsvSource({"'0000X1940{',5", "'X000019400',1", "'00 001940{',3", "'0000-1940{',5"})
        @DisplayName("any non-digit before the sign position is refused, by position and never by content")
        void anyNonDigitBeforeTheSignPositionIsRefused(final String field, final int position) {
            final IllegalArgumentException refused = catchThrowableOfType(IllegalArgumentException.class,
                    () -> FixtureLoader.decodeZonedDecimal(field, 2));

            assertThat(refused).isNotNull();
            assertThat(refused).hasMessageContaining("position " + position)
                    .hasMessageContaining("must be a digit")
                    .hasMessageContaining("PIC clause");
            assertThat(refused.getMessage())
                    .as("Clause D: the field may be personally identifiable, so it is withheld")
                    .doesNotContain(field);
        }

        @ParameterizedTest
        @ValueSource(chars = {'*', ' ', 'S', 'T', 'Z', 'a', 'j', '+', '-', '.', '[', ']'})
        @DisplayName("a sign position that is neither a digit nor a recognised overpunch is refused")
        void anUnrecognisedSignIsRefused(final char sign) {
            final IllegalArgumentException refused = catchThrowableOfType(IllegalArgumentException.class,
                    () -> FixtureLoader.decodeZonedDecimal("000019400" + sign, 2));

            assertThat(refused).isNotNull();
            assertThat(refused).hasMessageContaining("is not a zoned-decimal")
                    .hasMessageContaining(POSITIVE_OVERPUNCH)
                    .hasMessageContaining(NEGATIVE_OVERPUNCH);
        }

        @Test
        @DisplayName("the lower-case overpunch letters are NOT accepted: the alphabet is upper case only")
        void lowerCaseOverpunchLettersAreNotAccepted() {
            // Accepting 'a' as +1 would make every lower-case tail of a text field decode as a number, which
            // is precisely the blanket substitution the position-aware contract forbids.
            for (final char upper : POSITIVE_OVERPUNCH.toCharArray()) {
                final char lower = Character.toLowerCase(upper);
                if (lower == upper) {
                    continue;
                }
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(() -> FixtureLoader.decodeZonedDecimal("00001940" + lower, 2));
            }
        }

        @Test
        @DisplayName("signedDecimal wraps a malformed field as an IllegalStateException naming the columns")
        void signedDecimalWrapsAMalformedFieldWithItsColumns() {
            // ACCT-OPEN-DATE at columns 49-58 is '2014-11-20': dashes, so it is not a zoned decimal. Reading
            // it as one is the mistake this diagnostic has to make obvious.
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            final IllegalStateException refused = catchThrowableOfType(IllegalStateException.class,
                    () -> accounts.signedDecimal(0, 49, 10));

            assertThat(refused).isNotNull();
            assertThat(refused).hasMessageContaining("record 0")
                    .hasMessageContaining("acctdata.txt")
                    .hasMessageContaining("columns 49-58")
                    .hasMessageContaining("valid zoned decimal");
            assertThat(refused).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("8. Position awareness - the decode is a field-level operation, never a file-level one")
    class PositionAwareness {

        @Test
        @DisplayName("the account postal code is the literal 'A000000000' on every account record")
        void theAccountPostalCodeIsALiteralWithALeadingLetter() {
            // This is the whole argument for position awareness in one field. 'A' means +1 in the overpunch
            // alphabet, so a blanket letter substitution across the record would rewrite this postal code as
            // a number on all fifty rows.
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            for (int row = 0; row < accounts.recordCount(); row++) {
                assertThat(accounts.field(row, ACCT_ADDR_ZIP_COLUMN, ACCT_ADDR_ZIP_WIDTH))
                        .as("ACCT-ADDR-ZIP on row %d is text and keeps its leading letter", row)
                        .isEqualTo("A000000000");
            }
        }

        @Test
        @DisplayName("decoding the postal code as a number would succeed and be wrong, which is the trap")
        void decodingThePostalCodeWouldSucceedAndBeWrong() {
            // The decode cannot tell that this field is text: the last character is a digit, so it reads as
            // unsigned and returns a value. Only the caller's knowledge of the PIC clause prevents it.
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);
            final String postalCode = accounts.field(0, ACCT_ADDR_ZIP_COLUMN, ACCT_ADDR_ZIP_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the leading 'A' is not a digit, so this particular text field is caught")
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal(postalCode, 2));
        }

        @Test
        @DisplayName("a width one short reads the wrong character as the sign and yields a wrong value")
        void aWidthOneShortReadsTheWrongCharacterAsTheSign() {
            // ACCT-CURR-BAL is twelve characters, '00000001940{', so +194.00. Reading eleven stops before the
            // overpunch and takes the trailing '0' of the digit run as the sign position, so the field
            // decodes to 19.40 - a value that is plausible, unsigned, and wrong by a factor of ten.
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThat(accounts.signedDecimal(0, ACCT_CURR_BAL_COLUMN, FixtureLoader.MONEY_FIELD_WIDTH))
                    .isEqualByComparingTo(new BigDecimal("194.00"));
            assertThat(accounts.signedDecimal(0, ACCT_CURR_BAL_COLUMN,
                    FixtureLoader.MONEY_FIELD_WIDTH - 1))
                    .as("off by one is not a rounding error, it is a different number")
                    .isEqualByComparingTo(new BigDecimal("19.40"));
        }

        @Test
        @DisplayName("a width one long reads a neighbouring column as the sign and yields a wrong value")
        void aWidthOneLongReadsANeighbouringColumnAsTheSign() {
            // Thirteen characters from column 13 is '00000001940{' plus the first '0' of ACCT-CREDIT-LIMIT.
            // The '{' is then an interior character, so the decode is refused rather than silently wrong -
            // which is the better of the two failures and is asserted so it stays that way.
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> accounts.signedDecimal(0, ACCT_CURR_BAL_COLUMN,
                            FixtureLoader.MONEY_FIELD_WIDTH + 1))
                    .withMessageContaining("columns 13-25");
        }

        @Test
        @DisplayName("the three standard PIC widths are exposed as named constants, not typed as numbers")
        void theThreeStandardPicWidthsAreNamedConstants() {
            assertThat(FixtureLoader.DECIMAL_SCALE).isEqualTo(2);
            assertThat(FixtureLoader.MONEY_FIELD_WIDTH).isEqualTo(12);
            assertThat(FixtureLoader.AMOUNT_FIELD_WIDTH).isEqualTo(11);
            assertThat(FixtureLoader.RATE_FIELD_WIDTH).isEqualTo(6);
        }

        @Test
        @DisplayName("the overpunch letters occur legitimately as text, so no global substitution is safe")
        void theOverpunchLettersOccurLegitimatelyAsText() {
            // TRAN-SOURCE on the first daily transaction is 'POS TERM  ', which carries three of the ten
            // negative overpunch letters. A file-wide replacement would corrupt it.
            final FixtureData daily = FixtureLoader.load(Fixture.DAILY_TRANSACTION);
            final String source = daily.field(0, 23, 10);

            assertThat(source).isEqualTo("POS TERM  ");
            assertThat(source).containsAnyOf("O", "P", "M");
        }
    }

    @Nested
    @DisplayName("9. Real fixture decodes - the values the frozen datasets actually carry")
    class RealFixtureDecodes {

        @Test
        @DisplayName("the first account row decodes to +194.00, +2020.00 and +1020.00")
        void theFirstAccountRowDecodesToItsThreeMoneyValues() {
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);

            assertThat(accounts.field(0, ACCT_CURR_BAL_COLUMN, FixtureLoader.MONEY_FIELD_WIDTH))
                    .isEqualTo("00000001940{");
            assertThat(accounts.signedDecimal(0, ACCT_CURR_BAL_COLUMN, FixtureLoader.MONEY_FIELD_WIDTH))
                    .isEqualByComparingTo(new BigDecimal("194.00"));
            assertThat(accounts.signedDecimal(0, ACCT_CREDIT_LIMIT_COLUMN, FixtureLoader.MONEY_FIELD_WIDTH))
                    .isEqualByComparingTo(new BigDecimal("2020.00"));
            assertThat(accounts.signedDecimal(0, ACCT_CASH_CREDIT_LIMIT_COLUMN,
                    FixtureLoader.MONEY_FIELD_WIDTH))
                    .isEqualByComparingTo(new BigDecimal("1020.00"));
        }

        @Test
        @DisplayName("every account money field on every row decodes, so none of the fifty rows is malformed")
        void everyAccountMoneyFieldOnEveryRowDecodes() {
            final FixtureData accounts = FixtureLoader.load(Fixture.ACCOUNT);
            final int[] moneyColumns = {ACCT_CURR_BAL_COLUMN, ACCT_CREDIT_LIMIT_COLUMN,
                ACCT_CASH_CREDIT_LIMIT_COLUMN, 79, 91};

            for (int row = 0; row < accounts.recordCount(); row++) {
                for (final int column : moneyColumns) {
                    assertThat(accounts.signedDecimal(row, column, FixtureLoader.MONEY_FIELD_WIDTH))
                            .as("row %d column %d", row, column)
                            .isNotNull();
                }
            }
        }

        @Test
        @DisplayName("the category balance is eleven characters, not twelve: S9(09)V99 not S9(10)V99")
        void theCategoryBalanceIsElevenCharacters() {
            // The key is 11 + 2 + 4 = 17 characters, which is what makes the balance start at column 18.
            final FixtureData balances = FixtureLoader.load(Fixture.TRANSACTION_CATEGORY_BALANCE);

            assertThat(balances.field(0, 1, 11)).isEqualTo("00000000001");
            assertThat(balances.field(0, 12, 2)).isEqualTo("01");
            assertThat(balances.field(0, 14, 4)).isEqualTo("0001");
            assertThat(balances.field(0, TCATBAL_BALANCE_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .isEqualTo("0000000000{");
            assertThat(balances.signedDecimal(0, TCATBAL_BALANCE_COLUMN,
                    FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the DEFAULT disclosure group rate is six characters and decodes to +15.00")
        void theDefaultDisclosureGroupRateDecodes() {
            final FixtureData groups = FixtureLoader.load(Fixture.DISCLOSURE_GROUP);

            assertThat(groups.field(DISCGRP_DEFAULT_ROW, 1, 10)).isEqualTo("DEFAULT   ");
            assertThat(groups.field(DISCGRP_DEFAULT_ROW, DISCGRP_RATE_COLUMN,
                    FixtureLoader.RATE_FIELD_WIDTH)).isEqualTo("00150{");
            assertThat(groups.signedDecimal(DISCGRP_DEFAULT_ROW, DISCGRP_RATE_COLUMN,
                    FixtureLoader.RATE_FIELD_WIDTH))
                    .isEqualByComparingTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("seventeen disclosure group rows carry the literal DEFAULT group identifier")
        void seventeenDisclosureGroupRowsCarryTheDefaultIdentifier() {
            // That set is what makes the interest program's default-rate fallback succeed for those
            // type-and-category pairs and abend for any other, so both outcomes are testable.
            final FixtureData groups = FixtureLoader.load(Fixture.DISCLOSURE_GROUP);
            int defaults = 0;
            for (int row = 0; row < groups.recordCount(); row++) {
                if ("DEFAULT   ".equals(groups.field(row, 1, 10))) {
                    defaults++;
                }
            }

            assertThat(defaults).isEqualTo(17);
            assertThat(groups.recordCount()).isEqualTo(51);
        }

        @Test
        @DisplayName("a zero interest rate is present among the DEFAULT rows, so the no-interest arm is real")
        void aZeroInterestRateIsPresentAmongTheDefaultRows() {
            final FixtureData groups = FixtureLoader.load(Fixture.DISCLOSURE_GROUP);
            boolean zeroRateFound = false;
            for (int row = 0; row < groups.recordCount(); row++) {
                if (!"DEFAULT   ".equals(groups.field(row, 1, 10))) {
                    continue;
                }
                if (groups.signedDecimal(row, DISCGRP_RATE_COLUMN, FixtureLoader.RATE_FIELD_WIDTH)
                        .compareTo(BigDecimal.ZERO) == 0) {
                    zeroRateFound = true;
                }
            }

            assertThat(zeroRateFound)
                    .as("a zero rate produces no interest transaction and no accumulation")
                    .isTrue();
        }

        @Test
        @DisplayName("the daily transaction fixture carries both signs, so it must never be normalised")
        void theDailyTransactionFixtureCarriesBothSigns() {
            // app/cbl/CBTRN02C.cbl:547-552 adds a negative amount to the cycle DEBIT accumulator, which is
            // exactly why the over-limit formula subtracts that accumulator. Normalising these signs would
            // retire the branch the parity gate exists to prove.
            final FixtureData daily = FixtureLoader.load(Fixture.DAILY_TRANSACTION);
            int negatives = 0;
            int positives = 0;
            for (int row = 0; row < daily.recordCount(); row++) {
                final BigDecimal amount = daily.signedDecimal(row, DALYTRAN_AMOUNT_COLUMN,
                        FixtureLoader.AMOUNT_FIELD_WIDTH);
                if (amount.signum() < 0) {
                    negatives++;
                } else {
                    positives++;
                }
            }

            assertThat(daily.recordCount()).isEqualTo(300);
            assertThat(negatives).as("genuinely negative amounts").isEqualTo(50);
            assertThat(positives).isEqualTo(250);
        }

        @Test
        @DisplayName("the first daily transaction decodes to +504.77 and the second to -919.00")
        void theFirstTwoDailyTransactionsDecodeToTheirSignedValues() {
            final FixtureData daily = FixtureLoader.load(Fixture.DAILY_TRANSACTION);

            assertThat(daily.field(0, DALYTRAN_AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .isEqualTo("0000005047G");
            assertThat(daily.signedDecimal(0, DALYTRAN_AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(daily.field(1, DALYTRAN_AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .isEqualTo("0000009190}");
            assertThat(daily.signedDecimal(1, DALYTRAN_AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .isEqualByComparingTo(new BigDecimal("-919.00"));
        }

        @Test
        @DisplayName("all three hundred daily transactions carry the one originating timestamp, unpadded")
        void allDailyTransactionsCarryTheOneOriginatingTimestamp() {
            // 2022-06-10 19:27:53.000000 across columns 279-304 is why the whole test tier fixes its clock
            // at that instant: a value derived from the clock is then directly comparable to fixture bytes.
            final FixtureData daily = FixtureLoader.load(Fixture.DAILY_TRANSACTION);

            for (int row = 0; row < daily.recordCount(); row++) {
                assertThat(daily.field(row, 279, 26))
                        .as("row %d originating timestamp", row)
                        .isEqualTo("2022-06-10 19:27:53.000000");
            }
        }

        @Test
        @DisplayName("the processing timestamp is blank on input, because posting is what fills it")
        void theProcessingTimestampIsBlankOnInput() {
            final FixtureData daily = FixtureLoader.load(Fixture.DAILY_TRANSACTION);

            assertThat(daily.field(0, 305, 26)).isBlank().hasSize(26);
        }

        @Test
        @DisplayName("the card and customer fixtures carry no signed field, so no decode belongs near them")
        void theCardAndCustomerFixturesCarryNoSignedField() {
            // Stated as an executed assertion rather than as a comment: the last character of every record
            // of both files is a plain character, and neither copybook declares an S9 picture at all.
            final FixtureData cards = FixtureLoader.load(Fixture.CARD);
            final FixtureData customers = FixtureLoader.load(Fixture.CUSTOMER);

            assertThat(cards.recordCount()).isEqualTo(50);
            assertThat(customers.recordCount()).isEqualTo(50);
            assertThat(cards.field(0, 1, 16)).containsOnlyDigits();
            assertThat(customers.field(0, 1, 9)).containsOnlyDigits();
        }
    }

    @Nested
    @DisplayName("10. Diagnostics and privacy - a failure names offsets, never payload")
    class Diagnostics {

        @Test
        @DisplayName("a customer slice failure reports widths and offsets and no record content")
        void aCustomerSliceFailureReportsNoRecordContent() {
            // custdata.txt is the one fixture carrying synthetic personally identifiable data, so its
            // diagnostics are the ones that matter for Clause D.
            final FixtureData customers = FixtureLoader.load(Fixture.CUSTOMER);
            final String firstName = customers.field(0, 10, 25);

            final IllegalArgumentException refused = catchThrowableOfType(IllegalArgumentException.class,
                    () -> customers.field(0, 500, 2));

            assertThat(refused).isNotNull();
            assertThat(refused.getMessage())
                    .contains("custdata.txt")
                    .contains("columns 500-501")
                    .doesNotContain(firstName.strip());
        }

        @Test
        @DisplayName("a zoned-decimal refusal on a customer field quotes at most the sign character")
        void aZonedDecimalRefusalOnACustomerFieldQuotesAtMostTheSign() {
            final FixtureData customers = FixtureLoader.load(Fixture.CUSTOMER);
            final String firstName = customers.field(0, 10, 25);

            final IllegalStateException refused = catchThrowableOfType(IllegalStateException.class,
                    () -> customers.signedDecimal(0, 10, 25));

            assertThat(refused).isNotNull();
            assertThat(refused.getMessage())
                    .as("the message reports a position, never the name it found there")
                    .doesNotContain(firstName.strip());
        }

        @Test
        @DisplayName("the loader never reaches outside the classpath, so app/ cannot be read through it")
        void theLoaderNeverReachesOutsideTheClasspath() {
            // A filesystem path is not a classpath resource, so it simply does not resolve. That is the
            // structural reason the frozen corpus cannot be read - or touched - through this loader.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource("app/data/ASCII/acctdata.txt", 300))
                    .withMessageContaining("no classpath resource named");
        }

        @Test
        @DisplayName("every fixture name resolves in lower case exactly as declared")
        void everyFixtureNameResolvesInLowerCaseExactlyAsDeclared() {
            for (final Fixture fixture : Fixture.values()) {
                assertThat(fixture.resourceName())
                        .isEqualTo(fixture.resourceName().toLowerCase(Locale.ROOT));
            }
        }
    }
}
