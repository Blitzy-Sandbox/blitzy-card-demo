package com.vsergeychik.carddemo.common;

import java.util.List;

/**
 * The payload members a screen's response carries that its request does not declare, named once so every
 * request type can tolerate them on the way in.
 *
 * <p>The navigation triple is the stateless rendering of {@code EXEC CICS XCTL}: the program decides where
 * control goes, so {@code nextProgram}, {@code nextMapset} and {@code nextMap} are recomputed on every path
 * from the communication area and the program's own branch, never taken from the inbound body.
 */
public final class ResponseOnlyMembers {
    /**
     * {@code EXEC CICS XCTL PROGRAM(...)} - the program control is handed to.
     */
    public static final String NEXT_PROGRAM = "nextProgram";

    /**
     * The mapset the client should paint next, or blanks where the program names none.
     */
    public static final String NEXT_MAPSET = "nextMapset";

    public static final String NEXT_MAP = "nextMap";

    public static final String SCREEN_METADATA = "screenMetadata";

    /**
     * {@code COSGN00C}'s user-type routing decision, projected onto the sign-on response
     * ({@code app/cbl/COSGN00C.cbl:232} and {@code :237}).
     */
    public static final String ROLE = "role";

    /**
     * {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE) ERASE FREEKB} - the
     * unformatted eighty-byte transmission {@code COSGN00C} makes at {@code app/cbl/COSGN00C.cbl:164-169}
     * instead of sending its map.
     */
    public static final String PLAIN_TEXT = "plainText";

    public static final List<String> UNIVERSAL =
            List.of(NEXT_PROGRAM, NEXT_MAPSET, NEXT_MAP, SCREEN_METADATA);

    public static final List<String> ALL =
            List.of(NEXT_PROGRAM, NEXT_MAPSET, NEXT_MAP, SCREEN_METADATA, ROLE, PLAIN_TEXT);

    private ResponseOnlyMembers() {
        throw new AssertionError("ResponseOnlyMembers is a set of names, not a value");
    }
}
