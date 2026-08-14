/*
 * ******************************************************************
 * Program     : BmsSymbolicMap.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Reads a frozen BMS symbolic map from app/cpy-bms and
 *               exposes its input-field inventory, so that DTO width
 *               and field-count assertions are made against the
 *               copybook itself rather than against the Java constants
 *               they are supposed to be checking.
 * Source      : app/cpy-bms/*.CPY  (17 generated symbolic maps,
 *                                   441 input fields in total - the
 *                                   measured figure; the AAP 0.2.1.4
 *                                   prose says 460 and its own table
 *                                   sums to 440, see BmsSymbolicMapTest)
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A frozen BMS symbolic map, read from {@code app/cpy-bms} and parsed into its input-field inventory.
 *
 * <p>This exists so that a DTO's published field widths and field counts can be asserted against
 * <em>the copybook</em> rather than against the Java constants under test. Asserting a constant against
 * itself - or against a second literal copied from the same place - proves only that someone typed the same
 * number twice. Reading the generated symbolic map makes the copybook the oracle, which is what the migration
 * contract actually requires: field names, types and lengths are to be taken from
 * {@code app/cpy-bms/**} exactly.
 *
 * <p>Only <strong>input</strong> fields are collected. Each screen field is generated as a quintuple - a
 * {@code COMP PIC S9(4)} length field, an attribute byte, a redefined attribute alias, four reserved bytes and
 * the data field - and the input group is followed by an output group introduced by
 * {@code 01 <name>O REDEFINES <name>I.}. Everything from that redefinition onward is excluded, because those
 * are the same bytes under a second name and counting them would double every figure.
 *
 * <p>Two exclusions are deliberate rather than incidental. Generated input fields carry a trailing {@code I},
 * so the pattern requires one. And every symbolic map opens its group with a twelve-byte terminal I/O area
 * {@code FILLER}, which is storage rather than a screen field; it is excluded <em>by name</em> as well, so the
 * exclusion does not silently depend on {@code FILLER} happening not to end in {@code I}. Without it
 * {@code COTRN00} would report 60 fields where the map declares 59, and the off-by-one would then invite a
 * "correction" in the wrong place.
 *
 * <p>The parser recognises every picture form the corpus actually uses, which is not only {@code X(n)}: a
 * single input field, {@code app/cpy-bms/COACTVW.CPY:60}, is declared {@code PIC 99999999999}. Recognising
 * only {@code X(n)} dropped it and reported 36 fields for a member that declares 37. To make that class of
 * defect impossible to repeat silently, {@link #of(String)} cross-checks its result against the number of
 * {@code COMP PIC S9(4)} length fields, which the generator emits one-per-input-field and which live
 * entirely inside the input group in all seventeen members. Two independent declarations must agree or
 * construction fails.
 *
 * <p>The copybooks are read from the working directory, which Surefire sets to the project base directory.
 * They are part of the frozen legacy tree and are never written by this class.
 */
final class BmsSymbolicMap {

    /**
     * Matches a generated input-field declaration and captures its picture body.
     *
     * <p>Three spellings occur, and all three must be matched. A
     * census of every {@code PIC} clause in all seventeen members establishes the complete set: 910
     * {@code X(n)} data fields, 441 {@code S9(4)} length fields, 5 edited output masks, and exactly one
     * numeric input field. That one field is {@code app/cpy-bms/COACTVW.CPY:60},
     * {@code 02  ACCTSIDI  PIC 99999999999.}, the eleven-digit account identifier written in expanded
     * form. Matching only {@code X(n)} silently drops it, so {@code COACTVW} reports 36 fields where it
     * declares 37 - which is precisely the figure the AAP's own field-count table gets wrong. The
     * parenthesised {@code 9(n)} form is accepted too, defensively; no input group currently uses it.
     *
     * <p>The trailing period is required so that the bare {@code 9+} alternative cannot match a prefix of
     * some longer clause and report a short width.
     */
    private static final Pattern INPUT_FIELD =
            Pattern.compile("^\\s*\\d+\\s+(\\w+I)\\s+PIC\\s+([X9]\\(\\d+\\)|9+)\\s*\\.");

    /**
     * Matches the {@code COMP PIC S9(4)} length field that precedes every generated input field.
     *
     * <p>This is the independent corroboration that makes the oracle self-validating. Exactly one length
     * field is generated per input field, and in all seventeen members every one of them lies strictly
     * inside the input group - none appears after the output redefinition. Counting these therefore yields
     * the input-field count from an entirely different declaration than the one {@link #INPUT_FIELD}
     * reads, so a parser that drops a field disagrees with it and {@link #of(String)} fails loudly. The
     * dropped-{@code ACCTSIDI} defect would have been caught at construction had this check existed.
     */
    private static final Pattern LENGTH_FIELD = Pattern.compile("COMP\\s+PIC\\s+S9\\(4\\)");

    /** Picture bodies open with this when the width is parenthesised, as in {@code X(11)} or {@code 9(11)}. */
    private static final Pattern PARENTHESISED_WIDTH = Pattern.compile("^[X9]\\((\\d+)\\)$");

    /** The twelve-byte terminal I/O area that opens every generated group; storage, not a screen field. */
    private static final String TERMINAL_IO_AREA = "FILLER";

    /** Matches the output group that redefines the input group, for example {@code 01 COTRN0AO REDEFINES}. */
    private static final Pattern OUTPUT_REDEFINITION =
            Pattern.compile("^\\s*01\\s+\\w+O\\s+REDEFINES\\b");

    /** Matches the input group header, for example {@code 01  COTRN0AI.}. */
    private static final Pattern INPUT_GROUP = Pattern.compile("^\\s*01\\s+(\\w+I)\\s*\\.");

    private final String member;
    private final Map<String, Integer> fieldWidths;
    private final int inputGroupLine;
    private final int outputRedefinitionLine;

    private BmsSymbolicMap(final String member, final Map<String, Integer> fieldWidths,
            final int inputGroupLine, final int outputRedefinitionLine) {
        this.member = member;
        this.fieldWidths = fieldWidths;
        this.inputGroupLine = inputGroupLine;
        this.outputRedefinitionLine = outputRedefinitionLine;
    }

    /**
     * Reads and parses one symbolic map.
     *
     * @param member the member name without extension, for example {@code COTRN00}
     * @return the parsed map
     * @throws IllegalStateException if the member declares no input group, which would mean the copybook is
     *                               not a generated symbolic map and every assertion drawn from it would be
     *                               vacuous
     */
    static BmsSymbolicMap of(final String member) {
        final Path path = Path.of("app", "cpy-bms", member + ".CPY");
        final List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("cannot read the frozen symbolic map " + path, unreadable);
        }

        int groupLine = -1;
        int redefinitionLine = -1;
        int lengthFields = 0;
        final Map<String, Integer> widths = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index);
            if (groupLine < 0 && INPUT_GROUP.matcher(line).find()) {
                groupLine = index + 1;
                continue;
            }
            if (OUTPUT_REDEFINITION.matcher(line).find()) {
                redefinitionLine = index + 1;
                break;
            }
            if (groupLine < 0) {
                continue;
            }
            if (LENGTH_FIELD.matcher(line).find()) {
                lengthFields++;
            }
            final Matcher field = INPUT_FIELD.matcher(line);
            if (field.find() && !TERMINAL_IO_AREA.equals(field.group(1))) {
                widths.put(field.group(1), Integer.valueOf(widthOfPicture(field.group(2))));
            }
        }
        if (groupLine < 0) {
            throw new IllegalStateException(
                    member + " declares no '01 <name>I.' input group, so it is not a generated symbolic map");
        }
        if (lengthFields != widths.size()) {
            throw new IllegalStateException(member + " declares " + lengthFields
                    + " COMP PIC S9(4) length fields but this parser recognised " + widths.size()
                    + " input fields, so a picture form is going unmatched. The two counts come from"
                    + " independent declarations and must agree. Recognised: " + widths.keySet());
        }
        // Collections.unmodifiableMap over the LinkedHashMap, deliberately NOT Map.copyOf: the latter returns
        // a map whose iteration order is unspecified, which would silently destroy the copybook declaration
        // order that fieldNames() promises and that the field-ordering assertions depend on.
        return new BmsSymbolicMap(member, Collections.unmodifiableMap(new LinkedHashMap<>(widths)),
                groupLine, redefinitionLine);
    }

    /**
     * Resolves a picture body to the number of character positions it declares.
     *
     * @param picture the captured picture body, one of {@code X(n)}, {@code 9(n)} or a run of {@code 9}s
     * @return the declared width
     */
    private static int widthOfPicture(final String picture) {
        final Matcher parenthesised = PARENTHESISED_WIDTH.matcher(picture);
        if (parenthesised.matches()) {
            return Integer.parseInt(parenthesised.group(1));
        }
        // An expanded numeric picture declares one position per symbol, so PIC 99999999999 is eleven wide.
        return picture.length();
    }

    /**
     * Returns the declared width of one input field.
     *
     * @param fieldName the generated field name, including its trailing {@code I}
     * @return the width the copybook declares
     * @throws IllegalArgumentException if the member declares no such field, which is itself a finding rather
     *                                  than a test defect
     */
    int widthOf(final String fieldName) {
        final Integer width = fieldWidths.get(Objects.requireNonNull(fieldName, "fieldName"));
        if (width == null) {
            throw new IllegalArgumentException(
                    member + " declares no input field named " + fieldName + "; it declares " + fieldNames());
        }
        return width.intValue();
    }

    /**
     * Reports whether the member declares a given input field.
     *
     * @param fieldName the generated field name, including its trailing {@code I}
     * @return {@code true} when the copybook declares it
     */
    boolean declares(final String fieldName) {
        return fieldWidths.containsKey(fieldName);
    }

    /**
     * Returns the number of input fields the member declares.
     *
     * @return the input-field count, excluding the output redefinition
     */
    int inputFieldCount() {
        return fieldWidths.size();
    }

    /**
     * Returns the declared input-field names in copybook order.
     *
     * @return the field names, in the order the copybook declares them
     */
    List<String> fieldNames() {
        return new ArrayList<>(fieldWidths.keySet());
    }

    /**
     * Returns the one-based line on which the input group is declared.
     *
     * @return the input group's line number
     */
    int inputGroupLine() {
        return inputGroupLine;
    }

    /**
     * Returns the one-based line on which the output redefinition begins, bounding the input group.
     *
     * @return the redefinition's line number, or {@code -1} if the member declares none
     */
    int outputRedefinitionLine() {
        return outputRedefinitionLine;
    }

    /**
     * Returns the member name this map was read from.
     *
     * @return the member name without extension
     */
    String member() {
        return member;
    }
}
