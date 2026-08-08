package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * The error-highlight rule of {@code app/cpy/CSSETATY.cpy}: turn a screen field red when it failed
 * validation or was left empty, and additionally stamp an asterisk into it when it was empty -
 * but only when the program is being re-entered.
 *
 * <h2>The source, in full</h2>
 * {@code CSSETATY.cpy} is thirty-one lines of which eleven are executable. Those eleven lines,
 * verbatim from {@code app/cpy/CSSETATY.cpy:L17-L27}, are the entire specification of this class:
 *
 * <pre>{@code
 * *    Set (TESTVAR1) to red if in error and * if blankACSHLIM
 *      IF (FLG-(TESTVAR1)-NOT-OK
 *      OR  FLG-(TESTVAR1)-BLANK)
 *      AND CDEMO-PGM-REENTER
 *          MOVE DFHRED             TO
 *               (SCRNVAR2)C OF (MAPNAME3)O
 *          IF  FLG-(TESTVAR1)-BLANK
 *              MOVE '*'            TO
 *               (SCRNVAR2)O OF (MAPNAME3)O
 *          END-IF
 *      END-IF
 * }</pre>
 *
 * <h2>Thirty-nine textual expansions become one method</h2>
 * The fragment is not a paragraph but a {@code COPY REPLACING} template - COBOL's macro facility -
 * carrying three placeholder tokens:
 * <ul>
 *   <li><strong>{@code (TESTVAR1)}</strong> is a <em>validation-flag name stem</em>. Substituting
 *       {@code ACCT-STATUS} forms the condition names {@code FLG-ACCT-STATUS-NOT-OK} and
 *       {@code FLG-ACCT-STATUS-BLANK}. Its Java counterpart is {@link FieldValidationState}.</li>
 *   <li><strong>{@code (SCRNVAR2)}</strong> is a <em>symbolic-map field prefix</em>. Substituting
 *       {@code ACSTTUS} forms {@code ACSTTUSC}, the colour item, and {@code ACSTTUSO}, the output
 *       data item. Its Java counterpart is {@link FieldHighlight#screenFieldPrefix()}.</li>
 *   <li><strong>{@code (MAPNAME3)}</strong> is a <em>map name</em>. Substituting {@code CACTUPA}
 *       forms the output symbolic-map group {@code CACTUPAO}. Its Java counterpart is
 *       {@link FieldHighlight#mapName()}.</li>
 * </ul>
 *
 * <p>{@code app/cbl/COACTUPC.cbl} is the <strong>sole</strong> COBOL consumer, and it expands the
 * template <strong>39</strong> times - verified by {@code grep -c CSSETATY app/cbl/COACTUPC.cbl}
 * returning 39, and {@code grep -rl CSSETATY app/cbl} listing that one file. A representative site,
 * {@code app/cbl/COACTUPC.cbl:L3208-L3211}:
 *
 * <pre>{@code
 * COPY CSSETATY REPLACING
 *   ==(TESTVAR1)== BY ==ACCT-STATUS==
 *   ==(SCRNVAR2)== BY ==ACSTTUS==
 *   ==(MAPNAME3)== BY ==CACTUPA== .
 * }</pre>
 *
 * The sites that follow substitute {@code OPEN-YEAR}/{@code OPNYEAR},
 * {@code OPEN-MONTH}/{@code OPNMON}, {@code OPEN-DAY}/{@code OPNDAY},
 * {@code CRED-LIMIT}/{@code ACRDLIM}, {@code CASH-CREDIT-LIMIT}/{@code ACSHLIM} and so on, every one
 * of them with {@code (MAPNAME3)} = {@code CACTUPA}. Because the tokens become parameters here, the
 * thirty-nine textual copies collapse into the one decision method below. That is the single
 * structural improvement this file makes, and it is deliberate: the <em>behaviour</em> stays
 * byte-identical while the duplication disappears, which is exactly the resolution the migration
 * plan prescribes for the "JOBOL" anti-pattern - refactor readability, hold semantics invariant.
 *
 * <h2>The nesting is the specification</h2>
 * Two levels of {@code IF} produce three - not four - distinguishable field appearances:
 * untouched, red, and red-with-an-asterisk. The asterisk is gated by re-entry <em>because it is
 * nested inside the outer test</em>, not because of any condition of its own. Flattening the two
 * levels into a single condition would give a blank field an asterisk on first entry, a visible
 * change on every screen {@code COACTUPC} paints. The two levels are therefore preserved as two
 * levels in {@link #resolve(FieldValidationState, boolean, String, String)}, and the invariant they
 * imply - an assigned output item always accompanies an assigned colour item, never the reverse -
 * is enforced by {@link FieldHighlight}'s constructor so that a flattened re-implementation fails
 * loudly instead of silently.
 *
 * <h2>The truth table, reproduced exactly</h2>
 * All three inputs matter, and the two rows most easily got wrong are the blank-field rows under
 * first entry: they highlight <em>nothing at all</em>.
 *
 * <table border="1">
 *   <caption>{@code CSSETATY} outcomes for every combination of the three inputs</caption>
 *   <tr><th>{@code NOT-OK}</th><th>{@code BLANK}</th><th>{@code REENTER}</th>
 *       <th>colour item &#8592; {@code DFHRED}</th><th>output item &#8592; {@code '*'}</th></tr>
 *   <tr><td>false</td><td>false</td><td>false</td><td>no</td><td>no</td></tr>
 *   <tr><td>false</td><td>false</td><td>true</td><td>no</td><td>no</td></tr>
 *   <tr><td>true</td><td>false</td><td>false</td><td>no</td><td>no</td></tr>
 *   <tr><td>true</td><td>false</td><td>true</td><td><strong>yes</strong></td><td>no</td></tr>
 *   <tr><td>false</td><td>true</td><td>false</td><td>no</td><td>no</td></tr>
 *   <tr><td>false</td><td>true</td><td>true</td><td><strong>yes</strong></td>
 *       <td><strong>yes</strong></td></tr>
 *   <tr><td>true</td><td>true</td><td>false</td><td>no</td><td>no</td></tr>
 *   <tr><td>true</td><td>true</td><td>true</td><td><strong>yes</strong></td>
 *       <td><strong>yes</strong></td></tr>
 * </table>
 *
 * <p>The highlight applies <strong>only</strong> in re-entered state. That is gate <strong>G38</strong>
 * of the migration plan stated as a single sentence, and it falls straight out of
 * {@code CSSETATY.cpy:L20}.
 *
 * <h2>Two different target items, one letter apart</h2>
 * {@code DFHRED} is moved into {@code (SCRNVAR2)C} - the field's <strong>colour</strong> item - and
 * the asterisk into {@code (SCRNVAR2)O} - the field's <strong>output data</strong> item. Both are
 * qualified {@code OF (MAPNAME3)O}, the output symbolic-map group. Only a single suffix letter
 * distinguishes the two destinations in the COBOL, so the distinction is encoded in the Java
 * <em>types</em> here: {@link FieldHighlight#colourItemValue()} returns a {@code byte} and
 * {@link FieldHighlight#outputItemValue()} returns a {@code String}. Transposing them is a compile
 * error rather than a silently wrong screen.
 *
 * <p>Note also that the {@code O} in {@code (SCRNVAR2)O} and the {@code O} in {@code (MAPNAME3)O}
 * are the same letter meaning two different things - a symbolic-map <em>data</em> item suffix and a
 * map <em>direction</em> suffix. Both are named separately below, as
 * {@link #OUTPUT_ITEM_SUFFIX} and {@link #OUTPUT_MAP_SUFFIX}, precisely so the coincidence cannot be
 * mistaken for a single concept.
 *
 * <p>{@code (MAPNAME3)} is a seven-character map name: {@code CACTUPA}, whose output group is the
 * eight-character {@code CACTUPAO}. That independently corroborates why
 * {@code CDEMO-LAST-MAP PIC X(7)} in {@code app/cpy/COCOM01Y.cpy:L43} is seven and not eight bytes
 * wide - the eighth character is the {@code I}/{@code O} direction suffix, which is never stored.
 *
 * <h2>Re-entry arrives as a parameter, never from ambient state</h2>
 * {@code CDEMO-PGM-REENTER} is the {@code 88}-level on {@code CDEMO-PGM-CONTEXT PIC 9(01)}, true
 * when the value is {@code 1} and false when it is {@code 0}
 * ({@code app/cpy/COCOM01Y.cpy:L29-L31}). This class nonetheless takes it as a plain
 * {@code boolean} argument and deliberately does <strong>not</strong> reference the navigation
 * context type: the CICS conversation is pseudo-conversational, so no server-side session state
 * exists to read (rule R6, gate G37), and an explicit argument makes the table above trivially
 * testable. For the same reason nothing here is a Spring bean and no framework type is imported.
 *
 * <h2>It decides; it does not set</h2>
 * The class name comes from the migration plan, the behaviour from the source - and the two diverge
 * here. The COBOL fragment <em>mutates</em> the caller's symbolic map in place. This class instead
 * returns a {@link FieldHighlight} describing what to change, and the code that owns the symbolic-map
 * DTO applies it. Three reasons, all binding:
 * <ol>
 *   <li>The symbolic-map DTOs live in the domain packages ({@code account.dto}, {@code card.dto},
 *       {@code user.dto} and so on). Importing one from {@code common} would invert the dependency
 *       graph, so this class must not know their types.</li>
 *   <li>A pure function has no state to leak between requests, which the "no static mutable state"
 *       practice requires (gate G53). Every member below is either an immutable constant, an
 *       immutable nested type, or a static method with no side effect.</li>
 *   <li>The alternative - a reflective or {@code Map}-keyed field bag - would hide exactly the byte
 *       mapping that parity verification depends on, and is rejected on principle.</li>
 * </ol>
 *
 * <h2>Observations recorded rather than corrected</h2>
 * <ul>
 *   <li>The comment on {@code app/cpy/CSSETATY.cpy:L17} ends with a stray {@code ACSHLIM} fragment:
 *       it reads {@code * Set (TESTVAR1) to red if in error and * if blankACSHLIM}, with no space
 *       before the fragment. {@code ACSHLIM} is the {@code (SCRNVAR2)} value used for the Cash
 *       Credit Limit site at {@code app/cbl/COACTUPC.cbl:L3258}, so this is a leftover from an
 *       editing session that ran past the comment text. It carries no meaning and is <strong>not</strong>
 *       corrected: the copybook is a read-only parity oracle, and the note exists here so nobody
 *       later reads significance into it or "tidies" the source.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:L3198-L3205} holds a commented-out hand-written expansion of
 *       this same rule for {@code ACCT-STATUS}, immediately above the live {@code COPY} at
 *       {@code L3208}. Dead COBOL, left dead.</li>
 *   <li>The two {@code 88}-levels are mutually exclusive in the source data model, which is why
 *       {@link FieldValidationState} has three values rather than two independent flags. The
 *       evidence and the both-flags-set fallback are documented on that enum.</li>
 * </ul>
 *
 * @see BmsAttributes#DFHRED
 * @see FieldValidationState
 * @see FieldHighlight
 */
public final class FieldAttributeSetter {

    /**
     * The blank-field marker moved into the output data item: {@code MOVE '*'} at
     * {@code app/cpy/CSSETATY.cpy:L24}.
     *
     * <p>It is text, not an attribute byte, because it lands in a {@code PIC X} data item rather
     * than in the colour plane. Encoding it to the wire charset is the fixed-width codec's job, not
     * this class's, so it is held as a Java {@code String} of length one and no charset is consulted
     * here.
     */
    public static final String ASTERISK = "*";

    /**
     * The suffix that turns a symbolic-map field prefix into its <strong>colour</strong> item:
     * {@code (SCRNVAR2)C} at {@code app/cpy/CSSETATY.cpy:L22}. This is the item {@code DFHRED} is
     * moved into.
     */
    public static final String COLOUR_ITEM_SUFFIX = "C";

    /**
     * The suffix that turns a symbolic-map field prefix into its <strong>output data</strong> item:
     * {@code (SCRNVAR2)O} at {@code app/cpy/CSSETATY.cpy:L25}. This is the item the asterisk is
     * moved into.
     *
     * <p>Identical in value to {@link #OUTPUT_MAP_SUFFIX} and unrelated in meaning; see the class
     * documentation.
     */
    public static final String OUTPUT_ITEM_SUFFIX = "O";

    /**
     * The suffix that turns a map name into its <strong>output direction</strong> group:
     * {@code OF (MAPNAME3)O} at {@code app/cpy/CSSETATY.cpy:L22} and {@code L25}, which for
     * {@code CACTUPA} yields {@code CACTUPAO}.
     *
     * <p>Identical in value to {@link #OUTPUT_ITEM_SUFFIX} and unrelated in meaning; see the class
     * documentation.
     */
    public static final String OUTPUT_MAP_SUFFIX = "O";

    /**
     * The value the diagnostic identity parameters take when a caller does not supply them: the
     * empty string, meaning "unknown", never "blank field name".
     */
    private static final String UNNAMED = "";

    /**
     * Not instantiable: this type is a stateless holder of one pure decision and a pair of immutable
     * nested types. An instance would carry no meaning.
     *
     * @throws AssertionError always, so reflective instantiation fails loudly rather than silently
     *                        producing a useless instance
     */
    private FieldAttributeSetter() {
        throw new AssertionError("FieldAttributeSetter is a stateless helper and must not be instantiated");
    }

    /**
     * Decides the highlight for one screen field, carrying the field's and map's identity for
     * diagnostics. This is the <strong>only</strong> place the {@code CSSETATY} decision is written;
     * every other entry point on this class delegates here.
     *
     * <p>The body reproduces {@code app/cpy/CSSETATY.cpy:L18-L27} as the two nested levels the
     * copybook has, in the copybook's operand order. The two identity arguments are recorded on the
     * result and <strong>never</strong> consulted by the decision - passing different names cannot
     * change the outcome.
     *
     * @param state             the field's validation state, the {@code (TESTVAR1)} analogue; never
     *                          {@code null}
     * @param reenter           {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *                          {@code CDEMO-PGM-CONTEXT} is {@code 1}; {@code false} on first entry
     * @param screenFieldPrefix the symbolic-map field prefix, the {@code (SCRNVAR2)} analogue, for
     *                          example {@code "ACSTTUS"}; never {@code null}, and the empty string
     *                          when unknown
     * @param mapName           the map name, the {@code (MAPNAME3)} analogue, for example
     *                          {@code "CACTUPA"}; never {@code null}, and the empty string when
     *                          unknown
     * @return the highlight to apply; never {@code null}
     * @throws NullPointerException if {@code state}, {@code screenFieldPrefix} or {@code mapName} is
     *                              {@code null}
     */
    public static FieldHighlight resolve(FieldValidationState state, boolean reenter,
            String screenFieldPrefix, String mapName) {

        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(screenFieldPrefix, "screenFieldPrefix must not be null");
        Objects.requireNonNull(mapName, "mapName must not be null");

        // Outer level - CSSETATY.cpy:L18-L20, in the copybook's own operand order:
        //     IF (FLG-(TESTVAR1)-NOT-OK
        //     OR  FLG-(TESTVAR1)-BLANK)
        //     AND CDEMO-PGM-REENTER
        if ((state.notOk() || state.blank()) && reenter) {

            // Outer action - CSSETATY.cpy:L21-L22, applied unconditionally once the outer test
            // passes and independently of the inner test that follows:
            //     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
            boolean assignColourItem = true;
            boolean assignOutputItem = false;

            // Inner level - CSSETATY.cpy:L23, a refinement of the outer test, not a replacement
            // for it:
            //     IF FLG-(TESTVAR1)-BLANK
            if (state.blank()) {

                // Inner action - CSSETATY.cpy:L24-L25:
                //     MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
                assignOutputItem = true;
            }

            return new FieldHighlight(assignColourItem, assignOutputItem, screenFieldPrefix, mapName);
        }

        // Outer test failed - CSSETATY.cpy:L27 END-IF. Neither item is touched: the field keeps
        // whatever colour and whatever content the program had already put there.
        return FieldHighlight.none(screenFieldPrefix, mapName);
    }

    /**
     * Decides the highlight for one screen field without recording any identity, for callers that
     * already know which field they are asking about.
     *
     * <p>Equivalent to {@link #resolve(FieldValidationState, boolean, String, String)} with both
     * identity arguments empty.
     *
     * @param state   the field's validation state, the {@code (TESTVAR1)} analogue; never
     *                {@code null}
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
     * <p>This entry point exists because {@code CSSETATY} tests two independent condition names, and
     * a caller holding those two booleans should not have to decide how to combine them. The
     * combination is defined once, by {@link FieldValidationState#of(boolean, boolean)}, and it
     * reproduces the class documentation's truth table for every one of the eight input
     * combinations - including the two rows where both flags are set.
     *
     * @param notOk             {@code true} when {@code FLG-<field>-NOT-OK} holds
     * @param blank             {@code true} when {@code FLG-<field>-BLANK} holds
     * @param reenter           {@code true} when {@code CDEMO-PGM-REENTER} holds
     * @param screenFieldPrefix the symbolic-map field prefix, the {@code (SCRNVAR2)} analogue; never
     *                          {@code null}
     * @param mapName           the map name, the {@code (MAPNAME3)} analogue; never {@code null}
     * @return the highlight to apply; never {@code null}
     * @throws NullPointerException if {@code screenFieldPrefix} or {@code mapName} is {@code null}
     */
    public static FieldHighlight resolveFromFlags(boolean notOk, boolean blank, boolean reenter,
            String screenFieldPrefix, String mapName) {
        return resolve(FieldValidationState.of(notOk, blank), reenter, screenFieldPrefix, mapName);
    }

    /**
     * Decides the highlight from the two raw {@code 88}-level outcomes without recording any
     * identity.
     *
     * <p>Equivalent to {@link #resolveFromFlags(boolean, boolean, boolean, String, String)} with both
     * identity arguments empty.
     *
     * @param notOk   {@code true} when {@code FLG-<field>-NOT-OK} holds
     * @param blank   {@code true} when {@code FLG-<field>-BLANK} holds
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
     * <h2>Why three values and not two flags</h2>
     * {@code CSSETATY} tests two condition names, {@code FLG-<field>-NOT-OK} and
     * {@code FLG-<field>-BLANK}, which looks like two independent booleans. In the source they are
     * not independent: each such pair is declared as a set of {@code 88}-levels over a
     * <em>single</em> {@code PIC X(1)} flag field, with mutually exclusive {@code VALUE} clauses. For
     * example {@code app/cbl/COACTUPC.cbl:L192-L195}:
     *
     * <pre>{@code
     * 10  WS-EDIT-ACCT-STATUS                 PIC  X(1).
     *     88  FLG-ACCT-STATUS-ISVALID         VALUES 'Y', 'N'.
     *     88  FLG-ACCT-STATUS-NOT-OK          VALUE '0'.
     *     88  FLG-ACCT-STATUS-BLANK           VALUE 'B'.
     * }</pre>
     *
     * A single byte cannot be {@code '0'} and {@code 'B'} at once, so at run time at most one of the
     * two conditions holds and a three-valued vocabulary is the faithful model rather than a
     * simplification. Callers that genuinely hold two separate booleans are still served, by
     * {@link #of(boolean, boolean)}.
     *
     * <h2>The flag byte is deliberately not decoded here</h2>
     * The encoding varies by field: the {@code WS-NON-KEY-FLAGS} group uses {@code LOW-VALUES} for
     * valid, {@code '0'} for not-ok and {@code 'B'} for blank, while
     * {@code app/cbl/COACTUPC.cbl:L183-L189} declares {@code WS-EDIT-ACCT-FLAG} and
     * {@code WS-EDIT-CUST-FLAG} with {@code '1'} for valid and a <em>space</em> for blank. There is
     * no single mapping from flag byte to state, so no {@code fromFlagByte} factory is offered:
     * inventing one would put a guess in the highlight path. Decoding belongs to the validation code
     * that owns the flag.
     */
    public enum FieldValidationState {

        /**
         * Neither {@code FLG-<field>-NOT-OK} nor {@code FLG-<field>-BLANK} holds, so the outer test
         * at {@code app/cpy/CSSETATY.cpy:L18-L19} fails and the field is left completely untouched.
         *
         * <p>This covers two distinct source situations that {@code CSSETATY} cannot tell apart and
         * therefore treats identically: the field passed validation
         * ({@code FLG-<field>-ISVALID} holds), and the flag was never set at all - the enclosing
         * group starts out as spaces, which for {@code WS-EDIT-ACCT-STATUS} matches none of its three
         * {@code 88}-levels. Both produce no highlight.
         */
        OK,

        /**
         * {@code FLG-<field>-NOT-OK} holds: the field was supplied but failed validation. Under
         * re-entry the field turns red and receives <strong>no</strong> asterisk, because the inner
         * test at {@code app/cpy/CSSETATY.cpy:L23} checks blankness only.
         */
        NOT_OK,

        /**
         * {@code FLG-<field>-BLANK} holds: the field was left empty. Under re-entry the field turns
         * red <em>and</em> receives the asterisk, because blankness satisfies both the outer test at
         * {@code app/cpy/CSSETATY.cpy:L18-L19} and the inner test at {@code L23}.
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
         * <p>Blankness dominates, and the reason is the copybook's shape rather than a preference:
         * {@code FLG-<field>-BLANK} satisfies the outer test at {@code L18-L19} <em>and</em> the
         * inner test at {@code L23}, so a caller reporting both flags set observes exactly the
         * outcome the COBOL would produce if both condition names were somehow true - the colour
         * move from the outer level plus the asterisk move from the inner level. This is what makes
         * the both-flags-set rows of the class documentation's truth table come out right.
         *
         * @param notOk {@code true} when {@code FLG-<field>-NOT-OK} holds
         * @param blank {@code true} when {@code FLG-<field>-BLANK} holds
         * @return {@link #BLANK} when {@code blank}; otherwise {@link #NOT_OK} when {@code notOk};
         *         otherwise {@link #OK}
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
     * <h2>Why the moved values are derived rather than stored</h2>
     * The copybook moves two <em>literals</em>: {@code DFHRED} and {@code '*'}. There is consequently
     * no third possible value to record, and no "no value" sentinel is needed or wanted - notably
     * {@code BmsAttributes#DFHDFCOL} could not serve as one, because moving it would be a real
     * assignment meaning "reset this field to the default colour", which is not what the COBOL does
     * when the test fails. Instead this record stores only the two decisions, and
     * {@link #colourItemValue()} and {@link #outputItemValue()} derive the literals, refusing to
     * answer when the corresponding item is not being assigned. Asking for a value that is not being
     * moved is a programming error, and it is reported as one.
     *
     * <h2>The nesting invariant, enforced</h2>
     * The asterisk move sits <em>inside</em> the colour move's {@code IF}
     * ({@code app/cpy/CSSETATY.cpy:L23-L26}), so an assigned output item without an assigned colour
     * item is unreachable in the source and is rejected by the constructor. A flattened
     * re-implementation of the rule therefore fails loudly here rather than quietly painting an
     * asterisk on a field that was never turned red.
     *
     * <h2>Applying it</h2>
     * The caller owns the symbolic-map DTO and performs the two moves itself, which keeps
     * {@code common} free of any dependency on a domain package:
     *
     * <pre>{@code
     * FieldHighlight highlight = FieldAttributeSetter.resolve(state, reenter, "ACSTTUS", "CACTUPA");
     * if (highlight.colourItemAssigned()) {
     *     response.setAcsttusColour(highlight.colourItemValue());   // ACSTTUSC OF CACTUPAO
     * }
     * if (highlight.outputItemAssigned()) {
     *     response.setAcsttus(highlight.outputItemValue());         // ACSTTUSO OF CACTUPAO
     * }
     * }</pre>
     *
     * @param colourItemAssigned whether {@code DFHRED} is moved into the {@code (SCRNVAR2)C} colour
     *                           item, per {@code app/cpy/CSSETATY.cpy:L21-L22}
     * @param outputItemAssigned whether the asterisk is moved into the {@code (SCRNVAR2)O} output
     *                           data item, per {@code app/cpy/CSSETATY.cpy:L24-L25}; can only be
     *                           {@code true} when {@code colourItemAssigned} is
     * @param screenFieldPrefix  the {@code (SCRNVAR2)} token this decision was made for, for example
     *                           {@code "ACSTTUS"}; never {@code null}, and the empty string when the
     *                           caller supplied no identity
     * @param mapName            the {@code (MAPNAME3)} token this decision was made for, for example
     *                           the seven-character {@code "CACTUPA"}; never {@code null}, and the
     *                           empty string when the caller supplied no identity
     */
    public record FieldHighlight(boolean colourItemAssigned, boolean outputItemAssigned,
            String screenFieldPrefix, String mapName) {

        /**
         * Validates the two identity strings and the nesting invariant the copybook implies.
         *
         * @throws NullPointerException     if {@code screenFieldPrefix} or {@code mapName} is
         *                                  {@code null}
         * @throws IllegalArgumentException if the output item is assigned while the colour item is
         *                                  not, a combination {@code CSSETATY}'s nesting makes
         *                                  unreachable
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
         * A decision to change nothing: the outer test at {@code app/cpy/CSSETATY.cpy:L18-L20}
         * failed, so the field keeps the colour and the content the program already gave it.
         *
         * @param screenFieldPrefix the {@code (SCRNVAR2)} token; never {@code null}
         * @param mapName           the {@code (MAPNAME3)} token; never {@code null}
         * @return a highlight with neither item assigned; never {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public static FieldHighlight none(String screenFieldPrefix, String mapName) {
            return new FieldHighlight(false, false, screenFieldPrefix, mapName);
        }

        /**
         * The colour to move into the {@code (SCRNVAR2)C} item: always
         * {@link BmsAttributes#DFHRED}, the extended-colour byte {@code 0xF2}.
         *
         * <p>Returns a {@code byte} rather than text because a BMS colour is a 3270 attribute byte,
         * not a character. That also makes it impossible to move this value into the output data item
         * by mistake, since {@link #outputItemValue()} is typed {@code String}.
         *
         * @return {@link BmsAttributes#DFHRED}
         * @throws IllegalStateException if the colour item is not being assigned, because the COBOL
         *                               moves nothing in that case and there is no value to report
         */
        public byte colourItemValue() {
            if (!colourItemAssigned) {
                throw new IllegalStateException(
                        "the colour item is not assigned; check colourItemAssigned() first");
            }
            return BmsAttributes.DFHRED;
        }

        /**
         * The text to move into the {@code (SCRNVAR2)O} item: always {@link #ASTERISK}, a string of
         * length one.
         *
         * @return {@link #ASTERISK}
         * @throws IllegalStateException if the output item is not being assigned, because the COBOL
         *                               moves nothing in that case and there is no value to report
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
         * <p>By the nesting invariant enforced above this is exactly the negation of
         * {@link #colourItemAssigned()}; it exists so a caller can express "nothing to do" directly.
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
         * <p>Diagnostic only - nothing in the decision depends on it.
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
         * <p>Diagnostic only - nothing in the decision depends on it.
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
         * The name of the output symbolic-map group both items are qualified by: the map name
         * followed by {@link #OUTPUT_MAP_SUFFIX}, for example {@code "CACTUPAO"} for the
         * seven-character map {@code CACTUPA}.
         *
         * <p>Diagnostic only - nothing in the decision depends on it.
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
         * Renders this decision as the COBOL {@code MOVE} statements it stands for, for logging and
         * for tracing a parity difference back to the copybook line that produced it. When no
         * identity was supplied the copybook's own placeholder tokens are shown instead of concrete
         * item names.
         *
         * <p>Examples: {@code "MOVE DFHRED TO ACSTTUSC OF CACTUPAO; MOVE '*' TO ACSTTUSO OF
         * CACTUPAO"}, and {@code "no change (CSSETATY outer IF not taken)"}.
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
