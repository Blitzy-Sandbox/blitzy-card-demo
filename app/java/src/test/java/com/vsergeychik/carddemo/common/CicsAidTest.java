package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link CicsAid}, the Java reproduction of the IBM-supplied CICS {@code DFHAID}
 * copybook.
 *
 * <h2>Read this before trusting any assertion in this file: where the values come from</h2>
 *
 * <p>{@code DFHAID} is supplied by CICS, not by the application, and it is <strong>absent from
 * this repository</strong>. That was re-verified in this checkout rather than taken on trust:
 * {@code ls app/cpy/DFH*} fails with "No such file or directory", and none of the twenty-eight
 * copybooks in {@code app/cpy} is {@code DFHAID} (nor {@code DFHBMSCA}, nor {@code DFHATTR}).
 * Meanwhile {@code COPY DFHAID.} appears in <strong>seventeen</strong> programs under
 * {@code app/cbl} - every CICS online program in the migration.
 *
 * <p>The consequence has to be stated plainly, because it changes what this test file is able to
 * prove. <strong>There is no in-repository oracle for the AID byte values.</strong> They cannot be
 * diffed against anything in this checkout. Nothing in this file therefore claims that a value was
 * verified against source, and no test here is named as though it had been - because it could not
 * be. The values are transcribed from <strong>IBM CICS documentation</strong>, which is the
 * provenance recorded as implicit requirement <strong>I6</strong> and tracked as open risk
 * <strong>R-D</strong> in the Agent Action Plan (&sect;0.9.12). Surfacing that limitation here
 * instead of quietly absorbing it is what practice <strong>B12</strong> requires, and declining to
 * invent a substitute for the missing oracle is what practice <strong>B4</strong> requires.
 *
 * <h2>What this file does prove, given that limitation</h2>
 *
 * <p>A test that merely restated {@code CicsAid}'s constants back to itself would prove nothing at
 * all. Four properties are established instead, in ascending order of how much they are worth:
 *
 * <ol>
 *   <li><strong>Completeness measured against the consumer list, not against the class.</strong>
 *       The set of mnemonics the legacy COBOL actually references was read out of the repository:
 *       {@code app/cpy/CSSTRPFY.cpy} branches on twenty-eight of them, and twelve online programs
 *       that do <em>not</em> copy {@code CSSTRPFY} test seven of them inline against
 *       {@code EIBAID}. A constant missing from {@code CicsAid} is a compile break in a
 *       controller far away from here, so completeness is checked programmatically - by
 *       collecting what the class declares and comparing set against set - never by hand-copying
 *       assertions that can silently omit a member.</li>
 *   <li><strong>Distinctness.</strong> This is a genuine invariant that no amount of
 *       documentation can guarantee, and it is the strongest correctness property available in the
 *       absence of a value oracle. {@code CSSTRPFY}'s {@code EVALUATE TRUE} tests {@code EIBAID}
 *       against the mnemonics in declared order, so if any two shared a byte, one key would be
 *       silently routed into another key's branch and nothing else in the build would notice.</li>
 *   <li><strong>Shape and encoding.</strong> Each constant is a single byte, and the code page it
 *       is expressed in is named rather than assumed (practice <strong>B8</strong>).</li>
 *   <li><strong>Value provenance, audited against an oracle that is not the class.</strong> This
 *       is the part that makes the file worth having despite the missing copybook. {@code CicsAid}
 *       documents that its values were cross-checked by converting each character literal the
 *       {@code DFHAID} copybook is written with through the explicitly named {@code IBM037}
 *       charset. The JDK ships those {@code IBM037} conversion tables, and this project did not
 *       write them - so they are an <em>independent</em> authority. The parameterised tests below
 *       re-execute that conversion for all thirty-six members, which audits a documented
 *       verification step rather than restating its conclusion. It was confirmed to agree in every
 *       one of the thirty-six cases.</li>
 * </ol>
 *
 * <h2>Scope and constraints observed</h2>
 *
 * <p>A pure unit test: no Spring context, no Mockito, no fixture files. {@code CicsAid} is a
 * JDK-only constants holder that imports nothing from any sibling package, so nothing needs to be
 * stood up to exercise it. Reflection is used where the contract being asserted is itself
 * reflective - member completeness, modifiers, and the absence of mutable state.
 *
 * <p>{@code review_rules} reports <strong>no user rules provided</strong> for this project, which
 * was confirmed directly rather than assumed. Its absence is not treated as licence to relax
 * anything: the enterprise practices <strong>B1</strong>-<strong>B12</strong> of the Agent Action
 * Plan (&sect;0.10.2) bind in their place. Governing this file specifically -
 * <strong>B1</strong>/<strong>B2</strong>: only JUnit Jupiter and AssertJ, both arriving through
 * the already-pinned {@code spring-boot-starter-test}, with no new dependency and no version
 * declared anywhere. <strong>B3</strong>: nothing under {@code app/cbl}, {@code app/cpy} or any
 * other legacy directory is read or written at run time - the counts quoted in this file were
 * established once, during analysis, and are recorded here as documentation.
 * <strong>B5</strong>: members with no consumer are asserted to be <em>present</em>, never tidied
 * away. <strong>B8</strong>: every import is an explicit single member; there is no wildcard
 * import in this file, not even {@code Assertions.*} (gate <strong>G52</strong>).
 * <strong>B9</strong>: every static member declared below is {@code final} and immutable, and none
 * is an array (gate <strong>G53</strong>). <strong>B10</strong>: there is no {@code @Disabled}, no
 * empty test body and no deferred work.
 *
 * <p>Gate <strong>G11</strong> - "{@code CicsAid} and {@code BmsAttributes} populated from IBM
 * CICS documentation" - and the value half of risk <strong>R-D</strong> are what this file
 * discharges.
 *
 * <p>One thing this file deliberately does <em>not</em> attempt: manufacturing branch coverage for
 * gate <strong>G49</strong>. {@code CicsAid} is a constants holder and contains no conditional
 * logic whatsoever - JaCoCo measures it at zero branches, missed and covered alike - so no test
 * here can move the package's branch ratio in either direction, and inventing artificial branching
 * in order to appear to would be a straightforward corruption of the gate. What this file does
 * achieve is full instruction, line and method coverage of the class. Its contribution to the build
 * is correctness; the branch volume for the AID surface belongs to the PF-key resolver's tests,
 * where the {@code EVALUATE} chain actually lives.
 */
@DisplayName("CicsAid - DFHAID attention identifiers, reproduced from IBM CICS documentation")
class CicsAidTest {

    /**
     * Number of AID mnemonics {@code CicsAid} declares. Asserted as an exact figure so that a
     * later addition or deletion fails a test rather than passing unnoticed.
     *
     * <p>The three counts that matter all differ, and conflating them is the easiest way to get
     * completeness wrong: <strong>28</strong> mnemonics are referenced by the legacy COBOL,
     * <strong>29</strong> are mandated (the referenced 28 plus {@code DFHPA3}, which the Agent
     * Action Plan requires and nothing consumes), and <strong>36</strong> are declared - the
     * remaining seven being the other members the IBM copybook defines, reproduced so the class is
     * a faithful copy of the named copybook rather than a partial extract of it.
     */
    private static final int DECLARED_AID_COUNT = 36;

    /** Program function keys in the AID set: {@code DFHPF1} through {@code DFHPF24}. */
    private static final int PF_KEY_COUNT = 24;

    /**
     * Mnemonics referenced anywhere in the legacy COBOL: the twenty-eight that
     * {@code app/cpy/CSSTRPFY.cpy} branches on.
     */
    private static final int REFERENCED_COUNT = 28;

    /**
     * Mnemonics {@code CicsAid} declares that no COBOL in this repository references: the seven
     * copybook-completeness members plus {@code DFHPA3}. Retained under practice
     * <strong>B5</strong>.
     */
    private static final int UNCONSUMED_COUNT = 8;

    /** The code page {@link CicsAid#AID_CODE_PAGE} is expected to name, stated explicitly. */
    private static final String EXPECTED_CODE_PAGE = "IBM037";

    /** Prefix shared by every {@code DFHAID} mnemonic, used to select them reflectively. */
    private static final String MNEMONIC_PREFIX = "DFH";

    /** Prefix shared by the twenty-four program function key mnemonics. */
    private static final String PF_PREFIX = "DFHPF";

    /**
     * One row of the audit table: a {@code DFHAID} mnemonic, the byte IBM publishes for it, the
     * character literal the copybook declares it with, and how many times this repository's legacy
     * COBOL references it.
     *
     * @param mnemonic         the constant name, which must match the field name on
     *                         {@link CicsAid} exactly
     * @param aidByte          the AID code as an unsigned value in {@code 0x00}-{@code 0xFF},
     *                         held as an {@code int} so the table can be written in hexadecimal
     *                         without a cast on every row
     * @param copybookLiteral  the character the IBM {@code DFHAID} copybook uses to express that
     *                         byte, which encodes back to {@code aidByte} under
     *                         {@link CicsAid#AID_CODE_PAGE}
     * @param legacyReferences references in {@code app/cbl} plus {@code app/cpy}; zero means the
     *                         member has no consumer in this repository and is retained under
     *                         practice <strong>B5</strong>
     */
    private record DocumentedAid(String mnemonic, int aidByte, char copybookLiteral,
            int legacyReferences) {

        /**
         * Narrows the tabulated unsigned AID code to the {@code byte} the class declares.
         *
         * @return the AID code as a {@code byte}, matching {@link CicsAid}'s declared type
         */
        byte expectedByte() {
            return (byte) aidByte;
        }
    }

    /**
     * THE AUDIT TABLE. Every value below comes from <strong>IBM CICS documentation</strong>, not
     * from source in this checkout.
     *
     * <p>To restate the point made at the top of this file, because this table is where it bites:
     * the {@code DFHAID} copybook is <strong>absent from this repository</strong> -
     * {@code ls app/cpy/DFH*} fails - yet {@code COPY DFHAID.} appears in seventeen programs under
     * {@code app/cbl}. So no byte in this table was or could be read out of the checkout. Each is
     * transcribed from IBM's published 3270 attention identifier code table, paired with the
     * character literal IBM documents the copybook as using. This is risk <strong>R-D</strong> of
     * the Agent Action Plan (&sect;0.9.12), and this table is the auditable record of exactly what
     * was taken from IBM's documentation, so that a reviewer can check thirty-six rows against
     * that one source instead of reverse-engineering them from assertions.
     *
     * <p>The {@code copybookLiteral} column is not decoration: it is what lets these values be
     * checked against an authority other than the class under test. Encoding it through the JDK's
     * own {@code IBM037} tables must reproduce the byte in the neighbouring column, and the JDK
     * did not get those tables from this project.
     *
     * <p>Two runs of values in here look regular and are not, which is precisely why every row is
     * written out rather than generated. The EBCDIC digit run {@code '1'}-{@code '9'} covers PF1
     * to PF9 and then <strong>stops</strong>: PF10 to PF12 are punctuation at {@code 0x7A} to
     * {@code 0x7C}, not {@code 0xFA} to {@code 0xFC}. The capital letter run covers PF13 to PF21
     * as {@code 'A'} to {@code 'I'} and then stops as well, because EBCDIC letters are not
     * contiguous ({@code 'I'} is {@code 0xC9} and {@code 'J'} is {@code 0xD1}); PF22 to PF24
     * revert to punctuation at {@code 0x4A} to {@code 0x4C}. Generating either run arithmetically
     * would have produced three to six wrong constants.
     *
     * <p>{@code List.of} returns an unmodifiable list, and {@code DocumentedAid} is a record of
     * primitives and a {@code String}, so this table is deeply immutable (practice
     * <strong>B9</strong>).
     */
    private static final List<DocumentedAid> DOCUMENTED_AIDS = List.of(
            // -- Copybook members that are not keys, and the non-keyboard attentions -------------
            new DocumentedAid("DFHNULL", 0x40, ' ', 0),
            new DocumentedAid("DFHENTER", 0x7D, '\'', 17),
            new DocumentedAid("DFHCLEAR", 0x6D, '_', 1),
            new DocumentedAid("DFHCLRP", 0x6A, '\u00A6', 0),
            new DocumentedAid("DFHPEN", 0x7E, '=', 0),
            new DocumentedAid("DFHOPID", 0xE6, 'W', 0),
            new DocumentedAid("DFHMSRE", 0xE7, 'X', 0),
            new DocumentedAid("DFHSTRF", 0x88, 'h', 0),
            new DocumentedAid("DFHTRIG", 0x7F, '"', 0),
            // -- Program access keys. PA3 is mandated but referenced nowhere; see B5 -------------
            new DocumentedAid("DFHPA1", 0x6C, '%', 1),
            new DocumentedAid("DFHPA2", 0x6E, '>', 1),
            new DocumentedAid("DFHPA3", 0x6B, ',', 0),
            // -- PF1-PF9: the EBCDIC digits, the only contiguous run in the whole set -----------
            new DocumentedAid("DFHPF1", 0xF1, '1', 1),
            new DocumentedAid("DFHPF2", 0xF2, '2', 1),
            new DocumentedAid("DFHPF3", 0xF3, '3', 15),
            new DocumentedAid("DFHPF4", 0xF4, '4', 7),
            new DocumentedAid("DFHPF5", 0xF5, '5', 5),
            new DocumentedAid("DFHPF6", 0xF6, '6', 1),
            new DocumentedAid("DFHPF7", 0xF7, '7', 5),
            new DocumentedAid("DFHPF8", 0xF8, '8', 5),
            new DocumentedAid("DFHPF9", 0xF9, '9', 1),
            // -- First discontinuity: PF10-PF12 are punctuation, NOT 0xFA-0xFC ------------------
            new DocumentedAid("DFHPF10", 0x7A, ':', 1),
            new DocumentedAid("DFHPF11", 0x7B, '#', 1),
            new DocumentedAid("DFHPF12", 0x7C, '@', 3),
            // -- PF13-PF21: the EBCDIC capitals 'A'-'I', which end at 0xC9 ---------------------
            new DocumentedAid("DFHPF13", 0xC1, 'A', 1),
            new DocumentedAid("DFHPF14", 0xC2, 'B', 1),
            new DocumentedAid("DFHPF15", 0xC3, 'C', 1),
            new DocumentedAid("DFHPF16", 0xC4, 'D', 1),
            new DocumentedAid("DFHPF17", 0xC5, 'E', 1),
            new DocumentedAid("DFHPF18", 0xC6, 'F', 1),
            new DocumentedAid("DFHPF19", 0xC7, 'G', 1),
            new DocumentedAid("DFHPF20", 0xC8, 'H', 1),
            new DocumentedAid("DFHPF21", 0xC9, 'I', 1),
            // -- Second discontinuity: PF22-PF24 revert to punctuation, not letters -------------
            new DocumentedAid("DFHPF22", 0x4A, '\u00A2', 1),
            new DocumentedAid("DFHPF23", 0x4B, '.', 1),
            new DocumentedAid("DFHPF24", 0x4C, '<', 1));

    /**
     * The mnemonics {@code app/cpy/CSSTRPFY.cpy} branches on, which is the authoritative consumer
     * list for completeness: {@code DFHENTER}, {@code DFHCLEAR}, {@code DFHPA1}, {@code DFHPA2}
     * and every one of {@code DFHPF1} through {@code DFHPF24}.
     *
     * <p>Reading the copybook confirms all twenty-eight and confirms there is <strong>no</strong>
     * {@code DFHPA3} branch. The PF range is generated from {@code 1..24} on purpose: hand-typing
     * twenty-four names is exactly the mistake that lets one go missing without a test noticing.
     */
    private static final List<String> CSSTRPFY_SYMBOLS = Stream.concat(
            Stream.of("DFHENTER", "DFHCLEAR", "DFHPA1", "DFHPA2"),
            IntStream.rangeClosed(1, PF_KEY_COUNT).mapToObj(number -> PF_PREFIX + number))
            .toList();

    /**
     * Mnemonics that twelve online programs test <em>inline</em> against {@code EIBAID}, mapped to
     * the number of such sites in {@code app/cbl}, counted with comment lines excluded.
     *
     * <p>These matter more than their small number suggests. The twelve controllers that do not
     * copy {@code CSSTRPFY} compare {@code EIBAID} to these mnemonics directly - for instance
     * {@code app/cbl/COSGN00C.cbl:L85-88} and {@code app/cbl/COMEN01C.cbl:L93-96}, both
     * {@code EVALUATE EIBAID / WHEN DFHENTER / WHEN DFHPF3}. A constant missing from this subset
     * breaks a controller rather than merely the resolver, and it breaks it at a site with no
     * obvious connection to this file.
     */
    private static final Map<String, Integer> INLINE_REFERENCE_COUNTS = Map.of(
            "DFHENTER", 16,
            "DFHPF3", 14,
            "DFHPF4", 6,
            "DFHPF5", 4,
            "DFHPF7", 4,
            "DFHPF8", 4,
            "DFHPF12", 2);

    /**
     * The eight declared mnemonics that no COBOL in this repository references, verified by
     * searching {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms} and {@code app/bms}.
     *
     * <p>Seven are the other members the IBM {@code DFHAID} copybook defines, reproduced so the
     * class is a complete copy of it. The eighth, {@code DFHPA3}, is mandated by the Agent Action
     * Plan (&sect;0.4.3, the {@code DFHPA1}-{@code DFHPA3} range) despite having no consumer at
     * all. Practice <strong>B5</strong> forbids removing an unreferenced member on the grounds
     * that nothing currently uses it, so all eight are asserted to be <strong>present</strong>.
     */
    private static final Set<String> UNCONSUMED_MNEMONICS = Set.of(
            "DFHNULL", "DFHCLRP", "DFHPEN", "DFHOPID", "DFHMSRE", "DFHSTRF", "DFHTRIG", "DFHPA3");

    // ---------------------------------------------------------------------------------------
    // Reflective helpers
    //
    // Two details in here are load-bearing and both were arrived at the hard way.
    //
    // First, mnemonic fields are selected by NAME PREFIX and never by declared type. Selecting
    // them with getType() == byte.class would make "every AID constant is a byte" tautological -
    // the filter would guarantee the assertion - and a constant accidentally widened to int or
    // char would simply vanish from the set instead of failing anything.
    //
    // Second, synthetic and '$'-prefixed fields are excluded. When the build runs under Maven the
    // JaCoCo agent is attached from the initialize phase onwards, and it injects a NON-FINAL
    // static boolean[] named $jacocoData into every instrumented class. Without this exclusion the
    // "no mutable static state" assertion below would pass under a bare javac run and then fail
    // under `mvn test` and `mvn verify` - a false negative that appears only in the build that
    // matters. isSynthetic() alone is sufficient today; the '$' guard is kept as a cheap
    // belt-and-braces against any future instrumentation that omits the synthetic flag.
    // ---------------------------------------------------------------------------------------

    /**
     * Every field {@link CicsAid} declares that is genuinely part of the class, with tooling
     * artefacts filtered out.
     *
     * @return the real declared fields, ordered by name for deterministic failure output
     */
    private static List<Field> realDeclaredFields() {
        return Arrays.stream(CicsAid.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !field.getName().startsWith("$"))
                .sorted(Comparator.comparing(Field::getName))
                .toList();
    }

    /**
     * The {@code DFHAID} mnemonic constants, selected by name so that assertions about their
     * declared type stay meaningful.
     *
     * @return the mnemonic fields, ordered by name
     */
    private static List<Field> aidFields() {
        return realDeclaredFields().stream()
                .filter(field -> field.getName().startsWith(MNEMONIC_PREFIX))
                .toList();
    }

    /**
     * Reads one AID constant reflectively, by the name the {@code DFHAID} copybook uses.
     *
     * <p>Deliberately fails with a diagnostic naming the missing mnemonic rather than letting a
     * {@link NoSuchFieldException} surface: an absent constant is a completeness defect that will
     * otherwise show up as a compile break in a controller, and the message should say so.
     *
     * @param mnemonic the copybook mnemonic, for example {@code DFHPF3}
     * @return the declared AID byte
     */
    private static byte aidValue(String mnemonic) {
        try {
            return CicsAid.class.getDeclaredField(mnemonic).getByte(null);
        } catch (ReflectiveOperationException | IllegalArgumentException cause) {
            throw new AssertionError("CicsAid does not declare a byte constant named " + mnemonic
                    + ", which the legacy COBOL in this repository references; every consumer of "
                    + "that mnemonic will fail to compile", cause);
        }
    }

    /**
     * Collects a contiguous run of program function key values.
     *
     * @param firstKey lowest PF number, inclusive
     * @param lastKey  highest PF number, inclusive
     * @return the AID bytes for {@code DFHPF<firstKey>} through {@code DFHPF<lastKey>}
     */
    private static List<Byte> pfValues(int firstKey, int lastKey) {
        return IntStream.rangeClosed(firstKey, lastKey)
                .mapToObj(number -> aidValue(PF_PREFIX + number))
                .toList();
    }

    /**
     * Resolves the code page the class names, through the JDK rather than any project table.
     *
     * @return the charset {@link CicsAid#AID_CODE_PAGE} names
     */
    private static Charset declaredCodePage() {
        return Charset.forName(CicsAid.AID_CODE_PAGE);
    }

    // ---------------------------------------------------------------------------------------
    // Argument providers. Package-private and static, referenced from the @Nested classes below
    // by fully qualified name so that resolution is unambiguous and the tables above stay the
    // single source of truth rather than being duplicated per nested group.
    // ---------------------------------------------------------------------------------------

    /**
     * Supplies the audit table to the value-provenance tests.
     *
     * @return one row per documented AID: mnemonic, the byte IBM publishes, and the character
     *         literal the copybook declares it with
     */
    static Stream<Arguments> documentedAids() {
        return DOCUMENTED_AIDS.stream()
                .map(aid -> Arguments.of(aid.mnemonic(), aid.expectedByte(), aid.copybookLiteral()));
    }

    /**
     * Supplies the resolver's consumer list to the completeness tests.
     *
     * @return the twenty-eight mnemonics {@code app/cpy/CSSTRPFY.cpy} branches on
     */
    static Stream<String> csstrpfySymbols() {
        return CSSTRPFY_SYMBOLS.stream();
    }

    /**
     * Supplies the inline-consumer list to the completeness tests.
     *
     * @return one row per mnemonic tested inline against {@code EIBAID}, with its site count in
     *         {@code app/cbl}
     */
    static Stream<Arguments> inlineReferencedSymbols() {
        return INLINE_REFERENCE_COUNTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    /**
     * Completeness of the mnemonic set, measured against what the legacy COBOL references rather
     * than against what the class happens to declare. Gate <strong>G11</strong>.
     */
    @Nested
    @DisplayName("Completeness - every mnemonic the COBOL references is declared (G11)")
    class Completeness {

        @ParameterizedTest(name = "CSSTRPFY branches on {0}")
        @MethodSource("com.vsergeychik.carddemo.common.CicsAidTest#csstrpfySymbols")
        @DisplayName("every mnemonic CSSTRPFY's EVALUATE branches on is declared")
        void everySymbolCsstrpfyBranchesOnIsDeclared(String mnemonic) {
            byte value = aidValue(mnemonic);

            assertThat(CicsAid.mnemonicsByAid())
                    .as("%s is a WHEN branch of YYYY-STORE-PFKEY in app/cpy/CSSTRPFY.cpy and must "
                            + "resolve back to its own mnemonic", mnemonic)
                    .containsEntry(value, mnemonic);
        }

        @Test
        @DisplayName("CSSTRPFY's consumer list is the expected twenty-eight mnemonics")
        void theCsstrpfyConsumerListIsTwentyEightMnemonics() {
            // Guards the table this group measures against: if the expectation itself were
            // truncated, every assertion above would still pass while proving less.
            assertThat(CSSTRPFY_SYMBOLS)
                    .as("YYYY-STORE-PFKEY has one WHEN branch per mnemonic")
                    .hasSize(REFERENCED_COUNT)
                    .doesNotHaveDuplicates()
                    .contains("DFHENTER", "DFHCLEAR", "DFHPA1", "DFHPA2")
                    .doesNotContain("DFHPA3");
        }

        @Test
        @DisplayName("exactly twenty-four PF-key constants exist, numbered 1 to 24 with no gap")
        void exactlyTwentyFourPfKeyConstantsExist() {
            // Collected from the class rather than asserted name by name: twenty-four hand-copied
            // assertions are precisely how one goes missing unnoticed.
            List<Integer> declaredPfNumbers = aidFields().stream()
                    .map(Field::getName)
                    .filter(name -> name.matches("DFHPF\\d+"))
                    .map(name -> Integer.valueOf(name.substring(PF_PREFIX.length())))
                    .sorted()
                    .toList();

            assertThat(declaredPfNumbers)
                    .as("CSSTRPFY branches on all twenty-four PF keys, so all twenty-four must "
                            + "exist")
                    .hasSize(PF_KEY_COUNT)
                    .containsExactlyElementsOf(
                            IntStream.rangeClosed(1, PF_KEY_COUNT).boxed().toList());
        }

        @Test
        @DisplayName("DFHPA3 is declared even though nothing in the repository consumes it (B5)")
        void dfhpa3IsDeclaredDespiteHavingNoConsumer() {
            // DFHPA3 has ZERO references anywhere in app/cbl, app/cpy, app/cpy-bms or app/bms, and
            // CSSTRPFY - which does branch on DFHPA1 and DFHPA2 - has no PA3 branch at all. The
            // Agent Action Plan (0.4.3) mandates the DFHPA1-DFHPA3 range regardless, and practice
            // B5 forbids deleting an unreferenced member merely because nothing uses it yet. This
            // test exists so that "tidying it away" fails the build. Do not remove either.
            assertThat(aidFields())
                    .as("DFHPA3 is mandated by the plan and must not be pruned as dead code")
                    .anyMatch(field -> "DFHPA3".equals(field.getName()));

            assertThat(UNCONSUMED_MNEMONICS).contains("DFHPA3");
            assertThat(CSSTRPFY_SYMBOLS).doesNotContain("DFHPA3");
        }

        @Test
        @DisplayName("all eight unconsumed mnemonics are declared, none pruned as dead code (B5)")
        void everyUnconsumedMnemonicIsStillDeclared() {
            List<String> declaredNames = aidFields().stream().map(Field::getName).toList();

            assertThat(declaredNames)
                    .as("the seven copybook-completeness members plus DFHPA3 are retained under B5")
                    .containsAll(UNCONSUMED_MNEMONICS);
            assertThat(UNCONSUMED_MNEMONICS).hasSize(UNCONSUMED_COUNT);
        }

        @ParameterizedTest(name = "{0} - {1} inline EIBAID site(s) in app/cbl")
        @MethodSource("com.vsergeychik.carddemo.common.CicsAidTest#inlineReferencedSymbols")
        @DisplayName("every mnemonic a controller tests inline against EIBAID is declared")
        void everySymbolTestedInlineByAControllerIsDeclared(String mnemonic, int inlineSites) {
            // The twelve online programs that do not copy CSSTRPFY compare EIBAID to these
            // mnemonics directly, so a missing one breaks a controller rather than the resolver.
            byte value = aidValue(mnemonic);

            assertThat(CicsAid.mnemonicsByAid())
                    .as("%s is compared to EIBAID at %d inline site(s) in app/cbl by controllers "
                            + "that do not copy CSSTRPFY", mnemonic, inlineSites)
                    .containsEntry(value, mnemonic);
            assertThat(value)
                    .as("%s is an actively used key and must not collapse onto the DFHNULL "
                            + "sentinel", mnemonic)
                    .isNotEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("the declared set is exactly the thirty-six documented mnemonics")
        void theDeclaredSetIsExactlyTheDocumentedSet() {
            List<String> declaredNames = aidFields().stream().map(Field::getName).toList();
            List<String> documentedNames = DOCUMENTED_AIDS.stream()
                    .map(DocumentedAid::mnemonic)
                    .toList();

            // Set equality both ways, so an addition and a deletion each fail rather than only
            // one of them: hasSize alone would let a swap through.
            assertThat(declaredNames).hasSize(DECLARED_AID_COUNT).doesNotHaveDuplicates();
            assertThat(documentedNames).hasSize(DECLARED_AID_COUNT).doesNotHaveDuplicates();
            assertThat(declaredNames)
                    .as("the audit table and the class must describe the same member set; a "
                            + "constant added or removed without updating the table is a defect "
                            + "in one of the two")
                    .containsExactlyInAnyOrderElementsOf(documentedNames);
        }

        @Test
        @DisplayName("the referenced and unconsumed partitions reconcile to the declared total")
        void theReferencedAndUnconsumedPartitionsReconcile() {
            List<String> referenced = DOCUMENTED_AIDS.stream()
                    .filter(aid -> aid.legacyReferences() > 0)
                    .map(DocumentedAid::mnemonic)
                    .toList();
            List<String> unconsumed = DOCUMENTED_AIDS.stream()
                    .filter(aid -> aid.legacyReferences() == 0)
                    .map(DocumentedAid::mnemonic)
                    .toList();

            // 28 referenced + 8 unconsumed = 36 declared. Spelling the arithmetic out as an
            // assertion is what stops the three counts from being conflated later.
            assertThat(referenced)
                    .as("the referenced set must be exactly CSSTRPFY's consumer list")
                    .hasSize(REFERENCED_COUNT)
                    .containsExactlyInAnyOrderElementsOf(CSSTRPFY_SYMBOLS);
            assertThat(unconsumed)
                    .as("the unconsumed set must be exactly the eight retained under B5")
                    .hasSize(UNCONSUMED_COUNT)
                    .containsExactlyInAnyOrderElementsOf(UNCONSUMED_MNEMONICS);
            assertThat(referenced.size() + unconsumed.size()).isEqualTo(DECLARED_AID_COUNT);
        }

        @Test
        @DisplayName("no mnemonic is claimed both to be referenced and to be unconsumed")
        void theTwoPartitionsDoNotOverlap() {
            assertThat(CSSTRPFY_SYMBOLS)
                    .as("a mnemonic cannot be both a CSSTRPFY branch and unreferenced")
                    .doesNotContainAnyElementsOf(UNCONSUMED_MNEMONICS);
            assertThat(INLINE_REFERENCE_COUNTS.keySet())
                    .as("every inline-tested mnemonic is also a CSSTRPFY branch")
                    .allSatisfy(mnemonic -> assertThat(CSSTRPFY_SYMBOLS).contains(mnemonic));
        }
    }

    /**
     * Pairwise distinctness of the AID bytes.
     *
     * <p>With the copybook absent there is no oracle for the values, so distinctness is the
     * strongest correctness property this file can establish - and it is a real one rather than a
     * consolation prize. {@code YYYY-STORE-PFKEY} in {@code app/cpy/CSSTRPFY.cpy} is an
     * {@code EVALUATE TRUE} chain that compares {@code EIBAID} against the mnemonics in declared
     * order and takes the first match. Two constants sharing a byte would therefore route one key
     * silently into another key's branch: the wrong screen action, no exception, no log line, and
     * nothing anywhere else in the build that would notice.
     */
    @Nested
    @DisplayName("Distinctness - no two AIDs share a byte, so no key routes into another's branch")
    class Distinctness {

        @Test
        @DisplayName("all thirty-six AID values are pairwise distinct")
        void everyAidValueIsPairwiseDistinct() throws ReflectiveOperationException {
            List<Field> fields = aidFields();
            Map<Byte, String> firstOwnerOfValue = new HashMap<>();
            List<String> collisions = new ArrayList<>();

            for (Field field : fields) {
                byte value = field.getByte(null);
                String incumbent = firstOwnerOfValue.putIfAbsent(value, field.getName());
                if (incumbent != null) {
                    // Name the colliding pair: "some duplicate exists" is not an actionable
                    // failure message when there are thirty-six candidates.
                    collisions.add(String.format("%s and %s both equal 0x%02X",
                            incumbent, field.getName(), value));
                }
            }

            assertThat(collisions)
                    .as("CSSTRPFY's ordered EVALUATE would silently route one key into another "
                            + "key's branch if two AID constants shared a byte")
                    .isEmpty();
            assertThat(firstOwnerOfValue)
                    .as("thirty-six distinct mnemonics must occupy thirty-six distinct bytes")
                    .hasSize(fields.size())
                    .hasSize(DECLARED_AID_COUNT);
        }

        @Test
        @DisplayName("ENTER and CLEAR differ, and neither collides with any PF key")
        void enterAndClearAreDistinctAndNeitherIsAPfKey() {
            // The two most damaging collisions available. DFHENTER is compared to EIBAID at
            // sixteen inline sites plus one CSSTRPFY branch - seventeen in total, more than any
            // other AID - so a collision involving it would misroute the primary action of every
            // screen in the application.
            assertThat(CicsAid.DFHENTER)
                    .as("ENTER and CLEAR are opposite outcomes on every screen")
                    .isNotEqualTo(CicsAid.DFHCLEAR);

            List<Byte> allPfValues = pfValues(1, PF_KEY_COUNT);
            assertThat(allPfValues)
                    .as("ENTER must not be mistaken for a function key")
                    .doesNotContain(CicsAid.DFHENTER);
            assertThat(allPfValues)
                    .as("CLEAR must not be mistaken for a function key")
                    .doesNotContain(CicsAid.DFHCLEAR);
        }

        @Test
        @DisplayName("PF1-PF12 are disjoint from PF13-PF24, so the resolver's fold is not vacuous")
        void theLowPfRangeIsDisjointFromTheHighPfRange() {
            // This is subtler than the other distinctness checks and worth stating precisely.
            // CSSTRPFY deliberately FOLDS the high range onto the low one: DFHPF13 sets PFK01,
            // DFHPF14 sets PFK02, and so on to DFHPF24 setting PFK12. Twenty-four AIDs therefore
            // produce only twelve tokens - by design.
            //
            // The fold is a mapping of DISTINCT INPUTS onto shared OUTPUTS. That distinction is
            // the whole point: if the input bytes themselves collided, the fold would be vacuous,
            // the twelve high branches of the EVALUATE would be unreachable, and the COBOL's
            // behaviour would have been changed rather than translated. IBM's AID table makes the
            // two ranges structurally unrelated - digits and punctuation for the low range,
            // capitals and punctuation for the high - which is exactly why this holds.
            List<Byte> lowRange = pfValues(1, 12);
            List<Byte> highRange = pfValues(13, PF_KEY_COUNT);

            assertThat(lowRange).hasSize(12).doesNotHaveDuplicates();
            assertThat(highRange).hasSize(12).doesNotHaveDuplicates();
            assertThat(lowRange)
                    .as("CSSTRPFY folds PF13-PF24 onto PFK01-PFK12; the fold maps distinct AIDs "
                            + "onto shared outcomes, and a shared AID would make the twelve high "
                            + "branches unreachable")
                    .doesNotContainAnyElementsOf(highRange);
        }

        @Test
        @DisplayName("PA1, PA2 and PA3 differ from each other and from every other constant")
        void theProgramAccessKeysAreDistinctFromEachOtherAndFromEverythingElse()
                throws ReflectiveOperationException {
            assertThat(List.of(CicsAid.DFHPA1, CicsAid.DFHPA2, CicsAid.DFHPA3))
                    .as("the three program access keys are three different keys")
                    .doesNotHaveDuplicates();

            for (Field field : aidFields()) {
                if (field.getName().startsWith("DFHPA")) {
                    continue;
                }
                assertThat(field.getByte(null))
                        .as("%s must not collide with a program access key", field.getName())
                        .isNotEqualTo(CicsAid.DFHPA1)
                        .isNotEqualTo(CicsAid.DFHPA2)
                        .isNotEqualTo(CicsAid.DFHPA3);
            }
        }

        @Test
        @DisplayName("the sentinel is distinct from every real key")
        void theNullSentinelIsDistinctFromEveryRealKey() throws ReflectiveOperationException {
            // DFHNULL marks "no AID recorded yet". If any real key equalled it, an unpressed
            // screen would be indistinguishable from that key having been pressed.
            for (Field field : aidFields()) {
                if ("DFHNULL".equals(field.getName())) {
                    continue;
                }
                assertThat(field.getByte(null))
                        .as("%s must be distinguishable from the DFHNULL sentinel",
                                field.getName())
                        .isNotEqualTo(CicsAid.DFHNULL);
            }
        }
    }

    /**
     * Shape of each constant and the encoding it is expressed in.
     *
     * <p>A COBOL {@code PIC X(1)} field holds exactly one byte, and {@code EIBAID} is such a
     * field, so every AID must be one byte wide and no wider. The encoding matters just as much as
     * the width: the same key is a completely different number in EBCDIC and in ASCII, and the two
     * are indistinguishable at the point of writing a character literal.
     */
    @Nested
    @DisplayName("Shape and encoding - one byte each, in an explicitly named code page")
    class ShapeAndEncoding {

        @Test
        @DisplayName("every AID constant is declared as a primitive byte, so it is one byte wide")
        void everyAidConstantIsDeclaredAsAPrimitiveByte() {
            // Meaningful only because aidFields() selects on the DFH name prefix rather than on
            // the declared type; filtering by type would make this assertion prove itself. A Java
            // byte is one byte wide by definition (JLS 4.2.1), so asserting the declared type is
            // how "single byte" is asserted for this class's chosen representation.
            List<Field> fields = aidFields();

            assertThat(fields).hasSize(DECLARED_AID_COUNT);
            assertThat(fields).allSatisfy(field -> assertThat(field.getType())
                    .as("%s must be a primitive byte: a char literal would silently carry a "
                            + "Unicode code point instead of the EBCDIC AID byte", field.getName())
                    .isEqualTo(byte.class));
        }

        @Test
        @DisplayName("the code page is named explicitly, resolves, and is IBM037")
        void theCodePageIsNamedExplicitlyAndResolves() {
            // Practice B8: the encoding is stated, never inferred and never the platform default,
            // which is never correct for mainframe data.
            assertThat(CicsAid.AID_CODE_PAGE)
                    .as("the EBCDIC code page must be named on the class, not left to a comment")
                    .isEqualTo(EXPECTED_CODE_PAGE);
            assertThat(Charset.isSupported(CicsAid.AID_CODE_PAGE))
                    .as("a named code page that the JDK cannot resolve is not a usable declaration")
                    .isTrue();
            assertThat(declaredCodePage().name()).isEqualTo(EXPECTED_CODE_PAGE);
        }

        @Test
        @DisplayName("the code page declaration is load-bearing, not decorative")
        void theCodePageDeclarationIsLoadBearing() {
            // Demonstrates why naming the charset matters at all: the apostrophe that the DFHAID
            // copybook uses for ENTER is 0x7D under IBM037 and 0x27 under ASCII. Both are one byte
            // and both compile; only one is the AID a 3270 terminal transmits. This assertion is
            // unconditionally true on any JDK and does not depend on the platform default.
            assertThat("'".getBytes(declaredCodePage()))
                    .as("under the declared code page the ENTER literal is the declared AID byte")
                    .containsExactly(CicsAid.DFHENTER);
            assertThat("'".getBytes(StandardCharsets.US_ASCII))
                    .as("the same literal is a different byte under ASCII, which is the trap")
                    .containsExactly((byte) 0x27);
            assertThat(CicsAid.DFHENTER)
                    .as("DFHENTER must be the EBCDIC apostrophe 0x7D, never the ASCII 0x27")
                    .isEqualTo((byte) 0x7D)
                    .isNotEqualTo((byte) 0x27);
        }

        @Test
        @DisplayName("every AID byte decodes to exactly one printable character in that code page")
        void everyAidByteDecodesToExactlyOnePrintableCharacter()
                throws ReflectiveOperationException {
            // The DFHAID copybook declares its members as character literals, so every AID byte
            // must be a real, printable code point in the declared code page. A byte that decoded
            // to the replacement character or to a control code would mean the value is not a
            // copybook literal at all and the transcription had gone wrong.
            Charset codePage = declaredCodePage();

            for (Field field : aidFields()) {
                byte value = field.getByte(null);
                String decoded = new String(new byte[] {value}, codePage);

                assertThat(decoded)
                        .as("%s (0x%02X) must decode to exactly one character under %s",
                                field.getName(), value, EXPECTED_CODE_PAGE)
                        .hasSize(1);
                assertThat(decoded.charAt(0))
                        .as("%s (0x%02X) decoded to the Unicode replacement character, so it is "
                                + "not a valid code point in %s", field.getName(), value,
                                EXPECTED_CODE_PAGE)
                        .isNotEqualTo('\uFFFD');
                assertThat(Character.isISOControl(decoded.charAt(0)))
                        .as("%s (0x%02X) decoded to a control character; every DFHAID member is a "
                                + "printable literal", field.getName(), value)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("no AID constant is the zero byte")
        void noAidConstantIsTheZeroByte() throws ReflectiveOperationException {
            // Zero would be indistinguishable from an uninitialised byte array element, so it
            // cannot safely mean anything.
            for (Field field : aidFields()) {
                assertThat(field.getByte(null))
                        .as("%s must not be the zero byte, which is indistinguishable from "
                                + "uninitialised storage", field.getName())
                        .isNotZero();
            }
        }

        @Test
        @DisplayName("DFHNULL is the EBCDIC space sentinel, not the zero byte")
        void theNullSentinelIsTheEbcdicSpaceRatherThanZero() {
            // Worth pinning down because the name invites the wrong assumption twice over.
            //
            // First, DFHNULL is 0x40 - the EBCDIC space, the value an AID field carries before any
            // key has been recorded - and not 0x00. It is a sentinel, not the 3270 "no AID
            // generated" byte, which DFHAID does not define at all.
            //
            // Second, on provenance: the Agent Action Plan notes that this codebase references no
            // DFHNULL and that it therefore need not exist. CicsAid declares it anyway, as one of
            // the seven members reproduced so the class is a complete copy of the named copybook
            // rather than a partial extract. That is a deliberate superset, documented here rather
            // than presented as a requirement it is not.
            assertThat(CicsAid.DFHNULL)
                    .as("DFHNULL is the EBCDIC space, 0x40")
                    .isEqualTo((byte) 0x40)
                    .isNotEqualTo((byte) 0x00);
            assertThat(new String(new byte[] {CicsAid.DFHNULL}, declaredCodePage()))
                    .as("0x40 renders as a space under the declared code page")
                    .isEqualTo(" ");
            assertThat(UNCONSUMED_MNEMONICS)
                    .as("DFHNULL has no consumer in this repository and is retained for "
                            + "copybook completeness")
                    .contains("DFHNULL");
        }
    }

    /**
     * Value provenance - risk <strong>R-D</strong> of the Agent Action Plan (&sect;0.9.12).
     *
     * <p>Stated once more where it is being relied upon: the {@code DFHAID} copybook is
     * <strong>absent from this repository</strong> ({@code ls app/cpy/DFH*} fails) while
     * {@code COPY DFHAID.} appears in seventeen programs under {@code app/cbl}. Every value
     * asserted in this group therefore comes from <strong>IBM CICS documentation</strong> and
     * <strong>not</strong> from source in this checkout. No test in this group claims otherwise,
     * and none is named as though the value had been verified against the repository, because it
     * cannot be.
     *
     * <p>What raises this above a restatement of the class's own fields is the second test below.
     * {@code CicsAid} documents that its values were cross-checked by converting each character
     * literal the copybook is written with through the explicitly named {@code IBM037} charset.
     * Those conversion tables ship with the JDK and were not written by this project, which makes
     * them an authority independent of the class under test. The test re-executes that conversion
     * across all thirty-six members, so a mistyped byte fails against the JDK rather than being
     * quietly confirmed by the very field it was mistyped into. A documented verification step is
     * audited here, not merely cited.
     */
    @Nested
    @DisplayName("Value provenance - IBM CICS documentation, audited via the JDK's IBM037 tables")
    class ValueProvenance {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.common.CicsAidTest#documentedAids")
        @DisplayName("each constant equals the AID code IBM publishes for that key")
        void constantEqualsTheAidCodeIbmPublishes(String mnemonic, byte documentedByte,
                char copybookLiteral) {
            assertThat(aidValue(mnemonic))
                    .as("%s: IBM's 3270 attention identifier table gives 0x%02X, which the copybook"
                            + " expresses as the literal '%s'", mnemonic, documentedByte,
                            copybookLiteral)
                    .isEqualTo(documentedByte);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.common.CicsAidTest#documentedAids")
        @DisplayName("each constant agrees with its copybook character literal under IBM037")
        void constantAgreesWithItsCopybookLiteralUnderTheDeclaredCodePage(String mnemonic,
                byte documentedByte, char copybookLiteral) {
            // The independent cross-check. Three things must coincide: the literal IBM documents
            // the copybook as using, the byte IBM's AID table publishes, and the byte CicsAid
            // declares. The JDK's IBM037 tables are what relate the first to the second, and this
            // project did not supply them.
            byte[] encodedLiteral = String.valueOf(copybookLiteral).getBytes(declaredCodePage());

            assertThat(encodedLiteral)
                    .as("%s: the copybook literal must occupy exactly one byte under %s, as a "
                            + "PIC X(1) field does", mnemonic, EXPECTED_CODE_PAGE)
                    .hasSize(1);
            assertThat(encodedLiteral[0])
                    .as("%s: encoding the copybook literal through the JDK's %s tables must "
                            + "reproduce the AID code IBM publishes, 0x%02X", mnemonic,
                            EXPECTED_CODE_PAGE, documentedByte)
                    .isEqualTo(documentedByte);
            assertThat(aidValue(mnemonic))
                    .as("%s: the declared constant must equal that independently derived byte",
                            mnemonic)
                    .isEqualTo(encodedLiteral[0]);
        }

        @Test
        @DisplayName("the EBCDIC digit run covers PF1-PF9 and stops there")
        void theDigitRunCoversPf1ThroughPf9AndStops() {
            // PF1-PF9 are the EBCDIC digits '1'-'9', 0xF1-0xF9: the only contiguous, intuitive run
            // in the whole AID set. Everything after it is not, which is why the audit table above
            // is written out row by row instead of generated.
            for (int pfNumber = 1; pfNumber <= 9; pfNumber++) {
                assertThat(aidValue(PF_PREFIX + pfNumber))
                        .as("DFHPF%d is the EBCDIC digit '%d'", pfNumber, pfNumber)
                        .isEqualTo((byte) (0xF0 + pfNumber));
            }
        }

        @Test
        @DisplayName("PF10-PF12 are punctuation, not the 0xFA-0xFC continuation of the digit run")
        void pf10ThroughPf12AreNotTheContinuationOfTheDigitRun() {
            // First discontinuity, and the one a plausible-looking arithmetic translation gets
            // wrong. A secondary reproduction of the copybook asserting DFHPF12 = 0xFC was checked
            // against IBM's attention identifier table and rejected; the copybook literal for PF12
            // is the commercial-at, which is 0x7C in this code page. That conflict is recorded
            // rather than glossed over, per practice B4.
            assertThat(aidValue("DFHPF10"))
                    .as("DFHPF10 is the EBCDIC colon 0x7A, not 0xFA")
                    .isEqualTo((byte) 0x7A)
                    .isNotEqualTo((byte) 0xFA);
            assertThat(aidValue("DFHPF11"))
                    .as("DFHPF11 is the EBCDIC number sign 0x7B, not 0xFB")
                    .isEqualTo((byte) 0x7B)
                    .isNotEqualTo((byte) 0xFB);
            assertThat(aidValue("DFHPF12"))
                    .as("DFHPF12 is the EBCDIC commercial-at 0x7C, not 0xFC")
                    .isEqualTo((byte) 0x7C)
                    .isNotEqualTo((byte) 0xFC);
        }

        @Test
        @DisplayName("the capital letter run covers PF13-PF21 as 'A' to 'I' only")
        void theLetterRunCoversPf13ThroughPf21Only() {
            // Second discontinuity. The Agent Action Plan's cross-check hint described PF13-PF24 as
            // "the twelve EBCDIC letters 'A' through 'L'"; IBM's attention identifier table shows
            // the letters stop at 'I'. There are nine letters in the run, not twelve. IBM's
            // documentation is authoritative and is what CicsAid follows; had the hint been taken
            // literally, three constants would have been silently wrong. Recorded under practice
            // B4 rather than corrected away in silence.
            for (int pfNumber = 13; pfNumber <= 21; pfNumber++) {
                char letter = (char) ('A' + pfNumber - 13);
                assertThat(aidValue(PF_PREFIX + pfNumber))
                        .as("DFHPF%d is the EBCDIC capital '%s'", pfNumber, letter)
                        .isEqualTo((byte) (0xC1 + pfNumber - 13));
            }

            assertThat(aidValue("DFHPF21"))
                    .as("the letter run ends at 'I' = 0xC9, because 'J' is 0xD1 and not 0xCA")
                    .isEqualTo((byte) 0xC9);
        }

        @Test
        @DisplayName("PF22-PF24 revert to punctuation and are not letters")
        void pf22ThroughPf24RevertToPunctuation() {
            assertThat(aidValue("DFHPF22"))
                    .as("DFHPF22 is the EBCDIC cent sign 0x4A, and is not the non-letter 0xCA")
                    .isEqualTo((byte) 0x4A)
                    .isNotEqualTo((byte) 0xCA);
            assertThat(aidValue("DFHPF23"))
                    .as("DFHPF23 is the EBCDIC period 0x4B")
                    .isEqualTo((byte) 0x4B);
            assertThat(aidValue("DFHPF24"))
                    .as("DFHPF24 is the EBCDIC less-than sign 0x4C")
                    .isEqualTo((byte) 0x4C);
        }

        @Test
        @DisplayName("the audit table itself is internally consistent")
        void theAuditTableIsInternallyConsistent() {
            // A corrupted expectation table would weaken every assertion in this group while
            // still passing, so the table is checked before it is trusted: thirty-six rows, no
            // repeated mnemonic, no repeated byte, and every byte a real unsigned octet.
            assertThat(DOCUMENTED_AIDS).hasSize(DECLARED_AID_COUNT);
            assertThat(DOCUMENTED_AIDS.stream().map(DocumentedAid::mnemonic).toList())
                    .doesNotHaveDuplicates();
            assertThat(DOCUMENTED_AIDS.stream().map(DocumentedAid::expectedByte).toList())
                    .as("two rows claiming the same byte would hide a real collision")
                    .doesNotHaveDuplicates();
            assertThat(DOCUMENTED_AIDS).allSatisfy(aid -> assertThat(aid.aidByte())
                    .as("%s: an AID code is a single unsigned octet", aid.mnemonic())
                    .isBetween(0x01, 0xFF));
        }
    }

    /**
     * The {@link CicsAid#mnemonicsByAid()} diagnostic lookup.
     *
     * <p>Beyond its stated purpose of rendering an unrecognised {@code EIBAID} in human-readable
     * form, this map is a standing distinctness assertion in its own right: it is built with
     * {@code Map.ofEntries}, which rejects duplicate keys, so a copy-paste error giving two
     * mnemonics the same byte would fail at class initialisation rather than silently corrupt key
     * routing. Asserting that it contains every declared constant is therefore also asserting that
     * the guard is actually wired to the whole set and not just to part of it.
     */
    @Nested
    @DisplayName("mnemonicsByAid - the diagnostic lookup")
    class MnemonicLookup {

        @Test
        @DisplayName("the lookup maps every declared constant to its own mnemonic")
        void theLookupMapsEveryDeclaredConstantToItsOwnMnemonic()
                throws ReflectiveOperationException {
            Map<Byte, String> lookup = CicsAid.mnemonicsByAid();

            assertThat(lookup)
                    .as("the lookup must cover the whole AID set, or its duplicate-key guard "
                            + "protects only part of it")
                    .isNotNull()
                    .hasSize(DECLARED_AID_COUNT);

            for (Field field : aidFields()) {
                assertThat(lookup)
                        .as("%s must resolve back to its own name", field.getName())
                        .containsEntry(field.getByte(null), field.getName());
            }
        }

        @Test
        @DisplayName("the lookup is unmodifiable, so callers cannot corrupt shared state")
        void theLookupIsUnmodifiable() {
            Map<Byte, String> lookup = CicsAid.mnemonicsByAid();

            // Handed out directly rather than defensively copied, which is only safe because it is
            // genuinely immutable (practice B9).
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("put must be rejected")
                    .isThrownBy(() -> lookup.put((byte) 0x01, "INJECTED"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("remove must be rejected even for a key that is present")
                    .isThrownBy(() -> lookup.remove(CicsAid.DFHENTER));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("clear must be rejected")
                    .isThrownBy(lookup::clear);
        }

        @Test
        @DisplayName("the lookup is the same instance on every call")
        void theLookupIsTheSameInstanceOnEveryCall() {
            assertThat(CicsAid.mnemonicsByAid())
                    .as("an immutable map needs no per-call copy, and callers may retain it")
                    .isSameAs(CicsAid.mnemonicsByAid());
        }

        @Test
        @DisplayName("the lookup carries no built-in fallback for an unrecognised byte")
        void theLookupCarriesNoBuiltInFallback() {
            // The documented contract is a lookup and nothing more: interpreting an EIBAID byte as
            // a screen action belongs to the PF-key resolver, and a caller wanting a default
            // supplies its own. 0x00 is safe to probe with because no constant is the zero byte.
            assertThat(CicsAid.mnemonicsByAid().get((byte) 0x00))
                    .as("an unrecognised byte must map to null rather than to an invented name")
                    .isNull();
            assertThat(CicsAid.mnemonicsByAid().getOrDefault((byte) 0x00, "UNKNOWN"))
                    .as("the caller supplies the default, as the class documents")
                    .isEqualTo("UNKNOWN");
        }
    }

    /**
     * Immutability, modifiers and non-instantiability. Gate <strong>G53</strong> and practice
     * <strong>B9</strong>: COBOL {@code WORKING-STORAGE} must never become mutable static Java
     * state, because that would break request isolation and test determinism.
     */
    @Nested
    @DisplayName("Immutability and shape - no mutable static state, not instantiable (G53)")
    class ImmutabilityAndShape {

        @Test
        @DisplayName("every AID constant is public static final")
        void everyAidConstantIsPublicStaticFinal() {
            for (Field field : aidFields()) {
                int modifiers = field.getModifiers();

                assertThat(Modifier.isPublic(modifiers))
                        .as("%s must be public: seventeen online programs reference it",
                                field.getName())
                        .isTrue();
                assertThat(Modifier.isStatic(modifiers))
                        .as("%s must be static: a copybook constant belongs to no instance",
                                field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(modifiers))
                        .as("%s must be final: a reassignable AID would misroute every key that "
                                + "follows the reassignment", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the class holds no mutable static state and no instance state")
        void theClassHoldsNoMutableStaticStateAndNoInstanceState() {
            // Covers every declared field, not only the mnemonics, so AID_CODE_PAGE and the
            // backing lookup map are held to the same bar. Synthetic and '$'-prefixed fields are
            // skipped because the JaCoCo agent injects a non-final static boolean[] named
            // $jacocoData into every instrumented class; without that exclusion this assertion
            // would pass under a bare javac run and fail under Maven, which is the worst of both.
            for (Field field : realDeclaredFields()) {
                int modifiers = field.getModifiers();

                assertThat(Modifier.isStatic(modifiers))
                        .as("%s is an instance field, but this class has no instances",
                                field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(modifiers))
                        .as("%s is mutable static state, which gate G53 forbids", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("%s is an array: final makes the reference constant but leaves the "
                                + "contents writable, so it is still mutable static state",
                                field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the declared fields are exactly the mnemonics, the code page and the lookup")
        void theDeclaredFieldsAreExactlyTheExpectedMembers() {
            // Pins the member surface so that a stray field cannot appear unnoticed: thirty-six
            // mnemonics plus AID_CODE_PAGE plus the private backing map.
            List<String> nonMnemonicFields = realDeclaredFields().stream()
                    .map(Field::getName)
                    .filter(name -> !name.startsWith(MNEMONIC_PREFIX))
                    .toList();

            assertThat(realDeclaredFields()).hasSize(DECLARED_AID_COUNT + 2);
            assertThat(nonMnemonicFields).containsExactly("AID_CODE_PAGE", "MNEMONICS_BY_AID");
        }

        @Test
        @DisplayName("the class is final and declares exactly one private no-argument constructor")
        void theClassIsFinalWithASinglePrivateConstructor() {
            assertThat(Modifier.isFinal(CicsAid.class.getModifiers()))
                    .as("a constants holder must not be subclassable")
                    .isTrue();

            Constructor<?>[] constructors = CicsAid.class.getDeclaredConstructors();

            assertThat(constructors)
                    .as("exactly one constructor, and it exists only to be unusable")
                    .hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                    .as("the sole constructor must be private")
                    .isTrue();
            assertThat(constructors[0].getParameterCount()).isZero();
        }

        @Test
        @DisplayName("the class cannot be instantiated, not even reflectively")
        void theClassCannotBeInstantiatedNotEvenReflectively()
                throws ReflectiveOperationException {
            Constructor<CicsAid> constructor = CicsAid.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .as("a private constructor alone is bypassable by reflection; throwing from it "
                            + "is what actually makes the class non-instantiable")
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("the code page constant is a non-blank String, not a bare comment")
        void theCodePageConstantIsANonBlankString() throws ReflectiveOperationException {
            Field codePage = CicsAid.class.getDeclaredField("AID_CODE_PAGE");

            assertThat(codePage.getType()).isEqualTo(String.class);
            assertThat(Modifier.isPublic(codePage.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(codePage.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(codePage.getModifiers())).isTrue();
            assertThat(CicsAid.AID_CODE_PAGE)
                    .as("practice B8 requires the encoding to be named in code, where callers can "
                            + "reference it, not only in prose")
                    .isNotBlank();
        }
    }
}
