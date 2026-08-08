package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity tests for {@link PfKeyResolver}, the translation of {@code YYYY-STORE-PFKEY} from
 * {@code app/cpy/CSSTRPFY.cpy}.
 *
 * <p>The suite is organised around the four properties of the source paragraph that determine the
 * implementation, so that a failure points at a translation decision rather than merely at a value:
 *
 * <ol>
 *   <li>All <strong>28</strong> {@code WHEN} branches are present and in source order.</li>
 *   <li>{@code DFHPF13} through {@code DFHPF24} fold back onto {@code PFK01} through
 *       {@code PFK12} - the single most likely place for an off-by-one to hide, so every one of
 *       the twelve is asserted rather than a sample.</li>
 *   <li>There is no {@code WHEN OTHER} and {@code CCARD-AID} is not pre-cleared, so an
 *       unrecognised AID must yield an absent result and must leave any existing token standing.</li>
 *   <li>Tokens are {@code PIC X(5)}, so {@code PA1} and {@code PA2} keep their trailing spaces.</li>
 * </ol>
 *
 * <p>Every branch of the class under test is exercised from both sides, because the build enforces
 * at least 90% branch coverage independently for each package.
 */
@DisplayName("PfKeyResolver - CSSTRPFY YYYY-STORE-PFKEY AID mapping")
class PfKeyResolverTest {

    /**
     * The twenty-eight AIDs {@code CSSTRPFY} actually tests, in copybook order, each paired with the
     * mnemonic name for readable failure messages.
     *
     * <p>Deliberately built as an insertion-ordered map so the order audit in
     * {@link BranchOrderAndCompleteness} can compare against the copybook top to bottom.
     */
    private static Map<String, Byte> testedAidsInCopybookOrder() {
        Map<String, Byte> aids = new LinkedHashMap<>();
        aids.put("DFHENTER", CicsAid.DFHENTER);
        aids.put("DFHCLEAR", CicsAid.DFHCLEAR);
        aids.put("DFHPA1", CicsAid.DFHPA1);
        aids.put("DFHPA2", CicsAid.DFHPA2);
        aids.put("DFHPF1", CicsAid.DFHPF1);
        aids.put("DFHPF2", CicsAid.DFHPF2);
        aids.put("DFHPF3", CicsAid.DFHPF3);
        aids.put("DFHPF4", CicsAid.DFHPF4);
        aids.put("DFHPF5", CicsAid.DFHPF5);
        aids.put("DFHPF6", CicsAid.DFHPF6);
        aids.put("DFHPF7", CicsAid.DFHPF7);
        aids.put("DFHPF8", CicsAid.DFHPF8);
        aids.put("DFHPF9", CicsAid.DFHPF9);
        aids.put("DFHPF10", CicsAid.DFHPF10);
        aids.put("DFHPF11", CicsAid.DFHPF11);
        aids.put("DFHPF12", CicsAid.DFHPF12);
        aids.put("DFHPF13", CicsAid.DFHPF13);
        aids.put("DFHPF14", CicsAid.DFHPF14);
        aids.put("DFHPF15", CicsAid.DFHPF15);
        aids.put("DFHPF16", CicsAid.DFHPF16);
        aids.put("DFHPF17", CicsAid.DFHPF17);
        aids.put("DFHPF18", CicsAid.DFHPF18);
        aids.put("DFHPF19", CicsAid.DFHPF19);
        aids.put("DFHPF20", CicsAid.DFHPF20);
        aids.put("DFHPF21", CicsAid.DFHPF21);
        aids.put("DFHPF22", CicsAid.DFHPF22);
        aids.put("DFHPF23", CicsAid.DFHPF23);
        aids.put("DFHPF24", CicsAid.DFHPF24);
        return aids;
    }

    @Nested
    @DisplayName("Token vocabulary from CVCRD01Y")
    class TokenVocabulary {

        @Test
        @DisplayName("the token width is 5, from CCARD-AID PIC X(5)")
        void tokenWidthIsFive() {
            assertThat(PfKeyResolver.AID_TOKEN_LENGTH).isEqualTo(5);
        }

        @Test
        @DisplayName("there are exactly 16 AID conditions - CVCRD01Y shows 16, not the plan's 15")
        void thereAreSixteenConditions() {
            assertThat(AidKey.values()).hasSize(16);
        }

        @ParameterizedTest
        @EnumSource(AidKey.class)
        @DisplayName("every token is exactly 5 characters wide, because the field is PIC X(5)")
        void everyTokenIsExactlyFiveCharacters(AidKey key) {
            assertThat(key.token()).hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("PA1 and PA2 keep their two trailing spaces - a trim here would break parity")
        void paTokensKeepTheirTrailingSpaces() {
            // Asserted as exact equality AND as a length, because "PA1" would pass a prefix check
            // while being the wrong width for the PIC X(5) field.
            assertThat(AidKey.PA1.token()).isEqualTo("PA1  ").hasSize(5).isNotEqualTo("PA1");
            assertThat(AidKey.PA2.token()).isEqualTo("PA2  ").hasSize(5).isNotEqualTo("PA2");
        }

        @Test
        @DisplayName("the four non-function-key tokens carry their exact CVCRD01Y literals")
        void nonFunctionKeyTokens() {
            assertThat(AidKey.ENTER.token()).isEqualTo("ENTER");
            assertThat(AidKey.CLEAR.token()).isEqualTo("CLEAR");
            assertThat(AidKey.PA1.token()).isEqualTo("PA1  ");
            assertThat(AidKey.PA2.token()).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("the twelve PFK tokens are PFK01 through PFK12, zero-padded")
        void functionKeyTokens() {
            assertThat(AidKey.PFK01.token()).isEqualTo("PFK01");
            assertThat(AidKey.PFK02.token()).isEqualTo("PFK02");
            assertThat(AidKey.PFK03.token()).isEqualTo("PFK03");
            assertThat(AidKey.PFK04.token()).isEqualTo("PFK04");
            assertThat(AidKey.PFK05.token()).isEqualTo("PFK05");
            assertThat(AidKey.PFK06.token()).isEqualTo("PFK06");
            assertThat(AidKey.PFK07.token()).isEqualTo("PFK07");
            assertThat(AidKey.PFK08.token()).isEqualTo("PFK08");
            assertThat(AidKey.PFK09.token()).isEqualTo("PFK09");
            assertThat(AidKey.PFK10.token()).isEqualTo("PFK10");
            assertThat(AidKey.PFK11.token()).isEqualTo("PFK11");
            assertThat(AidKey.PFK12.token()).isEqualTo("PFK12");
        }

        @Test
        @DisplayName("all 16 tokens are distinct, so a token identifies its condition unambiguously")
        void allTokensAreDistinct() {
            List<String> tokens = new ArrayList<>();
            for (AidKey key : AidKey.values()) {
                tokens.add(key.token());
            }
            assertThat(tokens).doesNotHaveDuplicates().hasSize(16);
        }

        @Test
        @DisplayName("there is no PA3 and no NONE token - CVCRD01Y declares neither")
        void thereIsNoPa3AndNoNoneToken() {
            List<String> names = new ArrayList<>();
            for (AidKey key : AidKey.values()) {
                names.add(key.name());
            }
            assertThat(names).doesNotContain("PA3", "NONE", "UNRECOGNISED", "UNRECOGNIZED", "OTHER");
        }
    }

    @Nested
    @DisplayName("Resolution - CSSTRPFY lines 22-29, the ENTER, CLEAR and PA branches")
    class NonFunctionKeyBranches {

        @Test
        @DisplayName("DFHENTER resolves to ENTER (copybook L22-23)")
        void enter() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
        }

        @Test
        @DisplayName("DFHCLEAR resolves to CLEAR (copybook L24-25)")
        void clear() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR)).contains(AidKey.CLEAR);
        }

        @Test
        @DisplayName("DFHPA1 resolves to the padded token 'PA1  ' (copybook L26-27)")
        void pa1() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1).orElseThrow().token()).isEqualTo("PA1  ");
        }

        @Test
        @DisplayName("DFHPA2 resolves to the padded token 'PA2  ' (copybook L28-29)")
        void pa2() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2).orElseThrow().token()).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("DFHPA1 precedes DFHPA2 in the copybook and the two do not collide")
        void pa1AndPa2AreDistinctBranches() {
            assertThat(CicsAid.DFHPA1).isNotEqualTo(CicsAid.DFHPA2);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1))
                    .isNotEqualTo(PfKeyResolver.resolve(CicsAid.DFHPA2));
        }
    }

    /**
     * The twelve unshifted function keys, {@code CSSTRPFY} lines 30-53, as
     * {@code (mnemonic, AID, expected token, copybook line)}.
     *
     * <p>Held on the outer class so both the one-to-one test and the fold test can consume it
     * without duplicating the table.
     */
    static Stream<Arguments> pf1ThroughPf12() {
        return Stream.of(
                Arguments.of("DFHPF1", CicsAid.DFHPF1, AidKey.PFK01, 30),
                Arguments.of("DFHPF2", CicsAid.DFHPF2, AidKey.PFK02, 32),
                Arguments.of("DFHPF3", CicsAid.DFHPF3, AidKey.PFK03, 34),
                Arguments.of("DFHPF4", CicsAid.DFHPF4, AidKey.PFK04, 36),
                Arguments.of("DFHPF5", CicsAid.DFHPF5, AidKey.PFK05, 38),
                Arguments.of("DFHPF6", CicsAid.DFHPF6, AidKey.PFK06, 40),
                Arguments.of("DFHPF7", CicsAid.DFHPF7, AidKey.PFK07, 42),
                Arguments.of("DFHPF8", CicsAid.DFHPF8, AidKey.PFK08, 44),
                Arguments.of("DFHPF9", CicsAid.DFHPF9, AidKey.PFK09, 46),
                Arguments.of("DFHPF10", CicsAid.DFHPF10, AidKey.PFK10, 48),
                Arguments.of("DFHPF11", CicsAid.DFHPF11, AidKey.PFK11, 50),
                Arguments.of("DFHPF12", CicsAid.DFHPF12, AidKey.PFK12, 52));
    }

    /**
     * The twelve shifted function keys, {@code CSSTRPFY} lines 54-77, as
     * {@code (mnemonic, AID, expected token, copybook line)}. The expected tokens are the folded
     * ones: PF13 expects {@code PFK01}, through PF24 expecting {@code PFK12}.
     */
    static Stream<Arguments> pf13ThroughPf24() {
        return Stream.of(
                Arguments.of("DFHPF13", CicsAid.DFHPF13, AidKey.PFK01, 54),
                Arguments.of("DFHPF14", CicsAid.DFHPF14, AidKey.PFK02, 56),
                Arguments.of("DFHPF15", CicsAid.DFHPF15, AidKey.PFK03, 58),
                Arguments.of("DFHPF16", CicsAid.DFHPF16, AidKey.PFK04, 60),
                Arguments.of("DFHPF17", CicsAid.DFHPF17, AidKey.PFK05, 62),
                Arguments.of("DFHPF18", CicsAid.DFHPF18, AidKey.PFK06, 64),
                Arguments.of("DFHPF19", CicsAid.DFHPF19, AidKey.PFK07, 66),
                Arguments.of("DFHPF20", CicsAid.DFHPF20, AidKey.PFK08, 68),
                Arguments.of("DFHPF21", CicsAid.DFHPF21, AidKey.PFK09, 70),
                Arguments.of("DFHPF22", CicsAid.DFHPF22, AidKey.PFK10, 72),
                Arguments.of("DFHPF23", CicsAid.DFHPF23, AidKey.PFK11, 74),
                Arguments.of("DFHPF24", CicsAid.DFHPF24, AidKey.PFK12, 76));
    }

    @Nested
    @DisplayName("Resolution - CSSTRPFY lines 30-53, PF1 through PF12 one-to-one")
    class Pf1ThroughPf12Branches {

        @ParameterizedTest(name = "{0} -> {2} (copybook L{3})")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#pf1ThroughPf12")
        @DisplayName("all twelve of PF1 through PF12 map to their matching PFK token")
        void pf1ThroughPf12MapOneToOne(String mnemonic, byte aid, AidKey expected, int copybookLine) {
            assertThat(PfKeyResolver.resolve(aid))
                    .as("%s (copybook line %d)", mnemonic, copybookLine)
                    .contains(expected);
        }
    }

    @Nested
    @DisplayName("Resolution - CSSTRPFY lines 54-77, the PF13-PF24 fold onto PFK01-PFK12")
    class Pf13ThroughPf24Fold {

        @ParameterizedTest(name = "{0} -> {2} (copybook L{3}, folded)")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#pf13ThroughPf24")
        @DisplayName("all twelve of PF13 through PF24 fold onto PFK01 through PFK12")
        void pf13ThroughPf24FoldBack(String mnemonic, byte aid, AidKey expected, int copybookLine) {
            // A wrong offset in the fold is the most likely defect in the class under test, so every
            // one of the twelve is asserted individually rather than sampled.
            assertThat(PfKeyResolver.resolve(aid))
                    .as("%s (copybook line %d, folded)", mnemonic, copybookLine)
                    .contains(expected);
        }

        @Test
        @DisplayName("the fold endpoints are identical by object identity, not merely by value")
        void foldEndpointsAreTheSameObject() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13).orElseThrow())
                    .isSameAs(PfKeyResolver.resolve(CicsAid.DFHPF1).orElseThrow());
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24).orElseThrow())
                    .isSameAs(PfKeyResolver.resolve(CicsAid.DFHPF12).orElseThrow());
        }

        @ParameterizedTest(name = "PF{0}+12 resolves identically to PF{0}")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#foldPairs")
        @DisplayName("each shifted key resolves identically to its unshifted partner, all twelve")
        void everyShiftedKeyMatchesItsPartner(int keyNumber, byte unshifted, byte shifted) {
            assertThat(PfKeyResolver.resolve(shifted))
                    .as("PF%d and PF%d must resolve alike", keyNumber, keyNumber + 12)
                    .isEqualTo(PfKeyResolver.resolve(unshifted));
        }

        @Test
        @DisplayName("24 distinct AID bytes collapse onto exactly 12 distinct tokens")
        void twentyFourKeysYieldTwelveTokens() {
            List<AidKey> resolved = new ArrayList<>();
            for (Arguments arguments : Stream.concat(pf1ThroughPf12(), pf13ThroughPf24()).toList()) {
                resolved.add(PfKeyResolver.resolve((byte) arguments.get()[1]).orElseThrow());
            }
            assertThat(resolved).hasSize(24);
            assertThat(new LinkedHashSet<>(resolved)).hasSize(12);
        }
    }

    /**
     * The twelve unshifted/shifted function-key pairs the fold creates, as
     * {@code (keyNumber, PFn, PFn+12)}.
     */
    static Stream<Arguments> foldPairs() {
        return Stream.of(
                Arguments.of(1, CicsAid.DFHPF1, CicsAid.DFHPF13),
                Arguments.of(2, CicsAid.DFHPF2, CicsAid.DFHPF14),
                Arguments.of(3, CicsAid.DFHPF3, CicsAid.DFHPF15),
                Arguments.of(4, CicsAid.DFHPF4, CicsAid.DFHPF16),
                Arguments.of(5, CicsAid.DFHPF5, CicsAid.DFHPF17),
                Arguments.of(6, CicsAid.DFHPF6, CicsAid.DFHPF18),
                Arguments.of(7, CicsAid.DFHPF7, CicsAid.DFHPF19),
                Arguments.of(8, CicsAid.DFHPF8, CicsAid.DFHPF20),
                Arguments.of(9, CicsAid.DFHPF9, CicsAid.DFHPF21),
                Arguments.of(10, CicsAid.DFHPF10, CicsAid.DFHPF22),
                Arguments.of(11, CicsAid.DFHPF11, CicsAid.DFHPF23),
                Arguments.of(12, CicsAid.DFHPF12, CicsAid.DFHPF24));
    }

    @Nested
    @DisplayName("No match - the absent WHEN OTHER, reproduced not repaired")
    class NoMatchBehaviour {

        static Stream<Arguments> aidsWithNoBranch() {
            return Stream.of(
                    Arguments.of("DFHPA3 - defined by CicsAid, never tested by CSSTRPFY",
                            CicsAid.DFHPA3),
                    Arguments.of("DFHNULL - the EBCDIC space, 0x40", CicsAid.DFHNULL),
                    Arguments.of("DFHCLRP", CicsAid.DFHCLRP),
                    Arguments.of("DFHPEN", CicsAid.DFHPEN),
                    Arguments.of("DFHOPID", CicsAid.DFHOPID),
                    Arguments.of("DFHMSRE", CicsAid.DFHMSRE),
                    Arguments.of("DFHSTRF", CicsAid.DFHSTRF),
                    Arguments.of("DFHTRIG", CicsAid.DFHTRIG),
                    Arguments.of("an arbitrary unmapped byte 0x00", (byte) 0x00),
                    Arguments.of("an arbitrary unmapped byte 0x40", (byte) 0x40),
                    Arguments.of("an arbitrary unmapped byte 0xFF", (byte) 0xFF));
        }

        @ParameterizedTest(name = "{0} -> no match")
        @MethodSource("aidsWithNoBranch")
        @DisplayName("an AID with no WHEN branch yields an absent result, never a substitute token")
        void unmatchedAidYieldsAbsentResult(String description, byte aid) {
            assertThat(PfKeyResolver.resolve(aid)).as(description).isEmpty();
        }

        @Test
        @DisplayName("DFHPA3 specifically resolves to no match, and does not throw")
        void dfhPa3ResolvesToNoMatchWithoutThrowing() {
            // CicsAid defines DFHPA3 because the plan mandates the PA1-PA3 range, but CSSTRPFY has
            // no PA3 branch and CVCRD01Y has no CCARD-AID-PA3 condition. Absence is the correct
            // answer; a PA3 token would be an invention.
            Optional<AidKey> result = PfKeyResolver.resolve(CicsAid.DFHPA3);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("no unmatched byte is quietly treated as ENTER")
        void unmatchedIsNotSilentlyTreatedAsEnter() {
            // The most tempting wrong "helpful default" is to fall back to ENTER, so it is ruled out
            // explicitly rather than only implied by isEmpty().
            assertThat(PfKeyResolver.resolve((byte) 0x00))
                    .isEmpty()
                    .isNotEqualTo(Optional.of(AidKey.ENTER));
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                    .isEmpty()
                    .isNotEqualTo(Optional.of(AidKey.ENTER));
        }

        @Test
        @DisplayName("resolution is idempotent and side-effect free across repeated calls")
        void resolutionIsIdempotent() {
            for (int i = 0; i < 3; i++) {
                assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
                assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
                assertThat(PfKeyResolver.resolve((byte) 0x40)).isEmpty();
            }
        }

        @Test
        @DisplayName("resolve never returns null, for a matched or an unmatched byte")
        void resolveNeverReturnsNull() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).isNotNull();
            assertThat(PfKeyResolver.resolve((byte) 0x01)).isNotNull();
        }
    }

    @Nested
    @DisplayName("storePfKey - the paragraph's whole contract, including the absent pre-clear")
    class StorePfKeyBehaviour {

        @Test
        @DisplayName("a matched AID replaces the token that was there before")
        void matchReplacesTheExistingToken() {
            Optional<AidKey> result =
                    PfKeyResolver.storePfKey(CicsAid.DFHPF3, Optional.of(AidKey.ENTER));
            assertThat(result).contains(AidKey.PFK03);
        }

        @Test
        @DisplayName("a matched AID sets the token when none was recorded yet")
        void matchSetsTokenFromEmpty() {
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHENTER, Optional.empty()))
                    .contains(AidKey.ENTER);
        }

        @Test
        @DisplayName("an unmatched AID leaves the previous token STANDING - CCARD-AID is not cleared")
        void noMatchLeavesThePreviousTokenUntouched() {
            // This is the whole point of the missing WHEN OTHER. The COBOL never clears CCARD-AID
            // before the EVALUATE, so an unmatched AID leaves the prior key's flag set.
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK07)))
                    .contains(AidKey.PFK07);
            assertThat(PfKeyResolver.storePfKey((byte) 0x00, Optional.of(AidKey.ENTER)))
                    .contains(AidKey.ENTER);
        }

        @Test
        @DisplayName("an unmatched AID against an empty work area stays empty - nothing is invented")
        void noMatchFromEmptyStaysEmpty() {
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.empty())).isEmpty();
        }

        @Test
        @DisplayName("the current token is returned by identity on no match, proving it is not rebuilt")
        void noMatchReturnsTheVerySameOptional() {
            Optional<AidKey> current = Optional.of(AidKey.PFK12);
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, current)).isSameAs(current);
        }

        @Test
        @DisplayName("a null current token is rejected - absence must be Optional.empty()")
        void nullCurrentTokenIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PfKeyResolver.storePfKey(CicsAid.DFHENTER, null))
                    .withMessageContaining("currentAid");
        }

        @Test
        @DisplayName("storePfKey agrees with resolve for every AID that has a branch")
        void storePfKeyAgreesWithResolveOnEveryMatch() {
            testedAidsInCopybookOrder().forEach((mnemonic, aid) ->
                    assertThat(PfKeyResolver.storePfKey(aid, Optional.empty()))
                            .as(mnemonic)
                            .isEqualTo(PfKeyResolver.resolve(aid)));
        }
    }

    @Nested
    @DisplayName("Inline-tester equivalence - identical boolean outcomes for the 12 inline programs")
    class InlineTesterEquivalence {

        /**
         * The seven mnemonics the twelve non-copying programs are verified to test inline, with
         * their occurrence counts across {@code app/cbl}.
         */
        static Stream<Arguments> inlineMnemonics() {
            return Stream.of(
                    Arguments.of("DFHENTER", CicsAid.DFHENTER, 16),
                    Arguments.of("DFHPF3", CicsAid.DFHPF3, 14),
                    Arguments.of("DFHPF4", CicsAid.DFHPF4, 6),
                    Arguments.of("DFHPF5", CicsAid.DFHPF5, 4),
                    Arguments.of("DFHPF7", CicsAid.DFHPF7, 4),
                    Arguments.of("DFHPF8", CicsAid.DFHPF8, 4),
                    Arguments.of("DFHPF12", CicsAid.DFHPF12, 2));
        }

        @ParameterizedTest(name = "isAid is true for {0} and false for the other six")
        @MethodSource("inlineMnemonics")
        @DisplayName("isAid is exact: true only for the AID under test, false for every other")
        void isAidIsExactAcrossTheInlineSet(String mnemonic, byte aid, int occurrences) {
            assertThat(occurrences).isPositive();
            assertThat(PfKeyResolver.isAid(aid, aid)).as("%s equals itself", mnemonic).isTrue();
            inlineMnemonics().forEach(other -> {
                byte otherAid = (byte) other.get()[1];
                if (otherAid != aid) {
                    assertThat(PfKeyResolver.isAid(aid, otherAid))
                            .as("%s must not equal %s", mnemonic, other.get()[0])
                            .isFalse();
                }
            });
        }

        @Test
        @DisplayName("each named predicate is true for its own key")
        void namedPredicatesAreTrueForTheirOwnKey() {
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4)).isTrue();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF5)).isTrue();
            assertThat(PfKeyResolver.isPf7(CicsAid.DFHPF7)).isTrue();
            assertThat(PfKeyResolver.isPf8(CicsAid.DFHPF8)).isTrue();
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF12)).isTrue();
        }

        @Test
        @DisplayName("each named predicate is false for every other key in the inline set")
        void namedPredicatesAreFalseForEveryOtherKey() {
            // Exercises the false side of every predicate, which is what the 12 inline programs'
            // WHEN OTHER arms depend on.
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHENTER)).isFalse();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF4)).isFalse();
            assertThat(PfKeyResolver.isPf7(CicsAid.DFHPF8)).isFalse();
            assertThat(PfKeyResolver.isPf8(CicsAid.DFHPF7)).isFalse();
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF3)).isFalse();
        }

        @Test
        @DisplayName("a named predicate agrees with isAid against the same CicsAid constant")
        void namedPredicatesDelegateFaithfully() {
            for (Byte aid : testedAidsInCopybookOrder().values()) {
                assertThat(PfKeyResolver.isEnter(aid))
                        .isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHENTER));
                assertThat(PfKeyResolver.isPf3(aid))
                        .isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHPF3));
                assertThat(PfKeyResolver.isPf12(aid))
                        .isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHPF12));
            }
        }

        @Test
        @DisplayName("PF3 and PF15 are NOT equal by isAid, even though they resolve alike")
        void foldDoesNotLeakIntoTheEqualityPredicate() {
            // The fold is a property of resolve, not of byte equality. An inline tester comparing
            // EIBAID = DFHPF3 must NOT match PF15, exactly as the COBOL would not.
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF15)).isFalse();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF3));
        }

        @Test
        @DisplayName("isAid works for AIDs that have no CSSTRPFY branch at all")
        void isAidWorksForUnmappedAids() {
            assertThat(PfKeyResolver.isAid(CicsAid.DFHPA3, CicsAid.DFHPA3)).isTrue();
            assertThat(PfKeyResolver.isAid(CicsAid.DFHPA3, CicsAid.DFHPA1)).isFalse();
        }
    }

    @Nested
    @DisplayName("Branch order and completeness")
    class BranchOrderAndCompleteness {

        @Test
        @DisplayName("CSSTRPFY tests exactly 28 AIDs - not the 26 the plan's summary states")
        void twentyEightBranchesAreCovered() {
            assertThat(testedAidsInCopybookOrder()).hasSize(28);
        }

        @Test
        @DisplayName("every one of the 28 tested AIDs resolves to some token - no branch is missing")
        void everyTestedAidResolves() {
            testedAidsInCopybookOrder().forEach((mnemonic, aid) ->
                    assertThat(PfKeyResolver.resolve(aid)).as(mnemonic).isPresent());
        }

        @Test
        @DisplayName("the 28 switched AID constants are pairwise distinct")
        void switchedConstantsArePairwiseDistinct() {
            // The decisive precondition for a first-match-wins translation: were two AID constants
            // to share a byte, one WHEN branch would be silently unreachable and the EVALUATE would
            // become order-dependent in a way the COBOL is not. The consequence lands here, so the
            // assertion lives here as well as in the constants class.
            List<Byte> values = new ArrayList<>(testedAidsInCopybookOrder().values());
            assertThat(values).hasSize(28).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the 28 AIDs produce exactly the 16 declared tokens - all reachable, none extra")
        void theTwentyEightAidsCoverAllSixteenTokens() {
            List<AidKey> produced = new ArrayList<>();
            testedAidsInCopybookOrder().values()
                    .forEach(aid -> produced.add(PfKeyResolver.resolve(aid).orElseThrow()));
            assertThat(new LinkedHashSet<>(produced))
                    .hasSize(16)
                    .containsExactlyInAnyOrder(AidKey.values());
        }
    }

    @Nested
    @DisplayName("Class shape")
    class ClassShape {

        @Test
        @DisplayName("the resolver is not instantiable, including reflectively")
        void notInstantiable() throws ReflectiveOperationException {
            Constructor<PfKeyResolver> constructor = PfKeyResolver.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }
}
