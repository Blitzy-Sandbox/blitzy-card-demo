package com.vsergeychik.carddemo.common;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A mutable, fixed-length byte span standing in for a COBOL record area, addressed by absolute
 * 0-based offset.
 *
 * <h2>Position in the module</h2>
 * This is the <strong>lower</strong> of the two fixed-width layers and the root of this module's
 * dependency graph. It imports nothing from this repository - not {@code FixedWidthCodec}, not
 * {@code CobolDecimal}, not the sibling configuration package that resolves the code pages, and no
 * part of Spring. In particular the charset arrives as a constructor argument rather than through a
 * configuration import, because importing the configuration package would invert the foundation and
 * close a dependency cycle around it. The sibling {@code FixedWidthCodec} sits <em>above</em> this
 * class and adds PICTURE semantics:
 * converting a zoned {@code DISPLAY} field to and from a decimal value, applying the trailing-byte
 * sign overpunch, applying report edit masks, and choosing the direction in which an over-wide
 * {@code MOVE} truncates. The division of labour is deliberate and one-directional:
 * <ul>
 *   <li>this class knows <em>where</em> a field sits, <em>how wide</em> it is, and the
 *       <em>category-level</em> alignment and padding of a literal placed into it;</li>
 *   <li>the layer above knows what the bytes of a field <em>mean</em>.</li>
 * </ul>
 * Nothing here parses or formats a number, so no numeric conversion can be duplicated between the
 * two layers and then quietly diverge.
 *
 * <h2>Hand-written by mandate</h2>
 * Every offset is an explicit, reviewable integer. No copybook parser, no annotation-and-reflection
 * mapper, and no auto-aligning buffer abstraction is used, so a reviewer holding the copybook open
 * beside a layout declaration can confirm each offset by eye and diff it line by line.
 *
 * <h2>The charset is always supplied by the caller, never assumed</h2>
 * A {@link Charset} is a mandatory constructor argument. It is never derived from the platform, and
 * no method falls back to a platform-wide setting. That the charset is needed at
 * <em>construction</em> time, not merely at conversion time, is a measured fact rather than a
 * stylistic preference: the pad bytes themselves are code-page dependent.
 * <table border="1">
 *   <caption>Measured pad bytes</caption>
 *   <tr><th>Code page</th><th>space</th><th>zero</th></tr>
 *   <tr><td>{@code IBM037} (EBCDIC)</td><td>{@code 0x40}</td><td>{@code 0xF0}</td></tr>
 *   <tr><td>{@code US-ASCII}</td><td>{@code 0x20}</td><td>{@code 0x30}</td></tr>
 * </table>
 * A hard-coded {@code 0x20} would therefore corrupt every pad byte of an EBCDIC record. Both pad
 * bytes are consequently derived from the supplied charset once, at construction, and the charset
 * is rejected outright if it does not encode either character to exactly one byte - a fixed-width
 * record area cannot be addressed by absolute offset under a multi-byte code page. Holding the
 * charset as one immutable per-instance value also means a caller cannot mix code pages inside a
 * single record area, which would be a parity defect that is very hard to localise afterwards.
 *
 * <h2>{@code FILLER} is a first-class span</h2>
 * {@code FILLER} is never an implicit gap inferred from the distance between two named fields; it
 * is a positioned, length-bearing entry in the layout exactly like any other field. Omitting it
 * makes the record's total width wrong, which in a fixed-width file shifts every subsequent byte
 * offset in the entire dataset. {@link RecordLayout} therefore refuses to be constructed unless the
 * declared spans are contiguous from offset 0 and their total is exactly the declared record
 * length, which is what turns "the {@code FILLER} was dropped" from a silent data corruption into
 * an immediate, precisely located failure. Every persisted layout in this system ends in one:
 * {@code CVACT01Y} ends {@code FILLER X(178)}, {@code CVACT02Y} {@code FILLER X(59)},
 * {@code CVACT03Y} {@code FILLER X(14)}, {@code CVTRA05Y} {@code FILLER X(20)} and
 * {@code CVCUS01Y} {@code FILLER X(168)}.
 *
 * <h2>A {@code FILLER} emits its declared {@code VALUE}, and only otherwise a pad</h2>
 * The general guidance that {@code FILLER} is "emitted as spaces" holds for the persisted record
 * copybooks but is <strong>not</strong> universally true, and this class supports the exception
 * rather than normalising it away. {@code app/cpy/CSDAT01Y.cpy} declares separator fillers that
 * carry explicit non-space literals:
 * <pre>
 *   05 WS-CURDATE-MM-DD-YY.
 *     10  WS-CURDATE-MM             PIC 9(02).
 *     10  FILLER                    PIC X(01) VALUE '/'.
 *     10  WS-CURDATE-DD             PIC 9(02).
 *     10  FILLER                    PIC X(01) VALUE '/'.
 *     10  WS-CURDATE-YY             PIC 9(02).
 * </pre>
 * and likewise {@code ':'} in {@code WS-CURTIME-HH-MM-SS} and {@code '-'}, {@code ' '} and
 * {@code '.'} in {@code WS-TIMESTAMP}. Blanket space-filling would blank every date and time
 * separator in the system, turning {@code 12/25/24} into {@code 12 25 24}. The rule implemented
 * here is therefore: <em>a span emits its declared literal when one is present, and a pad byte only
 * when none is</em>. The literal is not restricted to {@code FILLER}: {@code app/cpy/CVCRD01Y.cpy}
 * declares {@code CC-ACCT-ID PIC X(11) VALUE SPACES} on a named field, and
 * {@code app/cpy/COMEN02Y.cpy} declares {@code FILLER PIC 9(02) VALUE 1} - a <em>numeric</em>
 * filler literal, which is why placement follows the span's {@link PictureKind} rather than a
 * single fixed rule.
 *
 * <h2>Which pad byte, when no literal is declared</h2>
 * {@link #initialise(RecordLayout)} follows the COBOL {@code INITIALIZE} convention: a numeric
 * {@code DISPLAY} span with no literal is filled with the charset's zero byte and an alphanumeric
 * or {@code FILLER} span with the charset's space byte. That is what makes the initialised
 * {@code WS-CURDATE-MM-DD-YY} group read {@code 00/00/00} rather than {@code   /  /  }. A bare
 * allocation through {@link #FixedWidthRecord(int, Charset)} is space-filled throughout, because a
 * COBOL record area is space-filled and because space-filling is what keeps the remaining bytes
 * correct in the common case where a trailing {@code FILLER} carries no literal.
 *
 * <h2>A signed field has no sign byte of its own</h2>
 * {@code PIC S9(p)V99} occupies exactly {@code p + 2} bytes. The sign is overpunched into the
 * trailing byte under the standard zoned-decimal rules and consumes no byte of its own. This is
 * proven twice over by arithmetic rather than asserted:
 * <ul>
 *   <li>{@code CVACT01Y} sums to its documented {@code RECLN 300} only when each of its five
 *       {@code S9(10)V99} fields occupies 12 bytes. Reserving a sign byte for each would give 305.</li>
 *   <li>{@code CVTRA05Y} sums to its documented {@code RECLN = 350} only when {@code TRAN-AMT
 *       PIC S9(09)V99} occupies 11 bytes.</li>
 * </ul>
 * {@link FieldSpan#signedScaled(String, int, int, int)} computes the width from the digit counts for
 * exactly this reason, so no caller can reintroduce a phantom sign byte by hand.
 *
 * <h2>No packed-decimal support, by design</h2>
 * {@code COMP-3} and {@code PACKED-DECIMAL} appear <strong>zero</strong> times in all 28 copybooks
 * under {@code app/cpy}; packed decimal occurs only in {@code WORKING-STORAGE} in five programs, for
 * counters and intermediates that are never persisted. Every persisted numeric field is therefore
 * zoned {@code DISPLAY} - one digit per byte - and this class deliberately contains no nibble
 * packing or unpacking whatsoever. The omission is a considered response to the verified evidence,
 * not an oversight: speculative packed-decimal handling would be dead code that dilutes the
 * branch-coverage ratio while buying no behaviour.
 *
 * <h2>{@code OCCURS} is 1-based in COBOL and 0-based in Java</h2>
 * This mismatch is the single most common defect in a migration of this kind, so the conversion is
 * never left to inline caller arithmetic. Use
 * {@link #occursElementOffsetOneBased(int, int, int, int)} or
 * {@link #occursElementSpan(FieldSpan, int, int, String, PictureKind)}; both name the 1-based
 * convention explicitly and reject index 0 and index greater than the occurrence count.
 *
 * <h2>{@code REDEFINES} shares one span, never a copy</h2>
 * An overlay created by {@link FieldSpan#redefinedAs(String, PictureKind)} reuses the redefined
 * span's offset and length under a new name and kind. Because both descriptors address the same
 * backing bytes, a write through either view is immediately visible through the other, exactly as
 * in COBOL. An overlay never advances the layout cursor and never contributes to the record total.
 *
 * <h2>Thread safety and encapsulation</h2>
 * The byte span is mutable per instance, because that is what a COBOL record area is. There is no
 * mutable static state anywhere in this class, so instances are independent, and the internal array
 * is never handed out: {@link #toByteArray()} and {@link #readBytes(int, int)} both return copies.
 * An instance is not safe for concurrent mutation and is not intended to be shared across threads;
 * confine one to the request, step or chunk that owns it.
 *
 * @see #initialise(RecordLayout)
 * @see RecordLayout
 * @see FieldSpan
 */
public final class FixedWidthRecord {

    /**
     * The category of a span, sufficient to decide how a literal is placed within it and which pad
     * byte fills it when no literal is declared. This is a <em>category</em> discriminator, not a
     * parsed PICTURE string: the digit counts, scale, sign handling and edit masks all belong to the
     * layer above.
     */
    public enum PictureKind {

        /**
         * {@code PIC X(n)} - character data. Left justified, padded on the right with the charset's
         * space byte, exactly as a COBOL alphanumeric {@code MOVE} pads.
         */
        ALPHANUMERIC,

        /**
         * {@code PIC 9(n)} - unsigned zoned {@code DISPLAY} digits, one digit per byte. Right
         * justified, padded on the left with the charset's zero byte, exactly as a COBOL numeric
         * {@code MOVE} pads.
         */
        UNSIGNED_NUMERIC,

        /**
         * {@code PIC S9(p)V(s)} - signed zoned {@code DISPLAY} digits occupying {@code p + s} bytes,
         * with the sign overpunched into the trailing byte and no byte of its own. Aligned and
         * padded as {@link #UNSIGNED_NUMERIC}; interpreting the overpunch is the upper layer's work.
         */
        SIGNED_SCALED,

        /**
         * {@code FILLER} - an unnamed reserved span. Treated as character data for alignment and
         * padding, and, critically, still a real positioned span that must be declared and emitted.
         * A {@code FILLER} whose copybook declaration carries a numeric picture and a numeric
         * literal, as in {@code app/cpy/COMEN02Y.cpy}, is declared with a numeric kind and the name
         * {@code FILLER}; the name remains non-referable either way.
         */
        FILLER;

        /**
         * Whether this kind holds zoned {@code DISPLAY} digits, and therefore right justifies and
         * pads with the zero byte.
         *
         * @return {@code true} for {@link #UNSIGNED_NUMERIC} and {@link #SIGNED_SCALED}
         */
        public boolean numericDisplay() {
            return this == UNSIGNED_NUMERIC || this == SIGNED_SCALED;
        }

        /**
         * Whether a literal placed into a span of this kind is left justified within it. Character
         * data is left justified and numeric data is right justified, mirroring COBOL's own
         * {@code VALUE} clause and {@code MOVE} alignment.
         *
         * @return {@code true} for character kinds, {@code false} for numeric {@code DISPLAY} kinds
         */
        public boolean leftJustified() {
            return !numericDisplay();
        }

        /**
         * Whether this kind denotes a reserved {@code FILLER} span.
         *
         * @return {@code true} only for {@link #FILLER}
         */
        public boolean filler() {
            return this == FILLER;
        }
    }

    /**
     * The reserved COBOL name for an unnamed span. {@code FILLER} may legitimately be declared many
     * times in one layout and can never be referenced, so it is excluded from duplicate-name
     * detection and from name lookup.
     */
    private static final String FILLER_NAME = "FILLER";

    /**
     * One positioned, length-bearing entry in a record layout: the immutable descriptor of a single
     * copybook data item.
     *
     * <p>The field name is carried <strong>verbatim</strong> as the copybook spells it, hyphens and
     * all, including misspellings. {@code app/cpy/CVACT01Y.cpy} declares
     * {@code ACCT-EXPIRAION-DATE} and {@code app/cpy/CVACT02Y.cpy} declares
     * {@code CARD-EXPIRAION-DATE}; both retain the missing {@code T}. The names are part of the
     * migration contract because the parity differ compares field by field <em>by name</em>, so
     * silently correcting one would make a real difference invisible.
     *
     * @param name         the copybook item name exactly as declared, or {@code FILLER} for a
     *                     reserved span; never blank
     * @param offset       the absolute 0-based byte offset of the span within the record; never
     *                     negative
     * @param length       the width of the span in bytes; at least 1. For
     *                     {@link PictureKind#SIGNED_SCALED} this is {@code p + s} with no sign byte
     * @param kind         the span's category, which decides literal alignment and the default pad
     * @param initialValue the declared {@code VALUE} literal, or {@code null} when the copybook
     *                     declares none. Never longer than {@code length}
     * @param redefinition {@code true} when this descriptor is a {@code REDEFINES} overlay over
     *                     storage that another descriptor already accounts for. An overlay never
     *                     advances the layout cursor and never contributes to the record total
     */
    public record FieldSpan(String name,
                            int offset,
                            int length,
                            PictureKind kind,
                            String initialValue,
                            boolean redefinition) {

        /**
         * Validates the descriptor at declaration time, so a transcription error in a layout is
         * caught where the layout is written rather than where a byte later reads back wrong.
         *
         * @throws NullPointerException     if {@code name} or {@code kind} is {@code null}
         * @throws IllegalArgumentException if {@code name} is blank, {@code offset} is negative,
         *                                  {@code length} is below 1, or {@code initialValue} is
         *                                  longer than {@code length}
         */
        public FieldSpan {
            Objects.requireNonNull(name, "FieldSpan name is required and must be the copybook item "
                    + "name verbatim, or FILLER for a reserved span");
            Objects.requireNonNull(kind, "FieldSpan kind is required; state the PICTURE category "
                    + "explicitly for field '" + name + "'");
            if (name.isBlank()) {
                throw new IllegalArgumentException("FieldSpan name must not be blank; use FILLER "
                        + "for a reserved span");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("FieldSpan '" + name + "' declares a negative "
                        + "offset " + offset + "; offsets are absolute and 0-based");
            }
            if (length < 1) {
                throw new IllegalArgumentException("FieldSpan '" + name + "' declares length "
                        + length + "; every copybook item occupies at least 1 byte");
            }
            if (initialValue != null && initialValue.length() > length) {
                throw new IllegalArgumentException("FieldSpan '" + name + "' declares a VALUE "
                        + "literal of " + initialValue.length() + " character(s) but is only "
                        + length + " byte(s) wide");
            }
        }

        /**
         * Declares a {@code PIC X(n)} character field.
         *
         * @param name   the copybook item name, verbatim
         * @param offset the absolute 0-based byte offset
         * @param length {@code n}, the declared character count
         * @return the descriptor
         */
        public static FieldSpan alphanumeric(String name, int offset, int length) {
            return new FieldSpan(name, offset, length, PictureKind.ALPHANUMERIC, null, false);
        }

        /**
         * Declares a {@code PIC 9(n)} unsigned zoned {@code DISPLAY} field, one digit per byte.
         *
         * @param name   the copybook item name, verbatim
         * @param offset the absolute 0-based byte offset
         * @param length {@code n}, the declared digit count
         * @return the descriptor
         */
        public static FieldSpan unsignedNumeric(String name, int offset, int length) {
            return new FieldSpan(name, offset, length, PictureKind.UNSIGNED_NUMERIC, null, false);
        }

        /**
         * Declares a {@code PIC S9(p)V(s)} signed zoned {@code DISPLAY} field. The width is computed
         * as {@code integerDigits + fractionDigits} and <strong>no sign byte is reserved</strong>,
         * because the sign is overpunched into the trailing byte. Passing the digit counts rather
         * than a byte width is what makes it impossible to reintroduce a phantom sign byte:
         * {@code signedScaled("ACCT-CURR-BAL", 12, 10, 2)} can only ever be 12 bytes wide, which is
         * the width at which {@code CVACT01Y} sums to its documented 300.
         *
         * @param name           the copybook item name, verbatim
         * @param offset         the absolute 0-based byte offset
         * @param integerDigits  {@code p}, the digits before the implied decimal point; at least 1
         * @param fractionDigits {@code s}, the digits after it; never negative, and 0 for a scaleless
         *                       signed field
         * @return the descriptor, {@code integerDigits + fractionDigits} bytes wide
         * @throws IllegalArgumentException if {@code integerDigits} is below 1 or
         *                                  {@code fractionDigits} is negative
         */
        public static FieldSpan signedScaled(String name,
                                             int offset,
                                             int integerDigits,
                                             int fractionDigits) {
            if (integerDigits < 1) {
                throw new IllegalArgumentException("Signed field '" + name + "' declares "
                        + integerDigits + " integer digit(s); PIC S9(p)V(s) requires p of at least 1");
            }
            if (fractionDigits < 0) {
                throw new IllegalArgumentException("Signed field '" + name + "' declares "
                        + fractionDigits + " fraction digit(s); s must not be negative");
            }
            return new FieldSpan(name, offset, integerDigits + fractionDigits,
                    PictureKind.SIGNED_SCALED, null, false);
        }

        /**
         * Declares a {@code FILLER PIC X(n)} reserved span carrying no {@code VALUE}, which
         * therefore initialises to the charset's space byte.
         *
         * @param offset the absolute 0-based byte offset
         * @param length {@code n}, the reserved byte count
         * @return the descriptor, named {@code FILLER}
         */
        public static FieldSpan filler(int offset, int length) {
            return new FieldSpan(FILLER_NAME, offset, length, PictureKind.FILLER, null, false);
        }

        /**
         * Declares a {@code FILLER PIC X(n) VALUE '...'} reserved span that carries a literal, such
         * as the {@code '/'} and {@code ':'} separators of {@code app/cpy/CSDAT01Y.cpy}. The literal
         * is emitted on initialisation instead of a pad byte.
         *
         * @param offset       the absolute 0-based byte offset
         * @param length       {@code n}, the reserved byte count
         * @param initialValue the declared literal, no longer than {@code length}
         * @return the descriptor, named {@code FILLER}
         * @throws NullPointerException if {@code initialValue} is {@code null}; use
         *                              {@link #filler(int, int)} for a span with no literal
         */
        public static FieldSpan filler(int offset, int length, String initialValue) {
            Objects.requireNonNull(initialValue, "A FILLER VALUE literal is required here; call "
                    + "filler(offset, length) for a FILLER that declares none");
            return new FieldSpan(FILLER_NAME, offset, length, PictureKind.FILLER, initialValue,
                    false);
        }

        /**
         * Declares a {@code REDEFINES} overlay directly at an absolute offset. Use this form for a
         * group redefinition, where the redefined item is a group rather than a single elementary
         * field and so has no descriptor of its own in a flattened layout -
         * {@code app/cpy/CSDAT01Y.cpy}'s {@code WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08)} covers
         * the three elementary items {@code WS-CURDATE-YEAR}, {@code WS-CURDATE-MONTH} and
         * {@code WS-CURDATE-DAY}. Where the redefined item does have its own descriptor, prefer
         * {@link #redefinedAs(String, PictureKind)}, which cannot get the offset wrong.
         *
         * <p>{@link RecordLayout} verifies that the overlay falls entirely inside storage already
         * declared ahead of it.
         *
         * @param name   the redefining item's name, verbatim
         * @param offset the absolute 0-based byte offset of the redefined storage
         * @param length the width of the overlay in bytes
         * @param kind   the category through which the overlay views those bytes
         * @return the overlay descriptor
         */
        public static FieldSpan redefining(String name, int offset, int length, PictureKind kind) {
            return new FieldSpan(name, offset, length, kind, null, true);
        }

        /**
         * Returns a copy of this descriptor carrying the given {@code VALUE} literal. Supports named
         * fields that declare one, such as {@code CC-ACCT-ID PIC X(11) VALUE SPACES} in
         * {@code app/cpy/CVCRD01Y.cpy}, and numeric fillers such as {@code FILLER PIC 9(02) VALUE 1}
         * in {@code app/cpy/COMEN02Y.cpy}.
         *
         * @param literal the declared literal, no longer than this span
         * @return a new descriptor identical to this one but carrying {@code literal}
         * @throws NullPointerException     if {@code literal} is {@code null}
         * @throws IllegalArgumentException if {@code literal} is longer than this span
         */
        public FieldSpan withInitialValue(String literal) {
            Objects.requireNonNull(literal, "VALUE literal is required for field '" + name + "'");
            return new FieldSpan(name, offset, length, kind, literal, redefinition);
        }

        /**
         * Returns a {@code REDEFINES} overlay covering exactly this span's bytes under a new name and
         * category - the {@code CC-ACCT-ID PIC X(11)} / {@code CC-ACCT-ID-N PIC 9(11)} pair of
         * {@code app/cpy/CVCRD01Y.cpy}. Both descriptors address one backing span, so a write
         * through either view is visible through the other.
         *
         * @param newName the redefining item's name, verbatim
         * @param newKind the category through which the overlay views the same bytes
         * @return the overlay descriptor, at this span's offset and length
         */
        public FieldSpan redefinedAs(String newName, PictureKind newKind) {
            return new FieldSpan(newName, offset, length, newKind, null, true);
        }

        /**
         * Returns a {@code REDEFINES} overlay starting at this span's offset but narrower than it,
         * for the case where the redefining item covers only the leading bytes of the redefined item.
         *
         * @param newName   the redefining item's name, verbatim
         * @param newKind   the category through which the overlay views those bytes
         * @param newLength the overlay's width; at least 1 and never wider than this span
         * @return the overlay descriptor
         * @throws IllegalArgumentException if {@code newLength} exceeds this span's length
         */
        public FieldSpan redefinedAs(String newName, PictureKind newKind, int newLength) {
            if (newLength > length) {
                throw new IllegalArgumentException("REDEFINES overlay '" + newName + "' is "
                        + newLength + " byte(s) wide but redefines '" + name + "', which is only "
                        + length + " byte(s) wide; an overlay must not exceed the span it redefines");
            }
            return new FieldSpan(newName, offset, newLength, newKind, null, true);
        }

        /**
         * Whether this span declares a {@code VALUE} literal. When it does, initialisation emits the
         * literal; when it does not, initialisation emits the pad byte for this span's kind.
         *
         * @return {@code true} when a literal is declared
         */
        public boolean hasInitialValue() {
            return initialValue != null;
        }

        /**
         * The exclusive end offset of this span, that is {@code offset + length}. For the last
         * storage span of a well-formed layout this equals the record length.
         *
         * @return the offset one byte past the end of this span
         */
        public int endOffsetExclusive() {
            return offset + length;
        }

        /**
         * A precise, single-line description used in layout and bounds failure messages, so a
         * failure names the offending descriptor rather than only a numeric offset.
         *
         * @return for example {@code ACCT-CURR-BAL (SIGNED_SCALED, offset 12, length 12)}
         */
        public String describe() {
            return name + " (" + kind + ", offset " + offset + ", length " + length
                    + (redefinition ? ", REDEFINES overlay)" : ")");
        }
    }

    /**
     * An ordered, self-checking description of one complete record layout: the declared record length
     * together with every span that makes it up, in copybook declaration order.
     *
     * <h2>Why this type refuses to be constructed when the arithmetic is wrong</h2>
     * The layout self-check runs in the constructor, so a layout either exists and is provably
     * consistent or does not exist at all. It enforces, in one place:
     * <ul>
     *   <li>storage spans are <strong>contiguous from offset 0</strong> - no gap and no overlap;</li>
     *   <li>every {@code REDEFINES} overlay falls entirely inside storage already declared ahead of
     *       it, and neither advances the cursor nor contributes to the total;</li>
     *   <li>the storage spans sum to <strong>exactly</strong> the declared record length;</li>
     *   <li>no referable name is declared twice ({@code FILLER} is exempt, being non-referable).</li>
     * </ul>
     * That single total check is what converts two whole classes of silent offset defect into an
     * immediate, precisely located failure. Dropping {@code CVACT01Y}'s trailing
     * {@code FILLER X(178)} leaves the layout 178 bytes short of 300 and fails here rather than
     * corrupting every subsequent record in the file. Reserving a sign byte on each of that record's
     * five {@code S9(10)V99} fields makes the layout 305 bytes and likewise fails here rather than
     * shifting every field after the first balance. Declaring a layout is therefore the cheapest
     * possible way to prove a record's geometry, and every record model in this system should declare
     * one.
     *
     * <p>Note that the record length is a parameter, supplied by the caller from configuration
     * (the {@code record-length} of the relevant dataset binding) or from the copybook a model
     * represents. This type hard-codes no dataset width of its own.
     *
     * @param recordLength the declared total width of the record in bytes; at least 1
     * @param spans        every span in copybook declaration order, storage and overlays alike;
     *                     never empty, and defensively copied so the layout cannot be mutated
     *                     afterwards
     */
    public record RecordLayout(int recordLength, List<FieldSpan> spans) {

        /**
         * Copies the span list defensively and then runs the full self-check.
         *
         * @throws NullPointerException     if {@code spans} is {@code null} or contains {@code null}
         * @throws IllegalArgumentException if {@code recordLength} is below 1, {@code spans} is
         *                                  empty, the spans are not contiguous from offset 0, an
         *                                  overlay lies outside already-declared storage, a referable
         *                                  name is declared twice, or the storage spans do not sum to
         *                                  {@code recordLength}
         */
        public RecordLayout {
            Objects.requireNonNull(spans, "A record layout requires its span list; declare every "
                    + "field and every FILLER explicitly");
            spans = List.copyOf(spans);
            if (recordLength < 1) {
                throw new IllegalArgumentException("Declared record length " + recordLength
                        + " is not a valid record width; a record occupies at least 1 byte");
            }
            if (spans.isEmpty()) {
                throw new IllegalArgumentException("A record layout of declared length "
                        + recordLength + " declares no spans; every byte must be accounted for, "
                        + "FILLER included");
            }
            verifyGeometry(recordLength, spans);
        }

        /**
         * Declares a layout from spans given in copybook declaration order.
         *
         * @param recordLength the declared total width of the record in bytes
         * @param spans        every span in declaration order, storage and overlays alike
         * @return the validated layout
         * @throws NullPointerException     if {@code spans} is {@code null} or contains {@code null}
         * @throws IllegalArgumentException if the layout does not describe exactly
         *                                  {@code recordLength} bytes
         */
        public static RecordLayout of(int recordLength, FieldSpan... spans) {
            Objects.requireNonNull(spans, "A record layout requires its span list");
            return new RecordLayout(recordLength, List.of(spans));
        }

        /**
         * The self-check. Kept as one traversal so that the first offending descriptor is reported,
         * rather than a summary that leaves the reader to find it.
         */
        private static void verifyGeometry(int recordLength, List<FieldSpan> spans) {
            Set<String> referableNames = new LinkedHashSet<>();
            int cursor = 0;
            for (FieldSpan span : spans) {
                if (!FILLER_NAME.equalsIgnoreCase(span.name()) && !referableNames.add(span.name())) {
                    throw new IllegalArgumentException("Layout declares the name '" + span.name()
                            + "' more than once at " + span.describe() + "; only FILLER may repeat");
                }
                if (span.redefinition()) {
                    if (span.endOffsetExclusive() > cursor) {
                        throw new IllegalArgumentException("REDEFINES overlay " + span.describe()
                                + " reaches byte " + span.endOffsetExclusive() + " but only " + cursor
                                + " byte(s) of storage are declared ahead of it; an overlay must "
                                + "redefine storage that already exists");
                    }
                } else if (span.offset() < cursor) {
                    throw new IllegalArgumentException("Layout overlap at " + span.describe()
                            + ": the preceding spans already occupy bytes 0 to " + (cursor - 1)
                            + ", so this span overlaps " + (cursor - span.offset()) + " byte(s). "
                            + "Declare it as a REDEFINES overlay if the overlap is intended");
                } else if (span.offset() > cursor) {
                    throw new IllegalArgumentException("Layout gap of "
                            + (span.offset() - cursor) + " byte(s) before " + span.describe()
                            + ": the preceding spans end at byte " + cursor + ". Every byte must be "
                            + "declared, so an unnamed gap must be declared as FILLER");
                } else {
                    cursor = span.endOffsetExclusive();
                }
            }
            if (cursor != recordLength) {
                throw new IllegalArgumentException(cursor < recordLength
                        ? "Layout declares " + cursor + " byte(s) of storage but the record length is "
                                + recordLength + "; it is " + (recordLength - cursor)
                                + " byte(s) short. A dropped trailing FILLER is the usual cause"
                        : "Layout declares " + cursor + " byte(s) of storage but the record length is "
                                + recordLength + "; it is " + (cursor - recordLength)
                                + " byte(s) too long. A sign byte reserved for a PIC S9 field is the "
                                + "usual cause - the sign is overpunched into the trailing byte and "
                                + "occupies no byte of its own");
            }
        }

        /**
         * The spans that actually occupy storage, in declaration order, excluding every
         * {@code REDEFINES} overlay. These are the spans {@link #initialise(RecordLayout)} fills and
         * the spans whose lengths sum to {@link #recordLength()}.
         *
         * @return an immutable list of the storage spans
         */
        public List<FieldSpan> storageSpans() {
            return select(false);
        }

        /**
         * The {@code REDEFINES} overlays, in declaration order. Overlays are alternative views of
         * storage that {@link #storageSpans()} already accounts for, so they are never initialised
         * separately and never contribute to the record length.
         *
         * @return an immutable list of the overlay spans, empty when the layout declares none
         */
        public List<FieldSpan> redefinitions() {
            return select(true);
        }

        private List<FieldSpan> select(boolean overlays) {
            List<FieldSpan> selected = new ArrayList<>(spans.size());
            for (FieldSpan span : spans) {
                if (span.redefinition() == overlays) {
                    selected.add(span);
                }
            }
            return List.copyOf(selected);
        }

        /**
         * Looks up a span by its exact copybook name, overlays included. {@code FILLER} spans are
         * skipped because {@code FILLER} is not a referable COBOL name.
         *
         * @param name the copybook item name, verbatim and case-sensitive
         * @return the matching span
         * @throws NullPointerException     if {@code name} is {@code null}
         * @throws IllegalArgumentException if this layout declares no such referable name
         */
        public FieldSpan span(String name) {
            FieldSpan found = find(name);
            if (found == null) {
                throw new IllegalArgumentException("Layout of declared length " + recordLength
                        + " declares no field named '" + name + "' among its " + spans.size()
                        + " span(s); names are case-sensitive and FILLER is not referable");
            }
            return found;
        }

        /**
         * Whether this layout declares a referable span with the given exact name.
         *
         * @param name the copybook item name, verbatim and case-sensitive
         * @return {@code true} when the name is declared
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public boolean hasSpan(String name) {
            return find(name) != null;
        }

        private FieldSpan find(String name) {
            Objects.requireNonNull(name, "A field name is required to look up a span");
            for (FieldSpan candidate : spans) {
                if (!FILLER_NAME.equalsIgnoreCase(candidate.name())
                        && candidate.name().equals(name)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Allocates a record over this layout and initialises it, emitting each span's declared
         * {@code VALUE} literal where one exists and the pad byte for the span's kind where none
         * does.
         *
         * @param charset the code page of the record's data, supplied explicitly by the caller
         * @return a newly allocated, initialised record of this layout's declared length
         * @throws NullPointerException     if {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code charset} does not encode the space and zero
         *                                  characters to exactly one byte each
         */
        public FixedWidthRecord newRecord(Charset charset) {
            return forLayout(this, charset);
        }
    }

    /**
     * The record area itself. Mutable by design - a COBOL record area is - but strictly
     * per-instance, and never handed out: every accessor that exposes bytes returns a copy.
     */
    private final byte[] area;

    /** The declared record width in bytes, fixed for the life of the instance. */
    private final int recordLength;

    /** The code page of this record's data, supplied explicitly by the caller and never derived. */
    private final Charset charset;

    /** The space byte in {@link #charset} - {@code 0x40} under EBCDIC, {@code 0x20} under ASCII. */
    private final byte spaceByte;

    /** The zero byte in {@link #charset} - {@code 0xF0} under EBCDIC, {@code 0x30} under ASCII. */
    private final byte zeroByte;

    /**
     * Allocates a space-filled record area of the given width.
     *
     * <p>The area is filled with {@code charset}'s space byte, matching a COBOL record area and
     * keeping the bytes correct in the common case of a trailing {@code FILLER} that declares no
     * literal. To apply the per-kind {@code INITIALIZE} convention instead, use
     * {@link #forLayout(RecordLayout, Charset)} or call {@link #initialise(RecordLayout)}.
     *
     * @param recordLength the declared record width in bytes, supplied by the caller from the
     *                     relevant dataset binding or copybook; at least 1
     * @param charset      the code page of the record's data, supplied explicitly. It must encode the
     *                     space and zero characters to exactly one byte each, because the area is
     *                     addressed by absolute byte offset
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code recordLength} is below 1, or if {@code charset} is
     *                                  not a single-byte code page for the space and zero characters
     */
    public FixedWidthRecord(int recordLength, Charset charset) {
        this.charset = Objects.requireNonNull(charset, "A charset must be supplied explicitly: the "
                + "pad bytes themselves are code-page dependent, so a fixed-width record area can "
                + "never be allocated against an assumed encoding");
        if (recordLength < 1) {
            throw new IllegalArgumentException("Record length " + recordLength
                    + " is not a valid record width; a record occupies at least 1 byte");
        }
        this.spaceByte = singleByte(' ', charset);
        this.zeroByte = singleByte('0', charset);
        this.recordLength = recordLength;
        this.area = new byte[recordLength];
        Arrays.fill(this.area, this.spaceByte);
    }

    /**
     * Wraps existing record bytes, taking a defensive copy of them.
     *
     * <p>The declared record length is a separate, explicit parameter rather than being inferred from
     * the array, precisely so that a short or over-long row read from a dataset is rejected here
     * instead of silently redefining the record's geometry. The fixture
     * {@code app/data/ASCII/cardxref.txt} is 36 bytes per row where {@code CVACT03Y} declares 50,
     * because the fixture omits the trailing {@code FILLER X(14)}; such a row must be right-padded to
     * its declared width by the caller before it reaches this method, and this check is what forces
     * that to be a conscious act.
     *
     * @param bytes        the record bytes; its length must equal {@code recordLength} exactly
     * @param recordLength the declared record width in bytes
     * @param charset      the code page of the record's data, supplied explicitly
     * @return a record holding a copy of {@code bytes}
     * @throws NullPointerException     if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} differs from {@code recordLength}, or
     *                                  if {@code charset} is not a single-byte code page for the
     *                                  space and zero characters
     */
    public static FixedWidthRecord copyOf(byte[] bytes, int recordLength, Charset charset) {
        Objects.requireNonNull(bytes, "Record bytes are required; call the (int, Charset) "
                + "constructor to allocate an empty, space-filled record area instead");
        if (bytes.length != recordLength) {
            throw new IllegalArgumentException("Supplied " + bytes.length + " byte(s) for a record "
                    + "declared as " + recordLength + " byte(s) wide; a fixed-width record must be "
                    + "exactly its declared width, so pad or reject the row before wrapping it");
        }
        FixedWidthRecord record = new FixedWidthRecord(recordLength, charset);
        System.arraycopy(bytes, 0, record.area, 0, recordLength);
        return record;
    }

    /**
     * Allocates a record over a layout and initialises every storage span from it, emitting each
     * declared {@code VALUE} literal and otherwise the pad byte for the span's kind.
     *
     * @param layout  the validated layout, whose declared record length becomes the record's width
     * @param charset the code page of the record's data, supplied explicitly
     * @return a newly allocated, initialised record
     * @throws NullPointerException     if {@code layout} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the
     *                                  space and zero characters
     */
    public static FixedWidthRecord forLayout(RecordLayout layout, Charset charset) {
        Objects.requireNonNull(layout, "A record layout is required to initialise a record from "
                + "its declared spans");
        FixedWidthRecord record = new FixedWidthRecord(layout.recordLength(), charset);
        record.initialise(layout);
        return record;
    }

    /**
     * Encodes one character under an explicitly named code page and insists the result is a single
     * byte, because a fixed-width area addressed by absolute offset cannot tolerate a variable-width
     * encoding of its pad characters.
     */
    private static byte singleByte(char character, Charset charset) {
        byte[] encoded = String.valueOf(character).getBytes(charset);
        if (encoded.length != 1) {
            throw new IllegalArgumentException("Charset " + charset.name() + " encodes '" + character
                    + "' to " + encoded.length + " byte(s). A fixed-width record area is addressed "
                    + "by absolute byte offset, so it requires a code page that encodes the space "
                    + "and zero characters to exactly one byte each - IBM037 for EBCDIC data and "
                    + "US-ASCII for the text fixtures both qualify");
        }
        return encoded[0];
    }

    /**
     * The declared record width in bytes. Callers and tests assert this against the copybook total -
     * 300 for the account record, 350 for a transaction, 150 for a card, 50 for a cross-reference,
     * 500 for a customer - so that a geometry error surfaces as a width mismatch.
     *
     * @return the record width in bytes
     */
    public int recordLength() {
        return recordLength;
    }

    /**
     * The code page of this record's data, as supplied by the caller at construction. Exposed so the
     * layer above can encode and decode field values under the same explicitly chosen encoding
     * rather than resolving one of its own.
     *
     * @return the record's charset, never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    /**
     * The space byte under this record's charset - the pad for character spans.
     *
     * @return {@code 0x40} under EBCDIC, {@code 0x20} under ASCII
     */
    public byte spacePadByte() {
        return spaceByte;
    }

    /**
     * The zero byte under this record's charset - the pad for numeric {@code DISPLAY} spans.
     *
     * @return {@code 0xF0} under EBCDIC, {@code 0x30} under ASCII
     */
    public byte zeroPadByte() {
        return zeroByte;
    }

    /**
     * The pad byte a span of the given kind is filled with when it declares no {@code VALUE}: the
     * zero byte for numeric {@code DISPLAY} kinds and the space byte otherwise. This is the COBOL
     * {@code INITIALIZE} convention, and it is what makes an initialised
     * {@code WS-CURDATE-MM-DD-YY} group read {@code 00/00/00}.
     *
     * @param kind the span category
     * @return the pad byte under this record's charset
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public byte padByteFor(PictureKind kind) {
        Objects.requireNonNull(kind, "A PICTURE kind is required to choose a pad byte");
        return kind.numericDisplay() ? zeroByte : spaceByte;
    }

    /**
     * The whole record area as a fresh array. This is the serialised form written to a dataset.
     *
     * <p>A <strong>copy</strong> is returned, never the internal array, so mutating the result cannot
     * change the record behind its owner's back.
     *
     * @return a copy of all {@link #recordLength()} bytes
     */
    public byte[] toByteArray() {
        return area.clone();
    }

    /**
     * Validates that {@code [offset, offset + length)} lies wholly inside the record area. The
     * comparison is written as {@code offset > recordLength - length} rather than
     * {@code offset + length > recordLength} so that it cannot be defeated by integer overflow.
     */
    private void checkSpan(int offset, int length) {
        if (offset < 0) {
            throw new IndexOutOfBoundsException("Offset " + offset + " is negative; record offsets "
                    + "are absolute and 0-based (record length " + recordLength + ")");
        }
        if (length < 1) {
            throw new IndexOutOfBoundsException("Length " + length + " at offset " + offset
                    + " is not a usable span; every span covers at least 1 byte (record length "
                    + recordLength + ")");
        }
        if (offset > recordLength - length) {
            throw new IndexOutOfBoundsException("Span at offset " + offset + " of length " + length
                    + " reaches byte " + ((long) offset + length) + ", past the end of a record of "
                    + "length " + recordLength);
        }
    }

    /**
     * Extracts a sub-span of the record as a fresh array. Use it to lift a whole group for a
     * group-level move, or to read the bytes behind a {@code REDEFINES} overlay.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @return a copy of the {@code length} bytes at {@code offset}
     * @throws IndexOutOfBoundsException if the span falls outside the record, with the offset, the
     *                                   length and the record length all named
     */
    public byte[] readBytes(int offset, int length) {
        checkSpan(offset, length);
        return Arrays.copyOfRange(area, offset, offset + length);
    }

    /**
     * Replaces a sub-span of the record with the supplied bytes, whose length determines the span
     * replaced. The counterpart to {@link #readBytes(int, int)} for group-level moves and overlay
     * writes.
     *
     * @param offset the absolute 0-based byte offset
     * @param source the replacement bytes, copied in full; at least 1 byte long
     * @throws NullPointerException      if {@code source} is {@code null}
     * @throws IndexOutOfBoundsException if the resulting span falls outside the record
     */
    public void writeBytes(int offset, byte[] source) {
        Objects.requireNonNull(source, "Replacement bytes are required; call fill(offset, length, "
                + "byte) to blank a span instead");
        checkSpan(offset, source.length);
        System.arraycopy(source, 0, area, offset, source.length);
    }

    /**
     * Decodes a span as text under this record's charset.
     *
     * <p>The result is <strong>not</strong> trimmed. A COBOL {@code PIC X} field is space-padded to
     * its full width and that padding is part of the field's value, so trimming here would discard
     * bytes the parity differ compares. Trim only where the COBOL itself trims, and do it in the
     * layer that knows the field's PICTURE.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @return the decoded text, exactly {@code length} bytes' worth
     * @throws IndexOutOfBoundsException if the span falls outside the record
     */
    public String readString(int offset, int length) {
        checkSpan(offset, length);
        return new String(area, offset, length, charset);
    }

    /**
     * Writes text into a span, left justified and padded on the right with this record's space byte -
     * the COBOL alphanumeric convention. For a numeric {@code DISPLAY} span use
     * {@link #writeString(int, int, String, boolean, byte)} with {@code leftJustified} set to
     * {@code false} and {@link #zeroPadByte()}, or use {@link #writeSpan(FieldSpan, String)} and let
     * the descriptor decide.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @param value  the text to write; its encoded form must not exceed {@code length}
     * @throws NullPointerException      if {@code value} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside the record
     * @throws IllegalArgumentException  if {@code value} encodes to more than {@code length} bytes
     */
    public void writeString(int offset, int length, String value) {
        writeString(offset, length, value, true, spaceByte);
    }

    /**
     * Writes text into a span with the justification and pad byte stated explicitly.
     *
     * <p>An over-wide value is <strong>rejected, never truncated</strong>. COBOL truncates a
     * cross-width {@code MOVE} on the right for {@code PIC X} and on the left for {@code PIC 9}, so
     * the direction is a per-PICTURE decision that must be taken deliberately by the layer above. If
     * this method quietly chose one, a truncation defect would be indistinguishable from correct
     * behaviour.
     *
     * @param offset        the absolute 0-based byte offset
     * @param length        the span width in bytes; at least 1
     * @param value         the text to write; its encoded form must not exceed {@code length}
     * @param leftJustified {@code true} to place the value at the start of the span and pad after it,
     *                      {@code false} to place it at the end and pad before it
     * @param padByte       the byte filling the remainder of the span
     * @throws NullPointerException      if {@code value} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside the record
     * @throws IllegalArgumentException  if {@code value} encodes to more than {@code length} bytes
     */
    public void writeString(int offset, int length, String value, boolean leftJustified,
                            byte padByte) {
        Objects.requireNonNull(value, "A value is required; call fill(offset, length, byte) to blank "
                + "a span instead");
        checkSpan(offset, length);
        byte[] encoded = value.getBytes(charset);
        if (encoded.length > length) {
            throw new IllegalArgumentException("Value encodes to " + encoded.length + " byte(s) "
                    + "under " + charset.name() + " but the span at offset " + offset + " is only "
                    + length + " byte(s) wide. This layer never truncates: COBOL truncates on the "
                    + "right for PIC X and on the left for PIC 9, so the direction must be chosen "
                    + "deliberately for the target PICTURE before the value reaches here");
        }
        Arrays.fill(area, offset, offset + length, padByte);
        int start = leftJustified ? offset : offset + length - encoded.length;
        System.arraycopy(encoded, 0, area, start, encoded.length);
    }

    /**
     * Fills a span with a single repeated byte. Used to blank a span and to apply a pad byte.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @param value  the byte to repeat
     * @throws IndexOutOfBoundsException if the span falls outside the record
     */
    public void fill(int offset, int length, byte value) {
        checkSpan(offset, length);
        Arrays.fill(area, offset, offset + length, value);
    }

    /**
     * Decodes a descriptor's span as text, untrimmed.
     *
     * @param field the descriptor naming the span
     * @return the decoded text, exactly {@code field.length()} bytes' worth
     * @throws NullPointerException      if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if the descriptor's span falls outside this record
     */
    public String readSpan(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return readString(field.offset(), field.length());
    }

    /**
     * Writes text into a descriptor's span, taking the justification and pad byte from the
     * descriptor's kind: character spans are left justified and space-padded, numeric
     * {@code DISPLAY} spans are right justified and zero-padded, exactly as COBOL aligns them.
     *
     * @param field the descriptor naming the span
     * @param value the text to write; its encoded form must not exceed the span
     * @throws NullPointerException      if {@code field} or {@code value} is {@code null}
     * @throws IndexOutOfBoundsException if the descriptor's span falls outside this record
     * @throws IllegalArgumentException  if {@code value} encodes to more bytes than the span holds
     */
    public void writeSpan(FieldSpan field, String value) {
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        writeString(field.offset(), field.length(), value, field.kind().leftJustified(),
                padByteFor(field.kind()));
    }

    /**
     * Extracts a descriptor's span as a fresh array.
     *
     * @param field the descriptor naming the span
     * @return a copy of the span's bytes
     * @throws NullPointerException      if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if the descriptor's span falls outside this record
     */
    public byte[] readSpanBytes(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return readBytes(field.offset(), field.length());
    }

    /**
     * Replaces a descriptor's span with the supplied bytes, which must be exactly as wide as the
     * span. Exact width is required here, unlike {@link #writeBytes(int, byte[])}, because a
     * descriptor states the span's width and a mismatch means the caller's idea of the layout and the
     * descriptor's have diverged.
     *
     * @param field  the descriptor naming the span
     * @param source the replacement bytes, exactly {@code field.length()} of them
     * @throws NullPointerException      if {@code field} or {@code source} is {@code null}
     * @throws IllegalArgumentException  if {@code source.length} differs from the span's width
     * @throws IndexOutOfBoundsException if the descriptor's span falls outside this record
     */
    public void writeSpanBytes(FieldSpan field, byte[] source) {
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        Objects.requireNonNull(source, "Replacement bytes are required for span '" + field.name()
                + "'");
        if (source.length != field.length()) {
            throw new IllegalArgumentException("Supplied " + source.length + " byte(s) for "
                    + field.describe() + "; a descriptor-driven write must match the span width "
                    + "exactly");
        }
        writeBytes(field.offset(), source);
    }

    /**
     * Initialises every storage span of a layout: each span that declares a {@code VALUE} literal
     * receives that literal, and every other span receives the pad byte for its kind.
     *
     * <p>This is where the {@code app/cpy/CSDAT01Y.cpy} exception is honoured. Initialising that
     * copybook's {@code WS-CURDATE-MM-DD-YY} group yields {@code 00/00/00}: the two numeric spans
     * declare no literal and so take the zero byte, while the two {@code FILLER X(01) VALUE '/'}
     * spans emit their slash. A blanket space-fill would have produced {@code   /  /  } at best and
     * blanked the separators entirely at worst.
     *
     * <p>{@code REDEFINES} overlays are skipped, because the storage they view has already been
     * initialised by the span they redefine. Initialising an overlay separately would overwrite that
     * storage a second time and make the result depend on declaration order.
     *
     * @param layout the layout to initialise from; its declared record length must equal this
     *               record's
     * @throws NullPointerException     if {@code layout} is {@code null}
     * @throws IllegalArgumentException if the layout's declared record length differs from this
     *                                  record's length
     */
    public void initialise(RecordLayout layout) {
        Objects.requireNonNull(layout, "A record layout is required to initialise from its "
                + "declared spans");
        if (layout.recordLength() != recordLength) {
            throw new IllegalArgumentException("Layout declares a record length of "
                    + layout.recordLength() + " but this record is " + recordLength
                    + " byte(s) wide; a layout and the record it initialises must agree exactly");
        }
        for (FieldSpan span : layout.spans()) {
            if (span.redefinition()) {
                continue;
            }
            if (span.hasInitialValue()) {
                writeSpan(span, span.initialValue());
            } else {
                fill(span.offset(), span.length(), padByteFor(span.kind()));
            }
        }
    }

    /**
     * Converts a 1-based COBOL {@code OCCURS} subscript into an absolute 0-based byte offset,
     * computing {@code baseOffset + (oneBasedIndex - 1) * elementLength}.
     *
     * <p>COBOL subscripts start at 1 and Java indices at 0. That single-element shift is the most
     * common defect in a migration of this kind, which is why the conversion lives here, names the
     * convention in its own method name, and is never written inline at a call site. Index 1 returns
     * {@code baseOffset}; index {@code occursCount} returns the last element's offset. Index 0 and
     * any index above {@code occursCount} are rejected.
     *
     * @param baseOffset    the absolute 0-based offset of the first element; never negative
     * @param elementLength the width of one element in bytes; at least 1
     * @param occursCount   the {@code OCCURS} count, that is the number of elements; at least 1
     * @param oneBasedIndex the COBOL subscript, from 1 to {@code occursCount} inclusive
     * @return the absolute 0-based byte offset of the addressed element
     * @throws IllegalArgumentException  if {@code baseOffset} is negative, or {@code elementLength}
     *                                   or {@code occursCount} is below 1
     * @throws IndexOutOfBoundsException if {@code oneBasedIndex} is below 1 or above
     *                                   {@code occursCount}
     */
    public static int occursElementOffsetOneBased(int baseOffset, int elementLength, int occursCount,
                                                 int oneBasedIndex) {
        if (baseOffset < 0) {
            throw new IllegalArgumentException("OCCURS table base offset " + baseOffset
                    + " is negative; offsets are absolute and 0-based");
        }
        if (elementLength < 1) {
            throw new IllegalArgumentException("OCCURS element length " + elementLength
                    + " is not usable; each element occupies at least 1 byte");
        }
        if (occursCount < 1) {
            throw new IllegalArgumentException("OCCURS count " + occursCount
                    + " is not usable; a table holds at least 1 element");
        }
        if (oneBasedIndex < 1 || oneBasedIndex > occursCount) {
            throw new IndexOutOfBoundsException("OCCURS subscript " + oneBasedIndex
                    + " is outside 1.." + occursCount + "; COBOL subscripts are 1-based, so the "
                    + "first element is index 1 and there is no index 0");
        }
        return baseOffset + (oneBasedIndex - 1) * elementLength;
    }

    /**
     * Derives the descriptor of one element of an {@code OCCURS} table from the descriptor of the
     * table as a whole, using the 1-based COBOL subscript.
     *
     * <p>The element width is the table's width divided by the occurrence count, and the division
     * must be exact: a table whose declared width is not a whole multiple of its occurrence count
     * means the table span or the count has been transcribed wrongly, and every element offset
     * derived from it would be silently skewed. An element of a table that is itself a
     * {@code REDEFINES} overlay is an overlay too, so that property is carried across.
     *
     * @param table         the descriptor of the whole table span
     * @param occursCount   the {@code OCCURS} count; at least 1, and an exact divisor of the table
     *                      width
     * @param oneBasedIndex the COBOL subscript, from 1 to {@code occursCount} inclusive
     * @param elementName   the element's name, verbatim as the copybook spells it
     * @param elementKind   the element's category
     * @return the descriptor of the addressed element
     * @throws NullPointerException      if {@code table} is {@code null}, or if {@code elementName}
     *                                   or {@code elementKind} is {@code null}
     * @throws IllegalArgumentException  if {@code occursCount} is below 1 or does not divide the
     *                                   table width exactly
     * @throws IndexOutOfBoundsException if {@code oneBasedIndex} is outside 1..{@code occursCount}
     */
    public static FieldSpan occursElementSpan(FieldSpan table, int occursCount, int oneBasedIndex,
                                             String elementName, PictureKind elementKind) {
        Objects.requireNonNull(table, "The descriptor of the whole OCCURS table span is required");
        if (occursCount < 1) {
            throw new IllegalArgumentException("OCCURS count " + occursCount + " for table "
                    + table.describe() + " is not usable; a table holds at least 1 element");
        }
        if (table.length() % occursCount != 0) {
            throw new IllegalArgumentException("OCCURS table " + table.describe() + " is "
                    + table.length() + " byte(s) wide, which is not a whole multiple of its "
                    + occursCount + " occurrence(s); the table width or the count is wrong");
        }
        int elementLength = table.length() / occursCount;
        int elementOffset = occursElementOffsetOneBased(table.offset(), elementLength, occursCount,
                oneBasedIndex);
        return new FieldSpan(elementName, elementOffset, elementLength, elementKind, null,
                table.redefinition());
    }

    /**
     * A diagnostic summary naming the geometry and the code page. The record's <em>content</em> is
     * deliberately excluded: these areas hold customer names, account identifiers and card numbers,
     * and a value that appears in a log by default is a value nobody chose to log.
     *
     * @return for example {@code FixedWidthRecord[recordLength=300, charset=IBM037]}
     */
    @Override
    public String toString() {
        return "FixedWidthRecord[recordLength=" + recordLength + ", charset=" + charset.name() + "]";
    }
}
