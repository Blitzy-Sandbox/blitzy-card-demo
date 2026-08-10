package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.transaction.DateParmReader;
import com.vsergeychik.carddemo.transaction.DateParmReader.DateParm;
import com.vsergeychik.carddemo.transaction.ReportRequestController;
import com.vsergeychik.carddemo.transaction.ReportRequestController.JobSubmissionPort;
import com.vsergeychik.carddemo.transaction.ReportRequestController.ProgramState;
import com.vsergeychik.carddemo.transaction.ReportRequestController.WriteQueueOutcome;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parity gate for {@code app/cbl/CORPT00C.cbl} - twenty declarative cases, judged field by field,
 * with a required diff count of zero.
 *
 * <h2>Risk R-A: the baseline is statically derived, never captured</h2>
 *
 * <p>Every expected value in {@code src/test/resources/parity/CORPT00C/} was produced by <strong>reading
 * the COBOL</strong> - the seventeen {@code PIC X(80)} {@code VALUE} items of {@code 02 JOB-DATA-1} at
 * {@code :83-125}, the message literals at their own source lines, the symbolic map
 * {@code app/cpy-bms/CORPT00.CPY}, the mapset {@code app/bms/CORPT00.bms}, the queue definition at
 * {@code app/csd/CARDDEMO.CSD:499-505} and the job {@code app/jcl/TRANREPT.jcl} the emitted stream
 * submits. <strong>None of it was captured from a running COBOL program</strong>, because running one is
 * impossible in this environment. Practice B12 requires that limit to be stated where the expectations
 * live rather than absorbed silently, so it is stated here, and three of the recorded blockers land
 * directly on this program: there is no CICS emulator, so a program whose every {@code EXEC CICS} command
 * is {@code WRITEQ TD}, {@code SEND}, {@code RECEIVE}, {@code RETURN} or {@code XCTL} cannot be executed
 * at any level; the IBM-supplied {@code DFHAID} and {@code DFHBMSCA} copybooks it copies at {@code :148}
 * and {@code :149} are absent from this repository (risk R-D), so their constants are reproduced in
 * {@link CicsAid} and {@link BmsAttributes} from IBM CICS documentation; and no Language Environment
 * {@code CEE*} service exists, which is what {@code CSUTLDTC} - called from {@code :392} and {@code :412}
 * - is a wrapper around.
 *
 * <p>Because a statically derived expectation can encode a misreading where a captured one cannot, every
 * case's {@code description} names the source lines it was derived from, and the nested classes below
 * re-derive the widths, the offsets and the AID mapping <em>mechanically</em> from the published layouts
 * instead of restating them as literals.
 *
 * <h2>The documented substitution: Java has no transient data queue</h2>
 *
 * <p>{@code CORPT00C} submits its batch job with {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at
 * {@code :517}. Java has no transient data queue and no internal reader, so that command is reproduced
 * through an explicit outbound port, {@link JobSubmissionPort} - and this is a <strong>documented
 * substitution, not a silent one</strong>. What is substituted is the transport; what is <em>not</em>
 * substituted is a single byte of the content:
 *
 * <ul>
 *   <li>{@code app/csd/CARDDEMO.CSD:499-505} defines the queue as {@code TYPE(EXTRA)}
 *       {@code DDNAME(INREADER)} {@code TYPEFILE(OUTPUT)} {@code RECORDSIZE(80)}
 *       {@code RECORDFORMAT(FIXED)} {@code BLOCKFORMAT(UNBLOCKED)} {@code DISPOSITION(MOD)}
 *       {@code ERROROPTION(IGNORE)}. Every one of those attributes is asserted here: the width, one
 *       record per block with nothing between them, append rather than truncate, and an outcome
 *       <em>reported</em> to the caller rather than thrown at it.</li>
 *   <li>All seventeen records are pinned <strong>byte for byte and in emission order</strong>, each at
 *       exactly {@value ReportRequestController#JCL_RECORD_LENGTH} characters, through the parity case
 *       model itself - as writes to the dataset key {@code JOBS} against a single-span
 *       {@code JCL-RECORD PIC X(80)} layout. A record short by one byte fails, a record out of order
 *       fails, and a record the program never produced is reported as unexpected output. That is
 *       gate G42.</li>
 *   <li>Record fifteen is not merely a record: it <strong>is</strong> the {@code DATEPARM} record that
 *       {@link DateParmReader} consumes on the batch side, so {@link TheDateParmHandOff} reads it back
 *       through that reader's own twenty-one byte receiver and requires the range to be the one the
 *       request asked for. This is the single place in the migration where an online program's output
 *       is a batch program's input, and the round trip is the strongest available proof that the two
 *       halves agree.</li>
 * </ul>
 *
 * <h2>What the twenty cases pin</h2>
 *
 * <p><strong>This program accesses no dataset.</strong> It is the only one of the seventeen online
 * programs with no file command at all - it declares {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'}
 * at {@code :40} and never opens it - so every case seeds nothing and the whole fingerprint is the
 * response, the emitted stream, the two message channels and the two {@code DISPLAY} statements.
 *
 * <p>Between them the cases drive: the {@code EIBCALEN = 0} guard; first entry, where
 * {@code MOVE LOW-VALUES TO CORPT0AO} clears all seventeen items; all three arms of the ordered
 * {@code EVALUATE EIBAID} including {@code WHEN OTHER}; all three arms of the ordered report-type
 * {@code EVALUATE} plus its {@code WHEN OTHER}; the monthly month-end derivation at a leap February, a
 * thirty-day month and December, where the arm's own year roll-over runs; both ends of the six-arm blank
 * chain; {@code FUNCTION NUMVAL-C} accepting an embedded space and rejecting a non-conforming argument
 * through <em>both</em> of its receivers; the character comparison {@code SDTMMI > '12'}; all three
 * {@code CSUTLDTC} outcomes - converted, the tolerated {@code '2513'}, and rejected; the confirm prompt,
 * the {@code 'N'} arm, and a queue that refuses the first record; and the success notice with
 * {@code DFHGREEN} on {@code ERRMSGC}.
 *
 * <p>The unit is {@link ReportRequestController}, constructed as a <strong>plain Java object</strong>
 * and called through {@code mainPara} directly. There is no {@code MockMvc}, no
 * {@code TestRestTemplate}, no {@code WebTestClient}, no servlet container and no {@code JobLauncher}
 * anywhere in the path, so every decision this program makes is reached with nothing between the
 * assertion and the arithmetic (gate G51). The {@code transaction} package created no service beneath
 * this controller, which is why the controller itself is the unit and why the case model records it as
 * {@link UnitKind#CONTROLLER_POJO}.
 *
 * <p>No server-side state is created, read or relied upon: the communication area, the attention
 * identifier and the seventeen screen values all travel in the payload, and the response's
 * {@code navigation}, {@code nextProgram}, {@code nextMapset} and {@code nextMap} are compared as
 * ordinary fields (gates G37, G40). Nothing in this class is {@code static} and mutable (gate G53), no
 * import is a wildcard (gate G52), no {@code double} or {@code float} appears (gate G22), and no
 * {@code String} is encoded or decoded without a named code page (practice B8) - the code page is the
 * one the case declares, taken from {@link ParityHarness.Invocation#charset()}.
 *
 * <p>There is no monetary arithmetic in this program to compare - it declares
 * {@code WS-TRAN-AMT PIC +99999999.99} at {@code :77} and never references it, and its only two numeric
 * receivers are the unsigned integers {@code WS-NUM-99 PIC 99} and {@code WS-NUM-9999 PIC 9999}. Numeric
 * parity is therefore asserted where it <em>is</em> observable: the six
 * {@code COMPUTE ... FUNCTION NUMVAL-C} stores at {@code :305-327} go through
 * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)}, and because the keyword {@code ROUNDED}
 * appears zero times in this program they must <strong>truncate</strong> the fraction rather than round
 * it - so {@code 7.9} stores as {@code 7} and the map item shows {@code "07"}, never {@code "08"} (rule
 * R2, gate G24).
 *
 * @see ParityHarness for how a case is seeded, run and fingerprinted
 * @see FieldDiffer for the field-by-field comparison the diff count comes from
 * @see CSUTLDTCParityTest which owns the nine-token {@code CEEDAYS} table this class relies on
 */
@DisplayName("CORPT00C parity - transaction CR00, and seventeen eighty-byte records for the reader")
final class CORPT00CParityTest {

    /**
     * The program under test, which is also the name of its case directory:
     * {@code src/test/resources/parity/CORPT00C/}.
     */
    private static final String PROGRAM = "CORPT00C";

    /**
     * {@code CORPT00C} is a CICS online program and the migration gives it a controller with no service
     * beneath it, so the unit a case reaches is the controller itself, constructed as a plain object.
     */
    private static final UnitKind UNIT_KIND = UnitKind.CONTROLLER_POJO;

    /**
     * The dataset binding key the emitted records are recorded under.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:499} defines {@code TDQUEUE(JOBS)}, so {@code JOBS} is the queue's
     * own name rather than a name invented here, and it is a binding key rather than a dataset name -
     * the destination the port actually appends to comes from {@code carddemo.job-submission} in
     * configuration, and no mainframe dataset-name literal is written anywhere in this file (gate G46).
     */
    private static final String QUEUE_DATASET = "JOBS";

    /** {@code JCL-RECORD PIC X(80)} - {@code app/cbl/CORPT00C.cbl:79}, the one span of a queue record. */
    private static final String JCL_RECORD_FIELD = "JCL-RECORD";

    /**
     * One queue record as a fixed-width layout: a single {@code PIC X(80)} span and nothing else.
     *
     * <p>Eighty bytes is not this class's choice twice over - it is
     * {@link ReportRequestController#JCL_RECORD_LENGTH}, which is itself {@code :79}'s declared width and
     * the queue's {@code RECORDSIZE(80)}. {@link RecordLayout} runs a geometry self-check on
     * construction, so declaring the layout here is what proves the span accounts for all eighty bytes;
     * a mistyped width would fail this field's initialisation and name the offending descriptor.
     */
    private static final RecordLayout JCL_RECORD_LAYOUT = RecordLayout.of(
            ReportRequestController.JCL_RECORD_LENGTH,
            FieldSpan.alphanumeric(JCL_RECORD_FIELD, 0, ReportRequestController.JCL_RECORD_LENGTH));

    /**
     * The symbolic-map length item {@code MOVE -1} writes into, expressed as the suffix the copybook
     * itself uses rather than as a literal per field.
     */
    private static final String LENGTH_ITEM_SUFFIX = "L";

    /**
     * {@code app/cpy/COCOM01Y.cpy} is 160 bytes, and the {@code XCTL} at {@code :549} and the
     * {@code RETURN} at {@code :589} both pass exactly that area - this program declares no extension of
     * its own, unlike the transaction-list screens.
     */
    private static final int COMMAREA_LENGTH = NavigationContext.COMMAREA_LENGTH;

    // =================================================================================================
    // The gate.
    // =================================================================================================

    /**
     * The program's twenty cases, in ascending case order.
     *
     * <p>{@link ParityHarness#casesOf(String)} is the only loader used, and it refuses anything other
     * than exactly {@code case01.json} through {@code case20.json} - a short set, a long set, or a
     * directory holding a stray file all fail loudly there. The checks below state the count, the
     * numbering and the unit kind a second time at the call site, so a reader of this class can see what
     * the gate requires without following the call (gate G15).
     *
     * @return exactly twenty cases
     */
    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);

        assertThat(loaded)
                .as("the gate is stated as twenty declarative cases per program, and "
                        + "'diff count is zero across all twenty' is satisfied vacuously by a shorter "
                        + "set - so the count is asserted before a single case runs")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        List<String> ids = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            ids.add(parityCase.caseId());
            assertThat(parityCase.program())
                    .as("every case in parity/%s/ must name that program, or it is judging a "
                            + "different one", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                    .as("case %s declares unitKind %s; CORPT00C is an online program reached as a "
                            + "plain controller object, so every one of its cases is %s",
                            parityCase.caseId(), parityCase.unitKind(), UNIT_KIND)
                    .isEqualTo(UNIT_KIND);
            assertThat(parityCase.inputs())
                    .as("case %s seeds a dataset. CORPT00C performs no file command at all - every "
                            + "EXEC CICS in app/cbl/CORPT00C.cbl is WRITEQ TD, SEND, RECEIVE, RETURN or "
                            + "XCTL - so a seeded dataset would be data no path can reach",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.screenRequest())
                    .as("case %s declares no screenRequest; an online program is driven by its "
                            + "communication area, its AID and its received map, and all three travel "
                            + "there", parityCase.caseId())
                    .isNotNull();
            assertThat(parityCase.expectedResponse())
                    .as("case %s declares no expectedResponse. Several paths of this program write no "
                            + "record at all and are entirely response, so without one they would "
                            + "assert nothing", parityCase.caseId())
                    .isNotNull();
        }

        List<String> expectedIds = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expectedIds.add(ParityHarness.caseId(ordinal));
        }
        assertThat(ids)
                .as("the twenty cases must be case01 through case20 in order, so that a mis-numbered "
                        + "fixture cannot silently replace another")
                .containsExactlyElementsOf(expectedIds);

        return loaded;
    }

    /**
     * Runs one case and requires the differ to find nothing.
     *
     * <p>The assertion is on the whole {@link DiffResult} rather than on a boolean, so a failure reports
     * the count and then every difference the differ found, each naming the field, the expected value,
     * the observed value and why the field matters. That rendering is the differ's work and is surfaced
     * verbatim rather than summarised.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("field-for-field identical to app/cbl/CORPT00C.cbl")
    void isFieldForFieldIdenticalToTheCobol(ParityCase parityCase) {
        DiffResult diff =
                ParityHarness.usAscii().judge(parityCase, UNIT_KIND, CORPT00CParityTest::execute);

        assertThat(diff.count())
                .as("%s/%s must produce a diff count of zero. A module is not complete until the count "
                        + "is zero across all twenty of its cases, so a single difference here is a "
                        + "failed gate rather than a tolerance.%n%s",
                        parityCase.program(), parityCase.caseId(), diff.render())
                .isZero();
        assertThat(diff.isClean())
                .as("the differ reported a clean result and a non-zero count, or the reverse - the two "
                        + "must agree.%n%s", diff.render())
                .isTrue();
    }

    // =================================================================================================
    // The adapter: how a case reaches CORPT00C. No HTTP, no job launcher, no session (gates G37, G51).
    // =================================================================================================

    /**
     * Constructs {@link ReportRequestController} as a plain object and calls its {@code MAIN-PARA}
     * method once.
     *
     * <p>One invocation is one CICS task. The four constructor arguments are supplied explicitly and
     * none of them is a mock of the unit's own logic:
     *
     * <ul>
     *   <li>a real {@link DateUtilityJob}, because {@code CSUTLDTC} is a called subprogram and its
     *       severity and message number are what {@code :396} and {@code :416} branch on. Stubbing it
     *       would replace the decision under test with the decision the stub was told to make;</li>
     *   <li>a capturing {@link CapturedJobsQueue}, which is the transient data queue's stand-in and
     *       enforces the eighty-byte contract itself rather than trusting its caller;</li>
     *   <li>the case's pinned clock, so the two header items {@code POPULATE-HEADER-INFO} builds at
     *       {@code :613-628} and the month end the monthly arm derives at {@code :229-230} are
     *       comparable byte for byte and the run is deterministic (practice B7);</li>
     *   <li>the case's code page, never the platform default (practice B8).</li>
     * </ul>
     *
     * @param invocation the clocked invocation the harness prepared
     * @return the fingerprint of that one run
     */
    private static ParityHarness.UnitOutcome execute(ParityHarness.Invocation invocation) {
        CapturedJobsQueue queue = new CapturedJobsQueue(invocation);
        ReportRequestController controller = new ReportRequestController(
                new DateUtilityJob(),
                queue,
                invocation.clock(),
                invocation.charset());

        ProgramState state = controller.mainPara(requestOf(invocation));

        requireQueueAgreesWithProgram(queue, state);
        return fingerprintOf(invocation, state);
    }

    /**
     * Assembles the request from the three things {@code CORPT00C} is driven by.
     *
     * <p>{@code EIBCALEN = 0} is expressed by leaving the communication area absent, which is what the
     * translation reads {@code :172} as: a request with no area cannot say who called, and that is the
     * whole content of the condition. A case declaring {@code eibcalen: 0} therefore produces a request
     * carrying no {@link NavigationContext} at all rather than one carrying a blank area, and the two are
     * not the same state.
     *
     * <p>For a non-zero length the area is assembled through the codec from the field images the case
     * declares and then parsed back by the domain type. That is
     * {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} done the way the copybook describes it:
     * the case names fields, the codec lays them out at their declared offsets and widths, and a field
     * the case does not name keeps the initialised content its {@code PICTURE} gives it. Nothing here
     * decides what a field means.
     *
     * @param invocation the invocation being run
     * @return the inbound screen
     */
    private static ReportRequestRequest requestOf(ParityHarness.Invocation invocation) {
        ReportRequestRequest request = ReportRequestRequest.empty();
        FixedWidthCodec codec = invocation.codec();

        if (invocation.eibcalen() > 0) {
            request = request.withNavigationContext(NavigationContext.fromFixedWidth(codec,
                    codec.serialise(NavigationContext.LAYOUT, invocation.commarea())));
        } else {
            request = request.withoutNavigationContext();
        }

        // EIBAID. The case declares a DFHAID mnemonic, which is what a reader of the case needs to see;
        // the payload projects the resolved five-character token, because that is what this screen's
        // request DTO carries. An undeclared AID leaves the request's own initialised value - five
        // spaces - which the translation reads as DFHNULL, the AID CICS reports when no key raised the
        // interrupt and one that matches neither of the two arms :185 and :187 name.
        String token = aidTokenOf(invocation.aid());
        if (token != null) {
            request = request.withAid(token);
        }

        for (Map.Entry<String, String> field : invocation.mapFields().entrySet()) {
            request = request.withValue(screenFieldOf(field.getKey()), field.getValue());
        }
        return request;
    }

    /**
     * The five-character AID token a {@code DFHAID} mnemonic resolves to, as the payload carries it.
     *
     * <p>Two indirections, and both are deliberate. {@code DFHAID} is IBM-supplied and absent from this
     * repository (risk R-D), so {@link CicsAid} is the single reproduction of it and the
     * mnemonic-to-byte correspondence is read from there rather than restated. The byte is then folded
     * to a token by {@link PfKeyResolver}, which is the module's one reproduction of
     * {@code app/cpy/CSSTRPFY.cpy} - and using it here matters even though {@code CORPT00C} does
     * <em>not</em> copy that member: this program tests {@code EIBAID} inline at {@code :184-195}, and
     * the inline tests and the shared resolver have to produce identical boolean outcomes or two
     * screens would disagree about which key was pressed.
     *
     * <p>A mnemonic {@link PfKeyResolver} does not fold - {@code DFHPA3} and {@code DFHNULL} among them -
     * has no token, so no AID is set and the request keeps its initialised spaces. That is the correct
     * projection: the {@code WHEN OTHER} arm is exactly where such a key belongs.
     *
     * @param mnemonic the mnemonic the case declared, or {@code null} for a path that reads no AID
     * @return the token, or {@code null} when the case declared no AID or the AID folds to none
     * @throws IllegalArgumentException if the mnemonic names no constant {@link CicsAid} reproduces
     */
    private static String aidTokenOf(String mnemonic) {
        if (mnemonic == null) {
            return null;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                Optional<AidKey> folded = PfKeyResolver.resolve(entry.getKey());
                return folded.map(AidKey::token).orElse(null);
            }
        }
        throw new IllegalArgumentException('"' + mnemonic + "\" is not a DFHAID mnemonic that "
                + CicsAid.class.getName() + " reproduces, yet ParityCase accepted it. The two read the "
                + "same map, so this means they have drifted apart.");
    }

    /**
     * The screen field one {@code xxxI} item name refers to.
     *
     * <p>Resolved against {@link ReportRequestRequest.ScreenField#inputItem()} rather than against a
     * seventeen-way table of literals, because the item name is derived from the {@code DFHMDF} label by
     * the copybook's own suffix rule - so a resolution cannot disagree with the copybook, whereas
     * seventeen hand-copied literals could.
     *
     * @param inputItem the {@code xxxI} name the case declared
     * @return the field it names
     * @throws IllegalArgumentException if the name is not one of the seventeen, listing the declared set
     */
    private static ReportRequestRequest.ScreenField screenFieldOf(String inputItem) {
        List<String> declared = new ArrayList<>(ReportRequestRequest.FIELD_COUNT);
        for (ReportRequestRequest.ScreenField field : ReportRequestRequest.ScreenField.values()) {
            if (field.inputItem().equals(inputItem)) {
                return field;
            }
            declared.add(field.inputItem());
        }
        throw new IllegalArgumentException('"' + inputItem + "\" is not one of the "
                + ReportRequestRequest.FIELD_COUNT + " xxxI items app/cpy-bms/CORPT00.CPY declares. The "
                + "declared set is " + declared + ". The xxxL, xxxF and xxxA items are length, flag and "
                + "attribute metadata and are not payload fields, so they are not settable from a case.");
    }

    /**
     * Requires the queue's own record of what it was handed to match the program's own record of what it
     * handed over.
     *
     * <p>The two are kept independently on purpose. {@link ProgramState#submittedRecords()} is the
     * program's account - it appends a record once the record has been handed over, whatever the queue
     * then reported, because {@code DISPOSITION(MOD)} means there is no rollback. The queue's list is the
     * transport's account. A translation that recorded a submission it never made, or made one it never
     * recorded, would satisfy one of the two and not the other, and everything downstream of this - the
     * seventeen pinned records included - is projected from the program's account.
     *
     * @param queue the capturing queue the run went through
     * @param state the working storage as it stood when the task ended
     */
    private static void requireQueueAgreesWithProgram(CapturedJobsQueue queue, ProgramState state) {
        assertThat(queue.records())
                .as("the records the port was handed and the records WIRTE-JOBSUB-TDQ recorded must be "
                        + "the same list in the same order: app/cbl/CORPT00C.cbl:517 hands one record to "
                        + "the queue per iteration of the loop at :498-508, and nothing else writes")
                .containsExactlyElementsOf(state.submittedRecords());
    }

    // =================================================================================================
    // Projecting the run into a fingerprint.
    // =================================================================================================

    /**
     * Everything one execution of {@code CORPT00C} leaves behind that a case can compare.
     *
     * <p>Four channels, and the second is the one this program exists for:
     *
     * <ol>
     *   <li>the online response - the screen, the navigation context, the next program, the mapset and
     *       map, the cursor and the termination;</li>
     *   <li>the eighty-byte records handed to the queue, in emission order, recorded against the
     *       {@code JCL-RECORD} layout so the differ compares them as records rather than as one opaque
     *       1,360-character string. They are recorded <em>only</em> when at least one was handed over:
     *       the queue is {@code OPENTIME(INITIAL)} and this program issues no {@code OPEN}, so
     *       "opened and wrote nothing" is not a state it can be in, and reporting the dataset with zero
     *       rows would assert something the COBOL never does;</li>
     *   <li>{@code WS-MESSAGE} at its declared {@code PIC X(80)} and {@code ERRMSGO} at its declared
     *       {@code PIC X(78)}, reported as two separate channels. {@code MOVE WS-MESSAGE TO ERRMSGO} at
     *       {@code :560} moves eighty characters into a seventy-eight character receiver, so the two
     *       differ by the two bytes COBOL loses on the right, and reporting only one of them would make
     *       that move unobservable;</li>
     *   <li>the two {@code DISPLAY} statements - {@code :210}, which every {@code ENTER} path emits, and
     *       {@code :529}, which only a refused write emits.</li>
     * </ol>
     *
     * <p>The {@code RETURN-CODE} is reported as zero. {@code CORPT00C} is an online program: it sets no
     * {@code RETURN-CODE} and contains no {@code CALL 'CEE3ABD'}, so zero is the value and it is stated
     * rather than defaulted.
     *
     * @param invocation the invocation that was run
     * @param state      the working storage as it stood when the task ended
     * @return the recorded outcome
     */
    private static ParityHarness.UnitOutcome fingerprintOf(ParityHarness.Invocation invocation,
                                                           ProgramState state) {
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        recorder.response(observedResponseOf(invocation.codec(), state));

        if (!state.submittedRecords().isEmpty()) {
            recorder.wroteAll(QUEUE_DATASET, JCL_RECORD_LAYOUT, state.submittedRecords());
        }

        recorder.message(new EmittedMessage(MessageChannel.WS_MESSAGE_80, state.message()));
        recorder.message(new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78,
                state.response().getErrmsgo()));
        for (String line : state.displayLines()) {
            recorder.display(line);
        }

        recorder.returnCode(0);
        return recorder.build();
    }

    /**
     * The response as the differ compares it.
     *
     * <p><strong>Why there is at most one send.</strong> {@code SEND-TRNRPT-SCREEN} ends
     * {@code GO TO RETURN-TO-CICS} at {@code :580}, and {@code RETURN-TO-CICS} issues
     * {@code EXEC CICS RETURN} - which ends the task. So a send never returns to its caller and no path
     * through this program can send twice. The send count is still reported as a list, because the count
     * is behaviour: a translation that lost the guard at {@code :445} would send the success notice on
     * top of a rejection, and the list is where that shows up.
     *
     * <p><strong>Why a blank mapset is reported as none.</strong> {@code RETURN-TO-PREV-SCREEN} sends no
     * map before transferring - the target program paints its own - so the response blanks its mapset and
     * map rather than leaving them naming this screen. A blank is how the 3270 layer says "no map";
     * {@code null} is how the case model says it, because {@link ParityCase.ExpectedResponse} validates a
     * mapset name against a pattern a run of spaces cannot satisfy. Translating one to the other here
     * keeps the case readable and loses nothing: the blank and the absence carry the same single fact.
     *
     * <p><strong>Why {@code ERRMSGC} is always reported.</strong> It is the one attribute item this
     * program assigns - {@code MOVE DFHGREEN TO ERRMSGC OF CORPT0AO} at {@code :448}, on the success path
     * only - so reporting it on every send is what proves the other nineteen paths leave it at the map's
     * default colour. This program copies neither {@code CSSETATY} nor {@code CSSTRPFY}, so no field is
     * recoloured red and no {@code '*'} is written into a blank one; an empty attribute map for the other
     * sixty-seven items says exactly that (gate G38).
     *
     * @param codec the case's code page and move rules
     * @param state the working storage as it stood when the task ended
     * @return the observed response
     */
    private static ObservedResponse observedResponseOf(FixedWidthCodec codec, ProgramState state) {
        ReportRequestResponse response = state.response();

        // The 160 bytes the XCTL at :549 and the RETURN at :589 both pass, projected field by field.
        // Statelessness (rule R6, gate G37) is what makes this comparable at all: the conversation state
        // is in the payload, so it can be read off the response instead of out of a session.
        Map<String, String> navigation = new LinkedHashMap<>(codec.deserialise(
                NavigationContext.LAYOUT, response.getNavigationContext().toFixedWidth(codec)));

        List<ObservedSend> sends = new ArrayList<>(1);
        if (state.screenSent()) {
            sends.add(new ObservedSend(response.fieldImages(),
                    Map.of(ReportRequestResponse.ScreenField.ERRMSG.colourItemName(),
                            BmsAttributes.colourMnemonic(response.getErrmsgc()))));
        }

        return new ObservedResponse(
                response.getNextProgram(),
                namedOrNone(response.getNextMapset()),
                namedOrNone(response.getNextMap()),
                navigation,
                sends,
                cursorLengthItemOf(state),
                terminationOf(state));
    }

    /**
     * A mapset or map name, or {@code null} where the program named none.
     *
     * @param reference the value the response carries
     * @return the name, or {@code null} when it is absent or blank
     */
    private static String namedOrNone(String reference) {
        return reference == null || reference.isBlank() ? null : reference;
    }

    /**
     * The symbolic-map length item {@code MOVE -1} was moved into, or {@code null} where the program
     * requested no cursor.
     *
     * <p>COBOL positions the cursor by moving {@code -1} into a field's {@code xxxL} item, so the length
     * item <em>is</em> the cursor and it is reported under that name rather than under the field's.
     * {@code CORPT00C} issues that move at twenty-two sites and every {@code EXEC CICS SEND} it performs
     * specifies {@code CURSOR}, which is what makes them meaningful. The field reported is the first in
     * map declaration order whose length item holds {@code -1}, which is the one the terminal would place
     * the cursor in.
     *
     * <p>The two paths that transfer control - {@code :174} and {@code :189} - send no map and request no
     * cursor.
     *
     * @param state the working storage as it stood when the task ended
     * @return the {@code xxxL} item name, or {@code null}
     */
    private static String cursorLengthItemOf(ProgramState state) {
        for (ReportRequestRequest.ScreenField field : ReportRequestRequest.ScreenField.values()) {
            if (state.cursorRequestedOn(field)) {
                return field.lengthItem();
            }
        }
        return null;
    }

    /**
     * How the task ended: {@code EXEC CICS XCTL} at {@code :548-551} or {@code EXEC CICS RETURN} at
     * {@code :587-591}.
     *
     * <p>The two are not interchangeable and cannot both happen: an {@code XCTL} transfers and never
     * comes back, so the {@code EXEC CICS RETURN} the source also writes at {@code :199-202} is
     * unreachable - every arm above it has already returned or transferred. Neither happening is a defect
     * rather than a third outcome, so it is refused here instead of being reported as one.
     *
     * @param state the working storage as it stood when the task ended
     * @return the termination the run performed
     * @throws IllegalStateException if the run performed both, or neither
     */
    private static Termination terminationOf(ProgramState state) {
        if (state.transferred() && state.returned()) {
            throw new IllegalStateException("The run reported both EXEC CICS XCTL and EXEC CICS RETURN. "
                    + "An XCTL transfers control and never comes back, so app/cbl/CORPT00C.cbl cannot "
                    + "reach both in one task and a translation that did has invented a behaviour.");
        }
        if (state.transferred()) {
            return Termination.XCTL;
        }
        if (state.returned()) {
            return Termination.RETURN_TRANSID;
        }
        throw new IllegalStateException("The run reported neither EXEC CICS XCTL nor EXEC CICS RETURN. "
                + "Every arm of MAIN-PARA ends in one of the two - :174 and :189 transfer, and every "
                + "other arm reaches SEND-TRNRPT-SCREEN, which ends GO TO RETURN-TO-CICS at :580 - so a "
                + "task that ended in neither has lost its navigation entirely.");
    }

    // =================================================================================================
    // The transient data queue's stand-in.
    // =================================================================================================

    /**
     * The capturing {@link JobSubmissionPort} every case runs through: the test-side reproduction of
     * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}.
     *
     * <p>Every attribute of {@code app/csd/CARDDEMO.CSD:499-505} is honoured rather than assumed:
     *
     * <ul>
     *   <li><strong>{@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)}</strong> - the width is
     *       enforced <em>here</em>, in the transport, rather than only in an expectation. A record of any
     *       other length fails immediately and names the record's ordinal, which is what makes gate G42
     *       a property of the port and not merely of the case files;</li>
     *   <li><strong>the code page is named</strong> - the record is encoded through
     *       {@link FixedWidthCodec#encodeImage(String, String)} with the case's charset, which refuses a
     *       character the code page cannot represent instead of substituting {@code '?'} for it as
     *       {@code String.getBytes()} would, and the encoded image is then required to be eighty
     *       <em>bytes</em>. A single-byte code page is what makes the character check and the byte check
     *       equivalent, and checking both is what proves it;</li>
     *   <li><strong>{@code DISPOSITION(MOD)}</strong> - records accumulate and nothing is ever
     *       truncated, so a refusal part way through leaves the earlier records standing;</li>
     *   <li><strong>{@code ERROROPTION(IGNORE)}</strong> - a refusal is <em>reported</em> as the
     *       {@code RESP} the program's own {@code EVALUATE WS-RESP-CD} at {@code :525} expects, and is
     *       never thrown at the caller;</li>
     *   <li><strong>{@code TYPEFILE(OUTPUT)}</strong> - there is no read operation, and none is invented.
     *       </li>
     * </ul>
     *
     * <p>Not {@code static} in any mutable sense: one instance belongs to one invocation, created inside
     * {@link #execute(ParityHarness.Invocation)}, so twenty cases cannot see each other's records
     * (practice B9).
     */
    private static final class CapturedJobsQueue implements JobSubmissionPort {

        /** The invocation this queue serves, which owns the code page and any forced outcome. */
        private final ParityHarness.Invocation invocation;

        /** Every record handed over, in order. Never truncated - {@code DISPOSITION(MOD)}. */
        private final List<String> records = new ArrayList<>();

        /**
         * @param invocation the invocation being run; never {@code null}
         */
        CapturedJobsQueue(ParityHarness.Invocation invocation) {
            this.invocation = Objects.requireNonNull(invocation, "An Invocation is required: the queue "
                    + "takes its code page and its forced outcome from the case being run");
        }

        /**
         * {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD) LENGTH(LENGTH OF JCL-RECORD)}.
         *
         * <p>The forced outcome is consumed <strong>on the first write rather than at construction</strong>,
         * and that ordering is the point: the harness refuses a run that left a declared forced outcome
         * unasked-for, so a case that declares one and takes a path which never writes fails loudly
         * instead of passing while claiming to have exercised the refusal.
         *
         * @param jclRecord the record the program has just filled
         * @return the {@code RESP} and {@code RESP2} the caller evaluates
         */
        @Override
        public WriteQueueOutcome writeQueueTd(String jclRecord) {
            int ordinal = records.size() + 1;
            assertThat(jclRecord)
                    .as("record %d handed to TDQUEUE(JOBS), which app/csd/CARDDEMO.CSD:499-505 declares "
                            + "RECORDSIZE(80) RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED); JCL-RECORD is "
                            + "PIC X(80) at app/cbl/CORPT00C.cbl:79, so a record of any other length "
                            + "cannot go to this queue", ordinal)
                    .isNotNull()
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            assertThat(invocation.codec().encodeImage(jclRecord,
                            "record " + ordinal + " of the " + QUEUE_DATASET + " queue"))
                    .as("record %d must encode to exactly %d bytes in the case's own code page (%s), "
                            + "because the queue's RECORDSIZE is a byte count and the code page is "
                            + "stated rather than assumed", ordinal,
                            ReportRequestController.JCL_RECORD_LENGTH, invocation.charset().name())
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);

            records.add(jclRecord);

            if (invocation.hasForcedOutcome(RepositoryOperation.WRITE)) {
                return refusal(invocation.forcedOutcome(RepositoryOperation.WRITE));
            }
            return WriteQueueOutcome.NORMAL;
        }

        /**
         * The records handed over, in order.
         *
         * @return an unmodifiable view
         */
        List<String> records() {
            return Collections.unmodifiableList(records);
        }

        /**
         * Translates a case's declared {@link ParityCase.ForcedOutcome} into the outcome the queue
         * reports.
         *
         * <p>Only {@link com.vsergeychik.carddemo.common.FileStatus.Outcome#OTHER} is accepted, and the
         * reason is that the {@code EVALUATE WS-RESP-CD} at {@code :525-535} has exactly two arms:
         * {@code DFHRESP(NORMAL)} and {@code WHEN OTHER}. A normal write needs no forcing - it is what
         * every unforced case already does - and there is no third arm for any other outcome to reach, so
         * forcing one would be a declaration that could not affect the run.
         *
         * @param forced what the case declared
         * @return the corresponding {@code RESP} and {@code RESP2}
         */
        private WriteQueueOutcome refusal(ParityCase.ForcedOutcome forced) {
            return switch (forced.outcome()) {
                case OTHER -> new WriteQueueOutcome(
                        forced.resp() == null ? WriteQueueOutcome.notOpen().resp() : forced.resp(),
                        forced.resp2() == null ? WriteQueueOutcome.notOpen().resp2() : forced.resp2());
                case OK, END_OF_FILE, NOT_FOUND, DUPLICATE -> throw new IllegalArgumentException(
                        "A CORPT00C case forced the " + forced.outcome() + " outcome for the queue "
                                + "write. EVALUATE WS-RESP-CD at app/cbl/CORPT00C.cbl:525-535 has two "
                                + "arms - DFHRESP(NORMAL) and WHEN OTHER - so a normal write needs no "
                                + "forcing and no other outcome has an arm to reach. Declare OTHER with "
                                + "the RESP the condition reports, or remove the declaration.");
            };
        }
    }

    // =================================================================================================
    // Structural assertions. Each one re-derives a fact about the COBOL mechanically, so a case file that
    // was transcribed wrongly is caught by something other than another transcription.
    // =================================================================================================

    /**
     * The seventeen eighty-byte records of {@code 02 JOB-DATA-1} - {@code app/cbl/CORPT00C.cbl:83-125} -
     * and the four points a request substitutes into them. This is gate G42 stated as geometry rather
     * than as content.
     */
    @Nested
    @DisplayName("the seventeen eighty-byte records and their four substitution points")
    class TheSkeleton {

        @Test
        @DisplayName("seventeen records, every one exactly eighty characters and eighty bytes")
        void theTemplateGeometryIsTheDeclaredGeometry() {
            assertThat(ReportRequestController.JOB_DATA_TEMPLATE)
                    .as("02 JOB-DATA-1 declares seventeen PIC X(80) items at app/cbl/CORPT00C.cbl:83-125")
                    .hasSize(ReportRequestController.JOB_LINE_COUNT)
                    .allSatisfy(record -> assertThat(record)
                            .hasSize(ReportRequestController.JCL_RECORD_LENGTH));
            assertThat(ReportRequestController.JOB_DATA_LENGTH)
                    .as("the group is seventeen records of eighty bytes, which is what JOB-DATA-2 "
                            + "REDEFINES at :126-127")
                    .isEqualTo(ReportRequestController.JOB_LINE_COUNT
                            * ReportRequestController.JCL_RECORD_LENGTH);

            FixedWidthCodec codec = ParityHarness.usAscii().codec();
            for (String record : ReportRequestController.JOB_DATA_TEMPLATE) {
                assertThat(codec.encodeImage(record, "a " + QUEUE_DATASET + " queue record"))
                        .as("the queue's RECORDSIZE is a byte count, so the width has to hold in the "
                                + "code page as well as in characters")
                        .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("the last record is the '/*EOF' sentinel, space filled to eighty")
        void theSentinelIsItselfARecord() {
            assertThat(ReportRequestController.EOF_MARKER_TEXT).isEqualTo("/*EOF");
            assertThat(ReportRequestController.JOB_LINE_17)
                    .as("record seventeen is the sentinel :502 recognises, and :507 writes it anyway")
                    .isEqualTo(ReportRequestController.EOF_MARKER_RECORD)
                    .startsWith(ReportRequestController.EOF_MARKER_TEXT)
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            assertThat(ReportRequestController.JOB_DATA_TEMPLATE
                            .get(ReportRequestController.JOB_LINE_COUNT - 1))
                    .isEqualTo(ReportRequestController.JOB_LINE_17);
        }

        @Test
        @DisplayName("JOB-LINES is subscripted from one, and outside its declared items is refused")
        void theOccursTableIsOneBased() {
            List<String> lines = ReportRequestController.JOB_DATA_TEMPLATE;

            assertThat(ReportRequestController.jobLine(lines, 1))
                    .as("OCCURS subscripts are one-based and Java list indices are zero-based; entry 1 "
                            + "is the job card")
                    .isEqualTo(ReportRequestController.JOB_LINE_01);
            assertThat(ReportRequestController.jobLine(lines, ReportRequestController.JOB_LINE_COUNT))
                    .isEqualTo(ReportRequestController.JOB_LINE_17);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("subscript zero is not an entry of a one-based table")
                    .isThrownBy(() -> ReportRequestController.jobLine(lines, 0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("the table says OCCURS %d TIMES but redefines only %d bytes, so entry %d reads "
                            + "storage 02 JOB-DATA-1 does not own",
                            ReportRequestController.JOB_LINES_OCCURS,
                            ReportRequestController.JOB_DATA_LENGTH,
                            ReportRequestController.JOB_LINE_COUNT + 1)
                    .isThrownBy(() -> ReportRequestController.jobLine(lines,
                            ReportRequestController.JOB_LINE_COUNT + 1));
        }

        @Test
        @DisplayName("a mis-sized prefix or date is refused rather than padded into place")
        void malformedRecordPartsAreRefused() {
            assertThatIllegalArgumentException()
                    .as("FILLER-1's prefix is declared PIC X(18); a shorter one would leave the "
                            + "composed record short of eighty")
                    .isThrownBy(() -> ReportRequestController.symnamesLine("X", "2026-01-01",
                            ReportRequestController.SYMNAMES_START_DATE_TAIL_LENGTH));
            assertThatIllegalArgumentException()
                    .as("PARM-START-DATE-1 is declared PIC X(10)")
                    .isThrownBy(() -> ReportRequestController.symnamesLine(
                            ReportRequestController.SYMNAMES_START_DATE_PREFIX, "2026-01-011",
                            ReportRequestController.SYMNAMES_START_DATE_TAIL_LENGTH));
            assertThatIllegalArgumentException()
                    .as("PARM-START-DATE-2 is declared PIC X(10) too")
                    .isThrownBy(() -> ReportRequestController.dateParmRecord("2026-01-011",
                            "2026-12-31"));
        }

        @Test
        @DisplayName("the class-initialisation self-checks reject drift in either file")
        void theSelfChecksActuallyGuard() {
            String widths = (ReportRequestController.JCL_RECORD_LENGTH + " ")
                    .repeat(ReportRequestController.JOB_LINE_COUNT);
            ReportRequestController.requireSkeletonWidths(widths);
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController.requireSkeletonWidths(
                            (ReportRequestController.JCL_RECORD_LENGTH + " ")
                                    .repeat(ReportRequestController.JOB_LINE_COUNT - 1)))
                    .withMessageContaining("02 JOB-DATA-1 declares");

            ReportRequestController.requireDateParmLayout("0:10/10:1/11:10/21:59@80");
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController
                            .requireDateParmLayout("0:10/10:1/11:10/21:58@80"))
                    .withMessageContaining("DateParmReader consumes");

            ReportRequestController.requireMatchingProjections(ReportRequestRequest.FIELD_COUNT,
                    ReportRequestResponse.ScreenField.values().length);
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController.requireMatchingProjections(
                            ReportRequestRequest.FIELD_COUNT,
                            ReportRequestRequest.FIELD_COUNT - 1))
                    .withMessageContaining("has drifted");
        }

        @Test
        @DisplayName("a record the port is handed short by one byte is refused by the port itself")
        void thePortEnforcesTheEightyByteContract() {
            ParityCase parityCase = ParityHarness.usAscii().load(PROGRAM, ParityHarness.caseId(1));

            assertThatExceptionOfType(AssertionError.class)
                    .as("gate G42 has to be a property of the transport and not only of the twenty case "
                            + "files, or a translation that emitted a seventy-nine byte record would be "
                            + "caught by an expectation rather than by the queue that cannot accept it")
                    .isThrownBy(() -> ParityHarness.usAscii().run(parityCase, UNIT_KIND, invocation -> {
                        CapturedJobsQueue queue = new CapturedJobsQueue(invocation);
                        queue.writeQueueTd(ReportRequestController.JOB_LINE_01.substring(0,
                                ReportRequestController.JCL_RECORD_LENGTH - 1));
                        return invocation.recorder().returnCode(0).build();
                    }))
                    .withMessageContaining("RECORDSIZE(80)");
        }
    }

    /**
     * Record fifteen is the {@code DATEPARM} record {@link DateParmReader} consumes - the one place in
     * this migration where an online program's output is a batch program's input.
     */
    @Nested
    @DisplayName("skeleton record fifteen is the DATEPARM record CBTRN03C reads")
    class TheDateParmHandOff {

        @ParameterizedTest(name = "{0} .. {1} survives the hand-off")
        @CsvSource({
            "2026-08-01, 2026-08-31",
            "2026-01-01, 2026-12-31",
            "1582-10-14, 1582-10-14"
        })
        @DisplayName("read back through the reader's twenty-one byte receiver it is the same range")
        void recordFifteenRoundTripsThroughTheReadersOwnLayout(String startDate, String endDate) {
            String record = ReportRequestController.dateParmRecord(startDate, endDate);

            assertThat(record)
                    .as("05 FILLER-3 at app/cbl/CORPT00C.cbl:117-121 is eighty bytes, and DateParmReader "
                            + "declares the same record length")
                    .hasSize(DateParmReader.RECORD_LENGTH)
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);

            DateParm parsed = new DateParm(
                    record.substring(DateParmReader.START_DATE_OFFSET,
                            DateParmReader.START_DATE_OFFSET + DateParmReader.START_DATE_LENGTH),
                    record.substring(DateParmReader.SEPARATOR_OFFSET,
                            DateParmReader.SEPARATOR_OFFSET + DateParmReader.SEPARATOR_LENGTH),
                    record.substring(DateParmReader.END_DATE_OFFSET,
                            DateParmReader.END_DATE_OFFSET + DateParmReader.END_DATE_LENGTH));

            assertThat(parsed.startDate()).isEqualTo(startDate);
            assertThat(parsed.endDate()).isEqualTo(endDate);
            assertThat(parsed.separator())
                    .as("10 FILLER PIC X VALUE SPACE at :119 - it is a byte of the record, so it is "
                            + "asserted rather than assumed")
                    .isEqualTo(" ");
            assertThat(parsed.receiverImage())
                    .as("the twenty-one bytes CBTRN03C's READ ... INTO receiver holds")
                    .isEqualTo(record.substring(0, DateParmReader.RECEIVER_LENGTH));
            assertThat(record.substring(DateParmReader.DISCARDED_TAIL_OFFSET))
                    .as("10 FILLER PIC X(59) VALUE SPACES at :121, the span the reader discards")
                    .hasSize(DateParmReader.DISCARDED_TAIL_LENGTH)
                    .isBlank();
        }

        @Test
        @DisplayName("record fifteen of a real run is that same record")
        void theEmittedRecordIsTheOneTheReaderParses() {
            ParityCase parityCase = ParityHarness.usAscii().load(PROGRAM, ParityHarness.caseId(6));
            ParityHarness.DecodedFingerprint fingerprint =
                    ParityHarness.usAscii().run(parityCase, UNIT_KIND, CORPT00CParityTest::execute);

            List<ParityHarness.DecodedRecord> emitted = fingerprint.findWrites(QUEUE_DATASET)
                    .orElseThrow(() -> new AssertionError("case06 confirms a monthly report, so "
                            + "SUBMIT-JOB-TO-INTRDR must hand seventeen records to the queue"));
            assertThat(emitted).hasSize(ReportRequestController.JOB_LINE_COUNT);

            String recordFifteen = emitted.get(ReportRequestController.DATEPARM_ENTRY - 1)
                    .field(JCL_RECORD_FIELD)
                    .orElseThrow(() -> new AssertionError("the JCL-RECORD span is the whole record"));
            assertThat(recordFifteen)
                    .isEqualTo(ReportRequestController.dateParmRecord("2026-08-01", "2026-08-31"));
        }
    }

    /**
     * The two ordered {@code EVALUATE}s that decide what this program does, and the {@code EIBAID}
     * mapping the first of them branches on (gate G30).
     */
    @Nested
    @DisplayName("the ordered EVALUATEs, and the inline EIBAID tests")
    class TheOrderedDecisions {

        @Test
        @DisplayName("EVALUATE EIBAID names exactly two keys and defaults everything else")
        void theTwoNamedAidsAndTheDefault() {
            assertThat(ReportRequestController.eibAidOf(AidKey.ENTER.token()))
                    .as("WHEN DFHENTER at app/cbl/CORPT00C.cbl:185")
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(ReportRequestController.eibAidOf(AidKey.PFK03.token()))
                    .as("WHEN DFHPF3 at :187, reached through CSSTRPFY's PF3-to-PFK03 fold")
                    .isEqualTo(CicsAid.DFHPF3);
            assertThat(ReportRequestController.eibAidOf(AidKey.PFK05.token()))
                    .as("WHEN OTHER at :190 - PF5 is named by neither arm")
                    .isEqualTo(CicsAid.DFHNULL);
            assertThat(ReportRequestController.eibAidOf(AidKey.CLEAR.token()))
                    .as("CLEAR is not named either, so it defaults too")
                    .isEqualTo(CicsAid.DFHNULL);
            assertThat(ReportRequestController.eibAidOf(null))
                    .as("no key pressed is DFHNULL, which also defaults")
                    .isEqualTo(CicsAid.DFHNULL);

            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHENTER)).isFalse();
        }

        @Test
        @DisplayName("PF15 is folded onto PFK03 by the resolver, which the inline test then acts on")
        void theFoldIsTheClientsAndIsRecordedAsSuch() {
            assertThat(aidTokenOf(CicsAid.mnemonicsByAid().get(CicsAid.DFHPF15)))
                    .as("app/cpy/CSSTRPFY.cpy folds PF13 to PF24 back onto PFK01 to PFK12, so a client "
                            + "resolving through it delivers PF15 as PFK03 - which this program's inline "
                            + "test at :187 then treats as PF3. The fold is the client's choice and is "
                            + "recorded rather than silently absorbed")
                    .isEqualTo(AidKey.PFK03.token());
            assertThat(aidTokenOf(CicsAid.mnemonicsByAid().get(CicsAid.DFHPA3)))
                    .as("CSSTRPFY does not test PA3, so it folds to no token at all")
                    .isNull();
        }

        @Test
        @DisplayName("an AID mnemonic the reproduction does not define is refused")
        void anUnknownMnemonicIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aidTokenOf("DFHPF99"))
                    .withMessageContaining("is not a DFHAID mnemonic");
        }

        @Test
        @DisplayName("an xxxI item the symbolic map does not declare is refused")
        void anUnknownMapItemIsRefused() {
            assertThat(screenFieldOf(ReportRequestRequest.ScreenField.SDTMM.inputItem()))
                    .isEqualTo(ReportRequestRequest.ScreenField.SDTMM);
            assertThatIllegalArgumentException()
                    .as("the xxxL, xxxF and xxxA items are metadata and are not settable from a case")
                    .isThrownBy(() -> screenFieldOf(
                            ReportRequestRequest.ScreenField.SDTMM.lengthItem()))
                    .withMessageContaining("app/cpy-bms/CORPT00.CPY declares");
        }

        @Test
        @DisplayName("the report-type EVALUATE is ordered: monthly, then yearly, then custom")
        void theReportTypeArmsAreTakenInSourceOrder() {
            assertThat(reportOf("Y", "Y", "Y"))
                    .as("all three selected: :213 wins because EVALUATE takes the FIRST matching WHEN")
                    .isEqualTo(ReportRequestController.REPORT_NAME_MONTHLY);
            assertThat(reportOf(" ", "Y", "Y"))
                    .as("monthly blank: :239 wins over :256")
                    .isEqualTo(ReportRequestController.REPORT_NAME_YEARLY);
            assertThat(reportOf(" ", " ", "Y"))
                    .as("only custom selected: :256")
                    .isEqualTo(ReportRequestController.REPORT_NAME_CUSTOM);
            assertThat(reportOf(" ", " ", " "))
                    .as("WHEN OTHER at :437 assigns no report name at all, so WS-REPORT-NAME stays at "
                            + "the spaces :58 gives it")
                    .isBlank();
        }

        /**
         * Runs one {@code PROCESS-ENTER-KEY} and reports which arm claimed it, by reading
         * {@code WS-REPORT-NAME}.
         *
         * <p>The custom arm is driven with a well-formed range so that it reaches {@code :433}, which is
         * where it assigns its name - after the edits rather than before, which is itself part of the
         * behaviour.
         *
         * @param monthly {@code MONTHLYI}
         * @param yearly  {@code YEARLYI}
         * @param custom  {@code CUSTOMI}
         * @return {@code WS-REPORT-NAME}, trimmed of the padding {@code PIC X(10)} adds
         */
        private String reportOf(String monthly, String yearly, String custom) {
            ProgramState state = runEnter(request -> request
                    .withMonthly(monthly).withYearly(yearly).withCustom(custom)
                    .withSdtmm("07").withSdtdd("01").withSdtyyyy("2026")
                    .withEdtmm("07").withEdtdd("31").withEdtyyyy("2026")
                    .withConfirm("Y"));
            return state.reportName().trim();
        }
    }

    /**
     * {@code FUNCTION DATE-OF-INTEGER} and {@code FUNCTION INTEGER-OF-DATE} - {@code :229-230} - and the
     * month end the monthly arm derives from the pair.
     */
    @Nested
    @DisplayName("the two date intrinsics and the month-end derivation")
    class TheDateIntrinsics {

        @Test
        @DisplayName("the anchors in both directions, and the undefined results")
        void theAnchorsAndTheUndefinedResults() {
            assertThat(ReportRequestController.integerOfDate(16010101))
                    .as("FUNCTION INTEGER-OF-DATE counts days from 31 December 1600, so 1601-01-01 is "
                            + "day one")
                    .isEqualTo(1);
            assertThat(ReportRequestController.integerOfDate(16011231))
                    .as("1601 was a common year")
                    .isEqualTo(365);
            assertThat(ReportRequestController.dateOfInteger(1)).isEqualTo(16010101);
            assertThat(ReportRequestController.dateOfInteger(365)).isEqualTo(16011231);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.integerOfDate(20220719)))
                    .as("the pair is an involution over the supported range")
                    .isEqualTo(20220719);

            assertThat(ReportRequestController.integerOfDate(16001231))
                    .as("below the lowest supported argument the intrinsic is undefined, and this "
                            + "implementation is deterministic about it")
                    .isEqualTo(ReportRequestController.DATE_INTRINSIC_UNDEFINED);
            assertThat(ReportRequestController.integerOfDate(20260231))
                    .as("31 February is not a calendar date")
                    .isEqualTo(ReportRequestController.DATE_INTRINSIC_UNDEFINED);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.DATE_OF_INTEGER_LOWEST_ARGUMENT - 1))
                    .isEqualTo(ReportRequestController.DATE_INTRINSIC_UNDEFINED);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.DATE_OF_INTEGER_HIGHEST_ARGUMENT))
                    .isEqualTo(ReportRequestController.INTEGER_OF_DATE_HIGHEST_ARGUMENT);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.DATE_OF_INTEGER_HIGHEST_ARGUMENT + 1))
                    .isEqualTo(ReportRequestController.DATE_INTRINSIC_UNDEFINED);
        }

        @Test
        @DisplayName("WS-CURDATE-N is composed and read back through its own redefinition")
        void theStandardDateFormAndItsThreeParts() {
            int standard = ReportRequestController.standardDate(2026, 8, 9);
            assertThat(standard).isEqualTo(20260809);
            assertThat(ReportRequestController.yearOfStandardDate(standard)).isEqualTo(2026);
            assertThat(ReportRequestController.monthOfStandardDate(standard)).isEqualTo(8);
            assertThat(ReportRequestController.dayOfStandardDate(standard)).isEqualTo(9);
            assertThat(ReportRequestController.MONTHS_PER_YEAR)
                    .as("IF WS-CURDATE-MONTH > 12 at :225")
                    .isEqualTo(12);
        }

        @ParameterizedTest(name = "on {0} the monthly range is {1} .. {2}")
        @CsvSource({
            "2026-01-15T00:00:00, 2026-01-01, 2026-01-31",
            "2026-04-15T00:00:00, 2026-04-01, 2026-04-30",
            "2024-02-15T00:00:00, 2024-02-01, 2024-02-29",
            "2023-02-15T00:00:00, 2023-02-01, 2023-02-28",
            "2026-12-15T00:00:00, 2026-12-01, 2026-12-31"
        })
        @DisplayName("the composite yields the last day of the current month, December included")
        void theMonthEndDerivation(String pinned, String expectedStart, String expectedEnd) {
            ProgramState state = runEnterAt(pinned,
                    request -> request.withMonthly("Y").withConfirm("Y"));

            assertThat(state.parmStartDate2()).isEqualTo(expectedStart);
            assertThat(state.parmEndDate2()).isEqualTo(expectedEnd);
            assertThat(state.parmStartDate1())
                    .as("PARM-START-DATE-1 and PARM-START-DATE-2 are distinct storage at :106 and :118, "
                            + "and :220-221 moves the same value into both")
                    .isEqualTo(state.parmStartDate2());
            assertThat(state.parmEndDate1()).isEqualTo(state.parmEndDate2());
        }
    }

    /**
     * {@code FUNCTION NUMVAL-C} at {@code :305}, {@code :309}, {@code :313}, {@code :317}, {@code :321}
     * and {@code :325} - gate G29, which requires the conversion to accept and reject exactly as COBOL
     * does, verified against malformed arguments as well as valid ones.
     */
    @Nested
    @DisplayName("FUNCTION NUMVAL-C: what it accepts, what it rejects, and what a rejection stores")
    class TheNumvalCIntrinsic {

        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") = {1}")
        @CsvSource(quoteCharacter = '`', value = {
            "`07`, 7",                      // a plain numeric
            "`$ 1,234.56`, 1234.56",        // a currency sign with digit-grouping commas and a point
            "`$1,234,567.89`, 1234567.89",  // more than one grouping comma
            "` 7`, 7",                      // an embedded space, leading
            "`7 `, 7",                      // and trailing
            "`- $12`, -12",                 // a leading sign, a space, a currency sign
            "`+7`, 7",
            "`-7`, -7",
            "`7-`, -7",                     // a trailing sign
            "`7CR`, -7",                    // and the two credit indicators
            "`7DB`, -7",
            "`.5`, 0.5",                    // no integer part
            "`12.`, 12"                     // no fraction after the point
        })
        @DisplayName("accepts every argument form the intrinsic documents")
        void theAcceptedForms(String image, String expected) {
            assertThat(ReportRequestController.numvalC(image))
                    .as("FUNCTION NUMVAL-C returns the value the character representation denotes, as a "
                            + "BigDecimal so every digit survives - never as double or float")
                    .isEqualByComparingTo(expected);
            assertThat(ReportRequestController.testNumvalC(image))
                    .as("and the conformance test agrees that it conformed")
                    .isEqualTo(ReportRequestController.NUMVAL_CONFORMS);
        }

        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") does not conform and yields zero")
        @CsvSource(quoteCharacter = '`', value = {
            "``",                           // an empty string
            "`  `",                         // an all-spaces string
            "`ab`",                         // no digit at all
            "`1.2.3`",                      // a double decimal point
            "`(5)`",                        // a negative in parentheses, which NUMVAL-C does not accept
            "`$`",                          // a currency sign and nothing else
            "`1a`",
            "`-5-`",                        // a sign at both ends
            "`1,,2`",                       // a doubled grouping comma
            "`,5`"                          // a grouping comma with no digit before it
        })
        @DisplayName("rejects every malformed argument, and a rejection is zero")
        void theRejectedForms(String image) {
            String argument = image == null ? "" : image;
            assertThat(ReportRequestController.numvalC(argument))
                    .as("a non-conforming argument yields zero, which is the whole reason the month "
                            + "'ab' becomes '00' at :307 and is then rejected by CSUTLDTC rather than by "
                            + "the month edit")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ReportRequestController.testNumvalC(argument))
                    .as("and the conformance test says so, which is what distinguishes the zero of "
                            + "\"%s\" from the zero of \"00\"", argument)
                    .isNotEqualTo(ReportRequestController.NUMVAL_CONFORMS);
        }

        @Test
        @DisplayName("TEST-NUMVAL-C reports the offending character position, not merely a failure")
        void theConformanceCodesArePositions() {
            assertThat(ReportRequestController.testNumvalC("00"))
                    .as("'00' conforms; it is a valid representation of zero")
                    .isEqualTo(ReportRequestController.NUMVAL_CONFORMS);
            assertThat(ReportRequestController.testNumvalC("1a"))
                    .as("the one-based position of the first character in error")
                    .isEqualTo(2);
            assertThat(ReportRequestController.testNumvalC("  "))
                    .as("an argument holding no digit at all reports its length plus one")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("the store is back through PIC 99 and PIC 9999, low-order digits first")
        void theNormalisationInPlace() {
            ReportRequestController controller = controllerAt("2026-08-09T14:05:06");

            assertThat(controller.computeIntoPic99(" 7")).isEqualTo("07");
            assertThat(controller.computeIntoPic99("7 ")).isEqualTo("07");
            assertThat(controller.computeIntoPic99("13")).isEqualTo("13");
            assertThat(controller.computeIntoPic99("ab"))
                    .as("a rejected argument is zero, and zero in PIC 99 is '00'")
                    .isEqualTo("00");
            assertThat(controller.computeIntoPic99("-5"))
                    .as("WS-NUM-99 is declared PIC 99 - unsigned - so the sign is dropped on the store")
                    .isEqualTo("05");
            assertThat(controller.computeIntoPic99("123"))
                    .as("a value too wide for the receiver keeps its low-order digits, which is what a "
                            + "COBOL store without ON SIZE ERROR does")
                    .isEqualTo("23");
            assertThat(controller.computeIntoPic9999("2026")).isEqualTo("2026");
            assertThat(controller.computeIntoPic9999(" 26")).isEqualTo("0026");
            assertThat(controller.computeIntoPic9999("abcd")).isEqualTo("0000");
        }

        @Test
        @DisplayName("the store truncates the fraction rather than rounding it away")
        void theStoreTruncatesBecauseROUNDEDIsNeverWritten() {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .as("the keyword ROUNDED appears zero times in all twenty-eight programs and zero "
                            + "times in app/cbl/CORPT00C.cbl, so a COBOL store truncates the excess "
                            + "fractional digits - which makes DOWN the only faithful mode and HALF_UP "
                            + "or HALF_EVEN a silent behaviour change (rule R2, gate G24)")
                    .isEqualTo(java.math.RoundingMode.DOWN);

            assertThat(ReportRequestController.numvalC("7.9"))
                    .as("FUNCTION NUMVAL-C keeps the fraction; it is the STORE that discards it")
                    .isEqualByComparingTo("7.9");
            assertThat(CobolDecimal.storeAtPicture(ReportRequestController.numvalC("7.9"),
                            ReportRequestController.WS_NUM_99_DIGITS,
                            ReportRequestController.INTEGER_SCALE))
                    .as("WS-NUM-99 is declared PIC 99 at :74 - two digits and no V - so COMPUTE "
                            + "WS-NUM-99 = FUNCTION NUMVAL-C(...) at :305 stores 7, never 8")
                    .isEqualByComparingTo("7");

            ReportRequestController controller = controllerAt(DEFAULT_INSTANT);
            assertThat(controller.computeIntoPic99("7.9"))
                    .as("and the two-digit image the map item receives is '07', which a rounding store "
                            + "would have made '08'")
                    .isEqualTo("07");
            assertThat(controller.computeIntoPic9999("2026.99"))
                    .as("WS-NUM-9999 is declared PIC 9999 at :75, and the same rule applies to it")
                    .isEqualTo("2026");
        }

        @Test
        @DisplayName("the text edits compare characters, not values")
        void theTextEditsAreAlphanumericComparisons() {
            assertThat(ReportRequestController.isNotValidTwoDigitPart("12",
                    ReportRequestController.HIGHEST_MONTH))
                    .as("SDTMMI > '12' at :330 is false for '12' itself")
                    .isFalse();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("13",
                    ReportRequestController.HIGHEST_MONTH))
                    .isTrue();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("31",
                    ReportRequestController.HIGHEST_DAY))
                    .isFalse();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("32",
                    ReportRequestController.HIGHEST_DAY))
                    .isTrue();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("a1",
                    ReportRequestController.HIGHEST_MONTH))
                    .as("IS NOT NUMERIC is the first of the two conditions at :329")
                    .isTrue();
            assertThat(ReportRequestController.isNumericClass("0000")).isTrue();
            assertThat(ReportRequestController.isNumericClass("20a6")).isFalse();
        }
    }

    /**
     * The {@code CSUTLDTC} acceptance rule at {@code :396-406} and {@code :416-426}: severity
     * {@code '0000'}, or a message number of {@code '2513'}, and nothing else.
     *
     * <p>{@code CSUTLDTCParityTest} owns the nine-token {@code CEEDAYS} table and the eighty-byte message
     * layout; this class relies on it and asserts only the decision this program makes from the two
     * fields it reads.
     */
    @Nested
    @DisplayName("the CSUTLDTC acceptance rule: '0000', or message number '2513', and nothing else")
    class TheCsutldtcAcceptanceRule {

        /** The subprogram as a service, exactly as the controller receives it. */
        private final DateUtilityJob dateUtility = new DateUtilityJob();

        @Test
        @DisplayName("a converted date reports severity '0000' and the arm CONTINUEs")
        void severityZeroIsAccepted() {
            DateValidationResult result = validate("2026-07-01");

            assertThat(result.severityCode())
                    .isEqualTo(ReportRequestController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.returnCode()).isZero();
            assertThat(result.message())
                    .as("the eighty-byte WS-MESSAGE image CSUTLDTC composes; CORPT00C reads two spans "
                            + "of it and puts none of it on the screen")
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH);
        }

        @Test
        @DisplayName("message number '2513' is tolerated even though the severity is not '0000'")
        void theOneToleratedError() {
            DateValidationResult result = validate("1582-10-14");

            assertThat(result.severityCode())
                    .as("14 October 1582 is the day before the Lillian epoch, so it does not convert")
                    .isNotEqualTo(ReportRequestController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.messageNumber())
                    .as("FC-UNSUPP-RANGE - the one non-zero outcome both call sites accept, because the "
                            + "inner IF at :399 and :419 is a NOT = test")
                    .isEqualTo(ReportRequestController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER);
        }

        @ParameterizedTest(name = "{0} is rejected with message number {1}")
        @CsvSource({
            "2026-00-15, 2517",
            "0000-07-01, 2521",
            "2023-02-29, 2508"
        })
        @DisplayName("every other non-zero severity rejects")
        void everyOtherErrorRejects(String date, String messageNumber) {
            DateValidationResult result = validate(date);

            assertThat(result.severityCode())
                    .isNotEqualTo(ReportRequestController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.messageNumber())
                    .isEqualTo(messageNumber)
                    .isNotEqualTo(ReportRequestController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER);
        }

        @Test
        @DisplayName("the rule is applied by the controller at both call sites")
        void bothCallSitesApplyTheSameRule() {
            ProgramState tolerated = runEnter(request -> request.withCustom("Y")
                    .withSdtmm("10").withSdtdd("14").withSdtyyyy("1582")
                    .withEdtmm("10").withEdtdd("14").withEdtyyyy("1582")
                    .withConfirm("Y"));
            assertThat(tolerated.errFlagOff())
                    .as("both dates are the tolerated outcome, so neither :400 nor :420 rejects")
                    .isTrue();
            assertThat(tolerated.submittedRecords())
                    .hasSize(ReportRequestController.JOB_LINE_COUNT);

            ProgramState endRejected = runEnter(request -> request.withCustom("Y")
                    .withSdtmm("07").withSdtdd("01").withSdtyyyy("2026")
                    .withEdtmm("02").withEdtdd("30").withEdtyyyy("2026")
                    .withConfirm("Y"));
            assertThat(endRejected.message())
                    .as("30 February is rejected by the SECOND call site at :412, and :420 carries its "
                            + "own literal rather than the start date's")
                    .startsWith(ReportRequestController.MSG_END_DATE_INVALID);
            assertThat(endRejected.cursorRequestedOn(ReportRequestRequest.ScreenField.EDTMM))
                    .as("MOVE -1 TO EDTMML at :423")
                    .isTrue();
            assertThat(endRejected.submittedRecords()).isEmpty();
        }

        /**
         * One {@code CALL 'CSUTLDTC' USING CSUTLDTC-DATE CSUTLDTC-DATE-FORMAT CSUTLDTC-RESULT}.
         *
         * @param date the ten-byte {@code 'YYYY-MM-DD'} group
         * @return the typed result the controller reads two spans of
         */
        private DateValidationResult validate(String date) {
            return dateUtility.validateDate(date, ReportRequestController.WS_DATE_FORMAT);
        }
    }

    /**
     * The conversation is pseudo-conversational and stays that way: no session, no static mutable state,
     * and a communication area that travels in the payload at its declared width (gates G37, G53, rule
     * R6).
     */
    @Nested
    @DisplayName("statelessness: the conversation travels in the payload and nowhere else")
    class TheStatelessConversation {

        @Test
        @DisplayName("CARDDEMO-COMMAREA is exactly 160 bytes on the wire, in both directions")
        void theCommareaIsOneHundredAndSixtyBytes() {
            FixedWidthCodec codec = ParityHarness.usAscii().codec();

            assertThat(COMMAREA_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy; CORPT00C declares no extension of its own, so the area "
                            + ":549 and :589 pass is the copybook's own width")
                    .isEqualTo(160);
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(COMMAREA_LENGTH);

            ProgramState state = runEnter(request -> request.withMonthly("Y").withConfirm("Y"));
            byte[] outbound = state.response().getNavigationContext().toFixedWidth(codec);
            assertThat(outbound).hasSize(COMMAREA_LENGTH);
            assertThat(codec.deserialise(NavigationContext.LAYOUT, outbound))
                    .as("all sixteen fields are named and comparable, which is what makes statelessness "
                            + "assertable at all")
                    .hasSize(NavigationContext.LAYOUT.spans().size());
        }

        @Test
        @DisplayName("two invocations of one controller share no working storage")
        void oneControllerServesTwoRequestsIndependently() {
            ReportRequestController controller = controllerAt("2026-08-09T14:05:06");

            ProgramState first = controller.mainPara(reenter()
                    .withMonthly("Y").withConfirm("Y"));
            ProgramState second = controller.mainPara(reenter());

            assertThat(first.submittedRecords())
                    .as("the first request confirmed a monthly report")
                    .hasSize(ReportRequestController.JOB_LINE_COUNT);
            assertThat(second.submittedRecords())
                    .as("the second selected no report type, so it must emit nothing - if the two "
                            + "shared working storage the first request's records would still be there")
                    .isEmpty();
            assertThat(second.message())
                    .startsWith(ReportRequestController.MSG_SELECT_REPORT_TYPE);
            assertThat(first.message())
                    .as("and the first request's own message must not have been overwritten by the "
                            + "second")
                    .startsWith("Monthly");
        }

        @Test
        @DisplayName("nothing in this test class or in the controller is static and mutable")
        void noStaticMutableStateAnywhere() {
            requireEveryStaticFieldFinal(CORPT00CParityTest.class);
            requireEveryStaticFieldFinal(CapturedJobsQueue.class);
            requireEveryStaticFieldFinal(ReportRequestController.class);
            requireEveryStaticFieldFinal(ProgramState.class);
        }

        /**
         * Requires every {@code static} field a class declares to be {@code final}.
         *
         * <p>COBOL {@code WORKING-STORAGE} in a CICS program is per-task storage. A {@code static}
         * mutable field is the one translation of it that would let two concurrent operators see each
         * other's screen and would make a test's outcome depend on which test ran first, so it is refused
         * mechanically rather than by convention (practice B9, gate G53).
         *
         * @param type the class to inspect
         */
        private void requireEveryStaticFieldFinal(Class<?> type) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || field.isSynthetic()) {
                    continue;
                }
                assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                        .as("%s.%s is static and not final. WORKING-STORAGE is per-task storage, so a "
                                + "static mutable field would leak one request's screen into another's",
                                type.getSimpleName(), field.getName())
                        .isTrue();
            }
        }
    }

    /**
     * The seventeen payload fields, and the rejection literals the twenty cases do not each get a slot
     * for. Every field traces to a {@code DFHMDF} definition and every literal to its source line.
     */
    @Nested
    @DisplayName("the seventeen-field projection and the fifteen rejection sites")
    class TheScreenContract {

        @Test
        @DisplayName("both projections of the symbolic map declare seventeen fields, in one order")
        void theTwoProjectionsAgree() {
            assertThat(ReportRequestRequest.FIELD_COUNT)
                    .as("app/cpy-bms/CORPT00.CPY declares seventeen xxxI items, and app/bms/CORPT00.bms "
                            + "seventeen DFHMDF fields")
                    .isEqualTo(17);
            assertThat(ReportRequestRequest.ScreenField.values())
                    .hasSameSizeAs(ReportRequestResponse.ScreenField.values());

            for (int field = 0; field < ReportRequestRequest.FIELD_COUNT; field++) {
                ReportRequestRequest.ScreenField in = ReportRequestRequest.ScreenField.values()[field];
                ReportRequestResponse.ScreenField out =
                        ReportRequestResponse.ScreenField.values()[field];
                assertThat(out.baseName())
                        .as("01 CORPT0AO REDEFINES CORPT0AI, so the two views describe the same field in "
                                + "the same position")
                        .isEqualTo(in.bmsName());
                assertThat(out.payloadLength())
                        .as("%s and %s are the same storage, so they are the same width",
                                in.inputItem(), out.payloadItemName())
                        .isEqualTo(in.declaredLength());
                assertThat(in.lengthItem())
                        .as("the cursor is the length item, and its name is the field's plus '%s'",
                                LENGTH_ITEM_SUFFIX)
                        .isEqualTo(in.bmsName() + LENGTH_ITEM_SUFFIX);
            }
        }

        @Test
        @DisplayName("a send carries all seventeen xxxO items and the one attribute item :448 writes")
        void aSendIsSeventeenFieldsAndOneColour() {
            ProgramState state = runEnter(request -> request.withMonthly("Y").withConfirm("Y"));
            ObservedResponse observed =
                    observedResponseOf(ParityHarness.usAscii().codec(), state);

            assertThat(observed.sends())
                    .as("SEND-TRNRPT-SCREEN ends GO TO RETURN-TO-CICS at :580, so no path sends twice")
                    .hasSize(1);
            assertThat(observed.sends().get(0).fields())
                    .hasSize(ReportRequestRequest.FIELD_COUNT)
                    .containsKey(ReportRequestResponse.ScreenField.ERRMSG.payloadItemName());
            assertThat(observed.sends().get(0).attributes())
                    .as("MOVE DFHGREEN TO ERRMSGC at :448 is the program's only attribute assignment; "
                            + "CORPT00C copies neither CSSETATY nor CSSTRPFY, so no other item is set")
                    .containsExactly(Map.entry(
                            ReportRequestResponse.ScreenField.ERRMSG.colourItemName(),
                            BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN)));
            assertThat(observed.termination()).isEqualTo(Termination.RETURN_TRANSID);
            assertThat(observed.nextProgram())
                    .as("EXEC CICS RETURN TRANSID('CR00') routes the next input back to this program, "
                            + "which app/csd/CARDDEMO.CSD:409-410 binds to CR00")
                    .isEqualTo(ReportRequestController.PROGRAM_NAME);
            assertThat(observed.nextMapset()).isEqualTo(ReportRequestRequest.MAPSET_NAME);
            assertThat(observed.nextMap()).isEqualTo(ReportRequestRequest.MAP_NAME);
        }

        @Test
        @DisplayName("ERRMSGO is two bytes narrower than WS-MESSAGE, and the move loses them")
        void theEightyByteMessageIsTruncatedToSeventyEight() {
            ProgramState state = runEnter(request -> request);

            assertThat(state.message())
                    .as("WS-MESSAGE PIC X(80) at :39")
                    .hasSize(ReportRequestController.WS_MESSAGE_LENGTH)
                    .startsWith(ReportRequestController.MSG_SELECT_REPORT_TYPE);
            assertThat(state.response().getErrmsgo())
                    .as("MOVE WS-MESSAGE TO ERRMSGO at :560 moves eighty characters into ERRMSGO "
                            + "PIC X(78), truncating on the right")
                    .hasSize(ReportRequestRequest.ERRMSG_LENGTH)
                    .isEqualTo(state.message().substring(0, ReportRequestRequest.ERRMSG_LENGTH));
        }

        @ParameterizedTest(name = "{0} rejects with the literal at :{1} and the cursor on {2}")
        @CsvSource(delimiter = '|', value = {
            "SDTDD | 268 | SDTDDL",
            "SDTYYYY | 275 | SDTYYYYL",
            "EDTMM | 282 | EDTMML",
            "EDTDD | 289 | EDTDDL"
        })
        @DisplayName("the four middle arms of the blank chain, each with its own literal and cursor")
        void theRemainingBlankArms(String blanked, int sourceLine, String cursorItem) {
            ReportRequestRequest.ScreenField field =
                    ReportRequestRequest.ScreenField.valueOf(blanked);
            ProgramState state = runEnter(request -> customRange(request)
                    .withValue(field, " ".repeat(field.declaredLength())));

            assertThat(state.message())
                    .as("app/cbl/CORPT00C.cbl:%d", sourceLine)
                    .startsWith(blankLiteralOf(blanked));
            assertThat(cursorLengthItemOf(state)).isEqualTo(cursorItem);
            assertThat(state.submittedRecords())
                    .as("a rejected arm sends and the send ends the task, so nothing is emitted")
                    .isEmpty();
        }

        @Test
        @DisplayName("the end-date text edits carry their own literals, distinct from the start date's")
        void theEndDateTextEdits() {
            ProgramState badMonth = runEnter(request -> customRange(request).withEdtmm("13"));
            assertThat(badMonth.message()).startsWith(ReportRequestController.MSG_END_DATE_INVALID_MONTH);
            assertThat(cursorLengthItemOf(badMonth)).isEqualTo("EDTMML");

            ProgramState badDay = runEnter(request -> customRange(request).withEdtdd("32"));
            assertThat(badDay.message()).startsWith(ReportRequestController.MSG_END_DATE_INVALID_DAY);
            assertThat(cursorLengthItemOf(badDay)).isEqualTo("EDTDDL");

            ProgramState badStartDay = runEnter(request -> customRange(request).withSdtdd("32"));
            assertThat(badStartDay.message())
                    .startsWith(ReportRequestController.MSG_START_DATE_INVALID_DAY);
            assertThat(cursorLengthItemOf(badStartDay)).isEqualTo("SDTDDL");
        }

        @Test
        @DisplayName("an unrecognised confirm value is quoted back in the message")
        void theInvalidConfirmArm() {
            ProgramState state = runEnter(request -> request.withMonthly("Y").withConfirm("X"));

            assertThat(state.message())
                    .as("STRING '\"' DELIMITED BY SIZE, CONFIRMI DELIMITED BY SPACE and the rest at "
                            + ":485-490")
                    .isEqualTo(ReportRequestController.stringInto(" ".repeat(80),
                            "\"X" + ReportRequestController.MSG_NOT_A_VALID_CONFIRM_VALUE));
            assertThat(cursorLengthItemOf(state)).isEqualTo("CONFIRML");
            assertThat(state.submittedRecords()).isEmpty();

            ProgramState declined = runEnter(request -> request.withMonthly("Y").withConfirm("n"));
            assertThat(declined.message())
                    .as("the 'n' arm at :480 clears WS-MESSAGE through INITIALIZE-ALL-FIELDS and sends "
                            + "an empty form with no message at all")
                    .isBlank();
            assertThat(declined.submittedRecords()).isEmpty();
        }

        /**
         * The literal one arm of the blank chain moves into {@code WS-MESSAGE}, keyed by the field the arm
         * tests.
         *
         * @param blanked the field the case blanked
         * @return the literal that arm carries
         */
        private String blankLiteralOf(String blanked) {
            return switch (blanked) {
                case "SDTMM" -> ReportRequestController.MSG_START_DATE_MONTH_EMPTY;
                case "SDTDD" -> ReportRequestController.MSG_START_DATE_DAY_EMPTY;
                case "SDTYYYY" -> ReportRequestController.MSG_START_DATE_YEAR_EMPTY;
                case "EDTMM" -> ReportRequestController.MSG_END_DATE_MONTH_EMPTY;
                case "EDTDD" -> ReportRequestController.MSG_END_DATE_DAY_EMPTY;
                case "EDTYYYY" -> ReportRequestController.MSG_END_DATE_YEAR_EMPTY;
                default -> throw new IllegalArgumentException(blanked + " is not one of the six date "
                        + "parts the blank chain at app/cbl/CORPT00C.cbl:258-303 tests");
            };
        }
    }

    // =================================================================================================
    // Shared builders for the nested classes. A parity case reaches the unit through the harness; these
    // reach it the same way, minus the case file, so a structural assertion can drive a path directly.
    // =================================================================================================

    /**
     * The default pinned instant the nested classes run at: 9 August 2026, 14:05:06.
     *
     * <p>Fixed rather than current, so {@code FUNCTION CURRENT-DATE} is reproducible (practice B7). The
     * date is deliberately mid-month and mid-year, so a month-end or year-end derivation that was wrong
     * would differ from it rather than coincide with it.
     */
    private static final String DEFAULT_INSTANT = "2026-08-09T14:05:06";

    /**
     * {@link ReportRequestController} wired as a plain object at a pinned instant.
     *
     * @param instant the ISO-8601 local date-time {@code FUNCTION CURRENT-DATE} must report
     * @return the controller, with a real {@link DateUtilityJob} and a queue that accepts every record
     */
    private static ReportRequestController controllerAt(String instant) {
        ParityHarness harness = ParityHarness.usAscii();
        return new ReportRequestController(new DateUtilityJob(),
                new AcceptingJobsQueue(harness.codec()),
                ParityHarness.fixedClockAt(java.time.LocalDateTime.parse(instant)),
                harness.charset());
    }

    /**
     * A re-entered request: the state every invocation after the first arrives in, with the
     * {@code ENTER} key pressed.
     *
     * @return the request, with {@code CDEMO-PGM-CONTEXT} set to re-enter
     */
    private static ReportRequestRequest reenter() {
        ReportRequestRequest initial = ReportRequestRequest.empty();
        return initial
                .withNavigationContext(initial.navigationContext().withPgmReenter())
                .withAid(AidKey.ENTER.token());
    }

    /**
     * A well-formed custom range, for the arms that reject one field of it.
     *
     * @param request the request to fill
     * @return the request with {@code CUSTOMI}, all six date parts and {@code CONFIRMI} set
     */
    private static ReportRequestRequest customRange(ReportRequestRequest request) {
        return request.withCustom("Y")
                .withSdtmm("07").withSdtdd("01").withSdtyyyy("2026")
                .withEdtmm("07").withEdtdd("31").withEdtyyyy("2026")
                .withConfirm("Y");
    }

    /**
     * Runs one {@code ENTER} task at {@link #DEFAULT_INSTANT}.
     *
     * @param screen how to fill the received map
     * @return the working storage as it stood when the task ended
     */
    private static ProgramState runEnter(
            java.util.function.UnaryOperator<ReportRequestRequest> screen) {
        return runEnterAt(DEFAULT_INSTANT, screen);
    }

    /**
     * Runs one {@code ENTER} task at a pinned instant.
     *
     * @param instant the instant {@code FUNCTION CURRENT-DATE} must report
     * @param screen  how to fill the received map
     * @return the working storage as it stood when the task ended
     */
    private static ProgramState runEnterAt(String instant,
            java.util.function.UnaryOperator<ReportRequestRequest> screen) {
        return controllerAt(instant).mainPara(screen.apply(reenter()));
    }

    /**
     * The queue the structural assertions run through: it enforces the eighty-byte contract and accepts
     * every record, which is what {@code DFHRESP(NORMAL)} means.
     *
     * <p>Separate from {@link CapturedJobsQueue} because that one takes its code page and its forced
     * outcome from a {@link ParityHarness.Invocation}, and a structural assertion has no case behind it.
     * Neither holds mutable static state.
     */
    private static final class AcceptingJobsQueue implements JobSubmissionPort {

        /** The code page the record's byte width is measured in - named, never defaulted. */
        private final FixedWidthCodec codec;

        /**
         * @param codec the codec whose charset the records are encoded through; never {@code null}
         */
        AcceptingJobsQueue(FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A codec is required: the queue's RECORDSIZE is a "
                    + "byte count, so the code page has to be named");
        }

        @Override
        public WriteQueueOutcome writeQueueTd(String jclRecord) {
            assertThat(codec.encodeImage(Objects.requireNonNull(jclRecord, "A record is required"),
                            "a " + QUEUE_DATASET + " queue record"))
                    .as("TDQUEUE(JOBS) declares RECORDSIZE(80) RECORDFORMAT(FIXED)")
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            return WriteQueueOutcome.NORMAL;
        }
    }

    /**
     * A guard against a locale-sensitive comparison creeping into this class.
     *
     * <p>Every value here is a fixed-width COBOL image and every comparison is on bytes, so no test in
     * this file may depend on the default locale. The constant is referenced by
     * {@link #localeIsNotConsulted()} so it cannot be dropped as unused, and the assertion is that this
     * class never asks the locale anything - which is a property a reader can check by grepping.
     */
    private static final Locale COMPARISON_LOCALE = Locale.ROOT;

    /**
     * States, rather than merely intends, that no comparison in this file is locale-sensitive.
     *
     * <p>{@code MSG_START_DATE_INVALID_MONTH} carries a capital {@code M} where its sibling at
     * {@code :340} carries a lower-case {@code d}; the difference is the source's and is preserved, and a
     * case-insensitive or locale-folded comparison anywhere here would erase it.
     */
    @Test
    @DisplayName("the source's own inconsistent capitalisation is preserved, not folded away")
    void localeIsNotConsulted() {
        assertThat(ReportRequestController.MSG_START_DATE_INVALID_MONTH)
                .as("app/cbl/CORPT00C.cbl:331 - capital M")
                .isEqualTo("Start Date - Not a valid Month...")
                .isNotEqualTo(ReportRequestController.MSG_START_DATE_INVALID_MONTH
                        .toLowerCase(COMPARISON_LOCALE));
        assertThat(ReportRequestController.MSG_START_DATE_INVALID)
                .as("app/cbl/CORPT00C.cbl:400 - lower-case d, from a different edit; the inconsistency "
                        + "is the source's and is not corrected")
                .isEqualTo("Start Date - Not a valid date...");
    }
}

