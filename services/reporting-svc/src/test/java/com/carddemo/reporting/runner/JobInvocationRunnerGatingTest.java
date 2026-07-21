package com.carddemo.reporting.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.carddemo.reporting.jobs.DailyPostingJobHandler;
import com.carddemo.reporting.jobs.InterestCalculationJobHandler;
import com.carddemo.reporting.jobs.ReportJobHandler;
import com.carddemo.reporting.jobs.StatementJobHandler;
import com.carddemo.reporting.jobs.TransactionReportJobHandler;

/**
 * Verifies the {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty} gate
 * on {@link JobInvocationRunner} (finding P4-m04).
 *
 * <p>The runner must be a real, wired invocation mechanism <em>only when explicitly enabled</em>:
 * it is registered when {@code carddemo.reporting.invoke-jobs-on-startup=true} and is completely
 * absent by default, so a plain start (and the build-green context-load smoke test) has no side
 * effects and reporting-svc keeps its non-serving, health-exempt posture.</p>
 *
 * <p>Uses {@link ApplicationContextRunner} (a lightweight, non-Boot context) so the condition is
 * evaluated without launching a full application; {@code ApplicationRunner} callbacks are not fired
 * by this container, so the test asserts bean registration only — actual invocation is covered by
 * {@link JobInvocationRunnerTest}.</p>
 */
class JobInvocationRunnerGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HandlersConfig.class, RunnerImportConfig.class);

    @Test
    void runnerAbsentByDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean(JobInvocationRunner.class));
    }

    @Test
    void runnerRegisteredWhenFlagEnabled() {
        contextRunner
                .withPropertyValues("carddemo.reporting.invoke-jobs-on-startup=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(JobInvocationRunner.class));
    }

    /**
     * Provides the five job-stub handler beans the runner depends on.
     */
    @Configuration
    static class HandlersConfig {
        @Bean
        ReportJobHandler reportJobHandler() {
            return new ReportJobHandler();
        }

        @Bean
        StatementJobHandler statementJobHandler() {
            return new StatementJobHandler();
        }

        @Bean
        DailyPostingJobHandler dailyPostingJobHandler() {
            return new DailyPostingJobHandler();
        }

        @Bean
        InterestCalculationJobHandler interestCalculationJobHandler() {
            return new InterestCalculationJobHandler();
        }

        @Bean
        TransactionReportJobHandler transactionReportJobHandler() {
            return new TransactionReportJobHandler();
        }
    }

    /**
     * Imports {@link JobInvocationRunner} as a bean candidate so its {@code @ConditionalOnProperty}
     * is evaluated by the context (an {@code @Import} of a {@code @Component} honors conditions,
     * unlike a direct {@code withBean} registration).
     */
    @Configuration
    @Import(JobInvocationRunner.class)
    static class RunnerImportConfig {
    }
}
