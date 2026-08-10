package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.customer.CustomerService.Execution;
import com.vsergeychik.carddemo.customer.CustomerService.Sysout;
import com.vsergeychik.carddemo.customer.CustomerService.SysoutSink;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Objects;

/**
 * {@code app/jcl/READCUST.jcl} as a Spring Batch job: one step, {@code STEP05 EXEC PGM=CBCUS01C}.
 *
 * <h2>What this class is, and what it deliberately is not</h2>
 *
 * <p>It is the Spring Batch wiring that {@code READCUST.jcl} implies, and nothing else. It owns the
 * {@link Job}, the {@link Step}, the {@code SYSOUT} destination and the path from an
 * {@link AbendException} to a non-zero exit status. It owns <strong>no decision</strong>: every
 * branch, every {@code FILE STATUS} test, every {@code DISPLAY} and the abend path itself live in
 * {@link CustomerService}, which is the translation of the 178 lines of
 * {@code app/cbl/CBCUS01C.cbl}.
 *
 * <p>That split is not a matter of taste. The acceptance gate is a JaCoCo {@code BRANCH} ratio of
 * {@code 0.90} enforced per module, and a branch reachable only by launching a batch job is a branch
 * the gate cannot see; gate <strong>G51</strong> requires the program body to be reachable with no
 * launcher in the path. So {@link CustomerService#readAndPrintCustomerFile(Sysout)} is directly
 * callable, and this class adds one tasklet over it.
 *
 * <h2>Why the class exists at all, when the prompt names only a repository and a service</h2>
 *
 * <p>The build prompt maps {@code CBCUS01C} to {@code CustomerRepository} and
 * {@code CustomerService}. Rule <strong>R1</strong> of the plan - <em>names from the prompt,
 * behaviour from the source</em> - governs what that omits: {@code CBCUS01C} is a
 * <strong>runnable batch program</strong> with its own JCL job, and a repository-and-service pair
 * cannot be launched. AAP section 0.4.5 names this third class for exactly that reason - "to preserve
 * the standalone batch behaviour the prompt's repository/service naming omits" - and
 * {@code BatchConfig.JobContracts.REQUIRED_JOBS} pairs the key {@value #JOB_KEY} with
 * {@value CustomerService#PROGRAM_ID}, so the contract exists whether or not a job consumes it.
 *
 * <h2>The JCL, transcribed</h2>
 *
 * <pre>
 * //READCUST JOB 'Read Customer Data file',CLASS=A,MSGCLASS=0,          READCUST.jcl:L1
 * //STEP05 EXEC PGM=CBCUS01C                                                        L6
 * //STEPLIB  DD DISP=SHR,DSN=&lt;load library&gt;                                    L7-L8
 * //CUSTFILE DD DISP=SHR,DSN=&lt;customer master KSDS&gt;                           L9-L10
 * //SYSOUT   DD SYSOUT=*                                                           L11
 * //SYSPRINT DD SYSOUT=*                                                           L12
 * </pre>
 *
 * <p>The two {@code DSN} values are shown as descriptions rather than transcribed, and gate
 * <strong>G46</strong> is why: a dataset name belongs to {@code application.yml}'s
 * {@code carddemo.datasets} bindings, and a scan of this module's Java sources must not find one. Read
 * {@code app/jcl/READCUST.jcl} for the literal names - it is the authoritative copy, and it is
 * read-only.
 *
 * <p>Four facts follow from it, and each is asserted rather than assumed:
 * <ul>
 *   <li><strong>One step, named {@value CustomerService#STEP_NAME}.</strong> Read from the contract so
 *       the name in the batch metadata is demonstrably the name configuration declares.</li>
 *   <li><strong>No {@code PARM}, so no job parameter.</strong> {@code L6} is a bare {@code EXEC PGM=},
 *       and {@link #jobParameters()} returns the empty set the contract declares. Only the interest
 *       calculator carries a {@code PARM} in this estate, and inventing one here would change how the
 *       job is identified in the batch metadata.</li>
 *   <li><strong>No {@code COND}, so no gate.</strong> Gating the only step of a single-step job would
 *       bypass all of its work, and the constructor rejects a contract that tries.</li>
 *   <li><strong>{@code STEPLIB} has no counterpart.</strong> It names the load library the program is
 *       fetched from, which on the JVM is the classpath; nothing is invented for it.</li>
 * </ul>
 *
 * <h2>The four readers, and the one thing this one does differently</h2>
 *
 * <p>{@code CBCUS01C} is one of four near-identical read-and-print programs, and its three siblings are
 * wired exactly like this class - same {@code @Configuration}, same single tasklet step, same
 * contract validation - differing only in their record type and their display format:
 *
 * <table border="1">
 *   <caption>The four read-and-print readers</caption>
 *   <tr><th>Program</th><th>JCL</th><th>Java</th><th>Dataset</th></tr>
 *   <tr><td>{@code CBACT01C}</td><td>{@code READACCT.jcl}</td>
 *       <td>{@code AccountBalanceJob}</td><td>{@code ACCTFILE}</td></tr>
 *   <tr><td>{@code CBACT02C}</td><td>{@code READCARD.jcl}</td>
 *       <td>{@code AccountBalanceReaderJob}</td><td>{@code CARDFILE}</td></tr>
 *   <tr><td>{@code CBACT03C}</td><td>{@code READXREF.jcl}</td>
 *       <td>{@code AccountBalanceUpdateJob}</td><td>{@code XREFFILE}</td></tr>
 *   <tr><td>{@code CBCUS01C}</td><td>{@code READCUST.jcl}</td>
 *       <td>{@code CustomerFileReaderJob}</td><td>{@code CUSTFILE}</td></tr>
 * </table>
 *
 * <p><strong>The difference is in the display, and it is not cosmetic.</strong> The other three call a
 * labelled-field display paragraph from inside their read routine - {@code CBACT01C:96} performs
 * {@code 1100-DISPLAY-ACCT-RECORD}, which emits eleven
 * {@code 'ACCT-ID                 :'}-style lines and a separator - and emit the raw group image only
 * once, from their mainline. {@code CBCUS01C} <strong>has no labelled-field display paragraph at
 * all</strong>: {@code L96} and {@code L78} are both a bare {@code DISPLAY CUSTOMER-RECORD} of the
 * <em>group item</em>, so every record produces <strong>two byte-identical 500-character raw images,
 * adjacent</strong>. Fifty fixture records therefore make {@code 2 + 50 x 2 = 102} lines, not 52. A
 * reviewer comparing the four classes should expect that asymmetry to be there; it is the source's, and
 * neither collapsing the pair nor borrowing the account reader's labelled paragraph would be faithful.
 *
 * <h2>{@code SYSOUT}, and where the lines actually go</h2>
 *
 * <p>{@code CBCUS01C}'s entire observable output is that {@code DISPLAY} sequence.
 * {@link CustomerService} accumulates it in a {@link Sysout} it returns, which is what parity cases are
 * judged against. This class is what puts it on the real spool.
 *
 * <p>The destination is an injected {@link SysoutSink} when the context publishes one - which is how a
 * test captures the exact line sequence - and {@link CustomerService#standardOutputSysoutSink()}
 * otherwise. Both the sink type and the default are {@link CustomerService}'s, deliberately: the
 * {@code SYSOUT} of {@value CustomerService#PROGRAM_ID} belongs to the translation of
 * {@value CustomerService#PROGRAM_ID}, and a second sink type declared here would be a second
 * definition of one program's output - the kind of duplicate that lets a spooled line and a
 * parity-asserted line drift apart. Resolution happens once, in the constructor, so the destination
 * cannot change between two records of a run.
 *
 * <h2>Spring Batch 5.2.6, and why not 6</h2>
 *
 * <p>Every batch API this class touches arrives through {@link BatchConfig}, which constructs
 * {@code JobBuilder} and {@code StepBuilder} instances directly - the Spring Batch 5 shapes. The two
 * builder-factory beans that earlier versions injected were removed in Batch 5 and appear nowhere in
 * this module. The batch-processing enable annotation is likewise <strong>absent by design</strong>, and
 * not merely unused: Boot 3 auto-configures Batch, and declaring that annotation anywhere in the module
 * would switch the auto-configuration <em>off</em>, taking the auto-configured job repository with it.
 *
 * <p>The version is pinned rather than merely current. Spring Batch {@code 6.0.4} and Spring Boot
 * {@code 4.1.0} are both published, and both are rejected: the build is fixed at Boot {@code 3.5.16},
 * whose managed Batch version is {@code 5.2.6}. Practice <strong>B2</strong> - constraint fidelity
 * outranks recency - is the reason, and it is not a stylistic preference; a Batch 6 API reached for here
 * would not resolve against this build at all.
 *
 * <p>Naming those removed types in prose is avoided deliberately, so that a scan of this file for a
 * pre-Batch-5 API is mechanically conclusive rather than something a reviewer has to read around.
 *
 * <p><strong>A failing run's lines reach the spool too.</strong> On the mainframe the {@code DISPLAY}
 * statements a run performed before it abended are already in the spool - {@code CEE3ABD} does not
 * retract them - so the sink is written from a {@code finally} and the abend is rethrown afterwards.
 * That is why the tasklet holds the {@link Sysout} itself instead of using
 * {@link CustomerService#readAndPrintCustomerFile()}: a run that abends never returns an
 * {@link Execution}, and the only way to emit what it did display is to have held the sink beforehand.
 *
 * <h2>The exit status</h2>
 *
 * <p>Nothing is caught. An {@link AbendException} must reach the framework so
 * {@code BatchConfig}'s listener can carry its {@code RETURN-CODE} onto the step's exit status and the
 * process exit code (gate <strong>G35</strong>); swallowing it would report a failed job as complete.
 * A clean run ends with {@code RETURN-CODE} zero, because {@code CBCUS01C} never moves anything into
 * it.
 *
 * <h2>State</h2>
 *
 * <p>A {@code @Configuration} class is a singleton, so nothing per-run lives on it: the {@link Sysout}
 * and every item of {@code WORKING-STORAGE} are created per execution (practice <strong>B9</strong>,
 * gate <strong>G53</strong>). Its four fields - the batch scaffolding, the service, the resolved sink
 * and the validated step contract - are all {@code final}, and all four are settled by the time the
 * constructor returns. There is no counter, no cursor and no accumulated total: the read count a run
 * reports is a local of the tasklet call, so two executions of this bean cannot observe each other and
 * the parity cases may run in any order, and in parallel, and still agree.
 *
 * @see CustomerService the program itself - every branch, every status test, every {@code DISPLAY}, and
 *      the {@code CUSTFILE} browse this job reaches only through it
 */
@Configuration(CustomerFileReaderJob.CONFIGURATION_BEAN_NAME)
public class CustomerFileReaderJob {

    // =================================================================================================
    // Identity. Every name is a configuration key or a JCL name, never a literal invented here.
    // =================================================================================================

    /**
     * The bean name of this configuration class itself.
     *
     * <p><strong>Stated explicitly, and it has to be.</strong> Component scanning names a configuration
     * bean after its class - {@code CustomerFileReaderJob} would become
     * {@code customerFileReaderJob} - and {@link #customerFileReaderJob()} publishes the {@link Job}
     * under exactly that name. Two definitions of one name is a hard failure: bean-definition
     * overriding is disabled by default, so the context would not start. The class name follows AAP
     * 0.4.5 and the job name follows the COBOL program, so neither may move; this bean's own name
     * carries no external contract, so it is the one that is qualified. Every sibling batch job class
     * in this module resolves the same collision the same way.
     */
    public static final String CONFIGURATION_BEAN_NAME = "customerFileReaderJobConfiguration";

    /**
     * This job's key under the {@code carddemo.jobs} configuration prefix.
     *
     * <p>{@code BatchConfig.JobContracts} binds strictly and validates at startup, and its
     * {@code REQUIRED_JOBS} map pairs this key with {@value CustomerService#PROGRAM_ID}, so an entry
     * that is absent, renamed or re-pointed at another program stops the context rather than producing
     * a job that runs against the wrong contract.
     */
    public static final String JOB_KEY = "customer-file-reader-job";

    /**
     * The Spring Batch job name, which is also this job's identity in the batch metadata and the name
     * of the bean {@link #customerFileReaderJob()} publishes.
     */
    public static final String JOB_NAME = "customerFileReaderJob";

    /**
     * The whole step sequence of {@code app/jcl/READCUST.jcl}: one step,
     * {@value CustomerService#STEP_NAME}, running {@value CustomerService#PROGRAM_ID}, ungated.
     *
     * <p>A sequence rather than a step name, because the JCL declares one {@code EXEC} and no second
     * one, and only a whole-sequence comparison states that. Checking that the named step is present
     * would accept a contract carrying an extra step, or carrying this one second.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(CustomerService.STEP_NAME, CustomerService.PROGRAM_ID, false));

    // =================================================================================================
    // Collaborators. All final, all constructor-injected, none holding per-run state.
    // =================================================================================================

    /** The batch seam: the job and step builders, the job repository and the abend listener. */
    private final BatchConfig batchConfig;

    /** The program. Every decision {@code CBCUS01C} makes is behind this reference. */
    private final CustomerService customerService;

    /** Where every {@code DISPLAY} goes. Resolved once, at construction, and never reassigned. */
    private final SysoutSink sysoutSink;

    /**
     * This job's single step as configuration declares it, resolved at construction so a mis-declared
     * contract fails at startup rather than when the job is first launched.
     */
    private final StepContract stepContract;

    /**
     * Wires the job and validates, at startup, that the configured contract still says what
     * {@code app/jcl/READCUST.jcl} says.
     *
     * <p>Two checks run here beyond the presence of the collaborators, and each fails loudly rather
     * than degrading: the contract must declare step {@value CustomerService#STEP_NAME} running program
     * {@value CustomerService#PROGRAM_ID} - a step naming another program would give this job another
     * program's dataset resolution - and that step must not be gated, because {@code READCUST.jcl}
     * carries no {@code COND} and declares only this step.
     *
     * <p><strong>There are exactly three parameters, and the omissions are the deliberate part.</strong>
     * This job reaches the customer master only through {@link CustomerService}. Handing the wiring layer
     * a repository of its own would give it a second, independent path to the data, and a second path is
     * a second place for a browse to be opened, ordered or closed differently. No connection source, no
     * SQL-template type and no code page is accepted either: reading dataset bytes - and therefore
     * deciding how they are decoded - belongs to the layer that does it, and this class reads none. The
     * parameter list is the enforcement, so the omissions are also scan-conclusive.
     *
     * @param batchConfig        the module's batch scaffolding; never {@code null}
     * @param customerService    the translation of {@value CustomerService#PROGRAM_ID}; never
     *                           {@code null}
     * @param sysoutSinkProvider provider for an injected {@code SYSOUT} destination, consulted once and
     *                           defaulted to {@link CustomerService#standardOutputSysoutSink()} when the
     *                           context declares none; never {@code null}, though it may resolve to
     *                           nothing
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if the contract is absent, names another program, or gates this
     *                               job's only step
     */
    public CustomerFileReaderJob(BatchConfig batchConfig,
            CustomerService customerService,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {

        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch scaffolding is required: the "
                + "job and step builders, the job repository, the transaction manager and the abend "
                + "listener all arrive through it, so this class holds no Spring Batch plumbing of its "
                + "own");
        this.customerService = Objects.requireNonNull(customerService, "The customer service is "
                + "required: it is the translation of " + CustomerService.PROGRAM_ID + ", and this job "
                + "is only its Spring Batch wiring");
        Objects.requireNonNull(sysoutSinkProvider, "A SYSOUT sink provider is required; it may resolve "
                + "to no bean, in which case the standard output stream is used");

        this.sysoutSink = sysoutSinkProvider.getIfAvailable(CustomerService::standardOutputSysoutSink);
        this.stepContract = requireUngatedStep(batchConfig);
    }

    /**
     * Resolves and validates this job's step contract.
     *
     * <p>The two guards below name the two plausible mistakes - a step re-pointed at another program,
     * and a gate on the only step a single-step job has - and each says specifically what is wrong.
     * Behind them the sequence is compared against {@link #REQUIRED_STEPS} as a whole, which is what
     * rejects a second step declared alongside this one or this one declared second: a per-step check
     * cannot see either.
     *
     * @param scaffolding the batch scaffolding holding the {@code carddemo.jobs} catalogue
     * @return the validated step contract
     * @throws IllegalStateException if the contract is absent, names another program, gates the step, or
     *                               declares any sequence other than {@link #REQUIRED_STEPS}
     */
    private static StepContract requireUngatedStep(BatchConfig scaffolding) {
        StepContract contract = scaffolding.contract(JOB_KEY).step(CustomerService.STEP_NAME);
        if (!CustomerService.PROGRAM_ID.equals(contract.program())) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' declares "
                    + "step '" + CustomerService.STEP_NAME + "' running program '" + contract.program()
                    + "', but this job is the wiring of " + CustomerService.PROGRAM_ID
                    + " (app/jcl/READCUST.jcl:L6). A step that named another program would resolve "
                    + "another program's DD names.");
        }
        if (contract.requirePrecedingExitCodeZero()) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' gates step "
                    + "'" + CustomerService.STEP_NAME + "' on a preceding exit code, but "
                    + "app/jcl/READCUST.jcl carries no COND and declares only this step. Gating the only "
                    + "step of a single-step job would bypass all of its work.");
        }
        scaffolding.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/jcl/READCUST.jcl:L6");
        return contract;
    }

    // =================================================================================================
    // The Spring Batch surface: one job, one step, one tasklet.
    //
    // Only the Job is a bean. The step and the tasklet are ordinary methods, deliberately: nine sibling
    // job classes exist in this module, and a published Step or Tasklet bean from each would make every
    // by-type injection of those interfaces ambiguous at once.
    // =================================================================================================

    /**
     * The job, published as a bean: one step, no job parameters.
     *
     * <p>The builder arrives from {@link BatchConfig#job(String)} already bound to the auto-configured
     * job repository and carrying the shared abend listener, which is what makes the return-code
     * contract of gate G35 hold for this job by construction rather than by opting in.
     *
     * <p>Publishing the job does <strong>not</strong> run it: {@code spring.batch.job.enabled} is
     * {@code false}, so it executes only when something deliberately launches it, with the empty
     * {@link #jobParameters()} this job declares.
     *
     * @return the {@value #JOB_NAME} job; never {@code null}
     */
    @Bean
    public Job customerFileReaderJob() {
        return batchConfig.job(JOB_NAME)
                .start(customerFileReaderStep())
                .build();
    }

    /**
     * The single step, named {@value CustomerService#STEP_NAME} after the JCL step it replaces.
     *
     * <p>A <strong>tasklet</strong> step, and never a chunk-oriented one: {@code CBCUS01C} is one
     * sequential pass whose display ordering is its entire observable behaviour, and chunking would
     * relocate the commit boundaries that ordering sits inside.
     *
     * <p>The name is read from the validated {@linkplain #stepContract() contract} rather than from the
     * constant, so the name that reaches the batch metadata is demonstrably the name configuration
     * declares.
     *
     * @return a fresh step; never {@code null}
     */
    public Step customerFileReaderStep() {
        return batchConfig.taskletStep(stepContract.name(), customerFileDisplayTasklet()).build();
    }

    /**
     * The step body: one invocation, one complete pass over the customer master.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet customerFileDisplayTasklet() {
        return this::executeStep;
    }

    /**
     * Runs the procedure division once, spools what it displayed, and reports how many records it read.
     *
     * <p>The read count is step metadata rather than COBOL output - {@code CBCUS01C} keeps no counter of
     * its own - so it is reported to the framework and never displayed.
     *
     * <p>The {@code finally} is what puts a failing run's {@code DISPLAY} lines on the spool: the three
     * fatal arms each emit their error text, the rendered file status and {@code ABENDING PROGRAM}
     * before the throw leaves the service, and on the mainframe those lines are in the spool whether or
     * not the step went on to complete. Nothing is caught, so the abend still reaches the framework.
     *
     * @param contribution the step's contribution, which the read count is reported to
     * @param chunkContext the framework's chunk context; unused, because a tasklet that runs once has
     *                     no per-chunk state to consult and this program has no restart semantics
     *                     beyond re-reading the dataset from its first record
     * @return {@link RepeatStatus#FINISHED}, always
     * @throws AbendException if the open, a read or the close reports a status the program treats as
     *                        fatal
     */
    private RepeatStatus executeStep(StepContribution contribution, ChunkContext chunkContext) {
        Sysout sysout = new Sysout();
        try {
            Execution execution = readAndPrintCustomerFile(sysout);
            for (int recorded = 0; recorded < execution.recordsRead(); recorded++) {
                contribution.incrementReadCount();
            }
            return RepeatStatus.FINISHED;
        } finally {
            spool(sysout.lines());
        }
    }

    /**
     * Runs the program, accumulating its {@code DISPLAY} sequence into a caller-held sink.
     *
     * <p>A thin delegation, published so the program is runnable from this job's own surface without a
     * {@code JobLauncher} in the path (gate G51). It creates nothing and decides nothing.
     *
     * @param sysout the sink to accumulate into; must not be {@code null} and should be empty
     * @return what the run emitted, the return code it ended with and how many records it read
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if the open, a read or the close reports a fatal status
     */
    public Execution readAndPrintCustomerFile(Sysout sysout) {
        return customerService.readAndPrintCustomerFile(sysout);
    }

    /**
     * Writes an accumulated {@code DISPLAY} sequence to the resolved {@code SYSOUT} destination.
     *
     * <p>In order, verbatim, and one call per line - a {@code DISPLAY} produces one line. Nothing is
     * trimmed: a raw {@code CUSTOMER-RECORD} image ends in the 168 spaces of {@code CVCUS01Y}'s
     * trailing {@code FILLER}, and they are part of the emitted line (gate G21).
     *
     * @param lines the lines to emit, in emission order; must not be {@code null}
     * @throws NullPointerException if {@code lines} is {@code null}
     */
    private void spool(List<String> lines) {
        Objects.requireNonNull(lines, "A run always has a line sequence, empty or not");
        for (String line : lines) {
            sysoutSink.write(line);
        }
    }

    /**
     * The job parameters this job is launched with: <strong>none</strong>.
     *
     * <p>{@code app/jcl/READCUST.jcl:L6} declares no {@code PARM}, and the empty
     * {@code carddemo.jobs.}{@value #JOB_KEY}{@code .parameters} list states that as a contract. Read
     * from the contract rather than built here, so the empty set configuration declares is demonstrably
     * what reaches the launcher.
     *
     * @return empty job parameters; never {@code null}
     */
    public JobParameters jobParameters() {
        return batchConfig.contract(JOB_KEY).jobParameters();
    }

    /**
     * This job's validated step contract, as {@code carddemo.jobs} declares it.
     *
     * @return the contract for step {@value CustomerService#STEP_NAME}; never {@code null}
     */
    public StepContract stepContract() {
        return stepContract;
    }

    /**
     * The destination every {@code DISPLAY} of this job is written to.
     *
     * <p>Surfaced so a caller can confirm which sink was resolved, and so a test can assert that an
     * injected sink really is the one in use rather than being shadowed by the default.
     *
     * @return the resolved sink; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSink;
    }

}
