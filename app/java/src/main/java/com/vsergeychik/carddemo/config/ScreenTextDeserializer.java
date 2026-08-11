package com.vsergeychik.carddemo.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import java.io.IOException;

/**
 * Judges every inbound JSON string once, at the boundary where the request body is read, for
 * characters no {@code EXEC CICS RECEIVE MAP} could have delivered.
 *
 * <h2>Why the judgement belongs here and not in seventeen controllers</h2>
 * Every payload member of all seventeen screens projects a {@code PIC X(n)} symbolic-map item, and the
 * rule that governs them is one rule. Applied at each controller it would have to be repeated
 * seventeen times over a per-route list of members, and a member left off any one list would be a
 * silent hole - which is precisely the shape of the gap this closes: the code-page judgement it sits
 * beside is wired into three routes and therefore judges nothing on the other fourteen.
 *
 * <p>Placed here it also names the member with <strong>the caller's own JSON spelling</strong>, because
 * that is the only name in scope at this point: the parser is positioned on the field it just read, so
 * there is no Java property name and no COBOL label to be tempted into the answer. That is the same
 * vocabulary every other arm of {@code CobolErrorHandler} uses.
 *
 * <h2>What is judged and what is not</h2>
 * Strings only, and strings anywhere in the body - a top-level screen field, a member of
 * {@code navigationContext}, a member of a communication-area extension, an element of an array. A
 * number, a boolean and an explicit {@code null} carry no character to judge and are left to the
 * deserializer Jackson would otherwise have used; a {@code null} token is returned as {@code null}
 * rather than coerced, so the "absent field is spaces on a terminal" behaviour of every screen is
 * untouched.
 *
 * <p>The rule itself lives in
 * {@link ScreenInputRejectedException#requireDeliverable(String, String)} rather than here, so it is
 * one statement, unit-testable without a parser, and shared with any caller that has a value and a
 * member name. In short: a control character is refused unless it is {@code U+0000} in a trailing run,
 * which is the one shape a real conversation produces, because BMS delivers an unmodified field as
 * all-nulls and this module renders an unpainted field the same way.
 *
 * <h2>How the refusal reaches the caller</h2>
 * A {@link JsonDeserializer} may only fail through Jackson, so the refusal is wrapped in a mapping
 * failure and surfaces to Spring as {@code HttpMessageNotReadableException}.
 * {@code WebConfig.CobolErrorHandler} unwraps it and answers with the same
 * {@code 400 REJECTED_VALUE} body, naming the same member, that a value refused inside a controller
 * produces - so the envelope has one shape however deep the refusal was raised.
 *
 * <h2>Why this does not narrow the API</h2>
 * A route's own {@code 200} response must remain a legal next request, and those responses are full of
 * {@code U+0000}: an unpainted field is rendered as {@code LOW-VALUES} at its declared width. Those
 * values are accepted, by the trailing-run exemption, and that property was re-verified across all
 * seventeen routes after this class was introduced. What is refused is a shape no response of this API
 * ever produces and no terminal can send.
 *
 * <p>Stateless and immutable, so the single instance registered on the object mapper is safe for
 * concurrent use.
 *
 * @see ScreenInputRejectedException#requireDeliverable(String, String)
 */
public final class ScreenTextDeserializer extends JsonDeserializer<String> {

    /** The member name used when the parser is positioned somewhere that has no field name. */
    static final String UNNAMED_MEMBER = "requestBody";

    /**
     * Reads one JSON string and judges it before it becomes a payload value.
     *
     * @param parser  the parser, positioned on the value; must not be {@code null}
     * @param context the deserialization context; must not be {@code null}
     * @return the string as sent, unchanged, or {@code null} for a JSON {@code null}
     * @throws IOException                  if the underlying parser fails
     * @throws ScreenInputRejectedException if the value carries a character no terminal could transmit
     */
    @Override
    public String deserialize(final JsonParser parser, final DeserializationContext context)
            throws IOException {

        if (parser.hasToken(JsonToken.VALUE_NULL)) {
            return null;
        }

        // getValueAsString rather than getText, so a number or a boolean written where a string is
        // expected still coerces exactly as Jackson's own String deserializer would. Only the judgement
        // is added here; no accepted value is altered.
        final String value = parser.getValueAsString();
        ScreenInputRejectedException.requireDeliverable(memberName(parser), value);
        return value;
    }

    /**
     * The name of the member being read, as the caller spelled it in the request body.
     *
     * <p>Walks outwards from the parser's current context to the nearest named field, so an element of
     * an array is attributed to the array's own member rather than to nothing. A body that is a bare
     * string, with no field name anywhere, falls back to {@link #UNNAMED_MEMBER}: no screen accepts such
     * a body, so this is a name for the unreachable case rather than a name a caller will see.
     *
     * @param parser the parser, positioned on the value; must not be {@code null}
     * @return the member name, never {@code null} and never blank
     */
    private static String memberName(final JsonParser parser) {
        for (JsonStreamContext context = parser.getParsingContext();
                context != null;
                context = context.getParent()) {
            final String name = context.getCurrentName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return UNNAMED_MEMBER;
    }
}
