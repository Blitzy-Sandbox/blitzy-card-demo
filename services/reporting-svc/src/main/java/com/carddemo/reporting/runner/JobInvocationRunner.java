package com.carddemo.reporting.runner;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.carddemo.reporting.jobs.DailyPostingJobHandler;
import com.carddemo.reporting.jobs.InterestCalculationJobHandler;
import com.carddemo.reporting.jobs.ReportJobHandler;
import com.carddemo.reporting.jobs.StatementJobHandler;
import com.carddemo.reporting.jobs.TransactionReportJobHandler;
import com.carddemo.reporting.model.JobAcknowledgement;

/**
 * [DEFERRED] Real, non-host-serving invocation mechanism for the reporting job-stub handlers
 * (finding P4-m04 / AAP-38 "Complete invokable batch stubs").
 *
 * <p>reporting-svc is an async job stub: it exposes no HTTP controller and publishes no host port,
 * so before this runner existed its {@code jobs/*JobHandler} beans had no scheduler, queue,
 * controller, or command that could actually trigger them — final acceptance could not invoke
 * them. This {@link ApplicationRunner} closes that gap: when enabled it invokes every job-stub
 * entrypoint once at application startup and logs the returned typed {@link JobAcknowledgement}s,
 * providing a deterministic, host-free way to demonstrate that every job stub is invokable.</p>
 *
 * <p><strong>Gated OFF by default.</strong> The runner is only registered when
 * {@code carddemo.reporting.invoke-jobs-on-startup=true} (via
 * {@link ConditionalOnProperty}). A default (unset/false) start therefore behaves exactly as
 * before — the context loads with no side effects, the build-green context-load smoke test is
 * undisturbed, and reporting-svc remains health-exempt and non-serving. To invoke the batch stubs,
 * run the container/JAR with the flag set, e.g.:</p>
 * <pre>java -jar reporting-svc.jar --carddemo.reporting.invoke-jobs-on-startup=true</pre>
 * <p>or the equivalent environment variable
 * {@code CARDDEMO_REPORTING_INVOKE_JOBS_ON_STARTUP=true}.</p>
 *
 * <p>Provenance: [SRC: CORPT00C, CBSTM03A/B, CBTRN02C, CBACT04C, CBTRN03C | TRANSACT/ACCTDAT].
 * Invoking a stub performs NO dataset read and NO calculation — each handler returns a typed
 * placeholder acknowledgement only.</p>
 */
@Component
@ConditionalOnProperty(name = "carddemo.reporting.invoke-jobs-on-startup", havingValue = "true")
public class JobInvocationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobInvocationRunner.class);

    private final ReportJobHandler reportJobHandler;
    private final StatementJobHandler statementJobHandler;
    private final DailyPostingJobHandler dailyPostingJobHandler;
    private final InterestCalculationJobHandler interestCalculationJobHandler;
    private final TransactionReportJobHandler transactionReportJobHandler;

    /**
     * Constructor injection of every job-stub handler bean discovered by the component scan.
     *
     * @param reportJobHandler              online transaction reports stub ([SRC: CORPT00C])
     * @param statementJobHandler           account statement generation stub ([SRC: CBSTM03A/B])
     * @param dailyPostingJobHandler        daily transaction posting stub ([SRC: CBTRN02C])
     * @param interestCalculationJobHandler interest calculation stub ([SRC: CBACT04C])
     * @param transactionReportJobHandler   batch transaction report stub ([SRC: CBTRN03C])
     */
    public JobInvocationRunner(ReportJobHandler reportJobHandler,
                               StatementJobHandler statementJobHandler,
                               DailyPostingJobHandler dailyPostingJobHandler,
                               InterestCalculationJobHandler interestCalculationJobHandler,
                               TransactionReportJobHandler transactionReportJobHandler) {
        this.reportJobHandler = reportJobHandler;
        this.statementJobHandler = statementJobHandler;
        this.dailyPostingJobHandler = dailyPostingJobHandler;
        this.interestCalculationJobHandler = interestCalculationJobHandler;
        this.transactionReportJobHandler = transactionReportJobHandler;
    }

    /**
     * Invokes every reporting job-stub entrypoint once and returns the typed acknowledgements.
     *
     * <p>Extracted from {@link #run(ApplicationArguments)} so the invocation is directly unit
     * testable without bootstrapping a Spring context. Passing {@code null} to the online-submitted
     * handlers is intentional: those handlers null-guard their request and, in this deferred stub,
     * carry no parameters through — they simply return a typed acknowledgement placeholder.</p>
     *
     * @return the acknowledgements returned by every job-stub handler, in invocation order
     */
    public List<JobAcknowledgement> invokeAll() {
        List<JobAcknowledgement> acknowledgements = new ArrayList<>();
        acknowledgements.add(reportJobHandler.submitReportJob(null));
        acknowledgements.add(statementJobHandler.submitStatementJob(null));
        acknowledgements.add(dailyPostingJobHandler.submitDailyPostingJob());
        acknowledgements.add(interestCalculationJobHandler.submitInterestCalculationJob());
        acknowledgements.add(transactionReportJobHandler.submitTransactionReportJob());
        return acknowledgements;
    }

    /**
     * Spring Boot startup hook. Invoked once after the context is ready when this runner is
     * enabled; drives {@link #invokeAll()} and logs each acknowledgement.
     *
     * @param args the incoming application arguments (unused; every stub is parameterless here)
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("[DEFERRED] JobInvocationRunner enabled — invoking all reporting job stubs "
                + "(non-host-serving batch invocation).");
        List<JobAcknowledgement> acknowledgements = invokeAll();
        for (JobAcknowledgement ack : acknowledgements) {
            log.info("[DEFERRED] job stub invoked: jobType={}, status={}, jobId={}",
                    ack.getJobType(), ack.getStatus(), ack.getJobId());
        }
        log.info("[DEFERRED] JobInvocationRunner completed — {} job stub(s) invoked.",
                acknowledgements.size());
    }
}
