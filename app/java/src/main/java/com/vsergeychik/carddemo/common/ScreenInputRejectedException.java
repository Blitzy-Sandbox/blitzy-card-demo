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
 * <p>The two cases below are exactly that: states the 3270 conversation cannot be in, which only a
 * hand-built JSON payload can reach. On a terminal, a {@code RECEIVE MAP} delivers bytes already in
 * the terminal's code page and a communication area is only ever the one the program itself wrote, so
 * neither case has a COBOL behaviour to be faithful to. Refusing them at the boundary keeps the abend
 * path for what it is for - a unit of work that genuinely did not complete - and gives the caller the
 * same controlled {@code 400} that {@code COUSR01C} and {@code COTRN02C}, which declare no
 * {@code HANDLE ABEND}, already produce for the identical input.
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
 *       program could have produced. See {@link #unrepresentable(String, String, Charset, int)}.</li>
 *   <li><strong>A communication area that contradicts itself.</strong> {@code COCRDUPC:671-672} moves
 *       {@code CCUP-OLD-ACCTID PIC X(11)} into {@code CDEMO-ACCT-ID PIC 9(11)} on the turn that
 *       processes a fetched card. The only writer of {@code CCUP-OLD-ACCTID} is the program itself, at
 *       {@code :1006-1007}, and it writes the eleven digits it read - so on any real conversation the
 *       receiving numeric item gets digits. A payload that asks for the processing action while leaving
 *       those keys blank describes a screen that was never fetched, and the numeric item it feeds
 *       cannot hold blanks at all. See {@link #inconsistentCommarea(String, String)}.</li>
 *   <li><strong>Two different values for one key.</strong> A keyed route states its record's key twice -
 *       once in the URI and once in the screen field the URI binds - and on a terminal there is only
 *       ever one. A payload that types {@code 00000000002} into the account filter while asking
 *       {@code /api/accounts/00000000001} describes a screen that cannot exist, and the alternative to
 *       refusing it is discarding one of the two values with no message, which is silent loss of the
 *       operator's own input. This is a refusal of the REST transport, raised before the flow begins,
 *       not an invented COBOL condition: the legacy screen has no URI to disagree with, and a client
 *       that echoes a painted screen agrees with the path and never reaches it. See
 *       {@link #requireKeyAgreement(String, String, String, int, FixedWidthCodec)}.</li>
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
     * The payload member at fault, or {@code null} when the refusal names no single member.
     *
     * <p>{@code transient} is deliberate even though {@link String} is serialisable: an exception that
     * crosses a serialisation boundary should carry no more of the request than its own message does,
     * and this field is a request detail.
     */
    private final transient String member;

    /**
     * Builds a refusal naming the payload member at fault.
     *
     * @param member  the member's name as the caller sent it, or {@code null} when none is named
     * @param message what is wrong, stating the shape of the fault and never the value
     */
    private ScreenInputRejectedException(final String member, final String message) {
        super(message);
        this.member = member;
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
        return new ScreenInputRejectedException(member, "The value supplied for " + member
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
        return new ScreenInputRejectedException(member, "The value supplied for " + member
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
            String value = field.getValue();
            if (value == null) {
                continue;
            }
            OptionalInt offending = codec.firstUnrepresentableCodePoint(value);
            if (offending.isPresent()) {
                throw unrepresentable(jsonMemberOfLabel(field.getKey()), field.getKey() + "I",
                        codec.charset(), offending.getAsInt());
            }
        }
    }

    /**
     * Refuses a communication-area member that does not carry what the program itself would have
     * written there.
     *
     * @param member   the payload member's name, as the caller sent it; must not be {@code null}
     * @param expected what the program writes there, stated as a shape and never as a value - for
     *                 example {@code "the eleven digits of the fetched account identifier"}; must not
     *                 be {@code null}
     * @return the refusal, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ScreenInputRejectedException inconsistentCommarea(final String member,
            final String expected) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(expected, "A statement of what the program writes there is required");
        return new ScreenInputRejectedException(member, "The communication area supplied for " + member
                + " does not carry " + expected + ", so it describes a conversation this program "
                + "cannot be in: the only writer of that member is this program itself, and the "
                + "requested action processes the record it fetched. Fetch the record first - send the "
                + "screen with no change action - and send back the communication area the reply "
                + "carries. The rejected value is not echoed here.");
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
        return new ScreenInputRejectedException(member, "The value supplied for " + member + " is "
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
        return new ScreenInputRejectedException(member, "The " + member + " and " + alias
                + " parameters are two spellings of the same value and state different things. One "
                + "terminal interaction has " + what + ", so the two cannot disagree. Send one of them, "
                + "or send both with the same value. Neither stated value is echoed here.");
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
        return new ScreenInputRejectedException(member, "The value supplied for " + member + " is "
                + "outside " + low + " to " + high + ", which is what " + what + " can hold, so no "
                + "terminal could have presented it. Narrowing it silently would select a value the "
                + "caller never sent. The rejected value is not echoed here.");
    }

    /**
     * Refuses a payload whose screen key member names a different record from the URI.
     *
     * <p>Neither value is echoed, for the reason the class documents: these key spans hold account
     * identifiers, card numbers and user identifiers.
     *
     * @param member the payload member's name, as the caller sent it; must not be {@code null}
     * @param width  the member's declared {@code PICTURE} width
     * @return the refusal, never {@code null}
     * @throws NullPointerException if {@code member} is {@code null}
     */
    public static ScreenInputRejectedException conflictingKey(final String member, final int width) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        return new ScreenInputRejectedException(member, "The URI names one record and the payload's "
                + member + " names another, so the request states its key twice and disagrees with "
                + "itself. A 3270 screen has one key field and no URI, so there is no COBOL behaviour "
                + "to be faithful to here, and honouring one value would silently discard the other. "
                + "Send " + member + " as the URI's key at its declared PIC X(" + width + ") width, or "
                + "leave it blank or LOW-VALUES and let the URI state the key alone. Neither rejected "
                + "value is echoed here.");
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
        if (isEvery(supplied, ' ') || isEvery(supplied, '\u0000')) {
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
