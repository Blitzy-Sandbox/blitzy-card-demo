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

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

/**
 * {@code app/jcl/READCUST.jcl} as a Spring Batch job: one step, {@code STEP05 EXEC PGM=CBCUS01C}.
 *
 * <p>A clean run ends with {@code RETURN-CODE} zero, because {@code CBCUS01C} never moves anything into it.
 */
@Configuration(CustomerFileReaderJob.CONFIGURATION_BEAN_NAME)
public class CustomerFileReaderJob {
    // Every name is a configuration key or a JCL name, never a literal invented here.

    /**
     * The bean name of this configuration class itself.
     */
    public static final String CONFIGURATION_BEAN_NAME = "customerFileReaderJobConfiguration";

    public static final String JOB_KEY = "customer-file-reader-job";

    public static final String JOB_NAME = "customerFileReaderJob";

    /**
     * The whole step sequence of {@code app/jcl/READCUST.jcl}: one step,
     * {@value CustomerService#STEP_NAME}, running {@value CustomerService#PROGRAM_ID}, ungated.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(CustomerService.STEP_NAME, CustomerService.PROGRAM_ID, false));

    private final BatchConfig batchConfig;

    private final CustomerService customerService;

    private final SysoutSink sysoutSink;

    private final StepContract stepContract;

    /**
     * Wires the job and validates, at startup, that the configured contract still says what
     * {@code app/jcl/READCUST.jcl} says.
     *
     * @param batchConfig the module's batch scaffolding; never {@code null}
     * @param customerService the translation of {@value CustomerService#PROGRAM_ID}; never {@code null}
     * @param sysoutSinkProvider provider for an injected {@code SYSOUT} destination, consulted once and
     *     defaulted to the service's own {@link CustomerService#standardOutputSysoutSink()}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if the contract is absent, names another program, or gates this job's
     *     only step
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

        this.sysoutSink =
                sysoutSinkProvider.getIfAvailable(this.customerService::standardOutputSysoutSink);
        this.stepContract = requireUngatedStep(batchConfig);
    }

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

    /**
     * The job, published as a bean: one step, no job parameters.
     *
     * @return the {@link #JOB_NAME} job; never {@code null}
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

    private RepeatStatus executeStep(StepContribution contribution, ChunkContext chunkContext) {
        int recordsRead = customerService.readAndPrintCustomerFileTo(sysoutSink);

        // Step metadata, not COBOL output: CBCUS01C keeps no counter, so this is reported and never
        // displayed. One call per record, which is what READCUST.jcl's step reports as read.
        for (int recorded = 0; recorded < recordsRead; recorded++) {
            contribution.incrementReadCount();
        }
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs the program, accumulating its {@code DISPLAY} sequence into a caller-held sink.
     *
     * @param sysout the sink to accumulate into; must not be {@code null} and should be empty
     * @return what the run emitted, the return code it ended with and how many records it read
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if the open, a read or the close reports a fatal status
     */
    public Execution readAndPrintCustomerFile(Sysout sysout) {
        return customerService.readAndPrintCustomerFile(sysout);
    }

    /**
     * The job parameters this job is launched with: none.
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
     * @return the resolved sink; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSink;
    }

}
