package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * The one place this module decides what a diagnostic rendering is allowed to disclose.
 *
 * <h2>Why this exists</h2>
 * Every record, DTO and repository in this module can end up in a log line, an exception message, an
 * assertion failure or a debugger view. The data they carry is cardholder data: primary account numbers,
 * card verification values, social security and government-issued identifiers, electronic funds transfer
 * accounts, plaintext passwords, names, addresses and telephone numbers. A generated {@code toString} on
 * a Java {@code record} renders every component, so a type that carries a PAN discloses it the first time
 * anything renders it - and nothing in the COBOL requires that, because COBOL has no {@code toString}.
 * Disclosure here is purely an artefact of the target language.
 *
 * <p>Before this class there were three different redaction markers in three files - {@code [REDACTED]},
 * {@code ********} and {@code [masked]} - which is what a policy spread across thirty types looks like
 * after a while. One authority prevents that drift, and it means a reviewer can read the policy once
 * rather than auditing each type.
 *
 * <h2>The policy</h2>
 * Four categories, and every sensitive field in the module falls into exactly one:
 * <ol>
 *   <li><strong>Never disclosed at all</strong> - card verification value, social security number,
 *       government-issued identifier, electronic funds transfer account, password. Rendered as
 *       {@value #REDACTED}, with no length and no partial value, because for these fields even a partial
 *       value or a length is worth withholding. Use {@link #redacted()}.</li>
 *   <li><strong>Primary account number</strong> - masked to its last {@value #REVEALED_TRAILING_DIGITS}
 *       digits at full stored width. Use {@link #maskPan(String)} or {@link #maskPan(long, int)}.</li>
 *   <li><strong>Identifiers</strong> - account, customer, card-cross-reference and <em>transaction</em>
 *       keys. Masked the same way, because an identifier is what makes every other value attributable to
 *       a person. Use {@link #maskIdentifier(String)} or {@link #maskIdentifier(long, int)}.</li>
 *   <li><strong>Free-text personal data</strong> - names, address lines, telephone numbers. The content
 *       is dropped entirely and only its shape is reported, because a name has no useful prefix to
 *       reveal. Use {@link #describeText(String)}.</li>
 * </ol>
 *
 * <p>Everything else is rendered plainly, and that is a deliberate decision rather than an oversight.
 * Program and transaction names, mapset and map names, screen titles, dates, times, return codes,
 * file-status values, attention identifiers and monetary amounts all stay legible. They are exactly what
 * a parity failure has to be diagnosed from - this migration's entire purpose is proving that amounts and
 * status codes match the COBOL byte for byte - and with every identifier masked they are no longer
 * attributable to a cardholder.
 *
 * <p>The <strong>transaction</strong> identifier used to be on that list, and this checkpoint moved it.
 * The reasoning that keeps an amount legible is that the keys which attribute it to a person are masked,
 * and {@code TRAN-ID} is one of those keys: it is the {@code TRANSACT} primary key, and
 * {@code app/cpy/CVTRA05Y.cpy} puts {@code TRAN-CARD-NUM} in the very record it opens. A screen that
 * shows no account and no card number still shows a joinable key beside a date and an amount, which
 * {@code COTRN00}'s ten rows do ten times over - one cardholder's financial history in a build log.
 * Diagnosability survives the change intact: {@link #maskIdentifier(String)} keeps the stored width and
 * the last {@value #REVEALED_TRAILING_DIGITS} characters, and this system's transaction identifiers are
 * zero-filled sequentials, so those four characters are the only part that ever differs between two of
 * them.
 *
 * <h2>What this class is NOT for</h2>
 * It is not a general-purpose sanitiser and it must never become the way parity tests read values. The
 * record types already publish explicit, named, caller-invoked accessors for that -
 * {@code fieldImages(Charset)}, {@code groupImage(Charset)} and the per-span readers - and those return
 * real bytes because a caller asked for them by name. The distinction that matters is between a value a
 * caller deliberately requested and a value that leaks because something rendered an object.
 *
 * <h2>Design constraints observed</h2>
 * Pure JDK, no Spring import and no framework annotation, because everything in {@code common/} is
 * reachable from both the web and batch sides and from the copybook model types. Stateless, with no
 * mutable static field (practice B9, gate G53), so it is safe to call from any thread. Not
 * instantiable. Null-tolerant throughout: a diagnostic rendering that threw while being built would turn
 * a log line into an outage, so every method accepts {@code null} and reports it as {@code "null"} -
 * which discloses nothing and preserves the distinction between an absent field and a blank one.
 */
public final class SensitiveDiagnostics {

    /**
     * The single marker for a value that is never disclosed in any form.
     *
     * <p>Lower case and bracketed so it cannot be mistaken for stored data: no {@code PIC X} field in
     * any of the twenty-eight copybooks can hold a bracket in a position that would produce this text.
     */
    public static final String REDACTED = "[redacted]";

    /** The rendering of an absent value - it discloses nothing and is not the same as a blank one. */
    public static final String ABSENT = "null";

    /** The character every masked digit is replaced with. */
    public static final char MASK_CHARACTER = '*';

    /**
     * How many trailing characters a masked identifier or account number keeps: four.
     *
     * <p>Four is the long-established convention for the last group of a payment card number, and it is
     * few enough that the value cannot be reconstructed while being enough to correlate two log lines
     * about the same record - which is the only reason to reveal any of it.
     */
    public static final int REVEALED_TRAILING_DIGITS = 4;

    /** Prefix of {@link #describeText(String)}'s rendering. */
    private static final String TEXT_PREFIX = "[text len=";

    /** Suffix of {@link #describeText(String)}'s rendering. */
    private static final String TEXT_SUFFIX = "]";

    /** Rendering for text that is present but entirely spaces - a blank fixed-width field. */
    private static final String BLANK_TEXT = "[blank]";

    /** Rendering for text that is present and empty. */
    private static final String EMPTY_TEXT = "[text len=0]";

    /**
     * How much of one field a diagnostic may disclose.
     *
     * <p>Exists for the screen DTOs, which render their fields in a loop over a map or an enum rather
     * than as a hand-written concatenation. A loop cannot choose a treatment per field, so the DTO
     * classifies each of its own field names and the loop asks {@link #render(Disclosure, String)} for
     * the rendering. Keeping the classification in the DTO is deliberate: a symbolic map is a closed,
     * compile-time set of names taken straight from {@code app/cpy-bms/}, so the DTO can enumerate its
     * sensitive fields exactly, whereas a name-pattern heuristic living here would silently fail to
     * match the next field somebody adds.
     */
    public enum Disclosure {

        /** No personal data: rendered as stored. Titles, dates, status codes, amounts, message text. */
        PLAIN,

        /** An account, customer or cross-reference key: masked to its last four characters. */
        IDENTIFIER,

        /** A primary account number: masked to its last four digits. */
        PAN,

        /** Free-text personal data - a name, address line or telephone number: length only. */
        TEXT,

        /** A credential - CVV, SSN, government id, EFT account, password: withheld entirely. */
        REDACTED_VALUE
    }

    /**
     * Renders one field according to its classification.
     *
     * @param disclosure how much of the field may be disclosed; {@code null} is treated as
     *                   {@link Disclosure#REDACTED_VALUE}, so a field nobody classified is withheld
     *                   rather than published
     * @param value      the stored value, or {@code null}
     * @return the rendering, never {@code null}
     */
    public static String render(Disclosure disclosure, String value) {
        if (disclosure == null) {
            return REDACTED;
        }
        return switch (disclosure) {
            case PLAIN -> plain(value);
            case IDENTIFIER -> maskIdentifier(value);
            case PAN -> maskPan(value);
            case TEXT -> describeText(value);
            case REDACTED_VALUE -> REDACTED;
        };
    }

    /**
     * The marker for a value withheld unconditionally.
     *
     * @return {@value #REDACTED}, never {@code null}
     */
    public static String redacted() {
        return REDACTED;
    }

    /**
     * Masks a primary account number, keeping its last {@value #REVEALED_TRAILING_DIGITS} digits and its
     * full stored width.
     *
     * <p>Width is preserved because a {@code PIC 9(16)} card number is stored as sixteen bytes and a
     * rendering of a different length would misreport the record's shape - which is the one thing a
     * fixed-width diagnostic still needs to be right about.
     *
     * <p>The four revealed characters are control-character escaped, so a card key that holds CR or LF -
     * which {@code CARD-NUM PIC X(16)} permits and no repository here rejects - cannot forge a second log
     * line out of the part of itself the mask leaves visible (CWE-117).
     *
     * @param pan the stored card number image, or {@code null}
     * @return the masked rendering, {@value #ABSENT} when {@code pan} is {@code null}, never
     *         {@code null} itself
     */
    public static String maskPan(String pan) {
        return maskTrailing(pan);
    }

    /**
     * Masks a primary account number held as a number, at the declared width of its field.
     *
     * @param pan   the card number
     * @param width the field's declared digit count, so the rendering keeps the stored width; a
     *              non-positive width is treated as the number's own digit count
     * @return the masked rendering, never {@code null}
     */
    public static String maskPan(long pan, int width) {
        return maskTrailing(zoned(pan, width));
    }

    /**
     * Masks an identifier - an account, customer or cross-reference key - keeping its last
     * {@value #REVEALED_TRAILING_DIGITS} characters.
     *
     * <p>The same treatment as a card number, deliberately. An account identifier is not a payment
     * credential, but it is what links a masked record to a person, so disclosing it in full would
     * undo the rest of this policy. The revealed tail is control-character escaped for the same reason it
     * is on a card number, since an identifier read out of a {@code PIC X} span can hold anything.
     *
     * @param identifier the stored identifier image, or {@code null}
     * @return the masked rendering, {@value #ABSENT} when {@code identifier} is {@code null}
     */
    public static String maskIdentifier(String identifier) {
        return maskTrailing(identifier);
    }

    /**
     * Masks a numeric identifier at the declared width of its field.
     *
     * @param identifier the identifier
     * @param width      the field's declared digit count; a non-positive width is treated as the
     *                   number's own digit count
     * @return the masked rendering, never {@code null}
     */
    public static String maskIdentifier(long identifier, int width) {
        return maskTrailing(zoned(identifier, width));
    }

    /**
     * Reports the shape of free-text personal data without any of its content.
     *
     * <p>Used for names, address lines and telephone numbers. Unlike an identifier these have no
     * useful trailing group to reveal - the last four characters of a surname are still part of the
     * surname - so nothing is revealed and only the length is reported. The length alone is worth
     * reporting: a fixed-width field that is the wrong length is a real defect, and it is the kind this
     * module was reviewed for.
     *
     * @param value the stored text, or {@code null}
     * @return {@value #ABSENT} when {@code value} is {@code null}, {@value #BLANK_TEXT} when it is
     *         present but entirely spaces, otherwise its length and nothing else
     */
    public static String describeText(String value) {
        if (value == null) {
            return ABSENT;
        }
        if (value.isEmpty()) {
            return EMPTY_TEXT;
        }
        if (value.isBlank()) {
            return BLANK_TEXT;
        }
        return TEXT_PREFIX + value.length() + TEXT_SUFFIX;
    }

    /**
     * Renders a value that carries no personal data, for symmetry at a call site that masks its
     * neighbours.
     *
     * <p>Present so that a {@code toString} reads as one uniform list rather than mixing bare
     * concatenation with policy calls, which is what lets a reviewer see at a glance that every field
     * was considered.
     *
     * <p>The rendering is confined to one line: control characters are escaped as COBOL hex literals
     * by {@link DiagnosticText#singleLine(String)}, because a value that reaches a log line unescaped
     * can carry CR or LF and forge a second entry (CWE-117). Nothing else about the value changes, so
     * a field this method renders is still shown in full.
     *
     * @param value any value, or {@code null}
     * @return the value's own single-line rendering, or {@value #ABSENT}
     */
    public static String plain(Object value) {
        return value == null ? ABSENT : DiagnosticText.singleLine(String.valueOf(value));
    }

    /**
     * Masks everything but the final {@value #REVEALED_TRAILING_DIGITS} characters.
     *
     * <p>A value at or below the revealed length is masked <strong>entirely</strong> rather than
     * disclosed in full: revealing four of four characters would disclose the whole field, which is the
     * opposite of the intent, and a short value here means the field was not what the copybook declares.
     *
     * <p>The surviving characters are escaped by {@link DiagnosticText#singleLine(String)} before they
     * are appended, and that is not decoration. {@code CARD-NUM} is {@code PIC X(16)} and
     * {@code XREF-CARD-NUM} is {@code PIC X(16)}: alphanumeric pictures, which hold whatever bytes the
     * dataset holds, and neither the repositories nor these record types validate them as digits -
     * deliberately, because rejecting a stored card key would be a behaviour change the COBOL never
     * makes. A carriage return or line feed sitting in the last four bytes therefore reaches this method,
     * and appending it raw would let the stored value append a log line of its own: a forged entry that a
     * reader cannot distinguish from a real one (CWE-117). Masking removes control characters from every
     * position it covers, but the revealed tail is by definition not covered, so the escape is what makes
     * the guarantee hold for the whole rendering rather than for most of it.
     *
     * <p>The mask itself is unchanged in width: exactly {@code length - }{@value
     * #REVEALED_TRAILING_DIGITS} mask characters, so the rendering still reports the field's stored
     * width, which is the one shape fact a fixed-width diagnostic has to keep. An escaped control
     * character does lengthen the visible tail - a line feed becomes the five characters {@code X'0A'} -
     * and that is the correct trade: the escape is lossless, so the diagnostic still says exactly which
     * byte was there, and it is unambiguous, because {@code X'0A'} contains no control character to
     * escape in turn. {@link DiagnosticText#masked(String)} makes the same trade, and these two must not
     * disagree about it - one policy rendered two ways is how a reviewer ends up auditing each call site.
     */
    private static String maskTrailing(String value) {
        if (value == null) {
            return ABSENT;
        }
        int length = value.length();
        if (length == 0) {
            return EMPTY_TEXT;
        }
        if (length <= REVEALED_TRAILING_DIGITS) {
            return String.valueOf(MASK_CHARACTER).repeat(length);
        }
        return String.valueOf(MASK_CHARACTER).repeat(length - REVEALED_TRAILING_DIGITS)
                + DiagnosticText.singleLine(value.substring(length - REVEALED_TRAILING_DIGITS));
    }

    /**
     * Renders a number as its zoned {@code DISPLAY} image at a declared width: left-zero-filled when
     * short, and keeping the low-order digits when it does not fit, which is the direction a COBOL
     * numeric receiver truncates in.
     *
     * <p>A negative value is rendered from its magnitude. None of the identifier fields this class
     * serves is signed - every one is an unsigned {@code PIC 9(n)} - so a sign here would mean the
     * caller passed the wrong field, and masking the magnitude discloses no more than masking zero.
     */
    private static String zoned(long value, int width) {
        String digits = Long.toString(Math.abs(value));
        if (width <= 0 || digits.length() == width) {
            return digits;
        }
        if (digits.length() > width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Not instantiable: this class is a policy, and it holds no state of any kind.
     *
     * @throws AssertionError always, if reflection is used to invoke it
     */
    private SensitiveDiagnostics() {
        throw new AssertionError("SensitiveDiagnostics is this module's disclosure policy and must not "
                + "be instantiated");
    }

    static {
        // The policy's own invariant. Revealing every character would disclose the whole value, so the
        // reveal length has to be a genuine minority of the shortest field it is applied to - the
        // narrowest is CUST-ID and CARD-XREF's PIC 9(09), and four of nine is well inside that.
        Objects.requireNonNull(REDACTED, "The redaction marker is this class's whole purpose");
        if (REVEALED_TRAILING_DIGITS < 1 || REVEALED_TRAILING_DIGITS >= 9) {
            throw new AssertionError("REVEALED_TRAILING_DIGITS is " + REVEALED_TRAILING_DIGITS
                    + "; it must reveal at least one character to be useful for correlation and fewer "
                    + "than the 9 of the narrowest identifier field to withhold anything at all");
        }
    }
}
