package com.vsergeychik.carddemo.common;

import java.nio.charset.Charset;
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
 * <h2>The two producers</h2>
 * <ul>
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
                throw unrepresentable(field.getKey(), field.getKey() + "I", codec.charset(),
                        offending.getAsInt());
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
}
