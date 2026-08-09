package com.vsergeychik.carddemo.common;

import java.util.List;

/**
 * The one name every online route uses for the query parameter that carries {@code EIBAID}, and the
 * rule for reading it.
 *
 * <h2>Why this class exists</h2>
 * {@code EIBAID} is one byte of the CICS exec interface block, and all 17 online programs read it:
 * every screen's first decision is which key the operator pressed. A raw byte cannot travel in a query
 * string, so the byte arrives as its unsigned integer value and each controller narrows it. That much
 * was always shared. The <em>name</em> of the parameter was not: five controllers each spelled it for
 * themselves, three as {@code eibaid} and two as {@code eibAid}, and Spring MVC discards a query
 * parameter no handler declares. A caller that used a sibling screen's spelling therefore had its key
 * <strong>silently ignored</strong> and its request executed as {@link CicsAid#DFHENTER} - so a
 * {@code PF3} exit became a validation screen with nothing in the response saying why. On
 * {@code GET /api/cards/{cardNum}} that was the whole of it, because that screen's request record
 * declares no {@code CCARD-AID} member and the query parameter is its only channel for the key.
 *
 * <p>The name is therefore stated once, here, and every route binds the same pair: the canonical
 * {@link #CANONICAL_NAME} and the {@link #ALTERNATE_NAME} spelling that was already in service on two
 * routes and stays accepted so no existing caller is broken. There is nothing to remember per screen
 * and nothing to get wrong on the next screen: the surface is uniform by construction, and
 * {@code AidRequestParameterTest} asserts that across every handler that binds it.
 *
 * <h2>What this class deliberately does not do</h2>
 * <strong>It does not judge the AID value.</strong> Whether a byte names a key this screen handles is
 * the program's decision and stays in the program: {@code app/cbl/COCRDSLC.cbl:299-308} silently
 * <em>coerces</em> an unrecognised attention identifier to {@code ENTER} rather than rejecting it, and
 * {@code app/cbl/COCRDLIC.cbl:378-380} does the same, so refusing an unknown key here would invent a
 * failure mode the legacy screen cannot produce. This class resolves which of two spellings carried the
 * value and hands the value on unexamined; range narrowing and the per-screen coercion happen exactly
 * where they happened before.
 *
 * <p>It also holds no state and is never instantiated (practice B9, gate G53), and it names no
 * framework type - {@code @RequestParam} stays on the handler where a reader looking for the route's
 * contract will find it.
 */
public final class AidRequestParameter {

    /**
     * The canonical name of the query parameter carrying the raw {@code EIBAID} byte: {@value}.
     *
     * <p>All lower case, which is the form three of the five routes already required and therefore the
     * one that leaves the fewest callers to migrate. It matches {@code eibcalen}, the other exec
     * interface block value this module accepts as a query parameter, so the two read alike.
     */
    public static final String CANONICAL_NAME = "eibaid";

    /**
     * The alternate spelling that stays accepted on every route: {@value}.
     *
     * <p>It was the declared name on {@code GET /api/cards/{cardNum}} and {@code POST /api/users}
     * before the surface was made uniform. Dropping it would have turned a working call on those two
     * routes into a silently ignored key - the very failure this class removes - so it is honoured
     * everywhere instead of nowhere.
     */
    public static final String ALTERNATE_NAME = "eibAid";

    /**
     * Both accepted names, canonical first.
     *
     * <p>Immutable, so it is a shared constant rather than mutable static state (gate G53). It exists
     * so a test can assert the surface is uniform without restating the two names, and so a future
     * screen has one list to bind rather than a precedent to copy.
     */
    public static final List<String> ACCEPTED_NAMES = List.of(CANONICAL_NAME, ALTERNATE_NAME);

    /**
     * Not instantiable: this class is a name and a rule, not a collaborator.
     */
    private AidRequestParameter() {
        throw new AssertionError("AidRequestParameter is a constant holder and is never instantiated");
    }

    /**
     * Folds the two accepted spellings into the single value the caller stated.
     *
     * <p>Four outcomes, and each one is a decision rather than a fallback:
     *
     * <ol>
     *   <li>Neither name present - {@code null}, which every caller already reads as "the request named
     *       no key" and answers with {@link CicsAid#DFHENTER} or with the payload's own
     *       {@code CCARD-AID} token, exactly as it did before.</li>
     *   <li>One name present - that value, whichever of the two names carried it. This is the case the
     *       whole class exists for.</li>
     *   <li>Both present and equal - that value. A client that sends both spellings of one key has
     *       stated one thing twice, which is not a contradiction.</li>
     *   <li>Both present and different - refused. There is no faithful answer: a terminal presents one
     *       attention identifier, not two, so picking either would send the request down a branch the
     *       caller did not unambiguously ask for. The in-module precedent is {@code eibcalen}, where a
     *       stated length that contradicts the carrier is refused rather than believed.</li>
     * </ol>
     *
     * <p>The value itself is not examined here - see the class documentation. Both arguments are
     * {@link Integer} rather than {@code int} because absence is meaningful and is what
     * {@code required = false} delivers.
     *
     * @param canonical the value bound from {@link #CANONICAL_NAME}, or {@code null} if it was absent
     * @param alternate the value bound from {@link #ALTERNATE_NAME}, or {@code null} if it was absent
     * @return the single value the request stated, or {@code null} when it named no key
     * @throws IllegalArgumentException if both names are present and carry different values
     */
    public static Integer resolve(final Integer canonical, final Integer alternate) {
        if (canonical == null) {
            return alternate;
        }
        if (alternate == null || alternate.equals(canonical)) {
            return canonical;
        }
        throw new IllegalArgumentException("The " + CANONICAL_NAME + " and " + ALTERNATE_NAME
                + " parameters are two spellings of one EIBAID byte, but this request gave them "
                + "different values (" + canonical + " and " + alternate + "). A terminal presents one "
                + "attention identifier, so send one spelling - or the same value in both.");
    }
}
