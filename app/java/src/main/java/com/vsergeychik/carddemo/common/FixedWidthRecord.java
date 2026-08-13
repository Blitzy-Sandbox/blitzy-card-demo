package com.vsergeychik.carddemo.common;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.UnmappableCharacterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A mutable, fixed-length byte span standing in for a COBOL record area, addressed by absolute 0-based
 * offset.
 *
 * <p>The sibling {@code FixedWidthCodec} sits above this class and adds PICTURE semantics: converting a
 * zoned {@code DISPLAY} field to and from a decimal value, applying the trailing-byte sign overpunch,
 * applying report edit masks, and choosing the direction in which an over-wide {@code MOVE} truncates.
 */
public final class FixedWidthRecord {
    /**
     * The category of a span, sufficient to decide how a literal is placed within it and which pad byte
     * fills it when no literal is declared.
     */
    public enum PictureKind {
        /**
         * {@code PIC X(n)} - character data.
         */
        ALPHANUMERIC,

        /**
         * {@code PIC 9(n)} - unsigned zoned {@code DISPLAY} digits, one digit per byte.
         */
        UNSIGNED_NUMERIC,

        /**
         * {@code PIC S9(p)V(s)} - signed zoned {@code DISPLAY} digits occupying {@code p + s} bytes, with
         * the sign overpunched into the trailing byte and no byte of its own.
         */
        SIGNED_SCALED,

        FILLER;

        /**
         * Whether this kind holds zoned {@code DISPLAY} digits, and therefore right justifies and pads with
         * the zero byte.
         *
         * @return {@code true} for {@link #UNSIGNED_NUMERIC} and {@link #SIGNED_SCALED}
         */
        public boolean numericDisplay() {
            return this == UNSIGNED_NUMERIC || this == SIGNED_SCALED;
        }

        /**
         * Whether a literal placed into a span of this kind is left justified within it.
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

    public enum FillerHandling {
        /**
         * The COBOL default: {@code FILLER} spans are not touched.
         */
        WITHOUT_FILLER,

        WITH_FILLER;

        public boolean includesFiller() {
            return this == WITH_FILLER;
        }
    }

    /**
     * What an {@code INITIALIZE} moves into each participating span.
     */
    public enum ValueHandling {
        /**
         * The unqualified {@code INITIALIZE}: spaces into character spans, zoned zeros into numeric
         * {@code DISPLAY} spans - with a positive-zero sign overpunch in the trailing byte of a signed
         * span, because that is the only representation of zero the datasets contain.
         */
        CATEGORY_DEFAULTS,

        TO_VALUE
    }

    /**
     * The zoned-{@code DISPLAY} sign convention: where the sign of a signed numeric span lives and which
     * byte carries it.
     */
    public static final class ZonedSign {
        public static final String POSITIVE_DIGITS = "{ABCDEFGHI";

        public static final String NEGATIVE_DIGITS = "}JKLMNOPQR";

        public static final char POSITIVE_ZERO = '{';

        public static final char NEGATIVE_ZERO = '}';

        private ZonedSign() {
            throw new AssertionError("ZonedSign publishes the zoned sign alphabet and is never "
                    + "instantiated");
        }

        /**
         * The trailing-byte character for a low-order digit under a given sign.
         *
         * @param digit the low-order digit, 0 through 9
         * @param negative {@code true} for a negative value
         * @return the overpunched character
         * @throws IllegalArgumentException if {@code digit} is outside 0 through 9
         */
        public static char overpunch(int digit, boolean negative) {
            if (digit < 0 || digit > 9) {
                throw new IllegalArgumentException("Digit " + digit + " cannot be sign-overpunched; "
                        + "a zoned DISPLAY byte carries exactly one decimal digit, 0 through 9");
            }
            return (negative ? NEGATIVE_DIGITS : POSITIVE_DIGITS).charAt(digit);
        }

        /**
         * The decimal digit a trailing byte carries, whether it is overpunched or a plain digit.
         *
         * @param trailing the trailing character of a signed zoned span
         * @return the digit 0 through 9, or {@code -1} when {@code trailing} is neither an overpunch
         *     character nor a decimal digit
         */
        public static int digitOf(char trailing) {
            int positive = POSITIVE_DIGITS.indexOf(trailing);
            if (positive >= 0) {
                return positive;
            }
            int negative = NEGATIVE_DIGITS.indexOf(trailing);
            if (negative >= 0) {
                return negative;
            }
            return trailing >= '0' && trailing <= '9' ? trailing - '0' : -1;
        }

        /**
         * Whether a trailing byte marks the value negative.
         *
         * @param trailing the trailing character of a signed zoned span
         * @return {@code true} only for a negative overpunch character
         */
        public static boolean isNegative(char trailing) {
            return NEGATIVE_DIGITS.indexOf(trailing) >= 0;
        }
    }

    /**
     * The single-byte text seam: the one place in this module where bytes become characters and characters
     * become bytes.
     */
    public static final class Transcoder {
        private final Charset charset;

        public Transcoder(Charset charset) {
            this.charset = Objects.requireNonNull(charset, "A charset must be supplied explicitly: "
                    + "a fixed-width span can never be transcoded against an assumed encoding");
        }

        public Charset charset() {
            return charset;
        }

        /**
         * Encodes text, refusing anything the code page cannot represent exactly.
         *
         * @param text the characters to encode
         * @param subject what is being encoded, named in any diagnostic - a field name, a span description
         *     or a record name
         * @return the encoded bytes, one per character
         * @throws NullPointerException if {@code text} or {@code subject} is {@code null}
         * @throws IllegalArgumentException if any character is unrepresentable in the code page, or if the
         *     code page is not single-byte for this text
         */
        public byte[] encode(String text, String subject) {
            byte[] bytes = encodeWithoutWidthCheck(text, subject);
            if (bytes.length != text.length()) {
                throw new IllegalArgumentException("Encoding " + text.length() + " character(s) for "
                        + subject + " under code page " + charset.name() + " produced "
                        + bytes.length + " byte(s). A fixed-width record area is addressed by "
                        + "absolute byte offset, so it requires a single-byte code page - one that "
                        + "encodes every character of its data to exactly one byte. IBM037 for the "
                        + "EBCDIC datasets and US-ASCII for the text fixtures both qualify");
            }
            return bytes;
        }

        byte[] encodeWithoutWidthCheck(String text, String subject) {
            Objects.requireNonNull(text, "Text is required to encode");
            Objects.requireNonNull(subject, "A subject is required so a coding failure can be "
                    + "attributed without quoting the content");
            CharsetEncoder encoder = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer input = CharBuffer.wrap(text);
            ByteBuffer encoded;
            try {
                encoded = encoder.encode(input);
            } catch (CharacterCodingException failure) {
                throw new IllegalArgumentException("The value supplied for " + subject + " contains "
                        + "a character that code page " + charset.name() + " cannot represent, at "
                        + "0-based character position " + input.position() + " of " + text.length()
                        + ". The content is withheld deliberately. A fixed-width dataset field must "
                        + "be written in the dataset's own code page, and substituting a replacement "
                        + "character would put a value into the dataset that no COBOL program could "
                        + "have produced", failure);
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        }

        /**
         * Decodes stored bytes, refusing anything that is not a character in the code page.
         *
         * @param source the buffer holding the stored bytes
         * @param offset the 0-based offset of the span within {@code source}
         * @param length the span width in bytes
         * @param subject what is being decoded, named in any diagnostic
         * @return the decoded characters, one per byte
         * @throws NullPointerException if {@code source} or {@code subject} is {@code null}
         * @throws IllegalStateException if any stored byte is not a character in the code page, or if the
         *     code page is not single-byte for these bytes
         */
        public String decode(byte[] source, int offset, int length, String subject) {
            Objects.requireNonNull(source, "Stored bytes are required to decode");
            Objects.requireNonNull(subject, "A subject is required so a coding failure can be "
                    + "attributed without quoting the content");
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded;
            try {
                decoded = decoder.decode(ByteBuffer.wrap(source, offset, length));
            } catch (CharacterCodingException failure) {
                throw new IllegalStateException("The stored bytes of " + subject + " are not valid "
                        + "code page " + charset.name() + " data. The content is withheld "
                        + "deliberately. Decoding them would substitute a replacement character and "
                        + "present corrupt storage as though it were a field value, so the span is "
                        + "refused instead", failure);
            }
            if (decoded.remaining() != length) {
                throw new IllegalStateException("Decoding " + length + " byte(s) of " + subject
                        + " under code page " + charset.name() + " produced " + decoded.remaining()
                        + " character(s). A fixed-width record area is addressed by absolute byte "
                        + "offset, so it requires a single-byte code page");
            }
            return decoded.toString();
        }

        int measuredWidthOf(char character) {
            if (!charset.newEncoder().canEncode(character)) {
                return 0;
            }
            return encodeWithoutWidthCheck(String.valueOf(character),
                    "the '" + character + "' character").length;
        }

    }

    private static final String FILLER_NAME = "FILLER";

    /**
     * One positioned, length-bearing entry in a record layout: the immutable descriptor of a single
     * copybook data item.
     *
     * @param name the copybook item name exactly as declared, or {@code FILLER} for a reserved span; never
     *     blank
     * @param offset the absolute 0-based byte offset of the span within the record; never negative
     * @param length the width of the span in bytes; at least 1
     * @param kind the span's category, which decides literal alignment and the default pad
     * @param initialValue the declared {@code VALUE} literal, or {@code null} when the copybook declares
     *     none
     * @param redefinition {@code true} when this descriptor is a {@code REDEFINES} overlay over storage
     *     that another descriptor already accounts for
     */
    public record FieldSpan(String name,
                            int offset,
                            int length,
                            PictureKind kind,
                            String initialValue,
                            boolean redefinition) {
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
            long endOffset = (long) offset + (long) length;
            if (endOffset > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("FieldSpan '" + name + "' at offset " + offset
                        + " of length " + length + " ends at byte " + endOffset + ", beyond the "
                        + Integer.MAX_VALUE + "-byte addressing limit of a record area. A record is "
                        + "held as one byte array, so its highest addressable end offset is that "
                        + "limit; a span reaching past it is a transcription error");
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
         * @param name the copybook item name, verbatim
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
         * @param name the copybook item name, verbatim
         * @param offset the absolute 0-based byte offset
         * @param length {@code n}, the declared digit count
         * @return the descriptor
         */
        public static FieldSpan unsignedNumeric(String name, int offset, int length) {
            return new FieldSpan(name, offset, length, PictureKind.UNSIGNED_NUMERIC, null, false);
        }

        /**
         * Declares a {@code PIC S9(p)V(s)} signed zoned {@code DISPLAY} field.
         *
         * @param name the copybook item name, verbatim
         * @param offset the absolute 0-based byte offset
         * @param integerDigits {@code p}, the digits before the implied decimal point; at least 1
         * @param fractionDigits {@code s}, the digits after it; never negative, and 0 for a scaleless
         *     signed field
         * @return the descriptor, {@code integerDigits + fractionDigits} bytes wide
         * @throws IllegalArgumentException if {@code integerDigits} is below 1 or {@code fractionDigits} is
         *     negative
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
         * Declares a {@code FILLER PIC X(n)} reserved span carrying no {@code VALUE}, which therefore
         * initialises to the charset's space byte.
         *
         * @param offset the absolute 0-based byte offset
         * @param length {@code n}, the reserved byte count
         * @return the descriptor, named {@code FILLER}
         */
        public static FieldSpan filler(int offset, int length) {
            return new FieldSpan(FILLER_NAME, offset, length, PictureKind.FILLER, null, false);
        }

        /**
         * Declares a {@code FILLER PIC X(n) VALUE '...'} reserved span that carries a literal, such as the
         * {@code '/'} and {@code ':'} separators of {@code app/cpy/CSDAT01Y.cpy}.
         *
         * @param offset the absolute 0-based byte offset
         * @param length {@code n}, the reserved byte count
         * @param initialValue the declared literal, no longer than {@code length}
         * @return the descriptor, named {@code FILLER}
         * @throws NullPointerException if {@code initialValue} is {@code null}; use
         *     {@link #filler(int, int)} for a span with no literal
         */
        public static FieldSpan filler(int offset, int length, String initialValue) {
            Objects.requireNonNull(initialValue, "A FILLER VALUE literal is required here; call "
                    + "filler(offset, length) for a FILLER that declares none");
            return new FieldSpan(FILLER_NAME, offset, length, PictureKind.FILLER, initialValue,
                    false);
        }

        /**
         * Declares a {@code REDEFINES} overlay directly at an absolute offset.
         *
         * @param name the redefining item's name, verbatim
         * @param offset the absolute 0-based byte offset of the redefined storage
         * @param length the width of the overlay in bytes
         * @param kind the category through which the overlay views those bytes
         * @return the overlay descriptor
         */
        public static FieldSpan redefining(String name, int offset, int length, PictureKind kind) {
            return new FieldSpan(name, offset, length, kind, null, true);
        }

        /**
         * Returns a copy of this descriptor carrying the given {@code VALUE} literal.
         *
         * @param literal the declared literal, no longer than this span
         * @return a new descriptor identical to this one but carrying {@code literal}
         * @throws NullPointerException if {@code literal} is {@code null}
         * @throws IllegalArgumentException if {@code literal} is longer than this span
         */
        public FieldSpan withInitialValue(String literal) {
            Objects.requireNonNull(literal, "VALUE literal is required for field '" + name + "'");
            return new FieldSpan(name, offset, length, kind, literal, redefinition);
        }

        /**
         * Returns a {@code REDEFINES} overlay covering exactly this span's bytes under a new name and
         * category - the {@code CC-ACCT-ID PIC X(11)} / {@code CC-ACCT-ID-N PIC 9(11)} pair of
         * {@code app/cpy/CVCRD01Y.cpy}.
         *
         * @param newName the redefining item's name, verbatim
         * @param newKind the category through which the overlay views the same bytes
         * @return the overlay descriptor, at this span's offset and length
         */
        public FieldSpan redefinedAs(String newName, PictureKind newKind) {
            return new FieldSpan(newName, offset, length, newKind, null, true);
        }

        /**
         * Returns a {@code REDEFINES} overlay starting at this span's offset but narrower than it, for the
         * case where the redefining item covers only the leading bytes of the redefined item.
         *
         * @param newName the redefining item's name, verbatim
         * @param newKind the category through which the overlay views those bytes
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

        public boolean hasInitialValue() {
            return initialValue != null;
        }

        /**
         * The exclusive end offset of this span, that is {@code offset + length}.
         *
         * @return the offset one byte past the end of this span
         */
        public int endOffsetExclusive() {
            return Math.toIntExact((long) offset + (long) length);
        }

        /**
         * A precise, single-line description used in layout and bounds failure messages, so a failure names
         * the offending descriptor rather than only a numeric offset.
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
     * @param recordLength the declared total width of the record in bytes; at least 1
     * @param spans every span in copybook declaration order, storage and overlays alike; never empty, and
     *     defensively copied so the layout cannot be mutated afterwards
     */
    public record RecordLayout(int recordLength, List<FieldSpan> spans) {
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
         * @param spans every span in declaration order, storage and overlays alike
         * @return the validated layout
         * @throws NullPointerException if {@code spans} is {@code null} or contains {@code null}
         * @throws IllegalArgumentException if the layout does not describe exactly {@code recordLength}
         *     bytes
         */
        public static RecordLayout of(int recordLength, FieldSpan... spans) {
            Objects.requireNonNull(spans, "A record layout requires its span list");
            return new RecordLayout(recordLength, List.of(spans));
        }

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
         * The spans that actually occupy storage, in declaration order, excluding every {@code REDEFINES}
         * overlay.
         *
         * @return an immutable list of the storage spans
         */
        public List<FieldSpan> storageSpans() {
            return select(false);
        }

        /**
         * The {@code REDEFINES} overlays, in declaration order.
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
         * Looks up a span by its exact copybook name, overlays included.
         *
         * @param name the copybook item name, verbatim and case-sensitive
         * @return the matching span
         * @throws NullPointerException if {@code name} is {@code null}
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
         * Allocates a record over this layout and establishes it as a program's record area is established
         * at load time - each span's category default, overlaid with its declared {@code VALUE} literal
         * where one exists.
         *
         * @param charset the code page of the record's data, supplied explicitly by the caller
         * @return a newly allocated, initialised record of this layout's declared length
         * @throws NullPointerException if {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code charset} does not encode the space and zero characters
         *     to exactly one byte each
         */
        public FixedWidthRecord newRecord(Charset charset) {
            return forLayout(this, charset);
        }
    }

    private final byte[] area;

    private final int recordLength;

    private final Charset charset;

    private final byte spaceByte;

    private final byte zeroByte;

    private final Transcoder transcoder;

    /**
     * Allocates a space-filled record area of the given width.
     *
     * @param recordLength the declared record width in bytes, supplied by the caller from the relevant
     *     dataset binding or copybook; at least 1
     * @param charset the code page of the record's data, supplied explicitly
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code recordLength} is below 1, or if {@code charset} is not a
     *     single-byte code page for the space and zero characters
     */
    public FixedWidthRecord(int recordLength, Charset charset) {
        this.charset = Objects.requireNonNull(charset, "A charset must be supplied explicitly: the "
                + "pad bytes themselves are code-page dependent, so a fixed-width record area can "
                + "never be allocated against an assumed encoding");
        if (recordLength < 1) {
            throw new IllegalArgumentException("Record length " + recordLength
                    + " is not a valid record width; a record occupies at least 1 byte");
        }
        this.transcoder = new Transcoder(charset);
        this.spaceByte = singleByte(' ', charset);
        this.zeroByte = singleByte('0', charset);
        this.recordLength = recordLength;
        this.area = new byte[recordLength];
        Arrays.fill(this.area, this.spaceByte);
    }

    /**
     * Wraps existing record bytes, taking a defensive copy of them.
     *
     * @param bytes the record bytes; its length must equal {@code recordLength} exactly
     * @param recordLength the declared record width in bytes
     * @param charset the code page of the record's data, supplied explicitly
     * @return a record holding a copy of {@code bytes}
     * @throws NullPointerException if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} differs from {@code recordLength}, or if
     *     {@code charset} is not a single-byte code page for the space and zero characters
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
     * Allocates a record area over a layout and establishes it as a program's own record area is
     * established at load time.
     *
     * @param layout the validated layout, whose declared record length becomes the record's width
     * @param charset the code page of the record's data, supplied explicitly
     * @return a newly allocated record holding each span's category default overlaid with its declared
     *     literal
     * @throws NullPointerException if {@code layout} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the space and
     *     zero characters
     */
    public static FixedWidthRecord forLayout(RecordLayout layout, Charset charset) {
        Objects.requireNonNull(layout, "A record layout is required to initialise a record from "
                + "its declared spans");
        FixedWidthRecord record = new FixedWidthRecord(layout.recordLength(), charset);
        record.initialize(layout, FillerHandling.WITH_FILLER, ValueHandling.CATEGORY_DEFAULTS);
        record.loadDeclaredValues(layout);
        return record;
    }

    /**
     * Encodes text under an explicitly named code page, reporting rather than replacing anything the code
     * page cannot represent.
     *
     * @param text the characters to encode; never {@code null}
     * @param charset the code page, always named explicitly and never a platform default
     * @param subject what is being encoded, for the failure message - a copybook field name, a span
     *     descriptor or a short phrase
     * @return the encoded bytes, exactly as the code page defines them
     * @throws NullPointerException if {@code text}, {@code charset} or {@code subject} is {@code null}
     * @throws IllegalArgumentException if any character of {@code text} is malformed or is not
     *     representable in {@code charset}
     */
    public static byte[] encodeStrictly(String text, Charset charset, String subject) {
        Objects.requireNonNull(text, "Text is required to encode a fixed-width span");
        Objects.requireNonNull(charset, "A code page is required; it is never the platform default");
        Objects.requireNonNull(subject, "A subject is required so a failure names what was encoded");
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            if (bytes.length != text.length()) {
                throw new IllegalArgumentException("Encoding " + text.length() + " character(s) for "
                        + subject + " under code page " + charset.name() + " produced "
                        + bytes.length + " byte(s). A fixed-width record area is addressed by "
                        + "absolute byte offset, so it requires a single-byte code page - one that "
                        + "encodes every character of its data to exactly one byte. IBM037 for the "
                        + "EBCDIC datasets and US-ASCII for the text fixtures both qualify");
            }
            return bytes;
        } catch (CharacterCodingException unrepresentable) {
            throw new IllegalArgumentException("Cannot encode " + subject + " under code page "
                    + charset.name() + ": the character at 0-based position "
                    + codingErrorPosition(unrepresentable, text.length())
                    + " is malformed or has no representation in that code page. A fixed-width record "
                    + "is addressed by absolute byte offset, so substituting a replacement byte would "
                    + "corrupt the record silently; the value is withheld from this message because it "
                    + "may carry sensitive data", unrepresentable);
        }
    }

    /**
     * Decodes bytes under an explicitly named code page, reporting rather than replacing any sequence the
     * code page does not define.
     *
     * @param bytes the source array; never {@code null}
     * @param offset the 0-based offset of the first byte to decode; never negative
     * @param length how many bytes to decode; never negative
     * @param charset the code page, always named explicitly and never a platform default
     * @param subject what is being decoded, for the failure message
     * @return the decoded characters, exactly {@code length} bytes' worth
     * @throws NullPointerException if {@code bytes}, {@code charset} or {@code subject} is {@code null}
     * @throws IndexOutOfBoundsException if {@code [offset, offset + length)} lies outside {@code bytes}
     * @throws IllegalArgumentException if the bytes are not a valid sequence in {@code charset}
     */
    public static String decodeStrictly(byte[] bytes, int offset, int length, Charset charset,
                                        String subject) {
        Objects.requireNonNull(bytes, "Bytes are required to decode a fixed-width span");
        Objects.requireNonNull(charset, "A code page is required; it is never the platform default");
        Objects.requireNonNull(subject, "A subject is required so a failure names what was decoded");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException undecodable) {
            throw new IllegalArgumentException("Cannot decode " + subject + " under code page "
                    + charset.name() + ": the byte at 0-based position "
                    + (offset + codingErrorPosition(undecodable, length))
                    + " begins a sequence that code page does not define. Substituting a replacement "
                    + "character would present bytes that were never stored; the bytes are withheld "
                    + "from this message because they may carry sensitive data", undecodable);
        }
    }

    /**
     * Decodes a whole array strictly - the common case where the span is the entire array.
     *
     * @param bytes the bytes to decode in full; never {@code null}
     * @param charset the code page, always named explicitly
     * @param subject what is being decoded, for the failure message
     * @return the decoded characters
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the bytes are not a valid sequence in {@code charset}
     */
    public static String decodeStrictly(byte[] bytes, Charset charset, String subject) {
        Objects.requireNonNull(bytes, "Bytes are required to decode a fixed-width record image");
        return decodeStrictly(bytes, 0, bytes.length, charset, subject);
    }

    private static int codingErrorPosition(CharacterCodingException failure, int inputLength) {
        int reported = 0;
        if (failure instanceof MalformedInputException malformed) {
            reported = malformed.getInputLength();
        } else if (failure instanceof UnmappableCharacterException unmappable) {
            reported = unmappable.getInputLength();
        }
        if (reported < 0) {
            return 0;
        }
        return Math.min(reported, Math.max(inputLength - 1, 0));
    }

    private static byte singleByte(char character, Charset charset) {
        byte[] encoded = new Transcoder(charset)
                .encodeWithoutWidthCheck(String.valueOf(character), "the '" + character
                        + "' pad character");
        if (encoded.length != 1) {
            throw new IllegalArgumentException("Charset " + charset.name() + " encodes '" + character
                    + "' to " + encoded.length + " byte(s). A fixed-width record area is addressed "
                    + "by absolute byte offset, so it requires a code page that encodes the space "
                    + "and zero characters to exactly one byte each - IBM037 for EBCDIC data and "
                    + "US-ASCII for the text fixtures both qualify");
        }
        return encoded[0];
    }

    public int recordLength() {
        return recordLength;
    }

    /**
     * The code page of this record's data, as supplied by the caller at construction.
     *
     * @return the record's charset, never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Encodes a record image strictly under an explicitly named code page, refusing any character the code
     * page cannot represent and any code page that is not single-byte.
     *
     * @param text the image to encode
     * @param charset the code page, supplied explicitly
     * @param subject what is being encoded, named in any diagnostic - never its content
     * @return the encoded bytes, one per character
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if a character is unrepresentable or the code page is not
     *     single-byte
     */
    public static byte[] encodeText(String text, Charset charset, String subject) {
        return new Transcoder(charset).encode(text, subject);
    }

    /**
     * Decodes stored bytes strictly under an explicitly named code page, refusing any byte that is not a
     * character in it.
     *
     * @param source the stored bytes, decoded in full
     * @param charset the code page, supplied explicitly
     * @param subject what is being decoded, named in any diagnostic - never its content
     * @return the decoded text, one character per byte
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if a stored byte is not valid data in the code page, or the code page
     *     is not single-byte
     */
    public static String decodeText(byte[] source, Charset charset, String subject) {
        Objects.requireNonNull(source, "Stored bytes are required to decode");
        return new Transcoder(charset).decode(source, 0, source.length, subject);
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
     * The byte that fills the unused remainder of a span of the given kind when a shorter value is placed
     * into it: the zero byte for numeric {@code DISPLAY} kinds and the space byte otherwise, mirroring how
     * COBOL pads a {@code MOVE} into each category.
     *
     * @param kind the span category
     * @return the pad byte under this record's charset
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public byte padByteFor(PictureKind kind) {
        Objects.requireNonNull(kind, "A PICTURE kind is required to choose a pad byte");
        return kind.numericDisplay() ? zeroByte : spaceByte;
    }

    public byte[] toByteArray() {
        return area.clone();
    }

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
     * Extracts a sub-span of the record as a fresh array.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @return a copy of the {@code length} bytes at {@code offset}
     * @throws IndexOutOfBoundsException if the span falls outside the record, with the offset, the length
     *     and the record length all named
     */
    public byte[] readBytes(int offset, int length) {
        checkSpan(offset, length);
        return Arrays.copyOfRange(area, offset, offset + length);
    }

    /**
     * Replaces a sub-span of the record with the supplied bytes, whose length determines the span replaced.
     *
     * @param offset the absolute 0-based byte offset
     * @param source the replacement bytes, copied in full; at least 1 byte long
     * @throws NullPointerException if {@code source} is {@code null}
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
     * <p>A COBOL {@code PIC X} field is space-padded to its full width and that padding is part of the
     * field's value, so trimming here would discard bytes the parity differ compares.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1 Stored bytes that are not characters in this
     *     record's code page are refused rather than decoded to replacement characters
     * @return the decoded text, exactly {@code length} bytes' worth
     * @throws IndexOutOfBoundsException if the span falls outside the record
     * @throws IllegalStateException if the stored bytes are not valid data in this record's code page
     */
    public String readString(int offset, int length) {
        return readString(offset, length, positionalSubject(offset, length));
    }

    /**
     * Decodes a span as text under this record's charset, attributing any coding failure to a named
     * subject.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @param subject what the span is, named in any diagnostic - never its content
     * @return the decoded text, exactly {@code length} bytes' worth
     * @throws NullPointerException if {@code subject} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside the record
     * @throws IllegalStateException if the stored bytes are not valid data in this record's code page
     */
    public String readString(int offset, int length, String subject) {
        checkSpan(offset, length);
        return transcoder.decode(area, offset, length, subject);
    }

    private String positionalSubject(int offset, int length) {
        return "the span at offset " + offset + " of length " + length + " in a record of length "
                + recordLength;
    }

    /**
     * Writes text into a span, left justified and padded on the right with this record's space byte - the
     * COBOL alphanumeric convention.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @param value the text to write; its encoded form must not exceed {@code length}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside the record
     * @throws IllegalArgumentException if {@code value} encodes to more than {@code length} bytes
     */
    public void writeString(int offset, int length, String value) {
        writeString(offset, length, value, true, spaceByte);
    }

    /**
     * Writes text into a span with the justification and pad byte stated explicitly.
     *
     * <p>COBOL truncates a cross-width {@code MOVE} on the right for {@code PIC X} and on the left for
     * {@code PIC 9}, so the direction is a per-PICTURE decision that must be taken deliberately by the
     * layer above.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @param value the text to write; its encoded form must not exceed {@code length}
     * @param leftJustified {@code true} to place the value at the start of the span and pad after it,
     *     {@code false} to place it at the end and pad before it
     * @param padByte the byte filling the remainder of the span
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside the record
     * @throws IllegalArgumentException if {@code value} encodes to more than {@code length} bytes
     */
    public void writeString(int offset, int length, String value, boolean leftJustified,
                            byte padByte) {
        writeString(offset, length, value, leftJustified, padByte,
                positionalSubject(offset, length));
    }

    /**
     * Writes text into a span with the justification, pad byte and diagnostic subject all stated
     * explicitly.
     *
     * @param offset the absolute 0-based byte offset
     * @param length the span width in bytes; at least 1
     * @param value the text to write; its encoded form must not exceed {@code length}
     * @param leftJustified {@code true} to place the value at the start of the span and pad after it
     * @param padByte the byte filling the remainder of the span
     * @param subject what the span is, named in any diagnostic - never its content
     * @throws NullPointerException if {@code value} or {@code subject} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside the record
     * @throws IllegalArgumentException if {@code value} encodes to more than {@code length} bytes, or
     *     contains a character this record's code page cannot represent
     */
    public void writeString(int offset, int length, String value, boolean leftJustified,
                            byte padByte, String subject) {
        Objects.requireNonNull(value, "A value is required; call fill(offset, length, byte) to blank "
                + "a span instead");
        checkSpan(offset, length);
        byte[] encoded = transcoder.encode(value, subject);
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

    public void fill(int offset, int length, byte value) {
        checkSpan(offset, length);
        Arrays.fill(area, offset, offset + length, value);
    }

    /**
     * Decodes a descriptor's span as text, untrimmed.
     *
     * @param field the descriptor naming the span
     * @return the decoded text, exactly {@code field.length()} bytes' worth
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if the descriptor's span falls outside this record
     */
    public String readSpan(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return readString(field.offset(), field.length(), field.describe());
    }

    /**
     * Writes text into a descriptor's span, taking the justification and pad byte from the descriptor's
     * kind: character spans are left justified and space-padded, numeric {@code DISPLAY} spans are right
     * justified and zero-padded, exactly as COBOL aligns them.
     *
     * @param field the descriptor naming the span
     * @param value the text to write; its encoded form must not exceed the span
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     * @throws IndexOutOfBoundsException if the descriptor's span falls outside this record
     * @throws IllegalArgumentException if {@code value} encodes to more bytes than the span holds
     */
    public void writeSpan(FieldSpan field, String value) {
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        writeString(field.offset(), field.length(), value, field.kind().leftJustified(),
                padByteFor(field.kind()), field.describe());
    }

    /**
     * Extracts a descriptor's span as a fresh array.
     *
     * @param field the descriptor naming the span
     * @return a copy of the span's bytes
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if the descriptor's span falls outside this record
     */
    public byte[] readSpanBytes(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return readBytes(field.offset(), field.length());
    }

    /**
     * Replaces a descriptor's span with the supplied bytes, which must be exactly as wide as the span.
     *
     * @param field the descriptor naming the span
     * @param source the replacement bytes, exactly {@code field.length()} of them
     * @throws NullPointerException if {@code field} or {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code source.length} differs from the span's width
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
     * Applies the layout's declared {@code VALUE} literals and nothing else, which is what establishing a
     * program's record area amounts to.
     *
     * @param layout the layout whose declared literals to apply; its declared record length must equal this
     *     record's
     * @throws NullPointerException if {@code layout} is {@code null}
     * @throws IllegalArgumentException if the layout's declared record length differs from this record's
     *     length
     */
    public void loadDeclaredValues(RecordLayout layout) {
        requireMatchingLayout(layout);
        for (FieldSpan span : layout.spans()) {
            if (span.redefinition() || !span.hasInitialValue()) {
                continue;
            }
            writeSpan(span, span.initialValue());
        }
    }

    /**
     * The COBOL {@code INITIALIZE} statement, with both of its phrases stated explicitly.
     *
     * @param layout the layout to initialise over; its declared record length must equal this record's
     * @param fillerHandling whether {@code FILLER} spans participate, i.e. the {@code WITH FILLER} phrase
     * @param valueHandling what is moved into each participating span
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the layout's declared record length differs from this record's
     *     length
     */
    public void initialize(RecordLayout layout, FillerHandling fillerHandling,
                           ValueHandling valueHandling) {
        requireMatchingLayout(layout);
        Objects.requireNonNull(fillerHandling, "State whether FILLER participates: COBOL's "
                + "INITIALIZE skips FILLER unless WITH FILLER is written, and a FILLER that declares "
                + "a literal VALUE is blanked for good by an unqualified fill");
        Objects.requireNonNull(valueHandling, "State what INITIALIZE moves: the category default, or "
                + "each span's declared VALUE");
        for (FieldSpan span : layout.spans()) {
            if (span.redefinition()) {
                continue;
            }
            if (span.kind().filler() && !fillerHandling.includesFiller()) {
                continue;
            }
            if (valueHandling == ValueHandling.TO_VALUE) {
                if (span.hasInitialValue()) {
                    writeSpan(span, span.initialValue());
                }
                continue;
            }
            applyCategoryDefault(span);
        }
    }

    private void applyCategoryDefault(FieldSpan span) {
        fill(span.offset(), span.length(), padByteFor(span.kind()));
        if (span.kind() == PictureKind.SIGNED_SCALED) {
            int trailing = span.offset() + span.length() - 1;
            area[trailing] = singleByte(ZonedSign.POSITIVE_ZERO, charset);
        }
    }

    private void requireMatchingLayout(RecordLayout layout) {
        Objects.requireNonNull(layout, "A record layout is required to initialise from its "
                + "declared spans");
        if (layout.recordLength() != recordLength) {
            throw new IllegalArgumentException("Layout declares a record length of "
                    + layout.recordLength() + " but this record is " + recordLength
                    + " byte(s) wide; a layout and the record it initialises must agree exactly");
        }
    }

    /**
     * Converts a 1-based COBOL {@code OCCURS} subscript into an absolute 0-based byte offset, computing
     * {@code baseOffset + (oneBasedIndex - 1) * elementLength}.
     *
     * @param baseOffset the absolute 0-based offset of the first element; never negative
     * @param elementLength the width of one element in bytes; at least 1
     * @param occursCount the {@code OCCURS} count, that is the number of elements; at least 1
     * @param oneBasedIndex the COBOL subscript, from 1 to {@code occursCount} inclusive
     * @return the absolute 0-based byte offset of the addressed element
     * @throws IllegalArgumentException if {@code baseOffset} is negative, or {@code elementLength} or
     *     {@code occursCount} is below 1
     * @throws IndexOutOfBoundsException if {@code oneBasedIndex} is below 1 or above {@code occursCount}
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
        long elementOffset = (long) baseOffset + ((long) oneBasedIndex - 1L) * (long) elementLength;
        long elementEnd = elementOffset + (long) elementLength;
        if (elementEnd > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("OCCURS element " + oneBasedIndex + " of "
                    + occursCount + ", each " + elementLength + " byte(s) wide from base offset "
                    + baseOffset + ", ends at byte " + elementEnd + ", beyond the "
                    + Integer.MAX_VALUE + "-byte addressing limit of a record area. The table span "
                    + "or the occurrence count is wrong");
        }
        return (int) elementOffset;
    }

    /**
     * Derives the descriptor of one element of an {@code OCCURS} table from the descriptor of the table as
     * a whole, using the 1-based COBOL subscript.
     *
     * @param table the descriptor of the whole table span
     * @param occursCount the {@code OCCURS} count; at least 1, and an exact divisor of the table width
     * @param oneBasedIndex the COBOL subscript, from 1 to {@code occursCount} inclusive
     * @param elementName the element's name, verbatim as the copybook spells it
     * @param elementKind the element's category
     * @return the descriptor of the addressed element
     * @throws NullPointerException if {@code table} is {@code null}, or if {@code elementName} or
     *     {@code elementKind} is {@code null}
     * @throws IllegalArgumentException if {@code occursCount} is below 1 or does not divide the table width
     *     exactly
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
     * A diagnostic summary naming the geometry and the code page.
     *
     * @return for example {@code FixedWidthRecord[recordLength=300, charset=IBM037]}
     */
    @Override
    public String toString() {
        return "FixedWidthRecord[recordLength=" + recordLength + ", charset=" + charset.name() + "]";
    }
}
