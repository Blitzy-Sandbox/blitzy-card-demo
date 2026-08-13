package com.vsergeychik.carddemo.common;

/**
 * The three fixed-width screen-title literals of the COBOL copybook {@code app/cpy/COTTL01Y.cpy},
 * transcribed byte-for-byte.
 *
 * <p>The copybook is a read-only parity reference and is never modified.
 */
public final class ScreenTitles {
    /**
     * The declared width of every item in {@code 01 CCDA-SCREEN-TITLE}: {@code PIC X(40)}.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * {@code CCDA-TITLE01} - the upper heading line of every online screen.
     */
    public static final String CCDA_TITLE01 =
            "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02} - the lower heading line of every online screen.
     */
    public static final String CCDA_TITLE02 =
            "              CardDemo                  ";

    /**
     * {@code CCDA-THANK-YOU} - the sign-off line shown when a user leaves the application.
     */
    public static final String CCDA_THANK_YOU =
            "Thank you for using CCDA application... ";

    private ScreenTitles() {
        throw new AssertionError("ScreenTitles is a constants holder and must not be instantiated");
    }
}
