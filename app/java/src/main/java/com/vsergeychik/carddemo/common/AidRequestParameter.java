package com.vsergeychik.carddemo.common;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 *
 * <h2>Why the byte, and not the token, is the channel that must exist</h2>
 * A screen's key can reach a controller two ways, and they are not equivalent. The raw {@code EIBAID}
 * byte distinguishes all twenty-eight attention identifiers CICS defines. The five-character
 * {@code CCARD-AID} token does not: {@code app/cpy/CSSTRPFY.cpy:54-77} folds {@code DFHPF13} through
 * {@code DFHPF24} back onto {@code 'PFK01'} through {@code 'PFK12'}, so {@code PF15} and {@code PF3}
 * arrive as the same five characters and no reader of the token can tell them apart again.
 *
 * <p>That fold is correct for the five programs that copy {@code CSSTRPFY} - {@code COACTUPC},
 * {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC} - and wrong for the twelve
 * that do not. {@code COUSR03C} tests {@code EIBAID} inline with {@code WHEN DFHPF3} and has no clause
 * for {@code DFHPF15}, so on the mainframe {@code PF15} reaches that program's {@code WHEN OTHER} and
 * paints "invalid key". A route that can only be told the token cannot reproduce that: it is handed
 * {@code 'PFK03'} and must take the PF3 exit. So the token alone is a <em>lossy</em> transport, and on a
 * program that never folded it is lossy in a way that changes which arm runs.
 *
 * <p>Every online route therefore accepts the byte, and where a payload also carries a token the byte
 * wins - see {@link #requireTokenAgreement(String, byte, String, FixedWidthCodec)} for what happens when
 * the two disagree. A caller that sends only a token keeps its existing behaviour exactly, so nothing
 * that worked before is changed by the byte becoming available.
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
     * The lowest value a stated {@code EIBAID} can take: {@value}.
     *
     * <p>{@code EIBAID} is one byte, and the parameter carries it in unsigned form because a query
     * string has no signed bytes. {@code 0} is {@link CicsAid#DFHNULL}, a real attention identifier, so
     * the bound is inclusive.
     */
    public static final int MIN_AID_VALUE = 0;

    /**
     * The highest value a stated {@code EIBAID} can take: {@value}.
     *
     * <p>Inclusive, and reached in practice: the AID constants run well above {@code 128} in EBCDIC -
     * {@code DFHPF24} is {@code 0xFC}, which is {@code 252}.
     */
    public static final int MAX_AID_VALUE = 255;

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

    /**
     * Narrows a stated parameter value to the one {@code EIBAID} byte it names.
     *
     * <p>{@code EIBAID} is a single byte of the exec interface block, so its stated value must lie in
     * {@code 0}-{@code 255}: those 256 values are every attention identifier a 3270 can present, and
     * nothing outside them is one. A value outside the range is <strong>refused</strong> rather than
     * narrowed, because {@code (byte) 259} is {@code 3} and would silently run the branch for a key the
     * terminal never presented - the one failure mode a lossless transport exists to remove.
     *
     * <p>Values {@code 128}-{@code 255} are accepted and are not a special case: they are how the
     * unsigned form of a byte Java models as negative arrives over a query string, and many real AIDs
     * live there - {@code DFHPF13} is {@code 0xC1}, which is {@code 193}. The narrowing is the plain
     * unsigned-to-signed reinterpretation, so {@code 193} becomes the byte {@code 0xC1} and compares
     * equal to {@link CicsAid#DFHPF13}.
     *
     * <p>This does <strong>not</strong> judge whether the byte names a key the screen handles: that stays
     * in the program, for the reason the class documentation gives.
     *
     * @param stated the value bound from one of {@link #ACCEPTED_NAMES}, already folded by
     *               {@link #resolve(Integer, Integer)}; must not be {@code null}
     * @return the raw EBCDIC attention-identifier byte the caller stated
     * @throws NullPointerException          if {@code stated} is {@code null} - absence is the caller's
     *                                       decision to make, not a value to narrow
     * @throws ScreenInputRejectedException if the value is outside {@code 0}-{@code 255}
     */
    public static byte requireAidByte(final Integer stated) {
        Objects.requireNonNull(stated, "A stated EIBAID value is required; test for absence before "
                + "narrowing, because an absent key is the caller's statement and not an error");
        if (stated < MIN_AID_VALUE || stated > MAX_AID_VALUE) {
            throw ScreenInputRejectedException.outsideRange(CANONICAL_NAME, "one EIBAID byte",
                    MIN_AID_VALUE, MAX_AID_VALUE);
        }
        return (byte) stated.intValue();
    }

    /**
     * Requires a payload's {@code CCARD-AID} token to name the same key as the raw byte, when it states
     * a key at all.
     *
     * <p>Called only when a request carried both channels. The byte is the statement that is acted on -
     * it is the lossless one - and this guard exists so that a payload naming a <em>different</em> key is
     * refused instead of quietly discarded. Discarding it would be the same class of fault as the lost
     * query parameter this class was created to fix: the caller states something, and the system acts as
     * though it had not.
     *
     * <p>Three kinds of token agree and are accepted, because on a terminal each means the operator's
     * key is stated elsewhere:
     *
     * <ul>
     *   <li>{@code null} - the payload has no token member, or left it unset.</li>
     *   <li>All spaces, at the token's declared {@code PIC X(5)} width - an unpainted field.</li>
     *   <li>All {@code LOW-VALUES} - a field a {@code RECEIVE MAP} left untouched.</li>
     *   <li>The raw one-character image of this very byte, {@link PfKeyResolver#aidImage(byte)} - the
     *       form the payload member itself documents as its carrier, so a request that states the byte
     *       in both channels states one key twice rather than two keys once.</li>
     * </ul>
     *
     * <p>Anything else is compared, at {@code PIC X(5)} and never by trimming, against the token
     * {@code CSSTRPFY} itself would store for this byte - {@link PfKeyResolver#resolve(byte)}, the
     * <em>folding</em> resolver. That is deliberate: the token is a value the copybook produces, so
     * {@code eibaid=DFHPF15} with {@code 'PFK03'} is one key stated twice consistently and is accepted,
     * with the byte still winning so a program that never folded can reach its {@code WHEN OTHER}. A
     * byte that {@code CSSTRPFY} has no clause for stores no token at all, so any token stated
     * alongside it disagrees.
     *
     * @param member        the payload member carrying the token, as the caller sent it; must not be
     *                      {@code null}
     * @param rawAid        the byte the request stated, already narrowed by
     *                      {@link #requireAidByte(Integer)}
     * @param suppliedToken the token the payload carried, or {@code null} if it carried none
     * @param codec         the codec the {@code PIC X(5)} comparison is made with; must not be
     *                      {@code null}
     * @throws NullPointerException          if {@code member} or {@code codec} is {@code null}
     * @throws ScreenInputRejectedException if the token names a different key from the byte
     */
    public static void requireTokenAgreement(final String member,
            final byte rawAid,
            final String suppliedToken,
            final FixedWidthCodec codec) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in the answer");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: a PIC X(5) comparison is made at "
                + "the declared width, never by trimming");
        if (suppliedToken == null) {
            return;
        }
        final String supplied = codec.movePicX(suppliedToken, PfKeyResolver.AID_TOKEN_LENGTH);
        if (statesNoKey(supplied)) {
            return;
        }
        if (supplied.equals(codec.movePicX(PfKeyResolver.aidImage(rawAid),
                PfKeyResolver.AID_TOKEN_LENGTH))) {
            // The raw one-character image of this very byte - PfKeyResolver.aidImage(byte), which is the
            // form the payload member documents as its carrier. One key stated twice, consistently.
            return;
        }
        final Optional<PfKeyResolver.AidKey> stored = PfKeyResolver.resolve(rawAid);
        if (stored.isPresent() && supplied.equals(stored.get().token())) {
            return;
        }
        throw ScreenInputRejectedException.conflictingAid(member, CANONICAL_NAME);
    }

    /**
     * The whole rule for a request that stated the raw byte: narrow it, cross-check any token beside it,
     * and hand back the byte to act on.
     *
     * <p>The one call an online route makes when {@link #resolve(Integer, Integer)} returned a value, so
     * that the rule is written once here rather than six times across the controllers that need it. It
     * is {@link #requireAidByte(Integer)} followed by
     * {@link #requireTokenAgreement(String, byte, String, FixedWidthCodec)}, in that order - the range
     * is checked first because an out-of-range value names no key to compare a token against.
     *
     * <p>The caller decides what an <em>absent</em> parameter means and keeps its existing token decode
     * for that case, which is why absence is not handled here: on {@code COSGN00C} an absent key is
     * {@link CicsAid#DFHNULL} and reaches {@code WHEN OTHER}, while on {@code COBIL00C} it is
     * {@link CicsAid#DFHENTER}, and those are the programs' decisions rather than this class's.
     *
     * @param tokenMember   the payload member carrying the {@code CCARD-AID} token, as the caller sends
     *                      it; must not be {@code null}
     * @param stated        the value {@link #resolve(Integer, Integer)} returned; must not be
     *                      {@code null}
     * @param suppliedToken the token the payload carried, or {@code null} if it carried none
     * @param codec         the codec the {@code PIC X(5)} comparison is made with; must not be
     *                      {@code null}
     * @return the raw EBCDIC attention-identifier byte to act on
     * @throws NullPointerException          if {@code tokenMember}, {@code stated} or {@code codec} is
     *                                       {@code null}
     * @throws ScreenInputRejectedException if the value is outside {@code 0}-{@code 255}, or a token
     *                                       beside it names a different key
     */
    public static byte requireStatedAid(final String tokenMember,
            final Integer stated,
            final String suppliedToken,
            final FixedWidthCodec codec) {
        final byte rawAid = requireAidByte(stated);
        requireTokenAgreement(tokenMember, rawAid, suppliedToken, codec);
        return rawAid;
    }

    /**
     * Whether a token, already at its declared width, is a field the operator never typed in - which
     * COBOL reads as {@code EQUAL SPACES} or {@code EQUAL LOW-VALUES}.
     *
     * @param token the token at {@link PfKeyResolver#AID_TOKEN_LENGTH} characters
     * @return {@code true} when every character is a space, or every character is {@code LOW-VALUES}
     */
    private static boolean statesNoKey(final String token) {
        return token.chars().allMatch(character -> character == ' ')
                || token.chars().allMatch(character -> character == '\u0000');
    }
}
