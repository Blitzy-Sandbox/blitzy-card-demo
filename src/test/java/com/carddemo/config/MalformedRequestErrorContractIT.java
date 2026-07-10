package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test proving that <strong>container-level</strong> error responses are
 * rendered as {@code dto.ErrorResponse} JSON, never Tomcat's stock {@code text/html} error page
 * (QA finding: malformed / percent-encoded request targets returned Tomcat HTML 400 instead of the
 * JSON error contract).
 *
 * <h2>Why a raw socket</h2>
 * <p>The failure only reproduces at the Tomcat connector: a percent-encoded path separator
 * ({@code ..%2F..}) or an embedded null byte ({@code %00}) is rejected during URI parsing, before the
 * request reaches the servlet filter chain or {@code DispatcherServlet}, and Tomcat's pipeline
 * {@code ErrorReportValve} writes the body. A normalizing HTTP client ({@code RestTemplate},
 * {@code WebClient}, {@code HttpClient}) would rewrite or refuse these targets client-side and never
 * exercise the connector path, so this test writes the literal HTTP request line over a
 * {@link Socket}. The fix under test is {@code config.JsonErrorReportValve}, installed by
 * {@code config.WebConfig#jsonErrorReportValveCustomizer(ObjectMapper)}.</p>
 *
 * <h2>Harness</h2>
 * <p>The full application boots on a random port under the {@code test} profile with the same
 * throwaway PostgreSQL&nbsp;16 and LocalStack containers used by the other web integration suites, so
 * the embedded Tomcat (and therefore the valve) is exercised exactly as in production. Source
 * COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}; rationale lives in
 * {@code docs/decision-log.md} (Explainability rule).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Malformed / encoded request targets return ErrorResponse JSON (not Tomcat HTML)")
class MalformedRequestErrorContractIT {

    // ------------------------------------------------------------------------------------------------
    // Container images / AWS test constants (mirror the other web ITs so the app resolves the same
    // coordinates it does across the integration suites).
    // ------------------------------------------------------------------------------------------------

    private static final String POSTGRES_IMAGE = "postgres:16";
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:4";
    private static final String DB_NAME = "carddemo";
    private static final String DB_USERNAME = "carddemo";
    private static final String DB_PASSWORD = "carddemo";
    private static final String AWS_REGION = "us-east-1";
    private static final String AWS_ACCESS_KEY = "test";
    private static final String AWS_SECRET_KEY = "test";

    /** Shared PostgreSQL 16 container (Testcontainers 2.0 module class; reaped by Ryuk at JVM exit). */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USERNAME)
            .withPassword(DB_PASSWORD);

    /** Shared LocalStack container exposing S3/SQS/SNS. */
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns");

    static {
        POSTGRES.start();
        LOCALSTACK.start();
    }

    /**
     * Registers the container-derived Spring properties so the full web context wires against the
     * throwaway PostgreSQL and LocalStack and never reaches live AWS.
     *
     * @param registry the Spring test registry receiving the lazily-evaluated suppliers
     */
    @DynamicPropertySource
    static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.cloud.aws.region.static", () -> AWS_REGION);
        registry.add("spring.cloud.aws.credentials.access-key", () -> AWS_ACCESS_KEY);
        registry.add("spring.cloud.aws.credentials.secret-key", () -> AWS_SECRET_KEY);

        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.sns.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> Boolean.TRUE);
    }

    /** Random embedded-server port for the raw socket client. */
    @LocalServerPort
    private int port;

    /** Application Jackson mapper used to parse the JSON error bodies returned by the server. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The precise reproduction from the QA finding: percent-encoded path separators and an embedded
     * null byte are rejected by the Tomcat connector. Each must now come back as {@code ErrorResponse}
     * JSON with the malformed-request contract, not a Tomcat HTML page.
     *
     * @param rawTarget the literal request target to place on the HTTP request line
     * @throws IOException if the raw socket exchange fails
     */
    @ParameterizedTest(name = "encoded target \"{0}\" -> 400 JSON, not HTML")
    @ValueSource(strings = {
            "/api/transactions/..%2F..%2Fetc%2Fpasswd",
            "/api/cards/%00null",
            "/api/accounts/%2e%2e%2f%2e%2e%2fetc%2fpasswd"
    })
    @DisplayName("connector-rejected encoded targets return 400 ErrorResponse JSON")
    void encodedTargetsReturnJson(final String rawTarget) throws IOException {
        final RawResponse response = rawHttpGet(rawTarget);

        // The status is a client error 400 (the connector rejects the malformed target).
        assertThat(response.status()).isEqualTo(400);

        // The body is JSON, never Tomcat's HTML error page.
        assertThat(response.contentType()).isNotNull();
        assertThat(response.contentType().toLowerCase(Locale.ROOT)).contains("application/json");
        assertThat(response.body().toLowerCase(Locale.ROOT))
                .doesNotContain("<!doctype", "<html", "apache tomcat");

        // The JSON matches the shared ErrorResponse contract for a malformed request.
        final JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.path("status").asInt()).isEqualTo(400);
        assertThat(json.path("error").asText()).isEqualTo("Bad Request");
        assertThat(json.path("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(json.path("message").asText())
                .isEqualTo("The request could not be processed because it was malformed.");
        // fieldErrors is always present (empty for a non-validation error).
        assertThat(json.has("fieldErrors")).isTrue();
        assertThat(json.path("fieldErrors").isArray()).isTrue();
    }

    /**
     * A well-formed but unknown/unauthorized target must also answer with JSON (here the security
     * filter chain returns a 401 {@code ErrorResponse}), confirming the API never falls back to an
     * HTML error page on any path.
     *
     * @throws IOException if the raw socket exchange fails
     */
    @Test
    @DisplayName("well-formed unknown target still returns JSON (never HTML)")
    void wellFormedUnknownTargetReturnsJson() throws IOException {
        final RawResponse response = rawHttpGet("/api/definitely-not-a-real-endpoint-xyz");

        assertThat(response.status()).isBetween(400, 499);
        assertThat(response.contentType()).isNotNull();
        assertThat(response.contentType().toLowerCase(Locale.ROOT)).contains("application/json");
        assertThat(response.body().toLowerCase(Locale.ROOT))
                .doesNotContain("<!doctype", "<html", "apache tomcat");

        final JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.path("status").asInt()).isBetween(400, 499);
    }

    /**
     * Issues a single HTTP/1.1 GET with the <em>literal</em> request target (no client-side
     * normalization) and reads the full response.
     *
     * @param rawTarget the exact bytes to write after {@code GET } on the request line
     * @return the parsed status code, content-type header, and body of the response
     * @throws IOException if the socket exchange fails
     */
    private RawResponse rawHttpGet(final String rawTarget) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(5000);

            final String request = "GET " + rawTarget + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final InputStream in = socket.getInputStream();
            final byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }

            final String raw = buffer.toString(StandardCharsets.UTF_8);
            final int separator = raw.indexOf("\r\n\r\n");
            final String headerBlock = (separator >= 0) ? raw.substring(0, separator) : raw;
            final String body = (separator >= 0) ? raw.substring(separator + 4) : "";

            final String[] headerLines = headerBlock.split("\r\n");
            final int status = parseStatus(headerLines[0]);
            String contentType = null;
            for (final String line : headerLines) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-type:")) {
                    contentType = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            return new RawResponse(status, contentType, body);
        }
    }

    /**
     * Parses the numeric status code out of an HTTP status line such as {@code HTTP/1.1 400 Bad Request}.
     *
     * @param statusLine the first line of the HTTP response
     * @return the parsed status code
     */
    private static int parseStatus(final String statusLine) {
        final String[] parts = statusLine.split(" ");
        return Integer.parseInt(parts[1]);
    }

    /**
     * Minimal captured HTTP response: status code, {@code Content-Type} header, and body text.
     *
     * @param status      the HTTP status code
     * @param contentType the {@code Content-Type} header value, or {@code null} if absent
     * @param body        the raw response body
     */
    private record RawResponse(int status, String contentType, String body) {
    }
}
