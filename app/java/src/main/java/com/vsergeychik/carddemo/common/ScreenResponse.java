package com.vsergeychik.carddemo.common;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.Objects;

/**
 * The response body of every online screen: the projected map, flattened, with its presentation
 * metadata beside it.
 *
 * <h2>The wire shape</h2>
 * {@link #screen()} is annotated {@link JsonUnwrapped}, so its members serialise at the top level of
 * the body exactly as they did before this envelope existed - one JSON member per {@code DFHMDF}
 * field, plus the communication area and the navigation triple - and {@link #screenMetadata()} joins
 * them as a single sibling object:
 *
 * <pre>{@code
 * {
 *   "trnName": "CU02",
 *   "usrIdIn": "USER0001",
 *   ...
 *   "navigationContext": { ... },
 *   "nextProgram": "COUSR02C",
 *   "nextMapset": "COUSR02",
 *   "nextMap":    "COUSR2A",
 *   "screenMetadata": {
 *     "cursorField": "USRIDIN",
 *     "messageColour": 248,
 *     "resetAllOutputFields": false,
 *     "fields": { "ERRMSG": { "colour": 248, "protection": 0, "highlight": 0, "validation": 0 } }
 *   }
 * }
 * }</pre>
 *
 * <h2>Why an envelope and not extra members on each response type</h2>
 * Three reasons, and the third is the one that matters most:
 *
 * <ol>
 *   <li><strong>The payload stays a 1:1 projection of the map.</strong> Plan section 0.3.9 requires
 *       every request and response field to map to a {@code DFHMDF} field. A colour byte or a cursor
 *       request is not one, so it must not be a sibling of the fields that are - and here it is not:
 *       it is one level down, under a member that says what it is.</li>
 *   <li><strong>The eleven response types keep their declared shape.</strong> Each was derived
 *       field-for-field from its symbolic map and several are {@code record}s whose component list is
 *       that derivation; widening them would blur the very correspondence a parity review checks.</li>
 *   <li><strong>One design, seventeen screens.</strong> Metadata reaches a client the same way on
 *       every screen, under the same member name, in the same shape. A per-screen arrangement -
 *       {@code errMsgColour} here, {@code attributeItems} there - would leave a client writing screen
 *       specific rendering code for a mechanism that is uniform in BMS.</li>
 * </ol>
 *
 * <h2>Serialisation only</h2>
 * This is an outbound type. {@link JsonUnwrapped} is fully supported when writing, which is all a
 * response body needs; nothing in this module reads one back, and a client that wants to echo a
 * screen back sends the paired <em>request</em> type, whose fields are flat to begin with. The
 * accessor {@link #screen()} is how a test or a service reaches the projected map without going
 * through JSON.
 *
 * @param <T>            the screen projection - one of the eleven online response types
 * @param screen         the projected map, serialised flat; never {@code null}
 * @param screenMetadata the cursor request, message colour, repaint flag and attribute quads; never
 *                       {@code null}, and {@link ScreenMetadata#empty()} when the program produced
 *                       none
 * @see ScreenMetadata for what the metadata carries and why the attribute bytes are unsigned
 */
public record ScreenResponse<T>(@JsonUnwrapped T screen, ScreenMetadata screenMetadata) {

    /**
     * Rejects {@code null} in either component: a body always carries a screen, and a screen whose
     * program produced no metadata carries {@link ScreenMetadata#empty()} rather than nothing, so
     * that a client never has to distinguish "absent" from "empty".
     *
     * @throws NullPointerException if either component is {@code null}
     */
    public ScreenResponse {
        Objects.requireNonNull(screen, "A screen projection is required: the body of an online "
                + "response is the map the program painted");
        Objects.requireNonNull(screenMetadata, "Screen metadata is required; pass "
                + "ScreenMetadata.empty() when the program produced none");
    }

    /**
     * Wraps a screen with its metadata.
     *
     * @param screen         the projected map; must not be {@code null}
     * @param screenMetadata the metadata; must not be {@code null}
     * @param <T>            the screen projection type
     * @return the body; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static <T> ScreenResponse<T> of(final T screen, final ScreenMetadata screenMetadata) {
        return new ScreenResponse<>(screen, screenMetadata);
    }

    /**
     * Wraps a screen whose program produced no presentation metadata.
     *
     * @param screen the projected map; must not be {@code null}
     * @param <T>    the screen projection type
     * @return the body, carrying {@link ScreenMetadata#empty()}; never {@code null}
     * @throws NullPointerException if {@code screen} is {@code null}
     */
    public static <T> ScreenResponse<T> of(final T screen) {
        return new ScreenResponse<>(screen, ScreenMetadata.empty());
    }
}
