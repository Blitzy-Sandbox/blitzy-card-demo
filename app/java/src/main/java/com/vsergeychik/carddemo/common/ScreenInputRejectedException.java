package com.vsergeychik.carddemo.common;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * An inbound payload value a {@code RECEIVE MAP} could never have delivered, refused at the screen
 * boundary so that it is answered as the caller's error rather than as the transaction abending.
 *
 * <h2>Why this type exists</h2>
 * Two of the seventeen online programs - {@code COACTUPC} and {@code COCRDUPC} - open with
 * {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} ({@code app/cbl/COACTUPC.cbl:862-864},
 * {@code app/cbl/COCRDUPC.cbl:1531}), so their Java form routes every unhandled failure to
 * {@code ABEND-ROUTINE} and answers {@code 500} with the {@code ABEND-DATA} the source transmits.
 * That is the faithful translation of an abend - and it is the wrong answer for a value the
 * <em>caller</em> supplied.
 *
 * <p>The cases below are exactly that: states the 3270 conversation cannot be in, which only a
 * hand-built JSON payload can reach. On a terminal, a {@code RECEIVE MAP} delivers bytes already in
 * the terminal's code page, so neither case has a COBOL behaviour to be faithful to. Refusing them at
 * the boundary keeps the abend path for what it is for - a unit of work that genuinely did not
 * complete - and gives the caller the same controlled {@code 400} that {@code COUSR01C} and
 * {@code COTRN02C}, which declare no {@code HANDLE ABEND}, already produce for the identical input.
 *
 * <p><strong>What is NOT refused here is as much part of the contract as what is.</strong> A screen key
 * that names a different record from the URI, and a communication area whose fetched key is blank, were
 * both refused by this type and are not any longer: a 3270 screen has one key field and no URI, so
 * there is no disagreement for the source to have an answer to, and a blank fetched key is answered by
 * the source's own {@code HANDLE ABEND} path rather than by a new class of HTTP error. Each route now
 * treats the URI as what seeds a first entry, and lets the program read its own map on a re-entry.
 *
 * <h2>The producers</h2>
 * <ul>
 *   <li><strong>A control character the terminal cannot transmit.</strong> A 3270 sends the modified
 *       fields of a screen as graphic characters; it has no way to place a TAB, a line feed, a DEL or an
 *       embedded NUL inside a {@code PIC X} field. NUL matters most of the three: BMS delivers an
 *       <em>unmodified</em> field as all-nulls, this module renders an unpainted field the same way, and
 *       Read Modified suppresses nulls - so a trailing run of them is genuine padding while one sitting
 *       between data bytes is not something any conversation produced. Left unjudged, a control byte
 *       enters a parity-critical record and a stored NUL becomes indistinguishable from an unpainted
 *       field. See {@link #requireDeliverable(String, String)}.</li>
 *   <li><strong>A character the code page cannot represent.</strong> Every screen field is
 *       {@code PIC X(n)}, which is n <em>bytes</em> in a single-byte code page - IBM037 for the EBCDIC
 *       datasets, US-ASCII for the fixtures. An accented Latin letter, a CJK ideograph or an emoji has
 *       no representation there, so {@code FixedWidthRecord.Transcoder} refuses it rather than letting
 *       {@code String.getBytes} substitute {@code '?'} and write a value into a dataset that no COBOL
 *       program could have produced. This is judged at the JSON boundary, for every string of every
 *       body of all seventeen routes, by {@code config.WebConfig.ScreenTextDeserializer} against the <em>active</em>
 *       code page the deployment named - not per route, and not inside a program's flow, where it would
 *       run ahead of the program's own decision about whether it receives a map at all.
 *       See {@link #unrepresentable(String, String, Charset, int)}.</li>
 * </ul>
 *
 * <h2>Why it extends {@link IllegalArgumentException}</h2>
 * Because that is already this module's boundary contract: {@code config/WebConfig}'s
 * {@code CobolErrorHandler} maps an {@link IllegalArgumentException} - the width and shape guards in
 * the record models, the request DTOs and {@code FixedWidthCodec} - onto {@code 400 Bad Request} with
 * a fixed, value-free explanation. Extending it means this refusal takes that same documented route on
 * every one of the seventeen screens rather than introducing a second error contract, and the two
 * controllers that wrap their flow in an abend handler simply let this subtype through.
 *
 * <h2>What it carries, and what it deliberately does not</h2>
 * It carries the <strong>name</strong> of the payload member at fault, so the answer names the member
 * of a 54-field screen the caller has to correct. It never carries the <strong>value</strong>: these
 * spans hold cardholder names, government identifiers and card numbers, so echoing one would put it in
 * a response body, in an exception message and in any log line that rendered either (CWE-532), and a
 * control character inside it could forge a second log entry (CWE-117). The message states the shape of
 * the fault, the code page or the item involved, and the width - never the content (practice
 * <strong>B6</strong>).
 *
 * <h2>Two audiences, two texts</h2>
 * <strong>What this type tells the caller and what it tells the server are deliberately different
 * texts, and only one of them is published.</strong>
 *
 * <ul>
 *   <li>{@link #publicDetail()} is what reaches the response body. It is a <em>fixed</em> sentence per
 *       {@link Reason} - composed here and nowhere else - naming the member and nothing else. No code
 *       page, no symbolic-map item, no {@code PIC X(n)}, no Unicode code point, no width, no bound and
 *       no 3270 or {@code RECEIVE MAP} mechanics.</li>
 *   <li>{@link #getMessage()} is the server-side diagnostic. It carries exactly those internal facts,
 *       because they are what an engineer holding the copybook open needs, and it reaches the server
 *       log and nothing else.</li>
 * </ul>
 *
 * <p>The split exists because the two audiences are not the same. The caller of an unauthenticated
 * endpoint needs to know <em>which member</em> to correct and <em>what kind</em> of thing is wrong with
 * it; the code page this deployment decodes datasets in, the copybook item a member projects and the
 * mechanics of a 3270 field are facts about the <em>inside</em> of this system, and publishing them one
 * rejected field at a time hands an attacker a map of it. Neither text ever carries the value.
 *
 * @see #publicDetail() for the text the caller is answered with
 * @see Reason for the fixed public sentence of each refusal
 *
 * @see com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler for the 400 this is answered with
 * @see AbendException for the failure family this type exists to stay out of
 */
public final class ScreenInputRejectedException extends IllegalArgumentException {

    /** Serialisation identity, fixed rather than generated, so it is stable across builds. */
    private static final long serialVersionUID = 1L;

    /**
     * COBOL's {@code LOW-VALUES}: the character an unpainted screen field is filled with.
     *
     * <p>Named rather than written as a literal because it appears in the control-character rule as an
     * exemption rather than as one more control character, and the distinction is the whole of that
     * rule.
     */
    private static final char LOW_VALUE = '\u0000';

    /**
     * What kind of thing is wrong with the request, and the <strong>fixed</strong> sentence the caller
     * is answered with for it.
     *
     * <p>One constant per refusal shape, and the sentence is a constant too: it is written here, it
     * takes no argument but the member name, and no factory can widen it by wording its own diagnostic
     * differently. That is the property that makes the published text safe to keep publishing as this
     * class grows - the risk a fixed, argument-free response exists to close.
     *
     * <p>Each sentence answers the only two questions a caller can act on: which member is at fault,
     * and what class of thing is wrong with it. None of them names a code page, a copybook item, a
     * {@code PICTURE} clause, a Unicode code point, a width, a bound, or anything about 3270 terminals -
     * those live in {@link ScreenInputRejectedException#getMessage()}, which reaches the server log
     * only.
     */
    public enum Reason {

        /**
         * A character the screen cannot carry - a control character no terminal transmits, or one the
         * configured code page has no representation for. The two are one answer to a caller, because
         * the correction is the same: send characters the screen can carry.
         */
        UNSUPPORTED_CHARACTER("contains a character this screen field cannot carry"),

        /** The member arrived as something other than JSON character data - a number, a boolean, an
         * object or an array where a screen field belongs. */
        NOT_CHARACTER_DATA("must be sent as a JSON string, because it is a screen field"),

        /** More characters than the screen field accepts. Refused rather than truncated, because
         * keeping the leading characters could silently address a different record. */
        TOO_WIDE("is longer than this screen field accepts"),

        /** A stated value outside the range the item it stands for can hold. */
        OUTSIDE_RANGE("is outside the range this request value accepts"),

        /** Two accepted spellings of one request value state different things. */
        CONTRADICTORY_SPELLINGS("contradicts another spelling of the same request value");

        /** The fault, worded for the caller, with the member name supplied at the call site. */
        private final String publicPredicate;

        /**
         * @param publicPredicate the fixed predicate, read after the member name
         */
        Reason(final String publicPredicate) {
            this.publicPredicate = publicPredicate;
        }

        /**
         * The sentence published for this reason, naming the member the caller sent and nothing else.
         *
         * @param member the payload member, path variable or parameter at fault, spelled as the caller
         *               sent it, or {@code null} when the refusal names none
         * @return the public detail; never {@code null}
         */
        String publicDetail(final String member) {
            return "The value supplied for " + (member == null ? "this request" : member) + " "
                    + publicPredicate + ". Correct it and send the request again. The rejected value "
                    + "is not echoed here.";
        }
    }

    /**
     * The payload member at fault, or {@code null} when the refusal names no single member.
     *
     * <p>{@code transient} is deliberate even though {@link String} is serialisable: an exception that
     * crosses a serialisation boundary should carry no more of the request than its own message does,
     * and this field is a request detail.
     */
    private final transient String member;

    /**
     * What kind of thing is wrong, which is what selects the fixed sentence the caller is answered
     * with. Never {@code null}.
     */
    private final Reason reason;

    /**
     * Builds a refusal naming the payload member at fault.
     *
     * @param reason  what kind of thing is wrong; must not be {@code null}
     * @param member  the member's name as the caller sent it, or {@code null} when none is named
     * @param message the <em>diagnostic</em>: what is wrong, stating the shape of the fault, the item,
     *                the code page or the width - and never the value. Reaches the server log, never a
     *                response body
     */
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
     * The text this refusal is <strong>published</strong> as: a fixed sentence for its
     * {@link #reason()}, naming the member the caller sent and disclosing nothing else.
     *
     * <p>This is what {@code WebConfig.CobolErrorHandler} puts in the response body, in place of
     * {@link #getMessage()}. The distinction is the whole point of the pair: the diagnostic names the
     * code page, the symbolic-map item, the {@code PICTURE} width and the Unicode code point, all of
     * which describe the inside of this system, and a caller of an unauthenticated endpoint has no
     * business being handed them one rejected field at a time.
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
     * <p>The offending character is reported by its Unicode code point rather than by rendering it,
     * because rendering it would put a fragment of the caller's value in the message - and a code point
     * is what a caller needs to find it in their own input anyway.
     *
     * @param member    the payload member's name, as the caller sent it; must not be {@code null}
     * @param itemName  the symbolic-map item the member projects, for example {@code ACSFNAMI}; must
     *                  not be {@code null}
     * @param charset   the code page that cannot represent the character; must not be {@code null}
     * @param codePoint the Unicode code point of the first character that has no representation
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member}, {@code itemName} or {@code charset} is
     *                             {@code null}
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
     * @param member    the payload member's name, as the caller sent it; must not be {@code null}
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
     * <p>The rule in one sentence: <strong>a control character is refused unless it is {@code U+0000}
     * in a trailing run.</strong> {@link Character#isISOControl(char)} is the test, so it covers C0
     * ({@code U+0000}-{@code U+001F}), {@code DEL} ({@code U+007F}) and C1
     * ({@code U+0080}-{@code U+009F}) together.
     *
     * <p>The exemption is not a convenience, it is the one shape a real conversation produces. BMS
     * delivers an unmodified field as all-nulls, this module renders an unpainted field as
     * {@code LOW-VALUES} at the declared width for exactly that reason, and a client that echoes a
     * response it was given therefore sends nulls back - so an all-null value, and a value whose data
     * is followed by nulls, must both be accepted or the API's own responses would stop being legal
     * requests. A null <em>between</em> data bytes is a different thing: Read Modified suppresses
     * nulls, so no terminal can produce one, and accepting it puts a byte into a parity-critical record
     * that no COBOL program could have written.
     *
     * <p>A {@code null} value is skipped rather than refused: an absent field is spaces on a terminal
     * and carries no character to judge. An empty value is likewise nothing to judge.
     *
     * @param member the payload member's name, as the caller sent it; must not be {@code null}
     * @param value  the value as received; may be {@code null}
     * @throws NullPointerException         if {@code member} is {@code null}
     * @throws ScreenInputRejectedException if the value carries a control character that is not
     *                                      {@code U+0000} in a trailing run
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

    /**
     * Whether every character from {@code from} to the end of {@code value} is {@code U+0000}.
     *
     * @param value the value being judged; must not be {@code null}
     * @param from  the index of the first {@code U+0000} found
     * @return {@code true} when the remainder is nothing but {@code U+0000}
     */
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
     * <p>Called at the map-receive boundary of the two programs that declare {@code EXEC CICS HANDLE
     * ABEND}, before their flow begins, so that such a value is answered as the caller's error instead
     * of reaching {@code ABEND-ROUTINE}. Sweeping the whole map in declaration order - rather than
     * checking each field where it is used - means the refusal happens before any read, any edit and
     * any write, so a rejected payload cannot have left a partial change behind.
     *
     * <p>The map argument is the request DTO's {@code fieldValues()} projection: keyed by
     * {@code DFHMDF} label, in declaration order, one entry per screen field. The symbolic-map item is
     * derived as the label with {@code I} appended, which is how BMS names the input item of every
     * field in all seventeen mapsets - verified against all 54 items of {@code app/cpy-bms/COACTUP.CPY}
     * and all 17 of {@code app/cpy-bms/COCRDUP.CPY}.
     *
     * <p>The <em>member</em> reported to the caller is the label lowercased, because that is the JSON
     * name the caller sent: this module publishes one lowercase {@code xxxI}-derived naming convention,
     * so {@code CRDNAME} on the wire is {@code crdname}. Reporting the uppercase COBOL label handed the
     * caller a third vocabulary alongside the JSON member and the Java property, and a name a client
     * never sent cannot be mapped back to anything in its own request. The <em>item</em> in the message
     * stays uppercase, because that is what the copybook calls it and it is documentation rather than
     * something the caller has to match.
     *
     * <p>A {@code null} value is skipped rather than refused: an absent field is spaces on a terminal,
     * which the flow itself handles, and it carries no character to judge.
     *
     * @param fieldValues the received map's values, keyed by {@code DFHMDF} label; must not be
     *                    {@code null}
     * @param codec       the codec carrying the screen code page; must not be {@code null}
     * @throws NullPointerException          if either argument is {@code null}
     * @throws ScreenInputRejectedException  if any value carries a character the code page cannot
     *                                       represent
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
     * <p>The single-value form of {@link #requireRepresentable(Map, FixedWidthCodec)}, and the form the
     * JSON boundary uses: {@code config.WebConfig.ScreenTextDeserializer} meets one string at a time, has the
     * member name the caller spelled, and has no map of the whole screen to sweep. Judging there is what
     * makes the rule reach <em>every</em> request family rather than the three that sweep their received
     * map - a member left off any one route's list would be a silent hole, and fourteen of the seventeen
     * screens were exactly that hole.
     *
     * <p>The rule itself is unchanged: every screen field is {@code PIC X(n)}, which is n <em>bytes</em>
     * in a single-byte code page, so a character with no representation there is a value no
     * {@code RECEIVE MAP} could have delivered. Refusing it here rather than at the record write is what
     * lets the answer name a member: by the time the value reaches a dataset it is one span of a
     * fixed-width image and the member it came from is no longer known.
     *
     * <p>A {@code null} value is skipped rather than refused: an absent field is spaces on a terminal,
     * and it carries no character to judge.
     *
     * @param member   the payload member's name, as the caller sent it; must not be {@code null}
     * @param itemName the item the member projects, for example {@code ACSFNAMI}, for the server-side
     *                 diagnostic only; must not be {@code null}
     * @param value    the value as received; may be {@code null}
     * @param codec    the codec carrying the screen code page; must not be {@code null}
     * @throws NullPointerException         if {@code member}, {@code itemName} or {@code codec} is
     *                                     {@code null}
     * @throws ScreenInputRejectedException if the value carries a character the code page cannot
     *                                     represent
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
     * <p>Every payload member of all seventeen screens projects a {@code PIC X(n)} item, so a JSON
     * number, boolean, object or array where a screen field belongs is not a screen field with an
     * unusual value - it is not a screen field at all. Coercing it would fabricate a character image no
     * terminal sent: {@code 11} for a {@code PIC X(11)} account filter drops the nine leading zeros the
     * screen actually carries, and {@code true} is a five-character word the operator never typed.
     *
     * <p>The token shape is named in the diagnostic and not in the published answer: what the caller
     * needs to be told is that the member must be sent as a string, which {@link Reason} states.
     *
     * @param member     the payload member's name, as the caller sent it; must not be {@code null}
     * @param tokenShape what arrived instead, for example {@code VALUE_NUMBER_INT}; must not be
     *                   {@code null}
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

    /**
     * The JSON member a {@code DFHMDF} label projects onto: the label lowercased.
     *
     * <p>One rule for all 441 fields of all seventeen mapsets. The labels are ASCII, so the fold is
     * pinned to {@link Locale#ROOT} rather than the default locale - a Turkish default would fold
     * {@code I} to a dotless {@code i} and name a member no client ever sent.
     *
     * @param label the {@code DFHMDF} label; must not be {@code null}
     * @return the JSON member name
     */
    private static String jsonMemberOfLabel(final String label) {
        return label.toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses a value that is wider than the item it has to be carried in.
     *
     * <p>A 3270 field physically cannot accept more characters than it declares, so a wider value is
     * another state no {@code RECEIVE MAP} could have delivered. It is refused rather than truncated
     * because keeping the leading characters would silently address a different record.
     *
     * <p>The widths are reported and the value is not: a width is a published fact about the screen -
     * the symbolic map declares it - while the value may be a card number or a government identifier.
     *
     * @param member   the payload member, path variable or parameter at fault, spelled as the caller
     *                 sent it; must not be {@code null}
     * @param itemName the item it is carried in, for example {@code ACCTSIDI PIC X(11)}; must not be
     *                 {@code null}
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
     * <p>{@code eibaid} and {@code eibAid} are two accepted spellings of the same parameter, and one
     * terminal interaction has one {@code EIBAID}, so the two cannot disagree. Neither stated value is
     * echoed; what is reported is that they differ.
     *
     * @param member the canonical spelling, which is the one to send; must not be {@code null}
     * @param alias  the accepted alternate spelling; must not be {@code null}
     * @param what   what the two spell, for example {@code "one EIBAID"}; must not be {@code null}
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
     * <p>A screen's key can arrive two ways: as the raw {@code EIBAID} byte in a query parameter, and as
     * the {@code CCARD-AID} token a previous screen's response echoed into the payload. One terminal
     * interaction presents one key, so the two cannot name different ones. Believing either would send
     * the request down a branch the caller did not unambiguously ask for - and because the byte is the
     * lossless statement and the token is a folded projection of it, the disagreement is exactly the
     * case where guessing changes behaviour.
     *
     * <p>Neither stated key is echoed, for the reason the class documents.
     *
     * @param member        the payload member carrying the token, as the caller sent it; must not be
     *                      {@code null}
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
     * <p>Used for {@code EIBAID}, which is one byte: a stated value outside {@code 0}-{@code 255} is not
     * an attention identifier at all, and narrowing it would select a key the terminal never presented.
     *
     * <p>The bounds are reported and the value is not, for the same reason a width is.
     *
     * @param member the parameter or member at fault, spelled as the caller sent it; must not be
     *               {@code null}
     * @param what   what the item is, for example {@code "one EIBAID byte"}; must not be {@code null}
     * @param low    the lowest acceptable value
     * @param high   the highest acceptable value
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
}
