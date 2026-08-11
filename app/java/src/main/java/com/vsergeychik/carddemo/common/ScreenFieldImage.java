package com.vsergeychik.carddemo.common;

/**
 * The one place this codebase decides what a BMS screen field looks like before anything has been
 * written into it, and the one place it decides what the COBOL figurative constant {@code SPACES}
 * looks like at a declared width.
 *
 * <h2>Why this class exists at all</h2>
 *
 * A BMS symbolic map is a {@code WORKING-STORAGE} group with no {@code VALUE} clause, and every one
 * of the seventeen online programs moves {@code LOW-VALUES} into its output view before painting:
 *
 * <pre>
 *  app/cbl/COSGN00C.cbl:81     MOVE LOW-VALUES TO COSGN0AO
 *  app/cbl/COMEN01C.cbl:89     MOVE LOW-VALUES TO COMEN1AO
 *  app/cbl/COADM01C.cbl:89     MOVE LOW-VALUES TO COADM1AO
 *  app/cbl/COACTVWC.cbl:432    MOVE LOW-VALUES TO CACTVWAO
 *  app/cbl/COACTUPC.cbl:2669   MOVE LOW-VALUES TO CACTUPAO
 *  app/cbl/COCRDLIC.cbl:643    MOVE LOW-VALUES TO CCRDLIAO
 *  app/cbl/COCRDSLC.cbl:428    MOVE LOW-VALUES TO CCRDSLAO
 *  app/cbl/COCRDUPC.cbl:1053   MOVE LOW-VALUES TO CCRDUPAO
 *  app/cbl/COTRN00C.cbl:114    MOVE LOW-VALUES TO COTRN0AO
 *  app/cbl/COTRN01C.cbl:101    MOVE LOW-VALUES TO COTRN1AO
 *  app/cbl/COTRN02C.cbl:122    MOVE LOW-VALUES TO COTRN2AO
 *  app/cbl/CORPT00C.cbl:179    MOVE LOW-VALUES TO CORPT0AO
 *  app/cbl/COBIL00C.cbl:114    MOVE LOW-VALUES TO COBIL0AO
 *  app/cbl/COUSR00C.cbl:117    MOVE LOW-VALUES TO COUSR0AO
 *  app/cbl/COUSR01C.cbl:85     MOVE LOW-VALUES TO COUSR1AO
 *  app/cbl/COUSR02C.cbl:97     MOVE LOW-VALUES TO COUSR2AO
 *  app/cbl/COUSR03C.cbl:97     MOVE LOW-VALUES TO COUSR3AO
 * </pre>
 *
 * Seventeen programs, seventeen sites, one byte: {@code X'00'}. So "this field was never painted"
 * has exactly one image in the legacy system, and it must have exactly one image here. Before this
 * class existed the decision was taken separately in a dozen response types, and they disagreed -
 * some emitted {@code U+0000}, some emitted spaces, two emitted JSON {@code null} - which left a
 * client needing three different emptiness tests against one API.
 *
 * <h2>The rule</h2>
 *
 * <ul>
 *   <li><strong>A screen field the program never wrote is {@link #unpainted(int)}</strong> - a run of
 *       {@code U+0000} at the field's declared width. This is the {@code MOVE LOW-VALUES} image and it
 *       is what every screen field starts as.</li>
 *   <li><strong>A screen field the program wrote spaces into is {@link #spaces(int)}</strong> - a run
 *       of {@code U+0020} at the field's declared width. This is a <em>painted</em> field whose
 *       painted value happens to be blank, and it is a different fact from the one above. The clearest
 *       example is the error line: every one of the ten programs that carry one execute
 *       {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO OF <map>O} unconditionally at the head of
 *       {@code MAIN-PARA} - {@code app/cbl/COTRN00C.cbl:102-103} is representative - so {@code ERRMSGO}
 *       is spaces on every path including the one that never sends the map.</li>
 *   <li><strong>A screen field is never {@code null}.</strong> A fixed-width field always has a width
 *       and therefore always has an image; {@code null} says "there is no such field", which is never
 *       true of a {@code DFHMDF} definition.</li>
 * </ul>
 *
 * The distinction between the first two is not cosmetic. {@code COSGN00C.cbl:118} and {@code :123}
 * test {@code = SPACES OR LOW-VALUES} as two separate conditions, and a field that <em>mixes</em> the
 * two satisfies neither and falls through to {@code WHEN OTHER}, where it is used as a file key. A
 * translation that collapsed the two constants into one would take a different branch there.
 *
 * <h2>Why {@code U+0000} and not spaces, given both are "blank"</h2>
 *
 * Because the source says {@code LOW-VALUES}, and a field-for-field diff of the returned map area
 * would report the substitution (AAP gate G21 and the {@code FieldDiffer} contract). The argument
 * that {@code X'00'} is awkward inside a JSON string was weighed and rejected: it is a well-formed
 * JSON string character, Jackson emits it as the {@code \u0000} escape, and the seven screens that
 * have always emitted it demonstrate the round trip works. Choosing spaces would have been choosing
 * the more comfortable byte over the correct one.
 *
 * <h2>Round-trip safety</h2>
 *
 * {@code U+0000} maps to {@code 0x00} and {@code U+0020} maps to {@code 0x40} under {@code IBM037}
 * and to {@code 0x20} under {@code US-ASCII}; all three are single-byte round trips, so an image
 * produced here survives {@code FixedWidthCodec} in either code page unchanged. Neither constant is
 * code-page dependent at the character level, which is why this class takes no {@code Charset}: it
 * produces characters, and the byte mapping belongs to the codec.
 *
 * <h2>Scope</h2>
 *
 * This class governs <strong>screen fields</strong> - the 441 {@code xxxI} items of the seventeen
 * symbolic maps in {@code app/cpy-bms} (AAP 0.6.3). It does not govern communication-area carriers
 * such as {@code CDEMO-TO-PROGRAM} or {@code CDEMO-LAST-MAPSET}: those are ordinary
 * {@code CARDDEMO-COMMAREA} items, they are not part of any map, and {@link NavigationContext#empty()}
 * documents their own resting state separately.
 *
 * <p>All methods are static and the class cannot be instantiated: this is a decision recorded once,
 * not an object with state.
 *
 * @see NavigationContext#empty()
 * @see FixedWidthCodec#movePicX(String, int)
 */
public final class ScreenFieldImage {

    /**
     * The COBOL figurative constant {@code LOW-VALUES} as a single character - {@code U+0000}, which
     * every supported code page encodes as {@code 0x00}.
     */
    public static final char LOW_VALUE = '\u0000';

    /**
     * The COBOL figurative constant {@code SPACES} as a single character - {@code U+0020}.
     */
    public static final char SPACE = ' ';

    /**
     * Not instantiable: the class records a decision, and an instance of it would carry no state and
     * mean nothing.
     */
    private ScreenFieldImage() {
        throw new AssertionError("ScreenFieldImage records a decision and is never instantiated");
    }

    /**
     * The image of a screen field that has not been painted: {@code width} {@code U+0000} characters,
     * reproducing {@code MOVE LOW-VALUES TO <map>O} for one field of the map.
     *
     * <p>Use this for the resting state of every screen field - at construction, and wherever a
     * program performs the group {@code MOVE LOW-VALUES}. Do not use it for a field the program
     * writes spaces into; use {@link #spaces(int)} there, because the two are different bytes and
     * {@code COSGN00C.cbl:118} and {@code :123} test them separately.
     *
     * @param width the field's declared width in characters, taken from its symbolic-map
     *              {@code xxxI PIC X(n)} clause; zero or more
     * @return a string of exactly {@code width} {@code U+0000} characters; empty when {@code width}
     *         is zero
     * @throws IllegalArgumentException if {@code width} is negative, which is never a declared width
     */
    public static String unpainted(final int width) {
        requireWidth(width);
        return String.valueOf(LOW_VALUE).repeat(width);
    }

    /**
     * The COBOL figurative constant {@code SPACES} filled to a field's declared width.
     *
     * <p>This is an unconditional fill and deliberately <em>not</em> the alphanumeric {@code MOVE}
     * rule: that rule pads a shorter sending value on the right and truncates a longer one, and it
     * lives solely in {@link FixedWidthCodec#movePicX(String, int)}.
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
     * <p>This is the single emptiness test a client needs for "was this field painted?", and it is
     * the reason the codebase converged on one representation. It is deliberately not a Java
     * blankness test: {@link String#isBlank()} reports a run of {@code U+0000} as <em>not</em> blank,
     * and {@code trim().isEmpty()} reports a field mixing {@code U+0000} and spaces as empty. Both
     * would answer differently from the COBOL.
     *
     * <p>An empty string is not unpainted: a zero-width field does not exist in any of the seventeen
     * maps, so there is nothing for the answer to be true of.
     *
     * @param image the field image, normally at its declared width; may be {@code null}
     * @return {@code true} when {@code image} is non-{@code null}, non-empty and consists entirely of
     *         {@code U+0000}
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
     * {@code app/cbl/COSGN00C.cbl:118} and {@code :123}, and the same test in fifteen sibling
     * programs.
     *
     * <p>Two conditions, tested independently, exactly as the source writes them. A field of all
     * spaces satisfies the first; a field of all {@code U+0000} satisfies the second; a field that
     * <strong>mixes</strong> them satisfies neither and must fall through to {@code WHEN OTHER}. That
     * last case is the whole reason this cannot be a blankness test.
     *
     * @param image the field image, normally at its declared width; may be {@code null}
     * @return {@code true} when {@code image} is non-{@code null}, non-empty and is either all spaces
     *         or all {@code U+0000}
     */
    public static boolean isSpacesOrLowValues(final String image) {
        if (image == null || image.isEmpty()) {
            return false;
        }
        return isUnpainted(image) || image.equals(spaces(image.length()));
    }

    /**
     * Rejects a negative width, which is never a declared field width.
     *
     * @param width the width to check
     * @throws IllegalArgumentException if {@code width} is negative
     */
    private static void requireWidth(final int width) {
        if (width < 0) {
            throw new IllegalArgumentException("Width " + width + " is not a declared field width; a "
                    + "BMS field occupies zero or more characters and its width comes from the "
                    + "symbolic map's xxxI PICTURE clause");
        }
    }
}
