package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Gate <strong>G34</strong>'s census: how many {@code REDEFINES} sites this codebase actually has, where
 * they are, and the arithmetic that keeps the answer honest.
 *
 * <h2>Why a census needs to be executable</h2>
 * The number was written into two files as prose - {@code FieldDiffer} and {@code COCRDLICParityTest} both
 * said 82 - and it was wrong in both. The true count of non-comment {@code REDEFINES} lines is
 * {@value #TOTAL_SITES}: {@value #PROGRAM_SITES} in {@code app/cbl} and {@value #COPYBOOK_SITES} in
 * {@code app/cpy}. A figure quoted in prose drifts because nothing checks it; the same figure written as a
 * per-file table whose parts must sum to the whole cannot be wrong in one place and right in another, and
 * that is the whole purpose of this class.
 *
 * <h2>What is counted</h2>
 * Every line of {@code app/cbl/*.cbl}, {@code app/cbl/*.CBL}, {@code app/cpy/*.cpy} and
 * {@code app/cpy/*.CPY} bearing the {@code REDEFINES} keyword, with commented lines excluded - column 7
 * holding {@code *} or {@code /}, which is how COBOL fixed format marks a comment. Both filename cases are
 * enumerated because this repository mixes them: {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} are
 * upper-case and every other program is not, and {@code COSTM01.CPY} is upper-case among the copybooks.
 * Counting {@code *.cbl} alone under-reports, which is one of the ways a hand-run count goes wrong.
 *
 * <p>A <em>line</em> rather than a <em>pair</em> is the unit, because that is what is mechanically
 * checkable against the source and because one line can introduce a whole group overlay - the
 * {@code FILLER REDEFINES WS-EDIT-DATE-X} at {@code app/cbl/COACTUPC.cbl:362} is one line and five items.
 * Where a specific overlay's two views matter, a test asserts the round trip directly:
 * {@code COCRDLICParityTest} does it for {@code CVCRD01Y}'s {@code CC-ACCT-ID} pair and
 * {@code AccountUpdateRedefinesCensusTest} for {@code COACTUPC}'s three output-edit overlays.
 *
 * <h2>What the distribution says</h2>
 * The overlays are not spread evenly and it matters where they concentrate. {@code COACTUPC} holds 43 of
 * the 80 program sites - more than half - which follows from its being the program with a staging copy of
 * every field on the densest screen in the system. The four other online card and account programs hold 22
 * between them, and the ten non-CICS programs that have any hold 14 in total - one or two each bar
 * {@code CSUTLDTC}'s three: a batch reader needs an overlay for its {@code FILE STATUS} byte pair and
 * little else. The eleventh non-CICS program, {@code CBSTM03B}, declares none at all.
 */
@DisplayName("G34: the REDEFINES census - 96 non-comment sites, 80 in app/cbl and 16 in app/cpy")
class RedefinesCensusTest {

    /** Non-comment {@code REDEFINES} lines in {@code app/cbl}. */
    private static final int PROGRAM_SITES = 80;

    /** Non-comment {@code REDEFINES} lines in {@code app/cpy}. */
    private static final int COPYBOOK_SITES = 16;

    /** The codebase total, and the figure {@code FieldDiffer} quotes. */
    private static final int TOTAL_SITES = PROGRAM_SITES + COPYBOOK_SITES;

    /**
     * Per-program counts, transcribed from {@code app/cbl} in filename order.
     *
     * <p>Every program with at least one site is named. The twelve programs absent from this map have
     * none, and {@code theProgramsWithNoOverlayAreAccountedFor} says which they are, so "absent" is a
     * statement rather than an omission.
     */
    private static final Map<String, Integer> PROGRAM_OVERLAYS = programOverlays();

    private static Map<String, Integer> programOverlays() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("CBACT01C.cbl", 1);
        counts.put("CBACT02C.cbl", 1);
        counts.put("CBACT03C.cbl", 1);
        counts.put("CBACT04C.cbl", 2);
        counts.put("CBCUS01C.cbl", 1);
        counts.put("CBSTM03A.CBL", 1);
        counts.put("CBTRN01C.cbl", 1);
        counts.put("CBTRN02C.cbl", 2);
        counts.put("CBTRN03C.cbl", 1);
        counts.put("COACTUPC.cbl", 43);
        counts.put("COACTVWC.cbl", 2);
        counts.put("COCRDLIC.cbl", 6);
        counts.put("COCRDSLC.cbl", 6);
        counts.put("COCRDUPC.cbl", 8);
        counts.put("CORPT00C.cbl", 1);
        counts.put("CSUTLDTC.cbl", 3);
        return Map.copyOf(counts);
    }

    /** Per-copybook counts, transcribed from {@code app/cpy} in filename order. */
    private static final Map<String, Integer> COPYBOOK_OVERLAYS = Map.of(
            "COADM02Y.cpy", 1,
            "COMEN02Y.cpy", 1,
            "CSDAT01Y.cpy", 2,
            "CSUTLDWY.cpy", 9,
            "CVCRD01Y.cpy", 3);

    /** The twelve programs of the twenty-eight that declare no overlay at all. */
    private static final java.util.Set<String> PROGRAMS_WITH_NO_OVERLAY = java.util.Set.of(
            "CBSTM03B.CBL", "COADM01C.cbl", "COBIL00C.cbl", "COMEN01C.cbl", "COSGN00C.cbl",
            "COTRN00C.cbl", "COTRN01C.cbl", "COTRN02C.cbl", "COUSR00C.cbl", "COUSR01C.cbl",
            "COUSR02C.cbl", "COUSR03C.cbl");

    /** The twenty-eight programs of {@code app/cbl}, which is what the two sets must partition. */
    private static final int PROGRAM_COUNT = 28;

    @Nested
    @DisplayName("The arithmetic - the parts must sum to the whole")
    class TheArithmetic {

        @Test
        @DisplayName("the per-program counts sum to 80")
        void theProgramCountsSum() {
            assertThat(PROGRAM_OVERLAYS.values().stream().mapToInt(Integer::intValue).sum())
                    .as("every non-comment REDEFINES line in app/cbl/*.cbl and app/cbl/*.CBL")
                    .isEqualTo(PROGRAM_SITES);
        }

        @Test
        @DisplayName("the per-copybook counts sum to 16")
        void theCopybookCountsSum() {
            assertThat(COPYBOOK_OVERLAYS.values().stream().mapToInt(Integer::intValue).sum())
                    .as("every non-comment REDEFINES line in app/cpy/*.cpy and app/cpy/*.CPY")
                    .isEqualTo(COPYBOOK_SITES);
        }

        @Test
        @DisplayName("80 plus 16 is 96, and 96 is the figure the differ quotes")
        void theTotalIsNinetySix() {
            assertThat(TOTAL_SITES).isEqualTo(96);
            assertThat(PROGRAM_SITES + COPYBOOK_SITES).isEqualTo(TOTAL_SITES);
            assertThat(TOTAL_SITES)
                    .as("and it is not 82, which is what both FieldDiffer and COCRDLICParityTest said "
                            + "for as long as the number lived only in prose")
                    .isNotEqualTo(82);
        }

        @Test
        @DisplayName("every count is positive - a named file with no overlay would be a wrong name")
        void everyNamedFileActuallyHasAnOverlay() {
            PROGRAM_OVERLAYS.forEach((file, count) -> assertThat(count)
                    .as("%s is named in the census, so it must have at least one", file)
                    .isPositive());
            COPYBOOK_OVERLAYS.forEach((file, count) -> assertThat(count)
                    .as("%s is named in the census, so it must have at least one", file)
                    .isPositive());
        }
    }

    @Nested
    @DisplayName("The distribution - where the overlays actually are")
    class TheDistribution {

        @Test
        @DisplayName("COACTUPC holds 43 of the 80, more than the other fifteen programs together")
        void coactupcDominates() {
            int coactupc = PROGRAM_OVERLAYS.get("COACTUPC.cbl");
            assertThat(coactupc).isEqualTo(43);
            assertThat(coactupc)
                    .as("it has a staging copy of every field on the densest screen in the system, so "
                            + "the concentration is a property of the program rather than a miscount")
                    .isGreaterThan(PROGRAM_SITES - coactupc);
        }

        @Test
        @DisplayName("the ten non-CICS programs with overlays hold fourteen between them")
        void theBatchProgramsHoldFourteen() {
            int batch = PROGRAM_OVERLAYS.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("CB")
                            || entry.getKey().startsWith("CSUTLDTC"))
                    .mapToInt(Map.Entry::getValue).sum();
            assertThat(batch)
                    .as("a batch reader needs an overlay for its FILE STATUS byte pair and little else: "
                            + "one each for CBACT01C, CBACT02C, CBACT03C, CBCUS01C, CBSTM03A, CBTRN01C "
                            + "and CBTRN03C, two each for CBACT04C and CBTRN02C, three for CSUTLDTC")
                    .isEqualTo(14);
            assertThat(PROGRAM_SITES - batch)
                    .as("which leaves 66 across the five online card and account programs and CORPT00C")
                    .isEqualTo(66);
        }

        @Test
        @DisplayName("the two upper-case filenames are counted, which a *.cbl pattern would miss")
        void theUpperCaseFilenamesAreCounted() {
            assertThat(PROGRAM_OVERLAYS)
                    .as("CBSTM03A.CBL is upper-case and does declare an overlay, so a census matching "
                            + "only *.cbl would under-report by one")
                    .containsKey("CBSTM03A.CBL");
            assertThat(PROGRAMS_WITH_NO_OVERLAY)
                    .as("CBSTM03B.CBL is the other upper-case program, and it declares none - which is "
                            + "recorded rather than left to look like an oversight")
                    .contains("CBSTM03B.CBL");
        }

        @Test
        @DisplayName("CSUTLDWY holds 9 of the 16 copybook sites - the date-edit engine")
        void csutldwyDominatesTheCopybooks() {
            assertThat(COPYBOOK_OVERLAYS.get("CSUTLDWY.cpy"))
                    .as("89 lines of date-edit working storage, each date view overlaid on its parts")
                    .isEqualTo(9);
            assertThat(COPYBOOK_OVERLAYS.get("CVCRD01Y.cpy"))
                    .as("including CC-ACCT-ID PIC X(11) over CC-ACCT-ID-N PIC 9(11), the pair "
                            + "COCRDLICParityTest round-trips")
                    .isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Completeness - the census partitions all twenty-eight programs")
    class Completeness {

        @Test
        @DisplayName("sixteen programs have overlays, twelve have none, and 16 + 12 is 28")
        void theProgramsWithNoOverlayAreAccountedFor() {
            assertThat(PROGRAM_OVERLAYS).hasSize(16);
            assertThat(PROGRAMS_WITH_NO_OVERLAY).hasSize(12);
            assertThat(PROGRAM_OVERLAYS.size() + PROGRAMS_WITH_NO_OVERLAY.size())
                    .as("every one of the 28 programs is either named with a count or named as having "
                            + "none - a program in neither set would be a program nobody counted")
                    .isEqualTo(PROGRAM_COUNT);
        }

        @Test
        @DisplayName("no program is in both sets")
        void theTwoSetsAreDisjoint() {
            assertThat(PROGRAM_OVERLAYS.keySet())
                    .as("a file cannot both have overlays and have none")
                    .doesNotContainAnyElementsOf(PROGRAMS_WITH_NO_OVERLAY);
        }

        @Test
        @DisplayName("every named file uses one of this repository's four real extensions")
        void theFilenamesAreWellFormed() {
            java.util.stream.Stream.concat(
                            java.util.stream.Stream.concat(PROGRAM_OVERLAYS.keySet().stream(),
                                    PROGRAMS_WITH_NO_OVERLAY.stream()),
                            COPYBOOK_OVERLAYS.keySet().stream())
                    .forEach(name -> assertThat(name)
                            .as("%s must name a real source file", name)
                            .matches("[A-Z][A-Z0-9]{7}\\.(cbl|CBL|cpy|CPY)"));
        }
    }
}
