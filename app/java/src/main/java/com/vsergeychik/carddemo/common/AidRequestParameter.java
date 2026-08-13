package com.vsergeychik.carddemo.common;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one name every online route uses for the query parameter that carries {@code EIBAID}, and the rule
 * for reading it.
 */
public final class AidRequestParameter {
    public static final String CANONICAL_NAME = "eibaid";

    public static final String ALTERNATE_NAME = "eibAid";

    public static final List<String> ACCEPTED_NAMES = List.of(CANONICAL_NAME, ALTERNATE_NAME);

    /**
     * The lowest value a stated {@code EIBAID} can take: {@value}.
     */
    public static final int MIN_AID_VALUE = 0;

    /**
     * The highest value a stated {@code EIBAID} can take: {@value}.
     */
    public static final int MAX_AID_VALUE = 255;

    private AidRequestParameter() {
        throw new AssertionError("AidRequestParameter is a constant holder and is never instantiated");
    }

    /**
     * Folds the two accepted spellings into the single value the caller stated.
     *
     * <p>Four outcomes, and each one is a decision rather than a fallback: Neither name present -
     * {@code null}, which every caller already reads as "the request named no key" and answers with
     * {@link CicsAid#DFHENTER} or with the payload's own {@code CCARD-AID} token, exactly as it did before.
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
     * @param stated the value bound from one of {@link #ACCEPTED_NAMES}, already folded by
     *     {@link #resolve(Integer, Integer)}; must not be {@code null}
     * @return the raw EBCDIC attention-identifier byte the caller stated
     * @throws NullPointerException if {@code stated} is {@code null} - absence is the caller's decision to
     *     make, not a value to narrow
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
     * Requires a payload's {@code CCARD-AID} token to name the same key as the raw byte, when it states a
     * key at all.
     *
     * <p>Anything else is compared, at {@code PIC X(5)} and never by trimming, against the token
     * {@code CSSTRPFY} itself would store for this byte - {@link PfKeyResolver#resolve(byte)}, the folding
     * resolver.
     *
     * @param member the payload member carrying the token, as the caller sent it; must not be {@code null}
     * @param rawAid the byte the request stated, already narrowed by {@link #requireAidByte(Integer)}
     * @param suppliedToken the token the payload carried, or {@code null} if it carried none
     * @param codec the codec the {@code PIC X(5)} comparison is made with; must not be {@code null}
     * @throws NullPointerException if {@code member} or {@code codec} is {@code null}
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
     * @param tokenMember the payload member carrying the {@code CCARD-AID} token, as the caller sends it;
     *     must not be {@code null}
     * @param stated the value {@link #resolve(Integer, Integer)} returned; must not be {@code null}
     * @param suppliedToken the token the payload carried, or {@code null} if it carried none
     * @param codec the codec the {@code PIC X(5)} comparison is made with; must not be {@code null}
     * @return the raw EBCDIC attention-identifier byte to act on
     * @throws NullPointerException if {@code tokenMember}, {@code stated} or {@code codec} is {@code null}
     * @throws ScreenInputRejectedException if the value is outside {@code 0}-{@code 255}, or a token beside
     *     it names a different key
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
     * Whether a token, already at its declared width, is a field the operator never typed in - which COBOL
     * reads as {@code EQUAL SPACES} or {@code EQUAL LOW-VALUES}.
     *
     * @param token the token at {@link PfKeyResolver#AID_TOKEN_LENGTH} characters
     * @return {@code true} when every character is a space, or every character is {@code LOW-VALUES}
     */
    private static boolean statesNoKey(final String token) {
        return token.chars().allMatch(character -> character == ' ')
                || token.chars().allMatch(character -> character == '\u0000');
    }
}
