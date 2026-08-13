package com.vsergeychik.carddemo.common;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.Objects;

/**
 * The response body of every online screen: the projected map, flattened, with its presentation metadata
 * beside it.
 *
 * <p>Every member of the projected map traces to a {@code DFHMDF} field of the screen's mapset, so the
 * flattened body carries exactly the field set the BMS map declares - no more, and none renamed.
 *
 * @param <T> the screen projection - one of the eleven online response types
 * @param screen the projected map, serialised flat; never {@code null}
 * @param screenMetadata the cursor request, message colour, repaint flag and attribute quads; never
 *     {@code null}, and {@link ScreenMetadata#empty()} when the program produced none
 */
public record ScreenResponse<T>(@JsonUnwrapped T screen, ScreenMetadata screenMetadata) {
    public ScreenResponse {
        Objects.requireNonNull(screen, "A screen projection is required: the body of an online "
                + "response is the map the program painted");
        Objects.requireNonNull(screenMetadata, "Screen metadata is required; pass "
                + "ScreenMetadata.empty() when the program produced none");
    }

    public static <T> ScreenResponse<T> of(final T screen, final ScreenMetadata screenMetadata) {
        return new ScreenResponse<>(screen, screenMetadata);
    }

    /**
     * Wraps a screen whose program produced no presentation metadata.
     *
     * @param <T> the screen projection type
     * @param screen the projected map; must not be {@code null}
     * @return the body, carrying {@link ScreenMetadata#empty()}; never {@code null}
     * @throws NullPointerException if {@code screen} is {@code null}
     */
    public static <T> ScreenResponse<T> of(final T screen) {
        return new ScreenResponse<>(screen, ScreenMetadata.empty());
    }
}
