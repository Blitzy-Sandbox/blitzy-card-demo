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
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.ApiResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON fallback for servlet-container errors that occur before a Spring MVC
 * controller method can run.
 *
 * <p><b>QA CR-11:</b> malformed or rejected request targets (for example an
 * encoded path-traversal segment) can be rejected by Tomcat and forwarded to
 * {@code /error}. Without this controller, Spring Boot/Tomcat may emit an HTML
 * error page such as {@code <h1>HTTP Status 400 – Bad Request</h1>}, which
 * leaks server technology and breaks the AAP &sect;0.3.4 JSON-envelope
 * contract. This controller keeps those routing-level failures in the same
 * {@link ApiResponse} shape used by {@code GlobalExceptionHandler}.</p>
 *
 * <p><b>COBOL provenance:</b> No direct source analogue exists; CICS/BMS
 * terminal input errors were redisplayed on the map. The Java REST equivalent
 * is a concise JSON error envelope for every API-facing failure path.</p>
 */
@RestController
public class ApiErrorController implements ErrorController {

    /**
     * Handles servlet error dispatches and emits a JSON {@link ApiResponse}.
     *
     * @param request servlet request carrying {@link RequestDispatcher}
     *                error attributes
     * @return standardized JSON error response with the original HTTP status
     */
    @RequestMapping(path = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Object>> handleError(HttpServletRequest request) {
        int statusCode = resolveStatusCode(request);
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            statusCode = status.value();
        }

        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(resolveCode(statusCode), resolveMessage(statusCode)));
    }

    private int resolveStatusCode(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status instanceof Integer integerStatus) {
            return integerStatus.intValue();
        }
        if (status instanceof String stringStatus) {
            try {
                return Integer.parseInt(stringStatus);
            } catch (NumberFormatException ignored) {
                return HttpStatus.INTERNAL_SERVER_ERROR.value();
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private String resolveCode(int statusCode) {
        return switch (statusCode) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> statusCode >= 500 ? "INTERNAL_ERROR" : "HTTP_ERROR";
        };
    }

    private String resolveMessage(int statusCode) {
        return switch (statusCode) {
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
    }
}