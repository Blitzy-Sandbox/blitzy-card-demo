package com.vsergeychik.carddemo.parity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One declarative behavioural-parity case for exactly one of the 28 COBOL programs in
 * {@code app/cbl}.
 *
 * <h2>This type is a published contract</h2>
 * <p>Every component name below is a <em>wire contract</em>. The sibling fixture tree
 * {@code app/java/src/test/resources/parity/<PROGRAM>/caseNN.json} is authored against this file
 * and binds to it by name, so <strong>renaming a component silently invalidates all 560
 * fixtures</strong>: Jackson would bind the renamed member to nothing and the affected
 * expectations would evaporate rather than fail. Treat the member set as frozen. Deserialisation is
 * deliberately strict - {@code @JsonIgnoreProperties(ignoreUnknown = false)} on every type here -
 * so that a misspelled key in a fixture fails loudly at load time instead of quietly binding
 * nothing and reporting a false pass. Every JSON key is pinned with an explicit
 * {@code @JsonProperty} so that no key ever depends on a configured naming strategy.
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
 * </ul>
 *
 * <h2>What this type deliberately does not do</h2>
 * <p>This is a pure data contract and carries no behaviour beyond validating and freezing its own
 * contents. It does not read fixtures from disk, resolve a {@link DatasetInput} against the
 * classpath, decode a fixed-width row into fields, or compare anything. Loading and seeding belong
 * to {@code ParityHarness}; comparison, and the application of {@link Normalisation}, belong to
 * {@code FieldDiffer}; byte-level decoding belongs to {@code common.FixedWidthCodec}, which takes
 * its {@code Charset} as an explicit parameter and is hand-written so that every offset stays
 * reviewable against its copybook. Keeping this type inert is what lets 560 fixtures and 28 test
 * classes evolve independently of each other.
 *
 * <p>Consistent with that, every row and every expected value is stored as a <strong>raw
 * {@code String}</strong>, byte-for-byte as it appears in the dataset. No value is trimmed,
 * re-scaled or parsed on the way in: leading zeros, significant trailing spaces and zoned
 * sign-overpunch bytes must survive deserialisation untouched. For instance the first row of
 * {@code acctdata.txt} opens <code>00000000001Y00000001940&#123;</code>, where that trailing
 * <code>&#123;</code> is the zoned overpunch closing {@code ACCT-CURR-BAL PIC S9(10)V99} and
 * encodes a positive digit zero, making the value 1940.00; the byte must arrive intact. A numeric
 * expectation is never modelled as a binary floating-point type, because {@code double} and
 * {@code float} cannot represent a COBOL {@code PIC S9(n)V99} value exactly and a case fixture must
 * not lose precision simply by being loaded.
 *
 * <h2>All auxiliary types live here</h2>
 * <p>{@link UnitKind}, {@link DatasetInput}, {@link ExpectedRecord} and {@link Normalisation} are
 * nested inside this one file on purpose. This package contains exactly three support types -
 * {@code ParityCase}, {@code FieldDiffer} and {@code ParityHarness} - alongside the 28 per-program
 * test classes. Promoting a nested type to its own file would add a file the plan does not name.
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
 * @param inputs input datasets to seed before the unit runs, keyed by dataset <em>binding key</em>
 *     and held in declaration order because seeding order can matter for sequential datasets;
 *     never {@code null}, empty for a unit that reads nothing
 * @param jobParameters batch job parameters in declaration order, as raw character strings; never
 *     {@code null}, empty for a unit that takes none
 * @param expectedRecords the records the unit is expected to write, in assertion order; never
 *     {@code null}, empty for a unit that writes no dataset
 * @param expectedReturnCode the expected COBOL {@code RETURN-CODE}, mapped onto the batch exit
 *     status
 * @param expectedMessages byte-exact emitted lines in emission order; order is part of the
 *     expectation and never {@code null}
 * @param normalisations the fixture-width normalisations {@code FieldDiffer} must apply before
 *     comparing; never {@code null}, and empty for a case touching no under-width fixture
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonPropertyOrder({
    "program",
    "caseId",
    "description",
    "unitKind",
    "inputs",
    "jobParameters",
    "expectedRecords",
    "expectedReturnCode",
    "expectedMessages",
    "normalisations"
})
public record ParityCase(
    @JsonProperty("program") String program,
    @JsonProperty("caseId") String caseId,
    @JsonProperty("description") String description,
    @JsonProperty("unitKind") UnitKind unitKind,
    @JsonProperty("inputs") Map<String, DatasetInput> inputs,
    @JsonProperty("jobParameters") Map<String, String> jobParameters,
    @JsonProperty("expectedRecords") List<ExpectedRecord> expectedRecords,
    @JsonProperty("expectedReturnCode") int expectedReturnCode,
    @JsonProperty("expectedMessages") List<String> expectedMessages,
    @JsonProperty("normalisations") List<Normalisation> normalisations) {

    /**
     * A COBOL program name: upper case, alphanumeric, beginning with a letter, and exactly eight
     * characters. Eight is not arbitrary - it is the partitioned-dataset member-name limit, and all
     * 28 in-scope program names occupy it exactly ({@code CBACT01C}, {@code COACTUPC},
     * {@code CSUTLDTC}, {@code CBSTM03A} and the rest). Rejecting anything else catches the one
     * failure mode that matters here: a value that does not match the {@code parity/<PROGRAM>/}
     * directory it is supposed to name, such as a lower-cased {@code "cbact01c"}.
     *
     * <p>{@link Pattern} instances are immutable and thread-safe, so this constant and the two
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
     * The largest value a z/OS step return code can carry. Return codes are non-negative by
     * definition, so this bound rejects a sign error at construction rather than letting it reach a
     * comparison.
     */
    private static final int MAX_RETURN_CODE = 4095;

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
     * @throws IllegalArgumentException if any member is missing, malformed, or addresses a dataset
     *     by something other than a binding key
     * @throws NullPointerException if {@code unitKind} is absent
     */
    public ParityCase {
        program = requireProgramName(program);
        caseId = requireCaseId(caseId);
        description = requireText(description, "description");
        unitKind = Objects.requireNonNull(
            unitKind,
            "ParityCase.unitKind is required: declare one of BATCH_JOB, SERVICE, COMPONENT or "
                + "CONTROLLER_POJO so the harness knows how to invoke the unit");
        inputs = freezeInputs(inputs);
        jobParameters = freezeJobParameters(jobParameters);
        expectedRecords = freezeExpectedRecords(expectedRecords);
        expectedReturnCode = requireReturnCode(expectedReturnCode);
        expectedMessages = freezeExpectedMessages(expectedMessages);
        normalisations = freezeNormalisations(normalisations);
    }

    // -------------------------------------------------------------------------------------------
    // Nested contract types. All four are nested here on purpose; see the class documentation.
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
    public enum UnitKind {

        /**
         * A Spring Batch job. The harness executes the job's tasklet or its
         * reader/processor/writer trio directly, so step sequencing and write ordering are
         * observed without a {@code JobLauncher}, a job repository or an asynchronous executor
         * between the assertion and the code.
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
         * every one of the 560 call sites.
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
     * <p><strong>Fixture-backed</strong> - {@code fixture} names a file on the test classpath under
     * {@code fixtures/}, optionally narrowed by {@code fromRow} and {@code rowCount}. This is the
     * shape for realistic volume, and it is what keeps cases seeded from genuine production-shaped
     * data rather than invented data. The nine available fixtures are derived from
     * {@code app/data/ASCII}: {@code acctdata.txt} (300 bytes x 50 rows), {@code carddata.txt}
     * (150 x 50), {@code cardxref.txt} (<strong>36</strong> x 50), {@code custdata.txt} (500 x 50),
     * {@code dailytran.txt} (350 x 300), {@code discgrp.txt} (50 x 51), {@code tcatbal.txt}
     * (50 x 50), {@code trancatg.txt} (60 x 18) and {@code trantype.txt} (60 x 7).
     *
     * <p>{@code cardxref.txt} is deliberately <em>not</em> pre-padded to its copybook width; a case
     * that seeds it must declare {@link Normalisation#CARDXREF_FILLER_PAD_36_TO_50} so the missing
     * trailing span is supplied at comparison time.
     *
     * <p>The two shapes are mutually exclusive and one of them is mandatory - a dataset entry that
     * declared neither would seed nothing while appearing to seed something, which is exactly the
     * silent-pass failure this model exists to prevent. The constructor enforces the exclusivity, so
     * {@link #inline()} and {@link #fixtureBacked()} are strict complements and a consumer may
     * branch on either with confidence.
     *
     * @param rows inline record images in seeding order; never {@code null} - empty for a
     *     fixture-backed input
     * @param fixture the classpath fixture file name relative to {@code fixtures/}, or {@code null}
     *     for an inline input; never blank
     * @param fromRow the zero-based index of the first fixture row to seed; normalised to {@code 0}
     *     when a fixture is named without one, and always {@code null} for an inline input
     * @param rowCount how many fixture rows to seed, or {@code null} for every remaining row from
     *     {@code fromRow} to the end of the fixture; always {@code null} for an inline input, and at
     *     least {@code 1} when present
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"rows", "fixture", "fromRow", "rowCount"})
    public record DatasetInput(
        @JsonProperty("rows") List<String> rows,
        @JsonProperty("fixture") String fixture,
        @JsonProperty("fromRow") Integer fromRow,
        @JsonProperty("rowCount") Integer rowCount) {

        /**
         * Validates that exactly one shape is declared, and freezes the row list.
         *
         * @throws IllegalArgumentException if both shapes or neither shape is declared, if a row
         *     range accompanies an inline input, if {@code fixture} is blank, if {@code fromRow} is
         *     negative or if {@code rowCount} is less than one
         */
        public DatasetInput {
            rows = freezeRows(rows);
            fixture = requireNullOrText(fixture, "DatasetInput.fixture");

            boolean hasRows = !rows.isEmpty();
            boolean hasFixture = fixture != null;
            if (hasRows == hasFixture) {
                throw new IllegalArgumentException(
                    "DatasetInput must declare exactly one of \"rows\" or \"fixture\", but "
                        + (hasRows
                            ? "both were declared; put the literal rows in \"rows\" or name the "
                                + "fixture in \"fixture\", never both"
                            : "neither was declared; an input that seeds nothing must be omitted "
                                + "from \"inputs\" rather than declared empty"));
            }

            if (hasRows) {
                if (fromRow != null || rowCount != null) {
                    throw new IllegalArgumentException(
                        "DatasetInput declares inline \"rows\" so \"fromRow\" and \"rowCount\" are "
                            + "meaningless and must be omitted; a row range narrows a fixture, and "
                            + "inline rows are already exactly the rows to seed");
                }
            } else {
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
         * Seeds a dataset from literal record images.
         *
         * @param rows the record images in seeding order, verbatim and not empty
         * @return an inline input
         */
        public static DatasetInput ofRows(List<String> rows) {
            return new DatasetInput(rows, null, null, null);
        }

        /**
         * Seeds a dataset from every row of a classpath fixture.
         *
         * @param fixture the fixture file name relative to {@code fixtures/}, not blank
         * @return a fixture-backed input covering the whole fixture
         */
        public static DatasetInput ofFixture(String fixture) {
            return new DatasetInput(List.of(), fixture, null, null);
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
         * Whether this input names a classpath fixture. Strict complement of {@link #inline()}.
         *
         * <p>Excluded from JSON for the same reason as {@link #inline()}.
         *
         * @return {@code true} when {@code fixture} is present
         */
        @JsonIgnore
        public boolean fixtureBacked() {
            return fixture != null;
        }
    }

    /**
     * One record the unit under test is expected to have written, pinned field by field and
     * optionally as a whole record image.
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
     * <p>Expectations are applied in list order, and more than one may target the same
     * {@code dataset} and {@code rowIndex} - additive expectations over disjoint field subsets are a
     * legitimate authoring style, so this is not treated as a conflict.
     *
     * @param dataset the output dataset binding key - never a literal dataset name
     * @param rowIndex the <strong>zero-based</strong> index of the expected row within that dataset;
     *     never negative
     * @param fields copybook field name to expected value, verbatim and in declaration order; never
     *     {@code null}, and empty only when {@code expectedBytes} is present
     * @param expectedBytes the complete expected record image, or {@code null} when the case pins
     *     individual fields only; never zero-length, though it may be entirely spaces
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"dataset", "rowIndex", "fields", "expectedBytes"})
    public record ExpectedRecord(
        @JsonProperty("dataset") String dataset,
        @JsonProperty("rowIndex") int rowIndex,
        @JsonProperty("fields") Map<String, String> fields,
        @JsonProperty("expectedBytes") String expectedBytes) {

        /**
         * Validates the expectation and freezes the field map.
         *
         * @throws IllegalArgumentException if {@code dataset} is missing or is not a binding key, if
         *     {@code rowIndex} is negative, if a field name is blank, if a field value is
         *     {@code null}, if {@code expectedBytes} is zero-length, or if the expectation pins
         *     nothing at all
         */
        public ExpectedRecord {
            dataset = requireDatasetKey(dataset, "ExpectedRecord.dataset");
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
    }

    /**
     * A width normalisation {@code FieldDiffer} must apply to a fixture before comparing it against
     * a decoded record.
     *
     * <p>Two fixtures in this repository are narrower than the copybook that describes them, in both
     * cases because a trailing span is simply absent from the data. Comparing such a row directly
     * against a correctly-built record would report a spurious difference on every field at or after
     * the missing span, so the shortfall is made up with spaces first. The pad is on the
     * <strong>right</strong> and the pad character is a space, which is what COBOL itself writes into
     * an unset {@code PIC X} span.
     *
     * <p>These are the only two constants, and no third may be added. A normalisation is a recorded,
     * evidenced deviation between a fixture and its copybook - not a general-purpose escape hatch for
     * a width mismatch. A mismatch that is not one of these two is a defect in the codec or in the
     * expectation, and padding it away would hide exactly the class of error this harness exists to
     * catch. Each constant therefore carries its own widths, its copybook and the name of the absent
     * span, so that {@code FieldDiffer} reads them from here rather than hard-coding a number and so
     * that a reviewer can check the arithmetic without leaving this file.
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
        CARDXREF_FILLER_PAD_36_TO_50(36, 50, "CVACT03Y", "FILLER PIC X(14)"),

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
        USRSEC_FILLER_PAD_57_TO_80(57, 80, "CSUSR01Y", "SEC-USR-FILLER PIC X(23)");

        /** The literal row width present in the fixture. */
        private final int sourceWidth;

        /** The copybook-declared record width the fixture must be padded up to. */
        private final int targetWidth;

        /** The {@code app/cpy} member that declares {@link #targetWidth}. */
        private final String copybook;

        /** The COBOL declaration of the span the fixture omits. */
        private final String absentSpan;

        /**
         * Binds a normalisation to the evidence that justifies it, so the widths live beside the
         * copybook that fixes them rather than in whichever class happens to apply the pad.
         *
         * @param sourceWidth the literal fixture row width
         * @param targetWidth the copybook-declared record width
         * @param copybook the {@code app/cpy} member name, without extension
         * @param absentSpan the omitted span's name and picture clause
         */
        Normalisation(int sourceWidth, int targetWidth, String copybook, String absentSpan) {
            this.sourceWidth = sourceWidth;
            this.targetWidth = targetWidth;
            this.copybook = copybook;
            this.absentSpan = absentSpan;
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
    // exceptions are read by whoever is authoring one of 560 fixtures and a message that only says
    // "invalid" costs them a debugging session.
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
     * @param value the candidate return code
     * @return the validated return code
     * @throws IllegalArgumentException if the value is negative or above 4095
     */
    private static int requireReturnCode(int value) {
        if (value < 0 || value > MAX_RETURN_CODE) {
            throw new IllegalArgumentException(
                "ParityCase.expectedReturnCode must be between 0 and " + MAX_RETURN_CODE
                    + " inclusive, the range a z/OS step return code can carry, but was " + value);
        }
        return value;
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
     * Copies the job parameters into an unmodifiable, order-preserving map.
     *
     * <p>Values are kept as raw character strings and are never parsed here. The one verified
     * parameter is {@code parmDate}, whose value {@code 2022071800} comes from
     * {@code PARM='2022071800'} in {@code app/jcl/INTCALC.jcl}. It is emphatically <em>not</em> a
     * date: {@code CBACT04C} concatenates it straight into generated transaction identifiers with
     * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID}, where
     * {@code PIC X(10)} followed by {@code PIC 9(06)} fills {@code TRAN-ID PIC X(16)} exactly.
     * Parsing it into a date and re-rendering it would corrupt those identifiers. By contrast
     * {@code POSTTRAN.jcl} declares no {@code PARM} at all, so cases for the job translated from
     * {@code CBTRN02C} carry an empty map.
     *
     * @param source the declared parameters, possibly {@code null}
     * @return an unmodifiable map in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if a name is blank or a value is {@code null}
     */
    private static Map<String, String> freezeJobParameters(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = requireText(entry.getKey(), "jobParameters key");
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
     * Copies the expectations into an unmodifiable list, preserving assertion order.
     *
     * @param source the declared expectations, possibly {@code null}
     * @return an unmodifiable list in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if any element is {@code null}
     */
    private static List<ExpectedRecord> freezeExpectedRecords(List<ExpectedRecord> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) == null) {
                throw new IllegalArgumentException(
                    "ParityCase.expectedRecords[" + i + "] is null; remove the entry rather than "
                        + "leaving a hole in the assertion order");
            }
        }
        return List.copyOf(source);
    }

    /**
     * Copies the expected messages into an unmodifiable list, preserving emission order.
     *
     * <p>An empty string is a legitimate expectation - COBOL {@code DISPLAY} of a blank line emits
     * one - so only {@code null} is rejected. Messages are compared byte-exactly, which includes any
     * text a shared renderer produces: a file-status line rendered as
     * {@code 'FILE STATUS IS: NNNN'} must be written here exactly as that renderer emits it, spacing
     * and all.
     *
     * @param source the declared messages, possibly {@code null}
     * @return an unmodifiable list in emission order, empty when nothing was declared
     * @throws IllegalArgumentException if any element is {@code null}
     */
    private static List<String> freezeExpectedMessages(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) == null) {
                throw new IllegalArgumentException(
                    "ParityCase.expectedMessages[" + i + "] is null; use an empty string for a blank "
                        + "emitted line, and remove the entry if no line is expected there");
            }
        }
        return List.copyOf(source);
    }

    /**
     * Copies the normalisations into an unmodifiable list, rejecting a repeat.
     *
     * <p>With only two constants in existence, the same one appearing twice is unambiguously an
     * authoring slip rather than an intention, so it is reported rather than quietly tolerated.
     *
     * @param source the declared normalisations, possibly {@code null}
     * @return an unmodifiable list in declaration order, empty when nothing was declared
     * @throws IllegalArgumentException if any element is {@code null} or appears more than once
     */
    private static List<Normalisation> freezeNormalisations(List<Normalisation> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < source.size(); i++) {
            Normalisation normalisation = source.get(i);
            if (normalisation == null) {
                throw new IllegalArgumentException(
                    "ParityCase.normalisations[" + i + "] is null; remove the entry instead");
            }
            if (source.indexOf(normalisation) != i) {
                throw new IllegalArgumentException(
                    "ParityCase.normalisations declares " + normalisation + " more than once; each "
                        + "normalisation is applied once and listing it twice is an authoring error");
            }
        }
        return List.copyOf(source);
    }

    /**
     * Copies inline rows into an unmodifiable list, preserving both order and every byte.
     *
     * <p>A row is stored exactly as written: not trimmed, not padded, not re-encoded. Significant
     * trailing spaces and zoned sign-overpunch bytes are part of the record image, and normalising
     * them here would corrupt the input the case is meant to seed.
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
}
