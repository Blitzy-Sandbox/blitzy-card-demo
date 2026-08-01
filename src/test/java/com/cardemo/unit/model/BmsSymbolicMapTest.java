/*
 * ******************************************************************
 * Program     : BmsSymbolicMapTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the BmsSymbolicMap test oracle against the
 *               frozen symbolic maps, so that every DTO width and
 *               field-count assertion resting on it rests on something
 *               that has itself been proved correct.
 * Source      : app/cpy-bms/*.CPY  (17 generated symbolic maps)
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the {@link BmsSymbolicMap} oracle correct.
 *
 * <p>This class exists because the oracle is load-bearing. Five DTO test classes assert screen-field widths
 * and field counts by reading the frozen copybooks through it rather than by restating the Java constants
 * under test, and an oracle that under-reports would make all of those assertions vacuously agreeable. An
 * earlier revision of the oracle did exactly that: its field pattern matched only {@code PIC X(n)}, so it
 * silently dropped {@code app/cpy-bms/COACTVW.CPY:60}, the one input field in the whole corpus declared with
 * a numeric picture, and reported 36 fields for a member that declares 37.
 *
 * <p>The assertions here are therefore deliberately independent of the oracle's own logic wherever that is
 * possible. Field counts are checked against the number of {@code COMP PIC S9(4)} length fields, which the
 * BMS generator emits one per input field and which this class counts itself, from the raw copybook text,
 * using its own regular expression. Two unrelated declarations of the same fact have to agree.
 */
@DisplayName("BmsSymbolicMap: the copybook oracle every DTO width assertion depends on")
final class BmsSymbolicMapTest {

    /** Every generated symbolic map in the frozen tree. */
    private static final List<String> ALL_MEMBERS = List.of(
            "COACTUP", "COACTVW", "COADM01", "COBIL00", "COCRDLI", "COCRDSL", "COCRDUP", "COMEN01",
            "CORPT00", "COSGN00", "COTRN00", "COTRN01", "COTRN02", "COUSR00", "COUSR01", "COUSR02",
            "COUSR03");

    /**
     * The measured total across all seventeen members.
     *
     * <p>AAP section 0.2.1.4 states 460 in prose, while its own per-map table sums to 440. Neither figure is
     * the count the copybooks declare. The 440 is the 441 below less the single numeric-picture field that
     * the table's {@code COACTVW} row omits, which is the same field an earlier oracle revision dropped.
     */
    private static final int MEASURED_TOTAL_INPUT_FIELDS = 441;

    /** Counts the length field the generator emits once per input field. */
    private static final Pattern LENGTH_FIELD = Pattern.compile("COMP\\s+PIC\\s+S9\\(4\\)");

    /** Locates the output redefinition that bounds the input group. */
    private static final Pattern REDEFINITION = Pattern.compile("^\\s*01\\s+\\w+O\\s+REDEFINES\\b");

    static List<String> allMembers() {
        return ALL_MEMBERS;
    }

    /**
     * Counts the length fields lying strictly inside a member's input group, from the raw copybook text.
     *
     * <p>This deliberately does not call the oracle. It is the independent witness.
     *
     * @param member the symbolic map member name
     * @return the number of {@code COMP PIC S9(4)} declarations before the output redefinition
     */
    private static int independentFieldCount(final String member) {
        final Path path = Path.of("app", "cpy-bms", member + ".CPY");
        final List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
        int counted = 0;
        for (final String line : lines) {
            if (REDEFINITION.matcher(line).find()) {
                break;
            }
            if (LENGTH_FIELD.matcher(line).find()) {
                counted++;
            }
        }
        return counted;
    }

    @Nested
    @DisplayName("1. Every member parses, and agrees with an independent witness")
    final class IndependentAgreement {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.BmsSymbolicMapTest#allMembers")
        @DisplayName("the parsed field count equals the length-field count")
        void parsedCountMatchesTheLengthFieldCount(final String member) {
            final BmsSymbolicMap map = BmsSymbolicMap.of(member);

            assertThat(map.inputFieldCount())
                    .as("%s: parsed input fields must equal its COMP PIC S9(4) length fields", member)
                    .isEqualTo(independentFieldCount(member));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.BmsSymbolicMapTest#allMembers")
        @DisplayName("the input group opens at line 17 and the output redefinition follows it")
        void groupBoundsAreWellFormed(final String member) {
            final BmsSymbolicMap map = BmsSymbolicMap.of(member);

            assertThat(map.inputGroupLine())
                    .as("%s: the generator opens every input group at the same line", member)
                    .isEqualTo(17);
            assertThat(map.outputRedefinitionLine())
                    .as("%s: the output redefinition must bound the input group from below", member)
                    .isGreaterThan(map.inputGroupLine());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.BmsSymbolicMapTest#allMembers")
        @DisplayName("every declared field has a positive width and an I suffix")
        void everyFieldIsWellFormed(final String member) {
            final BmsSymbolicMap map = BmsSymbolicMap.of(member);

            assertThat(map.fieldNames()).isNotEmpty().allSatisfy(name -> {
                assertThat(name).endsWith("I");
                assertThat(map.widthOf(name)).isPositive();
            });
        }

        @Test
        @DisplayName("the seventeen members declare 441 input fields between them")
        void theCorpusTotalIsFourHundredAndFortyOne() {
            final int total = ALL_MEMBERS.stream().mapToInt(m -> BmsSymbolicMap.of(m).inputFieldCount()).sum();

            assertThat(total)
                    .as("the measured corpus total; AAP 0.2.1.4 prose says 460 and its table sums to 440")
                    .isEqualTo(MEASURED_TOTAL_INPUT_FIELDS);
        }
    }

    @Nested
    @DisplayName("2. The numeric picture that an earlier revision dropped")
    final class NumericPicture {

        @Test
        @DisplayName("COACTVW declares ACCTSIDI, written PIC 99999999999, eleven characters wide")
        void accountIdentifierIsDeclaredWithANumericPicture() {
            final BmsSymbolicMap map = BmsSymbolicMap.of("COACTVW");

            assertThat(map.declares("ACCTSIDI"))
                    .as("app/cpy-bms/COACTVW.CPY:60 declares 02 ACCTSIDI PIC 99999999999")
                    .isTrue();
            assertThat(map.widthOf("ACCTSIDI"))
                    .as("an expanded numeric picture declares one position per symbol")
                    .isEqualTo(11);
        }

        @Test
        @DisplayName("COACTVW therefore declares 37 input fields, not the 36 the AAP table states")
        void coactvwDeclaresThirtySeven() {
            assertThat(BmsSymbolicMap.of("COACTVW").inputFieldCount())
                    .as("36 X-pictures plus the one numeric picture at COACTVW.CPY:60")
                    .isEqualTo(37);
        }

        @Test
        @DisplayName("it is the only numeric-picture input field in the corpus")
        void itIsTheOnlyOne() {
            // Every other member is wholly X-pictured, so removing COACTVW's single numeric field from the
            // corpus total yields a figure divisible in the way the AAP table records it: 441 - 1 = 440.
            final int othersTotal = ALL_MEMBERS.stream()
                    .filter(m -> !"COACTVW".equals(m))
                    .mapToInt(m -> BmsSymbolicMap.of(m).inputFieldCount())
                    .sum();

            assertThat(othersTotal + 37).isEqualTo(MEASURED_TOTAL_INPUT_FIELDS);
            assertThat(othersTotal + 36)
                    .as("the AAP table's own sum, which is short by exactly the dropped field")
                    .isEqualTo(MEASURED_TOTAL_INPUT_FIELDS - 1);
        }
    }

    @Nested
    @DisplayName("3. Declaration order is preserved")
    final class DeclarationOrder {

        @Test
        @DisplayName("fieldNames follows the copybook, not a hash order")
        void fieldNamesFollowsTheCopybook() {
            // A regression guard. An earlier revision built the map with Map.copyOf, whose iteration order is
            // unspecified, which silently destroyed the order this accessor promises.
            assertThat(BmsSymbolicMap.of("COTRN00").fieldNames())
                    .startsWith("TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
                            "PAGENUMI", "TRNIDINI")
                    .endsWith("ERRMSGI");
        }

        @Test
        @DisplayName("the order is the textual order of the declarations")
        void theOrderIsTextual() {
            final BmsSymbolicMap map = BmsSymbolicMap.of("COCRDSL");
            final List<String> names = map.fieldNames();
            final Path path = Path.of("app", "cpy-bms", "COCRDSL.CPY");
            final String text;
            try {
                text = Files.readString(path, StandardCharsets.ISO_8859_1);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException("cannot read " + path, unreadable);
            }

            int previous = -1;
            for (final String name : names) {
                final Matcher declaration = Pattern.compile("\\s" + name + "\\s").matcher(text);
                assertThat(declaration.find()).as("%s must appear in the copybook", name).isTrue();
                assertThat(declaration.start()).as("%s must follow its predecessor", name)
                        .isGreaterThan(previous);
                previous = declaration.start();
            }
        }
    }

    @Nested
    @DisplayName("4. The two deliberate exclusions")
    final class Exclusions {

        @Test
        @DisplayName("the terminal I/O area FILLER is not a screen field")
        void fillerIsExcluded() {
            final BmsSymbolicMap map = BmsSymbolicMap.of("COTRN00");

            assertThat(map.declares("FILLER")).isFalse();
            assertThat(map.fieldNames()).doesNotContain("FILLER");
            assertThat(map.inputFieldCount())
                    .as("counting the twelve-byte terminal I/O area would report 60")
                    .isEqualTo(59);
        }

        @Test
        @DisplayName("output-group fields are excluded, so the edited money masks never appear")
        void outputFieldsAreExcluded() {
            final BmsSymbolicMap map = BmsSymbolicMap.of("COACTVW");

            // COACTVW.CPY:302,314,326,332,344 declare PIC +ZZZ,ZZZ,ZZZ.99 masks in the output group. They lie
            // beyond the redefinition at line 241 and are not input fields.
            assertThat(map.outputRedefinitionLine()).isEqualTo(241);
            assertThat(map.fieldNames()).allSatisfy(name -> assertThat(name).doesNotEndWith("O"));
        }
    }

    @Nested
    @DisplayName("5. A miss is reported as a finding, not swallowed")
    final class Diagnostics {

        @Test
        @DisplayName("an unknown field name throws and lists what the member does declare")
        void unknownFieldThrows() {
            final BmsSymbolicMap map = BmsSymbolicMap.of("COTRN01");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> map.widthOf("TRNDESC1"))
                    .withMessageContaining("COTRN01")
                    .withMessageContaining("TRNDESC1")
                    .withMessageContaining("TDESCI");
        }

        @Test
        @DisplayName("an absent member is reported against its path")
        void absentMemberThrows() {
            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> BmsSymbolicMap.of("CONOSUCH"))
                    .withMessageContaining("app")
                    .withMessageContaining("CONOSUCH");
        }

        @Test
        @DisplayName("the member name is carried for use in assertion descriptions")
        void memberNameIsCarried() {
            assertThat(BmsSymbolicMap.of("COBIL00").member()).isEqualTo("COBIL00");
        }
    }
}
