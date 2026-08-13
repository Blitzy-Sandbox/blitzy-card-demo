package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * The one place this module decides what a diagnostic rendering is allowed to disclose.
 *
 * <p>A generated {@code toString} on a Java {@code record} renders every component, so a type that carries
 * a PAN discloses it the first time anything renders it - and nothing in the COBOL requires that, because
 * COBOL has no {@code toString}.
 */
public final class SensitiveDiagnostics {
    /**
     * The single marker for a value that is never disclosed in any form.
     *
     * <p>Lower case and bracketed so it cannot be mistaken for stored data: no {@code PIC X} field in any
     * of the twenty-eight copybooks can hold a bracket in a position that would produce this text.
     */
    public static final String REDACTED = "[redacted]";

    public static final String ABSENT = "null";

    public static final char MASK_CHARACTER = '*';

    public static final int REVEALED_TRAILING_DIGITS = 4;

    private static final String TEXT_PREFIX = "[text len=";

    private static final String TEXT_SUFFIX = "]";

    private static final String BLANK_TEXT = "[blank]";

    private static final String EMPTY_TEXT = "[text len=0]";

    /**
     * How much of one field a diagnostic may disclose.
     */
    public enum Disclosure {
        PLAIN,

        IDENTIFIER,

        PAN,

        TEXT,

        REDACTED_VALUE
    }

    /**
     * Renders one field according to its classification.
     *
     * @param disclosure how much of the field may be disclosed; {@code null} is treated as
     *     {@link Disclosure#REDACTED_VALUE}, so a field nobody classified is withheld rather than published
     * @param value the stored value, or {@code null}
     * @return the rendering, never {@code null}
     */
    public static String render(Disclosure disclosure, String value) {
        if (disclosure == null) {
            return REDACTED;
        }
        return switch (disclosure) {
            case PLAIN -> plain(value);
            case IDENTIFIER -> maskIdentifier(value);
            case PAN -> maskPan(value);
            case TEXT -> describeText(value);
            case REDACTED_VALUE -> REDACTED;
        };
    }

    /**
     * The marker for a value withheld unconditionally.
     *
     * @return {@link #REDACTED}, never {@code null}
     */
    public static String redacted() {
        return REDACTED;
    }

    /**
     * Masks a primary account number, keeping its last {@value #REVEALED_TRAILING_DIGITS} digits and its
     * full stored width.
     *
     * <p>Width is preserved because a {@code PIC 9(16)} card number is stored as sixteen bytes and a
     * rendering of a different length would misreport the record's shape - which is the one thing a
     * fixed-width diagnostic still needs to be right about.
     *
     * @param pan the stored card number image, or {@code null}
     * @return the masked rendering, {@link #ABSENT} when {@code pan} is {@code null}, never {@code null}
     *     itself
     */
    public static String maskPan(String pan) {
        return maskTrailing(pan);
    }

    /**
     * Masks a primary account number held as a number, at the declared width of its field.
     *
     * @param pan the card number
     * @param width the field's declared digit count, so the rendering keeps the stored width; a
     *     non-positive width is treated as the number's own digit count
     * @return the masked rendering, never {@code null}
     */
    public static String maskPan(long pan, int width) {
        return maskTrailing(zoned(pan, width));
    }

    /**
     * Masks an identifier - an account, customer or cross-reference key - keeping its last
     * {@value #REVEALED_TRAILING_DIGITS} characters.
     *
     * @param identifier the stored identifier image, or {@code null}
     * @return the masked rendering, {@link #ABSENT} when {@code identifier} is {@code null}
     */
    public static String maskIdentifier(String identifier) {
        return maskTrailing(identifier);
    }

    /**
     * Masks a numeric identifier at the declared width of its field.
     *
     * @param identifier the identifier to mask
     * @param width the field's declared digit count; a non-positive width is treated as the number's own
     *     digit count
     * @return the masked rendering, never {@code null}
     */
    public static String maskIdentifier(long identifier, int width) {
        return maskTrailing(zoned(identifier, width));
    }

    /**
     * Reports the shape of free-text personal data without any of its content.
     *
     * @param value the stored text, or {@code null}
     * @return {@link #ABSENT} when {@code value} is {@code null}, {@link #BLANK_TEXT} when it is present
     *     but entirely spaces, otherwise its length and nothing else
     */
    public static String describeText(String value) {
        if (value == null) {
            return ABSENT;
        }
        if (value.isEmpty()) {
            return EMPTY_TEXT;
        }
        if (value.isBlank()) {
            return BLANK_TEXT;
        }
        return TEXT_PREFIX + value.length() + TEXT_SUFFIX;
    }

    /**
     * Renders a value that carries no personal data, for symmetry at a call site that masks its neighbours.
     *
     * @param value any value, or {@code null}
     * @return the value's own single-line rendering, or {@link #ABSENT}
     */
    public static String plain(Object value) {
        return value == null ? ABSENT : DiagnosticText.singleLine(String.valueOf(value));
    }

    private static String maskTrailing(String value) {
        if (value == null) {
            return ABSENT;
        }
        int length = value.length();
        if (length == 0) {
            return EMPTY_TEXT;
        }
        if (length <= REVEALED_TRAILING_DIGITS) {
            return String.valueOf(MASK_CHARACTER).repeat(length);
        }
        return String.valueOf(MASK_CHARACTER).repeat(length - REVEALED_TRAILING_DIGITS)
                + DiagnosticText.singleLine(value.substring(length - REVEALED_TRAILING_DIGITS));
    }

    /**
     * Renders a number as its zoned {@code DISPLAY} image at a declared width: left-zero-filled when short,
     * and keeping the low-order digits when it does not fit, which is the direction a COBOL numeric
     * receiver truncates in.
     *
     * @param value the value being stored
     * @param width the width the receiving picture declares, in digits
     */
    private static String zoned(long value, int width) {
        String digits = Long.toString(Math.abs(value));
        if (width <= 0 || digits.length() == width) {
            return digits;
        }
        if (digits.length() > width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    private SensitiveDiagnostics() {
        throw new AssertionError("SensitiveDiagnostics is this module's disclosure policy and must not "
                + "be instantiated");
    }

    static {
        Objects.requireNonNull(REDACTED, "The redaction marker is this class's whole purpose");
        if (REVEALED_TRAILING_DIGITS < 1 || REVEALED_TRAILING_DIGITS >= 9) {
            throw new AssertionError("REVEALED_TRAILING_DIGITS is " + REVEALED_TRAILING_DIGITS
                    + "; it must reveal at least one character to be useful for correlation and fewer "
                    + "than the 9 of the narrowest identifier field to withhold anything at all");
        }
    }
}
