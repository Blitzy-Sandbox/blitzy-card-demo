package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity tests for {@link PfKeyResolver}, the Java translation of the {@code YYYY-STORE-PFKEY} paragraph
 * held in {@code app/cpy/CSSTRPFY.cpy}, which maps a raw CICS {@code EIBAID} byte onto the five-character
 * {@code CCARD-AID} token declared at {@code app/cpy/CVCRD01Y.cpy} line 3.
 */
@DisplayName("PfKeyResolver - CSSTRPFY YYYY-STORE-PFKEY, EIBAID to CCARD-AID")
class PfKeyResolverTest {
    private static final int CSSTRPFY_BRANCH_COUNT = 28;

    private static final int CCARD_AID_CONDITION_COUNT = 16;

    private static final int ALL_BYTE_VALUES = 256;

    /**
     * One {@code WHEN} arm of the {@code EVALUATE TRUE} in {@code app/cpy/CSSTRPFY.cpy}.
     *
     * @param mnemonic the {@code DFHAID} mnemonic the arm tests, as the copybook spells it
     * @param aid the raw EBCDIC AID byte, taken from the matching {@link CicsAid} constant
     * @param expectedToken the {@code CCARD-AID} condition the arm sets
     * @param copybookLine the line of {@code app/cpy/CSSTRPFY.cpy} carrying the {@code WHEN}
     */
    record AidBranch(String mnemonic, byte aid, AidKey expectedToken, int copybookLine) {
        @Override
        public String toString() {
            return "%s -> '%s' (CSSTRPFY.cpy L%d)".formatted(mnemonic, expectedToken.token(), copybookLine);
        }
    }

    static List<AidBranch> allBranchesInCopybookOrder() {
        List<AidBranch> branches = new ArrayList<>();
        branches.add(new AidBranch("DFHENTER", CicsAid.DFHENTER, AidKey.ENTER, 22));
        branches.add(new AidBranch("DFHCLEAR", CicsAid.DFHCLEAR, AidKey.CLEAR, 24));
        branches.add(new AidBranch("DFHPA1", CicsAid.DFHPA1, AidKey.PA1, 26));
        branches.add(new AidBranch("DFHPA2", CicsAid.DFHPA2, AidKey.PA2, 28));
        branches.add(new AidBranch("DFHPF1", CicsAid.DFHPF1, AidKey.PFK01, 30));
        branches.add(new AidBranch("DFHPF2", CicsAid.DFHPF2, AidKey.PFK02, 32));
        branches.add(new AidBranch("DFHPF3", CicsAid.DFHPF3, AidKey.PFK03, 34));
        branches.add(new AidBranch("DFHPF4", CicsAid.DFHPF4, AidKey.PFK04, 36));
        branches.add(new AidBranch("DFHPF5", CicsAid.DFHPF5, AidKey.PFK05, 38));
        branches.add(new AidBranch("DFHPF6", CicsAid.DFHPF6, AidKey.PFK06, 40));
        branches.add(new AidBranch("DFHPF7", CicsAid.DFHPF7, AidKey.PFK07, 42));
        branches.add(new AidBranch("DFHPF8", CicsAid.DFHPF8, AidKey.PFK08, 44));
        branches.add(new AidBranch("DFHPF9", CicsAid.DFHPF9, AidKey.PFK09, 46));
        branches.add(new AidBranch("DFHPF10", CicsAid.DFHPF10, AidKey.PFK10, 48));
        branches.add(new AidBranch("DFHPF11", CicsAid.DFHPF11, AidKey.PFK11, 50));
        branches.add(new AidBranch("DFHPF12", CicsAid.DFHPF12, AidKey.PFK12, 52));
        branches.add(new AidBranch("DFHPF13", CicsAid.DFHPF13, AidKey.PFK01, 54));
        branches.add(new AidBranch("DFHPF14", CicsAid.DFHPF14, AidKey.PFK02, 56));
        branches.add(new AidBranch("DFHPF15", CicsAid.DFHPF15, AidKey.PFK03, 58));
        branches.add(new AidBranch("DFHPF16", CicsAid.DFHPF16, AidKey.PFK04, 60));
        branches.add(new AidBranch("DFHPF17", CicsAid.DFHPF17, AidKey.PFK05, 62));
        branches.add(new AidBranch("DFHPF18", CicsAid.DFHPF18, AidKey.PFK06, 64));
        branches.add(new AidBranch("DFHPF19", CicsAid.DFHPF19, AidKey.PFK07, 66));
        branches.add(new AidBranch("DFHPF20", CicsAid.DFHPF20, AidKey.PFK08, 68));
        branches.add(new AidBranch("DFHPF21", CicsAid.DFHPF21, AidKey.PFK09, 70));
        branches.add(new AidBranch("DFHPF22", CicsAid.DFHPF22, AidKey.PFK10, 72));
        branches.add(new AidBranch("DFHPF23", CicsAid.DFHPF23, AidKey.PFK11, 74));
        branches.add(new AidBranch("DFHPF24", CicsAid.DFHPF24, AidKey.PFK12, 76));
        return branches;
    }

    static Stream<AidBranch> everyCsstrpfyBranch() {
        return allBranchesInCopybookOrder().stream();
    }

    static Stream<AidBranch> unfoldedBranches() {
        return allBranchesInCopybookOrder().stream().limit(CCARD_AID_CONDITION_COUNT);
    }

    static Stream<AidBranch> foldedBranches() {
        return allBranchesInCopybookOrder().stream().skip(CCARD_AID_CONDITION_COUNT);
    }

    static Stream<AidBranch> lowFunctionKeyBranches() {
        return unfoldedBranches().skip(4);
    }

    record FoldPair(int keyNumber, byte unshifted, byte shifted) {
        @Override
        public String toString() {
            return "PF%d and PF%d".formatted(keyNumber, keyNumber + 12);
        }
    }

    static Stream<FoldPair> foldPairs() {
        List<AidBranch> low = allBranchesInCopybookOrder().stream()
                .filter(branch -> branch.mnemonic().startsWith("DFHPF"))
                .limit(12)
                .toList();
        List<AidBranch> high = allBranchesInCopybookOrder().stream()
                .filter(branch -> branch.mnemonic().startsWith("DFHPF"))
                .skip(12)
                .toList();
        List<FoldPair> pairs = new ArrayList<>();
        for (int index = 0; index < low.size(); index++) {
            pairs.add(new FoldPair(index + 1, low.get(index).aid(), high.get(index).aid()));
        }
        return pairs.stream();
    }

    private static AidKey resolvedTokenOf(byte aid) {
        Optional<AidKey> resolved = PfKeyResolver.resolve(aid);
        assertThat(resolved)
                .as("AID 0x%02X must be one of the %d tested arms", aid, CSSTRPFY_BRANCH_COUNT)
                .isPresent();
        return resolved.orElseThrow();
    }

    private static List<Field> staticFieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    @Nested
    @DisplayName("Token vocabulary - CVCRD01Y CCARD-AID PIC X(5) and its 16 88-levels")
    class TokenVocabulary {
        @Test
        @DisplayName("the declared token width is 5, from CCARD-AID PIC X(5) at CVCRD01Y L3")
        void tokenWidthIsFive() {
            assertThat(PfKeyResolver.AID_TOKEN_LENGTH).isEqualTo(5);
        }

        @Test
        @DisplayName("there are exactly 16 AID conditions, as CVCRD01Y L4-L19 declares")
        void thereAreSixteenConditions() {
            assertThat(AidKey.values()).hasSize(CCARD_AID_CONDITION_COUNT);
        }

        @ParameterizedTest
        @EnumSource(AidKey.class)
        @DisplayName("every one of the 16 tokens is exactly 5 characters, because the field is PIC X(5)")
        void everyTokenIsExactlyFiveCharacters(AidKey key) {
            assertThat(key.token())
                    .as("token for %s", key.name())
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("PA1 is exactly 'PA1  ' - three characters and TWO trailing spaces")
        void pa1KeepsItsTwoTrailingSpaces() {
            assertThat(AidKey.PA1.token()).isEqualTo("PA1  ");
        }

        @Test
        @DisplayName("PA1's length is 5 - asserted separately, so a padding failure names itself")
        void pa1IsFiveCharactersLong() {
            assertThat(AidKey.PA1.token()).hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("PA1 is NOT the unpadded 'PA1'")
        void pa1IsNotTheUnpaddedMnemonic() {
            assertThat(AidKey.PA1.token()).isNotEqualTo("PA1");
        }

        @Test
        @DisplayName("PA2 is exactly 'PA2  ' - three characters and TWO trailing spaces")
        void pa2KeepsItsTwoTrailingSpaces() {
            assertThat(AidKey.PA2.token()).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("PA2's length is 5 - asserted separately from its value")
        void pa2IsFiveCharactersLong() {
            assertThat(AidKey.PA2.token()).hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("PA2 is NOT the unpadded 'PA2'")
        void pa2IsNotTheUnpaddedMnemonic() {
            assertThat(AidKey.PA2.token()).isNotEqualTo("PA2");
        }

        @Test
        @DisplayName("ENTER and CLEAR are 5 characters with NO padding - the invariant, different cause")
        void enterAndClearAreFiveCharactersWithoutPadding() {
            assertThat(AidKey.ENTER.token()).isEqualTo("ENTER").hasSize(5);
            assertThat(AidKey.CLEAR.token()).isEqualTo("CLEAR").hasSize(5);
            assertThat(AidKey.ENTER.token()).doesNotContain(" ");
            assertThat(AidKey.CLEAR.token()).doesNotContain(" ");
        }

        @Test
        @DisplayName("the twelve function-key tokens are PFK01 through PFK12, zero-padded to two digits")
        void functionKeyTokensAreZeroPaddedToTwoDigits() {
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
        void allSixteenTokensAreDistinct() {
            List<String> tokens = new ArrayList<>();
            for (AidKey key : AidKey.values()) {
                tokens.add(key.token());
            }
            assertThat(tokens).hasSize(CCARD_AID_CONDITION_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("no token is blank, so an absent result can never be mistaken for a token")
        void noTokenIsBlank() {
            for (AidKey key : AidKey.values()) {
                assertThat(key.token()).as("token for %s", key.name()).isNotBlank();
            }
        }

        @Test
        @DisplayName("there is no PA3 condition and no NONE sentinel - CVCRD01Y declares neither")
        void thereIsNoPa3AndNoSentinelCondition() {
            List<String> names = new ArrayList<>();
            for (AidKey key : AidKey.values()) {
                names.add(key.name());
            }
            assertThat(names).doesNotContain("PA3", "NONE", "UNKNOWN", "UNRECOGNISED", "UNRECOGNIZED", "OTHER");
        }

        @Test
        @DisplayName("the enum's order is the copybook's order, L4 through L19")
        void enumOrderMatchesCopybookOrder() {
            assertThat(AidKey.values())
                    .containsExactly(
                            AidKey.ENTER, AidKey.CLEAR, AidKey.PA1, AidKey.PA2,
                            AidKey.PFK01, AidKey.PFK02, AidKey.PFK03, AidKey.PFK04,
                            AidKey.PFK05, AidKey.PFK06, AidKey.PFK07, AidKey.PFK08,
                            AidKey.PFK09, AidKey.PFK10, AidKey.PFK11, AidKey.PFK12);
        }
    }

    @Nested
    @DisplayName("The 28-branch sweep - CSSTRPFY L22-L77, every WHEN arm")
    class AllTwentyEightBranches {
        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#everyCsstrpfyBranch")
        @DisplayName("each of the 28 tested AIDs resolves to the token its WHEN arm sets")
        void everyBranchResolvesToItsCopybookToken(AidBranch branch) {
            assertThat(PfKeyResolver.resolve(branch.aid()))
                    .as("CSSTRPFY.cpy L%d: WHEN EIBAID = %s -> SET CCARD-AID-%s",
                            branch.copybookLine(), branch.mnemonic(), branch.expectedToken().name())
                    .contains(branch.expectedToken());
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#everyCsstrpfyBranch")
        @DisplayName("each resolved token is exactly 5 characters, as the PIC X(5) field requires")
        void everyResolvedTokenIsFiveCharacters(AidBranch branch) {
            assertThat(resolvedTokenOf(branch.aid()).token())
                    .as("token width for %s", branch.mnemonic())
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("the sweep has exactly 28 cases - source says 28, the requirement's 26 omits PA1/PA2")
        void theSweepCoversExactlyTwentyEightBranches() {
            assertThat(allBranchesInCopybookOrder()).hasSize(CSSTRPFY_BRANCH_COUNT);
            assertThat(everyCsstrpfyBranch()).hasSize(CSSTRPFY_BRANCH_COUNT);
        }

        @Test
        @DisplayName("the table names DFHPA1 and DFHPA2 explicitly - the two arms the '26' count drops")
        void theTableIncludesTheTwoProgramAccessArms() {
            List<String> mnemonics = allBranchesInCopybookOrder().stream().map(AidBranch::mnemonic).toList();
            assertThat(mnemonics).contains("DFHPA1", "DFHPA2");
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
        }

        @Test
        @DisplayName("the table is in copybook order - its line numbers strictly increase from 22 to 76")
        void theTableIsInCopybookOrder() {
            List<Integer> lines = allBranchesInCopybookOrder().stream().map(AidBranch::copybookLine).toList();
            assertThat(lines).isSorted().doesNotHaveDuplicates();
            assertThat(lines).startsWith(22, 24, 26, 28).endsWith(70, 72, 74, 76);
            assertThat(lines.getFirst()).isEqualTo(22);
            assertThat(lines.getLast()).isEqualTo(76);
        }

        @Test
        @DisplayName("the 28 sweep inputs are 28 distinct AID bytes, so no arm is unreachable")
        void theSweepInputsAreDistinct() {
            List<Byte> aids = allBranchesInCopybookOrder().stream().map(AidBranch::aid).toList();
            assertThat(aids).hasSize(CSSTRPFY_BRANCH_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the first 16 arms introduce all 16 tokens exactly once, before the fold begins")
        void theFirstSixteenArmsIntroduceEveryToken() {
            List<AidKey> introduced = unfoldedBranches().map(AidBranch::expectedToken).toList();
            assertThat(introduced)
                    .hasSize(CCARD_AID_CONDITION_COUNT)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder(AidKey.values());
        }
    }

    @Nested
    @DisplayName("Arms 1-4 - CSSTRPFY L22-L29, ENTER, CLEAR and the two PA keys")
    class NonFunctionKeyBranches {
        @Test
        @DisplayName("DFHENTER resolves to ENTER (L22-L23, the paragraph's first arm)")
        void enterResolves() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
            assertThat(resolvedTokenOf(CicsAid.DFHENTER).token()).isEqualTo("ENTER");
        }

        @Test
        @DisplayName("DFHCLEAR resolves to CLEAR (L24-L25, the second arm)")
        void clearResolves() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR)).contains(AidKey.CLEAR);
            assertThat(resolvedTokenOf(CicsAid.DFHCLEAR).token()).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("DFHPA1 resolves to the padded token 'PA1  ' (L26-L27)")
        void pa1ResolvesToThePaddedToken() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
            assertThat(resolvedTokenOf(CicsAid.DFHPA1).token()).isEqualTo("PA1  ");
            assertThat(resolvedTokenOf(CicsAid.DFHPA1).token()).hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(resolvedTokenOf(CicsAid.DFHPA1).token()).isNotEqualTo("PA1");
        }

        @Test
        @DisplayName("DFHPA2 resolves to the padded token 'PA2  ' (L28-L29)")
        void pa2ResolvesToThePaddedToken() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
            assertThat(resolvedTokenOf(CicsAid.DFHPA2).token()).isEqualTo("PA2  ");
            assertThat(resolvedTokenOf(CicsAid.DFHPA2).token()).hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(resolvedTokenOf(CicsAid.DFHPA2).token()).isNotEqualTo("PA2");
        }

        @Test
        @DisplayName("the two PA arms are separate branches - distinct inputs, distinct tokens")
        void theTwoProgramAccessArmsDoNotCollide() {
            assertThat(CicsAid.DFHPA1).isNotEqualTo(CicsAid.DFHPA2);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1))
                    .isNotEqualTo(PfKeyResolver.resolve(CicsAid.DFHPA2));
        }

        @Test
        @DisplayName("none of the four is folded onto another's token")
        void theFourArmsProduceFourDistinctTokens() {
            List<AidKey> tokens = List.of(
                    resolvedTokenOf(CicsAid.DFHENTER),
                    resolvedTokenOf(CicsAid.DFHCLEAR),
                    resolvedTokenOf(CicsAid.DFHPA1),
                    resolvedTokenOf(CicsAid.DFHPA2));
            assertThat(tokens).hasSize(4).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Arms 5-16 - CSSTRPFY L30-L53, PF1 through PF12 one-to-one")
    class Pf1ThroughPf12Branches {
        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#lowFunctionKeyBranches")
        @DisplayName("each unshifted arm resolves to the token bearing its own key number")
        void eachUnshiftedArmResolvesToItsOwnToken(AidBranch branch) {
            assertThat(PfKeyResolver.resolve(branch.aid()))
                    .as("CSSTRPFY.cpy L%d: %s", branch.copybookLine(), branch.mnemonic())
                    .contains(branch.expectedToken());
        }

        @Test
        @DisplayName("PF1 through PF12 produce twelve distinct tokens - none is folded onto another")
        void theTwelveUnshiftedKeysProduceTwelveDistinctTokens() {
            List<AidKey> tokens = new ArrayList<>();
            foldPairs().forEach(pair -> tokens.add(resolvedTokenOf(pair.unshifted())));
            assertThat(tokens).hasSize(12).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the key number in the token matches the key number in the mnemonic")
        void tokenNumberTracksKeyNumber() {
            foldPairs().forEach(pair -> {
                String expectedSuffix = "%02d".formatted(pair.keyNumber());
                assertThat(resolvedTokenOf(pair.unshifted()).token())
                        .as("PF%d must resolve to PFK%s", pair.keyNumber(), expectedSuffix)
                        .isEqualTo("PFK" + expectedSuffix);
            });
        }
    }

    @Nested
    @DisplayName("Arms 17-28 - CSSTRPFY L54-L77, the PF13-PF24 fold onto PFK01-PFK12")
    class Pf13ThroughPf24Fold {
        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#foldedBranches")
        @DisplayName("each shifted arm resolves to the folded token its WHEN arm sets")
        void eachShiftedArmResolvesToItsFoldedToken(AidBranch branch) {
            assertThat(PfKeyResolver.resolve(branch.aid()))
                    .as("CSSTRPFY.cpy L%d: %s folds onto %s",
                            branch.copybookLine(), branch.mnemonic(), branch.expectedToken().name())
                    .contains(branch.expectedToken());
        }

        @ParameterizedTest(name = "[{index}] {0} resolve alike")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#foldPairs")
        @DisplayName("all twelve shifted keys resolve identically to their unshifted partners")
        void everyShiftedKeyResolvesLikeItsPartner(FoldPair pair) {
            assertThat(PfKeyResolver.resolve(pair.shifted()))
                    .as("PF%d and PF%d must resolve alike", pair.keyNumber(), pair.keyNumber() + 12)
                    .isEqualTo(PfKeyResolver.resolve(pair.unshifted()));
        }

        @ParameterizedTest(name = "[{index}] {0} are distinct inputs")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#foldPairs")
        @DisplayName("the folded inputs are nevertheless DISTINCT - otherwise the fold is vacuous")
        void theFoldedInputsAreDistinct(FoldPair pair) {
            assertThat(pair.shifted())
                    .as("PF%d and PF%d must be different AID bytes", pair.keyNumber(), pair.keyNumber() + 12)
                    .isNotEqualTo(pair.unshifted());
            assertThat(resolvedTokenOf(pair.shifted()))
                    .as("PF%d and PF%d must still share one token", pair.keyNumber(), pair.keyNumber() + 12)
                    .isSameAs(resolvedTokenOf(pair.unshifted()));
        }

        @Test
        @DisplayName("the fold is offset-correct at both boundaries AND at two interior points")
        void theFoldIsOffsetCorrectAtBoundariesAndInterior() {
            assertThat(resolvedTokenOf(CicsAid.DFHPF13).token()).isEqualTo("PFK01");
            assertThat(resolvedTokenOf(CicsAid.DFHPF18).token()).isEqualTo("PFK06");
            assertThat(resolvedTokenOf(CicsAid.DFHPF20).token()).isEqualTo("PFK08");
            assertThat(resolvedTokenOf(CicsAid.DFHPF24).token()).isEqualTo("PFK12");
        }

        @Test
        @DisplayName("the fold does not slip by one - PF18 is PFK06, and is neither PFK05 nor PFK07")
        void theFoldDoesNotSlipByOne() {
            assertThat(resolvedTokenOf(CicsAid.DFHPF18))
                    .isEqualTo(AidKey.PFK06)
                    .isNotEqualTo(AidKey.PFK05)
                    .isNotEqualTo(AidKey.PFK07);
            assertThat(resolvedTokenOf(CicsAid.DFHPF20))
                    .isEqualTo(AidKey.PFK08)
                    .isNotEqualTo(AidKey.PFK07)
                    .isNotEqualTo(AidKey.PFK09);
        }

        @Test
        @DisplayName("the fold endpoints are the same object, not merely equal values")
        void theFoldEndpointsAreTheSameObject() {
            assertThat(resolvedTokenOf(CicsAid.DFHPF13)).isSameAs(resolvedTokenOf(CicsAid.DFHPF1));
            assertThat(resolvedTokenOf(CicsAid.DFHPF24)).isSameAs(resolvedTokenOf(CicsAid.DFHPF12));
        }

        @Test
        @DisplayName("24 distinct function-key AIDs collapse onto exactly 12 distinct tokens")
        void twentyFourKeysCollapseOntoTwelveTokens() {
            List<AidKey> resolved = new ArrayList<>();
            foldPairs().forEach(pair -> {
                resolved.add(resolvedTokenOf(pair.unshifted()));
                resolved.add(resolvedTokenOf(pair.shifted()));
            });
            assertThat(resolved).hasSize(24);
            assertThat(new LinkedHashSet<>(resolved)).hasSize(12);
        }

        @Test
        @DisplayName("the fold covers PF13 to PF24 exhaustively - twelve pairs, no gap")
        void theFoldCoversTwelvePairsWithNoGap() {
            List<Integer> keyNumbers = foldPairs().map(FoldPair::keyNumber).toList();
            assertThat(keyNumbers).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
            assertThat(foldedBranches()).hasSize(12);
        }
    }

    @Nested
    @DisplayName("Branch order and completeness (G30) - and no invented WHEN OTHER")
    class BranchOrderAndCompleteness {
        @Test
        @DisplayName("DFHENTER is the paragraph's FIRST arm, at copybook L22")
        void enterIsTheFirstArm() {
            AidBranch first = allBranchesInCopybookOrder().getFirst();
            assertThat(first.mnemonic()).isEqualTo("DFHENTER");
            assertThat(first.copybookLine()).isEqualTo(22);
            assertThat(first.expectedToken()).isEqualTo(AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(first.aid())).contains(AidKey.ENTER);
        }

        @Test
        @DisplayName("DFHCLEAR is the SECOND arm, at copybook L24")
        void clearIsTheSecondArm() {
            AidBranch second = allBranchesInCopybookOrder().get(1);
            assertThat(second.mnemonic()).isEqualTo("DFHCLEAR");
            assertThat(second.copybookLine()).isEqualTo(24);
            assertThat(second.expectedToken()).isEqualTo(AidKey.CLEAR);
            assertThat(PfKeyResolver.resolve(second.aid())).contains(AidKey.CLEAR);
        }

        @Test
        @DisplayName("DFHPF24 is the LAST arm, at copybook L76 - the EVALUATE ends there")
        void pf24IsTheLastArm() {
            AidBranch last = allBranchesInCopybookOrder().getLast();
            assertThat(last.mnemonic()).isEqualTo("DFHPF24");
            assertThat(last.copybookLine()).isEqualTo(76);
            assertThat(last.expectedToken()).isEqualTo(AidKey.PFK12);
        }

        @Test
        @DisplayName("every one of the 28 arms is reachable - none is shadowed by an earlier one")
        void everyArmIsReachable() {
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                assertThat(PfKeyResolver.resolve(branch.aid()))
                        .as("%s (CSSTRPFY.cpy L%d) must be reachable", branch.mnemonic(), branch.copybookLine())
                        .contains(branch.expectedToken());
            }
        }

        @Test
        @DisplayName("no input reaches two arms - the 28 tests are mutually exclusive")
        void noInputReachesTwoArms() {
            List<Byte> aids = allBranchesInCopybookOrder().stream().map(AidBranch::aid).toList();
            assertThat(aids).hasSize(CSSTRPFY_BRANCH_COUNT).doesNotHaveDuplicates();

            for (AidBranch branch : allBranchesInCopybookOrder()) {
                AidKey produced = resolvedTokenOf(branch.aid());
                assertThat(produced)
                        .as("%s must reach its own arm", branch.mnemonic())
                        .isEqualTo(branch.expectedToken());
                for (AidBranch other : allBranchesInCopybookOrder()) {
                    if (other.aid() != branch.aid() && other.expectedToken() != branch.expectedToken()) {
                        assertThat(produced)
                                .as("%s must not reach %s's arm", branch.mnemonic(), other.mnemonic())
                                .isNotEqualTo(other.expectedToken());
                    }
                }
            }
        }

        @Test
        @DisplayName("NO byte outside the 28 maps to a token - all 256 values checked, no WHEN OTHER")
        void noByteOutsideTheTwentyEightMapsToAToken() {
            Set<Byte> testedAids = new LinkedHashSet<>(
                    allBranchesInCopybookOrder().stream().map(AidBranch::aid).toList());
            List<Byte> matched = new ArrayList<>();
            List<Byte> unmatched = new ArrayList<>();
            for (int candidate = Byte.MIN_VALUE; candidate <= Byte.MAX_VALUE; candidate++) {
                byte value = (byte) candidate;
                if (PfKeyResolver.resolve(value).isPresent()) {
                    matched.add(value);
                } else {
                    unmatched.add(value);
                }
            }
            assertThat(matched).hasSize(CSSTRPFY_BRANCH_COUNT)
                    .containsExactlyInAnyOrderElementsOf(testedAids);
            assertThat(unmatched).hasSize(ALL_BYTE_VALUES - CSSTRPFY_BRANCH_COUNT);
            assertThat(matched).doesNotContainAnyElementsOf(unmatched);
        }

        @Test
        @DisplayName("resolve is total over all 256 bytes - never null, never throwing")
        void resolveIsTotalOverTheWholeByteDomain() {
            for (int candidate = Byte.MIN_VALUE; candidate <= Byte.MAX_VALUE; candidate++) {
                byte value = (byte) candidate;
                assertThat(PfKeyResolver.resolve(value))
                        .as("resolve(0x%02X) must return a non-null Optional", value)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("the 28 arms produce exactly the 16 declared tokens - all reachable, none extra")
        void theTwentyEightArmsProduceExactlyTheSixteenTokens() {
            List<AidKey> produced = new ArrayList<>();
            allBranchesInCopybookOrder().forEach(branch -> produced.add(resolvedTokenOf(branch.aid())));
            assertThat(new LinkedHashSet<>(produced))
                    .hasSize(CCARD_AID_CONDITION_COUNT)
                    .containsExactlyInAnyOrder(AidKey.values());
        }
    }

    static Stream<Arguments> aidsWithNoBranch() {
        return Stream.of(
                Arguments.of(
                        "DFHPA3 - a real CICS AID with no CSSTRPFY arm", CicsAid.DFHPA3),
                Arguments.of(
                        "DFHNULL - the EBCDIC space, 0x40", CicsAid.DFHNULL),
                Arguments.of("the NUL byte, 0x00", (byte) 0x00),
                Arguments.of("ASCII 'A', 0x41", (byte) 0x41),
                Arguments.of("ASCII 'Z', 0x5A", (byte) 0x5A),
                Arguments.of("EBCDIC 'a', 0x81", (byte) 0x81),
                Arguments.of("DFHCLRP - clear partition", CicsAid.DFHCLRP),
                Arguments.of("DFHPEN - cursor select", CicsAid.DFHPEN),
                Arguments.of("DFHOPID - operator id reader", CicsAid.DFHOPID),
                Arguments.of("DFHMSRE - magnetic slot reader", CicsAid.DFHMSRE),
                Arguments.of("DFHSTRF - structured field", CicsAid.DFHSTRF),
                Arguments.of("DFHTRIG - trigger field", CicsAid.DFHTRIG),
                Arguments.of("Byte.MIN_VALUE, 0x80", Byte.MIN_VALUE),
                Arguments.of("Byte.MAX_VALUE, 0x7F - the same byte as the unmapped DFHTRIG", Byte.MAX_VALUE),
                Arguments.of("0xFF, all bits set", (byte) 0xFF));
    }

    @Nested
    @DisplayName("No match - the absent WHEN OTHER, reproduced rather than repaired")
    class NoMatchBehaviour {
        @ParameterizedTest(name = "[{index}] {0} -> no match")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#aidsWithNoBranch")
        @DisplayName("an AID with no WHEN arm yields the explicit no-match outcome")
        void unmatchedAidYieldsTheExplicitNoMatchOutcome(String description, byte aid) {
            assertThat(PfKeyResolver.resolve(aid)).as(description).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] {0} -> no token at all")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#aidsWithNoBranch")
        @DisplayName("no unmatched AID yields a token - not an empty one, not a blank one, none")
        void unmatchedAidYieldsNoTokenAtAll(String description, byte aid) {
            assertThat(PfKeyResolver.resolve(aid).map(AidKey::token)).as(description).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] {0} is none of the 16 tokens")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#aidsWithNoBranch")
        @DisplayName("the no-match outcome is distinguishable from every one of the 16 tokens")
        void theNoMatchOutcomeIsNotAnyOfTheSixteenTokens(String description, byte aid) {
            Set<AidKey> allSixteen = new LinkedHashSet<>(List.of(AidKey.values()));
            assertThat(allSixteen).hasSize(CCARD_AID_CONDITION_COUNT);
            Optional<AidKey> result = PfKeyResolver.resolve(aid);
            for (AidKey token : allSixteen) {
                assertThat(result)
                        .as("%s must not be reported as %s", description, token.name())
                        .isNotEqualTo(Optional.of(token));
            }
            assertThat(result.filter(allSixteen::contains)).as(description).isEmpty();
        }

        @Test
        @DisplayName("DFHPA3 resolves to no match and does NOT throw - it is a real AID, just unmapped")
        void dfhPa3ResolvesToNoMatchWithoutThrowing() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isNotNull();
        }

        @Test
        @DisplayName("DFHPA1 and DFHPA2 DO have arms even though DFHPA3 does not")
        void thePaArmsThatExistAreNotConfusedWithTheOneThatDoesNot() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).isPresent();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).isPresent();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
        }

        @Test
        @DisplayName("no unmatched byte is quietly treated as ENTER")
        void unmatchedIsNeverSilentlyEnter() {
            aidsWithNoBranch().forEach(argument -> {
                byte aid = (byte) argument.get()[1];
                assertThat(PfKeyResolver.resolve(aid))
                        .as("%s must not be laundered into ENTER", argument.get()[0])
                        .isEmpty()
                        .isNotEqualTo(Optional.of(AidKey.ENTER));
            });
        }

        @Test
        @DisplayName("no unmatched byte is treated as CLEAR either - no default arm of any kind")
        void unmatchedIsNeverSilentlyClear() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isNotEqualTo(Optional.of(AidKey.CLEAR));
            assertThat(PfKeyResolver.resolve((byte) 0x00)).isNotEqualTo(Optional.of(AidKey.CLEAR));
        }

        @Test
        @DisplayName("resolution is repeatable - the same byte gives the same answer every time")
        void resolutionIsRepeatable() {
            for (int repetition = 0; repetition < 3; repetition++) {
                assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
                assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
                assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("storePfKey - retain the previous token on no match, never clear it")
    class StorePfKeyBehaviour {
        @Test
        @DisplayName("a matched AID replaces whatever token was there before")
        void aMatchReplacesTheExistingToken() {
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPF3, Optional.of(AidKey.ENTER)))
                    .contains(AidKey.PFK03);
        }

        @Test
        @DisplayName("a matched AID sets the token when the work area held none")
        void aMatchSetsTheTokenFromEmpty() {
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHENTER, Optional.empty()))
                    .contains(AidKey.ENTER);
        }

        @Test
        @DisplayName("an unmatched AID leaves the previous token STANDING - CCARD-AID is not cleared")
        void noMatchLeavesThePreviousTokenStanding() {
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK07)))
                    .contains(AidKey.PFK07);
            assertThat(PfKeyResolver.storePfKey((byte) 0x00, Optional.of(AidKey.ENTER)))
                    .contains(AidKey.ENTER);
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHNULL, Optional.of(AidKey.PA1)))
                    .contains(AidKey.PA1);
        }

        @Test
        @DisplayName("an unmatched AID against an empty work area stays empty - nothing is invented")
        void noMatchFromEmptyStaysEmpty() {
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.empty())).isEmpty();
        }

        @Test
        @DisplayName("the current token is handed back by identity, proving it is retained not rebuilt")
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
        @DisplayName("null is rejected even when the AID matches nothing, so the check is unconditional")
        void nullIsRejectedOnTheNoMatchPathToo() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PfKeyResolver.storePfKey(CicsAid.DFHPA3, null))
                    .withMessageContaining("currentAid");
        }

        @Test
        @DisplayName("storePfKey agrees with resolve for all 28 matched arms")
        void storePfKeyAgreesWithResolveOnEveryMatch() {
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                assertThat(PfKeyResolver.storePfKey(branch.aid(), Optional.empty()))
                        .as("%s", branch.mnemonic())
                        .isEqualTo(PfKeyResolver.resolve(branch.aid()))
                        .contains(branch.expectedToken());
            }
        }

        @Test
        @DisplayName("storePfKey never clears a token, for any of the 256 possible AID bytes")
        void storePfKeyNeverClearsAToken() {
            Optional<AidKey> current = Optional.of(AidKey.PFK09);
            for (int candidate = Byte.MIN_VALUE; candidate <= Byte.MAX_VALUE; candidate++) {
                byte value = (byte) candidate;
                assertThat(PfKeyResolver.storePfKey(value, current))
                        .as("storePfKey(0x%02X, PFK09) must never clear the token", value)
                        .isPresent();
            }
        }
    }

    @Nested
    @DisplayName("The 16 tokens as a set, their multiplicities, and both states of each 88-level (G50)")
    class TokenSetAndMultiplicity {
        @Test
        @DisplayName("the 28 arms produce exactly 16 distinct tokens - no seventeenth, none missing")
        void theTwentyEightArmsProduceExactlySixteenDistinctTokens() {
            Set<AidKey> produced = new LinkedHashSet<>();
            allBranchesInCopybookOrder().forEach(branch -> produced.add(resolvedTokenOf(branch.aid())));
            assertThat(produced)
                    .hasSize(CCARD_AID_CONDITION_COUNT)
                    .containsExactlyInAnyOrder(AidKey.values());
        }

        @Test
        @DisplayName("multiplicities: ENTER, CLEAR, PA1 and PA2 once each; every PFKnn exactly twice")
        void everyTokenAppearsWithItsExpectedMultiplicity() {
            Map<AidKey, Integer> multiplicity = new EnumMap<>(AidKey.class);
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                multiplicity.merge(resolvedTokenOf(branch.aid()), 1, Integer::sum);
            }

            assertThat(multiplicity).hasSize(CCARD_AID_CONDITION_COUNT);
            assertThat(multiplicity.get(AidKey.ENTER)).as("ENTER, from DFHENTER only").isEqualTo(1);
            assertThat(multiplicity.get(AidKey.CLEAR)).as("CLEAR, from DFHCLEAR only").isEqualTo(1);
            assertThat(multiplicity.get(AidKey.PA1)).as("PA1, from DFHPA1 only").isEqualTo(1);
            assertThat(multiplicity.get(AidKey.PA2)).as("PA2, from DFHPA2 only").isEqualTo(1);

            foldPairs().forEach(pair -> {
                AidKey token = resolvedTokenOf(pair.unshifted());
                assertThat(multiplicity.get(token))
                        .as("%s must be produced twice - by PF%d and PF%d",
                                token.name(), pair.keyNumber(), pair.keyNumber() + 12)
                        .isEqualTo(2);
            });

            assertThat(multiplicity.values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(CSSTRPFY_BRANCH_COUNT);
        }

        @Test
        @DisplayName("the four singly-produced tokens are exactly ENTER, CLEAR, PA1 and PA2")
        void onlyTheFourNonFunctionKeysAreProducedOnce() {
            Map<AidKey, Integer> multiplicity = new EnumMap<>(AidKey.class);
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                multiplicity.merge(resolvedTokenOf(branch.aid()), 1, Integer::sum);
            }
            List<AidKey> producedOnce = new ArrayList<>();
            List<AidKey> producedTwice = new ArrayList<>();
            multiplicity.forEach((token, count) -> {
                if (count == 1) {
                    producedOnce.add(token);
                } else if (count == 2) {
                    producedTwice.add(token);
                }
            });
            assertThat(producedOnce)
                    .containsExactlyInAnyOrder(AidKey.ENTER, AidKey.CLEAR, AidKey.PA1, AidKey.PA2);
            assertThat(producedTwice).hasSize(12).doesNotContain(AidKey.ENTER, AidKey.CLEAR);
        }

        @ParameterizedTest(name = "[{index}] 88-level CCARD-AID-{0} is TRUE for its own AID")
        @EnumSource(AidKey.class)
        @DisplayName("each of the 16 88-levels is TRUE for at least one of the 28 AIDs")
        void eachConditionIsTrueForItsOwnAid(AidKey condition) {
            List<String> producingMnemonics = new ArrayList<>();
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                if (resolvedTokenOf(branch.aid()) == condition) {
                    producingMnemonics.add(branch.mnemonic());
                }
            }
            assertThat(producingMnemonics)
                    .as("CCARD-AID-%s must be settable by a tested AID", condition.name())
                    .isNotEmpty();
        }

        @ParameterizedTest(name = "[{index}] 88-level CCARD-AID-{0} is FALSE for another AID and on no match")
        @EnumSource(AidKey.class)
        @DisplayName("each of the 16 88-levels is FALSE for some other AID and for an unmapped one")
        void eachConditionIsFalseForAnotherAidAndOnNoMatch(AidKey condition) {
            AidBranch other = allBranchesInCopybookOrder().stream()
                    .filter(branch -> resolvedTokenOf(branch.aid()) != condition)
                    .findFirst()
                    .orElseThrow();
            assertThat(PfKeyResolver.resolve(other.aid()).filter(token -> token == condition))
                    .as("CCARD-AID-%s must be false for %s", condition.name(), other.mnemonic())
                    .isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3).filter(token -> token == condition))
                    .as("CCARD-AID-%s must be false for the unmapped DFHPA3", condition.name())
                    .isEmpty();
        }

        @Test
        @DisplayName("exactly one 88-level is true at a time - the conditions are mutually exclusive")
        void exactlyOneConditionIsTrueAtATime() {
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                AidKey produced = resolvedTokenOf(branch.aid());
                List<AidKey> trueConditions = new ArrayList<>();
                for (AidKey condition : AidKey.values()) {
                    if (produced == condition) {
                        trueConditions.add(condition);
                    }
                }
                assertThat(trueConditions)
                        .as("%s must set exactly one condition", branch.mnemonic())
                        .containsExactly(branch.expectedToken());
            }
        }

        @Test
        @DisplayName("no 88-level is true on no match - zero conditions hold for an unmapped AID")
        void noConditionIsTrueOnNoMatch() {
            List<AidKey> trueConditions = new ArrayList<>();
            for (AidKey condition : AidKey.values()) {
                if (PfKeyResolver.resolve(CicsAid.DFHPA3).filter(token -> token == condition).isPresent()) {
                    trueConditions.add(condition);
                }
            }
            assertThat(trueConditions).isEmpty();
        }
    }

    static Stream<Arguments> inlineTestedMnemonics() {
        return Stream.of(
                Arguments.of("DFHENTER", CicsAid.DFHENTER, 16),
                Arguments.of("DFHPF3", CicsAid.DFHPF3, 14),
                Arguments.of("DFHPF4", CicsAid.DFHPF4, 6),
                Arguments.of("DFHPF5", CicsAid.DFHPF5, 4),
                Arguments.of("DFHPF7", CicsAid.DFHPF7, 4),
                Arguments.of("DFHPF8", CicsAid.DFHPF8, 4),
                Arguments.of("DFHPF12", CicsAid.DFHPF12, 2));
    }

    @Nested
    @DisplayName("Inline-tester equivalence - identical booleans for the 12 non-copying programs")
    class InlineTesterEquivalence {
        @ParameterizedTest(name = "[{index}] {0} is matched by isAid and by nothing else")
        @MethodSource("com.vsergeychik.carddemo.common.PfKeyResolverTest#inlineTestedMnemonics")
        @DisplayName("isAid is exact - true for the AID under test, false for every other inline AID")
        void isAidIsExactAcrossTheInlineSet(String mnemonic, byte aid, int occurrences) {
            assertThat(occurrences).as("%s is tested inline at least once", mnemonic).isPositive();
            assertThat(PfKeyResolver.isAid(aid, aid)).as("%s equals itself", mnemonic).isTrue();
            inlineTestedMnemonics().forEach(other -> {
                byte otherAid = (byte) other.get()[1];
                if (otherAid != aid) {
                    assertThat(PfKeyResolver.isAid(aid, otherAid))
                            .as("%s must not equal %s", mnemonic, other.get()[0])
                            .isFalse();
                }
            });
        }

        @Test
        @DisplayName("DFHENTER - 16 inline sites; isEnter is true for it and false for others")
        void enterPredicate() {
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHCLEAR)).isFalse();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHPA3)).isFalse();
        }

        @Test
        @DisplayName("DFHPF3 - 14 inline sites, the conventional back key; both states asserted")
        void pf3Predicate() {
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHENTER)).isFalse();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF4)).isFalse();
        }

        @Test
        @DisplayName("DFHPF4 - 6 inline sites; both states asserted")
        void pf4Predicate() {
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4)).isTrue();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF5)).isFalse();
        }

        @Test
        @DisplayName("DFHPF5 - 4 inline sites; both states asserted")
        void pf5Predicate() {
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF5)).isTrue();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF4)).isFalse();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF7)).isFalse();
        }

        @Test
        @DisplayName("DFHPF7 - 4 inline sites, page backward; both states asserted")
        void pf7Predicate() {
            assertThat(PfKeyResolver.isPf7(CicsAid.DFHPF7)).isTrue();
            assertThat(PfKeyResolver.isPf7(CicsAid.DFHPF8)).isFalse();
            assertThat(PfKeyResolver.isPf7(CicsAid.DFHENTER)).isFalse();
        }

        @Test
        @DisplayName("DFHPF8 - 4 inline sites, page forward; both states asserted")
        void pf8Predicate() {
            assertThat(PfKeyResolver.isPf8(CicsAid.DFHPF8)).isTrue();
            assertThat(PfKeyResolver.isPf8(CicsAid.DFHPF7)).isFalse();
            assertThat(PfKeyResolver.isPf8(CicsAid.DFHENTER)).isFalse();
        }

        @Test
        @DisplayName("DFHPF12 - 2 inline sites; both states asserted")
        void pf12Predicate() {
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF12)).isTrue();
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF11)).isFalse();
        }

        @Test
        @DisplayName("each named predicate agrees with isAid against the same CicsAid constant")
        void namedPredicatesDelegateFaithfully() {
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                byte aid = branch.aid();
                assertThat(PfKeyResolver.isEnter(aid)).isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHENTER));
                assertThat(PfKeyResolver.isPf3(aid)).isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHPF3));
                assertThat(PfKeyResolver.isPf4(aid)).isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHPF4));
                assertThat(PfKeyResolver.isPf5(aid)).isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHPF5));
                assertThat(PfKeyResolver.isPf7(aid)).isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHPF7));
                assertThat(PfKeyResolver.isPf8(aid)).isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHPF8));
                assertThat(PfKeyResolver.isPf12(aid)).isEqualTo(PfKeyResolver.isAid(aid, CicsAid.DFHPF12));
            }
        }

        @Test
        @DisplayName("exactly one named predicate holds for its own key, across all 28 tested AIDs")
        void onlyTheMatchingNamedPredicateHolds() {
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                byte aid = branch.aid();
                List<Boolean> holds = List.of(
                        PfKeyResolver.isEnter(aid), PfKeyResolver.isPf3(aid), PfKeyResolver.isPf4(aid),
                        PfKeyResolver.isPf5(aid), PfKeyResolver.isPf7(aid), PfKeyResolver.isPf8(aid),
                        PfKeyResolver.isPf12(aid));
                long trueCount = holds.stream().filter(Boolean::booleanValue).count();
                assertThat(trueCount)
                        .as("at most one of the seven inline predicates may hold for %s", branch.mnemonic())
                        .isLessThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("the fold does NOT leak into byte equality - PF3 is not PF15, though both give PFK03")
        void theFoldDoesNotLeakIntoTheEqualityPredicate() {
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF15)).isFalse();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF16)).isFalse();
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF24)).isFalse();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHPF13)).isFalse();

            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15)).isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF3));
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24)).isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF12));
        }

        @Test
        @DisplayName("isAid works for AIDs the paragraph has no arm for, carrying no mapping semantics")
        void isAidWorksForUnmappedAids() {
            assertThat(PfKeyResolver.isAid(CicsAid.DFHPA3, CicsAid.DFHPA3)).isTrue();
            assertThat(PfKeyResolver.isAid(CicsAid.DFHPA3, CicsAid.DFHPA1)).isFalse();
            assertThat(PfKeyResolver.isAid(CicsAid.DFHNULL, CicsAid.DFHNULL)).isTrue();
        }

        @Test
        @DisplayName("isAid is exact over the whole byte domain - true for one value of 256, false for 255")
        void isAidIsExactOverTheWholeByteDomain() {
            int matches = 0;
            for (int candidate = Byte.MIN_VALUE; candidate <= Byte.MAX_VALUE; candidate++) {
                if (PfKeyResolver.isAid((byte) candidate, CicsAid.DFHPF3)) {
                    matches++;
                }
            }
            assertThat(matches).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Statelessness and class shape (G53) - no mutable static state anywhere")
    class StatelessnessAndClassShape {
        @Test
        @DisplayName("PfKeyResolver declares no non-final static field")
        void theResolverDeclaresNoMutableStaticField() {
            List<Field> mutable = staticFieldsOf(PfKeyResolver.class).stream()
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .toList();
            assertThat(mutable)
                    .as("PfKeyResolver must hold no mutable static state")
                    .isEmpty();
        }

        @Test
        @DisplayName("PfKeyResolver declares no instance field either - there is nothing to instantiate")
        void theResolverDeclaresNoInstanceField() {
            List<Field> instanceFields = new ArrayList<>();
            for (Field field : PfKeyResolver.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    instanceFields.add(field);
                }
            }
            assertThat(instanceFields).isEmpty();
        }

        @Test
        @DisplayName("AidKey declares no non-final static field and no mutable instance field")
        void theEnumHoldsNoMutableState() {
            for (Field field : AidKey.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("AidKey.%s must be final", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("this test class declares no non-final static field - its tables are methods")
        void theTestClassItselfHoldsNoMutableStaticState() {
            List<Field> mutable = staticFieldsOf(PfKeyResolverTest.class).stream()
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .toList();
            assertThat(mutable).isEmpty();
        }

        @Test
        @DisplayName("the tables are fresh per call - mutating one cannot affect the next")
        void theTablesAreFreshPerCall() {
            List<AidBranch> first = allBranchesInCopybookOrder();
            List<AidBranch> second = allBranchesInCopybookOrder();
            assertThat(first).isNotSameAs(second).isEqualTo(second);
            first.clear();
            assertThat(allBranchesInCopybookOrder()).hasSize(CSSTRPFY_BRANCH_COUNT);
        }

        @Test
        @DisplayName("resolving A then B then A again gives A the same answer both times")
        void resolutionIsStatelessAcrossInterleavedCalls() {
            Optional<AidKey> firstA = PfKeyResolver.resolve(CicsAid.DFHPF7);
            Optional<AidKey> b = PfKeyResolver.resolve(CicsAid.DFHPA1);
            Optional<AidKey> secondA = PfKeyResolver.resolve(CicsAid.DFHPF7);
            assertThat(firstA).contains(AidKey.PFK07);
            assertThat(b).contains(AidKey.PA1);
            assertThat(secondA).isEqualTo(firstA);

            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF7)).isEqualTo(firstA);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
        }

        @Test
        @DisplayName("storePfKey is stateless too - it reads only its arguments")
        void storePfKeyIsStateless() {
            Optional<AidKey> screenOne = PfKeyResolver.storePfKey(CicsAid.DFHPF3, Optional.empty());
            Optional<AidKey> screenTwo = PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK08));
            assertThat(screenOne).contains(AidKey.PFK03);
            assertThat(screenTwo).contains(AidKey.PFK08);
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.empty())).isEmpty();
            assertThat(screenOne).contains(AidKey.PFK03);
            assertThat(screenTwo).contains(AidKey.PFK08);
        }

        @Test
        @DisplayName("the resolver is final, so its mapping cannot be overridden by a subclass")
        void theResolverIsFinal() {
            assertThat(Modifier.isFinal(PfKeyResolver.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the resolver is not instantiable, reflection included")
        void theResolverIsNotInstantiable() throws ReflectiveOperationException {
            Constructor<PfKeyResolver> constructor = PfKeyResolver.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("resolveWithoutFolding and primaryAid - the twelve programs that never copied CSSTRPFY")
    class WithoutFolding {
        @Test
        @DisplayName("each token names the EIBAID byte of its own WHEN clause, never its folded partner")
        void everyTokenNamesItsOwnByte() {
            assertThat(AidKey.ENTER.primaryAid()).isEqualTo(CicsAid.DFHENTER);
            assertThat(AidKey.CLEAR.primaryAid()).isEqualTo(CicsAid.DFHCLEAR);
            assertThat(AidKey.PA1.primaryAid()).isEqualTo(CicsAid.DFHPA1);
            assertThat(AidKey.PA2.primaryAid()).isEqualTo(CicsAid.DFHPA2);
            assertThat(AidKey.PFK01.primaryAid()).isEqualTo(CicsAid.DFHPF1);
            assertThat(AidKey.PFK02.primaryAid()).isEqualTo(CicsAid.DFHPF2);
            assertThat(AidKey.PFK03.primaryAid()).isEqualTo(CicsAid.DFHPF3);
            assertThat(AidKey.PFK04.primaryAid()).isEqualTo(CicsAid.DFHPF4);
            assertThat(AidKey.PFK05.primaryAid()).isEqualTo(CicsAid.DFHPF5);
            assertThat(AidKey.PFK06.primaryAid()).isEqualTo(CicsAid.DFHPF6);
            assertThat(AidKey.PFK07.primaryAid()).isEqualTo(CicsAid.DFHPF7);
            assertThat(AidKey.PFK08.primaryAid()).isEqualTo(CicsAid.DFHPF8);
            assertThat(AidKey.PFK09.primaryAid()).isEqualTo(CicsAid.DFHPF9);
            assertThat(AidKey.PFK10.primaryAid()).isEqualTo(CicsAid.DFHPF10);
            assertThat(AidKey.PFK11.primaryAid()).isEqualTo(CicsAid.DFHPF11);
            assertThat(AidKey.PFK12.primaryAid()).isEqualTo(CicsAid.DFHPF12);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(AidKey.class)
        @DisplayName("a token's own byte round-trips through the non-folding resolver, for all sixteen")
        void everyPrimaryByteRoundTrips(AidKey key) {
            assertThat(PfKeyResolver.resolveWithoutFolding(key.primaryAid()))
                    .as("the byte a token was declared with must name that token again")
                    .contains(key);
            assertThat(PfKeyResolver.resolve(key.primaryAid()))
                    .as("and the folding resolver agrees on every primary byte - the fold only adds "
                            + "twelve extra bytes, it never moves an existing one")
                    .contains(key);
        }

        @ParameterizedTest(name = "DFHPF{0} folds to PFK{1} but names nothing on its own")
        @CsvSource({"13, 01", "14, 02", "15, 03", "16, 04", "17, 05", "18, 06",
            "19, 07", "20, 08", "21, 09", "22, 10", "23, 11", "24, 12"})
        @DisplayName("the twelve folded bytes resolve to nothing, so an inline tester reaches WHEN OTHER")
        void theFoldedTwelveNameNothing(int pfNumber, String foldedOnto) {
            byte upper = aidByte("DFHPF" + pfNumber);
            AidKey folded = AidKey.valueOf("PFK" + foldedOnto);

            assertThat(PfKeyResolver.resolve(upper))
                    .as("CSSTRPFY L54-L77 does fold it - that is the copybook, and it stands")
                    .contains(folded);
            assertThat(PfKeyResolver.resolveWithoutFolding(upper))
                    .as("but the byte is not that token's own, so a program with no CSSTRPFY sees no "
                            + "match and takes its WHEN OTHER arm")
                    .isEmpty();
            assertThat(folded.primaryAid())
                    .as("the fold target's own byte is the lower key, never this one")
                    .isNotEqualTo(upper);
        }

        @Test
        @DisplayName("a byte no resolver tests is empty in both forms")
        void anUntestedByteIsEmptyInBothForms() {
            for (byte untested : new byte[] {CicsAid.DFHPA3, CicsAid.DFHNULL, CicsAid.DFHPEN}) {
                assertThat(PfKeyResolver.resolve(untested)).isEmpty();
                assertThat(PfKeyResolver.resolveWithoutFolding(untested)).isEmpty();
            }
        }

        @Test
        @DisplayName("across all 256 byte values the non-folding form yields exactly sixteen matches")
        void exactlySixteenBytesNameAToken() {
            List<Integer> naming = new ArrayList<>();
            for (int value = 0; value < 256; value++) {
                if (PfKeyResolver.resolveWithoutFolding((byte) value).isPresent()) {
                    naming.add(value);
                }
            }

            assertThat(naming)
                    .as("one byte per token, which is what 'no fold' means")
                    .hasSize(AidKey.values().length)
                    .hasSize(16);

            long folding = 0;
            for (int value = 0; value < 256; value++) {
                if (PfKeyResolver.resolve((byte) value).isPresent()) {
                    folding++;
                }
            }
            assertThat(folding).isEqualTo(28L);
        }

        private static byte aidByte(String constantName) {
            try {
                Field constant = CicsAid.class.getDeclaredField(constantName);
                return constant.getByte(null);
            } catch (ReflectiveOperationException absent) {
                throw new AssertionError("CicsAid does not declare " + constantName, absent);
            }
        }
    }
}
