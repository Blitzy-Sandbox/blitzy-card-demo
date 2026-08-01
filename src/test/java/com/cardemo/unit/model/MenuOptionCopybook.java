/*
 * ******************************************************************
 * Program     : MenuOptionCopybook.java
 * Application : CardDemo
 * Type        : JUnit 5 test oracle - Java 25 / Spring Boot 3.5.11
 * Function    : Reads a frozen menu option table copybook and exposes
 *               the option slots it declares, so that a test asserting
 *               the Java option tables compares them against the corpus
 *               rather than against literals retyped into the test. The
 *               35-character option names carry their padding INSIDE the
 *               quoted VALUE literal, which a test fixture silently
 *               loses to whitespace trimming; reading the copybook
 *               removes that whole class of transcription error.
 * Source      : app/cpy/COADM02Y.cpy  (4 populated slots, OCCURS 9,
 *                                      no user-type byte)
 *               app/cpy/COMEN02Y.cpy  (10 populated slots, OCCURS 12,
 *                                      PIC X(01) user-type byte each)
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An oracle over the two menu option table copybooks, {@code app/cpy/COADM02Y.cpy} and
 * {@code app/cpy/COMEN02Y.cpy}.
 *
 * <p><strong>Why this exists rather than a literal in a test.</strong> Both copybooks declare each option
 * name as {@code PIC X(35)} whose {@code VALUE} literal carries the trailing padding explicitly, for
 * example {@code 'User List (Security)               '}. A test that retypes such a literal into a
 * {@code @CsvSource} loses the padding to JUnit's default whitespace trimming and then reports a failure
 * that says nothing about the code under test. Worse, a test that retypes it into a Java constant is
 * asserting the implementation against a second copy of the same transcription, so a shared mistake
 * passes. Reading the copybook removes both hazards: the corpus is the witness.</p>
 *
 * <p><strong>Fail loud, never skip.</strong> Every hazard this class could hide is turned into a throw.
 * A code line that declares a {@code VALUE} but cannot be parsed raises {@link IllegalStateException}
 * rather than being passed over - the silent-skip failure mode that a regex with an unanticipated shape
 * produces is the one defect an oracle must not have. Each parsed literal is additionally measured
 * against its own {@code PIC} width, so a mis-joined continuation line cannot slip through as a shorter
 * string. A lookup for an absent slot or an absent field raises {@link IllegalArgumentException} naming
 * everything that was found.</p>
 *
 * <p><strong>Slot shape is discovered, not assumed.</strong> The administrator table declares two
 * character fields per slot and the main table three, so the number of fields per slot is read from the
 * copybook rather than hardcoded. A {@code PIC 9(02)} {@code FILLER} with a {@code VALUE} opens a new
 * slot; every character field that follows belongs to it, in declaration order.</p>
 *
 * <p><strong>Placement and visibility.</strong> This class lives in {@code com.cardemo.unit.model}
 * alongside the other test oracles, and is public because {@code com.cardemo.unit.service} consumes it -
 * the same arrangement {@link FixedClockProvider} already uses. Its name deliberately does not end in
 * {@code Test} or {@code Tests}, so Surefire compiles it onto the test classpath without selecting it as
 * a test class. It is final, cannot be instantiated other than through its factories, holds only
 * immutable state once constructed, and performs no I/O after construction.</p>
 *
 * <p><strong>Working directory.</strong> Surefire runs with {@code ${basedir}} as the working directory,
 * which is what makes the default {@code app/cpy} location resolvable from a test. The
 * {@link #of(String, Path)} overload exists so that the refusal behaviour can be probed against a
 * temporary directory, because {@code app/} is frozen and cannot host a malformed fixture.</p>
 */
public final class MenuOptionCopybook {

    /** The directory holding the frozen record layout and table copybooks. */
    private static final Path DEFAULT_DIRECTORY = Path.of("app", "cpy");

    /**
     * A level number, a data name, a picture, the {@code VALUE} keyword and optionally the value itself.
     * The value is absent when the copybook continues it onto the following line, which both copybooks do
     * for every {@code PIC X(35)} option name.
     */
    private static final Pattern VALUE_CLAUSE = Pattern.compile(
            "^\\s*(\\d\\d)\\s+([A-Z0-9-]+)\\s+PIC\\s+(\\S+)\\s+VALUE\\s*(.*)$");

    /** An alphanumeric picture, {@code X(nn)}. */
    private static final Pattern ALPHANUMERIC = Pattern.compile("^X\\((\\d+)\\)$");

    /** An unsigned integer picture, {@code 9(nn)}. */
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("^9\\((\\d+)\\)$");

    /** The {@code OCCURS n TIMES} clause on the redefining group, which declares the table capacity. */
    private static final Pattern OCCURS = Pattern.compile("\\bOCCURS\\s+(\\d+)\\s+TIMES\\b");

    /** The data-name suffix that distinguishes the option count from the option slots. */
    private static final String COUNT_SUFFIX = "-OPT-COUNT";

    /** The data name every slot field carries, which is what makes the count line distinguishable. */
    private static final String SLOT_FIELD_NAME = "FILLER";

    /** The copybook member name, for use in diagnostics. */
    private final String member;

    /** The value of the {@code *-OPT-COUNT} field, which is the number of populated slots. */
    private final int declaredCount;

    /** The {@code OCCURS n TIMES} capacity of the redefining table, which is not a populated-slot bound. */
    private final int occursCapacity;

    /** The populated slots, in declaration order. */
    private final List<Slot> slots;

    private MenuOptionCopybook(final String member, final int declaredCount, final int occursCapacity,
            final List<Slot> slots) {
        this.member = member;
        this.declaredCount = declaredCount;
        this.occursCapacity = occursCapacity;
        this.slots = slots;
    }

    /**
     * One character field of a slot, with the picture that declares it and the literal it carries.
     *
     * @param picture the {@code PIC} clause exactly as the copybook writes it, for example {@code X(35)}
     * @param width   the declared width in characters, taken from the picture
     * @param value   the literal the copybook assigns, with its padding preserved verbatim
     */
    public record OptionField(String picture, int width, String value) {
    }

    /**
     * One populated option slot: the number its {@code PIC 9(02)} field carries, and the character fields
     * that follow it in declaration order.
     *
     * @param number the option number
     * @param fields the character fields of the slot, in the order the copybook declares them
     */
    public record Slot(int number, List<OptionField> fields) {

        /**
         * Returns the sole field of the given declared width.
         *
         * @param width the declared width to select on
         * @return the field declared {@code X(width)}
         * @throws IllegalArgumentException if no field, or more than one field, has that width
         */
        public OptionField fieldOfWidth(final int width) {
            final List<OptionField> matches = this.fields.stream()
                    .filter(field -> field.width() == width)
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException("slot " + this.number + " declares " + matches.size()
                        + " fields of width " + width + ", so the selection is not unique; the slot"
                        + " declares " + this.fields.stream().map(OptionField::picture).toList());
            }
            return matches.get(0);
        }
    }

    /**
     * Reads the named copybook from the frozen {@code app/cpy} directory.
     *
     * @param member the member name without its extension, for example {@code COADM02Y}
     * @return the parsed option table
     * @throws UncheckedIOException  if the member cannot be read
     * @throws IllegalStateException if the member declares no option count, no capacity, or a
     *                               {@code VALUE} clause this oracle cannot parse
     */
    public static MenuOptionCopybook of(final String member) {
        return of(member, DEFAULT_DIRECTORY);
    }

    /**
     * Reads the named copybook from the given directory.
     *
     * <p>The directory is injectable for one reason only: {@code app/} is frozen, so the refusal
     * behaviour of this oracle cannot be probed with a malformed member unless the location can be
     * pointed at a temporary directory. Production assertions use {@link #of(String)}.</p>
     *
     * @param member    the member name without its extension
     * @param directory the directory holding {@code <member>.cpy}
     * @return the parsed option table
     * @throws UncheckedIOException  if the member cannot be read
     * @throws IllegalStateException if the member declares no option count, no capacity, or a
     *                               {@code VALUE} clause this oracle cannot parse
     */
    public static MenuOptionCopybook of(final String member, final Path directory) {

        final Path source = directory.resolve(member + ".cpy");
        final List<String> lines;
        try {
            // ISO-8859-1 maps every byte to exactly one character, so no byte of a fixed-width source can
            // be lost to a decoding substitution. The copybooks are pure ASCII, which this also accepts.
            lines = Files.readAllLines(source, StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read the copybook at " + source.toAbsolutePath(),
                    unreadable);
        }

        // Comment lines are dropped first. COMEN02Y.cpy:69 is a commented-out option name literal, so a
        // parser that did not skip comments would read a slot that the corpus deliberately disabled.
        final List<String> codeLines = new ArrayList<>(lines.size());
        for (final String line : lines) {
            if (!isCommentLine(line)) {
                codeLines.add(line);
            }
        }

        int declaredCount = -1;
        int occursCapacity = -1;
        final List<Slot> slots = new ArrayList<>();
        List<OptionField> currentFields = null;

        for (int index = 0; index < codeLines.size(); index++) {

            final String line = codeLines.get(index);

            final Matcher occurs = OCCURS.matcher(line);
            if (occurs.find()) {
                occursCapacity = Integer.parseInt(occurs.group(1));
                // The redefining group follows, and declares pictures without VALUE clauses. Parsing stops
                // here so that those declarations cannot be mistaken for a further populated slot.
                break;
            }

            if (!line.contains(" VALUE")) {
                continue;
            }

            final Matcher clause = VALUE_CLAUSE.matcher(line);
            if (!clause.matches()) {
                // Fail loud. A VALUE clause this oracle cannot read is exactly the silent skip that makes
                // an oracle worse than no oracle, so it is reported rather than passed over.
                throw new IllegalStateException(member + ".cpy declares a VALUE clause that this oracle"
                        + " cannot parse, so a slot would have been silently dropped: [" + line + "]");
            }

            final String dataName = clause.group(2);
            final String picture = clause.group(3);
            final String rawValue = valueOf(member, codeLines, index, clause.group(4));

            if (dataName.endsWith(COUNT_SUFFIX)) {
                declaredCount = Integer.parseInt(unpunctuated(rawValue));
                continue;
            }
            if (!SLOT_FIELD_NAME.equals(dataName)) {
                throw new IllegalStateException(member + ".cpy declares an unexpected data name [" + dataName
                        + "] carrying a VALUE; only " + SLOT_FIELD_NAME + " and a name ending "
                        + COUNT_SUFFIX + " are understood");
            }

            final Matcher numeric = UNSIGNED_INTEGER.matcher(picture);
            if (numeric.matches()) {
                // A numeric FILLER with a VALUE opens a new slot. The field count per slot therefore comes
                // from the copybook: two for the administrator table, three for the main table.
                currentFields = new ArrayList<>();
                slots.add(new Slot(Integer.parseInt(unpunctuated(rawValue)),
                        Collections.unmodifiableList(currentFields)));
                continue;
            }

            final Matcher alphanumeric = ALPHANUMERIC.matcher(picture);
            if (!alphanumeric.matches()) {
                throw new IllegalStateException(member + ".cpy declares picture [" + picture
                        + "] carrying a VALUE, which is neither X(nn) nor 9(nn)");
            }
            if (currentFields == null) {
                throw new IllegalStateException(member + ".cpy declares a character field before any"
                        + " numeric slot opener, so its slot cannot be determined: [" + line + "]");
            }

            final int width = Integer.parseInt(alphanumeric.group(1));
            final String value = unquoted(member, rawValue);
            if (value.length() != width) {
                // A mis-joined continuation would produce a shorter literal than the picture declares.
                // Measuring every literal is what makes that impossible to miss.
                throw new IllegalStateException(member + ".cpy declares " + picture + " but its literal ["
                        + value + "] is " + value.length() + " characters, so the continuation line was"
                        + " joined incorrectly");
            }
            currentFields.add(new OptionField(picture, width, value));
        }

        if (declaredCount < 0) {
            throw new IllegalStateException(member + ".cpy declares no field whose name ends "
                    + COUNT_SUFFIX + ", so the populated option count is unknown");
        }
        if (occursCapacity < 0) {
            throw new IllegalStateException(member + ".cpy declares no OCCURS n TIMES clause, so the"
                    + " table capacity is unknown");
        }
        if (slots.size() != declaredCount) {
            throw new IllegalStateException(member + ".cpy declares a count of " + declaredCount
                    + " but " + slots.size() + " slots were parsed; the two must agree or a slot has been"
                    + " dropped or double counted");
        }

        return new MenuOptionCopybook(member, declaredCount, occursCapacity,
                Collections.unmodifiableList(slots));
    }

    /**
     * Returns the populated option count the copybook declares.
     *
     * @return the value of the {@code *-OPT-COUNT} field
     */
    public int declaredCount() {
        return this.declaredCount;
    }

    /**
     * Returns the {@code OCCURS n TIMES} capacity of the redefining table.
     *
     * <p>Published so that the difference from {@link #declaredCount()} is assertable: the spare
     * subscripts are unpopulated, so the capacity is not a valid bound on a selection.</p>
     *
     * @return the declared capacity
     */
    public int occursCapacity() {
        return this.occursCapacity;
    }

    /**
     * Returns the populated slots in declaration order.
     *
     * @return an unmodifiable list of slots
     */
    public List<Slot> slots() {
        return this.slots;
    }

    /**
     * Returns the slot bearing the given option number.
     *
     * @param optionNumber the option number to select
     * @return the slot
     * @throws IllegalArgumentException if no slot bears that number
     */
    public Slot slot(final int optionNumber) {
        for (final Slot slot : this.slots) {
            if (slot.number() == optionNumber) {
                return slot;
            }
        }
        throw new IllegalArgumentException(this.member + ".cpy declares no option " + optionNumber
                + "; the populated options are " + this.slots.stream().map(Slot::number).toList());
    }

    /**
     * Returns the {@code PIC X(35)} option name of the given slot, padding included.
     *
     * @param optionNumber the option number to select
     * @return the option name exactly as the copybook writes it, including trailing spaces
     * @throws IllegalArgumentException if no slot bears that number, or it has no 35-character field
     */
    public String nameOf(final int optionNumber) {
        return slot(optionNumber).fieldOfWidth(35).value();
    }

    /**
     * Returns the {@code PIC X(08)} program name of the given slot.
     *
     * @param optionNumber the option number to select
     * @return the program name
     * @throws IllegalArgumentException if no slot bears that number, or it has no 8-character field
     */
    public String programOf(final int optionNumber) {
        return slot(optionNumber).fieldOfWidth(8).value();
    }

    /**
     * Returns the {@code PIC X(01)} user-type byte of the given slot, if the table declares one.
     *
     * <p>{@code COMEN02Y.cpy} declares one per slot and {@code COADM02Y.cpy} declares none, so the
     * absence is a property of the table rather than a failure.</p>
     *
     * @param optionNumber the option number to select
     * @return the user-type byte, or empty when this table declares none
     * @throws IllegalArgumentException if no slot bears that number
     */
    public Optional<String> userTypeOf(final int optionNumber) {
        return slot(optionNumber).fields().stream()
                .filter(field -> field.width() == 1)
                .map(OptionField::value)
                .findFirst();
    }

    /**
     * Returns the member name this oracle read.
     *
     * @return the member name without its extension
     */
    public String member() {
        return this.member;
    }

    /**
     * Resolves the value of a {@code VALUE} clause, following a continuation line when the clause itself
     * carries no value.
     *
     * @param member    the member name, for diagnostics
     * @param codeLines the comment-stripped lines
     * @param index     the index of the line carrying the {@code VALUE} keyword
     * @param inline    the text that followed {@code VALUE} on that line, possibly blank
     * @return the raw value text, still carrying its terminating period
     * @throws IllegalStateException if the value is continued but no continuation line follows
     */
    private static String valueOf(final String member, final List<String> codeLines, final int index,
            final String inline) {
        if (!inline.isBlank()) {
            return inline.strip();
        }
        if (index + 1 >= codeLines.size()) {
            throw new IllegalStateException(member + ".cpy continues a VALUE clause past the end of the"
                    + " member, so its literal is unreadable");
        }
        final String continuation = codeLines.get(index + 1).strip();
        if (continuation.isEmpty()) {
            throw new IllegalStateException(member + ".cpy continues a VALUE clause onto a blank line, so"
                    + " its literal is unreadable");
        }
        return continuation;
    }

    /**
     * Strips the terminating period from a raw value.
     *
     * @param rawValue the raw value text
     * @return the value without its terminating period
     */
    private static String unpunctuated(final String rawValue) {
        return rawValue.endsWith(".") ? rawValue.substring(0, rawValue.length() - 1) : rawValue;
    }

    /**
     * Removes the surrounding quotes from a raw literal, preserving every character between them.
     *
     * @param member   the member name, for diagnostics
     * @param rawValue the raw value text, with or without a terminating period
     * @return the literal content, padding preserved
     * @throws IllegalStateException if the value is not a quoted literal
     */
    private static String unquoted(final String member, final String rawValue) {
        final String withoutPeriod = unpunctuated(rawValue);
        if (withoutPeriod.length() < 2 || !withoutPeriod.startsWith("'") || !withoutPeriod.endsWith("'")) {
            throw new IllegalStateException(member + ".cpy declares a character field whose VALUE is not a"
                    + " quoted literal: [" + rawValue + "]");
        }
        return withoutPeriod.substring(1, withoutPeriod.length() - 1);
    }

    /**
     * Reports whether a source line is a comment.
     *
     * <p>A COBOL comment carries an asterisk in the indicator area, column seven, which is index six of a
     * zero-based line. A line beginning with an asterisk is also treated as a comment so that a member
     * written without a sequence area is handled.</p>
     *
     * @param line the source line
     * @return true when the line is a comment
     */
    private static boolean isCommentLine(final String line) {
        return line.startsWith("*") || line.length() > 6 && line.charAt(6) == '*';
    }
}
