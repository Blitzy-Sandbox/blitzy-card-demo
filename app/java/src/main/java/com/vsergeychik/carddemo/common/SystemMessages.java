package com.vsergeychik.carddemo.common;

/**
 * Standard CardDemo message literals and the abend work area, translated byte-for-byte from two
 * COBOL copybooks that this one class deliberately merges.
 *
 * <h2>What this class is</h2>
 * <ul>
 *   <li>{@code app/cpy/CSMSG01Y.cpy} - group item {@code CCDA-COMMON-MESSAGES}, two
 *       {@code PIC X(50)} message fields. It is one of the six universal online includes and is
 *       copied by all <strong>17</strong> CICS online programs, so both constants are
 *       {@code public}.</li>
 *   <li>{@code app/cpy/CSMSG02Y.cpy} - group item {@code ABEND-DATA}, four fields totalling
 *       <strong>134</strong> bytes, every one of them declared {@code VALUE SPACES}. It has
 *       <strong>5</strong> consumers: COACTUPC, COACTVWC, COCRDLIC, COCRDSLC and COCRDUPC. That
 *       copybook's own header comment still calls it {@code CABENDD.CPY} - a historical alias
 *       recorded here for traceability; the file in this repository is {@code CSMSG02Y.cpy}.</li>
 * </ul>
 *
 * <h2>The 49-versus-50 subtlety - read this before "correcting" a literal</h2>
 * Both {@code CSMSG01Y} fields are declared {@code PIC X(50)}, but each source {@code VALUE}
 * literal is only <strong>49</strong> characters long. COBOL right-pads a short alphanumeric
 * {@code VALUE} literal with spaces up to the declared width, so the field actually holds
 * <strong>50</strong> characters and any {@code MOVE} from it transfers 50 characters. The
 * constants below therefore store the <em>padded</em> 50-character form, not the 49-character
 * source literal. The declared width and the source literal length are two separate, independently
 * true facts, and both are spelled out at each constant rather than reconciled into one number.
 * Neither value is a typo: do not shorten a constant to 49, and do not "tidy" the copybook.
 *
 * <h2>Do not confuse this thank-you message with the screen-title one</h2>
 * There are two similar-looking "thank you for using ... application..." strings in this codebase
 * and they are <strong>not</strong> interchangeable:
 * <table border="1">
 *   <caption>The two distinct thank-you strings</caption>
 *   <tr><th>Copybook</th><th>COBOL field</th><th>Picture</th><th>Wording</th><th>Owning Java class</th></tr>
 *   <tr>
 *     <td>{@code CSMSG01Y.cpy:18-19}</td><td>{@code CCDA-MSG-THANK-YOU}</td><td>{@code X(50)}</td>
 *     <td>"... using <strong>CardDemo</strong> application..."</td><td><strong>this class</strong></td>
 *   </tr>
 *   <tr>
 *     <td>{@code COTTL01Y.cpy:23-24}</td><td>{@code CCDA-THANK-YOU}</td><td>{@code X(40)}</td>
 *     <td>"... using <strong>CCDA</strong> application..."</td><td>{@code ScreenTitles}</td>
 *   </tr>
 * </table>
 * Different wording, different width, different owning class. Substituting one for the other
 * produces output that reads perfectly and still fails field-for-field parity diffing. This class
 * deliberately does not reference {@code ScreenTitles}; the comparison above is documentation only.
 *
 * <h2>Relationship to the batch abend path</h2>
 * {@code ABEND-DATA} is a {@code WORKING-STORAGE} work area used by the <em>online</em> abend
 * routine. It is not a persisted record, so it needs no fixed-width record codec and none is
 * provided here. The batch {@code CALL 'CEE3ABD'} equivalent is a separate concern living in
 * {@code AbendException}; this class deliberately has no dependency on it, so both remain
 * independently testable and the dependency graph stays acyclic.
 *
 * <h2>Design constraints honoured</h2>
 * <ul>
 *   <li>Zero imports, and therefore no wildcard import, no Spring type and no dependency on any
 *       other {@code com.vsergeychik.carddemo} package: this class is a graph root.</li>
 *   <li>No mutable static state. The class is {@code final} with a private constructor and exposes
 *       only {@code static final} constants; {@link AbendData} is an immutable record with no
 *       setter of any kind.</li>
 *   <li>Message text is reproduced exactly as the COBOL emits it, with no escaping, masking,
 *       redaction or trimming. Escaping is a serialisation concern; performing it here would change
 *       observable output.</li>
 *   <li>Ordinary quoted string literals only. A text block must never be used for these values
 *       because incidental-whitespace stripping would silently destroy the trailing spaces that
 *       make the fields 50 characters wide.</li>
 * </ul>
 *
 * @see AbendData
 */
public final class SystemMessages {

    /**
     * Declared width of both {@code CCDA-COMMON-MESSAGES} fields: {@code PIC X(50)}
     * [{@code app/cpy/CSMSG01Y.cpy:18} and {@code :20}].
     *
     * <p>This is the width of the <em>field</em>. It is intentionally not the length of either
     * source {@code VALUE} literal, which is 49 in both cases - see the class documentation.
     */
    public static final int MESSAGE_LENGTH = 50;

    /**
     * {@code CCDA-MSG-THANK-YOU}, {@code PIC X(50)}
     * [{@code app/cpy/CSMSG01Y.cpy:18-19}].
     *
     * <p>Composition, written below as three concatenated pieces so that every single space is
     * countable by eye (javac folds them into one compile-time constant):
     * <ol>
     *   <li><strong>43</strong> characters of content: {@code Thank you for using CardDemo
     *       application...} - note "CardDemo", not "CCDA"; the screen-title copybook owns the
     *       "CCDA application" wording and a different width.</li>
     *   <li><strong>6</strong> trailing spaces that are physically present inside the quotes in the
     *       copybook, bringing the source literal to <strong>49</strong> characters.</li>
     *   <li><strong>1</strong> further space that COBOL supplies implicitly to fill the declared
     *       {@code PIC X(50)}, bringing the stored field value to <strong>50</strong> characters.</li>
     * </ol>
     * 43 + 6 + 1 = {@value #MESSAGE_LENGTH}. Never trim this value: it is a fixed-width message
     * field, and the trailing spaces are part of what the COBOL moves onto the screen.
     */
    public static final String CCDA_MSG_THANK_YOU =
            "Thank you for using CardDemo application..."
                    + "      "
                    + " ";

    /**
     * {@code CCDA-MSG-INVALID-KEY}, {@code PIC X(50)}
     * [{@code app/cpy/CSMSG01Y.cpy:20-21}].
     *
     * <p>Composition, on the same three-piece pattern as {@link #CCDA_MSG_THANK_YOU}:
     * <ol>
     *   <li><strong>40</strong> characters of content: {@code Invalid key pressed. Please see
     *       below...} - forty, counted mechanically from the copybook including the trailing
     *       three-dot ellipsis; recount it against {@code CSMSG01Y.cpy:21} before assuming
     *       thirty-nine.</li>
     *   <li><strong>9</strong> trailing spaces physically present inside the quotes, bringing the
     *       source literal to <strong>49</strong> characters.</li>
     *   <li><strong>1</strong> further space supplied implicitly by COBOL to fill
     *       {@code PIC X(50)}, bringing the stored field value to <strong>50</strong>.</li>
     * </ol>
     * 40 + 9 + 1 = {@value #MESSAGE_LENGTH}. The split between pieces one and two is a property of
     * how the copybook author typed the literal and carries no meaning; only the two totals - 49
     * for the literal and 50 for the field - are behaviourally significant, and both are asserted
     * by the unit tests for this class.
     */
    public static final String CCDA_MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..."
                    + "         "
                    + " ";

    /**
     * Declared width of {@code ABEND-CODE}: {@code PIC X(4)}
     * [{@code app/cpy/CSMSG02Y.cpy:22-23}].
     */
    public static final int ABEND_CODE_LENGTH = 4;

    /**
     * Declared width of {@code ABEND-CULPRIT}: {@code PIC X(8)}
     * [{@code app/cpy/CSMSG02Y.cpy:24-25}]. Eight characters is exactly one COBOL program name,
     * which is what the online abend routine moves into it.
     */
    public static final int ABEND_CULPRIT_LENGTH = 8;

    /**
     * Declared width of {@code ABEND-REASON}: {@code PIC X(50)}
     * [{@code app/cpy/CSMSG02Y.cpy:26-27}].
     *
     * <p>This is 50 because {@code CSMSG02Y} declares it as 50, which is an independent fact from
     * {@link #MESSAGE_LENGTH} also being 50. The two constants are deliberately not aliased to one
     * another: they come from different copybooks and either could change without the other.
     */
    public static final int ABEND_REASON_LENGTH = 50;

    /**
     * Declared width of {@code ABEND-MSG}: {@code PIC X(72)}
     * [{@code app/cpy/CSMSG02Y.cpy:28-29}].
     */
    public static final int ABEND_MSG_LENGTH = 72;

    /**
     * Total size of the {@code ABEND-DATA} group item: 4 + 8 + 50 + 72 = <strong>134</strong>
     * bytes [{@code app/cpy/CSMSG02Y.cpy:21-29}].
     *
     * <p>Deliberately written as the sum of the four component widths rather than as the literal
     * 134, so that the arithmetic is performed and checkable in exactly one place. javac evaluates
     * it at compile time, so this remains a compile-time constant.
     */
    public static final int ABEND_DATA_LENGTH =
            ABEND_CODE_LENGTH + ABEND_CULPRIT_LENGTH + ABEND_REASON_LENGTH + ABEND_MSG_LENGTH;

    /**
     * Not instantiable: this type is a holder for copybook-derived constants and one nested value
     * type. The body is intentionally empty rather than throwing, because an empty private
     * constructor on a class with only static members is recognised as the utility-class idiom by
     * coverage tooling and does not distort the module's coverage figures.
     */
    private SystemMessages() {
        // No instances. See the constructor documentation above.
    }

    /**
     * The {@code ABEND-DATA} work area of {@code app/cpy/CSMSG02Y.cpy} (header alias
     * {@code CABENDD.CPY}), lines 21-29, as an immutable value type.
     *
     * <pre>
     * 01  ABEND-DATA.
     *   05  ABEND-CODE     PIC X(4)   VALUE SPACES.
     *   05  ABEND-CULPRIT  PIC X(8)   VALUE SPACES.
     *   05  ABEND-REASON   PIC X(50)  VALUE SPACES.
     *   05  ABEND-MSG      PIC X(72)  VALUE SPACES.
     * </pre>
     *
     * <h2>Why a record, and why immutable</h2>
     * In COBOL this is {@code WORKING-STORAGE}, which is per-run storage owned by the program. It
     * must not become static Java state: that would leak one request's abend detail into another
     * and would make tests order-dependent. Modelling it as a record gives value semantics, so two
     * areas built from the same four components are {@code equals}, and there is no setter and no
     * mutable component anywhere on the type.
     *
     * <h2>Translating a {@code MOVE} into one of the four fields</h2>
     * The online abend routine updates one field at a time - for example
     * {@code MOVE LIT-THISPGM TO ABEND-CULPRIT} followed by {@code MOVE '0001' TO ABEND-CODE}
     * (COACTVWC, COACTUPC, COCRDLIC, COCRDSLC and COCRDUPC all follow this shape). Each such
     * {@code MOVE} maps onto one of the {@code with...} methods below, which returns a new area
     * leaving the other three fields untouched, exactly as the COBOL {@code MOVE} does. Those
     * methods apply {@code PIC X} move semantics to the incoming value, so a field can never end up
     * holding more than its declared width.
     *
     * <h2>The default is spaces, not empty and not null</h2>
     * All four fields are declared {@code VALUE SPACES}, so the initial state of every component is
     * a run of spaces at its declared width - see {@link #spaces()}. An empty string would be
     * wrong, and {@code null} has no COBOL counterpart at all, which is why the canonical
     * constructor rejects it outright. Note for consumers that the online abend routine tests
     * {@code IF ABEND-MSG EQUAL LOW-VALUES} before substituting its own default text; that test
     * belongs to the program, not to this copybook, and is therefore not modelled here.
     *
     * @param abendCode    {@code ABEND-CODE}, {@code PIC X(4)}, width
     *                     {@value SystemMessages#ABEND_CODE_LENGTH}
     *                     [{@code app/cpy/CSMSG02Y.cpy:22-23}]
     * @param abendCulprit {@code ABEND-CULPRIT}, {@code PIC X(8)}, width
     *                     {@value SystemMessages#ABEND_CULPRIT_LENGTH}
     *                     [{@code app/cpy/CSMSG02Y.cpy:24-25}]
     * @param abendReason  {@code ABEND-REASON}, {@code PIC X(50)}, width
     *                     {@value SystemMessages#ABEND_REASON_LENGTH}
     *                     [{@code app/cpy/CSMSG02Y.cpy:26-27}]
     * @param abendMsg     {@code ABEND-MSG}, {@code PIC X(72)}, width
     *                     {@value SystemMessages#ABEND_MSG_LENGTH}
     *                     [{@code app/cpy/CSMSG02Y.cpy:28-29}]
     */
    public record AbendData(String abendCode, String abendCulprit, String abendReason,
                            String abendMsg) {

        /**
         * Rejects {@code null} in any component, naming the offending COBOL field so a failure is
         * self-explaining.
         *
         * <p>Widths are deliberately <em>not</em> enforced here. A COBOL group item can legitimately
         * be observed mid-update, and rejecting a short or long value at construction time would
         * make the type harder to use than the {@code WORKING-STORAGE} area it replaces. Use
         * {@link #toDeclaredWidths()} to obtain the canonical fixed-width form, or the
         * {@code with...} methods, which apply {@code PIC X} semantics as they go.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public AbendData {
            if (abendCode == null) {
                throw new NullPointerException(
                        "abendCode (ABEND-CODE) must not be null: CSMSG02Y declares VALUE SPACES,"
                                + " so the absent value is a run of spaces, never null");
            }
            if (abendCulprit == null) {
                throw new NullPointerException(
                        "abendCulprit (ABEND-CULPRIT) must not be null: CSMSG02Y declares VALUE"
                                + " SPACES, so the absent value is a run of spaces, never null");
            }
            if (abendReason == null) {
                throw new NullPointerException(
                        "abendReason (ABEND-REASON) must not be null: CSMSG02Y declares VALUE"
                                + " SPACES, so the absent value is a run of spaces, never null");
            }
            if (abendMsg == null) {
                throw new NullPointerException(
                        "abendMsg (ABEND-MSG) must not be null: CSMSG02Y declares VALUE SPACES,"
                                + " so the absent value is a run of spaces, never null");
            }
        }

        /**
         * The initial state declared by the copybook: every field {@code VALUE SPACES}, each one a
         * run of spaces at its own declared width - 4, 8, 50 and 72 characters respectively,
         * {@value SystemMessages#ABEND_DATA_LENGTH} characters in total.
         *
         * <p>No component is ever the empty string and no component is ever {@code null}.
         *
         * @return a fresh area in its {@code VALUE SPACES} initial state
         */
        public static AbendData spaces() {
            return new AbendData(
                    spacesOfWidth(ABEND_CODE_LENGTH),
                    spacesOfWidth(ABEND_CULPRIT_LENGTH),
                    spacesOfWidth(ABEND_REASON_LENGTH),
                    spacesOfWidth(ABEND_MSG_LENGTH));
        }

        /**
         * This area with every component brought to its declared width using {@code PIC X} move
         * semantics: <strong>padded on the right</strong> with spaces when short, and
         * <strong>truncated on the right</strong> when long.
         *
         * <p>Right-hand truncation is the correct direction for alphanumeric {@code PIC X} fields.
         * It is emphatically not the correct direction for numeric {@code PIC 9} fields, which
         * truncate on the left; this type has no numeric field, and nothing here should be reused as
         * a general-purpose codec. Full record serialisation, including {@code FILLER} handling and
         * zoned sign overpunch, is the fixed-width codec's responsibility - {@code ABEND-DATA} is a
         * work area and is never persisted.
         *
         * @return an area whose four components are exactly 4, 8, 50 and 72 characters long
         */
        public AbendData toDeclaredWidths() {
            return new AbendData(
                    picX(abendCode, ABEND_CODE_LENGTH, "ABEND-CODE"),
                    picX(abendCulprit, ABEND_CULPRIT_LENGTH, "ABEND-CULPRIT"),
                    picX(abendReason, ABEND_REASON_LENGTH, "ABEND-REASON"),
                    picX(abendMsg, ABEND_MSG_LENGTH, "ABEND-MSG"));
        }

        /**
         * The equivalent of {@code MOVE value TO ABEND-CODE}: a new area carrying {@code value} at
         * the declared width of {@value SystemMessages#ABEND_CODE_LENGTH}, with the other three
         * fields unchanged.
         *
         * @param value the value being moved in; padded or truncated on the right to width
         *              {@value SystemMessages#ABEND_CODE_LENGTH}
         * @return a new area; this one is unchanged
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public AbendData withAbendCode(String value) {
            return new AbendData(picX(value, ABEND_CODE_LENGTH, "ABEND-CODE"),
                    abendCulprit, abendReason, abendMsg);
        }

        /**
         * The equivalent of {@code MOVE value TO ABEND-CULPRIT}, as the abend routine does with the
         * failing program's name: a new area carrying {@code value} at the declared width of
         * {@value SystemMessages#ABEND_CULPRIT_LENGTH}, with the other three fields unchanged.
         *
         * @param value the value being moved in; padded or truncated on the right to width
         *              {@value SystemMessages#ABEND_CULPRIT_LENGTH}
         * @return a new area; this one is unchanged
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public AbendData withAbendCulprit(String value) {
            return new AbendData(abendCode,
                    picX(value, ABEND_CULPRIT_LENGTH, "ABEND-CULPRIT"), abendReason, abendMsg);
        }

        /**
         * The equivalent of {@code MOVE value TO ABEND-REASON}: a new area carrying {@code value} at
         * the declared width of {@value SystemMessages#ABEND_REASON_LENGTH}, with the other three
         * fields unchanged.
         *
         * @param value the value being moved in; padded or truncated on the right to width
         *              {@value SystemMessages#ABEND_REASON_LENGTH}
         * @return a new area; this one is unchanged
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public AbendData withAbendReason(String value) {
            return new AbendData(abendCode, abendCulprit,
                    picX(value, ABEND_REASON_LENGTH, "ABEND-REASON"), abendMsg);
        }

        /**
         * The equivalent of {@code MOVE value TO ABEND-MSG}: a new area carrying {@code value} at
         * the declared width of {@value SystemMessages#ABEND_MSG_LENGTH}, with the other three
         * fields unchanged.
         *
         * @param value the value being moved in; padded or truncated on the right to width
         *              {@value SystemMessages#ABEND_MSG_LENGTH}
         * @return a new area; this one is unchanged
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public AbendData withAbendMsg(String value) {
            return new AbendData(abendCode, abendCulprit, abendReason,
                    picX(value, ABEND_MSG_LENGTH, "ABEND-MSG"));
        }

        /**
         * Applies {@code PIC X} move semantics for one field: right-pad with spaces when the value
         * is shorter than the declared width, right-truncate when it is longer, and return it
         * untouched when it already matches.
         *
         * @param value     the incoming value, never {@code null}
         * @param width     the declared width of the receiving field
         * @param cobolName the receiving field's COBOL name, used only to make a failure legible
         * @return {@code value} at exactly {@code width} characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        private static String picX(String value, int width, String cobolName) {
            if (value == null) {
                throw new NullPointerException(cobolName
                        + " must not be null: CSMSG02Y declares VALUE SPACES, so the absent value"
                        + " is a run of spaces, never null");
            }
            if (value.length() == width) {
                return value;
            }
            if (value.length() < width) {
                return value + spacesOfWidth(width - value.length());
            }
            return value.substring(0, width);
        }

        /**
         * A run of exactly {@code width} spaces - the Java rendering of COBOL {@code SPACES} for a
         * field of that width.
         *
         * @param width how many spaces are required
         * @return a string of {@code width} space characters
         */
        private static String spacesOfWidth(int width) {
            return " ".repeat(width);
        }
    }

}
