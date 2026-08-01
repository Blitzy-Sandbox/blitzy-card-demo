/*
 * ******************************************************************
 * Program     : TransactionSourceCoverageTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that com.cardemo.model.enums.TransactionSource
 *               reproduces the TRAN-SOURCE field contract exactly -
 *               the mixed-case literal 'System' that CBACT04C moves
 *               onto every generated interest transaction, the ten-byte
 *               PIC X(10) field width with blank padding to the right,
 *               a case-sensitive primary lookup that never silently
 *               defaults, and a separate case-insensitive lookup kept
 *               distinct from it.
 * Source      : app/cpy/CVTRA05Y.cpy:L8 @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L484 @ 7756d89
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

import com.cardemo.model.enums.TransactionSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionSource}, the typed replacement for the {@code TRAN-SOURCE} field of the
 * transaction record layout.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds {@link TransactionSource} to the field contract of the frozen legacy corpus, read first hand
 * at the traceability anchor commit {@code 7756d89}:
 *
 * <ol>
 *   <li><strong>The literal is mixed case, and that is not a typo.</strong>
 *       {@code app/cbl/CBACT04C.cbl:L484} reads {@code MOVE 'System' TO TRAN-SOURCE} - capital S, lower-case
 *       ystem. Every generated interest transaction carries that exact byte sequence, so the parity
 *       comparison against the legacy baseline is a byte comparison of {@code System} and not of
 *       {@code SYSTEM} or {@code system}. This test asserts the literal character by character, because an
 *       upper-casing "tidy-up" of the code would pass a name-based assertion and still break parity.</li>
 *   <li><strong>The field is ten bytes wide and padded to the right with blanks.</strong>
 *       {@code app/cpy/CVTRA05Y.cpy:L8} declares {@code 05 TRAN-SOURCE PIC X(10).} A COBOL alphanumeric
 *       {@code MOVE} left-justifies and pads with spaces, so the six characters of {@code System} occupy
 *       bytes 1 to 6 and bytes 7 to 10 are blanks. The fixed-width rendering is asserted to be exactly ten
 *       bytes for every constant, so a record written at the object-storage boundary keeps the 350-byte
 *       geometry the parity comparison depends on.</li>
 *   <li><strong>The primary lookup is case sensitive and never defaults.</strong> Every out-of-domain input
 *       yields an empty {@link Optional} rather than falling back onto a constant, because a silent default
 *       would attribute a transaction to the wrong source.</li>
 *   <li><strong>The case-insensitive lookup is a separate, explicitly named operation.</strong> Both
 *       lookups strip surrounding blanks, which is what makes them usable against a fixed-width field read
 *       straight out of a record, and that stripping is asserted on both.</li>
 * </ol>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * mvn -B -o test                                       # whole unit tier
 * mvn -B -o test -Dtest=TransactionSourceCoverageTest          # this class alone
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. No Spring context, no connection, no external resource. The case folding this class performs is
 * pinned to {@link java.util.Locale#ROOT} inside the production type, so this test is immune to the default
 * locale of the JVM running it - which matters, because a Turkish default locale folds {@code i} to a
 * dotless {@code I} and would otherwise make the case-insensitive lookup behave differently on one host.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The literal assertion fails.</strong> The code was upper-cased or otherwise normalised.
 *       {@code app/cbl/CBACT04C.cbl:L484} is frozen and is the authority: restore {@code System}.</li>
 *   <li><strong>A fixed-width assertion fails.</strong> The field width drifted from {@code PIC X(10)},
 *       which shifts every offset after byte 32 of the 350-byte transaction record.</li>
 *   <li><strong>A lookup assertion fails.</strong> A permissive fallback was introduced; an unknown source
 *       must stay unknown.</li>
 * </ul>
 *
 * @see TransactionSource
 */
class TransactionSourceCoverageTest {

    /** {@code TRAN-SOURCE PIC X(10)} - app/cpy/CVTRA05Y.cpy:L8. */
    private static final int FIELD_WIDTH = 10;

    /** The literal moved by app/cbl/CBACT04C.cbl:L484, mixed case exactly as written. */
    private static final String SYSTEM_LITERAL = "System";

    @Test
    @DisplayName("exposes FIELD_LENGTH as 10, the width of TRAN-SOURCE PIC X(10)")
    void exposesTheCopybookFieldWidth() {
        assertThat(TransactionSource.FIELD_LENGTH)
                .as("app/cpy/CVTRA05Y.cpy:L8 declares TRAN-SOURCE PIC X(10); this constant is the single "
                        + "place the width is stated, so the fixed-width writers cannot disagree with it")
                .isEqualTo(FIELD_WIDTH);
    }

    @Test
    @DisplayName("SYSTEM carries the mixed-case literal 'System' transcribed from CBACT04C, byte for byte")
    void systemCarriesTheMixedCaseCopybookLiteral() {
        assertThat(TransactionSource.SYSTEM.getCode())
                .as("app/cbl/CBACT04C.cbl:L484 reads MOVE 'System' TO TRAN-SOURCE - capital S, lower-case "
                        + "ystem. The parity comparison is a byte comparison, so upper-casing this would "
                        + "break it while still passing any assertion phrased on the constant NAME")
                .isEqualTo(SYSTEM_LITERAL)
                .isNotEqualTo("SYSTEM")
                .isNotEqualTo("system");

        assertThat(TransactionSource.SYSTEM.getCode().toCharArray())
                .as("asserted character by character so no normalisation can hide inside an equals")
                .containsExactly('S', 'y', 's', 't', 'e', 'm');
    }

    @Test
    @DisplayName("the SYSTEM literal is six characters, leaving four blanks of padding inside X(10)")
    void theSystemLiteralIsSixCharactersLeavingFourBlanksOfPadding() {
        assertThat(SYSTEM_LITERAL).hasSize(6);
        assertThat(TransactionSource.FIELD_LENGTH - SYSTEM_LITERAL.length())
                .as("a COBOL alphanumeric MOVE left-justifies and blank-pads, so bytes 7 to 10 are spaces")
                .isEqualTo(4);
    }

    @ParameterizedTest
    @EnumSource(TransactionSource.class)
    @DisplayName("every constant renders to exactly ten bytes, left-justified and blank-padded")
    void everyConstantRendersToExactlyTenBytes(final TransactionSource source) {
        final String fixedWidth = source.getFixedWidthValue();

        assertThat(fixedWidth)
                .as("the fixed-width rendering is what reaches the 350-byte record and the object-storage "
                        + "boundary; anything other than %d bytes shifts every following offset", FIELD_WIDTH)
                .hasSize(FIELD_WIDTH)
                .startsWith(source.getCode());
        assertThat(fixedWidth.substring(source.getCode().length()))
                .as("the remainder is blanks, never NUL, low-values or a zero pad")
                .matches(" *");
        assertThat(fixedWidth.getBytes(StandardCharsets.US_ASCII))
                .as("the rendering must be pure single-byte ASCII to occupy exactly ten bytes on the wire")
                .hasSize(FIELD_WIDTH);
    }

    @Test
    @DisplayName("SYSTEM renders as 'System' followed by exactly four blanks")
    void systemRendersAsTheLiteralFollowedByFourBlanks() {
        assertThat(TransactionSource.SYSTEM.getFixedWidthValue()).isEqualTo("System    ");
    }

    @ParameterizedTest
    @EnumSource(TransactionSource.class)
    @DisplayName("no constant carries a code longer than the ten-byte field, so no rendering ever truncates")
    void noConstantCarriesACodeLongerThanTheField(final TransactionSource source) {
        assertThat(source.getCode().length())
                .as("a code longer than PIC X(10) would be silently truncated on the way to the record")
                .isLessThanOrEqualTo(FIELD_WIDTH);
        assertThat(source.getCode())
                .as("a code is never blank, or the field could not be attributed to a source at all")
                .isNotBlank();
    }

    @Test
    @DisplayName("values() hands back a defensive copy, so the constant set is never shared mutable state")
    void valuesHandsBackADefensiveCopy() {
        final TransactionSource[] first = TransactionSource.values();
        final TransactionSource[] second = TransactionSource.values();

        assertThat(first).isNotSameAs(second).isEqualTo(second);

        first[0] = null;
        assertThat(TransactionSource.values()[0])
                .as("mutating the returned array must not affect the enum constant set")
                .isNotNull();
    }

    @Test
    @DisplayName("every declared constant has a distinct code, so a lookup can never be ambiguous")
    void everyConstantHasADistinctCode() {
        assertThat(Arrays.stream(TransactionSource.values()).map(TransactionSource::getCode).toList())
                .as("two constants sharing a code would make the by-code map lose one of them silently")
                .doesNotHaveDuplicates()
                .hasSameSizeAs(TransactionSource.values());
    }

    @Test
    @DisplayName("every declared constant has a distinct upper-cased code, so the folding lookup is total too")
    void everyConstantHasADistinctUpperCasedCode() {
        assertThat(Arrays.stream(TransactionSource.values())
                        .map(source -> source.getCode().toUpperCase(java.util.Locale.ROOT))
                        .toList())
                .as("if two codes collided once folded, the case-insensitive map would drop one and the "
                        + "loser would become unreachable through that lookup")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("fromCode resolves the exact mixed-case literal to SYSTEM")
    void fromCodeResolvesTheExactMixedCaseLiteral() {
        assertThat(TransactionSource.fromCode(SYSTEM_LITERAL)).contains(TransactionSource.SYSTEM);
    }

    @ParameterizedTest
    @EnumSource(TransactionSource.class)
    @DisplayName("fromCode round-trips every constant through its own code")
    void fromCodeRoundTripsEveryConstant(final TransactionSource source) {
        assertThat(TransactionSource.fromCode(source.getCode())).contains(source);
    }

    @ParameterizedTest
    @EnumSource(TransactionSource.class)
    @DisplayName("fromCode resolves the blank-padded fixed-width form, as read straight from a record")
    void fromCodeResolvesTheBlankPaddedFixedWidthForm(final TransactionSource source) {
        assertThat(TransactionSource.fromCode(source.getFixedWidthValue()))
                .as("a value read out of PIC X(10) arrives blank-padded, so the lookup strips before "
                        + "matching; without that, no record-sourced value would ever resolve")
                .contains(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  System  ", "\tSystem", "System\n", "\n System \t"})
    @DisplayName("fromCode strips surrounding whitespace of every kind before matching")
    void fromCodeStripsSurroundingWhitespace(final String paddedLiteral) {
        assertThat(TransactionSource.fromCode(paddedLiteral)).contains(TransactionSource.SYSTEM);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM", "system", "SyStEm", "sYSTEM"})
    @DisplayName("fromCode is CASE SENSITIVE: a differently cased literal does not resolve")
    void fromCodeIsCaseSensitive(final String wrongCaseLiteral) {
        assertThat(TransactionSource.fromCode(wrongCaseLiteral))
                .as("the primary lookup matches the record byte for byte; case folding is a separate, "
                        + "explicitly named operation so a caller must opt into it")
                .isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n", "          "})
    @DisplayName("fromCode returns empty for null, empty and all-blank input rather than throwing")
    void fromCodeReturnsEmptyForNullEmptyAndBlankInput(final String absentValue) {
        assertThat(TransactionSource.fromCode(absentValue))
                .as("an unset TRAN-SOURCE field is all blanks; that is an absent source, not an error, and "
                        + "not a default")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sys", "Systems", "System1", "POS", "Batch", "ONLINE", "0", "-", "\u00e9"})
    @DisplayName("fromCode never falls back onto a constant for an unknown or hostile value")
    void fromCodeNeverFallsBackForAnUnknownValue(final String unknownValue) {
        assertThat(TransactionSource.fromCode(unknownValue))
                .as("a silent default would attribute a transaction to the wrong source, which is a parity "
                        + "break no test downstream would catch")
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(TransactionSource.class)
    @DisplayName("fromCodeIgnoreCase round-trips every constant through its own code")
    void fromCodeIgnoreCaseRoundTripsEveryConstant(final TransactionSource source) {
        assertThat(TransactionSource.fromCodeIgnoreCase(source.getCode())).contains(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM", "system", "SyStEm", "  SYSTEM  ", "System"})
    @DisplayName("fromCodeIgnoreCase resolves every casing of the literal, stripping blanks as well")
    void fromCodeIgnoreCaseResolvesEveryCasing(final String anyCasing) {
        assertThat(TransactionSource.fromCodeIgnoreCase(anyCasing)).contains(TransactionSource.SYSTEM);
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "        ", "Sys", "Systems", "unknown"})
    @DisplayName("fromCodeIgnoreCase returns empty for absent and unknown input, never a default")
    void fromCodeIgnoreCaseReturnsEmptyForAbsentAndUnknownInput(final String absentOrUnknown) {
        assertThat(TransactionSource.fromCodeIgnoreCase(absentOrUnknown)).isEmpty();
    }

    @Test
    @DisplayName("the two lookups differ exactly where casing differs and nowhere else")
    void theTwoLookupsDifferExactlyWhereCasingDiffers() {
        assertThat(TransactionSource.fromCode("SYSTEM")).isEmpty();
        assertThat(TransactionSource.fromCodeIgnoreCase("SYSTEM")).contains(TransactionSource.SYSTEM);

        assertThat(TransactionSource.fromCode(SYSTEM_LITERAL))
                .as("for the exact literal the two lookups must agree")
                .isEqualTo(TransactionSource.fromCodeIgnoreCase(SYSTEM_LITERAL));
        assertThat(TransactionSource.fromCode("nonsense"))
                .as("for a value that is unknown under either rule the two lookups must agree")
                .isEqualTo(TransactionSource.fromCodeIgnoreCase("nonsense"));
    }

    @Test
    @DisplayName("valueOf resolves each constant by name, and the name is not the code")
    void valueOfResolvesEachConstantByNameWhichIsNotTheCode() {
        assertThat(TransactionSource.valueOf("SYSTEM")).isSameAs(TransactionSource.SYSTEM);
        assertThat(TransactionSource.SYSTEM.name())
                .as("the Java constant name is upper snake case by convention while the code is the "
                        + "record literal; conflating the two is exactly how the mixed case gets lost")
                .isEqualTo("SYSTEM")
                .isNotEqualTo(TransactionSource.SYSTEM.getCode());
    }

    @Test
    @DisplayName("the constant set is usable in an EnumSet and covers every declared value")
    void theConstantSetIsUsableInAnEnumSet() {
        assertThat(EnumSet.allOf(TransactionSource.class))
                .hasSameSizeAs(TransactionSource.values())
                .contains(TransactionSource.SYSTEM);
    }

    @Test
    @DisplayName("carries no persistence, framework or credential concern: it stays a pure data holder")
    void staysAPureDataHolder() {
        assertThat(TransactionSource.class.getAnnotations())
                .as("a value transcribed from a copybook needs no annotation to be correct")
                .isEmpty();
        assertThat(TransactionSource.class.getInterfaces())
                .as("the enum declares no interface of its own beyond what Enum itself provides")
                .isEmpty();
        assertThat(TransactionSource.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }
}
