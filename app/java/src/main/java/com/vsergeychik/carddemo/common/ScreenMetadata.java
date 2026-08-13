package com.vsergeychik.carddemo.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The presentation metadata of one online screen, carried beside the screen's data fields rather than among
 * them.
 *
 * @param cursorField the {@code DFHMDF} label of the field the program asked the cursor to land on - the
 *     field whose {@code xxxL} item received {@code -1}
 * @param messageColour the unsigned 0-255 value of the message field's colour attribute ({@code ERRMSGC} on
 *     most maps), or {@code null} on a screen that resolves no message colour
 * @param resetAllOutputFields whether the client should clear every rendered output field before painting,
 *     which is what {@code MOVE LOW-VALUES TO <map>O} asks for
 * @param fields the attribute quad of each screen field, keyed by {@code DFHMDF} label in the map's own
 *     order; empty on a screen that carries no quads
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScreenMetadata(String cursorField,
                             Integer messageColour,
                             boolean resetAllOutputFields,
                             Map<String, FieldMetadata> fields,
                             List<String> nonDisplayFields) {

    /**
     * The {@code DFHMDF} label of the password field on the three screens that have one: {@value}.
     *
     * <p>One label, spelled once, because all three mapsets spell it identically - {@code COSGN00.bms}
     * line 175, {@code COUSR01.bms} line 126 and {@code COUSR02.bms} line 130.
     */
    public static final String PASSWORD_FIELD_LABEL = "PASSWD";

    /**
     * The non-display declaration of a screen whose only {@code DRK} field is its password.
     *
     * <p>The sign-on, user-add and user-update screens each declare exactly one, so they share one
     * immutable list rather than each building its own.
     */
    public static final List<String> PASSWORD_IS_NON_DISPLAY = List.of(PASSWORD_FIELD_LABEL);

    /**
     * Copies the field map defensively and makes it unmodifiable, so a response cannot be repainted
     * through metadata it has already published.
     *
     * <p>{@code null} is accepted for {@code fields} and normalised to an empty map, because "this
     * screen carries no quads" is a real state that five of the seventeen screens are in, and forcing
     * every one of them to pass {@link Map#of()} would add nothing. {@code nonDisplayFields} is
     * normalised the same way, for the same reason: fourteen of the seventeen declare no {@code DRK}
     * field.
     *
     * @throws NullPointerException if {@code fields} contains a {@code null} key or value, or
     *                              {@code nonDisplayFields} contains a {@code null} label
     */
    public ScreenMetadata {
        fields = fields == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        for (Map.Entry<String, FieldMetadata> entry : fields.entrySet()) {
            Objects.requireNonNull(entry.getKey(),
                    "A field metadata entry must be keyed by its DFHMDF label, never by null");
            Objects.requireNonNull(entry.getValue(),
                    "A field metadata entry must carry a quad, never null: a screen field always has "
                            + "an xxxC, xxxP, xxxH and xxxV item");
        }
        nonDisplayFields = nonDisplayFields == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(nonDisplayFields));
        for (String label : nonDisplayFields) {
            Objects.requireNonNull(label,
                    "A non-display declaration names a DFHMDF label, never null");
        }
    }

    /**
     * The attribute quad of one screen field - its colour, protection, highlighting and validation bytes -
     * as unsigned 0-255 values.
     *
     * @param colour the {@code xxxC} byte as 0-255
     * @param protection the basic attribute byte as 0-255: the {@code xxxA} item where the program writes
     *     one, the {@code xxxP} item otherwise
     * @param highlight the {@code xxxH} byte as 0-255
     * @param validation the {@code xxxV} byte as 0-255
     */
    public record FieldMetadata(int colour, int protection, int highlight, int validation) {
        /**
         * Builds a quad from the four raw attribute bytes, reading each as unsigned.
         *
         * @param colour the {@code xxxC} item
         * @param protection the basic attribute byte - the {@code xxxA} item where the program writes one,
         *     the {@code xxxP} item otherwise
         * @param highlight the {@code xxxH} item
         * @param validation the {@code xxxV} item
         * @return the quad; never {@code null}
         */
        public static FieldMetadata of(final byte colour,
                final byte protection,
                final byte highlight,
                final byte validation) {
            return new FieldMetadata(BmsAttributes.unsigned(colour),
                    BmsAttributes.unsigned(protection),
                    BmsAttributes.unsigned(highlight),
                    BmsAttributes.unsigned(validation));
        }
    }

    /**
     * The metadata of a screen that has published none yet: no cursor request, no message colour, no
     * repaint instruction and no quads.
     *
     * @return the empty metadata; never {@code null}
     */
    public static ScreenMetadata empty() {
        return new ScreenMetadata(null, null, false, Map.of(), List.of());
    }

    /**
     * The metadata of a screen that carries a cursor request and a message colour but no per-field quads -
     * the shape the four {@code COUSR} screens and {@code COADM01} are in, none of which declares an
     * attribute item this module writes to beyond the message field.
     *
     * @param cursorField the {@code DFHMDF} label the cursor was requested on, or {@code null} when none
     *     was
     * @param messageColour the message field's colour attribute
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES} asked for a full repaint
     * @return the metadata; never {@code null}
     */
    public static ScreenMetadata of(final String cursorField,
            final byte messageColour,
            final boolean resetAllOutputFields) {
        return of(cursorField, messageColour, resetAllOutputFields, List.of());
    }

    /**
     * The metadata of a screen that carries a cursor request, a message colour and a non-display
     * declaration, but no per-field quads - the shape the sign-on, user-add and user-update screens are
     * in, each of which declares {@code PASSWD ATTRB=(DRK,...)} in its mapset and assigns no attribute
     * item of its own.
     *
     * @param cursorField          the {@code DFHMDF} label the cursor was requested on, or
     *                             {@code null} when none was
     * @param messageColour        the message field's colour attribute
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES} asked for a full repaint
     * @param nonDisplayFields     the {@code DFHMDF} labels the mapset declares {@code DRK}; use
     *                             {@link #PASSWORD_IS_NON_DISPLAY} for a password screen
     * @return the metadata; never {@code null}
     */
    public static ScreenMetadata of(final String cursorField,
            final byte messageColour,
            final boolean resetAllOutputFields,
            final List<String> nonDisplayFields) {
        return new ScreenMetadata(cursorField,
                BmsAttributes.unsigned(messageColour),
                resetAllOutputFields,
                Map.of(),
                nonDisplayFields);
    }

    /**
     * The metadata of a screen that carries per-field attribute quads.
     *
     * @param cursorField the {@code DFHMDF} label the cursor was requested on, or {@code null} when none
     *     was
     * @param messageColour the message field's colour attribute
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES} asked for a full repaint
     * @param fields the quads, keyed by {@code DFHMDF} label in map order
     * @return the metadata; never {@code null}
     */
    public static ScreenMetadata of(final String cursorField,
            final byte messageColour,
            final boolean resetAllOutputFields,
            final Map<String, FieldMetadata> fields) {
        return new ScreenMetadata(cursorField,
                BmsAttributes.unsigned(messageColour),
                resetAllOutputFields,
                fields,
                List.of());
    }

    /**
     * This metadata with a different cursor request, for the common case where the quads come from the
     * response and the cursor from the program's working storage.
     *
     * @param newCursorField the {@code DFHMDF} label the cursor was requested on, or {@code null}
     * @return a new instance; this one is unchanged
     */
    public ScreenMetadata withCursorField(final String newCursorField) {
        return new ScreenMetadata(newCursorField, messageColour, resetAllOutputFields, fields,
                nonDisplayFields);
    }

    /**
     * The quad of one field, by its {@code DFHMDF} label.
     *
     * @param dfhmdfLabel the label, for example {@code "ERRMSG"} or {@code "ACCTSID"}
     * @return that field's quad, or {@code null} when this screen publishes none for it
     */
    public FieldMetadata field(final String dfhmdfLabel) {
        return fields.get(dfhmdfLabel);
    }
}
