package com.carddemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Producer-side {@link SqsTemplate} configuration for the migrated {@code CORPT00C}
 * report-submission bridge (CICS TDQ&nbsp;&rarr;&nbsp;SQS FIFO). It defines a single
 * {@link SqsTemplate} bean whose default message converter is configured to
 * <strong>omit the payload-type ({@code JavaType}) SQS message attribute</strong>, so the
 * report-request message body is transmitted as a plain JSON document with no framework
 * type header.
 *
 * <h2>Why this bean exists (defect being fixed)</h2>
 * <p>{@code service.ReportService} sends a typed {@code ReportJobMessage} record as the SQS
 * payload. The Spring Cloud AWS default {@code SqsMessagingMessageConverter} attaches, for
 * <em>every</em> non-{@code String} payload, a {@code JavaType} message attribute equal to the
 * payload's fully-qualified runtime class name (its default
 * {@code payloadTypeHeaderValueFunction} returns {@code payload.getClass().getName()}). The
 * receive side &mdash; {@code batch.ReportJobLauncher#onReportRequest(String)} &mdash; binds the
 * body to a raw {@link String} and parses it manually with the application {@link ObjectMapper};
 * when the inbound message carries a {@code JavaType} header naming a producer-internal class the
 * converter rejects the binding with a {@code MessageConversionException}, the listener never
 * launches the report job, and (absent a DLQ) the message poison-loops indefinitely.</p>
 *
 * <p>Calling {@code doNotSendPayloadTypeHeader()} on the producer's converter removes the header
 * entirely, so the wire body is exactly the JSON the consumer expects &mdash; identical to the
 * hand-crafted raw-JSON message that was independently verified to round-trip end-to-end. The
 * consumer's {@code ReportJobRequest} record has field-for-field identical component names and is
 * annotated {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so the contract is preserved and
 * remains additively evolvable (Gate&nbsp;5).</p>
 *
 * <h2>Why a dedicated bean rather than the auto-configured one</h2>
 * <p>Spring Cloud AWS 3.3.0 auto-configures its {@code sqsTemplate} bean as
 * {@code @ConditionalOnMissingBean}; declaring the bean here (same bean name, {@code sqsTemplate})
 * makes the auto-configuration back off so this converter-tuned instance is the one injected into
 * {@code ReportService}. The {@link SqsAsyncClient} (endpoint, region and credentials, all
 * LocalStack-resolved from {@code spring.cloud.aws.*}) and the application {@link ObjectMapper}
 * (records + JSR-310 modules registered by Spring Boot) are both consumed from the container, so
 * no endpoint, region, ARN, or credential is hardcoded here (zero live AWS). The
 * {@code @SqsListener} receive path is unaffected: it uses the listener-container factory, not
 * this template, which is a send-only facade.</p>
 *
 * <p>This bean is kept out of {@code AwsConfig} deliberately: {@code AwsConfig} documents (and its
 * unit test asserts) that it declares no client/template beans of its own. The rationale,
 * alternatives (String-payload send; a {@code @Qualifier}-scoped template) and residual risk for
 * this decision are recorded in {@code docs/decision-log.md} (Explainability rule), not inline.
 * Source traceability anchor: commit SHA {@code 27d6c6f}.</p>
 *
 * @see io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter#doNotSendPayloadTypeHeader()
 */
@Configuration
public class SqsTemplateConfig {

    /** SLF4J logger; structured JSON with the MDC {@code correlationId} is applied by logback. */
    private static final Logger log = LoggerFactory.getLogger(SqsTemplateConfig.class);

    /**
     * Builds the send-only {@link SqsTemplate} used by {@code ReportService} to enqueue report
     * requests onto {@code carddemo-report-jobs.fifo}.
     *
     * <p>The template is constructed over the auto-configured {@link SqsAsyncClient} so all
     * endpoint/region/credential resolution (LocalStack) is inherited unchanged. Its default
     * converter is configured to (a) serialize/deserialize with the application {@link ObjectMapper}
     * so the wire JSON is exactly what the consumer parses, and (b) omit the {@code JavaType}
     * payload-type header so the body is header-free plain JSON (the defect fix).</p>
     *
     * <p>Building the template performs no network I/O, so the bean is safe to create eagerly even
     * before LocalStack is reachable; the client is exercised only when a message is actually sent.</p>
     *
     * @param sqsAsyncClient the auto-configured Spring Cloud AWS async SQS client (LocalStack-backed);
     *                       never {@code null}
     * @param objectMapper   the application-wide JSON mapper (records + JSR-310 support); never
     *                       {@code null}
     * @return a send-configured {@link SqsTemplate} that omits the payload-type header
     */
    @Bean
    SqsTemplate sqsTemplate(final SqsAsyncClient sqsAsyncClient, final ObjectMapper objectMapper) {
        final SqsTemplate template = SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .configureDefaultConverter(converter -> {
                    // Serialize with the application mapper so producer JSON matches the consumer's
                    // manual objectMapper.readValue(...) exactly.
                    converter.setObjectMapper(objectMapper);
                    // Omit the JavaType payload-type message attribute so the body is plain JSON that
                    // the String-bound @SqsListener can parse (the report round-trip fix).
                    converter.doNotSendPayloadTypeHeader();
                })
                .build();
        log.info("Configured send-only SqsTemplate with payload-type header disabled "
                + "(report FIFO round-trip fix; overrides Spring Cloud AWS auto-configuration)");
        return template;
    }
}
