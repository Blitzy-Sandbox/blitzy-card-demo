package com.carddemo.integration.aws;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only configuration that prevents the application's {@code @SqsListener} containers from
 * auto-starting inside the AWS LocalStack integration-test context.
 *
 * <p>Rationale: the online-to-batch report bridge (app/cbl/CORPT00C.cbl, commit 27d6c6f, REFERENCE
 * ONLY) is re-platformed as a {@code ReportSubmissionService} publish to the FIFO queue
 * {@code carddemo-report-jobs.fifo} plus a downstream consumer — {@code TransactionReportJob}'s
 * {@code @SqsListener} on that same queue. Both are correct in production. In the full
 * {@link org.springframework.boot.test.context.SpringBootTest} context, however, the consumer
 * auto-starts and competes with {@code SqsIntegrationIT.monthlyReportSubmissionPublishesToFifoQueue},
 * which verifies the publish contract (Gate 5) by polling the very same queue: the listener drains
 * the message before the test can observe it, so the assertion sees an empty queue.</p>
 *
 * <p>Mechanism: a {@link BeanPostProcessor} customizes the auto-configured
 * {@code defaultSqsListenerContainerFactory} via {@code configure(options -> options.autoStartup(false))}.
 * The customization is registered while the factory bean is post-processed, before the
 * {@code @SqsListener} endpoints create their containers, so every container is built with
 * {@code autoStartup=false} and the listener registry never starts it. Production wiring is
 * untouched; only the test context keeps the listener idle so queue contents are deterministically
 * observable. No integration test relies on the listener consuming. Imported explicitly by
 * {@link AbstractAwsLocalStackIT}; it is not component-scanned.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
class SqsListenerAutoStartupDisabledConfig {

    /**
     * Registers a {@link BeanPostProcessor} that disables auto-startup on the auto-configured SQS
     * listener container factory. Declared {@code static} so it is instantiated early enough to
     * post-process the factory bean before any {@code @SqsListener} container is created.
     *
     * @return a post-processor that sets {@code autoStartup(false)} on the SQS listener factory
     */
    @Bean
    static BeanPostProcessor disableSqsListenerAutoStartup() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SqsMessageListenerContainerFactory<?> factory) {
                    factory.configure(options -> options.autoStartup(false));
                }
                return bean;
            }
        };
    }
}
