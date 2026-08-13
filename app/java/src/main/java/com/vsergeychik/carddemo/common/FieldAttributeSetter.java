package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * The error-highlight rule of {@code app/cpy/CSSETATY.cpy}: turn a screen field red when it failed
 * validation or was left empty, and additionally stamp an asterisk into it when it was empty - but only
 * when the program is being re-entered.
 */
public final class FieldAttributeSetter {
    /**
     * The blank-field marker moved into the output data item: {@code MOVE '*'} at
     * {@code app/cpy/CSSETATY.cpy:L24}.
     */
    public static final String ASTERISK = "*";

    /**
     * The suffix that turns a symbolic-map field prefix into its colour item: {@code (SCRNVAR2)C} at
     * {@code app/cpy/CSSETATY.cpy:L22}.
     */
    public static final String COLOUR_ITEM_SUFFIX = "C";

    /**
     * The suffix that turns a symbolic-map field prefix into its output data item: {@code (SCRNVAR2)O} at
     * {@code app/cpy/CSSETATY.cpy:L25}.
     */
    public static final String OUTPUT_ITEM_SUFFIX = "O";

    /**
     * The suffix that turns a map name into its output direction group: {@code OF (MAPNAME3)O} at
     * {@code app/cpy/CSSETATY.cpy:L22} and {@code L25}, which for {@code CACTUPA} yields {@code CACTUPAO}.
     */
    public static final String OUTPUT_MAP_SUFFIX = "O";

    private static final String UNNAMED = "";

    private FieldAttributeSetter() {
        throw new AssertionError("FieldAttributeSetter is a stateless helper and must not be instantiated");
    }

    /**
     * Decides the highlight for one screen field, carrying the field's and map's identity for diagnostics.
     *
     * @param state the field's validation state, the {@code (TESTVAR1)} analogue; never {@code null}
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *     {@code CDEMO-PGM-CONTEXT} is {@code 1}; {@code false} on first entry
     * @param screenFieldPrefix the symbolic-map field prefix, the {@code (SCRNVAR2)} analogue, for example
     *     {@code "ACSTTUS"}; never {@code null}, and the empty string when unknown
     * @param mapName the map name, the {@code (MAPNAME3)} analogue, for example {@code "CACTUPA"}; never
     *     {@code null}, and the empty string when unknown
     * @return the highlight to apply; never {@code null}
     * @throws NullPointerException if {@code state}, {@code screenFieldPrefix} or {@code mapName} is
     *     {@code null}
     */
    public static FieldHighlight resolve(FieldValidationState state, boolean reenter,
            String screenFieldPrefix, String mapName) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(screenFieldPrefix, "screenFieldPrefix must not be null");
        Objects.requireNonNull(mapName, "mapName must not be null");

        if ((state.notOk() || state.blank()) && reenter) {
            boolean assignColourItem = true;
            boolean assignOutputItem = false;

            if (state.blank()) {
                assignOutputItem = true;
            }

            return new FieldHighlight(assignColourItem, assignOutputItem, screenFieldPrefix, mapName);
        }

        return FieldHighlight.none(screenFieldPrefix, mapName);
    }

    /**
     * Decides the highlight for one screen field without recording any identity, for callers that already
     * know which field they are asking about.
     *
     * @param state the field's validation state, the {@code (TESTVAR1)} analogue; never {@code null}
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds
     * @return the highlight to apply; never {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public static FieldHighlight resolve(FieldValidationState state, boolean reenter) {
        return resolve(state, reenter, UNNAMED, UNNAMED);
    }

    /**
     * Decides the highlight from the two raw {@code 88}-level outcomes rather than from a
     * {@link FieldValidationState}, carrying the field's and map's identity for diagnostics.
     *
     * @param notOk {@code true} when {@code FLG-<field>-NOT-OK} holds
     * @param blank {@code true} when {@code FLG-<field>-BLANK} holds
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds
     * @param screenFieldPrefix the symbolic-map field prefix, the {@code (SCRNVAR2)} analogue; never
     *     {@code null}
     * @param mapName the map name, the {@code (MAPNAME3)} analogue; never {@code null}
     * @return the highlight to apply; never {@code null}
     * @throws NullPointerException if {@code screenFieldPrefix} or {@code mapName} is {@code null}
     */
    public static FieldHighlight resolveFromFlags(boolean notOk, boolean blank, boolean reenter,
            String screenFieldPrefix, String mapName) {
        return resolve(FieldValidationState.of(notOk, blank), reenter, screenFieldPrefix, mapName);
    }

    /**
     * Decides the highlight from the two raw {@code 88}-level outcomes without recording any identity.
     *
     * @param notOk {@code true} when {@code FLG-<field>-NOT-OK} holds
     * @param blank {@code true} when {@code FLG-<field>-BLANK} holds
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds
     * @return the highlight to apply; never {@code null}
     */
    public static FieldHighlight resolveFromFlags(boolean notOk, boolean blank, boolean reenter) {
        return resolveFromFlags(notOk, blank, reenter, UNNAMED, UNNAMED);
    }

    /**
     * The {@code (TESTVAR1)} token as a Java vocabulary: the validation outcome of one screen field,
     * expressed as the three states {@code CSSETATY} can distinguish.
     *
     * <p>For example {@code app/cbl/COACTUPC.cbl:L192-L195}: A single byte cannot be {@code '0'} and
     * {@code 'B'} at once, so at run time at most one of the two conditions holds and a three-valued
     * vocabulary is the faithful model rather than a simplification.
     */
    public enum FieldValidationState {
        /**
         * Neither {@code FLG-<field>-NOT-OK} nor {@code FLG-<field>-BLANK} holds, so the outer test at
         * {@code app/cpy/CSSETATY.cpy:L18-L19} fails and the field is left completely untouched.
         */
        OK,

        /**
         * {@code FLG-<field>-NOT-OK} holds: the field was supplied but failed validation.
         */
        NOT_OK,

        /**
         * {@code FLG-<field>-BLANK} holds: the field was left empty.
         */
        BLANK;

        /**
         * Whether {@code FLG-<field>-NOT-OK} holds - the first operand of the outer test at
         * {@code app/cpy/CSSETATY.cpy:L18}.
         *
         * @return {@code true} for {@link #NOT_OK} only
         */
        public boolean notOk() {
            return this == NOT_OK;
        }

        /**
         * Whether {@code FLG-<field>-BLANK} holds - the second operand of the outer test at
         * {@code app/cpy/CSSETATY.cpy:L19}, and the whole of the inner test at {@code L23}.
         *
         * @return {@code true} for {@link #BLANK} only
         */
        public boolean blank() {
            return this == BLANK;
        }

        /**
         * Collapses the two raw {@code 88}-level outcomes into one state.
         *
         * @param notOk {@code true} when {@code FLG-<field>-NOT-OK} holds
         * @param blank {@code true} when {@code FLG-<field>-BLANK} holds
         * @return {@link #BLANK} when {@code blank}; otherwise {@link #NOT_OK} when {@code notOk};
         *     otherwise {@link #OK}
         */
        public static FieldValidationState of(boolean notOk, boolean blank) {
            if (blank) {
                return BLANK;
            }
            if (notOk) {
                return NOT_OK;
            }
            return OK;
        }
    }

    /**
     * What {@code CSSETATY} would have moved, expressed as an immutable value instead of an in-place
     * mutation of the caller's symbolic map.
     *
     * <p>The asterisk move sits inside the colour move's {@code IF} ({@code app/cpy/CSSETATY.cpy:L23-L26}),
     * so an assigned output item without an assigned colour item is unreachable in the source and is
     * rejected by the constructor.
     *
     * @param colourItemAssigned whether {@code DFHRED} is moved into the {@code (SCRNVAR2)C} colour item,
     *     per {@code app/cpy/CSSETATY.cpy:L21-L22}
     * @param outputItemAssigned whether the asterisk is moved into the {@code (SCRNVAR2)O} output data
     *     item, per {@code app/cpy/CSSETATY.cpy:L24-L25}; can only be {@code true} when
     *     {@code colourItemAssigned} is
     * @param screenFieldPrefix the {@code (SCRNVAR2)} token this decision was made for, for example
     *     {@code "ACSTTUS"}; never {@code null}, and the empty string when the caller supplied no identity
     * @param mapName the {@code (MAPNAME3)} token this decision was made for, for example the
     *     seven-character {@code "CACTUPA"}; never {@code null}, and the empty string when the caller supplied
     *     no identity
     */
    public record FieldHighlight(boolean colourItemAssigned, boolean outputItemAssigned,
            String screenFieldPrefix, String mapName) {
        /**
         * Validates the two identity strings and the nesting invariant the copybook implies.
         */
        public FieldHighlight {
            Objects.requireNonNull(screenFieldPrefix, "screenFieldPrefix must not be null");
            Objects.requireNonNull(mapName, "mapName must not be null");

            if (outputItemAssigned && !colourItemAssigned) {
                throw new IllegalArgumentException("CSSETATY nests the '*' move inside the DFHRED "
                        + "move (CSSETATY.cpy:L23-L26), so the output item cannot be assigned "
                        + "while the colour item is not");
            }
        }

        /**
         * A decision to change nothing: the outer test at {@code app/cpy/CSSETATY.cpy:L18-L20} failed, so
         * the field keeps the colour and the content the program already gave it.
         *
         * @param screenFieldPrefix the {@code (SCRNVAR2)} token; never {@code null}
         * @param mapName the {@code (MAPNAME3)} token; never {@code null}
         * @return a highlight with neither item assigned; never {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public static FieldHighlight none(String screenFieldPrefix, String mapName) {
            return new FieldHighlight(false, false, screenFieldPrefix, mapName);
        }

        /**
         * The colour to move into the {@code (SCRNVAR2)C} item: always {@link BmsAttributes#DFHRED}, the
         * extended-colour byte {@code 0xF2}.
         *
         * @return {@link BmsAttributes#DFHRED}
         * @throws IllegalStateException if the colour item is not being assigned, because the COBOL moves
         *     nothing in that case and there is no value to report
         */
        public byte colourItemValue() {
            if (!colourItemAssigned) {
                throw new IllegalStateException(
                        "the colour item is not assigned; check colourItemAssigned() first");
            }
            return BmsAttributes.DFHRED;
        }

        /**
         * The text to move into the {@code (SCRNVAR2)O} item: always {@link #ASTERISK}, a string of length
         * one.
         *
         * @return {@link #ASTERISK}
         * @throws IllegalStateException if the output item is not being assigned, because the COBOL moves
         *     nothing in that case and there is no value to report
         */
        public String outputItemValue() {
            if (!outputItemAssigned) {
                throw new IllegalStateException(
                        "the output item is not assigned; check outputItemAssigned() first");
            }
            return ASTERISK;
        }

        /**
         * Whether this decision leaves the field entirely alone.
         *
         * @return {@code true} when neither item is assigned
         */
        public boolean untouched() {
            return !colourItemAssigned;
        }

        /**
         * The name of the colour item this decision targets: the field prefix followed by
         * {@link #COLOUR_ITEM_SUFFIX}, for example {@code "ACSTTUSC"}.
         *
         * @return the colour item name, or the empty string when no field prefix was supplied
         */
        public String colourItemName() {
            if (screenFieldPrefix.isEmpty()) {
                return UNNAMED;
            }
            return screenFieldPrefix + COLOUR_ITEM_SUFFIX;
        }

        /**
         * The name of the output data item this decision targets: the field prefix followed by
         * {@link #OUTPUT_ITEM_SUFFIX}, for example {@code "ACSTTUSO"}.
         *
         * @return the output item name, or the empty string when no field prefix was supplied
         */
        public String outputItemName() {
            if (screenFieldPrefix.isEmpty()) {
                return UNNAMED;
            }
            return screenFieldPrefix + OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The name of the output symbolic-map group both items are qualified by: the map name followed by
         * {@link #OUTPUT_MAP_SUFFIX}, for example {@code "CACTUPAO"} for the seven-character map
         * {@code CACTUPA}.
         *
         * @return the output map group name, or the empty string when no map name was supplied
         */
        public String outputMapGroupName() {
            if (mapName.isEmpty()) {
                return UNNAMED;
            }
            return mapName + OUTPUT_MAP_SUFFIX;
        }

        /**
         * Renders this decision as the COBOL {@code MOVE} statements it stands for, for logging and for
         * tracing a parity difference back to the copybook line that produced it.
         *
         * @return a human-readable rendering; never {@code null} and never empty
         */
        public String describe() {
            if (untouched()) {
                return "no change (CSSETATY outer IF not taken)";
            }

            String colourItem = screenFieldPrefix.isEmpty()
                    ? "(SCRNVAR2)" + COLOUR_ITEM_SUFFIX
                    : colourItemName();
            String outputItem = screenFieldPrefix.isEmpty()
                    ? "(SCRNVAR2)" + OUTPUT_ITEM_SUFFIX
                    : outputItemName();
            String group = mapName.isEmpty()
                    ? "(MAPNAME3)" + OUTPUT_MAP_SUFFIX
                    : outputMapGroupName();

            StringBuilder rendered = new StringBuilder(96)
                    .append("MOVE DFHRED TO ")
                    .append(colourItem)
                    .append(" OF ")
                    .append(group);

            if (outputItemAssigned) {
                rendered.append("; MOVE '")
                        .append(ASTERISK)
                        .append("' TO ")
                        .append(outputItem)
                        .append(" OF ")
                        .append(group);
            }

            return rendered.toString();
        }
    }
}
