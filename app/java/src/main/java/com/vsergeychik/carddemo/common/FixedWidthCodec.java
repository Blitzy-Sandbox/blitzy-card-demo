package com.vsergeychik.carddemo.common;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * COBOL {@code PICTURE} semantics over a fixed-width record area: the one place in this module where a
 * cross-width {@code MOVE} is implemented, and the only place a zoned {@code DISPLAY} field is converted to
 * and from a decimal value.
 *
 * <p>{@code PIC S9(p)V99} occupies exactly {@code p + 2} bytes.
 */
public final class FixedWidthCodec {
    private static final String POSITIVE_OVERPUNCH = FixedWidthRecord.ZonedSign.POSITIVE_DIGITS;

    private static final String NEGATIVE_OVERPUNCH = FixedWidthRecord.ZonedSign.NEGATIVE_DIGITS;

    private static final String SINGLE_BYTE_REPERTOIRE =
            "0123456789" + POSITIVE_OVERPUNCH + NEGATIVE_OVERPUNCH + " ";

    private static final char SPACE = ' ';

    private static final char ZERO = '0';

    private final Charset charset;

    private final FixedWidthRecord.Transcoder transcoder;

    public FixedWidthCodec(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required: fixed-width mainframe data is bytes "
                + "in a specific code page, so the code page must be stated explicitly and is never "
                + "derived from the platform");
        FixedWidthRecord.Transcoder measure = new FixedWidthRecord.Transcoder(charset);
        for (int index = 0; index < SINGLE_BYTE_REPERTOIRE.length(); index++) {
            char required = SINGLE_BYTE_REPERTOIRE.charAt(index);
            int width = measure.measuredWidthOf(required);
            if (width != 1) {
                throw new IllegalArgumentException("Charset " + charset.name() + " encodes '"
                        + required + "' to " + width + " byte(s); a zoned DISPLAY field of "
                        + "n digits occupies exactly n bytes, so every digit, every sign overpunch "
                        + "character and the space must encode to exactly one byte");
            }
        }
        this.charset = charset;
        this.transcoder = measure;
    }

    /**
     * The code page this codec reads and writes, as supplied at construction.
     *
     * @return the charset, never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Encodes a record image under this codec's code page, refusing any character the code page cannot
     * represent.
     *
     * @param image the row image
     * @param subject what is being encoded, named in any diagnostic - a record or field name
     * @return the encoded bytes, one per character
     * @throws NullPointerException if {@code image} or {@code subject} is {@code null}
     * @throws IllegalArgumentException if a character is unrepresentable in this code page
     */
    public byte[] encodeImage(String image, String subject) {
        return transcoder.encode(image, subject);
    }

    /**
     * The first character of a value that this code page cannot represent, reported as a Unicode code point
     * so a caller can locate it without the value being quoted back.
     *
     * @param value the value to judge; must not be {@code null}
     * @return the first unrepresentable code point, or an empty {@link OptionalInt} when every character of
     *     the value has a representation in this code page
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public OptionalInt firstUnrepresentableCodePoint(String value) {
        Objects.requireNonNull(value, "A value is required to judge against a code page");
        CharsetEncoder encoder = charset.newEncoder();
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int width = Character.charCount(codePoint);
            if (!encoder.canEncode(value.subSequence(index, index + width))) {
                return OptionalInt.of(codePoint);
            }
            index += width;
        }
        return OptionalInt.empty();
    }

    /**
     * Decodes a record image under this codec's code page, refusing any byte that is not a character in it.
     *
     * @param image the stored bytes, decoded in full
     * @param subject what is being decoded, named in any diagnostic
     * @return the decoded image, one character per byte
     * @throws NullPointerException if {@code image} or {@code subject} is {@code null}
     * @throws IllegalStateException if a stored byte is not valid data in this code page
     */
    public String decodeImage(byte[] image, String subject) {
        Objects.requireNonNull(image, "Stored bytes are required to decode an image");
        return transcoder.decode(image, 0, image.length, subject);
    }

    /**
     * Allocates a record over a layout and initialises it, so every {@code FILLER} and every span declaring
     * a {@code VALUE} is already correct before the caller writes a single field.
     *
     * @param layout the record's layout, transcribed from its copybook
     * @return a newly allocated record of the layout's declared length, established per
     *     {@link FixedWidthRecord#forLayout(RecordLayout, Charset)}
     * @throws NullPointerException if {@code layout} is {@code null}
     */
    public FixedWidthRecord newRecord(RecordLayout layout) {
        Objects.requireNonNull(layout, "A record layout is required to allocate a record; declare "
                + "every field and every FILLER from the copybook");
        return FixedWidthRecord.forLayout(layout, charset);
    }

    /**
     * Wraps stored bytes as a record of a layout, for reading.
     *
     * @param record the stored bytes, defensively copied by the record
     * @param layout the record's layout, transcribed from its copybook
     * @return a record over a copy of {@code record}
     * @throws NullPointerException if {@code record} or {@code layout} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} differs from the layout's declared record
     *     length
     */
    public FixedWidthRecord wrap(byte[] record, RecordLayout layout) {
        Objects.requireNonNull(record, "Stored bytes are required to wrap a record");
        Objects.requireNonNull(layout, "A record layout is required to wrap stored bytes");
        if (record.length != layout.recordLength()) {
            throw new IllegalArgumentException("Supplied " + record.length + " byte(s) for a layout "
                    + "declaring a record length of " + layout.recordLength() + "; a fixed-width "
                    + "record must match its declared width exactly. If the row is short because a "
                    + "trailing FILLER is absent from the source data, widen it first with "
                    + "padToDeclaredWidth(byte[], int)");
        }
        return FixedWidthRecord.copyOf(record, layout.recordLength(), charset);
    }

    /**
     * Performs a COBOL alphanumeric {@code MOVE}: returns {@code source} as an image of exactly
     * {@code targetLength} characters, padded on the right with spaces when it is shorter and truncated on
     * the right when it is longer.
     *
     * <p>Right truncation is the COBOL rule for a {@code PIC X} receiver: the receiving field is filled
     * from its leftmost character position and any sending character that does not fit is discarded.
     *
     * @param source the sending value; may be shorter or longer than the receiver, and may be empty
     * @param targetLength the receiving field's declared width in characters; at least 1
     * @return an image of exactly {@code targetLength} characters
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code targetLength} is below 1
     */
    public String movePicX(String source, int targetLength) {
        Objects.requireNonNull(source, "A sending value is required for an alphanumeric MOVE; to "
                + "blank a field move an empty string or SPACES explicitly");
        requirePositiveLength(targetLength, "an alphanumeric receiver");
        if (source.length() == targetLength) {
            return source;
        }
        if (source.length() > targetLength) {
            return source.substring(0, targetLength);
        }
        return source + repeat(SPACE, targetLength - source.length());
    }

    /**
     * Writes a value into an alphanumeric span, applying the {@code PIC X} move rule first so an over-wide
     * value is truncated on the right rather than rejected.
     *
     * @param record the record area to write into
     * @param field the descriptor naming the span; its length is the receiver's declared width
     * @param value the sending value
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the span is not wide enough to be a receiver, that is if its
     *     declared length is below 1
     */
    public void writePicX(FixedWidthRecord record, FieldSpan field, String value) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, movePicX(value, field.length()));
    }

    public String readPicX(FixedWidthRecord record, FieldSpan field) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return record.readSpan(field);
    }

    /**
     * Reads an alphanumeric span and removes its trailing spaces.
     *
     * @param record the record area to read from
     * @param field the descriptor naming the span
     * @return the span's characters with trailing spaces removed; empty when the span is all spaces
     * @throws NullPointerException if {@code record} or {@code field} is {@code null}
     */
    public String readPicXTrimmed(FixedWidthRecord record, FieldSpan field) {
        String image = readPicX(record, field);
        int end = image.length();
        while (end > 0 && image.charAt(end - 1) == SPACE) {
            end--;
        }
        return image.substring(0, end);
    }

    /**
     * Performs a COBOL numeric {@code MOVE}: returns {@code source} as an image of exactly
     * {@code targetLength} digits, padded on the left with zeros when it is shorter and truncated on the
     * left when it is longer, so the receiver keeps the low-order digits.
     *
     * @param source the sending digits; must be non-empty and consist only of {@code '0'} to {@code '9'}
     * @param targetLength the receiving field's declared digit count; at least 1
     * @return an image of exactly {@code targetLength} digits
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code targetLength} is below 1, or {@code source} is empty or
     *     contains a character other than a digit
     */
    public String movePic9(String source, int targetLength) {
        Objects.requireNonNull(source, "A sending value is required for a numeric MOVE");
        requirePositiveLength(targetLength, "a numeric receiver");
        requireDigits(source, "numeric MOVE source");
        if (source.length() == targetLength) {
            return source;
        }
        if (source.length() > targetLength) {
            return source.substring(source.length() - targetLength);
        }
        return repeat(ZERO, targetLength - source.length()) + source;
    }

    /**
     * Performs a COBOL numeric {@code MOVE} from an integral value, zero-filling on the left and truncating
     * on the left, exactly as {@link #movePic9(String, int)} does.
     *
     * @param source the sending value; must not be negative
     * @param targetLength the receiving field's declared digit count; at least 1
     * @return an image of exactly {@code targetLength} digits
     * @throws IllegalArgumentException if {@code targetLength} is below 1 or {@code source} is negative
     */
    public String movePic9(long source, int targetLength) {
        if (source < 0) {
            throw new IllegalArgumentException("Cannot store " + source + " in an unsigned PIC 9 "
                    + "field of " + targetLength + " digit(s); PIC 9 has no sign position, so a "
                    + "signed value belongs in a PIC S9 field and must be encoded as a signed "
                    + "zoned field instead");
        }
        return movePic9(Long.toString(source), targetLength);
    }

    /**
     * Writes an integral value into an unsigned numeric span, applying the {@code PIC 9} move rule.
     *
     * @param record the record area to write into
     * @param field the descriptor naming the span; its length is the receiver's digit count
     * @param value the sending value; must not be negative
     * @throws NullPointerException if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void writePic9(FixedWidthRecord record, FieldSpan field, long value) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, movePic9(value, field.length()));
    }

    /**
     * Writes a digit string into an unsigned numeric span, applying the {@code PIC 9} move rule.
     *
     * @param record the record area to write into
     * @param field the descriptor naming the span; its length is the receiver's digit count
     * @param digits the sending digits
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code digits} is empty or contains a non-digit
     */
    public void writePic9(FixedWidthRecord record, FieldSpan field, String digits) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, movePic9(digits, field.length()));
    }

    /**
     * Decodes an unsigned zoned {@code DISPLAY} image as a {@code long}.
     *
     * @param image the span's characters
     * @return the value the digits denote
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is empty, contains a non-digit, or denotes a value
     *     too large for a {@code long}, which a {@code PIC 9} field of more than 18 digits can
     */
    public long decodePic9(String image) {
        Objects.requireNonNull(image, "An image is required to decode an unsigned numeric field");
        requireDigits(image, "unsigned numeric field");
        try {
            return Long.parseLong(image);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("An unsigned numeric image of " + image.length()
                    + " digit(s) does not fit a long; a PIC 9 field of more than 18 digits must be "
                    + "decoded as a BigInteger by its owning model type", overflow);
        }
    }

    /**
     * Decodes an unsigned zoned {@code DISPLAY} image as an {@code int}, for the {@code PIC 9(n)} fields
     * with {@code n} of 9 or fewer that the migration maps to {@code int}.
     *
     * @param image the span's characters
     * @return the value the digits denote
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is empty, contains a non-digit, or denotes a value
     *     outside the {@code int} range
     */
    public int decodePic9AsInt(String image) {
        long value = decodePic9(image);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("An unsigned numeric image of " + image.length()
                    + " digit(s) denotes a value that exceeds Integer.MAX_VALUE; a PIC 9 field this "
                    + "wide maps to long, not int");
        }
        return (int) value;
    }

    public long readPic9(FixedWidthRecord record, FieldSpan field) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return decodePic9(record.readSpan(field));
    }

    public int readPic9AsInt(FixedWidthRecord record, FieldSpan field) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return decodePic9AsInt(record.readSpan(field));
    }

    // PIC S9(p)V(s) - signed zoned DISPLAY, exactly p + s bytes, sign overpunched into the trailing byte.
    // No sign byte is reserved, and no rounding mode is named: all scaling goes to CobolDecimal.

    /**
     * A signed zoned quantity as the storage actually holds it: an unsigned magnitude and a sign that is
     * recorded separately from it.
     *
     * @param magnitude the unsigned quantity, at the field's declared scale; never negative
     * @param negative whether the field's trailing byte marks the value negative
     */
    public record SignedZoned(BigDecimal magnitude, boolean negative) {
        public SignedZoned {
            Objects.requireNonNull(magnitude, "A magnitude is required; a signed zoned quantity is a "
                    + "magnitude plus a separately recorded sign");
            if (magnitude.signum() < 0) {
                throw new IllegalArgumentException("A signed zoned magnitude of scale "
                        + magnitude.scale() + " is negative. The sign is recorded in its own "
                        + "component precisely so it is held once; a negative magnitude would carry "
                        + "it twice and make the negative-zero case ambiguous again");
            }
        }

        /**
         * Splits a signed {@link BigDecimal} into a magnitude and a sign.
         *
         * @param signedValue the value to split
         * @return the equivalent signed zoned quantity
         * @throws NullPointerException if {@code signedValue} is {@code null}
         */
        public static SignedZoned of(BigDecimal signedValue) {
            Objects.requireNonNull(signedValue, "A value is required to split into magnitude and "
                    + "sign");
            return new SignedZoned(signedValue.abs(), signedValue.signum() < 0);
        }

        /**
         * Reads a decimal literal, honouring a leading minus sign even when every digit is zero.
         *
         * @param literal a decimal literal, optionally signed
         * @return the quantity the literal denotes, negative zero included
         * @throws NullPointerException if {@code literal} is {@code null}
         * @throws NumberFormatException if {@code literal} is not a decimal literal
         */
        public static SignedZoned ofLiteral(String literal) {
            Objects.requireNonNull(literal, "A literal is required to read a signed zoned quantity");
            String trimmed = literal.trim();
            boolean explicitlyNegative = trimmed.startsWith("-");
            BigDecimal parsed = new BigDecimal(trimmed);
            return new SignedZoned(parsed.abs(), explicitlyNegative);
        }

        public BigDecimal signedValue() {
            return negative ? magnitude.negate() : magnitude;
        }

        /**
         * Whether every digit is zero, irrespective of sign.
         *
         * @return {@code true} when the magnitude is zero
         */
        public boolean zero() {
            return magnitude.signum() == 0;
        }

        /**
         * Whether this is the negative zero that a {@link BigDecimal} cannot represent.
         *
         * @return {@code true} only when the magnitude is zero and the sign is negative
         */
        public boolean negativeZero() {
            return negative && zero();
        }
    }

    /**
     * Encodes a signed zoned quantity, preserving its sign independently of its magnitude.
     *
     * @param value the quantity to store
     * @param integerDigits {@code p}, the digit positions left of the implied decimal point; at least 1
     * @param fractionDigits {@code s}, the digit positions right of it; never negative
     * @return an image of exactly {@code integerDigits + fractionDigits} characters
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code integerDigits} is below 1 or {@code fractionDigits} is
     *     negative
     */
    public String encodeSignedZoned(SignedZoned value, int integerDigits, int fractionDigits) {
        Objects.requireNonNull(value, "A signed zoned quantity is required to encode");
        if (integerDigits < 1) {
            throw new IllegalArgumentException("A signed zoned field declares " + integerDigits
                    + " integer digit(s); PIC S9(p)V(s) requires p of at least 1");
        }
        if (fractionDigits < 0) {
            throw new IllegalArgumentException("A signed zoned field declares " + fractionDigits
                    + " fraction digit(s); s must not be negative");
        }

        BigDecimal stored = CobolDecimal.storeAtPicture(value.magnitude(), integerDigits,
                fractionDigits);

        int width = integerDigits + fractionDigits;
        String digits = stored.unscaledValue().toString();
        if (digits.length() < width) {
            digits = repeat(ZERO, width - digits.length()) + digits;
        }
        return overpunch(digits, value.negative());
    }

    /**
     * Decodes a signed zoned image into its magnitude and its sign, so that a negative zero survives.
     *
     * @param image the span's characters; at least 1, and at least {@code scale} of them
     * @param scale {@code s}, the digit positions right of the implied decimal point; never negative
     * @return the quantity the image denotes, its magnitude at exactly {@code scale}
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if the image is empty, the scale is negative or wider than the
     *     image, a leading character is not a digit, or the trailing character is neither a digit nor a
     *     recognised sign overpunch
     */
    public SignedZoned decodeSignedZoned(String image, int scale) {
        Objects.requireNonNull(image, "An image is required to decode a signed zoned field");
        if (image.isEmpty()) {
            throw new IllegalArgumentException("A signed zoned field occupies at least one character, "
                    + "which carries the low-order digit and the sign overpunch");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("Declared scale " + scale + " is negative; s in "
                    + "PIC S9(p)V(s) counts digit positions and cannot be below zero");
        }
        if (scale > image.length()) {
            throw new IllegalArgumentException("Declared scale " + scale + " exceeds the "
                    + image.length() + "-character image; a signed zoned field is "
                    + "p + s characters wide, so s can never exceed its width");
        }

        int lastIndex = image.length() - 1;
        String leadingDigits = image.substring(0, lastIndex);
        if (!leadingDigits.isEmpty()) {
            requireDigits(leadingDigits, "signed zoned field of " + image.length()
                    + " character(s)");
        }

        char trailing = image.charAt(lastIndex);
        int lowOrderDigit = FixedWidthRecord.ZonedSign.digitOf(trailing);
        if (lowOrderDigit < 0) {
            throw new IllegalArgumentException("A signed zoned field of " + image.length()
                    + " character(s) ends in a character that is neither a digit nor a sign "
                    + "overpunch character. The trailing character carries the low-order digit and "
                    + "the sign: '"
                    + POSITIVE_OVERPUNCH + "' for digits 0-9 positive and '" + NEGATIVE_OVERPUNCH
                    + "' for digits 0-9 negative");
        }
        boolean negative = FixedWidthRecord.ZonedSign.isNegative(trailing);

        BigInteger magnitude = new BigInteger(leadingDigits + (char) (ZERO + lowOrderDigit));
        return new SignedZoned(CobolDecimal.store(new BigDecimal(magnitude, scale), scale), negative);
    }

    /**
     * Writes a signed zoned quantity into a span, deriving the integer digit count from the span.
     *
     * @param record the record area to write into
     * @param field the descriptor naming the span; its length must exceed {@code scale}
     * @param value the quantity to store
     * @param scale {@code s}, the receiving field's declared scale
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code scale} leaves no integer digit position
     */
    public void writeSignedZoned(FixedWidthRecord record, FieldSpan field, SignedZoned value,
                                 int scale) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, encodeSignedZoned(value, integerDigitsOf(field, scale), scale));
    }

    /**
     * Reads a signed zoned span as a magnitude and a sign, so a negative zero is reported as one.
     *
     * @param record the record area to read from
     * @param field the descriptor naming the span
     * @param scale {@code s}, the field's declared scale
     * @return the quantity the span denotes
     * @throws NullPointerException if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code scale} leaves no integer digit position, or the span does
     *     not hold a valid signed zoned image
     */
    public SignedZoned readSignedZoned(FixedWidthRecord record, FieldSpan field, int scale) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        integerDigitsOf(field, scale);
        return decodeSignedZoned(record.readSpan(field), scale);
    }

    /**
     * Encodes a value as a signed zoned {@code DISPLAY} image of exactly
     * {@code integerDigits + fractionDigits} characters, with the sign overpunched into the trailing
     * character.
     *
     * @param value the value to store
     * @param integerDigits {@code p}, the digit positions left of the implied decimal point; at least 1
     * @param fractionDigits {@code s}, the digit positions right of it; never negative, and 0 for a
     *     scaleless signed field
     * @return an image of exactly {@code integerDigits + fractionDigits} characters
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code integerDigits} is below 1 or {@code fractionDigits} is
     *     negative
     */
    public String encodeSignedScaled(BigDecimal value, int integerDigits, int fractionDigits) {
        Objects.requireNonNull(value, "A value is required to encode a signed zoned field");
        if (integerDigits < 1) {
            throw new IllegalArgumentException("A signed zoned field declares " + integerDigits
                    + " integer digit(s); PIC S9(p)V(s) requires p of at least 1");
        }
        if (fractionDigits < 0) {
            throw new IllegalArgumentException("A signed zoned field declares " + fractionDigits
                    + " fraction digit(s); s must not be negative");
        }

        // Both truncations - fraction to s, then integer part to p keeping the low-order digits - happen
        // inside it, in the one class that names a rounding mode, and storeAtPicture is documented never to
        // throw on overflow, matching COBOL without ON SIZE ERROR.
        return encodeSignedZoned(SignedZoned.of(value), integerDigits, fractionDigits);
    }

    /**
     * Decodes a signed zoned {@code DISPLAY} image as a {@link BigDecimal} of exactly {@code scale}.
     *
     * @param image the span's characters; at least 1, and at least {@code scale} of them
     * @param scale {@code s}, the digit positions right of the implied decimal point; never negative
     * @return the value the image denotes, at exactly {@code scale}
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is empty, {@code scale} is negative or greater than
     *     the image's width, a leading character is not a digit, or the trailing character is neither a digit
     *     nor a recognised sign overpunch
     */
    public BigDecimal decodeSignedScaled(String image, int scale) {
        return decodeSignedZoned(image, scale).signedValue();
    }

    /**
     * Writes a value into a signed zoned span, deriving the integer digit count from the span itself.
     *
     * @param record the record area to write into
     * @param field the descriptor naming the span; its length must exceed {@code scale}
     * @param value the value to store; truncated toward zero to {@code scale}, and wrapped to the derived
     *     integer digit count if it overflows
     * @param scale {@code s}, the receiving field's declared scale
     * @throws NullPointerException if {@code record}, {@code field} or {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative or leaves no integer digit position,
     *     that is if it is not below {@code field.length()}
     */
    public void writeSignedScaled(FixedWidthRecord record, FieldSpan field, BigDecimal value,
                                 int scale) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, encodeSignedScaled(value, integerDigitsOf(field, scale), scale));
    }

    /**
     * Reads a signed zoned span as a {@link BigDecimal} of exactly {@code scale}.
     *
     * @param record the record area to read from
     * @param field the descriptor naming the span
     * @param scale {@code s}, the field's declared scale
     * @return the value the span denotes, at exactly {@code scale}
     * @throws NullPointerException if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code scale} leaves no integer digit position, or the span does
     *     not hold a valid signed zoned image
     */
    public BigDecimal readSignedScaled(FixedWidthRecord record, FieldSpan field, int scale) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        integerDigitsOf(field, scale);
        return decodeSignedScaled(record.readSpan(field), scale);
    }

    /**
     * Writes a monetary value into a signed zoned span at {@link CobolDecimal#MONETARY_SCALE}.
     *
     * @param record the record area to write into
     * @param field the descriptor naming the span; at least 3 characters wide
     * @param value the monetary value to store
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the span leaves no integer digit position at scale 2
     */
    public void writeMonetary(FixedWidthRecord record, FieldSpan field, BigDecimal value) {
        writeSignedScaled(record, field, value, CobolDecimal.MONETARY_SCALE);
    }

    /**
     * Reads a monetary span as a {@link BigDecimal} of scale {@link CobolDecimal#MONETARY_SCALE}.
     *
     * @param record the record area to read from
     * @param field the descriptor naming the span
     * @return the monetary value the span denotes, at scale 2
     * @throws NullPointerException if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if the span leaves no integer digit position at scale 2, or does not
     *     hold a valid signed zoned image
     */
    public BigDecimal readMonetary(FixedWidthRecord record, FieldSpan field) {
        return readSignedScaled(record, field, CobolDecimal.MONETARY_SCALE);
    }

    /**
     * Emits a span's declared {@code VALUE} literal, or its pad character when it declares none.
     *
     * <p>A declared literal shorter than its span is aligned by the span's {@link PictureKind}: left for
     * character data and {@code FILLER}, right for numeric {@code DISPLAY} data, exactly as COBOL aligns a
     * {@code VALUE} clause.
     *
     * @param record the record area to write into
     * @param field the descriptor naming the span
     * @throws NullPointerException if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if the declared literal is wider than the span
     */
    public void writeDeclaredValue(FixedWidthRecord record, FieldSpan field) {
        Objects.requireNonNull(record, "A record area is required to emit a declared VALUE");
        Objects.requireNonNull(field, "A field descriptor is required to emit its declared VALUE");
        if (field.hasInitialValue()) {
            record.writeSpan(field, field.initialValue());
        } else {
            record.fill(field.offset(), field.length(), record.padByteFor(field.kind()));
        }
    }

    /**
     * Widens a short row to a declared record width by padding it on the right with spaces, and rejects a
     * row that is already too long.
     *
     * @param row the stored row, which may be shorter than {@code declaredWidth}
     * @param declaredWidth the copybook's declared record width; at least 1
     * @return a row of exactly {@code declaredWidth} bytes: {@code row} followed by spaces
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws IllegalArgumentException if {@code declaredWidth} is below 1, or {@code row} is longer than
     *     {@code declaredWidth}
     */
    public byte[] padToDeclaredWidth(byte[] row, int declaredWidth) {
        Objects.requireNonNull(row, "A row is required to pad it to its declared width");
        requirePositiveLength(declaredWidth, "a declared record width");
        if (row.length > declaredWidth) {
            throw new IllegalArgumentException("Row of " + row.length + " byte(s) is wider than the "
                    + "declared width of " + declaredWidth + "; this operation only widens a short "
                    + "row and never truncates, because an over-long row means the layout and the "
                    + "data disagree");
        }
        FixedWidthRecord padded = new FixedWidthRecord(declaredWidth, charset);
        padded.writeBytes(0, row);
        return padded.toByteArray();
    }

    /**
     * Widens a short row image to a declared width by padding it on the right with spaces, and rejects an
     * image that is already too long.
     *
     * @param row the row image, which may be shorter than {@code declaredWidth}
     * @param declaredWidth the copybook's declared record width in characters; at least 1
     * @return an image of exactly {@code declaredWidth} characters
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws IllegalArgumentException if {@code declaredWidth} is below 1, or {@code row} is longer than
     *     {@code declaredWidth}
     */
    public String padToDeclaredWidth(String row, int declaredWidth) {
        Objects.requireNonNull(row, "A row image is required to pad it to its declared width");
        requirePositiveLength(declaredWidth, "a declared record width");
        if (row.length() > declaredWidth) {
            throw new IllegalArgumentException("Row image of " + row.length() + " character(s) is "
                    + "wider than the declared width of " + declaredWidth + "; this operation only "
                    + "widens a short row and never truncates");
        }
        return row + repeat(SPACE, declaredWidth - row.length());
    }

    /**
     * Concatenates operands at their full declared widths, which is what {@code DELIMITED BY SIZE} means:
     * every operand contributes every one of its characters, including its padding.
     *
     * @param operands the sending items, each already at its full declared width; at least one, and none
     *     {@code null}
     * @return the concatenation of every operand's full width
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public String concatenateDelimitedBySize(String... operands) {
        Objects.requireNonNull(operands, "At least one sending item is required for STRING ... "
                + "DELIMITED BY SIZE");
        if (operands.length == 0) {
            throw new IllegalArgumentException("STRING ... DELIMITED BY SIZE requires at least one "
                    + "sending item");
        }
        StringBuilder concatenated = new StringBuilder();
        for (int index = 0; index < operands.length; index++) {
            String operand = operands[index];
            Objects.requireNonNull(operand, "Sending item " + (index + 1) + " of "
                    + operands.length + " is null; every operand of STRING ... DELIMITED BY SIZE "
                    + "contributes its full declared width and so must be present");
            concatenated.append(operand);
        }
        return concatenated.toString();
    }

    /**
     * Performs {@code STRING ... DELIMITED BY SIZE INTO} a span: overlays the concatenated operands at the
     * start of the receiving span and leaves the remainder of the span unchanged.
     *
     * @param record the record area to write into
     * @param field the descriptor naming the receiving span
     * @param operands the sending items, each already at its full declared width
     * @throws NullPointerException if {@code record}, {@code field}, {@code operands} or any operand is
     *     {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoDelimitedBySize(FixedWidthRecord record, FieldSpan field,
                                          String... operands) {
        Objects.requireNonNull(record, "A record area is required for STRING ... INTO");
        Objects.requireNonNull(field, "A receiving field descriptor is required for STRING ... INTO");
        String concatenated = concatenateDelimitedBySize(operands);
        int transferred = Math.min(concatenated.length(), field.length());
        if (transferred > 0) {
            record.writeString(field.offset(), transferred, concatenated.substring(0, transferred));
        }
    }

    /**
     * Builds a complete record from field images, keyed by copybook field name.
     *
     * @param layout the record's layout, transcribed from its copybook
     * @param values field name to field image; names are the copybook's own, verbatim and case-sensitive,
     *     and {@code FILLER} is not among them because it is not referable
     * @return exactly {@code layout.recordLength()} bytes
     * @throws NullPointerException if {@code layout}, {@code values}, or any key or value in {@code values}
     *     is {@code null}
     * @throws IllegalArgumentException if a key names no span in the layout, or an image is not valid for
     *     its span's kind
     */
    public byte[] serialise(RecordLayout layout, Map<String, String> values) {
        Objects.requireNonNull(layout, "A record layout is required to serialise a record");
        FixedWidthRecord record = newRecord(layout);
        applyImages(record, layout, values);
        return record.toByteArray();
    }

    /**
     * Rewrites an existing record from field images, preserving every byte the images do not name -
     * {@code FILLER} and reserved spans included.
     *
     * <p>A program issues {@code READ ... INTO} a {@code WORKING-STORAGE} group, changes some fields, and
     * issues {@code REWRITE ... FROM} that same group: the bytes it never touched, including every
     * {@code FILLER}, travel from the stored record straight back to it.
     *
     * @param layout the record's layout, transcribed from its copybook
     * @param values field name to field image; only the named spans are overwritten
     * @param record the stored record to rewrite from, exactly {@code layout.recordLength()} bytes
     * @return exactly {@code layout.recordLength()} bytes: {@code record} with the named spans replaced
     * @throws NullPointerException if {@code layout}, {@code values}, {@code record}, or any key or value
     *     in {@code values} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} differs from the layout's declared record
     *     length, a key names no span in the layout, or an image is not valid for its span's kind
     */
    public byte[] serialise(RecordLayout layout, Map<String, String> values, byte[] record) {
        Objects.requireNonNull(layout, "A record layout is required to rewrite a record");
        FixedWidthRecord area = wrap(record, layout);
        applyImages(area, layout, values);
        return area.toByteArray();
    }

    /**
     * Decomposes a complete record into raw field images, keyed by copybook field name and returned in
     * copybook declaration order.
     *
     * <p>{@code FILLER} spans are omitted, because {@code FILLER} is not a referable COBOL name and a
     * layout may declare many of them, so they cannot be distinct map keys.
     *
     * @param layout the record's layout, transcribed from its copybook
     * @param record exactly {@code layout.recordLength()} bytes
     * @return an insertion-ordered map from field name to raw image, {@code FILLER} excluded
     * @throws NullPointerException if {@code layout} or {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} differs from the layout's declared record
     *     length
     */
    public Map<String, String> deserialise(RecordLayout layout, byte[] record) {
        Objects.requireNonNull(layout, "A record layout is required to deserialise a record");
        FixedWidthRecord area = wrap(record, layout);
        Map<String, String> images = new LinkedHashMap<>();
        for (FieldSpan field : layout.spans()) {
            if (field.kind().filler()) {
                continue;
            }
            images.put(field.name(), area.readSpan(field));
        }
        return images;
    }

    private void applyImages(FixedWidthRecord record, RecordLayout layout,
                             Map<String, String> values) {
        Objects.requireNonNull(values, "A field image map is required; pass an empty map to emit a "
                + "record holding only the bytes it already has");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(),
                    "A null field name cannot be resolved against the layout");
            String image = Objects.requireNonNull(entry.getValue(),
                    "Field '" + name + "' has a null image; to blank a field supply spaces, and to "
                            + "leave it as it stands omit it from the map");
            writeImage(record, layout.span(name), image);
        }
    }

    private void writeImage(FixedWidthRecord record, FieldSpan field, String image) {
        PictureKind kind = field.kind();
        if (kind == PictureKind.UNSIGNED_NUMERIC) {
            record.writeSpan(field, movePic9(image, field.length()));
        } else if (kind == PictureKind.SIGNED_SCALED) {
            record.writeSpan(field, padSignedImage(field, image));
        } else {
            record.writeSpan(field, movePicX(image, field.length()));
        }
    }

    private String padSignedImage(FieldSpan field, String image) {
        Objects.requireNonNull(image, "A signed zoned image is required for span '" + field.name()
                + "'");
        if (image.length() > field.length()) {
            throw new IllegalArgumentException("A signed zoned image of " + image.length()
                    + " character(s) is wider than " + field.describe() + ", which holds "
                    + field.length() + "; encode the value to the span's width with "
                    + "encodeSignedScaled(BigDecimal, int, int) rather than truncating an image, "
                    + "whose trailing character is a sign overpunch");
        }
        if (image.isEmpty()) {
            throw new IllegalArgumentException("An empty image cannot be stored in "
                    + field.describe() + "; a signed zoned field's trailing character carries its "
                    + "low-order digit and its sign");
        }
        String padded = image.length() == field.length()
                ? image
                : repeat(ZERO, field.length() - image.length()) + image;
        decodeSignedScaled(padded, 0);
        return padded;
    }

    private String overpunch(String digits, boolean negative) {
        int lastIndex = digits.length() - 1;
        int lowOrderDigit = digits.charAt(lastIndex) - ZERO;
        String table = negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return digits.substring(0, lastIndex) + table.charAt(lowOrderDigit);
    }

    private int integerDigitsOf(FieldSpan field, int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException("Declared scale " + scale + " for " + field.describe()
                    + " is negative; s in PIC S9(p)V(s) counts digit positions");
        }
        int integerDigits = field.length() - scale;
        if (integerDigits < 1) {
            throw new IllegalArgumentException("Declared scale " + scale + " leaves "
                    + integerDigits + " integer digit position(s) in " + field.describe()
                    + "; a signed zoned span is p + s characters wide with p of at least 1, so the "
                    + "scale must be below the span's declared length");
        }
        return integerDigits;
    }

    private void requirePositiveLength(int length, String what) {
        if (length < 1) {
            throw new IllegalArgumentException("Declared length " + length + " is not valid for "
                    + what + "; a field occupies at least one character position");
        }
    }

    private void requireDigits(String value, String what) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("An empty value is not a valid " + what
                    + "; a zoned DISPLAY field holds at least one digit");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < ZERO || character > '9') {
                throw new IllegalArgumentException("A " + what + " of " + value.length()
                        + " character(s) is not valid: character " + (index + 1) + " is not a digit, "
                        + "and a zoned DISPLAY field holds only the digits 0 to 9");
            }
        }
    }

    private String repeat(char character, int count) {
        return String.valueOf(character).repeat(count);
    }
}
