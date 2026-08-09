package com.vsergeychik.carddemo.common;

import java.util.Locale;
import java.util.Objects;

/**
 * The module's one policy for rendering a value into a diagnostic - a {@code toString()}, a log line,
 * an exception message - without disclosing it.
 *
 * <h2>The rule this class exists to make checkable</h2>
 * <strong>A diagnostic identifies a record and describes its shape; it does not disclose its
 * contents.</strong> That one sentence decides every call below, and it is deliberately a single
 * sentence rather than a list of forty per-field judgements: a reviewer holding a copybook open can
 * confirm a renderer against it, and an author adding a field has one question to answer rather than
 * a precedent to hunt for.
 *
 * <p>Applying the rule gives three operations and no fourth:
 *
 * <ul>
 *   <li>{@link #masked(String)} for the identifiers a diagnostic has to correlate on - a card number,
 *       an account identifier. Enough trailing characters survive to tell two records apart in a log,
 *       and no more. This is the "identify" half.</li>
 *   <li>{@link #omitted(String)} for everything a person or a balance supplied - a name, an address, a
 *       social-security number, a government-issued identifier, an EFT account, a CVV, a date of
 *       birth, a credit limit. The width is still reported, because a width is shape rather than
 *       content and a width mismatch is the single most common fixed-width defect. This is the "do not
 *       disclose" half.</li>
 *   <li>{@link #singleLine(String)} for anything else that reaches a log at all, because a value that
 *       travels through a screen field can contain a carriage return, and a carriage return in a log
 *       line lets whoever supplied it write a second log line of their own choosing.</li>
 * </ul>
 *
 * <h2>What this class must never be used for</h2>
 * <p>Nothing here touches a record image, a payload field, a repository parameter or a byte written to
 * a dataset. The migration's contract is byte-for-byte equivalence with the COBOL, so masking a value
 * on any path the COBOL can observe would be a behaviour change and a parity failure - and one of the
 * loudest kinds, since the parity differ compares field by field. In particular {@code SEC-USR-PWD}
 * remains a plaintext {@code PIC X(08)} in storage and on the wire, compared in plaintext exactly as
 * {@code COSGN00C} compares it, because hashing it would change behaviour (AAP section 0.8.3 and
 * practice B6). {@code SecUserRecord} renders it as {@code <omitted>} in its {@code toString()} and
 * stores it untouched; those two facts are not in tension, and this class is the reason the second one
 * does not require the first.
 *
 * <p>Every method is {@code static} and this class holds no state, so there is nothing here to share
 * between requests (practice B9).
 */
public final class DiagnosticText {

    /**
     * Rendered in place of a value that is withheld entirely.
     *
     * <p>The same marker {@code SecUserRecord} already uses for {@code SEC-USR-PWD}, so a reader who
     * has seen one withheld field recognises every other one.
     */
    public static final String OMITTED = "<omitted>";

    /** Rendered in place of a value that is {@code null} rather than merely withheld. */
    public static final String ABSENT = "<absent>";

    /**
     * How many trailing characters of a masked identifier stay legible.
     *
     * <p>Four, which is the convention a cardholder already sees on a receipt, and enough to tell two
     * records apart in a log without reconstructing either.
     */
    public static final int VISIBLE_TRAILING_CHARACTERS = 4;

    /** The character a masked position is rendered as. */
    private static final char MASK_CHARACTER = '*';

    /** Opens a COBOL-style hexadecimal literal, the form a control character is escaped to. */
    private static final String HEX_LITERAL_PREFIX = "X'";

    /** Closes a COBOL-style hexadecimal literal. */
    private static final String HEX_LITERAL_SUFFIX = "'";

    /** The upper bound, exclusive, of the C0 control range. */
    private static final char FIRST_PRINTABLE = 0x20;

    /** DEL, which is a control character despite sitting above the printable range. */
    private static final char DELETE = 0x7F;

    /** Lowest character of the C1 control range. */
    private static final char FIRST_C1_CONTROL = 0x80;

    /** Highest character of the C1 control range. */
    private static final char LAST_C1_CONTROL = 0x9F;

    /** Uppercase hexadecimal digits, so an escape renders the same on every platform and locale. */
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    /** Not instantiable: this is a policy expressed as functions, and it holds no state. */
    private DiagnosticText() {
        throw new AssertionError("DiagnosticText is a policy, not a value; call its static methods");
    }

    /**
     * Renders an identifier with only its last {@value #VISIBLE_TRAILING_CHARACTERS} characters legible.
     *
     * <p>For the identifiers a diagnostic must correlate on: a card number, an account identifier. A
     * value no longer than the visible allowance is masked <em>entirely</em> rather than shown, because
     * showing four of four characters would disclose the whole of a short identifier while looking like
     * it had been masked - the most misleading of the available outcomes.
     *
     * <p>Control characters are escaped as they are elsewhere, so a masked value cannot forge a log
     * line either. In practice the mask has already removed them, but the guarantee should not depend
     * on the value having been long enough for that to be true.
     *
     * @param value the identifier, possibly {@code null}
     * @return the masked identifier, or {@value #ABSENT} when {@code value} is {@code null}
     */
    public static String masked(final String value) {
        if (value == null) {
            return ABSENT;
        }
        if (value.length() <= VISIBLE_TRAILING_CHARACTERS) {
            return repeat(MASK_CHARACTER, value.length());
        }
        final int hidden = value.length() - VISIBLE_TRAILING_CHARACTERS;
        return repeat(MASK_CHARACTER, hidden) + singleLine(value.substring(hidden));
    }

    /**
     * Renders a numeric identifier with only its last {@value #VISIBLE_TRAILING_CHARACTERS} digits
     * legible.
     *
     * <p>Zero-padded to {@code digits} first, so the rendering does not vary with the value's magnitude
     * - an eleven-digit {@code ACCT-ID} looks the same whether it happens to start with a zero or not,
     * and the mask width therefore leaks nothing about the value either.
     *
     * @param value  the identifier
     * @param digits the field's declared digit count, from its {@code PICTURE}
     * @return the masked identifier
     * @throws IllegalArgumentException if {@code digits} is not positive
     */
    public static String masked(final long value, final int digits) {
        if (digits <= 0) {
            throw new IllegalArgumentException("A PIC 9 field occupies at least one digit position, so "
                    + digits + " cannot be a declared digit count");
        }
        final String unpadded = Long.toString(Math.abs(value));
        final String padded = unpadded.length() >= digits
                ? unpadded
                : repeat('0', digits - unpadded.length()) + unpadded;
        return masked(padded);
    }

    /**
     * Withholds a value entirely, reporting only its width.
     *
     * <p>For everything a person or a balance supplied. The width is reported because it is shape
     * rather than content: a field holding the wrong number of characters is the commonest defect in a
     * fixed-width estate, and a diagnostic that cannot show it would not be worth emitting.
     *
     * @param value the value to withhold, possibly {@code null}
     * @return {@value #OMITTED} with the width appended, or {@value #ABSENT} when {@code value} is
     *         {@code null}
     */
    public static String omitted(final String value) {
        if (value == null) {
            return ABSENT;
        }
        return OMITTED + ":" + value.length();
    }

    /**
     * Withholds a numeric value entirely, reporting nothing about it.
     *
     * <p>A number has no width to report that its {@code PICTURE} does not already state, and reporting
     * its magnitude - even as a digit count - would disclose part of a balance.
     *
     * @return {@value #OMITTED}
     */
    public static String omitted() {
        return OMITTED;
    }

    /**
     * Escapes every control character so the result cannot span more than one line.
     *
     * <p>A screen field arrives as caller-supplied text, and a carriage return inside one that reaches
     * a log unescaped lets whoever supplied it append a log line of their own - a forged entry,
     * indistinguishable from a real one, in whatever format the reader trusts. Escaping is therefore
     * applied to the text rather than the reader being trusted to notice.
     *
     * <p>Each control character becomes a COBOL-style hexadecimal literal - {@code X'0A'} for a line
     * feed - which is the notation this module already uses for a non-printable byte. Two properties
     * follow, and both matter: the escape is <strong>lossless</strong>, so a diagnostic still says
     * exactly what was there, and it is <strong>unambiguous</strong>, because the escape itself contains
     * no control character to escape in turn.
     *
     * <p>The C0 range, {@code DEL} and the C1 range are all escaped. C1 is included because a value
     * decoded from {@code IBM037} can land there, and several terminal emulators act on those
     * characters.
     *
     * @param value the text to escape, possibly {@code null}
     * @return the text with every control character escaped, or {@value #ABSENT} when {@code value} is
     *         {@code null}
     */
    public static String singleLine(final String value) {
        if (value == null) {
            return ABSENT;
        }
        int firstControl = -1;
        for (int index = 0; index < value.length(); index++) {
            if (isControl(value.charAt(index))) {
                firstControl = index;
                break;
            }
        }
        if (firstControl < 0) {
            // The overwhelmingly common case: nothing to escape, so nothing is allocated.
            return value;
        }
        final StringBuilder escaped = new StringBuilder(value.length() + 8);
        escaped.append(value, 0, firstControl);
        for (int index = firstControl; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (isControl(character)) {
                escaped.append(HEX_LITERAL_PREFIX)
                        .append(HEX_DIGITS[(character >> 4) & 0xF])
                        .append(HEX_DIGITS[character & 0xF])
                        .append(HEX_LITERAL_SUFFIX);
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /**
     * Renders one screen field, choosing between the three operations from the field's own
     * {@code DFHMDF} label.
     *
     * <p>Several response payloads are a map from {@code DFHMDF} label to value and render themselves
     * by walking it, so there is no per-field code in which to make a per-field decision. The label is
     * what remains, and the label is enough: BMS naming across all seventeen mapsets is consistent, so
     * an account number is spelled {@code ACCTNOnO}, {@code ACCTSIDO} or {@code ACCTID}, a card number
     * {@code CRDNUMnO}, {@code CARDSID} or {@code CARDID}, and the embossed name {@code CRDNAME}.
     *
     * <p>Deciding from the label rather than from a per-DTO list is deliberate: a new mapset gets the
     * right treatment without anyone remembering to add it, which is the failure mode a per-DTO list
     * has. A label the convention does not cover renders in full, so this method never withholds
     * something a reader needs by accident - it errs towards disclosure only for labels that carry no
     * identifier, which are the titles, dates, times, program names, flags and message fields.
     *
     * @param dfhmdfName the field's {@code DFHMDF} label; must not be {@code null}
     * @param value      the field's value, possibly {@code null}
     * @return the value masked, withheld or escaped, according to what the label denotes
     * @throws NullPointerException if {@code dfhmdfName} is {@code null}
     */
    public static String screenField(final String dfhmdfName, final String value) {
        final String base = baseLabelOf(Objects.requireNonNull(dfhmdfName,
                "A DFHMDF label is required to decide how one screen field is rendered"));
        for (String identifier : MASKED_LABEL_BASES) {
            if (base.startsWith(identifier)) {
                return masked(value);
            }
        }
        for (String withheld : WITHHELD_LABEL_BASES) {
            if (base.startsWith(withheld)) {
                return omitted(value);
            }
        }
        return singleLine(value);
    }

    /**
     * Reduces a symbolic-map item name to the base label the copybook names the field by.
     *
     * <p>A symbolic map exposes each field under several names: {@code ACCTNO1I} is the input value,
     * {@code ACCTNO1O} the output value, {@code ACCTNO1L} the length, {@code ACCTNO1F} the flag byte,
     * {@code ACCTNO1A} the attribute view, and {@code C}, {@code H}, {@code P} and {@code V} carry the
     * colour, highlight, position and validation. All of them describe one field, so the suffix is
     * dropped; the row digit of a repeating field is dropped for the same reason, since row 3 of a card
     * list is no less a card number than row 1.
     *
     * <p>Only a single trailing suffix letter is dropped, and only one from the symbolic-map set, so a
     * base name that genuinely ends in one of those letters is unharmed: {@code CRDNAME} keeps its
     * {@code E}, and {@code ACCTID} keeps its {@code D}.
     *
     * @param dfhmdfName the item name
     * @return the base label, upper-cased
     */
    private static String baseLabelOf(final String dfhmdfName) {
        String base = dfhmdfName.toUpperCase(Locale.ROOT);
        if (base.length() > 1 && SYMBOLIC_MAP_SUFFIXES.indexOf(base.charAt(base.length() - 1)) >= 0) {
            base = base.substring(0, base.length() - 1);
        }
        while (base.length() > 1 && Character.isDigit(base.charAt(base.length() - 1))) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * The suffix letters a symbolic map appends to one field's base label.
     *
     * <p>{@code I} input, {@code O} output, {@code L} length, {@code F} flag, {@code A} attribute, and
     * {@code C}, {@code H}, {@code P}, {@code V} for colour, highlight, position and validation.
     */
    private static final String SYMBOLIC_MAP_SUFFIXES = "IOLFACHPV";

    /**
     * The base labels that denote an account or card number, and are therefore masked.
     *
     * <p>Read off {@code app/cpy-bms/*.CPY} rather than guessed, because the estate spells these several
     * ways and a spelling missed here would be a spelling disclosed: {@code ACCTNOn} and {@code CRDNUMn}
     * are {@code COCRDLI}'s per-row account and card numbers, {@code ACCTSID} and {@code CARDSID} its
     * filters, {@code ACTIDIN} and {@code CARDNIN} are {@code COTRN02}'s, {@code CARDNUM} is
     * {@code COCRDSL}'s, and {@code ACCTID} and {@code CARDID} are {@code COCRDUP}'s commarea items.
     *
     * <p>Matching is by prefix on the base label, never by substring anywhere in the name. That
     * distinction is what keeps {@code CRDSELn}, {@code CRDSTSn}, {@code CRDSTPn} and {@code CRDSTCD} -
     * a selection flag and three status codes, which identify nobody - out of the masked set even though
     * they share three letters with {@code CRDNUMn}.
     */
    private static final String[] MASKED_LABEL_BASES = {
        "ACCTNO", "ACCTSID", "ACCTID", "ACTIDIN",
        "CARDNIN", "CARDNUM", "CARDSID", "CARDID", "CRDNUM",
    };

    /**
     * The base labels whose value is withheld outright, because no diagnostic needs to correlate on it.
     *
     * <p>{@code ACSTSSN} and {@code ACTSSNn} are social-security numbers, {@code ACSGOVT} a
     * government-issued identifier, {@code ACSEFT} an EFT account, {@code ACSTDOB} and
     * {@code DOBDAY}/{@code DOBMON}/{@code DOBYEAR} a date of birth, {@code FNAME}, {@code MNAME} and
     * {@code LNAME} the cardholder's name, {@code CRDNAME} the embossed name, {@code CVV} the card
     * verification value, {@code ACRDLIM} a credit limit and {@code EXP} a card expiry.
     *
     * <p>Prefix matching earns its keep here. {@code PGMNAME} <em>contains</em> {@code MNAME} and
     * {@code TRNNAME} contains {@code NAME}, yet both are copybook constants naming a program and a
     * transaction - neither identifies a person, and a substring rule would have withheld both.
     */
    private static final String[] WITHHELD_LABEL_BASES = {
        "ACSTSSN", "ACTSSN", "ACSGOVT", "ACSEFT", "ACSTDOB", "DOB",
        // ACSFNAM, ACSMNAM and ACSLNAM are the account screens' own spelling of the three name fields.
        // They do not START with FNAME, MNAME or LNAME, so the three bases below them do not match these
        // labels and the customer's name rendered in full until they were added. The COUSR list screens
        // use the shorter FNAMEnn and LNAMEnn, which is why both spellings have to be present.
        "ACSFNAM", "ACSMNAM", "ACSLNAM",
        "FNAME", "MNAME", "LNAME", "CRDNAME", "CVV", "ACRDLIM", "EXP",
    };

    /**
     * Whether a character is one a log line must not carry verbatim.
     *
     * @param character the character to classify
     * @return {@code true} for the C0 range, {@code DEL} and the C1 range
     */
    private static boolean isControl(final char character) {
        return character < FIRST_PRINTABLE
                || character == DELETE
                || (character >= FIRST_C1_CONTROL && character <= LAST_C1_CONTROL);
    }

    /**
     * Builds a run of one repeated character.
     *
     * @param character the character to repeat
     * @param count     how many times; may be zero
     * @return the run, empty when {@code count} is zero
     */
    private static String repeat(final char character, final int count) {
        return count <= 0 ? "" : String.valueOf(character).repeat(count);
    }
}
