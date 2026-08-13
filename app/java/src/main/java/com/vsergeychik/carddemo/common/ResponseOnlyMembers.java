package com.vsergeychik.carddemo.common;

import java.util.List;

/**
 * The payload members a screen's response carries that its request does not declare, named once so
 * every request type can tolerate them on the way in.
 *
 * <h2>Why a request has to tolerate them</h2>
 *
 * <p>CICS is pseudo-conversational: the task ends at {@code EXEC CICS RETURN TRANSID(...)
 * COMMAREA(CARDDEMO-COMMAREA)} and nothing about the conversation survives on the server. Rule R6 and
 * gate G37 carry that property into this module by putting the whole of the conversation - the
 * communication area, the attention identifier and the screen's own field values - in the request and
 * response payloads and keeping no session state. The consequence is direct and is the point of this
 * class: <strong>the client's next request is the previous response.</strong> A client that has to strip
 * members out of the body it was just handed before it can send it back is not holding a conversation,
 * it is reverse-engineering one, and the second screen of every flow is unreachable without that
 * knowledge.
 *
 * <p>Each of these six members is produced by the server and read from nowhere. The navigation triple
 * is the stateless rendering of {@code EXEC CICS XCTL}: the program decides where control goes, so
 * {@code nextProgram}, {@code nextMapset} and {@code nextMap} are recomputed on every path from the
 * communication area and the program's own branch, never taken from the inbound body. {@code role} is
 * {@code COSGN00C}'s hard-coded routing at {@code app/cbl/COSGN00C.cbl:232} and {@code :237} projected
 * onto a response field. {@code screenMetadata} carries the {@code xxxL} cursor request, the message
 * colour and the attribute quads, which AAP 0.6.3 makes metadata rather than payload - a request states
 * its own cursor through its own {@code xxxL} members, not through this one.
 *
 * <h2>Tolerated is not honoured</h2>
 *
 * <p>{@code plainText} is the sign-on screen's own: {@code COSGN00C} answers PF3 with
 * {@code EXEC CICS SEND TEXT}, which transmits eighty bytes to the terminal and sends no map at all, so
 * there is no screen field the text could travel in.
 *
 * <p>These names are declared on each request type through
 * {@code @JsonIgnoreProperties({...})} with {@code ignoreUnknown} left at its default of
 * {@code false}. That combination is exact: these names bind to nothing and are discarded, and
 * <strong>every other unrecognised name is still refused</strong> with the field named in the error
 * envelope's {@code fieldErrors}. So a client may echo the body back verbatim, and a client that
 * misspells a screen field still learns which one - the strictness that catches a typo is kept, and only
 * the strictness that broke the conversation is dropped.
 *
 * <p>Because the values are discarded rather than read, echoing a stale one cannot steer a branch: the
 * response to a body that carries them is identical to the response to the same body with them removed.
 * That is asserted at runtime rather than merely intended.
 *
 * <h2>Scope</h2>
 *
 * <p>Nothing here is a {@code DFHMDF} field, so AAP 0.6.3 does not govern these names and they keep the
 * camelCase spelling they have always had on all seventeen screens. The 441 screen fields take their
 * names and widths from the symbolic maps' {@code xxxI} items and are declared, validated and bound
 * normally.
 *
 * @see NavigationContext
 * @see ScreenMetadata
 * @see ScreenResponse
 */
public final class ResponseOnlyMembers {

    /**
     * {@code EXEC CICS XCTL PROGRAM(...)} - the program control is handed to. Recomputed on every path
     * from the communication area and the taken branch, so an inbound value is discarded.
     */
    public static final String NEXT_PROGRAM = "nextProgram";

    /**
     * The mapset the client should paint next, or blanks where the program names none. Recomputed, so an
     * inbound value is discarded.
     */
    public static final String NEXT_MAPSET = "nextMapset";

    /**
     * The map the client should paint next, or blanks where the program names none. Recomputed, so an
     * inbound value is discarded.
     */
    public static final String NEXT_MAP = "nextMap";

    /**
     * The presentation metadata beside the screen - the {@code xxxL} cursor request, the message colour
     * and the attribute quads. Metadata by declaration (AAP 0.6.3), so an inbound value is discarded.
     */
    public static final String SCREEN_METADATA = "screenMetadata";

    /**
     * {@code COSGN00C}'s user-type routing decision, projected onto the sign-on response
     * ({@code app/cbl/COSGN00C.cbl:232} and {@code :237}). Emitted by the sign-on screen alone, so it is
     * tolerated by the sign-on request alone. Recomputed from {@code USRSEC}, so an inbound value is
     * discarded.
     */
    public static final String ROLE = "role";

    /**
     * {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE) ERASE FREEKB} - the
     * unformatted eighty-byte transmission {@code COSGN00C} makes at {@code app/cbl/COSGN00C.cbl:164-169}
     * instead of sending its map.
     *
     * <p>A {@code SEND TEXT} writes to the terminal with no map and therefore no {@code DFHMDF} field, so
     * the migration has to express it as data for the same reason it expresses {@code XCTL} as data: the
     * server is stateless and the transmission is an observable output. Emitted by the sign-on screen
     * alone, so it is tolerated by the sign-on request alone. Recomputed from the taken branch, so an
     * inbound value is discarded.
     */
    public static final String PLAIN_TEXT = "plainText";

    /**
     * The four members every one of the seventeen responses can carry.
     *
     * <p>Held for tests and diagnostics. The annotations themselves name the constants directly, because
     * an annotation value has to be a compile-time constant and because naming them at each request type
     * keeps the contract visible where a reader of that type will look for it.
     */
    public static final List<String> UNIVERSAL =
            List.of(NEXT_PROGRAM, NEXT_MAPSET, NEXT_MAP, SCREEN_METADATA);

    /**
     * The universal four plus {@link #ROLE} and {@link #PLAIN_TEXT}, which only the sign-on screen
     * produces.
     */
    public static final List<String> ALL =
            List.of(NEXT_PROGRAM, NEXT_MAPSET, NEXT_MAP, SCREEN_METADATA, ROLE, PLAIN_TEXT);

    private ResponseOnlyMembers() {
        throw new AssertionError("ResponseOnlyMembers is a set of names, not a value");
    }
}
