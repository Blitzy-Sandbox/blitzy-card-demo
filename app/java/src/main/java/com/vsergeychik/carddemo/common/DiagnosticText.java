package com.vsergeychik.carddemo.common;

import java.util.Locale;
import java.util.Objects;

/**
 * The module's one policy for rendering a value into a diagnostic - a {@code toString()}, a log line, an
 * exception message - without disclosing it.
 *
 * <p>The migration's contract is byte-for-byte equivalence with the COBOL, so masking a value on any path
 * the COBOL can observe would be a behaviour change and a parity failure - and one of the loudest kinds,
 * since the parity differ compares field by field.
 */
public final class DiagnosticText {
    /**
     * Rendered in place of a value that is withheld entirely.
     */
    public static final String OMITTED = "<omitted>";

    public static final String ABSENT = "<absent>";

    public static final int VISIBLE_TRAILING_CHARACTERS = 4;

    private static final char MASK_CHARACTER = '*';

    private static final String HEX_LITERAL_PREFIX = "X'";

    private static final String HEX_LITERAL_SUFFIX = "'";

    private static final char FIRST_PRINTABLE = 0x20;

    private static final char DELETE = 0x7F;

    private static final char FIRST_C1_CONTROL = 0x80;

    private static final char LAST_C1_CONTROL = 0x9F;

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private DiagnosticText() {
        throw new AssertionError("DiagnosticText is a policy, not a value; call its static methods");
    }

    /**
     * Renders an identifier with only its last {@value #VISIBLE_TRAILING_CHARACTERS} characters legible.
     *
     * @param value the identifier, possibly {@code null}
     * @return the masked identifier, or {@link #ABSENT} when {@code value} is {@code null}
     */
    public static String masked(final String value) {
        if (value == null) {
            return ABSENT;
        }
        if (value.length() <= VISIBLE_TRAILING_CHARACTERS) {
            return repeat(MASK_CHARACTER, value.length());
        }
        final int hidden = value.length() - VISIBLE_TRAILING_CHARACTERS;
        return repeat(MASK_CHARACTER, hidden) + singleLine(value.substring(hidden));
    }

    /**
     * Renders a numeric identifier with only its last {@value #VISIBLE_TRAILING_CHARACTERS} digits legible.
     *
     * @param value the identifier
     * @param digits the field's declared digit count, from its {@code PICTURE}
     * @return the masked identifier
     * @throws IllegalArgumentException if {@code digits} is not positive
     */
    public static String masked(final long value, final int digits) {
        if (digits <= 0) {
            throw new IllegalArgumentException("A PIC 9 field occupies at least one digit position, so "
                    + digits + " cannot be a declared digit count");
        }
        final String unpadded = Long.toString(Math.abs(value));
        final String padded = unpadded.length() >= digits
                ? unpadded
                : repeat('0', digits - unpadded.length()) + unpadded;
        return masked(padded);
    }

    /**
     * Withholds a value entirely, reporting only its width.
     *
     * @param value the value to withhold, possibly {@code null}
     * @return {@link #OMITTED} with the width appended, or {@link #ABSENT} when {@code value} is
     *     {@code null}
     */
    public static String omitted(final String value) {
        if (value == null) {
            return ABSENT;
        }
        return OMITTED + ":" + value.length();
    }

    /**
     * Withholds a numeric value entirely, reporting nothing about it.
     *
     * @return {@link #OMITTED}
     */
    public static String omitted() {
        return OMITTED;
    }

    /**
     * Escapes every control character so the result cannot span more than one line.
     *
     * @param value the text to escape, possibly {@code null}
     * @return the text with every control character escaped, or {@link #ABSENT} when {@code value} is
     *     {@code null}
     */
    public static String singleLine(final String value) {
        if (value == null) {
            return ABSENT;
        }
        int firstControl = -1;
        for (int index = 0; index < value.length(); index++) {
            if (isControl(value.charAt(index))) {
                firstControl = index;
                break;
            }
        }
        if (firstControl < 0) {
            return value;
        }
        final StringBuilder escaped = new StringBuilder(value.length() + 8);
        escaped.append(value, 0, firstControl);
        for (int index = firstControl; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (isControl(character)) {
                escaped.append(HEX_LITERAL_PREFIX)
                        .append(HEX_DIGITS[(character >> 4) & 0xF])
                        .append(HEX_DIGITS[character & 0xF])
                        .append(HEX_LITERAL_SUFFIX);
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /**
     * Renders one screen field, choosing between the three operations from the field's own {@code DFHMDF}
     * label.
     *
     * @param dfhmdfName the field's {@code DFHMDF} label; must not be {@code null}
     * @param value the field's value, possibly {@code null}
     * @return the value masked, withheld or escaped, according to what the label denotes
     * @throws NullPointerException if {@code dfhmdfName} is {@code null}
     */
    public static String screenField(final String dfhmdfName, final String value) {
        final String base = baseLabelOf(Objects.requireNonNull(dfhmdfName,
                "A DFHMDF label is required to decide how one screen field is rendered"));
        for (String identifier : MASKED_LABEL_BASES) {
            if (base.startsWith(identifier)) {
                return masked(value);
            }
        }
        for (String withheld : WITHHELD_LABEL_BASES) {
            if (base.startsWith(withheld)) {
                return omitted(value);
            }
        }
        return singleLine(value);
    }

    private static String baseLabelOf(final String dfhmdfName) {
        String base = dfhmdfName.toUpperCase(Locale.ROOT);
        if (base.length() > 1 && SYMBOLIC_MAP_SUFFIXES.indexOf(base.charAt(base.length() - 1)) >= 0) {
            base = base.substring(0, base.length() - 1);
        }
        while (base.length() > 1 && Character.isDigit(base.charAt(base.length() - 1))) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static final String SYMBOLIC_MAP_SUFFIXES = "IOLFACHPV";

    private static final String[] MASKED_LABEL_BASES = {
        "ACCTNO", "ACCTSID", "ACCTID", "ACTIDIN",
        "CARDNIN", "CARDNUM", "CARDSID", "CARDID", "CRDNUM",
    };

    private static final String[] WITHHELD_LABEL_BASES = {
        "ACSTSSN", "ACTSSN", "ACSGOVT", "ACSEFT", "ACSTDOB", "DOB",
        "ACSFNAM", "ACSMNAM", "ACSLNAM",
        "FNAME", "MNAME", "LNAME", "CRDNAME", "CVV", "ACRDLIM", "EXP",
    };

    private static boolean isControl(final char character) {
        return character < FIRST_PRINTABLE
                || character == DELETE
                || (character >= FIRST_C1_CONTROL && character <= LAST_C1_CONTROL);
    }

    private static String repeat(final char character, final int count) {
        return count <= 0 ? "" : String.valueOf(character).repeat(count);
    }
}
