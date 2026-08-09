package com.vsergeychik.carddemo.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The presentation metadata of one online screen, carried <strong>beside</strong> the screen's data
 * fields rather than among them.
 *
 * <h2>What this type exists to solve</h2>
 * A BMS symbolic map declares four items per screen field, and only one of them is data
 * (plan section 0.6.3):
 *
 * <table border="1">
 *   <caption>The symbolic-map item pattern and where each item goes</caption>
 *   <tr><th>Item</th><th>Picture</th><th>Where it belongs</th></tr>
 *   <tr><td>{@code xxxI} / {@code xxxO}</td><td>{@code PIC X(n)}</td>
 *       <td>a payload field of the request or response - one JSON member per {@code DFHMDF}</td></tr>
 *   <tr><td>{@code xxxL}</td><td>{@code COMP PIC S9(4)}</td>
 *       <td><strong>metadata</strong> - the reported input length, and the cursor signal a
 *           {@code MOVE -1} sets</td></tr>
 *   <tr><td>{@code xxxF}</td><td>{@code PICTURE X}</td><td><strong>metadata</strong> - the flag byte</td></tr>
 *   <tr><td>{@code xxxA} ({@code REDEFINES xxxF})</td><td>{@code PICTURE X}</td>
 *       <td><strong>metadata</strong> - the attribute view a highlight writes into</td></tr>
 * </table>
 *
 * <p>Keeping the metadata out of the payload is therefore correct: a client that received
 * {@code errMsgColour} as a sibling of {@code errMsg} could no longer tell which members project a
 * {@code DFHMDF} field and which describe how to render one. Keeping it out of the <em>response
 * altogether</em> is a different thing and is a defect: a client cannot then place the cursor where
 * {@code MOVE -1 TO USRIDINL} asked for it, cannot repaint a field the {@code app/cpy/CSSETATY.cpy}
 * rule turned red, and cannot honour {@code MOVE LOW-VALUES TO <map>O} by clearing the screen before
 * painting. This record is the third option, and the one the migration requires: metadata that is
 * separate and available.
 *
 * <h2>One envelope for all seventeen screens</h2>
 * Every online response projects its metadata into <em>this</em> type, so the wire carries one member
 * name - {@code screenMetadata} - with one shape, on every screen. A screen that has no per-field
 * attribute quads (the four {@code COUSR} screens and {@code COADM01}) reports an empty
 * {@link #fields()} map rather than a different shape; a screen whose program never issues a
 * {@code MOVE -1} reports a {@code null} {@link #cursorField()}. Nothing is invented to fill a slot:
 * an absent value means the program produced none.
 *
 * <h2>Why the attribute bytes travel as unsigned integers</h2>
 * A BMS attribute is one byte and most of the {@code DFHBMSCA} values sit above {@code 0x7F}, where a
 * Java {@code byte} reads back negative - {@code DFHRED} is {@code 0xF8}, which prints as
 * {@code -8}. Serialising the raw {@code byte} would publish that negative reading and make the
 * documented hexadecimal codes unrecognisable, so every attribute is projected through
 * {@link BmsAttributes#unsigned(byte)} into the range 0 through 255. The bit pattern is unchanged;
 * only its interpretation is stated explicitly (practice <strong>B8</strong>).
 *
 * <h2>Immutability</h2>
 * A {@code record} whose only reference component is a defensively copied, unmodifiable map, so an
 * instance can be shared by concurrent requests and cannot be altered after a response has been
 * built. It holds no static mutable state (practice <strong>B9</strong>).
 *
 * @param cursorField           the {@code DFHMDF} label of the field the program asked the cursor to
 *                              land on - the field whose {@code xxxL} item received {@code -1} - or
 *                              {@code null} when the program issued no cursor request on this turn
 * @param messageColour         the unsigned 0-255 value of the message field's colour attribute
 *                              ({@code ERRMSGC} on most maps), or {@code null} on a screen that
 *                              resolves no message colour
 * @param resetAllOutputFields  whether the client should clear every rendered output field before
 *                              painting, which is what {@code MOVE LOW-VALUES TO <map>O} asks for
 * @param fields                the attribute quad of each screen field, keyed by {@code DFHMDF}
 *                              label in the map's own order; empty on a screen that carries no quads
 * @see BmsAttributes for the attribute, colour and highlighting constants these bytes come from
 * @see FieldAttributeSetter for the rule that turns a field red and stamps an asterisk into it
 * @see ScreenResponse for the body envelope that carries this beside the projected map
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScreenMetadata(String cursorField,
                             Integer messageColour,
                             boolean resetAllOutputFields,
                             Map<String, FieldMetadata> fields) {

    /**
     * Copies the field map defensively and makes it unmodifiable, so a response cannot be repainted
     * through metadata it has already published.
     *
     * <p>{@code null} is accepted for {@code fields} and normalised to an empty map, because "this
     * screen carries no quads" is a real state that five of the seventeen screens are in, and forcing
     * every one of them to pass {@link Map#of()} would add nothing.
     *
     * @throws NullPointerException if {@code fields} contains a {@code null} key or value
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
    }

    /**
     * The attribute quad of one screen field - its {@code xxxC}, {@code xxxP}, {@code xxxH} and
     * {@code xxxV} items - as unsigned 0-255 values.
     *
     * <p>The four names are the symbolic map's own, and they are kept in the copybook's offset order
     * so that a reader comparing this against {@code app/cpy-bms/*.CPY} meets them in the same
     * sequence:
     *
     * <ol>
     *   <li>{@code xxxC} - the colour attribute, {@code DFHDFCOL} until something overrides it and
     *       {@code DFHRED} once an edit has failed;</li>
     *   <li>{@code xxxP} - the programmed-symbol attribute;</li>
     *   <li>{@code xxxH} - the highlighting attribute, {@code DFHBLINK}, {@code DFHREVRS} or
     *       {@code DFHUNDLN} when set;</li>
     *   <li>{@code xxxV} - the validation attribute, which carries {@code DFHMUSTFI} and its
     *       relatives.</li>
     * </ol>
     *
     * @param colour     the {@code xxxC} byte as 0-255
     * @param protection the {@code xxxP} byte as 0-255
     * @param highlight  the {@code xxxH} byte as 0-255
     * @param validation the {@code xxxV} byte as 0-255
     */
    public record FieldMetadata(int colour, int protection, int highlight, int validation) {

        /**
         * Builds a quad from the four raw attribute bytes, reading each as unsigned.
         *
         * @param colour     the {@code xxxC} item
         * @param protection the {@code xxxP} item
         * @param highlight  the {@code xxxH} item
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
     * <p>This is the state a freshly constructed response is in, and it is a real state rather than a
     * placeholder: a program that has not yet run its {@code MOVE -1} has not asked for a cursor.
     *
     * @return the empty metadata; never {@code null}
     */
    public static ScreenMetadata empty() {
        return new ScreenMetadata(null, null, false, Map.of());
    }

    /**
     * The metadata of a screen that carries a cursor request and a message colour but no per-field
     * quads - the shape the four {@code COUSR} screens and {@code COADM01} are in, none of which
     * declares an attribute item this module writes to beyond the message field.
     *
     * @param cursorField          the {@code DFHMDF} label the cursor was requested on, or
     *                             {@code null} when none was
     * @param messageColour        the message field's colour attribute
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES} asked for a full repaint
     * @return the metadata; never {@code null}
     */
    public static ScreenMetadata of(final String cursorField,
            final byte messageColour,
            final boolean resetAllOutputFields) {
        return new ScreenMetadata(cursorField,
                BmsAttributes.unsigned(messageColour),
                resetAllOutputFields,
                Map.of());
    }

    /**
     * The metadata of a screen that carries per-field attribute quads.
     *
     * @param cursorField          the {@code DFHMDF} label the cursor was requested on, or
     *                             {@code null} when none was
     * @param messageColour        the message field's colour attribute
     * @param resetAllOutputFields whether {@code MOVE LOW-VALUES} asked for a full repaint
     * @param fields               the quads, keyed by {@code DFHMDF} label in map order
     * @return the metadata; never {@code null}
     */
    public static ScreenMetadata of(final String cursorField,
            final byte messageColour,
            final boolean resetAllOutputFields,
            final Map<String, FieldMetadata> fields) {
        return new ScreenMetadata(cursorField,
                BmsAttributes.unsigned(messageColour),
                resetAllOutputFields,
                fields);
    }

    /**
     * This metadata with a different cursor request, for the common case where the quads come from
     * the response and the cursor from the program's working storage.
     *
     * @param newCursorField the {@code DFHMDF} label the cursor was requested on, or {@code null}
     * @return a new instance; this one is unchanged
     */
    public ScreenMetadata withCursorField(final String newCursorField) {
        return new ScreenMetadata(newCursorField, messageColour, resetAllOutputFields, fields);
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
