/*
 * ******************************************************************
 * Program     : ApiMasking.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 *               support
 * Function    : The two rules by which an untrusted or sensitive
 *               value is reduced before it may be emitted: card-number
 *               masking for an HTTP response body, so the REST surface
 *               never emits a primary account number the 3270 screens
 *               rendered in full, and control-character neutralisation
 *               for a diagnostic rendering, so a value taken from a
 *               request body cannot forge a log record.
 * Source      : app/cpy/CVACT02Y.cpy:L5 (CARD-NUM PIC X(16), the
 *               16-byte primary account number) @ 7756d89
 * Source      : app/cpy-bms/COCRDSL.CPY:66 (CARDSIDI PIC X(16)),
 *               app/cpy-bms/COCRDLI.CPY (CRDNUM01..07 PIC X(16)),
 *               app/cpy-bms/COTRN01.CPY (CARDNUMI PIC X(16))
 *               - the screens that displayed it in full @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.dto;

import java.util.Locale;

/**
 * The two rules by which a value is reduced before it may be emitted.
 *
 * <h2>What it does and why it exists once</h2>
 *
 * <p>The legacy screens displayed the sixteen-byte primary account number of {@code CVACT02Y.cpy:L5} in
 * full, because a 3270 terminal in a card-operations centre is a controlled physical environment. An HTTP
 * response is not: it is logged by proxies, cached by clients, captured by browser tooling and forwarded by
 * gateways. Emitting the full value over HTTP would therefore disclose it far more widely than the source
 * ever did, so every response type that carries a card reference carries a masked one.</p>
 *
 * <p>The rule lives here, once, rather than in each response type. A masking rule implemented three times is
 * a rule that will eventually be implemented three different ways, and the one that is wrong will be the one
 * that is not tested.</p>
 *
 * <h2>The second rule: a diagnostic rendering may not forge a log record</h2>
 *
 * <p><strong>Finding, severity Medium - remediated by {@link #forDiagnostics(String)} and by the six
 * {@code toString()} methods that now call it.</strong> Six request and response types render a few of their
 * own fields into a diagnostic string, having deliberately excluded the credential, the card number and the
 * personally identifying names. That exclusion was correct and is unchanged. What was wrong is what the
 * remaining fields could carry: {@code userId}, {@code programName}, {@code transactionName},
 * {@code accountId}, {@code userType} and {@code accountStatus} are all declared {@code String} and all
 * arrive from a JSON request body, so a caller controlled their bytes. Concatenated straight into a message,
 * a CR or LF in any of them forges as many further log lines as the caller likes, in the exact shape a reader
 * trusts - and several of those methods documented themselves as safe to place in a log record while doing
 * it.</p>
 *
 * <p>The timing is what makes it reachable rather than theoretical. {@code @Size} and {@code @Pattern} run
 * <em>after</em> Jackson has constructed the record, and a validation failure is precisely the occasion on
 * which something renders the offending instance. The rendering therefore has to be safe on an instance that
 * never passed validation and never will.</p>
 *
 * <p>Neutralisation is chosen over rejecting the characters at construction. A record constructor that threw
 * would turn a validation concern into a deserialisation failure, which surfaces as a different HTTP status
 * and a different body than the field-level report the {@code CSSETATY.cpy} error-marker contract requires -
 * changing observable behaviour to fix a logging defect. Escaping changes only what the log receives.</p>
 *
 * <h2>The rule</h2>
 *
 * <ul>
 *   <li>A value with more than {@value #VISIBLE_DIGITS} characters keeps its last {@value #VISIBLE_DIGITS}
 *       and every earlier character becomes {@value #MASK_CHARACTER}. Length is therefore preserved, which
 *       keeps a sixteen-digit number distinguishable from a malformed one <em>without</em> disclosing any
 *       part of the number that identifies the card.</li>
 *   <li>A value of {@value #VISIBLE_DIGITS} characters or fewer is masked <em>in full</em>. Showing the last
 *       four of a four-character value would show all of it.</li>
 *   <li>{@code null} is returned unchanged, so "no card reference" stays distinct from "a masked one".</li>
 *   <li>A blank value is returned unchanged. The card-list rows of {@code app/cbl/COCRDLIC.cbl} are padded
 *       to the seven-row table depth with blanks, and turning a blank filler row into a run of asterisks
 *       would invent a card where the source displayed none.</li>
 *   </ul>
 *
 * <p>Trailing blanks are significant and are preserved: the symbolic maps declare fixed-width fields and the
 * value may arrive space padded, so the mask is applied to the trimmed extent while the padding is kept.</p>
 *
 * <h2>Inputs, outputs, side effects and failure modes</h2>
 *
 * <p><b>Inputs.</b> One card number as text, in any state including null and blank. <b>Outputs.</b> The
 * masked rendering. <b>Side effects.</b> None; the method is pure. <b>Failure modes.</b> None: there is no
 * input this method rejects, because a response is being built and refusing to render one is not an option
 * the caller can act on. Callers validate; this masks.</p>
 *
 * <p>This class is a stateless holder of one static method, is {@code final}, and cannot be instantiated.</p>
 */
public final class ApiMasking {

    /**
     * How many trailing characters survive masking, namely four - the industry-conventional last four, and
     * the largest suffix that identifies a card to its holder without identifying it to anyone else.
     */
    public static final int VISIBLE_DIGITS = 4;

    /** The character every masked position becomes. */
    public static final char MASK_CHARACTER = '*';

    /**
     * How many characters of one value survive into a diagnostic rendering, namely 128.
     *
     * <p>Derived rather than picked: the longest field declared by any request type in this package is 80
     * characters, which is the widest {@code PIC X(n)} the symbolic maps of {@code app/cpy-bms} contribute.
     * 128 therefore admits every legitimate value with room to spare while still bounding the record. The
     * bound is needed precisely because the length constraints have not necessarily run - an instance being
     * rendered because it failed validation may hold a field of any length at all.
     */
    public static final int DIAGNOSTIC_MAX_LENGTH = 128;

    /**
     * Not instantiable: this class is one rule, not an object.
     */
    private ApiMasking() {
        throw new AssertionError("ApiMasking holds two static rules and is never instantiated");
    }

    /**
     * Rendering of one escaped control character, uppercase so it reads as a code point, not a word.
     *
     * <p>The literal is split deliberately. Java translates unicode escapes before it lexes anything else,
     * and a {@code u} preceded by an even number of backslashes is an eligible escape - so writing the
     * two-character sequence directly makes the compiler read {@code %04X} as malformed hexadecimal and
     * refuse the file, even though the sequence is inside a string literal. Concatenating keeps the backslash
     * away from the {@code u} in source while producing exactly the same value at runtime.
     */
    private static final String CONTROL_ESCAPE_FORMAT = "\\" + "u%04X";

    /** Unicode LINE SEPARATOR: a line terminator to some consumers, and not an ISO control. */
    private static final char LINE_SEPARATOR = (char) 0x2028;

    /** Unicode PARAGRAPH SEPARATOR: the same, for the same reason. */
    private static final char PARAGRAPH_SEPARATOR = (char) 0x2029;

    /** Opening of the marker that states a value was truncated, and to what. */
    private static final String TRUNCATION_PREFIX = "...(";

    /** Closing of that marker. */
    private static final String TRUNCATION_SUFFIX = " chars)";

    /** Slack for escapes and the truncation marker, so the builder rarely grows. */
    private static final int BUILDER_HEADROOM = 24;

    /**
     * Masks a card number for emission in an HTTP response.
     *
     * @param cardNumber the card number as the service produced it, possibly space padded to its declared
     *     sixteen-byte width; may be null and may be blank
     * @return null when {@code cardNumber} is null, the value unchanged when it is blank, and otherwise the
     *     masked rendering described on this class; never longer or shorter than the input
     */
    public static String maskCardNumber(final String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return cardNumber;
        }
        final int significantLength = significantLengthOf(cardNumber);
        final int visible = significantLength > VISIBLE_DIGITS ? VISIBLE_DIGITS : 0;
        final StringBuilder masked = new StringBuilder(cardNumber.length());
        masked.append(String.valueOf(MASK_CHARACTER).repeat(significantLength - visible));
        masked.append(cardNumber, significantLength - visible, significantLength);
        masked.append(cardNumber, significantLength, cardNumber.length());
        return masked.toString();
    }

    /**
     * Reduces a value to a form that is safe to place in a single-line log record or exception message.
     *
     * <p>Every character the platform classifies as an ISO control - the C0 range including CR, LF and tab,
     * {@code DEL}, and the C1 range including {@code NEL} - together with the Unicode line and paragraph
     * separators, is replaced by its own {@code &#92;uXXXX} escape. The escape is deliberately not a
     * placeholder: a reader investigating a hostile request needs to know <em>which</em> byte arrived, and an
     * escape says exactly that while being inert in every log format this application writes. Values longer
     * than {@value #DIAGNOSTIC_MAX_LENGTH} are truncated and the original length is stated, so a bounded
     * record never silently hides that it was bounded.
     *
     * @param value the value as it arrived, in any state including null, blank, over-long and hostile
     * @return null when {@code value} is null, so an absent field stays distinguishable from an empty one;
     *     otherwise the value with every control character escaped and its length bounded
     */
    public static String forDiagnostics(final String value) {
        if (value == null) {
            return null;
        }
        final int retained = Math.min(value.length(), DIAGNOSTIC_MAX_LENGTH);
        final StringBuilder rendered = new StringBuilder(retained + BUILDER_HEADROOM);
        for (int index = 0; index < retained; index++) {
            final char character = value.charAt(index);
            if (isUnsafeForOneLineDiagnostics(character)) {
                rendered.append(String.format(Locale.ROOT, CONTROL_ESCAPE_FORMAT, (int) character));
            } else {
                rendered.append(character);
            }
        }
        if (retained < value.length()) {
            rendered.append(TRUNCATION_PREFIX).append(value.length()).append(TRUNCATION_SUFFIX);
        }
        return rendered.toString();
    }

    /**
     * Reports whether a character would break the one-record-per-line discipline the log format relies on.
     *
     * <p>{@link Character#isISOControl(char)} already covers {@code U+0000}-{@code U+001F},
     * {@code U+007F}-{@code U+009F} and therefore {@code NEL} at {@code U+0085}. The two Unicode separators
     * are added because they are line terminators to some consumers and are not ISO controls.
     *
     * @param character the character to classify
     * @return {@code true} when the character must be escaped rather than emitted
     */
    private static boolean isUnsafeForOneLineDiagnostics(final char character) {
        return Character.isISOControl(character)
                || character == LINE_SEPARATOR
                || character == PARAGRAPH_SEPARATOR;
    }

    /**
     * Returns the length of the value up to but excluding its trailing blanks, which is the extent the mask
     * is applied to.
     *
     * @param value a non-blank value
     * @return the index one past the last non-blank character; at least one, because the value is not blank
     */
    private static int significantLengthOf(final String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return end;
    }
}
