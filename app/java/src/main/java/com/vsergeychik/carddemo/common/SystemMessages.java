package com.vsergeychik.carddemo.common;

/**
 * Standard CardDemo message literals and the abend work area, translated byte-for-byte from two COBOL
 * copybooks that this one class deliberately merges.
 *
 * <p>Message text is reproduced exactly as the COBOL emits it, with no escaping, masking, redaction or
 * trimming.
 */
public final class SystemMessages {
    /**
     * Declared width of both {@code CCDA-COMMON-MESSAGES} fields: {@code PIC X(50)}
     * [{@code app/cpy/CSMSG01Y.cpy:18} and {@code :20}].
     */
    public static final int MESSAGE_LENGTH = 50;

    /**
     * {@code CCDA-MSG-THANK-YOU}, {@code PIC X(50)} [{@code app/cpy/CSMSG01Y.cpy:18-19}].
     */
    public static final String CCDA_MSG_THANK_YOU =
            "Thank you for using CardDemo application..."
                    + "      "
                    + " ";

    /**
     * {@code CCDA-MSG-INVALID-KEY}, {@code PIC X(50)} [{@code app/cpy/CSMSG01Y.cpy:20-21}].
     */
    public static final String CCDA_MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..."
                    + "         "
                    + " ";

    /**
     * Declared width of {@code ABEND-CODE}: {@code PIC X(4)} [{@code app/cpy/CSMSG02Y.cpy:22-23}].
     */
    public static final int ABEND_CODE_LENGTH = 4;

    /**
     * Declared width of {@code ABEND-CULPRIT}: {@code PIC X(8)} [{@code app/cpy/CSMSG02Y.cpy:24-25}].
     */
    public static final int ABEND_CULPRIT_LENGTH = 8;

    /**
     * Declared width of {@code ABEND-REASON}: {@code PIC X(50)} [{@code app/cpy/CSMSG02Y.cpy:26-27}].
     */
    public static final int ABEND_REASON_LENGTH = 50;

    /**
     * Declared width of {@code ABEND-MSG}: {@code PIC X(72)} [{@code app/cpy/CSMSG02Y.cpy:28-29}].
     */
    public static final int ABEND_MSG_LENGTH = 72;

    /**
     * Total size of the {@code ABEND-DATA} group item: 4 + 8 + 50 + 72 = 134 bytes
     * [{@code app/cpy/CSMSG02Y.cpy:21-29}].
     */
    public static final int ABEND_DATA_LENGTH =
            ABEND_CODE_LENGTH + ABEND_CULPRIT_LENGTH + ABEND_REASON_LENGTH + ABEND_MSG_LENGTH;

    private SystemMessages() {
    }

    /**
     * The {@code ABEND-DATA} work area of {@code app/cpy/CSMSG02Y.cpy} (header alias {@code CABENDD.CPY}),
     * lines 21-29, as an immutable value type.
     *
     * @param abendCode {@code ABEND-CODE}, {@code PIC X(4)}, width
     *     {@value SystemMessages#ABEND_CODE_LENGTH} [{@code app/cpy/CSMSG02Y.cpy:22-23}]
     * @param abendCulprit {@code ABEND-CULPRIT}, {@code PIC X(8)}, width
     *     {@value SystemMessages#ABEND_CULPRIT_LENGTH} [{@code app/cpy/CSMSG02Y.cpy:24-25}]
     * @param abendReason {@code ABEND-REASON}, {@code PIC X(50)}, width
     *     {@value SystemMessages#ABEND_REASON_LENGTH} [{@code app/cpy/CSMSG02Y.cpy:26-27}]
     * @param abendMsg {@code ABEND-MSG}, {@code PIC X(72)}, width {@value SystemMessages#ABEND_MSG_LENGTH}
     *     [{@code app/cpy/CSMSG02Y.cpy:28-29}]
     */
    public record AbendData(String abendCode, String abendCulprit, String abendReason,
                            String abendMsg) {
        /**
         * Rejects {@code null} in any component, naming the offending COBOL field so a failure is
         * self-explaining.
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
         * The initial state declared by the copybook: every field {@code VALUE SPACES}, each one a run of
         * spaces at its own declared width - 4, 8, 50 and 72 characters respectively,
         * {@value SystemMessages#ABEND_DATA_LENGTH} characters in total.
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
         * This area with every component brought to its declared width using {@code PIC X} move semantics:
         * padded on the right with spaces when short, and truncated on the right when long.
         *
         * <p>Right-hand truncation is the correct direction for alphanumeric {@code PIC X} fields.
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
         * The equivalent of {@code MOVE value TO ABEND-CODE}: a new area carrying {@code value} at the
         * declared width of {@value SystemMessages#ABEND_CODE_LENGTH}, with the other three fields
         * unchanged.
         *
         * @param value the value being moved in; padded or truncated on the right to width
         *     {@value SystemMessages#ABEND_CODE_LENGTH}
         * @return a new area; this one is unchanged
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public AbendData withAbendCode(String value) {
            return new AbendData(picX(value, ABEND_CODE_LENGTH, "ABEND-CODE"),
                    abendCulprit, abendReason, abendMsg);
        }

        /**
         * The equivalent of {@code MOVE value TO ABEND-CULPRIT}, as the abend routine does with the failing
         * program's name: a new area carrying {@code value} at the declared width of
         * {@value SystemMessages#ABEND_CULPRIT_LENGTH}, with the other three fields unchanged.
         *
         * @param value the value being moved in; padded or truncated on the right to width
         *     {@value SystemMessages#ABEND_CULPRIT_LENGTH}
         * @return a new area; this one is unchanged
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public AbendData withAbendCulprit(String value) {
            return new AbendData(abendCode,
                    picX(value, ABEND_CULPRIT_LENGTH, "ABEND-CULPRIT"), abendReason, abendMsg);
        }

        /**
         * The equivalent of {@code MOVE value TO ABEND-REASON}: a new area carrying {@code value} at the
         * declared width of {@value SystemMessages#ABEND_REASON_LENGTH}, with the other three fields
         * unchanged.
         *
         * @param value the value being moved in; padded or truncated on the right to width
         *     {@value SystemMessages#ABEND_REASON_LENGTH}
         * @return a new area; this one is unchanged
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public AbendData withAbendReason(String value) {
            return new AbendData(abendCode, abendCulprit,
                    picX(value, ABEND_REASON_LENGTH, "ABEND-REASON"), abendMsg);
        }

        /**
         * The equivalent of {@code MOVE value TO ABEND-MSG}: a new area carrying {@code value} at the
         * declared width of {@value SystemMessages#ABEND_MSG_LENGTH}, with the other three fields
         * unchanged.
         *
         * @param value the value being moved in; padded or truncated on the right to width
         *     {@value SystemMessages#ABEND_MSG_LENGTH}
         * @return a new area; this one is unchanged
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public AbendData withAbendMsg(String value) {
            return new AbendData(abendCode, abendCulprit, abendReason,
                    picX(value, ABEND_MSG_LENGTH, "ABEND-MSG"));
        }

        /**
         * Applies {@code PIC X} move semantics for one field: right-pad with spaces when the value is
         * shorter than the declared width, right-truncate when it is longer, and return it untouched when
         * it already matches.
         *
         * @param value the incoming value, never {@code null}
         * @param width the declared width of the receiving field
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
         * A run of exactly {@code width} spaces - the Java rendering of COBOL {@code SPACES} for a field of
         * that width.
         *
         * @param width how many spaces are required
         * @return a string of {@code width} space characters
         */
        private static String spacesOfWidth(int width) {
            return " ".repeat(width);
        }
    }

}
