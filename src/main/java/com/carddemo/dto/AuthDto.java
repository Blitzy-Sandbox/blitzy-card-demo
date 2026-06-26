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
package com.carddemo.dto;

import jakarta.validation.constraints.Size;

/**
 * Signon request and response payloads for the authentication endpoint
 * {@code POST /api/auth/signin}.
 *
 * <p>Field widths are derived from the BMS mapset {@code app/bms/COSGN00.bms}
 * and the symbolic map {@code app/cpy-bms/COSGN00.CPY @ 27d6c6f}. Only the two
 * unprotected (input) map fields, {@code USERID} and {@code PASSWD}, are modeled
 * as the request contract; protected output fields are screen chrome and are not
 * represented here.
 */
public final class AuthDto {

    private AuthDto() {
    }

    /**
     * Request body for {@code POST /api/auth/signin}.
     *
     * <p>Blank/empty {@code userId} and {@code password} are intentionally
     * <em>not</em> rejected here with a {@code @NotBlank} constraint. Doing so
     * would emit the generic Bean Validation default ("must not be blank") and
     * shadow the byte-exact COBOL on-screen literals ("Please enter User ID ...",
     * "Please enter Password ...") that {@code AuthService.signin} raises in the
     * legacy COSGN00C field-evaluation order. Presence validation is therefore
     * delegated to the service layer to preserve 100% behavioral parity
     * (AAP &sect;0.7.1.1); see DECISION_LOG D-056. The {@code @Size(max = 8)}
     * upper bound mirrors the fixed-width {@code X(8)} fields and remains here.
     *
     * @param userId   the user identifier supplied at signon ({@code USERID X(8)})
     * @param password the password supplied at signon ({@code PASSWD X(8)})
     */
    public record SigninRequest(
            @Size(max = 8) String userId,
            @Size(max = 8) String password
    ) {
    }

    /**
     * Response body returned on a successful signon.
     *
     * @param token    the issued JSON Web Token
     * @param userId   the authenticated user identifier ({@code X(8)})
     * @param userType the single-character user type code ({@code SEC-USR-TYPE X(1)})
     */
    public record SigninResponse(
            String token,
            @Size(max = 8) String userId,
            @Size(max = 1) String userType
    ) {
    }
}
