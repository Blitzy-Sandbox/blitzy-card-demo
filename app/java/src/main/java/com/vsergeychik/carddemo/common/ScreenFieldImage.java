package com.vsergeychik.carddemo.common;

/**
 * The one place this codebase decides what a BMS screen field looks like before anything has been written
 * into it, and the one place it decides what the COBOL figurative constant {@code SPACES} looks like at a
 * declared width.
 *
 * <p>A fixed-width field always has a width and therefore always has an image; {@code null} says "there is
 * no such field", which is never true of a {@code DFHMDF} definition.
 */
public final class ScreenFieldImage {
    /**
     * The COBOL figurative constant {@code LOW-VALUES} as a single character - {@code U+0000}, which every
     * supported code page encodes as {@code 0x00}.
     */
    public static final char LOW_VALUE = '\u0000';

    /**
     * The COBOL figurative constant {@code SPACES} as a single character - {@code U+0020}.
     */
    public static final char SPACE = ' ';

    private ScreenFieldImage() {
        throw new AssertionError("ScreenFieldImage records a decision and is never instantiated");
    }

    /**
     * The image of a screen field that has not been painted: {@code width} {@code U+0000} characters,
     * reproducing {@code MOVE LOW-VALUES TO <map>O} for one field of the map.
     *
     * @param width the field's declared width in characters, taken from its symbolic-map
     *     {@code xxxI PIC X(n)} clause; zero or more
     * @return a string of exactly {@code width} {@code U+0000} characters; empty when {@code width} is zero
     * @throws IllegalArgumentException if {@code width} is negative, which is never a declared width
     */
    public static String unpainted(final int width) {
        requireWidth(width);
        return String.valueOf(LOW_VALUE).repeat(width);
    }

    /**
     * The COBOL figurative constant {@code SPACES} filled to a field's declared width.
     *
     * @param width the field's declared width in characters; zero or more
     * @return a string of exactly {@code width} spaces; empty when {@code width} is zero
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String spaces(final int width) {
        requireWidth(width);
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * Whether an image is the unpainted image - every character {@code U+0000}.
     *
     * @param image the field image, normally at its declared width; may be {@code null}
     * @return {@code true} when {@code image} is non-{@code null}, non-empty and consists entirely of
     *     {@code U+0000}
     */
    public static boolean isUnpainted(final String image) {
        if (image == null || image.isEmpty()) {
            return false;
        }
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an image satisfies the COBOL condition {@code = SPACES OR LOW-VALUES} -
     * {@code app/cbl/COSGN00C.cbl:118} and {@code :123}, and the same test in fifteen sibling programs.
     *
     * @param image the field image, normally at its declared width; may be {@code null}
     * @return {@code true} when {@code image} is non-{@code null}, non-empty and is either all spaces or
     *     all {@code U+0000}
     */
    public static boolean isSpacesOrLowValues(final String image) {
        if (image == null || image.isEmpty()) {
            return false;
        }
        return isUnpainted(image) || image.equals(spaces(image.length()));
    }

    private static void requireWidth(final int width) {
        if (width < 0) {
            throw new IllegalArgumentException("Width " + width + " is not a declared field width; a "
                    + "BMS field occupies zero or more characters and its width comes from the "
                    + "symbolic map's xxxI PICTURE clause");
        }
    }
}
