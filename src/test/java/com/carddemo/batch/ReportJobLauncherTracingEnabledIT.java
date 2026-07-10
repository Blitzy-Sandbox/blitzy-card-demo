package com.carddemo.batch;

import org.springframework.test.context.TestPropertySource;

/**
 * Cross-finding regression guard: re-runs the entire {@link ReportJobLauncherIT} F-1 boundary flow
 * (producer&nbsp;&rarr;&nbsp;SQS FIFO&nbsp;&rarr;&nbsp;consumer&nbsp;&rarr;&nbsp;Spring Batch&nbsp;&rarr;&nbsp;S3)
 * with distributed tracing <strong>enabled</strong>, so the F-2 AWS-SDK OpenTelemetry instrumentation
 * cannot silently regress the F-1 SQS report contract.
 *
 * <h2>Why this test exists</h2>
 * <p>The F-2 fix ({@code config/AwsTracingConfig}) attaches an OpenTelemetry execution interceptor to the
 * auto-configured {@code SqsAsyncClient}. That interceptor injects W3C trace-context as SQS <em>message
 * attributes</em> &mdash; it must not alter the JSON message <em>body</em> that the consumer deserializes,
 * nor perturb the FIFO content-based deduplication (which hashes the body, not the attributes). The base
 * {@link ReportJobLauncherIT} runs with the {@code test} profile's tracing <em>disabled</em>, so the
 * interceptor is inactive there. This subclass re-enables tracing via {@link TestPropertySource} so the
 * interceptor is wired onto the very {@code SqsAsyncClient} the F-1 {@code SqsTemplate} producer and the
 * {@code @SqsListener} consumer both use, then relies on the inherited assertions (a single
 * {@code COMPLETED} execution carrying the propagated correlation id and the byte-exact 133-character
 * {@code tranrept.dat} object in S3) to prove the contract still holds end-to-end.</p>
 *
 * <p>OTLP export stays disabled (inherited from {@code application-test.yml}), so spans are created to
 * exercise the interceptor but no collector is contacted &mdash; fully hermetic and zero-live-AWS
 * (AAP&nbsp;&sect;0.7.7). The singleton PostgreSQL&nbsp;16 + LocalStack containers, the queue/bucket
 * provisioning, and the teardown are all inherited unchanged. Source COBOL/JCL is referenced read-only at
 * commit SHA {@code 27d6c6f}; rationale lives in {@code docs/decision-log.md} (Explainability rule).</p>
 */
@TestPropertySource(properties = {
        // Flip tracing on for this context only (the test profile disables it); OTLP export stays off,
        // so the AWS-SDK interceptor becomes active without contacting any collector.
        "management.tracing.enabled=true",
        "management.tracing.sampling.probability=1.0"
})
class ReportJobLauncherTracingEnabledIT extends ReportJobLauncherIT {
    // Intentionally empty: the inherited @Test (reportRequestMessageProducesReportInS3) is re-executed
    // under this tracing-enabled context. No behaviour is overridden.
}
