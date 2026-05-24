/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.text;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Screen-title constants translated from {@code app/cpy/COTTL01Y.cpy}.
 *
 * <pre>{@code
 * 01 CCDA-SCREEN-TITLE.
 *    05 CCDA-TITLE01    PIC X(40) VALUE 'AWS Mainframe Modernization'.
 *    05 CCDA-TITLE02    PIC X(40) VALUE 'CardDemo' (commented-out alternative:
 *                       'Credit Card Demo Application (CCDA)').
 *    05 CCDA-THANK-YOU  PIC X(40) VALUE 'Thank you for using CCDA application...'.
 * }</pre>
 *
 * <p>Per AAP &sect;0.7.1 the active (uncommented) values are preserved exactly,
 * including their 40-character padding. The commented-out alternative for
 * {@code CCDA-TITLE02} is documented for traceability but not exposed as
 * a constant.
 */
@CobolProgram(
        value = "COTTL01Y",
        sourcePath = "app/cpy/COTTL01Y.cpy",
        notes = "Screen title constants; 40-char fixed-width strings preserved verbatim"
)
public final class ScreenTitle {

    public static final int LEN_TITLE = 40;

    /** Active value of CCDA-TITLE01 (40 chars). */
    public static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * Active value of CCDA-TITLE02 (40 chars). The COBOL source has the
     * commented-out alternative {@code '  Credit Card Demo Application (CCDA)   '}
     * preserved for historical context.
     */
    public static final String TITLE_02 = "              CardDemo                  ";

    /** Active value of CCDA-THANK-YOU (40 chars). */
    public static final String THANK_YOU = "Thank you for using CCDA application... ";

    private ScreenTitle() {
        // Utility class
    }

    static {
        assertLength("TITLE_01", TITLE_01);
        assertLength("TITLE_02", TITLE_02);
        assertLength("THANK_YOU", THANK_YOU);
    }

    private static void assertLength(String name, String value) {
        if (value.length() != LEN_TITLE) {
            throw new AssertionError(
                    name + " must be exactly " + LEN_TITLE + " chars, got " + value.length());
        }
    }
}
