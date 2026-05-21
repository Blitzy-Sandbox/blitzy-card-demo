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
package com.aws.carddemo.dto.auth;

/**
 * Immutable authentication request — the Java replacement for the COBOL
 * {@code COSGN0AI} BMS-mapped input record carrying {@code USERIDI} (user ID) and
 * {@code PASSWDI} (plaintext password candidate) from {@code app/bms/COSGN00.bms}.
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code userId} — 8-character user identifier (COBOL {@code USERIDI OF COSGN0AI};
 *       {@code SEC-USR-ID PIC X(08)} on the persisted record)</li>
 *   <li>{@code password} — plaintext password candidate (COBOL {@code PASSWDI OF COSGN0AI};
 *       NEVER stored — the JPA {@code SecurityUser.getPassword()} holds the BCrypt
 *       hash, not the plaintext, and the service compares the candidate to the
 *       stored hash via {@code passwordEncoder.matches(plaintext, hash)})</li>
 * </ul>
 *
 * <h2>Security Note — toString()</h2>
 *
 * <p>The record's default generated {@link #toString()} would include the plaintext
 * password verbatim, which would leak credentials whenever an
 * {@code AuthenticationRequest} is logged for diagnostic purposes. The override
 * below replaces that behaviour with a redacted form to prevent accidental
 * credential disclosure (per AAP §0.10.5: "No plaintext credentials in any
 * configuration file" / "No financial data written to logs at any level" —
 * applied transitively to credentials).
 *
 * <h2>Package — {@code dto.auth}</h2>
 *
 * <p>Authentication DTOs (request, result, session) live in {@code com.aws.carddemo.dto.auth}
 * per the AAP §0.7.1 layered package convention. The {@code dto/**} subtree is
 * excluded from coverage rules because DTOs are data carriers exercised indirectly
 * by service-layer tests.
 *
 * @param userId   the 8-character user identifier (case-sensitive; the production
 *                 service may upper-case it before lookup, per COBOL
 *                 {@code MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID})
 * @param password the plaintext password candidate (never persisted, never logged)
 *
 * @see com.aws.carddemo.service.AuthenticationService
 */
public record AuthenticationRequest(String userId, String password) {

    /**
     * String representation deliberately REDACTS the {@link #password} field to
     * prevent accidental credential disclosure if an {@code AuthenticationRequest}
     * instance is ever logged for diagnostic purposes.
     *
     * <p>Per AAP §0.10.5 (Security Constraints), the plaintext password is a
     * transient input to the verification function and must never appear in any
     * log line, exception message, or persisted store. Overriding {@code toString()}
     * here provides defence-in-depth against accidental disclosure — without this
     * override, the record's auto-generated {@code toString()} would emit the
     * password verbatim in any log line that includes the request.
     */
    @Override
    public String toString() {
        return "AuthenticationRequest{userId='" + userId + "', password=***REDACTED***}";
    }
}
