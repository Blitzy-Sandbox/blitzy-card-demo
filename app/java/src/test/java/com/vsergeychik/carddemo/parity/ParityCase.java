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
 * One declarative behavioural-parity case for exactly one of the 28 COBOL programs in
 * {@code app/cbl}.
 *
 * <h2>This type is a published contract</h2>
 * <p>Every component name below is a <em>wire contract</em>. The sibling fixture tree
 * {@code app/java/src/test/resources/parity/<PROGRAM>/caseNN.json} is authored against this file
 * and binds to it by name, so <strong>renaming a component silently invalidates the fixtures</strong>:
 * Jackson would bind the renamed member to nothing and the affected expectations would evaporate
 * rather than fail. Deserialisation is deliberately strict - {@code @JsonIgnoreProperties(ignoreUnknown
 * = false)} on every type here - so that a misspelled or invented key in a fixture fails loudly at
 * load time instead of quietly binding nothing and reporting a false pass. Every JSON key is pinned
 * with an explicit {@code @JsonProperty} so that no key ever depends on a configured naming strategy.
 *
 * <h2>Four separate concepts, never one</h2>
 * <p>The single most dangerous thing a case model can do is let two different ideas share one
 * member, because a reader then cannot tell which idea a given fixture meant. This model keeps them
 * apart by construction:
 * <ol>
 *   <li>{@link #inputs()} - the <strong>pre-state</strong>. What is seeded into each dataset
 *       <em>before</em> the unit runs.</li>
 *   <li>{@link #expectedWrites()} - the <strong>ordered writes</strong>. What the unit is expected
 *       to write, in the order it writes them. A unit that writes nothing declares none, and that
 *       is a positive assertion that nothing was written, not an absence of interest.</li>
 *   <li>{@link #expectedFinalState()} - the <strong>final state</strong>. What each dataset holds
 *       once the unit has finished, row by row. For a rejection path this is the pre-state,
 *       unchanged - which is exactly the assertion such a case needs to make.</li>
 *   <li>{@link #expectedResponse()} - the <strong>online response</strong>. What a controller
 *       returned: the BMS payload of every screen send, the navigation context, the attribute
 *       metadata, the cursor and how the transaction ended.</li>
 * </ol>
 * Conflating writes with final state produced exactly the failure this separation prevents: a
 * faithful no-write path either appears to have missed ten writes, or the judge has to silently
 * redefine what the member means, and both readings can be argued from the same fixture.
 *
 * <h2>Baseline provenance - statically derived, never executed</h2>
 * <p>The expected values carried by a {@code ParityCase} are <strong>statically derived</strong>:
 * they are produced by structured reading of each COBOL paragraph, cross-checked against four
 * authoritative sources - the copybook byte layouts in {@code app/cpy} (exact offsets and
 * {@code PICTURE} clauses), the JCL {@code DD} and {@code PARM} contracts in {@code app/jcl} and
 * {@code app/proc} (record formats and lengths), the {@code DFHMDF} field definitions in
 * {@code app/bms} (screen field widths), and the nine real ASCII fixtures in
 * {@code app/data/ASCII}.
 *
 * <p>They are <strong>not</strong> captured from, recorded against, or replayed from any execution
 * of the legacy COBOL, and nothing in this harness should be described as though they were.
 * Executing the legacy programs is empirically impossible in this environment. Among the verified
 * blockers: no z/OS or CICS runtime is available; the available COBOL compiler reports its indexed
 * file handler as disabled, which excludes every program using {@code ORGANIZATION INDEXED}; no
 * Language Environment {@code CEE*} services exist, so neither {@code CEEDAYS} nor {@code CEE3ABD}
 * can be called; the IBM-supplied copybooks {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR}
 * are absent from the repository; and {@code app/cpy/CUSTREC.cpy} carries literal TAB characters on
 * lines 6-22, which breaks parsing outright.
 *
 * <p>This substitutes the <em>provenance</em> of the expected values and nothing else: 20 cases per
 * program, field-for-field diffing, the diff-count-equals-zero gate and the branch-coverage bar are
 * all preserved unchanged. Because it nonetheless modifies a stated success criterion, the
 * substitution is <strong>escalated for explicit user confirmation</strong> rather than silently
 * absorbed. A statically derived expectation can encode a misreading of the COBOL where a captured
 * one could not, which is precisely why widths and offsets are taken mechanically from the
 * copybooks rather than from prose, and why every case is seeded from genuine fixture data.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li><strong>20 cases per program, 560 in total.</strong> Each program owns {@code case01}
 *       through {@code case20} under its own {@code parity/<PROGRAM>/} directory. {@code caseId}
 *       enforces that range, so a fixture named {@code case21} or {@code case1} cannot bind.</li>
 *   <li><strong>A module is not complete until its diff count is zero across all 20 of its
 *       cases.</strong> The gate is per module, not per build: 19 clean cases and one diff means
 *       the module is incomplete, not 95% done.</li>
 *   <li><strong>{@code program} is also the directory segment.</strong> Program names are uniformly
 *       upper case, so the model value and the resource path agree by construction. The
 *       inconsistent <em>file</em> casing in the source tree - 26 programs use {@code .cbl} but
 *       {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} use {@code .CBL} - never reaches this model,
 *       which carries the program name only.</li>
 *   <li><strong>An expectation is never fed back in as an input.</strong> {@link #jobParameters()}
 *       carries batch job parameters and nothing else; an expected value belongs in
 *       {@link #expectedResponse()}, {@link #expectedWrites()}, {@link #expectedFinalState()} or
 *       {@link #expectedMessages()}. A case that supplied its own expectation as an invocation
 *       parameter would assert a tautology and always pass.</li>
 * </ul>
 *
 * <h2>What this type deliberately does not do</h2>
 * <p>This is a data contract. It validates and freezes its own contents, and it owns exactly one
 * behaviour beyond that: the seed-time width normalisation on {@link Normalisation}, which exists
 * here because the pad must have a single owner and the seeding side is that owner. It does not read
 * fixtures from disk, resolve a {@link DatasetInput} against the classpath, decode a fixed-width row
 * into fields, or compare anything. Loading and seeding belong to {@code ParityHarness}; comparison
 * belongs to {@code FieldDiffer}; byte-level decoding belongs to {@code common.FixedWidthCodec},
 * which takes its {@code Charset} as an explicit parameter and is hand-written so that every offset
 * stays reviewable against its copybook.
 *
 * <p>Consistent with that, every row and every expected value is stored as a <strong>raw
 * {@code String}</strong>, byte-for-byte as it appears in the dataset. No value is trimmed,
 * re-scaled or parsed on the way in: leading zeros, significant trailing spaces and zoned
 * sign-overpunch bytes must survive deserialisation untouched. For instance the first row of
 * {@code acctdata.txt} opens <code>00000000001Y00000001940&#123;</code>, where that trailing
 * <code>&#123;</code> is the zoned overpunch closing {@code ACCT-CURR-BAL PIC S9(10)V99} and
 * encodes a positive digit zero; the byte must arrive intact. A numeric expectation is never
 * modelled as a binary floating-point type, because {@code double} and {@code float} cannot
 * represent a COBOL {@code PIC S9(n)V99} value exactly and a case fixture must not lose precision
 * simply by being loaded.
 *
 * <h2>Diagnostics never disclose a credential span</h2>
 * <p>{@code app/cpy/CSUSR01Y.cpy} declares {@code SEC-USR-PWD PIC X(08)} and {@code COSGN00C}
 * compares it in plaintext, so the parity contract is obliged to carry that span byte for byte -
 * hashing it would change observable behaviour. Carrying it and <em>printing</em> it are different
 * things. Every {@code toString} in this file is written by hand rather than inherited from the
 * record, because the generated one would put the seed rows, the field maps and the record images
 * straight into a build log the moment a case failed to validate. {@link Redaction} is the single
 * place that decides what is masked, and {@code FieldDiffer} renders through the same helper.
 *
 * @param program the COBOL program this case pins, upper case and exactly eight characters, for
 *     example {@code "CBACT04C"} or {@code "CBSTM03A"}; doubles as the {@code parity/<PROGRAM>/}
 *     resource directory segment
 * @param caseId the case identifier, {@code "case01"} through {@code "case20"}, matching the
 *     fixture file name stem so a case is traceable to its file from a failure message alone
 * @param description required prose naming precisely <em>which</em> COBOL branch,
 *     {@code EVALUATE} arm, {@code 88}-level condition or arithmetic site this case pins - the
 *     means by which a reviewer audits parity coverage without re-reading the COBOL
 * @param unitKind how {@code ParityHarness} must invoke the unit under test; never {@code null}
 * @param inputs the pre-state: input datasets to seed before the unit runs, keyed by dataset
 *     <em>binding key</em> and held in declaration order because seeding order can matter for
 *     sequential datasets; never {@code null}, empty for a unit that reads nothing
 * @param jobParameters batch job parameters in declaration order, as raw character strings; never
 *     {@code null}, and necessarily empty for anything other than a {@link UnitKind#BATCH_JOB}
 * @param screenRequest the typed online invocation - the AID, the commarea, the received map
 *     fields and any forced repository outcome; required for a {@link UnitKind#CONTROLLER_POJO} and
 *     {@code null} for a batch job
 * @param expectedResponse the typed online expectation - every screen send's BMS payload, the
 *     navigation context, the attribute metadata, the cursor field and the termination; required
 *     for a {@link UnitKind#CONTROLLER_POJO} and {@code null} for a batch job
 * @param expectedWrites the records the unit is expected to write, in write order; never
 *     {@code null}, and empty asserts positively that nothing was written
 * @param expectedFinalState what each dataset holds after the run, row by row; never {@code null}
 * @param expectedReturnCode the expected COBOL {@code RETURN-CODE}, mapped onto the batch exit
 *     status. <strong>Mandatory</strong>, and boxed for exactly that reason: as a primitive
 *     {@code int} an omitted {@code "expectedReturnCode"} key bound silently to {@code 0}, so a
 *     fixture that forgot to state the return code asserted the most common value by accident, and a
 *     case expecting an abend of 8 or 12 passed while asserting a normal completion. A boxed member
 *     arrives {@code null} when the key is absent, which the constructor refuses
 * @param expectedMessages emitted lines in emission order, each declaring its channel and
 *     therefore its width; never {@code null}
 * @param normalisations the seed-time width normalisations, each bound to the dataset it applies
 *     to; never {@code null}, and empty for a case seeding no under-width fixture
 * @param expectedDatasets dataset-level expectations - the identity and the exact row count of a
 *     dataset on a channel, including a count of zero; never {@code null}, and empty for a case that
 *     asserts only at row level
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

    /**
     * A COBOL program name: upper case, alphanumeric, beginning with a letter, and exactly eight
     * characters. Eight is not arbitrary - it is the partitioned-dataset member-name limit, and all
     * 28 in-scope program names occupy it exactly ({@code CBACT01C}, {@code COACTUPC},
     * {@code CSUTLDTC}, {@code CBSTM03A} and the rest). Rejecting anything else catches the one
     * failure mode that matters here: a value that does not match the {@code parity/<PROGRAM>/}
     * directory it is supposed to name, such as a lower-cased {@code "cbact01c"}.
     *
     * <p>{@link Pattern} instances are immutable and thread-safe, so this constant and the others
     * below introduce no shared mutable state.
     */
    private static final Pattern PROGRAM_NAME = Pattern.compile("[A-Z][A-Z0-9]{7}");

    /**
     * {@code case01} through {@code case20} and nothing else, encoding the 20-cases-per-program
     * invariant directly in the model so a mis-numbered fixture cannot load.
     */
    private static final Pattern CASE_ID = Pattern.compile("case(?:0[1-9]|1[0-9]|20)");

    /**
     * A dataset <em>binding key</em>: upper case, alphanumeric, beginning with a letter, one to
     * eight characters, and containing no dot. Every binding key declared by
     * {@code src/main/resources/application.yml} satisfies this - the eight names taken from
     * {@code app/csd/CARDDEMO.CSD} plus the batch-only {@code DD} names, of which the longest are
     * eight characters and one ({@code XREFFIL1}) ends in a digit.
     *
     * <p>The shape is chosen so that a fully-qualified mainframe dataset name cannot pass: those
     * are dotted and far longer than eight characters. That is the point. A case must address a
     * dataset through its binding key, never through a literal dataset name, so that no dataset
     * name is written into source at all. Validating the shape rather than enumerating the 27 keys
     * also avoids restating the configuration here, where the copy would immediately begin to rot.
     */
    private static final Pattern DATASET_KEY = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

    /**
     * A copybook member name as {@code app/cpy} spells them: one to eight upper-case alphanumerics
     * beginning with a letter. {@code CVACT01Y}, {@code CVTRA05Y}, {@code CSUSR01Y}, {@code COSTM01},
     * {@code CUSTREC}.
     *
     * <p>Eight is the partitioned-dataset member-name limit, the same bound a program name obeys. The
     * mixed filename casing of the source tree - {@code .cpy} for twenty-seven of them and
     * {@code COSTM01.CPY} for the twenty-eighth - is a property of the file name and not of the member
     * name, so it does not reach this pattern.
     */
    private static final Pattern COPYBOOK_NAME = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

    /**
     * A batch job parameter name: lower camel case, letters and digits only.
     *
     * <p>Deliberately narrow, because this member has been abused before. The one verified
     * parameter in the whole migration is {@code parmDate}, from {@code PARM='2022071800'} in
     * {@code app/jcl/INTCALC.jcl}. A name carrying a hyphen, a dot or an upper-case initial is
     * almost always a COBOL field name, an EIB field or a screen field that belongs in
     * {@link ScreenRequest}, and a name beginning {@code expected} is an expectation that belongs
     * in one of the {@code expected*} members.
     */
    private static final Pattern JOB_PARAMETER_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");

    /**
     * A COMMAREA field name as {@code app/cpy/COCOM01Y.cpy} and its per-program extensions spell
     * them - {@code CDEMO-FROM-TRANID}, {@code CDEMO-PGM-CONTEXT},
     * {@code CDEMO-CU02-USR-SELECTED} and the rest.
     */
    private static final Pattern COMMAREA_FIELD = Pattern.compile("CDEMO-[A-Z0-9]+(?:-[A-Z0-9]+)*");

    /**
     * A field name of the <em>inbound</em> communication area, which is a wider thing than
     * {@link #COMMAREA_FIELD}: what a program receives in {@code DFHCOMMAREA} is the shared
     * {@code CARDDEMO-COMMAREA} <em>plus</em> whatever the receiving program appends to it, and the
     * two halves arrive as one area.
     *
     * <p>Most programs name their own extension with the same {@code CDEMO-} prefix, which is why
     * {@code COTRN00C}'s cases can state {@code CDEMO-CT00-PAGE-NUM}
     * [{@code app/cbl/COTRN00C.cbl:62-70}] and be accepted by the narrower pattern.
     * {@code COCRDLIC} does not: its 254-byte extension is {@code WS-THIS-PROGCOMMAREA} and its
     * items are {@code WS-CA-LAST-CARD-NUM}, {@code WS-CA-FIRST-CARD-NUM},
     * {@code WS-CA-SCREEN-NUM}, {@code WS-CA-LAST-PAGE-DISPLAYED}, {@code WS-CA-NEXT-PAGE-IND} and
     * {@code WS-RETURN-FLAG} [{@code app/cbl/COCRDLIC.cbl:229-248}], restored from bytes 161
     * onwards of the very same area the {@code CDEMO-} items come from bytes 1 to 160 of
     * [{@code :327-331}]. A {@code WS-} prefixed item is therefore accepted here.
     *
     * <p>This is deliberately the <strong>inbound</strong> pattern only.
     * {@link ExpectedResponse#navigation()} keeps the narrower {@link #COMMAREA_FIELD}, because the
     * compared navigation image is the shared 160-byte {@code CARDDEMO-COMMAREA} and nothing else -
     * a program's own extension leaves in the response payload, not in that image, and pinning it
     * there would assert a field the fingerprint does not carry.
     */
    private static final Pattern INBOUND_COMMAREA_FIELD =
        Pattern.compile("(?:CDEMO|WS)-[A-Z0-9]+(?:-[A-Z0-9]+)*");

    /**
     * A symbolic-map <em>input</em> item: the {@code xxxI} items of a {@code COxxxxAI} group, which
     * are the only payload-bearing items of a received map. {@code USRIDINI}, {@code FNAMEI},
     * {@code TITLE01I}. The {@code xxxL}, {@code xxxF} and {@code xxxA} items are length, flag and
     * attribute metadata and are addressed elsewhere.
     */
    private static final Pattern MAP_INPUT_FIELD = Pattern.compile("[A-Z][A-Z0-9]{0,28}I");

    /**
     * A symbolic-map <em>output</em> item: the {@code xxxO} items of a {@code COxxxxAO} group.
     * {@code ERRMSGO}, {@code TITLE01O}, {@code USRIDINO}.
     */
    private static final Pattern MAP_OUTPUT_FIELD = Pattern.compile("[A-Z][A-Z0-9]{0,28}O");

    /**
     * A symbolic-map attribute item: the colour, protection, highlight and validation bytes of an
     * output group, whose names end in {@code C}, {@code P}, {@code H} or {@code V}.
     * {@code ERRMSGC} is the one this migration actually writes to, from
     * {@code MOVE DFHRED TO ERRMSGC} and its siblings.
     */
    private static final Pattern MAP_ATTRIBUTE_FIELD = Pattern.compile("[A-Z][A-Z0-9]{0,28}[CPHV]");

    /**
     * A symbolic-map length item, which is where the cursor is expressed: COBOL positions the
     * cursor by moving {@code -1} into {@code xxxL}, so the field that received it <em>is</em> the
     * cursor position.
     */
    private static final Pattern MAP_LENGTH_FIELD = Pattern.compile("[A-Z][A-Z0-9]{0,28}L");

    /** A CICS program name: one to eight upper-case alphanumerics beginning with a letter. */
    private static final Pattern PROGRAM_REFERENCE = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

    /**
     * A BMS map or mapset name. Seven characters at most, which is the width
     * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} carry in {@code app/cpy/COCOM01Y.cpy}.
     */
    private static final Pattern MAP_REFERENCE = Pattern.compile("[A-Z][A-Z0-9]{0,6}");

    /**
     * The largest value a z/OS step return code can carry. Return codes are non-negative by
     * definition, so this bound rejects a sign error at construction rather than letting it reach a
     * comparison.
     */
    private static final int MAX_RETURN_CODE = 4095;

    /**
     * The two code pages this migration reads and writes, and the only two a case may name.
     *
     * <p>Enumerated rather than delegated to {@code Charset.isSupported}: a case naming
     * {@code UTF-8} or {@code ISO-8859-1} would load happily and then compare bytes that no dataset
     * in this system ever carries. {@code IBM037} is the EBCDIC code page of
     * {@code app/data/EBCDIC}; {@code US-ASCII} is the code page of the nine ASCII fixtures. The
     * platform default is never one of them, by design (practice B8).
     */
    private static final Set<String> PERMITTED_CHARSETS = Set.of("US-ASCII", "IBM037");

    /**
     * Every {@code DFHAID} mnemonic, taken from {@code common.CicsAid} rather than re-listed.
     *
     * <p>Sourcing the set from the class under the same contract is what stops the two drifting:
     * {@code DFHAID} is IBM-supplied and absent from this repository, so {@code CicsAid} is already
     * the single reproduction of it, and a second list here would be a second thing to keep right.
     */
    private static final Set<String> AID_MNEMONICS = Set.copyOf(CicsAid.mnemonicsByAid().values());

    /**
     * Every {@code DFHBMSCA} and {@code DFHATTR} mnemonic a case may name as an attribute value -
     * the field attributes, the colours and the highlights - taken from
     * {@code common.BmsAttributes} for the same reason.
     */
    private static final Set<String> ATTRIBUTE_MNEMONICS = attributeMnemonics();

    /**
     * Validates every member and freezes every collection.
     *
     * <p>Absent collections normalise to empty rather than {@code null}, so no consumer - neither
     * the harness nor any of the 28 test classes - has to null-check a member. Every collection is
     * defensively copied into an unmodifiable view that preserves declaration order, so a case is
     * immutable once constructed and one test cannot perturb a case another test is holding.
     *
     * <p>All validation is delegated to {@code private static} helpers deliberately: a compact
     * constructor that called instance methods would publish {@code this} before the record was
     * fully initialised.
     *
     * @throws IllegalArgumentException if any member is missing, malformed, addresses a dataset by
     *     something other than a binding key, carries an expectation in an input member, or
     *     declares a normalisation for a dataset it never seeds
     * @throws NullPointerException if {@code unitKind} is absent
     */
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

    /**
     * The shape of a case that declares no stimulus beyond its seeded data: the fourteen members that
     * existed before {@link #unitStimulus()} did.
     *
     * <p>Most cases are this shape - their whole stimulus is the rows they seed - so the member
     * normalises to {@link UnitStimulus#NONE} and no case file has to carry an empty object.
     *
     * @param program the eight-character upper-case COBOL program name
     * @param caseId {@code case01} through {@code case20}
     * @param description what the case exercises, in prose
     * @param unitKind how the harness must reach the unit
     * @param inputs the datasets to seed, keyed by binding key
     * @param jobParameters the batch job parameters, only for a batch case
     * @param screenRequest the online invocation, only for a controller case
     * @param expectedResponse the online response, only for a controller case
     * @param expectedWrites the records expected to be written, in write order
     * @param expectedFinalState what each dataset is expected to hold afterwards
     * @param expectedReturnCode the expected {@code RETURN-CODE}; mandatory
     * @param expectedMessages the lines expected to be emitted, in order
     * @param normalisations the seed-time width normalisations
     * @param expectedDatasets the dataset-level expectations
     */
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

    /**
     * The shape of a case that declares a stimulus but no operation sequence.
     *
     * @param program the eight-character upper-case COBOL program name
     * @param caseId {@code case01} through {@code case20}
     * @param description what the case exercises, in prose
     * @param unitKind how the harness must reach the unit
     * @param inputs the datasets to seed, keyed by binding key
     * @param jobParameters the batch job parameters, only for a batch case
     * @param screenRequest the online invocation, only for a controller case
     * @param expectedResponse the online response, only for a controller case
     * @param expectedWrites the records expected to be written, in write order
     * @param expectedFinalState what each dataset is expected to hold afterwards
     * @param expectedReturnCode the expected {@code RETURN-CODE}; mandatory
     * @param expectedMessages the lines expected to be emitted, in order
     * @param normalisations the seed-time width normalisations
     * @param expectedDatasets the dataset-level expectations
     * @param unitStimulus the stimulus applied beyond the seeded rows
     */
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

    /**
     * The shape of a case that declares no dataset-level expectation: the thirteen members that
     * existed before {@link #expectedDatasets()} did.
     *
     * <p>A dataset-level expectation is an <em>additional</em> assertion rather than a replacement for
     * a row-level one, so a case is entitled to declare none - and every call site that declares none
     * uses this form. Delegating rather than duplicating means it can never construct a value the
     * canonical constructor would have rejected.
     *
     * @param program the eight-character upper-case COBOL program name
     * @param caseId {@code case01} through {@code case20}
     * @param description what the case exercises, in prose
     * @param unitKind how the harness must reach the unit
     * @param inputs the datasets to seed, keyed by binding key
     * @param jobParameters the batch job parameters, only for a batch case
     * @param screenRequest the online invocation, only for a controller case
     * @param expectedResponse the online response, only for a controller case
     * @param expectedWrites the records expected to be written, in write order
     * @param expectedFinalState what each dataset is expected to hold afterwards
     * @param expectedReturnCode the expected {@code RETURN-CODE}; mandatory
     * @param expectedMessages the lines expected to be emitted, in order
     * @param normalisations the seed-time width normalisations
     */
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

    /**
     * Renders the case without disclosing a credential span.
     *
     * <p>Hand-written rather than inherited. The generated {@code toString} of a record prints every
     * component, which here would put the ten {@code USRSEC} seed rows - each carrying the legacy
     * plaintext {@code SEC-USR-PWD} span - and every expected record image into whatever log
     * catches a validation failure. What is printed instead is the identity a reader needs to find
     * the fixture, plus the shape of what it declares.
     *
     * @return a description naming the case and the size of each of its parts, never a field value
     */
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

    // -------------------------------------------------------------------------------------------
    // Nested contract types. Every one of them lives here on purpose: this package contains exactly
    // three support types - ParityCase, FieldDiffer and ParityHarness - alongside the 28
    // per-program test classes, and promoting a nested type to its own file would add a file the
    // plan does not name.
    // -------------------------------------------------------------------------------------------

    /**
     * How {@code ParityHarness} must obtain and invoke the unit a case exercises.
     *
     * <p>The discriminator exists because the layering is genuinely not uniform across the 28
     * programs. A dedicated {@code @Service} holding the decision logic exists only for
     * {@code COACTUPC}, {@code COCRDUPC}, {@code COBIL00C}, {@code COSGN00C}, {@code CBCUS01C},
     * {@code COADM01C} and {@code COMEN01C}; the whole {@code transaction} package and four of the
     * five {@code user} programs keep their decision logic in the {@code @RestController} class
     * itself. A harness that assumed one shape would be unable to reach the branches of the other,
     * so the case states the shape explicitly rather than letting the harness guess from a naming
     * convention.
     *
     * <p>Whichever constant applies, the operative requirement is the same and is absolute: the
     * unit is invoked with <strong>no HTTP layer and no {@code JobLauncher} in the path</strong>.
     * Parity assertions are about arithmetic and byte layout, and neither is improved by routing
     * through a servlet container or a job repository - both merely add ways for a test to fail for
     * a reason that has nothing to do with parity.
     */
    /**
     * The repository operations a case may force an outcome for - the closed set these 28 programs
     * actually perform.
     *
     * <p>A closed type rather than a name, because the set really is closed and the difference is not
     * cosmetic. The whole purpose of a forced outcome is to reach an arm the seeded data cannot reach:
     * the {@code WHEN OTHER} of a {@code RESP} check, a {@code DUPKEY} on a write, a {@code NOTFND} on
     * a keyed read. A key that matches no call site forces nothing, the arm stays unreached, and the
     * case passes - having asserted the opposite of what it says. Spelt as free text that is one
     * transposed letter away at every call site; spelt as this enum it cannot be written at all in
     * Java and is refused by name when read from JSON.
     *
     * <p>The JSON spelling is the repository method name in lower camel case, so a case file reads as
     * the code does. {@link #key()} is what Jackson writes and {@link #fromKey(String)} is what it
     * reads, in both directions including as a map key.
     */
    public enum RepositoryOperation {

        /** A keyed read - {@code EXEC CICS READ} or a {@code READ ... KEY IS} in a batch program. */
        READ("read"),

        /** A keyed read for update, which the two update programs perform before a rewrite. */
        READ_FOR_UPDATE("readForUpdate"),

        /** The next record of an open browse - {@code EXEC CICS READNEXT}. */
        READ_NEXT("readNext"),

        /** Positioning a browse - {@code EXEC CICS STARTBR}. */
        START_BROWSE("startBrowse"),

        /** Adding a record - {@code EXEC CICS WRITE} or a batch {@code WRITE}. */
        WRITE("write"),

        /** Replacing the record a read-for-update holds - {@code EXEC CICS REWRITE}. */
        REWRITE("rewrite"),

        /** Removing a record - {@code EXEC CICS DELETE}. */
        DELETE("delete");

        /** The repository method name, which is also the JSON key. */
        private final String key;

        /** Binds a constant to its method name. */
        RepositoryOperation(String key) {
            this.key = key;
        }

        /**
         * The repository method name, as a case file spells it.
         *
         * @return the lower-camel-case name, never {@code null}
         */
        @JsonValue
        public String key() {
            return key;
        }

        /**
         * Resolves a spelling from a case file.
         *
         * @param key the operation name as the case spells it
         * @return the matching operation
         * @throws IllegalArgumentException if the name is not one of the seven, listing them. The
         *     message names the whole set because the realistic failure is a typo - {@code rewirte}
         *     for {@code rewrite} - and a reader needs to see the right spelling, not be told the
         *     wrong one is wrong
         */
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

        /**
         * A Spring Batch job. The harness executes the job's tasklet or its
         * reader/processor/writer trio directly, so step sequencing and write ordering are
         * observed without a {@code JobLauncher}, a job repository or an asynchronous executor
         * between the assertion and the code. The only kind that may carry
         * {@link ParityCase#jobParameters()}.
         */
        BATCH_JOB,

        /**
         * A {@code @Service} holding the decision logic lifted out of a COBOL program - the shape
         * used by {@code COACTUPC}, {@code COCRDUPC}, {@code COBIL00C}, {@code COSGN00C},
         * {@code CBCUS01C}, {@code COADM01C} and {@code COMEN01C}. Constructed with its
         * collaborators and called as a plain object.
         */
        SERVICE,

        /**
         * A {@code @Component} collaborator that is not a job despite what its mandated class name
         * may suggest - the data-access collaborator translated from {@code CBSTM03B}, which is
         * called 13 times by {@code CBSTM03A} and has no {@code EXEC PGM=} anywhere, and the date
         * utility translated from {@code CSUTLDTC}, which is a called subprogram reached from two
         * online programs. Invoked through its typed method.
         */
        COMPONENT,

        /**
         * A {@code @RestController} exercised as a plain Java object: instantiated through its
         * constructor and its handler method called directly. This is the shape for every program
         * whose decision logic stays in the controller - the whole {@code transaction} package and
         * four of the five {@code user} programs.
         *
         * <p>Emphatically <strong>not</strong> {@code MockMvc}, not a running servlet container and
         * not a web application context. The name says {@code POJO} to make that unambiguous at
         * every call site.
         *
         * <p>A case of this kind <strong>must</strong> declare both
         * {@link ParityCase#screenRequest()} and {@link ParityCase#expectedResponse()}: an online
         * program's observable behaviour is mostly its response, and a case that could not state
         * one would be asserting only the part of the behaviour that reaches a dataset.
         */
        CONTROLLER_POJO
    }

    /**
     * The rows to seed into one dataset before the unit runs, in one of exactly two shapes.
     *
     * <p><strong>Inline</strong> - {@code rows} carries the record images literally. This is the
     * shape for a small, precisely-chosen input: the three cross-reference rows that drive one
     * {@code EVALUATE} arm, or the single malformed row that must be rejected. Every row is stored
     * verbatim, so leading zeros, significant trailing spaces and zoned sign-overpunch bytes are all
     * preserved exactly as written.
     *
     * <p><strong>Fixture-backed</strong> - {@code fixture} names one of the nine files shipped under
     * {@code src/test/resources/fixtures/}, optionally narrowed by {@code fromRow} and
     * {@code rowCount}. This is the shape for realistic volume, and it is what keeps cases seeded
     * from genuine production-shaped data rather than invented data.
     *
     * <h2>The name is a bare file name from a closed list, and nothing else</h2>
     * <p>{@code fixture} is checked against {@link #PERMITTED_FIXTURES} and is refused if it carries
     * a directory separator, a parent-directory segment, a drive letter, a scheme or any extension
     * other than {@code .txt}. This is not defensive decoration. The value is resolved against the
     * classpath, so before the allow-list existed a fixture of
     * {@code ../../../main/resources/application.yml} - or any other resource the test classpath can
     * reach - would have been loaded and seeded as though it were fixed-width dataset rows
     * (CWE-22). The nine names are also the entire set that exists, so an allow-list costs a case
     * author nothing and turns a typo into an immediate, specific failure.
     *
     * <p>{@code cardxref.txt} is deliberately <em>not</em> pre-padded to its copybook width; a case
     * that seeds it must declare a {@link DatasetNormalisation} carrying
     * {@link Normalisation#CARDXREF_FILLER_PAD_36_TO_50} against that dataset, and the pad is then
     * applied once, at seed time, before anything decodes the row.
     *
     * <p><strong>Empty</strong> - {@code empty} is {@code true} and the input declares a dataset that
     * <em>exists and holds no row</em>, carrying the {@code recordLength} and the {@code copybook} that
     * describe the rows it would hold.
     *
     * <h2>Why an empty dataset needs a shape of its own</h2>
     * <p>Before this shape existed, a dataset with zero rows could not be declared at all: the model
     * demanded exactly one of a non-empty {@code rows} list or a {@code fixture}, and a case whose
     * subject was an empty input had to <em>omit the dataset entirely</em>. Omission and emptiness then
     * looked identical to the harness, and they are not the same assertion at all:
     * <ul>
     *   <li>an <strong>omitted</strong> dataset is a dataset the case says nothing about. A unit that
     *       read it would read nothing, and nobody would know whether that was intended;</li>
     *   <li>an <strong>empty</strong> dataset is a positive statement that the unit opened a real
     *       dataset and reached end-of-file on its first read. That is a branch the COBOL genuinely
     *       has - every one of the five read-and-print programs takes it, the posting job takes it when
     *       the daily file is empty, and it is the only way to reach a {@code FILE STATUS} of
     *       {@code '10'} on the first {@code READ} - and no seeded row can produce it.</li>
     * </ul>
     * The width and the copybook are mandatory with this shape because they are what make it a
     * <em>dataset</em> rather than an absence: a unit that opens an empty file still knows how wide its
     * records are, and a fingerprint that reports the dataset must report it at that width.
     *
     * <p>The three shapes are mutually exclusive and exactly one is mandatory - a dataset entry that
     * declared none would seed nothing while appearing to seed something, which is exactly the
     * silent-pass failure this model exists to prevent. The constructor enforces the exclusivity, so
     * {@link #inline()}, {@link #fixtureBacked()} and {@link #declaredEmpty()} partition the space and
     * a consumer may branch on any of them with confidence.
     *
     * @param rows inline record images in seeding order; never {@code null} - empty for a
     *     fixture-backed or an empty input
     * @param fixture one of the nine permitted fixture file names, or {@code null} for an inline or an
     *     empty input
     * @param fromRow the zero-based index of the first fixture row to seed; normalised to {@code 0}
     *     when a fixture is named without one, and always {@code null} for an inline or an empty input
     * @param rowCount how many fixture rows to seed, or {@code null} for every remaining row from
     *     {@code fromRow} to the end of the fixture; always {@code null} for an inline or an empty
     *     input, and at least {@code 1} when present
     * @param empty {@code TRUE} to declare a dataset that exists and holds no row; {@code null} for
     *     the other two shapes. Never {@code FALSE}, which would be a third way of saying nothing
     * @param recordLength the declared record width of an empty dataset, at least 1; mandatory with
     *     {@code empty} and absent otherwise
     * @param copybook the copybook that describes the rows an empty dataset would hold - for example
     *     {@code CVACT01Y} - so the declaration names what it is empty of; mandatory with {@code empty}
     *     and absent otherwise
     */
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

        /**
         * The single classpath directory a fixture may be resolved from. A case names a file, this
         * names the place, and the two are joined by {@link #resourcePath()} - so no case can move
         * the root.
         */
        public static final String FIXTURE_ROOT = "fixtures/";

        /** The only extension a fixture may carry. All nine shipped fixtures are plain text. */
        public static final String FIXTURE_EXTENSION = ".txt";

        /**
         * The nine fixtures that exist, with their measured geometry recorded beside each so a case
         * author can see what they are choosing: {@code acctdata.txt} (300 bytes x 50 rows),
         * {@code carddata.txt} (150 x 50), {@code cardxref.txt} (<strong>36</strong> x 50),
         * {@code custdata.txt} (500 x 50), {@code dailytran.txt} (350 x 300), {@code discgrp.txt}
         * (50 x 51), {@code tcatbal.txt} (50 x 50), {@code trancatg.txt} (60 x 18) and
         * {@code trantype.txt} (60 x 7). Every one is byte-identical to its counterpart in
         * {@code app/data/ASCII}.
         *
         * <p>A closed set rather than a pattern, because the set genuinely is closed:
         * {@code app/data/ASCII} holds exactly nine files, and there is no tenth for a case to
         * legitimately want.
         */
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

        /**
         * Validates that exactly one shape is declared, that a named fixture is one of the nine,
         * and freezes the row list.
         *
         * @throws NullPointerException never - an absent member is a legitimate part of two of the
         *     three shapes
         * @throws IllegalArgumentException if more than one shape or no shape is declared, if a row
         *     range accompanies an inline or an empty input, if {@code fixture} is blank, is not a bare
         *     file name, or is not one of {@link #PERMITTED_FIXTURES}, if {@code fromRow} is negative,
         *     if {@code rowCount} is less than one, if {@code empty} is {@code FALSE}, or if an empty
         *     input omits its width or its copybook
         */
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
                // Only a fixture-backed input has a range to validate or a name to check. The
                // declared-empty shape reaches here too, and its own members were validated above;
                // treating "not inline" as "must be a fixture" would demand a fixture name it has
                // deliberately not got.
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

        /**
         * The classpath resource path this input's fixture is read from.
         *
         * <p>Composed rather than stored, from {@link ParityCase#FIXTURE_ROOT} and a name the
         * constructor has already confined to a bare file name from
         * {@link ParityCase#FIXTURE_NAMES}. That composition is the confinement: a loader that reads
         * whatever this method returns cannot address anything outside the fixture directory, because
         * there is no input to this class from which such a path could be built. The alternative -
         * every future loader remembering to prepend the root and re-check the name - is the
         * arrangement that eventually gets one of the two wrong.
         *
         * @return the resource path, or {@code null} when this input seeds from inline rows
         */
        @JsonIgnore
        public String fixtureResourcePath() {
            return fixture == null ? null : FIXTURE_ROOT + fixture;
        }

        /**
         * The shape of an input that is not empty: the four members that existed before the empty
         * declaration did.
         *
         * <p>Every inline and fixture-backed call site uses this form, which is why it exists: adding
         * the empty shape must not require an edit at a site that is not about emptiness. It delegates
         * to the canonical constructor with the three empty-only members absent, so it can never
         * produce a value the canonical constructor would have rejected.
         *
         * @param rows inline record images in seeding order, or an empty list
         * @param fixture one of the nine permitted fixture file names, or {@code null}
         * @param fromRow the zero-based first fixture row, or {@code null}
         * @param rowCount how many fixture rows, or {@code null} for all of them
         */
        public DatasetInput(List<String> rows, String fixture, Integer fromRow, Integer rowCount) {
            this(rows, fixture, fromRow, rowCount, null, null, null);
        }

        /**
         * Seeds a dataset from literal record images.
         *
         * @param rows the record images in seeding order, verbatim and not empty
         * @return an inline input
         */
        public static DatasetInput ofRows(List<String> rows) {
            return new DatasetInput(rows, null, null, null, null, null, null);
        }

        /**
         * Seeds a dataset from every row of one of the nine permitted fixtures.
         *
         * @param fixture the fixture file name, which must be one of {@link #PERMITTED_FIXTURES}
         * @return a fixture-backed input covering the whole fixture
         */
        public static DatasetInput ofFixture(String fixture) {
            return new DatasetInput(List.of(), fixture, null, null, null, null, null);
        }

        /**
         * Declares a dataset that exists and holds no row - the only way a case can reach the branch a
         * unit takes when its first {@code READ} meets end-of-file.
         *
         * @param recordLength the declared record width, at least 1
         * @param copybook the copybook describing the rows it would hold, for example {@code CVACT01Y}
         * @return an empty input
         * @throws IllegalArgumentException if the width is below 1 or the copybook name is malformed
         */
        public static DatasetInput ofEmpty(int recordLength, String copybook) {
            return new DatasetInput(List.of(), null, null, null, Boolean.TRUE, recordLength,
                copybook);
        }

        /**
         * Whether this input carries its rows literally. Strict complement of
         * {@link #fixtureBacked()}.
         *
         * <p>Excluded from JSON: it is derived from {@code rows} and {@code fixture}, and emitting
         * it would add a key that strict deserialisation would then reject on the way back in.
         *
         * @return {@code true} when {@code rows} is populated
         */
        @JsonIgnore
        public boolean inline() {
            return !rows.isEmpty();
        }

        /**
         * Whether this input names a classpath fixture.
         *
         * <p>Excluded from JSON for the same reason as {@link #inline()}.
         *
         * @return {@code true} when {@code fixture} is present
         */
        @JsonIgnore
        public boolean fixtureBacked() {
            return fixture != null;
        }

        /**
         * Whether this input declares a dataset that exists and holds no row.
         *
         * <p>The third member of the partition with {@link #inline()} and {@link #fixtureBacked()};
         * exactly one of the three is true for any valid instance.
         *
         * @return {@code true} when {@code empty} was declared
         */
        @JsonIgnore
        public boolean declaredEmpty() {
            return Boolean.TRUE.equals(empty);
        }

        /**
         * The classpath location this input resolves to: {@link #FIXTURE_ROOT} joined to the
         * validated file name.
         *
         * <p>The join happens here and only here, so a caller never concatenates a path of its own
         * and the root cannot be redirected by anything a case declares.
         *
         * @return the classpath-relative resource path
         * @throws IllegalStateException if this input is inline and therefore names no resource
         */
        @JsonIgnore
        public String resourcePath() {
            if (fixture == null) {
                throw new IllegalStateException("This DatasetInput carries inline rows, so it names "
                    + "no classpath resource. Call inline() first, or seed rows() directly.");
            }
            return FIXTURE_ROOT + fixture;
        }

        /**
         * Renders this input without printing a row.
         *
         * <p>An inline seed block is exactly where a credential span appears in practice - the ten
         * {@code USRSEC} rows transcribed from {@code app/jcl/DUSRSECJ.jcl} each carry
         * {@code SEC-USR-PWD} - so the row images are summarised by count and never emitted.
         *
         * @return the shape of this input, never its content
         */
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

        /**
         * Requires a bare, permitted fixture file name.
         *
         * <p>Every rejection below is a real attack or accident shape rather than a hypothetical: a
         * separator or a {@code ..} segment escapes {@link #FIXTURE_ROOT}; a leading separator or a
         * drive letter escapes the classpath root entirely; a scheme turns the value into a URI that
         * could reach outside the build; and anything not in the closed set is either a typo or an
         * attempt to seed a resource that is not dataset rows at all.
         *
         * @param fixture the candidate name
         * @throws IllegalArgumentException if the name is not a bare permitted file name
         */
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

        /**
         * Requires the empty flag to be either absent or {@code TRUE}.
         *
         * <p>{@code "empty": false} is refused rather than read as "not empty". It would be a second
         * way of saying nothing, and a fixture author who wrote it plainly meant something - most
         * likely that the dataset is not empty, which is already said by declaring rows or a fixture.
         * Leaving it legal would let a case declare {@code "empty": false} and no rows and no fixture,
         * and that case seeds nothing at all.
         *
         * @param empty the declared flag
         * @return {@code true} when the empty shape was declared
         * @throws IllegalArgumentException if the flag is {@code FALSE}
         */
        private static boolean requireEmptyFlag(Boolean empty) {
            if (Boolean.FALSE.equals(empty)) {
                throw new IllegalArgumentException("DatasetInput.empty is false, which says nothing: "
                    + "a dataset that holds rows declares them in \"rows\" or names a \"fixture\", "
                    + "and a dataset that exists and holds no row declares \"empty\": true. Omit the "
                    + "member entirely for the first two shapes.");
            }
            return Boolean.TRUE.equals(empty);
        }

        /**
         * Requires an empty dataset to state the width of the records it would hold.
         *
         * @param recordLength the declared width
         * @return the validated width
         * @throws IllegalArgumentException if it is absent or below 1
         */
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

        /**
         * Requires an empty dataset to name the copybook describing the rows it would hold.
         *
         * @param copybook the declared copybook name
         * @return the validated name
         * @throws IllegalArgumentException if it is absent or is not a copybook member name
         */
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
     * The typed invocation of an online unit: everything a CICS pseudo-conversational program
     * receives, and nothing that it produces.
     *
     * <h2>Why this exists rather than a map of loose strings</h2>
     * <p>Before this type existed, an online case expressed its input through
     * {@link ParityCase#jobParameters()} - an untyped {@code Map<String, String>} whose keys were
     * validated only for being non-blank. Three things followed, and all three are the reason the
     * member set below is typed and closed. Incompatible spellings of the same concept all bound
     * successfully, so twenty cases for one program used {@code EIBAID}, {@code eibaid},
     * {@code eib.EIBAID} and {@code EIBAID} again with no way to tell which the harness would read.
     * A typo bound to nothing and silently removed an input. And an <em>expected</em> value could be
     * declared in the same map as the inputs, which fed the expectation back in as an invocation
     * parameter and made the assertion a tautology.
     *
     * <p>Strict deserialisation closes all three: {@code @JsonIgnoreProperties(ignoreUnknown =
     * false)} means an unrecognised key fails the load, and every value below is validated for
     * shape, so {@code aid} must be a real {@code DFHAID} mnemonic and {@code charset} must be one
     * of the two code pages this system uses.
     *
     * @param eibcalen the value of {@code EIBCALEN} - the commarea length CICS reports. Zero is a
     *     real and important state, not an absence: it is the first-ever invocation with no
     *     commarea, and every one of the seventeen online programs guards on it
     * @param aid the resolved {@code EIBAID} mnemonic, for example {@code DFHENTER} or
     *     {@code DFHPF5}; must be a mnemonic {@code common.CicsAid} defines, and may be {@code null}
     *     only on a path that never reads the AID
     * @param pinnedClock the fixed instant the unit must observe, as an ISO-8601 local date-time.
     *     Every online program moves {@code FUNCTION CURRENT-DATE} into its screen header, so a case
     *     that did not pin the clock could not assert the header at all. May be {@code null} for a
     *     path that paints no header
     * @param charset the code page of the record images this case carries; one of
     *     {@code US-ASCII} or {@code IBM037}, never the platform default
     * @param commarea the {@code CARDDEMO-COMMAREA} field values keyed by copybook name, in
     *     declaration order; never {@code null}, and empty when {@code eibcalen} is zero
     * @param mapFields the received map's {@code xxxI} payload items keyed by their symbolic-map
     *     names; never {@code null}, and empty on a path that receives no map
     * @param forcedOutcomes the repository outcomes the harness must force, keyed by the repository
     *     operation name - the only way to reach a {@code WHEN OTHER} arm of an
     *     {@code EVALUATE WS-RESP-CD}, which no seeded data can produce; never {@code null}
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

        /**
         * Validates and freezes the request.
         *
         * @throws IllegalArgumentException if {@code eibcalen} is negative, if {@code aid} is not a
         *     {@code DFHAID} mnemonic, if {@code pinnedClock} is not an ISO-8601 local date-time, if
         *     {@code charset} is not one of the two permitted code pages, or if any map key is not
         *     the kind of COBOL name its member accepts
         */
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

        /**
         * The pinned clock as a {@link LocalDateTime}, parsed rather than re-derived by the caller.
         *
         * @return the pinned instant, or {@code null} when this case pins no clock
         */
        @JsonIgnore
        public LocalDateTime pinnedClockAt() {
            return pinnedClock == null ? null : LocalDateTime.parse(pinnedClock);
        }

        /**
         * Renders the request without printing a field value.
         *
         * <p>{@code mapFields} routinely carries {@code PASSWDI}, which is a credential span by any
         * reading, so the member is summarised by its key set. The AID and the commarea length are
         * printed because they identify the path and disclose nothing.
         *
         * @return the shape of this request
         */
        @Override
        public String toString() {
            return "ScreenRequest[eibcalen=" + eibcalen + ", aid=" + aid
                + ", pinnedClock=" + pinnedClock + ", charset=" + charset
                + ", commarea=" + commarea.keySet()
                + ", mapFields=" + mapFields.keySet()
                + ", forcedOutcomes=" + forcedOutcomeKeys() + ']';
        }

        /**
         * The forced-outcome operations by their case-file spelling, for the rendering above.
         *
         * @return the declared operation names in declaration order
         */
        private List<String> forcedOutcomeKeys() {
            List<String> keys = new ArrayList<>(forcedOutcomes.size());
            for (RepositoryOperation operation : forcedOutcomes.keySet()) {
                keys.add(operation.key());
            }
            return keys;
        }
    }

    /**
     * A repository outcome a case forces, so that an {@code EVALUATE WS-RESP-CD} arm no seeded data
     * can reach is still driven.
     *
     * <p>{@code COUSR02C} illustrates why this is needed. Its read-for-update has three arms -
     * {@code DFHRESP(NORMAL)}, {@code DFHRESP(NOTFND)} and {@code WHEN OTHER} - and the third is
     * reachable only from a genuine I/O failure: a closed file, an invalid request, a length error.
     * No arrangement of seeded rows produces one. Forcing the outcome is therefore the only way to
     * drive that branch, and the {@code RESP} and {@code RESP2} values matter as well as the arm,
     * because the program emits them: {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}.
     *
     * @param outcome which arm to take, named with the vocabulary {@code common.FileStatus} already
     *     defines so that the case and the production code agree on what an outcome is
     * @param resp the {@code RESP} value the forced outcome reports, or {@code null} to let the
     *     harness use the outcome's own conventional value. {@code common.FileStatus} names them:
     *     {@code NORMAL} 0, {@code NOTFND} 13, {@code INVREQ} 16, {@code NOTOPEN} 19
     * @param resp2 the {@code RESP2} value, or {@code null} for zero
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"outcome", "resp", "resp2"})
    public record ForcedOutcome(
        @JsonProperty("outcome") FileStatus.Outcome outcome,
        @JsonProperty("resp") Integer resp,
        @JsonProperty("resp2") Integer resp2) {

        /**
         * Validates the forced outcome.
         *
         * @throws NullPointerException if {@code outcome} is absent
         * @throws IllegalArgumentException if a response code is negative
         */
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

    /**
     * The stimulus a case applies to its unit beyond its seeded data: the statuses a named call site
     * reports, the operations a called subprogram is asked to perform, the linkage values it is handed,
     * the condition codes preceding job steps left behind, and the environmental variants the program
     * runs under.
     *
     * <h2>Why this is a schema member and not adapter code</h2>
     * <p>Everything here used to live in Java, selected by {@code caseId} inside the test class: a
     * {@code switch} over {@code case01}..{@code case20} returning a private scenario record. Two things
     * were wrong with that, and both are the reason this type exists.
     *
     * <p><strong>The case file did not state its own inputs.</strong> A reader of
     * {@code parity/CBACT02C/case18.json} could see the fixture rows and the expected lines but not that
     * the {@code CLOSE} was arranged to be refused, so the file read as though it described an ordinary
     * full-file pass. The declarative gate is only declarative if the declaration is complete.
     *
     * <p><strong>{@link ParityCase#caseId()} is identity, not input.</strong> Once an adapter branches on
     * it, renumbering a case silently changes what the case does, two cases that differ only in Java
     * look identical in review - which is exactly how four duplicate pairs went unnoticed - and a case
     * file added without a matching Java arm either throws or, worse, falls into a default and asserts
     * the wrong run.
     *
     * <p>Every member is optional and normalises to empty, because most cases need none of it: their
     * whole stimulus is their seed. A case that needs one names it, and the name is validated here
     * rather than interpreted by the adapter.
     *
     * @param callSiteOutcomes the outcome each named call site reports, keyed by the call site's name -
     *     upper case with hyphens or underscores, as in {@code READ-XREFFILE} or {@code CLOSE_TRNXFILE}.
     *     This is how a case reaches a {@code WHEN OTHER} arm no arrangement of seeded rows can produce:
     *     a refused {@code OPEN}, a {@code FILE STATUS '37'} on a sequential read, a {@code '38'} from a
     *     {@code CLOSE}. Never {@code null}; empty for a case whose data reaches its own arms
     * @param operationScript the operations a called data-access subprogram is asked to perform, in the
     *     order the caller issues them. {@code CBSTM03B} is the reason it is a list: its whole contract
     *     is a DD name plus a one-character operation code, and the sequence of those calls is the
     *     behaviour under test. Never {@code null}; empty for a unit that is not driven this way
     * @param linkage the named values a case hands its unit across a linkage boundary - {@code LS-DATE}
     *     and {@code LS-DATE-FORMAT} for {@code CSUTLDTC}, {@code CCUP-OLD-DETAILS} and
     *     {@code CCUP-NEW-DETAILS} for {@code COCRDUPC}'s {@code 9200-WRITE-PROCESSING}. Keys are COBOL
     *     data names, so they are upper case and hyphenated, and values are the images the COBOL would
     *     hold. Never {@code null}; empty for a unit taking no linkage
     * @param stepStatuses the condition code each preceding job step left behind, keyed by step name.
     *     {@code app/jcl/CREASTMT.JCL} gates its steps with {@code COND=(0,NE)}, so "the step before this
     *     one ended with 8" is an input to the run and not a property of the data. Never {@code null}
     * @param environment the environmental variants the run is subject to, keyed by one of
     *     {@link #PERMITTED_ENVIRONMENT_KEYS}. A closed key set rather than a free bag: an unrecognised
     *     key would be a control the adapter silently ignores, which is the failure mode this whole type
     *     exists to remove. Never {@code null}
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({
        "callSiteOutcomes", "operationScript", "linkage", "stepStatuses", "environment"})
    public record UnitStimulus(
        @JsonProperty("callSiteOutcomes") Map<String, CallSiteOutcome> callSiteOutcomes,
        @JsonProperty("operationScript") List<ScriptedOperation> operationScript,
        @JsonProperty("linkage") Map<String, String> linkage,
        @JsonProperty("stepStatuses") Map<String, Integer> stepStatuses,
        @JsonProperty("environment") Map<String, String> environment) {

        /**
         * The environment keys a case may name.
         *
         * <p>Each is here because exactly one program's behaviour turns on it and no seeded row can
         * express it:
         * <ul>
         *   <li>{@code NULL_UCB_DDS} - the DD names whose TIOT entry carries no unit control block, which
         *       {@code CBSTM03A}'s control-block walk at {@code app/cbl/CBSTM03A.CBL:L262-L291} reports
         *       with a different literal. A comma-separated list of DD names.</li>
         *   <li>{@code MENU_TABLE_VARIANT} - which option table {@code COADM01C} or {@code COMEN01C} runs
         *       over, since the {@code OCCURS} slots past the populated range are storage rather than
         *       data and their content is not seeded from any dataset.</li>
         *   <li>{@code DATABASE_VARIANT} - the shape of the relational backing a case needs, for the
         *       programs whose repositories are reached through a real {@code JdbcTemplate} rather than a
         *       stub.</li>
         *   <li>{@code RECORDS_BEFORE_FAILURE} - how many records a browse delivers before the scripted
         *       failure, when the failing site is a loop rather than a single call.</li>
         * </ul>
         */
        public static final Set<String> PERMITTED_ENVIRONMENT_KEYS = Set.of(
            "NULL_UCB_DDS", "MENU_TABLE_VARIANT", "DATABASE_VARIANT", "RECORDS_BEFORE_FAILURE");

        /** A call-site name: upper case, alphanumeric, hyphen or underscore separated. */
        private static final Pattern CALL_SITE_NAME =
            Pattern.compile("[A-Z][A-Z0-9]*(?:[-_][A-Z0-9]+)*");

        /** A COBOL data name as a linkage key - upper case, alphanumeric, hyphen separated. */
        private static final Pattern LINKAGE_NAME = Pattern.compile("[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*");

        /** A JCL step name: upper case, alphanumeric, at most eight characters. */
        private static final Pattern STEP_NAME = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

        /** The stimulus a case that declares none carries, so no consumer null-checks the member. */
        public static final UnitStimulus NONE =
            new UnitStimulus(Map.of(), List.of(), Map.of(), Map.of(), Map.of());

        /**
         * Validates every member and freezes every collection.
         *
         * @throws IllegalArgumentException if a call-site name, linkage name, step name or environment
         *     key is malformed, if an environment key is not one of
         *     {@link #PERMITTED_ENVIRONMENT_KEYS}, or if a condition code is negative
         */
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

        /**
         * Whether this stimulus declares nothing at all.
         *
         * <p>Not a JSON member: it is derived from the five that are, and a bean-named boolean is exactly
         * the kind of accessor Jackson would otherwise write out as {@code "empty": true} and then refuse
         * to read back, because deserialisation here is strict by design.
         *
         * @return {@code true} when every member is empty
         */
        @JsonIgnore
        public boolean isEmpty() {
            return callSiteOutcomes.isEmpty() && operationScript.isEmpty() && linkage.isEmpty()
                && stepStatuses.isEmpty() && environment.isEmpty();
        }

        /**
         * The outcome a named call site is scripted to report.
         *
         * @param callSite the call-site name
         * @return that site's scripted outcome, or empty when the case scripts nothing there
         */
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

        /**
         * An environment value by its key.
         *
         * @param key one of {@link #PERMITTED_ENVIRONMENT_KEYS}
         * @return the declared value, or empty when the case declares none
         * @throws IllegalArgumentException if {@code key} is not a permitted key, so a typo in a
         *     consumer fails as loudly as a typo in a case file
         */
        public Optional<String> environmentValue(String key) {
            if (!PERMITTED_ENVIRONMENT_KEYS.contains(key)) {
                throw new IllegalArgumentException("Environment key '" + key + "' is not one of "
                    + new TreeSet<>(PERMITTED_ENVIRONMENT_KEYS) + ". Reading a key no case file may "
                    + "declare would always return empty, which is a control that silently does "
                    + "nothing.");
            }
            return Optional.ofNullable(environment.get(key));
        }

        /**
         * The condition code a named preceding step left behind.
         *
         * @param stepName the JCL step name
         * @return that step's condition code, or empty when the case declares none
         */
        public OptionalInt stepStatus(String stepName) {
            Integer declared = stepStatuses.get(stepName);
            return declared == null ? OptionalInt.empty() : OptionalInt.of(declared);
        }

        /**
         * Renders the stimulus by shape rather than by value.
         *
         * <p>Hand-written for the same reason {@link ParityCase#toString()} is: {@link #linkage} carries
         * record images, and a {@code CCUP-OLD-DETAILS} image is customer data.
         *
         * @return the declared keys and counts, never a value
         */
        @Override
        public String toString() {
            return "UnitStimulus[callSites=" + callSiteOutcomes.keySet()
                + ", operations=" + operationScript.size()
                + ", linkage=" + linkage.keySet()
                + ", steps=" + stepStatuses.keySet()
                + ", environment=" + environment.keySet() + ']';
        }

        /**
         * Validates and freezes the call-site outcomes.
         *
         * @param source the declared map, possibly {@code null}
         * @return an unmodifiable copy preserving declaration order
         */
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

        /**
         * Validates and freezes a map of COBOL-named string values.
         *
         * @param source the declared map, possibly {@code null}
         * @param shape the pattern every key must match
         * @param member the member name, quoted in a failure
         * @param expected what a well-formed key looks like, quoted in a failure
         * @return an unmodifiable copy preserving declaration order
         */
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

        /**
         * Validates and freezes the preceding-step condition codes.
         *
         * @param source the declared map, possibly {@code null}
         * @return an unmodifiable copy preserving declaration order
         */
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

        /**
         * Validates and freezes the environment, refusing any key outside the closed set.
         *
         * @param source the declared map, possibly {@code null}
         * @return an unmodifiable copy preserving declaration order
         */
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

        /**
         * Requires a map key to be present and to match its declared shape.
         *
         * @param value the key
         * @param shape the pattern it must match
         * @param member the member name, quoted in a failure
         * @param expected what a well-formed key looks like, quoted in a failure
         * @return the key, stripped
         */
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

    /**
     * What one named call site reports when the case reaches it.
     *
     * <p>Four members rather than one status, because a call site fails in more than one way and the
     * differences are behavioural. A dataset that cannot be opened at all takes a different arm from one
     * whose {@code OPEN} reports a non-zero response; a browse that fails on its fourth read has already
     * displayed three records, and those three lines are part of the expectation.
     *
     * @param status the two-character {@code FILE STATUS} the site reports - {@code "00"}, {@code "10"},
     *     {@code "23"}, {@code "37"}, {@code "38"} - or {@code null} when the site reports a CICS
     *     {@code RESP} instead
     * @param resp the CICS {@code RESP} value the site reports, or {@code null} when it reports a
     *     {@code FILE STATUS}. {@code common.FileStatus} names the conventional values
     * @param refused {@code true} when the site is refused outright rather than reporting a status - an
     *     unreachable dataset, which is not the same observable event as an open that reports one.
     *     {@code null} normalises to {@code false}
     * @param afterRecords how many records the site delivers before it reports, for a site inside a
     *     loop; {@code null} for a site that reports on its first call
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"status", "resp", "refused", "afterRecords"})
    public record CallSiteOutcome(
        @JsonProperty("status") String status,
        @JsonProperty("resp") Integer resp,
        @JsonProperty("refused") Boolean refused,
        @JsonProperty("afterRecords") Integer afterRecords) {

        /** A COBOL {@code FILE STATUS}: exactly two characters, digits or upper-case letters. */
        private static final Pattern FILE_STATUS = Pattern.compile("[0-9A-Z]{2}");

        /**
         * Validates the outcome.
         *
         * @throws IllegalArgumentException if the status is not two characters, if a response code or a
         *     record count is negative, or if the site declares nothing at all
         */
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

        /**
         * Whether the site was refused outright.
         *
         * <p>Not a JSON member of its own: {@code refused} is already a component, and a second
         * bean-named accessor for the same name is a serialisation conflict waiting to happen.
         *
         * @return {@code true} for an unreachable dataset
         */
        @JsonIgnore
        public boolean isRefused() {
            return Boolean.TRUE.equals(refused);
        }

        /**
         * How many records precede the report.
         *
         * @return the declared count, or zero when the site reports on its first call
         */
        public int recordsBefore() {
            return afterRecords == null ? 0 : afterRecords;
        }
    }

    /**
     * One operation in a called subprogram's script: the DD name, the operation code, and the key it is
     * given.
     *
     * <p>{@code CBSTM03B} is why this exists. Its whole contract is
     * {@code 01 LK-M03B-AREA} - a DD name, a one-character operation code with six {@code 88}-level
     * spellings, a two-character return code, a key and a key length - and
     * {@code PROCEDURE DIVISION USING LK-M03B-AREA} dispatches on the DD name. The sequence of calls is
     * the behaviour under test, so it belongs in the case file rather than in a Java table keyed by
     * case identifier.
     *
     * @param dd the DD name the operation addresses, as {@code LK-M03B-DD} carries it; required
     * @param operation the operation code, one of the {@code 88}-level spellings {@code O}, {@code C},
     *     {@code R}, {@code K}, {@code W} or {@code Z}; required
     * @param key the key the operation is given, as {@code LK-M03B-KEY} carries it; {@code null} for an
     *     operation that takes none
     * @param keyLength the key length, as {@code LK-M03B-KEY-LN} carries it; {@code null} for an
     *     operation that takes no key
     * @param status the two-character value the caller hands in as {@code LK-M03B-RC} before issuing the
     *     call, or {@code null} to carry forward whatever the previous call returned. The distinction is
     *     load-bearing rather than cosmetic: the subroutine's three fall-through exits at
     *     {@code app/cbl/CBSTM03B.cbl:151-152}, {@code :200-201} and their siblings move the DD's own
     *     {@code FILE STATUS} area into {@code LK-M03B-RC} whatever happened, so a call that carries a
     *     stale value in is the only way to observe an exit that assigns nothing new
     * @param primesRecordArea whether the caller issues {@code MOVE SPACES TO WS-M03B-FLDT} before the
     *     call, as {@code app/cbl/CBSTM03A.CBL:L350}, {@code :L745} and {@code :L834} do and
     *     {@code :L857-860} conspicuously does not; {@code null} means it does not, which is the reading
     *     that makes "at end of file the record area is returned unchanged" an assertion with content.
     *     Two dimensions rather than one because the two are independent in the source: a caller may
     *     prime the status and keep the record area, which is exactly what {@code :L857-860} does
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

        /** The six operation codes {@code CBSTM03B}'s {@code 88}-levels declare. */
        public static final Set<String> PERMITTED_OPERATIONS = Set.of("O", "C", "R", "K", "W", "Z");

        /** A DD name: upper case, alphanumeric, at most eight characters. */
        private static final Pattern DD_NAME = Pattern.compile("[A-Z][A-Z0-9]{0,7}");

        /**
         * Validates the operation.
         *
         * @throws IllegalArgumentException if the DD name is malformed, if the operation code is not one
         *     of {@link #PERMITTED_OPERATIONS}, if the key length is not positive, or if the status is
         *     not two characters
         */
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

        /**
         * Whether the caller blanks the record area before this call.
         *
         * <p>Declared as a boxed {@code Boolean} so a case file may omit it, and read through this
         * accessor so every consumer sees the same reading of an omission: the caller does <em>not</em>
         * blank it, and the area arrives holding whatever the previous call returned.
         *
         * @return {@code true} only when the case declares it
         */
        public boolean primesRecordAreaOrDefault() {
            return Boolean.TRUE.equals(primesRecordArea);
        }
    }

    /**
     * One repository operation a case expects the run to issue, against the record it names.
     *
     * <h2>Why an ordered operation expectation exists at all</h2>
     * <p>A fingerprint records what each dataset held afterwards. It cannot distinguish a record removed
     * by the keyless {@code EXEC CICS DELETE} of {@code app/cbl/COUSR03C.cbl:307-311} - which acts on
     * whatever the task is holding from a preceding {@code READ ... UPDATE} - from the same record
     * removed by a delete-by-key, an operation no program in this application performs. The end state is
     * identical and the behaviour is not, so the <em>sequence</em> has to be an expectation of its own.
     *
     * <p>It is a declared expectation rather than a Java-side list for the same reason every other
     * expectation is: a case that stated its sequence in Java would be a case whose file did not describe
     * what it asserts.
     *
     * @param dataset the binding key of the dataset the operation addresses; required
     * @param operation which operation, named with the same vocabulary a forced outcome uses; required
     * @param key the key of the record the operation addressed, or {@code null} for an operation that
     *     carries none - a browse start, or the keyless delete of a held record, whose identity is stated
     *     by the read that preceded it
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "operation", "key"})
    public record ExpectedOperation(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("operation") RepositoryOperation operation,
        @JsonProperty("key") String key) {

        /**
         * Validates the operation.
         *
         * @throws IllegalArgumentException if the dataset is not a binding key
         * @throws NullPointerException if the operation is absent
         */
        @JsonCreator
        public ExpectedOperation {
            dataset = requireDatasetKey(dataset, "expectedOperations.dataset");
            operation = Objects.requireNonNull(operation, "ExpectedOperation.operation is required: "
                + "name one of the RepositoryOperation constants, so the case and the repository agree "
                + "on what operation is being expected");
            key = requireNullOrText(key, "expectedOperations.key");
        }

        /**
         * Renders the operation without disclosing the key.
         *
         * <p>The key of a {@code USRSEC} record is a user id rather than a credential, but the rule is
         * applied uniformly: no expectation type in this model prints a value.
         *
         * @return the dataset and the operation
         */
        @Override
        public String toString() {
            return "ExpectedOperation[" + dataset + '.' + operation.key()
                + (key == null ? "" : ", keyed") + ']';
        }
    }

    /**
     * The typed expectation for an online unit's response: what the screen carried, where the
     * conversation went next, and how the transaction ended.
     *
     * <h2>Why the response is a first-class expectation</h2>
     * <p>An online program's observable behaviour is mostly its response. Of the seventeen
     * translated programs, several paths write no dataset at all and are <em>entirely</em>
     * response - the no-commarea guard, an empty-field rejection, an invalid key. Before this type
     * existed a case could only assert datasets, the return code and emitted lines, so every one of
     * those paths could have returned the wrong {@code nextProgram}, the wrong screen text, the
     * wrong cursor or no response at all and still produced a diff count of zero. That is the
     * defect this closes.
     *
     * <p>{@link #sends()} is a <strong>list</strong> because the send count is itself behaviour.
     * Several {@code COUSR02C} paths perform {@code SEND-USRUPD-SCREEN} more than once in one
     * invocation - an inherited quirk of the program, not a translation artefact - and a model
     * carrying one screen state would have to discard all but one of them and could never assert
     * that the quirk was preserved.
     *
     * @param nextProgram the program the response names as the next target, which is what
     *     {@code EXEC CICS XCTL PROGRAM(...)} becomes in a stateless translation; {@code null} on a
     *     path that returns to the same transaction
     * @param nextMapset the mapset the response names, at most seven characters as
     *     {@code CDEMO-LAST-MAPSET} carries; may be {@code null}
     * @param nextMap the map the response names, at most seven characters as
     *     {@code CDEMO-LAST-MAP} carries; may be {@code null}
     * @param navigation the expected {@code CARDDEMO-COMMAREA} field values keyed by copybook name.
     *     This is where statelessness is asserted: the conversation state travels in the payload,
     *     so it is comparable, and a translation that kept it in a session could not satisfy this
     * @param sends every screen send in order, each carrying that send's BMS payload and attribute
     *     metadata; never {@code null}, and empty for a path that sends no map
     * @param cursorField the {@code xxxL} item that received {@code MOVE -1}, which is how COBOL
     *     positions the cursor; {@code null} when no cursor was set
     * @param termination how the transaction ended; never {@code null}
     */
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

        /**
         * Validates and freezes the expectation.
         *
         * @throws NullPointerException if {@code termination} is absent
         * @throws IllegalArgumentException if a program, mapset, map or cursor name is malformed, or
         *     if a navigation key is not a COMMAREA field name
         */
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

        /**
         * How many screen sends this expectation pins - behaviour in its own right.
         *
         * @return {@code sends().size()}
         */
        @JsonIgnore
        public int sendCount() {
            return sends.size();
        }

        /**
         * Renders the expectation without printing a screen field value.
         *
         * @return the shape of this expectation
         */
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
     * @param fields the {@code xxxO} output items keyed by their symbolic-map names, each at the
     *     full declared width of its {@code PICTURE} clause - {@code ERRMSGO} is
     *     {@code PIC X(78)} and an expectation of 78 characters is what a faithful send produces,
     *     even though the {@code WS-MESSAGE} it came from is {@code PIC X(80)}
     * @param attributes the attribute items keyed by their symbolic-map names, valued with the
     *     {@code DFHBMSCA} or {@code DFHATTR} mnemonic the program moved into them - so
     *     {@code ERRMSGC} carries {@code DFHRED}, {@code DFHNEUTR} or {@code DFHGREEN} rather than a
     *     raw byte. Colour is not decoration here: it is how the program distinguishes an error from
     *     a prompt from a confirmation, and it is asserted as behaviour
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"fields", "attributes"})
    public record ScreenSend(
        @JsonProperty("fields") Map<String, String> fields,
        @JsonProperty("attributes") Map<String, String> attributes) {

        /**
         * Validates and freezes the send.
         *
         * @throws IllegalArgumentException if a field key is not an {@code xxxO} item, if an
         *     attribute key is not an attribute item, if an attribute value is not a known mnemonic,
         *     or if the send pins nothing at all
         */
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

        /**
         * Renders the send without printing a field value, because {@code PASSWDO} is one of them.
         *
         * @return the shape of this send
         */
        @Override
        public String toString() {
            return "ScreenSend[fields=" + fields.keySet() + ", attributes=" + attributes + ']';
        }
    }

    /**
     * How an online invocation ended - the three exits CICS actually gives these programs.
     *
     * <p>The distinction is behavioural rather than cosmetic. {@code EXEC CICS XCTL} transfers
     * control and never returns, so the {@code EXEC CICS RETURN TRANSID(...)} that follows it in the
     * source is simply not reached - a translation that performed both would have invented a
     * behaviour, and one that performed neither would have lost the navigation. A <em>bare</em>
     * {@code EXEC CICS RETURN} is a third outcome again: it names no transaction, so the
     * pseudo-conversation ends rather than continuing, and the client has nothing to send the
     * commarea back to.
     */
    public enum Termination {

        /**
         * Control transferred to another program. In a stateless translation this is a response
         * field naming the next target, resolved by the client, with the commarea travelling in the
         * payload.
         */
        XCTL,

        /**
         * The transaction returned to itself, pseudo-conversationally, with the commarea carried
         * forward - {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}.
         */
        RETURN_TRANSID,

        /**
         * The bare {@code EXEC CICS RETURN} - no {@code TRANSID} and no {@code COMMAREA} - so the
         * conversation <strong>ends</strong> and the terminal is released.
         *
         * <p>Reached from a {@code SEND-PLAIN-TEXT} paragraph, which transmits unformatted text and
         * then returns from inside itself. {@code app/cbl/COSGN00C.cbl:162-172} is the worked
         * example: the PF3 arm at {@code :88-90} performs it, and because the {@code RETURN} sits at
         * {@code :171-172} the {@code EXEC CICS RETURN TRANSID(WS-TRANID)
         * COMMAREA(CARDDEMO-COMMAREA)} at {@code :98-102} is <em>unreachable</em> on that path.
         * Three other programs declare the same paragraph - {@code COACTVWC}, {@code COCRDLIC} and
         * {@code COCRDSLC}.
         *
         * <p>This constant exists because neither of the other two can express that exit without
         * misstating it. {@code XCTL} would claim a transfer that never happened, and
         * {@code RETURN_TRANSID} would claim the pseudo-conversation continues - which is precisely
         * the normalisation the parity contract exists to prevent. The name is
         * {@code user.SignOnService.Termination.RETURN_NO_TRANSID} verbatim, so the model and the
         * production code name the same exit the same way rather than through a translation table.
         */
        RETURN_NO_TRANSID
    }

    /**
     * One record expectation, pinned field by field and optionally as a whole record image.
     *
     * <h2>Which list it appears in decides what it means</h2>
     * <p>The same shape serves both record expectations, and the list it belongs to says which
     * concept it expresses:
     * <ul>
     *   <li>In {@link ParityCase#expectedWrites()}, {@code rowIndex} is the <strong>0-based
     *       position in that dataset's write sequence</strong>. Write order is behaviour: a job that
     *       posts a transaction and then rewrites an account balance has done something different
     *       from one that does it the other way round.</li>
     *   <li>In {@link ParityCase#expectedFinalState()}, {@code rowIndex} is the <strong>0-based row
     *       index within the dataset</strong> once the unit has finished.</li>
     * </ul>
     *
     * <h2>Field names are the COBOL names, verbatim</h2>
     * <p>The keys of {@code fields} are copybook field names copied exactly as the copybook spells
     * them. This is not a stylistic preference, it is the contract that makes field-for-field
     * diffing possible, and it has three consequences worth stating outright:
     * <ul>
     *   <li>{@code ACCT-EXPIRAION-DATE} is <strong>misspelled in {@code app/cpy/CVACT01Y.cpy}</strong>
     *       and must be misspelled here too. Correcting it to {@code ACCT-EXPIRATION-DATE} would
     *       leave the diff looking for a field the decoder never produces - a silent parity
     *       break rather than a loud one.</li>
     *   <li>{@code CUSTREC}'s {@code CUST-DOB-YYYYMMDD} is deliberately distinct from
     *       {@code CVCUS01Y}'s {@code CUST-DOB-YYYY-MM-DD}. The two copybooks describe near-identical
     *       layouts under different field names, and collapsing them would discard a name the diff
     *       depends on.</li>
     *   <li>{@code FILLER} spans are addressable fields, not gaps. They must be emitted as spaces
     *       and they carry the record's total width; omitting one shifts every offset after it.
     *       Where a single record declares more than one {@code FILLER} span, the fixture
     *       disambiguates with an ordinal suffix - {@code FILLER}, {@code FILLER-2} and so on -
     *       because a JSON object cannot hold a duplicate key. {@code FieldDiffer} owns that
     *       convention.</li>
     * </ul>
     *
     * <h2>Pinning the whole record</h2>
     * <p>{@code expectedBytes} pins the complete record image, which is how a case asserts the
     * <em>total width</em> as well as the field values - and therefore how it proves that
     * {@code FILLER} was emitted at all, since a record missing a {@code FILLER} span is short by
     * exactly that span. The widths a case may pin, each fixed by its copybook or its JCL:
     * Account 300, Card 150, CardXref 50, Customer 500, Transaction 350, DalyTran 350, TranCatBal
     * 50, DisclosureGroup 50, TranType 60, TranCategory 60, SecUser 80 and Trnx 350; and for
     * generated output, {@code DALYREJS} 430, {@code TRANREPT} 133, {@code STMTFILE} 80 and
     * {@code HTMLFILE} <strong>100</strong>. That last one is worth care: {@code CREASTMT.JCL}
     * declares {@code HTMLFILE} twice, at {@code LRECL=80} in the pre-delete step and at
     * {@code LRECL=100} in the step that creates it. The creating step is authoritative, so 100
     * wins and 80 is the losing declaration.
     *
     * <p>An {@code expectedBytes} value consisting entirely of spaces is legitimate - a record whose
     * every span is {@code FILLER} or blank looks exactly like that - so only a zero-length value is
     * rejected. The same applies to individual field values: all-spaces is a real expectation for a
     * {@code FILLER} span or a cleared {@code PIC X} field, and is accepted as one.
     *
     * <p>At least one of {@code fields} and {@code expectedBytes} must be populated. An expectation
     * that pinned neither would assert nothing while inflating the apparent case count, and a case
     * that passes because it checks nothing is worse than no case at all.
     *
     * @param dataset the dataset binding key - never a literal dataset name
     * @param rowIndex the <strong>zero-based</strong> write position or row index, as described
     *     above; never negative. <strong>Mandatory</strong>, and boxed for exactly that reason: as a
     *     primitive {@code int} an omitted {@code "rowIndex"} key bound silently to {@code 0}, so an
     *     expectation meant for another row of a dataset silently addressed the first one, and either
     *     passed against a record nobody meant to pin or reported a difference nobody could act on
     * @param fields copybook field name to expected value, verbatim and in declaration order; never
     *     {@code null}, and empty only when {@code expectedBytes} is present
     * @param expectedBytes the complete expected record image, or {@code null} when the case pins
     *     individual fields only; never zero-length, though it may be entirely spaces
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "rowIndex", "fields", "expectedBytes"})
    public record ExpectedRecord(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("rowIndex") Integer rowIndex,
        @JsonProperty("fields") Map<String, String> fields,
        @JsonProperty("expectedBytes") String expectedBytes) {

        /**
         * Validates the expectation and freezes the field map.
         *
         * @throws NullPointerException if {@code rowIndex} is {@code null}, which is what an absent
         *     JSON member arrives as
         * @throws IllegalArgumentException if {@code dataset} is missing or is not a binding key, if
         *     {@code rowIndex} is negative, if a field name is blank, if a field value is
         *     {@code null}, if {@code expectedBytes} is zero-length, or if the expectation pins
         *     nothing at all
         */
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

        /**
         * Renders the expectation without printing a value.
         *
         * <p>A {@code USRSEC} record image carries {@code SEC-USR-PWD} at offset 48, and the field
         * map carries it by name, so neither is emitted. {@code FieldDiffer} renders values through
         * {@link Redaction} when it has to show them; this summary does not need to.
         *
         * @return the dataset, row and the names pinned - never a value
         */
        @Override
        public String toString() {
            return "ExpectedRecord[" + dataset + " row " + rowIndex + ", fields=" + fields.keySet()
                + ", expectedBytes=" + (expectedBytes == null ? "absent"
                    : expectedBytes.length() + " char(s)") + ']';
        }
    }

    /**
     * Which of the two record channels a dataset-level expectation is about.
     *
     * <p>The two are not interchangeable and a case has to say which it means. "The reject file was
     * created and nothing was written to it" is an assertion about {@link #WRITES}; "the account file
     * still holds its fifty rows" is an assertion about {@link #FINAL_STATE}. A dataset commonly appears
     * on both, with different counts.
     */
    public enum DatasetChannel {

        /**
         * The records the unit wrote, in write order - what a {@code WRITE} or a {@code REWRITE}
         * produced.
         */
        WRITES,

        /** What a dataset holds after the run, row by row, whether or not the unit wrote to it. */
        FINAL_STATE
    }

    /**
     * A dataset-level expectation: that a dataset exists on a channel, and exactly how many rows it
     * holds there.
     *
     * <h2>What a row-level expectation cannot say</h2>
     * <p>Every other expectation in this model addresses a <em>row</em>, and there is one statement no
     * collection of row expectations can make: <strong>this dataset was opened, or created, and
     * remained empty.</strong> An empty {@code expectedWrites} list asserts that nothing was written
     * <em>anywhere</em>, which is a different and much weaker claim - it cannot distinguish a job that
     * created its reject file and wrote no reject from one that never opened it at all. Both of those
     * are real behaviours of this system, and the difference between them is exactly what a
     * {@code COND} gate downstream of the job reacts to.
     *
     * <p>The reverse gap is just as sharp on the observed side. A dataset that a unit opened and left
     * empty has zero rows, so no row-driven scan can reach it, and a differ that reports it as
     * unexpected output is reporting correct behaviour as a difference. Naming the dataset here is what
     * makes that behaviour assertable rather than merely tolerable.
     *
     * <p>{@code rowCount} is <strong>mandatory and may be zero</strong>. Zero is the whole point: it is
     * the only value that turns "I have no expectation for this dataset" into "I assert this dataset
     * produced nothing", and boxing it is what stops an omitted member from claiming that by accident.
     *
     * <p>A dataset-level expectation <em>adds</em> to the row-level ones rather than replacing them: a
     * case that declares {@code rowCount} 3 and pins two of the rows still has its third row reported as
     * unaccounted for. That is deliberate - a count is not a substitute for reading the bytes.
     *
     * @param dataset the dataset binding key - never a literal dataset name
     * @param channel which record channel the expectation is about; never {@code null}
     * @param rowCount how many rows the dataset holds on that channel; mandatory, never negative, and
     *     {@code 0} to assert that it exists and produced nothing
     * @param recordLength the declared record width the observed dataset must report, or {@code null}
     *     to leave the width unasserted. Stating it is how a case pins the identity of a dataset it
     *     expects to be empty, since an empty dataset has no row whose width could be checked
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "channel", "rowCount", "recordLength"})
    public record ExpectedDataset(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("channel") DatasetChannel channel,
        @JsonProperty("rowCount") Integer rowCount,
        @JsonProperty("recordLength") Integer recordLength) {

        /**
         * Validates the expectation.
         *
         * @throws NullPointerException if {@code channel} or {@code rowCount} is absent
         * @throws IllegalArgumentException if {@code dataset} is not a binding key, if
         *     {@code rowCount} is negative, or if {@code recordLength} is present and below 1
         */
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

        /**
         * Asserts that a dataset exists on a channel and holds no row.
         *
         * @param dataset the dataset binding key
         * @param channel the channel the assertion is about
         * @param recordLength the width the dataset must report
         * @return the expectation
         */
        public static ExpectedDataset empty(String dataset, DatasetChannel channel,
                                            int recordLength) {
            return new ExpectedDataset(dataset, channel, 0, recordLength);
        }

        /**
         * Asserts that a dataset exists on a channel and holds exactly that many rows.
         *
         * @param dataset the dataset binding key
         * @param channel the channel the assertion is about
         * @param rowCount the exact row count, which may be zero
         * @return the expectation, with the width left unasserted
         */
        public static ExpectedDataset of(String dataset, DatasetChannel channel, int rowCount) {
            return new ExpectedDataset(dataset, channel, rowCount, null);
        }

        /**
         * Renders the expectation, which carries no value and so needs no masking.
         *
         * @return the dataset, the channel, the count and the width
         */
        @Override
        public String toString() {
            return "ExpectedDataset[" + dataset + " on " + channel + ", rowCount=" + rowCount
                + ", recordLength=" + (recordLength == null ? "unasserted" : recordLength) + ']';
        }
    }

    /**
     * One line the unit emitted, together with the channel that fixes its width.
     *
     * <h2>Why a channel is required</h2>
     * <p>Three different things were previously written into one list of plain strings, and nothing
     * distinguished them: a COBOL {@code DISPLAY} line, whose width is whatever it is; the 80-byte
     * {@code WS-MESSAGE} working-storage field; and the 78-byte {@code ERRMSGO} screen field that
     * {@code WS-MESSAGE} is truncated into on its way to the map. The three are related but not
     * equal - {@code MOVE WS-MESSAGE TO ERRMSGO} loses the last two bytes - so an expectation that
     * did not say which one it meant could not be checked. Naming the channel makes the width part
     * of the expectation and lets it be validated at load time.
     *
     * <p>Screen text belongs in {@link ScreenSend#fields()} rather than here: this list is for lines
     * a program <em>emits</em>, which in practice means {@code DISPLAY}.
     *
     * @param channel which channel the line was emitted on; never {@code null}
     * @param text the line, byte-exact and never trimmed. An empty string is a legitimate
     *     expectation on a variable-width channel, because a COBOL {@code DISPLAY} of a blank line
     *     emits one
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"channel", "text"})
    public record EmittedMessage(
        @JsonProperty("channel") MessageChannel channel,
        @JsonProperty("text") String text) {

        /**
         * Validates the message against its channel's declared width.
         *
         * @throws NullPointerException if either member is absent
         * @throws IllegalArgumentException if the text is not exactly the channel's fixed width
         */
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

        /**
         * Renders the message with its channel and length, and its text via {@link Redaction}.
         *
         * @return the rendered message
         */
        @Override
        public String toString() {
            return "EmittedMessage[" + channel + ", len=" + text.length() + ", '"
                + Redaction.maskIfSensitiveText(text) + "']";
        }
    }

    /**
     * The channels a message can be emitted on, each carrying the width that makes it comparable.
     */
    public enum MessageChannel {

        /**
         * A COBOL {@code DISPLAY}. Variable width, because the verb concatenates its operands and
         * emits the result: {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} produces one
         * 28-character line from a five-character literal, a nine-digit {@code COMP} field, a
         * five-character literal and another nine-digit field.
         */
        DISPLAY_LINE(0, "a COBOL DISPLAY is as wide as the operands it concatenates"),

        /**
         * The 80-byte working-storage message field - {@code WS-MESSAGE PIC X(80)} in every one of
         * the seventeen online programs. A message moved into it is space-padded to 80.
         */
        WS_MESSAGE_80(80, "WS-MESSAGE is declared PIC X(80) in the program's WORKING-STORAGE"),

        /**
         * The 78-byte screen error field - {@code ERRMSGO PIC X(78)} in the symbolic map.
         * {@code MOVE WS-MESSAGE TO ERRMSGO} truncates the 80-byte field to 78, and those two lost
         * bytes are exactly why this channel is distinct from {@link #WS_MESSAGE_80}.
         */
        SCREEN_ERRMSG_78(78, "ERRMSGO is declared PIC X(78) in the symbolic map, so the 80-byte "
            + "WS-MESSAGE is truncated by two bytes on its way to the screen");

        /** The channel's fixed width, or zero when it is variable. */
        private final int fixedWidth;

        /** The COBOL declaration that fixes the width, quoted in a validation failure. */
        private final String declaration;

        /**
         * Binds a channel to the declaration that fixes its width.
         *
         * @param fixedWidth the width in characters, or zero for a variable-width channel
         * @param declaration the COBOL declaration the width comes from
         */
        MessageChannel(int fixedWidth, String declaration) {
            this.fixedWidth = fixedWidth;
            this.declaration = declaration;
        }

        /**
         * The channel's fixed width.
         *
         * @return the width in characters, or zero when the channel is variable width
         */
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

    /**
     * A width normalisation, <strong>bound to the dataset it applies to</strong>.
     *
     * <h2>Why the dataset is part of the declaration</h2>
     * <p>A normalisation used to be a bare marker carrying only a pair of widths, and it fired
     * whenever those widths happened to match. That is too loose to be safe. Two of the datasets in
     * this system are 50 bytes wide - {@code CVACT03Y}'s cross-reference record and
     * {@code CVTRA01Y}'s category balance - so a case that declared the cross-reference pad and then
     * produced a genuinely mis-sized category-balance record would have had that second, unrelated
     * defect padded away and reported clean. Naming the dataset makes the declaration say what it
     * means: <em>this</em> dataset's rows are short by <em>this</em> span.
     *
     * @param dataset the dataset binding key whose seeded rows are normalised - and which
     *     {@link ParityCase} requires to be one the case actually seeds, because a normalisation for
     *     a dataset that is never seeded is at best noise and at worst a misunderstanding
     * @param kind which of the two recorded fixture-to-copybook deviations this is
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "kind"})
    public record DatasetNormalisation(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("kind") Normalisation kind) {

        /**
         * Validates the binding.
         *
         * @throws NullPointerException if {@code kind} is absent
         * @throws IllegalArgumentException if {@code dataset} is not a binding key
         */
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

        /**
         * Applies this normalisation to one seeded row, at seed time and before anything decodes it.
         *
         * @param row the row exactly as the case declares it
         * @return the row at its copybook width
         * @throws IllegalArgumentException if the row is neither the source nor the target width
         */
        public String normaliseSeedRow(String row) {
            return kind.normaliseSeedRow(row, dataset);
        }

        /**
         * Applies this normalisation to every seeded row of its dataset.
         *
         * @param rows the rows exactly as the case declares them, in seeding order
         * @return the rows at their copybook width, in the same order
         * @throws IllegalArgumentException if any row is neither the source nor the target width
         */
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
     *
     * <h2>This is the sole owner of the pad, and it applies it once, at seed time</h2>
     * <p>Both deviations exist because a trailing span is simply absent from the data. A row like
     * that cannot be decoded as it stands - {@code SecUserRecord.decode} requires exactly 80 bytes
     * and rejects a short row rather than partially decoding it - so the shortfall must be made up
     * <strong>before</strong> the row reaches a repository, not afterwards. Padding it here, at
     * seeding time, means the whole system downstream sees only full-width records, and
     * {@code FieldDiffer} is free to treat any width disagreement it meets as a genuine difference.
     *
     * <p>The pad is on the <strong>right</strong> and the pad character is a space, which is what
     * COBOL itself writes into an unset {@code PIC X} span. It is applied in character space here;
     * the byte-level pad, where a space is {@code 0x20} under {@code US-ASCII} and {@code 0x40}
     * under {@code IBM037}, belongs to {@code common.FixedWidthCodec}.
     *
     * <p>The operation is <strong>idempotent</strong>: a row already at the target width is returned
     * unchanged, so applying a normalisation twice cannot double-pad. Any other width is refused,
     * because a row that is neither the documented short width nor the copybook width is a defect in
     * the case rather than something to repair silently.
     *
     * <p>These are the only two constants, and no third may be added. A normalisation is a recorded,
     * evidenced deviation between a fixture and its copybook - not a general-purpose escape hatch for
     * a width mismatch. Each constant carries its own widths, its copybook and the name of the absent
     * span, so that a reviewer can check the arithmetic without leaving this file.
     */
    public enum Normalisation {

        /**
         * Right-pads a card cross-reference row from 36 to 50 bytes with 14 spaces.
         *
         * <p>{@code app/cpy/CVACT03Y.cpy} declares {@code CARD-XREF-RECORD} as
         * {@code XREF-CARD-NUM PIC X(16)} + {@code XREF-CUST-ID PIC 9(09)} +
         * {@code XREF-ACCT-ID PIC 9(11)} + {@code FILLER PIC X(14)}, which is 50 bytes. The rows in
         * {@code cardxref.txt} measure exactly 36 - {@code 16 + 9 + 11}, with the trailing
         * {@code FILLER} span absent - so the 14 bytes it occupies must be supplied as spaces.
         */
        CARDXREF_FILLER_PAD_36_TO_50(36, 50, "CVACT03Y", "FILLER PIC X(14)",
            Set.of("CCXREF", "CXACAIX", "XREFFILE", "XREFFIL1", "CARDXREF")),

        /**
         * Right-pads a security-user row from 57 to 80 bytes with 23 spaces.
         *
         * <p>{@code app/cpy/CSUSR01Y.cpy} declares {@code SEC-USER-DATA} as
         * {@code SEC-USR-ID PIC X(08)} + {@code SEC-USR-FNAME PIC X(20)} +
         * {@code SEC-USR-LNAME PIC X(20)} + {@code SEC-USR-PWD PIC X(08)} +
         * {@code SEC-USR-TYPE PIC X(01)} + {@code SEC-USR-FILLER PIC X(23)}, which is 80 bytes. The
         * in-stream rows in {@code app/jcl/DUSRSECJ.jcl} measure exactly 57 -
         * {@code 8 + 20 + 20 + 8 + 1}, with {@code SEC-USR-FILLER} absent - while the very same job
         * writes them to a file declared {@code DCB=(LRECL=80,RECFM=FB)} and then defines the VSAM
         * cluster with {@code RECORDSIZE(80,80) KEYS(8,0)}. The record is 80 bytes on disk; the
         * literal data is 57; the 23-byte difference is the missing filler.
         */
        USRSEC_FILLER_PAD_57_TO_80(57, 80, "CSUSR01Y", "SEC-USR-FILLER PIC X(23)",
            Set.of("USRSEC"));

        /** The character COBOL writes into an unset {@code PIC X} span. */
        private static final char PAD_CHARACTER = ' ';

        /** The literal row width present in the fixture. */
        private final int sourceWidth;

        /** The copybook-declared record width the fixture must be padded up to. */
        private final int targetWidth;

        /** The {@code app/cpy} member that declares {@link #targetWidth}. */
        private final String copybook;

        /** The COBOL declaration of the span the fixture omits. */
        private final String absentSpan;

        /**
         * Every dataset binding key whose records {@link #copybook} describes, and therefore the only
         * datasets this normalisation may ever be applied to.
         *
         * <p>This binding is what makes the widths safe to act on. A width pair alone does not
         * identify a record: four of this system's datasets declare a 50-byte record - the card
         * cross-reference, the transaction category balance, the disclosure group and the
         * cross-reference again under its batch DD names - so a rule authorised by "measured 36,
         * declared 50" would fire on a 36-byte row seeded into <em>any</em> of them and silently
         * supply fourteen spaces that the record it actually landed in has no such span for. Naming
         * the datasets a copybook describes closes that hole: a normalisation can only ever repair the
         * record it was derived from.
         *
         * <p>The cross-reference appears under five keys because {@code application.yml} binds the one
         * VSAM cluster to its CICS file name {@code CCXREF}, its alternate-index path
         * {@code CXACAIX}, and the batch {@code DD} names {@code XREFFILE}, {@code XREFFIL1} and
         * {@code CARDXREF} - {@code app/jcl/INTCALC.jcl} opens the base and the path in one step, which
         * is why two of them coexist. {@code USRSEC} has one key and one alias.
         */
        private final Set<String> datasets;

        /**
         * Binds a normalisation to the evidence that justifies it, so the widths live beside the
         * copybook that fixes them rather than in whichever class happens to apply the pad.
         *
         * @param sourceWidth the literal fixture row width
         * @param targetWidth the copybook-declared record width
         * @param copybook the {@code app/cpy} member name, without extension
         * @param absentSpan the omitted span's name and picture clause
         * @param datasets every dataset binding key whose records {@code copybook} describes
         */
        Normalisation(int sourceWidth, int targetWidth, String copybook, String absentSpan,
                      Set<String> datasets) {
            this.sourceWidth = sourceWidth;
            this.targetWidth = targetWidth;
            this.copybook = copybook;
            this.absentSpan = absentSpan;
            this.datasets = Set.copyOf(datasets);
        }

        /**
         * The literal row width found in the fixture.
         *
         * @return the width in bytes before normalisation
         */
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

        /**
         * How many space characters the pad adds - the width of {@link #absentSpan()}.
         *
         * @return {@link #targetWidth()} minus {@link #sourceWidth()}
         */
        public int padWidth() {
            return targetWidth - sourceWidth;
        }

        /**
         * Every dataset binding key this normalisation may be applied to.
         *
         * @return an immutable set of dataset binding keys, never empty
         */
        public Set<String> datasets() {
            return datasets;
        }

        /**
         * Whether this normalisation describes the records of a given dataset.
         *
         * <p>The comparison is exact and case-sensitive, because a dataset binding key is a
         * {@code DD} name or a CICS file name and both are upper case by construction.
         *
         * @param dataset the dataset binding key a row was seeded into or written to
         * @return {@code true} only when {@code copybook()} describes that dataset's records
         * @throws NullPointerException if {@code dataset} is {@code null}
         */
        public boolean describes(String dataset) {
            Objects.requireNonNull(dataset,
                "A dataset binding key is required to decide whether " + name() + " applies to it");
            return datasets.contains(dataset);
        }

        /**
         * The dataset binding keys this normalisation may be applied to, and no others.
         *
         * @return the same immutable set {@link #datasets()} returns
         */
        public Set<String> eligibleDatasets() {
            return datasets;
        }

        /**
         * Whether this normalisation may be applied to a given dataset.
         *
         * <p>The same rule as {@link #describes(String)}, under the name the eligibility reads by at
         * the point a pad would be authorised: without it the two normalisations are distinguishable
         * only by their width pair, and a width pair is not evidence about a dataset.
         *
         * @param dataset the dataset binding key under comparison
         * @return {@code true} only when this normalisation describes that dataset's records
         * @throws NullPointerException if {@code dataset} is {@code null}
         */
        public boolean appliesTo(String dataset) {
            return describes(dataset);
        }

        /**
         * Right-pads one seeded row to {@link #targetWidth()}, idempotently.
         *
         * @param row the row exactly as the case declares it
         * @param dataset the dataset it is being seeded into, quoted in a failure message
         * @return the row at {@link #targetWidth()} characters
         * @throws NullPointerException if {@code row} is {@code null}
         * @throws IllegalArgumentException if the row is neither {@link #sourceWidth()} nor
         *     {@link #targetWidth()} characters wide
         */
        public String normaliseSeedRow(String row, String dataset) {
            Objects.requireNonNull(row, "A seeded row is required to normalise it; a row the case "
                + "does not declare must be absent from \"rows\" rather than null");
            if (row.length() == targetWidth) {
                // Already at the copybook width. Returning it unchanged is what makes the operation
                // idempotent, so a row cannot be double-padded by a second application.
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

    /**
     * The single place that decides what a rendered diagnostic may show.
     *
     * <h2>Carrying a credential and printing it are different things</h2>
     * <p>{@code app/cpy/CSUSR01Y.cpy} declares {@code SEC-USR-PWD PIC X(08)} and {@code COSGN00C}
     * compares it byte for byte with no hashing, so the parity contract must carry that span
     * verbatim: hashing it would change observable behaviour, and Spring Security is out of scope.
     * None of that requires the value to be written into a build log, a CI artefact or an assertion
     * failure - and a failure report is precisely where it would otherwise end up, because the
     * report exists to show the bytes.
     *
     * <p>Two things are masked, and the second matters as much as the first: the named fields whose
     * {@code PICTURE} is a password, and the <em>span</em> those fields occupy inside a whole-record
     * image. Masking only the named field would leave the same eight bytes visible in the record
     * image printed beside it.
     *
     * <p>Comparison is unaffected. Redaction happens at rendering time only; the raw values are
     * compared byte for byte, so a wrong password is still a difference and is still reported - as a
     * difference in a redacted field, which is all a reviewer needs to know.
     */
    public static final class Redaction {

        /** What a masked value is rendered as. Fixed text, so it can never be mistaken for data. */
        public static final String MASK = "<redacted>";

        /**
         * How a value is rendered once it is classified as anything other than {@link #PUBLIC}.
         *
         * <p>Three classes rather than one, because the three need different renderings and lumping
         * them together would either disclose too much or destroy the diagnostic. A credential
         * discloses <em>nothing</em>; a name or an identifier discloses its length and a digest, which
         * is enough to tell "expected and observed differ" from "they are equal" without putting the
         * value itself into a build log.
         */
        public enum Sensitivity {

            /**
             * A password. Rendered as {@link #MASK} and its length, and never anything else - not a
             * digest either, because the credential space here is small enough that a digest of an
             * eight-character password is a reversible disclosure.
             */
            CREDENTIAL,

            /**
             * Personal data about a real-shaped person: a name, an address, a telephone number, a
             * social-security number, a government-issued identifier, a date of birth, an
             * electronic-funds account or a credit score. Rendered as a class marker, a length and a
             * truncated digest.
             */
            PERSONAL,

            /**
             * An identifier that names an individual account, card, customer or user - including the
             * {@code CDEMO-} commarea fields that carry those identifiers between transactions.
             * Rendered as a class marker, a length and a truncated digest.
             */
            IDENTIFIER,

            /**
             * Everything else: a balance, a status byte, a message text, a transaction identifier, a
             * {@code FILLER} span. Rendered verbatim, because a difference in one of these is what a
             * reviewer has to read in order to act on it.
             */
            PUBLIC
        }

        /**
         * The field names whose values are credentials, spelled as the copybook and the symbolic
         * maps spell them: the stored password field, the received screen field and the sent screen
         * field. All three carry the same eight bytes at some point in a sign-on or user-update
         * path.
         */
        public static final Set<String> CREDENTIAL_FIELDS =
            Set.of("SEC-USR-PWD", "PASSWDI", "PASSWDO");

        /**
         * The field names carrying personal data, taken from the copybooks and the symbolic maps that
         * declare them rather than inferred from a name pattern.
         *
         * <p>{@code app/cpy/CVCUS01Y.cpy} and its near-duplicate {@code app/cpy/CUSTREC.cpy} supply
         * the customer spans - and both spellings of the date of birth are listed, because the two
         * copybooks disagree ({@code CUST-DOB-YYYY-MM-DD} against {@code CUST-DOB-YYYYMMDD}) and that
         * disagreement is deliberately preserved elsewhere in this migration.
         * {@code app/cpy/CSUSR01Y.cpy} supplies the two security-user names. The remaining entries are
         * the symbolic-map items through which the same values reach and leave a screen: the received
         * {@code xxxI} items and the sent {@code xxxO} items of {@code COACTUP}, {@code COACTVW},
         * {@code COUSR01}, {@code COUSR02} and the ten numbered rows of {@code COUSR00}.
         *
         * <p>An explicit set rather than a pattern, for the same reason the fixture list is explicit:
         * a pattern over "anything containing NAME" would classify {@code PGMNAMEI} - the program-name
         * item every one of the seventeen screens carries - as a person's name and mask the single
         * value a navigation failure is diagnosed from.
         */
        public static final Set<String> PERSONAL_FIELDS = personalFields();

        /**
         * The field names that identify one account, card, customer or user.
         *
         * <p>Includes the {@code CDEMO-} commarea carriers from {@code app/cpy/COCOM01Y.cpy}, because
         * the navigation context travels in the response payload under rule R6 and is therefore
         * rendered by every navigation difference - which is exactly where these values were escaping
         * unmasked.
         */
        public static final Set<String> IDENTIFIER_FIELDS = identifierFields();

        /**
         * The credential values the shipped fixtures actually carry, so an occurrence of one can be
         * scrubbed out of free text that was never field-classified at all.
         *
         * <p>One value, because there is one: all ten {@code USRSEC} rows transcribed from
         * {@code app/jcl/DUSRSECJ.jcl} carry the literal {@code PASSWORD} in their
         * {@code SEC-USR-PWD} span. Kept as a set so a second seeded credential is a one-line
         * addition rather than a redesign.
         */
        public static final Set<String> CREDENTIAL_LITERALS = Set.of("PASSWORD");

        /**
         * A credential quoted as a labelled value inside foreign text -
         * {@code password=SECRET99}, {@code pwd: hunter2}, {@code secret = x}.
         *
         * <p>This is how a credential that is <em>not</em> one of the fixture literals still reaches a
         * log: something outside this package - a driver, a repository, a validator - names it and
         * prints it. The label is matched, and the value after the separator is what gets masked.
         *
         * <p>A separator is <strong>required</strong>, and that requirement is the whole design. Without
         * it the pattern would also match ordinary prose, and the prose in question is not hypothetical:
         * {@code COUSR01C} emits {@code 'Password can NOT be empty...'} as a screen message, and a
         * pattern that masked the word after {@code Password} would turn that expectation into
         * {@code 'Password <redacted> NOT be empty...'} - destroying the diagnosability of a real
         * program message in order to protect a value that was never there. Applied only by
         * {@link #sanitiseDiagnostic(String)}, never to a line the program itself emitted.
         */
        private static final Pattern LABELLED_CREDENTIAL = Pattern.compile(
            "(?i)\\b(pass(?:word|wd)?|pwd|secret|credential|token)(\\s*[:=]\\s*)(\\S+)");

        /**
         * The longest diagnostic text that is rendered in full.
         *
         * <p>A bound rather than a filter, and it exists for one shape in particular: an exception
         * raised deep inside a repository routinely quotes the whole record it was handed, which for a
         * customer row is 500 bytes of names, address and social-security number. Field classification
         * cannot help there, because free text carries no field names. Truncating bounds what such a
         * message can disclose while leaving every ordinary message - which is far shorter than this -
         * completely intact.
         */
        public static final int MAX_DIAGNOSTIC_LENGTH = 320;

        /** What {@link #sanitiseDiagnostic(String)} appends when it has truncated. */
        public static final String TRUNCATION_MARKER = "... (truncated)";

        /** What {@link #sanitiseDiagnostic(String)} returns for a message that carries no text. */
        public static final String NO_MESSAGE = "(no message)";

        /** How many hexadecimal characters of a digest a masked value discloses. */
        public static final int DIGEST_LENGTH = 8;

        /**
         * The dataset whose record image contains a credential span, and where that span sits:
         * {@code USRSEC} rows carry {@code SEC-USR-PWD} at offset 48 for 8 bytes, as
         * {@code user.model.SecUserRecord} declares.
         */
        public static final String SENSITIVE_DATASET = "USRSEC";

        /** The 0-based offset of the credential span within a {@link #SENSITIVE_DATASET} row. */
        public static final int SENSITIVE_SPAN_OFFSET = 48;

        /** The width of the credential span within a {@link #SENSITIVE_DATASET} row. */
        public static final int SENSITIVE_SPAN_LENGTH = 8;

        /**
         * The declared width of a {@link #SENSITIVE_DATASET} row: {@code 8 + 20 + 20 + 8 + 1 + 23}.
         *
         * <p>Held here because it is what tells {@link #maskRecordImage(String, String)} whether the
         * credential offset can be trusted. An image at this width has its spans where the copybook puts
         * them; an image at any other width does not, and the two cases cannot be masked the same way.
         */
        public static final int SENSITIVE_RECORD_LENGTH = 80;

        /** No instance: this is a policy, not an object. */
        private Redaction() {
            throw new AssertionError("Redaction is a policy holder and is never instantiated");
        }

        /**
         * Which class a field name falls into.
         *
         * <p>Case-sensitive and exact, because copybook and symbolic-map names are. A lower-cased
         * {@code sec-usr-pwd} names nothing in this system and is deliberately <em>not</em>
         * classified: matching it would mean matching on a shape rather than on a declaration, and
         * the whole point of these sets is that every entry is traceable to the copybook line that
         * declares it.
         *
         * @param fieldName the COBOL or symbolic-map field name; may be {@code null}
         * @return the class, {@link Sensitivity#PUBLIC} for an unclassified or absent name
         */
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

        /**
         * Whether a field's value must be masked in every rendering.
         *
         * @param fieldName the COBOL or symbolic-map field name; may be {@code null}
         * @return {@code true} for any classified field - a credential, personal data or an
         *     individual identifier
         */
        public static boolean sensitiveField(String fieldName) {
            return classify(fieldName) != Sensitivity.PUBLIC;
        }

        /**
         * Whether a field's value is a credential specifically, which is the one class that discloses
         * nothing at all.
         *
         * @param fieldName the COBOL or symbolic-map field name; may be {@code null}
         * @return {@code true} for the three names that carry the eight password bytes
         */
        public static boolean credentialField(String fieldName) {
            return classify(fieldName) == Sensitivity.CREDENTIAL;
        }

        /**
         * Masks a value according to its field's class, preserving the length so a width difference
         * is still diagnosable.
         *
         * <p>The length is disclosed for every class because it is the difference between "the value
         * is wrong" and "the field was space-padded to the wrong width", and a parity report that
         * could not distinguish those two would be unusable. A digest accompanies the two data
         * classes for the same reason: two renderings of the same value carry the same digest, so a
         * reviewer can still see whether the expected and observed sides differ at all, and can still
         * correlate one row against another, without either value being written down.
         *
         * @param fieldName the field the value belongs to
         * @param value the raw value; may be {@code null}
         * @return the value for an unclassified field, otherwise a mask naming its class, its length
         *     and - for personal data and identifiers - a truncated digest
         */
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

        /**
         * Masks the credential span inside a whole-record image of a sensitive dataset.
         *
         * <p>At the declared width the rest of the image is untouched, which is what keeps the rendering
         * useful: a reviewer still sees the user id, both names, the type and the filler, and sees that
         * the password span is present and how wide it is.
         *
         * <h2>An image of the wrong width is the case that matters</h2>
         * <p>This method used to return an image shorter than {@code offset + length} <em>unchanged</em>,
         * on the reasoning that it was too short to hold the span. It was not too short to hold part of
         * it. A 49-to-55 character {@code USRSEC} image holds one to seven bytes of
         * {@code SEC-USR-PWD} at offset 48, and every one of them was written out verbatim.
         *
         * <p>That is not a hypothetical width. Both callers in {@code FieldDiffer} pass images whose
         * width is arbitrary <strong>by construction</strong>, and on the very paths a wrong width
         * produces: an {@code EXTRA_RECORD} difference renders a row the unit actually wrote, at
         * whatever width it wrote it, and a {@code MISSING_RECORD} difference renders the image a case
         * fixture pinned, at whatever width its author typed. A unit that truncated a {@code USRSEC}
         * row is exactly the defect this harness exists to catch, and catching it printed the password
         * into the failure report - into a build log, a CI artefact and an assertion message
         * (CWE-532).
         *
         * <p>So the width decides which of two treatments applies, and both preserve the rendered length
         * so a width difference stays diagnosable:
         * <ul>
         *   <li><strong>Exactly {@value #SENSITIVE_RECORD_LENGTH}</strong> - the spans are where
         *       {@code CSUSR01Y} puts them, so exactly the credential span is masked and everything else
         *       stays legible. This is the ordinary case and its rendering is unchanged.</li>
         *   <li><strong>Any other width</strong> - the record does not match the copybook, so no offset
         *       past the credential's is trustworthy: a span that is short or long moves every byte after
         *       it, and the password's bytes may now sit anywhere from offset 48 to the end. Everything
         *       from offset 48 onwards is therefore masked. It is the same reasoning
         *       {@code FieldDiffer} already applies when it stops comparing fields on a width mismatch -
         *       once the total width is wrong, offsets address the wrong bytes - and it costs only the
         *       type and the filler of a row that is malformed anyway, while the user id and both names,
         *       which a truncation leaves in place, stay visible for diagnosis.</li>
         * </ul>
         *
         * <p>An image that ends at or before offset 48 is returned unchanged: it holds no byte of the
         * credential span to mask, and masking nothing is not the same as having nothing to mask.
         *
         * @param dataset the dataset binding key the image belongs to; may be {@code null}
         * @param image the record image; may be {@code null}
         * @return the image with every credential byte it could hold replaced by asterisks, at the same
         *         rendered length, or the image unchanged when it carries none
         */
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

        /**
         * One addressable span of a record image, as the caller that holds the layout sees it.
         *
         * <p>Declared here rather than reusing {@code FixedWidthRecord.FieldSpan} so that the policy
         * depends on nothing but its own inputs: a caller maps whatever it holds onto this, and the
         * masking rule stays readable next to the classification it applies.
         *
         * @param fieldName the name the span is addressed by, which is what gets classified
         * @param offset the span's zero-based offset within the record
         * @param length the span's width in characters
         */
        public record Span(String fieldName, int offset, int length) {

            /**
             * Validates the geometry, because a negative offset or width would silently mask the
             * wrong bytes.
             *
             * @throws NullPointerException if {@code fieldName} is {@code null}
             * @throws IllegalArgumentException if {@code offset} is negative or {@code length} is
             *     below 1
             */
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

        /**
         * Masks every classified span of a record image, given the layout the caller holds.
         *
         * <p>This is the general form of {@link #maskRecordImage(String, String)}, and the reason it
         * exists is the reason that method exists at all: masking a named field's value while printing
         * the whole record image beside it discloses the same bytes twice over, and the second
         * disclosure is the one nobody notices. A caller that knows the layout - which
         * {@code FieldDiffer} always does, since it has already resolved the addressable spans in
         * order to compare them - can therefore mask the credential, the names, the address, the
         * social-security number and the account and card identifiers wherever the copybook puts
         * them, in any dataset, rather than only the one span of the one dataset whose offsets are
         * hard-coded.
         *
         * <p>A span is masked with asterisks at exactly its own width, so the image keeps its declared
         * length and a width difference stays diagnosable. Spans reaching past the end of a
         * short image are masked as far as the image goes, because a truncated row still discloses the
         * part of the span it kept - which is precisely the defect a truncation test exists to catch.
         *
         * @param image the record image; may be {@code null}
         * @param spans every addressable span of the record, in any order; may be empty
         * @return the image with every classified span replaced by asterisks, at the same length
         * @throws NullPointerException if {@code spans} is {@code null}
         */
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

        /**
         * Renders a record image whose geometry is not known, as its length and a truncated digest.
         *
         * <p>Used on the one path where no layout is available: a {@code MISSING_RECORD} difference
         * for a dataset the unit produced nothing at all for, so there is no observed output to take a
         * layout from. Without a layout there are no offsets to trust, and almost every dataset in this
         * system carries something classified - a customer row is names, address and a
         * social-security number; an account, card or cross-reference row leads with an identifier; a
         * {@code USRSEC} row carries the legacy plaintext password; a statement line carries a name.
         * Masking span by span is impossible and printing the image whole is a disclosure, so the image
         * is rendered as what the review of this harness asked for in that situation: its length and a
         * digest, and nothing else.
         *
         * <p>Nothing diagnostic is lost that the difference does not already carry. The length is
         * stated, so a width difference is still visible; the digest is stable, so the expected and
         * observed sides of two renderings can still be told apart or matched up; and the dataset, the
         * row index and the field names the expectation pinned are all reported beside it. The fixture
         * that declared the image is in the repository, which is where a developer reads it.
         *
         * @param image the record image; may be {@code null}
         * @return the length-and-digest rendering, or {@code null} for a {@code null} image
         */
        public static String maskUnlocatedImage(String image) {
            if (image == null) {
                return null;
            }
            return "<image>(len=" + image.length() + ",sha256=" + digest(image) + ')';
        }

        /**
         * Masks a free-text line, scrubbing any credential value it carries.
         *
         * <p>Deliberately narrow about what it removes. An emitted line is not generally sensitive -
         * {@code 'User ADMIN003 has been updated ...'} must be shown in full or the expectation cannot
         * be diagnosed - so a line is only ever reduced to {@link #MASK} when it is nothing but a
         * credential, which is what a mis-channelled password expectation looks like. A line that
         * <em>contains</em> a credential among other text keeps the other text and loses the
         * credential, because a {@code DISPLAY} that concatenated the password into a diagnostic is a
         * real leak and dropping the whole line would hide the defect instead of the value.
         *
         * @param text the line; may be {@code null}
         * @return the line with every credential occurrence masked, or {@link #MASK} for a line that
         *     is nothing else
         */
        public static String maskIfSensitiveText(String text) {
            if (text == null) {
                return null;
            }
            if (CREDENTIAL_LITERALS.contains(text.strip())) {
                return MASK;
            }
            return scrubCredentialLiterals(text);
        }

        /**
         * Renders a diagnostic that came from outside this package - an exception message, a
         * repository failure, a driver's complaint - with nothing sensitive in it and nothing
         * unbounded about it.
         *
         * <p>Free text carries no field names, so classification cannot reach it. Three rules apply
         * instead, and between them they close the disclosure without closing the diagnostic:
         * <ul>
         *   <li>every occurrence of a known credential value is masked, wherever in the text it
         *       sits;</li>
         *   <li>a credential quoted as a labelled value - {@code password=...}, {@code pwd: ...} -
         *       loses the value and keeps the label, which catches a credential this package has never
         *       seen;</li>
         *   <li>the result is bounded at {@value #MAX_DIAGNOSTIC_LENGTH} characters. That bound is
         *       what handles the shape neither rule can: a failure raised while decoding a
         *       customer row routinely quotes the whole 500-byte image, names, address and
         *       social-security number included, with nothing in it to match on. Every ordinary message
         *       is far shorter than the bound and survives untouched.</li>
         * </ul>
         *
         * @param text the diagnostic text; may be {@code null} or blank
         * @return sanitised, bounded text, or {@link #NO_MESSAGE} when there was none
         */
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

        /**
         * Describes a throwable as its type and its sanitised message - the only form in which one
         * raised by a unit under test may be quoted.
         *
         * <p>The type is the part a reader acts on, and it is safe by construction: a class name
         * carries no data. The message is the part that is not safe, so it goes through
         * {@link #sanitiseDiagnostic(String)}. What is chained as the {@code cause} is a
         * {@link SanitisedCause} surrogate rather than the throwable itself, so the type and the throw
         * site survive for a reader while neither rendering repeats the message raw.
         *
         * @param failure the throwable; may be {@code null}
         * @return a description naming the type and the sanitised message
         */
        public static String describeThrowable(Throwable failure) {
            if (failure == null) {
                return NO_MESSAGE;
            }
            return failure.getClass().getName() + ": " + sanitiseDiagnostic(failure.getMessage());
        }

        /**
         * A stand-in for a throwable raised inside a unit under test, safe to chain as a
         * {@code cause}.
         *
         * <p>Chaining the original throwable is what this exists to stop. The harness's own message
         * goes through {@link #sanitiseDiagnostic(String)}, but a chained cause is rendered by the test
         * runner independently: {@code trimStackTrace} is {@code false} in {@code app/java/pom.xml} -
         * deliberately, so a parity difference is traceable to the translation decision that caused it
         * - and with it surefire prints the whole {@code Caused by:} chain <em>including the original
         * message, raw</em>. A repository failure routinely quotes the record it was handed, which for a
         * customer row is five hundred bytes of names, address and social-security number and for a
         * {@code USRSEC} row is the legacy plaintext password. Sanitising one rendering and leaving the
         * other raw sanitises nothing (CWE-532).
         *
         * <p>What a reader actually needs from a cause survives intact:
         * <ul>
         *   <li>the original <strong>type</strong>, named in this surrogate's message. A class name
         *       carries no data, and it is the part a reader acts on;</li>
         *   <li>the original <strong>stack trace</strong>, copied frame for frame. A frame is a class
         *       name, a method name and a line number - it locates the throw site exactly and carries
         *       no record content;</li>
         *   <li>the original <strong>message</strong>, scrubbed and bounded by the same policy the
         *       harness's own text uses.</li>
         * </ul>
         *
         * <p>What does not survive is the original object, and that is the point: it is neither chained
         * nor suppressed here, so there is no path by which the runner can reach its raw message. A
         * developer who needs the unscrubbed text runs the failing unit directly, where the throwable is
         * never intercepted.
         */
        public static final class SanitisedCause extends RuntimeException {

            /** Serialisation identity, required because {@link RuntimeException} is serialisable. */
            private static final long serialVersionUID = 1L;

            /** The original throwable's fully qualified type name, retained as metadata. */
            private final String originalType;

            /**
             * @param original the throwable to stand in for; never {@code null}
             */
            private SanitisedCause(Throwable original) {
                // No cause argument, deliberately: chaining the original is the disclosure this type
                // exists to prevent. The explicit null also disables writableStackTrace's default
                // fillInStackTrace, which would otherwise report THIS constructor's frames rather than
                // the original throw site.
                super(describeThrowable(original), null, true, true);
                this.originalType = original.getClass().getName();
                setStackTrace(original.getStackTrace());
            }

            /**
             * The type of the throwable this stands in for.
             *
             * @return the fully qualified class name, never {@code null}
             */
            public String originalType() {
                return originalType;
            }
        }

        /**
         * Wraps a throwable in a {@link SanitisedCause} so it can be chained without disclosing its
         * message.
         *
         * @param failure the throwable raised by a unit under test; never {@code null}
         * @return a surrogate carrying the type, the stack frames and the sanitised message
         * @throws NullPointerException if {@code failure} is {@code null}
         */
        public static SanitisedCause sanitisedCause(Throwable failure) {
            Objects.requireNonNull(failure, "A throwable is required: there is nothing to sanitise "
                + "otherwise, and a null cause is spelled by passing no cause at all");
            return new SanitisedCause(failure);
        }

        /** Replaces every occurrence of every known credential value with {@link #MASK}. */
        private static String scrubCredentialLiterals(String text) {
            String scrubbed = text;
            for (String literal : CREDENTIAL_LITERALS) {
                if (scrubbed.contains(literal)) {
                    scrubbed = scrubbed.replace(literal, MASK);
                }
            }
            return scrubbed;
        }

        /**
         * The first {@value #DIGEST_LENGTH} hexadecimal characters of the SHA-256 of a value.
         *
         * <p>Truncated on purpose. The digest exists so that two renderings of one value can be seen
         * to match and two renderings of different values can be seen to differ; it is not an
         * integrity check, and a full 64-character digest per field would make a multi-field failure
         * unreadable. {@code UTF-8} is named rather than defaulted so the same value digests
         * identically on every machine.
         */
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
                // Every conformant Java platform provides SHA-256, so this cannot happen. It is
                // still handled rather than declared, because a rendering routine that could throw
                // would turn a reported difference into a lost one.
                throw new IllegalStateException("SHA-256 is required to render a masked value and is "
                    + "mandated by the platform, so its absence is an unusable JVM", impossible);
            }
        }

        /**
         * The personal-data field names, assembled from the copybooks and symbolic maps that declare
         * them.
         *
         * <p>Built in a method rather than written as one {@code Set.of(...)} call so that each group
         * can carry the source it came from, which is what makes the set auditable against those
         * files during review.
         */
        private static Set<String> personalFields() {
            Set<String> names = new LinkedHashSet<>();
            // app/cpy/CVCUS01Y.cpy, and app/cpy/CUSTREC.cpy for the second spelling of the date of
            // birth - the two copybooks genuinely disagree and both spellings are preserved.
            names.addAll(List.of(
                "CUST-FIRST-NAME", "CUST-MIDDLE-NAME", "CUST-LAST-NAME",
                "CUST-ADDR-LINE-1", "CUST-ADDR-LINE-2", "CUST-ADDR-LINE-3", "CUST-ADDR-ZIP",
                "CUST-PHONE-NUM-1", "CUST-PHONE-NUM-2",
                "CUST-SSN", "CUST-GOVT-ISSUED-ID",
                "CUST-DOB-YYYY-MM-DD", "CUST-DOB-YYYYMMDD",
                "CUST-EFT-ACCOUNT-ID", "CUST-FICO-CREDIT-SCORE"));
            // app/cpy/CSUSR01Y.cpy, and the name embossed on a card from app/cpy/CVACT02Y.cpy - a
            // person's name wherever it is stored.
            names.addAll(List.of("SEC-USR-FNAME", "SEC-USR-LNAME", "CARD-EMBOSSED-NAME"));
            // The symbolic-map items the same values pass through: COACTUP, COACTVW, COUSR01 and
            // COUSR02 carry them singly, and COUSR00 carries ten numbered rows of names.
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

        /**
         * The identifier field names: the copybook spans that name one account, card, customer or
         * user, the symbolic-map items that carry them, and the {@code CDEMO-} commarea fields that
         * carry them between transactions.
         */
        private static Set<String> identifierFields() {
            Set<String> names = new LinkedHashSet<>();
            // app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy, CVCUS01Y.cpy, CVTRA05Y.cpy,
            // CVTRA06Y.cpy, COSTM01.CPY and CSUSR01Y.cpy.
            names.addAll(List.of(
                "ACCT-ID", "CARD-NUM", "CARD-ACCT-ID",
                "XREF-CARD-NUM", "XREF-ACCT-ID", "XREF-CUST-ID",
                "CUST-ID", "TRAN-CARD-NUM", "DALYTRAN-CARD-NUM", "TRNX-CARD-NUM",
                "SEC-USR-ID"));
            // The symbolic-map items the same identifiers reach and leave a screen through.
            for (String stem : List.of("ACCTSID", "CARDSID", "CARDNUM", "USRIDIN", "XREFNBR")) {
                names.add(stem + 'I');
                names.add(stem + 'O');
            }
            for (int row = 1; row <= 10; row++) {
                String ordinal = row < 10 ? "0" + row : Integer.toString(row);
                names.add("USRID" + ordinal + 'I');
                names.add("USRID" + ordinal + 'O');
            }
            // app/cpy/COCOM01Y.cpy - the navigation context, which travels in the response payload
            // under rule R6 and is rendered by every navigation difference.
            names.addAll(List.of(
                "CDEMO-USER-ID", "CDEMO-ACCT-ID", "CDEMO-CARD-NUM", "CDEMO-CUST-ID",
                "CDEMO-CU01-USR-SELECTED", "CDEMO-CU02-USR-SELECTED",
                "CDEMO-CU03-USR-SELECTED"));
            return Collections.unmodifiableSet(names);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Validation and freezing helpers.
    //
    // Every one is private and static. Static because a compact constructor that reached for an
    // instance method would leak a partially-initialised "this"; private because none of them is
    // part of the published contract and none should become something a fixture can depend on.
    //
    // No value is ever trimmed, re-cased or coerced on the way through. A malformed value is
    // rejected with a message that names the member and says what shape was expected, because these
    // exceptions are read by whoever is authoring a fixture and a message that only says "invalid"
    // costs them a debugging session.
    // -------------------------------------------------------------------------------------------

    /**
     * Requires text that is present and not blank, returning it completely unaltered.
     *
     * @param value the candidate value
     * @param member the member name to quote in the failure message
     * @return {@code value} exactly as supplied, never trimmed
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    private static String requireText(String value, String member) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "ParityCase." + member + " is required and must not be blank");
        }
        return value;
    }

    /**
     * Allows an absent value but rejects a present-but-blank one, which is almost always a fixture
     * holding an empty string where it meant to omit the key.
     *
     * @param value the candidate value
     * @param member the member name to quote in the failure message
     * @return {@code value} exactly as supplied, possibly {@code null}
     * @throws IllegalArgumentException if {@code value} is present but blank
     */
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

    /**
     * Allows an absent value but requires a present one to match a pattern.
     *
     * @param value the candidate value
     * @param pattern the shape the value must have
     * @param member the member name to quote in the failure message
     * @param expectation prose describing the shape, so the message says what to write instead
     * @return {@code value} exactly as supplied, possibly {@code null}
     * @throws IllegalArgumentException if the value is present and does not match
     */
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

    /**
     * Requires an eight-character upper-case program name that matches its resource directory.
     *
     * @param value the candidate program name
     * @return the validated program name
     * @throws IllegalArgumentException if the name is absent or not eight upper-case alphanumerics
     */
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

    /**
     * Requires a case identifier within the mandated range of twenty per program.
     *
     * @param value the candidate case identifier
     * @return the validated case identifier
     * @throws IllegalArgumentException if the identifier is absent or outside
     *     {@code case01}..{@code case20}
     */
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

    /**
     * Requires a dataset binding key rather than a dataset name.
     *
     * @param value the candidate key
     * @param member the member name to quote in the failure message
     * @return the validated binding key
     * @throws IllegalArgumentException if the key is absent, or is not one to eight upper-case
     *     alphanumerics - which is what a dotted, fully-qualified dataset name would fail on
     */
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

    /**
     * Requires a return code inside the range a z/OS step can actually carry.
     *
     * <p>The values this migration produces are {@code 0} for a normal completion; {@code 3}, which
     * is what the date utility translated from {@code CSUTLDTC} moves to {@code RETURN-CODE} for
     * every named {@code CEEDAYS} feedback token, all eight of which carry severity three where a
     * valid date carries zero; {@code 4}; {@code 8} and {@code 12} from the
     * {@code MOVE 8}/{@code MOVE 12 TO APPL-RESULT}-then-abend convention; and {@code 16} for the
     * end-of-file condition. Those are documented rather than enforced: the bound below rejects the
     * mistake that actually happens - a negative value from a sign error - without freezing a set
     * that a legitimately-discovered path could extend.
     *
     * <p>An <strong>absent</strong> value is refused rather than defaulted. A case states what the
     * step ended with, and {@code 0} is the most common answer - so a defaulted zero is the one wrong
     * value most likely to look right, and a fixture that omitted the member would assert a normal
     * completion where the COBOL abends with 8 or 12. Refusing it costs a fixture author one line and
     * names the line.
     *
     * @param value the candidate return code, or {@code null} when the member was absent
     * @return the validated return code
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is negative or above 4095
     */
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

    /**
     * Requires an AID mnemonic {@code common.CicsAid} actually defines.
     *
     * @param value the candidate mnemonic, possibly {@code null}
     * @return the validated mnemonic, possibly {@code null}
     * @throws IllegalArgumentException if the mnemonic is unknown
     */
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

    /**
     * Requires an ISO-8601 local date-time, so a pinned clock is a real instant rather than prose.
     *
     * @param value the candidate instant, possibly {@code null}
     * @return the validated text, possibly {@code null}
     * @throws IllegalArgumentException if the text is not parseable
     */
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

    /**
     * Requires one of the two code pages this system uses.
     *
     * @param value the candidate charset name, possibly {@code null}
     * @return the validated name, possibly {@code null}
     * @throws IllegalArgumentException if the name is not {@code US-ASCII} or {@code IBM037}
     */
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

    /**
     * Copies the input datasets into an unmodifiable, order-preserving map.
     *
     * <p>{@link LinkedHashMap} rather than {@code Map.copyOf}: the latter produces an unspecified
     * iteration order, and seeding order can matter for a sequential dataset.
     *
     * @param source the declared inputs, possibly {@code null}
     * @return an unmodifiable map in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if a key is not a binding key or a value is {@code null}
     */
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

    /**
     * Copies the job parameters into an unmodifiable, order-preserving map, and refuses everything
     * that is not one.
     *
     * <h2>Why this member is now narrow</h2>
     * <p>It carries <strong>batch job parameters</strong>. It used to accept any non-blank key, and
     * that made it the path of least resistance for two things it is not: an online invocation - the
     * AID, the commarea, the received screen fields - and an <em>expectation</em>. The second is the
     * serious one. A value declared here is an input to the invocation, so an expectation parked
     * here is fed back into the unit and then compared against itself: the case passes by
     * construction and proves nothing. {@link ScreenRequest} and the {@code expected*} members exist
     * precisely so that neither has to borrow this map.
     *
     * <p>Three rules follow. Only a {@link UnitKind#BATCH_JOB} may declare parameters at all, since
     * nothing else has any. A name must be lower camel case, which excludes the COBOL and EIB field
     * names that were arriving here. And a name beginning {@code expected} is refused outright with
     * a message naming the member it belongs in.
     *
     * <p>The one verified parameter in the migration is {@code parmDate}, whose value
     * {@code 2022071800} comes from {@code PARM='2022071800'} in {@code app/jcl/INTCALC.jcl}. It is
     * emphatically <em>not</em> a date: {@code CBACT04C} concatenates it straight into generated
     * transaction identifiers with {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO
     * TRAN-ID}, where {@code PIC X(10)} followed by {@code PIC 9(06)} fills {@code TRAN-ID PIC X(16)}
     * exactly. Parsing it into a date and re-rendering it would corrupt those identifiers. By
     * contrast {@code POSTTRAN.jcl} declares no {@code PARM} at all, so cases for the job translated
     * from {@code CBTRN02C} carry an empty map.
     *
     * @param source the declared parameters, possibly {@code null}
     * @param unitKind the kind of unit, which decides whether parameters are permissible at all
     * @return an unmodifiable map in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if a name is blank, malformed, an expectation, or declared for
     *     a unit that takes no job parameter, or if a value is {@code null}
     */
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

    /**
     * Requires an online member for a controller case and forbids it for a batch job.
     *
     * <p>Both directions matter. A controller case without them could assert only the part of an
     * online program's behaviour that reaches a dataset, which for several paths is nothing at all.
     * A batch case <em>with</em> them would be describing a screen that does not exist.
     *
     * @param <T> the member type
     * @param member the declared member, possibly {@code null}
     * @param unitKind the kind of unit
     * @param name the member name to quote in the failure message
     * @param carries prose naming what the member carries, so the message says why it is needed
     * @return the member, unchanged
     * @throws IllegalArgumentException if the member is required and absent, or present and
     *     meaningless
     */
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

    /**
     * Copies a list of record expectations into an unmodifiable list, preserving assertion order.
     *
     * @param source the declared expectations, possibly {@code null}
     * @param member the member name to quote in the failure message
     * @return an unmodifiable list in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if any element is {@code null}
     */
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

    /**
     * Copies the expected messages into an unmodifiable list, preserving emission order.
     *
     * @param source the declared messages, possibly {@code null}
     * @return an unmodifiable list in emission order, empty when nothing was declared
     * @throws IllegalArgumentException if any element is {@code null}
     */
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

    /**
     * Copies the screen sends into an unmodifiable list, preserving send order.
     *
     * @param source the declared sends, possibly {@code null}
     * @return an unmodifiable list in send order, empty when nothing was declared
     * @throws IllegalArgumentException if any element is {@code null}
     */
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

    /**
     * Copies the normalisations into an unmodifiable list, rejecting a repeat and an irrelevant one.
     *
     * <p>The relevance check is the substance here. A normalisation is applied to the rows seeded
     * into one named dataset, so a declaration naming a dataset the case never seeds cannot fire; it
     * is either a leftover from a copied case or a misunderstanding of what the pad does, and both
     * are worth reporting rather than ignoring. The same {@code (dataset, kind)} pair twice is
     * likewise an authoring slip, because the pad is applied once and is idempotent.
     *
     * @param source the declared normalisations, possibly {@code null}
     * @param inputs the frozen inputs, which are the datasets a normalisation can legitimately name
     * @return an unmodifiable list in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if any element is {@code null}, repeated, or names a dataset
     *     the case does not seed
     */
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

    /**
     * Copies the dataset-level expectations into an unmodifiable list, rejecting a repeat.
     *
     * <p>The same {@code (dataset, channel)} pair twice is refused rather than resolved. Two counts for
     * one dataset on one channel cannot both hold, so whichever the differ honoured, the other would be
     * an assertion the case appears to make and nobody checks - and if the two agreed, the duplicate is
     * an editing slip worth naming. Unlike a normalisation, an expectation is <em>not</em> required to
     * name a seeded dataset: a case legitimately asserts a dataset that only exists as output, which is
     * what the reject file and the report file are.
     *
     * @param source the declared expectations, possibly {@code null}
     * @return an unmodifiable list in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if any element is {@code null} or repeats a dataset and channel
     */
    /**
     * Validates and freezes the expected operation sequence.
     *
     * @param source the declared list, possibly {@code null}
     * @return an unmodifiable copy in declaration order, which is the order the run must issue them in
     * @throws IllegalArgumentException if an entry is {@code null}
     */
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

    /**
     * The classpath directory every seeding fixture lives in, relative to the test resource root.
     *
     * <p>Named once, here, so the loader cannot be pointed anywhere else and so
     * {@link DatasetInput#fixtureResourcePath()} can prove containment by construction rather than by
     * a check that a future caller might bypass.
     */
    public static final String FIXTURE_ROOT = "fixtures/";

    /**
     * Every fixture a case may name: the nine files derived from {@code app/data/ASCII}.
     *
     * <p>A whitelist rather than a pattern, because the set is closed and known. {@code app/data/ASCII}
     * holds exactly nine fixtures and the migration adds none, so a name outside this set is either a
     * typo - which would otherwise surface as a confusing empty seed - or an attempt to read something
     * that is not a fixture at all. Enumerating them also documents what a case author may choose from.
     */
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

    /**
     * Validates a fixture name, or passes {@code null} through for an inline input.
     *
     * <p>A case is data, and this one names a resource that something will later open. Nothing in this
     * class performs that read - {@code ParityCase} is deliberately inert - so the confinement has to
     * be established here, at the point the name enters the model, and it has to hold for whatever
     * eventually does the reading.
     *
     * <p>Two independent barriers, in order. The name may contain no path separator, no
     * {@code .} or {@code ..} segment and no {@code :} - so it cannot be a relative traversal, an
     * absolute path, a Windows drive letter or a URI scheme - and it must then be one of the
     * {@value #FIXTURE_NAMES} names actually present. The whitelist alone would be sufficient; the
     * structural checks come first so that a rejected name is reported as the specific thing it did
     * wrong rather than merely as "not a fixture", which is the difference between a diagnostic a case
     * author can act on and one they cannot.
     *
     * @param fixture the fixture name from the case, or {@code null} for an inline input
     * @return {@code fixture} unchanged, or {@code null}
     * @throws IllegalArgumentException if the name is blank, is not a bare file name, or is not one of
     *     the nine fixtures
     */
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

    /**
     * The substrings a bare file name may not contain.
     *
     * <p>{@code /} and {@code \\} are path separators on the two platforms this build runs on,
     * {@code ..} is a parent traversal, {@code ./} a same-directory prefix that a naive resolver may
     * mishandle, and {@code :} introduces both a URI scheme and a Windows drive letter. A single
     * trailing or interior {@code .} is not forbidden, because every fixture name contains one.
     *
     * <p>The separators are listed first so that a full URI such as {@code file:///acctdata.txt},
     * which trips several of these at once, is reported as the separator it carries rather than as the
     * scheme - the first structural fault found is the one named, and the ordering makes which that is
     * deterministic rather than incidental.
     */
    private static final List<String> FORBIDDEN_IN_FIXTURE_NAME =
        List.of("/", "\\", "..", "./", ":");

    /**
     * The subset of {@link #FORBIDDEN_IN_FIXTURE_NAME} that is a path separator.
     *
     * <p>Kept as its own set purely so the refusal can name the specific category the name fell into -
     * a separator, or a traversal segment or scheme - instead of listing every possibility and leaving
     * the author to work out which applied. That distinction is the whole point of checking structure
     * before consulting the allow-list.
     */
    private static final Set<String> SEPARATORS_IN_FIXTURE_NAME = Set.of("/", "\\");

    /**
     * The fixture names in a stable order, for a diagnostic.
     *
     * @return the nine names sorted, so the message reads the same on every run
     */
    private static String sortedFixtureNames() {
        return FIXTURE_NAMES.stream().sorted().collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Copies inline rows into an unmodifiable list, preserving both order and every byte.
     *
     * <p>A row is stored exactly as written: not trimmed, not padded, not re-encoded. Significant
     * trailing spaces and zoned sign-overpunch bytes are part of the record image, and normalising
     * them here would corrupt the input the case is meant to seed. The one width adjustment this
     * system performs is the declared seed-time normalisation, which is applied by
     * {@link Normalisation} and is never implicit.
     *
     * @param source the declared rows, possibly {@code null}
     * @return an unmodifiable list in seeding order, empty when nothing was declared
     * @throws IllegalArgumentException if any row is {@code null}
     */
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

    /**
     * Copies expected field values into an unmodifiable, order-preserving map.
     *
     * <p>Field names are copybook names verbatim and are neither trimmed nor re-cased. A value of
     * all spaces is legitimate - that is what a {@code FILLER} span or a cleared {@code PIC X} field
     * looks like - so only {@code null} is rejected.
     *
     * @param source the declared field expectations, possibly {@code null}
     * @param dataset the owning dataset key, quoted in failure messages
     * @return an unmodifiable map in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if a field name is blank or a value is {@code null}
     */
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

    /**
     * Copies a name-to-value map into an unmodifiable, order-preserving map, validating every key
     * against the kind of COBOL name the member accepts.
     *
     * @param source the declared entries, possibly {@code null}
     * @param keyShape the pattern every key must match
     * @param member the member name to quote in the failure message
     * @param expectation prose describing the key shape, so a message says what to write instead
     * @return an unmodifiable map in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if a key is malformed or a value is {@code null}
     */
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

    /**
     * Copies the attribute expectations, validating both the item name and the mnemonic it carries.
     *
     * @param source the declared attributes, possibly {@code null}
     * @return an unmodifiable map in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if an item name is malformed or a value is not a mnemonic
     *     {@code common.BmsAttributes} defines
     */
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

    /**
     * Copies the forced outcomes into an unmodifiable, order-preserving map.
     *
     * @param source the declared outcomes, possibly {@code null}
     * @return an unmodifiable map in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if an operation name is malformed or an outcome is
     *     {@code null}
     */
    private static Map<RepositoryOperation, ForcedOutcome> freezeForcedOutcomes(
        Map<RepositoryOperation, ForcedOutcome> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        // No name check here any more: the key is a RepositoryOperation, so a spelling that matches no
        // call site cannot reach this method. It is refused where the spelling still exists as text -
        // by RepositoryOperation.fromKey, on the way in from a case file.
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

    /**
     * The union of the three mnemonic tables {@code common.BmsAttributes} publishes - field
     * attributes, colours and highlights.
     *
     * @return every mnemonic a case may name as an attribute value
     */
    private static Set<String> attributeMnemonics() {
        Set<String> mnemonics = new LinkedHashSet<>();
        mnemonics.addAll(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.values());
        mnemonics.addAll(BmsAttributes.COLOUR_MNEMONICS.values());
        mnemonics.addAll(BmsAttributes.HIGHLIGHT_MNEMONICS.values());
        return Collections.unmodifiableSet(mnemonics);
    }
}
