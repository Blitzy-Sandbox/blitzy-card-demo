package com.carddemo.card.config;

import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI configuration anchor for {@code card-svc} (CardDemo walking skeleton).
 *
 * <p>This is an intentionally <strong>empty, dependency-free</strong>
 * {@link Configuration} class. It defines <em>no</em> beans and imports no
 * springdoc / swagger types.</p>
 *
 * <p><strong>Contract is the single source of truth (SSoT).</strong> The frozen
 * OpenAPI 3.1 contract at {@code /contracts/card-svc.openapi.yaml} is the SSoT for
 * this service's HTTP surface. At build time the {@code openapi-generator-maven-plugin}
 * (generatorName {@code spring}, {@code interfaceOnly=true}) generates the API
 * interfaces into {@code com.carddemo.card.api.*} and the DTO models into
 * {@code com.carddemo.card.model.*}. Those generated sources are NEVER hand-edited;
 * to change the API, edit the frozen contract and regenerate.</p>
 *
 * <p><strong>Why no springdoc bean?</strong> The generator is configured with
 * {@code documentationProvider=none}, {@code annotationLibrary=none}, and
 * {@code openApiNullable=false}, so springdoc-openapi and the Swagger model
 * library are deliberately absent from the classpath. Declaring a Swagger
 * {@code OpenAPI} model bean here would fail the green build. Health is served
 * by Spring Boot Actuator at {@code /actuator/health}.</p>
 *
 * <p>Provenance: [SRC: COCRDSLC | CARDDAT] &mdash; app/csd/CARDDEMO.CSD
 * (CCDL -&gt; COCRDSLC over file CARDDAT).</p>
 */
@Configuration
public class OpenApiConfig {
}
