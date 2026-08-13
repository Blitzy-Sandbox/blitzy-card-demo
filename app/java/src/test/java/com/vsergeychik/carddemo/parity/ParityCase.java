package com.vsergeychik.carddemo.parity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One declarative behavioural-parity case for exactly one of the 28 COBOL programs in {@code app/cbl}.
 *
 * @param program the COBOL program this case pins, upper case and exactly eight characters, for example
 *     {@code "CBACT04C"} or {@code "CBSTM03A"}; doubles as the {@code parity/<PROGRAM>/} resource directory
 *     segment
 * @param caseId the case identifier, {@code "case01"} through {@code "case20"}, matching the fixture file
 *     name stem so a case is traceable to its file from a failure message alone
 * @param description required prose naming precisely which COBOL branch, {@code EVALUATE} arm,
 *     {@code 88}-level condition or arithmetic site this case pins
 * @param unitKind how {@code ParityHarness} must invoke the unit under test; never {@code null}
 * @param inputs the pre-state: input datasets to seed before the unit runs, keyed by dataset binding key
 *     and held in declaration order because seeding order can matter for sequential datasets; never
 *     {@code null}
 * @param jobParameters batch job parameters in declaration order, as raw character strings; never
 *     {@code null}, and necessarily empty for anything other than a {@link UnitKind#BATCH_JOB}
 * @param screenRequest the typed online invocation - the AID, the commarea, the received map fields and any
 *     forced repository outcome; required for a {@link UnitKind#CONTROLLER_POJO} and {@code null} for a batch
 *     job
 * @param expectedResponse the typed online expectation - every screen send's BMS payload, the navigation
 *     context, the attribute metadata, the cursor field and the termination
 * @param expectedWrites the records the unit is expected to write, in write order; never {@code null}, and
 *     empty asserts positively that nothing was written
 * @param expectedFinalState what each dataset holds after the run, row by row; never {@code null}
 * @param expectedReturnCode the expected COBOL {@code RETURN-CODE}, mapped onto the batch exit status
 * @param expectedMessages emitted lines in emission order, each declaring its channel and therefore its
 *     width; never {@code null}
 * @param normalisations the seed-time width normalisations, each bound to the dataset it applies to; never
 *     {@code null}, and empty for a case seeding no under-width fixture
 * @param expectedDatasets dataset-level expectations - the identity and the exact row count of a dataset on
 *     a channel, including a count of zero; never {@code null}, and empty for a case that asserts only at row
 *     level
 * @param unitStimulus the declarative description of the run the harness must perform
 * @param expectedOperations the dataset operations the run is expected to issue, in order
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonPropertyOrder({
    "program",
    "caseId",
    "description",
    "unitKind",
    "inputs",
    "jobParameters",
    "screenRequest",
    "expectedResponse",
    "expectedWrites",
    "expectedFinalState",
    "expectedReturnCode",
    "expectedMessages",
    "normalisations",
    "expectedDatasets",
    "unitStimulus",
    "expectedOperations"
})
public record ParityCase(
    @JsonProperty("program") String program,
    @JsonProperty("caseId") String caseId,
    @JsonProperty("description") String description,
    @JsonProperty("unitKind") UnitKind unitKind,
    @JsonProperty("inputs") Map<String, DatasetInput> inputs,
    @JsonProperty("jobParameters") Map<String, String> jobParameters,
    @JsonProperty("screenRequest") ScreenRequest screenRequest,
    @JsonProperty("expectedResponse") ExpectedResponse expectedResponse,
    @JsonProperty("expectedWrites") List<ExpectedRecord> expectedWrites,
    @JsonProperty("expectedFinalState") List<ExpectedRecord> expectedFinalState,
    @JsonProperty("expectedReturnCode") Integer expectedReturnCode,
    @JsonProperty("expectedMessages") List<EmittedMessage> expectedMessages,
    @JsonProperty("normalisations") List<DatasetNormalisation> normalisations,
    @JsonProperty("expectedDatasets") List<ExpectedDataset> expectedDatasets,
    @JsonProperty("unitStimulus") UnitStimulus unitStimulus,
    @JsonProperty("expectedOperations") List<ExpectedOperation> expectedOperations) {
    private static final Pattern PROGRAM_NAME = Pattern.compile("[A-Z][A-Z0-9]{7}");

    private static final Pattern CASE_ID = Pattern.compile("case(?:0[1-9]|1[0-9]|20)");

    private static final Pattern DATASET_KEY = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

    private static final Pattern COPYBOOK_NAME = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

    private static final Pattern JOB_PARAMETER_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");

    private static final Pattern COMMAREA_FIELD = Pattern.compile("CDEMO-[A-Z0-9]+(?:-[A-Z0-9]+)*");

    private static final Pattern INBOUND_COMMAREA_FIELD =
        Pattern.compile("(?:CDEMO|WS)-[A-Z0-9]+(?:-[A-Z0-9]+)*");

    private static final Pattern MAP_INPUT_FIELD = Pattern.compile("[A-Z][A-Z0-9]{0,28}I");

    private static final Pattern MAP_OUTPUT_FIELD = Pattern.compile("[A-Z][A-Z0-9]{0,28}O");

    private static final Pattern MAP_ATTRIBUTE_FIELD = Pattern.compile("[A-Z][A-Z0-9]{0,28}[CPHV]");

    private static final Pattern MAP_LENGTH_FIELD = Pattern.compile("[A-Z][A-Z0-9]{0,28}L");

    private static final Pattern PROGRAM_REFERENCE = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

    private static final Pattern MAP_REFERENCE = Pattern.compile("[A-Z][A-Z0-9]{0,6}");

    private static final int MAX_RETURN_CODE = 4095;

    private static final Set<String> PERMITTED_CHARSETS = Set.of("US-ASCII", "IBM037");

    private static final Set<String> AID_MNEMONICS = Set.copyOf(CicsAid.mnemonicsByAid().values());

    private static final Set<String> ATTRIBUTE_MNEMONICS = attributeMnemonics();

    @JsonCreator
    public ParityCase {
        program = requireProgramName(program);
        caseId = requireCaseId(caseId);
        description = requireText(description, "description");
        unitKind = Objects.requireNonNull(
            unitKind,
            "ParityCase.unitKind is required: declare one of BATCH_JOB, SERVICE, COMPONENT or "
                + "CONTROLLER_POJO so the harness knows how to invoke the unit");
        inputs = freezeInputs(inputs);
        jobParameters = freezeJobParameters(jobParameters, unitKind);
        screenRequest = requireOnlineMember(screenRequest, unitKind, "screenRequest",
            "the AID, the commarea and the received map fields the handler is called with");
        expectedResponse = requireOnlineMember(expectedResponse, unitKind, "expectedResponse",
            "every screen send's BMS payload, the navigation context and the termination");
        expectedWrites = freezeExpectedRecords(expectedWrites, "expectedWrites");
        expectedFinalState = freezeExpectedRecords(expectedFinalState, "expectedFinalState");
        expectedReturnCode = requireReturnCode(expectedReturnCode);
        expectedMessages = freezeExpectedMessages(expectedMessages);
        normalisations = freezeNormalisations(normalisations, inputs);
        expectedDatasets = freezeExpectedDatasets(expectedDatasets);
        unitStimulus = unitStimulus == null ? UnitStimulus.NONE : unitStimulus;
        expectedOperations = freezeExpectedOperations(expectedOperations);
    }

    public ParityCase(String program, String caseId, String description, UnitKind unitKind,
                      Map<String, DatasetInput> inputs, Map<String, String> jobParameters,
                      ScreenRequest screenRequest, ExpectedResponse expectedResponse,
                      List<ExpectedRecord> expectedWrites, List<ExpectedRecord> expectedFinalState,
                      Integer expectedReturnCode, List<EmittedMessage> expectedMessages,
                      List<DatasetNormalisation> normalisations,
                      List<ExpectedDataset> expectedDatasets) {
        this(program, caseId, description, unitKind, inputs, jobParameters, screenRequest,
            expectedResponse, expectedWrites, expectedFinalState, expectedReturnCode,
            expectedMessages, normalisations, expectedDatasets, UnitStimulus.NONE, List.of());
    }

    public ParityCase(String program, String caseId, String description, UnitKind unitKind,
                      Map<String, DatasetInput> inputs, Map<String, String> jobParameters,
                      ScreenRequest screenRequest, ExpectedResponse expectedResponse,
                      List<ExpectedRecord> expectedWrites, List<ExpectedRecord> expectedFinalState,
                      Integer expectedReturnCode, List<EmittedMessage> expectedMessages,
                      List<DatasetNormalisation> normalisations,
                      List<ExpectedDataset> expectedDatasets, UnitStimulus unitStimulus) {
        this(program, caseId, description, unitKind, inputs, jobParameters, screenRequest,
            expectedResponse, expectedWrites, expectedFinalState, expectedReturnCode,
            expectedMessages, normalisations, expectedDatasets, unitStimulus, List.of());
    }

    public ParityCase(String program, String caseId, String description, UnitKind unitKind,
                      Map<String, DatasetInput> inputs, Map<String, String> jobParameters,
                      ScreenRequest screenRequest, ExpectedResponse expectedResponse,
                      List<ExpectedRecord> expectedWrites, List<ExpectedRecord> expectedFinalState,
                      Integer expectedReturnCode, List<EmittedMessage> expectedMessages,
                      List<DatasetNormalisation> normalisations) {
        this(program, caseId, description, unitKind, inputs, jobParameters, screenRequest,
            expectedResponse, expectedWrites, expectedFinalState, expectedReturnCode,
            expectedMessages, normalisations, List.of());
    }

    @Override
    public String toString() {
        return "ParityCase[" + program + '/' + caseId
            + ", unitKind=" + unitKind
            + ", inputs=" + inputs.keySet()
            + ", jobParameters=" + jobParameters.keySet()
            + ", screenRequest=" + (screenRequest == null ? "absent" : "present")
            + ", expectedResponse=" + (expectedResponse == null ? "absent" : "present")
            + ", expectedWrites=" + expectedWrites.size()
            + ", expectedFinalState=" + expectedFinalState.size()
            + ", expectedReturnCode=" + expectedReturnCode
            + ", expectedMessages=" + expectedMessages.size()
            + ", normalisations=" + normalisations.size()
            + ", expectedDatasets=" + expectedDatasets.size()
            + ", unitStimulus=" + (unitStimulus.isEmpty() ? "none" : unitStimulus.toString())
            + ", expectedOperations=" + expectedOperations.size() + ']';
    }

    public enum RepositoryOperation {
        READ("read"),

        READ_FOR_UPDATE("readForUpdate"),

        READ_NEXT("readNext"),

        START_BROWSE("startBrowse"),

        WRITE("write"),

        REWRITE("rewrite"),

        DELETE("delete");

        private final String key;

        RepositoryOperation(String key) {
            this.key = key;
        }

        @JsonValue
        public String key() {
            return key;
        }

        @JsonCreator
        public static RepositoryOperation fromKey(String key) {
            for (RepositoryOperation operation : values()) {
                if (operation.key.equals(key)) {
                    return operation;
                }
            }
            StringBuilder permitted = new StringBuilder();
            for (RepositoryOperation operation : values()) {
                permitted.append(permitted.isEmpty() ? "" : ", ").append(operation.key);
            }
            throw new IllegalArgumentException("\"" + key + "\" is not a repository operation this "
                + "migration performs. The seven are: " + permitted + ". A forced outcome exists to "
                + "reach an arm the seeded data cannot reach, so a key matching no call site would "
                + "force nothing and leave the case asserting the opposite of what it says.");
        }
    }

    public enum UnitKind {
        BATCH_JOB,

        SERVICE,

        COMPONENT,

        CONTROLLER_POJO
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"rows", "fixture", "fromRow", "rowCount", "empty", "recordLength",
        "copybook"})
    public record DatasetInput(
        @JsonProperty("rows") List<String> rows,
        @JsonProperty("fixture") String fixture,
        @JsonProperty("fromRow") Integer fromRow,
        @JsonProperty("rowCount") Integer rowCount,
        @JsonProperty("empty") Boolean empty,
        @JsonProperty("recordLength") Integer recordLength,
        @JsonProperty("copybook") String copybook) {
        public static final String FIXTURE_ROOT = "fixtures/";

        public static final String FIXTURE_EXTENSION = ".txt";

        public static final Set<String> PERMITTED_FIXTURES = Set.of(
            "acctdata.txt",
            "carddata.txt",
            "cardxref.txt",
            "custdata.txt",
            "dailytran.txt",
            "discgrp.txt",
            "tcatbal.txt",
            "trancatg.txt",
            "trantype.txt");

        @JsonCreator
        public DatasetInput {
            rows = freezeRows(rows);
            fixture = requireFixtureName(fixture);

            boolean hasRows = !rows.isEmpty();
            boolean hasFixture = fixture != null;
            boolean hasEmpty = requireEmptyFlag(empty);
            int shapes = (hasRows ? 1 : 0) + (hasFixture ? 1 : 0) + (hasEmpty ? 1 : 0);
            if (shapes != 1) {
                throw new IllegalArgumentException(
                    "DatasetInput must declare exactly one of \"rows\", \"fixture\" or "
                        + "\"empty\": true, but " + (shapes == 0
                            ? "none was declared; a dataset the case says nothing about must be "
                                + "omitted from \"inputs\" altogether, and a dataset that exists and "
                                + "holds no row must say so with \"empty\": true plus its "
                                + "\"recordLength\" and \"copybook\" - the two are different "
                                + "assertions and only the second reaches the first-read "
                                + "end-of-file branch"
                            : shapes + " were declared; put the literal rows in \"rows\", or name "
                                + "the fixture in \"fixture\", or declare \"empty\": true - never "
                                + "more than one"));
            }

            if (hasEmpty) {
                if (fromRow != null || rowCount != null) {
                    throw new IllegalArgumentException(
                        "DatasetInput declares \"empty\": true so \"fromRow\" and \"rowCount\" "
                            + "are meaningless and must be omitted; a row range narrows a fixture, and "
                            + "an empty dataset has no row to narrow");
                }
                recordLength = requireEmptyRecordLength(recordLength);
                copybook = requireEmptyCopybook(copybook);
            } else {
                if (recordLength != null || copybook != null) {
                    throw new IllegalArgumentException(
                        "DatasetInput declares \"recordLength\" or \"copybook\" without "
                            + "\"empty\": true. Those two describe a dataset that holds no row; a "
                            + "seeded row measures its own width, and a fixture's width is the "
                            + "fixture's. Declaring them here would create a second, unchecked opinion "
                            + "about how wide a record is.");
                }
            }

            if (hasRows) {
                if (fromRow != null || rowCount != null) {
                    throw new IllegalArgumentException(
                        "DatasetInput declares inline \"rows\" so \"fromRow\" and \"rowCount\" are "
                            + "meaningless and must be omitted; a row range narrows a fixture, and "
                            + "inline rows are already exactly the rows to seed");
                }
            } else if (hasFixture) {
                requirePermittedFixture(fixture);
                fromRow = fromRow == null ? Integer.valueOf(0) : fromRow;
                if (fromRow < 0) {
                    throw new IllegalArgumentException(
                        "DatasetInput.fromRow is a zero-based row index and must not be negative, "
                            + "but was " + fromRow);
                }
                if (rowCount != null && rowCount < 1) {
                    throw new IllegalArgumentException(
                        "DatasetInput.rowCount must be at least 1 when present, but was " + rowCount
                            + "; omit it entirely to seed every row from fromRow to end of fixture");
                }
            }
        }

        @JsonIgnore
        public String fixtureResourcePath() {
            return fixture == null ? null : FIXTURE_ROOT + fixture;
        }

        public DatasetInput(List<String> rows, String fixture, Integer fromRow, Integer rowCount) {
            this(rows, fixture, fromRow, rowCount, null, null, null);
        }

        public static DatasetInput ofRows(List<String> rows) {
            return new DatasetInput(rows, null, null, null, null, null, null);
        }

        public static DatasetInput ofFixture(String fixture) {
            return new DatasetInput(List.of(), fixture, null, null, null, null, null);
        }

        public static DatasetInput ofEmpty(int recordLength, String copybook) {
            return new DatasetInput(List.of(), null, null, null, Boolean.TRUE, recordLength,
                copybook);
        }

        @JsonIgnore
        public boolean inline() {
            return !rows.isEmpty();
        }

        @JsonIgnore
        public boolean fixtureBacked() {
            return fixture != null;
        }

        @JsonIgnore
        public boolean declaredEmpty() {
            return Boolean.TRUE.equals(empty);
        }

        @JsonIgnore
        public String resourcePath() {
            if (fixture == null) {
                throw new IllegalStateException("This DatasetInput carries inline rows, so it names "
                    + "no classpath resource. Call inline() first, or seed rows() directly.");
            }
            return FIXTURE_ROOT + fixture;
        }

        @Override
        public String toString() {
            if (declaredEmpty()) {
                return "DatasetInput[empty, recordLength=" + recordLength + ", copybook=" + copybook
                    + ']';
            }
            return fixture == null
                ? "DatasetInput[inline, " + rows.size() + " row(s)]"
                : "DatasetInput[fixture=" + resourcePath() + ", fromRow=" + fromRow + ", rowCount="
                    + (rowCount == null ? "all" : rowCount) + ']';
        }

        private static void requirePermittedFixture(String fixture) {
            if (fixture.indexOf('/') >= 0 || fixture.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("DatasetInput.fixture must be a bare file name "
                    + "with no path separator, but was \"" + fixture + "\". Fixtures are resolved "
                    + "from the fixed classpath root " + FIXTURE_ROOT + " and a case cannot move it: "
                    + "a separator would let a case reach any resource the test classpath carries "
                    + "and seed it as though it were fixed-width dataset rows.");
            }
            if (fixture.contains("..") || fixture.contains(":")) {
                throw new IllegalArgumentException("DatasetInput.fixture must not contain a "
                    + "parent-directory segment or a scheme or drive separator, but was \"" + fixture
                    + "\". Both are path traversal, and both are refused rather than normalised.");
            }
            if (!fixture.endsWith(FIXTURE_EXTENSION)) {
                throw new IllegalArgumentException("DatasetInput.fixture must name a "
                    + FIXTURE_EXTENSION + " file, but was \"" + fixture + "\". All nine shipped "
                    + "fixtures are plain fixed-width text; nothing else is dataset rows.");
            }
            if (!PERMITTED_FIXTURES.contains(fixture)) {
                throw new IllegalArgumentException("DatasetInput.fixture is \"" + fixture
                    + "\", which is not one of the nine fixtures that exist. app/data/ASCII holds "
                    + "exactly nine files and the classpath copies are byte-identical to them, so "
                    + "the permitted set is closed: "
                    + String.join(", ", new TreeSet<>(PERMITTED_FIXTURES)) + '.');
            }
        }

        private static boolean requireEmptyFlag(Boolean empty) {
            if (Boolean.FALSE.equals(empty)) {
                throw new IllegalArgumentException("DatasetInput.empty is false, which says nothing: "
                    + "a dataset that holds rows declares them in \"rows\" or names a \"fixture\", "
                    + "and a dataset that exists and holds no row declares \"empty\": true. Omit the "
                    + "member entirely for the first two shapes.");
            }
            return Boolean.TRUE.equals(empty);
        }

        private static int requireEmptyRecordLength(Integer recordLength) {
            if (recordLength == null) {
                throw new IllegalArgumentException("DatasetInput declares \"empty\": true but no "
                    + "\"recordLength\". An empty dataset is still a fixed-width dataset: a unit that "
                    + "opens one knows how wide its records are, and a fingerprint reporting the "
                    + "dataset has to report it at that width. Without the width, an empty declaration "
                    + "would be indistinguishable from the absence it exists to be distinguished from.");
            }
            if (recordLength < 1) {
                throw new IllegalArgumentException("DatasetInput.recordLength is " + recordLength
                    + ", which is not a record width; a record occupies at least 1 byte");
            }
            return recordLength;
        }

        private static String requireEmptyCopybook(String copybook) {
            if (copybook == null) {
                throw new IllegalArgumentException("DatasetInput declares \"empty\": true but no "
                    + "\"copybook\". Naming it is what makes the declaration say what the dataset is "
                    + "empty OF, which is the difference between an assertion a reviewer can check "
                    + "against app/cpy and a bare width nobody can trace.");
            }
            if (!COPYBOOK_NAME.matcher(copybook).matches()) {
                throw new IllegalArgumentException("DatasetInput.copybook is \"" + copybook
                    + "\", which is not a copybook member name. The names are one to eight upper-case "
                    + "alphanumerics beginning with a letter, exactly as app/cpy spells them - "
                    + "CVACT01Y, CVTRA05Y, CSUSR01Y, COSTM01, CUSTREC.");
            }
            return copybook;
        }
    }

    /**
     * The typed invocation of an online unit: everything a CICS pseudo-conversational program receives, and
     * nothing that it produces.
     *
     * @param eibcalen the value of {@code EIBCALEN} - the commarea length CICS reports
     * @param aid the resolved {@code EIBAID} mnemonic, for example {@code DFHENTER} or {@code DFHPF5}; must
     *     be a mnemonic {@code common.CicsAid} defines, and may be {@code null} only on a path that never reads
     *     the AID
     * @param pinnedClock the fixed instant the unit must observe, as an ISO-8601 local date-time
     * @param charset the code page of the record images this case carries; one of {@code US-ASCII} or
     *     {@code IBM037}, never the platform default
     * @param commarea the {@code CARDDEMO-COMMAREA} field values keyed by copybook name, in declaration
     *     order; never {@code null}, and empty when {@code eibcalen} is zero
     * @param mapFields the received map's {@code xxxI} payload items keyed by their symbolic-map names;
     *     never {@code null}, and empty on a path that receives no map
     * @param forcedOutcomes the repository outcomes the harness must force, keyed by the repository
     *     operation name - the only way to reach a {@code WHEN OTHER} arm of an {@code EVALUATE WS-RESP-CD}
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({
        "eibcalen", "aid", "pinnedClock", "charset", "commarea", "mapFields", "forcedOutcomes"})
    public record ScreenRequest(
        @JsonProperty("eibcalen") int eibcalen,
        @JsonProperty("aid") String aid,
        @JsonProperty("pinnedClock") String pinnedClock,
        @JsonProperty("charset") String charset,
        @JsonProperty("commarea") Map<String, String> commarea,
        @JsonProperty("mapFields") Map<String, String> mapFields,
        @JsonProperty("forcedOutcomes") Map<RepositoryOperation, ForcedOutcome> forcedOutcomes) {
        public ScreenRequest {
            if (eibcalen < 0) {
                throw new IllegalArgumentException("ScreenRequest.eibcalen is " + eibcalen
                    + "; EIBCALEN is a length and is never negative. Zero is the no-commarea state "
                    + "and is expressed by omitting the key or declaring 0.");
            }
            aid = requireNullOrAid(aid);
            pinnedClock = requireNullOrInstant(pinnedClock);
            charset = requireNullOrCharset(charset);
            commarea = freezeNamedValues(commarea, INBOUND_COMMAREA_FIELD,
                "ScreenRequest.commarea",
                "a CARDDEMO-COMMAREA field name as app/cpy/COCOM01Y.cpy spells it, such as "
                    + "CDEMO-PGM-CONTEXT or CDEMO-CU02-USR-SELECTED, or an item of the receiving "
                    + "program's own extension to that area, which arrives in the same "
                    + "DFHCOMMAREA - COCRDLIC's WS-CA-SCREEN-NUM and WS-RETURN-FLAG at "
                    + "app/cbl/COCRDLIC.cbl:229-248 are that");
            mapFields = freezeNamedValues(mapFields, MAP_INPUT_FIELD, "ScreenRequest.mapFields",
                "a symbolic-map input item, which is an xxxI name such as USRIDINI or FNAMEI. The "
                    + "xxxL, xxxF and xxxA items are length, flag and attribute metadata and are "
                    + "not payload fields");
            forcedOutcomes = freezeForcedOutcomes(forcedOutcomes);
        }

        @JsonIgnore
        public LocalDateTime pinnedClockAt() {
            return pinnedClock == null ? null : LocalDateTime.parse(pinnedClock);
        }

        @Override
        public String toString() {
            return "ScreenRequest[eibcalen=" + eibcalen + ", aid=" + aid
                + ", pinnedClock=" + pinnedClock + ", charset=" + charset
                + ", commarea=" + commarea.keySet()
                + ", mapFields=" + mapFields.keySet()
                + ", forcedOutcomes=" + forcedOutcomeKeys() + ']';
        }

        private List<String> forcedOutcomeKeys() {
            List<String> keys = new ArrayList<>(forcedOutcomes.size());
            for (RepositoryOperation operation : forcedOutcomes.keySet()) {
                keys.add(operation.key());
            }
            return keys;
        }
    }

    /**
     * A repository outcome a case forces, so that an {@code EVALUATE WS-RESP-CD} arm no seeded data can
     * reach is still driven.
     *
     * @param outcome which arm to take, named with the vocabulary {@code common.FileStatus} already defines
     *     so that the case and the production code agree on what an outcome is
     * @param resp the {@code RESP} value the forced outcome reports, or {@code null} to let the harness use
     *     the outcome's own conventional value
     * @param resp2 the {@code RESP2} value, or {@code null} for zero
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"outcome", "resp", "resp2"})
    public record ForcedOutcome(
        @JsonProperty("outcome") FileStatus.Outcome outcome,
        @JsonProperty("resp") Integer resp,
        @JsonProperty("resp2") Integer resp2) {
        public ForcedOutcome {
            Objects.requireNonNull(outcome, "ForcedOutcome.outcome is required: name one of the "
                + "FileStatus.Outcome constants - OK, END_OF_FILE, NOT_FOUND, DUPLICATE or OTHER - "
                + "so the case and the repository agree on what outcome is being forced");
            if (resp != null && resp < 0) {
                throw new IllegalArgumentException("ForcedOutcome.resp is " + resp
                    + "; a CICS RESP value is never negative");
            }
            if (resp2 != null && resp2 < 0) {
                throw new IllegalArgumentException("ForcedOutcome.resp2 is " + resp2
                    + "; a CICS RESP2 value is never negative");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({
        "callSiteOutcomes", "operationScript", "linkage", "stepStatuses", "environment"})
    public record UnitStimulus(
        @JsonProperty("callSiteOutcomes") Map<String, CallSiteOutcome> callSiteOutcomes,
        @JsonProperty("operationScript") List<ScriptedOperation> operationScript,
        @JsonProperty("linkage") Map<String, String> linkage,
        @JsonProperty("stepStatuses") Map<String, Integer> stepStatuses,
        @JsonProperty("environment") Map<String, String> environment) {
        public static final Set<String> PERMITTED_ENVIRONMENT_KEYS = Set.of(
            "NULL_UCB_DDS", "MENU_TABLE_VARIANT", "DATABASE_VARIANT", "RECORDS_BEFORE_FAILURE");

        private static final Pattern CALL_SITE_NAME =
            Pattern.compile("[A-Z][A-Z0-9]*(?:[-_][A-Z0-9]+)*");

        private static final Pattern LINKAGE_NAME = Pattern.compile("[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*");

        private static final Pattern STEP_NAME = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

        public static final UnitStimulus NONE =
            new UnitStimulus(Map.of(), List.of(), Map.of(), Map.of(), Map.of());

        @JsonCreator
        public UnitStimulus {
            callSiteOutcomes = freezeCallSiteOutcomes(callSiteOutcomes);
            operationScript = operationScript == null
                ? List.of()
                : List.copyOf(operationScript);
            linkage = freezeNamedValues(linkage, LINKAGE_NAME, "linkage",
                "a COBOL data name, upper case and hyphen separated, as the copybook spells it");
            stepStatuses = freezeStepStatuses(stepStatuses);
            environment = freezeEnvironment(environment);
        }

        @JsonIgnore
        public boolean isEmpty() {
            return callSiteOutcomes.isEmpty() && operationScript.isEmpty() && linkage.isEmpty()
                && stepStatuses.isEmpty() && environment.isEmpty();
        }

        public Optional<CallSiteOutcome> callSite(String callSite) {
            return Optional.ofNullable(callSiteOutcomes.get(callSite));
        }

        /**
         * A linkage value by its COBOL data name.
         *
         * @param name the COBOL data name
         * @return the declared image, or empty when the case declares none
         */
        public Optional<String> linkageValue(String name) {
            return Optional.ofNullable(linkage.get(name));
        }

        public Optional<String> environmentValue(String key) {
            if (!PERMITTED_ENVIRONMENT_KEYS.contains(key)) {
                throw new IllegalArgumentException("Environment key '" + key + "' is not one of "
                    + new TreeSet<>(PERMITTED_ENVIRONMENT_KEYS) + ". Reading a key no case file may "
                    + "declare would always return empty, which is a control that silently does "
                    + "nothing.");
            }
            return Optional.ofNullable(environment.get(key));
        }

        public OptionalInt stepStatus(String stepName) {
            Integer declared = stepStatuses.get(stepName);
            return declared == null ? OptionalInt.empty() : OptionalInt.of(declared);
        }

        @Override
        public String toString() {
            return "UnitStimulus[callSites=" + callSiteOutcomes.keySet()
                + ", operations=" + operationScript.size()
                + ", linkage=" + linkage.keySet()
                + ", steps=" + stepStatuses.keySet()
                + ", environment=" + environment.keySet() + ']';
        }

        private static Map<String, CallSiteOutcome> freezeCallSiteOutcomes(
                Map<String, CallSiteOutcome> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, CallSiteOutcome> frozen = new LinkedHashMap<>(source.size());
            for (Map.Entry<String, CallSiteOutcome> entry : source.entrySet()) {
                String name = requireName(entry.getKey(), CALL_SITE_NAME, "callSiteOutcomes",
                    "the call site's name, upper case, as in READ-XREFFILE or CLOSE_TRNXFILE");
                frozen.put(name, Objects.requireNonNull(entry.getValue(),
                    "UnitStimulus.callSiteOutcomes['" + name + "'] is null. A call site named with no "
                        + "outcome scripts nothing, which reads as though it did."));
            }
            return Collections.unmodifiableMap(frozen);
        }

        private static Map<String, String> freezeNamedValues(Map<String, String> source,
                                                             Pattern shape,
                                                             String member,
                                                             String expected) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, String> frozen = new LinkedHashMap<>(source.size());
            for (Map.Entry<String, String> entry : source.entrySet()) {
                String name = requireName(entry.getKey(), shape, member, expected);
                frozen.put(name, Objects.requireNonNull(entry.getValue(),
                    "UnitStimulus." + member + "['" + name + "'] is null. A declared name with no "
                        + "value is indistinguishable from one the case never declared; omit the "
                        + "entry or give it the image the COBOL holds - the empty string is a legal "
                        + "image and says something different from absent."));
            }
            return Collections.unmodifiableMap(frozen);
        }

        private static Map<String, Integer> freezeStepStatuses(Map<String, Integer> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, Integer> frozen = new LinkedHashMap<>(source.size());
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                String name = requireName(entry.getKey(), STEP_NAME, "stepStatuses",
                    "a JCL step name, upper case and at most eight characters");
                Integer code = Objects.requireNonNull(entry.getValue(),
                    "UnitStimulus.stepStatuses['" + name + "'] is null; state the condition code the "
                        + "step ended with, which is 0 for a step that succeeded");
                if (code < 0) {
                    throw new IllegalArgumentException("UnitStimulus.stepStatuses['" + name + "'] is "
                        + code + "; a JCL condition code is never negative");
                }
                frozen.put(name, code);
            }
            return Collections.unmodifiableMap(frozen);
        }

        private static Map<String, String> freezeEnvironment(Map<String, String> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, String> frozen = new LinkedHashMap<>(source.size());
            for (Map.Entry<String, String> entry : source.entrySet()) {
                String key = entry.getKey();
                if (key == null || !PERMITTED_ENVIRONMENT_KEYS.contains(key)) {
                    throw new IllegalArgumentException("UnitStimulus.environment declares '" + key
                        + "', which is not one of " + new TreeSet<>(PERMITTED_ENVIRONMENT_KEYS)
                        + ". The key set is closed on purpose: an unrecognised control is one the "
                        + "adapter ignores in silence, and a case whose stimulus is ignored passes "
                        + "for the wrong reason. Add the key to PERMITTED_ENVIRONMENT_KEYS in the "
                        + "same change that teaches an adapter to honour it.");
                }
                frozen.put(key, Objects.requireNonNull(entry.getValue(),
                    "UnitStimulus.environment['" + key + "'] is null; omit the key or give it a "
                        + "value"));
            }
            return Collections.unmodifiableMap(frozen);
        }

        private static String requireName(String value, Pattern shape, String member,
                                          String expected) {
            String name = value == null ? "" : value.strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("UnitStimulus." + member + " has a blank key; "
                    + "each key is " + expected);
            }
            if (!shape.matcher(name).matches()) {
                throw new IllegalArgumentException("UnitStimulus." + member + " declares key '" + name
                    + "', which is not " + expected + ". Case files and adapters agree on these "
                    + "names, so an unconventional spelling is a control that reaches nothing.");
            }
            return name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"status", "resp", "refused", "afterRecords"})
    public record CallSiteOutcome(
        @JsonProperty("status") String status,
        @JsonProperty("resp") Integer resp,
        @JsonProperty("refused") Boolean refused,
        @JsonProperty("afterRecords") Integer afterRecords) {
        private static final Pattern FILE_STATUS = Pattern.compile("[0-9A-Z]{2}");

        @JsonCreator
        public CallSiteOutcome {
            if (status != null && !FILE_STATUS.matcher(status).matches()) {
                throw new IllegalArgumentException("CallSiteOutcome.status is '" + status
                    + "'; a COBOL FILE STATUS is exactly two characters, as in 00, 10, 23, 37 or 38");
            }
            if (resp != null && resp < 0) {
                throw new IllegalArgumentException("CallSiteOutcome.resp is " + resp
                    + "; a CICS RESP value is never negative");
            }
            if (afterRecords != null && afterRecords < 0) {
                throw new IllegalArgumentException("CallSiteOutcome.afterRecords is " + afterRecords
                    + "; a record count is never negative");
            }
            refused = refused != null && refused;
            if (status == null && resp == null && !refused) {
                throw new IllegalArgumentException("CallSiteOutcome declares neither a status, nor a "
                    + "RESP, nor a refusal, so it scripts nothing. Omit the call site instead: a "
                    + "site named with no outcome reads as though the case arranged something there.");
            }
        }

        @JsonIgnore
        public boolean isRefused() {
            return Boolean.TRUE.equals(refused);
        }

        public int recordsBefore() {
            return afterRecords == null ? 0 : afterRecords;
        }
    }

    /**
     * One operation in a called subprogram's script: the DD name, the operation code, and the key it is
     * given.
     *
     * @param dd the DD name the operation addresses, as {@code LK-M03B-DD} carries it; required
     * @param operation the operation code, one of the {@code 88}-level spellings {@code O}, {@code C},
     *     {@code R}, {@code K}, {@code W} or {@code Z}; required
     * @param key the key the operation is given, as {@code LK-M03B-KEY} carries it; {@code null} for an
     *     operation that takes none
     * @param keyLength the key length, as {@code LK-M03B-KEY-LN} carries it; {@code null} for an operation
     *     that takes no key
     * @param status the two-character value the caller hands in as {@code LK-M03B-RC} before issuing the
     *     call, or {@code null} to carry forward whatever the previous call returned
     * @param primesRecordArea whether the caller issues {@code MOVE SPACES TO WS-M03B-FLDT} before the
     *     call, as {@code app/cbl/CBSTM03A.CBL:L350}, {@code :L745} and {@code :L834} do and {@code :L857-860}
     *     conspicuously does not
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dd", "operation", "key", "keyLength", "status", "primesRecordArea"})
    public record ScriptedOperation(
        @JsonProperty("dd") String dd,
        @JsonProperty("operation") String operation,
        @JsonProperty("key") String key,
        @JsonProperty("keyLength") Integer keyLength,
        @JsonProperty("status") String status,
        @JsonProperty("primesRecordArea") Boolean primesRecordArea) {
        public static final Set<String> PERMITTED_OPERATIONS = Set.of("O", "C", "R", "K", "W", "Z");

        private static final Pattern DD_NAME = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

        @JsonCreator
        public ScriptedOperation {
            String name = dd == null ? "" : dd.strip();
            if (!DD_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("ScriptedOperation.dd is '" + dd + "'; a DD name is "
                    + "upper case, alphanumeric and at most eight characters, as LK-M03B-DD carries it");
            }
            dd = name;
            String code = operation == null ? "" : operation.strip();
            if (!PERMITTED_OPERATIONS.contains(code)) {
                throw new IllegalArgumentException("ScriptedOperation.operation is '" + operation
                    + "'; the six codes CBSTM03B declares are O (open), C (close), R (read), K (keyed "
                    + "read), W (write) and Z (rewrite)");
            }
            operation = code;
            if (keyLength != null && keyLength <= 0) {
                throw new IllegalArgumentException("ScriptedOperation.keyLength is " + keyLength
                    + "; LK-M03B-KEY-LN is the length of a key, so it is at least one. Omit it for an "
                    + "operation that takes no key.");
            }
            if (status != null && status.length() != 2) {
                throw new IllegalArgumentException("ScriptedOperation.status is '" + status
                    + "'; LK-M03B-RC is exactly two characters");
            }
        }

        public boolean primesRecordAreaOrDefault() {
            return Boolean.TRUE.equals(primesRecordArea);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "operation", "key"})
    public record ExpectedOperation(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("operation") RepositoryOperation operation,
        @JsonProperty("key") String key) {
        @JsonCreator
        public ExpectedOperation {
            dataset = requireDatasetKey(dataset, "expectedOperations.dataset");
            operation = Objects.requireNonNull(operation, "ExpectedOperation.operation is required: "
                + "name one of the RepositoryOperation constants, so the case and the repository agree "
                + "on what operation is being expected");
            key = requireNullOrText(key, "expectedOperations.key");
        }

        @Override
        public String toString() {
            return "ExpectedOperation[" + dataset + '.' + operation.key()
                + (key == null ? "" : ", keyed") + ']';
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({
        "nextProgram", "nextMapset", "nextMap", "navigation", "sends", "cursorField", "termination"})
    public record ExpectedResponse(
        @JsonProperty("nextProgram") String nextProgram,
        @JsonProperty("nextMapset") String nextMapset,
        @JsonProperty("nextMap") String nextMap,
        @JsonProperty("navigation") Map<String, String> navigation,
        @JsonProperty("sends") List<ScreenSend> sends,
        @JsonProperty("cursorField") String cursorField,
        @JsonProperty("termination") Termination termination) {
        public ExpectedResponse {
            nextProgram = requireNullOrPattern(nextProgram, PROGRAM_REFERENCE,
                "ExpectedResponse.nextProgram",
                "a CICS program name of one to eight upper-case alphanumerics, such as COSGN00C");
            nextMapset = requireNullOrPattern(nextMapset, MAP_REFERENCE,
                "ExpectedResponse.nextMapset",
                "a BMS mapset name of at most seven upper-case alphanumerics, such as COUSR02");
            nextMap = requireNullOrPattern(nextMap, MAP_REFERENCE, "ExpectedResponse.nextMap",
                "a BMS map name of at most seven upper-case alphanumerics, such as COUSR2A");
            navigation = freezeNamedValues(navigation, COMMAREA_FIELD,
                "ExpectedResponse.navigation",
                "a CARDDEMO-COMMAREA field name as app/cpy/COCOM01Y.cpy spells it");
            sends = freezeSends(sends);
            cursorField = requireNullOrPattern(cursorField, MAP_LENGTH_FIELD,
                "ExpectedResponse.cursorField",
                "the symbolic-map LENGTH item that received MOVE -1, which is an xxxL name such as "
                    + "USRIDINL or FNAMEL - COBOL positions the cursor by moving -1 into it, so the "
                    + "length item is the cursor");
            termination = Objects.requireNonNull(termination, "ExpectedResponse.termination is "
                + "required: declare XCTL when control transfers to another program, "
                + "RETURN_TRANSID when the transaction returns to itself, or RETURN_NO_TRANSID for "
                + "the bare EXEC CICS RETURN that ends the pseudo-conversation. The three are not "
                + "interchangeable - an XCTL never reaches the EXEC CICS RETURN that follows it, and "
                + "a bare RETURN names no transaction to carry the commarea back to.");
        }

        @JsonIgnore
        public int sendCount() {
            return sends.size();
        }

        @Override
        public String toString() {
            return "ExpectedResponse[nextProgram=" + nextProgram + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap + ", navigation=" + navigation.keySet()
                + ", sends=" + sends.size() + ", cursorField=" + cursorField
                + ", termination=" + termination + ']';
        }
    }

    /**
     * One {@code EXEC CICS SEND MAP}: the BMS payload it carried and the attribute metadata it set.
     *
     * @param fields the {@code xxxO} output items keyed by their symbolic-map names, each at the full
     *     declared width of its {@code PICTURE} clause
     * @param attributes the attribute items keyed by their symbolic-map names, valued with the
     *     {@code DFHBMSCA} or {@code DFHATTR} mnemonic the program moved into them - so {@code ERRMSGC} carries
     *     {@code DFHRED}
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"fields", "attributes"})
    public record ScreenSend(
        @JsonProperty("fields") Map<String, String> fields,
        @JsonProperty("attributes") Map<String, String> attributes) {
        public ScreenSend {
            fields = freezeNamedValues(fields, MAP_OUTPUT_FIELD, "ScreenSend.fields",
                "a symbolic-map output item, which is an xxxO name such as ERRMSGO or TITLE01O");
            attributes = freezeAttributes(attributes);
            if (fields.isEmpty() && attributes.isEmpty()) {
                throw new IllegalArgumentException("A ScreenSend pins nothing: declare \"fields\", "
                    + "or \"attributes\", or both. A send that asserts nothing still counts toward "
                    + "the send count, so it would inflate the apparent expectation while checking "
                    + "none of it.");
            }
        }

        @Override
        public String toString() {
            return "ScreenSend[fields=" + fields.keySet() + ", attributes=" + attributes + ']';
        }
    }

    /**
     * How an online invocation ended - the three exits CICS actually gives these programs.
     */
    public enum Termination {
        XCTL,

        RETURN_TRANSID,

        RETURN_NO_TRANSID
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "rowIndex", "fields", "expectedBytes"})
    public record ExpectedRecord(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("rowIndex") Integer rowIndex,
        @JsonProperty("fields") Map<String, String> fields,
        @JsonProperty("expectedBytes") String expectedBytes) {
        public ExpectedRecord {
            dataset = requireDatasetKey(dataset, "ExpectedRecord.dataset");
            Objects.requireNonNull(rowIndex, "ExpectedRecord.rowIndex is required and is never "
                + "defaulted for dataset " + dataset + ": state the zero-based write position or row "
                + "index the expectation addresses. An omitted member used to bind to 0, so an "
                + "expectation intended for another row silently addressed the first one - which "
                + "either passed against a record nobody meant to pin, or reported a difference "
                + "nobody could act on.");
            if (rowIndex < 0) {
                throw new IllegalArgumentException(
                    "ExpectedRecord.rowIndex is zero-based and must not be negative, but was "
                        + rowIndex + " for dataset " + dataset);
            }
            fields = freezeFields(fields, dataset);
            if (expectedBytes != null && expectedBytes.isEmpty()) {
                throw new IllegalArgumentException(
                    "ExpectedRecord.expectedBytes for dataset " + dataset + " row " + rowIndex
                        + " is zero-length; omit it entirely to pin fields only. A record image of "
                        + "all spaces is legitimate and is accepted, but an empty one is not.");
            }
            if (fields.isEmpty() && expectedBytes == null) {
                throw new IllegalArgumentException(
                    "ExpectedRecord for dataset " + dataset + " row " + rowIndex + " pins nothing: "
                        + "declare \"fields\", or \"expectedBytes\", or both. An expectation that "
                        + "asserts nothing always passes, which is worse than having no expectation.");
            }
        }

        @Override
        public String toString() {
            return "ExpectedRecord[" + dataset + " row " + rowIndex + ", fields=" + fields.keySet()
                + ", expectedBytes=" + (expectedBytes == null ? "absent"
                    : expectedBytes.length() + " char(s)") + ']';
        }
    }

    public enum DatasetChannel {
        WRITES,

        FINAL_STATE
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "channel", "rowCount", "recordLength"})
    public record ExpectedDataset(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("channel") DatasetChannel channel,
        @JsonProperty("rowCount") Integer rowCount,
        @JsonProperty("recordLength") Integer recordLength) {
        public ExpectedDataset {
            dataset = requireDatasetKey(dataset, "ExpectedDataset.dataset");
            Objects.requireNonNull(channel, "ExpectedDataset.channel is required for dataset "
                + dataset + ": declare WRITES for what the unit wrote, or FINAL_STATE for what the "
                + "dataset holds afterwards. A dataset commonly appears on both with different "
                + "counts, so the two cannot be conflated.");
            Objects.requireNonNull(rowCount, "ExpectedDataset.rowCount is required for dataset "
                + dataset + " on the " + channel + " channel and is never defaulted: state the exact "
                + "number of rows, which is 0 when the assertion is that the dataset exists and "
                + "produced nothing. An omitted member binding to 0 would make that assertion by "
                + "accident, and it is the strongest one this type can make.");
            if (rowCount < 0) {
                throw new IllegalArgumentException("ExpectedDataset.rowCount is " + rowCount
                    + " for dataset " + dataset + "; a dataset cannot hold a negative number of rows");
            }
            if (recordLength != null && recordLength < 1) {
                throw new IllegalArgumentException("ExpectedDataset.recordLength is " + recordLength
                    + " for dataset " + dataset + ", which is not a record width; a record occupies at "
                    + "least 1 byte. Omit the member to leave the width unasserted.");
            }
        }

        public static ExpectedDataset empty(String dataset, DatasetChannel channel,
                                            int recordLength) {
            return new ExpectedDataset(dataset, channel, 0, recordLength);
        }

        public static ExpectedDataset of(String dataset, DatasetChannel channel, int rowCount) {
            return new ExpectedDataset(dataset, channel, rowCount, null);
        }

        @Override
        public String toString() {
            return "ExpectedDataset[" + dataset + " on " + channel + ", rowCount=" + rowCount
                + ", recordLength=" + (recordLength == null ? "unasserted" : recordLength) + ']';
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"channel", "text"})
    public record EmittedMessage(
        @JsonProperty("channel") MessageChannel channel,
        @JsonProperty("text") String text) {
        public EmittedMessage {
            Objects.requireNonNull(channel, "EmittedMessage.channel is required: declare "
                + "DISPLAY_LINE for a COBOL DISPLAY, WS_MESSAGE_80 for the 80-byte working-storage "
                + "message field, or SCREEN_ERRMSG_78 for the 78-byte screen field it is truncated "
                + "into. The three have different widths, so an unchannelled expectation cannot be "
                + "compared.");
            Objects.requireNonNull(text, "EmittedMessage.text is required; use an empty string for a "
                + "blank DISPLAY line rather than omitting the key");
            if (channel.fixedWidth() > 0 && text.length() != channel.fixedWidth()) {
                throw new IllegalArgumentException("EmittedMessage on channel " + channel
                    + " must be exactly " + channel.fixedWidth() + " character(s) because "
                    + channel.declaration() + ", but this one is " + text.length()
                    + ". Write the expectation out to the full width - space-padded on the right, "
                    + "which is what COBOL moves into an alphanumeric receiver - or declare "
                    + MessageChannel.DISPLAY_LINE + " if the line really is variable width.");
            }
        }

        @Override
        public String toString() {
            return "EmittedMessage[" + channel + ", len=" + text.length() + ", '"
                + Redaction.maskIfSensitiveText(text) + "']";
        }
    }

    public enum MessageChannel {
        DISPLAY_LINE(0, "a COBOL DISPLAY is as wide as the operands it concatenates"),

        WS_MESSAGE_80(80, "WS-MESSAGE is declared PIC X(80) in the program's WORKING-STORAGE"),

        SCREEN_ERRMSG_78(78, "ERRMSGO is declared PIC X(78) in the symbolic map, so the 80-byte "
            + "WS-MESSAGE is truncated by two bytes on its way to the screen");

        private final int fixedWidth;

        private final String declaration;

        MessageChannel(int fixedWidth, String declaration) {
            this.fixedWidth = fixedWidth;
            this.declaration = declaration;
        }

        public int fixedWidth() {
            return fixedWidth;
        }

        /**
         * The COBOL declaration the width is taken from.
         *
         * @return the declaration, for quoting in a failure message
         */
        public String declaration() {
            return declaration;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "kind"})
    public record DatasetNormalisation(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("kind") Normalisation kind) {
        public DatasetNormalisation {
            dataset = requireDatasetKey(dataset, "DatasetNormalisation.dataset");
            Objects.requireNonNull(kind, "DatasetNormalisation.kind is required: name one of the "
                + "two recorded deviations, CARDXREF_FILLER_PAD_36_TO_50 or "
                + "USRSEC_FILLER_PAD_57_TO_80. There is no third, and a width mismatch that is not "
                + "one of these two is a defect rather than something to pad away.");
            if (!kind.describes(dataset)) {
                throw new IllegalArgumentException("DatasetNormalisation binds " + kind
                    + " to dataset " + dataset + ", which that normalisation does not describe. "
                    + kind + " supplies " + kind.copybook() + "'s absent " + kind.absentSpan()
                    + ", so it is meaningful only for the DD names that reach that record: "
                    + kind.datasets() + ". Two of this migration's datasets are 50 bytes wide - "
                    + "CVACT03Y's cross-reference and CVTRA01Y's transaction category balance - so a "
                    + "pad authorised by a width pair alone would silently supply a trailing span to "
                    + "a record whose copybook has no such span at all, which is exactly the class of "
                    + "error this harness exists to catch.");
            }
        }

        public String normaliseSeedRow(String row) {
            return kind.normaliseSeedRow(row, dataset);
        }

        public List<String> normaliseSeedRows(List<String> rows) {
            Objects.requireNonNull(rows, "Rows are required to normalise; pass an empty list for a "
                + "dataset with no seeded row");
            List<String> normalised = new ArrayList<>(rows.size());
            for (String row : rows) {
                normalised.add(normaliseSeedRow(row));
            }
            return Collections.unmodifiableList(normalised);
        }
    }

    /**
     * The two recorded deviations between a shipped fixture and the copybook that describes it.
     */
    public enum Normalisation {
        CARDXREF_FILLER_PAD_36_TO_50(36, 50, "CVACT03Y", "FILLER PIC X(14)",
            Set.of("CCXREF", "CXACAIX", "XREFFILE", "XREFFIL1", "CARDXREF")),

        USRSEC_FILLER_PAD_57_TO_80(57, 80, "CSUSR01Y", "SEC-USR-FILLER PIC X(23)",
            Set.of("USRSEC"));

        private static final char PAD_CHARACTER = ' ';

        private final int sourceWidth;

        private final int targetWidth;

        private final String copybook;

        private final String absentSpan;

        private final Set<String> datasets;

        Normalisation(int sourceWidth, int targetWidth, String copybook, String absentSpan,
                      Set<String> datasets) {
            this.sourceWidth = sourceWidth;
            this.targetWidth = targetWidth;
            this.copybook = copybook;
            this.absentSpan = absentSpan;
            this.datasets = Set.copyOf(datasets);
        }

        public int sourceWidth() {
            return sourceWidth;
        }

        /**
         * The copybook-declared record width the fixture must be padded to.
         *
         * @return the width in bytes after normalisation
         */
        public int targetWidth() {
            return targetWidth;
        }

        /**
         * The copybook that declares the authoritative width.
         *
         * @return the {@code app/cpy} member name, without extension
         */
        public String copybook() {
            return copybook;
        }

        /**
         * The COBOL declaration of the span the fixture omits.
         *
         * @return the absent span's name and picture clause
         */
        public String absentSpan() {
            return absentSpan;
        }

        public int padWidth() {
            return targetWidth - sourceWidth;
        }

        public Set<String> datasets() {
            return datasets;
        }

        public boolean describes(String dataset) {
            Objects.requireNonNull(dataset,
                "A dataset binding key is required to decide whether " + name() + " applies to it");
            return datasets.contains(dataset);
        }

        public Set<String> eligibleDatasets() {
            return datasets;
        }

        public boolean appliesTo(String dataset) {
            return describes(dataset);
        }

        public String normaliseSeedRow(String row, String dataset) {
            Objects.requireNonNull(row, "A seeded row is required to normalise it; a row the case "
                + "does not declare must be absent from \"rows\" rather than null");
            if (row.length() == targetWidth) {
                return row;
            }
            if (row.length() != sourceWidth) {
                throw new IllegalArgumentException("Row of " + row.length() + " character(s) seeded "
                    + "into dataset " + dataset + " cannot be normalised by " + name()
                    + ", which pads " + sourceWidth + " to " + targetWidth + " by supplying "
                    + copybook + "'s absent " + absentSpan + ". A row that is neither width is a "
                    + "defect in the case: padding an arbitrary width up to the copybook's would "
                    + "hide exactly the class of error this harness exists to catch.");
            }
            StringBuilder padded = new StringBuilder(targetWidth).append(row);
            while (padded.length() < targetWidth) {
                padded.append(PAD_CHARACTER);
            }
            return padded.toString();
        }
    }

    public static final class Redaction {
        public static final String MASK = "<redacted>";

        public enum Sensitivity {
            CREDENTIAL,

            PERSONAL,

            IDENTIFIER,

            PUBLIC
        }

        public static final Set<String> CREDENTIAL_FIELDS =
            Set.of("SEC-USR-PWD", "PASSWDI", "PASSWDO");

        public static final Set<String> PERSONAL_FIELDS = personalFields();

        public static final Set<String> IDENTIFIER_FIELDS = identifierFields();

        public static final Set<String> CREDENTIAL_LITERALS = Set.of("PASSWORD");

        private static final Pattern LABELLED_CREDENTIAL = Pattern.compile(
            "(?i)\\b(pass(?:word|wd)?|pwd|secret|credential|token)(\\s*[:=]\\s*)(\\S+)");

        public static final int MAX_DIAGNOSTIC_LENGTH = 320;

        public static final String TRUNCATION_MARKER = "... (truncated)";

        public static final String NO_MESSAGE = "(no message)";

        public static final int DIGEST_LENGTH = 8;

        public static final String SENSITIVE_DATASET = "USRSEC";

        public static final int SENSITIVE_SPAN_OFFSET = 48;

        public static final int SENSITIVE_SPAN_LENGTH = 8;

        public static final int SENSITIVE_RECORD_LENGTH = 80;

        private Redaction() {
            throw new AssertionError("Redaction is a policy holder and is never instantiated");
        }

        public static Sensitivity classify(String fieldName) {
            if (fieldName == null) {
                return Sensitivity.PUBLIC;
            }
            if (CREDENTIAL_FIELDS.contains(fieldName)) {
                return Sensitivity.CREDENTIAL;
            }
            if (PERSONAL_FIELDS.contains(fieldName)) {
                return Sensitivity.PERSONAL;
            }
            if (IDENTIFIER_FIELDS.contains(fieldName)) {
                return Sensitivity.IDENTIFIER;
            }
            return Sensitivity.PUBLIC;
        }

        public static boolean sensitiveField(String fieldName) {
            return classify(fieldName) != Sensitivity.PUBLIC;
        }

        public static boolean credentialField(String fieldName) {
            return classify(fieldName) == Sensitivity.CREDENTIAL;
        }

        public static String maskFieldValue(String fieldName, String value) {
            if (value == null) {
                return null;
            }
            return switch (classify(fieldName)) {
                case CREDENTIAL -> MASK + "(len=" + value.length() + ')';
                case PERSONAL -> "<personal>(len=" + value.length() + ",sha256=" + digest(value)
                    + ')';
                case IDENTIFIER -> "<identifier>(len=" + value.length() + ",sha256=" + digest(value)
                    + ')';
                case PUBLIC -> value;
            };
        }

        public static String maskRecordImage(String dataset, String image) {
            if (image == null || !SENSITIVE_DATASET.equals(dataset)) {
                return image;
            }
            if (image.length() <= SENSITIVE_SPAN_OFFSET) {
                return image;
            }
            int maskEnd = image.length() == SENSITIVE_RECORD_LENGTH
                ? SENSITIVE_SPAN_OFFSET + SENSITIVE_SPAN_LENGTH
                : image.length();
            return image.substring(0, SENSITIVE_SPAN_OFFSET)
                + "*".repeat(maskEnd - SENSITIVE_SPAN_OFFSET)
                + image.substring(maskEnd);
        }

        public record Span(String fieldName, int offset, int length) {
            public Span {
                Objects.requireNonNull(fieldName, "A Span needs the name it is addressed by: the "
                    + "name is what Redaction classifies, so a span without one cannot be masked");
                if (offset < 0) {
                    throw new IllegalArgumentException("Span " + fieldName + " has offset " + offset
                        + ", which is not a position within a record");
                }
                if (length < 1) {
                    throw new IllegalArgumentException("Span " + fieldName + " has length " + length
                        + "; a span occupies at least one byte");
                }
            }
        }

        public static String maskImage(String image, List<Span> spans) {
            Objects.requireNonNull(spans, "The spans of the record are required to mask it; pass an "
                + "empty list when no layout is known and use maskRecordImage(String, String) "
                + "instead, which falls back to the one dataset whose geometry is fixed here");
            if (image == null || image.isEmpty()) {
                return image;
            }
            char[] masked = null;
            for (Span span : spans) {
                if (!sensitiveField(span.fieldName()) || span.offset() >= image.length()) {
                    continue;
                }
                if (masked == null) {
                    masked = image.toCharArray();
                }
                int end = Math.min(span.offset() + span.length(), image.length());
                for (int index = span.offset(); index < end; index++) {
                    masked[index] = '*';
                }
            }
            return masked == null ? image : new String(masked);
        }

        public static String maskUnlocatedImage(String image) {
            if (image == null) {
                return null;
            }
            return "<image>(len=" + image.length() + ",sha256=" + digest(image) + ')';
        }

        public static String maskIfSensitiveText(String text) {
            if (text == null) {
                return null;
            }
            if (CREDENTIAL_LITERALS.contains(text.strip())) {
                return MASK;
            }
            return scrubCredentialLiterals(text);
        }

        public static String sanitiseDiagnostic(String text) {
            if (text == null || text.isBlank()) {
                return NO_MESSAGE;
            }
            String scrubbed = LABELLED_CREDENTIAL.matcher(scrubCredentialLiterals(text))
                .replaceAll(match -> Matcher.quoteReplacement(
                    match.group(1) + match.group(2) + MASK));
            if (scrubbed.length() <= MAX_DIAGNOSTIC_LENGTH) {
                return scrubbed;
            }
            return scrubbed.substring(0, MAX_DIAGNOSTIC_LENGTH) + TRUNCATION_MARKER;
        }

        public static String describeThrowable(Throwable failure) {
            if (failure == null) {
                return NO_MESSAGE;
            }
            return failure.getClass().getName() + ": " + sanitiseDiagnostic(failure.getMessage());
        }

        public static final class SanitisedCause extends RuntimeException {
            private static final long serialVersionUID = 1L;

            private final String originalType;

            private SanitisedCause(Throwable original) {
                super(describeThrowable(original), null, true, true);
                this.originalType = original.getClass().getName();
                setStackTrace(original.getStackTrace());
            }

            public String originalType() {
                return originalType;
            }
        }

        public static SanitisedCause sanitisedCause(Throwable failure) {
            Objects.requireNonNull(failure, "A throwable is required: there is nothing to sanitise "
                + "otherwise, and a null cause is spelled by passing no cause at all");
            return new SanitisedCause(failure);
        }

        private static String scrubCredentialLiterals(String text) {
            String scrubbed = text;
            for (String literal : CREDENTIAL_LITERALS) {
                if (scrubbed.contains(literal)) {
                    scrubbed = scrubbed.replace(literal, MASK);
                }
            }
            return scrubbed;
        }

        private static String digest(String value) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(DIGEST_LENGTH);
                for (int index = 0; hex.length() < DIGEST_LENGTH && index < hash.length; index++) {
                    hex.append(Character.forDigit((hash[index] >> 4) & 0xF, 16));
                    hex.append(Character.forDigit(hash[index] & 0xF, 16));
                }
                return hex.substring(0, DIGEST_LENGTH);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required to render a masked value and is "
                    + "mandated by the platform, so its absence is an unusable JVM", impossible);
            }
        }

        private static Set<String> personalFields() {
            Set<String> names = new LinkedHashSet<>();
            names.addAll(List.of(
                "CUST-FIRST-NAME", "CUST-MIDDLE-NAME", "CUST-LAST-NAME",
                "CUST-ADDR-LINE-1", "CUST-ADDR-LINE-2", "CUST-ADDR-LINE-3", "CUST-ADDR-ZIP",
                "CUST-PHONE-NUM-1", "CUST-PHONE-NUM-2",
                "CUST-SSN", "CUST-GOVT-ISSUED-ID",
                "CUST-DOB-YYYY-MM-DD", "CUST-DOB-YYYYMMDD",
                "CUST-EFT-ACCOUNT-ID", "CUST-FICO-CREDIT-SCORE"));
            names.addAll(List.of("SEC-USR-FNAME", "SEC-USR-LNAME", "CARD-EMBOSSED-NAME"));
            for (String stem : List.of("FNAME", "LNAME", "MIDNAME", "CRDNAME", "ADDRLN1", "ADDRLN2",
                "ADDRLN3", "ADDRZIP", "ACTPHNUM1", "ACTPHNUM2", "SSN", "SSN1", "SSN2", "SSN3", "DOB",
                "DOBYEAR", "DOBMON", "DOBDAY", "FICOSCR", "EFTAC")) {
                names.add(stem + 'I');
                names.add(stem + 'O');
            }
            for (int row = 1; row <= 10; row++) {
                String ordinal = row < 10 ? "0" + row : Integer.toString(row);
                names.add("FNAME" + ordinal + 'I');
                names.add("FNAME" + ordinal + 'O');
                names.add("LNAME" + ordinal + 'I');
                names.add("LNAME" + ordinal + 'O');
            }
            return Collections.unmodifiableSet(names);
        }

        private static Set<String> identifierFields() {
            Set<String> names = new LinkedHashSet<>();
            names.addAll(List.of(
                "ACCT-ID", "CARD-NUM", "CARD-ACCT-ID",
                "XREF-CARD-NUM", "XREF-ACCT-ID", "XREF-CUST-ID",
                "CUST-ID", "TRAN-CARD-NUM", "DALYTRAN-CARD-NUM", "TRNX-CARD-NUM",
                "SEC-USR-ID"));
            for (String stem : List.of("ACCTSID", "CARDSID", "CARDNUM", "USRIDIN", "XREFNBR")) {
                names.add(stem + 'I');
                names.add(stem + 'O');
            }
            for (int row = 1; row <= 10; row++) {
                String ordinal = row < 10 ? "0" + row : Integer.toString(row);
                names.add("USRID" + ordinal + 'I');
                names.add("USRID" + ordinal + 'O');
            }
            names.addAll(List.of(
                "CDEMO-USER-ID", "CDEMO-ACCT-ID", "CDEMO-CARD-NUM", "CDEMO-CUST-ID",
                "CDEMO-CU01-USR-SELECTED", "CDEMO-CU02-USR-SELECTED",
                "CDEMO-CU03-USR-SELECTED"));
            return Collections.unmodifiableSet(names);
        }
    }

    private static String requireText(String value, String member) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "ParityCase." + member + " is required and must not be blank");
        }
        return value;
    }

    private static String requireNullOrText(String value, String member) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                member + " must not be blank when present; omit the key entirely instead");
        }
        return value;
    }

    private static String requireNullOrPattern(String value,
                                               Pattern pattern,
                                               String member,
                                               String expectation) {
        String present = requireNullOrText(value, member);
        if (present != null && !pattern.matcher(present).matches()) {
            throw new IllegalArgumentException(member + " must be " + expectation + ", but was \""
                + present + "\"");
        }
        return present;
    }

    private static String requireProgramName(String value) {
        String name = requireText(value, "program");
        if (!PROGRAM_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "ParityCase.program must be the COBOL program name in UPPER case - exactly eight "
                    + "alphanumeric characters beginning with a letter, such as \"CBACT04C\" - "
                    + "because it is also the parity/<PROGRAM>/ resource directory segment. Got: \""
                    + name + "\"");
        }
        return name;
    }

    private static String requireCaseId(String value) {
        String id = requireText(value, "caseId");
        if (!CASE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                "ParityCase.caseId must be \"case01\" through \"case20\" - twenty cases per program, "
                    + "560 in total - and must match the fixture file name stem. Note the mandatory "
                    + "two-digit form: \"case1\" and \"case021\" are both rejected. Got: \"" + id
                    + "\"");
        }
        return id;
    }

    private static String requireDatasetKey(String value, String member) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(member + " is required and must not be blank");
        }
        String key = value;
        if (!DATASET_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                member + " must be a dataset binding key as declared under carddemo.datasets in "
                    + "application.yml - one to eight upper-case alphanumeric characters beginning "
                    + "with a letter, such as \"ACCTDAT\", \"TCATBALF\" or \"XREFFIL1\". A literal "
                    + "dataset name must never appear in a case, so that no dataset name is written "
                    + "into source at all. Got: \"" + key + "\"");
        }
        return key;
    }

    private static int requireReturnCode(Integer value) {
        Objects.requireNonNull(value, "ParityCase.expectedReturnCode is required and is never "
            + "defaulted: state the RETURN-CODE the run ends with - 0 for a normal completion, or 3, "
            + "4, 8, 12 or 16 for the paths this system actually produces. An omitted member used to "
            + "bind to 0, which is the most common correct answer and therefore the one wrong value "
            + "hardest to notice: a case expecting an abend would have asserted a normal completion "
            + "and passed.");
        if (value < 0 || value > MAX_RETURN_CODE) {
            throw new IllegalArgumentException(
                "ParityCase.expectedReturnCode must be between 0 and " + MAX_RETURN_CODE
                    + " inclusive, the range a z/OS step return code can carry, but was " + value);
        }
        return value;
    }

    private static String requireNullOrAid(String value) {
        String aid = requireNullOrText(value, "ScreenRequest.aid");
        if (aid != null && !AID_MNEMONICS.contains(aid)) {
            throw new IllegalArgumentException("ScreenRequest.aid is \"" + aid + "\", which is not a "
                + "DFHAID mnemonic. DFHAID is IBM-supplied and absent from this repository, so "
                + "common.CicsAid is the single reproduction of it and the permitted set comes from "
                + "there: " + String.join(", ", new TreeSet<>(AID_MNEMONICS)) + '.');
        }
        return aid;
    }

    private static String requireNullOrInstant(String value) {
        String instant = requireNullOrText(value, "ScreenRequest.pinnedClock");
        if (instant == null) {
            return null;
        }
        try {
            LocalDateTime.parse(instant);
        } catch (DateTimeParseException notAnInstant) {
            throw new IllegalArgumentException("ScreenRequest.pinnedClock must be an ISO-8601 local "
                + "date-time such as \"2022-07-19T23:12:34\", because the header fields a case "
                + "asserts are derived from it - CURDATE as mm/dd/yy and CURTIME as hh:mm:ss. Got: \""
                + instant + "\" (" + notAnInstant.getMessage() + ')', notAnInstant);
        }
        return instant;
    }

    private static String requireNullOrCharset(String value) {
        String charset = requireNullOrText(value, "ScreenRequest.charset");
        if (charset != null && !PERMITTED_CHARSETS.contains(charset)) {
            throw new IllegalArgumentException("ScreenRequest.charset is \"" + charset + "\", but the "
                + "only code pages in this system are " + String.join(" and ",
                    new TreeSet<>(PERMITTED_CHARSETS))
                + " - IBM037 for app/data/EBCDIC and US-ASCII for the nine ASCII fixtures. Any "
                + "other charset would load happily and then compare bytes no dataset here carries, "
                + "and the platform default is never one of them.");
        }
        return charset;
    }

    private static Map<String, DatasetInput> freezeInputs(Map<String, DatasetInput> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, DatasetInput> copy = new LinkedHashMap<>();
        for (Map.Entry<String, DatasetInput> entry : source.entrySet()) {
            String key = requireDatasetKey(entry.getKey(), "ParityCase.inputs key");
            DatasetInput input = entry.getValue();
            if (input == null) {
                throw new IllegalArgumentException(
                    "ParityCase.inputs[\"" + key + "\"] must declare rows or a fixture, but was null");
            }
            copy.put(key, input);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> freezeJobParameters(Map<String, String> source,
                                                           UnitKind unitKind) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        if (unitKind != UnitKind.BATCH_JOB) {
            throw new IllegalArgumentException("ParityCase.jobParameters declares "
                + source.keySet() + " but unitKind is " + unitKind + ", which takes no job "
                + "parameter. A job parameter comes from a JCL PARM; an online invocation belongs in "
                + "\"screenRequest\" and an expectation belongs in \"expectedResponse\", "
                + "\"expectedWrites\", \"expectedFinalState\" or \"expectedMessages\". Parking an "
                + "expected value here would feed it back in as an input and make the assertion "
                + "tautological.");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = requireText(entry.getKey(), "jobParameters key");
            if (name.toLowerCase(Locale.ROOT).startsWith("expected")) {
                throw new IllegalArgumentException("ParityCase.jobParameters[\"" + name + "\"] names "
                    + "an expectation, not a job parameter. An expected value declared among the "
                    + "inputs is fed back into the unit and then compared against itself, so the "
                    + "case passes by construction. Move it to \"expectedResponse\", "
                    + "\"expectedWrites\", \"expectedFinalState\" or \"expectedMessages\".");
            }
            if (!JOB_PARAMETER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("ParityCase.jobParameters[\"" + name + "\"] must "
                    + "be a lower camel case job parameter name such as \"parmDate\", which is what "
                    + "a Spring Batch JobParameter is called. A hyphen, a dot or an upper-case "
                    + "initial almost always means a COBOL field, an EIB field or a screen field, "
                    + "and all three belong in \"screenRequest\".");
            }
            String value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(
                    "ParityCase.jobParameters[\"" + name + "\"] must carry a value; a parameter with "
                        + "no value must be omitted rather than declared null");
            }
            copy.put(name, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <T> T requireOnlineMember(T member,
                                             UnitKind unitKind,
                                             String name,
                                             String carries) {
        if (unitKind == UnitKind.CONTROLLER_POJO && member == null) {
            throw new IllegalArgumentException("ParityCase." + name + " is required for a "
                + UnitKind.CONTROLLER_POJO + " case: it carries " + carries + ". An online "
                + "program's observable behaviour is mostly its response - several paths write no "
                + "dataset at all - so a case that could not state one would leave the wrong "
                + "nextProgram, the wrong screen text or the wrong cursor undetected while still "
                + "reporting a diff count of zero.");
        }
        if (unitKind == UnitKind.BATCH_JOB && member != null) {
            throw new IllegalArgumentException("ParityCase." + name + " is declared but unitKind is "
                + UnitKind.BATCH_JOB + ", which has no screen. Remove it: a batch job's observable "
                + "behaviour is its writes, its return code and the lines it displays.");
        }
        return member;
    }

    private static List<ExpectedRecord> freezeExpectedRecords(List<ExpectedRecord> source,
                                                             String member) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) == null) {
                throw new IllegalArgumentException(
                    "ParityCase." + member + '[' + i + "] is null; remove the entry rather than "
                        + "leaving a hole in the assertion order");
            }
        }
        return List.copyOf(source);
    }

    private static List<EmittedMessage> freezeExpectedMessages(List<EmittedMessage> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) == null) {
                throw new IllegalArgumentException(
                    "ParityCase.expectedMessages[" + i + "] is null; declare an EmittedMessage with "
                        + "an empty text on the " + MessageChannel.DISPLAY_LINE + " channel for a "
                        + "blank emitted line, and remove the entry if no line is expected there");
            }
        }
        return List.copyOf(source);
    }

    private static List<ScreenSend> freezeSends(List<ScreenSend> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) == null) {
                throw new IllegalArgumentException("ExpectedResponse.sends[" + i + "] is null; "
                    + "remove the entry rather than leaving a hole in the send order, because the "
                    + "position of a send within the sequence is part of the expectation");
            }
        }
        return List.copyOf(source);
    }

    private static List<DatasetNormalisation> freezeNormalisations(
        List<DatasetNormalisation> source, Map<String, DatasetInput> inputs) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < source.size(); i++) {
            DatasetNormalisation normalisation = source.get(i);
            if (normalisation == null) {
                throw new IllegalArgumentException(
                    "ParityCase.normalisations[" + i + "] is null; remove the entry instead");
            }
            if (!inputs.containsKey(normalisation.dataset())) {
                throw new IllegalArgumentException("ParityCase.normalisations[" + i + "] normalises "
                    + "dataset " + normalisation.dataset() + ", which this case does not seed. A "
                    + "normalisation pads the rows seeded into one named dataset, so a declaration "
                    + "for a dataset that is never seeded can never fire - it is a leftover from a "
                    + "copied case or a misunderstanding of what the pad does. Seeded here: "
                    + inputs.keySet() + '.');
            }
            if (inputs.get(normalisation.dataset()).declaredEmpty()) {
                throw new IllegalArgumentException("ParityCase.normalisations[" + i + "] normalises "
                    + "dataset " + normalisation.dataset() + ", which this case declares empty. A "
                    + "normalisation pads seeded rows to the copybook width and an empty dataset has "
                    + "no row to pad, so the declaration can never fire - the same defect as naming a "
                    + "dataset the case does not seed at all. The empty declaration already carries "
                    + "the width, which is what the pad would otherwise have supplied.");
            }
            String identity = normalisation.dataset() + ':' + normalisation.kind();
            if (!seen.add(identity)) {
                throw new IllegalArgumentException("ParityCase.normalisations declares "
                    + normalisation.kind() + " for dataset " + normalisation.dataset()
                    + " more than once. The pad is applied once and is idempotent, so a second "
                    + "declaration changes nothing and means the case was edited by hand twice.");
            }
        }
        return List.copyOf(source);
    }

    private static List<ExpectedOperation> freezeExpectedOperations(
            List<ExpectedOperation> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index) == null) {
                throw new IllegalArgumentException("ParityCase.expectedOperations[" + index
                    + "] is null. The list is ordered and positional, so a hole in it would shift "
                    + "every operation after it onto the wrong expectation.");
            }
        }
        return List.copyOf(source);
    }

    private static List<ExpectedDataset> freezeExpectedDatasets(List<ExpectedDataset> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < source.size(); i++) {
            ExpectedDataset expectation = source.get(i);
            if (expectation == null) {
                throw new IllegalArgumentException(
                    "ParityCase.expectedDatasets[" + i + "] is null; remove the entry instead");
            }
            String identity = expectation.dataset() + ':' + expectation.channel();
            if (!seen.add(identity)) {
                throw new IllegalArgumentException("ParityCase.expectedDatasets declares dataset "
                    + expectation.dataset() + " on the " + expectation.channel() + " channel more "
                    + "than once. Two row counts for one dataset on one channel cannot both hold, so "
                    + "one of them is an assertion the case appears to make and nothing checks.");
            }
        }
        return List.copyOf(source);
    }

    public static final String FIXTURE_ROOT = "fixtures/";

    public static final Set<String> FIXTURE_NAMES = Set.of(
        "acctdata.txt",
        "carddata.txt",
        "cardxref.txt",
        "custdata.txt",
        "dailytran.txt",
        "discgrp.txt",
        "tcatbal.txt",
        "trancatg.txt",
        "trantype.txt");

    private static String requireFixtureName(String fixture) {
        if (fixture == null) {
            return null;
        }
        if (fixture.isBlank()) {
            throw new IllegalArgumentException(
                "DatasetInput.fixture is present but blank; omit it entirely and declare \"rows\" "
                    + "instead, or name one of the fixtures " + sortedFixtureNames());
        }
        if (!fixture.equals(fixture.strip())) {
            throw new IllegalArgumentException(
                "DatasetInput.fixture \"" + fixture + "\" is padded with whitespace; a resource name "
                    + "is matched exactly, so write it without surrounding blanks");
        }
        for (String forbidden : FORBIDDEN_IN_FIXTURE_NAME) {
            if (fixture.contains(forbidden)) {
                throw new IllegalArgumentException(
                    "DatasetInput.fixture must be a bare file name with "
                        + (SEPARATORS_IN_FIXTURE_NAME.contains(forbidden)
                            ? "no path separator"
                            : "no parent-directory segment or a scheme or drive separator")
                        + ", but \"" + fixture + "\" contains \"" + forbidden + "\". Every fixture is "
                        + "resolved beneath the classpath directory " + FIXTURE_ROOT + ", and a name "
                        + "carrying a path separator, a dot segment or a scheme separator could "
                        + "address something outside it. Name one of " + sortedFixtureNames()
                        + " instead.");
            }
        }
        if (!FIXTURE_NAMES.contains(fixture)) {
            throw new IllegalArgumentException(
                "DatasetInput.fixture \"" + fixture + "\" is not one of the nine fixtures derived "
                    + "from app/data/ASCII. The available names are " + sortedFixtureNames()
                    + ". A case that needs data no fixture holds declares it inline through "
                    + "\"rows\" rather than naming a new file.");
        }
        return fixture;
    }

    private static final List<String> FORBIDDEN_IN_FIXTURE_NAME =
        List.of("/", "\\", "..", "./", ":");

    private static final Set<String> SEPARATORS_IN_FIXTURE_NAME = Set.of("/", "\\");

    private static String sortedFixtureNames() {
        return FIXTURE_NAMES.stream().sorted().collect(Collectors.joining(", ", "[", "]"));
    }

    private static List<String> freezeRows(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) == null) {
                throw new IllegalArgumentException(
                    "DatasetInput.rows[" + i + "] is null; every inline row must carry its record "
                        + "image verbatim");
            }
        }
        return List.copyOf(source);
    }

    private static Map<String, String> freezeFields(Map<String, String> source, String dataset) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                    "ExpectedRecord.fields for dataset " + dataset + " declares a blank field name; "
                        + "use the copybook field name verbatim");
            }
            String value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(
                    "ExpectedRecord.fields[\"" + name + "\"] for dataset " + dataset + " is null; use "
                        + "spaces for a blank expectation, which is what COBOL writes into an unset "
                        + "PIC X field");
            }
            copy.put(name, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> freezeNamedValues(Map<String, String> source,
                                                         Pattern keyShape,
                                                         String member,
                                                         String expectation) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey();
            if (name == null || !keyShape.matcher(name).matches()) {
                throw new IllegalArgumentException(member + " key \"" + name + "\" must be "
                    + expectation + ". Names are the copybook's own, verbatim and case-sensitive, "
                    + "because they are what a field-by-field comparison keys on.");
            }
            String value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(member + "[\"" + name + "\"] is null; use spaces "
                    + "for a blank expectation, which is what COBOL writes into an unset PIC X "
                    + "field");
            }
            copy.put(name, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> freezeAttributes(Map<String, String> source) {
        Map<String, String> named = freezeNamedValues(source, MAP_ATTRIBUTE_FIELD,
            "ScreenSend.attributes",
            "a symbolic-map attribute item, whose name ends in C, P, H or V - ERRMSGC is the one "
                + "this migration writes to");
        for (Map.Entry<String, String> entry : named.entrySet()) {
            if (!ATTRIBUTE_MNEMONICS.contains(entry.getValue())) {
                throw new IllegalArgumentException("ScreenSend.attributes[\"" + entry.getKey()
                    + "\"] is \"" + entry.getValue() + "\", which is not a DFHBMSCA or DFHATTR "
                    + "mnemonic. Both copybooks are IBM-supplied and absent from this repository, so "
                    + "common.BmsAttributes is the single reproduction of them and the permitted set "
                    + "comes from there. An attribute is declared by mnemonic rather than by raw "
                    + "byte so the expectation says what the program moved, not what that happens to "
                    + "encode to.");
            }
        }
        return named;
    }

    private static Map<RepositoryOperation, ForcedOutcome> freezeForcedOutcomes(
        Map<RepositoryOperation, ForcedOutcome> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<RepositoryOperation, ForcedOutcome> copy = new LinkedHashMap<>();
        for (Map.Entry<RepositoryOperation, ForcedOutcome> entry : source.entrySet()) {
            RepositoryOperation operation = entry.getKey();
            Objects.requireNonNull(operation, "ScreenRequest.forcedOutcomes carries a null key; name "
                + "the repository operation whose outcome is being forced");
            ForcedOutcome outcome = entry.getValue();
            if (outcome == null) {
                throw new IllegalArgumentException("ScreenRequest.forcedOutcomes[\""
                    + operation.key() + "\"] is null; declare the outcome to force, or omit the "
                    + "entry to let the seeded data decide");
            }
            copy.put(operation, outcome);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Set<String> attributeMnemonics() {
        Set<String> mnemonics = new LinkedHashSet<>();
        mnemonics.addAll(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.values());
        mnemonics.addAll(BmsAttributes.COLOUR_MNEMONICS.values());
        mnemonics.addAll(BmsAttributes.HIGHLIGHT_MNEMONICS.values());
        return Collections.unmodifiableSet(mnemonics);
    }
}
