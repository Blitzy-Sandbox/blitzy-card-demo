/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.adapter;

import com.awsm2.carddemo.exception.CardDemoException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SecretsManagerService}, the AWS Secrets Manager adapter
 * that backs runtime credential retrieval across CardDemo.
 *
 * <p>This adapter is the centerpiece of the CardDemo PCI-DSS posture
 * (AAP &sect;0.7.1 / &sect;0.7.2): no credentials may live in
 * {@code application.yml}, environment variables, or any plaintext source.
 * Every credential is fetched at runtime through this adapter, with the
 * primary credential pattern handled by Spring Cloud AWS auto-config and the
 * residual programmatic cases (JWT key rotation, ad-hoc third-party API keys,
 * audit-time secret-version verification) handled by this class.</p>
 *
 * <h2>Test coverage matrix</h2>
 * <p>The tests in this class cover four behavioral contracts of the adapter:</p>
 * <ol>
 *   <li><strong>Happy path</strong> (Phase 6 of the test agent_prompt) &mdash;
 *       {@link SecretsManagerService#getSecret(String)} returns the
 *       {@code SecretString} payload (and falls back to UTF-8 decoded
 *       {@code SecretBinary} when {@code SecretString} is null);
 *       {@link SecretsManagerService#getSecretJsonField(String, String)} parses
 *       a JSON payload and returns a single field as
 *       {@link Optional}{@code <String>};
 *       {@link SecretsManagerService#getSecretValue(String)} returns the
 *       parsed {@link JsonNode} tree.</li>
 *   <li><strong>Exception wrapping</strong> (Phase 7) &mdash; AWS SDK
 *       {@link ResourceNotFoundException} is wrapped as
 *       {@link CardDemoException} with reason code {@code SECRET_NOT_FOUND};
 *       {@link SecretsManagerException} is wrapped as
 *       {@code SECRETS_MANAGER_ERROR} with the original SDK exception
 *       preserved as the cause; both-null
 *       {@code secretString}/{@code secretBinary} responses surface as
 *       {@code SECRET_EMPTY}; Jackson parse failures surface as
 *       {@code SECRET_JSON_PARSE_ERROR}. Every reason code is verified
 *       verbatim per AAP &sect;0.7.2 ("Error codes and condition handling
 *       surfaced to downstream consumers must be preserved verbatim").</li>
 *   <li><strong>Input validation</strong> (Phase 8) &mdash; null, empty, and
 *       blank {@code secretArn} and {@code fieldName} arguments must throw
 *       {@link IllegalArgumentException} before any SDK call is made.</li>
 *   <li><strong>Behavioral assertions / security checks</strong> (Phase 9)
 *       &mdash; the adapter performs <strong>no local caching</strong>:
 *       three successive {@code getSecret} calls produce three distinct AWS
 *       SDK calls per AAP &sect;0.6.4 rotation discipline; the captured
 *       {@link GetSecretValueRequest} always carries the verbatim ARN
 *       supplied by the caller.</li>
 * </ol>
 *
 * <h2>Mocking strategy</h2>
 * <p>This is a pure Mockito unit test &mdash; no Spring context is loaded.
 * {@link MockitoExtension} (strict-stubbing mode, the JUnit 5 default in
 * Mockito 5.x) wires the {@code @Mock SecretsManagerClient} into the
 * production {@link SecretsManagerService} constructor via
 * {@code @InjectMocks}. The shared static {@link
 * com.fasterxml.jackson.databind.ObjectMapper} held inside the production
 * class is exercised directly (no mocking) so that JSON parsing semantics
 * remain authentic.</p>
 *
 * <h2>Compliance constraints honored</h2>
 * <ul>
 *   <li><strong>AWS SDK v2 only</strong> &mdash; all AWS types come from
 *       {@code software.amazon.awssdk.*}; no {@code com.amazonaws.*}
 *       imports anywhere in this file (AAP &sect;0.5.1).</li>
 *   <li><strong>No real AWS credentials</strong> &mdash; every call is
 *       routed through a Mockito mock; never to real AWS. The LocalStack
 *       convention test account ID {@code 000000000000} is used in the
 *       sample ARN constant.</li>
 *   <li><strong>No real card numbers, SSNs, or production ARNs</strong> in
 *       test data &mdash; the only secret value in this file is the placeholder
 *       {@code "hunter2"} (a well-known XKCD reference, never a real
 *       credential).</li>
 *   <li><strong>JUnit 5 idioms only</strong> &mdash; {@code @Test},
 *       {@code @DisplayName}, {@code @ExtendWith}, {@code assertThrows},
 *       {@code assertEquals}, {@code assertNotNull}, {@code assertTrue},
 *       {@code assertFalse}. No JUnit 4 ({@code @Before},
 *       {@code @Test(expected=...)}).</li>
 * </ul>
 *
 * <h2>Source mainframe context</h2>
 * <p>// Replaces: COBOL credential-file-based authentication
 * (plaintext {@code SEC-USR-PWD} in {@code app/cpy/CSUSR01Y.cpy} USRSEC
 * file, referenced by {@code app/cbl/COSGN00C.cbl}). The Java target
 * replaces plaintext storage with KMS-encrypted Secrets Manager retrieval.</p>
 * <p>// Backs AAP &sect;0.6.4: AWS Secrets Manager dynamic rotation without
 * Spring Boot restart &mdash; the no-caching contract verified by Test 9.1
 * is the foundation of the &#64;RefreshScope-based rotation discipline.</p>
 *
 * @see SecretsManagerService
 * @see com.awsm2.carddemo.exception.CardDemoException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SecretsManagerService \u2014 AWS Secrets Manager adapter unit tests")
class SecretsManagerServiceTest {

    // -------------------------------------------------------------------------
    // Mocks and System Under Test
    // -------------------------------------------------------------------------

    /**
     * The mocked AWS SDK v2 Secrets Manager client. Mockito strict-stubbing
     * (enabled by default via {@link MockitoExtension} in Mockito 5.x)
     * ensures that every {@code when(...)} stub set on this mock is actually
     * invoked by the production code &mdash; unused stubs fail the test,
     * catching over-mocking that hides real bugs.
     *
     * <p>The mock is reset by Mockito between test methods (per the
     * {@link MockitoExtension} lifecycle), so each test starts with a clean
     * collaborator instance.</p>
     */
    @Mock
    private SecretsManagerClient secretsManagerClient;

    /**
     * The production {@link SecretsManagerService} instance under test.
     * {@link InjectMocks} discovers the single-argument constructor
     * {@code SecretsManagerService(SecretsManagerClient)} on the production
     * class and injects the {@code @Mock secretsManagerClient} field above.
     *
     * <p>The internal static {@code ObjectMapper} held by
     * {@link SecretsManagerService} is left unmocked &mdash; its
     * {@code readTree} method is thread-safe per Jackson documentation and
     * is exercised directly to validate authentic JSON parsing behavior in
     * Tests 6.2, 6.3, 6.4, 7.4, and 7.5.</p>
     */
    @InjectMocks
    private SecretsManagerService service;

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    /**
     * LocalStack-convention ARN under the dummy test account ID
     * {@code 000000000000}. Never references a real production secret per the
     * test agent_prompt PCI-DSS compliance constraints. The ARN includes
     * realistic region and secret-name path segments so that
     * {@link ArgumentCaptor} verifications exercise full ARN strings rather
     * than trivial placeholders.
     */
    private static final String VALID_ARN =
            "arn:aws:secretsmanager:us-east-1:000000000000:secret:carddemo-rds-credentials";

    /**
     * Sample JSON top-level field name used by Tests 6.2 and 8.x.
     * Mirrors the production usage pattern for RDS-credential secrets where
     * the JSON shape is {@code {"username":"...","password":"..."}}.
     */
    private static final String VALID_FIELD_NAME = "password";

    /**
     * Sample JSON secret payload exercising mixed-type field extraction.
     * The {@code "password":"hunter2"} entry deliberately uses the well-known
     * XKCD placeholder string &mdash; never a real credential.
     * {@code "port":5432} is included so that Test 6.4 can verify integer
     * field extraction via {@link JsonNode#asInt()}.
     */
    private static final String VALID_JSON_PAYLOAD =
            "{\"username\":\"carddemo\",\"password\":\"hunter2\",\"port\":5432}";

    /**
     * Deliberately malformed JSON payload used by Tests 7.4 and 7.5 to
     * exercise the {@code SECRET_JSON_PARSE_ERROR} branch of the adapter.
     * Jackson rejects this string with a {@code JsonParseException} when
     * passed to {@link com.fasterxml.jackson.databind.ObjectMapper#readTree
     * ObjectMapper.readTree(...)}.
     */
    private static final String INVALID_JSON_PAYLOAD = "not valid json {{";

    // =========================================================================
    // Phase 6 \u2014 Happy path tests
    // =========================================================================

    /**
     * Test 6.1: Verifies that {@code getSecret} returns the verbatim
     * {@code SecretString} from the AWS SDK response, and that the
     * {@link GetSecretValueRequest} passed to the SDK carries the supplied
     * ARN unchanged in its {@code secretId()} field.
     *
     * <p>This is the canonical happy-path retrieval scenario. The
     * {@link ArgumentCaptor} verification protects against silent regressions
     * where a future refactor might accidentally mutate, prefix, or truncate
     * the ARN before passing it to the SDK.</p>
     */
    @Test
    @DisplayName("getSecret returns secret string for a valid ARN")
    void getSecret_withValidArn_returnsSecretString() {
        // Given: the SDK returns a response with a non-null SecretString
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString("hunter2")
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When: the adapter is invoked
        String secret = service.getSecret(VALID_ARN);

        // Then: the secret string is returned verbatim
        assertEquals("hunter2", secret);

        // And: the SDK call carries the supplied ARN as secretId()
        ArgumentCaptor<GetSecretValueRequest> captor =
                ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(secretsManagerClient).getSecretValue(captor.capture());
        assertEquals(VALID_ARN, captor.getValue().secretId());
    }

    /**
     * Additional happy-path test: verifies the UTF-8 binary fallback path
     * (production code lines 235-240). When the SDK returns a response with
     * a null {@code secretString} but a non-null {@code secretBinary}, the
     * adapter must decode the binary payload as UTF-8 and return the
     * resulting string.
     *
     * <p>This exercises the rare-but-valid case where a secret was originally
     * created via the AWS SDK ByteBuffer overload rather than the string
     * overload. The fallback ensures binary-textual secrets (e.g., binary-encoded
     * JWT keys) are still retrievable via the same adapter API.</p>
     */
    @Test
    @DisplayName("getSecret falls back to UTF-8 decoded SecretBinary when SecretString is null")
    void getSecret_withSecretBinaryOnly_returnsUtf8DecodedString() {
        // Given: the SDK returns a response with secretString null and
        // secretBinary populated (the binary fallback case).
        SdkBytes binary = SdkBytes.fromUtf8String("binary-fallback-secret");
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretBinary(binary)
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When: the adapter is invoked
        String secret = service.getSecret(VALID_ARN);

        // Then: the binary payload is decoded as UTF-8 and returned
        assertEquals("binary-fallback-secret", secret);
    }

    /**
     * Test 6.2: Verifies that {@code getSecretJsonField} parses a JSON-formatted
     * secret and returns the requested field's value wrapped in
     * {@link Optional}{@code <String>}.
     *
     * <p>This is the most common production usage pattern &mdash; extracting
     * a single field (e.g., {@code password}) from a multi-field credential
     * JSON. The {@code Optional} return type cleanly distinguishes "field
     * present" from "field absent" without resorting to {@code null}.</p>
     */
    @Test
    @DisplayName("getSecretJsonField returns the field value when present in JSON")
    void getSecretJsonField_withValidFieldName_returnsFieldValue() {
        // Given: the SDK returns the sample JSON payload as the secret string
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(VALID_JSON_PAYLOAD)
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When: the adapter extracts the "password" field
        Optional<String> field = service.getSecretJsonField(VALID_ARN, VALID_FIELD_NAME);

        // Then: the field is present and carries the expected value
        assertTrue(field.isPresent(), "Expected the password field to be present in the JSON payload");
        assertEquals("hunter2", field.get());
    }

    /**
     * Test 6.3: Verifies that {@code getSecretJsonField} returns
     * {@link Optional#empty()} when the requested field is absent from the
     * JSON payload &mdash; cleanly signaling "field not found" without
     * throwing an exception.
     *
     * <p>The contract preserves the COBOL convention where missing fields in
     * a record are treated as a valid (but distinct) condition rather than a
     * fatal error.</p>
     */
    @Test
    @DisplayName("getSecretJsonField returns Optional.empty when the field is absent from JSON")
    void getSecretJsonField_withAbsentFieldName_returnsEmpty() {
        // Given: the SDK returns the sample JSON payload (which lacks
        // the "nonExistentField" key)
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(VALID_JSON_PAYLOAD)
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When: the adapter tries to extract a non-existent field
        Optional<String> field = service.getSecretJsonField(VALID_ARN, "nonExistentField");

        // Then: the Optional is empty (NOT throwing or returning null)
        assertFalse(field.isPresent(), "Expected the missing field to surface as Optional.empty()");
    }

    /**
     * Test 6.4: Verifies that {@code getSecretValue} returns the full Jackson
     * {@link JsonNode} tree parsed from the secret's JSON payload, allowing
     * the caller to extract multiple fields without further SDK calls.
     *
     * <p>This exercises the production class's internal static
     * {@code ObjectMapper}-based JSON parsing path end-to-end. The assertion
     * verifies both string ({@code username}, {@code password}) and integer
     * ({@code port}) field types &mdash; the latter exercising
     * {@link JsonNode#asInt()}.</p>
     */
    @Test
    @DisplayName("getSecretValue returns the parsed JsonNode tree for a valid JSON secret")
    void getSecretValue_withValidArn_returnsParsedJsonNode() {
        // Given: the SDK returns a multi-field JSON payload
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(VALID_JSON_PAYLOAD)
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When: the adapter returns the parsed JsonNode tree
        JsonNode node = service.getSecretValue(VALID_ARN);

        // Then: every field of the JSON is recoverable from the tree
        assertNotNull(node, "Expected a non-null JsonNode tree");
        assertEquals("carddemo", node.get("username").asText());
        assertEquals("hunter2", node.get("password").asText());
        assertEquals(5432, node.get("port").asInt());
    }

    // =========================================================================
    // Phase 7 \u2014 Exception wrapping tests
    // =========================================================================

    /**
     * Test 7.1: Verifies that the adapter wraps the AWS SDK
     * {@link ResourceNotFoundException} as a {@link CardDemoException} with
     * the verbatim reason code {@code "SECRET_NOT_FOUND"}, and that the
     * wrapper message contains the ARN for diagnostic context.
     *
     * <p>The reason code is consumed by {@code GlobalExceptionHandler} and
     * surfaces in the {@code ApiResponse.code} JSON envelope field, allowing
     * downstream consumers to distinguish "ARN does not resolve" from other
     * Secrets Manager failure modes.</p>
     */
    @Test
    @DisplayName("getSecret wraps ResourceNotFoundException as CardDemoException SECRET_NOT_FOUND")
    void getSecret_whenResourceNotFoundException_wrapsAsSecretNotFound() {
        // Given: the SDK throws ResourceNotFoundException (the documented
        // "ARN does not resolve" failure mode)
        ResourceNotFoundException sdkException = ResourceNotFoundException.builder()
                .message("Secrets Manager can't find the specified secret.")
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(sdkException);

        // When/Then: the adapter wraps it as CardDemoException with the
        // SECRET_NOT_FOUND reason code
        CardDemoException ex = assertThrows(
                CardDemoException.class,
                () -> service.getSecret(VALID_ARN));
        assertEquals("SECRET_NOT_FOUND", ex.getReasonCode());

        // And: the wrapper message contains the ARN for diagnostic context
        assertTrue(
                ex.getMessage() != null && ex.getMessage().contains(VALID_ARN),
                "Expected the exception message to contain the supplied ARN: " + ex.getMessage());

        // And: the original SDK exception is preserved as the cause for
        // full root-cause analysis in CloudWatch / OpenSearch logs
        assertSame(sdkException, ex.getCause(),
                "Expected the original ResourceNotFoundException to be preserved as the cause");
    }

    /**
     * Test 7.2: Verifies that the adapter wraps generic
     * {@link SecretsManagerException} (throttling, unauthorized, network,
     * etc.) as a {@link CardDemoException} with reason code
     * {@code "SECRETS_MANAGER_ERROR"}, and that the original SDK exception
     * is preserved as the cause.
     *
     * <p>This is the catch-all branch for Secrets Manager service failures
     * other than {@code ResourceNotFoundException}. The cause preservation is
     * critical for fraud investigation and audit trails per AAP &sect;0.6.6.</p>
     */
    @Test
    @DisplayName("getSecret wraps SecretsManagerException as CardDemoException SECRETS_MANAGER_ERROR with cause")
    void getSecret_whenSecretsManagerException_wrapsAsSecretsManagerError() {
        // Given: the SDK throws a generic SecretsManagerException. The
        // production code references e.awsErrorDetails().errorMessage() inside
        // its catch block; the AWS SDK v2 builder does NOT auto-populate
        // awsErrorDetails when only .message(...) is supplied, so we must set
        // it explicitly here to faithfully simulate the SDK's runtime
        // contract (a real AWS-service-raised SecretsManagerException always
        // carries AwsErrorDetails).
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorMessage("Internal failure")
                .errorCode("InternalServiceError")
                .serviceName("SecretsManager")
                .build();
        SecretsManagerException sdkException = (SecretsManagerException)
                SecretsManagerException.builder()
                        .awsErrorDetails(errorDetails)
                        .message("Internal failure")
                        .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(sdkException);

        // When/Then: the adapter wraps it as CardDemoException with the
        // SECRETS_MANAGER_ERROR reason code
        CardDemoException ex = assertThrows(
                CardDemoException.class,
                () -> service.getSecret(VALID_ARN));
        assertEquals("SECRETS_MANAGER_ERROR", ex.getReasonCode());

        // And: the original SDK exception is preserved as the cause
        // (AAP §0.7.2 — error chains preserved verbatim for downstream
        // consumers and audit trails)
        assertNotNull(ex.getCause(),
                "Expected the original SecretsManagerException to be preserved as the cause");
        assertTrue(ex.getCause() instanceof SecretsManagerException,
                "Expected the cause to be a SecretsManagerException, got: "
                        + ex.getCause().getClass().getName());
    }

    /**
     * Test 7.3: Verifies that the adapter throws {@link CardDemoException}
     * with reason code {@code "SECRET_EMPTY"} when the SDK response carries
     * neither a {@code secretString} nor a {@code secretBinary} payload.
     *
     * <p>This is an exceptional edge case typically seen only immediately
     * after secret creation. The dedicated reason code distinguishes "secret
     * exists but is empty" from "secret does not exist"
     * ({@code SECRET_NOT_FOUND}), enabling distinct telemetry / alerting
     * pathways downstream.</p>
     */
    @Test
    @DisplayName("getSecret throws CardDemoException SECRET_EMPTY when both secretString and secretBinary are null")
    void getSecret_whenSecretStringAndBinaryNull_throwsSecretEmpty() {
        // Given: the SDK returns a response with both secretString and
        // secretBinary null (the empty-secret edge case)
        GetSecretValueResponse response = GetSecretValueResponse.builder().build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When/Then: the adapter throws CardDemoException with the
        // SECRET_EMPTY reason code
        CardDemoException ex = assertThrows(
                CardDemoException.class,
                () -> service.getSecret(VALID_ARN));
        assertEquals("SECRET_EMPTY", ex.getReasonCode());
    }

    /**
     * Test 7.4: Verifies that {@code getSecretJsonField} wraps Jackson parse
     * failures as a {@link CardDemoException} with reason code
     * {@code "SECRET_JSON_PARSE_ERROR"}.
     *
     * <p>This protects callers from raw {@code JsonProcessingException} or
     * {@code JsonParseException} leaks &mdash; every failure mode of this
     * adapter surfaces as a typed {@link CardDemoException} with a verbatim
     * reason code, satisfying the AAP &sect;0.7.1 exception-hierarchy contract.</p>
     */
    @Test
    @DisplayName("getSecretJsonField wraps Jackson parse failures as CardDemoException SECRET_JSON_PARSE_ERROR")
    void getSecretJsonField_whenSecretIsInvalidJson_throwsParseError() {
        // Given: the SDK returns a secret value that is not valid JSON
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(INVALID_JSON_PAYLOAD)
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When/Then: the adapter wraps the Jackson parse failure as
        // CardDemoException with the SECRET_JSON_PARSE_ERROR reason code
        CardDemoException ex = assertThrows(
                CardDemoException.class,
                () -> service.getSecretJsonField(VALID_ARN, VALID_FIELD_NAME));
        assertEquals("SECRET_JSON_PARSE_ERROR", ex.getReasonCode());
    }

    /**
     * Test 7.5: Same scenario as Test 7.4, but invoked via
     * {@code getSecretValue} rather than {@code getSecretJsonField}, to
     * verify that both JSON-returning methods produce identical reason codes
     * for invalid-JSON inputs.
     */
    @Test
    @DisplayName("getSecretValue wraps Jackson parse failures as CardDemoException SECRET_JSON_PARSE_ERROR")
    void getSecretValue_whenSecretIsInvalidJson_throwsParseError() {
        // Given: the SDK returns a secret value that is not valid JSON
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(INVALID_JSON_PAYLOAD)
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When/Then: the adapter wraps the Jackson parse failure as
        // CardDemoException with the SECRET_JSON_PARSE_ERROR reason code
        CardDemoException ex = assertThrows(
                CardDemoException.class,
                () -> service.getSecretValue(VALID_ARN));
        assertEquals("SECRET_JSON_PARSE_ERROR", ex.getReasonCode());
    }

    // =========================================================================
    // Phase 8 \u2014 Input validation tests (IllegalArgumentException)
    // =========================================================================

    /**
     * Test 8.1: Verifies that {@code getSecret(null)} throws
     * {@link IllegalArgumentException} <em>before</em> any SDK call is made.
     *
     * <p>Fail-fast input validation prevents both wasted AWS API calls and
     * the propagation of latent {@code NullPointerException}s deep inside
     * the SDK call chain.</p>
     */
    @Test
    @DisplayName("getSecret throws IllegalArgumentException for null ARN and never calls the SDK")
    void getSecret_withNullArn_throwsIllegalArgument() {
        // When/Then: the adapter rejects a null ARN
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.getSecret(null));

        // And: the exception message hints at the parameter name for
        // diagnostic context
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("secretArn"),
                "Expected the exception message to reference the secretArn parameter: " + ex.getMessage());

        // And: no SDK call was made (verifies fail-fast input validation)
        verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
    }

    /**
     * Test 8.2: Verifies that {@code getSecret} rejects whitespace-only ARNs
     * (per the production class's {@code String.isBlank()} check).
     */
    @Test
    @DisplayName("getSecret throws IllegalArgumentException for blank (whitespace) ARN")
    void getSecret_withBlankArn_throwsIllegalArgument() {
        // When/Then: whitespace-only ARN is rejected as blank
        assertThrows(
                IllegalArgumentException.class,
                () -> service.getSecret("   "));

        // And: no SDK call was made
        verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
    }

    /**
     * Test 8.3: Verifies that {@code getSecret} rejects the empty string
     * (per the production class's {@code String.isBlank()} check, which
     * returns {@code true} for empty strings).
     */
    @Test
    @DisplayName("getSecret throws IllegalArgumentException for empty-string ARN")
    void getSecret_withEmptyArn_throwsIllegalArgument() {
        // When/Then: empty-string ARN is rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> service.getSecret(""));

        // And: no SDK call was made
        verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
    }

    /**
     * Test 8.4: Verifies that {@code getSecretJsonField(null, fieldName)}
     * throws {@link IllegalArgumentException} when the ARN is null but the
     * field name is valid.
     *
     * <p>The production class validates {@code fieldName} first, then
     * delegates to {@code getSecret(secretArn)} which validates the ARN.
     * The expected behavior is that {@code getSecret}'s validation surfaces
     * the same {@link IllegalArgumentException} class, but with a message
     * that references the {@code secretArn} parameter.</p>
     */
    @Test
    @DisplayName("getSecretJsonField throws IllegalArgumentException for null ARN")
    void getSecretJsonField_withNullArn_throwsIllegalArgument() {
        // When/Then: null ARN is rejected (validation cascades through to
        // the delegated getSecret call)
        assertThrows(
                IllegalArgumentException.class,
                () -> service.getSecretJsonField(null, VALID_FIELD_NAME));

        // And: no SDK call was made
        verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
    }

    /**
     * Test 8.5: Verifies that {@code getSecretJsonField(arn, null)} throws
     * {@link IllegalArgumentException} when the field name is null.
     *
     * <p>This validation is performed <em>before</em> any SDK call is made,
     * preserving the fail-fast contract for the {@code fieldName} parameter.</p>
     */
    @Test
    @DisplayName("getSecretJsonField throws IllegalArgumentException for null field name")
    void getSecretJsonField_withNullFieldName_throwsIllegalArgument() {
        // When/Then: null fieldName is rejected
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.getSecretJsonField(VALID_ARN, null));

        // And: the exception message references the fieldName parameter
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("fieldName"),
                "Expected the exception message to reference the fieldName parameter: " + ex.getMessage());

        // And: no SDK call was made (fieldName validation precedes the
        // delegated getSecret invocation)
        verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
    }

    /**
     * Test 8.6: Verifies that {@code getSecretJsonField} rejects
     * whitespace-only (blank) field names.
     */
    @Test
    @DisplayName("getSecretJsonField throws IllegalArgumentException for blank field name")
    void getSecretJsonField_withBlankFieldName_throwsIllegalArgument() {
        // When/Then: whitespace-only fieldName is rejected as blank
        assertThrows(
                IllegalArgumentException.class,
                () -> service.getSecretJsonField(VALID_ARN, "   "));

        // And: no SDK call was made
        verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
    }

    /**
     * Test 8.7: Verifies that {@code getSecretValue(null)} throws
     * {@link IllegalArgumentException} (the validation propagates through
     * the delegated {@code getSecret(null)} call).
     */
    @Test
    @DisplayName("getSecretValue throws IllegalArgumentException for null ARN")
    void getSecretValue_withNullArn_throwsIllegalArgument() {
        // When/Then: null ARN is rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> service.getSecretValue(null));

        // And: no SDK call was made
        verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
    }

    // =========================================================================
    // Phase 9 \u2014 Behavioral assertions / security checks
    // =========================================================================

    /**
     * Test 9.1: <strong>The critical no-local-caching assertion.</strong>
     * Three sequential calls to {@code getSecret} must produce three distinct
     * SDK invocations &mdash; the adapter performs <strong>no local
     * caching</strong> of any secret value.
     *
     * <p>This contract is mandated by AAP &sect;0.6.4 ("Dynamic Rotation
     * Without Restart"). Local caching at the adapter layer would defeat
     * the rotation discipline: a cached value would shadow the rotated
     * secret until the cache entry expired, creating a window where the
     * application uses stale credentials. Rotation is instead handled
     * exclusively via Spring {@code @RefreshScope} beans at the caller
     * layer, so that {@code RefreshEvent}-triggered re-instantiation
     * invalidates any caller-side caches atomically.</p>
     *
     * <p>If this test fails, a future regression has introduced caching
     * inside the adapter &mdash; a security-critical issue that breaks
     * AAP &sect;0.6.4 and must be reverted.</p>
     */
    @Test
    @DisplayName("repeated getSecret calls do not cache locally and always hit the AWS client")
    void noLocalCaching_repeatedCallsAlwaysHitClient() {
        // Given: the SDK consistently returns the same secret value
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString("hunter2")
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When: the adapter is invoked THREE times for the same ARN
        String first = service.getSecret(VALID_ARN);
        String second = service.getSecret(VALID_ARN);
        String third = service.getSecret(VALID_ARN);

        // Then: each call returns the expected value
        assertEquals("hunter2", first);
        assertEquals("hunter2", second);
        assertEquals("hunter2", third);

        // And: the SDK was invoked exactly THREE times — proving no local
        // caching. AAP §0.6.4 mandates rotation via @RefreshScope at the
        // caller layer, NOT cache invalidation at the adapter layer.
        verify(secretsManagerClient, times(3))
                .getSecretValue(any(GetSecretValueRequest.class));
    }

    /**
     * Test 9.2: Verifies that the {@link GetSecretValueRequest} passed to
     * the SDK carries the verbatim ARN supplied by the caller, even for an
     * arbitrary ARN value distinct from the standard {@link #VALID_ARN}
     * fixture.
     *
     * <p>This complements Test 6.1 by exercising a different ARN to guard
     * against the (unlikely) regression where the adapter accidentally
     * hard-codes or mutates ARN strings.</p>
     */
    @Test
    @DisplayName("getSecret constructs GetSecretValueRequest with the exact ARN supplied")
    void getSecret_constructsRequestWithCorrectSecretId() {
        // Given: a different ARN than the standard fixture, and a happy-path
        // SDK response
        String customArn = "arn:aws:secretsmanager:us-east-1:111122223333:secret:my-app-secret";
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString("opaque-token-value")
                .build();
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When: the adapter is invoked with the custom ARN
        service.getSecret(customArn);

        // Then: the captured GetSecretValueRequest carries the exact ARN
        ArgumentCaptor<GetSecretValueRequest> captor =
                ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(secretsManagerClient).getSecretValue(captor.capture());
        assertEquals(customArn, captor.getValue().secretId(),
                "Expected the request's secretId to match the supplied custom ARN verbatim");
    }
}
