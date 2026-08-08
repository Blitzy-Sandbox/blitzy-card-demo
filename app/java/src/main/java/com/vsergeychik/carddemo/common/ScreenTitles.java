package com.vsergeychik.carddemo.common;

/**
 * The three fixed-width screen-title literals of the COBOL copybook {@code app/cpy/COTTL01Y.cpy},
 * transcribed byte-for-byte.
 *
 * <h2>The source, reproduced verbatim</h2>
 *
 * {@code app/cpy/COTTL01Y.cpy} lines 17-24 declare one group item and three {@code PIC X(40)}
 * elementary items:
 *
 * <pre>
 *  01 CCDA-SCREEN-TITLE.
 *    05 CCDA-TITLE01    PIC X(40) VALUE
 *       '      AWS Mainframe Modernization       '.
 *    05 CCDA-TITLE02    PIC X(40) VALUE
 * *     '  Credit Card Demo Application (CCDA)   '.
 *       '              CardDemo                  '.
 *    05 CCDA-THANK-YOU  PIC X(40) VALUE
 *       'Thank you for using CCDA application... '.
 * </pre>
 *
 * Each of the three literals is already exactly {@value #TITLE_LENGTH} characters long in the
 * copybook, so the declared {@code PIC X(40)} width applies no COBOL padding: what the source
 * quotes is what the field holds. The constants below therefore carry the leading and trailing
 * spaces as part of their value, and {@link #TITLE_LENGTH} is the invariant every one of them
 * satisfies.
 *
 * <h2>Line 21 is a comment and is deliberately NOT used</h2>
 *
 * Line 21 of the copybook - the line reading
 * {@code *     '  Credit Card Demo Application (CCDA)   '.} above - carries an asterisk in column
 * 7, which makes it a COBOL comment: an abandoned earlier wording for {@code CCDA-TITLE02} that
 * sits directly above the live value on line 22. It is recorded here so that no future maintainer
 * mistakes the live value for a truncation, and so that nobody "restores" it. This class exposes
 * <strong>only</strong> the live line 22 value. The commented-out wording is not offered as an
 * alternative constant, is not blended with the live value, and must never be substituted for it:
 * that would be a silent behaviour change, and the like-for-like migration documents conflicts
 * rather than fixing them.
 *
 * <h2>Two different "thank you" strings - do not conflate them</h2>
 *
 * {@link #CCDA_THANK_YOU} is <strong>not</strong> the thank-you message owned by the sibling class
 * {@code SystemMessages}. They are different fields, from different copybooks, with different
 * widths and different wording:
 *
 * <ul>
 *   <li>{@code CCDA-THANK-YOU} - this class, from {@code app/cpy/COTTL01Y.cpy}, {@code PIC X(40)},
 *       and it names the <em>CCDA</em> application.</li>
 *   <li>{@code CCDA-MSG-THANK-YOU} - {@code SystemMessages}, from {@code app/cpy/CSMSG01Y.cpy},
 *       {@code PIC X(50)}, and it names the <em>CardDemo</em> application.</li>
 * </ul>
 *
 * Substituting one for the other produces output that reads correctly to a human and fails
 * field-for-field comparison on both length and content. The two are never unified, and this class
 * deliberately does not reference {@code SystemMessages} in code.
 *
 * <h2>Consumers</h2>
 *
 * {@code COPY COTTL01Y.} appears in all 17 CICS online programs - {@code COACTUPC}, {@code COACTVWC},
 * {@code COADM01C}, {@code COBIL00C}, {@code COCRDLIC}, {@code COCRDSLC}, {@code COCRDUPC},
 * {@code COMEN01C}, {@code CORPT00C}, {@code COSGN00C}, {@code COTRN00C}, {@code COTRN01C},
 * {@code COTRN02C}, {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} -
 * making it one of the six universal online includes alongside {@code COCOM01Y}, {@code CSDAT01Y},
 * {@code CSMSG01Y}, {@code DFHAID} and {@code DFHBMSCA}. Every one of those programs paints the two
 * heading lines with {@code MOVE CCDA-TITLE01 TO TITLE01O} and {@code MOVE CCDA-TITLE02 TO TITLE02O},
 * and the receiving symbolic-map items are themselves declared {@code TITLE01I PIC X(40)} and
 * {@code TITLE02I PIC X(40)} in all 17 maps under {@code app/cpy-bms}. The width is therefore 40 at
 * both the source and the destination of every move, which is why {@link #TITLE_LENGTH} is a single
 * shared constant. Because every screen response carries these heading lines, the constants are
 * public.
 *
 * <h2>Handling rules</h2>
 *
 * <ul>
 *   <li>These values are fixed-width screen fields, not display text. Never trim, strip, normalise
 *       or collapse their whitespace, and never store or transmit them without their leading and
 *       trailing spaces - the padding <em>is</em> the field.</li>
 *   <li>Compare and validate against {@link #TITLE_LENGTH} rather than a bare literal 40.</li>
 *   <li>The values are written as ordinary quoted string literals on purpose. A Java text block
 *       would strip incidental leading whitespace and silently destroy the 6- and 14-space
 *       prefixes.</li>
 * </ul>
 *
 * <p>This class is a constants holder with no state and no behaviour: it is {@code final}, cannot be
 * instantiated, and every member is an immutable {@code static final} value. It has no dependency of
 * any kind - no import, no framework annotation - which makes it a root of the dependency graph and
 * safe to reference from every layer.</p>
 *
 * <p>Provenance: {@code app/cpy/COTTL01Y.cpy}, version footer
 * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT}. The copybook is a
 * read-only parity reference and is never modified.</p>
 */
public final class ScreenTitles {

    /**
     * The declared width of every item in {@code 01 CCDA-SCREEN-TITLE}: {@code PIC X(40)}.
     *
     * <p>The same 40 is the width of the symbolic-map items the titles are moved into
     * ({@code TITLE01I PIC X(40)} and {@code TITLE02I PIC X(40)} in all 17 maps), so a single named
     * constant covers both ends of the move and no caller needs a magic number to pad or validate a
     * title field.</p>
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * {@code CCDA-TITLE01} - the upper heading line of every online screen.
     *
     * <p>Source: {@code app/cpy/COTTL01Y.cpy} lines 18-19.</p>
     *
     * <p>Composition, which must be preserved exactly: 6 leading spaces + the 27 characters
     * {@code AWS Mainframe Modernization} + 7 trailing spaces = {@value #TITLE_LENGTH} characters.
     * The literal below is transcribed character-for-character from the copybook; the spaces at both
     * ends are significant.</p>
     */
    public static final String CCDA_TITLE01 =
            "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02} - the lower heading line of every online screen.
     *
     * <p>Source: {@code app/cpy/COTTL01Y.cpy} line 20 (the declaration) with its value on line 22.
     * Line 21, which sits between them, is a comment holding an abandoned earlier wording and is
     * deliberately ignored - see the class documentation.</p>
     *
     * <p>Composition, which must be preserved exactly: 14 leading spaces + the 8 characters
     * {@code CardDemo} + 18 trailing spaces = {@value #TITLE_LENGTH} characters. The literal below
     * is transcribed character-for-character from line 22; the spaces at both ends are
     * significant.</p>
     */
    public static final String CCDA_TITLE02 =
            "              CardDemo                  ";

    /**
     * {@code CCDA-THANK-YOU} - the sign-off line shown when a user leaves the application.
     *
     * <p>Source: {@code app/cpy/COTTL01Y.cpy} lines 23-24.</p>
     *
     * <p>Composition, which must be preserved exactly: the 39 characters
     * {@code Thank you for using CCDA application...} + 1 trailing space =
     * {@value #TITLE_LENGTH} characters. The three trailing full stops are part of the text, and the
     * single trailing space is part of the field.</p>
     *
     * <p>This is the {@code PIC X(40)} message that names the <em>CCDA</em> application. It is not
     * the {@code PIC X(50)} message naming the <em>CardDemo</em> application, which belongs to
     * {@code CCDA-MSG-THANK-YOU} in {@code app/cpy/CSMSG01Y.cpy} and to the sibling class
     * {@code SystemMessages}. Never substitute one for the other.</p>
     */
    public static final String CCDA_THANK_YOU =
            "Thank you for using CCDA application... ";

    /**
     * Not instantiable: this class only holds the copybook's constants and has no state.
     *
     * @throws AssertionError always, if reflection is used to invoke it
     */
    private ScreenTitles() {
        throw new AssertionError("ScreenTitles is a constants holder and must not be instantiated");
    }
}
