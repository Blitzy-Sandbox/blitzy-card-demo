package com.vsergeychik.carddemo.common;

import java.util.Map;

/**
 * BMS field-attribute and extended-attribute constants: the Java reproduction of the two IBM-supplied
 * copybooks {@code DFHBMSCA} and {@code DFHATTR}.
 *
 * <p>{@code DFHBMSCA}, {@code DFHATTR} and {@code DFHAID} ship with IBM CICS itself, from the
 * {@code SDFHCOB} library, and were never checked in here.
 */
public final class BmsAttributes {
    /**
     * Unprotected, normal intensity, MDT off: {@code 0x40}, EBCDIC space.
     */
    public static final byte DFHBMUNP = (byte) 0x40;

    /**
     * Unprotected, normal intensity, MDT set: {@code 0x41} in ASCII terms but {@code 0xC1} here, EBCDIC
     * {@code 'A'}.
     */
    public static final byte DFHBMFSE = (byte) 0xC1;

    /**
     * Unprotected, intensified, selector-pen detectable, MDT off: {@code 0xC8}, EBCDIC {@code 'H'}.
     */
    public static final byte DFHBMBRY = (byte) 0xC8;

    /**
     * Unprotected, intensified, selector-pen detectable, MDT set: {@code 0xC9}, EBCDIC {@code 'I'}.
     */
    public static final byte DFHUNIMD = (byte) 0xC9;

    /**
     * Unprotected, non-display, non-detectable, MDT off: {@code 0x4C}, EBCDIC {@code '<'}.
     */
    public static final byte DFHBMDAR = (byte) 0x4C;

    /**
     * Unprotected, non-display, non-detectable, MDT set: {@code 0x4D}, EBCDIC {@code '('}.
     */
    public static final byte DFHUNNOD = (byte) 0x4D;

    /**
     * Unprotected, numeric, normal intensity, MDT off: {@code 0x50}, EBCDIC {@code '&'}.
     */
    public static final byte DFHBMUNN = (byte) 0x50;

    /**
     * Unprotected, numeric, normal intensity, MDT set: {@code 0xD1}, EBCDIC {@code 'J'}.
     */
    public static final byte DFHUNNUM = (byte) 0xD1;

    /**
     * Unprotected, numeric, intensified, selector-pen detectable, MDT off: {@code 0xD8}, EBCDIC
     * {@code 'Q'}.
     */
    public static final byte DFHUNNUB = (byte) 0xD8;

    /**
     * Unprotected, numeric, intensified, selector-pen detectable, MDT set: {@code 0xD9}, EBCDIC
     * {@code 'R'}.
     */
    public static final byte DFHUNINT = (byte) 0xD9;

    /**
     * Unprotected, numeric, non-display, non-detectable, MDT set: {@code 0x5D}, EBCDIC {@code ')'}.
     */
    public static final byte DFHUNNON = (byte) 0x5D;

    /**
     * Protected, normal intensity, MDT off: {@code 0x60}, EBCDIC {@code '-'} (hyphen).
     */
    public static final byte DFHBMPRO = (byte) 0x60;

    /**
     * Protected, normal intensity, MDT set: {@code 0x61}, EBCDIC {@code '/'}.
     */
    public static final byte DFHBMPRF = (byte) 0x61;

    /**
     * Protected, intensified, selector-pen detectable, MDT off: {@code 0xE8}, EBCDIC {@code 'Y'}.
     */
    public static final byte DFHPROTI = (byte) 0xE8;

    /**
     * Protected, non-display, non-detectable, MDT off: {@code 0x6C}, EBCDIC {@code '%'}.
     */
    public static final byte DFHPROTN = (byte) 0x6C;

    /**
     * Autoskip - protected and numeric - normal intensity, MDT off: {@code 0xF0}, EBCDIC {@code '0'}.
     */
    public static final byte DFHBMASK = (byte) 0xF0;

    /**
     * Autoskip, normal intensity, MDT set: {@code 0xF1}, EBCDIC {@code '1'}.
     */
    public static final byte DFHBMASF = (byte) 0xF1;

    /**
     * Autoskip, intensified, selector-pen detectable, MDT off: {@code 0xF8}, EBCDIC {@code '8'}.
     */
    public static final byte DFHBMASB = (byte) 0xF8;

    // The copybooks express these as the characters '1' to '7', which in EBCDIC are exactly 0xF1 to 0xF7 -
    // the architected codes and the character literals coincide, which is why the copybook can be written
    // in printable characters at all.

    public static final byte DFHDFCOL = (byte) 0x00;

    /**
     * Blue: {@code 0xF1}, EBCDIC {@code '1'}, 3270 colour code 1.
     */
    public static final byte DFHBLUE = (byte) 0xF1;

    /**
     * Red: {@code 0xF2}, EBCDIC {@code '2'}, 3270 colour code 2.
     */
    public static final byte DFHRED = (byte) 0xF2;

    /**
     * Pink: {@code 0xF3}, EBCDIC {@code '3'}, 3270 colour code 3.
     */
    public static final byte DFHPINK = (byte) 0xF3;

    /**
     * Green: {@code 0xF4}, EBCDIC {@code '4'}, 3270 colour code 4.
     */
    public static final byte DFHGREEN = (byte) 0xF4;

    /**
     * Turquoise: {@code 0xF5}, EBCDIC {@code '5'}, 3270 colour code 5.
     */
    public static final byte DFHTURQ = (byte) 0xF5;

    /**
     * Yellow: {@code 0xF6}, EBCDIC {@code '6'}, 3270 colour code 6.
     */
    public static final byte DFHYELLO = (byte) 0xF6;

    /**
     * Neutral: {@code 0xF7}, EBCDIC {@code '7'}, 3270 colour code 7.
     */
    public static final byte DFHNEUTR = (byte) 0xF7;

    public static final byte DFHDFHI = (byte) 0x00;

    /**
     * Blink: {@code 0xF1}, EBCDIC {@code '1'}.
     */
    public static final byte DFHBLINK = (byte) 0xF1;

    /**
     * Reverse video: {@code 0xF2}, EBCDIC {@code '2'}.
     */
    public static final byte DFHREVRS = (byte) 0xF2;

    /**
     * Underscore: {@code 0xF4}, EBCDIC {@code '4'}.
     */
    public static final byte DFHUNDLN = (byte) 0xF4;

    private static final int MASK_PROTECTED = 0x20;

    private static final int MASK_NUMERIC = 0x10;

    private static final int MASK_DISPLAY = 0x0C;

    private static final int DISPLAY_INTENSIFIED = 0x08;

    private static final int DISPLAY_NON_DISPLAY = 0x0C;

    private static final int MASK_MODIFIED_DATA_TAG = 0x01;

    // Byte-to-mnemonic maps, one per plane, so a failing parity comparison can report "DFHRED" instead of
    // "-14". Deliberately three separate maps and not one merged map: the planes share byte values, so a
    // merged map could not be built at all.

    /**
     * Every Section 1 basic field attribute byte, mapped to its copybook mnemonic.
     */
    public static final Map<Byte, String> FIELD_ATTRIBUTE_MNEMONICS = Map.ofEntries(
            Map.entry(DFHBMUNP, "DFHBMUNP"),
            Map.entry(DFHBMFSE, "DFHBMFSE"),
            Map.entry(DFHBMBRY, "DFHBMBRY"),
            Map.entry(DFHUNIMD, "DFHUNIMD"),
            Map.entry(DFHBMDAR, "DFHBMDAR"),
            Map.entry(DFHUNNOD, "DFHUNNOD"),
            Map.entry(DFHBMUNN, "DFHBMUNN"),
            Map.entry(DFHUNNUM, "DFHUNNUM"),
            Map.entry(DFHUNNUB, "DFHUNNUB"),
            Map.entry(DFHUNINT, "DFHUNINT"),
            Map.entry(DFHUNNON, "DFHUNNON"),
            Map.entry(DFHBMPRO, "DFHBMPRO"),
            Map.entry(DFHBMPRF, "DFHBMPRF"),
            Map.entry(DFHPROTI, "DFHPROTI"),
            Map.entry(DFHPROTN, "DFHPROTN"),
            Map.entry(DFHBMASK, "DFHBMASK"),
            Map.entry(DFHBMASF, "DFHBMASF"),
            Map.entry(DFHBMASB, "DFHBMASB"));

    /**
     * Every Section 2 extended colour value, mapped to its copybook mnemonic.
     */
    public static final Map<Byte, String> COLOUR_MNEMONICS = Map.ofEntries(
            Map.entry(DFHDFCOL, "DFHDFCOL"),
            Map.entry(DFHBLUE, "DFHBLUE"),
            Map.entry(DFHRED, "DFHRED"),
            Map.entry(DFHPINK, "DFHPINK"),
            Map.entry(DFHGREEN, "DFHGREEN"),
            Map.entry(DFHTURQ, "DFHTURQ"),
            Map.entry(DFHYELLO, "DFHYELLO"),
            Map.entry(DFHNEUTR, "DFHNEUTR"));

    /**
     * Every Section 3 extended highlighting value, mapped to its copybook mnemonic.
     */
    public static final Map<Byte, String> HIGHLIGHT_MNEMONICS = Map.ofEntries(
            Map.entry(DFHDFHI, "DFHDFHI"),
            Map.entry(DFHBLINK, "DFHBLINK"),
            Map.entry(DFHREVRS, "DFHREVRS"),
            Map.entry(DFHUNDLN, "DFHUNDLN"));

    /**
     * Reads an attribute byte as an unsigned 0-255 integer.
     *
     * @param attribute any attribute, colour or highlighting byte
     * @return the same bit pattern as an integer in the range 0 through 255 inclusive
     */
    public static int unsigned(final byte attribute) {
        return attribute & 0xFF;
    }

    /**
     * Renders an attribute byte as the EBCDIC hexadecimal notation the copybooks and IBM documentation use,
     * for example {@code X'C1'}.
     *
     * @param attribute any attribute, colour or highlighting byte
     * @return the byte in {@code X'hh'} form, always two upper-case hexadecimal digits
     */
    public static String toHex(final byte attribute) {
        return String.format(java.util.Locale.ROOT, "X'%02X'", unsigned(attribute));
    }

    /**
     * Names a Section 1 basic field attribute byte.
     *
     * @param attribute the byte to identify
     * @return the copybook mnemonic when the byte is one this class defines, otherwise the byte in
     *     {@code X'hh'} form
     */
    public static String fieldAttributeMnemonic(final byte attribute) {
        final String mnemonic = FIELD_ATTRIBUTE_MNEMONICS.get(attribute);
        return mnemonic != null ? mnemonic : toHex(attribute);
    }

    public static String colourMnemonic(final byte colour) {
        final String mnemonic = COLOUR_MNEMONICS.get(colour);
        return mnemonic != null ? mnemonic : toHex(colour);
    }

    /**
     * Names a Section 3 extended highlighting value.
     *
     * @param highlight the byte to identify
     * @return the copybook mnemonic when the byte is one this class defines, otherwise the byte in
     *     {@code X'hh'} form
     */
    public static String highlightMnemonic(final byte highlight) {
        final String mnemonic = HIGHLIGHT_MNEMONICS.get(highlight);
        return mnemonic != null ? mnemonic : toHex(highlight);
    }

    /**
     * Whether a basic field attribute byte protects its field against keyboard entry - bit 2 set.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} for the protected and autoskip combinations, {@code false} for unprotected
     */
    public static boolean isProtected(final byte attribute) {
        return (unsigned(attribute) & MASK_PROTECTED) != 0;
    }

    /**
     * Whether a basic field attribute byte marks its field numeric - bit 3 set.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} when the numeric bit is on
     */
    public static boolean isNumeric(final byte attribute) {
        return (unsigned(attribute) & MASK_NUMERIC) != 0;
    }

    /**
     * Whether a basic field attribute byte makes its field autoskip - protected and numeric together.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} only when both the protected and numeric bits are on
     */
    public static boolean isAutoskip(final byte attribute) {
        return isProtected(attribute) && isNumeric(attribute);
    }

    /**
     * Whether a basic field attribute byte requests high intensity - bits 4-5 set to binary 10.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} for {@link #DFHBMBRY}, {@link #DFHUNIMD}, {@link #DFHUNNUB}, {@link #DFHUNINT},
     *     {@link #DFHPROTI} and {@link #DFHBMASB}
     */
    public static boolean isIntensified(final byte attribute) {
        return (unsigned(attribute) & MASK_DISPLAY) == DISPLAY_INTENSIFIED;
    }

    /**
     * Whether a basic field attribute byte suppresses display - bits 4-5 set to binary 11.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} for {@link #DFHBMDAR}, {@link #DFHUNNOD}, {@link #DFHUNNON} and
     *     {@link #DFHPROTN}
     */
    public static boolean isNonDisplay(final byte attribute) {
        return (unsigned(attribute) & MASK_DISPLAY) == DISPLAY_NON_DISPLAY;
    }

    /**
     * Whether a basic field attribute byte has the modified data tag pre-set - bit 7 set.
     *
     * @param attribute a basic field attribute byte
     * @return {@code true} when the modified data tag bit is on
     */
    public static boolean isModifiedDataTagSet(final byte attribute) {
        return (unsigned(attribute) & MASK_MODIFIED_DATA_TAG) != 0;
    }

    private BmsAttributes() {
        throw new AssertionError("BmsAttributes is a constant holder and must not be instantiated");
    }
}
