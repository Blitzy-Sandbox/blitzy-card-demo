package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
import com.vsergeychik.carddemo.customer.CustomerRepository.ReadResult;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The whole of {@code app/cbl/CBCUS01C.cbl} - {@code Type: BATCH COBOL Program},
 * {@code Function : Read and print customer data file.} - as one Java service.
 *
 * <p>178 lines of COBOL, invoked as {@code STEP05 EXEC PGM=CBCUS01C} by
 * {@code app/jcl/READCUST.jcl:L6}, which binds {@code CUSTFILE} to the customer master KSDS at
 * {@code L9-L10} and routes {@code SYSOUT} and {@code SYSPRINT} to the spool at {@code L11-L12}.
 * The job declares <strong>no {@code PARM}</strong>, so this service takes no parameters either.
 *
 * <h2>Why a service, and why there is also a job</h2>
 * The build prompt mandates the names {@code CustomerRepository} and {@code CustomerService} for
 * {@code CBCUS01C}. Rule <strong>R1</strong> of the plan - <em>names from the prompt, behaviour from
 * the source</em> - governs the mismatch that follows: the prompt's repository/service pair says
 * nothing about the fact that {@code CBCUS01C} is a <em>runnable batch program</em> with its own JCL
 * job, so a third class, {@code CustomerFileReaderJob}, carries the Spring Batch wiring that
 * {@code READCUST.jcl} implies. That job is deliberately thin. It owns the {@code Job}, the
 * {@code Step} and the mapping from an {@link AbendException} to a non-zero {@code ExitStatus}; it
 * owns <strong>no decision</strong>. Every branch, every file-status test, every {@code DISPLAY} and
 * the abend path live here.
 *
 * <p>That split is not a matter of taste. The migration's acceptance gate is a JaCoCo {@code BRANCH}
 * ratio of {@code 0.90} enforced <em>per package</em>, and a branch reachable only by launching a
 * Spring Batch job is a branch the gate cannot see. Gate <strong>G51</strong> states the requirement
 * directly: business logic sits in services so the parity cases reach it with no batch launcher and no
 * HTTP in the path. A test constructs this class with a repository and nothing else, and this file
 * references no batch type at all.
 *
 * <h2>Three entry points, one implementation</h2>
 * {@link #readAndPrintCustomerFileTo(SysoutSink)} is the <strong>production</strong> shape: it streams
 * every {@code DISPLAY} to a destination - {@link #standardOutputSysoutSink()} is the
 * {@code //SYSOUT DD SYSOUT=*} equivalent - and keeps none of it. {@link #readAndPrintCustomerFile()}
 * and {@link #readAndPrintCustomerFile(Sysout)} accumulate the whole sequence into an {@link Execution}
 * instead, which is what a parity case and a unit test are made of and what a batch run must not do:
 * every customer contributes {@link #DISPLAYS_PER_RECORD} lines of {@link #RECORD_LENGTH} characters and
 * the customer master has no record ceiling, so retaining the sequence costs memory proportional to the
 * dataset for output that has already been written.
 *
 * <p>All three run one private {@code runProgram}, so the emitted line sequence, its order and its bytes
 * are decided in one place and streaming cannot drift away from capturing. The only difference between
 * them is whether a copy is kept.
 *
 * <h2>The program, paragraph by paragraph</h2>
 * <pre>
 * PROCEDURE DIVISION.                                                              L70-L87
 *     DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.                            L71
 *     PERFORM 0000-CUSTFILE-OPEN.                                                  L72
 *     PERFORM UNTIL END-OF-FILE = 'Y'                                              L74
 *         IF  END-OF-FILE = 'N'                                                    L75
 *             PERFORM 1000-CUSTFILE-GET-NEXT                                       L76
 *             IF  END-OF-FILE = 'N'                                                L77
 *                 DISPLAY CUSTOMER-RECORD                                          L78
 *     PERFORM 9000-CUSTFILE-CLOSE.                                                 L83
 *     DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.                              L85
 *     GOBACK.                                                                      L87
 * </pre>
 *
 * <p>Rule <strong>R7</strong> asks for structured control flow, and here it asks for almost nothing:
 * {@code CBCUS01C} contains <strong>no {@code GO TO}</strong>, <strong>no
 * {@code PERFORM … THRU}</strong> and <strong>no {@code EVALUATE}</strong>. It is already structured,
 * so the translation is a paragraph-for-method transcription with the nesting and the evaluation order
 * left exactly as written. Nothing is flattened: a nested {@code IF} that reads oddly is still the
 * order in which the source enters its branches.
 *
 * <h2>Three preserved defects. Do not repair any of them.</h2>
 * Practice <strong>B5</strong> - preserve behaviour including defects and dead code - is the binding
 * constraint on this class, and it is the one most likely to be violated by good intentions. Three
 * concrete rulings:
 *
 * <ol>
 *   <li><strong>Every record is displayed twice, in the same raw form.</strong> {@code L96} displays
 *       {@code CUSTOMER-RECORD} inside {@code 1000-CUSTFILE-GET-NEXT} on a {@code '00'} status, and
 *       {@code L78} displays it again in the mainline as soon as the {@code PERFORM} returns with the
 *       flag still {@code 'N'}. Both are a {@code DISPLAY} of the <em>group item</em>, so both emit
 *       the raw 500-byte image and the two lines are byte-identical and adjacent. Fifty fixture
 *       records therefore produce <strong>one hundred</strong> record lines, and the complete output
 *       is <strong>102</strong> lines, not 52. This is where {@code CBCUS01C} parts company with its
 *       four sibling readers: {@code CBACT01C:96} performs {@code 1100-DISPLAY-ACCT-RECORD}, which
 *       emits eleven labelled {@code 'ACCT-ID                 :'}-style lines and a separator, and
 *       only its mainline emits the raw group. <strong>{@code CBCUS01C} has no labelled-field display
 *       paragraph at all.</strong> Do not invent one, do not borrow the account reader's, and do not
 *       collapse the pair.</li>
 *   <li><strong>Two dead result seeds are kept.</strong> {@code MOVE 8 TO APPL-RESULT} at {@code L119}
 *       and {@code ADD 8 TO ZERO GIVING APPL-RESULT} at {@code L137} are both overwritten on every
 *       path that follows them, so neither is observable. They are transcribed anyway, as explicit
 *       assignments carrying a comment that says so - the same ruling the plan applies to
 *       {@code CBACT04C}'s {@code 1400-COMPUTE-FEES} stub. Deleting them, or folding them into the
 *       branch that overwrites them, would be an unrequested change to a program this migration is
 *       not allowed to improve.</li>
 *   <li><strong>The unreachable status arms keep their shape.</strong> The read ladder's final
 *       {@code ELSE MOVE 12} at {@code L101} is the COBOL {@code WHEN OTHER}. A sequential browse of
 *       an input file cannot report {@code '22'} (duplicate) or {@code '23'} (not found), yet both
 *       must route through that one arm and both must remain <em>testable</em>, because gate
 *       <strong>G47</strong> requires every file status to be exercised per repository call site. No
 *       special-case handling is added that the COBOL does not have, and the arm is not collapsed into
 *       the end-of-file test.</li>
 * </ol>
 *
 * <h2>{@code DISPLAY} is returned as data, not only logged</h2>
 * A {@code DISPLAY} in a JCL batch step writes a line to {@code SYSOUT}, and for this program the
 * ordered line sequence <em>is</em> the entire observable output - there is no output dataset. So
 * {@link #readAndPrintCustomerFile()} returns that sequence as an ordered, immutable
 * {@link Execution#sysout() list}. The parity harness captures the return code and the emitted
 * messages as its behavioural fingerprint and the differ compares them field by field, which is only
 * possible if the lines are values rather than console side effects. It also means the whole program
 * is assertable without capturing standard output.
 *
 * <p>The lines are additionally written through the module's logger, which is the Spring JCL facade
 * over SLF4J, with <strong>one deliberate exception</strong>. Every control line - the two banners,
 * the three error texts, the rendered file status and {@code ABENDING PROGRAM} - is logged verbatim.
 * The 500-byte record images are <strong>not</strong>: a {@code CUSTOMER-RECORD} carries
 * {@code CUST-SSN}, {@code CUST-DOB-YYYY-MM-DD}, {@code CUST-GOVT-ISSUED-ID} and the customer's
 * names, and copying those into the application log would disclose cardholder data to a file that is
 * read by more people, kept for longer and guarded less than the dataset it came from (CWE-532). The
 * per-record log entry therefore carries only the browse ordinal and the byte count.
 *
 * <p>This costs no parity. The application log is not a COBOL-observable artefact: {@code SYSOUT} is,
 * and {@code SYSOUT} is reproduced byte for byte in the returned list, which is what
 * {@code CustomerFileReaderJob} writes to the real spool and what every parity case is judged
 * against. Masking a log that the COBOL does not have cannot change what the COBOL produced.
 *
 * <h2>Numbers, and why no decimal type appears</h2>
 * {@code app/cpy/CVCUS01Y.cpy} declares no signed picture and no {@code V}-scaled picture anywhere in
 * its 500 bytes. Its only numeric items are {@code CUST-ID PIC 9(09)}, {@code CUST-SSN PIC 9(09)} and
 * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}, all scale-free. This package is consequently the one place
 * in the migration where the correct answer to "where is the fixed-point decimal arithmetic?" is
 * <em>there is none</em>: gate <strong>G22</strong> is satisfied here by there being no fractional
 * quantity to represent at all, so the module's fixed-point decimal seam is deliberately not imported -
 * it has no subject in this package - and no binary floating-point primitive appears either, as nowhere
 * else in this module.
 *
 * <p>{@code APPL-RESULT PIC S9(9) COMP} is an {@code int}. {@code ABCODE} and {@code TIMING}, both
 * {@code PIC S9(9) BINARY}, are {@code int} values carried by {@link AbendException}.
 * {@code END-OF-FILE PIC X(01)} keeps its character semantics as a one-character {@code String}
 * holding {@code 'Y'} or {@code 'N'} - not a {@code boolean}, because the mainline tests {@code = 'N'}
 * while the loop tests {@code = 'Y'} and those are not each other's negation.
 *
 * <h2>State</h2>
 * A {@code @Service} is a singleton, so nothing this program mutates may live on it. Every
 * {@code WORKING-STORAGE} item - {@code END-OF-FILE}, {@code APPL-RESULT}, {@code IO-STATUS} - belongs
 * to one execution and is created fresh per call in a {@link WorkingStorage} carrier. There is no
 * static mutable state, no field injection and no setter (practice <strong>B9</strong>, gate
 * <strong>G53</strong>): the only field is the injected repository, and it is {@code final}. That is
 * what lets twenty parity cases run in any order, and in parallel, and still agree.
 *
 * @see CustomerRepository the customer master's only reader, and the source of the browse
 * @see CustomerRecord the 500-byte {@code CVCUS01Y} record and its raw group image
 * @see FileStatus the shared {@code Z-DISPLAY-IO-STATUS} renderer
 * @see AbendException the {@code CALL 'CEE3ABD'} equivalent
 */
@Service
public class CustomerService {

    /**
     * Logger for the line sequence this service emits.
     *
     * <p>{@code static final} and a reference to an immutable logger, so it introduces no shared
     * mutable state. Spring JCL routes it to SLF4J, and thence to whatever the deployment configures.
     */
    private static final Log LOG = LogFactory.getLog(CustomerService.class);

    // =================================================================================================
    // Program identity. Transcribed, never derived.
    // =================================================================================================

    /**
     * The COBOL {@code PROGRAM-ID}: {@value}.
     *
     * <p>{@code app/cbl/CBCUS01C.cbl:L23}. Carried into every {@link AbendException} this service
     * raises, so a non-zero exit names the program a mainframe operator would recognise.
     */
    public static final String PROGRAM_ID = "CBCUS01C";

    /**
     * The JCL step that invokes it: {@value}.
     *
     * <p>{@code app/jcl/READCUST.jcl:L6}, {@code //STEP05 EXEC PGM=CBCUS01C}. The job's only step.
     */
    public static final String STEP_NAME = "STEP05";

    /**
     * The DD name of the input dataset: {@code CUSTFILE}.
     *
     * <p>{@code ASSIGN TO CUSTFILE} at {@code app/cbl/CBCUS01C.cbl:L29} and the DD
     * {@code app/jcl/READCUST.jcl:L9-L10} binds. Taken from {@link CustomerRepository} rather than
     * restated, so the module has one name for this dataset - and it is a key, never a dataset name.
     */
    public static final String DD_NAME = CustomerRepository.BATCH_DD_NAME;

    // =================================================================================================
    // The six DISPLAY literals. Byte-exact observable output - every one of them.
    //
    // Transcribed as named constants rather than inlined, and never assembled from a format string:
    // a format string can drift, and a drifted banner is a parity failure that reads like a typo.
    // The asymmetry in the third and fourth texts is the source's own and is preserved (practice B8).
    // =================================================================================================

    /**
     * {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'} - {@code app/cbl/CBCUS01C.cbl:L71}.
     *
     * <p>The first line of the program's output, always, on every path including a failed open.
     */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBCUS01C";

    /**
     * {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'} - {@code app/cbl/CBCUS01C.cbl:L85}.
     *
     * <p>The last line of a <em>normal</em> end. An abend never reaches {@code L85}, so an execution
     * that abends has no closing banner - which is exactly how an operator tells the two apart.
     */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBCUS01C";

    /**
     * {@code DISPLAY 'ERROR OPENING CUSTFILE'} - {@code app/cbl/CBCUS01C.cbl:L129}.
     *
     * <p><strong>This one names the DD name, and the other two name the file.</strong> The open text
     * says {@code CUSTFILE} - the {@code ASSIGN TO} name - while the read text at {@code L110} and the
     * close text at {@code L147} both say {@code CUSTOMER FILE}. The inconsistency is in the source and
     * is reproduced exactly; "harmonising" the three would change three lines of observable output.
     */
    public static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTFILE";

    /**
     * {@code DISPLAY 'ERROR READING CUSTOMER FILE'} - {@code app/cbl/CBCUS01C.cbl:L110}.
     *
     * <p>Emitted only from the read ladder's {@code WHEN OTHER} arm. End of file is not an error and
     * never reaches this text.
     */
    public static final String ERROR_READING_CUSTOMER_FILE = "ERROR READING CUSTOMER FILE";

    /**
     * {@code DISPLAY 'ERROR CLOSING CUSTOMER FILE'} - {@code app/cbl/CBCUS01C.cbl:L147}.
     */
    public static final String ERROR_CLOSING_CUSTOMER_FILE = "ERROR CLOSING CUSTOMER FILE";

    /**
     * {@code DISPLAY 'ABENDING PROGRAM'} - {@code app/cbl/CBCUS01C.cbl:L155}, the first statement of
     * {@code Z-ABEND-PROGRAM}.
     *
     * <p>Taken from {@link AbendException#ABEND_DISPLAY_TEXT} rather than restated, because nine
     * programs display this same text immediately before {@code CALL 'CEE3ABD'} and the module holds
     * one copy of it.
     */
    public static final String ABENDING_PROGRAM = AbendException.ABEND_DISPLAY_TEXT;

    /**
     * The three error texts in the order their paragraphs appear in the source, for assertion and for
     * review.
     *
     * <p>Immutable, so publishing it introduces no mutable static state.
     */
    public static final List<String> ERROR_TEXTS = List.of(
            ERROR_READING_CUSTOMER_FILE,
            ERROR_OPENING_CUSTFILE,
            ERROR_CLOSING_CUSTOMER_FILE);

    // =================================================================================================
    // WORKING-STORAGE constants - app/cbl/CBCUS01C.cbl:L61-L67.
    // =================================================================================================

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBCUS01C.cbl:L62}.
     *
     * <p>One of the program's only two {@code 88}-level condition names. Taken from
     * {@link FileStatus#APPL_AOK}: five batch programs declare the identical pair and the module holds
     * one copy of each value.
     */
    public static final int APPL_AOK = FileStatus.APPL_AOK;

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBCUS01C.cbl:L63}.
     *
     * <p>The other of the two. Note that it is a value of {@code APPL-RESULT} and not a file status:
     * the file status for end of file is {@code '10'}, and {@code L99} maps it to this {@code 16}.
     */
    public static final int APPL_EOF = FileStatus.APPL_EOF;

    /**
     * The value the two dead seeds move: {@value}.
     *
     * <p>{@code MOVE 8 TO APPL-RESULT} at {@code L119} and {@code ADD 8 TO ZERO GIVING APPL-RESULT} at
     * {@code L137}. Named so the transcription can state the number the source states while the comment
     * beside it records that nothing ever observes it. Taken from
     * {@link AbendException#RETURN_CODE_ASSUMED_FAILURE}, which is the conventional meaning of an
     * {@code 8} pre-seeded before an operation whose success has yet to be proven.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value every fatal arm moves: {@value}.
     *
     * <p>{@code MOVE 12 TO APPL-RESULT} at {@code L101} and {@code L124}, and
     * {@code ADD 12 TO ZERO GIVING APPL-RESULT} at {@code L142}. All three abend sites are reached with
     * {@code APPL-RESULT} holding this value, which is therefore the return code every
     * {@link AbendException} from this service carries. Taken from
     * {@link CustomerRepository#APPL_RESULT_FATAL}.
     */
    public static final int APPL_RESULT_FATAL = CustomerRepository.APPL_RESULT_FATAL;

    /**
     * The process return code of a normal end: {@value}.
     *
     * <p>{@code GOBACK} at {@code L87} leaves {@code RETURN-CODE} untouched, and the program never
     * moves anything into it, so a run that reaches {@code L87} ends with zero. Every non-zero outcome
     * leaves by throwing instead, which is why {@link Execution#returnCode()} is always this value.
     */
    public static final int RETURN_CODE_NORMAL_END = AbendException.RETURN_CODE_OK;

    /**
     * {@code END-OF-FILE PIC X(01) VALUE 'N'} - the declared initial value,
     * {@code app/cbl/CBCUS01C.cbl:L65}.
     *
     * <p>A one-character {@code String} and not a {@code boolean}, because the flag is tested two ways
     * that are not each other's negation: the loop at {@code L74} tests {@code = 'Y'} and the two
     * guards at {@code L75} and {@code L77} test {@code = 'N'}.
     */
    public static final String END_OF_FILE_NO = "N";

    /**
     * {@code MOVE 'Y' TO END-OF-FILE} - the value the end-of-file arm moves,
     * {@code app/cbl/CBCUS01C.cbl:L108}. The only value ever moved into the flag at run time.
     */
    public static final String END_OF_FILE_YES = "Y";

    // =================================================================================================
    // Derived geometry, so the 102-line fact is stated once and can be asserted.
    // =================================================================================================

    /**
     * How many lines one record produces: {@value}.
     *
     * <p>The preserved duplicate display of ruling (1) on this class - {@code L96} then {@code L78} -
     * and the single highest-value parity fact about this program. It is <strong>two</strong>, not one.
     */
    public static final int DISPLAYS_PER_RECORD = 2;

    /**
     * How many lines are emitted regardless of the record count: {@value}.
     *
     * <p>The opening banner at {@code L71} and the closing one at {@code L85}, on a normal end.
     */
    public static final int BANNER_LINE_COUNT = 2;

    /**
     * The declared width of every record line: {@value} bytes.
     *
     * <p>{@code app/cpy/CVCUS01Y.cpy} sums to exactly this, {@code FILLER X(168)} at bytes 333 to 500
     * included. Taken from {@link CustomerRecord#RECORD_LENGTH}. Gate <strong>G21</strong> is checked by
     * this width: a codec that dropped the trailing {@code FILLER} would produce 332-character lines,
     * and the total width is what fails first.
     */
    public static final int RECORD_LENGTH = CustomerRecord.RECORD_LENGTH;

    // =================================================================================================
    // Collaborators.
    // =================================================================================================

    /**
     * The customer master's only reader.
     *
     * <p>{@code final} and constructor-injected. This service uses two of its five access paths - the
     * {@code openInput}/{@code closeFile} lifecycle and the sequential browse - because
     * {@code CBCUS01C} performs nothing else: it issues one {@code OPEN INPUT}, reads forward to end of
     * file, and closes. No keyed read, no read-for-update and no rewrite is called from here.
     */
    private final CustomerRepository customerRepository;

    /**
     * The codec that renders a decoded record back to its stored 500-byte image.
     *
     * <p>Built once, from the repository's own configured code page, and reused for every record.
     * Deeply immutable, so sharing it across executions introduces no mutable state - and building one
     * per record would allocate a hundred of them for a fifty-record file to no purpose.
     *
     * <p>The code page is the repository's rather than a choice of this class, which is what keeps the
     * emitted image identical to the stored bytes. It is never the platform default (practice
     * <strong>B8</strong>).
     */
    private final FixedWidthCodec codec;

    /**
     * Constructs the service over the customer master.
     *
     * <p>Plain constructor injection, with no injection annotation of any kind: Spring resolves a single
     * constructor without one, and its absence keeps the class trivially constructible by a plain JUnit
     * test - which is the whole point of gate <strong>G51</strong>. There are no setters and no field
     * injection.
     *
     * @param customerRepository the customer master's reader; must not be {@code null}
     * @throws NullPointerException if {@code customerRepository} is {@code null}
     */
    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = Objects.requireNonNull(customerRepository, "A CustomerRepository is "
                + "required: CBCUS01C reads the customer master and has nothing to do without it");
        this.codec = new FixedWidthCodec(customerRepository.datasetCharset());
    }

    // =================================================================================================
    // PROCEDURE DIVISION - app/cbl/CBCUS01C.cbl:L70-L87.
    // =================================================================================================

    /**
     * Runs {@code CBCUS01C} end to end and returns everything it emitted.
     *
     * <p>The mainline, statement for statement:
     * <pre>
     * DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.                             L71
     * PERFORM 0000-CUSTFILE-OPEN.                                                   L72
     * PERFORM UNTIL END-OF-FILE = 'Y'                                               L74
     *     IF  END-OF-FILE = 'N'                                                     L75
     *         PERFORM 1000-CUSTFILE-GET-NEXT                                        L76
     *         IF  END-OF-FILE = 'N'                                                 L77
     *             DISPLAY CUSTOMER-RECORD                                           L78
     *         END-IF
     *     END-IF
     * END-PERFORM.                                                                  L81
     * PERFORM 9000-CUSTFILE-CLOSE.                                                  L83
     * DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.                               L85
     * GOBACK.                                                                       L87
     * </pre>
     *
     * <p><strong>Nothing here is short-circuited.</strong> The outer {@code IF} at {@code L75} is
     * redundant against the loop's own {@code UNTIL … = 'Y'} on any run the flag reaches, and it is kept
     * regardless: it is a real branch of the translated control flow, and removing it would change the
     * branch count that gates <strong>G49</strong> and <strong>G50</strong> are measured against. The
     * inner {@code IF} at {@code L77} is not redundant at all - it is what stops the mainline displaying
     * a record for the read that found none.
     *
     * <p><strong>A fresh {@code WORKING-STORAGE} per call.</strong> The flag and the status register
     * belong to this execution. Nothing they hold survives the return, which is what lets twenty parity
     * cases run in any order against one singleton bean.
     *
     * <p><strong>What an abend does to the returned value.</strong> Nothing: there is no returned value.
     * {@code Z-ABEND-PROGRAM} calls {@code CEE3ABD}, which terminates the run, so the Java equivalent
     * throws and the caller never sees an {@link Execution}. {@code CustomerFileReaderJob} turns the throw
     * into a non-zero {@code ExitStatus} so JCL-equivalent {@code COND} gating still works. In particular
     * an abend produces <strong>no</strong> {@link #END_OF_EXECUTION} banner and, when the open is what
     * failed, <strong>no</strong> close either - which is how an operator tells a failed run from a clean
     * one at a glance.
     *
     * <p>The lines a failing run did emit before it abended are therefore not on the return path at all.
     * A caller that needs them - a parity case whose expected outcome <em>is</em> an abend, for instance -
     * supplies its own {@link Sysout} through {@link #readAndPrintCustomerFile(Sysout)} and reads it after
     * catching the exception. This overload exists for the ordinary caller, which does not.
     *
     * @return the emitted line sequence, the return code and the number of records read; never
     *         {@code null}
     * @throws AbendException if the open, any read, or the close reports a status the program treats as
     *                        fatal - the Java form of {@code CALL 'CEE3ABD'} at {@code L158}
     */
    public Execution readAndPrintCustomerFile() {
        // SYSOUT for this run. Ordered, append-only, and returned to the caller.
        return readAndPrintCustomerFile(new Sysout());
    }

    /**
     * Runs {@code CBCUS01C} end to end, accumulating {@code SYSOUT} into a sink the caller owns.
     *
     * <p>Identical in behaviour to {@link #readAndPrintCustomerFile()} - that method delegates here with a
     * fresh sink - and it exists for one reason: a run that abends never returns, so the only way to
     * observe the lines it emitted before abending is to have held the sink beforehand. The three fatal
     * arms each emit their error text, then the rendered file status, then {@code ABENDING PROGRAM}, and
     * all three of those lines land in the supplied sink before the throw leaves this method.
     *
     * <p>The sink must be fresh. Reusing one across two runs would prepend the first run's output to the
     * second's, and the line count invariant {@link Execution} enforces would reject the result - which is
     * the invariant doing its job rather than an inconvenience.
     *
     * @param sysout the sink to accumulate into; must not be {@code null} and should be empty
     * @return the emitted line sequence, the return code and the number of records read; never
     *         {@code null}
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if the open, any read, or the close reports a status the program treats
     *                              as fatal
     */
    public Execution readAndPrintCustomerFile(Sysout sysout) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required: the displayed line sequence is this "
                + "program's entire observable output, so there is nothing to run without somewhere to "
                + "put it");
        if (!sysout.retains()) {
            throw new IllegalArgumentException("This overload returns an " + Execution.class
                    .getSimpleName() + ", which carries the emitted line sequence, but the supplied sink "
                    + "streams to a destination and keeps nothing. Run a streaming sink through "
                    + "readAndPrintCustomerFileTo(SysoutSink) - which returns nothing, because there is "
                    + "nothing left to return - or supply a capturing sink here.");
        }

        int recordsRead = runProgram(sysout);

        // GOBACK - RETURN-CODE is never moved into, so a normal end is zero.                       L87
        return new Execution(sysout.lines(), RETURN_CODE_NORMAL_END, recordsRead);
    }

    /**
     * Runs {@code CBCUS01C} end to end, streaming every {@code DISPLAY} straight to {@code sysout} and
     * retaining none of it.
     *
     * <p><strong>This is the production entry point.</strong> {@code app/jcl/READCUST.jcl:11} binds
     * {@code //SYSOUT DD SYSOUT=*} - the job's print stream - and a print stream is written to and
     * forgotten. The two {@link Execution}-returning overloads accumulate the whole sequence instead,
     * which is what a parity case and a unit test need and what a batch run must not do: every customer
     * contributes {@link #DISPLAYS_PER_RECORD} lines of {@link #RECORD_LENGTH} characters, the customer
     * master has no record ceiling, and holding all of it costs memory proportional to the dataset for
     * output that has already been written.
     *
     * <p>Behaviourally identical to the capturing overloads in every respect that is observable. The
     * same lines are emitted, in the same order, with the same bytes, from the same code - all three
     * shapes call one private {@link #runProgram(Sysout)} - and the preserved duplicate display of
     * {@code L96} then {@code L78} is intact here too. The only difference is that nothing keeps a copy.
     *
     * <p>Nothing is returned, and that is deliberate rather than a shortcut. A normal end sets
     * {@link #RETURN_CODE_NORMAL_END}, because {@code CBCUS01C} never moves into {@code RETURN-CODE}
     * ({@code L87}), so a return value could only ever be that one constant; and a fatal open, read or
     * close leaves by {@link AbendException}, which carries the code the run ended with. A caller that
     * needs the line count for a diagnostic can read it from its own sink.
     *
     * @param sysout where every emitted line goes, one call per {@code DISPLAY}; must not be
     *               {@code null}. {@link #standardOutputSysoutSink()} is the {@code SYSOUT=*} equivalent
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if the open, any read, or the close reports a status the program
     *                              treats as fatal - the Java form of {@code CALL 'CEE3ABD'} at
     *                              {@code L158}. Its three lines reach {@code sysout} before the throw
     * @see #readAndPrintCustomerFile() for the capturing form a parity case uses
     */
    public void readAndPrintCustomerFileTo(SysoutSink sysout) {
        Objects.requireNonNull(sysout, "A SYSOUT destination is required: the displayed line sequence is "
                + "this program's entire observable output, so there is nothing to run without somewhere "
                + "to put it");

        runProgram(new Sysout(sysout));
    }

    /**
     * The whole {@code PROCEDURE DIVISION} - {@code app/cbl/CBCUS01C.cbl:L69-L87}.
     *
     * <p>One implementation behind all three public shapes, so that streaming and capturing cannot drift
     * apart: the line sequence, its order and its bytes are decided here, and whether a copy is kept is
     * decided by the {@link Sysout} handed in. A second implementation for the streaming path would be
     * the obvious way to write this and the obvious way for the two to diverge silently.
     *
     * @param sysout this run's SYSOUT façade, capturing or streaming
     * @return the number of records the browse returned and displayed
     * @throws AbendException if the open, any read, or the close reports a fatal status
     */
    private int runProgram(Sysout sysout) {
        // WORKING-STORAGE for this run - L61-L67. Never a field of this singleton.
        WorkingStorage workingStorage = new WorkingStorage();

        // DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.                                        L71
        sysout.display(START_OF_EXECUTION);

        // PERFORM 0000-CUSTFILE-OPEN.                                                             L72
        CustomerFile custFile = custfileOpen(sysout, workingStorage);

        // Everything from here on holds an open browse, so everything from here on is released on the
        // way out - see releaseHandle. The open itself is outside the boundary because a failed open
        // throws without returning a handle, and there is then nothing to release.
        try {
            // PERFORM UNTIL END-OF-FILE = 'Y' … END-PERFORM.                                  L74-L81
            int recordsRead = custfileDisplayLoop(sysout, workingStorage, custFile);

            // PERFORM 9000-CUSTFILE-CLOSE.                                                        L83
            custfileClose(sysout, workingStorage, custFile);

            // DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.                                      L85
            sysout.display(END_OF_EXECUTION);

            // GOBACK.                                                                              L87
            return recordsRead;
        } finally {
            releaseHandle(custFile);
        }
    }

    /**
     * Releases the customer master browse on every path out of
     * {@link #readAndPrintCustomerFile(Sysout)}, silently.
     *
     * <p>This is <strong>not</strong> a second {@code CLOSE}. Three properties keep it from changing
     * anything the program does:
     * <ul>
     *   <li><strong>Idempotent.</strong> Only a handle that is still open is touched, and
     *       {@link CustomerFile#closeFile()} is idempotent in its own right. On the normal path
     *       {@code 9000-CUSTFILE-CLOSE} has already closed it at {@code L83} and this does nothing at
     *       all.</li>
     *   <li><strong>Silent.</strong> No {@code DISPLAY}, no status returned, nothing branched on. The
     *       close at {@code L138} is the program's only close of this file and it is already translated
     *       in {@link #custfileClose}, with its {@code '00'}-or-12 ladder and its
     *       {@value #ERROR_CLOSING_CUSTOMER_FILE} text intact. Repeating either here would emit output
     *       the program never writes.</li>
     *   <li><strong>Non-throwing.</strong> A failure here is swallowed, because this runs in a
     *       {@code finally}: throwing would replace the {@link AbendException} the caller needs with a
     *       cleanup fault, and the abend is always the more important of the two. The swallowed
     *       condition is logged instead, so it stays diagnosable.</li>
     * </ul>
     *
     * <p>Why it is needed at all, when {@code CBCUS01C} has no such statement: {@code CALL 'CEE3ABD'}
     * ends a z/OS task and the operating system reclaims its open files, whereas an
     * {@link AbendException} ends one step inside a JVM that keeps running, and a browse left open there
     * is held for the life of the process.
     *
     * @param custFile the handle {@code 0000-CUSTFILE-OPEN} returned; never {@code null} here, because a
     *                 failed open throws instead of returning one
     */
    private static void releaseHandle(CustomerFile custFile) {
        if (custFile.isClosed()) {
            return;
        }
        try {
            custFile.closeFile();
        } catch (RuntimeException cleanupFailure) {
            // The throwable is deliberately not handed to the logger, and neither is its message: a
            // driver composes its message around the value it refused, and a customer master row carries
            // CUST-SSN, CUST-DOB-YYYY-MM-DD and the customer's names (CWE-532), with a newline in that
            // text able to forge a second entry (CWE-117). BackendDiagnostic carries the SQLSTATE, the
            // vendor code and the exception type, and has no component for a message.
            LOG.warn("Releasing the " + DD_NAME + " browse of " + PROGRAM_ID + " after an incomplete run "
                    + "failed - " + BackendDiagnostic.of(cleanupFailure).describe()
                    + ". The run's own outcome is reported to the caller unchanged, because the run's own "
                    + "failure is the one that matters.");
        }
    }

    /**
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} - {@code app/cbl/CBCUS01C.cbl:L74-L81}.
     *
     * <p>A {@code while} over the same test the source writes, so the iteration count and the point at
     * which it stops are identical. The loop carries no termination condition of its own: it ends when
     * the flag becomes {@code 'Y'}, which only {@link #custfileGetNext} sets, and a fatal read leaves it
     * by throwing.
     *
     * <p>The test is {@code != 'Y'} rather than {@code == 'N'}, deliberately. Those differ for any third
     * value, and the source's loop tests {@code 'Y'} while its guards test {@code 'N'}; translating each
     * as written is what keeps them distinguishable.
     *
     * @param sysout         where displayed lines accumulate
     * @param workingStorage this run's status register and end-of-file flag
     * @param custFile       the opened customer master
     * @return the number of records read and displayed
     * @throws AbendException if a read reports a fatal status
     */
    private int custfileDisplayLoop(Sysout sysout, WorkingStorage workingStorage,
            CustomerFile custFile) {
        int recordsRead = 0;
        while (!workingStorage.endOfFileIsYes()) {
            recordsRead += custfileDisplayIteration(sysout, workingStorage, custFile);
        }
        return recordsRead;
    }

    /**
     * One iteration of the mainline loop - {@code app/cbl/CBCUS01C.cbl:L75-L80}:
     * <pre>
     * IF  END-OF-FILE = 'N'
     *     PERFORM 1000-CUSTFILE-GET-NEXT
     *     IF  END-OF-FILE = 'N'
     *         DISPLAY CUSTOMER-RECORD
     *     END-IF
     * END-IF
     * </pre>
     *
     * <p>Package-private rather than private so that the <em>false</em> arm of the first guard stays
     * directly assertable. On a real run the flag only ever holds {@code 'N'} or {@code 'Y'}, so the
     * loop condition already implies the guard and the mainline cannot reach that arm - which is exactly
     * why it needs a test that can call this method with the flag set to something else. It is a real
     * part of the translated control flow, not something to drop (practice <strong>B5</strong>).
     *
     * @param sysout         where displayed lines accumulate
     * @param workingStorage this run's status register and end-of-file flag
     * @param custFile       the opened customer master
     * @return {@code 1} when a record was read and displayed, {@code 0} otherwise
     * @throws AbendException if the read reports a fatal status
     */
    int custfileDisplayIteration(Sysout sysout, WorkingStorage workingStorage,
            CustomerFile custFile) {
        // IF END-OF-FILE = 'N'                                                                     L75
        if (!workingStorage.endOfFileIsNo()) {
            return 0;
        }

        // PERFORM 1000-CUSTFILE-GET-NEXT                                                           L76
        ReadResult result = custfileGetNext(sysout, workingStorage, custFile);

        // IF END-OF-FILE = 'N'                                                                     L77
        if (!workingStorage.endOfFileIsNo()) {
            return 0;
        }

        // ---------------------------------------------------------------------------------------------
        // DISPLAY CUSTOMER-RECORD                                                                  L78
        //
        // PRESERVED DEFECT (practice B5) - THE SECOND OF THIS RECORD'S TWO IDENTICAL DISPLAYS.
        // 1000-CUSTFILE-GET-NEXT has already displayed this very record at L96, in this very form: the
        // raw 500-byte group image. So each record appears TWICE, on two adjacent byte-identical lines,
        // and 50 fixture records produce 100 record lines. This is not redundant code to remove - it is
        // the program's observable output, and de-duplicating it would halve every parity fingerprint.
        // CBACT01C differs precisely here: its L96 performs a LABELLED display paragraph, so only its
        // mainline emits the raw group. CBCUS01C has no labelled display paragraph at all.
        // ---------------------------------------------------------------------------------------------
        sysout.displayCustomerRecord(recordImageOf(result, workingStorage));
        return 1;
    }

    /**
     * {@code 0000-CUSTFILE-OPEN} - {@code app/cbl/CBCUS01C.cbl:L118-L134}.
     *
     * <pre>
     * MOVE 8 TO APPL-RESULT.                                                        L119
     * OPEN INPUT CUSTFILE-FILE                                                      L120
     * IF  CUSTFILE-STATUS = '00'   MOVE 0  TO APPL-RESULT                       L121-L122
     * ELSE                         MOVE 12 TO APPL-RESULT                       L123-L124
     * IF  APPL-AOK                 CONTINUE                                     L126-L127
     * ELSE                         DISPLAY 'ERROR OPENING CUSTFILE'                  L129
     *                              MOVE CUSTFILE-STATUS TO IO-STATUS                 L130
     *                              PERFORM Z-DISPLAY-IO-STATUS                       L131
     *                              PERFORM Z-ABEND-PROGRAM                           L132
     * </pre>
     *
     * <p>Two guard chains in sequence rather than one: the first classifies the file status into
     * {@code APPL-RESULT}, the second decides what to do about the classification. Keeping them apart is
     * what keeps the {@code 88}-level conditions the second chain tests, and the register the abend's
     * return code comes from, both present.
     *
     * <p>An open that fails abends here, so the browse never starts, {@code 9000-CUSTFILE-CLOSE} never
     * runs and the closing banner is never emitted. The only line the run produces beyond the opening
     * banner is this paragraph's error text, the rendered status and {@code ABENDING PROGRAM}.
     *
     * @param sysout         where displayed lines accumulate
     * @param workingStorage this run's status register
     * @return the opened file handle, positioned before the first record
     * @throws AbendException if the open reports any status other than {@code '00'}
     */
    private CustomerFile custfileOpen(Sysout sysout, WorkingStorage workingStorage) {
        // MOVE 8 TO APPL-RESULT.
        //
        // DEAD STORE, PRESERVED (practice B5). Every path below overwrites it before anything reads it,
        // so no execution can observe this 8. It is transcribed because the source contains it, on the
        // same ruling that keeps CBACT04C's 1400-COMPUTE-FEES stub a no-op: this migration reproduces
        // the program it was given, including the parts of it that do nothing.                     L119
        workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);

        // OPEN INPUT CUSTFILE-FILE                                                                L120
        CustomerFile custFile = customerRepository.openInput();
        String status = custFile.openStatus();

        if (FileStatus.isOk(status)) {                                                          // L121
            workingStorage.moveToApplResult(APPL_AOK);                                          // L122
        } else {                                                                                // L123
            workingStorage.moveToApplResult(APPL_RESULT_FATAL);                                 // L124
        }

        // IF APPL-AOK CONTINUE ELSE …                                                       L126-L133
        if (!workingStorage.applAok()) {
            throw reportAndAbend(sysout, workingStorage, ERROR_OPENING_CUSTFILE, status);
        }
        return custFile;
    }

    /**
     * {@code 1000-CUSTFILE-GET-NEXT} - {@code app/cbl/CBCUS01C.cbl:L92-L116}.
     *
     * <pre>
     * READ CUSTFILE-FILE INTO CUSTOMER-RECORD.                                       L93
     * IF  CUSTFILE-STATUS = '00'    MOVE 0  TO APPL-RESULT                       L94-L95
     *                               DISPLAY CUSTOMER-RECORD                          L96
     * ELSE IF CUSTFILE-STATUS = '10' MOVE 16 TO APPL-RESULT                      L98-L99
     *      ELSE                      MOVE 12 TO APPL-RESULT                         L101
     * IF  APPL-AOK                  CONTINUE                                   L104-L105
     * ELSE IF APPL-EOF              MOVE 'Y' TO END-OF-FILE                    L107-L108
     *      ELSE                     DISPLAY 'ERROR READING CUSTOMER FILE'           L110
     *                               MOVE CUSTFILE-STATUS TO IO-STATUS                L111
     *                               PERFORM Z-DISPLAY-IO-STATUS                      L112
     *                               PERFORM Z-ABEND-PROGRAM                          L113
     * </pre>
     *
     * <p><strong>This is where the first of a record's two displays happens</strong> - inside the
     * successful arm of the <em>read</em>, at {@code L96}, before control ever returns to the mainline.
     * See {@link #custfileDisplayIteration} for the second.
     *
     * <p><strong>The final {@code ELSE} is the {@code WHEN OTHER}.</strong> {@code '22'} and {@code '23'}
     * cannot arise from a sequential browse of an input file, and they route through it anyway, exactly
     * as anything that is neither {@code '00'} nor {@code '10'} does. The arm is not collapsed and no
     * status gets special-cased, so gate <strong>G47</strong> can exercise each one at this call site.
     *
     * @param sysout         where displayed lines accumulate
     * @param workingStorage this run's status register and end-of-file flag
     * @param custFile       the opened customer master
     * @return the read's outcome, carrying the decoded record on the successful arm
     * @throws AbendException if the status is neither {@code '00'} nor {@code '10'}
     */
    private ReadResult custfileGetNext(Sysout sysout, WorkingStorage workingStorage,
            CustomerFile custFile) {
        // READ CUSTFILE-FILE INTO CUSTOMER-RECORD.                                                 L93
        ReadResult result = custFile.readNext();
        String status = result.status();

        if (FileStatus.isOk(status)) {                                                           // L94
            workingStorage.moveToApplResult(APPL_AOK);                                           // L95

            // -----------------------------------------------------------------------------------------
            // DISPLAY CUSTOMER-RECORD                                                             L96
            //
            // PRESERVED DEFECT (practice B5) - THE FIRST OF THIS RECORD'S TWO IDENTICAL DISPLAYS.
            // The mainline displays the same record again at L78, in the same raw group form. Both
            // lines are emitted; neither is a duplicate to be removed. Note that CBACT01C performs a
            // labelled-field paragraph here instead - CBCUS01C has none, and must not be given one.
            // -----------------------------------------------------------------------------------------
            sysout.displayCustomerRecord(recordImageOf(result, workingStorage));
        } else if (FileStatus.isEndOfFile(status)) {                                             // L98
            workingStorage.moveToApplResult(APPL_EOF);                                           // L99
        } else {
            // WHEN OTHER - every status that is neither '00' nor '10', '22' and '23' included.   L101
            workingStorage.moveToApplResult(APPL_RESULT_FATAL);
        }

        // IF APPL-AOK CONTINUE ELSE …                                                      L104-L115
        if (!workingStorage.applAok()) {
            if (workingStorage.applEof()) {                                                     // L107
                workingStorage.moveEndOfFile(END_OF_FILE_YES);                                  // L108
            } else {
                throw reportAndAbend(sysout, workingStorage, ERROR_READING_CUSTOMER_FILE, status);
            }
        }
        return result;
    }

    /**
     * {@code 9000-CUSTFILE-CLOSE} - {@code app/cbl/CBCUS01C.cbl:L136-L152}.
     *
     * <pre>
     * ADD 8 TO ZERO GIVING APPL-RESULT.                                             L137
     * CLOSE CUSTFILE-FILE                                                           L138
     * IF  CUSTFILE-STATUS = '00'   SUBTRACT APPL-RESULT FROM APPL-RESULT        L139-L140
     * ELSE                         ADD 12 TO ZERO GIVING APPL-RESULT           L141-L142
     * IF  APPL-AOK                 CONTINUE                                    L144-L145
     * ELSE                         DISPLAY 'ERROR CLOSING CUSTOMER FILE'            L147
     *                              MOVE CUSTFILE-STATUS TO IO-STATUS                L148
     *                              PERFORM Z-DISPLAY-IO-STATUS                      L149
     *                              PERFORM Z-ABEND-PROGRAM                          L150
     * </pre>
     *
     * <p><strong>The arithmetic spelling is preserved.</strong> This paragraph reaches the same three
     * values the open reaches, and reaches them by arithmetic rather than by {@code MOVE}:
     * {@code ADD 8 TO ZERO GIVING} instead of {@code MOVE 8}, {@code SUBTRACT APPL-RESULT FROM
     * APPL-RESULT} instead of {@code MOVE 0}, and {@code ADD 12 TO ZERO GIVING} instead of
     * {@code MOVE 12}. The results are identical, and the spelling is transcribed anyway - through
     * {@link WorkingStorage#addToZeroGivingApplResult(int)} and
     * {@link WorkingStorage#subtractApplResultFromApplResult()} - so that a reviewer diffing this method
     * against the paragraph finds one Java statement per COBOL statement and no silent normalisation.
     *
     * @param sysout         where displayed lines accumulate
     * @param workingStorage this run's status register
     * @param custFile       the file to close
     * @throws AbendException if the close reports any status other than {@code '00'}
     */
    private void custfileClose(Sysout sysout, WorkingStorage workingStorage, CustomerFile custFile) {
        // ADD 8 TO ZERO GIVING APPL-RESULT.
        //
        // DEAD STORE, PRESERVED (practice B5), and spelled as the source spells it. Overwritten on
        // both arms below before anything reads it.                                               L137
        workingStorage.addToZeroGivingApplResult(APPL_RESULT_ASSUMED_FAILURE);

        // CLOSE CUSTFILE-FILE                                                                    L138
        String status = custFile.closeFile();

        if (FileStatus.isOk(status)) {                                                          // L139
            // SUBTRACT APPL-RESULT FROM APPL-RESULT - the source's way of writing zero.          L140
            workingStorage.subtractApplResultFromApplResult();
        } else {                                                                                // L141
            workingStorage.addToZeroGivingApplResult(APPL_RESULT_FATAL);                        // L142
        }

        // IF APPL-AOK CONTINUE ELSE …                                                      L144-L151
        if (!workingStorage.applAok()) {
            throw reportAndAbend(sysout, workingStorage, ERROR_CLOSING_CUSTOMER_FILE, status);
        }
    }

    // =================================================================================================
    // The record image - what DISPLAY CUSTOMER-RECORD actually writes.
    // =================================================================================================

    /**
     * The 500-character group image of the record a successful read carried.
     *
     * <p>{@code DISPLAY CUSTOMER-RECORD} names the {@code 01} group item, so it writes the record's
     * <strong>whole stored image</strong> and not a rendering of its fields: nine digits of
     * {@code CUST-ID}, then each {@code PIC X} field at its declared width, then the 168 spaces of the
     * trailing {@code FILLER}. Rendering it from {@link CustomerRecord#recordImage(FixedWidthCodec)}
     * rather than from a {@code toString} is what guarantees that - a diagnostic rendering could trim,
     * reorder or mask, and any of those would break the field-for-field diff. Nothing here is trimmed,
     * nothing is JSON, and the {@code FILLER} is present (gate <strong>G21</strong>).
     *
     * @param result         the read outcome, which must be the successful arm
     * @param workingStorage this run's flag and register, for the diagnostic if it is not
     * @return exactly {@link #RECORD_LENGTH} characters
     * @throws IllegalStateException if the outcome carries no record, which only a defect in this class
     *                               can produce
     */
    private String recordImageOf(ReadResult result, WorkingStorage workingStorage) {
        CustomerRecord customer = result.customer().orElseThrow(() -> new IllegalStateException(
                "A read of the customer master reported file status '" + result.status() + "' and left "
                        + "END-OF-FILE = '" + workingStorage.endOfFileFlag() + "', so DISPLAY "
                        + "CUSTOMER-RECORD was reached with no record to display. Only the '"
                        + FileStatus.OK + "' arm displays, and that arm always carries the decoded "
                        + "record."));
        return customer.recordImage(codec);
    }

    // =================================================================================================
    // Z-DISPLAY-IO-STATUS and Z-ABEND-PROGRAM - app/cbl/CBCUS01C.cbl:L154-L174.
    //
    // Both paragraphs are shared behaviour under a local name: CBACT01C, CBACT02C, CBACT03C, CBACT04C,
    // CBTRN01C, CBTRN02C and CBTRN03C spell them 9910-DISPLAY-IO-STATUS and 9999-ABEND-PROGRAM, and the
    // logic is identical. So the renderer lives in FileStatus and the abend lives in AbendException, and
    // neither is reimplemented here.
    // =================================================================================================

    /**
     * The three-statement tail every fatal arm performs: display the error text, render the file status,
     * abend.
     *
     * <p>{@code L110-L113}, {@code L129-L132} and {@code L147-L150} are the same four statements with a
     * different text, so they are one method with the text as a parameter. It <em>returns</em> the
     * exception instead of throwing it, so each call site reads {@code throw reportAndAbend(…)} and the
     * compiler can see that control does not continue past it.
     *
     * <p>{@code APPL-RESULT} holds {@link #APPL_RESULT_FATAL} at all three sites, which is asserted here
     * rather than assumed: it is the value that becomes the process return code, and a wrong one would
     * change how JCL-equivalent {@code COND} gating behaves downstream.
     *
     * @param sysout         where displayed lines accumulate
     * @param workingStorage this run's status register, which supplies the return code
     * @param errorText      the paragraph's own error literal
     * @param status         the two-character file status the failing operation reported
     * @return the abend to throw
     */
    private AbendException reportAndAbend(Sysout sysout, WorkingStorage workingStorage,
            String errorText, String status) {
        // DISPLAY '<the paragraph's error text>'                          L110 / L129 / L147
        sysout.display(errorText);

        // MOVE CUSTFILE-STATUS TO IO-STATUS, then PERFORM Z-DISPLAY-IO-STATUS.
        //                                                                L111-L112 / L130-L131 / L148-L149
        workingStorage.moveToIoStatus(status);
        String statusLine = displayIoStatus(sysout, workingStorage);

        // PERFORM Z-ABEND-PROGRAM.                                       L113 / L132 / L150
        return abendProgram(sysout, workingStorage, errorText + " - " + statusLine);
    }

    /**
     * {@code Z-DISPLAY-IO-STATUS} - {@code app/cbl/CBCUS01C.cbl:L161-L174}.
     *
     * <p><strong>Delegated, never duplicated.</strong> The paragraph appears once per batch program
     * across the estate with only its name changing - {@code CBACT01C} calls it
     * {@code 9910-DISPLAY-IO-STATUS} - so {@link FileStatus#toDisplayLine(String)} owns it and this
     * method passes the raw two-character status and emits what comes back.
     *
     * <p>For a reviewer, the two branches it owns are:
     * <ul>
     *   <li>the <strong>extended</strong> branch, taken when {@code IO-STATUS NOT NUMERIC OR IO-STAT1 =
     *       '9'} ({@code L162-L163}): the first character of the image is the status's first byte, and
     *       the remaining three digits are the second byte reinterpreted as a binary value through the
     *       {@code TWO-BYTES-BINARY} / {@code TWO-BYTES-ALPHA} redefinition;</li>
     *   <li>the <strong>numeric</strong> branch ({@code L170-L171}): the image is {@code '0000'} with the
     *       two status characters overlaid at one-based positions three and four.</li>
     * </ul>
     *
     * <p>And the quirk worth stating plainly: {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} writes
     * the literal - {@code NNNN} included, which is text rather than a substitution marker - and then
     * appends the four-character image, so status {@code '00'} produces {@code FILE STATUS IS: NNNN0000}.
     * That oddity is observable output and is reproduced, not corrected.
     *
     * @param sysout         where the rendered line goes
     * @param workingStorage this run's {@code IO-STATUS}, already moved
     * @return the line that was emitted, so the abend can carry it as its reason
     */
    private String displayIoStatus(Sysout sysout, WorkingStorage workingStorage) {
        String statusLine = FileStatus.toDisplayLine(workingStorage.ioStatus());
        sysout.display(statusLine);
        return statusLine;
    }

    /**
     * {@code Z-ABEND-PROGRAM} - {@code app/cbl/CBCUS01C.cbl:L154-L158}.
     *
     * <pre>
     * DISPLAY 'ABENDING PROGRAM'                                                    L155
     * MOVE 0 TO TIMING                                                              L156
     * MOVE 999 TO ABCODE                                                            L157
     * CALL 'CEE3ABD'.                                                               L158
     * </pre>
     *
     * <p><strong>The banner is emitted before the throw</strong>, so it is the last line of the
     * fingerprint on every failing path. Emitting it afterwards - or letting the exception's message
     * stand in for it - would leave the observable output one line short of what the COBOL produced.
     *
     * <p>{@link AbendException#standard(String, int, String)} supplies {@code ABCODE = 999} and
     * {@code TIMING = 0} exactly as {@code L156-L157} move them, so those two numbers are not restated
     * here. The return code is whatever {@code APPL-RESULT} holds, which at all three call sites is
     * {@link #APPL_RESULT_FATAL}, taken from the register rather than from a literal so that the register
     * remains the single source of it.
     *
     * <p><strong>The file is deliberately not closed on this path.</strong> {@code CEE3ABD} terminates
     * the run where it stands: a failed read never reaches {@code 9000-CUSTFILE-CLOSE}. Adding a
     * defensive close would issue a metadata probe against the backend that the COBOL never issues, so
     * it is left out. Nothing leaks by leaving it out - the handle holds no connection and no descriptor,
     * only a browse position.
     *
     * @param sysout         where the banner goes
     * @param workingStorage this run's status register, which supplies the return code
     * @param reason         the failing paragraph's error text and rendered status, for the diagnostic
     * @return the abend to throw
     */
    private AbendException abendProgram(Sysout sysout, WorkingStorage workingStorage, String reason) {
        // DISPLAY 'ABENDING PROGRAM'                                                              L155
        sysout.display(ABENDING_PROGRAM);

        // MOVE 0 TO TIMING, MOVE 999 TO ABCODE, CALL 'CEE3ABD'.                             L156-L158
        return AbendException.standard(PROGRAM_ID, workingStorage.applResult(), reason);
    }

    // =================================================================================================
    // The two 88-level condition names, as named predicates - app/cbl/CBCUS01C.cbl:L62-L63.
    //
    // Exposed as pure static functions of the register's value rather than only as instance tests, so a
    // test can drive each of them true and false directly. Gate G50 requires both states of every
    // 88-level, and this program has exactly two. Static AND pure, so they add no mutable state.
    // =================================================================================================

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBCUS01C.cbl:L62}.
     *
     * <p>Tested at {@code L104}, {@code L126} and {@code L144}, once per I/O paragraph. It is an
     * equality against zero and not a "not an error" test: {@link #APPL_EOF} and
     * {@link #APPL_RESULT_FATAL} both make it false, and the chain that follows is what tells those two
     * apart.
     *
     * @param applResult the value {@code APPL-RESULT} holds
     * @return {@code true} when the register holds {@link #APPL_AOK}
     */
    public static boolean isApplAok(int applResult) {
        return applResult == APPL_AOK;
    }

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBCUS01C.cbl:L63}.
     *
     * <p>Tested at {@code L107} only, inside the read's guard chain, and reached only when
     * {@link #isApplAok(int)} is already false. The open and close paragraphs never test it, because
     * neither operation can report end of file.
     *
     * @param applResult the value {@code APPL-RESULT} holds
     * @return {@code true} when the register holds {@link #APPL_EOF}
     */
    public static boolean isApplEof(int applResult) {
        return applResult == APPL_EOF;
    }

    // =================================================================================================
    // Declared output geometry, so the 102-line fact is derivable rather than folklore.
    // =================================================================================================

    /**
     * How many lines a normal end emits for a given record count:
     * {@link #BANNER_LINE_COUNT} + {@link #DISPLAYS_PER_RECORD} × {@code recordsRead}.
     *
     * <p>For the fifty-record {@code app/data/ASCII/custdata.txt} fixture that is
     * {@code 2 + 2 × 50 = 102}. Stated as a function rather than as a constant so the arithmetic - and
     * in particular the {@code × 2} that the preserved duplicate display makes necessary - is visible
     * and assertable at any record count, including zero.
     *
     * <p>It describes a <em>normal</em> end only. An abending run has no closing banner and emits its
     * error text, its rendered status and {@code ABENDING PROGRAM} instead.
     *
     * @param recordsRead how many records the browse returned; must not be negative
     * @return the exact number of lines a normal end emits
     * @throws IllegalArgumentException if {@code recordsRead} is negative
     */
    public static int expectedSysoutLineCount(int recordsRead) {
        if (recordsRead < 0) {
            throw new IllegalArgumentException("A record count cannot be negative: " + recordsRead
                    + ". A browse returns zero records for an empty dataset and never fewer.");
        }
        return BANNER_LINE_COUNT + DISPLAYS_PER_RECORD * recordsRead;
    }

    // =================================================================================================
    // The returned value - the behavioural fingerprint of one execution.
    // =================================================================================================

    /**
     * Everything one run of {@code CBCUS01C} produced: the {@code SYSOUT} line sequence, the process
     * return code, and how many records the browse returned.
     *
     * <p>These are the three things the parity harness fingerprints. The line sequence is the program's
     * entire observable output - {@code CBCUS01C} writes no dataset - so comparing two runs field by
     * field means comparing these lines in this order. Returning them as a value rather than writing them
     * to a stream is what lets the differ do that without parsing a console, and what lets a unit test
     * assert the whole program without redirecting standard output.
     *
     * <p>A record, so an execution's result is immutable once produced and cannot be edited by whoever
     * receives it.
     *
     * @param sysout      every emitted line, in emission order: the opening banner, then two identical
     *                    500-character images per record, then the closing banner. Immutable and never
     *                    {@code null}
     * @param returnCode  the process return code, always {@link #RETURN_CODE_NORMAL_END} for a returned
     *                    execution - {@code GOBACK} at {@code L87} leaves {@code RETURN-CODE} untouched,
     *                    and every non-zero outcome leaves by throwing {@link AbendException} instead of
     *                    returning
     * @param recordsRead how many records the browse returned, which is
     *                    {@code (sysout.size() - 2) / 2} on a normal end
     */
    public record Execution(List<String> sysout, int returnCode, int recordsRead) {

        /**
         * Enforces the invariants of a fingerprint at construction, so no inconsistent one exists.
         *
         * @throws NullPointerException     if {@code sysout} is {@code null}
         * @throws IllegalArgumentException if {@code recordsRead} is negative, or if the line count does
         *                                  not match {@link #expectedSysoutLineCount(int)} for that
         *                                  record count
         */
        public Execution {
            Objects.requireNonNull(sysout, "An execution carries its emitted lines; an empty list is the "
                    + "way to say nothing was emitted, and null is not");
            if (recordsRead < 0) {
                throw new IllegalArgumentException("An execution cannot have read " + recordsRead
                        + " records. A browse returns zero for an empty dataset and never fewer.");
            }
            int expected = expectedSysoutLineCount(recordsRead);
            if (sysout.size() != expected) {
                throw new IllegalArgumentException("An execution that read " + recordsRead
                        + " records emits exactly " + expected + " lines - " + BANNER_LINE_COUNT
                        + " banners plus " + DISPLAYS_PER_RECORD + " identical images per record, because "
                        + "CBCUS01C displays every record twice (L96 and L78) - but this one carries "
                        + sysout.size() + ". Either a display was dropped or one was added.");
            }
            sysout = List.copyOf(sysout);
        }

        /**
         * How many lines were emitted.
         *
         * @return the size of {@link #sysout()}; {@code 102} for the fifty-record fixture
         */
        public int lineCount() {
            return sysout.size();
        }

        /**
         * The record images alone, with the two banners removed.
         *
         * <p>{@link #DISPLAYS_PER_RECORD} × {@link #recordsRead()} lines, in emission order, each pair
         * adjacent and byte-identical.
         *
         * @return the record lines; immutable and never {@code null}
         */
        public List<String> recordLines() {
            return sysout.subList(1, sysout.size() - 1);
        }
    }

    // =================================================================================================
    // SYSOUT - app/jcl/READCUST.jcl:L11.
    // =================================================================================================

    /**
     * The {@code SYSOUT} of one execution: an ordered, append-only line sequence that also reaches the
     * module's logger.
     *
     * <p>Created per call, so it is neither shared nor visible beyond the execution that owns it. Not
     * thread-safe, and it does not need to be: one execution emits through one of these from one thread,
     * and two executions never see the same instance.
     *
     * <h2>Capturing or streaming, and the run cannot tell</h2>
     * {@link #Sysout()} keeps every line, which is what a parity fingerprint and a unit test are made of.
     * {@link #Sysout(SysoutSink)} hands each line to a destination and keeps nothing, which is what a
     * batch run does: every customer contributes {@link #DISPLAYS_PER_RECORD} lines of
     * {@link #RECORD_LENGTH} characters and the customer master has no record ceiling, so retaining the
     * sequence costs memory proportional to the dataset for output already written.
     *
     * <p>Both modes emit through one {@code destination} field and one {@code emit} method, so the two
     * cannot produce different bytes or a different order. What differs is only whether a copy is kept:
     * {@link #lines()} refuses on a streaming sink rather than answering with an empty list, while
     * {@link #lineCount()} and {@link #recordImageCount()} are counters and stay answerable in both.
     *
     * <h2>Two emission methods, and why they differ</h2>
     * {@link #display(String)} appends a line and logs it verbatim. It carries the two banners, the three
     * error texts, the rendered file status and {@code ABENDING PROGRAM} - none of which contains
     * customer data.
     *
     * <p>{@link #displayCustomerRecord(String)} appends the record image verbatim, because that is the
     * program's observable output and the parity contract, but does <strong>not</strong> hand it to the
     * logger. A {@code CUSTOMER-RECORD} carries {@code CUST-SSN}, {@code CUST-DOB-YYYY-MM-DD},
     * {@code CUST-GOVT-ISSUED-ID} and the customer's names; copying those into the application log would
     * disclose them to a file that outlives and out-reaches the dataset (CWE-532), and a control
     * character anywhere in the 500 bytes could split the entry (CWE-117). Its log line therefore carries
     * only the browse ordinal and the byte count. Parity is untouched by that choice, because the
     * application log is not an artefact the COBOL produces - {@code SYSOUT} is, and {@code SYSOUT} is
     * what {@link #lines()} returns, byte for byte.
     *
     * <h2>Public to read, package-private to write</h2>
     * The type and {@link #lines()} are public so that a caller in another package - the parity harness,
     * whose expected outcome for some cases <em>is</em> an abend - can create one, hand it to
     * {@link CustomerService#readAndPrintCustomerFile(Sysout)} and read what was emitted after catching the
     * exception. The two emission methods are package-private, so only this service can append: the line
     * sequence is a program's output, not something a caller may edit into a shape it prefers. That holds
     * for a streaming sink too - the caller supplies the destination, and the service decides what reaches
     * it.
     */
    public static final class Sysout {

        /**
         * The lines emitted so far, in emission order, or {@code null} when this sink streams.
         *
         * <p>Appended to, never rewritten. {@code null} rather than an empty list for a streaming sink,
         * so that {@link #lines()} can refuse instead of answering "nothing was emitted" about a run that
         * emitted everything it was asked to.
         */
        private final List<String> lines;

        /**
         * Where an emitted line goes.
         *
         * <p>One seam for both modes: a capturing sink points this at its own list, a streaming sink
         * points it at the caller's destination, and {@link #display} and {@link #displayCustomerRecord}
         * are then identical in both. That is what keeps the two modes from producing different bytes.
         */
        private final SysoutSink destination;

        /**
         * How many record images have been emitted, for the ordinal in the per-record log entry.
         *
         * <p>Counted rather than derived from {@link #lines}: deriving it would silently assume exactly
         * one banner precedes every image, and an assumption that holds today is not a thing to build a
         * diagnostic on.
         */
        private int recordImageCount;

        /**
         * How many lines have been emitted, counted rather than derived.
         *
         * <p>A counter because a streaming sink has no list to size, and because the count is the thing
         * {@link Execution}'s invariant is stated in - so it stays answerable in both modes.
         */
        private int lineCount;

        /**
         * Creates an empty sink.
         *
         * <p>Declared explicitly rather than left to the compiler's default so that it can be documented:
         * a sink is always created empty, and one execution owns exactly one of them.
         */
        public Sysout() {
            List<String> captured = new ArrayList<>();
            this.lines = captured;
            this.destination = captured::add;
        }

        /**
         * Creates a sink that streams every line to {@code destination} and keeps none of them.
         *
         * <p>For a batch run, where SYSOUT is a print stream that has already consumed the line by the
         * time the next one is emitted. {@link #lines()} refuses on a sink built this way, because there
         * is nothing to answer with and an empty list would read as a run that displayed nothing.
         *
         * <p>{@link #lineCount()} and {@link #recordImageCount()} both still work: they are counters, not
         * derived from a retained list, which is exactly why they were written that way.
         *
         * @param destination where each emitted line goes, one call per {@code DISPLAY}; must not be
         *                    {@code null}
         * @throws NullPointerException if {@code destination} is {@code null}
         */
        public Sysout(SysoutSink destination) {
            this.lines = null;
            this.destination = Objects.requireNonNull(destination, "A streaming SYSOUT sink needs "
                    + "somewhere to stream to");
        }

        /**
         * Whether this sink keeps what it emits.
         *
         * @return {@code true} for a capturing sink, {@code false} for a streaming one
         */
        public boolean retains() {
            return lines != null;
        }

        /**
         * Emits one line of control output: appended verbatim and logged verbatim.
         *
         * <p>Logged at {@code INFO} whatever the line says, including the three error texts, because
         * {@code DISPLAY} makes no such distinction and classifying the lines here would be a judgement
         * the source does not make. A failure is surfaced at {@code ERROR} where it belongs - on the
         * {@link AbendException} that leaves this service, which carries the same error text and the same
         * rendered status.
         *
         * @param line the line, exactly as the COBOL {@code DISPLAY} writes it; must not be {@code null}
         * @throws NullPointerException if {@code line} is {@code null}
         */
        void display(String line) {
            Objects.requireNonNull(line, "A DISPLAY writes a line, and a COBOL literal is never null");
            emit(line);
            LOG.info(line);
        }

        /**
         * Emits one record image: appended verbatim, logged only as an ordinal and a width.
         *
         * <p>The single-argument log call is deliberate as well as sufficient - the module forbids handing
         * a second argument to a logger anywhere in its main sources, because a throwable passed there
         * emits its whole cause chain verbatim.
         *
         * @param recordImage the record's whole group image, exactly {@link #RECORD_LENGTH} characters;
         *                    must not be {@code null}
         * @throws NullPointerException     if {@code recordImage} is {@code null}
         * @throws IllegalArgumentException if it is not exactly {@link #RECORD_LENGTH} characters, which
         *                                  would mean the trailing {@code FILLER} was dropped or the
         *                                  layout drifted (gate <strong>G21</strong>)
         */
        void displayCustomerRecord(String recordImage) {
            Objects.requireNonNull(recordImage, "DISPLAY CUSTOMER-RECORD writes the record's group "
                    + "image, and a rendered group image is never null");
            if (recordImage.length() != RECORD_LENGTH) {
                throw new IllegalArgumentException("A CUSTOMER-RECORD image is exactly " + RECORD_LENGTH
                        + " characters - CVCUS01Y's fields plus its trailing FILLER X(168) - and this one "
                        + "is " + recordImage.length() + ". A short image means the FILLER was omitted, "
                        + "which breaks every offset downstream of it.");
            }
            emit(recordImage);
            recordImageCount++;
            if (LOG.isDebugEnabled()) {
                // Deliberately NOT the image: it holds CUST-SSN, CUST-DOB-YYYY-MM-DD,
                // CUST-GOVT-ISSUED-ID and the customer's names (CWE-532). The ordinal is enough to
                // correlate a log entry with a position in the browse.
                LOG.debug("Displayed CUSTOMER-RECORD image " + recordImageCount + " of the " + DD_NAME
                        + " browse (" + RECORD_LENGTH + " bytes, withheld from this log)");
            }
        }

        /**
         * How many record images have been emitted so far.
         *
         * <p>Two per record, because of the preserved duplicate display, so this is
         * {@link CustomerService#DISPLAYS_PER_RECORD} × the record count.
         *
         * @return the count
         */
        public int recordImageCount() {
            return recordImageCount;
        }

        /**
         * The emitted lines.
         *
         * <p>A snapshot: appending afterwards does not change a list already handed out, and the list
         * handed out cannot be used to append. Both directions matter, because this value is the parity
         * fingerprint and a fingerprint that can be edited after the fact evidences nothing.
         *
         * @return an immutable snapshot in emission order; never {@code null}
         */
        public List<String> lines() {
            if (lines == null) {
                throw new IllegalStateException("This sink streamed its " + lineCount + " lines to a "
                        + "destination and kept none of them, so there is no sequence to hand back. A "
                        + "caller that needs the sequence - a parity case, or a test - constructs a "
                        + "capturing sink with the no-argument constructor.");
            }
            return Collections.unmodifiableList(new ArrayList<>(lines));
        }

        /**
         * How many lines have been emitted so far.
         *
         * @return the line count, which is {@link CustomerService#BANNER_LINE_COUNT} plus
         *         {@link #recordImageCount()} on a normal end
         */
        public int lineCount() {
            return lineCount;
        }

        /**
         * Hands one line to the destination and counts it.
         *
         * <p>The single place a line leaves this sink. Counting here rather than at the two call sites is
         * what makes the count and the emission impossible to get out of step.
         *
         * @param line the line, already validated by the caller
         */
        private void emit(String line) {
            destination.write(line);
            lineCount++;
        }
    }

    /**
     * Where a {@code DISPLAY} goes.
     *
     * <p>{@code app/jcl/READCUST.jcl:11} binds {@code //SYSOUT DD SYSOUT=*}, the job's print stream.
     * This is that binding as one method, so a batch run can write each line and forget it while a
     * parity case can keep every one.
     *
     * <p><strong>Undecorated, and that is the whole point.</strong> An implementation must not prefix a
     * timestamp, a severity or a logger name, must not trim, wrap or re-encode, and must not reorder.
     * Trailing spaces are significant and a record image is exactly {@link #RECORD_LENGTH} characters of
     * it. A parity case compares the emitted sequence line for line against what the COBOL writes, so any
     * decoration fails every case while the translation underneath is correct.
     */
    @FunctionalInterface
    public interface SysoutSink {

        /**
         * Accepts one line of output.
         *
         * @param line the line exactly as {@code DISPLAY} writes it, with no line terminator of its own
         *             and no decoration of any kind; never {@code null}
         */
        void write(String line);
    }

    /**
     * A {@link SysoutSink} over a {@link PrintStream}.
     *
     * <p>A named type rather than a lambda, so the one place this program's output reaches a stream is
     * visible and directly testable. Order is preserved because a print stream preserves it.
     *
     * @param stream the stream to write to, one line per call; never {@code null}
     */
    public record PrintStreamSysoutSink(PrintStream stream) implements SysoutSink {

        /**
         * Rejects a missing stream.
         *
         * @throws NullPointerException if {@code stream} is {@code null}
         */
        public PrintStreamSysoutSink {
            Objects.requireNonNull(stream, "A print stream is required to emit SYSOUT lines");
        }

        /**
         * Writes the line and terminates it, which is what one {@code DISPLAY} produces.
         *
         * @param line the line; never {@code null}
         * @throws NullPointerException if {@code line} is {@code null}
         */
        @Override
        public void write(String line) {
            Objects.requireNonNull(line, "A DISPLAY never writes an absent line");
            stream.println(line);
        }
    }

    /**
     * The {@code //SYSOUT DD SYSOUT=*} equivalent: a sink over the standard output stream.
     *
     * <p>The stream is handed to the adapter as a value rather than reached statically at each emission
     * point, so every {@code DISPLAY} in a run goes through one seam and a test can replace all of them
     * at once. This is also why the module's logger is not the destination: it is for diagnostics about
     * the run, not for the run's own output.
     *
     * @return a sink over the standard output stream; never {@code null}
     */
    public static SysoutSink standardOutputSysoutSink() {
        return new PrintStreamSysoutSink(System.out);
    }

    // =================================================================================================
    // WORKING-STORAGE - app/cbl/CBCUS01C.cbl:L46-L67, fresh for every execution.
    // =================================================================================================

    /**
     * The program's {@code WORKING-STORAGE}, for exactly one execution.
     *
     * <p>Package-private so a test can drive the register and the flag directly - including into the
     * states a real run cannot reach, which is how the vacuous guard at {@code L75} and both sides of
     * both {@code 88}-levels are covered.
     *
     * <p><strong>Why this is not a set of fields on the service.</strong> A {@code @Service} is a
     * singleton. {@code END-OF-FILE} and {@code APPL-RESULT} describe the progress of one run through one
     * file, so putting them on the bean would let two concurrent runs overwrite each other's flag - one
     * would stop early and the other would loop past end of file - and would make a test's outcome depend
     * on which tests ran before it. Practice <strong>B9</strong> and gate <strong>G53</strong> forbid it,
     * and the reason they forbid it is that it would be wrong.
     *
     * <p>The three items {@code CBCUS01C} declares and this class does not need are accounted for rather
     * than forgotten. {@code CUSTFILE-STATUS} ({@code L46-L48}) is the value the repository returns from
     * each operation, so it is carried as a method-local {@code status} at each site instead of being
     * stored. {@code TWO-BYTES-BINARY} / {@code TWO-BYTES-ALPHA} ({@code L53-L56}) and
     * {@code IO-STATUS-04} ({@code L57-L59}) exist only to compose the rendered status line, and that
     * composition belongs to {@link FileStatus#toDisplayLine(String)}. {@code ABCODE} and {@code TIMING}
     * ({@code L66-L67}) are set by {@code Z-ABEND-PROGRAM} and are carried by {@link AbendException}.
     */
    static final class WorkingStorage {

        /**
         * {@code APPL-RESULT PIC S9(9) COMP} - the status register, {@code app/cbl/CBCUS01C.cbl:L61}.
         *
         * <p>An {@code int}: nine digits with a sign fits comfortably, and {@code COMP} is a binary
         * integer with no scale. The declaration carries no {@code VALUE}, so COBOL leaves it
         * uninitialised and every paragraph seeds it before testing it - which Java's default of zero
         * happens to agree with, but no path here relies on that.
         */
        private int applResult;

        /**
         * {@code END-OF-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBCUS01C.cbl:L65}.
         *
         * <p>Initialised to {@link #END_OF_FILE_NO} because the declaration does. A one-character
         * {@code String} rather than a {@code boolean}: the loop tests {@code = 'Y'} and the guards test
         * {@code = 'N'}, and collapsing the two would erase the distinction.
         */
        private String endOfFile = END_OF_FILE_NO;

        /**
         * {@code IO-STATUS} - {@code app/cbl/CBCUS01C.cbl:L50-L52}, the two-byte copy of the file status
         * that {@code Z-DISPLAY-IO-STATUS} renders.
         *
         * <p>A separate item from the file status in the source, and separate here, because the MOVE at
         * {@code L111}, {@code L130} and {@code L148} is a step of the fatal arm that a reader of the
         * paragraph can see. It holds nothing until one of those MOVEs runs.
         */
        private String ioStatus;

        /**
         * Creates a carrier in the state {@code WORKING-STORAGE} declares.
         *
         * <p>Declared explicitly rather than left to the compiler's default so that it can be documented:
         * the flag starts at {@link #END_OF_FILE_NO} because {@code L65} gives it that {@code VALUE}, and
         * the register and {@code IO-STATUS} start unset because their declarations carry no
         * {@code VALUE} clause and every paragraph writes them before reading them. One execution creates
         * exactly one of these.
         */
        WorkingStorage() {
            // The three items carry their declared initial state. Intentionally nothing else to do.
        }

        /**
         * {@code IF APPL-AOK} - {@code app/cbl/CBCUS01C.cbl:L104}, {@code L126} and {@code L144}.
         *
         * @return {@code true} when the register holds {@link #APPL_AOK}
         */
        boolean applAok() {
            return isApplAok(applResult);
        }

        /**
         * {@code IF APPL-EOF} - {@code app/cbl/CBCUS01C.cbl:L107}.
         *
         * @return {@code true} when the register holds {@link #APPL_EOF}
         */
        boolean applEof() {
            return isApplEof(applResult);
        }

        /**
         * {@code MOVE <n> TO APPL-RESULT} - {@code app/cbl/CBCUS01C.cbl:L95}, {@code L99}, {@code L101},
         * {@code L119}, {@code L122} and {@code L124}.
         *
         * @param value the value to move
         */
        void moveToApplResult(int value) {
            this.applResult = value;
        }

        /**
         * {@code ADD <n> TO ZERO GIVING APPL-RESULT} - {@code app/cbl/CBCUS01C.cbl:L137} and
         * {@code L142}.
         *
         * <p>Arithmetically a move, and kept as its own method because the close paragraph writes it as
         * an {@code ADD} while the open paragraph writes the same intent as a {@code MOVE}. One method per
         * spelling keeps the transcription one-for-one, which is what makes the two paragraphs diffable
         * against their source.
         *
         * @param addend the value added to zero
         */
        void addToZeroGivingApplResult(int addend) {
            this.applResult = 0 + addend;
        }

        /**
         * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} - {@code app/cbl/CBCUS01C.cbl:L140}.
         *
         * <p>The close paragraph's way of writing zero: subtracting a value from itself. Transcribed as
         * the subtraction rather than as an assignment of zero, for the same reason as above.
         */
        void subtractApplResultFromApplResult() {
            this.applResult = this.applResult - this.applResult;
        }

        /**
         * The register's current value, which is the return code an abend carries.
         *
         * @return {@code APPL-RESULT}
         */
        int applResult() {
            return applResult;
        }

        /**
         * {@code UNTIL END-OF-FILE = 'Y'} - the loop test at {@code app/cbl/CBCUS01C.cbl:L74}.
         *
         * @return {@code true} when the flag holds {@link #END_OF_FILE_YES}
         */
        boolean endOfFileIsYes() {
            return END_OF_FILE_YES.equals(endOfFile);
        }

        /**
         * {@code IF END-OF-FILE = 'N'} - the guard test at {@code app/cbl/CBCUS01C.cbl:L75} and
         * {@code L77}.
         *
         * <p>Not the negation of {@link #endOfFileIsYes()}. A flag holding neither {@code 'Y'} nor
         * {@code 'N'} would satisfy the loop and fail this guard, and the source's two tests are kept
         * distinct so that remains true of the translation.
         *
         * @return {@code true} when the flag holds {@link #END_OF_FILE_NO}
         */
        boolean endOfFileIsNo() {
            return END_OF_FILE_NO.equals(endOfFile);
        }

        /**
         * {@code MOVE 'Y' TO END-OF-FILE} - {@code app/cbl/CBCUS01C.cbl:L108}.
         *
         * @param value the value to move; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        void moveEndOfFile(String value) {
            this.endOfFile = Objects.requireNonNull(value, "END-OF-FILE is PIC X(01): it always holds a "
                    + "character, and a COBOL alphanumeric item has no null state");
        }

        /**
         * The flag's current value, for diagnostics.
         *
         * @return {@code END-OF-FILE}; never {@code null}
         */
        String endOfFileFlag() {
            return endOfFile;
        }

        /**
         * {@code MOVE CUSTFILE-STATUS TO IO-STATUS} - {@code app/cbl/CBCUS01C.cbl:L111}, {@code L130}
         * and {@code L148}.
         *
         * @param status the two-character file status; must not be {@code null}
         * @throws NullPointerException if {@code status} is {@code null}
         */
        void moveToIoStatus(String status) {
            this.ioStatus = Objects.requireNonNull(status, "A file status is two characters and is never "
                    + "null; the repository reports one for every operation");
        }

        /**
         * The status {@code Z-DISPLAY-IO-STATUS} renders.
         *
         * @return {@code IO-STATUS}
         * @throws IllegalStateException if no status has been moved into it, which would mean the
         *                               renderer was reached without the {@code MOVE} that precedes it in
         *                               every one of the three fatal arms
         */
        String ioStatus() {
            if (ioStatus == null) {
                throw new IllegalStateException("Z-DISPLAY-IO-STATUS was reached with nothing in "
                        + "IO-STATUS. All three fatal arms MOVE CUSTFILE-STATUS TO IO-STATUS first - "
                        + "CBCUS01C.cbl:L111, L130 and L148 - so reaching the renderer without one is a "
                        + "defect in the translation, not a file status.");
            }
            return ioStatus;
        }
    }
}
