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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.util.Optional;

/**
 * AWS Secrets Manager wrapper for CardDemo programmatic, on-demand secret access.
 *
 * <p>AAP &sect;0.7.1 / &sect;0.7.2: ALL credentials in Secrets Manager &mdash; never
 * hardcoded. Spring Cloud AWS handles bootstrap binding; this adapter handles
 * programmatic access not covered by auto-config.</p>
 *
 * <h2>Relationship to Spring Cloud AWS auto-config (PRIMARY pattern per AAP &sect;0.6.4)</h2>
 * <p>The <strong>primary</strong> credential management pattern in this codebase is
 * Spring Cloud AWS automatic Secrets Manager bootstrap via
 * {@code spring.config.import=aws-secretsmanager:...} declared in
 * {@code application*.yml}. Those declarations are processed by
 * {@code spring-cloud-aws-starter-secrets-manager} at application startup and
 * surface as Spring {@code Environment} properties &mdash; consumed by
 * {@code @Value(...)} injections on beans like {@code DataSource},
 * {@code KafkaProducerFactory}, and {@code KafkaConsumerFactory}, which are
 * themselves annotated {@code @RefreshScope} so they rebuild on rotation
 * notification per AAP &sect;0.6.4 (Dynamic Rotation Without Restart).</p>
 *
 * <p>This {@code SecretsManagerService} adapter is for the residual cases that
 * cannot be handled by static auto-config:</p>
 * <ul>
 *   <li>JWT key rotation handler &mdash; the JWT signing key is fetched
 *       on demand by {@code JwtTokenProvider} each time a token is issued so
 *       that key rotation is observed without a Spring context refresh.</li>
 *   <li>Ad-hoc third-party API keys looked up by client name at runtime.</li>
 *   <li>Audit-time secret-version verification (verify which version of a
 *       given ARN was active when an event was recorded).</li>
 * </ul>
 *
 * <h2>Caching discipline (AAP &sect;0.6.4)</h2>
 * <p>This adapter performs a fresh {@code GetSecretValue} call on every
 * invocation &mdash; it deliberately maintains <strong>no local cache</strong>.
 * Local caching would defeat AAP &sect;0.6.4's "rotation without restart"
 * requirement because the cached value would shadow the rotated secret until
 * the cache entry expired. Spring Cloud AWS itself maintains an internal
 * cache with TTL for properties imported via {@code spring.config.import},
 * but that cache lives at a different layer and is purged automatically on
 * {@code RefreshEvent}. Callers needing cached access MUST place the cache
 * in a {@code @RefreshScope}-annotated bean so that rotation invalidates it.</p>
 *
 * <h2>Security hygiene (AAP &sect;0.6.6)</h2>
 * <p>This class NEVER logs secret values &mdash; only ARN, version ID, and
 * success/failure status. The {@link #toString()} method is intentionally
 * not overridden to prevent accidental disclosure via diagnostic output.
 * Defensive clearing of {@code String} contents is not attempted because
 * Java's heap does not guarantee secure erase; the security boundary is the
 * Secrets Manager backing store (KMS-encrypted at rest) and the in-flight
 * TLS channel established by the AWS SDK.</p>
 *
 * <h2>Exception model (AAP &sect;0.7.1)</h2>
 * <p>All failures are wrapped in {@link CardDemoException} with a verbatim
 * reason code that propagates through {@code GlobalExceptionHandler} into
 * the {@code ApiResponse.code} JSON envelope field. Reason codes emitted by
 * this adapter:</p>
 * <ul>
 *   <li>{@code SECRET_NOT_FOUND} &mdash; the ARN does not resolve to any
 *       secret (AWS {@link ResourceNotFoundException}). Surfaces as HTTP
 *       500 by default; callers may catch and re-throw as a domain-specific
 *       exception when appropriate.</li>
 *   <li>{@code SECRETS_MANAGER_ERROR} &mdash; any other Secrets Manager
 *       service error (throttling, unauthorized, network). Wraps
 *       {@link SecretsManagerException}.</li>
 *   <li>{@code SECRET_JSON_PARSE_ERROR} &mdash; the secret value is not
 *       valid JSON when the caller invokes {@link #getSecretJsonField} or
 *       {@link #getSecretValue}.</li>
 *   <li>{@code SECRET_EMPTY} &mdash; the secret has neither a
 *       {@code SecretString} nor a {@code SecretBinary} payload (unusual
 *       but possible immediately after creation).</li>
 * </ul>
 *
 * <h2>Source mainframe context</h2>
 * <p>COBOL provenance: replaces plaintext credential storage embedded in
 * mainframe {@code PARM} datasets and the {@code USRSEC} VSAM file defined
 * by {@code app/cpy/CSUSR01Y.cpy} ({@code SEC-USR-PWD PIC X(08)},
 * referenced by {@code app/cbl/COSGN00C.cbl} via
 * {@code COPY CSUSR01Y}). Per AAP &sect;0.1.1, the user-password upgrade to
 * BCrypt happens in the {@code UserSecurity} JPA entity and the
 * {@code UserAddService} / {@code UserUpdateService} pair &mdash; this
 * adapter handles the parallel security improvement for non-user secrets
 * (JWT signing keys, third-party API keys, RDS credentials, MSK SASL
 * credentials, KMS data keys).</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is fully thread-safe. The injected {@link SecretsManagerClient}
 * is thread-safe per AWS SDK v2 contract. The static {@link ObjectMapper}
 * is shared per Jackson documentation (configured once, never mutated, and
 * its {@code readTree} method is thread-safe).</p>
 *
 * @see com.awsm2.carddemo.config.AwsSdkConfig#secretsManagerClient()
 * @see com.awsm2.carddemo.exception.CardDemoException
 */
@Service
public class SecretsManagerService {

    /**
     * SLF4J logger emitting structured operational events. Per AAP
     * &sect;0.6.6, log statements include the Secret ARN and (when
     * available) the version ID, but NEVER the secret value itself.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerService.class);

    /**
     * Shared singleton {@link ObjectMapper} for parsing JSON-formatted
     * secret payloads. Jackson's {@code ObjectMapper} is thread-safe once
     * configured, and {@link ObjectMapper#readTree(String)} performs no
     * shared mutable-state access &mdash; safe for concurrent reads.
     *
     * <p>Declaring this as {@code static final} avoids per-invocation
     * allocation of the parser (Jackson's mapper construction is
     * relatively expensive and is the documented anti-pattern in the
     * Jackson user guide).</p>
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Singleton AWS SDK v2 client injected via constructor. The bean is
     * provided by {@code com.awsm2.carddemo.config.AwsSdkConfig#secretsManagerClient()}
     * &mdash; configured per-profile with the LocalStack endpoint override
     * (local) or default real-AWS endpoint resolution (dev / prod) and the
     * credentials chain appropriate to the profile.
     *
     * <p>The field is {@code final} to enforce single-binding at
     * construction (constructor injection per AAP &sect;0.3.3
     * "Dependency injection for loose coupling").</p>
     */
    private final SecretsManagerClient secretsManagerClient;

    /**
     * Constructs the adapter with its single collaborator. Spring auto-wires
     * the {@link SecretsManagerClient} bean &mdash; no further configuration
     * is required at the call site.
     *
     * <p>Constructor injection is mandated by AAP &sect;0.3.3 (Adapter
     * Pattern + Dependency Injection) and replaces COBOL's
     * {@code CALL ... USING} static linkage with an injectable, mockable
     * dependency that supports unit testing via Mockito.</p>
     *
     * @param secretsManagerClient the singleton AWS SDK v2 client bean
     *                             produced by {@code AwsSdkConfig}; never
     *                             {@code null}
     */
    public SecretsManagerService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Retrieves a secret as a raw {@link String}. The return value is the
     * verbatim {@code SecretString} payload from AWS Secrets Manager
     * (falling back to a UTF-8 decode of {@code SecretBinary} for the
     * uncommon case where the secret was created in binary form).
     *
     * <p>Use this method for single-value secrets &mdash; e.g., simple API
     * tokens, opaque tokens with no JSON structure. For JSON-encoded
     * secrets, prefer {@link #getSecretJsonField(String, String)} (single
     * field) or {@link #getSecretValue(String)} (full structure).</p>
     *
     * <p>// Replaces: plaintext SEC-USR-PWD in CSUSR01Y.cpy USRSEC file
     * (upgraded to BCrypt in UserSecurity entity + this adapter for other
     * secrets)</p>
     *
     * <p>// No local caching &mdash; Spring @RefreshScope at caller layer
     * handles rotation per AAP &sect;0.6.4</p>
     *
     * @param secretArn the full Secrets Manager ARN (e.g.,
     *                  {@code arn:aws:secretsmanager:us-east-1:123456789012:secret:carddemo/jwt-signing-key-abc123}),
     *                  or the bare secret name; never {@code null} or blank
     * @return the secret value as a {@link String}; never {@code null}
     * @throws IllegalArgumentException if {@code secretArn} is {@code null}
     *                                  or blank
     * @throws CardDemoException        with reason code
     *                                  {@code SECRET_NOT_FOUND} if the ARN
     *                                  does not resolve, with reason code
     *                                  {@code SECRETS_MANAGER_ERROR} on any
     *                                  other Secrets Manager service error,
     *                                  or with reason code
     *                                  {@code SECRET_EMPTY} if the secret
     *                                  carries neither {@code SecretString}
     *                                  nor {@code SecretBinary} content
     */
    public String getSecret(String secretArn) {
        // Replaces: plaintext SEC-USR-PWD in CSUSR01Y.cpy USRSEC file
        // (upgraded to BCrypt in UserSecurity entity + this adapter for other
        // secrets). No local caching — Spring @RefreshScope at caller layer
        // handles rotation per AAP §0.6.4.
        if (secretArn == null || secretArn.isBlank()) {
            throw new IllegalArgumentException("secretArn must not be null/blank");
        }
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretArn)
                .build();
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            // SecretString is the conventional textual representation; SecretBinary
            // is supported as a fallback for binary secrets stored via the AWS
            // SDK ByteBuffer overload. Both cannot be null simultaneously for a
            // valid secret, but we defend against the edge case anyway.
            String secretValue = response.secretString();
            if (secretValue == null && response.secretBinary() != null) {
                // SDK v2 SdkBytes wraps the binary payload; asUtf8String() decodes
                // it as UTF-8 (acceptable for our use cases — JWT keys, API tokens
                // — which are textual). Non-UTF-8 binary secrets would require a
                // different decoding strategy; out of scope for the current AAP.
                secretValue = response.secretBinary().asUtf8String();
            }
            if (secretValue == null) {
                // Both SecretString and SecretBinary are null — exceptional case
                // typically seen only when a secret was created without an
                // initial value. Surface as a typed CardDemoException so the
                // caller can distinguish from a missing secret (SECRET_NOT_FOUND).
                throw new CardDemoException(
                        "SECRET_EMPTY",
                        "Secret value is null/empty for ARN: " + secretArn,
                        null);
            }
            // Operational logging: ARN + version ID, never the value itself.
            LOG.debug("Secret retrieved arn={} versionId={}", secretArn, response.versionId());
            return secretValue;
        } catch (ResourceNotFoundException e) {
            // AWS distinguishes "secret does not exist" from generic service
            // errors. Map to a dedicated reason code so GlobalExceptionHandler
            // can emit HTTP 404-equivalent telemetry if desired.
            LOG.error("Secret not found arn={}", secretArn);
            throw new CardDemoException(
                    "SECRET_NOT_FOUND",
                    "Secret not found: " + secretArn,
                    e);
        } catch (SecretsManagerException e) {
            // Throttling, unauthorized, network, and all other Secrets Manager
            // failures. e.awsErrorDetails().errorMessage() gives the
            // service-supplied diagnostic — safe to include in the wrapper
            // message (no secret value exposure here, just the API error text).
            LOG.error(
                    "Secrets Manager failure arn={} cause={}",
                    secretArn,
                    e.awsErrorDetails().errorMessage(),
                    e);
            throw new CardDemoException(
                    "SECRETS_MANAGER_ERROR",
                    "Failed to retrieve secret: " + e.awsErrorDetails().errorMessage(),
                    e);
        }
    }

    /**
     * Retrieves a single field from a JSON-formatted secret. Convenience
     * for the common case where a secret is stored as a JSON object (e.g.,
     * RDS credentials, JWT key pairs) and the caller wants exactly one
     * field by name.
     *
     * <p>// Primary use: RDS credentials JSON {"username":"...",
     * "password":"..."} and JWT signing keys</p>
     *
     * <p>// No local caching &mdash; Spring @RefreshScope at caller layer
     * handles rotation per AAP &sect;0.6.4</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>RDS credentials secret stored as
     *       {@code {"username":"carddemo","password":"s3cret"}} &mdash; call
     *       {@code getSecretJsonField(arn, "password")} to extract just the
     *       password.</li>
     *   <li>JWT key pair stored as
     *       {@code {"privateKey":"-----BEGIN ...","publicKey":"-----BEGIN ..."}}
     *       &mdash; call {@code getSecretJsonField(arn, "privateKey")} for
     *       signing.</li>
     * </ul>
     *
     * <p>Returns {@link Optional#empty()} when the field is syntactically
     * absent or its value is the JSON literal {@code null} &mdash; this
     * cleanly distinguishes "field not present" from "field present with an
     * empty string". A non-empty {@link Optional} containing an empty
     * {@link String} is possible if the field is present with value
     * {@code ""}.</p>
     *
     * @param secretArn the full Secrets Manager ARN of a JSON-formatted
     *                  secret; never {@code null} or blank (validated by
     *                  the delegated {@link #getSecret(String)} call)
     * @param fieldName the top-level JSON field name to extract; never
     *                  {@code null} or blank
     * @return {@link Optional} containing the field's textual value, or
     *         {@link Optional#empty()} if the field is absent or its value
     *         is JSON {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is {@code null}
     *                                  or blank (or {@code secretArn} via
     *                                  the underlying {@link #getSecret}
     *                                  call)
     * @throws CardDemoException        with reason code
     *                                  {@code SECRET_JSON_PARSE_ERROR} when
     *                                  the secret value is not parseable as
     *                                  JSON, or any reason code propagated
     *                                  from {@link #getSecret(String)}
     */
    public Optional<String> getSecretJsonField(String secretArn, String fieldName) {
        // Primary use: RDS credentials JSON {"username":"...", "password":"..."}
        // and JWT signing keys. No local caching — Spring @RefreshScope at
        // caller layer handles rotation per AAP §0.6.4.
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be null/blank");
        }
        // Delegate to getSecret which validates secretArn and handles all
        // Secrets Manager failure modes consistently.
        String json = getSecret(secretArn);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode field = root.get(fieldName);
            if (field == null || field.isNull()) {
                // Field absent or explicitly null — log only the field name
                // (the field name itself is not sensitive; the value would
                // be, but the value is what's missing here).
                LOG.debug("Field {} not found in secret arn={}", fieldName, secretArn);
                return Optional.empty();
            }
            // asText() coerces non-string types (numeric, boolean) to their
            // string form; for JSON object/array fields the returned value
            // is the empty string per JsonNode contract — callers passing
            // a nested-object field name receive Optional.of("") which they
            // can detect if necessary. Most secrets are flat JSON, so this
            // edge is rare and acceptable.
            return Optional.of(field.asText());
        } catch (CardDemoException e) {
            // Re-throw CardDemoException unchanged (delegated propagation;
            // would otherwise be swallowed by the broader catch below).
            throw e;
        } catch (Exception e) {
            // Jackson parse errors and any other unexpected condition.
            // e.getMessage() is the parser-supplied message — does NOT
            // include the JSON content itself per Jackson defaults, so
            // it is safe to include in the wrapper message and log.
            LOG.error(
                    "Failed to parse secret JSON arn={} field={} cause={}",
                    secretArn,
                    fieldName,
                    e.getMessage(),
                    e);
            throw new CardDemoException(
                    "SECRET_JSON_PARSE_ERROR",
                    "Failed to parse JSON-formatted secret: " + secretArn,
                    e);
        }
    }

    /**
     * Retrieves the full secret content parsed as a {@link JsonNode} tree.
     * Used when callers need multiple fields and want to avoid multiple
     * Secrets Manager API calls (one call, multiple field extractions).
     *
     * <p>// No local caching &mdash; Spring @RefreshScope at caller layer
     * handles rotation per AAP &sect;0.6.4</p>
     *
     * <p>Example: an RDS credentials secret stored as
     * {@code {"username":"...","password":"...","host":"...","port":5432}}
     * &mdash; one {@code getSecretValue(arn)} call returns the parsed tree
     * and the caller extracts all four fields locally without further
     * AWS API traffic.</p>
     *
     * <p>The returned {@link JsonNode} is a Jackson tree node, not a
     * domain-typed POJO. Callers that need strong typing should either
     * call {@link #getSecret(String)} and deserialize via their own
     * {@code ObjectMapper.readValue(json, MyClass.class)}, or extract
     * individual fields with {@link #getSecretJsonField(String, String)}.</p>
     *
     * @param secretArn the full Secrets Manager ARN; never {@code null} or
     *                  blank
     * @return the parsed Jackson {@link JsonNode} tree of the secret's JSON
     *         content; never {@code null}
     * @throws IllegalArgumentException if {@code secretArn} is {@code null}
     *                                  or blank (via the delegated
     *                                  {@link #getSecret} call)
     * @throws CardDemoException        with reason code
     *                                  {@code SECRET_JSON_PARSE_ERROR} when
     *                                  the secret value is not parseable as
     *                                  JSON, or any reason code propagated
     *                                  from {@link #getSecret(String)}
     */
    public JsonNode getSecretValue(String secretArn) {
        // No local caching — Spring @RefreshScope at caller layer handles
        // rotation per AAP §0.6.4.
        String json = getSecret(secretArn);
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (CardDemoException e) {
            // Re-throw CardDemoException unchanged (defensive — getSecret
            // is the only call inside the try block, and it throws
            // CardDemoException, not Jackson exceptions; this preserves
            // the original reason code through the catch chain).
            throw e;
        } catch (Exception e) {
            // Jackson parse errors. Message excludes the JSON payload per
            // Jackson defaults.
            LOG.error(
                    "Failed to parse secret JSON arn={} cause={}",
                    secretArn,
                    e.getMessage(),
                    e);
            throw new CardDemoException(
                    "SECRET_JSON_PARSE_ERROR",
                    "Failed to parse JSON-formatted secret: " + secretArn,
                    e);
        }
    }
}
