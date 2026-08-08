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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity tests for {@link PfKeyResolver}, the Java translation of the {@code YYYY-STORE-PFKEY}
 * paragraph held in {@code app/cpy/CSSTRPFY.cpy}, which maps a raw CICS {@code EIBAID} byte onto
 * the five-character {@code CCARD-AID} token declared at {@code app/cpy/CVCRD01Y.cpy} line 3.
 *
 * <h2>Where every expected value in this file comes from</h2>
 *
 * <p>The legacy COBOL <strong>cannot be executed in this environment</strong> - that limitation is
 * documented with eight independently verified blockers and tracked as open risk <strong>R-A</strong>
 * in the Agent Action Plan - so not one expectation below was captured from a live run. Every one was
 * derived statically, by reading the two authoritative copybooks and transcribing them. Practice
 * <strong>B12</strong> requires that provenance be written where a reader will find it rather than
 * absorbed silently, so the two sources are named precisely:
 *
 * <ul>
 *   <li><strong>{@code app/cpy/CSSTRPFY.cpy}</strong> supplies the <em>branch table</em>. Lines 17
 *       to 82 declare the paragraph {@code YYYY-STORE-PFKEY.} under the banner "Map AID to PFKey in
 *       COMMON Area", containing one {@code EVALUATE TRUE} that opens at line 21, carries its
 *       {@code WHEN} arms from line 22 to line 77, and closes with {@code END-EVALUATE} at line 78.
 *       {@link #allBranchesInCopybookOrder()} is a line-for-line transcription of those arms, and
 *       every row records the copybook line it came from so this file can be diffed against the
 *       copybook top to bottom.</li>
 *   <li><strong>{@code app/cpy/CVCRD01Y.cpy}</strong> supplies the <em>token vocabulary</em>. Line 3
 *       declares {@code 10 CCARD-AID PIC X(5).} and lines 4 to 19 declare its sixteen {@code 88}
 *       level condition names with their literal values.</li>
 * </ul>
 *
 * <p>Practice <strong>B3</strong> keeps those inputs immutable and out of the runtime path: the table
 * is transcribed into Java here, and no test in this file opens, reads or writes anything under
 * {@code app/}. The suite has no file system dependency at all, which is also why it cannot be
 * broken by an unrelated change to the reference tree.
 *
 * <h2>Discrepancy recorded, not silently resolved: the branch count is 28, not 26</h2>
 *
 * <p>The package-level requirement for this class states that the paragraph has
 * <strong>26</strong> branches. Direct verification in this checkout contradicts it:
 * {@code grep -c 'WHEN EIBAID' app/cpy/CSSTRPFY.cpy} returns <strong>28</strong>, and
 * {@code grep -c 'SET CCARD-AID' app/cpy/CSSTRPFY.cpy} independently returns <strong>28</strong> as
 * well, one {@code SET} per {@code WHEN}. The two arms the figure of 26 leaves out are
 * <strong>{@code DFHPA1}</strong> (copybook line 26) and <strong>{@code DFHPA2}</strong> (line 28):
 * four plus twelve plus twelve is twenty-eight, and the requirement's own itemised table numbers its
 * last row group 17 to 28.
 *
 * <p>Practice <strong>B4</strong> forbids resolving that quietly in either direction. This suite
 * therefore asserts the <strong>source-verified 28</strong> - behaviour comes from the source, and
 * 28 subsumes 26 and so satisfies the requirement's evident intent - and records the discrepancy
 * here instead of picking a number and moving on. Dropping two rows to make a stated total agree
 * would have left the PA1 and PA2 keys untested, which is exactly the class of silent defect this
 * migration exists to prevent.
 *
 * <h2>The property that shapes the whole suite: there is no {@code WHEN OTHER}</h2>
 *
 * <p>Two facts about the source, both verified rather than assumed, together determine what an
 * unrecognised AID must do:
 *
 * <ol>
 *   <li>{@code grep -c 'WHEN OTHER' app/cpy/CSSTRPFY.cpy} returns <strong>0</strong>. The
 *       {@code EVALUATE} ends at the {@code DFHPF24} arm on line 76 and falls straight through to
 *       {@code END-EVALUATE}. There is no default arm.</li>
 *   <li>Nothing clears {@code CCARD-AID} beforehand: between the paragraph label on line 17 and the
 *       {@code EVALUATE} on line 21 there is no {@code MOVE} and no {@code SET} - zero matches.</li>
 * </ol>
 *
 * <p>So on the mainframe an unmatched {@code EIBAID} executes no {@code SET} at all and
 * {@code CCARD-AID} simply <em>retains whatever the previous interaction left in it</em>. Practice
 * <strong>B5</strong> forbids tidying that away. A Java resolver cannot literally retain a prior
 * value without holding hidden state, which practice <strong>B9</strong> and gate <strong>G53</strong>
 * forbid, so the translation splits the behaviour in two and this suite asserts both halves:
 * {@link PfKeyResolver#resolve(byte)} reports <strong>no match explicitly</strong> as an empty
 * {@link Optional}, and {@link PfKeyResolver#storePfKey(byte, Optional)} applies the consequence by
 * handing the caller's existing token straight back. That is a deliberate, documented translation
 * decision, not an omission.
 *
 * <h2>Why defaulting an unknown key to {@code ENTER} would be a real defect</h2>
 *
 * <p>The most tempting wrong "helpful default" is to treat an unrecognised key as {@code ENTER}, so
 * the suite rules it out explicitly rather than leaving it implied by an emptiness check. The source
 * shows why it matters, and shows it in the consumer rather than in the paragraph:
 * {@code app/cbl/COCRDLIC.cbl} lines 370 to 380 read {@code SET PFK-INVALID TO TRUE}, then
 * {@code IF CCARD-AID-ENTER OR CCARD-AID-PFK03 OR CCARD-AID-PFK07 OR CCARD-AID-PFK08} then
 * {@code SET PFK-VALID TO TRUE}, and only then {@code IF PFK-INVALID} then
 * {@code SET CCARD-AID-ENTER TO TRUE}.
 *
 * <p>The fall-back to {@code ENTER} is therefore a decision the <em>consumer</em> makes at line 379,
 * after it has already classified the key as invalid. Had the resolver defaulted to {@code ENTER}
 * itself, the guard at line 371 would have matched, {@code PFK-VALID} would have been set, and
 * {@code PFK-INVALID} would never have fired - laundering an invalid key into a valid one at the
 * precise point the program decides validity, and losing the invalid-key path entirely.
 *
 * <h2>Serving both consumption styles, five copiers and twelve inline testers</h2>
 *
 * <p>{@code CSSTRPFY} is copied by exactly <strong>five</strong> of the seventeen CICS online
 * programs - {@code COACTUPC} line 4199, {@code COACTVWC} line 913, {@code COCRDLIC} line 1416,
 * {@code COCRDSLC} line 855 and {@code COCRDUPC} line 1528, all in the quoted
 * {@code COPY 'CSSTRPFY'} spelling. Those five consume the resolved <em>token</em>, through chains
 * such as {@code IF CCARD-AID-ENTER OR CCARD-AID-PFK03 ...}, which is why token identity has to be
 * exact down to the trailing spaces.
 *
 * <p>The other <strong>twelve</strong> never copied the paragraph and test {@code EIBAID}
 * <em>inline</em> instead, in the shape at {@code app/cbl/COMEN01C.cbl} lines 93 to 102:
 * {@code EVALUATE EIBAID / WHEN DFHENTER / WHEN DFHPF3 / WHEN OTHER}. One resolver now serves both
 * styles, so it must reproduce both sets of boolean outcomes identically, and this suite asserts the
 * inline side through {@link PfKeyResolver#isAid(byte, byte)} and its named delegates. Note the
 * asymmetry, because it is the reason the resolver must stay silent on no match: each of those twelve
 * programs supplies its <em>own</em> {@code WHEN OTHER} - every one of the twelve was checked and
 * every one has at least two - so the unknown-key decision belongs to the call site in both styles.
 *
 * <p>The inline tests concentrate on seven mnemonics, whose occurrence counts across {@code app/cbl}
 * were counted directly: {@code DFHENTER} 16, {@code DFHPF3} 14, {@code DFHPF4} 6, {@code DFHPF5} 4,
 * {@code DFHPF7} 4, {@code DFHPF8} 4 and {@code DFHPF12} 2. Each of the seven is asserted by name in
 * addition to appearing in the sweep, because a break in any one of them breaks a controller.
 *
 * <h2>Gates this suite is accountable for</h2>
 *
 * <ul>
 *   <li><strong>G30</strong> - {@code EVALUATE} order is preserved with {@code WHEN OTHER} last.
 *       Here the finding is that there <em>is</em> no {@code WHEN OTHER}, so
 *       {@link BranchOrderAndCompleteness} proves no default was invented, by exhausting all 256
 *       byte values rather than by sampling.</li>
 *   <li><strong>G49</strong> - branch coverage of at least 0.90, enforced per package as well as for
 *       the bundle. {@link PfKeyResolver#resolve(byte)} carries the largest branch surface in
 *       {@code com.vsergeychik.carddemo.common}, and every one of its arms is driven below,
 *       including the default.</li>
 *   <li><strong>G50</strong> - all sixteen {@code CCARD-AID} {@code 88}-level conditions driven in
 *       both their true and their false state; see {@link TokenSetAndMultiplicity}.</li>
 *   <li><strong>G52</strong> - no wildcard imports, static imports included, so every symbol used
 *       here is traceable to its declaring type.</li>
 *   <li><strong>G53</strong> - no mutable static state. This file declares <em>no static field at
 *       all</em>: its tables are static methods returning a fresh value per call, so no test can
 *       observe another's mutation. {@link StatelessnessAndClassShape} audits the class under test,
 *       its enum and this test class reflectively.</li>
 *   <li><strong>G54</strong> - the suite is plain JUnit 5. There is no Spring context, no Mockito, no
 *       container, no clock and no randomness, so it runs non-interactively and deterministically.</li>
 * </ul>
 *
 * <p>No project-specific rules were supplied for this migration; the enterprise practices
 * <strong>B1</strong> through <strong>B12</strong> referenced above govern in their place, and their
 * absence was not treated as licence to relax any of them. Only the pinned stack is used - JUnit
 * Jupiter and AssertJ, both arriving through {@code spring-boot-starter-test} (practices
 * <strong>B1</strong> and <strong>B2</strong>).
 *
 * @see PfKeyResolver
 * @see CicsAid
 */
@DisplayName("PfKeyResolver - CSSTRPFY YYYY-STORE-PFKEY, EIBAID to CCARD-AID")
class PfKeyResolverTest {

    /**
     * The number of {@code WHEN EIBAID} arms in {@code app/cpy/CSSTRPFY.cpy}, verified in this
     * checkout by {@code grep -c 'WHEN EIBAID'}.
     *
     * <p>Declared as a named local constant rather than written as a bare literal at each use so
     * that the count appears once and the discrepancy documented in the class comment - the
     * requirement says 26, the source says 28, and the two omitted arms are {@code DFHPA1} and
     * {@code DFHPA2} - has exactly one place to be reconciled.
     */
    private static final int CSSTRPFY_BRANCH_COUNT = 28;

    /**
     * The number of {@code 88}-level condition names on {@code CCARD-AID}, from
     * {@code app/cpy/CVCRD01Y.cpy} lines 4 to 19.
     */
    private static final int CCARD_AID_CONDITION_COUNT = 16;

    /**
     * The number of distinct byte values a {@code PIC X(1)} field can hold, used to make the
     * "no {@code WHEN OTHER} was invented" proof exhaustive rather than sampled.
     */
    private static final int ALL_BYTE_VALUES = 256;

    /**
     * One {@code WHEN} arm of the {@code EVALUATE TRUE} in {@code app/cpy/CSSTRPFY.cpy}.
     *
     * <p>A record rather than a raw {@code Arguments} row so that the table is type checked at
     * compile time and needs no casting when it is iterated: an argument list of loosely typed
     * objects is exactly where a transposed AID and token would hide. {@code copybookLine} is
     * carried purely so a failure names the source line to open.
     *
     * @param mnemonic     the {@code DFHAID} mnemonic the arm tests, as the copybook spells it
     * @param aid          the raw EBCDIC AID byte, taken from the matching {@link CicsAid} constant
     * @param expectedToken the {@code CCARD-AID} condition the arm sets
     * @param copybookLine the line of {@code app/cpy/CSSTRPFY.cpy} carrying the {@code WHEN}
     */
    record AidBranch(String mnemonic, byte aid, AidKey expectedToken, int copybookLine) {

        /** {@return a display name naming the mnemonic, the expected token and the source line} */
        @Override
        public String toString() {
            return "%s -> '%s' (CSSTRPFY.cpy L%d)".formatted(mnemonic, expectedToken.token(), copybookLine);
        }
    }

    /**
     * All twenty-eight {@code WHEN} arms of {@code app/cpy/CSSTRPFY.cpy} lines 22 to 77, transcribed
     * in copybook order.
     *
     * <p>This is the single source of truth for the whole suite: the sweep, the fold checks, the
     * order audit, the completeness audit and the multiplicity audit all read it, so a mistranscribed
     * row cannot pass in one place while failing in another. The order is the copybook's order and is
     * asserted to be, by way of the strictly increasing {@code copybookLine} column.
     *
     * <p>Note the last twelve rows: {@code DFHPF13} through {@code DFHPF24} repeat the tokens
     * {@code PFK01} through {@code PFK12}. That repetition is the fold, and it is transcribed
     * explicitly rather than computed, because a computed fold would reproduce an off-by-one in the
     * implementation instead of catching it.
     *
     * @return a fresh, ordered list of all twenty-eight arms, never shared between tests
     */
    static List<AidBranch> allBranchesInCopybookOrder() {
        List<AidBranch> branches = new ArrayList<>();
        // CSSTRPFY.cpy L22-L29 - ENTER, CLEAR, and the two PA keys the paragraph tests.
        branches.add(new AidBranch("DFHENTER", CicsAid.DFHENTER, AidKey.ENTER, 22));
        branches.add(new AidBranch("DFHCLEAR", CicsAid.DFHCLEAR, AidKey.CLEAR, 24));
        branches.add(new AidBranch("DFHPA1", CicsAid.DFHPA1, AidKey.PA1, 26));
        branches.add(new AidBranch("DFHPA2", CicsAid.DFHPA2, AidKey.PA2, 28));
        // CSSTRPFY.cpy L30-L53 - PF1 through PF12, one token each.
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
        // CSSTRPFY.cpy L54-L77 - PF13 through PF24 FOLD BACK onto PFK01 through PFK12.
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

    /**
     * The twenty-eight arms as a {@code @MethodSource} stream, for the sweep that drives every one of
     * them through {@link PfKeyResolver#resolve(byte)}.
     *
     * @return a stream of all twenty-eight arms in copybook order
     */
    static Stream<AidBranch> everyCsstrpfyBranch() {
        return allBranchesInCopybookOrder().stream();
    }

    /**
     * The first sixteen arms, {@code app/cpy/CSSTRPFY.cpy} lines 22 to 53 - the ones that introduce a
     * token rather than repeating one.
     *
     * @return a stream of the sixteen token-introducing arms
     */
    static Stream<AidBranch> unfoldedBranches() {
        return allBranchesInCopybookOrder().stream().limit(CCARD_AID_CONDITION_COUNT);
    }

    /**
     * The last twelve arms, {@code app/cpy/CSSTRPFY.cpy} lines 54 to 77 - the fold, where
     * {@code DFHPF13} through {@code DFHPF24} repeat {@code PFK01} through {@code PFK12}.
     *
     * @return a stream of the twelve folded arms
     */
    static Stream<AidBranch> foldedBranches() {
        return allBranchesInCopybookOrder().stream().skip(CCARD_AID_CONDITION_COUNT);
    }

    /**
     * The twelve unshifted function-key arms only, {@code app/cpy/CSSTRPFY.cpy} lines 30 to 53 -
     * {@link #unfoldedBranches()} less the four {@code ENTER}, {@code CLEAR}, {@code PA1} and
     * {@code PA2} arms that precede them.
     *
     * @return a stream of the {@code DFHPF1} through {@code DFHPF12} arms, in key-number order
     */
    static Stream<AidBranch> lowFunctionKeyBranches() {
        return unfoldedBranches().skip(4);
    }

    /**
     * One unshifted and shifted function-key pair created by the fold, for instance PF1 with PF13.
     *
     * @param keyNumber the unshifted key number, 1 through 12
     * @param unshifted the {@code DFHPFn} AID byte
     * @param shifted   the {@code DFHPF(n+12)} AID byte, which must resolve to the same token
     */
    record FoldPair(int keyNumber, byte unshifted, byte shifted) {

        /** {@return a display name naming both keys of the pair} */
        @Override
        public String toString() {
            return "PF%d and PF%d".formatted(keyNumber, keyNumber + 12);
        }
    }

    /**
     * The twelve function-key pairs the fold creates, built by pairing the {@code DFHPF1} through
     * {@code DFHPF12} arms with the {@code DFHPF13} through {@code DFHPF24} arms positionally.
     *
     * <p>Derived from {@link #allBranchesInCopybookOrder()} rather than typed out a second time, so
     * the pairing cannot drift from the table the rest of the suite uses. The derivation is
     * positional - the nth low arm with the nth high arm - which is precisely the correspondence the
     * copybook establishes and which an off-by-one in the implementation would violate.
     *
     * @return a stream of the twelve unshifted and shifted pairs, in key-number order
     */
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

    /**
     * Resolves an AID and fails the test rather than returning an absent value, for the assertions
     * that are only meaningful once a match is established.
     *
     * @param aid the raw EBCDIC AID byte, which must be one of the twenty-eight the paragraph tests
     * @return the resolved token
     */
    private static AidKey resolvedTokenOf(byte aid) {
        Optional<AidKey> resolved = PfKeyResolver.resolve(aid);
        assertThat(resolved)
                .as("AID 0x%02X must be one of the %d tested arms", aid, CSSTRPFY_BRANCH_COUNT)
                .isPresent();
        return resolved.orElseThrow();
    }

    /**
     * Every static field declared by a type, for the reflective no-mutable-state audits.
     *
     * @param type the type to inspect
     * @return its declared static fields, synthetic members included
     */
    private static List<Field> staticFieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * The token vocabulary itself, from {@code app/cpy/CVCRD01Y.cpy} line 3 and lines 4 to 19.
     *
     * <p>These assertions are about the {@code PIC X(5)} field rather than about the mapping: they
     * establish that the sixteen literals are reproduced at the right width before any test relies on
     * a resolved token being correct. {@code PA1} and {@code PA2} are the trap, and they get the most
     * attention here.
     */
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
            // CVCRD01Y L6: 88 CCARD-AID-PA1 VALUE 'PA1  '. The two spaces are part of the value, not
            // incidental formatting: the field is PIC X(5) and the mnemonic is only three characters
            // long, so COBOL pads it to width. A trimmed "PA1" is three characters and is WRONG.
            assertThat(AidKey.PA1.token()).isEqualTo("PA1  ");
        }

        @Test
        @DisplayName("PA1's length is 5 - asserted separately, so a padding failure names itself")
        void pa1IsFiveCharactersLong() {
            // Deliberately a second, independent assertion rather than a chained one. If the padding
            // were dropped, an equality-only failure reports a confusing "expected 'PA1  ' but was
            // 'PA1'" whose two values look almost identical in a console; a length failure reports
            // "expected size 5 but was 3", which is unambiguous.
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
            // CVCRD01Y L7: 88 CCARD-AID-PA2 VALUE 'PA2  '.
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
            // Worth stating explicitly: "every token is 5 characters" holds for two different reasons
            // in this vocabulary. ENTER, CLEAR and the twelve PFKnn literals happen to BE five
            // characters, so they need no padding; PA1 and PA2 are three characters and reach five
            // only because COBOL pads a PIC X(5) VALUE clause on the right. A test that only asserted
            // the group invariant would pass while PA1 was silently stored trimmed, which is why the
            // padded pair is asserted individually above and the unpadded pair is asserted here.
            assertThat(AidKey.ENTER.token()).isEqualTo("ENTER").hasSize(5);
            assertThat(AidKey.CLEAR.token()).isEqualTo("CLEAR").hasSize(5);
            assertThat(AidKey.ENTER.token()).doesNotContain(" ");
            assertThat(AidKey.CLEAR.token()).doesNotContain(" ");
        }

        @Test
        @DisplayName("the twelve function-key tokens are PFK01 through PFK12, zero-padded to two digits")
        void functionKeyTokensAreZeroPaddedToTwoDigits() {
            // CVCRD01Y L8-L19. PFK01 rather than PFK1: the zero is what makes each literal exactly
            // five characters without any trailing space, so dropping it would break the field width
            // for eleven of the twelve.
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
            // Load-bearing for the no-match contract: because no token is empty or all-spaces, the
            // "no match" outcome cannot be confused with a token carrying an empty value.
            for (AidKey key : AidKey.values()) {
                assertThat(key.token()).as("token for %s", key.name()).isNotBlank();
            }
        }

        @Test
        @DisplayName("there is no PA3 condition and no NONE sentinel - CVCRD01Y declares neither")
        void thereIsNoPa3AndNoSentinelCondition() {
            // CSSTRPFY has no DFHPA3 arm and CVCRD01Y has no CCARD-AID-PA3, so a PA3 token would be
            // an invention. A NONE or UNRECOGNISED member would be worse: it would need a
            // five-character literal the copybook does not define, and would let an absent result
            // masquerade as a seventeenth condition.
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

    /**
     * The sweep: every one of the twenty-eight {@code WHEN} arms driven through
     * {@link PfKeyResolver#resolve(byte)} in one parameterised test.
     *
     * <p>The inputs are the real {@link CicsAid} constants rather than byte literals, so the sweep
     * exercises the actual values the {@code switch} in the implementation compares against. Were a
     * constant and a {@code case} label to disagree, a literal-driven test could pass while the
     * production path failed.
     *
     * <p>The sweep is <strong>self-checking</strong>: {@link #theSweepCoversExactlyTwentyEightBranches()}
     * asserts the table's size, so a row deleted by accident fails the build instead of quietly
     * shrinking coverage - the failure mode a parameterised test is otherwise most prone to.
     */
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
            // Asserted on the RESOLVED token, not merely on the enum constant: this is the value that
            // actually reaches a caller, and it is the width the work-area field demands.
            assertThat(resolvedTokenOf(branch.aid()).token())
                    .as("token width for %s", branch.mnemonic())
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("the sweep has exactly 28 cases - source says 28, the requirement's 26 omits PA1/PA2")
        void theSweepCoversExactlyTwentyEightBranches() {
            // DISCREPANCY, recorded rather than resolved silently (practice B4).
            //
            //   The package-level requirement for this class states the paragraph has 26 branches.
            //   Direct verification in this checkout:
            //       grep -c 'WHEN EIBAID'   app/cpy/CSSTRPFY.cpy  ->  28
            //       grep -c 'SET CCARD-AID' app/cpy/CSSTRPFY.cpy  ->  28   (one SET per WHEN)
            //   The two arms the figure of 26 leaves out are DFHPA1 (copybook L26) and DFHPA2 (L28).
            //   4 + 12 + 12 = 28, and the requirement's own itemised table numbers its last row
            //   group 17-28.
            //
            //   28 is asserted here because BEHAVIOUR COMES FROM SOURCE, and because 28 subsumes 26
            //   and therefore satisfies the requirement's stated intent. Trimming the table to 26
            //   would have left the PA1 and PA2 keys entirely untested.
            assertThat(allBranchesInCopybookOrder()).hasSize(CSSTRPFY_BRANCH_COUNT);
            assertThat(everyCsstrpfyBranch()).hasSize(CSSTRPFY_BRANCH_COUNT);
        }

        @Test
        @DisplayName("the table names DFHPA1 and DFHPA2 explicitly - the two arms the '26' count drops")
        void theTableIncludesTheTwoProgramAccessArms() {
            // Named directly so that the discrepancy above is not merely commented but enforced: a
            // future edit that trimmed the table back to 26 by removing these two rows fails here.
            List<String> mnemonics = allBranchesInCopybookOrder().stream().map(AidBranch::mnemonic).toList();
            assertThat(mnemonics).contains("DFHPA1", "DFHPA2");
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
        }

        @Test
        @DisplayName("the table is in copybook order - its line numbers strictly increase from 22 to 76")
        void theTableIsInCopybookOrder() {
            // Proves the transcription is ordered as CSSTRPFY orders it, which is what makes the
            // order audit in BranchOrderAndCompleteness meaningful rather than circular.
            List<Integer> lines = allBranchesInCopybookOrder().stream().map(AidBranch::copybookLine).toList();
            assertThat(lines).isSorted().doesNotHaveDuplicates();
            assertThat(lines).startsWith(22, 24, 26, 28).endsWith(70, 72, 74, 76);
            assertThat(lines.getFirst()).isEqualTo(22);
            assertThat(lines.getLast()).isEqualTo(76);
        }

        @Test
        @DisplayName("the 28 sweep inputs are 28 distinct AID bytes, so no arm is unreachable")
        void theSweepInputsAreDistinct() {
            // CicsAidTest owns the distinctness of the constants themselves. What matters HERE is the
            // consequence for this table: were two rows to carry the same byte, one WHEN arm would be
            // unreachable through the resolver and the sweep would silently under-test.
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

    /**
     * The four arms of {@code app/cpy/CSSTRPFY.cpy} lines 22 to 29, asserted individually.
     *
     * <p>They are already in the sweep. They are repeated here because each carries something the
     * sweep does not express: {@code ENTER} is the first arm and the most heavily consumed AID in the
     * application, and {@code PA1} and {@code PA2} are the two arms the "26 branches" count omits and
     * the only two whose tokens are space-padded.
     */
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
            // The width check is repeated on the resolved value because this is the path a caller
            // takes: a trim applied anywhere between the enum and the response would surface here.
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

    /**
     * The twelve arms of {@code app/cpy/CSSTRPFY.cpy} lines 30 to 53, where {@code DFHPF1} through
     * {@code DFHPF12} map one-to-one onto {@code PFK01} through {@code PFK12}.
     */
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
            // Catches a transposition inside the low range, which the one-to-one sweep above would
            // also catch but only if the table itself is right; this derives the expectation from the
            // key number instead of from the table, so the two checks are independent.
            foldPairs().forEach(pair -> {
                String expectedSuffix = "%02d".formatted(pair.keyNumber());
                assertThat(resolvedTokenOf(pair.unshifted()).token())
                        .as("PF%d must resolve to PFK%s", pair.keyNumber(), expectedSuffix)
                        .isEqualTo("PFK" + expectedSuffix);
            });
        }
    }

    /**
     * Arms 17 to 28, {@code app/cpy/CSSTRPFY.cpy} lines 54 to 77 - the fold, where {@code DFHPF13}
     * through {@code DFHPF24} set {@code PFK01} through {@code PFK12} all over again.
     *
     * <p>On a 3270 terminal PF13 to PF24 are the shifted upper row, and this application treats them
     * as aliases of PF1 to PF12. <strong>This is the single most likely place for a defect</strong>,
     * because an off-by-one in the fold shifts every shifted key by one position and still passes a
     * test that only checks PF13. So all twelve pairs are asserted, the boundaries and two interior
     * points are asserted again by name, and the twenty-four-onto-twelve collapse is asserted as a
     * whole.
     */
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
            // The fold maps two DIFFERENT AID bytes onto one token. If the two bytes were equal there
            // would be no fold to test and every assertion above would be trivially true. CicsAidTest
            // owns the distinctness of the constants; what is asserted here is the property the
            // RESOLVER depends on - two distinct inputs, one output.
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
            // An off-by-one in the fold shifts EVERY shifted key by one position. Testing PF13 alone
            // would not reveal it if the implementation started the fold correctly and drifted, and
            // testing only the boundaries would not reveal a drift that happens to return to the
            // right value at the end. Interior points are therefore asserted explicitly, by name and
            // with their literal expected tokens rather than through the table.
            assertThat(resolvedTokenOf(CicsAid.DFHPF13).token()).isEqualTo("PFK01"); // lower boundary
            assertThat(resolvedTokenOf(CicsAid.DFHPF18).token()).isEqualTo("PFK06"); // interior
            assertThat(resolvedTokenOf(CicsAid.DFHPF20).token()).isEqualTo("PFK08"); // interior
            assertThat(resolvedTokenOf(CicsAid.DFHPF24).token()).isEqualTo("PFK12"); // upper boundary
        }

        @Test
        @DisplayName("the fold does not slip by one - PF18 is PFK06, and is neither PFK05 nor PFK07")
        void theFoldDoesNotSlipByOne() {
            // The negative form of the interior check, stated so that the intent survives refactoring:
            // the neighbours on either side are what an off-by-one would produce.
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
            // AidKey constants are enum singletons, so the fold is observable by identity as well as
            // by value. Identity is the stronger statement: it rules out a second constant carrying a
            // duplicate literal, which value equality on the token string alone would not.
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

    /**
     * Gate <strong>G30</strong>: {@code EVALUATE} order preserved, and - the finding that matters
     * here - <strong>no {@code WHEN OTHER} invented</strong>.
     *
     * <h2>Why branch order is not directly observable, and what is asserted instead</h2>
     *
     * <p>A COBOL {@code EVALUATE} is first-match-wins, so branch order is semantic rather than
     * cosmetic. In this particular paragraph, though, every arm is an <em>exact equality</em> test
     * against a distinct one-byte constant, so no single {@code EIBAID} value can satisfy two arms and
     * the order in which they are examined has no observable consequence. Order-sensitivity would
     * become observable only if two of the twenty-eight AID constants shared a byte value, or if the
     * translation had used ranges or overlapping predicates instead of equality.
     *
     * <p>Rather than write an assertion that cannot fail, this section asserts the two properties that
     * <em>make</em> order unobservable, so that if either is ever broken the suite fails at once:
     * every arm is reachable, and no input reaches two arms. The mutual-exclusivity check is the
     * substantive one - it is what licenses the claim that order does not matter here. Copybook order
     * itself is enforced separately, by the strictly increasing line numbers asserted in
     * {@link AllTwentyEightBranches#theTableIsInCopybookOrder()}.
     *
     * <h2>The single most important structural assertion in this file</h2>
     *
     * <p>{@code grep -c 'WHEN OTHER' app/cpy/CSSTRPFY.cpy} returns <strong>0</strong>. The Java
     * translation must not invent one. {@link #noByteOutsideTheTwentyEightMapsToAToken()} proves that
     * by exhausting <em>all 256</em> byte values rather than sampling a handful: exactly twenty-eight
     * yield a token and the remaining two hundred and twenty-eight yield none. A sampled test can miss
     * a default that only some inputs reach; an exhaustive one cannot.
     */
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
            // L78 is END-EVALUATE. There is nothing between L76's arm and it, which is the structural
            // fact that "no WHEN OTHER" rests on.
            AidBranch last = allBranchesInCopybookOrder().getLast();
            assertThat(last.mnemonic()).isEqualTo("DFHPF24");
            assertThat(last.copybookLine()).isEqualTo(76);
            assertThat(last.expectedToken()).isEqualTo(AidKey.PFK12);
        }

        @Test
        @DisplayName("every one of the 28 arms is reachable - none is shadowed by an earlier one")
        void everyArmIsReachable() {
            // The first half of what makes order unobservable. In a first-match-wins chain a shadowed
            // arm would be dead code, and its token would only ever be produced by its partner.
            for (AidBranch branch : allBranchesInCopybookOrder()) {
                assertThat(PfKeyResolver.resolve(branch.aid()))
                        .as("%s (CSSTRPFY.cpy L%d) must be reachable", branch.mnemonic(), branch.copybookLine())
                        .contains(branch.expectedToken());
            }
        }

        @Test
        @DisplayName("no input reaches two arms - the 28 tests are mutually exclusive")
        void noInputReachesTwoArms() {
            // The second half, and the substantive one: because the twenty-eight AID bytes are
            // pairwise distinct and every arm is an exact-equality test, no EIBAID value can satisfy
            // more than one arm. That is precisely why first-match-wins ordering has no observable
            // effect here - and if this ever stopped holding, order WOULD matter and this failure is
            // what would say so.
            List<Byte> aids = allBranchesInCopybookOrder().stream().map(AidBranch::aid).toList();
            assertThat(aids).hasSize(CSSTRPFY_BRANCH_COUNT).doesNotHaveDuplicates();

            // Stated as behaviour rather than as a property of the constants: resolving any arm's AID
            // must produce that arm's token and no other arm's, for all 28 x 28 combinations.
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
            // THE structural assertion of this file. grep -c 'WHEN OTHER' app/cpy/CSSTRPFY.cpy = 0,
            // so the translation must not supply a default arm that hands back a token. Proved
            // exhaustively over the entire byte domain, because a PIC X(1) EIBAID field can hold any
            // of 256 values and a sampled check could walk straight past an invented default.
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
            // The complement of the assertion above: the absence of a default must not have been
            // implemented as an exception or a null. Every byte gets an answer, and the answer is
            // always a non-null Optional.
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

    /**
     * The AIDs the paragraph has no arm for, and the explicit no-match outcome they must produce.
     *
     * @return a stream of unmatched inputs, each with a description of why it belongs here
     */
    static Stream<Arguments> aidsWithNoBranch() {
        return Stream.of(
                // The best possible no-match case: a LEGITIMATE CICS AID that CicsAid declares - the
                // migration plan mandates the whole DFHPA1-DFHPA3 range - but for which CSSTRPFY has
                // no arm at all. Verified: grep -c 'DFHPA3' app/cpy/CSSTRPFY.cpy = 0, and CVCRD01Y
                // declares no CCARD-AID-PA3 condition either, so there would be nothing to set.
                Arguments.of(
                        "DFHPA3 - a real CICS AID with no CSSTRPFY arm", CicsAid.DFHPA3),
                // A space: DFHNULL, EBCDIC 0x40, the value an AID field carries before any key is
                // recorded into it.
                Arguments.of(
                        "DFHNULL - the EBCDIC space, 0x40", CicsAid.DFHNULL),
                // A null byte.
                Arguments.of("the NUL byte, 0x00", (byte) 0x00),
                // Arbitrary letters. Note the trap avoided here: EBCDIC 'A' is 0xC1, which IS DFHPF13,
                // so an "arbitrary letter" chosen carelessly would be a MATCHED input. ASCII 'A' and
                // 'Z' and EBCDIC lower-case 'a' are all genuinely unmapped.
                Arguments.of("ASCII 'A', 0x41", (byte) 0x41),
                Arguments.of("ASCII 'Z', 0x5A", (byte) 0x5A),
                Arguments.of("EBCDIC 'a', 0x81", (byte) 0x81),
                // The other IBM DFHAID members CicsAid reproduces for completeness but CSSTRPFY never
                // tests: clear-partition, light pen, operator id, magnetic reader, structured field
                // and trigger.
                Arguments.of("DFHCLRP - clear partition", CicsAid.DFHCLRP),
                Arguments.of("DFHPEN - cursor select", CicsAid.DFHPEN),
                Arguments.of("DFHOPID - operator id reader", CicsAid.DFHOPID),
                Arguments.of("DFHMSRE - magnetic slot reader", CicsAid.DFHMSRE),
                Arguments.of("DFHSTRF - structured field", CicsAid.DFHSTRF),
                Arguments.of("DFHTRIG - trigger field", CicsAid.DFHTRIG),
                // Both extremes of the signed byte range, and the all-bits-set value. Included because
                // the AID arrives as a signed Java byte: PF1 is 0xF1, which is the NEGATIVE value -15,
                // so the sign boundary is exactly where a comparison that widened to int would go wrong.
                Arguments.of("Byte.MIN_VALUE, 0x80", Byte.MIN_VALUE),
                Arguments.of("Byte.MAX_VALUE, 0x7F - the same byte as the unmapped DFHTRIG", Byte.MAX_VALUE),
                Arguments.of("0xFF, all bits set", (byte) 0xFF));
    }

    /**
     * The no-match outcome, which exists because {@code app/cpy/CSSTRPFY.cpy} has no
     * {@code WHEN OTHER} and does not pre-clear {@code CCARD-AID}.
     *
     * <h2>The translation decision, recorded deliberately</h2>
     *
     * <p>On the mainframe an unmatched {@code EIBAID} executes no {@code SET}, and because nothing
     * cleared the field first, {@code CCARD-AID} keeps <em>the previous key's token</em>. Reproducing
     * "keep the previous value" literally would require the resolver to remember the previous value,
     * which means hidden mutable state - forbidden by practice <strong>B9</strong> and gate
     * <strong>G53</strong>, and destructive of both thread safety and test determinism.
     *
     * <p>So the behaviour is split, and both halves are asserted. {@link PfKeyResolver#resolve(byte)}
     * reports no match <em>explicitly</em>, as an empty {@link Optional} that cannot be mistaken for
     * any of the sixteen tokens; and {@link PfKeyResolver#storePfKey(byte, Optional)} takes the
     * caller's current token as a parameter and hands it straight back, which is the retention the
     * COBOL performs, expressed as a pure function. Practice <strong>B5</strong> is honoured because
     * nothing was repaired: the odd behaviour is preserved, only its representation changed, and the
     * caller keeps the decision the COBOL leaves to it.
     */
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
            // "Not an empty string" made precise. Because the result is an Optional<AidKey> rather
            // than a String, there is no empty-string outcome to guard against directly; what is
            // asserted instead is that mapping the result to a token yields nothing whatsoever. Taken
            // with TokenVocabulary#noTokenIsBlank, that rules out both an empty token and a
            // space-filled one.
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
            // The most instructive no-match case in the whole file. DFHPA3 is a legitimate CICS AID
            // that CicsAid declares because the plan mandates the PA1-PA3 range, yet CSSTRPFY has no
            // arm for it and CVCRD01Y declares no PA3 condition. Absence is the correct answer; a PA3
            // token would be an invention, and an exception would be a behaviour change - the COBOL
            // does not fail on an unknown key, it simply sets nothing.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isNotNull();
        }

        @Test
        @DisplayName("DFHPA1 and DFHPA2 DO have arms even though DFHPA3 does not")
        void thePaArmsThatExistAreNotConfusedWithTheOneThatDoesNot() {
            // Guards the asymmetry directly: two of the three PA keys are mapped and the third is not,
            // which is easy to "tidy" in either direction. Both directions fail here.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).isPresent();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).isPresent();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
        }

        @Test
        @DisplayName("no unmatched byte is quietly treated as ENTER")
        void unmatchedIsNeverSilentlyEnter() {
            // The single most consequential negative assertion in the file, and the reason is in the
            // source rather than in taste: app/cbl/COCRDLIC.cbl L370-L380 reads
            //     SET PFK-INVALID TO TRUE
            //     IF CCARD-AID-ENTER OR CCARD-AID-PFK03 OR CCARD-AID-PFK07 OR CCARD-AID-PFK08
            //         SET PFK-VALID TO TRUE
            //     END-IF
            //     IF PFK-INVALID
            //         SET CCARD-AID-ENTER TO TRUE
            //     END-IF
            // The fall-back to ENTER is the CONSUMER's decision, taken at L379 only AFTER the key has
            // been classified invalid. Had the resolver defaulted to ENTER itself, the guard at L371
            // would match, PFK-VALID would be set, and PFK-INVALID would never fire - laundering an
            // invalid key into a valid one at the exact point the program decides validity, and losing
            // the invalid-key path altogether. On a sign-on screen that is a security-adjacent change.
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
            // ENTER is the tempting default; CLEAR is the second most tempting, since a terminal reset
            // is a plausible "safe" fall-back. Neither is what the COBOL does.
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

    /**
     * {@link PfKeyResolver#storePfKey(byte, Optional)} - the paragraph's <em>whole</em> contract,
     * including the property most easily lost in translation: on no match the existing token is left
     * exactly as it was, and is never cleared.
     */
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
            // The whole point of the missing WHEN OTHER combined with the missing pre-clear. Verified
            // in the source: no MOVE and no SET appears between the paragraph label at CSSTRPFY L17
            // and the EVALUATE at L21, so the field is never blanked before the chain runs.
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
            // Drives the other side of the requireNonNull guard: a lazily placed null check inside the
            // match arm only would let the no-match path return null instead of failing fast.
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
            // Exhaustive counterpart to the no-WHEN-OTHER proof: whatever byte arrives, a work area
            // that already held a token must never come back empty. Clearing on an unknown key is the
            // other plausible wrong translation of the missing default arm, and this rules it out
            // across the whole domain rather than for a sample.
            Optional<AidKey> current = Optional.of(AidKey.PFK09);
            for (int candidate = Byte.MIN_VALUE; candidate <= Byte.MAX_VALUE; candidate++) {
                byte value = (byte) candidate;
                assertThat(PfKeyResolver.storePfKey(value, current))
                        .as("storePfKey(0x%02X, PFK09) must never clear the token", value)
                        .isPresent();
            }
        }
    }

    /**
     * Gate <strong>G50</strong>: the sixteen {@code CCARD-AID} {@code 88}-level conditions, as a set,
     * with their multiplicities, each driven in both its true and its false state.
     *
     * <h2>What a {@code 88}-level is, in Java</h2>
     *
     * <p>In the COBOL, {@code SET CCARD-AID-PFK03 TO TRUE} stores the literal {@code 'PFK03'} into the
     * {@code PIC X(5)} field, and {@code IF CCARD-AID-PFK03} later tests the field against that same
     * literal. The Java translation makes the sixteen conditions the sixteen {@link AidKey} constants,
     * so the {@code 88}-level's true state is "the resolved token is this constant" and its false state
     * is "the resolved token is some other constant, or there is no token". Both states are driven
     * below for all sixteen.
     *
     * <h2>Why the multiplicities are asserted</h2>
     *
     * <p>Twenty-eight arms produce sixteen tokens, so the multiset of outcomes is as informative as the
     * set: {@code ENTER}, {@code CLEAR}, {@code PA1} and {@code PA2} must each be produced exactly
     * once, and each of the twelve {@code PFKnn} tokens exactly twice - once from {@code PFn} and once
     * from {@code PF(n+12)}. That single check simultaneously proves the fold is complete, that nothing
     * else was folded by accident, and that no arm was duplicated: a stray extra fold would push some
     * count to three, and a missing one would drop a count to one.
     */
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

            // Each PFKnn twice: once from PFn, once from PF(n+12). A count of 3 would mean something
            // extra was folded in; a count of 1 would mean a fold arm is missing or misdirected.
            foldPairs().forEach(pair -> {
                AidKey token = resolvedTokenOf(pair.unshifted());
                assertThat(multiplicity.get(token))
                        .as("%s must be produced twice - by PF%d and PF%d",
                                token.name(), pair.keyNumber(), pair.keyNumber() + 12)
                        .isEqualTo(2);
            });

            // The multiset must account for every arm and nothing more.
            assertThat(multiplicity.values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(CSSTRPFY_BRANCH_COUNT);
        }

        @Test
        @DisplayName("the four singly-produced tokens are exactly ENTER, CLEAR, PA1 and PA2")
        void onlyTheFourNonFunctionKeysAreProducedOnce() {
            // The complement of the check above, phrased so that a PFKnn accidentally produced once
            // fails here even if its own multiplicity assertion were somehow relaxed.
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
            // The true state of the 88-level: some tested AID must set it. A condition that no arm can
            // produce would be dead vocabulary.
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
            // The false state of the same 88-level, driven twice over: once against an AID that sets a
            // DIFFERENT condition, and once against an AID that sets none at all. G50 requires both
            // states of every condition, and the no-match case is the one an implementation with an
            // invented default arm would fail.
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
            // CCARD-AID is a single PIC X(5) field, so it can hold only one literal and therefore only
            // one of the sixteen conditions can be true at any moment. Asserted for every tested AID.
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
            // The direct expression of "no SET executed". Zero of the sixteen conditions hold, which is
            // materially different from one holding by default.
            List<AidKey> trueConditions = new ArrayList<>();
            for (AidKey condition : AidKey.values()) {
                if (PfKeyResolver.resolve(CicsAid.DFHPA3).filter(token -> token == condition).isPresent()) {
                    trueConditions.add(condition);
                }
            }
            assertThat(trueConditions).isEmpty();
        }
    }

    /**
     * The seven AID mnemonics the twelve non-copying online programs test inline, with the occurrence
     * count of each across {@code app/cbl}.
     *
     * @return a stream of {@code (mnemonic, AID byte, inline occurrence count)} rows
     */
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

    /**
     * Equivalence for the twelve online programs that never copied {@code CSSTRPFY} and test
     * {@code EIBAID} inline instead.
     *
     * <h2>Two consumption styles, one resolver</h2>
     *
     * <p>{@code CSSTRPFY} is copied by exactly <strong>five</strong> programs -
     * {@code app/cbl/COACTUPC.cbl} line 4199, {@code COACTVWC.cbl} line 913, {@code COCRDLIC.cbl} line
     * 1416, {@code COCRDSLC.cbl} line 855 and {@code COCRDUPC.cbl} line 1528, every one in the quoted
     * {@code COPY 'CSSTRPFY'} spelling. Those five consume the resolved <em>token</em> and branch on
     * the {@code 88}-level conditions, as {@code COCRDLIC.cbl} lines 371 to 374 show with
     * {@code IF CCARD-AID-ENTER OR CCARD-AID-PFK03 OR CCARD-AID-PFK07 OR CCARD-AID-PFK08}.
     *
     * <p>The other <strong>twelve</strong> - {@code COADM01C}, {@code COBIL00C}, {@code COMEN01C},
     * {@code CORPT00C}, {@code COSGN00C}, {@code COTRN00C}, {@code COTRN01C}, {@code COTRN02C},
     * {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} - test the byte
     * directly, in the shape at {@code app/cbl/COMEN01C.cbl} lines 93 to 102:
     * {@code EVALUATE EIBAID / WHEN DFHENTER / WHEN DFHPF3 / WHEN OTHER}. The migration consolidates all
     * seventeen programs onto this one resolver while requiring the inline tests keep identical boolean
     * outcomes, so this section asserts the plain byte comparison those twelve performed, with no token
     * lookup interposed.
     *
     * <p>Every one of the twelve was checked and every one has its <em>own</em> {@code WHEN OTHER} arm.
     * That is the same division of responsibility the five copiers observe, and it is the structural
     * reason the resolver must stay silent on an unrecognised key: in both styles the unknown-key
     * decision belongs to the call site.
     *
     * <p>The seven mnemonics asserted here are exactly the ones those programs are verified to test,
     * with their counted occurrences across {@code app/cbl} - {@code DFHENTER} 16, {@code DFHPF3} 14,
     * {@code DFHPF4} 6, {@code DFHPF5} 4, {@code DFHPF7} 4, {@code DFHPF8} 4, {@code DFHPF12} 2. Each
     * is asserted by name as well as through the sweep, because a break in any one of them breaks a
     * controller.
     */
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
            // Proves the seven convenience methods are not independent reimplementations that could
            // drift from the equality test the COBOL performs. Checked across all 28 tested AIDs.
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
            // The sharpest distinction between the two consumption styles. The fold is a property of
            // token resolution, never of byte equality: an inline tester asking EIBAID = DFHPF3 must NOT
            // match PF15, exactly as the COBOL would not, even though both keys resolve to PFK03.
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF15)).isFalse();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF16)).isFalse();
            assertThat(PfKeyResolver.isPf12(CicsAid.DFHPF24)).isFalse();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHPF13)).isFalse();

            // ...while the token view folds them, which is the whole point of having both views.
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
            // Rules out any normalisation, widening or masking inside the comparison: for a fixed
            // constant, exactly one of the 256 possible EIBAID bytes may match it.
            int matches = 0;
            for (int candidate = Byte.MIN_VALUE; candidate <= Byte.MAX_VALUE; candidate++) {
                if (PfKeyResolver.isAid((byte) candidate, CicsAid.DFHPF3)) {
                    matches++;
                }
            }
            assertThat(matches).isEqualTo(1);
        }
    }

    /**
     * Statelessness and class shape - gate <strong>G53</strong> and practice <strong>B9</strong>.
     *
     * <p>COBOL {@code WORKING-STORAGE} must never become static Java state: it would break request
     * isolation for the seventeen online programs, make results depend on test execution order, and
     * make the resolver unsafe to share across threads. The audits below are reflective rather than
     * behavioural because that is the only way to prove the <em>absence</em> of a mutable field, and
     * they cover the class under test, its enum, and this test class itself.
     */
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
            // The enum carries the token literals, so a non-final field here would make a token
            // rewritable at run time - the one way a PIC X(5) value could change under a caller.
            for (Field field : AidKey.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("AidKey.%s must be final", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("this test class declares no non-final static field - its tables are methods")
        void theTestClassItselfHoldsNoMutableStaticState() {
            // Applied to the suite as well as to the subject. Every table in this file is a static
            // METHOD returning a fresh value per call, so no test can mutate a structure another test
            // reads, and execution order cannot change an outcome.
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
            // The behavioural counterpart of the reflective audits: an implementation that remembered
            // its last input - the naive way to reproduce "CCARD-AID retains its previous value" -
            // would fail here, because the second resolution of A would be contaminated by B.
            Optional<AidKey> firstA = PfKeyResolver.resolve(CicsAid.DFHPF7);
            Optional<AidKey> b = PfKeyResolver.resolve(CicsAid.DFHPA1);
            Optional<AidKey> secondA = PfKeyResolver.resolve(CicsAid.DFHPF7);
            assertThat(firstA).contains(AidKey.PFK07);
            assertThat(b).contains(AidKey.PA1);
            assertThat(secondA).isEqualTo(firstA);

            // And an unmatched byte in between must not leave a trace either, in either direction.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF7)).isEqualTo(firstA);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
        }

        @Test
        @DisplayName("storePfKey is stateless too - it reads only its arguments")
        void storePfKeyIsStateless() {
            // Two independent "work areas" must not influence one another, which is what makes the
            // resolver safe for the seventeen stateless controllers to share.
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
}
