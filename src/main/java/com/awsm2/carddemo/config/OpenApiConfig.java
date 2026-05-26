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
package com.awsm2.carddemo.config;

import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.AccountViewDto;
import com.awsm2.carddemo.dto.AdminMenuDto;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.BillPaymentDto;
import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.dto.CardListDto;
import com.awsm2.carddemo.dto.CardUpdateDto;
import com.awsm2.carddemo.dto.MainMenuDto;
import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.dto.SignonRequestDto;
import com.awsm2.carddemo.dto.SignonResponseDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.dto.TransactionDetailDto;
import com.awsm2.carddemo.dto.TransactionListDto;
import com.awsm2.carddemo.dto.UserAddDto;
import com.awsm2.carddemo.dto.UserDeleteDto;
import com.awsm2.carddemo.dto.UserListDto;
import com.awsm2.carddemo.dto.UserUpdateDto;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring {@code @Configuration} that declares the {@link OpenAPI} bean used by
 * springdoc-openapi 2.x to generate the OpenAPI 3 contract for the CardDemo
 * REST API.
 *
 * <p>The OpenAPI document is served at {@code /v3/api-docs} (JSON) and rendered
 * as Swagger UI at {@code /swagger-ui.html}; both paths are configured in
 * {@code application.yml} under {@code springdoc.api-docs.path} and
 * {@code springdoc.swagger-ui.path} respectively. Path scanning for
 * {@code @RestController} classes is constrained to
 * {@code com.awsm2.carddemo.controller} via {@code springdoc.packages-to-scan}
 * in {@code application.yml}, which keeps batch jobs, AWS adapters, and other
 * internal components out of the public API surface.</p>
 *
 * <p>Per AAP &sect;0.3.4, the OpenAPI document is the public contract for the
 * 16 REST endpoint groups that replace the original CICS 3270 transactions
 * driven by the 18 online COBOL programs ({@code app/cbl/CO*.cbl}) and
 * documented by the 17 BMS mapsets ({@code app/bms/*.bms}).</p>
 *
 * <p><b>Replaces:</b> BMS mapset documentation and CICS terminal screen
 * specifications &mdash; the OpenAPI contract is now the single source of
 * truth for the public REST API surface.</p>
 *
 * <p>The {@code SecurityScheme} declared here documents the JWT bearer-token
 * authentication scheme implemented by {@code SecurityConfig} and
 * {@code JwtAuthenticationFilter} per AAP &sect;0.3.4 (session management:
 * "JWT bearer tokens issued by {@code /api/auth/signin}"). Controllers that
 * expose public endpoints (such as {@code AuthController#signin()}) opt out
 * of the global security requirement by annotating the method with
 * {@code @SecurityRequirements({})}.</p>
 *
 * <h2>Profile behavior</h2>
 * <ul>
 *   <li><b>local</b> &mdash; Swagger UI enabled; server URL defaults to
 *       {@code http://localhost:8080} so the embedded Swagger UI can issue
 *       requests against the local Spring Boot service.</li>
 *   <li><b>dev</b> &mdash; Swagger UI enabled with the dev environment ALB
 *       DNS as the server URL (sourced from
 *       {@code carddemo.openapi.server-url} in {@code application-dev.yml}).</li>
 *   <li><b>prod</b> &mdash; per AAP &sect;0.3.4, Swagger UI is gated by Spring
 *       Profile in production. The {@code application-prod.yml} overlay sets
 *       {@code springdoc.swagger-ui.enabled: false}; ALB rules additionally
 *       block {@code /swagger-ui*} paths externally. This config class itself
 *       performs no profile-specific gating &mdash; profile overlays do.</li>
 * </ul>
 *
 * <h2>AAP cross-references</h2>
 * <ul>
 *   <li>&sect;0.3.4 &mdash; API documentation: OpenAPI 3 generated by
 *       springdoc-openapi, served at {@code /api-docs} and
 *       {@code /swagger-ui.html} (gated by Spring Profile in production).</li>
 *   <li>&sect;0.5.1 &mdash; Dependency
 *       {@code org.springdoc:springdoc-openapi-starter-webmvc-ui} 2.x on the
 *       classpath via {@code pom.xml}.</li>
 *   <li>&sect;0.7.1 &mdash; Stateless infrastructure wiring; no business
 *       logic, no AWS SDK calls, no JPA references.</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    /**
     * Constant security scheme name shared between the {@link Components}
     * definition and the global {@link SecurityRequirement}. Swagger UI uses
     * this identifier to wire the "Authorize" dialog to the HTTP bearer
     * scheme.
     */
    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    /**
     * Spring application name; injected from {@code spring.application.name}
     * in {@code application.yml} (default value {@code carddemo}). Available
     * for inclusion in the OpenAPI document metadata or for future extension
     * (e.g., tagging operations with the producing service).
     */
    @Value("${spring.application.name:carddemo}")
    private String applicationName;

    /**
     * Service version surfaced in the OpenAPI {@link Info} block. Sourced
     * from the {@code SERVICE_VERSION} environment variable when the service
     * is deployed to ECS Fargate (the CI/CD pipeline writes the artifact
     * version into the task definition); falls back to {@code 0.1.0}
     * &mdash; the canonical Maven artifact version declared in
     * {@code pom.xml} ({@code <version>0.1.0</version>}) &mdash; during
     * local development.
     *
     * <p><b>QA Final Checkpoint 12, Issue 11 fix:</b> the default was
     * changed from {@code 0.1.0-SNAPSHOT} to {@code 0.1.0} so the
     * OpenAPI {@code info.version} field matches the Maven artifact
     * version when {@code SERVICE_VERSION} is not injected. This
     * eliminates the &quot;SNAPSHOT&quot; suffix mismatch flagged by API
     * discovery clients and aligns with AAP &sect;0.5 (&quot;All
     * versions are explicitly pinned &mdash; no {@code latest}, no
     * placeholders, no SNAPSHOT&quot;). Production deployments override
     * this default via the {@code SERVICE_VERSION} environment variable
     * resolved from the ECS task definition.</p>
     */
    @Value("${SERVICE_VERSION:0.1.0}")
    private String serviceVersion;

    /**
     * Public-facing API server URL displayed by Swagger UI as the default
     * target host. Overridden per profile:
     * <ul>
     *   <li>{@code local} &mdash; {@code http://localhost:8080} (default,
     *       baked into this {@code @Value} annotation)</li>
     *   <li>{@code dev} / {@code prod} &mdash;
     *       {@code carddemo.openapi.server-url} in the profile overlay
     *       points to the ALB DNS name (HTTPS)</li>
     * </ul>
     */
    @Value("${carddemo.openapi.server-url:http://localhost:8080}")
    private String serverUrl;

    /**
     * Contact email surfaced in the OpenAPI {@link Contact} block. Provided
     * via {@code carddemo.openapi.contact-email} in profile overlays so
     * downstream API consumers can reach the owning team. Defaults to a
     * placeholder for local development; production overlays MUST set a real
     * value.
     */
    @Value("${carddemo.openapi.contact-email:noreply@example.com}")
    private String contactEmail;

    /**
     * Default no-arg constructor. This configuration class has no constructor
     * dependencies &mdash; the {@code @Value}-injected fields are populated
     * by Spring's
     * {@link org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor
     * AutowiredAnnotationBeanPostProcessor} after instantiation, before the
     * {@link #cardDemoOpenAPI()} factory method is invoked.
     */
    public OpenApiConfig() {
        // No-op: Spring populates the @Value fields after construction.
    }

    /**
     * Builds the OpenAPI 3 contract document for the CardDemo REST API.
     *
     * <p>The bean is discovered automatically by springdoc-openapi 2.x's
     * autoconfiguration ({@code SpringDocConfiguration}) and used as the
     * base document onto which scanned {@code @RestController}-level
     * operation metadata is merged.</p>
     *
     * <p>The document declares:</p>
     * <ul>
     *   <li>API title, version, description, contact, and license
     *       (Apache 2.0 &mdash; matches the repository {@code LICENSE}
     *       file at the project root per AAP &sect;0.2.2)</li>
     *   <li>Server URL (profile-dependent: {@code localhost:8080} in local,
     *       ALB DNS in dev/prod)</li>
     *   <li>{@code BearerAuth} security scheme &mdash; HTTP bearer with the
     *       {@code JWT} bearer format, declared inside
     *       {@link Components#securitySchemes} so it appears in the
     *       Swagger UI "Authorize" dialog (per AAP &sect;0.3.4 session
     *       management)</li>
     *   <li>Global {@link SecurityRequirement} applying {@code BearerAuth}
     *       to every operation by default; controllers may opt out per
     *       endpoint via {@code @SecurityRequirements({})} (used by
     *       {@code AuthController#signin()} for the public sign-on
     *       endpoint that issues the JWT)</li>
     * </ul>
     *
     * <p>The HTTP bearer scheme signals to API consumers and to Swagger UI
     * that the {@code Authorization: Bearer &lt;token&gt;} header is required
     * for authenticated endpoints, where the token is the JWT issued by
     * {@code POST /api/auth/signin} (replacement for the original CICS
     * {@code COSGN00C} signon transaction).</p>
     *
     * @return the configured {@link OpenAPI} document; never {@code null}
     */
    @Bean
    public OpenAPI cardDemoOpenAPI() {
        // Replaces: BMS mapset field-contract documentation (app/bms/*.bms)
        // and CICS terminal screen specifications. The OpenAPI document is
        // now the single source of truth for the public REST API surface
        // consumed by external clients and downstream services.

        return new OpenAPI()
            .info(new Info()
                .title("CardDemo API")
                .description("REST API for the CardDemo credit-card management system. "
                    + "This service is the Java/Spring Boot 3.x port of the AWS "
                    + "Mainframe Modernization CardDemo COBOL application. Each "
                    + "endpoint replaces a CICS 3270 terminal transaction documented "
                    + "in the original BMS mapsets (app/bms/*.bms) and the 18 online "
                    + "COBOL programs (app/cbl/CO*.cbl). All monetary values follow "
                    + "the COBOL PIC S9(n)V99 contract (java.math.BigDecimal with "
                    + "RoundingMode.HALF_EVEN). Authenticated endpoints require a JWT "
                    + "bearer token obtained from POST /api/auth/signin.")
                .version(serviceVersion)
                .contact(new Contact()
                    .name("CardDemo Team")
                    .email(contactEmail))
                .license(new License()
                    .name("Apache License 2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0")))
            .servers(List.of(
                new Server()
                    .url(serverUrl)
                    .description("CardDemo API server (" + applicationName + ")")))
            // Global security requirement: every operation requires BearerAuth
            // unless the controller method opts out via @SecurityRequirements({}).
            .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
            .components(new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                    .name(SECURITY_SCHEME_NAME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .in(SecurityScheme.In.HEADER)
                    .description("JWT bearer token issued by POST /api/auth/signin "
                        + "(replaces CICS COSGN00C signon transaction). Pass as "
                        + "'Authorization: Bearer <token>' header on every "
                        + "authenticated request.")));
    }

    /**
     * Request/response DTO classes that must be present in
     * {@code components.schemas} of the OpenAPI document so external API
     * consumers can generate type-safe clients and reference the
     * concrete payload shapes by name.
     *
     * <p><b>QA Final Checkpoint 12, Issue 12 fix:</b> springdoc-openapi
     * 2.x does not always expand the generic {@code T} parameter of the
     * {@code ResponseEntity<ApiResponse<T>>} return type into
     * {@code components.schemas}; consequently, response DTOs such as
     * {@link AccountViewDto}, {@link CardDetailDto}, etc. were missing
     * from the generated spec, leaving the {@code data} payload as
     * {@code type: object, nullable: true} without a discoverable
     * concrete schema. This list forces every public-API request and
     * response DTO into {@code components.schemas} via the
     * {@link #responseDtoSchemaCustomizer()} {@link OpenApiCustomizer}
     * below.</p>
     *
     * <p>The list is closed-set and deliberately enumerated rather than
     * scanned reflectively: any DTO that should appear in the public
     * OpenAPI contract MUST be added here explicitly. Internal-only DTOs
     * (e.g., {@code CommonContextDto}, {@code MenuOptionDto},
     * {@code CardWorkAreasDto}, {@code DateTimeWorkAreaDto},
     * {@code AbendDataDto}, {@code ReportLineDto},
     * {@code StatementTransactionDto}) are intentionally omitted.</p>
     */
    private static final List<Class<?>> PUBLIC_API_DTOS = List.of(
            // The standardized envelope itself (already referenced by every
            // operation's @ApiResponse, but listing here ensures the
            // generic envelope is always present even when an operation
            // omits its explicit declaration).
            ApiResponse.class,
            // Request DTOs (← BMS mapsets per AAP §0.3.4)
            SignonRequestDto.class,
            AccountUpdateDto.class,
            CardUpdateDto.class,
            TransactionAddDto.class,
            BillPaymentDto.class,
            ReportRequestDto.class,
            UserAddDto.class,
            UserUpdateDto.class,
            UserDeleteDto.class,
            // Response DTOs (← BMS mapsets per AAP §0.3.4)
            SignonResponseDto.class,
            AccountViewDto.class,
            CardDetailDto.class,
            CardListDto.class,
            TransactionDetailDto.class,
            TransactionListDto.class,
            MainMenuDto.class,
            AdminMenuDto.class,
            UserListDto.class
    );

    /**
     * {@link OpenApiCustomizer} bean that registers every public-API
     * request and response DTO listed in {@link #PUBLIC_API_DTOS} into
     * the OpenAPI document's {@code components.schemas} section using
     * Swagger Core's {@link ModelConverters} resolver.
     *
     * <p><b>Why this is needed:</b> springdoc-openapi 2.x scans
     * {@code @RestController} return types to populate
     * {@code components.schemas}, but when controllers return a generic
     * envelope {@code ResponseEntity<ApiResponse<T>>}, the concrete
     * payload type {@code T} (e.g., {@link AccountViewDto}) is not
     * always resolved into a top-level component &mdash; it stays
     * inlined as an anonymous schema or is reported as
     * {@code type: object, nullable: true}. Downstream tooling
     * (OpenAPI Generator, Postman) cannot then produce a typed client
     * stub for the payload.</p>
     *
     * <p><b>How this fixes it:</b> for each class in
     * {@link #PUBLIC_API_DTOS}, the customizer calls
     * {@link ModelConverters#readAllAsResolvedSchema(Class)} which
     * walks the class plus all transitively referenced types, builds
     * fully-resolved {@link Schema} objects, and the customizer adds
     * each result to {@code components.schemas} keyed by simple class
     * name. The result is that every request/response DTO appears as a
     * named schema in the OpenAPI document, ready for
     * {@code $ref} lookups from operations and ready for code
     * generators to emit typed client classes.</p>
     *
     * <p>If springdoc has already added a given schema (most commonly
     * via the {@code @Schema} annotations on the DTO fields), the
     * existing entry is preserved &mdash; this customizer only adds
     * missing entries; it never overwrites.</p>
     *
     * <p><b>AAP alignment:</b> AAP &sect;0.7.1 (&quot;Maintain all
     * public API contracts &mdash; REST endpoints &hellip; request /
     * response shape &hellip; must remain stable&quot;) requires the
     * full payload shape to be discoverable. The OpenAPI document is
     * the contract; this customizer ensures the contract is complete.</p>
     *
     * @return an {@link OpenApiCustomizer} that registers public-API
     *         DTOs into {@code components.schemas} on every OpenAPI
     *         generation
     */
    @Bean
    public OpenApiCustomizer responseDtoSchemaCustomizer() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            // Preserve any existing schemas (e.g., those discovered by
            // springdoc via @RestController method scanning); only add
            // missing ones. We use a LinkedHashMap to preserve insertion
            // order for deterministic spec output, important for
            // diff-able CI artefacts and reproducible code generation.
            Map<String, Schema> existing = components.getSchemas();
            if (existing == null) {
                existing = new LinkedHashMap<>();
                components.setSchemas(existing);
            }

            ModelConverters converters = ModelConverters.getInstance();
            for (Class<?> dtoClass : PUBLIC_API_DTOS) {
                ResolvedSchema resolved = converters.readAllAsResolvedSchema(dtoClass);
                if (resolved == null || resolved.schema == null) {
                    continue;
                }
                // Add the top-level DTO schema (e.g., AccountViewDto)
                String name = dtoClass.getSimpleName();
                if (!existing.containsKey(name)) {
                    existing.put(name, resolved.schema);
                }
                // Add transitively referenced schemas (e.g.,
                // AccountViewDto.CardSummary, AccountViewDto.CustomerInfo)
                // so nested $ref lookups resolve to top-level components.
                if (resolved.referencedSchemas != null) {
                    for (Map.Entry<String, Schema> referenced : resolved.referencedSchemas.entrySet()) {
                        existing.putIfAbsent(referenced.getKey(), referenced.getValue());
                    }
                }
            }
        };
    }
}
