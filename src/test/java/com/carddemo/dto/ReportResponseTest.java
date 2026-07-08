package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.objectMapper;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static com.carddemo.dto.DtoTestSupport.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Framework-free unit test for {@link ReportResponse} &mdash; the asynchronous
 * <em>job-accepted acknowledgement</em> returned by the reports flow (legacy
 * transaction {@code CR00}) once a report request has been enqueued onto the
 * {@code carddemo-report-jobs.fifo} SQS FIFO queue that triggers the Spring
 * Batch report launch.
 *
 * <p>{@code ReportResponse} is the modern, stateless equivalent of the legacy
 * {@code CORPT00C} CICS Transient Data Queue &rarr; JES bridge acknowledgement:
 * instead of returning the rendered report, the caller receives an opaque
 * {@code jobId} plus a human-readable {@code message} (HTTP&nbsp;202
 * &quot;Accepted&quot; semantics). Its field provenance in the frozen COBOL
 * source (referenced by SHA {@code 27d6c6f}, not copied into this repository):
 * {@code reportType}, {@code startDate} and {@code endDate} echo the selection
 * and date-range fields of the {@code CORPT00} BMS map
 * ({@code MONTHLY}/{@code YEARLY}/{@code CUSTOM} and the {@code SDT*}/{@code EDT*}
 * month/day/year fields), while {@code reportName} derives from the
 * {@code REPORT-NAME-HEADER} long-name field of copybook {@code CVTRA07Y}
 * ({@code REPT-LONG-NAME PIC X(41)}).</p>
 *
 * <p>These tests pin the <em>wire contract</em> so downstream consumers can rely
 * on a stable payload. Each concern is exercised as its own {@link Nested}
 * group, mirroring the four documented acceptance phases:</p>
 * <ol>
 *   <li><strong>JSON round-trip &amp; shape</strong> &mdash; a fully populated
 *       instance survives a serialize/deserialize cycle unchanged and exposes
 *       exactly the seven mapped keys.</li>
 *   <li><strong>Status &amp; async acknowledgement</strong> &mdash; the canonical
 *       {@code "ACCEPTED"} status round-trips, and the {@code accepted()} factory
 *       stamps it.</li>
 *   <li><strong>ISO {@link LocalDate} dates</strong> &mdash; {@code startDate} and
 *       {@code endDate} serialize as ISO-8601 strings (for example
 *       {@code "2024-01-01"}), never an epoch number or a {@code [y,M,d]} array,
 *       and their record components are typed {@link LocalDate}.</li>
 *   <li><strong>Passthrough String fields</strong> &mdash; {@code jobId},
 *       {@code reportType}, {@code reportName} and {@code message} round-trip
 *       verbatim.</li>
 * </ol>
 *
 * <p>The suite uses JUnit&nbsp;5 and AssertJ only. It loads no Spring context and
 * no Testcontainers; JSON is produced and inspected through the shared,
 * production-mirroring {@link DtoTestSupport#OBJECT_MAPPER} so the assertions
 * match exactly what the running application emits (in particular
 * {@link LocalDate} pinned to ISO-8601 string output).</p>
 */
@DisplayName("ReportResponse — asynchronous report-job acknowledgement DTO")
class ReportResponseTest {

    /** Opaque acknowledgement identifier used by the caller to poll the job. */
    private static final String JOB_ID = "a1b2c3d4-0000-4a5b-8c9d-000000000001";

    /** Report selection echoed from the {@code CORPT00} map (MONTHLY flag). */
    private static final String REPORT_TYPE = "MONTHLY";

    /** Reporting-period start date; serializes to {@link #START_DATE_ISO}. */
    private static final LocalDate START_DATE = LocalDate.of(2024, 1, 1);

    /** Reporting-period end date; serializes to {@link #END_DATE_ISO}. */
    private static final LocalDate END_DATE = LocalDate.of(2024, 1, 31);

    /** ISO-8601 textual form the shared mapper must emit for {@link #START_DATE}. */
    private static final String START_DATE_ISO = "2024-01-01";

    /** ISO-8601 textual form the shared mapper must emit for {@link #END_DATE}. */
    private static final String END_DATE_ISO = "2024-01-31";

    /**
     * Human-readable report title derived from the {@code CVTRA07Y}
     * {@code REPT-LONG-NAME} header (24 characters, well within the
     * {@link ReportResponse#REPORT_NAME_MAX_LENGTH} bound of 41).
     */
    private static final String REPORT_NAME = "Daily Transaction Report";

    /** Human-readable confirmation text carrying no infrastructure detail. */
    private static final String MESSAGE = "Report request accepted for processing";

    /**
     * The seven wire keys the acknowledgement must expose, one per record
     * component. Order is irrelevant to the assertions, which compare membership.
     */
    private static final String[] EXPECTED_JSON_KEYS = {
        "jobId", "status", "reportType", "startDate", "endDate", "reportName", "message"
    };

    /**
     * Builds a fully populated acknowledgement (every component non-null) whose
     * status is the canonical {@link ReportResponse#STATUS_ACCEPTED}.
     *
     * @return a populated {@link ReportResponse}
     */
    private static ReportResponse fullyPopulated() {
        return new ReportResponse(
                JOB_ID,
                ReportResponse.STATUS_ACCEPTED,
                REPORT_TYPE,
                START_DATE,
                END_DATE,
                REPORT_NAME,
                MESSAGE);
    }

    /**
     * Extracts the top-level object keys of a JSON document, in encounter order.
     *
     * @param node the parsed JSON tree
     * @return the field names of {@code node}
     */
    private static List<String> topLevelKeys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /**
     * Locates the {@link ReportResponse} record component with the given name for
     * type-level (reflection) assertions.
     *
     * @param name the record-component name to find
     * @return the matching {@link RecordComponent}
     */
    private static RecordComponent componentNamed(String name) {
        for (RecordComponent component : ReportResponse.class.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new AssertionError("ReportResponse has no record component named '" + name + "'");
    }

    // ---------------------------------------------------------------------
    // Phase 1 — JSON round-trip & wire shape
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 1 — JSON round-trip & wire shape")
    class RoundTripAndKeys {

        @Test
        @DisplayName("a fully populated ReportResponse round-trips through JSON to an equal value")
        void fullyPopulated_roundTripsToEqualValue() {
            ReportResponse original = fullyPopulated();

            ReportResponse restored = roundTrip(original, ReportResponse.class);

            assertThat(restored).isEqualTo(original);
            // Guard the individual components too, so a future record change that keeps
            // equals() but drops a component from the wire is still caught here.
            assertThat(restored.jobId()).isEqualTo(JOB_ID);
            assertThat(restored.status()).isEqualTo(ReportResponse.STATUS_ACCEPTED);
            assertThat(restored.reportType()).isEqualTo(REPORT_TYPE);
            assertThat(restored.startDate()).isEqualTo(START_DATE);
            assertThat(restored.endDate()).isEqualTo(END_DATE);
            assertThat(restored.reportName()).isEqualTo(REPORT_NAME);
            assertThat(restored.message()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("serialized JSON exposes exactly the seven documented top-level keys")
        void serializedJson_hasExactlySevenTopLevelKeys() throws JsonProcessingException {
            String json = toJson(fullyPopulated());

            JsonNode node = objectMapper().readTree(json);

            assertThat(topLevelKeys(node)).containsExactlyInAnyOrder(EXPECTED_JSON_KEYS);
        }
    }

    // ---------------------------------------------------------------------
    // Phase 2 — status & asynchronous acknowledgement semantics
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 2 — status & asynchronous acknowledgement semantics")
    class StatusAndAsyncAck {

        @Test
        @DisplayName("status \"ACCEPTED\" round-trips unchanged (asynchronous job-accepted response)")
        void acceptedStatus_roundTripsUnchanged() throws JsonProcessingException {
            ReportResponse original = fullyPopulated();
            // The report itself is produced out-of-band by the triggered batch job;
            // this DTO is only the accepted acknowledgement, hence the "ACCEPTED" status.
            assertThat(original.status()).isEqualTo("ACCEPTED");

            ReportResponse restored = roundTrip(original, ReportResponse.class);
            assertThat(restored.status()).isEqualTo("ACCEPTED");

            JsonNode node = objectMapper().readTree(toJson(original));
            assertThat(node.get("status").isTextual()).as("status must be a JSON string").isTrue();
            assertThat(node.get("status").asText()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("the accepted() factory stamps status = STATUS_ACCEPTED and echoes the request")
        void acceptedFactory_setsAcceptedStatus() {
            ReportResponse ack = ReportResponse.accepted(
                    JOB_ID, REPORT_TYPE, START_DATE, END_DATE, REPORT_NAME, MESSAGE);

            assertThat(ack.status())
                    .isEqualTo(ReportResponse.STATUS_ACCEPTED)
                    .isEqualTo("ACCEPTED");
            // The remaining request parameters are echoed verbatim by the factory.
            assertThat(ack.jobId()).isEqualTo(JOB_ID);
            assertThat(ack.reportType()).isEqualTo(REPORT_TYPE);
            assertThat(ack.startDate()).isEqualTo(START_DATE);
            assertThat(ack.endDate()).isEqualTo(END_DATE);
            assertThat(ack.reportName()).isEqualTo(REPORT_NAME);
            assertThat(ack.message()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the STATUS_ACCEPTED constant is the literal \"ACCEPTED\"")
        void statusAcceptedConstant_isAccepted() {
            assertThat(ReportResponse.STATUS_ACCEPTED).isEqualTo("ACCEPTED");
        }
    }

    // ---------------------------------------------------------------------
    // Phase 3 — dates serialize as ISO LocalDate
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3 — dates serialize as ISO LocalDate")
    class IsoLocalDates {

        @Test
        @DisplayName("startDate and endDate serialize as ISO date strings, never epoch numbers or arrays")
        void dates_serializeAsIsoStrings() throws JsonProcessingException {
            JsonNode node = objectMapper().readTree(toJson(fullyPopulated()));
            JsonNode start = node.get("startDate");
            JsonNode end = node.get("endDate");

            assertThat(node.has("startDate")).as("startDate key must be present").isTrue();
            assertThat(node.has("endDate")).as("endDate key must be present").isTrue();

            assertThat(start.isTextual())
                    .as("startDate must serialize as a JSON string (was %s)", start.getNodeType())
                    .isTrue();
            assertThat(start.isNumber()).as("startDate must not be an epoch number").isFalse();
            assertThat(start.isArray()).as("startDate must not be a [y,M,d] array").isFalse();
            assertThat(start.asText())
                    .as("startDate text must be the ISO-8601 form")
                    .isEqualTo(START_DATE_ISO);

            assertThat(end.isTextual())
                    .as("endDate must serialize as a JSON string (was %s)", end.getNodeType())
                    .isTrue();
            assertThat(end.isNumber()).as("endDate must not be an epoch number").isFalse();
            assertThat(end.isArray()).as("endDate must not be a [y,M,d] array").isFalse();
            assertThat(end.asText())
                    .as("endDate text must be the ISO-8601 form")
                    .isEqualTo(END_DATE_ISO);
        }

        @Test
        @DisplayName("the startDate and endDate record components are typed java.time.LocalDate")
        void dateComponents_areLocalDate() {
            Class<?> startType = componentNamed("startDate").getType();
            Class<?> endType = componentNamed("endDate").getType();

            assertThat(startType).isEqualTo(LocalDate.class);
            assertThat(endType).isEqualTo(LocalDate.class);
            // Never a date-time, epoch numeric, or floating-point substitution.
            assertThat(startType).isNotIn(LocalDateTime.class, long.class, double.class, float.class);
            assertThat(endType).isNotIn(LocalDateTime.class, long.class, double.class, float.class);
        }
    }

    // ---------------------------------------------------------------------
    // Phase 4 — passthrough String fields
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 4 — passthrough String fields")
    class PassthroughStrings {

        @Test
        @DisplayName("jobId, reportType, reportName and message round-trip unchanged as Strings")
        void stringFields_roundTripUnchanged() {
            ReportResponse restored = roundTrip(fullyPopulated(), ReportResponse.class);

            assertThat(restored.jobId()).isEqualTo(JOB_ID);
            assertThat(restored.reportType()).isEqualTo(REPORT_TYPE);
            assertThat(restored.reportName()).isEqualTo(REPORT_NAME);
            assertThat(restored.message()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("jobId, status, reportType, reportName and message record components are String")
        void stringComponents_areString() {
            assertThat(componentNamed("jobId").getType()).isEqualTo(String.class);
            assertThat(componentNamed("status").getType()).isEqualTo(String.class);
            assertThat(componentNamed("reportType").getType()).isEqualTo(String.class);
            assertThat(componentNamed("reportName").getType()).isEqualTo(String.class);
            assertThat(componentNamed("message").getType()).isEqualTo(String.class);
        }
    }
}
