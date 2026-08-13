package com.vsergeychik.carddemo.common;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * An inbound payload value a {@code RECEIVE MAP} could never have delivered, refused at the screen boundary
 * so that it is answered as the caller's error rather than as the transaction abending.
 */
public final class ScreenInputRejectedException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private static final char LOW_VALUE = '\u0000';

    /**
     * What kind of thing is wrong with the request, and the fixed sentence the caller is answered with for
     * it.
     */
    public enum Reason {
        UNSUPPORTED_CHARACTER("contains a character this screen field cannot carry"),

        NOT_CHARACTER_DATA("must be sent as a JSON string, because it is a screen field"),

        TOO_WIDE("is longer than this screen field accepts"),

        OUTSIDE_RANGE("is outside the range this request value accepts"),

        /** Two accepted spellings of one request value state different things. */
        CONTRADICTORY_SPELLINGS("contradicts another spelling of the same request value"),

        /**
         * The payload's screen key member names a different record from the one the URI names, so the
         * request states its key twice and disagrees with itself. Refused rather than resolved: the URI
         * is the resource, and honouring the body instead would let one URI read, rewrite or delete
         * another record.
         */
        CONFLICTING_KEY("names a different record from the one this URI names"),

        /**
         * The conversation-state token did not come from this deployment for this screen: it failed the
         * authentication tag, or it is not a token at all. The caller must send back the token it was
         * handed, unaltered - the program's own communication area is state the program issues, not state
         * a caller composes.
         */
        UNAUTHENTIC_STATE("is not a conversation-state token this screen issued"),

        /**
         * The conversation-state token authenticated, but it was issued for a different record from the one
         * the URI names. Accepting it would let state proved for one account or card be presented as proof
         * for another.
         */
        STATE_NAMES_ANOTHER_RECORD("is a conversation-state token issued for a different record");

        private final String publicPredicate;

        Reason(final String publicPredicate) {
            this.publicPredicate = publicPredicate;
        }

        String publicDetail(final String member) {
            return "The value supplied for " + (member == null ? "this request" : member) + " "
                    + publicPredicate + ". Correct it and send the request again. The rejected value "
                    + "is not echoed here.";
        }
    }

    private final transient String member;

    private final Reason reason;

    private ScreenInputRejectedException(final Reason reason,
            final String member,
            final String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "A Reason is required: it selects the fixed "
                + "sentence the caller is answered with, and there is no unclassified refusal");
        this.member = member;
    }

    /**
     * What kind of thing is wrong with the request.
     *
     * @return the reason; never {@code null}
     */
    public Reason reason() {
        return reason;
    }

    /**
     * The text this refusal is published as: a fixed sentence for its {@link #reason()}, naming the member
     * the caller sent and disclosing nothing else.
     *
     * @return the public detail; never {@code null} and never carrying the rejected value
     */
    public String publicDetail() {
        return reason.publicDetail(member);
    }

    /**
     * The member a {@code RECEIVE MAP} could not have carried this value in.
     *
     * @return the member's name, or an empty {@link Optional} when the refusal names none
     */
    public Optional<String> member() {
        return Optional.ofNullable(member);
    }

    /**
     * Refuses a value carrying a character the dataset code page cannot represent.
     *
     * @param member the payload member's name, as the caller sent it; must not be {@code null}
     * @param itemName the symbolic-map item the member projects, for example {@code ACSFNAMI}; must not be
     *     {@code null}
     * @param charset the code page that cannot represent the character; must not be {@code null}
     * @param codePoint the Unicode code point of the first character that has no representation
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member}, {@code itemName} or {@code charset} is {@code null}
     */
    public static ScreenInputRejectedException unrepresentable(final String member,
            final String itemName,
            final Charset charset,
            final int codePoint) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(itemName, "The symbolic-map item name is required");
        Objects.requireNonNull(charset, "The code page that refused the character is required");
        return new ScreenInputRejectedException(Reason.UNSUPPORTED_CHARACTER, member,
                "The value supplied for " + member
                + " contains a character - Unicode code point U+"
                + String.format("%04X", codePoint) + " - that code page " + charset.name()
                + " cannot represent, so no 3270 RECEIVE MAP could have delivered it into "
                + itemName + ". Every screen field is PIC X(n), which is n bytes in a single-byte "
                + "code page. The rejected value is not echoed here.");
    }

    /**
     * Refuses a control character in a screen value, naming the member and the code point.
     *
     * @param member the payload member's name, as the caller sent it; must not be {@code null}
     * @param codePoint the offending code point, reported as {@code U+XXXX} and never rendered
     * @return the refusal, carrying a value-free message
     * @throws NullPointerException if {@code member} is {@code null}
     */
    public static ScreenInputRejectedException controlCharacter(final String member,
            final int codePoint) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        return new ScreenInputRejectedException(Reason.UNSUPPORTED_CHARACTER, member,
                "The value supplied for " + member
                + " contains a control character - Unicode code point U+"
                + String.format("%04X", codePoint) + " - which a 3270 terminal cannot transmit into a "
                + "PIC X field: a RECEIVE MAP delivers the modified fields of a screen as graphic "
                + "characters. A trailing run of U+0000 is accepted, because that is how BMS delivers "
                + "an unmodified field and how an unpainted field is rendered; one between data bytes "
                + "is not, because Read Modified suppresses nulls. The rejected value is not echoed "
                + "here.");
    }

    /**
     * Judges one screen value for characters no terminal could have transmitted, and refuses the first.
     *
     * <p>A null between data bytes is a different thing: Read Modified suppresses nulls, so no terminal can
     * produce one, and accepting it puts a byte into a parity-critical record that no COBOL program could
     * have written.
     *
     * @param member the payload member's name, as the caller sent it; must not be {@code null}
     * @param value the value as received; may be {@code null}
     * @throws NullPointerException if {@code member} is {@code null}
     * @throws ScreenInputRejectedException if the value carries a control character that is not
     *     {@code U+0000} in a trailing run
     */
    public static void requireDeliverable(final String member, final String value) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        if (value == null || value.isEmpty()) {
            return;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!Character.isISOControl(character)) {
                continue;
            }
            if (character == LOW_VALUE && isTrailingRunOfLowValues(value, index)) {
                return;
            }
            throw controlCharacter(member, character);
        }
    }

    private static boolean isTrailingRunOfLowValues(final String value, final int from) {
        for (int index = from; index < value.length(); index++) {
            if (value.charAt(index) != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sweeps every screen value of one received map and refuses the first one the code page cannot
     * represent.
     *
     * <p>Reporting the uppercase COBOL label handed the caller a third vocabulary alongside the JSON member
     * and the Java property, and a name a client never sent cannot be mapped back to anything in its own
     * request.
     *
     * @param fieldValues the received map's values, keyed by {@code DFHMDF} label; must not be {@code null}
     * @param codec the codec carrying the screen code page; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ScreenInputRejectedException if any value carries a character the code page cannot represent
     */
    public static void requireRepresentable(final Map<String, String> fieldValues,
            final FixedWidthCodec codec) {
        Objects.requireNonNull(fieldValues, "The received map's values are required to judge them");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: the code page a screen value is "
                + "judged against is stated explicitly, never derived from the platform");
        for (Map.Entry<String, String> field : fieldValues.entrySet()) {
            requireRepresentable(jsonMemberOfLabel(field.getKey()), field.getKey() + "I",
                    field.getValue(), codec);
        }
    }

    /**
     * Judges one value against the code page and refuses the first character it cannot represent.
     *
     * @param member the payload member's name, as the caller sent it; must not be {@code null}
     * @param itemName the item the member projects, for example {@code ACSFNAMI}, for the server-side
     *     diagnostic only; must not be {@code null}
     * @param value the value as received; may be {@code null}
     * @param codec the codec carrying the screen code page; must not be {@code null}
     * @throws NullPointerException if {@code member}, {@code itemName} or {@code codec} is {@code null}
     * @throws ScreenInputRejectedException if the value carries a character the code page cannot represent
     */
    public static void requireRepresentable(final String member,
            final String itemName,
            final String value,
            final FixedWidthCodec codec) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(itemName, "The item the member projects is required for the diagnostic");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: the code page a screen value is "
                + "judged against is stated explicitly, never derived from the platform");
        if (value == null) {
            return;
        }
        final OptionalInt offending = codec.firstUnrepresentableCodePoint(value);
        if (offending.isPresent()) {
            throw unrepresentable(member, itemName, codec.charset(), offending.getAsInt());
        }
    }

    /**
     * Refuses a member that arrived as something other than JSON character data.
     *
     * <p>Coercing it would fabricate a character image no terminal sent: {@code 11} for a {@code PIC X(11)}
     * account filter drops the nine leading zeros the screen actually carries, and {@code true} is a
     * five-character word the operator never typed.
     *
     * @param member the payload member's name, as the caller sent it; must not be {@code null}
     * @param tokenShape what arrived instead, for example {@code VALUE_NUMBER_INT}; must not be
     *     {@code null}
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member} or {@code tokenShape} is {@code null}
     */
    public static ScreenInputRejectedException notCharacterData(final String member,
            final String tokenShape) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(tokenShape, "The JSON token shape that arrived is required");
        return new ScreenInputRejectedException(Reason.NOT_CHARACTER_DATA, member,
                "The member " + member + " arrived as JSON " + tokenShape + " where a screen field "
                + "belongs. Every payload member of every screen projects a PIC X(n) item, so it is "
                + "character data or it is nothing: coercing a number would drop leading zeros and "
                + "fabricate a field image no RECEIVE MAP delivered. Send it as a JSON string, or as an "
                + "explicit null where the screen accepts an untransmitted field. The rejected value is "
                + "not echoed here.");
    }

    private static String jsonMemberOfLabel(final String label) {
        return label.toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses a value that is wider than the item it has to be carried in.
     *
     * @param member the payload member, path variable or parameter at fault, spelled as the caller sent it;
     *     must not be {@code null}
     * @param itemName the item it is carried in, for example {@code ACCTSIDI PIC X(11)}; must not be
     *     {@code null}
     * @param declared the item's declared width
     * @param supplied the number of characters supplied
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member} or {@code itemName} is {@code null}
     */
    public static ScreenInputRejectedException tooWide(final String member,
            final String itemName,
            final int declared,
            final int supplied) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(itemName, "The item the value is carried in is required");
        return new ScreenInputRejectedException(Reason.TOO_WIDE, member,
                "The value supplied for " + member + " is "
                + supplied + " characters, but it is carried in " + itemName + ", so a 3270 field could "
                + "not have delivered it. It is refused rather than truncated, because keeping the "
                + "leading " + declared + " characters would silently address a different record than "
                + "the one named. The rejected value is not echoed here.");
    }

    /**
     * Refuses two spellings of one request value that state different things.
     *
     * @param member the canonical spelling, which is the one to send; must not be {@code null}
     * @param alias the accepted alternate spelling; must not be {@code null}
     * @param what what the two spell, for example {@code "one EIBAID"}; must not be {@code null}
     * @return the refusal, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static ScreenInputRejectedException contradictorySpellings(final String member,
            final String alias,
            final String what) {
        Objects.requireNonNull(member, "The canonical parameter name is required");
        Objects.requireNonNull(alias, "The alternate parameter name is required");
        Objects.requireNonNull(what, "A statement of what the two spell is required");
        return new ScreenInputRejectedException(Reason.CONTRADICTORY_SPELLINGS, member,
                "The " + member + " and " + alias
                + " parameters are two spellings of the same value and state different things. One "
                + "terminal interaction has " + what + ", so the two cannot disagree. Send one of them, "
                + "or send both with the same value. Neither stated value is echoed here.");
    }

    /**
     * Refuses a request that states one attention identifier twice and disagrees with itself.
     *
     * @param member the payload member carrying the token, as the caller sent it; must not be {@code null}
     * @param parameterName the query parameter carrying the raw byte; must not be {@code null}
     * @return the refusal, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ScreenInputRejectedException conflictingAid(final String member,
            final String parameterName) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(parameterName, "The parameter name carrying the raw byte is required");
        return new ScreenInputRejectedException(Reason.CONTRADICTORY_SPELLINGS, member,
                "The " + parameterName + " parameter and the payload's " + member
                + " both state which key was pressed, and they name different keys. One terminal "
                + "interaction presents one attention identifier, so the two cannot disagree; and "
                + "because " + parameterName + " carries the byte itself while " + member + " carries a "
                + "token folded from it, honouring either would select a key the caller did not "
                + "unambiguously send. Send " + parameterName + " alone, or send a " + member + " that "
                + "is the token that byte stores, or leave " + member + " blank or LOW-VALUES. Neither "
                + "stated key is echoed here.");
    }

    /**
     * Refuses a value outside the range the item it stands for can hold.
     *
     * @param member the parameter or member at fault, spelled as the caller sent it; must not be
     *     {@code null}
     * @param what what the item is, for example {@code "one EIBAID byte"}; must not be {@code null}
     * @param low the lowest acceptable value
     * @param high the highest acceptable value
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member} or {@code what} is {@code null}
     */
    public static ScreenInputRejectedException outsideRange(final String member,
            final String what,
            final int low,
            final int high) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(what, "A statement of what the item is is required");
        return new ScreenInputRejectedException(Reason.OUTSIDE_RANGE, member,
                "The value supplied for " + member + " is "
                + "outside " + low + " to " + high + ", which is what " + what + " can hold, so no "
                + "terminal could have presented it. Narrowing it silently would select a value the "
                + "caller never sent. The rejected value is not echoed here.");
    }

    /**
     * Refuses a payload whose screen key member names a different record from the URI.
     *
     * <p>Neither value is echoed, for the reason the class documents: these key spans hold account
     * identifiers, card numbers, transaction identifiers and user identifiers.
     *
     * @param member the payload member's name, as the caller sent it; must not be {@code null}
     * @param width  the member's declared {@code PICTURE} width
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member} is {@code null}
     */
    public static ScreenInputRejectedException conflictingKey(final String member, final int width) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        return new ScreenInputRejectedException(Reason.CONFLICTING_KEY, member,
                "The URI names one record and the payload's " + member + " names another, so the "
                + "request states its key twice and disagrees with itself. The URI is the resource this "
                + "call acts on, so honouring the body instead would let one URI read, rewrite or "
                + "delete a different record than the one it names. Send " + member + " as the URI's "
                + "key at its declared PIC X(" + width + ") width, or leave it blank or LOW-VALUES and "
                + "let the URI state the key alone. Neither rejected value is echoed here.");
    }

    /**
     * Requires a payload's screen key member to agree with the key the URI names, and refuses it when
     * it contradicts it.
     *
     * <p>Four kinds of input agree and are accepted, because on a terminal each of them means "the
     * operator typed nothing here, so the key comes from elsewhere" or "the operator typed exactly this
     * key":
     *
     * <ol>
     *   <li>{@code null} - the member did not arrive at all;</li>
     *   <li>all spaces or all {@code LOW-VALUES} at the declared width - which is what BMS leaves in an
     *       input field that was never modified, and what every one of these routes paints into the key
     *       member on a cold start;</li>
     *   <li>any image the screen itself reads as "no criterion supplied", passed in by the caller. The
     *       account and card screens paint {@code '*'} into their key field when nothing was supplied
     *       [{@code app/cbl/COACTVWC.cbl:563}, {@code app/cbl/COCRDSLC.cbl:543,549}] and read
     *       {@code = '*'} back as exactly that [{@code COACTVWC:628}, {@code COACTUPC:1051},
     *       {@code COCRDSLC:615,622}], so an asterisk names no record and a client echoing that painted
     *       screen must not be refused;</li>
     *   <li>the URI's key itself, brought to the declared width by the {@code PIC X} move rule - which
     *       is what a client echoing a <em>populated</em> painted screen sends back.</li>
     * </ol>
     *
     * <p>Anything else is two different keys in one request and is refused by
     * {@link #conflictingKey(String, int)}. Every comparison is made <em>at the declared width</em>, so
     * {@code "USER1"} and {@code "USER1   "} are the same key rather than a conflict - the field is
     * {@code PIC X(n)} and a short value is space padded on a terminal too, and a one-character literal
     * like {@code '*'} is space extended before it is compared exactly as COBOL extends it.
     *
     * <h4>Why this is a transport judgement and not an invented COBOL condition</h4>
     * A 3270 screen has one key field and no URI, so the source has no answer to a request that states
     * its key twice, because no conversation can produce one. The REST projection is the only layer
     * that has two carriers, and it is the layer that has to reconcile them. It does so in the one way
     * that keeps the URI meaning what it says: the caller is told the request contradicts itself, and
     * the identity the caller typed is never silently discarded and never silently honoured over the
     * resource the call names.
     *
     * @param member            the payload member's name, as the caller sent it; must not be
     *                          {@code null}
     * @param uriKey            the key the URI names, already required to fit; must not be {@code null}
     * @param bodyValue         the member as it arrived, of any length, or {@code null}
     * @param width             the member's declared {@code PICTURE} width
     * @param codec             the codec carrying the {@code PIC X} move rule; must not be {@code null}
     * @param alsoMeaningNoKey  further images this screen reads as "no criterion supplied", each
     *                          compared at {@code width}; none for a screen that has none
     * @throws NullPointerException         if {@code member}, {@code uriKey}, {@code codec} or any
     *                                      element of {@code alsoMeaningNoKey} is {@code null}
     * @throws ScreenInputRejectedException if the member names a different record from the URI
     */
    public static void requireKeyAgreement(final String member,
            final String uriKey,
            final String bodyValue,
            final int width,
            final FixedWidthCodec codec,
            final String... alsoMeaningNoKey) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(uriKey, "The key the URI names is required to compare against");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: a PIC X comparison is made at the "
                + "declared width, never by trimming");
        Objects.requireNonNull(alsoMeaningNoKey, "A no-criterion image array is required, possibly empty");
        if (bodyValue == null) {
            return;
        }
        final String supplied = codec.movePicX(bodyValue, width);
        if (isEvery(supplied, ' ') || isEvery(supplied, LOW_VALUE)) {
            return;
        }
        for (final String noCriterion : alsoMeaningNoKey) {
            Objects.requireNonNull(noCriterion, "A no-criterion image must not be null");
            if (supplied.equals(codec.movePicX(noCriterion, width))) {
                return;
            }
        }
        if (supplied.equals(codec.movePicX(uriKey, width))) {
            return;
        }
        throw conflictingKey(member, width);
    }

    /**
     * Refuses a conversation-state token this deployment did not issue for this screen.
     *
     * <p>Covers every way a token can fail to be one: not Base64-URL, too short to hold an initialisation
     * vector and a tag, a failed authentication tag, or a length prefix that does not describe its own
     * contents. They are one answer to a caller because the correction is the same - send back the token
     * that was handed to you, unaltered - and because distinguishing them would tell an attacker which of
     * their guesses got closer.
     *
     * @param member the payload member the token arrived in; must not be {@code null}
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member} is {@code null}
     */
    public static ScreenInputRejectedException unauthenticStateToken(final String member) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        return new ScreenInputRejectedException(Reason.UNAUTHENTIC_STATE, member,
                "The value supplied for " + member + " is not a conversation-state token this screen "
                + "issued: it is not the shape of one, or its authentication tag does not verify. The "
                + "program's own communication area carries the proof that its edits already passed, so "
                + "it is state the program issues and never state a caller composes. Send back the token "
                + "that was handed to you, unaltered. The rejected value is not echoed here.");
    }

    /**
     * Refuses a conversation-state token that authenticated but belongs to another record.
     *
     * @param member the payload member the token arrived in; must not be {@code null}
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member} is {@code null}
     */
    public static ScreenInputRejectedException stateTokenNamesAnotherRecord(final String member) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        return new ScreenInputRejectedException(Reason.STATE_NAMES_ANOTHER_RECORD, member,
                "The value supplied for " + member + " is a conversation-state token this screen issued, "
                + "but for a different record from the one this URI names. State proved for one record is "
                + "not proof for another: the snapshots it carries, and the confirmation it records, were "
                + "reached against the record it was issued for. Start this record's conversation at its "
                + "own URI. Neither record is named here.");
    }

    /**
     * Whether every character of a value is the given one, which is how COBOL reads {@code EQUAL SPACES}
     * and {@code EQUAL LOW-VALUES} over a fixed-width item.
     *
     * @param value     the value, already at its declared width
     * @param character the character to test for
     * @return {@code true} when the value is not empty and every character is {@code character}
     */
    private static boolean isEvery(final String value, final char character) {
        if (value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != character) {
                return false;
            }
        }
        return true;
    }
}
