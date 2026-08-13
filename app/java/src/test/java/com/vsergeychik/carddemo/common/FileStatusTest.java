package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link FileStatus}, the single status vocabulary shared by the batch
 * {@code FILE STATUS} tests and the online CICS {@code RESP} tests.
 *
 * <h2>What this suite is responsible for</h2>
 * <p>{@code FileStatus} carries two obligations, and this suite pins both. The first is the
 * <em>vocabulary</em>: the four two-character batch statuses, the eight CICS responses, the two
 * {@code APPL-RESULT} condition values, and the collapse of all of them onto one discriminated
 * {@link Outcome} so that a repository can hand the same answer to a Spring Batch job and to a REST
 * controller without either caller's branch structure changing. The second is the
 * <em>renderer</em>: the {@code 9910-DISPLAY-IO-STATUS} paragraph, whose two-branch shape and
 * counter-intuitive display literal are reproduced verbatim rather than tidied.
 *
 * <p>Per-call-site coverage - "outcome {@code '00'} is exercised where the account repository reads,
 * outcome {@code '10'} where it browses" - belongs to each repository's own test. This suite owns
 * the vocabulary and the renderer, and it drives every predicate from both sides so that both states
 * of every condition name are evidenced.
 *
 * <h2>Where the expected values come from</h2>
 * <p>The legacy COBOL <strong>cannot be executed in this environment</strong>: there is no z/OS
 * runtime, the available compiler has indexed file support disabled and no Language Environment
 * {@code CEE*} services, and no CICS emulator is present. Every expectation below is therefore
 * <em>statically derived</em> from the source, and each group cites the {@code file:line} it was
 * derived from so a failure points at a specific translation decision instead of merely at a value.
 * The citations were verified against this checkout:
 *
 * <ul>
 *   <li>{@code app/cbl/CBACT01C.cbl:50-59} - the {@code IO-STATUS}, {@code TWO-BYTES-BINARY},
 *       {@code TWO-BYTES-ALPHA} and {@code IO-STATUS-04} declarations that fix the two-character
 *       status width and the four-character image width.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl:61-63} - {@code 01 APPL-RESULT PIC S9(9) COMP} with
 *       {@code 88 APPL-AOK VALUE 0} and {@code 88 APPL-EOF VALUE 16}.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl:94-101} - the guard that links a status to a result value:
 *       {@code '00'} moves {@code 0}, {@code '10'} moves {@code 16}, anything else moves
 *       {@code 12}. {@code :134} additionally moves {@code 8} before an {@code OPEN}, so {@code 8}
 *       and {@code 12} are real values that satisfy neither condition name.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl:176-189} - the renderer itself.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL:353-362} - the three-way caller shape
 *       ({@code WHEN '00'} continue, {@code WHEN '10'} set end-of-file, {@code WHEN OTHER} display
 *       then abend), plus the two-armed variant at {@code :379-386} and {@code :403-410}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:422} and {@code app/cbl/CBTRN02C.cbl:481} - the compound
 *       {@code IF <FILE>-STATUS = '00' OR '23'} that makes not-found a success path.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - the eight CICS {@code FILE} definitions whose
 *       {@code READ / UPDATE / BROWSE / ADD / DELETE} capabilities are the operations whose
 *       responses this vocabulary has to describe.</li>
 * </ul>
 *
 * <h2>Two traps this suite exists to catch</h2>
 * <p><strong>Trap one - {@code NNNN} is a literal, not a template.</strong>
 * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} emits the literal text <em>and then</em> the
 * field, so a real line of output reads {@code FILE STATUS IS: NNNN0010}. An implementation that
 * "helpfully" substituted the digits into the {@code NNNN} would emit different bytes and break
 * byte-level comparison against the legacy output. The composed line is therefore asserted
 * byte-exactly, including the single space after the colon and the surviving {@code NNNN}.
 *
 * <p><strong>Trap two - the renderer's condition has an operand reachable only one way.</strong>
 * {@code IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'} has two independent operands. A status such as
 * {@code "90"} <em>is</em> numeric, so it reaches the extended branch solely through the second
 * operand. Omit that case and the class silently sits below the mandated branch bar with no obvious
 * gap in the report, so it is driven explicitly and labelled where it happens.
 *
 * <h2>Deliberate non-dependency</h2>
 * <p>{@code FileStatus} imports only {@code java.util.Optional} and {@code java.util.OptionalInt} -
 * it references no other type from this application, which makes it a root of the dependency graph.
 * It classifies and renders; the sibling abend type terminates. This suite honours that split and
 * never mentions the abend type, and {@link ClassShapeAndImmutability} asserts the separation
 * structurally rather than trusting a comment.
 */
@DisplayName("FileStatus - one status vocabulary for batch FILE STATUS and CICS RESP")
class FileStatusTest {

    /**
     * The display literal, transcribed independently of the class under test so that the assertions
     * compare against the COBOL rather than against the implementation's own constant.
     *
     * <p>Derived from {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}
     * [{@code app/cbl/CBACT01C.cbl:183} and {@code :187}]. Note the single space after the colon and
     * the four literal {@code N} characters that are <em>not</em> placeholders.
     */
    private static final String EXPECTED_DISPLAY_PREFIX = "FILE STATUS IS: NNNN";

    /** Length of {@link #EXPECTED_DISPLAY_PREFIX}: {@code "FILE STATUS IS: "} is 16, plus 4. */
    private static final int EXPECTED_PREFIX_LENGTH = 20;

    /** Length of a full display line: the 20-character prefix plus the 4-character image. */
    private static final int EXPECTED_DISPLAY_LINE_LENGTH = 24;

    /**
     * Statuses that no program in the estate enumerates, used to drive every {@code WHEN OTHER} and
     * {@code ELSE} arm. All are well-formed two-character values, so they pass the width guard and
     * reach the classification logic itself.
     *
     * <p>{@code '04'} is deliberately <strong>not</strong> among them, and used to be. It is a named
     * constant now - {@link FileStatus#RECORD_LENGTH_CONFLICT} - because
     * {@code app/cbl/CBSTM03A.CBL} enumerates it at nine sites, so calling it unenumerated would be
     * false. {@code '35'} stands in its place: a real COBOL file status that no program in this estate
     * compares against, keeping the four samples spread across a VSAM extended status, two ordinary
     * unlisted ones and a non-numeric one.
     */
    private static final List<String> UNENUMERATED_STATUSES = List.of("99", "37", "35", "9A");

    @Nested
    @DisplayName("Batch FILE STATUS literals")
    class BatchStatusLiterals {

        @Test
        @DisplayName("'00' is success - the status every OPEN, READ, WRITE and CLOSE guard tests")
        void okIsZeroZero() {
            // IF ACCTFILE-STATUS = '00' [app/cbl/CBACT01C.cbl:94, :136, :154]
            assertThat(FileStatus.OK).isEqualTo("00");
        }

        @Test
        @DisplayName("'04' is a record-length conflict - a SUCCESSFUL read of a non-conforming record")
        void recordLengthConflictIsZeroFour() {
            // IF WS-M03B-RC = '00' OR '04' [app/cbl/CBSTM03A.CBL:736, :748, :771, :789, :807, :862,
            // :879, :895, :911] - nine sites, and the one at :748 is a READ rather than an OPEN or a
            // CLOSE, which is what makes '04' a status the program expects to see on a record it goes on
            // to use.
            assertThat(FileStatus.RECORD_LENGTH_CONFLICT).isEqualTo("04")
                    .hasSize(FileStatus.STATUS_LENGTH);
        }

        @Test
        @DisplayName("'04' is not folded into success, because three sites in one program abend on it")
        void recordLengthConflictIsNotSuccess() {
            // The loop read's EVALUATE at app/cbl/CBSTM03A.CBL:836-847 has arms for '00' and '10' only,
            // so '04' reaches WHEN OTHER and abends; the two keyed reads (:379-386, :403-410) do the
            // same. Which sites accept '04' is therefore a property of each site, and no compound
            // predicate here may decide it for them.
            assertThat(FileStatus.isOk(FileStatus.RECORD_LENGTH_CONFLICT)).isFalse();
            assertThat(FileStatus.isOkOrNotFound(FileStatus.RECORD_LENGTH_CONFLICT)).isFalse();
            assertThat(FileStatus.RECORD_LENGTH_CONFLICT).isNotEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("'10' is end of file - the status that moves 16 into APPL-RESULT")
        void endOfFileIsOneZero() {
            // IF ACCTFILE-STATUS = '10' / MOVE 16 TO APPL-RESULT [app/cbl/CBACT01C.cbl:98-99]
            assertThat(FileStatus.END_OF_FILE).isEqualTo("10");
        }

        @Test
        @DisplayName("'22' is duplicate key, even though no batch program compares against it")
        void duplicateIsTwoTwo() {
            // A comment-stripped search of all 28 programs in app/cbl finds zero literal '22'
            // comparisons; the online programs express the same condition as DUPREC and DUPKEY.
            // The constant is required by the status set and is kept rather than "resolved" away.
            assertThat(FileStatus.DUPLICATE).isEqualTo("22");
        }

        @Test
        @DisplayName("'23' is record not found - and is a success path in two programs")
        void notFoundIsTwoThree() {
            // IF DISCGRP-STATUS = '00' OR '23' [app/cbl/CBACT04C.cbl:422]
            // IF TCATBALF-STATUS = '00' OR '23' [app/cbl/CBTRN02C.cbl:481]
            assertThat(FileStatus.NOT_FOUND).isEqualTo("23");
        }

        @ParameterizedTest(name = "\"{0}\" is exactly two characters")
        @ValueSource(strings = {"00", "04", "10", "22", "23"})
        @DisplayName("every status literal is exactly two characters, matching the PIC X pair")
        void everyLiteralIsExactlyTwoCharacters(String literal) {
            // 05 IO-STAT1 PIC X. 05 IO-STAT2 PIC X. [app/cbl/CBACT01C.cbl:51-52] - two single-byte
            // items and nothing else, so a status is two characters wide by declaration.
            assertThat(literal).hasSize(FileStatus.STATUS_LENGTH);
        }

        @Test
        @DisplayName("the five literals are the exact set, with no duplicates among them")
        void theFiveLiteralsAreDistinct() {
            Set<String> literals = new LinkedHashSet<>(
                    List.of(FileStatus.OK, FileStatus.RECORD_LENGTH_CONFLICT, FileStatus.END_OF_FILE,
                            FileStatus.DUPLICATE, FileStatus.NOT_FOUND));

            assertThat(literals).hasSize(5).containsExactly("00", "04", "10", "22", "23");
        }

        @Test
        @DisplayName("a status is a String and never an int, because an int loses the field width")
        void statusesAreStringsAndNeverIntegers() {
            // "00" parses to the value 0, and 0 renders as the single character "0". The value
            // survives the conversion; the two-byte width does not. The width is exactly what the
            // COBOL declaration fixes and what the renderer's overlay depends on, which is why the
            // primary representation here is a two-character String.
            assertThat(Integer.parseInt(FileStatus.OK)).isZero();
            assertThat(String.valueOf(Integer.parseInt(FileStatus.OK))).isEqualTo("0").hasSize(1);
            assertThat(FileStatus.OK).hasSize(FileStatus.STATUS_LENGTH).isNotEqualTo("0");
        }

        @Test
        @DisplayName("STATUS_LENGTH is 2, fixed by the two PIC X items")
        void statusLengthIsTwo() {
            assertThat(FileStatus.STATUS_LENGTH).isEqualTo(2);
        }

        @Test
        @DisplayName("STATUS_IMAGE_LENGTH is 4, fixed by PIC 9 followed by PIC 999")
        void statusImageLengthIsFour() {
            // 05 IO-STATUS-0401 PIC 9 VALUE 0. 05 IO-STATUS-0403 PIC 999 VALUE 0.
            // [app/cbl/CBACT01C.cbl:58-59] - one digit plus three digits is four characters.
            assertThat(FileStatus.STATUS_IMAGE_LENGTH).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("outcomeOfStatus - classifying a batch status, WHEN OTHER included")
    class OutcomeClassification {

        @ParameterizedTest(name = "\"{0}\" classifies as {1}")
        @CsvSource({
            // The four enumerated arms, in the order the implementation tests them.
            "00, OK",
            "10, END_OF_FILE",
            "23, NOT_FOUND",
            "22, DUPLICATE",
            // WHEN OTHER [app/cbl/CBSTM03A.CBL:358-361]. Four distinct unenumerated values, not
            // one: a single sample could be satisfied by an accidental special case, whereas a VSAM
            // extended status, an ordinary unlisted status and a non-numeric one all landing on the
            // same arm evidences a genuine default.
            "99, OTHER",
            "37, OTHER",
            "35, OTHER",
            "9A, OTHER",
            // '04' is a NAMED constant with no named Outcome, and that is correct rather than an
            // oversight: the Outcome vocabulary is the four statuses the estate's guard chains branch on,
            // and no program branches on '04' as a class of outcome - CBSTM03A tests the literal.
            "04, OTHER",
        })
        @DisplayName("each status maps to its outcome, and everything unenumerated maps to OTHER")
        void statusMapsToOutcome(String status, Outcome expected) {
            assertThat(FileStatus.outcomeOfStatus(status)).isSameAs(expected);
        }

        @ParameterizedTest(name = "\"{0}\" is neither OK nor END_OF_FILE nor NOT_FOUND nor DUPLICATE")
        @MethodSource("unenumeratedStatuses")
        @DisplayName("an unenumerated status is not quietly folded into one of the named outcomes")
        void unenumeratedStatusIsNotFoldedIntoANamedOutcome(String status) {
            // Asserted as an exclusion as well as an inclusion, because the failure mode that
            // matters is a wrong status being *accepted* as success rather than merely misreported.
            assertThat(FileStatus.outcomeOfStatus(status))
                    .isSameAs(Outcome.OTHER)
                    .isNotIn(Outcome.OK, Outcome.END_OF_FILE, Outcome.NOT_FOUND, Outcome.DUPLICATE);
        }

        @Test
        @DisplayName("all five outcomes are reachable from a two-character status")
        void allFiveOutcomesAreReachable() {
            Set<Outcome> reached = new LinkedHashSet<>(List.of(
                    FileStatus.outcomeOfStatus(FileStatus.OK),
                    FileStatus.outcomeOfStatus(FileStatus.END_OF_FILE),
                    FileStatus.outcomeOfStatus(FileStatus.NOT_FOUND),
                    FileStatus.outcomeOfStatus(FileStatus.DUPLICATE),
                    FileStatus.outcomeOfStatus("99")));

            assertThat(reached).containsExactly(Outcome.OK, Outcome.END_OF_FILE, Outcome.NOT_FOUND,
                    Outcome.DUPLICATE, Outcome.OTHER);
        }

        @Test
        @DisplayName("the outcome vocabulary is exactly five constants, in declaration order")
        void thereAreExactlyFiveOutcomes() {
            // The size is asserted because a sixth constant would make every exhaustive switch in
            // the codebase - written without a default arm on purpose - fail to compile, and the
            // reason for that would be much clearer from this failure than from that one.
            assertThat(Outcome.values()).containsExactly(Outcome.OK, Outcome.END_OF_FILE,
                    Outcome.NOT_FOUND, Outcome.DUPLICATE, Outcome.OTHER);
        }

        @ParameterizedTest(name = "{0}.batchStatus() is non-null")
        @EnumSource(Outcome.class)
        @DisplayName("batchStatus() never returns null, for any outcome including OTHER")
        void batchStatusIsNeverNull(Outcome outcome) {
            assertThat(outcome.batchStatus()).isNotNull();
        }

        @ParameterizedTest(name = "{0}.batchStatus() is \"{1}\" and round-trips")
        @CsvSource({"OK, 00", "END_OF_FILE, 10", "NOT_FOUND, 23", "DUPLICATE, 22"})
        @DisplayName("the four enumerated outcomes round-trip back to their two-character status")
        void enumeratedOutcomesRoundTrip(Outcome outcome, String status) {
            assertThat(outcome.batchStatus()).contains(status);
            assertThat(FileStatus.outcomeOfStatus(status)).isSameAs(outcome);
        }

        @Test
        @DisplayName("OTHER carries no single status, because it stands for every unlisted one")
        void otherCarriesNoSingleStatus() {
            Optional<String> otherStatus = Outcome.OTHER.batchStatus();

            assertThat(otherStatus).isEmpty();
        }

        /**
         * The unenumerated statuses that must all reach {@code WHEN OTHER}.
         *
         * @return one argument per unenumerated status
         */
        private static Stream<Arguments> unenumeratedStatuses() {
            return UNENUMERATED_STATUSES.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("The caller branch shape from CBSTM03A - ok, eof, everything else abends")
    class CallerBranchShape {

        /** What the three-way dispatch decided; a stand-in for the COBOL's three actions. */
        private static final String CONTINUE = "CONTINUE";

        /** The {@code MOVE 'Y' TO END-OF-FILE} action. */
        private static final String SET_END_OF_FILE = "SET-END-OF-FILE";

        /** The {@code PERFORM 9999-ABEND-PROGRAM} action. */
        private static final String ABEND = "ABEND";

        @ParameterizedTest(name = "three-armed dispatch on \"{0}\" performs {1}")
        @CsvSource({
            "00, CONTINUE",
            "10, SET-END-OF-FILE",
            // Not-found and duplicate are fatal in *this* paragraph because it never enumerates
            // them - which is precisely why the classification keeps them as separate constants
            // instead of pre-collapsing them into OTHER. A different caller accepts them.
            "23, ABEND",
            "22, ABEND",
            "99, ABEND",
            "37, ABEND",
        })
        @DisplayName("the three-armed EVALUATE at CBSTM03A.CBL:353-362 is expressible verbatim")
        void threeArmedDispatchMatchesTheCobol(String returnCode, String expectedAction) {
            assertThat(threeArmedDispatch(returnCode)).isEqualTo(expectedAction);
        }

        @ParameterizedTest(name = "two-armed dispatch on \"{0}\" performs {1}")
        @CsvSource({
            "00, CONTINUE",
            // In the two-armed form there is no end-of-file arm, so '10' abends. Asserting this
            // separately keeps the two shapes from being conflated: the same status legitimately
            // produces different actions in different paragraphs of the same program.
            "10, ABEND",
            "23, ABEND",
            "99, ABEND",
        })
        @DisplayName("the two-armed EVALUATE at CBSTM03A.CBL:379-386 and :403-410 also holds")
        void twoArmedDispatchMatchesTheCobol(String returnCode, String expectedAction) {
            assertThat(twoArmedDispatch(returnCode)).isEqualTo(expectedAction);
        }

        @ParameterizedTest(name = "compound '00' OR '23' dispatch on \"{0}\" performs {1}")
        @CsvSource({
            "00, CONTINUE",
            "23, CONTINUE",
            // '10' and '22' are NOT part of the compound condition and must stay fatal there.
            "10, ABEND",
            "22, ABEND",
            "99, ABEND",
        })
        @DisplayName("the compound guard at CBACT04C.cbl:422 and CBTRN02C.cbl:481 also holds")
        void compoundDispatchMatchesTheCobol(String status, String expectedAction) {
            assertThat(compoundDispatch(status)).isEqualTo(expectedAction);
        }

        @Test
        @DisplayName("the three-armed switch needs no default arm, so no outcome can go unhandled")
        void theThreeArmedSwitchIsExhaustiveWithoutADefault() {
            // threeArmedDispatch below is an exhaustive switch expression over Outcome with no
            // default arm; it compiles only while all five constants are covered. Driving it with
            // every constant proves at run time what the compiler proved at build time.
            assertThat(Arrays.stream(Outcome.values()).map(this::actionForOutcome).toList())
                    .containsExactly(CONTINUE, SET_END_OF_FILE, ABEND, ABEND, ABEND);
        }

        /**
         * The three-armed dispatch from {@code app/cbl/CBSTM03A.CBL:353-362}, expressed over the
         * shared vocabulary.
         *
         * @param returnCode the two-character return code the subroutine reported
         * @return the action the COBOL performs for that code
         */
        private String threeArmedDispatch(String returnCode) {
            return actionForOutcome(FileStatus.outcomeOfStatus(returnCode));
        }

        /**
         * The action each outcome triggers in the three-armed paragraph. Written as an exhaustive
         * switch expression with no {@code default}, exactly as a translated job class should.
         *
         * @param outcome the classified outcome
         * @return the action the COBOL performs
         */
        private String actionForOutcome(Outcome outcome) {
            return switch (outcome) {
                case OK -> CONTINUE;
                case END_OF_FILE -> SET_END_OF_FILE;
                case NOT_FOUND, DUPLICATE, OTHER -> ABEND;
            };
        }

        /**
         * The two-armed dispatch from {@code app/cbl/CBSTM03A.CBL:379-386}, which has no
         * end-of-file arm.
         *
         * @param returnCode the two-character return code the subroutine reported
         * @return the action the COBOL performs for that code
         */
        private String twoArmedDispatch(String returnCode) {
            return FileStatus.isOk(returnCode) ? CONTINUE : ABEND;
        }

        /**
         * The compound guard from {@code app/cbl/CBACT04C.cbl:422} and
         * {@code app/cbl/CBTRN02C.cbl:481}, where not-found is a success path.
         *
         * @param status the two-character file status
         * @return the action the COBOL performs for that status
         */
        private String compoundDispatch(String status) {
            return FileStatus.isOkOrNotFound(status) ? CONTINUE : ABEND;
        }
    }

    @Nested
    @DisplayName("Predicates - one per status test the COBOL performs, driven from both sides")
    class Predicates {

        @ParameterizedTest(name = "isOk(\"{0}\") is {1}")
        @CsvSource({"00, true", "10, false", "22, false", "23, false", "99, false"})
        @DisplayName("isOk is true only for '00'")
        void isOkIsTrueOnlyForSuccess(String status, boolean expected) {
            assertThat(FileStatus.isOk(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isRecordLengthConflict(\"{0}\") is {1}")
        @CsvSource({"04, true", "00, false", "10, false", "22, false", "23, false", "99, false"})
        @DisplayName("isRecordLengthConflict is true only for '04'")
        void isRecordLengthConflictIsTrueOnlyForZeroFour(String status, boolean expected) {
            assertThat(FileStatus.isRecordLengthConflict(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isEndOfFile(\"{0}\") is {1}")
        @CsvSource({"10, true", "00, false", "22, false", "23, false", "99, false"})
        @DisplayName("isEndOfFile is true only for '10'")
        void isEndOfFileIsTrueOnlyForEndOfFile(String status, boolean expected) {
            assertThat(FileStatus.isEndOfFile(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isNotFound(\"{0}\") is {1}")
        @CsvSource({"23, true", "00, false", "10, false", "22, false", "99, false"})
        @DisplayName("isNotFound is true only for '23'")
        void isNotFoundIsTrueOnlyForNotFound(String status, boolean expected) {
            assertThat(FileStatus.isNotFound(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isDuplicate(\"{0}\") is {1}")
        @CsvSource({"22, true", "00, false", "10, false", "23, false", "99, false"})
        @DisplayName("isDuplicate is true only for '22'")
        void isDuplicateIsTrueOnlyForDuplicate(String status, boolean expected) {
            assertThat(FileStatus.isDuplicate(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isOkOrNotFound(\"{0}\") is {1}")
        @CsvSource({
            // The left operand alone satisfies the condition.
            "00, true",
            // The left operand fails and the right one carries it - the arm that a short-circuit
            // bug would skip entirely.
            "23, true",
            // Both operands fail. '10' and '22' are deliberately included: end-of-file and a
            // duplicate key are NOT part of the compound COBOL condition, and treating either as
            // acceptable would turn an abend into a silent success.
            "10, false",
            "22, false",
            "99, false",
            // '04' is not part of this compound condition either. It belongs to a different program's
            // guard shape entirely, and folding it in here would make every '00' OR '23' site in
            // CBACT04C and CBTRN02C silently accept a record-length conflict.
            "04, false",
        })
        @DisplayName("isOkOrNotFound reproduces IF <FILE>-STATUS = '00' OR '23' exactly")
        void isOkOrNotFoundReproducesTheCompoundCondition(String status, boolean expected) {
            assertThat(FileStatus.isOkOrNotFound(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "exactly one predicate matches \"{0}\"")
        @ValueSource(strings = {"00", "04", "10", "22", "23"})
        @DisplayName("the five predicates are mutually exclusive over the five recognised statuses")
        void thePredicatesAreMutuallyExclusive(String status) {
            List<Boolean> matches = List.of(
                    FileStatus.isOk(status),
                    FileStatus.isRecordLengthConflict(status),
                    FileStatus.isEndOfFile(status),
                    FileStatus.isNotFound(status),
                    FileStatus.isDuplicate(status));

            assertThat(matches).containsOnlyOnce(Boolean.TRUE);
        }

        @ParameterizedTest(name = "no predicate matches \"{0}\"")
        @MethodSource("unenumeratedStatusValues")
        @DisplayName("no predicate matches an unenumerated status, so none of them is a catch-all")
        void noPredicateMatchesAnUnenumeratedStatus(String status) {
            assertThat(FileStatus.isOk(status)).isFalse();
            assertThat(FileStatus.isRecordLengthConflict(status)).isFalse();
            assertThat(FileStatus.isEndOfFile(status)).isFalse();
            assertThat(FileStatus.isNotFound(status)).isFalse();
            assertThat(FileStatus.isDuplicate(status)).isFalse();
            assertThat(FileStatus.isOkOrNotFound(status)).isFalse();
        }

        /**
         * The unenumerated statuses, reused here so the predicates and the classifier are driven
         * with the same inputs.
         *
         * @return one argument per unenumerated status
         */
        private static Stream<Arguments> unenumeratedStatusValues() {
            return UNENUMERATED_STATUSES.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("APPL-RESULT condition names, in both states")
    class ApplResultConditions {

        @Test
        @DisplayName("88 APPL-AOK VALUE 0")
        void applAokIsZero() {
            // 01 APPL-RESULT PIC S9(9) COMP. / 88 APPL-AOK VALUE 0.
            // [app/cbl/CBACT01C.cbl:61-62; identical at app/cbl/CBTRN02C.cbl:142-143]
            assertThat(FileStatus.APPL_AOK).isZero();
        }

        @Test
        @DisplayName("88 APPL-EOF VALUE 16")
        void applEofIsSixteen() {
            // 88 APPL-EOF VALUE 16. [app/cbl/CBACT01C.cbl:63]
            assertThat(FileStatus.APPL_EOF).isEqualTo(16);
        }

        @Test
        @DisplayName("the two condition values are distinct, so a result cannot satisfy both")
        void theTwoConditionValuesAreDistinct() {
            assertThat(FileStatus.APPL_AOK).isNotEqualTo(FileStatus.APPL_EOF);
        }

        @ParameterizedTest(name = "APPL-RESULT {0}: APPL-AOK={1}, APPL-EOF={2}")
        @CsvSource({
            // Both condition names driven true and driven false.
            "0,  true,  false",
            "16, false, true",
            // 8 and 12 are the other two values the estate actually stores, and neither satisfies
            // either condition name - which is what makes the guard chain's else arm reachable.
            // MOVE 8 TO APPL-RESULT [app/cbl/CBACT01C.cbl:134] precedes every OPEN;
            // MOVE 12 TO APPL-RESULT [app/cbl/CBACT01C.cbl:101] is the general failure value.
            "8,  false, false",
            "12, false, false",
            "4,  false, false",
        })
        @DisplayName("each condition name is driven true and false, and 8, 12 and 4 satisfy neither")
        void bothConditionNamesAreDrivenBothWays(int applResult, boolean aok, boolean eof) {
            assertThat(applResult == FileStatus.APPL_AOK).isEqualTo(aok);
            assertThat(applResult == FileStatus.APPL_EOF).isEqualTo(eof);
        }

        @ParameterizedTest(name = "status \"{0}\" derives APPL-RESULT {1}")
        @CsvSource({
            // The linkage from status to result value, verbatim from the READ guard
            // [app/cbl/CBACT01C.cbl:94-102]: '00' moves 0, '10' moves 16, else moves 12.
            "00, 0",
            "10, 16",
            "23, 12",
            "22, 12",
            "99, 12",
        })
        @DisplayName("'10' is what links the file status to APPL-EOF, and '00' to APPL-AOK")
        void statusDerivesTheApplResultValue(String status, int expectedApplResult) {
            assertThat(applResultOf(status)).isEqualTo(expectedApplResult);
        }

        @Test
        @DisplayName("APPL-AOK shares the numeric value of DFHRESP(NORMAL) yet is a different notion")
        void applAokSharesNormalsValueButNotItsMeaning() {
            // Both are 0. They are not interchangeable: APPL_AOK is the condition a batch program
            // derived from a status it already tested, while NORMAL is what CICS itself reported.
            // The coincidence is recorded so that nobody "simplifies" one into the other.
            assertThat(FileStatus.APPL_AOK).isEqualTo(FileStatus.NORMAL);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL)).isSameAs(Outcome.OK);
        }

        @Test
        @DisplayName("APPL-EOF shares the numeric value of DFHRESP(INVREQ) yet classifies apart")
        void applEofSharesInvreqsValueButNotItsMeaning() {
            // Both are 16, and they mean opposite things: end of input versus an invalid request.
            // The classifier keeps them apart, which is the assertion that matters.
            assertThat(FileStatus.APPL_EOF).isEqualTo(FileStatus.INVREQ);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.INVREQ)).isSameAs(Outcome.OTHER);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.END_OF_FILE))
                    .isSameAs(Outcome.END_OF_FILE);
        }

        /**
         * The status-to-{@code APPL-RESULT} derivation from {@code app/cbl/CBACT01C.cbl:94-102},
         * expressed over the shared predicates.
         *
         * @param status the two-character file status the file system reported
         * @return the value the COBOL moves into {@code APPL-RESULT}
         */
        private int applResultOf(String status) {
            if (FileStatus.isOk(status)) {
                return FileStatus.APPL_AOK;
            }
            if (FileStatus.isEndOfFile(status)) {
                return FileStatus.APPL_EOF;
            }
            // MOVE 12 TO APPL-RESULT [app/cbl/CBACT01C.cbl:101] - the general failure value, which
            // is neither condition name and therefore reaches the abend arm.
            return 12;
        }
    }

    @Nested
    @DisplayName("CICS RESP constants - the online half of the vocabulary")
    class CicsResponseConstants {

        /**
         * All eight responses the class publishes, in declaration order.
         *
         * <p>The online half matters as much as the batch half: {@code DFHRESP} is referenced
         * <strong>84 times</strong> across {@code app/cbl} once comment lines are stripped -
         * {@code NORMAL} at 43 sites, {@code NOTFND} at 23, {@code ENDFILE} at 8, {@code DUPREC} at
         * 7 and {@code DUPKEY} at 3, which is exactly 84. Seventeen of the twenty-eight programs
         * report their I/O outcomes this way and not through a two-character status at all.
         */
        private static final List<Integer> ALL_RESPONSES = List.of(
                FileStatus.NORMAL, FileStatus.NOTFND, FileStatus.DUPREC, FileStatus.DUPKEY,
                FileStatus.INVREQ, FileStatus.NOTOPEN, FileStatus.ENDFILE, FileStatus.LENGERR);

        @Test
        @DisplayName("the eight documented DFHRESP values are carried exactly")
        void theEightResponseValuesAreCarriedExactly() {
            // Values from IBM CICS Transaction Server documentation ("RESP and RESP2 options" and
            // the EIBRESP condition table). No DFHRESP copybook exists in this repository - the
            // same gap as the absent DFHAID, DFHBMSCA and DFHATTR copybooks - so the documentation
            // is the source, and that provenance is recorded rather than left implicit.
            assertThat(FileStatus.NORMAL).isEqualTo(0);
            assertThat(FileStatus.NOTFND).isEqualTo(13);
            assertThat(FileStatus.DUPREC).isEqualTo(14);
            assertThat(FileStatus.DUPKEY).isEqualTo(15);
            assertThat(FileStatus.INVREQ).isEqualTo(16);
            assertThat(FileStatus.NOTOPEN).isEqualTo(19);
            assertThat(FileStatus.ENDFILE).isEqualTo(20);
            assertThat(FileStatus.LENGERR).isEqualTo(22);
        }

        @Test
        @DisplayName("all eight responses are pairwise distinct - asserted by set size, not by eye")
        void allEightResponsesArePairwiseDistinct() {
            // Collected into a set and sized, rather than compared by inspection: a duplicated
            // value would make one condition unreachable through the classifier, and a
            // hand-written pairwise comparison is exactly the kind of check that rots silently.
            Set<Integer> distinct = new LinkedHashSet<>(ALL_RESPONSES);

            assertThat(ALL_RESPONSES).hasSize(8);
            assertThat(distinct).hasSize(8);
        }

        @Test
        @DisplayName("LENGERR's numeric 22 collides with DUPLICATE's text \"22\" and must not be confused")
        void lengerrValueCollidesWithDuplicateTextOnly() {
            // Two independent numbering schemes happen to meet at 22. The collision is meaningless,
            // and the types keep it harmless: one is an int response, the other a two-character
            // status. They classify to different outcomes, which is the assertion that protects it.
            assertThat(String.valueOf(FileStatus.LENGERR)).isEqualTo(FileStatus.DUPLICATE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.LENGERR)).isSameAs(Outcome.OTHER);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.DUPLICATE)).isSameAs(Outcome.DUPLICATE);
        }

        @ParameterizedTest(name = "response {0} classifies without throwing")
        @MethodSource("everyResponse")
        @DisplayName("every published response classifies to a non-null outcome")
        void everyResponseClassifiesToANonNullOutcome(int cicsResp) {
            assertThat(FileStatus.outcomeOfCicsResp(cicsResp)).isNotNull();
            assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).isNotNull();
        }

        /**
         * Every published CICS response, so each one is driven through both conversions.
         *
         * @return one argument per response value
         */
        private static Stream<Arguments> everyResponse() {
            return ALL_RESPONSES.stream().map(Arguments::of);
        }

        @Test
        @DisplayName("RESP_NOT_REPORTED is -1, and so cannot collide with any DFHRESP value")
        void respNotReportedCannotCollideWithAnyResponse() {
            // Every DFHRESP value is non-negative, so a negative sentinel is the one choice that no
            // EVALUATE arm can name. That is what sends it to WHEN OTHER exactly as an unrecognised
            // response would, and what stops it from being mistaken for NORMAL - which is zero, and is
            // also the VALUE ZEROS state every WS-RESP-CD starts in.
            assertThat(FileStatus.RESP_NOT_REPORTED).isEqualTo(-1).isNegative();
            assertThat(ALL_RESPONSES).allSatisfy(response -> assertThat(response).isNotNegative());
            assertThat(ALL_RESPONSES).doesNotContain(FileStatus.RESP_NOT_REPORTED);
            assertThat(FileStatus.RESP_NOT_REPORTED).isNotEqualTo(FileStatus.NORMAL);
        }

        @ParameterizedTest(name = "respReported({0}) is true")
        @MethodSource("everyResponse")
        @DisplayName("respReported is true for every real response, including NORMAL and NOTFND")
        void respReportedIsTrueForEveryRealResponse(int cicsResp) {
            // Not a success test. NORMAL and NOTFND both answer true here: the only question asked is
            // whether there is a response code to show.
            assertThat(FileStatus.respReported(cicsResp)).isTrue();
        }

        @Test
        @DisplayName("respReported is false only for the sentinel")
        void respReportedIsFalseOnlyForTheSentinel() {
            assertThat(FileStatus.respReported(FileStatus.RESP_NOT_REPORTED)).isFalse();
            assertThat(FileStatus.respReported(FileStatus.NORMAL)).isTrue();
            assertThat(FileStatus.respReported(0)).isTrue();
            assertThat(FileStatus.respReported(-2))
                    .as("another negative is not the sentinel; only -1 is")
                    .isTrue();
            assertThat(FileStatus.respReported(Integer.MAX_VALUE)).isTrue();
            assertThat(FileStatus.respReported(Integer.MIN_VALUE)).isTrue();
        }

        @ParameterizedTest(name = "respNotReportedImage({0}) is {0} asterisks")
        @ValueSource(ints = {1, 4, 9, 18})
        @DisplayName("the image is exactly as wide as asked, and holds no digit")
        void theImageIsWidthPreservingAndNotNumeric(int digits) {
            String image = FileStatus.respNotReportedImage(digits);

            assertThat(image).hasSize(digits);
            assertThat(image.chars()).allMatch(character -> character == '*');
            assertThat(image.chars().anyMatch(Character::isDigit))
                    .as("no reader can mistake the image for a value")
                    .isFalse();
        }

        @Test
        @DisplayName("the nine-digit image lines up with a rendered PIC S9(09) operand")
        void theNineDigitImageMatchesADisplayedOperandsWidth() {
            // The whole reason the image is width-preserving: DISPLAY concatenates its operands at their
            // declared widths, so a substitute of any other length would shift every character after it
            // and change a line the parity contract covers.
            assertThat(FileStatus.respNotReportedImage(9))
                    .isEqualTo("*********")
                    .hasSameSizeAs("000000000");
        }

        @ParameterizedTest(name = "respNotReportedImage({0}) is refused")
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("a width below one digit is refused, because no such field exists")
        void aWidthBelowOneDigitIsRefused(int digits) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FileStatus.respNotReportedImage(digits))
                    .withMessageContaining("at least one digit position");
        }
    }

    @Nested
    @DisplayName("The collapse onto one vocabulary - the entire reason the class exists")
    class VocabularyCollapse {

        @Test
        @DisplayName("each response classifies to the outcome the documented mapping names")
        void eachResponseClassifiesAsDocumented() {
            // Written against the named constants rather than against literal ints, so that a
            // change to any constant's value is caught here instead of surfacing later as a
            // mysterious mis-classification.
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL)).isSameAs(Outcome.OK);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.ENDFILE))
                    .isSameAs(Outcome.END_OF_FILE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NOTFND)).isSameAs(Outcome.NOT_FOUND);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.DUPREC)).isSameAs(Outcome.DUPLICATE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.DUPKEY)).isSameAs(Outcome.DUPLICATE);
        }

        @ParameterizedTest(name = "response {0} and status \"{1}\" reach the same outcome")
        @CsvSource({
            // 0 = NORMAL, 20 = ENDFILE, 13 = NOTFND, 14 = DUPREC, 15 = DUPKEY.
            "0,  00",
            "20, 10",
            "13, 23",
            "14, 22",
            "15, 22",
        })
        @DisplayName("a batch status and its CICS response are indistinguishable to the caller")
        void batchAndOnlineReachTheSameOutcome(int cicsResp, String batchStatus) {
            // This is the property the whole class is for. A repository translated from an online
            // program and one translated from a batch program must be able to report the same
            // discriminated outcome, so that neither caller's branch structure has to change.
            assertThat(FileStatus.outcomeOfCicsResp(cicsResp))
                    .isSameAs(FileStatus.outcomeOfStatus(batchStatus));
        }

        @ParameterizedTest(name = "response {0} has no batch equivalent and classifies as OTHER")
        @CsvSource({
            // 16 = INVREQ, 19 = NOTOPEN, 22 = LENGERR. None is tested by any program in app/cbl,
            // and none has a two-character batch counterpart, so each must reach the caller's
            // fatal arm rather than being given a fabricated status.
            "16",
            "19",
            "22",
            // Two values outside the published set, including a negative one, to prove the default
            // arm is a genuine default and not a listed case.
            "99",
            "-1",
        })
        @DisplayName("INVREQ, NOTOPEN, LENGERR and anything unknown drive the caller to abend")
        void responsesWithoutABatchEquivalentClassifyAsOther(int cicsResp) {
            assertThat(FileStatus.outcomeOfCicsResp(cicsResp)).isSameAs(Outcome.OTHER);
            assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).isEmpty();
        }

        @Test
        @DisplayName("batchStatusOfCicsResp translates the five mappable responses")
        void batchStatusOfCicsRespTranslatesTheMappableResponses() {
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.NORMAL))
                    .contains(FileStatus.OK);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.ENDFILE))
                    .contains(FileStatus.END_OF_FILE);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.NOTFND))
                    .contains(FileStatus.NOT_FOUND);
            // Both duplicate conditions collapse forward onto the single '22'.
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.DUPREC))
                    .contains(FileStatus.DUPLICATE);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.DUPKEY))
                    .contains(FileStatus.DUPLICATE);
        }

        @Test
        @DisplayName("cicsRespOfBatchStatus translates only the three one-to-one statuses")
        void cicsRespOfBatchStatusTranslatesTheOneToOneStatuses() {
            assertThat(FileStatus.cicsRespOfBatchStatus(FileStatus.OK))
                    .hasValue(FileStatus.NORMAL);
            assertThat(FileStatus.cicsRespOfBatchStatus(FileStatus.END_OF_FILE))
                    .hasValue(FileStatus.ENDFILE);
            assertThat(FileStatus.cicsRespOfBatchStatus(FileStatus.NOT_FOUND))
                    .hasValue(FileStatus.NOTFND);
        }

        @Test
        @DisplayName("'22' has no single CICS response, because DUPREC and DUPKEY both map onto it")
        void duplicateHasNoUnambiguousReverseMapping() {
            // Nominating either condition as canonical would fabricate behaviour the COBOL never
            // had, so the ambiguity is reported instead of resolved. A caller that needs a specific
            // condition must name DUPREC or DUPKEY itself.
            OptionalInt reverse = FileStatus.cicsRespOfBatchStatus(FileStatus.DUPLICATE);

            assertThat(reverse).isEmpty();
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.DUPREC))
                    .isEqualTo(FileStatus.batchStatusOfCicsResp(FileStatus.DUPKEY));
        }

        @ParameterizedTest(name = "unenumerated status \"{0}\" has no CICS response")
        @MethodSource("unenumeratedStatusValues")
        @DisplayName("an unenumerated status has no CICS response, rather than a fabricated one")
        void unenumeratedStatusHasNoCicsResponse(String status) {
            assertThat(FileStatus.cicsRespOfBatchStatus(status)).isEmpty();
        }

        @ParameterizedTest(name = "\"{0}\" round-trips through both conversions")
        @ValueSource(strings = {"00", "10", "23"})
        @DisplayName("the three one-to-one statuses survive a full round trip unchanged")
        void theOneToOneStatusesRoundTrip(String status) {
            OptionalInt response = FileStatus.cicsRespOfBatchStatus(status);

            assertThat(response).isPresent();
            assertThat(FileStatus.batchStatusOfCicsResp(response.getAsInt())).contains(status);
        }

        /**
         * The unenumerated statuses, reused so the conversions are driven with the same inputs as
         * the classifier and the predicates.
         *
         * @return one argument per unenumerated status
         */
        private static Stream<Arguments> unenumeratedStatusValues() {
            return UNENUMERATED_STATUSES.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("toStatusImage, numeric branch - the ELSE arm of 9910-DISPLAY-IO-STATUS")
    class StatusImageNumericBranch {

        @ParameterizedTest(name = "\"{0}\" renders as \"{1}\"")
        @CsvSource({
            // MOVE '0000' TO IO-STATUS-04 / MOVE IO-STATUS TO IO-STATUS-04(3:2)
            // [app/cbl/CBACT01C.cbl:185-186]. Positions 1 and 2 keep their zeros because the
            // overlay starts at one-based position 3, so the image is "00" followed by the status.
            // Re-derived by hand for each of the four recognised statuses.
            "00, 0000",
            "10, 0010",
            "22, 0022",
            "23, 0023",
            // Two further numeric statuses whose first character is not '9', confirming the arm is
            // about the *shape* of the status and not about the four recognised values.
            "37, 0037",
            "89, 0089",
        })
        @DisplayName("a numeric status not starting with '9' renders as \"00\" plus the status")
        void numericStatusRendersWithTwoLeadingZeros(String status, String expectedImage) {
            assertThat(FileStatus.toStatusImage(status)).isEqualTo(expectedImage);
        }

        @ParameterizedTest(name = "\"{0}\" renders exactly four characters starting \"00\"")
        @ValueSource(strings = {"00", "10", "22", "23", "37", "89"})
        @DisplayName("the numeric branch always emits four characters, the first two of them zeros")
        void numericBranchAlwaysEmitsFourCharacters(String status) {
            String image = FileStatus.toStatusImage(status);

            // Four characters, from 05 IO-STATUS-0401 PIC 9 plus 05 IO-STATUS-0403 PIC 999
            // [app/cbl/CBACT01C.cbl:58-59]. The width is asserted separately from the value so a
            // padding regression is reported as a width failure rather than as a value mismatch.
            assertThat(image).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(image).startsWith("00").endsWith(status);
            assertThat(image).isEqualTo("00" + status);
        }

        @ParameterizedTest(name = "both overloads agree for \"{0}\"")
        @ValueSource(strings = {"00", "10", "22", "23"})
        @DisplayName("the String overload and the char-pair overload agree")
        void bothOverloadsAgree(String status) {
            assertThat(FileStatus.toStatusImage(status))
                    .isEqualTo(FileStatus.toStatusImage(status.charAt(0), status.charAt(1)));
        }
    }

    @Nested
    @DisplayName("toStatusImage, extended branch - both operands of the IF condition")
    class StatusImageExtendedBranch {

        @ParameterizedTest(name = "operand (i): {0} renders as \"{3}\"")
        @MethodSource("nonNumericCases")
        @DisplayName("operand (i) - a non-numeric status takes the extended branch")
        void nonNumericStatusTakesTheExtendedBranch(String label, char stat1, char stat2,
                String expectedImage) {
            // The label carries the byte pair in a printable form. The raw bytes are deliberately
            // kept out of the parameterised display name: some of them are control characters, and
            // a control character in a test name ends up in the surefire XML report.
            assertThat(label).isNotBlank();
            // IF IO-STATUS NOT NUMERIC ... [app/cbl/CBACT01C.cbl:177]. IO-STATUS is a group of two
            // PIC X items and therefore alphanumeric, so the class condition holds only when both
            // bytes are single-byte digits. The image is IO-STAT1 verbatim, then the unsigned value
            // of IO-STAT2 rendered as three zero-padded digits.
            String image = FileStatus.toStatusImage(stat1, stat2);

            assertThat(image).isEqualTo(expectedImage);
            assertThat(image).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(image.charAt(0)).isEqualTo(stat1);
            // Not the numeric-branch answer: proof the ELSE arm was not taken.
            assertThat(image).isNotEqualTo("00" + stat1 + stat2);
        }

        @ParameterizedTest(name = "operand (ii): numeric \"{0}\" renders as \"{1}\", not \"00{0}\"")
        @CsvSource({
            // ---------------------------------------------------------------------------------
            // THE OPERAND THAT IS EASY TO MISS.
            //
            // Every status here IS numeric, so the first operand of
            //     IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
            // is false. These cases reach the extended branch SOLELY through the second operand,
            // IO-STAT1 = '9' [app/cbl/CBACT01C.cbl:178]. They are the only way that operand is
            // ever exercised, so omitting them leaves the condition half-covered - with no obvious
            // gap in the coverage report - and drops the class below the mandated branch bar.
            //
            // Expected values re-derived by hand from the US-ASCII code points of the second byte:
            //   '0' = 48 -> "048"    '1' = 49 -> "049"    '3' = 51 -> "051"
            //   '7' = 55 -> "055"    '9' = 57 -> "057"
            // ---------------------------------------------------------------------------------
            "90, 9048",
            "91, 9049",
            "93, 9051",
            "97, 9055",
            "99, 9057",
        })
        @DisplayName("operand (ii) - a numeric status starting '9' still takes the extended branch")
        void numericNineStatusTakesTheExtendedBranchThroughTheSecondOperand(
                String status, String expectedImage) {
            // Stated as an assertion rather than left to the reader: these really are numeric, so
            // the first operand cannot be what routed them.
            assertThat(status).matches("[0-9]{2}");

            String image = FileStatus.toStatusImage(status);

            assertThat(image).isEqualTo(expectedImage);
            assertThat(image).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(image).startsWith("9");
            // The numeric branch would have produced "00" + status. Asserting its absence is what
            // proves the second operand fired.
            assertThat(image).isNotEqualTo("00" + status);
        }

        @ParameterizedTest(name = "raw byte {0} after '9' renders as \"{3}\"")
        @MethodSource("rawSecondByteCases")
        @DisplayName("the second byte is read as an unsigned value in 0..255, never as a signed one")
        void theSecondByteIsReadAsAnUnsignedValue(String label, char stat2, int expectedValue,
                String expectedImage) {
            assertThat(label).isNotBlank();
            // MOVE 0 TO TWO-BYTES-BINARY / MOVE IO-STAT2 TO TWO-BYTES-RIGHT / MOVE
            // TWO-BYTES-BINARY TO IO-STATUS-0403 [app/cbl/CBACT01C.cbl:180-182]. Zeroing the
            // PIC 9(4) BINARY and then overwriting only its low-order byte through the
            // TWO-BYTES-ALPHA REDEFINES leaves the field holding that byte's unsigned value, which
            // a PIC 999 receiver then renders as three zero-padded digits. 0xFF must read as 255
            // and never as -1, and the maximum of 255 is why three digits never truncate.
            String image = FileStatus.toStatusImage('9', stat2);

            assertThat(image).isEqualTo(expectedImage);
            assertThat(image).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(Integer.parseInt(image.substring(1))).isEqualTo(expectedValue);
        }

        @Test
        @DisplayName("only the low-order eight bits matter, matching a single-byte PIC X")
        void onlyTheLowOrderEightBitsOfTheSecondByteMatter() {
            // A Java char is sixteen bits wide; a COBOL PIC X is one byte. The high-order bits are
            // therefore masked away rather than carried, so 0x141 contributes only its 0x41 = 65.
            assertThat(FileStatus.toStatusImage('9', (char) 0x0141)).isEqualTo("9065");
            assertThat(FileStatus.toStatusImage('9', (char) 0x0041)).isEqualTo("9065");
        }

        @Test
        @DisplayName("the char-pair overload accepts any byte pair without a width guard")
        void theCharPairOverloadNeedsNoGuard() {
            // Two chars are always exactly two bytes, so there is nothing to validate - unlike the
            // String overload, which can be handed the wrong width.
            assertThatCode(() -> FileStatus.toStatusImage((char) 0x00, (char) 0xFF))
                    .doesNotThrowAnyException();
            assertThat(FileStatus.toStatusImage((char) 0x00, (char) 0xFF))
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }

        /**
         * Status byte pairs that fail the {@code NUMERIC} class condition, and therefore reach the
         * extended branch through its first operand.
         *
         * <p>The set is chosen to drive every side of the digit test itself, which is written as
         * {@code character >= '0' && character <= '9'}: a byte above {@code '9'}, a byte below
         * {@code '0'}, and a digit, in each of the two positions.
         *
         * @return {@code (label, stat1, stat2, expectedImage)} tuples
         */
        private static Stream<Arguments> nonNumericCases() {
            return Stream.of(
                    // 'A' is above '9', so the digit test's upper bound fails on the first byte and
                    // the AND short-circuits before the second byte is examined at all.
                    // 'B' = 66 -> "066".
                    Arguments.of("'A' then 'B'", 'A', 'B', "A066"),
                    // The first byte IS a digit, so the AND's right operand is evaluated and fails.
                    // 'A' = 65 -> "065".
                    Arguments.of("'0' then 'A'", '0', 'A', "0065"),
                    // A space, 0x20, is BELOW '0', so the digit test's lower bound fails - the other
                    // half of the digit test, in the second position. Space = 32 -> "032".
                    Arguments.of("'1' then space", '1', ' ', "1032"),
                    // The same lower-bound failure in the first position. '5' = 53 -> "053".
                    Arguments.of("space then '5'", ' ', '5', " 053"),
                    // Neither byte is a digit. 'Y' = 89 -> "089".
                    Arguments.of("'X' then 'Y'", 'X', 'Y', "X089"),
                    // First byte '9' AND a non-numeric second byte: both operands of the IF are
                    // true at once, which must still render exactly one image.
                    // 'Z' = 90 -> "090".
                    Arguments.of("'9' then 'Z'", '9', 'Z', "9090"),
                    // Both bytes below '0'. NUL = 0 -> "000".
                    Arguments.of("0x00 then 0x00", (char) 0x00, (char) 0x00, "\u0000" + "000"));
        }

        /**
         * Raw second bytes after a {@code '9'} first byte, covering the boundaries of the
         * three-digit unsigned rendering.
         *
         * @return {@code (label, stat2, expectedUnsignedValue, expectedImage)} tuples
         */
        private static Stream<Arguments> rawSecondByteCases() {
            return Stream.of(
                    // The bottom of the range: one digit rendered as three.
                    Arguments.of("0x00", (char) 0x00, 0, "9000"),
                    // A line feed, the value that makes the three-digit padding visible.
                    Arguments.of("0x0A", (char) 0x0A, 10, "9010"),
                    // The last seven-bit value.
                    Arguments.of("0x7F", (char) 0x7F, 127, "9127"),
                    // 0x80 is where a signed byte would turn negative; it must read as 128.
                    Arguments.of("0x80", (char) 0x80, 128, "9128"),
                    // The top of the range: 255, emphatically not -1, and still three digits.
                    Arguments.of("0xFF", (char) 0xFF, 255, "9255"));
        }
    }

    @Nested
    @DisplayName("toDisplayLine - the literal NNNN survives, byte for byte")
    class DisplayLine {

        @Test
        @DisplayName("the prefix is the COBOL literal exactly, ending in four literal N characters")
        void thePrefixIsTheCobolLiteralExactly() {
            // DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04 [app/cbl/CBACT01C.cbl:183 and :187].
            // Compared against a literal transcribed independently at the top of this class, so the
            // assertion checks the implementation against the COBOL and not against itself.
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo(EXPECTED_DISPLAY_PREFIX);
            assertThat(FileStatus.DISPLAY_PREFIX).hasSize(EXPECTED_PREFIX_LENGTH);
            assertThat(FileStatus.DISPLAY_PREFIX).endsWith("NNNN");
        }

        @Test
        @DisplayName("there is exactly one space after the colon, and no run of two spaces anywhere")
        void thereIsExactlyOneSpaceAfterTheColon() {
            // An accidental extra space is invisible on a terminal and fatal to a byte comparison,
            // so the spacing is asserted directly rather than left to the equality check above.
            assertThat(FileStatus.DISPLAY_PREFIX).contains("IS: N");
            assertThat(FileStatus.DISPLAY_PREFIX).doesNotContain("  ");
            // Exactly three: after FILE, after STATUS, and after the colon. Re-derived by hand from
            // 'FILE STATUS IS: NNNN' - 4 + 1 + 6 + 1 + 3 + 1 + 4 = the 20 characters asserted above.
            assertThat(FileStatus.DISPLAY_PREFIX.chars().filter(c -> c == ' ').count()).isEqualTo(3);
        }

        @ParameterizedTest(name = "toDisplayLine(\"{0}\") is \"{1}\"")
        @CsvSource({
            // The counter-intuitive part, asserted for every recognised status: the literal NNNN is
            // STILL THERE, and the image follows it. COBOL emits the literal and then the field; it
            // does not substitute anything into the literal. An implementation that "helpfully"
            // replaced NNNN with the digits would produce different bytes.
            "00, 'FILE STATUS IS: NNNN0000'",
            "10, 'FILE STATUS IS: NNNN0010'",
            "22, 'FILE STATUS IS: NNNN0022'",
            "23, 'FILE STATUS IS: NNNN0023'",
        })
        @DisplayName("the composed line keeps NNNN and appends the image, byte for byte")
        void theComposedLineKeepsTheLiteralAndAppendsTheImage(String status, String expectedLine) {
            String line = FileStatus.toDisplayLine(status);

            assertThat(line).isEqualTo(expectedLine);
            // Total length asserted so an extra space, a separator or a trailing newline fails.
            assertThat(line).hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
            assertThat(line).isEqualTo(FileStatus.DISPLAY_PREFIX + FileStatus.toStatusImage(status));
        }

        @ParameterizedTest(name = "toDisplayLine(\"{0}\") still contains the literal NNNN")
        @ValueSource(strings = {"00", "10", "22", "23", "37"})
        @DisplayName("NNNN is never substituted away, and the digits never appear in its place")
        void theLiteralIsNeverSubstituted(String status) {
            String line = FileStatus.toDisplayLine(status);

            assertThat(line).contains("NNNN").startsWith(EXPECTED_DISPLAY_PREFIX);
            // The shape a substituting implementation would produce, asserted absent.
            assertThat(line).isNotEqualTo("FILE STATUS IS: " + FileStatus.toStatusImage(status));
        }

        @Test
        @DisplayName("the line is byte-identical in US-ASCII, not merely equal as a String")
        void theLineIsByteIdenticalInAscii() {
            // The parity contract is about bytes on a report line, so at least one case is compared
            // as bytes. US-ASCII is the charset of the authoritative ASCII fixtures; the EBCDIC
            // datasets are reference-only and their charset is named separately at the codec layer.
            byte[] actual = FileStatus.toDisplayLine("10").getBytes(StandardCharsets.US_ASCII);
            byte[] expected = "FILE STATUS IS: NNNN0010".getBytes(StandardCharsets.US_ASCII);

            assertThat(actual).containsExactly(expected);
            assertThat(actual).hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
        }

        @Test
        @DisplayName("the extended branch composes the same way, prefix then four-character image")
        void theExtendedBranchComposesTheSameWay() {
            // '9' then a line feed: the extended branch reached through the second operand's
            // sibling case, composed into a full line. 0x0A = 10 -> "010".
            assertThat(FileStatus.toDisplayLine('9', (char) 0x0A))
                    .isEqualTo("FILE STATUS IS: NNNN9010")
                    .hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
            // 'A' then 'B': the extended branch reached through the first operand. 'B' = 66.
            assertThat(FileStatus.toDisplayLine('A', 'B'))
                    .isEqualTo("FILE STATUS IS: NNNNA066")
                    .hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
        }

        @ParameterizedTest(name = "both toDisplayLine overloads agree for \"{0}\"")
        @ValueSource(strings = {"00", "10", "22", "23", "90", "9A"})
        @DisplayName("the String overload and the char-pair overload compose identically")
        void bothDisplayLineOverloadsAgree(String status) {
            assertThat(FileStatus.toDisplayLine(status))
                    .isEqualTo(FileStatus.toDisplayLine(status.charAt(0), status.charAt(1)));
        }

        @ParameterizedTest(name = "every line for \"{0}\" is 24 characters")
        @ValueSource(strings = {"00", "10", "22", "23", "37", "90", "99", "9A", "AB"})
        @DisplayName("every line is 20 prefix characters plus a 4-character image, whichever branch")
        void everyLineIsTwentyPlusFourCharacters(String status) {
            assertThat(FileStatus.toStatusImage(status)).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(FileStatus.toDisplayLine(status))
                    .hasSize(EXPECTED_PREFIX_LENGTH + FileStatus.STATUS_IMAGE_LENGTH)
                    .hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
        }
    }

    @Nested
    @DisplayName("Guards - a FILE STATUS is two bytes by declaration, so anything else is a defect")
    class Guards {

        @ParameterizedTest(name = "{0} rejects null")
        @MethodSource("statusAcceptingMethods")
        @DisplayName("every method that takes a status rejects null with a diagnostic message")
        void everyStatusAcceptingMethodRejectsNull(String methodName, Consumer<String> invocation) {
            assertThatNullPointerException()
                    .as(methodName)
                    .isThrownBy(() -> invocation.accept(null))
                    .withMessageContaining("two-byte");
        }

        @ParameterizedTest(name = "{0} rejects a status of the wrong width")
        @MethodSource("statusAcceptingMethods")
        @DisplayName("every method that takes a status rejects a value that is not two characters")
        void everyStatusAcceptingMethodRejectsTheWrongWidth(String methodName,
                Consumer<String> invocation) {
            // Too short and too long, from the same method, so the guard is proven to be a width
            // check rather than a minimum or a maximum.
            assertThatIllegalArgumentException()
                    .as(methodName + " with one character")
                    .isThrownBy(() -> invocation.accept("0"));
            assertThatIllegalArgumentException()
                    .as(methodName + " with three characters")
                    .isThrownBy(() -> invocation.accept("000"));
        }

        @ParameterizedTest(name = "{0} accepts a well-formed two-character status")
        @MethodSource("statusAcceptingMethods")
        @DisplayName("every method that takes a status accepts a well-formed one")
        void everyStatusAcceptingMethodAcceptsAWellFormedStatus(String methodName,
                Consumer<String> invocation) {
            // The other side of both guards: with a correct width, nothing is thrown. Without this,
            // a method that rejected everything would pass the two tests above.
            assertThatCode(() -> invocation.accept(FileStatus.OK))
                    .as(methodName)
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "\"{0}\" is rejected because it is not two characters")
        @ValueSource(strings = {"", "0", "000", "0000", "00 ", " 00"})
        @DisplayName("the width guard is exact: empty, one, three, four and padded values all fail")
        void theWidthGuardIsExact(String malformed) {
            // " 00" and "00 " are three characters. They matter because a caller that trimmed or
            // padded a status would produce exactly these, and silently accepting them would let a
            // mis-widthed status reach the renderer and shift every character of the image.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FileStatus.toStatusImage(malformed));
        }

        @Test
        @DisplayName("the width message names the offending length and the offending value")
        void theWidthMessageNamesTheLengthAndTheValue() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FileStatus.outcomeOfStatus("000"))
                    .withMessageContaining("exactly 2")
                    .withMessageContaining("was 3")
                    .withMessageContaining("000");
        }

        @Test
        @DisplayName("the null message points at the two-byte COBOL declaration")
        void theNullMessagePointsAtTheDeclaration() {
            assertThatNullPointerException()
                    .isThrownBy(() -> FileStatus.isOk(null))
                    .withMessageContaining("IO-STAT1")
                    .withMessageContaining("IO-STAT2");
        }

        /**
         * Every public method that accepts a two-character status, paired with a printable name.
         *
         * <p>Driving all nine through one parameterised test is what proves the two guards live in
         * exactly one place: a method that validated its argument itself, slightly differently,
         * would show up here as a single failing row rather than being missed entirely. The method
         * references are adapted to {@link Consumer} and their return values discarded, because
         * these three tests are about the guard and not about the result.
         *
         * @return {@code (methodName, invocation)} pairs
         */
        private static Stream<Arguments> statusAcceptingMethods() {
            return Stream.of(
                    Arguments.of("toStatusImage", (Consumer<String>) FileStatus::toStatusImage),
                    Arguments.of("toDisplayLine", (Consumer<String>) FileStatus::toDisplayLine),
                    Arguments.of("isOk", (Consumer<String>) FileStatus::isOk),
                    Arguments.of("isEndOfFile", (Consumer<String>) FileStatus::isEndOfFile),
                    Arguments.of("isNotFound", (Consumer<String>) FileStatus::isNotFound),
                    Arguments.of("isDuplicate", (Consumer<String>) FileStatus::isDuplicate),
                    Arguments.of("isOkOrNotFound", (Consumer<String>) FileStatus::isOkOrNotFound),
                    Arguments.of("outcomeOfStatus", (Consumer<String>) FileStatus::outcomeOfStatus),
                    Arguments.of("cicsRespOfBatchStatus",
                            (Consumer<String>) FileStatus::cicsRespOfBatchStatus));
        }
    }

    @Nested
    @DisplayName("Class shape - immutable, stateless, non-instantiable and a graph root")
    class ClassShapeAndImmutability {

        /**
         * The eighteen constants the class publishes.
         *
         * <p>Listed by name rather than merely counted, so that an accidentally added public field -
         * the usual way mutable static state gets in - fails with the offending name in the report.
         */
        private static final List<String> PUBLISHED_CONSTANT_NAMES = List.of(
                "OK", "RECORD_LENGTH_CONFLICT", "END_OF_FILE", "DUPLICATE", "NOT_FOUND",
                "OPEN_MODE_CONFLICT", "STATUS_LENGTH",
                "STATUS_IMAGE_LENGTH", "NORMAL", "NOTFND", "DUPREC", "DUPKEY", "INVREQ", "NOTOPEN",
                "ENDFILE", "LENGERR", "NO_REASON_CODE", "RESP_NOT_REPORTED", "APPL_AOK", "APPL_EOF",
                "DISPLAY_PREFIX");

        @Test
        @DisplayName("the class is final, so no subclass can add state to it")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(FileStatus.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the sole constructor is private, and its body holds no state")
        void theSoleConstructorIsPrivate() throws ReflectiveOperationException {
            assertThat(FileStatus.class.getDeclaredConstructors()).hasSize(1);

            Constructor<FileStatus> constructor = FileStatus.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();

            // The constructor body is intentionally empty, so reflective instantiation succeeds and
            // yields an object that carries nothing. That is asserted rather than assumed: a reader
            // who sees a private constructor often expects it to throw, and this records that this
            // one does not - the class is a constant holder, not a guarded singleton.
            constructor.setAccessible(true);
            assertThat(constructor.newInstance()).isNotNull();
        }

        @Test
        @DisplayName("every published constant is public static final, verified reflectively")
        void everyPublishedConstantIsPublicStaticFinal() {
            List<Field> publicFields = Arrays.stream(FileStatus.class.getFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(publicFields)
                    .extracting(Field::getName)
                    .containsExactlyInAnyOrderElementsOf(PUBLISHED_CONSTANT_NAMES);
            assertThat(publicFields).allSatisfy(field -> {
                assertThat(Modifier.isPublic(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(Modifier.isStatic(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
            });
        }

        @Test
        @DisplayName("there is no mutable state at all: every field is static final and none is an array")
        void thereIsNoMutableState() {
            // Synthetic fields are filtered out because the coverage agent adds one - a private
            // static transient boolean[] - to every instrumented class at run time. This assertion
            // is about the source, not about the instrumentation.
            List<Field> declaredFields = Arrays.stream(FileStatus.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(declaredFields).isNotEmpty();
            assertThat(declaredFields).allSatisfy(field -> {
                assertThat(Modifier.isStatic(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
                // An array reference can be final while its contents are freely mutable, which is
                // the classic way static mutable state hides behind a `static final`. There is no
                // array constant here, and this keeps it that way.
                assertThat(field.getType().isArray()).as(field.getName()).isFalse();
            });
        }

        @Test
        @DisplayName("the Outcome enum is immutable too: every declared field is final")
        void theOutcomeEnumIsImmutable() {
            // The enum's own constants are declared fields as well, so this covers the five
            // constants and the private batchStatus field in one pass. The synthetic $VALUES array
            // that javac generates is filtered out for the same reason as above.
            List<Field> declaredFields = Arrays.stream(Outcome.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(declaredFields).isNotEmpty();
            assertThat(declaredFields).allSatisfy(field ->
                    assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue());
        }

        @Test
        @DisplayName("the published API mentions no other application type, so the graph stays acyclic")
        void thePublishedApiMentionsNoOtherApplicationType() {
            // FileStatus classifies and renders; the sibling abend type terminates. Keeping the two
            // apart is what makes this class a root of the dependency graph and lets each half be
            // unit-tested alone - which is why neither this test nor any other in this file names
            // the abend type. Confirmed by inspection as well: FileStatus imports exactly two
            // types, java.util.Optional and java.util.OptionalInt, and nothing from this codebase.
            //
            // Asserted structurally rather than trusted to that comment: the only application type
            // reachable through any declared field, return type or parameter type is the nested
            // Outcome enum.
            Field[] declaredFields = FileStatus.class.getDeclaredFields();
            Method[] declaredMethods = FileStatus.class.getDeclaredMethods();

            List<String> applicationTypes = Stream.concat(
                            Arrays.stream(declaredFields)
                                    .filter(field -> !field.isSynthetic())
                                    .map(Field::getType),
                            Arrays.stream(declaredMethods)
                                    .filter(method -> !method.isSynthetic())
                                    .flatMap(method -> Stream.concat(
                                            Stream.of(method.getReturnType()),
                                            Arrays.stream(method.getParameterTypes()))))
                    .map(Class::getName)
                    .filter(name -> name.startsWith("com.vsergeychik.carddemo"))
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(applicationTypes)
                    .containsExactly("com.vsergeychik.carddemo.common.FileStatus$Outcome");
        }

        @Test
        @DisplayName("both conversion results are value types, so no caller can be handed null")
        void bothConversionResultsAreValueTypes() {
            // Optional and OptionalInt are the only two types the class imports. Naming them here
            // records that the absence of a mapping is reported as an empty value rather than as a
            // null or a fabricated status.
            Optional<String> absentBatchStatus =
                    FileStatus.batchStatusOfCicsResp(FileStatus.NOTOPEN);
            OptionalInt absentResponse = FileStatus.cicsRespOfBatchStatus(FileStatus.DUPLICATE);

            assertThat(absentBatchStatus).isNotNull().isEmpty();
            assertThat(absentResponse).isNotNull().isEmpty();
        }
    }

    // =============================================================================================
    // F11 - both status characters are narrowed to the one byte a PIC X item holds.
    // =============================================================================================

    /**
     * That {@code toStatusImage} narrows <strong>both</strong> operands to their low-order eight bits
     * before it classifies or renders either of them.
     *
     * <p>{@code IO-STAT1} and {@code IO-STAT2} are each {@code PIC X}, one byte, so a {@code char}
     * above {@code 0xFF} carries more than COBOL storage can hold. Narrowing one operand and not the
     * other let the two halves of the method disagree: the class condition would be evaluated on the
     * wide value while the rendering used it verbatim, so a character whose low byte is a digit could
     * be classified as non-numeric and emitted on the wrong arm. These tests pin the narrowing at
     * both operand positions and on both arms of the {@code EVALUATE}.
     */
    @Nested
    @DisplayName("toStatusImage narrows both PIC X operands - F11")
    class StatusCharacterNarrowing {

        @Test
        @DisplayName("a wide first operand classifies and renders as its low-order byte")
        void aWideFirstOperandIsNarrowed() {
            // U+0130 has low-order byte 0x30, which is the digit '0'. Narrowed, the pair is "00" and
            // takes the numeric arm; unnarrowed, the class condition failed and the wide char itself
            // was emitted at position one.
            assertThat(FileStatus.toStatusImage((char) 0x0130, '0'))
                    .isEqualTo(FileStatus.toStatusImage('0', '0'))
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("a wide second operand classifies and renders as its low-order byte")
        void aWideSecondOperandIsNarrowed() {
            assertThat(FileStatus.toStatusImage('0', (char) 0x0130))
                    .isEqualTo(FileStatus.toStatusImage('0', '0'))
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("a wide operand whose low byte is '9' still selects the '9' arm")
        void aWideOperandWhoseLowByteIsNineSelectsTheNineArm() {
            // U+0139 has low-order byte 0x39, the digit '9', so the IO-STAT1 = '9' half of the
            // condition must fire and the second byte must render as three unsigned decimal digits.
            assertThat(FileStatus.toStatusImage((char) 0x0139, (char) 0x000A))
                    .isEqualTo(FileStatus.toStatusImage('9', (char) 0x000A))
                    .isEqualTo("9010");
        }

        @Test
        @DisplayName("the non-numeric arm renders the narrowed first byte, not the wide char")
        void theNonNumericArmRendersTheNarrowedFirstByte() {
            // U+0141 has low-order byte 0x41, 'A' - non-numeric, so the first arm is taken and the
            // rendered first character must be 'A' rather than U+0141.
            assertThat(FileStatus.toStatusImage((char) 0x0141, '\u0000'))
                    .isEqualTo("A000")
                    .doesNotContain(String.valueOf((char) 0x0141));
        }

        @Test
        @DisplayName("the highest byte value reads as 255, never as -1")
        void theHighestByteValueReadsAsUnsigned() {
            assertThat(FileStatus.toStatusImage('9', (char) 0x00FF)).isEqualTo("9255");
            assertThat(FileStatus.toStatusImage('9', (char) 0x01FF)).isEqualTo("9255");
        }

        @Test
        @DisplayName("toDisplayLine inherits the narrowing through toStatusImage")
        void toDisplayLineInheritsTheNarrowing() {
            assertThat(FileStatus.toDisplayLine((char) 0x0130, '0'))
                    .isEqualTo(FileStatus.toDisplayLine('0', '0'));
        }
    }
}
