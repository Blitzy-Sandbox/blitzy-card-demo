/*
 * ******************************************************************
 * Program     : RecordLayoutCopybook.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Parses a frozen COBOL record-layout copybook so that
 *               entity tests assert field widths and numeric domains
 *               against the corpus rather than against the Java
 *               implementation they are meant to be checking.
 * Source      : app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *               CVCUS01Y.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy,
 *               CVTRA03Y.cpy, CVTRA04Y.cpy, CVTRA05Y.cpy,
 *               CVTRA06Y.cpy, CSUSR01Y.cpy
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a frozen record-layout copybook and exposes its leaf field geometry.
 *
 * <p>This is the record-layout counterpart of {@link BmsSymbolicMap}, and it exists for the same reason: an
 * entity test that asserted a column width against the entity's own {@code @Column(length = ...)} constant
 * would prove only that the file agrees with itself. Reading {@code app/cpy} instead means the assertion is
 * anchored in the system of record.
 *
 * <p><strong>The parser refuses to skip anything it does not understand.</strong> A line that looks like a
 * leaf declaration but carries a picture form this class cannot decode raises
 * {@link IllegalStateException} rather than being passed over. That rule is deliberate: the one defect this
 * class of oracle is prone to is silently dropping a field whose picture form was not anticipated, which
 * makes the oracle under-report and quietly weakens every test built on it. Only three picture forms occur
 * across the eleven record layouts - {@code X(n)}, {@code 9(n)} and {@code S9(n)V99} - and anything else is
 * an error rather than a no-op.
 *
 * <p>An independent corroboration is available to callers: {@link #recordLength()} sums every leaf including
 * {@code FILLER}, and for all eleven layouts that sum equals the record length catalogued in
 * {@code app/catlg/LISTCAT.txt}. A parse that dropped or misread a field would break that equality, so tests
 * assert it as a second witness.
 */
final class RecordLayoutCopybook {

    /** Directory holding the frozen record-layout copybooks. */
    private static final Path COPYBOOK_DIRECTORY = Path.of("app", "cpy");

    /**
     * Matches a leaf declaration: a level number, a name, {@code PIC}, and everything up to the
     * terminating period.
     *
     * <p>The picture is captured as {@code [^.]+} rather than as a single whitespace-free token, and that
     * detail is load-bearing. A token-shaped capture cannot span a picture carrying a USAGE clause such as
     * {@code PIC S9(7) COMP-3.}, so the whole line fails to match and is <em>skipped silently</em> - the
     * field vanishes from the layout and the oracle under-reports without any error. Capturing to the
     * period instead guarantees every leaf declaration reaches {@link #geometryOf}, which either decodes
     * it or refuses it out loud. No picture form contains a period, so the capture is unambiguous.
     */
    private static final Pattern LEAF_FIELD =
            Pattern.compile("^\\s*(\\d\\d)\\s+([A-Z0-9-]+)\\s+PIC\\s+([^.]+)\\.");

    /** The alphanumeric picture {@code X(n)}. */
    private static final Pattern ALPHANUMERIC = Pattern.compile("^X\\((\\d+)\\)$");

    /** The unsigned integer picture {@code 9(n)}. */
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("^9\\((\\d+)\\)$");

    /** The signed decimal picture {@code S9(n)V99}. */
    private static final Pattern SIGNED_DECIMAL = Pattern.compile("^S9\\((\\d+)\\)V(9+)$");

    /** Unnamed slack, counted toward the record length but never exposed as a field. */
    private static final String FILLER = "FILLER";

    private final String member;
    private final Map<String, Geometry> fields;
    private final int recordLength;

    private RecordLayoutCopybook(final String member, final Map<String, Geometry> fields,
            final int recordLength) {
        this.member = member;
        this.fields = fields;
        this.recordLength = recordLength;
    }

    /**
     * The decoded geometry of one leaf field.
     *
     * @param picture     the raw picture clause exactly as the copybook declares it
     * @param width       the number of character positions the field occupies
     * @param digits      the integer digit count for a numeric field, or zero for an alphanumeric one
     * @param scale       the decimal position count, zero unless the picture carries a {@code V}
     * @param signed      whether the picture carries a leading {@code S}
     */
    record Geometry(String picture, int width, int digits, int scale, boolean signed) {

        /**
         * Reports whether this field holds a number rather than text.
         *
         * @return {@code true} for a {@code 9} or {@code S9} picture
         */
        boolean numeric() {
            return digits > 0;
        }
    }

    /**
     * Parses one record-layout copybook.
     *
     * @param member the copybook member name without its extension, for example {@code CVACT02Y}
     * @return the parsed layout
     * @throws IllegalStateException if the member declares no leaf fields, or if any leaf carries a
     *                               picture form this parser cannot decode
     */
    static RecordLayoutCopybook of(final String member) {
        return of(member, COPYBOOK_DIRECTORY);
    }

    /**
     * Parses one record-layout copybook from a nominated directory.
     *
     * <p>The directory is a parameter for one reason: {@code app/cpy} is frozen, so the only way to prove
     * that {@link #geometryOf} genuinely refuses an unrecognised picture form - rather than merely being
     * believed to - is to present it with a probe layout written somewhere else. Production callers use
     * {@link #of(String)} and never choose a directory.
     *
     * @param member    the copybook member name without its extension
     * @param directory the directory holding {@code <member>.cpy}
     * @return the parsed layout
     * @throws IllegalStateException if the member declares no leaf fields, or if any leaf carries a
     *                               picture form this parser cannot decode
     */
    static RecordLayoutCopybook of(final String member, final Path directory) {
        final Path path = directory.resolve(member + ".cpy");
        final List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        } catch (final IOException failure) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), failure);
        }

        final Map<String, Geometry> parsed = new LinkedHashMap<>();
        int length = 0;
        for (final String line : lines) {
            if (line.startsWith("*") || line.length() > 6 && line.charAt(6) == '*') {
                continue;
            }
            final Matcher matcher = LEAF_FIELD.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            final String name = matcher.group(2);
            final Geometry geometry = geometryOf(member, name, matcher.group(3).trim());
            length += geometry.width();
            if (!FILLER.equals(name)) {
                parsed.put(name, geometry);
            }
        }

        if (parsed.isEmpty()) {
            throw new IllegalStateException(member + " declares no leaf fields; the copybook layout or "
                    + "this parser's LEAF_FIELD pattern has changed");
        }
        return new RecordLayoutCopybook(member, Collections.unmodifiableMap(parsed), length);
    }

    private static Geometry geometryOf(final String member, final String name, final String picture) {
        final Matcher alphanumeric = ALPHANUMERIC.matcher(picture);
        if (alphanumeric.matches()) {
            return new Geometry(picture, Integer.parseInt(alphanumeric.group(1)), 0, 0, false);
        }
        final Matcher unsigned = UNSIGNED_INTEGER.matcher(picture);
        if (unsigned.matches()) {
            final int digits = Integer.parseInt(unsigned.group(1));
            return new Geometry(picture, digits, digits, 0, false);
        }
        final Matcher signed = SIGNED_DECIMAL.matcher(picture);
        if (signed.matches()) {
            final int digits = Integer.parseInt(signed.group(1));
            final int scale = signed.group(2).length();
            return new Geometry(picture, digits + scale, digits, scale, true);
        }
        throw new IllegalStateException(member + "." + name + " declares picture '" + picture
                + "', which this parser cannot decode. Silently skipping it would make the oracle "
                + "under-report and weaken every test built on it, so extend geometryOf instead.");
    }

    /**
     * Returns the geometry of one named field.
     *
     * @param cobolField the COBOL field name, for example {@code CARD-NUM}
     * @return its geometry
     * @throws IllegalArgumentException if the layout declares no such field; the message lists every
     *                                 name that is declared, so a typo is immediately obvious
     */
    Geometry geometry(final String cobolField) {
        final Geometry geometry = fields.get(cobolField);
        if (geometry == null) {
            throw new IllegalArgumentException(member + " declares no field named " + cobolField
                    + "; declared fields are " + fields.keySet());
        }
        return geometry;
    }

    /**
     * Returns the character width of one named field.
     *
     * @param cobolField the COBOL field name
     * @return the number of character positions it occupies
     */
    int widthOf(final String cobolField) {
        return geometry(cobolField).width();
    }

    /**
     * Reports whether this layout declares a field.
     *
     * @param cobolField the COBOL field name
     * @return {@code true} when the field is declared and is not {@code FILLER}
     */
    boolean declares(final String cobolField) {
        return fields.containsKey(cobolField);
    }

    /**
     * Returns every named leaf field in copybook declaration order.
     *
     * @return the field names, excluding {@code FILLER}
     */
    List<String> fieldNames() {
        return List.copyOf(fields.keySet());
    }

    /**
     * Returns the total record length.
     *
     * @return the sum of every leaf width including {@code FILLER}, which equals the length catalogued
     *         in {@code app/catlg/LISTCAT.txt} for all eleven layouts
     */
    int recordLength() {
        return recordLength;
    }

    /**
     * Returns the copybook member name.
     *
     * @return the member name without its extension
     */
    String member() {
        return member;
    }
}
