package com.vsergeychik.carddemo.parity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.Redaction;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The contract test for {@link ParityCase} - the model every one of the 560 declarative parity cases
 * deserialises into.
 *
 * <h2>Why this suite exists at all</h2>
 * <p>{@code ParityCase} is not a data holder that happens to validate; validation <em>is</em> what it
 * is for. The whole parity gate is stated as "diff count = 0", and a case that loads with an input
 * silently dropped, an expectation parked among the inputs, or a dataset addressed by a literal name
 * produces a diff count of zero for reasons that have nothing to do with the translation being right.
 * Every guard below therefore closes a specific way a green gate can be meaningless, and each test
 * says which one.
 *
 * <p>Three properties get disproportionate attention because they carry disproportionate weight:
 * <ul>
 *   <li><strong>Strict deserialisation.</strong> Every type in the contract is annotated
 *       {@code @JsonIgnoreProperties(ignoreUnknown = false)}, so a misspelled key fails the load
 *       instead of removing an input. That is asserted at every level of the object graph, not just
 *       the top, because the level a typo lands on is not the level anybody chose.</li>
 *   <li><strong>The fixture allow-list.</strong> A case names a file and the model supplies the
 *       directory, so nothing a case can write reaches a resource outside {@code fixtures/}. Path
 *       traversal, absolute paths, drive letters, URI schemes and unknown names are each refused
 *       rather than normalised.</li>
 *   <li><strong>Redaction.</strong> The legacy password span is carried verbatim because
 *       {@code COSGN00C} compares it verbatim, and it must never be rendered. Every {@code toString}
 *       that could reach a log is asserted not to disclose it.</li>
 * </ul>
 *
 * <p>Nothing here reads the wall clock, opens a network connection, writes a file or mutates static
 * state. The {@link ObjectMapper} is built per test method from {@link JsonMapper}, so no two tests
 * can share a configured instance.
 */
@DisplayName("ParityCase - the declarative parity case contract")
class ParityCaseTest {

    /** A program name of the shape every one of the 28 in-scope programs has. */
    private static final String PROGRAM = "COUSR02C";

    /** The USRSEC binding key, which is the dataset every COUSR02C case seeds. */
    private static final String USRSEC = "USRSEC";

    /** A 57-character USRSEC seed row exactly as {@code app/jcl/DUSRSECJ.jcl} carries it. */
    private static final String SEED_ROW_57 =
        "ADMIN001John                Doe                 PASSWORDA";

    /** The same row at the 80-byte width {@code CSUSR01Y} declares. */
    private static final String SEED_ROW_80 = SEED_ROW_57 + " ".repeat(23);

    /** A fresh mapper per call, so no test can be affected by another's configuration. */
    private static ObjectMapper mapper() {
        return JsonMapper.builder().build();
    }

    /** A minimal valid batch case, which every batch test starts from and then perturbs. */
    private static ParityCase batchCase() {
        return new ParityCase(PROGRAM, "case01", "a minimal valid case", UnitKind.BATCH_JOB,
            Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());
    }

    /** A minimal valid controller case, which requires both online members. */
    private static ParityCase controllerCase() {
        return new ParityCase(PROGRAM, "case01", "a minimal valid controller case",
            UnitKind.CONTROLLER_POJO, Map.of(), Map.of(), minimalRequest(), minimalResponse(),
            List.of(), List.of(), 0, List.of(), List.of());
    }

    /** The smallest well-formed screen request. */
    private static ScreenRequest minimalRequest() {
        return new ScreenRequest(0, "DFHENTER", null, "US-ASCII", Map.of(), Map.of(), Map.of());
    }

    /** The smallest well-formed expected response. */
    private static ExpectedResponse minimalResponse() {
        return new ExpectedResponse("COSGN00C", null, null, Map.of(), List.of(), null,
            Termination.XCTL);
    }

    /** Asserts that a callable is rejected with a message mentioning every supplied fragment. */
    private static void rejectedBecause(ThrowingCallable callable, String... fragments) {
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy(callable)
            .withMessageContainingAll(fragments);
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Identity members: program, caseId, description, unitKind")
    class Identity {

        @ParameterizedTest(name = "program \"{0}\" is rejected")
        @DisplayName("a program name that is not eight upper-case alphanumerics is refused")
        @ValueSource(strings = {"cousr02c", "COUSR02", "COUSR02CX", "1OUSR02C", "COUSR-2C",
            "COUSR02c", " OUSR02C", ""})
        void aMalformedProgramNameIsRefused(String program) {
            rejectedBecause(() -> new ParityCase(program, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "ParityCase.program");
        }

        @Test
        @DisplayName("an absent program name is refused, naming the member")
        void anAbsentProgramNameIsRefused() {
            rejectedBecause(() -> new ParityCase(null, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "ParityCase.program", "required");
        }

        @ParameterizedTest(name = "program \"{0}\" is accepted")
        @DisplayName("every shape the 28 in-scope program names actually take is accepted")
        @ValueSource(strings = {"COUSR02C", "CBACT01C", "CSUTLDTC", "CBSTM03A", "COACTUPC"})
        void everyRealProgramNameShapeIsAccepted(String program) {
            ParityCase parityCase = new ParityCase(program, "case01", "d", UnitKind.BATCH_JOB,
                Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThat(parityCase.program()).isEqualTo(program);
        }

        @ParameterizedTest(name = "caseId \"{0}\" is rejected")
        @DisplayName("a case identifier outside case01..case20 is refused, one-digit forms included")
        @ValueSource(strings = {"case00", "case21", "case1", "case021", "CASE01", "case", "case-01",
            "case1a", "01"})
        void aCaseIdOutsideTheTwentyIsRefused(String caseId) {
            rejectedBecause(() -> new ParityCase(PROGRAM, caseId, "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "ParityCase.caseId", "case01");
        }

        @Test
        @DisplayName("all twenty case identifiers are accepted, which is the 20-per-program gate")
        void allTwentyCaseIdentifiersAreAccepted() {
            List<String> accepted = new ArrayList<>();
            for (int number = 1; number <= 20; number++) {
                String caseId = String.format("case%02d", number);
                accepted.add(new ParityCase(PROGRAM, caseId, "d", UnitKind.BATCH_JOB, Map.of(),
                    Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of()).caseId());
            }

            Assertions.assertThat(accepted)
                .as("gate G15 requires exactly twenty cases per program")
                .hasSize(20)
                .doesNotHaveDuplicates()
                .startsWith("case01")
                .endsWith("case20");
        }

        @ParameterizedTest(name = "description [{0}] is rejected")
        @DisplayName("a blank description is refused: a case nobody can read is a case nobody reviews")
        @ValueSource(strings = {"", " ", "\t", "\n"})
        void aBlankDescriptionIsRefused(String description) {
            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", description, UnitKind.BATCH_JOB,
                Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "ParityCase.description");
        }

        @Test
        @DisplayName("an absent unitKind is refused, because the harness cannot guess the shape")
        void anAbsentUnitKindIsRefused() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new ParityCase(PROGRAM, "case01", "d", null, Map.of(), Map.of(),
                    null, null, List.of(), List.of(), 0, List.of(), List.of()))
                .withMessageContainingAll("unitKind", "BATCH_JOB", "CONTROLLER_POJO");
        }

        @ParameterizedTest
        @DisplayName("every UnitKind constant can carry a case, so none is decorative")
        @EnumSource(UnitKind.class)
        void everyUnitKindCanCarryACase(UnitKind kind) {
            ScreenRequest request = kind == UnitKind.CONTROLLER_POJO ? minimalRequest() : null;
            ExpectedResponse response = kind == UnitKind.CONTROLLER_POJO ? minimalResponse() : null;

            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", kind, Map.of(), Map.of(),
                request, response, List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThat(parityCase.unitKind()).isEqualTo(kind);
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("expectedReturnCode: the z/OS step return code bound")
    class ReturnCode {

        @ParameterizedTest(name = "return code {0} is accepted")
        @DisplayName("every code this migration produces, and the bounds, are accepted")
        @ValueSource(ints = {0, 3, 4, 8, 12, 16, 4095})
        void everyProducedReturnCodeIsAccepted(int returnCode) {
            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                Map.of(), Map.of(), null, null, List.of(), List.of(), returnCode, List.of(),
                List.of());

            Assertions.assertThat(parityCase.expectedReturnCode()).isEqualTo(returnCode);
        }

        @ParameterizedTest(name = "return code {0} is rejected")
        @DisplayName("a negative or out-of-range code is refused as a sign or transcription error")
        @ValueSource(ints = {-1, -8, 4096, 100_000})
        void anOutOfRangeReturnCodeIsRefused(int returnCode) {
            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, null, List.of(), List.of(), returnCode, List.of(),
                    List.of()),
                "expectedReturnCode", "4095");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("inputs: addressed by binding key, never by dataset name")
    class Inputs {

        @ParameterizedTest(name = "inputs key \"{0}\" is rejected")
        @DisplayName("a literal dataset name, a lower-cased key or an over-long key is refused")
        @ValueSource(strings = {"AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS", "usrsec", "USRSECFILE",
            "1USRSEC", "USR SEC", "USR-SEC", ""})
        void aKeyThatIsNotABindingKeyIsRefused(String key) {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put(key, DatasetInput.ofRows(List.of(SEED_ROW_80)));

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB, inputs,
                    Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "ParityCase.inputs key");
        }

        @ParameterizedTest(name = "inputs key \"{0}\" is accepted")
        @DisplayName("every binding key shape application.yml declares is accepted")
        @ValueSource(strings = {"USRSEC", "ACCTDAT", "TCATBALF", "XREFFIL1", "A"})
        void everyRealBindingKeyShapeIsAccepted(String key) {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put(key, DatasetInput.ofRows(List.of(SEED_ROW_80)));

            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                inputs, Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThat(parityCase.inputs()).containsOnlyKeys(key);
        }

        @Test
        @DisplayName("a null input value is refused rather than seeding nothing silently")
        void aNullInputValueIsRefused() {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put(USRSEC, null);

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB, inputs,
                Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "inputs", USRSEC);
        }

        @Test
        @DisplayName("declaration order is preserved, because seeding order can be behaviour")
        void declarationOrderIsPreserved() {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put("TRANFILE", DatasetInput.ofRows(List.of("a")));
            inputs.put("ACCTDAT", DatasetInput.ofRows(List.of("b")));
            inputs.put("USRSEC", DatasetInput.ofRows(List.of("c")));

            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                inputs, Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThat(parityCase.inputs().keySet())
                .containsExactly("TRANFILE", "ACCTDAT", "USRSEC");
        }

        @Test
        @DisplayName("the frozen map rejects mutation, so one test cannot perturb another's case")
        void theFrozenInputMapRejectsMutation() {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put(USRSEC, DatasetInput.ofRows(List.of(SEED_ROW_80)));
            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                inputs, Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> parityCase.inputs().put("ACCTDAT", DatasetInput.ofRows(
                    List.of("x"))));
        }

        @Test
        @DisplayName("the case copies the caller's map, so a later external edit cannot reach it")
        void theCaseCopiesTheCallersMap() {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put(USRSEC, DatasetInput.ofRows(List.of(SEED_ROW_80)));
            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                inputs, Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());

            inputs.put("ACCTDAT", DatasetInput.ofRows(List.of("late")));

            Assertions.assertThat(parityCase.inputs()).containsOnlyKeys(USRSEC);
        }

        @Test
        @DisplayName("the minimal valid batch and controller cases both construct")
        void theMinimalValidCasesConstruct() {
            Assertions.assertThat(batchCase().unitKind()).isEqualTo(UnitKind.BATCH_JOB);
            Assertions.assertThat(batchCase().screenRequest()).isNull();
            Assertions.assertThat(controllerCase().unitKind()).isEqualTo(UnitKind.CONTROLLER_POJO);
            Assertions.assertThat(controllerCase().expectedResponse()).isNotNull();
        }

        @Test
        @DisplayName("an absent inputs member normalises to empty rather than null")
        void anAbsentInputsMemberNormalisesToEmpty() {
            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB, null,
                null, null, null, null, null, 0, null, null);

            Assertions.assertThat(parityCase.inputs()).isEmpty();
            Assertions.assertThat(parityCase.jobParameters()).isEmpty();
            Assertions.assertThat(parityCase.expectedWrites()).isEmpty();
            Assertions.assertThat(parityCase.expectedFinalState()).isEmpty();
            Assertions.assertThat(parityCase.expectedMessages()).isEmpty();
            Assertions.assertThat(parityCase.normalisations()).isEmpty();
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("DatasetInput: exactly one shape, and a closed fixture allow-list")
    class FixtureInputs {

        @Test
        @DisplayName("declaring both rows and a fixture is refused")
        void declaringBothShapesIsRefused() {
            rejectedBecause(() -> new DatasetInput(List.of(SEED_ROW_80), "acctdata.txt", null, null),
                "exactly one", "both were declared");
        }

        @Test
        @DisplayName("declaring neither rows nor a fixture is refused")
        void declaringNeitherShapeIsRefused() {
            rejectedBecause(() -> new DatasetInput(List.of(), null, null, null),
                "exactly one", "neither was declared");
        }

        @Test
        @DisplayName("a row range alongside inline rows is refused as meaningless")
        void aRowRangeAlongsideInlineRowsIsRefused() {
            rejectedBecause(() -> new DatasetInput(List.of(SEED_ROW_80), null, 3, null),
                "fromRow", "rowCount", "meaningless");
            rejectedBecause(() -> new DatasetInput(List.of(SEED_ROW_80), null, null, 3),
                "fromRow", "rowCount", "meaningless");
        }

        @ParameterizedTest(name = "fixture \"{0}\" is refused")
        @DisplayName("a path separator or a parent segment is refused rather than normalised")
        @ValueSource(strings = {"../acctdata.txt", "../../application.yml",
            "fixtures/acctdata.txt", "sub/acctdata.txt", "..\\acctdata.txt",
            "dir\\acctdata.txt", "/etc/passwd", "/acctdata.txt"})
        void pathTraversalIsRefused(String fixture) {
            rejectedBecause(() -> DatasetInput.ofFixture(fixture), "DatasetInput.fixture");
        }

        @ParameterizedTest(name = "fixture \"{0}\" is refused")
        @DisplayName("a URI scheme or a drive letter is refused, so nothing leaves the classpath")
        @ValueSource(strings = {"C:acctdata.txt", "jar:acctdata.txt", "classpath:acctdata.txt"})
        void aSchemeOrDriveLetterIsRefused(String fixture) {
            rejectedBecause(() -> DatasetInput.ofFixture(fixture),
                "parent-directory segment or a scheme");
        }

        @ParameterizedTest(name = "fixture \"{0}\" is refused")
        @DisplayName("a full URI is refused too; the separator check catches it first")
        @ValueSource(strings = {"file:/etc/passwd", "http://example.invalid/x.txt",
            "file:///acctdata.txt"})
        void aFullUriIsRefused(String fixture) {
            rejectedBecause(() -> DatasetInput.ofFixture(fixture), "DatasetInput.fixture",
                "no path separator");
        }

        @ParameterizedTest(name = "fixture \"{0}\" is refused")
        @DisplayName("a non-.txt name is refused: nothing else on the classpath is dataset rows")
        @ValueSource(strings = {"acctdata", "acctdata.TXT", "ACCTDATA.TXT", "acctdata.yml",
            "application.yml", "acctdata.txt.bak"})
        void aNonTextFixtureIsRefused(String fixture) {
            rejectedBecause(() -> DatasetInput.ofFixture(fixture), "DatasetInput.fixture");
        }

        @ParameterizedTest(name = "fixture \"{0}\" is refused")
        @DisplayName("an unknown .txt name is refused, because the permitted set is closed at nine")
        @ValueSource(strings = {"secrets.txt", "acct-data.txt", "acctdata2.txt", "carddata2.txt"})
        void anUnknownFixtureIsRefused(String fixture) {
            rejectedBecause(() -> DatasetInput.ofFixture(fixture), "not one of the nine fixtures");
        }

        @Test
        @DisplayName("a blank fixture name is refused, rather than resolving to the root itself")
        void aBlankFixtureNameIsRefused() {
            rejectedBecause(() -> new DatasetInput(List.of(), "   ", null, null),
                "DatasetInput.fixture", "blank");
        }

        @Test
        @DisplayName("all nine shipped fixtures are accepted and resolve under the fixed root")
        void allNineShippedFixturesAreAccepted() {
            Assertions.assertThat(DatasetInput.PERMITTED_FIXTURES).hasSize(9);

            for (String fixture : DatasetInput.PERMITTED_FIXTURES) {
                DatasetInput input = DatasetInput.ofFixture(fixture);

                Assertions.assertThat(input.fixtureBacked()).isTrue();
                Assertions.assertThat(input.inline()).isFalse();
                Assertions.assertThat(input.resourcePath())
                    .isEqualTo(DatasetInput.FIXTURE_ROOT + fixture)
                    .startsWith("fixtures/")
                    .endsWith(DatasetInput.FIXTURE_EXTENSION);
            }
        }

        @Test
        @DisplayName("every permitted fixture actually exists on the test classpath")
        void everyPermittedFixtureExistsOnTheClasspath() {
            for (String fixture : DatasetInput.PERMITTED_FIXTURES) {
                String path = DatasetInput.ofFixture(fixture).resourcePath();

                Assertions.assertThat(getClass().getClassLoader().getResource(path))
                    .as("the allow-list must name only fixtures that exist, or a case naming one "
                        + "would pass validation and then fail to seed: %s", path)
                    .isNotNull();
            }
        }

        @Test
        @DisplayName("fromRow defaults to zero and a row range narrows a fixture")
        void aRowRangeNarrowsAFixture() {
            DatasetInput whole = DatasetInput.ofFixture("acctdata.txt");
            DatasetInput narrowed = new DatasetInput(List.of(), "acctdata.txt", 5, 3);

            Assertions.assertThat(whole.fromRow()).isZero();
            Assertions.assertThat(whole.rowCount()).isNull();
            Assertions.assertThat(narrowed.fromRow()).isEqualTo(5);
            Assertions.assertThat(narrowed.rowCount()).isEqualTo(3);
        }

        @ParameterizedTest(name = "fromRow {0} is refused")
        @DisplayName("a negative fromRow is refused: the index is zero-based")
        @ValueSource(ints = {-1, -50})
        void aNegativeFromRowIsRefused(int fromRow) {
            rejectedBecause(() -> new DatasetInput(List.of(), "acctdata.txt", fromRow, null),
                "fromRow", "zero-based");
        }

        @ParameterizedTest(name = "rowCount {0} is refused")
        @DisplayName("a rowCount below one is refused: omit it to seed to end of fixture")
        @ValueSource(ints = {0, -1})
        void aRowCountBelowOneIsRefused(int rowCount) {
            rejectedBecause(() -> new DatasetInput(List.of(), "acctdata.txt", 0, rowCount),
                "rowCount", "at least 1");
        }

        @Test
        @DisplayName("resourcePath() refuses to invent a path for an inline input")
        void resourcePathRefusesForAnInlineInput() {
            DatasetInput inline = DatasetInput.ofRows(List.of(SEED_ROW_80));

            Assertions.assertThatIllegalStateException()
                .isThrownBy(inline::resourcePath)
                .withMessageContaining("inline rows");
        }

        @Test
        @DisplayName("inline() and fixtureBacked() are strict complements")
        void inlineAndFixtureBackedAreStrictComplements() {
            DatasetInput inline = DatasetInput.ofRows(List.of(SEED_ROW_80));
            DatasetInput backed = DatasetInput.ofFixture("acctdata.txt");

            Assertions.assertThat(inline.inline()).isNotEqualTo(inline.fixtureBacked());
            Assertions.assertThat(backed.inline()).isNotEqualTo(backed.fixtureBacked());
        }

        @Test
        @DisplayName("inline rows are preserved byte for byte, trailing spaces included")
        void inlineRowsArePreservedVerbatim() {
            String overpunched = "00000001940}";
            DatasetInput input = DatasetInput.ofRows(List.of(SEED_ROW_80, overpunched, "   "));

            Assertions.assertThat(input.rows())
                .as("a row is seeded, not tidied: trailing spaces and overpunch bytes are the record")
                .containsExactly(SEED_ROW_80, overpunched, "   ");
            Assertions.assertThat(input.rows().get(0)).hasSize(80);
        }

        @Test
        @DisplayName("a null inline row is refused rather than seeded as an empty record")
        void aNullInlineRowIsRefused() {
            List<String> rows = new ArrayList<>();
            rows.add(SEED_ROW_80);
            rows.add(null);

            rejectedBecause(() -> DatasetInput.ofRows(rows), "rows[1]", "verbatim");
        }

        @Test
        @DisplayName("the row list is frozen against mutation")
        void theRowListIsFrozen() {
            DatasetInput input = DatasetInput.ofRows(List.of(SEED_ROW_80));

            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> input.rows().add("extra"));
        }

        @Test
        @DisplayName("toString names the shape and never a row, which is where the password sits")
        void toStringNeverPrintsARow() {
            String inline = DatasetInput.ofRows(List.of(SEED_ROW_80)).toString();
            String backed = DatasetInput.ofFixture("acctdata.txt").toString();

            Assertions.assertThat(inline)
                .contains("inline", "1 row(s)")
                .doesNotContain("PASSWORD", "ADMIN001");
            Assertions.assertThat(backed).contains("fixtures/acctdata.txt", "rowCount=all");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("jobParameters: batch parameters only, and never an expectation")
    class JobParameters {

        @Test
        @DisplayName("the one verified parameter in the migration is accepted verbatim")
        void parmDateIsAcceptedVerbatim() {
            ParityCase parityCase = new ParityCase("CBACT04C", "case01", "d", UnitKind.BATCH_JOB,
                Map.of(), Map.of("parmDate", "2022071800"), null, null, List.of(), List.of(), 0,
                List.of(), List.of());

            Assertions.assertThat(parityCase.jobParameters())
                .as("PARM='2022071800' is character data concatenated into TRAN-ID, not a date")
                .containsExactly(Assertions.entry("parmDate", "2022071800"));
        }

        @ParameterizedTest(name = "unitKind {0} may not declare a job parameter")
        @DisplayName("only a batch job may declare a parameter; anything else is refused")
        @CsvSource({"SERVICE", "COMPONENT", "CONTROLLER_POJO"})
        void onlyABatchJobMayDeclareAParameter(UnitKind kind) {
            ScreenRequest request = kind == UnitKind.CONTROLLER_POJO ? minimalRequest() : null;
            ExpectedResponse response = kind == UnitKind.CONTROLLER_POJO ? minimalResponse() : null;

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", kind, Map.of(),
                    Map.of("parmDate", "2022071800"), request, response, List.of(), List.of(), 0,
                    List.of(), List.of()),
                "jobParameters", "screenRequest");
        }

        @ParameterizedTest(name = "parameter \"{0}\" is refused as an expectation")
        @DisplayName("a name beginning \"expected\" is refused, whatever its case")
        @ValueSource(strings = {"expectedMessage", "expectedNextProgram", "EXPECTEDROW",
            "ExpectedRecord", "expected"})
        void aNameThatIsAnExpectationIsRefused(String name) {
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put(name, "anything");

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), parameters, null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "names an expectation", "passes by construction");
        }

        @ParameterizedTest(name = "parameter \"{0}\" is refused as malformed")
        @DisplayName("a COBOL, EIB or screen field name is refused: those belong in screenRequest")
        @ValueSource(strings = {"EIBAID", "EIBCALEN", "CDEMO-FROM-PROGRAM", "eib.EIBAID",
            "USRIDINI", "parm_date", "Parm-Date", "parm date"})
        void aFieldNameIsRefused(String name) {
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put(name, "value");

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), parameters, null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "lower camel case", "screenRequest");
        }

        @Test
        @DisplayName("a null parameter value is refused rather than bound as absent")
        void aNullParameterValueIsRefused() {
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("parmDate", null);

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                Map.of(), parameters, null, null, List.of(), List.of(), 0, List.of(), List.of()),
                "parmDate", "must carry a value");
        }

        @Test
        @DisplayName("an empty map is legitimate: POSTTRAN.jcl declares no PARM at all")
        void anEmptyMapIsLegitimate() {
            ParityCase parityCase = new ParityCase("CBTRN02C", "case01", "d", UnitKind.BATCH_JOB,
                Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThat(parityCase.jobParameters()).isEmpty();
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Online members: required for a controller, forbidden for a batch job")
    class OnlineMembers {

        @Test
        @DisplayName("a controller case without a screenRequest is refused")
        void aControllerCaseWithoutAScreenRequestIsRefused() {
            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.CONTROLLER_POJO,
                    Map.of(), Map.of(), null, minimalResponse(), List.of(), List.of(), 0, List.of(),
                    List.of()),
                "screenRequest", "required", "CONTROLLER_POJO");
        }

        @Test
        @DisplayName("a controller case without an expectedResponse is refused")
        void aControllerCaseWithoutAnExpectedResponseIsRefused() {
            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.CONTROLLER_POJO,
                    Map.of(), Map.of(), minimalRequest(), null, List.of(), List.of(), 0, List.of(),
                    List.of()),
                "expectedResponse", "required", "diff count of zero");
        }

        @Test
        @DisplayName("a batch case declaring a screenRequest is refused: a job has no screen")
        void aBatchCaseWithAScreenRequestIsRefused() {
            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), minimalRequest(), null, List.of(), List.of(), 0, List.of(),
                    List.of()),
                "screenRequest", "no screen");
        }

        @Test
        @DisplayName("a batch case declaring an expectedResponse is refused")
        void aBatchCaseWithAnExpectedResponseIsRefused() {
            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, minimalResponse(), List.of(), List.of(), 0, List.of(),
                    List.of()),
                "expectedResponse", "no screen");
        }

        @ParameterizedTest(name = "unitKind {0} may omit both online members")
        @DisplayName("a service or component may omit both, since neither is a screen or a job")
        @CsvSource({"SERVICE", "COMPONENT"})
        void aServiceOrComponentMayOmitBoth(UnitKind kind) {
            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", kind, Map.of(), Map.of(),
                null, null, List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThat(parityCase.screenRequest()).isNull();
            Assertions.assertThat(parityCase.expectedResponse()).isNull();
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("ScreenRequest: the typed online invocation")
    class Requests {

        @Test
        @DisplayName("eibcalen zero is a real state - the first invocation with no commarea")
        void eibcalenZeroIsARealState() {
            ScreenRequest request = new ScreenRequest(0, "DFHENTER", null, "US-ASCII", Map.of(),
                Map.of(), Map.of());

            Assertions.assertThat(request.eibcalen()).isZero();
        }

        @ParameterizedTest(name = "eibcalen {0} is refused")
        @DisplayName("a negative eibcalen is refused: a length is never negative")
        @ValueSource(ints = {-1, -100})
        void aNegativeEibcalenIsRefused(int eibcalen) {
            rejectedBecause(() -> new ScreenRequest(eibcalen, null, null, null, Map.of(), Map.of(),
                Map.of()), "eibcalen", "never negative");
        }

        @ParameterizedTest(name = "aid \"{0}\" is accepted")
        @DisplayName("every AID mnemonic CicsAid defines is accepted")
        @ValueSource(strings = {"DFHENTER", "DFHCLEAR", "DFHPF3", "DFHPF4", "DFHPF5", "DFHPF12",
            "DFHPA1"})
        void everyKnownAidMnemonicIsAccepted(String aid) {
            Assertions.assertThat(CicsAid.mnemonicsByAid().values())
                .as("the permitted set is sourced from CicsAid, not re-listed")
                .contains(aid);

            Assertions.assertThat(new ScreenRequest(1, aid, null, null, Map.of(), Map.of(), Map.of())
                .aid()).isEqualTo(aid);
        }

        @ParameterizedTest(name = "aid \"{0}\" is refused")
        @DisplayName("a mnemonic CicsAid does not define is refused, not silently ignored")
        @ValueSource(strings = {"DFHPF25", "dfhenter", "ENTER", "DFHPF0", "PF3"})
        void anUnknownAidIsRefused(String aid) {
            rejectedBecause(() -> new ScreenRequest(1, aid, null, null, Map.of(), Map.of(), Map.of()),
                "ScreenRequest.aid", "DFHAID mnemonic");
        }

        @Test
        @DisplayName("an absent AID is allowed for a path that never reads one")
        void anAbsentAidIsAllowed() {
            Assertions.assertThat(new ScreenRequest(0, null, null, null, Map.of(), Map.of(), Map.of())
                .aid()).isNull();
        }

        @Test
        @DisplayName("a pinned clock is parsed to the instant the header is derived from")
        void aPinnedClockIsParsed() {
            ScreenRequest request = new ScreenRequest(1, null, "2022-07-19T23:12:34", null, Map.of(),
                Map.of(), Map.of());

            Assertions.assertThat(request.pinnedClockAt())
                .isEqualTo(LocalDateTime.of(2022, 7, 19, 23, 12, 34));
        }

        @Test
        @DisplayName("no pinned clock yields no instant rather than the wall clock")
        void noPinnedClockYieldsNoInstant() {
            Assertions.assertThat(new ScreenRequest(1, null, null, null, Map.of(), Map.of(),
                Map.of()).pinnedClockAt()).isNull();
        }

        @ParameterizedTest(name = "pinnedClock \"{0}\" is refused")
        @DisplayName("prose or a date-only value is refused: the header needs a time too")
        @ValueSource(strings = {"now", "2022-07-19", "19/07/2022", "2022-07-19 23:12:34",
            "2022-13-01T00:00:00"})
        void anUnparseablePinnedClockIsRefused(String pinnedClock) {
            rejectedBecause(() -> new ScreenRequest(1, null, pinnedClock, null, Map.of(), Map.of(),
                Map.of()), "pinnedClock", "ISO-8601");
        }

        @ParameterizedTest(name = "charset \"{0}\" is accepted")
        @DisplayName("only the two code pages this system actually uses are accepted")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        void onlyTheTwoRealCodePagesAreAccepted(String charset) {
            Assertions.assertThat(new ScreenRequest(1, null, null, charset, Map.of(), Map.of(),
                Map.of()).charset()).isEqualTo(charset);
        }

        @ParameterizedTest(name = "charset \"{0}\" is refused")
        @DisplayName("any other charset is refused, the platform default included")
        @ValueSource(strings = {"UTF-8", "ISO-8859-1", "IBM1047", "us-ascii", "UTF-16"})
        void anyOtherCharsetIsRefused(String charset) {
            rejectedBecause(() -> new ScreenRequest(1, null, null, charset, Map.of(), Map.of(),
                Map.of()), "ScreenRequest.charset", "IBM037");
        }

        @ParameterizedTest(name = "commarea key \"{0}\" is refused")
        @DisplayName("a commarea key that is not a CDEMO- field name is refused")
        @ValueSource(strings = {"FROM-PROGRAM", "cdemo-from-program", "CDEMO", "CDEMO-",
            "EIBAID", "CDEMO_FROM_PROGRAM"})
        void aMalformedCommareaKeyIsRefused(String key) {
            Map<String, String> commarea = new LinkedHashMap<>();
            commarea.put(key, "COADM01C");

            rejectedBecause(() -> new ScreenRequest(300, null, null, null, commarea, Map.of(),
                Map.of()), "ScreenRequest.commarea", "COCOM01Y");
        }

        @ParameterizedTest(name = "commarea key \"{0}\" is accepted")
        @DisplayName("the commarea field names COCOM01Y and its extensions declare are accepted")
        @ValueSource(strings = {"CDEMO-FROM-PROGRAM", "CDEMO-PGM-CONTEXT", "CDEMO-USER-TYPE",
            "CDEMO-CU02-USR-SELECTED", "CDEMO-FROM-TRANID"})
        void realCommareaKeysAreAccepted(String key) {
            Map<String, String> commarea = new LinkedHashMap<>();
            commarea.put(key, "value");

            Assertions.assertThat(new ScreenRequest(300, null, null, null, commarea, Map.of(),
                Map.of()).commarea()).containsOnlyKeys(key);
        }

        @ParameterizedTest(name = "mapFields key \"{0}\" is refused")
        @DisplayName("a length, flag or attribute item is refused: only xxxI carries payload")
        @ValueSource(strings = {"USRIDINL", "USRIDINF", "USRIDINA", "ERRMSGO", "usridini",
            "I"})
        void aNonPayloadMapKeyIsRefused(String key) {
            Map<String, String> mapFields = new LinkedHashMap<>();
            mapFields.put(key, "ADMIN001");

            rejectedBecause(() -> new ScreenRequest(300, null, null, null, Map.of(), mapFields,
                Map.of()), "ScreenRequest.mapFields", "xxxI");
        }

        @ParameterizedTest(name = "mapFields key \"{0}\" is accepted")
        @DisplayName("the twelve COUSR02 payload items are all valid xxxI names")
        @ValueSource(strings = {"USRIDINI", "FNAMEI", "LNAMEI", "PASSWDI", "USRTYPEI", "TITLE01I"})
        void realPayloadMapKeysAreAccepted(String key) {
            Map<String, String> mapFields = new LinkedHashMap<>();
            mapFields.put(key, "x");

            Assertions.assertThat(new ScreenRequest(300, null, null, null, Map.of(), mapFields,
                Map.of()).mapFields()).containsOnlyKeys(key);
        }

        @Test
        @DisplayName("a null map value is refused: use spaces for a blank field")
        void aNullMapValueIsRefused() {
            Map<String, String> mapFields = new LinkedHashMap<>();
            mapFields.put("USRIDINI", null);

            rejectedBecause(() -> new ScreenRequest(300, null, null, null, Map.of(), mapFields,
                Map.of()), "USRIDINI", "spaces");
        }

        @Test
        @DisplayName("map and commarea order is preserved")
        void mapAndCommareaOrderIsPreserved() {
            Map<String, String> mapFields = new LinkedHashMap<>();
            mapFields.put("USRIDINI", "ADMIN001");
            mapFields.put("FNAMEI", "John");
            mapFields.put("LNAMEI", "Doe");

            Assertions.assertThat(new ScreenRequest(300, null, null, null, Map.of(), mapFields,
                Map.of()).mapFields().keySet()).containsExactly("USRIDINI", "FNAMEI", "LNAMEI");
        }

        @Test
        @DisplayName("the frozen maps reject mutation")
        void theFrozenRequestMapsRejectMutation() {
            ScreenRequest request = minimalRequest();

            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> request.mapFields().put("USRIDINI", "x"));
            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> request.commarea().put("CDEMO-USER-TYPE", "A"));
        }

        @Test
        @DisplayName("toString names the AID and the key sets but never PASSWDI's value")
        void toStringNeverPrintsAPassword() {
            Map<String, String> mapFields = new LinkedHashMap<>();
            mapFields.put("USRIDINI", "ADMIN001");
            mapFields.put("PASSWDI", "PASSWORD");
            ScreenRequest request = new ScreenRequest(300, "DFHPF5", null, "US-ASCII", Map.of(),
                mapFields, Map.of());

            Assertions.assertThat(request.toString())
                .contains("DFHPF5", "PASSWDI", "eibcalen=300")
                .doesNotContain("PASSWORD", "ADMIN001");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("ForcedOutcome: the only way to reach a WHEN OTHER arm")
    class ForcedOutcomes {

        @ParameterizedTest
        @DisplayName("every FileStatus.Outcome can be forced, so none is unreachable")
        @EnumSource(FileStatus.Outcome.class)
        void everyOutcomeCanBeForced(FileStatus.Outcome outcome) {
            ForcedOutcome forced = new ForcedOutcome(outcome, null, null);

            Assertions.assertThat(forced.outcome()).isEqualTo(outcome);
            Assertions.assertThat(forced.resp()).isNull();
        }

        @Test
        @DisplayName("the RESP and RESP2 a program DISPLAYs can be pinned")
        void respAndResp2CanBePinned() {
            ForcedOutcome forced = new ForcedOutcome(FileStatus.Outcome.OTHER,
                FileStatus.NOTOPEN, 0);

            Assertions.assertThat(forced.resp()).isEqualTo(FileStatus.NOTOPEN);
            Assertions.assertThat(forced.resp2()).isZero();
        }

        @Test
        @DisplayName("an absent outcome is refused, naming the FileStatus vocabulary")
        void anAbsentOutcomeIsRefused() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new ForcedOutcome(null, 0, 0))
                .withMessageContainingAll("ForcedOutcome.outcome", "NOT_FOUND");
        }

        @Test
        @DisplayName("a negative RESP or RESP2 is refused")
        void aNegativeResponseCodeIsRefused() {
            rejectedBecause(() -> new ForcedOutcome(FileStatus.Outcome.OK, -1, null),
                "ForcedOutcome.resp", "never negative");
            rejectedBecause(() -> new ForcedOutcome(FileStatus.Outcome.OK, null, -1),
                "ForcedOutcome.resp2", "never negative");
        }

        @ParameterizedTest(name = "operation \"{0}\" is refused")
        @DisplayName("a forced-outcome key that is not a repository method name is refused")
        @ValueSource(strings = {"READ", "Read", "read-for-update", "read.next", ""})
        void aMalformedOperationKeyIsRefused(String operation) {
            Map<String, ForcedOutcome> forced = new LinkedHashMap<>();
            forced.put(operation, new ForcedOutcome(FileStatus.Outcome.OTHER, null, null));

            rejectedBecause(() -> new ScreenRequest(300, null, null, null, Map.of(), Map.of(),
                forced), "forcedOutcomes");
        }

        @ParameterizedTest(name = "operation \"{0}\" is accepted")
        @DisplayName("the repository operations these programs actually perform are accepted")
        @ValueSource(strings = {"read", "readForUpdate", "readNext", "startBrowse", "write",
            "rewrite", "delete"})
        void realOperationKeysAreAccepted(String operation) {
            Map<String, ForcedOutcome> forced = new LinkedHashMap<>();
            forced.put(operation, new ForcedOutcome(FileStatus.Outcome.OTHER, null, null));

            Assertions.assertThat(new ScreenRequest(300, null, null, null, Map.of(), Map.of(),
                forced).forcedOutcomes()).containsOnlyKeys(operation);
        }

        @Test
        @DisplayName("a null forced outcome is refused")
        void aNullForcedOutcomeIsRefused() {
            Map<String, ForcedOutcome> forced = new LinkedHashMap<>();
            forced.put("read", null);

            rejectedBecause(() -> new ScreenRequest(300, null, null, null, Map.of(), Map.of(),
                forced), "forcedOutcomes", "read");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("ExpectedResponse and ScreenSend: the online expectation")
    class Responses {

        @Test
        @DisplayName("an absent termination is refused: XCTL and RETURN are not interchangeable")
        void anAbsentTerminationIsRefused() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new ExpectedResponse(null, null, null, Map.of(), List.of(), null,
                    null))
                .withMessageContainingAll("termination", "XCTL", "RETURN_TRANSID");
        }

        @ParameterizedTest
        @DisplayName("both terminations are expressible, so neither is decorative")
        @EnumSource(Termination.class)
        void bothTerminationsAreExpressible(Termination termination) {
            ExpectedResponse response = new ExpectedResponse(null, null, null, Map.of(), List.of(),
                null, termination);

            Assertions.assertThat(response.termination()).isEqualTo(termination);
        }

        @ParameterizedTest(name = "nextProgram \"{0}\" is refused")
        @DisplayName("a next-program name longer than eight or lower-cased is refused")
        @ValueSource(strings = {"COADM01CX", "coadm01c", "1OADM01C", "COADM-1C", " OADM01C"})
        void aMalformedNextProgramIsRefused(String nextProgram) {
            rejectedBecause(() -> new ExpectedResponse(nextProgram, null, null, Map.of(), List.of(),
                null, Termination.XCTL), "nextProgram", "COSGN00C");
        }

        @ParameterizedTest(name = "mapset \"{0}\" is refused")
        @DisplayName("a mapset or map name longer than seven is refused: CDEMO-LAST-MAP is X(7)")
        @ValueSource(strings = {"COUSR02X1", "COUSR02C", "cousr02"})
        void anOverlongMapsetIsRefused(String mapset) {
            rejectedBecause(() -> new ExpectedResponse(null, mapset, null, Map.of(), List.of(), null,
                Termination.XCTL), "nextMapset", "seven");
            rejectedBecause(() -> new ExpectedResponse(null, null, mapset, Map.of(), List.of(), null,
                Termination.XCTL), "nextMap", "seven");
        }

        @Test
        @DisplayName("the real COUSR02C targets are accepted")
        void theRealTargetsAreAccepted() {
            ExpectedResponse response = new ExpectedResponse("COADM01C", "COUSR02", "COUSR2A",
                Map.of(), List.of(), "USRIDINL", Termination.XCTL);

            Assertions.assertThat(response.nextProgram()).isEqualTo("COADM01C");
            Assertions.assertThat(response.nextMapset()).isEqualTo("COUSR02");
            Assertions.assertThat(response.nextMap()).isEqualTo("COUSR2A");
            Assertions.assertThat(response.cursorField()).isEqualTo("USRIDINL");
        }

        @ParameterizedTest(name = "cursorField \"{0}\" is refused")
        @DisplayName("a cursor field that is not an xxxL item is refused: MOVE -1 goes to the length")
        @ValueSource(strings = {"USRIDINI", "USRIDINO", "usridinl", "FNAME", "L"})
        void aCursorFieldThatIsNotALengthItemIsRefused(String cursorField) {
            rejectedBecause(() -> new ExpectedResponse(null, null, null, Map.of(), List.of(),
                cursorField, Termination.XCTL), "cursorField", "MOVE -1");
        }

        @Test
        @DisplayName("sendCount reports the pinned send count, which is behaviour")
        void sendCountReportsThePinnedCount() {
            ScreenSend send = ScreenSendFixtures.errorSend();
            ExpectedResponse response = new ExpectedResponse(null, null, null, Map.of(),
                List.of(send, send), null, Termination.RETURN_TRANSID);

            Assertions.assertThat(response.sendCount())
                .as("several COUSR02C paths send the same map twice in one invocation")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("send order is preserved and the list is frozen")
        void sendOrderIsPreservedAndFrozen() {
            ScreenSend first = ScreenSendFixtures.errorSend();
            ScreenSend second = new ScreenSend(Map.of("TITLE01O", "T"), Map.of());
            ExpectedResponse response = new ExpectedResponse(null, null, null, Map.of(),
                List.of(first, second), null, Termination.RETURN_TRANSID);

            Assertions.assertThat(response.sends()).containsExactly(first, second);
            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> response.sends().add(second));
        }

        @Test
        @DisplayName("a null send is refused: a hole in the send order changes the expectation")
        void aNullSendIsRefused() {
            List<ScreenSend> sends = new ArrayList<>();
            sends.add(ScreenSendFixtures.errorSend());
            sends.add(null);

            rejectedBecause(() -> new ExpectedResponse(null, null, null, Map.of(), sends, null,
                Termination.RETURN_TRANSID), "sends[1]", "send order");
        }

        @ParameterizedTest(name = "navigation key \"{0}\" is refused")
        @DisplayName("a navigation key that is not a commarea field name is refused")
        @ValueSource(strings = {"nextProgram", "TO-PROGRAM", "cdemo-to-program"})
        void aMalformedNavigationKeyIsRefused(String key) {
            Map<String, String> navigation = new LinkedHashMap<>();
            navigation.put(key, "COADM01C");

            rejectedBecause(() -> new ExpectedResponse(null, null, null, navigation, List.of(), null,
                Termination.XCTL), "ExpectedResponse.navigation");
        }

        @Test
        @DisplayName("a send pinning nothing is refused: it would inflate the send count")
        void aSendPinningNothingIsRefused() {
            rejectedBecause(() -> new ScreenSend(Map.of(), Map.of()), "pins nothing", "send count");
        }

        @ParameterizedTest(name = "send field \"{0}\" is refused")
        @DisplayName("a send field that is not an xxxO item is refused")
        @ValueSource(strings = {"ERRMSGI", "ERRMSGL", "errmsgo", "ERRMSG"})
        void aSendFieldThatIsNotAnOutputItemIsRefused(String field) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(field, "text");

            rejectedBecause(() -> new ScreenSend(fields, Map.of()), "ScreenSend.fields", "xxxO");
        }

        @ParameterizedTest(name = "attribute value \"{0}\" is refused")
        @DisplayName("an attribute value that is not a BmsAttributes mnemonic is refused")
        @ValueSource(strings = {"RED", "dfhred", "0x08", "DFHORANGE", "DFHMAGENTA"})
        void anUnknownAttributeMnemonicIsRefused(String mnemonic) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("ERRMSGC", mnemonic);

            rejectedBecause(() -> new ScreenSend(Map.of("ERRMSGO", "x"), attributes),
                "ScreenSend.attributes");
        }

        @ParameterizedTest(name = "attribute \"{0}\" is accepted")
        @DisplayName("the three colours COUSR02C moves are all known mnemonics")
        @ValueSource(strings = {"DFHRED", "DFHNEUTR", "DFHGREEN"})
        void theColoursTheProgramMovesAreAccepted(String mnemonic) {
            Assertions.assertThat(BmsAttributes.COLOUR_MNEMONICS.values())
                .as("the permitted set is sourced from BmsAttributes, not re-listed")
                .contains(mnemonic);

            ScreenSend send = new ScreenSend(Map.of("ERRMSGO", "x"), Map.of("ERRMSGC", mnemonic));

            Assertions.assertThat(send.attributes()).containsEntry("ERRMSGC", mnemonic);
        }

        @ParameterizedTest(name = "attribute key \"{0}\" is refused")
        @DisplayName("an attribute key that does not end in C, P, H or V is refused")
        @ValueSource(strings = {"ERRMSGO", "ERRMSGI", "ERRMSGX", "errmsgc"})
        void aMalformedAttributeKeyIsRefused(String key) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put(key, "DFHRED");

            rejectedBecause(() -> new ScreenSend(Map.of("ERRMSGO", "x"), attributes),
                "ScreenSend.attributes");
        }

        @Test
        @DisplayName("a send may pin attributes alone, which is what a colour-only change looks like")
        void aSendMayPinAttributesAlone() {
            ScreenSend send = new ScreenSend(Map.of(), Map.of("ERRMSGC", "DFHRED"));

            Assertions.assertThat(send.fields()).isEmpty();
            Assertions.assertThat(send.attributes()).containsEntry("ERRMSGC", "DFHRED");
        }

        @Test
        @DisplayName("toString of a send and a response never prints a field value")
        void toStringNeverPrintsASendValue() {
            ScreenSend send = new ScreenSend(Map.of("PASSWDO", "PASSWORD"),
                Map.of("ERRMSGC", "DFHRED"));
            ExpectedResponse response = new ExpectedResponse("COADM01C", "COUSR02", "COUSR2A",
                Map.of("CDEMO-USER-TYPE", "A"), List.of(send), "USRIDINL", Termination.XCTL);

            Assertions.assertThat(send.toString())
                .contains("PASSWDO", "DFHRED")
                .doesNotContain("PASSWORD");
            Assertions.assertThat(response.toString())
                .contains("COADM01C", "CDEMO-USER-TYPE", "sends=1")
                .doesNotContain("PASSWORD");
        }
    }

    /** Send fixtures shared by the response tests, so no test depends on another's object. */
    private static final class ScreenSendFixtures {

        private ScreenSendFixtures() {
            throw new AssertionError("fixture holder");
        }

        /** A send carrying the 78-byte error field and the colour that goes with it. */
        static ScreenSend errorSend() {
            String text = "User ID can NOT be empty...";
            return new ScreenSend(Map.of("ERRMSGO", text + " ".repeat(78 - text.length())),
                Map.of("ERRMSGC", "DFHRED"));
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("ExpectedRecord: what a record expectation may and may not say")
    class Records {

        @Test
        @DisplayName("a record pinning nothing is refused: it would always pass")
        void aRecordPinningNothingIsRefused() {
            rejectedBecause(() -> new ExpectedRecord(USRSEC, 0, Map.of(), null),
                "pins nothing", "always passes");
        }

        @Test
        @DisplayName("a zero-length expectedBytes is refused; all-spaces is legitimate")
        void aZeroLengthImageIsRefusedButSpacesAreNot() {
            rejectedBecause(() -> new ExpectedRecord(USRSEC, 0, Map.of(), ""),
                "zero-length");

            ExpectedRecord spaces = new ExpectedRecord(USRSEC, 0, Map.of(), " ".repeat(80));

            Assertions.assertThat(spaces.expectedBytes()).hasSize(80).isBlank();
        }

        @ParameterizedTest(name = "rowIndex {0} is refused")
        @DisplayName("a negative row index is refused: the index is zero-based")
        @ValueSource(ints = {-1, -10})
        void aNegativeRowIndexIsRefused(int rowIndex) {
            rejectedBecause(() -> new ExpectedRecord(USRSEC, rowIndex, Map.of("SEC-USR-ID", "A"),
                null), "rowIndex", "zero-based");
        }

        @ParameterizedTest(name = "dataset \"{0}\" is refused")
        @DisplayName("a dataset addressed by anything but a binding key is refused")
        @ValueSource(strings = {"AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS", "usrsec", "USRSECDATA", ""})
        void aRecordAddressedByADatasetNameIsRefused(String dataset) {
            rejectedBecause(() -> new ExpectedRecord(dataset, 0, Map.of("SEC-USR-ID", "A"), null),
                "ExpectedRecord.dataset");
        }

        @Test
        @DisplayName("a blank field name is refused: names are the copybook's own")
        void aBlankFieldNameIsRefused() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("  ", "value");

            rejectedBecause(() -> new ExpectedRecord(USRSEC, 0, fields, null),
                "blank field name", "verbatim");
        }

        @Test
        @DisplayName("a null field value is refused: use spaces for a blank expectation")
        void aNullFieldValueIsRefused() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("SEC-USR-FNAME", null);

            rejectedBecause(() -> new ExpectedRecord(USRSEC, 0, fields, null),
                "SEC-USR-FNAME", "spaces");
        }

        @Test
        @DisplayName("a misspelled copybook name is carried verbatim, ACCT-EXPIRAION-DATE included")
        void aCopybookMisspellingIsCarriedVerbatim() {
            ExpectedRecord record = new ExpectedRecord("ACCTDAT", 0,
                Map.of("ACCT-EXPIRAION-DATE", "2024-01-01"), null);

            Assertions.assertThat(record.fields())
                .as("CVACT01Y spells it that way; renaming it would break field-for-field diffing")
                .containsOnlyKeys("ACCT-EXPIRAION-DATE");
        }

        @Test
        @DisplayName("field order is preserved and the map is frozen")
        void fieldOrderIsPreservedAndFrozen() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("SEC-USR-ID", "ADMIN001");
            fields.put("SEC-USR-FNAME", "John");
            fields.put("SEC-USR-TYPE", "A");
            ExpectedRecord record = new ExpectedRecord(USRSEC, 0, fields, null);

            Assertions.assertThat(record.fields().keySet())
                .containsExactly("SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-TYPE");
            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> record.fields().put("SEC-USR-LNAME", "Doe"));
        }

        @Test
        @DisplayName("toString names the dataset, row and pinned fields but never a value")
        void toStringNeverPrintsARecordValue() {
            ExpectedRecord record = new ExpectedRecord(USRSEC, 3,
                Map.of("SEC-USR-PWD", "PASSWORD"), SEED_ROW_80);

            Assertions.assertThat(record.toString())
                .contains(USRSEC, "row 3", "SEC-USR-PWD", "80 char(s)")
                .doesNotContain("PASSWORD", "ADMIN001");
        }

        @Test
        @DisplayName("a null element in either record list is refused")
        void aNullRecordElementIsRefused() {
            List<ExpectedRecord> withHole = new ArrayList<>();
            withHole.add(new ExpectedRecord(USRSEC, 0, Map.of("SEC-USR-ID", "A"), null));
            withHole.add(null);

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, null, withHole, List.of(), 0, List.of(), List.of()),
                "expectedWrites[1]", "hole");
            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, null, List.of(), withHole, 0, List.of(), List.of()),
                "expectedFinalState[1]", "hole");
        }

        @Test
        @DisplayName("writes and final state are separate members, so a case can state both")
        void writesAndFinalStateAreSeparateMembers() {
            ExpectedRecord written = new ExpectedRecord(USRSEC, 0,
                Map.of("SEC-USR-FNAME", "Johnny              "), null);
            ExpectedRecord finalRow = new ExpectedRecord(USRSEC, 2,
                Map.of("SEC-USR-FNAME", "Johnny              "), null);

            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                Map.of(), Map.of(), null, null, List.of(written), List.of(finalRow), 0, List.of(),
                List.of());

            Assertions.assertThat(parityCase.expectedWrites()).containsExactly(written);
            Assertions.assertThat(parityCase.expectedFinalState()).containsExactly(finalRow);
            Assertions.assertThat(parityCase.expectedWrites())
                .as("the write sequence and the resulting state are different assertions")
                .isNotEqualTo(parityCase.expectedFinalState());
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("EmittedMessage and MessageChannel: width belongs to the channel")
    class Messages {

        @Test
        @DisplayName("the three channels carry the three widths the programs actually use")
        void theThreeChannelsCarryTheirWidths() {
            Assertions.assertThat(MessageChannel.DISPLAY_LINE.fixedWidth()).isZero();
            Assertions.assertThat(MessageChannel.WS_MESSAGE_80.fixedWidth()).isEqualTo(80);
            Assertions.assertThat(MessageChannel.SCREEN_ERRMSG_78.fixedWidth()).isEqualTo(78);
        }

        @ParameterizedTest
        @DisplayName("every channel declares why its width is what it is")
        @EnumSource(MessageChannel.class)
        void everyChannelDeclaresItsProvenance(MessageChannel channel) {
            Assertions.assertThat(channel.declaration()).isNotBlank();
        }

        @Test
        @DisplayName("a DISPLAY line may be any width, including empty and 28 characters")
        void aDisplayLineMayBeAnyWidth() {
            String respLine = "RESP:000000019REAS:000000000";

            Assertions.assertThat(new EmittedMessage(MessageChannel.DISPLAY_LINE, respLine).text())
                .hasSize(28);
            Assertions.assertThat(new EmittedMessage(MessageChannel.DISPLAY_LINE, "").text())
                .as("a COBOL DISPLAY of a blank line emits one")
                .isEmpty();
        }

        @Test
        @DisplayName("an 80-byte channel accepts exactly 80 and refuses 79 or 81")
        void theEightyByteChannelIsExact() {
            Assertions.assertThat(new EmittedMessage(MessageChannel.WS_MESSAGE_80, " ".repeat(80))
                .text()).hasSize(80);

            rejectedBecause(() -> new EmittedMessage(MessageChannel.WS_MESSAGE_80, " ".repeat(79)),
                "WS_MESSAGE_80", "exactly 80");
            rejectedBecause(() -> new EmittedMessage(MessageChannel.WS_MESSAGE_80, " ".repeat(81)),
                "WS_MESSAGE_80", "exactly 80");
        }

        @Test
        @DisplayName("a 78-byte channel accepts exactly 78: MOVE WS-MESSAGE TO ERRMSGO truncates")
        void theSeventyEightByteChannelIsExact() {
            Assertions.assertThat(new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78,
                " ".repeat(78)).text()).hasSize(78);

            rejectedBecause(() -> new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78,
                " ".repeat(80)), "SCREEN_ERRMSG_78", "exactly 78");
        }

        @Test
        @DisplayName("the invalid-key message renders at both widths and they differ by two bytes")
        void theSameTextAtBothWidthsIsTwoDifferentExpectations() {
            String text = "Invalid key pressed. Please see below...";
            EmittedMessage eighty = new EmittedMessage(MessageChannel.WS_MESSAGE_80,
                text + " ".repeat(80 - text.length()));
            EmittedMessage seventyEight = new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78,
                text + " ".repeat(78 - text.length()));

            Assertions.assertThat(eighty.text()).hasSize(80);
            Assertions.assertThat(seventyEight.text()).hasSize(78);
            Assertions.assertThat(eighty.text()).isNotEqualTo(seventyEight.text());
        }

        @Test
        @DisplayName("an absent channel or text is refused")
        void anAbsentChannelOrTextIsRefused() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new EmittedMessage(null, "x"))
                .withMessageContainingAll("channel", "DISPLAY_LINE", "SCREEN_ERRMSG_78");
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new EmittedMessage(MessageChannel.DISPLAY_LINE, null))
                .withMessageContaining("empty string");
        }

        @Test
        @DisplayName("a null element in expectedMessages is refused")
        void aNullMessageElementIsRefused() {
            List<EmittedMessage> withHole = new ArrayList<>();
            withHole.add(new EmittedMessage(MessageChannel.DISPLAY_LINE, "line"));
            withHole.add(null);

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, null, List.of(), List.of(), 0, withHole, List.of()),
                "expectedMessages[1]", "DISPLAY_LINE");
        }

        @Test
        @DisplayName("emission order is preserved and the list is frozen")
        void emissionOrderIsPreservedAndFrozen() {
            EmittedMessage first = new EmittedMessage(MessageChannel.DISPLAY_LINE, "first");
            EmittedMessage second = new EmittedMessage(MessageChannel.DISPLAY_LINE, "second");
            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(first, second),
                List.of());

            Assertions.assertThat(parityCase.expectedMessages()).containsExactly(first, second);
            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> parityCase.expectedMessages().add(first));
        }

        @Test
        @DisplayName("toString masks a line that is nothing but the legacy credential")
        void toStringMasksACredentialOnlyLine() {
            EmittedMessage credential = new EmittedMessage(MessageChannel.DISPLAY_LINE, "PASSWORD");
            EmittedMessage ordinary = new EmittedMessage(MessageChannel.DISPLAY_LINE,
                "User ADMIN003 has been updated ...");

            Assertions.assertThat(credential.toString())
                .contains(Redaction.MASK)
                .doesNotContain("PASSWORD");
            Assertions.assertThat(ordinary.toString())
                .as("an ordinary emitted line must be shown in full or it cannot be diagnosed")
                .contains("User ADMIN003 has been updated ...");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Normalisation: the seed-time pad, owned here and applied once")
    class Normalisations {

        @Test
        @DisplayName("there are exactly two recorded deviations and no third")
        void thereAreExactlyTwoRecordedDeviations() {
            Assertions.assertThat(Normalisation.values())
                .as("a normalisation is an evidenced fixture-to-copybook deviation, not an escape "
                    + "hatch for any width mismatch")
                .hasSize(2)
                .containsExactly(Normalisation.CARDXREF_FILLER_PAD_36_TO_50,
                    Normalisation.USRSEC_FILLER_PAD_57_TO_80);
        }

        @Test
        @DisplayName("each constant carries the arithmetic a reviewer can check without leaving here")
        void eachConstantCarriesItsArithmetic() {
            Normalisation xref = Normalisation.CARDXREF_FILLER_PAD_36_TO_50;
            Normalisation usrsec = Normalisation.USRSEC_FILLER_PAD_57_TO_80;

            Assertions.assertThat(xref.sourceWidth()).isEqualTo(36);
            Assertions.assertThat(xref.targetWidth()).isEqualTo(50);
            Assertions.assertThat(xref.padWidth()).isEqualTo(14);
            Assertions.assertThat(xref.copybook()).isEqualTo("CVACT03Y");
            Assertions.assertThat(xref.absentSpan()).contains("FILLER", "14");

            Assertions.assertThat(usrsec.sourceWidth()).isEqualTo(57);
            Assertions.assertThat(usrsec.targetWidth()).isEqualTo(80);
            Assertions.assertThat(usrsec.padWidth()).isEqualTo(23);
            Assertions.assertThat(usrsec.copybook()).isEqualTo("CSUSR01Y");
            Assertions.assertThat(usrsec.absentSpan()).contains("SEC-USR-FILLER", "23");
        }

        @Test
        @DisplayName("the pad is on the right, with spaces, to exactly the copybook width")
        void thePadIsRightHandSpacesToTheCopybookWidth() {
            String padded = Normalisation.USRSEC_FILLER_PAD_57_TO_80
                .normaliseSeedRow(SEED_ROW_57, USRSEC);

            Assertions.assertThat(padded)
                .hasSize(80)
                .startsWith(SEED_ROW_57)
                .endsWith(" ".repeat(23))
                .isEqualTo(SEED_ROW_80);
        }

        @Test
        @DisplayName("the pad is idempotent, so applying it twice cannot double-pad")
        void thePadIsIdempotent() {
            Normalisation kind = Normalisation.USRSEC_FILLER_PAD_57_TO_80;
            String once = kind.normaliseSeedRow(SEED_ROW_57, USRSEC);
            String twice = kind.normaliseSeedRow(once, USRSEC);
            String thrice = kind.normaliseSeedRow(twice, USRSEC);

            Assertions.assertThat(twice).isEqualTo(once).hasSize(80);
            Assertions.assertThat(thrice).isEqualTo(once);
        }

        @ParameterizedTest(name = "a row of {0} characters is refused")
        @DisplayName("a row of any other width is refused rather than padded up silently")
        @ValueSource(ints = {0, 1, 36, 56, 58, 79, 81, 100})
        void anyOtherWidthIsRefused(int width) {
            String row = "x".repeat(width);

            rejectedBecause(() -> Normalisation.USRSEC_FILLER_PAD_57_TO_80
                    .normaliseSeedRow(row, USRSEC),
                "cannot be normalised", "USRSEC_FILLER_PAD_57_TO_80", "defect in the case");
        }

        @Test
        @DisplayName("the cross-reference pad closes the recorded 36-versus-50 fixture deviation")
        void theCrossReferencePadClosesTheRecordedDeviation() {
            String row36 = "1234567890123456" + "000000001" + "00000000011";

            Assertions.assertThat(row36).hasSize(36);
            Assertions.assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50
                .normaliseSeedRow(row36, "CCXREF")).hasSize(50).startsWith(row36);
        }

        @Test
        @DisplayName("a null row is refused rather than normalised into spaces")
        void aNullRowIsRefused() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> Normalisation.USRSEC_FILLER_PAD_57_TO_80
                    .normaliseSeedRow(null, USRSEC))
                .withMessageContaining("seeded row is required");
        }

        @Test
        @DisplayName("a dataset-bound normalisation applies to every row of its dataset, in order")
        void aBoundNormalisationAppliesToEveryRowInOrder() {
            DatasetNormalisation bound = new DatasetNormalisation(USRSEC,
                Normalisation.USRSEC_FILLER_PAD_57_TO_80);
            String second = "USER0001Jane                Roe                 PASSWORDU";

            List<String> normalised = bound.normaliseSeedRows(List.of(SEED_ROW_57, second));

            Assertions.assertThat(normalised)
                .hasSize(2)
                .allSatisfy(row -> Assertions.assertThat(row).hasSize(80));
            Assertions.assertThat(normalised.get(0)).startsWith("ADMIN001");
            Assertions.assertThat(normalised.get(1)).startsWith("USER0001");
        }

        @Test
        @DisplayName("normaliseSeedRows returns a frozen list and refuses a null list")
        void normaliseSeedRowsReturnsAFrozenList() {
            DatasetNormalisation bound = new DatasetNormalisation(USRSEC,
                Normalisation.USRSEC_FILLER_PAD_57_TO_80);
            List<String> normalised = bound.normaliseSeedRows(List.of(SEED_ROW_57));

            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> normalised.add("x"));
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> bound.normaliseSeedRows(null))
                .withMessageContaining("Rows are required");
        }

        @Test
        @DisplayName("an absent kind or a malformed dataset is refused on the binding")
        void anAbsentKindOrMalformedDatasetIsRefused() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new DatasetNormalisation(USRSEC, null))
                .withMessageContainingAll("kind", "CARDXREF_FILLER_PAD_36_TO_50");
            rejectedBecause(() -> new DatasetNormalisation("usrsec",
                Normalisation.USRSEC_FILLER_PAD_57_TO_80), "DatasetNormalisation.dataset");
        }

        @Test
        @DisplayName("a normalisation for a dataset the case never seeds is refused")
        void anIrrelevantNormalisationIsRefused() {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put(USRSEC, DatasetInput.ofRows(List.of(SEED_ROW_57)));
            List<DatasetNormalisation> irrelevant = List.of(new DatasetNormalisation("CCXREF",
                Normalisation.CARDXREF_FILLER_PAD_36_TO_50));

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB, inputs,
                    Map.of(), null, null, List.of(), List.of(), 0, List.of(), irrelevant),
                "does not seed", "can never fire");
        }

        @Test
        @DisplayName("the same (dataset, kind) pair twice is refused as a hand-editing slip")
        void aDuplicateNormalisationIsRefused() {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put(USRSEC, DatasetInput.ofRows(List.of(SEED_ROW_57)));
            DatasetNormalisation bound = new DatasetNormalisation(USRSEC,
                Normalisation.USRSEC_FILLER_PAD_57_TO_80);

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB, inputs,
                    Map.of(), null, null, List.of(), List.of(), 0, List.of(),
                    List.of(bound, bound)),
                "more than once", "idempotent");
        }

        @Test
        @DisplayName("a null normalisation element is refused")
        void aNullNormalisationElementIsRefused() {
            List<DatasetNormalisation> withHole = new ArrayList<>();
            withHole.add(null);

            rejectedBecause(() -> new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                    Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), withHole),
                "normalisations[0]", "null");
        }

        @Test
        @DisplayName("two 50-byte datasets cannot share one declaration, which is why it is bound")
        void twoFiftyByteDatasetsCannotShareOneDeclaration() {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put("CCXREF", DatasetInput.ofRows(List.of("x".repeat(36))));
            inputs.put("TCATBALF", DatasetInput.ofRows(List.of("y".repeat(50))));
            ParityCase parityCase = new ParityCase(PROGRAM, "case01", "d", UnitKind.BATCH_JOB,
                inputs, Map.of(), null, null, List.of(), List.of(), 0, List.of(),
                List.of(new DatasetNormalisation("CCXREF",
                    Normalisation.CARDXREF_FILLER_PAD_36_TO_50)));

            Assertions.assertThat(parityCase.normalisations())
                .as("the pad names CCXREF, so a mis-sized TCATBALF row cannot be padded away by it")
                .singleElement()
                .satisfies(normalisation -> Assertions.assertThat(normalisation.dataset())
                    .isEqualTo("CCXREF"));
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Redaction: carrying a credential and printing it are different things")
    class Redactions {

        @ParameterizedTest(name = "\"{0}\" is a credential field")
        @DisplayName("the three names that carry the eight password bytes are all sensitive")
        @ValueSource(strings = {"SEC-USR-PWD", "PASSWDI", "PASSWDO"})
        void theThreeCredentialFieldsAreSensitive(String fieldName) {
            Assertions.assertThat(Redaction.sensitiveField(fieldName)).isTrue();
            Assertions.assertThat(Redaction.maskFieldValue(fieldName, "PASSWORD"))
                .doesNotContain("PASSWORD")
                .contains(Redaction.MASK, "len=8");
        }

        @ParameterizedTest(name = "\"{0}\" is not a credential field")
        @DisplayName("an ordinary field is not masked, or a diff could not be diagnosed")
        @ValueSource(strings = {"SEC-USR-ID", "SEC-USR-FNAME", "ERRMSGO", "ACCT-CURR-BAL",
            "sec-usr-pwd", "SEC-USR-PWD-HASH"})
        void anOrdinaryFieldIsNotMasked(String fieldName) {
            Assertions.assertThat(Redaction.sensitiveField(fieldName)).isFalse();
            Assertions.assertThat(Redaction.maskFieldValue(fieldName, "ADMIN001"))
                .isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("a null field name or value passes through without a NullPointerException")
        void nullsPassThrough() {
            Assertions.assertThat(Redaction.sensitiveField(null)).isFalse();
            Assertions.assertThat(Redaction.maskFieldValue("SEC-USR-PWD", null)).isNull();
            Assertions.assertThat(Redaction.maskRecordImage(USRSEC, null)).isNull();
            Assertions.assertThat(Redaction.maskIfSensitiveText(null)).isNull();
        }

        @Test
        @DisplayName("the mask preserves the length, so a width difference stays diagnosable")
        void theMaskPreservesTheLength() {
            Assertions.assertThat(Redaction.maskFieldValue("SEC-USR-PWD", "PASS")).contains("len=4");
            Assertions.assertThat(Redaction.maskFieldValue("SEC-USR-PWD", "PASSWORDX"))
                .contains("len=9");
        }

        @Test
        @DisplayName("the credential span inside a USRSEC image is masked and nothing else is")
        void theCredentialSpanInsideAnImageIsMasked() {
            String masked = Redaction.maskRecordImage(USRSEC, SEED_ROW_80);

            Assertions.assertThat(masked)
                .hasSameSizeAs(SEED_ROW_80)
                .doesNotContain("PASSWORD")
                .contains("ADMIN001")
                .contains("John")
                .contains("*".repeat(Redaction.SENSITIVE_SPAN_LENGTH));
            Assertions.assertThat(masked.substring(0, Redaction.SENSITIVE_SPAN_OFFSET))
                .isEqualTo(SEED_ROW_80.substring(0, Redaction.SENSITIVE_SPAN_OFFSET));
        }

        @Test
        @DisplayName("the masked span is exactly where SecUserRecord declares SEC-USR-PWD")
        void theMaskedSpanIsWhereTheCopybookPutsIt() {
            Assertions.assertThat(Redaction.SENSITIVE_SPAN_OFFSET)
                .as("SEC-USR-ID 8 + SEC-USR-FNAME 20 + SEC-USR-LNAME 20 = 48")
                .isEqualTo(48);
            Assertions.assertThat(Redaction.SENSITIVE_SPAN_LENGTH)
                .as("SEC-USR-PWD PIC X(08)")
                .isEqualTo(8);
            Assertions.assertThat(SEED_ROW_80.substring(Redaction.SENSITIVE_SPAN_OFFSET,
                Redaction.SENSITIVE_SPAN_OFFSET + Redaction.SENSITIVE_SPAN_LENGTH))
                .isEqualTo("PASSWORD");
        }

        @Test
        @DisplayName("an image of another dataset is untouched, even at the same offset")
        void anImageOfAnotherDatasetIsUntouched() {
            Assertions.assertThat(Redaction.maskRecordImage("ACCTDAT", SEED_ROW_80))
                .isEqualTo(SEED_ROW_80);
        }

        @Test
        @DisplayName("a USRSEC image too short to hold the span is returned unchanged, not truncated")
        void aShortImageIsReturnedUnchanged() {
            String short55 = "x".repeat(55);

            Assertions.assertThat(Redaction.maskRecordImage(USRSEC, short55)).isEqualTo(short55);
        }

        @Test
        @DisplayName("text masking is narrow: only a line that is nothing but the credential")
        void textMaskingIsNarrow() {
            Assertions.assertThat(Redaction.maskIfSensitiveText("PASSWORD")).isEqualTo(
                Redaction.MASK);
            Assertions.assertThat(Redaction.maskIfSensitiveText("  PASSWORD  ")).isEqualTo(
                Redaction.MASK);
            Assertions.assertThat(Redaction.maskIfSensitiveText("Password can NOT be empty..."))
                .isEqualTo("Password can NOT be empty...");
        }

        @Test
        @DisplayName("the mask is fixed text that cannot be mistaken for data")
        void theMaskIsFixedText() {
            Assertions.assertThat(Redaction.MASK).isEqualTo("<redacted>");
            Assertions.assertThat(Redaction.SENSITIVE_FIELDS).hasSize(3);
            Assertions.assertThat(Redaction.SENSITIVE_DATASET).isEqualTo(USRSEC);
        }

        @Test
        @DisplayName("Redaction is a policy holder and refuses instantiation")
        void redactionRefusesInstantiation() throws ReflectiveOperationException {
            Constructor<Redaction> constructor = Redaction.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            Assertions.assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(constructor::newInstance)
                .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("ParityCase.toString discloses shape and identity but no value")
        void theCaseToStringDisclosesNoValue() {
            Map<String, DatasetInput> inputs = new LinkedHashMap<>();
            inputs.put(USRSEC, DatasetInput.ofRows(List.of(SEED_ROW_80)));
            ParityCase parityCase = new ParityCase(PROGRAM, "case07", "update a user",
                UnitKind.CONTROLLER_POJO, inputs, Map.of(), minimalRequest(), minimalResponse(),
                List.of(new ExpectedRecord(USRSEC, 0, Map.of("SEC-USR-PWD", "PASSWORD"), null)),
                List.of(), 0, List.of(), List.of());

            Assertions.assertThat(parityCase.toString())
                .contains("COUSR02C/case07", "CONTROLLER_POJO", USRSEC, "expectedWrites=1")
                .doesNotContain("PASSWORD", "ADMIN001", "John");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Strict JSON binding: a typo fails the load rather than removing an input")
    class Binding {

        /** A complete, valid controller case as a fixture would write it. */
        private static final String VALID_JSON = """
            {
              "program": "COUSR02C",
              "caseId": "case01",
              "description": "no commarea: the EIBCALEN guard transfers to the sign-on screen",
              "unitKind": "CONTROLLER_POJO",
              "inputs": {
                "USRSEC": { "rows": ["%s"] }
              },
              "jobParameters": {},
              "screenRequest": {
                "eibcalen": 0,
                "aid": "DFHENTER",
                "pinnedClock": "2022-07-19T23:12:34",
                "charset": "US-ASCII",
                "commarea": {},
                "mapFields": {},
                "forcedOutcomes": {}
              },
              "expectedResponse": {
                "nextProgram": "COSGN00C",
                "nextMapset": "COUSR02",
                "nextMap": "COUSR2A",
                "navigation": { "CDEMO-TO-PROGRAM": "COSGN00C" },
                "sends": [
                  { "fields": { "ERRMSGO": "%s" }, "attributes": { "ERRMSGC": "DFHRED" } }
                ],
                "cursorField": "USRIDINL",
                "termination": "XCTL"
              },
              "expectedWrites": [],
              "expectedFinalState": [
                { "dataset": "USRSEC", "rowIndex": 0, "fields": { "SEC-USR-ID": "ADMIN001" } }
              ],
              "expectedReturnCode": 0,
              "expectedMessages": [
                { "channel": "DISPLAY_LINE", "text": "RESP:000000019REAS:000000000" }
              ],
              "normalisations": [
                { "dataset": "USRSEC", "kind": "USRSEC_FILLER_PAD_57_TO_80" }
              ]
            }
            """.formatted(SEED_ROW_57, " ".repeat(78));

        /**
         * A valid batch document with one member left open, so a superseded member can be injected
         * into an otherwise-clean case. Batch rather than controller because a batch case needs no
         * screen members, which keeps the injected member the only thing under test.
         */
        private static final String LEGACY_SKELETON = """
            {
              "program": "COUSR02C",
              "caseId": "case01",
              "description": "a case written against the superseded contract",
              "unitKind": "BATCH_JOB",
              "expectedReturnCode": 0,
              %s
            }
            """;

        @Test
        @DisplayName("a complete valid case deserialises with every member bound")
        void aCompleteValidCaseDeserialises() throws Exception {
            ParityCase parityCase = mapper().readValue(VALID_JSON, ParityCase.class);

            Assertions.assertThat(parityCase.program()).isEqualTo("COUSR02C");
            Assertions.assertThat(parityCase.caseId()).isEqualTo("case01");
            Assertions.assertThat(parityCase.unitKind()).isEqualTo(UnitKind.CONTROLLER_POJO);
            Assertions.assertThat(parityCase.inputs()).containsOnlyKeys(USRSEC);
            Assertions.assertThat(parityCase.inputs().get(USRSEC).rows()).containsExactly(
                SEED_ROW_57);
            Assertions.assertThat(parityCase.screenRequest().aid()).isEqualTo("DFHENTER");
            Assertions.assertThat(parityCase.screenRequest().pinnedClockAt())
                .isEqualTo(LocalDateTime.of(2022, 7, 19, 23, 12, 34));
            Assertions.assertThat(parityCase.expectedResponse().termination())
                .isEqualTo(Termination.XCTL);
            Assertions.assertThat(parityCase.expectedResponse().sendCount()).isEqualTo(1);
            Assertions.assertThat(parityCase.expectedFinalState()).hasSize(1);
            Assertions.assertThat(parityCase.expectedMessages()).singleElement()
                .satisfies(message -> Assertions.assertThat(message.channel())
                    .isEqualTo(MessageChannel.DISPLAY_LINE));
            Assertions.assertThat(parityCase.normalisations()).singleElement()
                .satisfies(normalisation -> Assertions.assertThat(normalisation.kind())
                    .isEqualTo(Normalisation.USRSEC_FILLER_PAD_57_TO_80));
        }

        @ParameterizedTest(name = "unknown top-level key \"{0}\" fails the load")
        @DisplayName("an unrecognised top-level key fails the load rather than being ignored")
        @ValueSource(strings = {"expectedRecords", "expectedRecord", "programme", "unit_kind",
            "screenRequests", "normalization"})
        void anUnknownTopLevelKeyFailsTheLoad(String key) {
            String json = VALID_JSON.replaceFirst("\\{", "{ \"" + key + "\": 1,");

            Assertions.assertThatExceptionOfType(UnrecognizedPropertyException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .withMessageContaining(key);
        }

        @Test
        @DisplayName("an unrecognised key nested in screenRequest fails the load too")
        void anUnknownNestedKeyFailsTheLoad() {
            String json = VALID_JSON.replace("\"eibcalen\": 0", "\"eibcalen\": 0, \"EIBAID\": \"x\"");

            Assertions.assertThatExceptionOfType(UnrecognizedPropertyException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .withMessageContaining("EIBAID");
        }

        @Test
        @DisplayName("an unrecognised key nested in a dataset input fails the load")
        void anUnknownDatasetInputKeyFailsTheLoad() {
            String json = VALID_JSON.replace("{ \"rows\": [", "{ \"path\": \"x\", \"rows\": [");

            Assertions.assertThatExceptionOfType(UnrecognizedPropertyException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .withMessageContaining("path");
        }

        @Test
        @DisplayName("an unrecognised key nested in a record expectation fails the load")
        void anUnknownRecordKeyFailsTheLoad() {
            String json = VALID_JSON.replace("\"rowIndex\": 0", "\"rowIndex\": 0, \"row\": 0");

            Assertions.assertThatExceptionOfType(UnrecognizedPropertyException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .withMessageContaining("row");
        }

        @Test
        @DisplayName("an unrecognised key nested in a send fails the load")
        void anUnknownSendKeyFailsTheLoad() {
            String json = VALID_JSON.replace("{ \"fields\": { \"ERRMSGO\"",
                "{ \"colour\": \"red\", \"fields\": { \"ERRMSGO\"");

            Assertions.assertThatExceptionOfType(UnrecognizedPropertyException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .withMessageContaining("colour");
        }

        @Test
        @DisplayName("an unrecognised key nested in a message fails the load")
        void anUnknownMessageKeyFailsTheLoad() {
            String json = VALID_JSON.replace("\"channel\": \"DISPLAY_LINE\"",
                "\"channel\": \"DISPLAY_LINE\", \"width\": 28");

            Assertions.assertThatExceptionOfType(UnrecognizedPropertyException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .withMessageContaining("width");
        }

        @Test
        @DisplayName("an unrecognised key nested in a normalisation fails the load")
        void anUnknownNormalisationKeyFailsTheLoad() {
            String json = VALID_JSON.replace("\"kind\": \"USRSEC_FILLER_PAD_57_TO_80\"",
                "\"kind\": \"USRSEC_FILLER_PAD_57_TO_80\", \"padTo\": 80");

            Assertions.assertThatExceptionOfType(UnrecognizedPropertyException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .withMessageContaining("padTo");
        }

        @ParameterizedTest(name = "unitKind \"{0}\" fails the load")
        @DisplayName("an unknown enum constant fails the load rather than defaulting")
        @ValueSource(strings = {"CONTROLLER", "controller_pojo", "JOB", "TASKLET"})
        void anUnknownUnitKindFailsTheLoad(String kind) {
            String json = VALID_JSON.replace("\"unitKind\": \"CONTROLLER_POJO\"",
                "\"unitKind\": \"" + kind + '"');

            Assertions.assertThatExceptionOfType(MismatchedInputException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class));
        }

        @ParameterizedTest(name = "channel \"{0}\" fails the load")
        @DisplayName("an unknown message channel fails the load")
        @ValueSource(strings = {"DISPLAY", "WS_MESSAGE", "SCREEN", "display_line"})
        void anUnknownMessageChannelFailsTheLoad(String channel) {
            String json = VALID_JSON.replace("\"channel\": \"DISPLAY_LINE\"",
                "\"channel\": \"" + channel + '"');

            Assertions.assertThatExceptionOfType(MismatchedInputException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class));
        }

        @ParameterizedTest(name = "normalisation kind \"{0}\" fails the load")
        @DisplayName("an invented normalisation kind fails the load: there is no third")
        @ValueSource(strings = {"PAD_TO_80", "ACCTDAT_PAD", "USRSEC_FILLER_PAD"})
        void anInventedNormalisationKindFailsTheLoad(String kind) {
            String json = VALID_JSON.replace("\"kind\": \"USRSEC_FILLER_PAD_57_TO_80\"",
                "\"kind\": \"" + kind + '"');

            Assertions.assertThatExceptionOfType(MismatchedInputException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class));
        }

        @Test
        @DisplayName("an unknown termination fails the load")
        void anUnknownTerminationFailsTheLoad() {
            String json = VALID_JSON.replace("\"termination\": \"XCTL\"",
                "\"termination\": \"RETURN\"");

            Assertions.assertThatExceptionOfType(MismatchedInputException.class)
                .isThrownBy(() -> mapper().readValue(json, ParityCase.class));
        }

        @Test
        @DisplayName("the old schema no longer loads, which is what makes the migration complete")
        void theSupersededSchemaNoLongerLoads() {
            // Jackson defers unknown-property reporting until after the creator has run, so each
            // superseded member is asserted against a document that is otherwise valid. That way the
            // failure reported is the one being asserted and not an unrelated earlier one.
            String bareStringMessage = LEGACY_SKELETON.formatted(
                "\"expectedMessages\": [\"a bare string with no channel\"]");
            String bareStringNormalisation = LEGACY_SKELETON.formatted(
                "\"normalisations\": [\"USRSEC_PAD_57_TO_80\"]");
            String supersededRecords = LEGACY_SKELETON.formatted("\"expectedRecords\": []");
            String supersededParameter = LEGACY_SKELETON.formatted(
                "\"jobParameters\": { \"EXPECTED-NEXT-PROGRAM\": \"COSGN00C\" }");

            Assertions.assertThatExceptionOfType(MismatchedInputException.class)
                .as("a message must declare its channel, so a bare string cannot bind")
                .isThrownBy(() -> mapper().readValue(bareStringMessage, ParityCase.class))
                .withMessageContaining("EmittedMessage");

            Assertions.assertThatExceptionOfType(MismatchedInputException.class)
                .as("a normalisation must name the dataset it pads, so a bare marker cannot bind")
                .isThrownBy(() -> mapper().readValue(bareStringNormalisation, ParityCase.class))
                .withMessageContaining("DatasetNormalisation");

            Assertions.assertThatExceptionOfType(UnrecognizedPropertyException.class)
                .as("expectedRecords conflated writes with final state and no longer exists")
                .isThrownBy(() -> mapper().readValue(supersededRecords, ParityCase.class))
                .withMessageContaining("expectedRecords");

            Assertions.assertThatThrownBy(
                    () -> mapper().readValue(supersededParameter, ParityCase.class))
                .as("an expectation parked among the inputs is refused by name")
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names an expectation");
        }

        @Test
        @DisplayName("a validation failure surfaces the model's own message, not a generic one")
        void aValidationFailureSurfacesTheModelMessage() {
            String json = VALID_JSON.replace("\"caseId\": \"case01\"", "\"caseId\": \"case21\"");

            Assertions.assertThatThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .hasMessageContaining("case01")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a serialise-then-deserialise round trip preserves every member")
        void aRoundTripPreservesEveryMember() throws Exception {
            ObjectMapper mapper = mapper();
            ParityCase original = mapper.readValue(VALID_JSON, ParityCase.class);

            String json = mapper.writeValueAsString(original);
            ParityCase reloaded = mapper.readValue(json, ParityCase.class);

            Assertions.assertThat(reloaded)
                .as("a derived accessor emitted into JSON would fail strict reload; none is")
                .isEqualTo(original);
        }

        @Test
        @DisplayName("a round trip preserves trailing spaces and an overpunch byte exactly")
        void aRoundTripPreservesSignificantBytes() throws Exception {
            ObjectMapper mapper = mapper();
            String image = "00000001940}" + " ".repeat(10);
            ParityCase original = new ParityCase("CBACT04C", "case01", "d", UnitKind.BATCH_JOB,
                Map.of("TRANSACT", DatasetInput.ofRows(List.of(image))), Map.of(), null, null,
                List.of(new ExpectedRecord("TRANSACT", 0, Map.of("TRAN-AMT", "00000001940}"),
                    image)),
                List.of(), 0, List.of(), List.of());

            ParityCase reloaded = mapper.readValue(mapper.writeValueAsString(original),
                ParityCase.class);

            Assertions.assertThat(reloaded.inputs().get("TRANSACT").rows()).containsExactly(image);
            Assertions.assertThat(reloaded.expectedWrites().get(0).expectedBytes())
                .isEqualTo(image)
                .endsWith(" ".repeat(10));
            Assertions.assertThat(reloaded.expectedWrites().get(0).fields())
                .containsEntry("TRAN-AMT", "00000001940}");
        }

        @Test
        @DisplayName("serialised property order follows the declared reading order")
        void serialisedPropertyOrderFollowsTheDeclaredOrder() throws Exception {
            ObjectMapper mapper = mapper();
            String json = mapper.writeValueAsString(mapper.readValue(VALID_JSON, ParityCase.class));

            Assertions.assertThat(json.indexOf("\"program\""))
                .isLessThan(json.indexOf("\"caseId\""));
            Assertions.assertThat(json.indexOf("\"inputs\""))
                .isLessThan(json.indexOf("\"expectedWrites\""));
            Assertions.assertThat(json.indexOf("\"expectedWrites\""))
                .isLessThan(json.indexOf("\"expectedFinalState\""));
            Assertions.assertThat(json.indexOf("\"expectedFinalState\""))
                .isLessThan(json.indexOf("\"expectedMessages\""));
        }

        @Test
        @DisplayName("a fixture-backed input binds its row range")
        void aFixtureBackedInputBindsItsRowRange() throws Exception {
            String json = """
                {
                  "program": "CBACT01C",
                  "caseId": "case02",
                  "description": "seed three account rows from the shipped fixture",
                  "unitKind": "BATCH_JOB",
                  "inputs": {
                    "ACCTFILE": { "fixture": "acctdata.txt", "fromRow": 4, "rowCount": 3 }
                  },
                  "expectedReturnCode": 0
                }
                """;

            ParityCase parityCase = mapper().readValue(json, ParityCase.class);
            DatasetInput input = parityCase.inputs().get("ACCTFILE");

            Assertions.assertThat(input.fixtureBacked()).isTrue();
            Assertions.assertThat(input.resourcePath()).isEqualTo("fixtures/acctdata.txt");
            Assertions.assertThat(input.fromRow()).isEqualTo(4);
            Assertions.assertThat(input.rowCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a traversing fixture name fails the load, so the guard holds through JSON")
        void aTraversingFixtureNameFailsTheLoad() {
            String json = """
                {
                  "program": "CBACT01C",
                  "caseId": "case02",
                  "description": "an attempt to seed something that is not dataset rows",
                  "unitKind": "BATCH_JOB",
                  "inputs": {
                    "ACCTFILE": { "fixture": "../application-test.yml" }
                  },
                  "expectedReturnCode": 0
                }
                """;

            Assertions.assertThatThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DatasetInput.fixture");
        }

        @Test
        @DisplayName("an expectation parked in jobParameters fails the load")
        void anExpectationParkedInJobParametersFailsTheLoad() {
            String json = """
                {
                  "program": "CBACT04C",
                  "caseId": "case02",
                  "description": "an expectation smuggled in among the inputs",
                  "unitKind": "BATCH_JOB",
                  "jobParameters": { "expectedTranId": "0000000000000001" },
                  "expectedReturnCode": 0
                }
                """;

            Assertions.assertThatThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names an expectation");
        }

        @Test
        @DisplayName("a BMS map smuggled through the dataset channel fails on its key shape")
        void aMapSmuggledThroughTheDatasetChannelFailsTheLoad() {
            String json = """
                {
                  "program": "COUSR02C",
                  "caseId": "case06",
                  "description": "a screen map addressed as though it were a dataset",
                  "unitKind": "BATCH_JOB",
                  "expectedFinalState": [
                    { "dataset": "COUSR2A-MAP", "rowIndex": 0, "fields": { "ERRMSGO": "x" } }
                  ],
                  "expectedReturnCode": 0
                }
                """;

            Assertions.assertThatThrownBy(() -> mapper().readValue(json, ParityCase.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ExpectedRecord.dataset");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("The shipped COUSR02C suite: twenty cases that must load and must cover the program")
    class ShippedSuite {

        /** Where the shipped cases live on the test classpath. */
        private static final String FOLDER = "parity/COUSR02C/";

        /** The five keys {@code COUSR02C} names explicitly in its ordered EVALUATE EIBAID. */
        private static final List<String> NAMED_AIDS =
            List.of("DFHENTER", "DFHPF3", "DFHPF4", "DFHPF5", "DFHPF12");

        /**
         * Every blank-field message the program can issue, one per named arm of the two ordered
         * {@code EVALUATE TRUE} paragraphs. Every one of these must appear in some case's screen
         * payload, or that arm is unreached and the suite's coverage claim is untrue.
         */
        private static final List<String> BLANK_FIELD_MESSAGES = List.of(
            "User ID can NOT be empty...",
            "First Name can NOT be empty...",
            "Last Name can NOT be empty...",
            "Password can NOT be empty...",
            "User Type can NOT be empty...");

        /** The ten 57-character seed cards, verbatim from {@code app/jcl/DUSRSECJ.jcl}. */
        private static final List<String> SEED_CARDS = List.of(
            "ADMIN001MARGARET            GOLD                PASSWORDA",
            "ADMIN002RUSSELL             RUSSELL             PASSWORDA",
            "ADMIN003RAYMOND             WHITMORE            PASSWORDA",
            "ADMIN004EMMANUEL            CASGRAIN            PASSWORDA",
            "ADMIN005GRANVILLE           LACHAPELLE          PASSWORDA",
            "USER0001LAWRENCE            THOMAS              PASSWORDU",
            "USER0002AJITH               KUMAR               PASSWORDU",
            "USER0003LAURITZ             ALME                PASSWORDU",
            "USER0004AVERARDO            MAZZI               PASSWORDU",
            "USER0005LEE                 TING                PASSWORDU");

        /** Reads one shipped case's raw text from the classpath. */
        private String rawCase(String caseId) {
            try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(FOLDER + caseId + ".json")) {
                Assertions.assertThat(stream)
                    .as("shipped case %s must exist on the test classpath at %s", caseId, FOLDER)
                    .isNotNull();
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("shipped case " + caseId + " is unreadable",
                    unreadable);
            }
        }

        /** Loads one shipped case through the strict mapper, which is the real assertion. */
        private ParityCase load(String caseId) {
            try {
                return mapper().readValue(rawCase(caseId), ParityCase.class);
            } catch (IOException rejected) {
                throw new UncheckedIOException("shipped case " + caseId + " does not satisfy the "
                    + "ParityCase contract: " + rejected.getMessage(), rejected);
            }
        }

        /** All twenty shipped cases in case order. */
        private List<ParityCase> loadAll() {
            List<ParityCase> cases = new ArrayList<>(20);
            for (int number = 1; number <= 20; number++) {
                cases.add(load(String.format("case%02d", number)));
            }
            return cases;
        }

        /** Every ERRMSGO value carried by any send of any case, in case then send order. */
        private List<String> allScreenMessages() {
            List<String> messages = new ArrayList<>();
            for (ParityCase parityCase : loadAll()) {
                for (ScreenSend send : parityCase.expectedResponse().sends()) {
                    String text = send.fields().get("ERRMSGO");
                    if (text != null) {
                        messages.add(text);
                    }
                }
            }
            return messages;
        }

        @Test
        @DisplayName("all twenty ship, load under the strict contract, and are numbered case01..case20")
        void allTwentyShipAndLoad() {
            List<ParityCase> cases = loadAll();

            Assertions.assertThat(cases)
                .as("gate G15: twenty declarative cases per program")
                .hasSize(20);
            Assertions.assertThat(cases).extracting(ParityCase::caseId)
                .containsExactly("case01", "case02", "case03", "case04", "case05", "case06",
                    "case07", "case08", "case09", "case10", "case11", "case12", "case13", "case14",
                    "case15", "case16", "case17", "case18", "case19", "case20");
            Assertions.assertThat(cases).extracting(ParityCase::program).containsOnly("COUSR02C");
        }

        @Test
        @DisplayName("there is no twenty-first case, and the folder holds exactly twenty JSON files")
        void thereIsNoTwentyFirstCase() {
            Assertions.assertThat(getClass().getClassLoader().getResource(FOLDER + "case21.json"))
                .as("the count is exactly twenty, not at least twenty")
                .isNull();

            URL folder = getClass().getClassLoader().getResource(FOLDER);
            Assertions.assertThat(folder).isNotNull();
            try (Stream<Path> entries = Files.list(Path.of(folder.toURI()))) {
                Assertions.assertThat(entries.filter(path -> path.toString().endsWith(".json")))
                    .hasSize(20);
            } catch (IOException | URISyntaxException unreadable) {
                throw new IllegalStateException("the shipped case folder is unreadable", unreadable);
            }
        }

        @Test
        @DisplayName("every case is a controller case with a screen request and a response")
        void everyCaseIsAControllerCase() {
            for (ParityCase parityCase : loadAll()) {
                Assertions.assertThat(parityCase.unitKind()).isEqualTo(UnitKind.CONTROLLER_POJO);
                Assertions.assertThat(parityCase.screenRequest()).isNotNull();
                Assertions.assertThat(parityCase.expectedResponse()).isNotNull();
            }
        }

        @Test
        @DisplayName("no case declares a job parameter, and no raw file mentions an EXPECTED- key")
        void noCaseParksAnExpectationAmongTheInputs() {
            for (ParityCase parityCase : loadAll()) {
                Assertions.assertThat(parityCase.jobParameters())
                    .as("%s is an online case: a job parameter would be an input it does not take",
                        parityCase.caseId())
                    .isEmpty();
            }
            for (int number = 1; number <= 20; number++) {
                String caseId = String.format("case%02d", number);

                Assertions.assertThat(rawCase(caseId).toUpperCase(Locale.ROOT))
                    .as("%s must carry no EXPECTED- key: an expected value declared among the "
                        + "inputs is fed back into the unit and compared against itself", caseId)
                    .doesNotContain("EXPECTED-");
            }
        }

        @Test
        @DisplayName("no case addresses a dataset by name; USRSEC is the only dataset touched")
        void noCaseAddressesADatasetByName() {
            for (int number = 1; number <= 20; number++) {
                String caseId = String.format("case%02d", number);
                ParityCase parityCase = load(caseId);

                Assertions.assertThat(parityCase.inputs()).containsOnlyKeys("USRSEC");
                Assertions.assertThat(rawCase(caseId))
                    .as("%s must not write a literal dataset name into a case", caseId)
                    .doesNotContain("AWS.M2.CARDDEMO");
            }
        }

        @Test
        @DisplayName("every case seeds the ten DUSRSECJ cards verbatim and declares the pad")
        void everyCaseSeedsTheTenCardsVerbatim() {
            for (ParityCase parityCase : loadAll()) {
                DatasetInput input = parityCase.inputs().get("USRSEC");

                Assertions.assertThat(input.inline()).isTrue();
                Assertions.assertThat(input.rows())
                    .as("%s seeds app/jcl/DUSRSECJ.jcl's cards verbatim", parityCase.caseId())
                    .containsExactlyElementsOf(SEED_CARDS)
                    .allSatisfy(row -> Assertions.assertThat(row).hasSize(57));
                Assertions.assertThat(parityCase.normalisations())
                    .as("%s seeds 57-byte rows, so it must declare the 57-to-80 pad",
                        parityCase.caseId())
                    .singleElement()
                    .satisfies(normalisation -> {
                        Assertions.assertThat(normalisation.dataset()).isEqualTo("USRSEC");
                        Assertions.assertThat(normalisation.kind())
                            .isEqualTo(Normalisation.USRSEC_FILLER_PAD_57_TO_80);
                    });
            }
        }

        @Test
        @DisplayName("every case states the complete final state: ten rows, each 80 bytes")
        void everyCaseStatesTheCompleteFinalState() {
            for (ParityCase parityCase : loadAll()) {
                List<ExpectedRecord> state = parityCase.expectedFinalState();

                Assertions.assertThat(state)
                    .as("%s must state what USRSEC holds afterwards, row by row", parityCase.caseId())
                    .hasSize(10);
                for (int index = 0; index < 10; index++) {
                    ExpectedRecord record = state.get(index);
                    Assertions.assertThat(record.dataset()).isEqualTo("USRSEC");
                    Assertions.assertThat(record.rowIndex()).isEqualTo(index);
                    Assertions.assertThat(record.expectedBytes())
                        .as("%s row %d must pin the whole 80-byte record, filler included",
                            parityCase.caseId(), index)
                        .isNotNull()
                        .hasSize(80);
                }
            }
        }

        @Test
        @DisplayName("every screen payload is at its declared BMS width, ERRMSGO at exactly 78")
        void everyScreenPayloadIsAtItsDeclaredWidth() {
            Map<String, Integer> widths = new LinkedHashMap<>();
            widths.put("TRNNAMEO", 4);
            widths.put("TITLE01O", 40);
            widths.put("CURDATEO", 8);
            widths.put("PGMNAMEO", 8);
            widths.put("TITLE02O", 40);
            widths.put("CURTIMEO", 8);
            widths.put("USRIDINO", 8);
            widths.put("FNAMEO", 20);
            widths.put("LNAMEO", 20);
            widths.put("PASSWDO", 8);
            widths.put("USRTYPEO", 1);
            widths.put("ERRMSGO", 78);

            for (ParityCase parityCase : loadAll()) {
                for (ScreenSend send : parityCase.expectedResponse().sends()) {
                    Assertions.assertThat(send.fields())
                        .as("%s pins the twelve payload items app/cpy-bms/COUSR02.CPY declares",
                            parityCase.caseId())
                        .containsOnlyKeys(widths.keySet().toArray(new String[0]));
                    send.fields().forEach((field, value) -> Assertions.assertThat(value)
                        .as("%s field %s", parityCase.caseId(), field)
                        .hasSize(widths.get(field)));
                }
            }
        }

        @Test
        @DisplayName("all six arms of the ordered EVALUATE EIBAID are covered")
        void allSixAidArmsAreCovered() {
            List<String> aids = new ArrayList<>();
            boolean sawNoCommarea = false;
            for (ParityCase parityCase : loadAll()) {
                ScreenRequest request = parityCase.screenRequest();
                if (request.eibcalen() == 0) {
                    sawNoCommarea = true;
                }
                if (request.aid() != null) {
                    aids.add(request.aid());
                }
            }

            Assertions.assertThat(aids)
                .as("the five keys COUSR02C names explicitly must each be driven")
                .containsAll(NAMED_AIDS);
            Assertions.assertThat(aids)
                .as("and so must the WHEN OTHER arm, with a key none of the five names")
                .anySatisfy(aid -> Assertions.assertThat(NAMED_AIDS).doesNotContain(aid));
            Assertions.assertThat(sawNoCommarea)
                .as("the EIBCALEN = 0 guard must be driven; it is the only path with no commarea")
                .isTrue();
        }

        @Test
        @DisplayName("both program-context arms are covered, with and without a pre-selected user")
        void bothProgramContextArmsAreCovered() {
            boolean firstEntryWithoutSelection = false;
            boolean firstEntryWithSelection = false;
            boolean reentry = false;
            for (ParityCase parityCase : loadAll()) {
                Map<String, String> commarea = parityCase.screenRequest().commarea();
                String context = commarea.get("CDEMO-PGM-CONTEXT");
                if (context == null) {
                    continue;
                }
                String selected = commarea.getOrDefault("CDEMO-CU02-USR-SELECTED", "");
                if ("0".equals(context)) {
                    if (selected.isBlank()) {
                        firstEntryWithoutSelection = true;
                    } else {
                        firstEntryWithSelection = true;
                    }
                } else if ("1".equals(context)) {
                    reentry = true;
                }
            }

            Assertions.assertThat(firstEntryWithoutSelection)
                .as("the NOT-REENTER arm with the CDEMO-CU02-USR-SELECTED test FALSE").isTrue();
            Assertions.assertThat(firstEntryWithSelection)
                .as("and the same arm with it TRUE, which is the triple-send path").isTrue();
            Assertions.assertThat(reentry).as("and the REENTER arm").isTrue();
        }

        @Test
        @DisplayName("every blank-field arm of both ordered EVALUATE TRUE paragraphs is covered")
        void everyBlankFieldArmIsCovered() {
            List<String> screenMessages = allScreenMessages();

            for (String message : BLANK_FIELD_MESSAGES) {
                Assertions.assertThat(screenMessages)
                    .as("the arm issuing '%s' must be driven by some case, or it is unreached",
                        message)
                    .anySatisfy(text -> Assertions.assertThat(text).startsWith(message));
            }
        }

        @Test
        @DisplayName("both figurative constants of 'SPACES OR LOW-VALUES' are driven")
        void bothFigurativeConstantsAreDriven() {
            boolean sawLowValues = false;
            boolean sawSpaces = false;
            for (ParityCase parityCase : loadAll()) {
                for (String value : parityCase.screenRequest().mapFields().values()) {
                    if (!value.isEmpty() && value.chars().allMatch(character -> character == 0)) {
                        sawLowValues = true;
                    } else if (!value.isEmpty() && value.isBlank()) {
                        sawSpaces = true;
                    }
                }
            }

            Assertions.assertThat(sawLowValues)
                .as("a translation that tested only for spaces would pass every spaces-based case")
                .isTrue();
            Assertions.assertThat(sawSpaces).as("and the spaces half must be driven too").isTrue();
        }

        @Test
        @DisplayName("every repository outcome arm is reachable, forced where data cannot reach it")
        void everyRepositoryOutcomeArmIsCovered() {
            Map<String, List<FileStatus.Outcome>> forced = new LinkedHashMap<>();
            for (ParityCase parityCase : loadAll()) {
                parityCase.screenRequest().forcedOutcomes()
                    .forEach((operation, outcome) -> forced
                        .computeIfAbsent(operation, key -> new ArrayList<>())
                        .add(outcome.outcome()));
            }

            Assertions.assertThat(forced)
                .as("the WHEN OTHER arms of both EVALUATE WS-RESP-CD paragraphs need a forced "
                    + "outcome: no arrangement of seeded rows produces an I/O failure")
                .containsOnlyKeys("readForUpdate", "rewrite");
            Assertions.assertThat(forced.get("readForUpdate"))
                .contains(FileStatus.Outcome.NOT_FOUND, FileStatus.Outcome.OTHER);
            Assertions.assertThat(forced.get("rewrite"))
                .contains(FileStatus.Outcome.NOT_FOUND, FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("both sides of the USR-MODIFIED gate are covered")
        void bothSidesOfTheModifiedGateAreCovered() {
            List<String> screenMessages = allScreenMessages();

            Assertions.assertThat(screenMessages)
                .as("the TRUE side: a rewrite that succeeded says so by name")
                .anySatisfy(text -> Assertions.assertThat(text).contains("has been updated ..."));
            Assertions.assertThat(screenMessages)
                .as("the FALSE side: nothing changed, so nothing is written")
                .anySatisfy(text -> Assertions.assertThat(text)
                    .startsWith("Please modify to update ..."));
        }

        @Test
        @DisplayName("the three colours the program moves are each asserted somewhere")
        void theThreeColoursAreEachAsserted() {
            List<String> colours = new ArrayList<>();
            for (ParityCase parityCase : loadAll()) {
                for (ScreenSend send : parityCase.expectedResponse().sends()) {
                    colours.addAll(send.attributes().values());
                }
            }

            Assertions.assertThat(colours)
                .as("colour is how the program distinguishes an error from a prompt from a "
                    + "confirmation, so all three must be pinned by some case")
                .contains("DFHNEUTR", "DFHRED", "DFHGREEN");
        }

        @Test
        @DisplayName("both terminations and a range of send counts are covered")
        void bothTerminationsAndSeveralSendCountsAreCovered() {
            List<Termination> terminations = new ArrayList<>();
            List<Integer> sendCounts = new ArrayList<>();
            for (ParityCase parityCase : loadAll()) {
                terminations.add(parityCase.expectedResponse().termination());
                sendCounts.add(parityCase.expectedResponse().sendCount());
            }

            Assertions.assertThat(terminations)
                .contains(Termination.XCTL, Termination.RETURN_TRANSID);
            Assertions.assertThat(sendCounts)
                .as("zero sends is a real path (the guards and PF12), and three is the inherited "
                    + "triple-send quirk; a model carrying one screen could express neither")
                .contains(0, 1, 2, 3);
        }

        @Test
        @DisplayName("a write is asserted, and so is a rewrite that was attempted and rejected")
        void bothWrittenAndRejectedRewritesAreAsserted() {
            boolean sawWrite = false;
            boolean sawRejectedRewrite = false;
            for (ParityCase parityCase : loadAll()) {
                if (!parityCase.expectedWrites().isEmpty()) {
                    sawWrite = true;
                    Assertions.assertThat(parityCase.expectedWrites())
                        .allSatisfy(record -> {
                            Assertions.assertThat(record.dataset()).isEqualTo("USRSEC");
                            Assertions.assertThat(record.expectedBytes()).hasSize(80);
                        });
                }
                boolean rewriteForcedToFail = parityCase.screenRequest().forcedOutcomes()
                    .entrySet().stream()
                    .anyMatch(entry -> "rewrite".equals(entry.getKey())
                        && entry.getValue().outcome() != FileStatus.Outcome.OK);
                if (rewriteForcedToFail && parityCase.expectedWrites().isEmpty()) {
                    sawRejectedRewrite = true;
                }
            }

            Assertions.assertThat(sawWrite).as("a successful rewrite must be asserted").isTrue();
            Assertions.assertThat(sawRejectedRewrite)
                .as("and a rejected one, whose empty write list records that nothing was written")
                .isTrue();
        }

        @Test
        @DisplayName("an invalid user type is persisted verbatim, proving no validation happens")
        void anInvalidUserTypeIsPersistedVerbatim() {
            List<String> persistedTypes = new ArrayList<>();
            for (ParityCase parityCase : loadAll()) {
                for (ExpectedRecord record : parityCase.expectedWrites()) {
                    String type = record.fields().get("SEC-USR-TYPE");
                    if (type != null) {
                        persistedTypes.add(type);
                    }
                }
            }

            Assertions.assertThat(persistedTypes)
                .as("COCOM01Y declares only 'A' and 'U' as user types and COUSR02C tests neither, "
                    + "so a case that changed the type to a VALID value would leave the absence of "
                    + "validation unproven - a translation that quietly validated the field would "
                    + "accept 'A' and still pass")
                .anySatisfy(type -> Assertions.assertThat(type).isNotIn("A", "U"));
        }

        @Test
        @DisplayName("emitted DISPLAY lines are the RESP/REAS pair at its own natural width")
        void emittedDisplayLinesAreTheRespReasPair() {
            List<EmittedMessage> emitted = new ArrayList<>();
            for (ParityCase parityCase : loadAll()) {
                emitted.addAll(parityCase.expectedMessages());
            }

            Assertions.assertThat(emitted)
                .as("the two DISPLAY sites in the program are the only lines it emits")
                .isNotEmpty()
                .allSatisfy(message -> {
                    Assertions.assertThat(message.channel())
                        .isEqualTo(MessageChannel.DISPLAY_LINE);
                    Assertions.assertThat(message.text())
                        .as("'RESP:' + S9(09) COMP + 'REAS:' + S9(09) COMP")
                        .hasSize(28)
                        .startsWith("RESP:")
                        .contains("REAS:");
                });
        }

        @Test
        @DisplayName("every case explains itself, and no two cases share a description")
        void everyCaseExplainsItself() {
            List<String> descriptions = new ArrayList<>();
            for (ParityCase parityCase : loadAll()) {
                Assertions.assertThat(parityCase.description())
                    .as("%s must say which arm it pins and why", parityCase.caseId())
                    .isNotBlank()
                    .contains("COUSR02C");
                descriptions.add(parityCase.description());
            }

            Assertions.assertThat(descriptions)
                .as("a duplicated description means two cases pin the same thing")
                .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the clock is pinned in every case, so a header assertion is possible at all")
        void theClockIsPinnedInEveryCase() {
            for (ParityCase parityCase : loadAll()) {
                Assertions.assertThat(parityCase.screenRequest().pinnedClockAt())
                    .as("%s must pin the clock: POPULATE-HEADER-INFO moves FUNCTION CURRENT-DATE "
                        + "into CURDATEO and CURTIMEO", parityCase.caseId())
                    .isEqualTo(LocalDateTime.of(2022, 7, 19, 23, 12, 34));
                Assertions.assertThat(parityCase.screenRequest().charset()).isEqualTo("US-ASCII");
            }
        }

        @Test
        @DisplayName("no shipped case discloses a credential through its own rendering")
        void noShippedCaseDisclosesACredential() {
            for (ParityCase parityCase : loadAll()) {
                Assertions.assertThat(parityCase.toString())
                    .as("%s renders its shape, not its content", parityCase.caseId())
                    .doesNotContain("PASSWORD", "SECRET99");
                for (ScreenSend send : parityCase.expectedResponse().sends()) {
                    Assertions.assertThat(send.toString()).doesNotContain("PASSWORD", "SECRET99");
                }
                for (ExpectedRecord record : parityCase.expectedWrites()) {
                    Assertions.assertThat(record.toString()).doesNotContain("PASSWORD", "SECRET99");
                }
            }
        }
    }
}
