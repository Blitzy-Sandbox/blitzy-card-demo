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

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;

/**
 * Embedded Tomcat hardening and API-error-envelope compatibility settings.
 *
 * <p><b>QA CR-11:</b> Tomcat rejects encoded solidus ({@code %2F}) by default
 * before the request reaches Spring MVC, which causes Tomcat's built-in HTML
 * error report to leak through for path-traversal probes. Passing encoded
 * solidus through to Spring keeps the malicious value inside a single path
 * segment when possible; the custom Tomcat {@link ErrorReportValve} below
 * covers lower-level connector rejections that still happen before
 * {@code /error} dispatch.</p>
 */
@Configuration
public class TomcatConfig {

    /**
     * Allows encoded slashes to pass through as encoded data rather than
     * being rejected by Tomcat before Spring can produce a JSON envelope.
     *
     * @return Tomcat web-server factory customizer
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> encodedSolidusCustomizer() {
        return factory -> {
            factory.addConnectorCustomizers(connector ->
                    connector.setProperty("encodedSolidusHandling", "passthrough"));
            factory.addContextCustomizers(context -> {
                if (context.getParent() instanceof StandardHost host) {
                    host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
                }
            });
        };
    }

    /**
     * Tomcat host-level error reporter that emits JSON instead of the default
     * HTML report for connector/container errors.
     *
     * <p>This valve is intentionally self-contained and does not depend on
     * Spring beans: Tomcat may invoke it for malformed requests before the
     * Spring application context participates in request handling.</p>
     */
    public static class JsonErrorReportValve extends ErrorReportValve {

        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            int statusCode = response.getStatus();
            if (statusCode < 400 || !response.setErrorReported()) {
                return;
            }

            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            try {
                Writer writer = response.getReporter();
                if (writer != null) {
                    writer.write(json(statusCode));
                    response.finishResponse();
                }
            } catch (IOException ignored) {
                // If the client disconnected or the response is no longer
                // writable, Tomcat has no safe recovery path. Suppress to avoid
                // recursively invoking the error valve.
            }
        }

        private String json(int statusCode) {
            String code = switch (statusCode) {
                case 400 -> "BAD_REQUEST";
                case 401 -> "UNAUTHORIZED";
                case 403 -> "FORBIDDEN";
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> statusCode >= 500 ? "INTERNAL_ERROR" : "HTTP_ERROR";
            };
            String message = switch (statusCode) {
                case 400 -> "Bad request";
                case 401 -> "Authentication required";
                case 403 -> "Access denied";
                case 404 -> "Resource not found";
                case 405 -> "Method not allowed";
                case 415 -> "Unsupported media type";
                default -> statusCode >= 500
                        ? "An unexpected error occurred"
                        : "Request could not be processed";
            };
            return "{\"code\":\"" + code + "\",\"message\":\"" + message
                    + "\",\"timestamp\":\"" + Instant.now() + "\"}";
        }
    }
}